package com.doenv.schema.baseline;

import java.util.List;
import java.util.Locale;

/**
 * ClickHouse 侧的标识符与类型工具。
 */
public final class CkTokens {

    private CkTokens() {
    }

    /** 去掉反引号；<b>大小写原样保留</b> —— CK 标识符是大小写敏感的 */
    public static String unquote(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() >= 2 && t.charAt(0) == '`' && t.charAt(t.length() - 1) == '`') {
            return t.substring(1, t.length() - 1);
        }
        // 可能带库名前缀，取最后一段（基准里不写库名，这里只是兜底）
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }

    /**
     * 顶层关键字查找，跳过引号与括号内的内容。
     * 与 PG 那份的区别是要额外识别反引号。
     */
    public static int indexOfKeyword(String s, String keyword) {
        String u = s.toUpperCase(Locale.ROOT);
        String k = keyword.toUpperCase(Locale.ROOT);
        int depth = 0;
        int i = 0;
        final int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\'') {
                i++;
                while (i < n) {
                    char ch = s.charAt(i);
                    if (ch == '\\') {
                        i += 2;
                        continue;
                    }
                    if (ch == '\'') {
                        if (i + 1 < n && s.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '`') {
                i++;
                while (i < n && s.charAt(i) != '`') {
                    i++;
                }
                i++;
                continue;
            }
            if (c == '(') {
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                depth--;
                i++;
                continue;
            }
            if (depth == 0 && u.startsWith(k, i)) {
                boolean leftOk = (i == 0) || !isIdentChar(s.charAt(i - 1));
                int after = i + k.length();
                boolean rightOk = (after >= n) || !isIdentChar(s.charAt(after));
                if (leftOk && rightOk) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * 解析 ORDER BY / PRIMARY KEY 的键清单。
     * 两种写法：{@code ORDER BY id} 和 {@code ORDER BY (a, b, c)}。
     */
    public static List<String> keyList(String expr) {
        if (expr == null) {
            return List.of();
        }
        String t = expr.trim();
        if (t.isEmpty()) {
            return List.of();
        }
        if (t.startsWith("(")) {
            int close = SqlTokens.matchParen(t, 0);
            if (close > 0) {
                t = t.substring(1, close);
            }
        }
        return SqlTokens.splitTopLevel(t, ',').stream()
                .map(String::trim)
                .map(CkTokens::unquote)
                .filter(x -> !x.isEmpty())
                .toList();
    }

    /**
     * 类型归一化。只做空白折叠 —— CK 的类型系统里
     * {@code Nullable(String)} 与 {@code String} 是真的不同类型，不能像 PG 那样归并。
     */
    public static String normalizeType(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.replaceAll("\\s+", "").trim();
        // Decimal 的 scale 可省，CK 存下来时会补成 0：基准写 Decimal(38)，
        // 回读是 Decimal(38,0)。不补齐就会每轮生成同一条 MODIFY COLUMN 却永远改不掉 ——
        // 实测 vax_swap_bridges 的 fromamount/toamount 就卡在这里
        return t.replaceAll("(?i)\\bDecimal\\((\\d+)\\)", "Decimal($1,0)");
    }

    /** 粗粒度类型族，用于判断变更是放宽还是收窄 */
    public static String familyOf(String raw) {
        if (raw == null) {
            return "OTHER";
        }
        String t = raw.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        // 剥掉包装类型再判断
        while (t.startsWith("NULLABLE(") || t.startsWith("LOWCARDINALITY(")) {
            int p = t.indexOf('(');
            int q = SqlTokens.matchParen(t, p);
            if (q < 0) {
                break;
            }
            t = t.substring(p + 1, q);
        }
        if (t.startsWith("STRING") || t.startsWith("FIXEDSTRING") || t.startsWith("UUID")) {
            return "STRING";
        }
        if (t.startsWith("INT") || t.startsWith("UINT")) {
            return "INT";
        }
        if (t.startsWith("FLOAT")) {
            return "FLOAT";
        }
        if (t.startsWith("DECIMAL")) {
            return "DECIMAL";
        }
        if (t.startsWith("BOOL")) {
            return "BOOL";
        }
        if (t.startsWith("DATETIME")) {
            return "DATETIME";
        }
        if (t.startsWith("DATE")) {
            return "DATE";
        }
        if (t.startsWith("ARRAY")) {
            return "ARRAY";
        }
        if (t.startsWith("MAP")) {
            return "MAP";
        }
        return "OTHER";
    }

    /**
     * CK 字符串字面量解引号。
     *
     * <p>与 PG 的关键差别：<b>ClickHouse 的普通 {@code '...'} 字面量默认就解释反斜杠转义</b>，
     * 不需要 PG 那样的 {@code E'...'} 前缀。基准里的注释写成 {@code '...0=user,\r\n...'}，
     * 存进 CK 是真的 CR+LF；若按 PG 规则原样保留反斜杠，读回来永远和基准对不上，
     * 会导致这一列的注释差异每轮都重新出现、永不收敛。
     */
    public static String unquoteLiteral(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() < 2 || t.charAt(0) != '\'' || t.charAt(t.length() - 1) != '\'') {
            return t;
        }
        String body = t.substring(1, t.length() - 1);
        StringBuilder sb = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char nx = body.charAt(++i);
                switch (nx) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '0' -> sb.append('\0');
                    case '\\' -> sb.append('\\');
                    case '\'' -> sb.append('\'');
                    default -> sb.append(nx);
                }
            } else if (c == '\'' && i + 1 < body.length() && body.charAt(i + 1) == '\'') {
                sb.append('\'');
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 标识符渲染：CK 用反引号 */
    public static String ident(String name) {
        return '`' + name.replace("`", "\\`") + '`';
    }
}
