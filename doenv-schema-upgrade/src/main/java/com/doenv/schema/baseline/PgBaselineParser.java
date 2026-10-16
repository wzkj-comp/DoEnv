package com.doenv.schema.baseline;

import com.doenv.schema.model.ColumnModel;
import com.doenv.schema.model.ConstraintModel;
import com.doenv.schema.model.IndexModel;
import com.doenv.schema.model.SchemaModel;
import com.doenv.schema.model.SequenceModel;
import com.doenv.schema.model.SeedTable;
import com.doenv.schema.model.TableModel;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 PG 纯净库 SQL 解析成结构模型 + 种子数据。
 *
 * <p><b>安全红线</b>：纯净库原文头部有 167 条 {@code DROP TABLE} 和 165 段
 * <b>无 WHERE 的 {@code DELETE FROM}</b>，任何可执行路径都是数据全灭。
 * 本解析器只<b>提取</b>结构与数据，所有破坏性语句一律丢弃并计数，
 * 升级 SQL 由渲染器按策略重新生成，绝不复用原文。
 */
public class PgBaselineParser {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "^CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([^(\\s]+)\\s*\\(", Pattern.CASE_INSENSITIVE);

    private static final Pattern ALTER_TABLE = Pattern.compile(
            "^ALTER\\s+TABLE\\s+(?:ONLY\\s+)?([^\\s]+)\\s+(.*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern ADD_CONSTRAINT = Pattern.compile(
            "^ADD\\s+CONSTRAINT\\s+(\"[^\"]+\"|[A-Za-z0-9_]+)\\s+(.*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern COMMENT_ON = Pattern.compile(
            "^COMMENT\\s+ON\\s+(TABLE|COLUMN)\\s+(.+?)\\s+IS\\s+(.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern INSERT_INTO = Pattern.compile(
            "^INSERT\\s+INTO\\s+([^(\\s]+)\\s*\\(", Pattern.CASE_INSENSITIVE);

    public BaselineBundle parse(String sqlText, String baselineId, String sourceFile) {
        BaselineBundle bundle = new BaselineBundle();
        bundle.setBaselineId(baselineId);
        bundle.setSourceFile(sourceFile);

        SchemaModel schema = bundle.getSchema();
        schema.setSource("BASELINE");
        schema.setBaselineId(baselineId);

        for (String raw : SqlStatementSplitter.split(sqlText)) {
            String stmt = SqlStatementSplitter.stripLeadingComments(raw).trim();
            if (stmt.isEmpty()) {
                continue;
            }
            try {
                dispatch(stmt, bundle);
            } catch (RuntimeException e) {
                bundle.getUnparsedSamples().putIfAbsent(
                        "ERROR:" + e.getClass().getSimpleName(),
                        head(stmt) + "  // " + e.getMessage());
            }
        }
        return bundle;
    }

    private void dispatch(String stmt, BaselineBundle bundle) {
        String upper = stmt.toUpperCase(Locale.ROOT);

        // ── 破坏性 / 无关语句：一律丢弃并计数 ──
        if (upper.startsWith("DROP TABLE")) {
            bundle.discard("DROP_TABLE");
            return;
        }
        if (upper.startsWith("DROP ")) {
            bundle.discard("DROP_OTHER");
            return;
        }
        if (upper.startsWith("DELETE FROM")) {
            bundle.discard(upper.contains(" WHERE ") ? "DELETE_WITH_WHERE" : "UNSAFE_DELETE");
            return;
        }
        if (upper.startsWith("TRUNCATE")) {
            bundle.discard("TRUNCATE");
            return;
        }
        if (upper.startsWith("LOCK TABLE") || upper.startsWith("BEGIN") || upper.startsWith("COMMIT")
                || upper.startsWith("START TRANSACTION") || upper.startsWith("ROLLBACK")) {
            bundle.discard("TX_CONTROL");
            return;
        }
        if (upper.startsWith("CREATE SEQUENCE")) {
            parseCreateSequence(stmt, bundle);
            return;
        }
        if (upper.startsWith("SELECT SETVAL")) {
            parseSetval(stmt, bundle);
            return;
        }
        if (upper.startsWith("DO ") || upper.startsWith("DO$")) {
            parseDoBlock(stmt, bundle);
            return;
        }
        if (upper.startsWith("ALTER SEQUENCE")) {
            // 只有 OWNER TO，无结构含义
            bundle.discard("SEQUENCE_OWNER");
            return;
        }
        if (upper.startsWith("CREATE VIEW") || upper.startsWith("CREATE OR REPLACE VIEW")
                || upper.startsWith("CREATE MATERIALIZED VIEW")
                || upper.startsWith("CREATE RECURSIVE VIEW")) {
            com.doenv.schema.model.ViewModel v = parseView(stmt);
            if (v != null) {
                bundle.getSchema().getViews().add(v);
            } else {
                bundle.discard("VIEW_UNPARSED");
            }
            return;
        }
        if (upper.startsWith("CREATE OR REPLACE FUNCTION") || upper.startsWith("CREATE FUNCTION")
                || upper.startsWith("ALTER FUNCTION")) {
            bundle.discard("FUNCTION");
            return;
        }
        if (upper.startsWith("SET ") || upper.startsWith("SELECT ")) {
            bundle.discard("MISC");
            return;
        }

        if (upper.startsWith("CREATE TABLE")) {
            parseCreateTable(stmt, bundle);
            return;
        }
        if (upper.startsWith("ALTER TABLE")) {
            parseAlterTable(stmt, bundle);
            return;
        }
        if (upper.startsWith("CREATE INDEX") || upper.startsWith("CREATE UNIQUE INDEX")) {
            parseCreateIndex(stmt, bundle);
            return;
        }
        if (upper.startsWith("COMMENT ON")) {
            parseComment(stmt, bundle);
            return;
        }
        if (upper.startsWith("INSERT INTO")) {
            parseInsert(stmt, bundle);
            return;
        }

        bundle.discard("UNKNOWN");
        bundle.getUnparsedSamples().putIfAbsent("UNKNOWN:" + firstWord(upper), head(stmt));
    }

    // ────────────────────────── CREATE TABLE ──────────────────────────

    /**
     * 解析 {@code CREATE [OR REPLACE] [MATERIALIZED] VIEW [schema.]名字 AS <查询>}。
     *
     * <p>之前这类语句会被静默丢弃 —— 基准将来加了视图也没人知道，
     * 现场就永远缺它。CK 那边的 vax_trans_view 就是这么漏掉的。
     */
    private com.doenv.schema.model.ViewModel parseView(String stmt) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "CREATE\\s+(OR\\s+REPLACE\\s+)?(MATERIALIZED\\s+|RECURSIVE\\s+)?VIEW\\s+"
                        + "(?:\"?\\w+\"?\\.)?\"?(\\w+)\"?",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(stmt);
        if (!m.find()) {
            return null;
        }
        // 视图名之后的第一个独立 AS 就是分隔符；查询体里的列别名 AS 都在它之后
        java.util.regex.Matcher as = java.util.regex.Pattern
                .compile("\\bAS\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(stmt);
        if (!as.find(m.end())) {
            return null;
        }
        int asAt = as.start();
        com.doenv.schema.model.ViewModel v = new com.doenv.schema.model.ViewModel();
        v.setName(m.group(3));
        v.setMaterialized(m.group(2) != null && m.group(2).trim().equalsIgnoreCase("MATERIALIZED"));
        String q = stmt.substring(asAt + 2).trim();
        if (q.endsWith(";")) {
            q = q.substring(0, q.length() - 1).trim();
        }
        v.setQuery(q);
        return v;
    }

    private void parseCreateTable(String stmt, BaselineBundle bundle) {
        Matcher m = CREATE_TABLE.matcher(stmt);
        if (!m.find()) {
            bundle.discard("CREATE_TABLE_UNPARSED");
            return;
        }
        String tableName = SqlTokens.tableNameOf(m.group(1));
        int open = stmt.indexOf('(', m.end() - 1);
        int close = SqlTokens.matchParen(stmt, open);
        if (close < 0) {
            bundle.discard("CREATE_TABLE_UNPARSED");
            return;
        }
        String body = stmt.substring(open + 1, close);

        TableModel table = new TableModel();
        table.setName(tableName);

        int pos = 0;
        for (String item : SqlTokens.splitTopLevel(body, ',')) {
            String s = item.trim();
            if (s.isEmpty()) {
                continue;
            }
            String u = s.toUpperCase(Locale.ROOT);
            if (u.startsWith("CONSTRAINT ") || u.startsWith("PRIMARY KEY")
                    || u.startsWith("UNIQUE ") || u.startsWith("UNIQUE(") || u.startsWith("CHECK")) {
                ConstraintModel c = parseInlineConstraint(s);
                if (c != null) {
                    table.getConstraints().add(c);
                }
                continue;
            }
            ColumnModel col = parseColumnDef(s, ++pos);
            if (col != null) {
                table.addColumn(col);
            }
        }
        bundle.getSchema().addTable(table);
    }

    /** 解析一条列定义： "name" type [COLLATE x] [NOT NULL] [DEFAULT expr] */
    private ColumnModel parseColumnDef(String def, int position) {
        String s = def.trim();
        int nameEnd;
        String name;
        if (s.charAt(0) == '"') {
            nameEnd = s.indexOf('"', 1);
            if (nameEnd < 0) {
                return null;
            }
            name = s.substring(1, nameEnd);
            nameEnd++;
        } else {
            nameEnd = s.indexOf(' ');
            if (nameEnd < 0) {
                return null;
            }
            name = s.substring(0, nameEnd);
        }

        String rest = s.substring(nameEnd).trim();

        int notNullIdx = indexOfKeyword(rest, "NOT NULL");
        int defaultIdx = indexOfKeyword(rest, "DEFAULT");
        int collateIdx = indexOfKeyword(rest, "COLLATE");
        int nullIdx = (notNullIdx < 0) ? indexOfKeyword(rest, "NULL") : -1;

        int typeEnd = rest.length();
        for (int idx : new int[]{notNullIdx, defaultIdx, collateIdx, nullIdx}) {
            if (idx >= 0 && idx < typeEnd) {
                typeEnd = idx;
            }
        }
        String rawType = rest.substring(0, typeEnd).trim();

        ColumnModel col = new ColumnModel();
        col.setName(name);
        col.setPosition(position);
        TypeNormalizer.apply(col, rawType);
        col.setNotNull(notNullIdx >= 0);

        if (defaultIdx >= 0) {
            String after = rest.substring(defaultIdx + "DEFAULT".length()).trim();
            int stop = indexOfKeyword(after, "NOT NULL");
            if (stop < 0) {
                stop = after.length();
            }
            String defRaw = after.substring(0, stop).trim();
            col.setDefaultRaw(defRaw);
            col.setDefaultNorm(TypeNormalizer.normalizeDefault(defRaw));
        }
        return col;
    }

    private ConstraintModel parseInlineConstraint(String s) {
        String body = s;
        String name = null;
        if (body.toUpperCase(Locale.ROOT).startsWith("CONSTRAINT ")) {
            String after = body.substring("CONSTRAINT".length()).trim();
            int end;
            if (after.startsWith("\"")) {
                end = after.indexOf('"', 1) + 1;
                name = SqlTokens.unquoteIdent(after.substring(0, end));
            } else {
                end = after.indexOf(' ');
                if (end < 0) {
                    return null;
                }
                name = after.substring(0, end);
            }
            body = after.substring(end).trim();
        }
        return buildConstraint(name, body);
    }

    private ConstraintModel buildConstraint(String name, String body) {
        String u = body.toUpperCase(Locale.ROOT);
        ConstraintModel c = new ConstraintModel();
        c.setName(name);
        if (u.startsWith("PRIMARY KEY")) {
            c.setType("PRIMARY_KEY");
            c.setColumns(columnListOf(body));
        } else if (u.startsWith("UNIQUE")) {
            c.setType("UNIQUE");
            c.setColumns(columnListOf(body));
        } else if (u.startsWith("CHECK")) {
            c.setType("CHECK");
            int open = body.indexOf('(');
            int close = SqlTokens.matchParen(body, open);
            c.setExpression(close > 0 ? body.substring(open + 1, close).trim() : body);
        } else if (u.startsWith("FOREIGN KEY")) {
            c.setType("FOREIGN_KEY");
            c.setColumns(columnListOf(body));
            parseForeignKeyTail(c, body);
        } else {
            return null;
        }
        return c;
    }

    private static final Pattern FK_REFS = Pattern.compile(
            "REFERENCES\\s+([^(\\s]+)\\s*\\(", Pattern.CASE_INSENSITIVE);

    /** 解析 FOREIGN KEY (a) REFERENCES "public"."t" (b) [ON DELETE x] [ON UPDATE y] */
    private void parseForeignKeyTail(ConstraintModel c, String body) {
        Matcher m = FK_REFS.matcher(body);
        if (!m.find()) {
            return;
        }
        c.setRefTable(SqlTokens.tableNameOf(m.group(1)));
        int open = body.indexOf('(', m.end() - 1);
        int close = SqlTokens.matchParen(body, open);
        if (close > open) {
            c.setRefColumns(SqlTokens.splitTopLevel(body.substring(open + 1, close), ',').stream()
                    .map(SqlTokens::unquoteIdent)
                    .filter(x -> !x.isEmpty())
                    .toList());
        }
        String tail = close > 0 ? body.substring(close + 1) : "";
        c.setOnDelete(referentialAction(tail, "ON DELETE"));
        c.setOnUpdate(referentialAction(tail, "ON UPDATE"));
    }

    /** 取 ON DELETE / ON UPDATE 后面的动作词；NO ACTION 是默认值，按无处理 */
    private String referentialAction(String tail, String keyword) {
        int i = indexOfKeyword(tail, keyword);
        if (i < 0) {
            return null;
        }
        String rest = tail.substring(i + keyword.length()).trim();
        String upper = rest.toUpperCase(Locale.ROOT);
        for (String act : new String[]{"NO ACTION", "RESTRICT", "CASCADE", "SET NULL", "SET DEFAULT"}) {
            if (upper.startsWith(act)) {
                return "NO ACTION".equals(act) ? null : act;
            }
        }
        return null;
    }

    private List<String> columnListOf(String body) {
        int open = body.indexOf('(');
        int close = SqlTokens.matchParen(body, open);
        String inner = (open >= 0 && close > open) ? body.substring(open + 1, close) : "";
        return SqlTokens.splitTopLevel(inner, ',').stream()
                .map(SqlTokens::unquoteIdent)
                .filter(x -> !x.isEmpty())
                .toList();
    }

    // ────────────────────────── ALTER TABLE ──────────────────────────

    private void parseAlterTable(String stmt, BaselineBundle bundle) {
        Matcher m = ALTER_TABLE.matcher(stmt);
        if (!m.matches()) {
            bundle.discard("ALTER_UNPARSED");
            return;
        }
        String tableName = SqlTokens.tableNameOf(m.group(1));
        String action = m.group(2).trim();
        String u = action.toUpperCase(Locale.ROOT);

        if (u.startsWith("OWNER TO")) {
            bundle.discard("OWNER_TO");
            return;
        }
        Matcher am = ADD_CONSTRAINT.matcher(action);
        if (am.matches()) {
            TableModel t = bundle.getSchema().findTable(tableName);
            if (t == null) {
                bundle.discard("CONSTRAINT_ORPHAN");
                return;
            }
            ConstraintModel c = buildConstraint(SqlTokens.unquoteIdent(am.group(1)), am.group(2).trim());
            if (c != null) {
                t.getConstraints().add(c);
            }
            return;
        }
        bundle.discard("ALTER_OTHER");
    }

    /**
     * 解析纯净库导出器生成的幂等约束块。
     *
     * <p>导出器把 {@code ALTER TABLE ... ADD CONSTRAINT} 包在 {@code DO $$} 的
     * {@code IF NOT EXISTS} 中，DoEnv 只需要提取其中的结构定义，不复用控制流原文。
     * 其他 DO 块不猜测含义，明确计入丢弃统计。
     */
    private void parseDoBlock(String stmt, BaselineBundle bundle) {
        String upper = stmt.toUpperCase(Locale.ROOT);
        int alterAt = upper.indexOf("ALTER TABLE");
        if (alterAt < 0) {
            bundle.discard("DO_OTHER");
            return;
        }
        List<String> inner = SqlStatementSplitter.split(stmt.substring(alterAt));
        if (inner.isEmpty() || !inner.get(0).trim().toUpperCase(Locale.ROOT).startsWith("ALTER TABLE")) {
            bundle.discard("DO_UNPARSED");
            return;
        }
        parseAlterTable(inner.get(0).trim(), bundle);
    }

    // ────────────────────────── CREATE INDEX ──────────────────────────

    private void parseCreateIndex(String stmt, BaselineBundle bundle) {
        // 与现场侧共用 IndexDefParser，保证两边归一化口径完全一致
        IndexDefParser.Parsed parsed = IndexDefParser.parse(stmt);
        if (parsed == null) {
            bundle.discard("INDEX_UNPARSED");
            return;
        }
        TableModel t = bundle.getSchema().findTable(parsed.tableName());
        if (t == null) {
            bundle.discard("INDEX_ORPHAN");
            return;
        }
        t.getIndexes().add(parsed.index());
    }

    // ────────────────────────── SEQUENCE ──────────────────────────

    private static final Pattern CREATE_SEQ = Pattern.compile(
            "^CREATE\\s+SEQUENCE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([^\\s;]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern SETVAL = Pattern.compile(
            "SETVAL\\s*\\(\\s*'([^']+)'\\s*(?:::\\s*(?:PG_CATALOG\\.)?REGCLASS)?"
                    + "\\s*,\\s*(-?\\d+)\\s*(?:,\\s*(true|false)\\s*)?\\)",
            Pattern.CASE_INSENSITIVE);

    private void parseCreateSequence(String stmt, BaselineBundle bundle) {
        Matcher m = CREATE_SEQ.matcher(stmt);
        if (!m.find()) {
            bundle.discard("SEQUENCE_UNPARSED");
            return;
        }
        String name = SqlTokens.tableNameOf(m.group(1));
        if (bundle.getSchema().findSequence(name) == null) {
            SequenceModel s = new SequenceModel();
            s.setName(name);
            bundle.getSchema().getSequences().add(s);
        }
    }

    /** SELECT setval('"vao_label_id_seq"', 50, true); */
    private void parseSetval(String stmt, BaselineBundle bundle) {
        Matcher m = SETVAL.matcher(stmt);
        if (!m.find()) {
            bundle.discard("SETVAL_UNPARSED");
            return;
        }
        String name = SqlTokens.tableNameOf(m.group(1).replace("\"", ""));
        SequenceModel s = bundle.getSchema().findSequence(name);
        if (s == null) {
            s = new SequenceModel();
            s.setName(name);
            bundle.getSchema().getSequences().add(s);
        }
        s.setLastValue(Long.valueOf(m.group(2)));
        s.setCalled(m.group(3) == null || Boolean.parseBoolean(m.group(3)));
    }

    // ────────────────────────── COMMENT ON ──────────────────────────

    private void parseComment(String stmt, BaselineBundle bundle) {
        Matcher m = COMMENT_ON.matcher(stmt);
        if (!m.matches()) {
            bundle.discard("COMMENT_UNPARSED");
            return;
        }
        String kind = m.group(1).toUpperCase(Locale.ROOT);
        String target = m.group(2).trim();
        String value = SqlTokens.unquoteLiteral(m.group(3).trim());

        List<String> parts = SqlTokens.splitTopLevel(target, '.');
        if ("TABLE".equals(kind)) {
            TableModel t = bundle.getSchema().findTable(SqlTokens.unquoteIdent(parts.get(parts.size() - 1)));
            if (t != null) {
                t.setComment(value);
            } else {
                bundle.discard("COMMENT_ORPHAN");
            }
            return;
        }
        // COLUMN "public"."t"."c" 或 "t"."c"
        if (parts.size() < 2) {
            bundle.discard("COMMENT_UNPARSED");
            return;
        }
        String colName = SqlTokens.unquoteIdent(parts.get(parts.size() - 1));
        String tblName = SqlTokens.unquoteIdent(parts.get(parts.size() - 2));
        TableModel t = bundle.getSchema().findTable(tblName);
        if (t == null) {
            bundle.discard("COMMENT_ORPHAN");
            return;
        }
        ColumnModel c = t.findColumn(colName);
        if (c == null) {
            bundle.discard("COMMENT_ORPHAN");
            return;
        }
        c.setComment(value);
    }

    // ────────────────────────── INSERT INTO ──────────────────────────

    private void parseInsert(String stmt, BaselineBundle bundle) {
        Matcher m = INSERT_INTO.matcher(stmt);
        if (!m.find()) {
            bundle.discard("INSERT_UNPARSED");
            return;
        }
        String tableName = SqlTokens.tableNameOf(m.group(1));
        int open = stmt.indexOf('(', m.end() - 1);
        int close = SqlTokens.matchParen(stmt, open);
        if (close < 0) {
            bundle.discard("INSERT_UNPARSED");
            return;
        }
        List<String> cols = SqlTokens.splitTopLevel(stmt.substring(open + 1, close), ',').stream()
                .map(SqlTokens::unquoteIdent)
                .toList();

        // 从列清单右括号之后开始找 VALUES —— 必须跳过那个 ')'，
        // 否则 indexOfKeyword 的括号深度会变成 -1，永远匹配不上顶层关键字
        int afterCols = close + 1;
        int valuesIdx = indexOfKeyword(stmt.substring(afterCols), "VALUES");
        if (valuesIdx < 0) {
            // INSERT ... SELECT 形态，基准里不该出现
            bundle.discard("INSERT_SELECT");
            return;
        }
        int cursor = afterCols + valuesIdx + "VALUES".length();

        SeedTable seed = bundle.getSeeds().computeIfAbsent(tableName.toLowerCase(Locale.ROOT), k -> {
            SeedTable st = new SeedTable();
            st.setTableName(tableName);
            st.setColumns(cols);
            return st;
        });

        while (cursor < stmt.length()) {
            int o = stmt.indexOf('(', cursor);
            if (o < 0) {
                break;
            }
            int cl = SqlTokens.matchParen(stmt, o);
            if (cl < 0) {
                break;
            }
            List<String> vals = SqlTokens.splitTopLevel(stmt.substring(o + 1, cl), ',').stream()
                    .map(SqlTokens::unquoteLiteral)
                    .toList();
            if (vals.size() == cols.size()) {
                seed.addRow(vals);
            } else {
                bundle.discard("INSERT_ARITY_MISMATCH");
            }
            cursor = cl + 1;
        }
    }

    // ────────────────────────── helpers ──────────────────────────

    /** 在顶层（不在引号/括号内）查找关键字，要求前后是词边界；找不到返回 -1 */
    static int indexOfKeyword(String s, String keyword) {
        String u = s.toUpperCase(Locale.ROOT);
        String k = keyword.toUpperCase(Locale.ROOT);
        int depth = 0;
        int i = 0;
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

    private static String firstWord(String s) {
        int i = s.indexOf(' ');
        return i < 0 ? s : s.substring(0, i);
    }

    private static String head(String s) {
        String one = s.replaceAll("\\s+", " ");
        return one.length() > 160 ? one.substring(0, 160) + "…" : one;
    }
}
