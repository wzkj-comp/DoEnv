package com.doenv.schema.render;

import com.doenv.schema.model.ColumnModel;
import com.doenv.schema.model.ConstraintModel;
import com.doenv.schema.model.IndexModel;
import com.doenv.schema.model.TableModel;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PG 结构变更 SQL 渲染器。
 *
 * <p>所有产物必须满足项目 PG-SQL 规范：
 * <ul>
 *   <li>表引用一律带 schema 限定符</li>
 *   <li>可重复执行（IF NOT EXISTS 或 DO $$ 守卫）</li>
 *   <li>不产生任何 DROP / TRUNCATE / 无 WHERE 的 DELETE</li>
 * </ul>
 */
public class PgDdlRenderer {

    private final String schema;

    public PgDdlRenderer(String schema) {
        this.schema = (schema == null || schema.isBlank()) ? "public" : schema;
    }

    public String getSchema() {
        return schema;
    }

    // ────────────────────────── 建表 ──────────────────────────

    public List<String> createTable(TableModel t) {
        List<String> out = new ArrayList<>();

        String cols = t.getColumns().stream()
                .map(this::columnDefInCreate)
                .collect(Collectors.joining(",\n  "));

        out.add("CREATE TABLE IF NOT EXISTS " + SqlQuoter.table(schema, t.getName()) + " (\n  "
                + cols + "\n);");

        // 主键 / 唯一 / CHECK：建表后单独加，且用 DO $$ 守卫保证可重跑
        for (ConstraintModel c : t.getConstraints()) {
            String sql = addConstraint(t.getName(), c);
            if (sql != null) {
                out.add(sql);
            }
        }
        for (IndexModel idx : t.getIndexes()) {
            out.add(createIndex(t.getName(), idx));
        }
        if (t.getComment() != null && !t.getComment().isEmpty()) {
            out.add(commentOnTable(t.getName(), t.getComment()));
        }
        for (ColumnModel c : t.getColumns()) {
            if (c.getComment() != null && !c.getComment().isEmpty()) {
                out.add(commentOnColumn(t.getName(), c.getName(), c.getComment()));
            }
        }
        return out;
    }

    private String columnDefInCreate(ColumnModel c) {
        StringBuilder sb = new StringBuilder();
        sb.append(SqlQuoter.ident(c.getName())).append(' ').append(c.ddlType());
        if (c.isNotNull()) {
            sb.append(" NOT NULL");
        }
        if (c.getDefaultRaw() != null && !c.getDefaultRaw().isBlank()) {
            sb.append(" DEFAULT ").append(c.getDefaultRaw());
        }
        return sb.toString();
    }

    // ────────────────────────── 加列 ──────────────────────────

    /**
     * 加列。NOT NULL 的处理是有意分情况的：
     * <ul>
     *   <li>基准 NOT NULL 且有非 volatile 常量默认值 → 直接带 NOT NULL DEFAULT。
     *       PG 11+ 这是纯元数据操作，存量行读 attmissingval，几十亿行也是秒级</li>
     *   <li>基准 NOT NULL 但无默认值 → 降级为可空。存量行没有值，带 NOT NULL 必然失败</li>
     * </ul>
     */
    public String addColumn(String table, ColumnModel c) {
        StringBuilder sb = new StringBuilder();
        sb.append("ALTER TABLE ").append(SqlQuoter.table(schema, table))
                .append(" ADD COLUMN IF NOT EXISTS ")
                .append(SqlQuoter.ident(c.getName())).append(' ').append(c.ddlType());

        if (c.canAddWithNotNull()) {
            sb.append(" DEFAULT ").append(c.getDefaultRaw()).append(" NOT NULL");
        } else if (c.getDefaultRaw() != null && !c.getDefaultRaw().isBlank()) {
            sb.append(" DEFAULT ").append(c.getDefaultRaw());
        }
        return sb.append(';').toString();
    }

    // ────────────────────────── 改列 ──────────────────────────

