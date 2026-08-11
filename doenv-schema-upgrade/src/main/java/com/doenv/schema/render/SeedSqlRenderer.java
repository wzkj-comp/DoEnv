package com.doenv.schema.render;

import com.doenv.schema.seed.SeedTableRule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 配置数据 SQL 渲染器。
 *
 * <p>产物必须满足项目 PG-SQL 规范：显式列名的标准 INSERT、可重复执行、
 * 表引用带 schema 限定符。禁止 {@code SELECT ... FROM (VALUES ...)}、
 * CTE 插入、省略表名这几种写法。
 */
public class SeedSqlRenderer {

    private final String schema;

    public SeedSqlRenderer(String schema) {
        this.schema = (schema == null || schema.isBlank()) ? "public" : schema;
    }

    public String getSchema() {
        return schema;
    }

    /**
     * 补缺行：{@code INSERT ... SELECT ... WHERE NOT EXISTS}。
     *
     * <p>用 WHERE NOT EXISTS 而不是 ON CONFLICT —— 目标表大多没有主键/唯一索引，
     * ON CONFLICT 会直接报错「no unique or exclusion constraint matching」。
     */
    public String insertIfAbsent(String table, List<String> columns,
                                 Map<String, String> row, List<String> keyColumns) {
        String cols = columns.stream().map(SqlQuoter::ident).collect(Collectors.joining(", "));
        String vals = columns.stream()
                .map(c -> SqlQuoter.literal(row.get(c)))
                .collect(Collectors.joining(", "));
        String where = keyColumns.stream()
                .map(k -> SqlQuoter.ident(k) + eqOrIsNull(row.get(k)))
                .collect(Collectors.joining(" AND "));

        return "INSERT INTO " + SqlQuoter.table(schema, table) + " (" + cols + ")\n"
                + "SELECT " + vals + "\n"
                + " WHERE NOT EXISTS (SELECT 1 FROM " + SqlQuoter.table(schema, table)
                + " WHERE " + where + ");";
    }

    /**
     * 更新已有行。<b>冻结列绝不出现在 SET 子句里</b> —— 这是整套工具最重要的一条保护。
     *
     * @return 无可更新列时返回 null
     */
    public String updateNonFrozen(String table, Map<String, String> row,
                                  List<String> changedColumns, SeedTableRule rule) {
        List<String> updatable = changedColumns.stream()
                .filter(c -> !rule.isFrozen(c))
                .filter(c -> rule.getKey().stream().noneMatch(k -> k.equalsIgnoreCase(c)))
                .toList();
        if (updatable.isEmpty()) {
            return null;
        }
        String set = updatable.stream()
                .map(c -> SqlQuoter.ident(c) + " = " + SqlQuoter.literal(row.get(c)))
                .collect(Collectors.joining(",\n       "));
        String where = rule.getKey().stream()
                .map(k -> SqlQuoter.ident(k) + eqOrIsNull(row.get(k)))
                .collect(Collectors.joining(" AND "));

        return "UPDATE " + SqlQuoter.table(schema, table) + "\n"
                + "   SET " + set + "\n"
                + " WHERE " + where + ";";
    }

    /**
     * FULL_OVERWRITE：先按谓词删掉基准来源的旧行，再整体重灌。
     *
     * <p>谓词为空时直接拒绝 —— 绝不生成无条件的 DELETE。
     */
    public List<String> fullOverwrite(String table, List<String> columns,
                                      List<Map<String, String>> rows, SeedTableRule rule) {
        if (rule.getDeletePredicate() == null || rule.getDeletePredicate().isBlank()) {
            throw new IllegalStateException(
                    table + " 配置为 FULL_OVERWRITE 但未提供 deletePredicate，拒绝生成无条件 DELETE");
        }
        List<String> out = new ArrayList<>();
        out.add("DELETE FROM " + SqlQuoter.table(schema, table)
                + " WHERE " + rule.getDeletePredicate() + ";");

        String cols = columns.stream().map(SqlQuoter::ident).collect(Collectors.joining(", "));
        // 分批，避免单条语句过大
        final int batch = 500;
        for (int i = 0; i < rows.size(); i += batch) {
            List<Map<String, String>> slice = rows.subList(i, Math.min(i + batch, rows.size()));
            String values = slice.stream()
                    .map(r -> "(" + columns.stream()
                            .map(c -> SqlQuoter.literal(r.get(c)))
                            .collect(Collectors.joining(", ")) + ")")
                    .collect(Collectors.joining(",\n       "));
            out.add("INSERT INTO " + SqlQuoter.table(schema, table) + " (" + cols + ")\n"
                    + "VALUES " + values + ";");
        }
        return out;
    }

