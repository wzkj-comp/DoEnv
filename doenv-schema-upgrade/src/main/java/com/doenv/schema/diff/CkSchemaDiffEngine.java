package com.doenv.schema.diff;

import com.doenv.schema.model.CkTableModel;
import com.doenv.schema.model.ColumnModel;
import com.doenv.schema.model.SchemaModel;
import com.doenv.schema.model.TableModel;
import com.doenv.schema.render.CkDdlRenderer;
import com.doenv.schema.rename.ColumnRenameRule;
import com.doenv.schema.rename.ColumnRenameRules;

import com.doenv.schema.model.CkIndexModel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * ClickHouse 结构差异引擎。
 *
 * <p>与 PG 那套同样<b>严格单向</b>，但 CK 有几条独有的红线：
 * <ul>
 *   <li><b>标识符大小写敏感</b>：{@code IF NOT EXISTS} 按精确名匹配，
 *       现场是 {@code CaseID} 而基准是 {@code caseid} 时会<b>真的加出第二个列</b>，
 *       不报错、不丢数据，但分片键可能指向空列。所以只做精确匹配，
 *       忽略大小写命中一律判 MANUAL</li>
 *   <li><b>引擎 / 分区键 / 排序键 / 主键不可 ALTER</b>：一律 MANUAL，
 *       只记录跳过原因，不生成迁移 SQL</li>
 *   <li><b>建 Replicated 表要防 ZK 残留</b>：IF NOT EXISTS 挡不住 Code 342</li>
 * </ul>
 */
public class CkSchemaDiffEngine {

    /** 取表当前行数。返回负数表示取不到（此时按「有数据」保守处理） */
    @FunctionalInterface
    public interface TableRowCounter {
        long count(String table);
    }

    private final CkDdlRenderer renderer;
    private final TableRowCounter rowCounter;
    private final List<ColumnRenameRule> renameRules;

    public CkSchemaDiffEngine(CkDdlRenderer renderer) {
        this(renderer, t -> -1L, List.of());
    }

    public CkSchemaDiffEngine(CkDdlRenderer renderer, TableRowCounter rowCounter) {
        this(renderer, rowCounter, List.of());
    }

    public CkSchemaDiffEngine(CkDdlRenderer renderer, TableRowCounter rowCounter,
                              List<ColumnRenameRule> renameRules) {
        this.renderer = renderer;
        this.rowCounter = rowCounter == null ? t -> -1L : rowCounter;
        this.renameRules = renameRules == null ? List.of() : List.copyOf(renameRules);
    }

    public List<DiffItem> diff(SchemaModel baseline, SchemaModel live, DiffOptions opt) {
        List<DiffItem> out = new ArrayList<>();
        Set<String> matched = new HashSet<>();

        for (TableModel bt : baseline.getTables()) {
            TableModel lt = findExact(live, bt.getName());

            if (lt == null) {
                TableModel ci = findIgnoreCase(live, bt.getName());
                if (ci != null) {
                    matched.add(ci.getName());
                    out.add(DiffItem.of(DiffType.CASE_MISMATCH, DiffLevel.MANUAL, bt.getName(), null)
                            .note("现场表名为 " + ci.getName() + "，与基准仅大小写不同。"
                                    + "CK 标识符大小写敏感，自动建表会建出第二张表"));
                    continue;
                }
                diffMissingTable(bt, out);
                continue;
            }

            matched.add(lt.getName());
            diffColumns(bt, lt, opt, out);
            diffIndexes(bt, lt, out);
            diffEngineLevel(bt, lt, out);
            if (opt.isCheckComment()) {
                diffTableComment(bt, lt, out);
            }
            if (renderer.isDistributed()) {
                diffRouteDrift(bt, lt, out);
            }
        }

        diffViews(baseline, live, opt, out);

        if (opt.isReportExtra()) {
            for (TableModel lt : live.getTables()) {
                if (!matched.contains(lt.getName())) {
                    out.add(DiffItem.of(DiffType.TABLE_EXTRA, DiffLevel.INFO, lt.getName(), null)
                            .note(classifyExtra(lt.getName())));
                }
            }
        }
        return out;
    }

    // ────────────────────────── 视图 ──────────────────────────