    /** 改类型。用 DO $$ 守卫 —— PG 没有 ALTER COLUMN ... IF 的条件语法 */
    public String alterColumnType(String table, ColumnModel target) {
        String body = "  IF EXISTS (SELECT 1 FROM information_schema.columns\n"
                + "             WHERE table_schema = " + SqlQuoter.literal(schema)
                + " AND table_name = " + SqlQuoter.literal(table)
                + " AND column_name = " + SqlQuoter.literal(target.getName()) + ")\n"
                + "  THEN\n"
                + "    ALTER TABLE " + SqlQuoter.table(schema, table)
                + " ALTER COLUMN " + SqlQuoter.ident(target.getName())
                + " TYPE " + target.ddlType() + ";\n"
                + "  END IF;";
        return SqlQuoter.doBlock(body);
    }

    public String setColumnDefault(String table, ColumnModel target) {
        if (target.getDefaultRaw() == null || target.getDefaultRaw().isBlank()) {
            return "ALTER TABLE " + SqlQuoter.table(schema, table)
                    + " ALTER COLUMN " + SqlQuoter.ident(target.getName()) + " DROP DEFAULT;";
        }
        return "ALTER TABLE " + SqlQuoter.table(schema, table)
                + " ALTER COLUMN " + SqlQuoter.ident(target.getName())
                + " SET DEFAULT " + target.getDefaultRaw() + ";";
    }

    // 刻意没有 dropNotNull：只补键、不删键。现场有而基准没有的 NOT NULL
    // 属于现场多出来的约束，与「现场多出的表/列/约束一律不 DROP」同一条原则。
    // 留着这个方法等于留着一把随时会被误用的刀。

    /**
     * 建视图。
     *
     * <p>用 {@code CREATE OR REPLACE} 而不是 {@code CREATE} —— PG 的普通视图没有
     * {@code IF NOT EXISTS} 语法，而调用方只在「现场没有」时才生成这条，
     * 此时 OR REPLACE 等同于 CREATE，还顺带做到了可重复执行。
     * 物化视图有 IF NOT EXISTS，但没有 OR REPLACE，走另一条分支。
     */
    public String createView(com.doenv.schema.model.ViewModel v) {
        if (v.isMaterialized()) {
            return "CREATE MATERIALIZED VIEW IF NOT EXISTS "
                    + SqlQuoter.table(schema, v.getName()) + " AS\n" + v.getQuery() + ";";
        }
        return "CREATE OR REPLACE VIEW " + SqlQuoter.table(schema, v.getName())
                + " AS\n" + v.getQuery() + ";";
    }

    /** 收紧 NOT NULL。只在 MANUAL 档产出，供人工确认后执行 */
    public String setNotNull(String table, String column) {
        return "ALTER TABLE " + SqlQuoter.table(schema, table)
                + " ALTER COLUMN " + SqlQuoter.ident(column) + " SET NOT NULL;";
    }

    // ────────────────────────── 索引 ──────────────────────────

    /**
     * 建索引。现场升级一律用 CONCURRENTLY 且必须在事务外执行 ——
     * 普通 CREATE INDEX 持 SHARE 锁会阻塞该表全部写入，千万行表是分钟级。
     */
    public String createIndex(String table, IndexModel idx) {
        String cols = idx.getColumns().stream()
                .map(c -> SqlQuoter.ident(c.getName())
                        + (c.isDesc() ? " DESC" : "")
                        + (c.isNullsFirst() ? " NULLS FIRST" : ""))
                .collect(Collectors.joining(", "));

        return "CREATE " + (idx.isUnique() ? "UNIQUE " : "") + "INDEX CONCURRENTLY IF NOT EXISTS "
                + SqlQuoter.ident(idx.getName())
                + " ON " + SqlQuoter.table(schema, table)
                + " USING " + (idx.getMethod() == null ? "btree" : idx.getMethod())
                + " (" + cols + ")"
                // 必须用谓词原文：whereNorm 为了比对把空格和括号都去掉了，
                // 拿去渲染会生成 "recipient_idisnotnull" 这种无效 SQL
                + (idx.getWhereRaw() == null || idx.getWhereRaw().isEmpty()
                        ? "" : " WHERE " + idx.getWhereRaw())
                + ";";
    }

