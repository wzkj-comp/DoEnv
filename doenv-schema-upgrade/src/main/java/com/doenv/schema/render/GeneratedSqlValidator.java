package com.doenv.schema.render;

import com.doenv.schema.diff.DiffItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 生成结果自检。<b>不可关闭</b> —— 这是防止工具自身出 bug 把现场搞坏的最后一道闸。
 *
 * <p>任一断言不通过就把整份报告标红并禁止执行，宁可什么都不做。
 */
public final class GeneratedSqlValidator {

    private GeneratedSqlValidator() {
    }

    public static List<String> validate(List<DiffItem> diffs, String schema) {
        List<String> errors = new ArrayList<>();

        for (DiffItem d : diffs) {
            List<String> ddl = d.getDdl();
            for (int i = 0; i < ddl.size(); i++) {
                String sql = ddl.get(i);
                String u = sql.toUpperCase(Locale.ROOT);
                // 整表重建的豁免只给「第一条、且精确是无条件清空」那一条。
                // 放宽到整个 DiffItem 的话，将来往 SEED_REBUILD 里加语句会被静默豁免
                boolean rebuildWipe = i == 0
                        && d.getType() == com.doenv.schema.diff.DiffType.SEED_REBUILD
                        && u.trim().matches("DELETE FROM \\S+;");

                if (u.contains("DROP TABLE")) {
                    errors.add(d.getKey() + "：出现 DROP TABLE");
                }
                if (u.contains("DROP COLUMN")) {
                    errors.add(d.getKey() + "：出现 DROP COLUMN");
                }
                if (u.contains("TRUNCATE")) {
                    errors.add(d.getKey() + "：出现 TRUNCATE");
                }
                if (u.contains("DROP DATABASE") || u.contains("DROP SCHEMA")) {
                    errors.add(d.getKey() + "：出现 DROP DATABASE/SCHEMA");
                }
                if (u.contains("OWNER TO")) {
                    errors.add(d.getKey() + "：出现 OWNER TO（现场角色与基准不同）");
                }
                // DELETE 必须带 WHERE —— 基准原文里那 170 段无条件 DELETE 是数据全灭。
                // 唯一例外是元数据表的整表重建：它的语义就是「这张表整个以基准为准」，
                // 清空是本意而非事故。只认 DiffType，不靠猜 SQL 文本
                int del = u.indexOf("DELETE FROM");
                if (del >= 0 && !u.substring(del).contains("WHERE") && !rebuildWipe) {
                    errors.add(d.getKey() + "：出现无 WHERE 的 DELETE");
                }
                // 表引用必须带 schema 限定符
                if ((u.startsWith("ALTER TABLE ") || u.startsWith("CREATE TABLE ")
                        || u.startsWith("INSERT INTO ") || u.startsWith("UPDATE ")
                        || u.startsWith("DELETE FROM ") || u.startsWith("COMMENT ON "))
                        && !sql.contains('"' + schema + "\".")) {
                    errors.add(d.getKey() + "：语句缺少 schema 限定符 -> " + head(sql));
                }
                // 加列不得带 NOT NULL 而无默认值（存量行无法回填）
                if (u.contains("ADD COLUMN") && u.contains("NOT NULL") && !u.contains("DEFAULT")) {
                    errors.add(d.getKey() + "：ADD COLUMN 带 NOT NULL 但无默认值");
                }
            }

            // 现场多出的对象永远不该生成 SQL
            switch (d.getType()) {
                case TABLE_EXTRA, COLUMN_EXTRA, INDEX_EXTRA, SEED_ROW_EXTRA -> {
                    if (!d.getDdl().isEmpty()) {
                        errors.add(d.getKey() + "：现场多出的对象不应生成任何 SQL");
                    }
                }
                default -> {
                }
            }
        }
        return errors;
    }

    private static String head(String s) {
        String one = s.replaceAll("\\s+", " ");
        return one.length() > 120 ? one.substring(0, 120) + "…" : one;
    }
}
