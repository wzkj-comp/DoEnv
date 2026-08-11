package com.doenv.schema.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 索引定义。
 *
 * <p>比对按 {@link #getFingerprint()} 而不是索引名 —— 基准里的索引命名极不规范
 * （useId_index 驼峰、unid、index_user_token 混用），同义异名会被误判为缺失并重复创建。
 */
@Data
public class IndexModel {

    private String name;
    private boolean unique;

    /** 索引方法，PG 默认 btree */
    private String method = "btree";

    private List<IndexColumn> columns = new ArrayList<>();

    /**
     * 部分索引谓词的<b>原文</b>。渲染 SQL 时只能用这个。
     */
    private String whereRaw = "";

    /**
     * 部分索引谓词的归一化形式，<b>仅供指纹比对</b>。
     * 它去掉了全部空格和括号，直接拿去渲染会生成无效 SQL。
     */
    private String whereNorm = "";

    @Data
    public static class IndexColumn {
        private String name;
        private boolean desc;
        private boolean nullsFirst;

        public IndexColumn() {
        }

        public IndexColumn(String name, boolean desc, boolean nullsFirst) {
            this.name = name;
            this.desc = desc;
            this.nullsFirst = nullsFirst;
        }
    }

    /**
     * 等价签名：method | 列序列(名:排序:NULLS) | U0/U1 | 归一化谓词。
     * 含 method 是必要的 —— 同列上的 gin/hash 索引与 btree 索引不等价，
     * 漏掉会导致该建的 btree 索引被跳过，造成静默的查询性能退化。
     */
    public String getFingerprint() {
        String cols = columns.stream()
                .map(c -> c.getName() + ":" + (c.isDesc() ? "D" : "A") + ":" + (c.isNullsFirst() ? "F" : "L"))
                .collect(Collectors.joining(","));
        return (method == null ? "btree" : method.toLowerCase())
                + "|" + cols
                + "|" + (unique ? "U1" : "U0")
                + "|" + (whereNorm == null ? "" : whereNorm);
    }
}
