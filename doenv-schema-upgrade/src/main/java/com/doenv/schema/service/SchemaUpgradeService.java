package com.doenv.schema.service;

import com.doenv.schema.baseline.BaselineBundle;
import com.doenv.schema.baseline.PgBaselineParser;
import com.doenv.schema.config.SchemaUpgradeProperties;
import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffOptions;
import com.doenv.schema.diff.FinalCheckDiffEngine;
import com.doenv.schema.diff.SchemaDiffEngine;
import com.doenv.schema.live.PgLiveSchemaReader;
import com.doenv.schema.model.SchemaModel;
import com.doenv.schema.model.TableModel;
import com.doenv.schema.render.GeneratedSqlValidator;
import com.doenv.schema.render.PgDdlRenderer;
import com.doenv.schema.rename.RenameManifest;
import com.doenv.schema.render.SeedSqlRenderer;
import com.doenv.schema.seed.SeedDiffEngine;
import com.doenv.schema.seed.SeedManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 比对编排。
 *
 * <p>一期定位是<b>只读诊断器</b>：比对 + 报告 + 导出 .sql，<b>不执行任何 DDL</b>。
 * 这一期已经消灭了核心痛点（不用从旧版本一条条翻增量文件），风险为零，
 * 而执行通道是在已验证正确的差异结果之上叠加，不会返工。
 */
public class SchemaUpgradeService {

