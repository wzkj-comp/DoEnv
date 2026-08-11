package com.doenv.schema.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一张表从基准 INSERT 里提取出来的种子数据。
 *
 * <p>只保存值，不保存 SQL 原文 —— 基准原文是「无 WHERE 的 DELETE + 巨型 INSERT」的
 * 全量覆盖写法，一行都不能直接拿去现场跑，必须由渲染器按策略重新生成。
 */
@Data
public class SeedTable {

    private String tableName;

    /** 列名清单，顺序与每行的值一一对应 */
    private List<String> columns = new ArrayList<>();

    /** 每行一个 map：列名（原样） -> 值（null 表示 SQL NULL） */
    private List<Map<String, String>> rows = new ArrayList<>();

    public int getRowCount() {
        return rows.size();
    }

    /**
     * 每行的 SQL 原始词法：列名 -> 未经反引号处理的原文。
     *
     * <p>基准里的值并不都是字符串字面量 —— 还有 {@code NULL}、{@code now()}、裸数字。
     * 只留反引号后的文本就分不清 {@code 'now()'}（字符串）和 {@code now()}（函数），
     * 重新加引号会把 DateTime 列插成非法值。渲染时优先用这份原文。
     */
    private List<Map<String, String>> rawRows = new ArrayList<>();

    public void addRow(List<String> values) {
        addRow(values, values);
    }

    public void addRow(List<String> values, List<String> raw) {
        Map<String, String> row = new LinkedHashMap<>();
        Map<String, String> rawRow = new LinkedHashMap<>();
        for (int i = 0; i < columns.size() && i < values.size(); i++) {
            row.put(columns.get(i), values.get(i));
            rawRow.put(columns.get(i), i < raw.size() ? raw.get(i) : values.get(i));
        }
        rows.add(row);
        rawRows.add(rawRow);
    }

    /** 取第 i 行的原始词法，没有就退回反引号后的值 */
    public Map<String, String> rawRow(int i) {
        return i < rawRows.size() ? rawRows.get(i) : rows.get(i);
    }
}
