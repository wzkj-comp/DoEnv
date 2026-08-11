package com.doenv.schema.live;

import com.doenv.schema.baseline.IndexDefParser;
import com.doenv.schema.baseline.TypeNormalizer;
import com.doenv.schema.model.ColumnModel;
import com.doenv.schema.model.ConstraintModel;
import com.doenv.schema.model.SchemaModel;
import com.doenv.schema.model.ViewModel;
import com.doenv.schema.model.TableModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 从现场 PG 读取实际结构。
 *
 * <p>走 {@code pg_catalog} 而不是 {@code information_schema}：后者是标准视图，
 * 拿不到 {@code pg_get_indexdef}、{@code obj_description} 这些关键信息，
 * 而且在国产化替代库上行为差异更大。
 *
 * <p>schema 名不硬编码 —— 现场不一定是 public。
 */
public class PgLiveSchemaReader {

    private final JdbcTemplate jdbc;

    public PgLiveSchemaReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 读当前连接的 schema，供未显式配置时使用 */
    public String currentSchema() {
        String s = jdbc.queryForObject("SELECT current_schema()", String.class);
        return (s == null || s.isBlank()) ? "public" : s;
    }

    public SchemaModel read(String schema) {
        SchemaModel model = new SchemaModel();
        model.setSource("LIVE");
        model.setSchemaName(schema);

        readTables(model, schema);
        readColumns(model, schema);
        readIndexes(model, schema);
        readConstraints(model, schema);
        readSequences(model, schema);
        readViews(model, schema);
        return model;
    }

    // ────────────────────────── 视图 ──────────────────────────

    /**
     * 读视图。
     *
     * <p>{@code readTables} 只取 {@code relkind='r'}，视图不在其中 —— 这是对的
     * （视图没有列默认值、没有约束、没有索引），但也意味着必须单独读一遍，
     * 否则基准里的视图永远下发不到现场。
     */
    private void readViews(SchemaModel model, String schema) {
        jdbc.query("SELECT viewname AS name, definition, false AS matview FROM pg_views "
                + "WHERE schemaname = ? "
                + "UNION ALL "
                + "SELECT matviewname, definition, true FROM pg_matviews WHERE schemaname = ? "
                + "ORDER BY 1", rs -> {
            ViewModel v = new ViewModel();
            v.setName(rs.getString("name"));
            v.setQuery(rs.getString("definition"));
            v.setMaterialized(rs.getBoolean("matview"));
            model.getViews().add(v);
        }, schema, schema);
    }

    // ────────────────────────── 序列 ──────────────────────────

    private void readSequences(SchemaModel model, String schema) {
        // 只能先拿到序列名 —— pg_sequences.last_value 在 is_called=false 时返回 NULL，
        // 分不清「没被调用过」和「被 setval(x,false) 设过」，所以真实值要逐个查序列本身
        List<String> names = jdbc.queryForList(
                "SELECT sequencename FROM pg_sequences WHERE schemaname = ? ORDER BY sequencename",
                String.class, schema);

        for (String name : names) {
            com.doenv.schema.model.SequenceModel s = new com.doenv.schema.model.SequenceModel();
            s.setName(name);
            try {
                // 直接查序列关系本身，能同时拿到 last_value 与 is_called
                jdbc.query("SELECT last_value, is_called FROM "
                        + com.doenv.schema.render.SqlQuoter.table(schema, name), rs -> {
                    s.setLastValue(rs.getLong("last_value"));
                    s.setCalled(rs.getBoolean("is_called"));
                });
            } catch (RuntimeException e) {
                // 读不到就跳过，不影响其余比对
                model.getWarnings().add("序列 " + name + " 读取失败：" + e.getMessage());
                continue;
            }
            model.getSequences().add(s);
        }
    }

    // ────────────────────────── 表 ──────────────────────────

    private void readTables(SchemaModel model, String schema) {
        String sql = """
                SELECT c.relname AS table_name,
                       obj_description(c.oid, 'pg_class') AS table_comment
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = ? AND c.relkind = 'r'
                 ORDER BY c.relname
                """;
        jdbc.query(sql, rs -> {
            TableModel t = new TableModel();
            t.setName(rs.getString("table_name"));
            t.setComment(rs.getString("table_comment"));
            model.addTable(t);
        }, schema);
    }

    // ────────────────────────── 列 ──────────────────────────

    private void readColumns(SchemaModel model, String schema) {
        // 一次查全库再按表分组，避免 N 次往返
        String sql = """
                SELECT c.relname                            AS table_name,
                       a.attnum                             AS position,
                       a.attname                            AS column_name,
                       format_type(a.atttypid, a.atttypmod) AS full_type,
                       a.attnotnull                         AS not_null,
                       pg_get_expr(d.adbin, d.adrelid)      AS column_default,
                       col_description(c.oid, a.attnum)     AS column_comment
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_attribute a ON a.attrelid = c.oid
                  LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
                 WHERE n.nspname = ? AND c.relkind = 'r'
                   AND a.attnum > 0 AND NOT a.attisdropped
                 ORDER BY c.relname, a.attnum
                """;
        jdbc.query(sql, rs -> {
            TableModel t = model.findTable(rs.getString("table_name"));
            if (t == null) {
                return;
            }
            ColumnModel col = new ColumnModel();
            col.setName(rs.getString("column_name"));
            col.setPosition(rs.getInt("position"));
            TypeNormalizer.apply(col, rs.getString("full_type"));
            col.setNotNull(rs.getBoolean("not_null"));
            String def = rs.getString("column_default");
            col.setDefaultRaw(def);
            col.setDefaultNorm(TypeNormalizer.normalizeDefault(def));
            col.setComment(rs.getString("column_comment"));
            t.addColumn(col);
        }, schema);
    }

