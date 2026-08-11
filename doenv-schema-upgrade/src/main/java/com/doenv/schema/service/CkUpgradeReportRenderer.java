package com.doenv.schema.service;

import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffLevel;
import com.doenv.schema.diff.DiffType;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * CK 升级结果日志。
 *
 * <p>重点是把<b>没做什么、为什么没做</b>讲清楚 —— 自动完成的部分看汇总就够了，
 * 真正需要人接手的是那些被跳过的表：哪张表、多少行、哪一项变了、为什么工具不敢动。
 */
public final class CkUpgradeReportRenderer {

    private CkUpgradeReportRenderer() {
    }

    public static String render(String database, String topology, String baselineFile,
                                int tablesBefore, int tablesAfter,
                                List<ExecutionResult> rounds,
                                List<DiffItem> remaining) {
        StringBuilder sb = new StringBuilder();
        sb.append("════════════════════════════════════════════════════════════════\n");
        sb.append("  DoEnv ClickHouse 升级结果\n");
        sb.append("════════════════════════════════════════════════════════════════\n");
        sb.append("目标库    : ").append(database).append("    拓扑: ").append(topology).append('\n');
        sb.append("基准      : ").append(baselineFile).append('\n');
        sb.append("表数      : ").append(tablesBefore).append(" → ").append(tablesAfter)
                .append(tablesAfter > tablesBefore ? "  (+" + (tablesAfter - tablesBefore) + ")" : "")
                .append('\n');

        int total = rounds.stream().mapToInt(ExecutionResult::getTotal).sum();
        int ok = rounds.stream().mapToInt(ExecutionResult::getOk).sum();
        int failed = rounds.stream().mapToInt(ExecutionResult::getFailed).sum();
        int skipped = rounds.stream().mapToInt(ExecutionResult::getSkipped).sum();
        long cost = rounds.stream().mapToLong(ExecutionResult::getTotalCostMs).sum();

        sb.append("语句统计  : 共 ").append(total).append(" 条，成功 ").append(ok)
                .append("，跳过 ").append(skipped).append("，失败 ").append(failed)
                .append("，耗时 ").append(cost).append(" ms\n");

        // ── 一、执行轮次 ──
        sb.append("\n──────────────── 一、执行轮次 ────────────────\n");
        for (ExecutionResult r : rounds) {
            sb.append(String.format("  第 %d 轮：%5d 条  成功 %5d  跳过 %4d  失败 %4d  %7d ms%n",
                    r.getRound(), r.getTotal(), r.getOk(), r.getSkipped(),
                    r.getFailed(), r.getTotalCostMs()));
        }
        boolean converged = !rounds.isEmpty() && rounds.get(rounds.size() - 1).getTotal() == 0;
        sb.append(converged ? "  ✅ 已收敛\n" : "  ⚠ 未收敛，仍有可执行项\n");

        // ── 二、对哪些表做了什么 ──
        sb.append("\n──────────────── 二、已完成的变更 ────────────────\n");
        Map<String, TableChange> merged = new LinkedHashMap<>();
        for (ExecutionResult r : rounds) {
            r.getTableChanges().forEach((k, v) -> merged.merge(k, v, (a, b2) -> {
                b2.getActions().forEach((act, n) -> a.getActions().merge(act, n, Integer::sum));
                return a;
            }));
        }
        Map<String, Integer> actionTotals = new TreeMap<>();
        merged.values().forEach(tc ->
                tc.getActions().forEach((a, n) -> actionTotals.merge(a, n, Integer::sum)));
        if (actionTotals.isEmpty()) {
            sb.append("  （无）\n");
        } else {
            actionTotals.forEach((a, n) ->
                    sb.append(String.format("  %-16s %5d 条%n", a, n)));
            sb.append("  涉及表数: ").append(merged.size()).append('\n');
        }

        // ── 三、跳过的表（最重要的一节） ──
        List<DiffItem> skippedItems = remaining.stream()
                .filter(d -> d.getLevel() == DiffLevel.MANUAL)
                .sorted(Comparator
                        .comparing((DiffItem d) -> d.getEstimatedRows() == null
                                ? Long.MAX_VALUE : -d.getEstimatedRows())
                        .thenComparing(DiffItem::getTableName))
                .toList();

        sb.append("\n──────────────── 三、跳过的表（需人工处理） ────────────────\n");
        if (skippedItems.isEmpty()) {
            sb.append("  （无）\n");
        } else {
            Map<String, Integer> byType = new TreeMap<>();
            skippedItems.forEach(d -> byType.merge(d.getType().name(), 1, Integer::sum));
            sb.append("  共 ").append(skippedItems.size()).append(" 项：").append(byType).append("\n\n");

            sb.append(String.format("  %-38s %10s  %s%n", "表名", "行数", "跳过原因 / 变更项"));
            sb.append("  ").append("─".repeat(100)).append('\n');
            for (DiffItem d : skippedItems) {
                String rows = d.getEstimatedRows() == null ? "未知"
                        : String.format("%,d", d.getEstimatedRows());
                String reason = d.getNotes().isEmpty() ? "" : d.getNotes().get(0);
                sb.append(String.format("  %-38s %10s  %s%n",
                        d.getTableName() + (d.getObjectName() == null ? "" : "." + d.getObjectName()),
                        rows, reason));
                // 变更项单独一行，看得清到底哪一项不一致
                d.getNotes().stream().skip(1).limit(1).forEach(n ->
                        sb.append(String.format("  %-38s %10s  %s%n", "", "", n)));
            }

            long withData = skippedItems.stream()
                    .filter(d -> d.getEstimatedRows() != null && d.getEstimatedRows() > 0).count();
            long unknown = skippedItems.stream()
                    .filter(d -> d.getEstimatedRows() == null).count();
            sb.append("\n  其中：有数据 ").append(withData).append(" 张");
            if (unknown > 0) {
                sb.append("，行数未知 ").append(unknown).append(" 张（按有数据保守处理）");
            }
            sb.append('\n');
            sb.append("  这些表的引擎/分区键/排序键/主键在 CK 里建表后不可 ALTER，\n");
            sb.append("  重建会丢数据，因此工具不动它们。迁移模板已随差异项生成，需人工执行。\n");
        }

        // ── 四、只读提示 ──
        List<DiffItem> infos = remaining.stream()
                .filter(d -> d.getLevel() == DiffLevel.INFO).toList();
        if (!infos.isEmpty()) {
            sb.append("\n──────────────── 四、仅提示（现场多出的对象） ────────────────\n");
            Map<String, Integer> byType = new TreeMap<>();
            infos.forEach(d -> byType.merge(d.getType().name(), 1, Integer::sum));
            sb.append("  ").append(byType).append("  —— 严格单向，永不生成 DROP\n");
            infos.stream().limit(10).forEach(d -> sb.append("    ")
                    .append(d.getTableName())
                    .append(d.getObjectName() == null ? "" : "." + d.getObjectName())
                    .append(d.getNotes().isEmpty() ? "" : "  // " + d.getNotes().get(0))
                    .append('\n'));
            if (infos.size() > 10) {
                sb.append("    …… 其余 ").append(infos.size() - 10).append(" 条见比对报告\n");
            }
        }

        // ── 五、失败明细 ──
        List<StatementLog> failures = rounds.stream()
                .flatMap(r -> r.getStatements().stream())
                .filter(s -> "FAILED".equals(s.getStatus())).toList();
        if (!failures.isEmpty()) {
            sb.append("\n──────────────── 五、失败明细 ────────────────\n");
            for (StatementLog sl : failures) {
                sb.append("  ").append(sl.getTable()).append("  ").append(sl.getError()).append('\n');
                sb.append("    SQL: ").append(head(sl.getSql())).append('\n');
            }
        }

        // ── 六、被前置检查拦下的 ──
        List<StatementLog> blocked = rounds.stream()
                .flatMap(r -> r.getStatements().stream())
                .filter(s -> "SKIP_PRECHECK_FAILED".equals(s.getPfcVerdict())).toList();
        if (!blocked.isEmpty()) {
            sb.append("\n──────────────── 六、前置检查拦下的 ────────────────\n");
            for (StatementLog sl : blocked) {
                sb.append("  ").append(sl.getTable()).append("  ").append(sl.getError()).append('\n');
            }
        }

        return sb.toString();
    }

    /** 空表重建的单独统计，放在日志里让人一眼看到「自动重建了哪些」 */
    public static String renderRebuilds(List<DiffItem> executed) {
        List<DiffItem> rebuilds = executed.stream()
                .filter(d -> d.getType() == DiffType.CK_REBUILD_EMPTY).toList();
        if (rebuilds.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n──────────────── 空表重建明细 ────────────────\n");
        sb.append("  这些表结构变更无法 ALTER，但表是空的，重建零数据风险：\n");
        for (DiffItem d : rebuilds) {
            sb.append("    ").append(d.getTableName()).append('\n');
            sb.append("      现场: ").append(d.getLiveDef()).append('\n');
            sb.append("      基准: ").append(d.getBaselineDef()).append('\n');
        }
        return sb.toString();
    }

    private static String head(String s) {
        String one = s.replaceAll("\\s+", " ");
        return one.length() > 200 ? one.substring(0, 200) + "…" : one;
    }
}