    /**
     * 整表重建：清空 + 按基准全量重灌 + 回填运行时列。
     *
     * <p>与 {@link #fullOverwrite} 的区别是这里<b>无条件清空整表</b>，
     * 不需要 deletePredicate —— 语义就是「现场这张表整个以基准为准」，
     * 现场独有的行本就该消失。所以调用方必须先确认该表确实适用这个语义。
     *
     * @param preserved 重建后要回填的值：id -> (列名 -> 值)。传空则不生成回填语句
     */
    public List<String> rebuild(String table, List<String> columns,
                                List<Map<String, String>> rows,
                                Map<String, Map<String, String>> preserved) {
        List<String> out = new ArrayList<>();
        out.add("DELETE FROM " + SqlQuoter.table(schema, table) + ";");

        String cols = columns.stream().map(SqlQuoter::ident).collect(Collectors.joining(", "));
        final int batch = 500;
        for (int i = 0; i < rows.size(); i += batch) {
            List<Map<String, String>> slice = rows.subList(i, Math.min(i + batch, rows.size()));
            String values = slice.stream()
                    .map(r -> "(" + columns.stream()
                            .map(c -> SqlQuoter.literal(r.get(c)))
                            .collect(Collectors.joining(", ")) + ")")
                    .collect(Collectors.joining(",\n       "));
            out.add("INSERT INTO " + SqlQuoter.table(schema, table) + " (" + cols + ")\n"
                    + "VALUES " + values + ";");
        }

        // 回填运行时列。基准里 count 恒为 0，不回填等于把现场统计清零
        preserved.forEach((id, vals) -> {
            String sets = vals.entrySet().stream()
                    .map(e -> SqlQuoter.ident(e.getKey()) + " = " + SqlQuoter.literal(e.getValue()))
                    .collect(Collectors.joining(", "));
            out.add("UPDATE " + SqlQuoter.table(schema, table) + " SET " + sets
                    + " WHERE " + SqlQuoter.ident("id") + " = " + SqlQuoter.literal(id) + ";");
        });
        return out;
    }

    /**
     * 探测「现场有、基准没有」的行数，供整表重建的执行时刻复检使用。
     *
     * <p>id 集合可能上千个，但这条只在执行前跑一次，比起误删数据的代价可以忽略。
     */
    public String countRowsNotIn(String table, String keyColumn, java.util.Collection<String> baselineIds) {
        if (baselineIds.isEmpty()) {
            return "SELECT count(*) FROM " + SqlQuoter.table(schema, table);
        }
        String in = baselineIds.stream().map(SqlQuoter::literal)
                .collect(Collectors.joining(", "));
        return "SELECT count(*) FROM " + SqlQuoter.table(schema, table)
                + " WHERE " + SqlQuoter.ident(keyColumn) + " NOT IN (" + in + ")";
    }

    /** 按稳定键查现场行的 SQL，供比对阶段使用 */
    public String selectByKeys(String table, List<String> columns, List<String> keyColumns) {
        LinkedHashMap<String, Boolean> all = new LinkedHashMap<>();
        keyColumns.forEach(k -> all.put(k, true));
        columns.forEach(c -> all.put(c, true));
        String cols = all.keySet().stream().map(SqlQuoter::ident).collect(Collectors.joining(", "));
        return "SELECT " + cols + " FROM " + SqlQuoter.table(schema, table);
    }

    private String eqOrIsNull(String value) {
        return value == null ? " IS NULL" : " = " + SqlQuoter.literal(value);
    }
}
