package com.doenv.schema.service;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一张表的变更归纳。回答「最终对这张表做了什么」。
 */
@Data
public class TableChange {

    private String table;

    /** 结构 / 配置数据 */
    private String category;

    /** 动作 -> 次数，如 {INSERT=15, UPDATE=20} */
    private Map<String, Integer> actions = new LinkedHashMap<>();

    /** 动作 -> 累计影响行数 */
    private Map<String, Integer> rowsByAction = new LinkedHashMap<>();

    private long rowsBefore = -1;
    private long rowsAfter = -1;

    private int okCount;
    private int failedCount;
    private int skippedCount;

    /** 明细说明，直接展示给人看 */
    private List<String> details = new ArrayList<>();

    public void record(StatementLog log) {
        actions.merge(log.getAction(), 1, Integer::sum);
        if (log.getRowsAffected() > 0) {
            rowsByAction.merge(log.getAction(), log.getRowsAffected(), Integer::sum);
        }
        switch (log.getStatus()) {
            case "OK" -> okCount++;
            case "FAILED" -> failedCount++;
            default -> skippedCount++;
        }
    }

    public long getRowDelta() {
        return (rowsBefore < 0 || rowsAfter < 0) ? 0 : rowsAfter - rowsBefore;
    }

    /** 一行式摘要，如 "vao_recordtype_column  配置数据  UPDATE×20(20行)  7159 → 7159" */
    public String summaryLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-38s %-8s ", table, category));
        List<String> parts = new ArrayList<>();
        actions.forEach((a, n) -> {
            Integer rows = rowsByAction.get(a);
            parts.add(a + "×" + n + (rows == null ? "" : "(" + rows + "行)"));
        });
        sb.append(String.join(" ", parts));
        if (rowsBefore >= 0 && rowsAfter >= 0) {
            sb.append(String.format("   行数 %d → %d", rowsBefore, rowsAfter));
            long d = getRowDelta();
            if (d != 0) {
                sb.append(String.format(" (%+d)", d));
            }
        }
        if (failedCount > 0) {
            sb.append("   失败 ").append(failedCount);
        }
        return sb.toString();
    }
}
