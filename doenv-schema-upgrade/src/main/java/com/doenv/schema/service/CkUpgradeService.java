package com.doenv.schema.service;

import com.doenv.schema.baseline.BaselineBundle;
import com.doenv.schema.baseline.CkBaselineParser;
import com.doenv.schema.config.SchemaUpgradeProperties;
import com.doenv.schema.diff.CkSchemaDiffEngine;
import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffLevel;
import com.doenv.schema.diff.DiffOptions;
import com.doenv.schema.diff.FinalCheckDiffEngine;
import com.doenv.schema.live.CkLiveSchemaReader;
import com.doenv.schema.live.CkTopology;
import com.doenv.schema.model.SchemaModel;
import com.doenv.schema.render.CkDdlRenderer;
import com.doenv.schema.rename.RenameManifest;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * ClickHouse 升级编排。
 *
 * <p>这段逻辑原先写在集成测试里 —— 环境探测、集群名解析、行数探测、循环收敛，
 * 全是生产行为，放在测试里意味着真正运行时没人执行它们。搬到这里之后，
 * CLI 与测试共用同一份编排，不会出现「测试通过但线上跑的是另一套」。
 */
public class CkUpgradeService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CkUpgradeService.class);

    private final SchemaUpgradeProperties.Ck props;
    private final HikariDataSource ds;
    private final JdbcTemplate jdbc;
    private final CkLiveSchemaReader reader;
    private final RenameManifest renameManifest;
    private final List<String> renameManifestErrors;

    private BaselineBundle baseline;
    private String baselineName = "-";

    public CkUpgradeService(SchemaUpgradeProperties.Ck props) {
        this.props = props;
        this.ds = new HikariDataSource();
        ds.setJdbcUrl(props.getUrl());
        ds.setUsername(props.getUsername());
        ds.setPassword(props.getPassword());
        ds.setDriverClassName(props.getDriverClassName());
        ds.setMaximumPoolSize(4);
        ds.setPoolName("doenv-ck");
        this.jdbc = new JdbcTemplate(ds);
        this.reader = new CkLiveSchemaReader(jdbc);
        RenameManifest loaded;
        List<String> errors = new ArrayList<>();
        try {
            loaded = RenameManifest.loadDefault();
            errors.addAll(loaded.validate());
        } catch (Exception e) {
            loaded = new RenameManifest();
            errors.add("字段改名清单读取失败：" + e.getMessage());
        }
        this.renameManifest = loaded;
        this.renameManifestErrors = List.copyOf(errors);
    }

    @Override
    public void close() {
        ds.close();
    }

    public DataSource dataSource() {
        return ds;
    }

    // ────────────────────────── 环境 ──────────────────────────

    /** 环境探测结果。blockers 非空时禁止执行，只出脚本 */
    public record CkEnvironment(String database, String version, CkTopology topology,
                                Map<String, String> macros, List<String> clusters,
                                int tableCount, int vaxTableCount,
                                String clusterRef, boolean zkMacrosOk,
                                List<String> blockers) {
    }

    public CkEnvironment probe() {
        String db = reader.currentDatabase();
        String ver = reader.version();
        CkTopology topo = reader.detectTopology();
        Map<String, String> macros = reader.macros();
        List<String> clusters = reader.clusterCandidates();
        int tables = reader.countAllTables();
        int vax = reader.countVaxTables();

        String clusterRef = macros.containsKey("cluster")
                ? "'{cluster}'"
                : (clusters.size() == 1 ? "'" + clusters.get(0) + "'" : null);
        boolean zkOk = macros.containsKey("database") && macros.containsKey("shard")
                && macros.containsKey("replica");

        List<String> blockers = new ArrayList<>();
        // 连错库硬闸：现网存在多种库名，连接串不带库名时 currentDatabase() 会回退到 default，
        // 那时会把 503 张表全判成缺失，在错误的库里建出一整套空表
        if (vax == 0 || tables < 100) {
            blockers.add("疑似连错库：当前库 " + db + " 只有 " + tables
                    + " 张表、vax_ 表 " + vax + " 张，禁止一切建表操作");
        }
        if (topo == CkTopology.UNKNOWN) {
            blockers.add("拓扑判定为 UNKNOWN（Distributed 表与 Replicated 表只占其一），禁止执行");
        }
        if (topo == CkTopology.DISTRIBUTED && clusterRef == null) {
            blockers.add("分布式现场但无法确定集群名（无 cluster 宏且候选集群不唯一）");
        }
        return new CkEnvironment(db, ver, topo, macros, clusters, tables, vax,
                clusterRef, zkOk, blockers);
    }

    // ────────────────────────── 基准 ──────────────────────────

    public synchronized BaselineBundle loadBaseline() {
        if (baseline != null) {
            return baseline;
        }
        try {
            Located loc = locateBaseline();
            baselineName = loc.name();
            baseline = new CkBaselineParser().parse(loc.sql(), versionOf(loc.name()), loc.name());
            log.info("已加载 CK 基准 {}：{} 张表 / {} 列",
                    baselineName, baseline.getSchema().getTableCount(),
                    baseline.getSchema().getColumnCount());
            return baseline;
        } catch (Exception e) {
            throw new IllegalStateException("加载 CK 基准失败：" + e.getMessage(), e);
        }
    }

    public String baselineFileName() {
        return baselineName;
    }

    /** 定位到的基准：文件名 + 内容。内容可能来自 jar 内，所以不暴露 Path */
    private record Located(String name, String sql) {
    }

    private Located locateBaseline() throws Exception {
        String file = props.getBaselineFile();
        if (file != null && !file.isBlank()) {
            if (file.startsWith("classpath:")) {
                org.springframework.core.io.Resource r =
                        new org.springframework.core.io.DefaultResourceLoader().getResource(file);
                if (!r.exists()) {
                    throw new IllegalStateException("CK 基准文件不存在：" + file);
                }
                return new Located(r.getFilename(), read(r));
            }
            Path p = Path.of(file.replaceFirst("^file:", ""));
            if (!Files.exists(p)) {
                throw new IllegalStateException("CK 基准文件不存在：" + p.toAbsolutePath());
            }
            return new Located(p.getFileName().toString(), Files.readString(p));
        }

        String dir = props.getBaselineDir();
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("未配置 doenv.schema-upgrade.ck.baseline-dir");
        }
        // 默认 classpath 时先看 jar 同级的外部基准，换基准不必重新打包
        if (dir.startsWith("classpath:")) {
            java.nio.file.Path ext = BaselineLocator.externalDir("clickhouse-sql");
            if (ext != null) {
                try (Stream<Path> st = Files.list(ext)) {
                    Optional<Path> newest = st
                            .filter(p -> p.getFileName().toString().startsWith(props.getBaselineNamePrefix()))
                            .filter(p -> p.getFileName().toString().endsWith(".sql"))
                            .max(Comparator.comparing(p -> p.getFileName().toString()));
                    if (newest.isPresent()) {
                        log.info("使用 jar 外部基准目录 {}", ext.toAbsolutePath());
                        return new Located(newest.get().getFileName().toString(),
                                Files.readString(newest.get()));
                    }
                }
            }
        }
        // jar 内的基准：现场只丢一个 jar，不能再要求运维配路径
        if (dir.startsWith("classpath:")) {
            org.springframework.core.io.Resource r =
                    ClasspathBaselines.newest(dir, props.getBaselineNamePrefix());
            if (r == null) {
                throw new IllegalStateException("jar 内 " + dir + " 下没有 "
                        + props.getBaselineNamePrefix() + "*.sql");
            }
            return new Located(r.getFilename(), read(r));
        }
        Path d = Path.of(dir.replaceFirst("^file:", ""));
        if (!Files.isDirectory(d)) {
            throw new IllegalStateException("CK 基准目录不存在：" + d.toAbsolutePath());
        }
        try (Stream<Path> s = Files.list(d)) {
            Optional<Path> newest = s
                    .filter(p -> p.getFileName().toString().startsWith(props.getBaselineNamePrefix()))
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
            Path p = newest.orElseThrow(() -> new IllegalStateException(
                    "目录里没有 " + props.getBaselineNamePrefix() + "*.sql：" + d.toAbsolutePath()));
            return new Located(p.getFileName().toString(), Files.readString(p));
        }
    }

    private static String read(org.springframework.core.io.Resource r) throws Exception {
        try (java.io.InputStream in = r.getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String versionOf(String name) {
        int dash = name.lastIndexOf('-');
        return dash > 0 ? name.substring(dash + 1).replace(".sql", "") : name;
    }

    // ────────────────────────── 比对 ──────────────────────────

    /**
     * 行数探测器。空表可重建、有数据必须跳过，判据就是它。
     * 取不到时返回 -1，差异引擎会按「有数据」保守处理。
     */
    private CkSchemaDiffEngine.TableRowCounter rowCounter(CkTopology topo) {
        return table -> {
            String physical = topo == CkTopology.DISTRIBUTED ? table + "_local" : table;
            try {
                Long n = jdbc.queryForObject(
                        "SELECT sum(rows) FROM system.parts WHERE database = currentDatabase() "
                                + "AND table = ? AND active", Long.class, physical);
                return n == null ? 0L : n;
            } catch (RuntimeException e) {
                return -1L;
            }
        };
    }

    public List<DiffItem> compare(CkEnvironment env, DiffOptions opt) {
        BaselineBundle b = loadBaseline();
        SchemaModel live = reader.read(env.topology());
        CkDdlRenderer renderer = new CkDdlRenderer(
                env.topology(), env.clusterRef(), env.zkMacrosOk());
        List<DiffItem> diffs = new java.util.ArrayList<>(
                new CkSchemaDiffEngine(renderer, rowCounter(env.topology()), renameManifest.getCk())
                        .diff(b.getSchema(), live, opt));
        // 配置数据：既有 7 张表只补不改；后续新表按 id 补缺并更新差异列
        diffs.addAll(new com.doenv.schema.seed.CkSeedDiffEngine(
                jdbc, env.topology() == CkTopology.DISTRIBUTED, env.clusterRef())
                .diff(b.getSeeds(), live));
        // 固定收尾检查：只对明确授权的 vax_item_label 引擎差异做迁移。
        // 追加在末尾，确保普通加列/改列已先完成，复制时两侧字段清单一致。
        diffs.addAll(FinalCheckDiffEngine.itemLabelEngine(
                b.getSchema().findTable(FinalCheckDiffEngine.ITEM_LABEL_TABLE),
                live.findTable(FinalCheckDiffEngine.ITEM_LABEL_TABLE), renderer,
                env.topology(), rowCounter(env.topology()).count(FinalCheckDiffEngine.ITEM_LABEL_TABLE)));
        return diffs;
    }

    // ────────────────────────── 执行 ──────────────────────────

    /** 一次执行的产物：每轮结果 + 最终剩余差异 + 实际执行过的差异项 */
    public record CkRunResult(CkEnvironment env, List<ExecutionResult> rounds,
                              List<DiffItem> remaining, List<DiffItem> executedItems,
                              int tablesBefore, int tablesAfter,
                              List<String> generatorErrors) {
    }

    /**
     * 比对 → 执行 → 循环到收敛。
     *
     * @param dryRun 干跑。CK 没有事务可回滚，干跑只用 EXPLAIN SYNTAX 验语法，因此<b>不循环</b>
     */
    public CkRunResult executeUntilConverged(List<DiffLevel> levels, boolean dryRun, int maxRounds) {
        CkEnvironment env = probe();
        int before = env.tableCount();

        List<ExecutionResult> rounds = new ArrayList<>();
        List<DiffItem> executed = new ArrayList<>();
        List<String> genErrors = new ArrayList<>();

        int limit = dryRun ? 1 : Math.max(1, maxRounds);
        DiffOptions opt = new DiffOptions();

        for (int i = 1; i <= limit; i++) {
            if (!renameManifestErrors.isEmpty()) {
                genErrors.addAll(renameManifestErrors);
                log.error("CK 字段改名清单无效，中止执行：{}", renameManifestErrors);
                break;
            }
            List<DiffItem> diffs = compare(env, opt);

            List<DiffItem> exec = diffs.stream()
                    .filter(d -> levels.contains(d.getLevel()))
                    .filter(d -> !d.getDdl().isEmpty())
                    .toList();

            // 生成物自检：DROP TABLE 只对「已确认空表重建」放行
            List<String> errors = CkDdlRenderer.validateItems(exec);
            if (!errors.isEmpty()) {
                genErrors.addAll(errors);
                log.error("CK 生成物自检未通过，中止执行：{}", errors);
                break;
            }

            if (!env.blockers().isEmpty()) {
                log.error("CK 环境有阻断项，只出脚本不执行：{}", env.blockers());
                break;
            }

            ExecutionResult r = new CkUpgradeExecutor(ds, jdbc, env.topology())
                    .execute(diffs, levels, dryRun, env.database());
            r.setRound(i);
            r.setBaselineFile(baselineFileName());
            rounds.add(r);
            executed.addAll(exec);

            if (r.getTotal() == 0) {
                log.info("CK 第 {} 轮无可执行项，已收敛", i);
                break;
            }
            if (r.getOk() == 0) {
                log.warn("CK 第 {} 轮 {} 条全部未成功，停止循环", i, r.getTotal());
                break;
            }
        }

        // 收敛后重新比对，得到最终剩余差异（跳过项就从这里来）
        CkEnvironment after = probe();
        List<DiffItem> remaining = compare(after, opt);

        return new CkRunResult(env, rounds, remaining, executed,
                before, after.tableCount(), genErrors);
    }
}
