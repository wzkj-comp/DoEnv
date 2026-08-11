package com.doenv.schema.service;

import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffType;
import com.doenv.schema.diff.DiffLevel;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 升级结果文档。PG 与 CK 合并成一份，无论成功失败都产出。
 *
 * <p>两个主体部分：
 * <ol>
 *   <li><b>执行的每一条 SQL 明细</b> —— 逐条列出，含状态、影响行数、耗时、失败原因</li>
 *   <li><b>跳过的表 / 操作及原因</b> —— 说清楚为什么工具不动它</li>
 * </ol>
 */
public final class UpgradeResultDocument {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private UpgradeResultDocument() {
    }

    /** 一个库的运行结果 */
    public record Section(String dbKind, String database, String extra, String baselineFile,
                          int objectsBefore, int objectsAfter,
                          List<ExecutionResult> rounds,
                          List<DiffItem> remaining,
                          List<String> blockers,
                          List<String> generatorErrors,
                          /** 本轮实际执行的差异项。用来把「重建了哪些表」单独摊开 */
                          List<DiffItem> executedItems) {

        /** 兼容不关心重建清单的调用方 */
        public Section(String dbKind, String database, String extra, String baselineFile,
                       int objectsBefore, int objectsAfter, List<ExecutionResult> rounds,
                       List<DiffItem> remaining, List<String> blockers,
                       List<String> generatorErrors) {
            this(dbKind, database, extra, baselineFile, objectsBefore, objectsAfter,
                    rounds, remaining, blockers, generatorErrors, List.of());
        }
    }

    /**
     * 渲染结果文档正文。
     *
     * <p>SQL 原文不放正文 —— 6000 多条语句、单条 INSERT 可达上万字符，
     * 全塞进来文档会到几 MB，编辑器打不开。正文只留摘要表格（含序号），
     * 需要看原文时按序号去附录里查。
     *
     * @param sqlAppendixName SQL 原文附录的文件名，写进正文供交叉引用
     */
    public static String render(List<Section> sections, String sqlAppendixName) {
        StringBuilder sb = new StringBuilder();
        String now = LocalDateTime.now().format(TS);

        int allTotal = 0;
        int allOk = 0;
        int allFailed = 0;
        int allSkipped = 0;
        for (Section s : sections) {
            allTotal += s.rounds().stream().mapToInt(ExecutionResult::getTotal).sum();
            allOk += s.rounds().stream().mapToInt(ExecutionResult::getOk).sum();
            allFailed += s.rounds().stream().mapToInt(ExecutionResult::getFailed).sum();
            allSkipped += s.rounds().stream().mapToInt(ExecutionResult::getSkipped).sum();
        }
        boolean anyBlocked = sections.stream().anyMatch(s -> !s.blockers().isEmpty()
                || !s.generatorErrors().isEmpty());
        // 跑满轮次仍有可执行项 = 没收敛。以前这种情况照样盖「成功」章，
        // 而残留的 AUTO/CONFIRM 项在文档里两个部分都不渲染，等于完全看不见
        List<Section> notConverged = sections.stream()
                .filter(s -> s.rounds().stream().anyMatch(r -> r.getTotal() > 0))
                .filter(s -> s.remaining().stream()
                        .anyMatch(d -> (d.getLevel() == DiffLevel.AUTO
                                || d.getLevel() == DiffLevel.CONFIRM) && !d.getDdl().isEmpty()))
                .toList();
        String verdict = allFailed > 0 || anyBlocked ? "存在失败或阻断"
                : notConverged.isEmpty() ? "成功" : "未收敛（仍有待执行差异）";

        sb.append("════════════════════════════════════════════════════════════════════════\n");
        sb.append("  DoEnv 数据库升级结果文档\n");
        sb.append("════════════════════════════════════════════════════════════════════════\n");
        sb.append("生成时间 : ").append(now).append('\n');
        sb.append("总体结论 : ").append(verdict).append('\n');
        sb.append("语句合计 : ").append(allTotal).append(" 条，成功 ").append(allOk)
                .append("，跳过 ").append(allSkipped).append("，失败 ").append(allFailed).append('\n');

        sb.append("\n涉及的库：\n");
        for (Section s : sections) {
            sb.append(String.format("  %-4s %-24s 对象数 %d → %d   基准 %s%n",
                    s.dbKind(), s.database(), s.objectsBefore(), s.objectsAfter(),
                    s.baselineFile()));
            if (s.extra() != null && !s.extra().isEmpty()) {
                sb.append("       ").append(s.extra()).append('\n');
            }
        }

        for (Section s : notConverged) {
            long items = s.remaining().stream()
                    .filter(d -> (d.getLevel() == DiffLevel.AUTO || d.getLevel() == DiffLevel.CONFIRM)
                            && !d.getDdl().isEmpty()).count();
            sb.append("\n⚠ ").append(s.dbKind()).append(" 未收敛：执行完仍有 ").append(items)
                    .append(" 项可执行差异。可能是某类差异生成的 SQL 改不掉它自己，")
                    .append("或轮次上限不够（--doenv.schema-upgrade.cli.maxRounds）\n");
            s.remaining().stream()
                    .filter(d -> (d.getLevel() == DiffLevel.AUTO || d.getLevel() == DiffLevel.CONFIRM)
                            && !d.getDdl().isEmpty())
                    .limit(20)
                    .forEach(d -> sb.append("    ").append(d.getType()).append("  ")
                            .append(nz(d.getTableName())).append("  ")
                            .append(d.getNotes().isEmpty() ? "" : d.getNotes().get(0)).append('\n'));
        }

        // 阻断与自检错误最先说，因为它们意味着「根本没执行」
        for (Section s : sections) {
            if (!s.blockers().isEmpty() || !s.generatorErrors().isEmpty()) {
                sb.append("\n【").append(s.dbKind()).append(" 未执行】\n");
                s.blockers().forEach(x -> sb.append("  ✗ 环境阻断：").append(x).append('\n'));
                s.generatorErrors().forEach(x -> sb.append("  ✗ 生成物自检：").append(x).append('\n'));
            }
        }

        // ════════ 第一部分 ════════
        sb.append("\n\n");
        sb.append("========================================================================\n");
        sb.append("  第一部分：执行的 SQL 明细\n");
        sb.append("========================================================================\n");
        if (sqlAppendixName != null) {
            sb.append("  SQL 原文见附录：").append(sqlAppendixName)
                    .append("（按下表的「序号」检索）\n");
        }
        for (Section s : sections) {
            renderStatements(sb, s);
        }

        // ════════ 第二部分 ════════
        sb.append("\n\n");
        sb.append("========================================================================\n");
        sb.append("  第二部分：跳过的表 / 操作及原因\n");
        sb.append("========================================================================\n");
        for (Section s : sections) {
            renderSkipped(sb, s);
        }

        return sb.toString();
    }

