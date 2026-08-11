package com.doenv.schema.standalone;

import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffLevel;
import com.doenv.schema.diff.DiffOptions;
import com.doenv.schema.service.ExecutionResult;
import com.doenv.schema.service.SchemaUpgradeService;
import com.doenv.schema.service.UpgradeExecutor;
import com.doenv.schema.service.UpgradeReport;
import com.doenv.schema.service.UpgradeResultDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * 命令行模式。跑完把完整报告落盘，然后退出进程，方便在服务器上一条命令做完升级并留档。
 *
 * <pre>
 * java -jar doenv-schema-upgrade-1.0.0-exec.jar \
 *   --doenv.schema-upgrade.cli.mode=dry-run \
 *   --doenv.schema-upgrade.cli.out=/tmp/doenv-report \
 *   --doenv.schema-upgrade.baseline-dir=... \
 *   --doenv.schema-upgrade.target.url=... --doenv.schema-upgrade.target.username=... \
 *   --doenv.schema-upgrade.target.password=...
 * </pre>
 *
 * mode 取值：{@code compare}（只比对） / {@code dry-run}（执行后回滚） / {@code execute}（真执行）
 */
@Component
@ConditionalOnProperty(prefix = "doenv.schema-upgrade.cli", name = "mode")
public class DoEnvCliRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DoEnvCliRunner.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 延迟解析：db=ck 时不该因为没配 PG 连接而启动失败 */
    private final org.springframework.beans.factory.ObjectProvider<SchemaUpgradeService> serviceProvider;
    private final ConfigurableApplicationContext ctx;

    @Value("${doenv.schema-upgrade.cli.mode}")
    private String mode;

    @Value("${doenv.schema-upgrade.cli.out:./doenv-report}")
    private String outDir;

    @Value("${doenv.schema-upgrade.cli.levels:AUTO}")
    private String levels;

    @Value("${doenv.schema-upgrade.cli.checkComment:true}")
    private boolean checkComment;

    /** 要升级哪个库：pg / ck / both */
    @Value("${doenv.schema-upgrade.cli.db:pg}")
    private String dbKind;

    /** 自动收敛的轮次上限。兜底防止某个差异反复生成却始终消不掉时死循环 */
    @Value("${doenv.schema-upgrade.cli.maxRounds:8}")
    private int maxRounds;

    /** 跑完是否退出进程。默认 true —— 命令行模式就是一次性任务 */
    @Value("${doenv.schema-upgrade.cli.exit:true}")
    private boolean exitAfterRun;

    private final com.doenv.schema.config.SchemaUpgradeProperties props;

    public DoEnvCliRunner(
            org.springframework.beans.factory.ObjectProvider<SchemaUpgradeService> serviceProvider,
            ConfigurableApplicationContext ctx,
            com.doenv.schema.config.SchemaUpgradeProperties props) {
        this.serviceProvider = serviceProvider;
        this.ctx = ctx;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        int exitCode = 0;
        try {
            exitCode = doRun();
        } catch (Exception e) {
            log.error("命令行执行失败", e);
            exitCode = 2;
        }
        if (exitAfterRun) {
            System.exit(SpringApplicationExit.close(ctx, exitCode));
        }
    }

    private int doRun() throws Exception {
        String stamp = LocalDateTime.now().format(STAMP);
        Path dir = Path.of(outDir);
        Files.createDirectories(dir);

        boolean wantPg = "pg".equalsIgnoreCase(dbKind) || "both".equalsIgnoreCase(dbKind);
        boolean wantCk = "ck".equalsIgnoreCase(dbKind) || "both".equalsIgnoreCase(dbKind);

        log.info("###### DoEnv CLI 启动  db={} mode={} levels={} 输出={} ######",
                dbKind, mode, levels, dir.toAbsolutePath());

        boolean dryRun = "dry-run".equalsIgnoreCase(mode);
        boolean compareOnly = "compare".equalsIgnoreCase(mode);
        List<DiffLevel> want = Arrays.stream(levels.split(","))
                .map(String::trim).filter(x -> !x.isEmpty())
                .map(DiffLevel::valueOf).toList();

        DiffOptions opt = new DiffOptions();
        opt.setCheckComment(checkComment);

        List<UpgradeResultDocument.Section> sections = new java.util.ArrayList<>();
        int exit = 0;

        // ────────── PG ──────────
        if (wantPg) {
            SchemaUpgradeService service = serviceProvider.getObject();
            UpgradeReport report = service.compare(opt);
            writeCompareReport(dir.resolve("pg-compare-" + stamp + ".txt"), report);
            writeSql(dir.resolve("pg-upgrade-" + stamp + ".sql"), report);

            List<ExecutionResult> rounds = compareOnly
                    ? List.of()
                    : service.executeUntilConverged(opt, want, dryRun, maxRounds);

            UpgradeReport after = service.compare(opt);
            sections.add(new UpgradeResultDocument.Section(
                    "PG", report.getDatabase(),
                    "schema=" + report.getSchema() + "  " + report.getServerVersion(),
                    report.getBaselineFile(),
                    report.getLiveTables(), after.getLiveTables(),
                    rounds, after.allDiffs(),
                    after.getBlockers(), after.getGeneratorErrors(),
                    // 取执行「前」的差异：整表重建做完就查不到了，
                    // 而重建恰恰是最需要在报告里逐张列清楚的动作。
                    // 只比对模式同样要给 —— 那时它就是「将要重建」的预览清单
                    report.allDiffs().stream()
                            .filter(x -> x.getType() == com.doenv.schema.diff.DiffType.SEED_REBUILD)
                            .filter(x -> want.contains(x.getLevel()))
                            .toList()));

            if (rounds.stream().anyMatch(r -> r.getFailed() > 0)) {
                exit = 1;
            }
        }

        // ────────── CK ──────────
        if (wantCk) {
            if (!props.getCk().isConfigured()) {
                log.error("要升级 CK 但未配置 doenv.schema-upgrade.ck.url");
                exit = 2;
            } else {
                try (com.doenv.schema.service.CkUpgradeService ck =
                             new com.doenv.schema.service.CkUpgradeService(props.getCk())) {
                    com.doenv.schema.service.CkUpgradeService.CkRunResult res =
                            compareOnly
                                    ? compareOnlyCk(ck, opt)
                                    : ck.executeUntilConverged(want, dryRun, maxRounds);

                    sections.add(new UpgradeResultDocument.Section(
                            "CK", res.env().database(),
                            "拓扑=" + res.env().topology() + "  " + res.env().version()
                                    + "  vax_表=" + res.env().vaxTableCount(),
                            ck.baselineFileName(),
                            res.tablesBefore(), res.tablesAfter(),
                            res.rounds(), res.remaining(),
                            res.env().blockers(), res.generatorErrors(),
                            res.executedItems()));

                    if (res.rounds().stream().anyMatch(r -> r.getFailed() > 0)
                            || !res.generatorErrors().isEmpty()
                            || !res.env().blockers().isEmpty()) {
                        exit = 1;
                    }
                }
            }
        }

        // ────────── 结果文档：无论成败都出 ──────────
        // 正文与 SQL 原文拆开：正文只留摘要表格，几十 KB 随时可开；
        // 原文单独落 .sql 附录，排查时按序号检索
        String appendixName = "升级SQL原文-" + stamp + ".sql";
        String doc = UpgradeResultDocument.render(sections, appendixName);
        Path docPath = dir.resolve("升级结果-" + stamp + ".txt");
        Files.writeString(docPath, doc, StandardCharsets.UTF_8);

        Path sqlPath = dir.resolve(appendixName);
        Files.writeString(sqlPath, UpgradeResultDocument.renderSqlAppendix(sections),
                StandardCharsets.UTF_8);

        System.out.println();
        System.out.println(doc);
        log.info("###### 升级结果文档 {} ######", docPath.toAbsolutePath());
        log.info("###### SQL 原文附录 {} ######", sqlPath.toAbsolutePath());
        return exit;
    }

    /** 只比对模式下也要拿到 CK 的差异，用于结果文档的第二部分 */
    private com.doenv.schema.service.CkUpgradeService.CkRunResult compareOnlyCk(
            com.doenv.schema.service.CkUpgradeService ck, DiffOptions opt) {
        var env = ck.probe();
        var diffs = ck.compare(env, opt);
        // 「将要重建哪些表」恰恰是执行前最该看到的东西，只比对模式必须也给出来
        List<com.doenv.schema.diff.DiffItem> willRebuild = diffs.stream()
                .filter(d -> d.getType() == com.doenv.schema.diff.DiffType.CK_REBUILD_EMPTY)
                .toList();
        return new com.doenv.schema.service.CkUpgradeService.CkRunResult(
                env, List.of(), diffs, willRebuild,
                env.tableCount(), env.tableCount(), List.of());
    }

    /** 多轮汇总：总语句数、各轮次、是否收敛 */
    private String renderConvergenceSummary(List<ExecutionResult> rounds, boolean dryRun) {
        int total = rounds.stream().mapToInt(ExecutionResult::getTotal).sum();
        int ok = rounds.stream().mapToInt(ExecutionResult::getOk).sum();
        int failed = rounds.stream().mapToInt(ExecutionResult::getFailed).sum();
        int skipped = rounds.stream().mapToInt(ExecutionResult::getSkipped).sum();
        long cost = rounds.stream().mapToLong(ExecutionResult::getTotalCostMs).sum();
        boolean converged = !rounds.isEmpty() && rounds.get(rounds.size() - 1).getTotal() == 0;

        StringBuilder sb = new StringBuilder();
        sb.append("\n════════════════════════════════════════════════════════════════\n");
        sb.append("  收敛汇总\n");
        sb.append("════════════════════════════════════════════════════════════════\n");
        for (ExecutionResult r : rounds) {
            sb.append(String.format("  第 %d 轮：%5d 条  成功 %5d  跳过 %4d  失败 %4d  %6d ms%n",
                    r.getRound(), r.getTotal(), r.getOk(), r.getSkipped(),
                    r.getFailed(), r.getTotalCostMs()));
        }
        sb.append(String.format("  ── 合计：%d 条  成功 %d  跳过 %d  失败 %d  耗时 %d ms%n",
                total, ok, skipped, failed, cost));
        if (dryRun) {
            sb.append("  模式：干跑（已回滚，库未改动）。干跑不循环——回滚后库没变，再跑一轮结果相同\n");
        } else if (converged) {
            sb.append("  结果：✅ 已收敛，无剩余可执行项\n");
        } else {
            sb.append("  结果：⚠ 未收敛，仍有可执行项。可再跑一次，或调大 cli.maxRounds\n");
        }
        return sb.toString();
    }

    private void writeCompareReport(Path path, UpgradeReport r) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("════════════════ DoEnv 比对报告 ════════════════\n");
        sb.append("reportId    : ").append(r.getReportId()).append('\n');
        sb.append("扫描时间    : ").append(r.getScannedAt()).append('\n');
        sb.append("目标库      : ").append(r.getDatabase()).append(" / ").append(r.getSchema()).append('\n');
        sb.append("服务端版本  : ").append(r.getServerVersion()).append('\n');
        sb.append("基准文件    : ").append(r.getBaselineFile()).append('\n');
        sb.append("基准规模    : ").append(r.getBaselineTables()).append(" 表 / ")
                .append(r.getBaselineColumns()).append(" 列 / 种子 ")
                .append(r.getBaselineSeedTables()).append(" 表 ")
                .append(r.getBaselineSeedRows()).append(" 行\n");
        sb.append("现场规模    : ").append(r.getLiveTables()).append(" 表 / ")
                .append(r.getLiveColumns()).append(" 列（vao_ 表 ")
                .append(r.getLiveVaoTables()).append("）\n");
        sb.append("可建表      : ").append(r.isCanCreateTable()).append('\n');
        sb.append("INVALID索引 : ").append(r.getInvalidIndexes()).append('\n');
        sb.append("基准丢弃统计: ").append(r.getBaselineDiscarded()).append('\n');
        sb.append("阻断项      : ").append(r.getBlockers().isEmpty() ? "无" : r.getBlockers()).append('\n');
        sb.append("自检错误    : ").append(r.getGeneratorErrors().isEmpty() ? "无" : r.getGeneratorErrors()).append('\n');
        sb.append("差异按类型  : ").append(r.getSummaryByType()).append('\n');
        sb.append("差异按档位  : ").append(r.getSummaryByLevel()).append('\n');

        sb.append("\n──────── 结构差异 ").append(r.getStructureDiffs().size()).append(" 条 ────────\n");
        appendDiffs(sb, r.getStructureDiffs());
        sb.append("\n──────── 配置数据差异 ").append(r.getSeedDiffs().size()).append(" 条 ────────\n");
        appendDiffs(sb, r.getSeedDiffs());

        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        log.info("比对报告已写入 {}", path.toAbsolutePath());
    }

    private void appendDiffs(StringBuilder sb, List<DiffItem> diffs) {
        for (DiffItem d : diffs) {
            sb.append(String.format("  [%-7s/%-20s] %-36s %s%n",
                    d.getLevel(), d.getType(), d.getTableName(),
                    d.getObjectName() == null ? "" : d.getObjectName()));
            if (d.getLiveDef() != null) {
                sb.append("        现场: ").append(d.getLiveDef()).append('\n');
            }
            if (d.getBaselineDef() != null) {
                sb.append("        基准: ").append(d.getBaselineDef()).append('\n');
            }
            d.getNotes().forEach(n -> sb.append("        说明: ").append(n).append('\n'));
            if (!d.getDdl().isEmpty()) {
                sb.append("        语句: ").append(d.getDdl().size()).append(" 条\n");
            }
        }
    }

    private void writeSql(Path path, UpgradeReport r) throws Exception {
        Files.writeString(path, r.toSqlScript(DiffLevel.AUTO, DiffLevel.CONFIRM, DiffLevel.MANUAL),
                StandardCharsets.UTF_8);
        log.info("升级 SQL 已写入 {}", path.toAbsolutePath());
    }

    /** 把关闭上下文与退出码分离，便于测试 */
    static final class SpringApplicationExit {
        static int close(ConfigurableApplicationContext ctx, int code) {
            ctx.close();
            return code;
        }
    }
}