    /**
     * 比对视图。<b>只补不删</b>：基准有而现场没有就建，现场多出的不动。
     *
     * <p>普通视图不存储数据，同名但定义不同就以纯净库为准自动替换；
     * Materialized View 有落地数据，仍绝不自动重建或覆盖。
     */
    private void diffViews(SchemaModel baseline, SchemaModel live, DiffOptions opt,
                           List<DiffItem> out) {
        for (com.doenv.schema.model.ViewModel bv : baseline.getViews()) {
            com.doenv.schema.model.ViewModel lv = live.findView(bv.getName());
            if (lv == null) {
                DiffItem d = DiffItem.of(DiffType.VIEW_MISSING, DiffLevel.AUTO, bv.getName(), null);
                d.setBaselineDef((bv.isMaterialized() ? "物化视图" : "视图")
                        + "，查询体 " + bv.getQuery().length() + " 字符");
                d.sql(renderer.createView(bv));
                d.note("建视图：无数据、不重建表，纯元数据操作");
                out.add(d);
                continue;
            }
            if (bv.isMaterialized() != lv.isMaterialized()) {
                out.add(DiffItem.of(DiffType.VIEW_DIFF, DiffLevel.MANUAL, bv.getName(), null)
                        .note("普通视图与物化视图不能互转，需人工处理（物化视图自带存储）"));
                continue;
            }
            if (!bv.normalizedQuery().equals(lv.normalizedQuery())) {
                DiffItem d = DiffItem.of(DiffType.VIEW_DIFF, DiffLevel.AUTO, bv.getName(), null);
                d.setBaselineDef(head(bv.getQuery()));
                d.setLiveDef(head(lv.getQuery()));
                d.sql(renderer.replaceView(bv));
                d.note("普通视图定义与基准不同：不涉及表数据，使用 CREATE OR REPLACE VIEW 对齐");
                out.add(d);
            }
        }
        if (opt.isReportExtra()) {
            for (com.doenv.schema.model.ViewModel lv : live.getViews()) {
                if (baseline.findView(lv.getName()) == null) {
                    out.add(DiffItem.of(DiffType.VIEW_EXTRA, DiffLevel.INFO,
                                    lv.getName(), null)
                            .note("现场多出的视图，只报告不处理"));
                }
            }
        }
    }

    private static String head(String q) {
        String one = q == null ? "" : q.replaceAll("\\s+", " ").trim();
        return one.length() > 120 ? one.substring(0, 120) + "…" : one;
    }

    // ────────────────────────── 跳数索引 ──────────────────────────

    /**
     * 比对跳数索引。<b>只补不删</b>，与整套单向原则一致。
     *
     * <p>{@code ALTER TABLE ... ADD INDEX} 是纯元数据操作：秒级返回、不重写数据、
     * 不需要重建表，所以缺索引可以自动补。但它<b>只对之后写入的数据生效</b>，
     * 存量数据要 {@code MATERIALIZE INDEX} 才会真正建出索引文件，那是扫全表的重操作，
     * 不由工具替现场决定，只在有数据时提示。
     *
     * <p>同名但定义不同的索引只报告不处理 —— 改索引必须先 DROP，那是删除操作。
     */
    private void diffIndexes(TableModel bt, TableModel lt, List<DiffItem> out) {
        if (bt.getCk() == null || lt.getCk() == null) {
            return;
        }
        Map<String, CkIndexModel> liveByName = new LinkedHashMap<>();
        for (CkIndexModel i : lt.getCk().getIndexes()) {
            liveByName.put(i.getName().toLowerCase(Locale.ROOT), i);
        }

        List<CkIndexModel> missing = new ArrayList<>();
        for (CkIndexModel bi : bt.getCk().getIndexes()) {
            CkIndexModel li = liveByName.get(bi.getName().toLowerCase(Locale.ROOT));
            if (li == null) {
                missing.add(bi);
                continue;
            }
            if (!bi.normalizedExpression().equals(li.normalizedExpression())
                    || !bi.normalizedType().equals(li.normalizedType())) {
                DiffItem diff = DiffItem.of(DiffType.CK_INDEX_DIFF, DiffLevel.INFO,
                        bt.getName(), bi.getName());
                diff.setLiveDef(li.getExpression() + " TYPE " + li.getType());
                diff.setBaselineDef(bi.getExpression() + " TYPE " + bi.getType());
                diff.note("同名索引定义不同。改索引需先 DROP INDEX，属删除操作，只报告不处理");
                out.add(diff);
            }
        }
        if (missing.isEmpty()) {
            return;
        }

        long rows = rowCounter.count(bt.getName());
        DiffItem d = DiffItem.of(DiffType.CK_INDEX_MISSING, DiffLevel.AUTO, bt.getName(), null);
        d.setBaselineDef("基准 " + bt.getCk().getIndexes().size() + " 个索引");
        d.setLiveDef("现场缺 " + missing.size() + " 个："
                + missing.stream().map(CkIndexModel::getName).limit(6).toList());
        missing.forEach(i -> d.sql(renderer.addIndex(bt.getName(), i)));
        d.note("补跳数索引（元数据操作，不重建表、不重写数据）");
        if (rows > 0) {
            d.setEstimatedRows(rows);
            d.note("该表已有 " + rows + " 行存量数据，新索引只对后续写入生效；"
                    + "如需让存量数据也走索引，需人工执行 MATERIALIZE INDEX（扫全表的重操作）");
        }
        out.add(d);
    }

