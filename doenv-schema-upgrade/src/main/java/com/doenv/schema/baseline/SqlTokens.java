package com.doenv.schema.baseline;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 片段的引号感知工具：顶层切分、括号定位、标识符/字面量解引号。
 */
public final class SqlTokens {

    private SqlTokens() {
    }

    /**
     * 按顶层分隔符切分（忽略括号内、引号内的分隔符）。
     */
    public static List<String> splitTopLevel(String s, char delim) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int depth = 0;
        int i = 0;
        final int n = s.length();

        while (i < n) {
            char c = s.charAt(i);

            if (c == '\'') {
                boolean esc = i > 0 && (s.charAt(i - 1) == 'e' || s.charAt(i - 1) == 'E');
                int j = i + 1;
                buf.append(c);
                while (j < n) {
                    char ch = s.charAt(j);
                    if (esc && ch == '\\' && j + 1 < n) {
                        buf.append(ch).append(s.charAt(j + 1));
                        j += 2;
                        continue;
                    }
                    if (ch == '\'') {
                        if (j + 1 < n && s.charAt(j + 1) == '\'') {
                            buf.append("''");
                            j += 2;
                            continue;
                        }
                        buf.append(ch);
                        j++;
                        break;
                    }
                    buf.append(ch);
                    j++;
                }
                i = j;
                continue;
            }

            if (c == '"') {
                int j = i + 1;
                buf.append(c);
                while (j < n) {
                    char ch = s.charAt(j);
                    buf.append(ch);
                    j++;
                    if (ch == '"') {
                        break;
                    }
                }
                i = j;
                continue;
            }

            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == delim && depth == 0) {
                out.add(buf.toString().trim());
                buf.setLength(0);
                i++;
                continue;
            }

            buf.append(c);
            i++;
        }
        out.add(buf.toString().trim());
        return out;
    }

    /**
     * 找到 from 位置起第一个 '(' 对应的 ')' 下标；找不到返回 -1。
     */
    public static int matchParen(String s, int openIdx) {
        int depth = 0;
        int i = openIdx;
        final int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\'') {
                boolean esc = i > 0 && (s.charAt(i - 1) == 'e' || s.charAt(i - 1) == 'E');
                i++;
                while (i < n) {
                    char ch = s.charAt(i);
                    if (esc && ch == '\\') {
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
            if (c == '"') {
                i++;
                while (i < n && s.charAt(i) != '"') {
                    i++;
                }
                i++;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    /** 去掉标识符两侧的双引号，还原 "" 转义 */
    public static String unquoteIdent(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            return t.substring(1, t.length() - 1).replace("\"\"", "\"");
        }
        return t;
    }

    /**
     * 把 SQL 字面量还原成 Java 值。NULL 返回 null。
     * 处理 '...'、E'...'、以及 ''/反斜杠转义。
     */
    public static String unquoteLiteral(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.equalsIgnoreCase("NULL") || t.isEmpty()) {
            return null;
        }
        boolean escapeString = false;
        if (t.length() >= 2 && (t.charAt(0) == 'e' || t.charAt(0) == 'E') && t.charAt(1) == '\'') {
            escapeString = true;
            t = t.substring(1);
        }
        if (t.length() >= 2 && t.charAt(0) == '\'' && t.charAt(t.length() - 1) == '\'') {
            String body = t.substring(1, t.length() - 1);
            body = body.replace("''", "'");
            if (escapeString) {
                body = unescapeBackslash(body);
            }
            return body;
        }
        return t;
    }

    private static String unescapeBackslash(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char nx = s.charAt(++i);
                switch (nx) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '\\' -> sb.append('\\');
                    case '\'' -> sb.append('\'');
                    default -> sb.append('\\').append(nx);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 取表名：支持 "public"."t" / public.t / "t" / t */
    public static String tableNameOf(String qualified) {
        List<String> parts = splitTopLevel(qualified.trim(), '.');
        return unquoteIdent(parts.get(parts.size() - 1));
    }
}
