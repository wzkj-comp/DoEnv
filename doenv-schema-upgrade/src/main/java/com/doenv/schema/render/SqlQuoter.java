package com.doenv.schema.render;

/**
 * 标识符与字面量的引号处理。
 *
 * <p>标识符一律加双引号 —— PG 未加引号的标识符会折叠成小写，
 * 而基准里存在 {@code vao_valueObject} 这样的驼峰表名，不加引号会建出另一张表。
 */
public final class SqlQuoter {

    private SqlQuoter() {
    }

    /** 标识符加引号：a"b -> "a""b" */
    public static String ident(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /** schema 限定的表名，如 "public"."vao_case" */
    public static String table(String schema, String tableName) {
        return ident(schema) + "." + ident(tableName);
    }

    /** 字符串字面量：a'b -> 'a''b'；null -> NULL */
    public static String literal(String value) {
        if (value == null) {
            return "NULL";
        }
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * 给 DO $$ 块里的字符串再套一层引号。
     * DO 块本身是一个字符串字面量，里面的单引号要按 $$ 定界规避，
     * 所以这里统一用 $do$ 作为定界符，避免与内容里的 $$ 冲突。
     */
    public static String doBlock(String body) {
        String tag = "$do$";
        if (body.contains(tag)) {
            tag = "$doenv$";
        }
        return "DO " + tag + "\nBEGIN\n" + body + "\nEND\n" + tag + ";";
    }
}