    // ────────────────────────── 索引 ──────────────────────────

    private void readIndexes(SchemaModel model, String schema) {
        // 排除约束自带的索引（主键/唯一约束），那些由 readConstraints 负责，
        // 否则同一个对象会同时出现在索引差异和约束差异里
        String sql = """
                SELECT t.relname AS table_name,
                       pg_get_indexdef(i.indexrelid) AS index_def
                  FROM pg_index i
                  JOIN pg_class ic ON ic.oid = i.indexrelid
                  JOIN pg_class t  ON t.oid  = i.indrelid
                  JOIN pg_namespace n ON n.oid = t.relnamespace
                 WHERE n.nspname = ?
                   AND t.relkind = 'r'
                   AND i.indisvalid
                   AND NOT EXISTS (SELECT 1 FROM pg_constraint con
                                    WHERE con.conindid = i.indexrelid)
                 ORDER BY t.relname, ic.relname
                """;
        jdbc.query(sql, rs -> {
            String tableName = rs.getString("table_name");
            String def = rs.getString("index_def");
            TableModel t = model.findTable(tableName);
            if (t == null || def == null) {
                return;
            }
            IndexDefParser.Parsed parsed = IndexDefParser.parse(def);
            if (parsed != null) {
                t.getIndexes().add(parsed.index());
            } else {
                model.getWarnings().add("索引定义解析失败：" + def);
            }
        }, schema);
    }

    /** 现场存在的 INVALID 索引（CONCURRENTLY 失败的残留），建索引前必须先清理 */
    public List<String> findInvalidIndexes(String schema) {
        String sql = """
                SELECT ic.relname
                  FROM pg_index i
                  JOIN pg_class ic ON ic.oid = i.indexrelid
                  JOIN pg_class t  ON t.oid  = i.indrelid
                  JOIN pg_namespace n ON n.oid = t.relnamespace
                 WHERE n.nspname = ? AND i.indisvalid = false
                 ORDER BY ic.relname
                """;
        return jdbc.queryForList(sql, String.class, schema);
    }

    // ────────────────────────── 约束 ──────────────────────────

    private void readConstraints(SchemaModel model, String schema) {
        String sql = """
                SELECT rel.relname AS table_name,
                       con.conname AS constraint_name,
                       con.contype AS constraint_type,
                       pg_get_constraintdef(con.oid) AS constraint_def,
                       ARRAY(SELECT a.attname
                               FROM unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
                               JOIN pg_attribute a
                                 ON a.attrelid = con.conrelid AND a.attnum = k.attnum
                              ORDER BY k.ord) AS cols
                  FROM pg_constraint con
                  JOIN pg_class rel ON rel.oid = con.conrelid
                  JOIN pg_namespace n ON n.oid = rel.relnamespace
                 WHERE n.nspname = ? AND con.contype IN ('p', 'u', 'c', 'f')
                 ORDER BY rel.relname, con.conname
                """;
        jdbc.query(sql, rs -> {
            TableModel t = model.findTable(rs.getString("table_name"));
            if (t == null) {
                return;
            }
            ConstraintModel c = new ConstraintModel();
            c.setName(rs.getString("constraint_name"));
            String type = rs.getString("constraint_type");
            c.setType(switch (type) {
                case "p" -> "PRIMARY_KEY";
                case "u" -> "UNIQUE";
                case "f" -> "FOREIGN_KEY";
                default -> "CHECK";
            });
            java.sql.Array arr = rs.getArray("cols");
            if (arr != null) {
                List<String> cols = new ArrayList<>();
                for (Object o : (Object[]) arr.getArray()) {
                    if (o != null) {
                        cols.add(o.toString());
                    }
                }
                c.setColumns(cols);
            }
            if ("CHECK".equals(c.getType())) {
                String def = rs.getString("constraint_def");
                // pg_get_constraintdef 返回 CHECK ((expr))，剥到表达式本体
                if (def != null && def.toUpperCase().startsWith("CHECK")) {
                    def = def.substring(5).trim();
                }
                c.setExpression(def);
            }
            t.getConstraints().add(c);
        }, schema);
    }

    // ────────────────────────── 体检 ──────────────────────────

    /** 现场 vao_ 开头的表数。为 0 说明大概率连错库，必须禁用一切 CREATE */
    public int countVaoTables(String schema) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = ? AND c.relkind = 'r' AND c.relname LIKE 'vao\\_%'
                """, Integer.class, schema);
        return n == null ? 0 : n;
    }

    public boolean canCreateTable(String schema) {
        Boolean b = jdbc.queryForObject(
                "SELECT has_schema_privilege(current_user, ?, 'CREATE')", Boolean.class, schema);
        return Boolean.TRUE.equals(b);
    }

    public String serverVersion() {
        return jdbc.queryForObject("SELECT version()", String.class);
    }

    public String currentDatabase() {
        return jdbc.queryForObject("SELECT current_database()", String.class);
    }
}
