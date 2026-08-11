package com.doenv.schema.baseline;

import java.util.ArrayList;
import java.util.List;

/**
 * 引号感知的 SQL 语句切分器。
 *
 * <p>纯净库文件（16MB / 13000 行）里实测存在的坑，本类逐条处理：
 * <ul>
 *   <li>单引号字符串里含分号、{@code --}、{@code /*}</li>
 *   <li>{@code ''} 形式的引号转义</li>
 *   <li>{@code E'...'} / {@code e'...'} 里的反斜杠转义，且值内含真实换行
 *       （3215-3216 行的 vao_lab_verify_config_item 就是跨行的）</li>
 *   <li>{@code "双引号标识符"} 里含特殊字符</li>
 *   <li>{@code $$ ... $$} / {@code $BODY$ ... $BODY$} 美元引号（CREATE FUNCTION 用）</li>
 *   <li>单条 INSERT 跨数千行、单值可达 MB 级</li>
 * </ul>
 */
public final class SqlStatementSplitter {

    private SqlStatementSplitter() {
    }

    public static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int i = 0;
        final int n = text.length();

        while (i < n) {
            char c = text.charAt(i);
            char nx = (i + 1 < n) ? text.charAt(i + 1) : '\0';

            // -- 行注释
            if (c == '-' && nx == '-') {
                int end = text.indexOf('\n', i);
                if (end < 0) {
                    end = n;
                }
                buf.append(text, i, end);
                i = end;
                continue;
            }

            // /* */ 块注释（PG 支持嵌套）
            if (c == '/' && nx == '*') {
                int depth = 0;
                int j = i;
                while (j < n) {
                    if (text.charAt(j) == '/' && j + 1 < n && text.charAt(j + 1) == '*') {
                        depth++;
                        j += 2;
                    } else if (text.charAt(j) == '*' && j + 1 < n && text.charAt(j + 1) == '/') {
                        depth--;
                        j += 2;
                        if (depth == 0) {
                            break;
                        }
                    } else {
                        j++;
                    }
                }
                buf.append(text, i, Math.min(j, n));
                i = j;
                continue;
            }

            // 单引号字符串；前一个非空白字符是 e/E 则按 escape string 处理反斜杠
            if (c == '\'') {
                boolean escapeString = isEscapeStringPrefix(buf);
                int j = i + 1;
                buf.append(c);
                while (j < n) {
                    char ch = text.charAt(j);
                    if (escapeString && ch == '\\' && j + 1 < n) {
                        buf.append(ch).append(text.charAt(j + 1));
                        j += 2;
                        continue;
                    }
                    if (ch == '\'') {
                        if (j + 1 < n && text.charAt(j + 1) == '\'') {   // '' 转义
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

            // `反引号标识符` —— ClickHouse 用它，PG 不用，加上无副作用
            if (c == '`') {
                int j = i + 1;
                buf.append(c);
                while (j < n) {
                    char ch = text.charAt(j);
                    buf.append(ch);
                    j++;
                    if (ch == '`') {
                        break;
                    }
                }
                i = j;
                continue;
            }

            // "双引号标识符"
            if (c == '"') {
                int j = i + 1;
                buf.append(c);
                while (j < n) {
                    char ch = text.charAt(j);
                    buf.append(ch);
                    j++;
                    if (ch == '"') {
                        if (j < n && text.charAt(j) == '"') {   // "" 转义
                            buf.append('"');
                            j++;
                            continue;
                        }
                        break;
                    }
                }
                i = j;
                continue;
            }

            // $tag$ ... $tag$ 美元引号
            if (c == '$') {
                String tag = readDollarTag(text, i);
                if (tag != null) {
                    int close = text.indexOf(tag, i + tag.length());
                    int end = (close < 0) ? n : close + tag.length();
                    buf.append(text, i, end);
                    i = end;
                    continue;
                }
            }

            if (c == ';') {
                String s = buf.toString().trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
                buf.setLength(0);
                i++;
                continue;
            }

            buf.append(c);
            i++;
        }

        String tail = buf.toString().trim();
        if (!tail.isEmpty()) {
            out.add(tail);
        }
        return out;
    }

    /** 判断刚写入的内容是否以 e/E 结尾（且 e 前不是标识符字符），即 E'...' 形态 */
    private static boolean isEscapeStringPrefix(StringBuilder buf) {
        if (buf.length() == 0) {
            return false;
        }
        char last = buf.charAt(buf.length() - 1);
        if (last != 'e' && last != 'E') {
            return false;
        }
        if (buf.length() == 1) {
            return true;
        }
        char prev = buf.charAt(buf.length() - 2);
        return !Character.isLetterOrDigit(prev) && prev != '_';
    }

    /** 在 pos 处尝试读出 $tag$（含两侧 $）；不是合法美元引号则返回 null */
    private static String readDollarTag(String text, int pos) {
        int n = text.length();
        int j = pos + 1;
        while (j < n) {
            char ch = text.charAt(j);
            if (ch == '$') {
                return text.substring(pos, j + 1);
            }
            if (!Character.isLetterOrDigit(ch) && ch != '_') {
                return null;
            }
            j++;
        }
        return null;
    }

    /**
     * 去掉语句开头的连续注释（行注释与块注释都要处理）。
     * 纯净库文件头就是一段 {@code /* PostgreSQL Backup ... *​/}，
     * 只剥行注释的话它会被当成一条未识别语句。
     */
    public static String stripLeadingComments(String stmt) {
        int i = 0;
        int n = stmt.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(stmt.charAt(i))) {
                i++;
            }
            if (i + 1 < n && stmt.charAt(i) == '-' && stmt.charAt(i + 1) == '-') {
                int end = stmt.indexOf('\n', i);
                if (end < 0) {
                    return "";
                }
                i = end + 1;
                continue;
            }
            if (i + 1 < n && stmt.charAt(i) == '/' && stmt.charAt(i + 1) == '*') {
                int depth = 0;
                int j = i;
                while (j < n) {
                    if (stmt.charAt(j) == '/' && j + 1 < n && stmt.charAt(j + 1) == '*') {
                        depth++;
                        j += 2;
                    } else if (stmt.charAt(j) == '*' && j + 1 < n && stmt.charAt(j + 1) == '/') {
                        depth--;
                        j += 2;
                        if (depth == 0) {
                            break;
                        }
                    } else {
                        j++;
                    }
                }
                if (j >= n) {
                    return "";
                }
                i = j;
                continue;
            }
            break;
        }
        return stmt.substring(i);
    }
}