    // ────────────────────────── 缺表 ──────────────────────────

    private void diffMissingTable(TableModel bt, List<DiffItem> out) {
        // 分布式下新建表要推导分片键；已存在的表永不重算
        CkTableModel ck = bt.getCk();
        if (renderer.isDistributed() && ck != null && ck.getShardKey() == null) {
            String sk = CkDdlRenderer.deriveShardKey(bt);
            ck.setShardKey(sk);
            ck.setShardKeyNeedsReview(CkDdlRenderer.shardKeyNeedsReview(sk));
        }

        // 建 Replicated 表必须先探 ZK 残留，IF NOT EXISTS 挡不住 Code 342
        DiffLevel level = renderer.isDistributed() ? DiffLevel.CONFIRM : DiffLevel.AUTO;

        // 分布式下宏不全就不能自动建：ZK 路径里的 {database}/{shard} 无法用字面量替代，
        // 不同 shard 必须落不同路径，写死会让所有副本抢同一个路径
        if (renderer.isDistributed() && !renderer.isZkMacrosAvailable()) {
            out.add(DiffItem.of(DiffType.TABLE_MISSING, DiffLevel.MANUAL, bt.getName(), null)
                    .note("分布式现场缺 {database}/{shard} 宏，ZooKeeper 路径无法安全生成，转人工"));
            return;
        }

        DiffItem d = DiffItem.of(DiffType.TABLE_MISSING, level, bt.getName(), null);
        d.setBaselineDef(bt.getColumns().size() + " 列 / " + (ck == null ? "?" : ck.getEngineFamily()));
        renderer.createTable(bt).forEach(d::sql);

        if (renderer.isDistributed()) {
            d.precheck(PrecheckRule.expectZero(renderer.precheckZkResidue(bt.getName()),
                    "ZooKeeper 残留元数据",
                    "ZK 里有该表的残留路径，建 Replicated 表会报 Code 342，"
                            + "需先 SYSTEM DROP REPLICA"));
            if (ck != null && ck.isShardKeyNeedsReview()) {
                d.note("分片键退化为 rand()：Replacing 引擎跨分片无法去重，请人工确认");
            }
        }
        out.add(d);
    }

    // ────────────────────────── 列 ──────────────────────────

