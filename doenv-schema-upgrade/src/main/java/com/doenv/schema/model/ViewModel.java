package com.doenv.schema.model;

import lombok.Data;

import java.util.Locale;

/**
 * 视图。
 *
 * <p>视图没有引擎、分区键、排序键，也没有数据 —— 和表完全不是一类东西，
 * 所以单独建模而不是塞进 {@link TableModel}。补视图是纯元数据操作，
 * 不重建表、不动一行数据。
 *
 * <p>只保留 {@code AS} 之后的查询体，不保留 CK 回读时补上的列清单。
 * 因为业务视图多用 {@code SELECT * EXCEPT (...)} 写法，跟着源表走才是本意；
 * 钉死列清单反而会在源表加列后与视图脱节。
 */
@Data
public class ViewModel {

    private String name;

    /** {@code AS} 之后的查询体原文 */
    private String query;

    /** 物化视图。CK 的 MaterializedView 自带存储，处理方式与普通视图不同 */
    private boolean materialized;

    /**
     * 比对用的归一化查询体。
     *
     * <p>基准是 SQL 原文，现场是 CK 规范化后的 {@code create_table_query}，
     * 两者的空白、换行、库名限定都不一样。不归一就会永远判为「定义不同」，
     * 每轮都想改它却又改不掉 —— 索引比对已经在这上面栽过一次。
     */
    public String normalizedQuery() {
        if (query == null) {
            return "";
        }
        return query.replaceAll("\\s+", " ")
                .replace("`", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
