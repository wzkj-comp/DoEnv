package com.doenv.schema.render;

import com.doenv.schema.baseline.CkTokens;
import com.doenv.schema.live.CkTopology;
import com.doenv.schema.model.CkTableModel;
import com.doenv.schema.model.ColumnModel;
import com.doenv.schema.model.TableModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ClickHouse DDL 渲染器。同时覆盖单机与分布式两种形态。
 *
 * <p>分布式的转换规则来自仓库里已在 2 分片×2 副本集群实测过的 {@code gen_dist.py}，
 * 逐条移植：
 * <ul>
 *   <li>{@code CREATE TABLE T} → {@code T_local}(Replicated*) + {@code T}(Distributed)</li>
 *   <li>{@code ADD/DROP/MODIFY/COMMENT COLUMN} → 本地表与路由表<b>各一条</b>，
 *       漏路由表会导致查询报「列不存在」，漏本地表则数据写不进</li>
 *   <li>{@code ADD INDEX} → <b>只打本地表</b>（路由表报 Code 48）</li>
 * </ul>
 *
 * <p>硬性约束：表引用<b>不写库名</b>（各现场库名不同）；
 * {@code ON CLUSTER '{cluster}'} 的单引号不能省，否则 Code 62。
 */
public class CkDdlRenderer {

    private final CkTopology topology;

    /** 集群引用。宏可用时是 {@code '{cluster}'}，否则是字面量集群名 */
    private final String clusterRef;

    /** ZooKeeper 路径模板可用（{database}/{shard} 宏都在）时才允许自动建 Replicated 表 */
    private final boolean zkMacrosAvailable;

    public CkDdlRenderer(CkTopology topology, String clusterRef, boolean zkMacrosAvailable) {
        this.topology = topology;
        this.clusterRef = clusterRef;
        this.zkMacrosAvailable = zkMacrosAvailable;
    }

    public boolean isDistributed() {
        return topology == CkTopology.DISTRIBUTED;
    }

    public boolean isZkMacrosAvailable() {
        return zkMacrosAvailable;
    }

    /** ON CLUSTER 片段；单机为空串 */
    private String onCluster() {
        return isDistributed() ? " ON CLUSTER " + clusterRef : "";
    }

    private String localName(String table) {
        return isDistributed() ? table + "_local" : table;
    }

    // ────────────────────────── 建表 ──────────────────────────