    private void diffColumns(TableModel bt, TableModel lt, DiffOptions opt, List<DiffItem> out) {
        Set<String> matched = new HashSet<>();

        for (ColumnModel bc : bt.getColumns()) {
            ColumnModel lc = findExactColumn(lt, bc.getName());
            ColumnRenameRule rename = ColumnRenameRules.byTarget(
                    renameRules, bt.getName(), bc.getName());
            ColumnModel old = rename == null ? null : findExactColumn(lt, rename.getFrom());

            if (old != null) {
                matched.add(old.getName());
                if (lc != null) {
                    matched.add(lc.getName());
                    if (findExactColumn(bt, rename.getFrom()) != null) {
                        diffOneColumn(bt, bc, lc, opt, out);
                        continue;
                    }
                    out.add(DiffItem.of(DiffType.COLUMN_RENAME_CONFLICT, DiffLevel.MANUAL,
                                    bt.getName(), bc.getName())
                            .note("rename 清单要求 " + rename.getFrom() + " → " + rename.getTo()
                                    + "，但现场新旧列同时存在。为避免覆盖数据，禁止自动改名"));
                    diffOneColumn(bt, bc, lc, opt, out);
                    continue;
                }

                DiffItem d = DiffItem.of(DiffType.COLUMN_RENAME, DiffLevel.CONFIRM,
                        bt.getName(), bc.getName());
                d.setLiveDef(rename.getFrom());
                d.setBaselineDef(rename.getTo());
                renderer.renameColumn(bt.getName(), rename.getFrom(), rename.getTo())
                        .forEach(d::sql);
                d.note("按 rename 清单改名，保留原列数据"
                        + (rename.getReason() == null || rename.getReason().isBlank()
                        ? "" : "：" + rename.getReason()));
                if (renderer.isDistributed()) {
                    d.note("分布式：本地表与路由表各一条，必须成对执行");
                }
                out.add(d);
                diffOneColumn(bt, bc, old, opt, out);
                continue;
            }

            if (lc == null) {
                ColumnModel ci = findColumnIgnoreCase(lt, bc.getName());
                if (ci != null) {
                    matched.add(ci.getName());
                    out.add(DiffItem.of(DiffType.CASE_MISMATCH, DiffLevel.MANUAL,
                                    bt.getName(), bc.getName())
                            .note("现场列名为 " + ci.getName() + "，与基准仅大小写不同。"
                                    + "ADD COLUMN IF NOT EXISTS 按精确名匹配挡不住，"
                                    + "会真的加出第二个列且不报错"));
                    continue;
                }
                // 加列是纯元数据操作，几十亿行也秒级
                DiffItem d = DiffItem.of(DiffType.COLUMN_MISSING, DiffLevel.AUTO,
                        bt.getName(), bc.getName());
                d.setBaselineDef(bc.getRawType());
                renderer.addColumn(bt.getName(), bc).forEach(d::sql);
                if (renderer.isDistributed()) {
                    d.note("分布式：本地表与路由表各一条，必须成对执行");
                }
                out.add(d);
                continue;
            }
            matched.add(lc.getName());
            diffOneColumn(bt, bc, lc, opt, out);
        }

        if (opt.isReportExtra()) {
            for (ColumnModel lc : lt.getColumns()) {
                if (!matched.contains(lc.getName())) {
                    ColumnRenameRule stale = ColumnRenameRules.bySource(
                            renameRules, bt.getName(), lc.getName());
                    if (stale != null) {
                        out.add(DiffItem.of(DiffType.COLUMN_RENAME_CONFLICT, DiffLevel.MANUAL,
                                        bt.getName(), stale.getTo())
                                .note("rename 清单登记了 " + stale.getFrom() + " → " + stale.getTo()
                                        + "，但目标列不在当前纯净库表结构中。保留旧列，需修正清单"));
                        continue;
                    }
                    out.add(DiffItem.of(DiffType.COLUMN_EXTRA, DiffLevel.INFO,
                                    bt.getName(), lc.getName())
                            .note("现场多出的列，只报告不处理"));
                }
            }
        }
    }

    private void diffOneColumn(TableModel bt, ColumnModel bc, ColumnModel lc,
                               DiffOptions opt, List<DiffItem> out) {
        String table = bt.getName();
        CkTableModel ck = bt.getCk();

        if (!Objects.equals(bc.getNormType(), lc.getNormType())) {
            // 横切规则：差异列若在排序键/分区键/主键里，CK 直接拒绝 ALTER，
            // 无条件降为 MANUAL，不管类型变化本身是放宽还是收窄
            boolean onKey = ck != null && (
                    containsIgnoreCase(ck.getOrderBy(), bc.getName())
                            || containsIgnoreCase(ck.getPrimaryKey(), bc.getName())
                            || (ck.getPartitionBy() != null
                                && ck.getPartitionBy().contains(bc.getName())));

            DiffLevel level = onKey ? DiffLevel.MANUAL : DiffLevel.CONFIRM;
            DiffItem d = DiffItem.of(DiffType.TYPE_WIDEN_HEAVY, level, table, bc.getName());
            d.setLiveDef(lc.getRawType());
            d.setBaselineDef(bc.getRawType());
            d.precheck(PrecheckRule.info(renderer.precheckRowCount(table), "表行数"));

            if (onKey) {
                d.note("该列在排序键/分区键/主键中，CK 无法直接修改，已跳过");
            } else {
                renderer.modifyColumn(table, bc).forEach(d::sql);
                d.note("CK 改类型要重写该列所有 part，大表是小时级且需额外磁盘");
            }
            out.add(d);
        }

        if (opt.isCheckComment()
                && bc.getComment() != null && !bc.getComment().isEmpty()
                && !Objects.equals(bc.getComment(), lc.getComment())) {
            DiffItem d = DiffItem.of(DiffType.COLUMN_COMMENT_DIFF, DiffLevel.AUTO,
                    table, bc.getName());
            d.setLiveDef(nz(lc.getComment()));
            d.setBaselineDef(bc.getComment());
            renderer.commentColumn(table, bc.getName(), bc.getComment()).forEach(d::sql);
            out.add(d);
        }
    }

