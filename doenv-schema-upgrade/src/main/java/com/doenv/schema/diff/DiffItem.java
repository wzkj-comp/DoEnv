package com.doenv.schema.diff;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条原子差异。对外会按表聚合成 ChangeSet，但内部保持原子粒度便于精确渲染。
 */
@Data
public class DiffItem {

    /** 稳定唯一键，如 PG|COLUMN|vao_case|isnonlocal */
    private String key;

    private DiffType type;

    private DiffLevel level;

    private String tableName;

    /** 列名 / 索引名 / 约束名 / 种子行主键；表级差异为空 */
    private String objectName;

    /** 现场现状的可读描述 */
    private String liveDef;

    /** 基准定义的可读描述 */
    private String baselineDef;

    /** 生成的 SQL。MANUAL 档也生成，但界面不给执行按钮 */
    private List<String> ddl = new ArrayList<>();

    /**
     * CONFIRM 档的前置探测。执行器会在执行本项之前逐条跑，
     * 全部通过才放行；任一不通过则跳过本项并记录原因。
     */
    private List<PrecheckRule> prechecks = new ArrayList<>();

    /** 目标表的行数。用于「空表可重建 / 有数据必须跳过」的判定与日志 */
    private Long estimatedRows;

    /** 风险提示、降级说明 */
    private List<String> notes = new ArrayList<>();

    public DiffItem note(String n) {
        notes.add(n);
        return this;
    }

    public DiffItem sql(String s) {
        ddl.add(s);
        return this;
    }

    public DiffItem precheck(PrecheckRule r) {
        prechecks.add(r);
        return this;
    }

    /** 报告展示用：只取 SQL 文本 */
    public List<String> getPrecheckSql() {
        return prechecks.stream().map(PrecheckRule::getSql).toList();
    }

    public static DiffItem of(DiffType type, DiffLevel level, String table, String object) {
        DiffItem d = new DiffItem();
        d.type = type;
        d.level = level;
        d.tableName = table;
        d.objectName = object;
        d.key = "PG|" + type.name() + "|" + table + (object == null ? "" : "|" + object);
        return d;
    }
}
