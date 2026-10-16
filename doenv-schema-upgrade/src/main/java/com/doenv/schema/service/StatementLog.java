package com.doenv.schema.service;

import lombok.Data;

/**
 * 单条语句的执行流水。排查问题的最小单位 —— 每一条都要能回答
 * 「什么时候、对哪张表、执行了什么、成没成、影响几行、花了多久」。
 */
@Data
public class StatementLog {

    private int seq;

    private String startedAt;

    /** 所属表 */
    private String table;

    /** 所属对象，如列名、索引名；用于把分布式 local/route 两条 SQL 归并成一个逻辑变更 */
    private String objectName;

    /** 差异类型，如 COLUMN_MISSING / SEED_ROW_DIFF */
    private String diffType;

    /** 档位 AUTO / CONFIRM */
    private String level;

    /** 动作归类：CREATE_TABLE / ADD_COLUMN / DROP_COLUMN / RENAME_COLUMN / CREATE_INDEX / INSERT / UPDATE / DELETE / COMMENT / OTHER */
    private String action;

    private String sql;

    /** OK / SKIPPED / FAILED */
    private String status;

    /** 执行前置检查结论：EXECUTE / SKIP_SATISFIED / SKIP_BLOCKED */
    private String pfcVerdict;

    /** DML 影响行数；DDL 为 -1 */
    private int rowsAffected = -1;

    private long costMs;

    private String error;

    /** 是否在事务外单独执行（CREATE INDEX CONCURRENTLY） */
    private boolean outsideTransaction;

    public String oneLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("#%-4d %-8s %-16s %-34s %6sms",
                seq, status, action, table, costMs));
        if (rowsAffected >= 0) {
            sb.append(String.format("  影响 %d 行", rowsAffected));
        }
        if (error != null) {
            sb.append("  错误: ").append(error);
        }
        return sb.toString();
    }
}