    /**
     * 重建过的表，单独摊开。
     *
     * <p>重建 = DROP + CREATE，是整个流程里唯一会丢数据的动作，只对「已确认 0 行」的表放行。
     * 混在几百条语句的明细里根本看不见（实测一次跑重建了 135 张表，报告里只体现为
     * {@code OTHER×135}），必须单列出来让人一眼扫到、能逐张核对。
     */
    private static void renderRebuilds(StringBuilder sb, Section s) {
        List<DiffItem> rebuilds = s.executedItems().stream()
                .filter(d -> d.getType() == DiffType.CK_REBUILD_EMPTY
                        || d.getType() == DiffType.SEED_REBUILD)
                .toList();
        if (rebuilds.isEmpty()) {
            return;
        }
        boolean seed = rebuilds.get(0).getType() == DiffType.SEED_REBUILD;
        // 只比对时一条语句都没执行，此处是「将要重建」的预览，措辞不能说成已经做了
        boolean executed = s.rounds().stream().anyMatch(r -> r.getTotal() > 0);
        String when = executed ? "本次" : "【只比对，未执行】将要";
        sb.append(seed
                        ? "  ⚠ " + when + "整表重建（清空 + 按基准全量重灌）的表共 " + rebuilds.size()
                          + " 张 —— 现场独有的行会被删除，运行时统计列已回填：\n"
                        : "  ⚠ " + when + "重建（DROP + CREATE）的表共 " + rebuilds.size()
                          + " 张 —— 均已确认为 0 行空表，不涉及数据丢失：\n");
        for (DiffItem d : rebuilds) {
            sb.append("      ").append(d.getTableName());
            if (!d.getNotes().isEmpty()) {
                sb.append("  // ").append(String.join("；", d.getNotes()));
            }
            sb.append('\n');
        }
        sb.append('\n');
    }

