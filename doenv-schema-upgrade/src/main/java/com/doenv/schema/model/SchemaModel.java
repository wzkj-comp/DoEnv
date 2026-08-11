package com.doenv.schema.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个库（基准或现场）的完整结构模型。
 */
@Data
public class SchemaModel {

    /** BASELINE / LIVE */
    private String source;

    /** 基准版本号，如 2026-08-05；现场侧为空 */
    private String baselineId;

    /** PG schema 名。不硬编码 public —— 现场可能不是 public，达梦是 DATAO */
    private String schemaName = "public";

    private List<TableModel> tables = new ArrayList<>();

    /** 序列。基准来自 CREATE SEQUENCE + setval，现场来自 pg_sequences */
    private List<SequenceModel> sequences = new ArrayList<>();

    /** 视图。没有数据、没有引擎，补起来是纯元数据操作 */
    private List<ViewModel> views = new ArrayList<>();

    /** 解析/读取过程中丢弃或降级的内容，供报告展示 */
    private List<String> warnings = new ArrayList<>();

    private transient Map<String, TableModel> tableIndex;

    public TableModel findTable(String tableName) {
        if (tableIndex == null) {
            Map<String, TableModel> m = new LinkedHashMap<>();
            for (TableModel t : tables) {
                m.put(t.getName().toLowerCase(), t);
            }
            tableIndex = m;
        }
        return tableIndex.get(tableName == null ? null : tableName.toLowerCase());
    }

    public void addTable(TableModel t) {
        tables.add(t);
        tableIndex = null;
    }

    public int getTableCount() {
        return tables.size();
    }

    public ViewModel findView(String name) {
        if (name == null) {
            return null;
        }
        return views.stream()
                .filter(v -> name.equalsIgnoreCase(v.getName()))
                .findFirst().orElse(null);
    }

    public SequenceModel findSequence(String name) {
        if (name == null) {
            return null;
        }
        for (SequenceModel s : sequences) {
            if (s.getName().equalsIgnoreCase(name)) {
                return s;
            }
        }
        return null;
    }

    public int getColumnCount() {
        return tables.stream().mapToInt(t -> t.getColumns().size()).sum();
    }
}