    // ────────────────────────── 引擎层 ──────────────────────────

    /** 引擎 / 分区键 / 排序键 / 主键差异 —— CK 建表后改不了，一律 MANUAL，只记录原因 */
    private void diffEngineLevel(TableModel bt, TableModel lt, List<DiffItem> out) {
        CkTableModel b = bt.getCk();
        CkTableModel l = lt.getCk();
        if (!CkDdlRenderer.isImmutableDiff(b, l)) {
            return;
        }
        long rows = rowCounter.count(bt.getName());
        String liveSig = l == null ? "?" : l.immutableSignature();
        String baseSig = b == null ? "?" : b.immutableSignature();
        String changed = describeImmutableChange(b, l);

        // ── 空表：重建零数据风险，可自动执行 ──
        if (rows == 0) {
            DiffItem d = DiffItem.of(DiffType.CK_REBUILD_EMPTY, DiffLevel.CONFIRM,
                    bt.getName(), null);
            d.setLiveDef(liveSig);
            d.setBaselineDef(baseSig);
            d.note("空表（0 行），直接按基准重建。变更项：" + changed);
            // 执行时刻必须再验一次——比对到执行之间可能有数据写进来
            d.precheck(PrecheckRule.expectZero(renderer.precheckRowCount(bt.getName()),
                    bt.getName() + " 当前行数",
                    "表已有数据，重建会丢数据，改走人工迁移"));
            renderer.rebuildEmptyTable(bt).forEach(d::sql);
            out.add(d);
            return;
        }

        // ── 有数据：一律跳过，把理由记全 ──
        DiffItem d = DiffItem.of(DiffType.CK_IMMUTABLE_DIFF, DiffLevel.MANUAL, bt.getName(), null);
        d.setLiveDef(liveSig);
        d.setBaselineDef(baseSig);
        d.setEstimatedRows(rows < 0 ? null : rows);
        if (rows < 0) {
            d.note("跳过原因：表行数未知（探测失败），按有数据保守处理");
        } else {
            d.note("跳过原因：表有数据（" + rows + " 行），重建会丢数据");
        }
        d.note("变更项：" + changed);
        d.note("CK 的引擎/分区键/排序键/主键建表后不可 ALTER，已跳过");
        out.add(d);
    }

    /** 把不可变部分的差异逐项说清楚，供跳过日志展示 */
    private String describeImmutableChange(CkTableModel b, CkTableModel l) {
        if (b == null || l == null) {
            return "无法比较";
        }
        List<String> parts = new ArrayList<>();
        if (!nz(b.getEngineFamily()).equalsIgnoreCase(nz(l.getEngineFamily()))) {
            parts.add("引擎 " + nz(l.getEngineFamily()) + " → " + nz(b.getEngineFamily()));
        }
        if (!nz(b.getPartitionBy()).equals(nz(l.getPartitionBy()))) {
            parts.add("分区键 " + orNone(l.getPartitionBy()) + " → " + orNone(b.getPartitionBy()));
        }
        if (!b.getOrderBy().equals(l.getOrderBy())) {
            parts.add("排序键 [" + String.join(",", l.getOrderBy())
                    + "] → [" + String.join(",", b.getOrderBy()) + "]");
        }
        if (!b.getPrimaryKey().equals(l.getPrimaryKey())) {
            parts.add("主键 [" + String.join(",", l.getPrimaryKey())
                    + "] → [" + String.join(",", b.getPrimaryKey()) + "]");
        }
        return parts.isEmpty() ? "无" : String.join("；", parts);
    }