    /**
     * 只比对模式下的「将要执行」预览。
     *
     * <p>报告的第一部分本来只渲染执行记录，第二部分只收 MANUAL/INFO 档，
     * 于是 compare 模式下 AUTO/CONFIRM 档的动作（建表、加列、重建）两头都落不到，
     * 报告成了一张白纸 —— 而这恰恰是执行前唯一想看的东西。
     */
    private static void renderPlanned(StringBuilder sb, Section s) {
        List<DiffItem> planned = s.remaining().stream()
                .filter(d -> d.getLevel() == DiffLevel.AUTO || d.getLevel() == DiffLevel.CONFIRM)
                .filter(d -> !d.getDdl().isEmpty())
                .toList();
        if (planned.isEmpty()) {
            sb.append("  （无执行语句，且无待执行差异）\n");
            return;
        }

        int stmts = planned.stream().mapToInt(d -> d.getDdl().size()).sum();
        sb.append("  【只比对，未执行】待执行 ").append(planned.size())
                .append(" 项差异 / ").append(stmts).append(" 条语句\n");

        Map<String, Integer> byType = new TreeMap<>();
        planned.forEach(d -> byType.merge(d.getType().name(), 1, Integer::sum));
        sb.append("  按差异类型：").append(byType).append("\n\n");
        renderRebuilds(sb, s);

        sb.append(String.format("  %-22s %-34s %-8s %s%n", "差异类型", "表名", "档位", "说明"));
        sb.append("  ").append("─".repeat(118)).append('\n');
        for (DiffItem d : planned) {
            sb.append(String.format("  %-22s %-34s %-8s %s%n",
                    d.getType().name(), nz(d.getTableName()), d.getLevel().name(),
                    d.getNotes().isEmpty() ? "" : String.join("；", d.getNotes())));
        }
    }

    // ────────────────────────── 第一部分 ──────────────────────────

    private static void renderStatements(StringBuilder sb, Section s) {
        List<StatementLog> all = s.rounds().stream()
                .flatMap(r -> r.getStatements().stream()).toList();

        sb.append("\n──── ").append(s.dbKind()).append(" / ").append(s.database())
                .append(" ────\n");
        if (all.isEmpty()) {
            // 只比对模式：一条都没执行，但「将要执行什么」正是此时唯一有价值的信息。
            // 不渲染的话整个 compare 模式等于哑巴 —— 实测基准 503 张表、现场 384 张，
            // 报告却只字不提要建 119 张
            renderPlanned(sb, s);
            return;
        }

        // 先给个按动作的汇总，便于快速了解规模
        Map<String, Integer> byAction = new TreeMap<>();
        Map<String, Integer> rowsByAction = new TreeMap<>();
        all.stream().filter(x -> "OK".equals(x.getStatus())).forEach(x -> {
            byAction.merge(x.getAction(), 1, Integer::sum);
            if (x.getRowsAffected() > 0) {
                rowsByAction.merge(x.getAction(), x.getRowsAffected(), Integer::sum);
            }
        });
        sb.append("  动作汇总：");
        if (byAction.isEmpty()) {
            sb.append("无");
        } else {
            List<String> parts = new ArrayList<>();
            byAction.forEach((a, n) -> {
                Integer rows = rowsByAction.get(a);
                parts.add(a + "×" + n + (rows == null ? "" : "(" + rows + "行)"));
            });
            sb.append(String.join("  ", parts));
        }
        sb.append("\n\n");
        renderRebuilds(sb, s);

        sb.append(String.format("  %-5s %-4s %-8s %-18s %-34s %8s %9s  %s%n",
                "序号", "轮次", "状态", "动作", "表名", "耗时ms", "影响行数", "错误"));
        sb.append("  ").append("─".repeat(118)).append('\n');

        for (ExecutionResult r : s.rounds()) {
            for (StatementLog x : r.getStatements()) {
                sb.append(String.format("  %-5d %-4d %-8s %-18s %-34s %8d %9s  %s%n",
                        x.getSeq(), r.getRound(), x.getStatus(), nz(x.getAction()),
                        nz(x.getTable()), x.getCostMs(),
                        x.getRowsAffected() >= 0 ? String.valueOf(x.getRowsAffected()) : "-",
                        nz(x.getError())));
            }
        }

        // 失败与跳过的语句，原文直接放正文 —— 这些是真正要排查的，不该让人再去翻附录
        List<StatementLog> notOk = all.stream()
                .filter(x -> !"OK".equals(x.getStatus())).toList();
        if (!notOk.isEmpty()) {
            sb.append("\n  ── 未成功语句的原文 ──\n");
            for (StatementLog x : notOk) {
                sb.append("  #").append(x.getSeq()).append(" [").append(x.getStatus()).append("] ")
                        .append(x.getTable()).append('\n');
                sb.append("     ").append(oneLine(x.getSql())).append('\n');
                if (x.getError() != null && !x.getError().isEmpty()) {
                    sb.append("     ✗ ").append(x.getError()).append('\n');
                }
            }
        }
    }

    // ────────────────────────── 第二部分 ──────────────────────────

