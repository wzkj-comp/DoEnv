package com.doenv.schema.service;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次执行的完整结果。
 */
@Data
public class ExecutionResult {

    /** 第几轮（从 1 开始）。一次升级会自动跑多轮直到收敛 */
    private int round = 1;

    private String runId;
    private String reportId;

    private String startedAt;
    private String finishedAt;
    private long totalCostMs;

    private String database;
    private String schema;
    private String baselineFile;

    /** 干跑：执行后回滚，只验证语法与可行性，不留下任何改动 */
    private boolean dryRun;

    private List<String> levels = new ArrayList<>();

    private int total;
    private int ok;
    private int skipped;
    private int failed;

    /** 逐语句流水 */
    private List<StatementLog> statements = new ArrayList<>();

    /** 表级归纳：最终对哪张表做了什么 */
    private Map<String, TableChange> tableChanges = new LinkedHashMap<>();

    /** 执行前就中止的原因（阻断项、自检错误） */
    private List<String> abortedReasons = new ArrayList<>();

    public boolean isAborted() {
        return !abortedReasons.isEmpty();
    }

    public boolean isAllSucceeded() {
        return failed == 0 && !isAborted();
    }

    /** 按顺序列出真正产生了改动的表 */
    public List<TableChange> changedTables() {
        return tableChanges.values().stream()
                .filter(t -> t.getOkCount() > 0)
                .toList();
    }
}