    /**
     * 清理历史遗留的 INVALID 索引。
     *
     * <p>CREATE INDEX CONCURRENTLY 失败会留下一个 indisvalid=false 的索引：
     * 不参与查询规划、占空间、拖慢写入，而重跑时 IF NOT EXISTS 只按名字判存在
     * → 认为已存在 → 跳过 → 坏索引永久留库。所以建索引前必须先清理。
     */
    public String dropInvalidIndex(String indexName) {
        String body = "  IF EXISTS (SELECT 1 FROM pg_index i\n"
                + "               JOIN pg_class c ON c.oid = i.indexrelid\n"
                + "               JOIN pg_namespace n ON n.oid = c.relnamespace\n"
                + "             WHERE n.nspname = " + SqlQuoter.literal(schema)
                + " AND c.relname = " + SqlQuoter.literal(indexName)
                + " AND i.indisvalid = false)\n"
                + "  THEN\n"
                + "    DROP INDEX " + SqlQuoter.table(schema, indexName) + ";\n"
                + "  END IF;";
        return SqlQuoter.doBlock(body);
    }

    // ────────────────────────── 约束 ──────────────────────────

    public String addConstraint(String table, ConstraintModel c) {
        String name = c.getName() != null ? c.getName()
                : table + "_" + c.getType().toLowerCase() + "_doenv";
        String clause = switch (c.getType()) {
            case "PRIMARY_KEY" -> "PRIMARY KEY (" + quoteCols(c) + ")";
            case "UNIQUE" -> "UNIQUE (" + quoteCols(c) + ")";
            case "CHECK" -> "CHECK (" + c.getExpression() + ")";
            default -> null;
        };
        if (clause == null) {
            return null;
        }
        String body = "  IF NOT EXISTS (SELECT 1 FROM pg_constraint con\n"
                + "                   JOIN pg_class rel ON rel.oid = con.conrelid\n"
                + "                   JOIN pg_namespace n ON n.oid = rel.relnamespace\n"
                + "                 WHERE n.nspname = " + SqlQuoter.literal(schema)
                + " AND rel.relname = " + SqlQuoter.literal(table)
                + " AND con.conname = " + SqlQuoter.literal(name) + ")\n"
                + "  THEN\n"
                + "    ALTER TABLE " + SqlQuoter.table(schema, table)
                + " ADD CONSTRAINT " + SqlQuoter.ident(name) + " " + clause + ";\n"
                + "  END IF;";
        return SqlQuoter.doBlock(body);
    }

    private String quoteCols(ConstraintModel c) {
        return c.getColumns().stream().map(SqlQuoter::ident).collect(Collectors.joining(", "));
    }

    // ────────────────────────── 外键 ──────────────────────────

    /**
     * 加外键。同样用 DO $$ 守卫保证可重跑。
     *
     * <p>注意：外键会<b>校验存量数据</b> —— 子表若存在引用不到父表的值，
     * ADD CONSTRAINT 直接失败。所以调用方必须配 {@link #precheckOrphanRows} 前置探测。
     */
    public String addForeignKey(String table, ConstraintModel c) {
        String cols = c.getColumns().stream().map(SqlQuoter::ident).collect(Collectors.joining(", "));
        String refCols = c.getRefColumns().stream().map(SqlQuoter::ident).collect(Collectors.joining(", "));
        StringBuilder clause = new StringBuilder("FOREIGN KEY (" + cols + ") REFERENCES "
                + SqlQuoter.table(schema, c.getRefTable()) + " (" + refCols + ")");
        if (c.getOnDelete() != null) {
            clause.append(" ON DELETE ").append(c.getOnDelete());
        }
        if (c.getOnUpdate() != null) {
            clause.append(" ON UPDATE ").append(c.getOnUpdate());
        }
        String body = "  IF NOT EXISTS (SELECT 1 FROM pg_constraint con\n"
                + "                   JOIN pg_class rel ON rel.oid = con.conrelid\n"
                + "                   JOIN pg_namespace n ON n.oid = rel.relnamespace\n"
                + "                 WHERE n.nspname = " + SqlQuoter.literal(schema)
                + " AND rel.relname = " + SqlQuoter.literal(table)
                + " AND con.conname = " + SqlQuoter.literal(c.getName()) + ")\n"
                + "  THEN\n"
                + "    ALTER TABLE " + SqlQuoter.table(schema, table)
                + " ADD CONSTRAINT " + SqlQuoter.ident(c.getName()) + " " + clause + ";\n"
                + "  END IF;";
        return SqlQuoter.doBlock(body);
    }

