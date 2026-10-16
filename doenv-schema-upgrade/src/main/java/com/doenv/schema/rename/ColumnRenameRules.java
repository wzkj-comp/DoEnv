package com.doenv.schema.rename;

import java.util.List;

/** 字段改名清单查询。表名和列名按数据库标识符语义做精确匹配。 */
public final class ColumnRenameRules {

    private ColumnRenameRules() {
    }

    public static ColumnRenameRule byTarget(List<ColumnRenameRule> rules,
                                            String table, String targetColumn) {
        if (rules == null) {
            return null;
        }
        return rules.stream()
                .filter(r -> eq(r.getTable(), table) && eq(r.getTo(), targetColumn))
                .findFirst().orElse(null);
    }

    public static ColumnRenameRule bySource(List<ColumnRenameRule> rules,
                                            String table, String sourceColumn) {
        if (rules == null) {
            return null;
        }
        return rules.stream()
                .filter(r -> eq(r.getTable(), table) && eq(r.getFrom(), sourceColumn))
                .findFirst().orElse(null);
    }

    private static boolean eq(String left, String right) {
        return left != null && left.equals(right);
    }
}