    private static void renderSkipped(StringBuilder sb, Section s) {
        sb.append("\n──── ").append(s.dbKind()).append(" / ").append(s.database())
                .append(" ────\n");

        // 来源一：比对阶段就判定为 MANUAL（工具不碰）
        List<DiffItem> manual = s.remaining().stream()
                .filter(d -> d.getLevel() == DiffLevel.MANUAL)
                .sorted(Comparator
                        .comparing((DiffItem d) -> d.getEstimatedRows() == null
                                ? Long.MIN_VALUE : -d.getEstimatedRows())
                        .thenComparing(DiffItem::getTableName))
                .toList();

        // 来源二：执行阶段被前置检查拦下
        List<StatementLog> blocked = s.rounds().stream()
                .flatMap(r -> r.getStatements().stream())
                .filter(x -> "SKIPPED".equals(x.getStatus()))
                .toList();

        if (manual.isEmpty() && blocked.isEmpty()) {
            sb.append("  （无跳过项）\n");
            return;
        }

        if (!manual.isEmpty()) {
            Map<String, Integer> byType = new TreeMap<>();
            manual.forEach(d -> byType.merge(d.getType().name(), 1, Integer::sum));
            sb.append("\n  【比对阶段判定为需人工处理】共 ").append(manual.size())
                    .append(" 项  ").append(byType).append("\n\n");

            sb.append(String.format("  %-38s %12s  %s%n", "表 / 对象", "行数", "原因"));
            sb.append("  ").append("─".repeat(110)).append('\n');
            for (DiffItem d : manual) {
                String name = d.getTableName()
                        + (d.getObjectName() == null ? "" : "." + d.getObjectName());
                String rows = d.getEstimatedRows() == null ? "-"
                        : String.format("%,d", d.getEstimatedRows());
                String reason = d.getNotes().isEmpty() ? d.getType().name() : d.getNotes().get(0);
                sb.append(String.format("  %-38s %12s  %s%n", name, rows, reason));
                // 其余说明逐条缩进列出，把「差在哪」讲清楚
                d.getNotes().stream().skip(1).forEach(n ->
                        sb.append(String.format("  %-38s %12s  %s%n", "", "", n)));
                if (d.getLiveDef() != null && d.getBaselineDef() != null) {
                    sb.append(String.format("  %-38s %12s  现场: %s%n", "", "", d.getLiveDef()));
                    sb.append(String.format("  %-38s %12s  基准: %s%n", "", "", d.getBaselineDef()));
                }
            }
        }

        if (!blocked.isEmpty()) {
            sb.append("\n  【执行阶段被跳过】共 ").append(blocked.size()).append(" 条\n\n");
            sb.append(String.format("  %-38s %-24s  %s%n", "表", "判定", "原因"));
            sb.append("  ").append("─".repeat(110)).append('\n');
            for (StatementLog x : blocked) {
                sb.append(String.format("  %-38s %-24s  %s%n",
                        nz(x.getTable()), nz(x.getPfcVerdict()), nz(x.getError())));
            }
        }

        // 仅提示项：现场多出的对象，永不处理
        List<DiffItem> infos = s.remaining().stream()
                .filter(d -> d.getLevel() == DiffLevel.INFO).toList();
        if (!infos.isEmpty()) {
            Map<String, Integer> byType = new TreeMap<>();
            infos.forEach(d -> byType.merge(d.getType().name(), 1, Integer::sum));
            sb.append("\n  【仅提示，不处理】").append(byType)
                    .append("  —— 严格单向，现场多出的对象永不生成 DROP\n");
            infos.stream().limit(15).forEach(d -> sb.append("    ")
                    .append(d.getTableName())
                    .append(d.getObjectName() == null ? "" : "." + d.getObjectName())
                    .append(d.getNotes().isEmpty() ? "" : "  // " + d.getNotes().get(0))
                    .append('\n'));
            if (infos.size() > 15) {
                sb.append("    …… 其余 ").append(infos.size() - 15).append(" 条\n");
            }
        }
    }

    /**
     * SQL 原文附录。按序号编排，与正文表格一一对应。
     * 单独成文件，正文才能保持在可打开的体积。
     */
    public static String renderSqlAppendix(List<Section> sections) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- DoEnv 升级 SQL 原文附录\n");
        sb.append("-- 与「升级结果」文档第一部分的序号一一对应\n");
        sb.append("-- 生成时间: ").append(LocalDateTime.now().format(TS)).append("\n");

        for (Section s : sections) {
            sb.append("\n\n-- ==========================================================\n");
            sb.append("-- ").append(s.dbKind()).append(" / ").append(s.database()).append('\n');
            sb.append("-- ==========================================================\n");
            for (ExecutionResult r : s.rounds()) {
                for (StatementLog x : r.getStatements()) {
                    sb.append("\n-- #").append(x.getSeq())
                            .append("  轮次 ").append(r.getRound())
                            .append("  [").append(x.getStatus()).append("]")
                            .append("  ").append(nz(x.getTable()))
                            .append("  ").append(x.getCostMs()).append("ms");
                    if (x.getRowsAffected() >= 0) {
                        sb.append("  影响 ").append(x.getRowsAffected()).append(" 行");
                    }
                    sb.append('\n');
                    if (x.getError() != null && !x.getError().isEmpty()) {
                        sb.append("-- ✗ ").append(x.getError()).append('\n');
                    }
                    sb.append(x.getSql()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
