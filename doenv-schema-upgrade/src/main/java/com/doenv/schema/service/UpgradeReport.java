package com.doenv.schema.service;

import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffLevel;
import com.doenv.schema.diff.DiffType;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 一次比对的完整报告。
 */
@Data
public class UpgradeReport {

    private String reportId;
    private String scannedAt;

    private String baselineFile;
    private String baselineId;
    private int baselineTables;
    private int baselineColumns;
    private int baselineSeedTables;
    private int baselineSeedRows;

    private String database;
    private String schema;
    private String serverVersion;
    private int liveTables;
    private int liveColumns;
    private int liveVaoTables;
    private boolean canCreateTable;
    private List<String> invalidIndexes = new ArrayList<>();

    /** 阻断项。非空时界面必须禁用一切执行入口，只允许导出脚本 */
    private List<String> blockers = new ArrayList<>();

    /** 基准解析过程中丢弃的语句统计 */
    private Map<String, Integer> baselineDiscarded = new LinkedHashMap<>();

    private List<DiffItem> structureDiffs = new ArrayList<>();
    private List<DiffItem> seedDiffs = new ArrayList<>();

    /** 生成的 SQL 自检未通过的项。非空时禁止执行 */
    private List<String> generatorErrors = new ArrayList<>();

    public Map<String, Long> getSummaryByType() {
        return allDiffs().stream().collect(Collectors.groupingBy(
                d -> d.getType().name(), LinkedHashMap::new, Collectors.counting()));
    }

    public Map<String, Long> getSummaryByLevel() {
        return allDiffs().stream().collect(Collectors.groupingBy(
                d -> d.getLevel().name(), LinkedHashMap::new, Collectors.counting()));
    }

    public List<DiffItem> allDiffs() {
        List<DiffItem> all = new ArrayList<>(structureDiffs);
        all.addAll(seedDiffs);
        return all;
    }

    /** 可执行项（AUTO 档）的语句数 */
    public int getAutoStatementCount() {
        return allDiffs().stream()
                .filter(d -> d.getLevel() == DiffLevel.AUTO)
                .mapToInt(d -> d.getDdl().size())
                .sum();
    }

    public boolean isExecutable() {
        return blockers.isEmpty() && generatorErrors.isEmpty();
    }

    /** 导出 SQL 脚本文本 */
    public String toSqlScript(DiffLevel... levels) {
        List<DiffLevel> want = List.of(levels);
        StringBuilder sb = new StringBuilder();
        sb.append("-- DoEnv 结构升级脚本\n")
                .append("-- 基准: ").append(baselineFile).append('\n')
                .append("-- 目标: ").append(database).append('/').append(schema).append('\n')
                .append("-- 生成: ").append(scannedAt).append('\n')
                .append("-- 档位: ").append(want).append('\n')
                .append("-- 注意: CREATE INDEX CONCURRENTLY 必须在事务外执行\n\n");

        for (DiffItem d : allDiffs()) {
            if (!want.contains(d.getLevel()) || d.getDdl().isEmpty()) {
                continue;
            }
            sb.append("-- [").append(d.getLevel()).append('/').append(d.getType()).append("] ")
                    .append(d.getTableName());
            if (d.getObjectName() != null) {
                sb.append('.').append(d.getObjectName());
            }
            sb.append('\n');
            for (String note : d.getNotes()) {
                sb.append("--   ").append(note).append('\n');
            }
            d.getDdl().forEach(s -> sb.append(s).append('\n'));
            sb.append('\n');
        }
        return sb.toString();
    }

    public List<DiffItem> byType(DiffType type) {
        return allDiffs().stream().filter(d -> d.getType() == type).toList();
    }
}
