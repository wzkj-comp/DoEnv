package com.doenv.schema.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ClickHouse 表的引擎层定义。挂在 {@link TableModel#getCk()} 上。
 *
 * <p>这里保存的是<b>逻辑层</b>定义 —— 不含 {@code Replicated} 前缀、ZooKeeper 路径、
 * {@code _local} 后缀、{@code ON CLUSTER}。分布式形态由渲染器在运行时推导，
 * 基准只维护单机一份。已实测：分布式纯净库 503 张表可由单机版机械生成，逐字节一致。
 */
@Data
public class CkTableModel {

    /** 引擎族，如 MergeTree / ReplacingMergeTree */
    private String engineFamily;

    /** 引擎参数，如 ReplacingMergeTree(ver) 里的 ver */
    private List<String> engineArgs = List.of();

    /** PARTITION BY 表达式原文；无分区为 null */
    private String partitionBy;

    /** ORDER BY 的列/表达式清单 */
    private List<String> orderBy = List.of();

    /** PRIMARY KEY 清单。CK 要求它必须是 ORDER BY 的前缀 */
    private List<String> primaryKey = List.of();

    private String sampleBy;

    /** TTL 表达式原文 */
    private String ttl;

    /** SETTINGS，如 index_granularity=8192 */
    private Map<String, String> settings = new LinkedHashMap<>();

    /** 跳数索引。补索引是元数据操作，不涉及重建表 */
    private List<CkIndexModel> indexes = new ArrayList<>();

    /**
     * 分片键。只在<b>新建</b>分布式表时用，已存在的表永不重算 ——
     * 分片键写死在 Distributed 引擎参数里，改它等于重建路由表并重分布数据。
     */
    private String shardKey;

    /** 分片键退化为 rand() 时置位，需人工确认（Replacing 引擎跨分片无法去重） */
    private boolean shardKeyNeedsReview;

    /**
     * 这些是 CK 建表后<b>不可 ALTER</b> 的部分。差异只能报告，不能自动改 ——
     * 只能新建表 + INSERT SELECT + RENAME。
     */
    public String immutableSignature() {
        return engineFamily + "(" + String.join(",", engineArgs) + ")"
                + "|PARTITION=" + (partitionBy == null ? "" : partitionBy)
                + "|ORDER=" + String.join(",", orderBy)
                + "|PK=" + String.join(",", primaryKey);
    }
}
