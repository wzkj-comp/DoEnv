package com.doenv.schema.model;

import lombok.Data;

/**
 * 一个列的结构定义。基准侧与现场侧共用，diff 只比较归一化后的字段。
 */
@Data
public class ColumnModel {

    /** 列名，保留原始大小写（PG 里存在 vao_valueObject 这类驼峰标识符） */
    private String name;

    /** 序号，1 起。仅作展示，不参与 diff */
    private int position;

    /** 源文件/现场的类型原文，如 varchar(100)、character varying(100) */
    private String rawType;

    /** 归一化类型，diff 的唯一依据。如 varchar(100) */
    private String normType;

    /** 类型族：STRING / INT / FLOAT / DECIMAL / BOOL / DATE / DATETIME / TIME / BINARY / OTHER */
    private String typeFamily;

    private Integer length;
    private Integer precision;
    private Integer scale;

    private boolean notNull;

    /** DEFAULT 原文 */
    private String defaultRaw;

    /** 归一化后的 DEFAULT。CURRENT_TIMESTAMP 与 "pg_catalog"."current_timestamp"() 会归一成同一个值 */
    private String defaultNorm;

    private String comment;

    /**
     * 生成 DDL 时用的类型。
     *
     * <p>用原文而不是 {@link #normType} —— 归一化会把 {@code timestamp(6)} 折成
     * {@code timestamp}（6 是 PG 默认精度，两者行为完全一致），但建出来的列 typmod 是 -1，
     * 与基准的 6 在系统目录里不同，拿 Navicat 之类的工具比会显示成差异。
     * 归一化只服务于比对，落库要忠实于基准原文。
     */
    public String ddlType() {
        return (rawType != null && !rawType.isBlank()) ? rawType.trim() : normType;
    }

    /** 该列是否可以在 ADD COLUMN 时直接带上 NOT NULL（要求有非 volatile 常量默认值） */
    public boolean canAddWithNotNull() {
        if (!notNull || defaultNorm == null || defaultNorm.isEmpty()) {
            return false;
        }
        // CURRENT_TIMESTAMP 属于 volatile，存量行回填语义不确定，不走这条路
        return !defaultNorm.toUpperCase().contains("CURRENT_TIMESTAMP");
    }
}