    private static String orNone(String s) {
        return (s == null || s.isEmpty()) ? "(无)" : s;
    }

    private void diffTableComment(TableModel bt, TableModel lt, List<DiffItem> out) {
        if (bt.getComment() == null || bt.getComment().isEmpty()
                || Objects.equals(bt.getComment(), lt.getComment())) {
            return;
        }
        DiffItem d = DiffItem.of(DiffType.TABLE_COMMENT_DIFF, DiffLevel.AUTO, bt.getName(), null);
        d.setLiveDef(nz(lt.getComment()));
        d.setBaselineDef(bt.getComment());
        renderer.modifyTableComment(bt.getName(), bt.getComment()).forEach(d::sql);
        out.add(d);
    }

    // ────────────────────────── 分布式路由表漂移 ──────────────────────────

    /**
     * 本地表有、路由表没有的列。
     *
     * <p>这不是理论风险：{@code gen_dist.py} 的 LOCAL_ONLY 正则不含 {@code MODIFY ORDER BY}，
     * 导致 2026-05 那条多子句 ALTER 也发给了路由表，而路由表不支持 MODIFY ORDER BY，
     * <b>整条语句在路由表上失败</b> —— 跑过该增量的现场，路由表大概率就缺那一列。
     */
    private void diffRouteDrift(TableModel bt, TableModel lt, List<DiffItem> out) {
        if (!lt.isDistRouteExists()) {
            DiffItem d = DiffItem.of(DiffType.CK_TOPOLOGY_MISMATCH, DiffLevel.CONFIRM,
                    bt.getName(), null);
            d.note("有本地表但缺 Distributed 路由表，查询会直接找不到表");
            d.sql("CREATE TABLE IF NOT EXISTS " + com.doenv.schema.baseline.CkTokens.ident(bt.getName())
                    + " AS " + com.doenv.schema.baseline.CkTokens.ident(bt.getName() + "_local") + ";");
            out.add(d);
            return;
        }
        Set<String> routeCols = lt.getRouteColumns();
        if (routeCols == null) {
            return;
        }
        for (ColumnModel lc : lt.getColumns()) {
            if (!routeCols.contains(lc.getName())) {
                DiffItem d = DiffItem.of(DiffType.DIST_ROUTE_DRIFT, DiffLevel.AUTO,
                        bt.getName(), lc.getName());
                d.setLiveDef("本地表有、路由表缺");
                d.note("本地表与路由表列不一致，查询会报「列不存在」。补路由表是纯元数据操作");
                renderer.addColumnToRouteOnly(bt.getName(), lc).forEach(d::sql);
                out.add(d);
            }
        }
    }

    // ────────────────────────── helpers ──────────────────────────

    /** 现场多出对象的归类。CK 现场有大量运行时动态表，折叠成组避免噪音 */
    private String classifyExtra(String name) {
        if (name.matches("dx_source_[0-9a-f]{16}")) {
            return "检材导入来源表（运行时动态创建），只报告不处理";
        }
        if (name.startsWith("temp_") || name.startsWith("temp")) {
            return "临时表，只报告不处理";
        }
        if (name.endsWith("_local")) {
            return "疑似分布式本地表但未识别出路由表，只报告不处理";
        }
        if (name.contains("precompute")) {
            return "报表预计算表（运行时创建），只报告不处理";
        }
        return "现场多出的表，只报告不处理";
    }

    private TableModel findExact(SchemaModel m, String name) {
        for (TableModel t : m.getTables()) {
            if (t.getName().equals(name)) {
                return t;
            }
        }
        return null;
    }

    private TableModel findIgnoreCase(SchemaModel m, String name) {
        for (TableModel t : m.getTables()) {
            if (t.getName().equalsIgnoreCase(name)) {
                return t;
            }
        }
        return null;
    }

    private ColumnModel findExactColumn(TableModel t, String name) {
        for (ColumnModel c : t.getColumns()) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    private ColumnModel findColumnIgnoreCase(TableModel t, String name) {
        for (ColumnModel c : t.getColumns()) {
            if (c.getName().equalsIgnoreCase(name)) {
                return c;
            }
        }
        return null;
    }

    private boolean containsIgnoreCase(List<String> list, String v) {
        return list.stream().anyMatch(x -> x.equalsIgnoreCase(v));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
