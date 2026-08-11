package com.doenv.schema.model;

import lombok.Data;

import java.util.Locale;

/**
 * ClickHouse 的跳数索引（data skipping index）。
 *
 * <p>语法：{@code INDEX <名字> <表达式> TYPE <类型> GRANULARITY <粒度>}，
 * 写在建表语句的列定义区里，也可以事后 {@code ALTER TABLE ... ADD INDEX} 补。
 *
 * <p>补索引是<b>纯元数据操作</b>，秒级返回，不重写数据、不需要重建表 ——
 * 这是它和 ENGINE / PARTITION BY / ORDER BY 那些不可变属性的根本区别。
 * 唯一的代价在于：新加的索引只对之后写入的数据生效，存量数据要
 * {@code MATERIALIZE INDEX} 才会真正建出索引文件，而那是要扫全表的重操作。
 */
@Data
public class CkIndexModel {

    private String name;

    /** 索引表达式原文，如 {@code caseid} 或 {@code (CaseID, ItemType, ItemID)} */
    private String expression;

    /** 索引类型，如 minmax / set(100) / bloom_filter */
    private String type;

    /** 粒度。缺省按 CK 默认值 1 */
    private int granularity = 1;

    /**
     * 比对用的归一化表达式。
     *
     * <p>基准写的是 SQL 原文，现场读回来的是 CK 规范化之后的形式，
     * 括号、空格、大小写都可能不同（{@code (CaseID, ItemType)} vs {@code CaseID, ItemType}）。
     * 不归一就会永远判为「不一致」，每轮都想改它却又改不掉。
     */
    public String normalizedExpression() {
        if (expression == null) {
            return "";
        }
        String e = expression.trim();
        while (e.startsWith("(") && e.endsWith(")")) {
            e = e.substring(1, e.length() - 1).trim();
        }
        return e.replaceAll("\\s+", "").replace("`", "").toLowerCase(Locale.ROOT);
    }

    /** 类型也归一：CK 会把 {@code minmax} 原样返回，但空格与大小写不保证 */
    public String normalizedType() {
        return type == null ? "" : type.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