    /** 孤儿引用探测：子表里引用不到父表的行数，必须为 0 才能加外键 */
    public String precheckOrphanRows(String childTable, List<String> childCols,
                                     String parentTable, List<String> parentCols) {
        StringBuilder on = new StringBuilder();
        for (int i = 0; i < childCols.size() && i < parentCols.size(); i++) {
            if (i > 0) {
                on.append(" AND ");
            }
            on.append("c.").append(SqlQuoter.ident(childCols.get(i)))
              .append(" = p.").append(SqlQuoter.ident(parentCols.get(i)));
        }
        String notNull = childCols.stream()
                .map(x -> "c." + SqlQuoter.ident(x) + " IS NOT NULL")
                .collect(Collectors.joining(" AND "));

        return "SELECT count(*) AS orphan_rows FROM " + SqlQuoter.table(schema, childTable) + " c"
                + " LEFT JOIN " + SqlQuoter.table(schema, parentTable) + " p ON " + on
                + " WHERE " + notNull + " AND p." + SqlQuoter.ident(parentCols.get(0)) + " IS NULL;";
    }

    // ────────────────────────── 序列 ──────────────────────────

    /**
     * 前推序列当前值。
     *
     * <p><b>只前推不后退</b> —— 现场序列值若已大于基准（现场用得更多），
     * 后退会让后续取号撞上已有主键。所以这里用 {@code GREATEST}
     * 把现场当前值也纳入，保证结果永远不小于现在。
     */
    public String advanceSequence(String sequence, long targetLastValue, boolean called) {
        String seq = SqlQuoter.literal(schema + "." + sequence);
        return "SELECT setval(" + seq + ", GREATEST("
                + "(SELECT last_value FROM " + SqlQuoter.table(schema, sequence) + "), "
                + targetLastValue + "), " + called + ");";
    }

    // ────────────────────────── 注释 ──────────────────────────

    public String commentOnTable(String table, String comment) {
        return "COMMENT ON TABLE " + SqlQuoter.table(schema, table)
                + " IS " + SqlQuoter.literal(comment) + ";";
    }

    public String commentOnColumn(String table, String column, String comment) {
        return "COMMENT ON COLUMN " + SqlQuoter.table(schema, table) + "." + SqlQuoter.ident(column)
                + " IS " + SqlQuoter.literal(comment) + ";";
    }

    // ────────────────────────── 前置探测 ──────────────────────────

    public String precheckDuplicates(String table, List<String> columns) {
        String cols = columns.stream().map(SqlQuoter::ident).collect(Collectors.joining(", "));
        return "SELECT " + cols + ", count(*) FROM " + SqlQuoter.table(schema, table)
                + " GROUP BY " + cols + " HAVING count(*) > 1 LIMIT 20;";
    }

    public String precheckNullCount(String table, String column) {
        return "SELECT count(*) AS null_rows FROM " + SqlQuoter.table(schema, table)
                + " WHERE " + SqlQuoter.ident(column) + " IS NULL;";
    }

    public String precheckOverlong(String table, String column, int maxLen) {
        return "SELECT count(*) AS overlong_rows FROM " + SqlQuoter.table(schema, table)
                + " WHERE length(" + SqlQuoter.ident(column) + ") > " + maxLen + ";";
    }

    public String precheckRowCount(String table) {
        return "SELECT reltuples::bigint AS estimated_rows FROM pg_class c "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = " + SqlQuoter.literal(schema)
                + " AND c.relname = " + SqlQuoter.literal(table) + ";";
    }
}
