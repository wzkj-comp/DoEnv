package com.doenv.schema.baseline;

import com.doenv.schema.model.ColumnModel;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 类型与默认值归一化。基准侧与现场侧必须共用同一套规则，否则会报出大量假差异。
 *
 * <p>基准文件写 {@code varchar(100)}，而 {@code format_type()} 返回
 * {@code character varying(100)}；基准写 {@code int4}，现场返回 {@code integer}。
 * 不归一化的话 2237 个列会几乎全部误判为「类型不一致」。
 */
public final class TypeNormalizer {

    private TypeNormalizer() {
    }

    /** 别名 -> 规范名 */
    private static final Map<String, String> ALIAS = Map.ofEntries(
            Map.entry("character varying", "varchar"),
            Map.entry("character", "bpchar"),
            Map.entry("integer", "int4"),
            Map.entry("int", "int4"),
            Map.entry("smallint", "int2"),
            Map.entry("bigint", "int8"),
            Map.entry("real", "float4"),
            Map.entry("double precision", "float8"),
            Map.entry("boolean", "bool"),
            Map.entry("timestamp without time zone", "timestamp"),
            Map.entry("timestamp with time zone", "timestamptz"),
            Map.entry("time without time zone", "time"),
            Map.entry("time with time zone", "timetz"),
            Map.entry("decimal", "numeric"),
            Map.entry("bit varying", "varbit")
    );

    /**
     * 修饰符括号可能出现在<b>中间</b>而不只是末尾 ——
     * {@code format_type()} 对时间类型返回的是 {@code timestamp(6) without time zone}，
     * 早期版本按「括号必须在末尾」来匹配，导致 300+ 个时间列全部解析失败、
     * 落进 OTHER 族，进而被误判成跨类型族变更。
     */
    private static final Pattern MODIFIER = Pattern.compile("\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)");

    /**
     * 把类型原文解析并写入 column 的 normType / typeFamily / length / precision / scale。
     */
    public static void apply(ColumnModel col, String rawType) {
        col.setRawType(rawType);
        String t = rawType == null ? "" : rawType.trim();

        // 去掉 COLLATE 子句（基准里 1400+ 个 varchar/text 列全带 COLLATE "pg_catalog"."default"）
        int collate = t.toLowerCase(Locale.ROOT).indexOf(" collate ");
        if (collate >= 0) {
            t = t.substring(0, collate).trim();
        }

        boolean array = t.endsWith("[]");
        if (array) {
            t = t.substring(0, t.length() - 2).trim();
        }

        // 先摘出修饰符（无论它在中间还是末尾），剩下的整串就是类型名
        Integer p1 = null;
        Integer p2 = null;
        Matcher m = MODIFIER.matcher(t);
        if (m.find()) {
            p1 = Integer.valueOf(m.group(1));
            p2 = m.group(2) == null ? null : Integer.valueOf(m.group(2));
            t = (t.substring(0, m.start()) + " " + t.substring(m.end())).trim();
        }

        String base = t.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (base.isEmpty()) {
            col.setNormType(rawType == null ? "" : rawType.toLowerCase(Locale.ROOT));
            col.setTypeFamily("OTHER");
            return;
        }
        base = ALIAS.getOrDefault(base, base);

        StringBuilder norm = new StringBuilder(base);
        switch (base) {
            case "varchar", "bpchar", "varbit", "bit" -> {
                col.setLength(p1);
                if (p1 != null) {
                    norm.append('(').append(p1).append(')');
                }
            }
            case "numeric" -> {
                col.setPrecision(p1);
                col.setScale(p2);
                if (p1 != null) {
                    norm.append('(').append(p1);
                    if (p2 != null) {
                        norm.append(',').append(p2);
                    }
                    norm.append(')');
                }
            }
            case "timestamp", "timestamptz", "time", "timetz" -> {
                // PG 的默认精度就是 6，format_type() 对 timestamp(6) 只输出 timestamp。
                // 基准文件写的是显式 timestamp(6)，若不把 6 归一成「无修饰符」，
                // 现场每一个时间列都会被误判成类型变更（实测 300+ 条假差异）。
                col.setPrecision(p1);
                if (p1 != null && p1 != 6) {
                    norm.append('(').append(p1).append(')');
                }
            }
            default -> {
                // 其余类型忽略修饰符
            }
        }
        if (array) {
            norm.append("[]");
        }

        col.setNormType(norm.toString());
        col.setTypeFamily(familyOf(base));
    }

    private static String familyOf(String base) {
        return switch (base) {
            case "varchar", "bpchar", "text", "name", "citext" -> "STRING";
            case "int2", "int4", "int8" -> "INT";
            case "float4", "float8" -> "FLOAT";
            case "numeric", "money" -> "DECIMAL";
            case "bool" -> "BOOL";
            case "date" -> "DATE";
            case "timestamp", "timestamptz" -> "DATETIME";
            case "time", "timetz" -> "TIME";
            case "bytea" -> "BINARY";
            default -> "OTHER";
        };
    }

    /**
     * DEFAULT 归一化。基准里同一语义有两种写法：
     * {@code CURRENT_TIMESTAMP} 与 {@code "pg_catalog"."current_timestamp"()}，
     * 不归一化会误报差异。
     */
    public static String normalizeDefault(String raw) {
        if (raw == null) {
            return null;
        }
        String d = raw.trim();
        if (d.isEmpty()) {
            return null;
        }
        // 去掉显式类型标注：'0'::character varying -> '0'
        d = d.replaceAll("::\\s*\"?[a-zA-Z_][a-zA-Z0-9_ ]*\"?(\\(\\d+(,\\d+)?\\))?", "");
        d = d.replace("\"pg_catalog\".", "").replace("pg_catalog.", "");
        d = d.replaceAll("\\s+", " ").trim();

        String upper = d.toUpperCase(Locale.ROOT);
        if (upper.equals("CURRENT_TIMESTAMP()") || upper.equals("\"CURRENT_TIMESTAMP\"()")
                || upper.equals("NOW()") || upper.equals("CURRENT_TIMESTAMP")) {
            return "CURRENT_TIMESTAMP";
        }
        if (upper.equals("TRUE") || upper.equals("FALSE")) {
            return upper.toLowerCase(Locale.ROOT);
        }
        if (upper.equals("NULL")) {
            return null;
        }
        return d;
    }
}