    /**
     * 建表。分布式下产出两条：先本地表，再路由表。
     * 顺序不能反 —— 路由表是 {@code AS T_local} 定义的，本地表不存在时建不出来。
     */
    public List<String> createTable(TableModel t) {
        List<String> out = new ArrayList<>();
        CkTableModel ck = t.getCk();
        if (ck == null) {
            return out;
        }

        String cols = t.getColumns().stream()
                .map(this::columnDef)
                .collect(Collectors.joining(",\n    "));

        // 跳数索引必须写进建表语句。漏掉的话：新建的表一个索引都没有，
        // 被重建的表（DROP + CREATE）原有索引全部丢失 ——
        // 实测现场 384 张表自带 645 个索引，升级后只剩 534 个，差的就是这里
        if (!ck.getIndexes().isEmpty()) {
            cols += ",\n    " + ck.getIndexes().stream()
                    .map(CkDdlRenderer::indexDef)
                    .collect(Collectors.joining(",\n    "));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(CkTokens.ident(localName(t.getName())))
                .append(onCluster()).append("\n(\n    ").append(cols).append("\n)\n");
        sb.append("ENGINE = ").append(engineClause(t.getName(), ck)).append('\n');
        if (ck.getPartitionBy() != null) {
            sb.append("PARTITION BY ").append(ck.getPartitionBy()).append('\n');
        }
        if (!ck.getPrimaryKey().isEmpty()) {
            sb.append("PRIMARY KEY ").append(keyExpr(ck.getPrimaryKey())).append('\n');
        }
        if (!ck.getOrderBy().isEmpty()) {
            sb.append("ORDER BY ").append(keyExpr(ck.getOrderBy())).append('\n');
        }
        if (ck.getSampleBy() != null) {
            sb.append("SAMPLE BY ").append(ck.getSampleBy()).append('\n');
        }
        if (ck.getTtl() != null) {
            sb.append("TTL ").append(ck.getTtl()).append('\n');
        }
        if (!ck.getSettings().isEmpty()) {
            sb.append("SETTINGS ").append(ck.getSettings().entrySet().stream()
                    .map(e -> e.getKey() + " = " + e.getValue())
                    .collect(Collectors.joining(", "))).append('\n');
        }
        if (t.getComment() != null && !t.getComment().isEmpty()) {
            sb.append("COMMENT ").append(SqlQuoter.literal(t.getComment()));
        }
        out.add(sb.toString().trim() + ";");

        if (isDistributed()) {
            out.add(createDistributedTable(t, ck));
        }
        return out;
    }

    /** 路由表：AS 本地表 + Distributed 引擎 */
    private String createDistributedTable(TableModel t, CkTableModel ck) {
        String shardKey = ck.getShardKey() != null ? ck.getShardKey() : "rand()";
        return "CREATE TABLE IF NOT EXISTS " + CkTokens.ident(t.getName()) + onCluster()
                + " AS " + CkTokens.ident(localName(t.getName())) + "\n"
                + "ENGINE = Distributed(" + clusterRef + ", currentDatabase(), "
                + SqlQuoter.literal(localName(t.getName())) + ", " + shardKey + ");";
    }

    /**
     * 引擎子句。分布式下换成 Replicated 家族并补上 ZK 两参 ——
     * 路径模板 503/503 无例外：{@code '/ClickHouse/{database}/{shard}/<表名>_local','{replica}'}
     */
    private String engineClause(String logicalTable, CkTableModel ck) {
        String family = ck.getEngineFamily();
        List<String> args = new ArrayList<>(ck.getEngineArgs());

        if (!isDistributed()) {
            return args.isEmpty() ? family : family + "(" + String.join(", ", args) + ")";
        }
        String replicated = family.startsWith("Replicated") ? family : "Replicated" + family;
        List<String> full = new ArrayList<>();
        full.add(SqlQuoter.literal("/ClickHouse/{database}/{shard}/" + localName(logicalTable)));
        full.add("'{replica}'");
        full.addAll(args);
        return replicated + "(" + String.join(", ", full) + ")";
    }

    private String columnDef(ColumnModel c) {
        StringBuilder sb = new StringBuilder();
        sb.append(CkTokens.ident(c.getName())).append(' ').append(c.getRawType());
        if (c.getDefaultRaw() != null && !c.getDefaultRaw().isBlank()) {
            sb.append(" DEFAULT ").append(c.getDefaultRaw());
        }
        if (c.getComment() != null && !c.getComment().isEmpty()) {
            sb.append(" COMMENT ").append(SqlQuoter.literal(c.getComment()));
        }
        return sb.toString();
    }

    private String keyExpr(List<String> keys) {
        if (keys.size() == 1) {
            return keys.get(0);
        }
        return "(" + String.join(", ", keys) + ")";
    }

    /**
     * 建视图。视图没有数据，{@code IF NOT EXISTS} 让它天然幂等。
     *
     * <p>分布式下加 {@code ON CLUSTER}，但<b>不改视图体里的表名</b> ——
     * 视图查的是路由表（逻辑名），本来就该是那个名字，
     * 换成 {@code _local} 会让它只看到本分片的数据。
     */
    public String createView(com.doenv.schema.model.ViewModel v) {
        return "CREATE " + (v.isMaterialized() ? "MATERIALIZED " : "") + "VIEW IF NOT EXISTS "
                + CkTokens.ident(v.getName()) + onCluster() + "\nAS " + v.getQuery() + ";";
    }

    /**
     * 用基准定义覆盖普通视图。普通 View 不存储数据，替换仅更新查询定义；
     * Materialized View 有落地数据，调用方不得走这条路径。
     */
    public String replaceView(com.doenv.schema.model.ViewModel v) {
        if (v.isMaterialized()) {
            throw new IllegalArgumentException("物化视图不能自动替换：" + v.getName());
        }
        return "CREATE OR REPLACE VIEW " + CkTokens.ident(v.getName()) + onCluster()
                + "\nAS " + v.getQuery() + ";";
    }

    /** 建表语句里的索引子句，与 ADD INDEX 共用同一份写法，避免两处漂移 */
    private static String indexDef(com.doenv.schema.model.CkIndexModel i) {
        return "INDEX " + CkTokens.ident(i.getName()) + " " + i.getExpression()
                + " TYPE " + i.getType() + " GRANULARITY " + i.getGranularity();
    }

    /**
     * 补跳数索引。纯元数据操作，秒级返回，不重写数据也不锁表。
     *
     * <p>刻意不生成 {@code MATERIALIZE INDEX}：那要扫全表重建索引文件，
     * 在大表上是小时级的重操作，不该由自动升级替现场决定。
     * 存量数据要不要建索引，交给报告提示人工判断。
     */
    public String addIndex(String table, com.doenv.schema.model.CkIndexModel idx) {
        // 分布式下只打本地表：Distributed 引擎不存索引，对路由表 ADD INDEX 报 Code 48
        return "ALTER TABLE " + CkTokens.ident(localName(table)) + onCluster()
                + " ADD " + indexDef(idx).replaceFirst("^INDEX ", "INDEX IF NOT EXISTS ") + ";";
    }

    /**
     * 重建空表：先删再按基准建。
     *
     * <p><b>只允许用于已确认为空的表</b>。引擎/分区键/排序键/主键在 CK 里建表后不可 ALTER，
     * 空表没有数据可丢，重建是零风险的；有数据的表必须走人工迁移，绝不能走这条路。
     * 调用方必须配 {@link #precheckRowCount} 的 ZERO_COUNT 探测，
     * 由执行器在<b>执行时刻</b>再验一次 —— 比对到执行之间可能有数据写进来。
     */
    public List<String> rebuildEmptyTable(TableModel t) {
        List<String> out = new ArrayList<>();
        // 分布式下先删路由表再删本地表：路由表依赖本地表定义
        if (isDistributed()) {
            out.add("DROP TABLE IF EXISTS " + CkTokens.ident(t.getName()) + onCluster() + ";");
        }
        out.add("DROP TABLE IF EXISTS " + CkTokens.ident(localName(t.getName())) + onCluster() + ";");
        out.addAll(createTable(t));
        return out;
    }

    /** 收尾迁移专用：只改表名，不删除原表；分布式下带 ON CLUSTER。 */
    public String renameTable(String from, String to) {
        return "RENAME TABLE " + CkTokens.ident(from) + " TO " + CkTokens.ident(to)
                + onCluster() + ";";
    }

    /** 收尾迁移专用：临时 Distributed 路由表已不再使用时才删除。 */
    public String dropTable(String table) {
        return "DROP TABLE IF EXISTS " + CkTokens.ident(table) + onCluster() + ";";
    }

    // ────────────────────────── 加列 ──────────────────────────

    /**
     * 加列。CK 的 ADD COLUMN 是<b>纯元数据操作</b>，几十亿行的表也是秒级。
     *
     * <p>分布式下必须<b>成对</b>：本地表与路由表各一条。这两条要作为不可分割的
     * 原子步骤相邻执行，只成功一半会留下查询报错的表。
     */
    public List<String> addColumn(String table, ColumnModel c) {
        List<String> out = new ArrayList<>();
        String def = CkTokens.ident(c.getName()) + " " + c.getRawType()
                + (c.getDefaultRaw() != null && !c.getDefaultRaw().isBlank()
                        ? " DEFAULT " + c.getDefaultRaw() : "")
                + (c.getComment() != null && !c.getComment().isEmpty()
                        ? " COMMENT " + SqlQuoter.literal(c.getComment()) : "");

        out.add("ALTER TABLE " + CkTokens.ident(localName(table)) + onCluster()
                + " ADD COLUMN IF NOT EXISTS " + def + ";");
        if (isDistributed()) {
            out.add("ALTER TABLE " + CkTokens.ident(table) + onCluster()
                    + " ADD COLUMN IF NOT EXISTS " + def + ";");
        }
        return out;
    }

    /** 显式字段改名；分布式下本地表与路由表成对执行。 */
    public List<String> renameColumn(String table, String from, String to) {
        List<String> out = new ArrayList<>();
        String tail = " RENAME COLUMN IF EXISTS " + CkTokens.ident(from)
                + " TO " + CkTokens.ident(to) + ";";
        out.add("ALTER TABLE " + CkTokens.ident(localName(table)) + onCluster() + tail);
        if (isDistributed()) {
            out.add("ALTER TABLE " + CkTokens.ident(table) + onCluster() + tail);
        }
        return out;
    }

    /** 只给路由表补列 —— 修复本地表有、路由表没有的漂移 */
    public List<String> addColumnToRouteOnly(String table, ColumnModel c) {
        String def = CkTokens.ident(c.getName()) + " " + c.getRawType();
        return List.of("ALTER TABLE " + CkTokens.ident(table) + onCluster()
                + " ADD COLUMN IF NOT EXISTS " + def + ";");
    }

    // ────────────────────────── 注释 ──────────────────────────

    /** 列注释。分布式下同样成对 */
    public List<String> commentColumn(String table, String column, String comment) {
        List<String> out = new ArrayList<>();
        String tail = " COMMENT COLUMN IF EXISTS " + CkTokens.ident(column)
                + " " + SqlQuoter.literal(comment) + ";";
        out.add("ALTER TABLE " + CkTokens.ident(localName(table)) + onCluster() + tail);
        if (isDistributed()) {
            out.add("ALTER TABLE " + CkTokens.ident(table) + onCluster() + tail);
        }
        return out;
    }

    /** 表注释。分布式下只打本地表 —— 路由表的注释没有意义 */
    public List<String> modifyTableComment(String table, String comment) {
        return List.of("ALTER TABLE " + CkTokens.ident(localName(table)) + onCluster()
                + " MODIFY COMMENT " + SqlQuoter.literal(comment) + ";");
    }

    // ────────────────────────── 改列类型 ──────────────────────────

    /**
     * 改列类型。<b>只在 CONFIRM 及以上档位使用</b> ——
     * CK 改类型要重写该列的所有 part，大表是小时级且需额外磁盘。
     */
    public List<String> modifyColumn(String table, ColumnModel c) {
        List<String> out = new ArrayList<>();
        String tail = " MODIFY COLUMN IF EXISTS " + CkTokens.ident(c.getName())
                + " " + c.getRawType() + ";";
        out.add("ALTER TABLE " + CkTokens.ident(localName(table)) + onCluster() + tail);
        if (isDistributed()) {
            out.add("ALTER TABLE " + CkTokens.ident(table) + onCluster() + tail);
        }
        return out;
    }

    // ────────────────────────── 前置探测 ──────────────────────────

    /** 表是否已存在 */
    public String precheckTableExists(String table) {
        return "SELECT count() FROM system.tables WHERE database = currentDatabase() "
                + "AND name = " + SqlQuoter.literal(localName(table));
    }

    /**
     * ZooKeeper 残留探测。
     *
     * <p>Replicated 表建表会校验 ZK 元数据，<b>{@code IF NOT EXISTS} 挡不住 Code 342</b>
     * —— 历史 DROP 没走 SYSTEM DROP REPLICA 时 ZK 里会留下路径，此时建表直接失败。
     * 实测曾出现 24 个 Code 342 级联出 742 个 Code 60。
     */
    public String precheckZkResidue(String table) {
        return "SELECT count() FROM system.zookeeper WHERE path = "
                + SqlQuoter.literal("/ClickHouse/" + "{database}" + "/{shard}/" + localName(table));
    }

    /** 表行数，用于判断改列类型的代价 */
    public String precheckRowCount(String table) {
        return "SELECT sum(rows) FROM system.parts WHERE database = currentDatabase() "
                + "AND table = " + SqlQuoter.literal(localName(table)) + " AND active";
    }

    /** 未完成的 mutation —— 有积压时不宜再下发重型 DDL */
    public String precheckPendingMutations() {
        return "SELECT count() FROM system.mutations WHERE database = currentDatabase() "
                + "AND is_done = 0";
    }

    /** 分布式 DDL 队列残留 */
    public String precheckDdlQueue() {
        return "SELECT count() FROM system.distributed_ddl_queue WHERE status != 'Finished'";
    }

    // ────────────────────────── 分片键推导 ──────────────────────────

    /**
     * 推导分片键。规则逐条移植自 {@code gen_dist.py} 的 {@code shard_key()}，
     * 已在真实集群实测：分片键必须<b>非 Nullable</b>，
     * 否则 javaHash 返回 Nullable(Int32)，CK 报 Code 53。
     *
     * <p><b>只用于新建表</b>。已存在的表永不重算 —— 分片键写死在 Distributed 引擎参数里。
     */
    public static String deriveShardKey(TableModel t) {
        Map<String, ColumnModel> byName = new LinkedHashMap<>();
        t.getColumns().forEach(c -> byName.put(c.getName(), c));

        String[] caseIdNames = {"caseid", "CaseID", "CASE_ID", "caseId", "case_id"};
        String[] idNames = {"id", "ID"};

        // ① 非 Nullable 的 String 类型 caseid 族
        for (String n : caseIdNames) {
            ColumnModel c = byName.get(n);
            if (c != null && isPlainString(c)) {
                return "javaHash(" + n + ")";
            }
        }
        // ② 非 Nullable 的 String 类型 id 族
        for (String n : idNames) {
            ColumnModel c = byName.get(n);
            if (c != null && isPlainString(c)) {
                return "javaHash(" + n + ")";
            }
        }
        // ③ 任意类型的 caseid 族
        for (String n : caseIdNames) {
            if (byName.containsKey(n)) {
                return "javaHash(ifNull(" + n + ",''))";
            }
        }
        // ④ 任意类型的 id 族
        for (String n : idNames) {
            if (byName.containsKey(n)) {
                return "javaHash(ifNull(toString(" + n + "),''))";
            }
        }
        return "rand()";
    }

    private static boolean isPlainString(ColumnModel c) {
        String t = c.getRawType() == null ? "" : c.getRawType().replaceAll("\\s+", "");
        return t.equalsIgnoreCase("String");
    }

    /** 分片键退化为 rand() 时需人工确认：Replacing 引擎跨分片无法去重 */
    public static boolean shardKeyNeedsReview(String shardKey) {
        return "rand()".equals(shardKey);
    }

    // ────────────────────────── 校验 ──────────────────────────

    /** CK 建表后不可 ALTER 的部分，差异只能报告不能自动改 */
    public static boolean isImmutableDiff(CkTableModel baseline, CkTableModel live) {
        if (baseline == null || live == null) {
            return false;
        }
        return !nz(baseline.getEngineFamily()).equalsIgnoreCase(nz(live.getEngineFamily()))
                || !nz(baseline.getPartitionBy()).equals(nz(live.getPartitionBy()))
                || !baseline.getOrderBy().equals(live.getOrderBy())
                || !baseline.getPrimaryKey().equals(live.getPrimaryKey());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * 生成物自检：CK 侧的红线。
     *
     * @param allowDropTable 是否允许出现 {@code DROP TABLE}。
     *                       只有「已确认为空表的重建」这一类可以放行，其余一律禁止
     */
    public static List<String> validate(List<String> statements, boolean allowDropTable) {
        List<String> errors = new ArrayList<>();
        for (String sql : statements) {
            String u = sql.toUpperCase(Locale.ROOT);
            if (u.contains("DROP DATABASE")) {
                errors.add("生成了 DROP DATABASE：" + head(sql));
            }
            if (u.contains("DROP TABLE") && !allowDropTable) {
                errors.add("生成了 DROP TABLE：" + head(sql));
            }
            if (u.contains("TRUNCATE")) {
                errors.add("生成了 TRUNCATE：" + head(sql));
            }
            if (u.contains("DROP COLUMN")) {
                errors.add("生成了 DROP COLUMN：" + head(sql));
            }
            // CK 表引用绝不能带库名——各现场库名不同
            if (u.matches(".*\\b(TABLE|INTO|FROM)\\s+`?[A-Za-z0-9_]+`?\\.`?[A-Za-z0-9_]+`?.*")
                    && !u.contains("SYSTEM.") && !u.contains("CURRENTDATABASE()")) {
                errors.add("表引用带了库名，各现场库名不同：" + head(sql));
            }
            if ((u.startsWith("CREATE TABLE") || u.startsWith("ALTER TABLE"))
                    && u.contains("ON CLUSTER") && !sql.contains("'")) {
                errors.add("ON CLUSTER 的集群名未加单引号，会报 Code 62：" + head(sql));
            }
        }
        return errors;
    }

    /**
     * 按差异项自检：只有 {@code CK_REBUILD_EMPTY} 的语句允许含 DROP TABLE。
     * 这样 DROP 的放行范围被死死绑在「已验证为空表」这一个场景上，
     * 别的差异类型即使误生成了 DROP 也会被拦下。
     */
    public static List<String> validateItems(List<com.doenv.schema.diff.DiffItem> items) {
        List<String> errors = new ArrayList<>();
        for (com.doenv.schema.diff.DiffItem d : items) {
            boolean allowDrop = d.getType() == com.doenv.schema.diff.DiffType.CK_REBUILD_EMPTY
                    || d.getType() == com.doenv.schema.diff.DiffType.FINAL_ITEM_LABEL_ENGINE_MIGRATION;
            for (String e : validate(d.getDdl(), allowDrop)) {
                errors.add("[" + d.getType() + "/" + d.getTableName() + "] " + e);
            }
            if (d.getType() == com.doenv.schema.diff.DiffType.FINAL_ITEM_LABEL_ENGINE_MIGRATION
                    && d.getDdl().stream().anyMatch(sql -> sql.toUpperCase(Locale.ROOT).contains("DROP TABLE")
                    && !sql.contains("`vax_item_label__doenv_new`"))) {
                errors.add("[" + d.getTableName() + "] 收尾迁移只能删除临时表 vax_item_label__doenv_new");
            }
            // 空表重建必须带行数探测，否则可能删掉有数据的表
            if (d.getType() == com.doenv.schema.diff.DiffType.CK_REBUILD_EMPTY
                    && d.getPrechecks().stream().noneMatch(
                    r -> r.getExpect() == com.doenv.schema.diff.PrecheckRule.Expect.ZERO_COUNT)) {
                errors.add("[" + d.getTableName() + "] 空表重建缺少行数探测，拒绝执行");
            }
            if (d.getType() == com.doenv.schema.diff.DiffType.COLUMN_RENAME) {
                if (d.getDdl().isEmpty() || d.getDdl().size() > 2) {
                    errors.add("[" + d.getTableName() + "] 字段改名语句数量异常");
                }
                for (String sql : d.getDdl()) {
                    String u = sql.toUpperCase(Locale.ROOT);
                    if (!u.contains("RENAME COLUMN IF EXISTS")
                            || !sql.contains(CkTokens.ident(d.getObjectName()))) {
                        errors.add("[" + d.getTableName() + "] 字段改名 SQL 与清单目标不一致："
                                + head(sql));
                    }
                }
            }
        }
        return errors;
    }

    private static String head(String s) {
        String one = s.replaceAll("\\s+", " ");
        return one.length() > 140 ? one.substring(0, 140) + "…" : one;
    }
}
