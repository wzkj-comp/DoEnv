package com.doenv.schema.baseline;

import com.doenv.schema.model.CkTableModel;
import com.doenv.schema.model.ColumnModel;
import com.doenv.schema.model.SchemaModel;
import com.doenv.schema.model.SeedTable;
import com.doenv.schema.model.TableModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 CK 单机纯净库解析成结构模型。
 *
 * <p>只解析<b>单机版</b>基准。分布式版已实证可由单机版机械推导（503 张表逐字节一致），
 * 所以基准只维护一份，分布式 DDL 由 {@code CkDistributedRenderer} 运行时生成。
 *
 * <p>CK 与 PG 的关键差别，解析时必须守住：
 * <ul>
 *   <li><b>标识符大小写敏感</b> —— 同一份基准里 CASE_ID / CaseID / caseid 三种写法并存，
 *       绝不能折叠成小写</li>
 *   <li>表引用<b>不写库名</b> —— 各现场库名不同</li>
 *   <li>引擎 / PARTITION BY / ORDER BY / PRIMARY KEY 建表后<b>不可 ALTER</b></li>
 * </ul>
 */
public class CkBaselineParser {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "^CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([^(\\s]+)\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern INSERT_INTO = Pattern.compile(
            "^INSERT\\s+INTO\\s+([^(\\s]+)\\s*\\(", Pattern.CASE_INSENSITIVE);

    /** 建表尾部各子句的关键字，按出现顺序无关的方式扫描 */
    private static final String[] TAIL_KEYWORDS = {
            "ENGINE", "PARTITION BY", "PRIMARY KEY", "ORDER BY", "SAMPLE BY",
            "TTL", "SETTINGS", "COMMENT"
    };

    public BaselineBundle parse(String sqlText, String baselineId, String sourceFile) {
        BaselineBundle bundle = new BaselineBundle();
        bundle.setBaselineId(baselineId);
        bundle.setSourceFile(sourceFile);

        SchemaModel schema = bundle.getSchema();
        schema.setSource("BASELINE");
        schema.setBaselineId(baselineId);
        schema.setSchemaName(null);   // CK 不写库名

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

        if (upper.startsWith("CREATE TABLE")) {
            parseCreateTable(stmt, bundle);
            return;
        }
        if (upper.startsWith("INSERT INTO")) {
            parseInsert(stmt, bundle);
            return;
        }
        // 破坏性 / 与结构无关的语句：丢弃并计数
        if (upper.startsWith("DROP ")) {
            bundle.discard("DROP");
            return;
        }
        if (upper.startsWith("TRUNCATE")) {
            bundle.discard("TRUNCATE");
            return;
        }
        if (upper.startsWith("DELETE FROM") || upper.contains(" DELETE WHERE")) {
            bundle.discard("DELETE");
            return;
        }
        if (upper.startsWith("ALTER ")) {
            bundle.discard("ALTER");
            return;
        }
        if (upper.startsWith("OPTIMIZE")) {
            bundle.discard("OPTIMIZE");
            return;
        }
        if (upper.startsWith("CREATE VIEW") || upper.startsWith("CREATE MATERIALIZED VIEW")
                || upper.startsWith("CREATE OR REPLACE VIEW")
                || upper.startsWith("CREATE LIVE VIEW") || upper.startsWith("CREATE WINDOW VIEW")) {
            com.doenv.schema.model.ViewModel v = parseView(stmt);
            if (v != null) {
                bundle.getSchema().getViews().add(v);
            } else {
                bundle.discard("VIEW_UNPARSED");
            }
            return;
        }
        if (upper.startsWith("SET ") || upper.startsWith("USE ") || upper.startsWith("SELECT ")) {
            bundle.discard("MISC");
            return;
        }
        bundle.discard("UNKNOWN");
        bundle.getUnparsedSamples().putIfAbsent("UNKNOWN:" + firstWord(upper), head(stmt));
    }

    // ────────────────────────── CREATE TABLE ──────────────────────────

    private void parseCreateTable(String stmt, BaselineBundle bundle) {
        Matcher m = CREATE_TABLE.matcher(stmt);
        if (!m.find()) {
            bundle.discard("CREATE_TABLE_UNPARSED");
            return;
        }
        String tableName = CkTokens.unquote(m.group(1));
        int open = stmt.indexOf('(', m.end() - 1);
        int close = SqlTokens.matchParen(stmt, open);
        if (close < 0) {
            bundle.discard("CREATE_TABLE_UNPARSED");
            return;
        }

        TableModel table = new TableModel();
        table.setName(tableName);

        int pos = 0;
        List<com.doenv.schema.model.CkIndexModel> indexes = new ArrayList<>();
        for (String item : SqlTokens.splitTopLevel(stmt.substring(open + 1, close), ',')) {
            String s = item.trim();
            if (s.isEmpty()) {
                continue;
            }
            String u = s.toUpperCase(Locale.ROOT);
            // 跳数索引写在列定义区里：INDEX name expr TYPE ... GRANULARITY n
            if (u.startsWith("INDEX ")) {
                com.doenv.schema.model.CkIndexModel idx = parseIndexDef(s);
                if (idx != null) {
                    indexes.add(idx);
                } else {
                    bundle.discard("INDEX_UNPARSED");
                }
                continue;
            }
            if (u.startsWith("CONSTRAINT ") || u.startsWith("PROJECTION ")) {
                continue;
            }
            ColumnModel col = parseColumnDef(s, ++pos);
            if (col != null) {
                table.addColumn(col);
            }
        }

        table.setCk(parseTail(stmt.substring(close + 1), table, bundle));
        if (table.getCk() != null) {
            table.getCk().setIndexes(indexes);
        }
        // 表注释在尾部 COMMENT 子句里，parseTail 会填到 table 上
        bundle.getSchema().addTable(table);
    }

    /**
     * 解析 {@code CREATE [MATERIALIZED] VIEW [IF NOT EXISTS] [库.]名字 [(列清单)] AS <查询>}。
     *
     * <p>只留 AS 之后的查询体：业务视图多是 {@code SELECT * EXCEPT (...)} 写法，
     * 跟着源表走才是本意，钉死列清单反而会在源表加列后脱节。
     */
    private com.doenv.schema.model.ViewModel parseView(String stmt) {
        int asAt = CkTokens.indexOfKeyword(stmt, "AS");
        if (asAt < 0) {
            return null;
        }
        String head = stmt.substring(0, asAt);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "CREATE\\s+(OR\\s+REPLACE\\s+)?(MATERIALIZED\\s+|LIVE\\s+|WINDOW\\s+)?VIEW\\s+"
                        + "(IF\\s+NOT\\s+EXISTS\\s+)?(?:[`\"]?\\w+[`\"]?\\.)?[`\"]?(\\w+)[`\"]?",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(head);
        if (!m.find()) {
            return null;
        }
        com.doenv.schema.model.ViewModel v = new com.doenv.schema.model.ViewModel();
        v.setName(m.group(4));
        v.setMaterialized(m.group(2) != null && m.group(2).trim().equalsIgnoreCase("MATERIALIZED"));
        String q = stmt.substring(asAt + 2).trim();
        if (q.endsWith(";")) {
            q = q.substring(0, q.length() - 1).trim();
        }
        // 基准可能带着导出环境的库名（实测 2026-08-11 那份是
        // CREATE VIEW datao_pure.vax_trans_view ... FROM datao_pure.vax_trans）。
        // 各现场库名都不同，照搬会报 Code 81 Database ... does not exist，
        // 所以把视图头部出现的那个库名从查询体里一并剥掉
        String db = extractDbQualifier(head);
        if (db != null) {
            q = q.replaceAll("(?i)\\b" + java.util.regex.Pattern.quote(db) + "\\.", "")
                 .replace("`" + db + "`.", "");
        }
        v.setQuery(q);
        return v;
    }

    /** 从 {@code CREATE VIEW <库>.<名>} 里取出库名限定；没写库名返回 null */
    private static String extractDbQualifier(String head) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "VIEW\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[`\"]?(\\w+)[`\"]?\\s*\\.",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(head);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 解析跳数索引定义：{@code INDEX <名字> <表达式> TYPE <类型> GRANULARITY <n>}。
     *
     * <p>表达式可能带括号且跨多行（{@code INDEX idx (CaseID,\n ItemType) TYPE minmax ...}），
     * 所以按 TYPE / GRANULARITY 两个关键字切，不能简单按空格拆。
     */
    private com.doenv.schema.model.CkIndexModel parseIndexDef(String def) {
        String body = def.trim().substring("INDEX".length()).trim();
        int typeAt = CkTokens.indexOfKeyword(body, "TYPE");
        if (typeAt < 0) {
            return null;
        }
        String head = body.substring(0, typeAt).trim();
        int sp = head.indexOf(' ');
        if (sp < 0) {
            return null;   // 只有名字没有表达式，不合法
        }
        com.doenv.schema.model.CkIndexModel idx = new com.doenv.schema.model.CkIndexModel();
        idx.setName(SqlTokens.unquoteIdent(head.substring(0, sp).trim()));
        idx.setExpression(head.substring(sp + 1).trim());

        String rest = body.substring(typeAt + "TYPE".length()).trim();
        int granAt = CkTokens.indexOfKeyword(rest, "GRANULARITY");
        if (granAt < 0) {
            idx.setType(rest.trim());
        } else {
            idx.setType(rest.substring(0, granAt).trim());
            String g = rest.substring(granAt + "GRANULARITY".length()).trim();
            try {
                idx.setGranularity(Integer.parseInt(g.split("\\s+")[0]));
            } catch (RuntimeException ignored) {
                // 粒度写法异常时用默认值，不因为它丢掉整个索引
            }
        }
        return idx;
    }

    /**
     * 解析一条列定义：{@code `name` Type [DEFAULT expr] [CODEC(..)] [COMMENT '..']}
     */
    private ColumnModel parseColumnDef(String def, int position) {
        String s = def.trim();
        String name;
        int cursor;
        if (s.charAt(0) == '`') {
            int end = s.indexOf('`', 1);
            if (end < 0) {
                return null;
            }
            name = s.substring(1, end);
            cursor = end + 1;
        } else {
            int end = s.indexOf(' ');
            if (end < 0) {
                return null;
            }
            name = s.substring(0, end);
            cursor = end;
        }

        String rest = s.substring(cursor).trim();

        // 类型一直取到 DEFAULT / MATERIALIZED / ALIAS / CODEC / COMMENT / TTL 之一
        int typeEnd = rest.length();
        for (String kw : new String[]{"DEFAULT", "MATERIALIZED", "ALIAS", "EPHEMERAL",
                "CODEC", "COMMENT", "TTL"}) {
            int idx = CkTokens.indexOfKeyword(rest, kw);
            if (idx >= 0 && idx < typeEnd) {
                typeEnd = idx;
            }
        }

        ColumnModel col = new ColumnModel();
        col.setName(name);   // 大小写原样保留，CK 是敏感的
        col.setPosition(position);
        String rawType = rest.substring(0, typeEnd).trim();
        col.setRawType(rawType);
        col.setNormType(CkTokens.normalizeType(rawType));
        col.setTypeFamily(CkTokens.familyOf(rawType));
        // CK 的可空性写在类型里：Nullable(T)
        col.setNotNull(!rawType.toUpperCase(Locale.ROOT).startsWith("NULLABLE("));

        int defIdx = CkTokens.indexOfKeyword(rest, "DEFAULT");
        if (defIdx >= 0) {
            String after = rest.substring(defIdx + "DEFAULT".length());
            int stop = after.length();
            for (String kw : new String[]{"CODEC", "COMMENT", "TTL"}) {
                int idx = CkTokens.indexOfKeyword(after, kw);
                if (idx >= 0 && idx < stop) {
                    stop = idx;
                }
            }
            String d = after.substring(0, stop).trim();
            col.setDefaultRaw(d);
            col.setDefaultNorm(d);
        }

        int cmtIdx = CkTokens.indexOfKeyword(rest, "COMMENT");
        if (cmtIdx >= 0) {
            col.setComment(CkTokens.unquoteLiteral(
                    rest.substring(cmtIdx + "COMMENT".length()).trim()));
        }
        return col;
    }

    /** 解析建表尾部：ENGINE / PARTITION BY / ORDER BY / PRIMARY KEY / TTL / SETTINGS / COMMENT */
    private CkTableModel parseTail(String tail, TableModel table, BaselineBundle bundle) {
        CkTableModel ck = new CkTableModel();

        Map<String, String> clauses = splitTailClauses(tail);

        String engine = clauses.get("ENGINE");
        if (engine != null) {
            // 子句是按关键字切的，ENGINE 后面还留着 '='
            String e = engine.trim();
            if (e.startsWith("=")) {
                e = e.substring(1).trim();
            }
            int p = e.indexOf('(');
            if (p > 0) {
                int q = SqlTokens.matchParen(e, p);
                ck.setEngineFamily(e.substring(0, p).trim());
                if (q > p) {
                    ck.setEngineArgs(SqlTokens.splitTopLevel(e.substring(p + 1, q), ',').stream()
                            .map(String::trim).filter(x -> !x.isEmpty()).toList());
                }
            } else {
                ck.setEngineFamily(e);
            }
        } else {
            bundle.discard("CREATE_TABLE_NO_ENGINE");
        }

        ck.setPartitionBy(trimOrNull(clauses.get("PARTITION BY")));
        ck.setSampleBy(trimOrNull(clauses.get("SAMPLE BY")));
        ck.setTtl(trimOrNull(clauses.get("TTL")));
        ck.setOrderBy(CkTokens.keyList(clauses.get("ORDER BY")));
        List<String> pk = CkTokens.keyList(clauses.get("PRIMARY KEY"));
        // 没写 PRIMARY KEY 时，CK 默认把主键取成排序键。现场读回来的 primary_key
        // 就是这个默认值，基准这边不补齐的话每张这样的表都会被误判成主键差异。
        ck.setPrimaryKey(pk.isEmpty() ? ck.getOrderBy() : pk);

        String settings = clauses.get("SETTINGS");
        if (settings != null) {
            for (String kv : SqlTokens.splitTopLevel(settings, ',')) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    ck.getSettings().put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                }
            }
        }

        String comment = clauses.get("COMMENT");
        if (comment != null) {
            table.setComment(CkTokens.unquoteLiteral(comment.trim()));
        }
        return ck;
    }

    /** 把尾部按关键字切成子句。关键字必须在顶层（不在括号/引号里） */
    private Map<String, String> splitTailClauses(String tail) {
        List<int[]> hits = new ArrayList<>();   // {起点, 关键字长度, 关键字下标}
        for (int k = 0; k < TAIL_KEYWORDS.length; k++) {
            int from = 0;
            while (true) {
                int idx = CkTokens.indexOfKeyword(tail.substring(from), TAIL_KEYWORDS[k]);
                if (idx < 0) {
                    break;
                }
                hits.add(new int[]{from + idx, TAIL_KEYWORDS[k].length(), k});
                from = from + idx + TAIL_KEYWORDS[k].length();
            }
        }
        hits.sort((a, b) -> Integer.compare(a[0], b[0]));

        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < hits.size(); i++) {
            int[] h = hits.get(i);
            int valueStart = h[0] + h[1];
            int valueEnd = (i + 1 < hits.size()) ? hits.get(i + 1)[0] : tail.length();
            String key = TAIL_KEYWORDS[h[2]];
            // PRIMARY KEY 会被 "PRIMARY KEY" 命中，别再被别的关键字重复收录
            out.putIfAbsent(key, tail.substring(valueStart, valueEnd).trim());
        }
        return out;
    }

    // ────────────────────────── INSERT ──────────────────────────

    private void parseInsert(String stmt, BaselineBundle bundle) {
        Matcher m = INSERT_INTO.matcher(stmt);
        if (!m.find()) {
            bundle.discard("INSERT_UNPARSED");
            return;
        }
        String tableName = CkTokens.unquote(m.group(1));
        int open = stmt.indexOf('(', m.end() - 1);
        int close = SqlTokens.matchParen(stmt, open);
        if (close < 0) {
            bundle.discard("INSERT_UNPARSED");
            return;
        }
        List<String> cols = SqlTokens.splitTopLevel(stmt.substring(open + 1, close), ',').stream()
                .map(CkTokens::unquote).toList();

        int afterCols = close + 1;
        int valuesIdx = CkTokens.indexOfKeyword(stmt.substring(afterCols), "VALUES");
        if (valuesIdx < 0) {
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
            List<String> raw = SqlTokens.splitTopLevel(stmt.substring(o + 1, cl), ',').stream()
                    .map(String::trim).toList();
            List<String> vals = raw.stream().map(CkTokens::unquoteLiteral).toList();
            if (vals.size() == cols.size()) {
                seed.addRow(vals, raw);
            } else {
                bundle.discard("INSERT_ARITY_MISMATCH");
            }
            cursor = cl + 1;
        }
    }

    private static String trimOrNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
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
