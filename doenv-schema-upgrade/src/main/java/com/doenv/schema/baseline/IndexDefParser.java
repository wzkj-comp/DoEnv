package com.doenv.schema.baseline;

import com.doenv.schema.model.IndexModel;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 {@code CREATE [UNIQUE] INDEX ... ON ... USING ... (...) [WHERE ...]}。
 *
 * <p><b>基准侧与现场侧共用本类</b>：现场结构从 {@code pg_get_indexdef()} 拿到的
 * 也是同样形态的 CREATE INDEX 语句，两边走同一套归一化，指纹才能对得上。
 * 各写一套的话，opclass、{@code ASC NULLS LAST} 这类 PG 默认值的处理差异
 * 会让每一条索引都变成假差异。
 */
public final class IndexDefParser {

    private IndexDefParser() {
    }

    private static final Pattern HEAD = Pattern.compile(
            "^CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(?:CONCURRENTLY\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                    + "(\"[^\"]+\"|[A-Za-z0-9_]+)\\s+ON\\s+(?:ONLY\\s+)?([^\\s(]+)\\s*"
                    + "(?:USING\\s+([A-Za-z0-9_]+)\\s*)?\\(",
            Pattern.CASE_INSENSITIVE);

    /** 解析结果：索引所属表名 + 索引模型 */
    public record Parsed(String tableName, IndexModel index) {
    }

    public static Parsed parse(String stmt) {
        Matcher m = HEAD.matcher(stmt.trim());
        if (!m.find()) {
            return null;
        }
        String idxName = SqlTokens.unquoteIdent(m.group(2));
        String tableName = SqlTokens.tableNameOf(m.group(3));
        String method = m.group(4) == null ? "btree" : m.group(4).toLowerCase(Locale.ROOT);

        int open = stmt.indexOf('(', m.end() - 1);
        int close = SqlTokens.matchParen(stmt, open);
        if (close < 0) {
            return null;
        }

        IndexModel idx = new IndexModel();
        idx.setName(idxName);
        idx.setUnique(m.group(1) != null);
        idx.setMethod(method);

        for (String colExpr : SqlTokens.splitTopLevel(stmt.substring(open + 1, close), ',')) {
            String e = colExpr.trim();
            if (e.isEmpty()) {
                continue;
            }
            String eu = e.toUpperCase(Locale.ROOT);
            boolean desc = containsWord(eu, "DESC");
            // PG 默认：ASC -> NULLS LAST，DESC -> NULLS FIRST。
            // pg_get_indexdef 只会输出非默认值，所以这里必须按默认值补全，
            // 否则基准写了 NULLS FIRST 而现场没输出，就会算成不同指纹
            boolean nullsFirst = containsWord(eu, "NULLS FIRST")
                    || (desc && !containsWord(eu, "NULLS LAST"));
            String colName = e.split("\\s+")[0];
            idx.getColumns().add(new IndexModel.IndexColumn(
                    SqlTokens.unquoteIdent(colName), desc, nullsFirst));
        }

        String tail = stmt.substring(close + 1);
        int whereIdx = PgBaselineParser.indexOfKeyword(tail, "WHERE");
        if (whereIdx >= 0) {
            String raw = tail.substring(whereIdx + "WHERE".length()).trim();
            if (raw.endsWith(";")) {
                raw = raw.substring(0, raw.length() - 1).trim();
            }
            idx.setWhereRaw(raw);                       // 渲染用
            idx.setWhereNorm(normalizePredicate(raw));  // 比对用
        }
        return new Parsed(tableName, idx);
    }

    /**
     * 谓词 / 表达式归一化，用于<b>相等性比较</b>（不用于求值）。
     *
     * <p>基准是人手写的 {@code recipient_id is not null and is_read = false}，
     * 而 {@code pg_get_indexdef} / {@code pg_get_constraintdef} 输出的是
     * {@code ((recipient_id IS NOT NULL) AND (is_read = false))} —— 括号层级、
     * 空格、类型标注全都不同。实测不抹平这些差异，现存的索引与 CHECK 约束
     * 会被全部误判成缺失。
     *
     * <p>归一化会去掉全部括号。代价是理论上
     * {@code (a or b) and c} 与 {@code a or (b and c)} 会被认为相同 ——
     * 这类结构在配置型谓词里不会出现，且后果只是漏报一条需人工复核的差异，
     * 远小于「几百条假差异把真差异淹没」的代价。
     */
    public static String normalizeExpression(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw;
        // 去掉类型标注：'ENTRUST'::character varying -> 'ENTRUST'
        s = s.replaceAll("::\\s*\"?[a-zA-Z_][a-zA-Z0-9_ ]*\"?(\\(\\d+(,\\d+)?\\))?(\\[\\])?", "");
        s = s.replace("\"", "");
        s = s.replace("(", "").replace(")", "");
        s = s.replaceAll("\\s+", "");
        return s.toLowerCase(Locale.ROOT);
    }

    static String normalizePredicate(String raw) {
        return normalizeExpression(raw);
    }

    private static boolean containsWord(String upperText, String word) {
        int i = upperText.indexOf(word);
        while (i >= 0) {
            boolean leftOk = i == 0 || !Character.isLetterOrDigit(upperText.charAt(i - 1));
            int after = i + word.length();
            boolean rightOk = after >= upperText.length()
                    || !Character.isLetterOrDigit(upperText.charAt(after));
            if (leftOk && rightOk) {
                return true;
            }
            i = upperText.indexOf(word, i + 1);
        }
        return false;
    }
}
