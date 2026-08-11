package com.doenv.schema.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一张表的结构定义。
 */
@Data
public class TableModel {

    /** 表名，保留原始大小写 */
    private String name;

    private String comment;

    private List<ColumnModel> columns = new ArrayList<>();

    private List<IndexModel> indexes = new ArrayList<>();

    private List<ConstraintModel> constraints = new ArrayList<>();

    /** ClickHouse 引擎层定义。PG 表为 null */
    private CkTableModel ck;

    /**
     * 分布式现场专用：路由表（Distributed）是否存在。
     * 只记存在性不够——列集也要单独比，本地表和路由表的列必须成对，
     * 漏一张查询就报「列不存在」。
     */
    private boolean distRouteExists;

    /** 分布式现场里路由表的列名集合，用于检测 local/route 列漂移 */
    private java.util.Set<String> routeColumns;

    /** 列名（小写） -> 列，懒建 */
    private transient Map<String, ColumnModel> columnIndex;

    /**
     * 按列名取列。PG 未加引号的标识符会折叠成小写，比对时统一走忽略大小写匹配；
     * 真正的大小写敏感场景（vao_valueObject）由 SqlQuoter 在渲染时保证带引号。
     */
    public ColumnModel findColumn(String columnName) {
        if (columnIndex == null) {
            Map<String, ColumnModel> m = new LinkedHashMap<>();
            for (ColumnModel c : columns) {
                m.put(c.getName().toLowerCase(), c);
            }
            columnIndex = m;
        }
        return columnIndex.get(columnName == null ? null : columnName.toLowerCase());
    }

    public void addColumn(ColumnModel c) {
        columns.add(c);
        columnIndex = null;
    }
}