    private static final Logger log = LoggerFactory.getLogger(SchemaUpgradeService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final SchemaUpgradeProperties props;
    private final ResourceLoader resourceLoader;

    /** 报告缓存，按插入顺序淘汰 */
    private final Map<String, UpgradeReport> reports = new LinkedHashMap<>();

    private volatile BaselineBundle cachedBaseline;
    private volatile String cachedBaselineKey;

    public SchemaUpgradeService(JdbcTemplate jdbc, SchemaUpgradeProperties props,
                                ResourceLoader resourceLoader) {
        this.jdbc = jdbc;
        this.props = props;
        this.resourceLoader = resourceLoader == null ? new DefaultResourceLoader() : resourceLoader;
    }

    // ────────────────────────── 环境 ──────────────────────────

    public UpgradeReport probeEnvironment() {
        UpgradeReport r = new UpgradeReport();
        PgLiveSchemaReader reader = new PgLiveSchemaReader(jdbc);

        r.setScannedAt(LocalDateTime.now().format(TS));
        r.setDatabase(reader.currentDatabase());
        String schema = resolveSchema(reader);
        r.setSchema(schema);
        r.setServerVersion(reader.serverVersion());
        r.setLiveVaoTables(reader.countVaoTables(schema));
        r.setCanCreateTable(reader.canCreateTable(schema));
        r.setInvalidIndexes(reader.findInvalidIndexes(schema));

        applyBlockers(r);
        return r;
    }

    /**
     * 连错库 / 权限不足这类硬闸。
     *
     * <p>现网存在多个库名，连接串还可能不带库名 —— 那样 {@code current_schema()}
     * 落到一个空库上，diff 会判「基准全部表缺失」并生成一整套 CREATE TABLE，
     * 在错误的库里建出一套空表。必须在这里拦住。
     */
    private void applyBlockers(UpgradeReport r) {
        if (r.getLiveVaoTables() == 0) {
            r.getBlockers().add("目标库里 vao_ 开头的表数为 0，疑似连错库或空库。已禁用一切执行入口");
        } else if (r.getLiveVaoTables() < 50) {
            r.getBlockers().add("目标库里 vao_ 表只有 " + r.getLiveVaoTables()
                    + " 张，明显少于预期，请先确认库名是否正确");
        }
        if (!r.isCanCreateTable()) {
            r.getBlockers().add("当前账号对 schema " + r.getSchema() + " 没有 CREATE 权限");
        }
        if (!r.getInvalidIndexes().isEmpty()) {
            r.getBlockers().add("存在 INVALID 索引（CONCURRENTLY 失败残留），建索引前需先清理："
                    + r.getInvalidIndexes());
        }
    }

    private String resolveSchema(PgLiveSchemaReader reader) {
        if (props.getSchema() != null && !props.getSchema().isBlank()) {
            return props.getSchema();
        }
        return reader.currentSchema();
    }

    // ────────────────────────── 比对 ──────────────────────────

    public UpgradeReport compare(DiffOptions options) {
        UpgradeReport r = probeEnvironment();
        r.setReportId(UUID.randomUUID().toString().replace("-", "").substring(0, 12));

        BaselineBundle baseline = loadBaseline();
        r.setBaselineFile(baseline.getSourceFile());
        r.setBaselineId(baseline.getBaselineId());
        r.setBaselineTables(baseline.getSchema().getTableCount());
        r.setBaselineColumns(baseline.getSchema().getColumnCount());
        r.setBaselineSeedTables(baseline.getSeeds().size());
        r.setBaselineSeedRows(baseline.getSeedRowCount());
        r.setBaselineDiscarded(new LinkedHashMap<>(baseline.getDiscarded()));

        PgLiveSchemaReader reader = new PgLiveSchemaReader(jdbc);
        SchemaModel live = reader.read(r.getSchema());
        r.setLiveTables(live.getTableCount());
        r.setLiveColumns(live.getColumnCount());

        RenameManifest renameManifest = loadRenameManifest(r);
        PgDdlRenderer ddl = new PgDdlRenderer(r.getSchema());
        DiffOptions opt = options == null ? new DiffOptions() : options;
        r.setStructureDiffs(new SchemaDiffEngine(ddl, renameManifest.getPg())
                .diff(baseline.getSchema(), live, opt));

        try {
            SeedManifest manifest = loadManifest();
            SeedSqlRenderer seedRenderer = new SeedSqlRenderer(r.getSchema());
            r.setSeedDiffs(new SeedDiffEngine(jdbc, seedRenderer, manifest)
                    .diff(baseline.getSeeds(), live));
        } catch (Exception e) {
            log.warn("配置数据比对失败", e);
            r.getGeneratorErrors().add("配置数据比对失败：" + e.getMessage());
        }

        // 收尾检查必须排在普通结构/配置数据之后：本轮若刚补上 vao_user 或 systemauths，
        // 下一轮会自然进入这里，避免在字段不存在时直接 UPDATE 报错。
        TableModel liveUser = live.findTable(FinalCheckDiffEngine.SYSTEMAUTHS_TABLE);
        Long nullRows = countSystemauthsNulls(r.getSchema(), liveUser);
        r.getSeedDiffs().add(FinalCheckDiffEngine.systemauths(r.getSchema(), liveUser, nullRows));

        List<DiffItem> all = r.allDiffs();
        r.getGeneratorErrors().addAll(GeneratedSqlValidator.validate(all, r.getSchema()));

        cacheReport(r);
        log.info("比对完成 reportId={} 结构差异={} 配置差异={} 阻断={} 自检错误={}",
                r.getReportId(), r.getStructureDiffs().size(), r.getSeedDiffs().size(),
                r.getBlockers().size(), r.getGeneratorErrors().size());
        return r;
    }

    private Long countSystemauthsNulls(String schema, TableModel liveUser) {
        if (liveUser == null || liveUser.findColumn("systemauths") == null) {
            return 0L;
        }
        try {
            Long n = jdbc.queryForObject("SELECT count(*) FROM \"" + schema.replace("\"", "\"\"")
                    + "\".\"vao_user\" WHERE \"systemauths\" IS NULL", Long.class);
            return n == null ? 0L : n;
        } catch (RuntimeException e) {
            log.warn("收尾检查 systemauths 空值探测失败，按安全原则不生成 UPDATE：{}", e.getMessage());
            return null;
        }
    }

    private synchronized void cacheReport(UpgradeReport r) {
        reports.put(r.getReportId(), r);
        while (reports.size() > Math.max(1, props.getReportCacheSize())) {
            String oldest = reports.keySet().iterator().next();
            reports.remove(oldest);
        }
    }

    public synchronized UpgradeReport getReport(String reportId) {
        return reports.get(reportId);
    }

    // ────────────────────────── 执行 ──────────────────────────

    /**
     * 执行升级。
     *
     * @param reportId 必须是本进程刚比对出来的报告 —— 不接受外部传入 SQL，
     *                 语句一律由后端从基准重新生成，杜绝篡改与前后端不一致
     * @param levels   要执行的档位。MANUAL / INFO 会被强制剔除，永不执行
     * @param dryRun   干跑：每条执行后立刻回滚
     */
    /**
     * 一路执行到收敛。
     *
     * <p>为什么需要多轮：比对是执行前<b>一次性</b>算完的。本轮才新建的表，在比对时
     * 还不存在，它们的配置数据会被判成「现场缺表，待建表后再下发」；同理新建表上的
     * 索引、注释也要等表落地之后才比得出来。所以执行完必须重新比对，直到没有可执行项。
     *
     * <p><b>干跑不循环</b> —— 干跑每条执行完立刻回滚，库根本没变，
     * 第二轮会算出一模一样的差异，永远停不下来。
     *
     * @param maxRounds 轮次上限，兜底防止某个差异反复生成却始终消不掉时死循环
     * @return 每一轮的执行结果，按顺序
     */
    public List<ExecutionResult> executeUntilConverged(DiffOptions options,
                                                       List<com.doenv.schema.diff.DiffLevel> levels,
                                                       boolean dryRun, int maxRounds) {
        List<ExecutionResult> rounds = new ArrayList<>();
        int limit = dryRun ? 1 : Math.max(1, maxRounds);

        for (int i = 1; i <= limit; i++) {
            UpgradeReport report = compare(options);
            ExecutionResult r = execute(report.getReportId(), levels, dryRun);
            r.setRound(i);
            rounds.add(r);

            if (r.isAborted()) {
                log.warn("第 {} 轮被中止，停止循环", i);
                break;
            }
            if (r.getTotal() == 0) {
                log.info("第 {} 轮无可执行项，已收敛", i);
                break;
            }
            if (r.getOk() == 0) {
                // 有语句但一条都没成功：再跑一轮也是同样结果，及时收手
                log.warn("第 {} 轮 {} 条语句全部未成功，停止循环避免空转", i, r.getTotal());
                break;
            }
            if (i == limit) {
                log.warn("已达轮次上限 {}，仍有可执行项未收敛", limit);
            }
        }
        return rounds;
    }

    public ExecutionResult execute(String reportId, List<com.doenv.schema.diff.DiffLevel> levels,
                                   boolean dryRun) {
        UpgradeReport report = getReport(reportId);
        if (report == null) {
            throw new IllegalArgumentException("报告不存在或已过期，请重新比对：" + reportId);
        }
        List<com.doenv.schema.diff.DiffLevel> safe = levels.stream()
                .filter(l -> l == com.doenv.schema.diff.DiffLevel.AUTO
                        || l == com.doenv.schema.diff.DiffLevel.CONFIRM)
                .toList();
        if (safe.isEmpty()) {
            throw new IllegalArgumentException("没有可执行的档位（只允许 AUTO / CONFIRM）");
        }
        if (safe.size() != levels.size()) {
            log.warn("请求的档位里包含 MANUAL/INFO，已剔除。实际执行：{}", safe);
        }
        UpgradeExecutor executor = new UpgradeExecutor(
                jdbc.getDataSource(), jdbc, report.getSchema());
        return executor.execute(report, safe, dryRun);
    }

    // ────────────────────────── 基准加载 ──────────────────────────

    public synchronized BaselineBundle loadBaseline() {
        String key = props.getBaselineFile() != null ? props.getBaselineFile() : props.getBaselineDir();
        if (cachedBaseline != null && key.equals(cachedBaselineKey)) {
            return cachedBaseline;
        }
        try {
            LocatedBaseline located = locateBaseline();
            String sql = located.read();
            BaselineBundle b = new PgBaselineParser().parse(sql, located.version(), located.name());
            cachedBaseline = b;
            cachedBaselineKey = key;
            log.info("已加载基准 {}：{} 张表 / {} 列 / 种子 {} 表 {} 行",
                    located.name(), b.getSchema().getTableCount(), b.getSchema().getColumnCount(),
                    b.getSeeds().size(), b.getSeedRowCount());
            return b;
        } catch (IOException e) {
            throw new IllegalStateException("加载基准文件失败：" + e.getMessage(), e);
        }
    }

    private record LocatedBaseline(String name, Resource resource, Path path) {
        String version() {
            return name.replaceAll("^.*?纯净库-", "").replaceAll("\\.sql$", "");
        }

        String read() throws IOException {
            if (path != null) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
            try (InputStream in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    /** 目录里文件名最大（即日期最新）的一份基准；没有返回 null */
    private static Path newestIn(Path dir, String namePrefix) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(namePrefix))
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        }
    }

    private LocatedBaseline locateBaseline() throws IOException {
        if (props.getBaselineFile() != null && !props.getBaselineFile().isBlank()) {
            String loc = props.getBaselineFile();
            if (loc.startsWith("classpath:")) {
                Resource res = resourceLoader.getResource(loc);
                if (!res.exists()) {
                    throw new IOException("基准文件不存在：" + loc);
                }
                return new LocatedBaseline(res.getFilename(), res, null);
            }
            Path p = Path.of(loc.replaceFirst("^file:", ""));
            if (!Files.exists(p)) {
                throw new IOException("基准文件不存在：" + p.toAbsolutePath());
            }
            return new LocatedBaseline(p.getFileName().toString(), null, p);
        }

        // 自动发现：目录里文件名最大的一份（文件名带日期，最大即最新）
        String dir = props.getBaselineDir();
        // 还是默认的 classpath 时，先看 jar 同级有没有外部基准 ——
        // 换基准不该要求重新打包 jar
        if (dir.startsWith("classpath:")) {
            Path ext = BaselineLocator.externalDir("pg-sql");
            if (ext != null) {
                Path p = newestIn(ext, props.getBaselineNamePrefix());
                if (p != null) {
                    log.info("使用 jar 外部基准目录 {}", ext.toAbsolutePath());
                    return new LocatedBaseline(p.getFileName().toString(), null, p);
                }
            }
        }
        if (dir.startsWith("classpath:")) {
            // jar 内的基准。现场只丢一个 jar + 一份 application.properties，
            // 基准必须能从 jar 里自己找出来，不能再要求运维配路径
            Resource res = ClasspathBaselines.newest(dir, props.getBaselineNamePrefix());
            if (res == null) {
                throw new IOException("jar 内 " + dir + " 下没有 "
                        + props.getBaselineNamePrefix() + "*.sql");
            }
            return new LocatedBaseline(res.getFilename(), res, null);
        }
        Path d = Path.of(dir.replaceFirst("^file:", ""));
        if (!Files.isDirectory(d)) {
            throw new IOException("基准目录不存在：" + d.toAbsolutePath());
        }
        try (Stream<Path> s = Files.list(d)) {
            Optional<Path> newest = s
                    .filter(p -> p.getFileName().toString().startsWith(props.getBaselineNamePrefix()))
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
            Path p = newest.orElseThrow(() ->
                    new IOException("目录里没有 " + props.getBaselineNamePrefix() + "*.sql：" + d));
            return new LocatedBaseline(p.getFileName().toString(), null, p);
        }
    }

    private SeedManifest loadManifest() throws IOException {
        Resource res = resourceLoader.getResource(props.getSeedManifest());
        if (!res.exists()) {
            log.warn("未找到配置数据清单 {}，配置数据比对将全部按 STRUCTURE_ONLY 处理",
                    props.getSeedManifest());
            SeedManifest empty = new SeedManifest();
            empty.setTables(new ArrayList<>());
            return empty;
        }
        return SeedManifest.load(res);
    }

    private RenameManifest loadRenameManifest(UpgradeReport report) {
        try {
            RenameManifest manifest = RenameManifest.loadDefault();
            report.getGeneratorErrors().addAll(manifest.validate());
            return manifest;
        } catch (IOException e) {
            report.getGeneratorErrors().add("字段改名清单读取失败：" + e.getMessage());
            return new RenameManifest();
        }
    }
}
