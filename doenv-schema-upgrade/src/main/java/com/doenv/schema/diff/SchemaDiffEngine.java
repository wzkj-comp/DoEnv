package com.doenv.schema.diff;

import com.doenv.schema.model.ColumnModel;
import com.doenv.schema.model.ConstraintModel;
import com.doenv.schema.model.IndexModel;
import com.doenv.schema.model.SchemaModel;
import com.doenv.schema.model.SequenceModel;
import com.doenv.schema.model.TableModel;
import com.doenv.schema.render.PgDdlRenderer;
import com.doenv.schema.rename.ColumnRenameRule;
import com.doenv.schema.rename.ColumnRenameRules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 结构差异引擎。
 *
 * <p>表和索引保持单向补充，不删除现场对象；列结构以纯净库为准，
 * 现场多出的 PG 列会生成不带 {@code CASCADE} 的删除语句。列顺序不参与比对。
 */
public class SchemaDiffEngine {

    private final PgDdlRenderer renderer;
    private final List<ColumnRenameRule> renameRules;

    public SchemaDiffEngine(PgDdlRenderer renderer) {
        this(renderer, List.of());
    }

    public SchemaDiffEngine(PgDdlRenderer renderer, List<ColumnRenameRule> renameRules) {
        this.renderer = renderer;
        this.renameRules = renameRules == null ? List.of() : List.copyOf(renameRules);
    }

    public List<DiffItem> diff(SchemaModel baseline, SchemaModel live, DiffOptions opt) {
        List<DiffItem> out = new ArrayList<>();
        Set<String> matchedLiveTables = new HashSet<>();

        for (TableModel bt : baseline.getTables()) {
            TableModel lt = findExact(live, bt.getName());

            if (lt == null) {
                TableModel ci = live.findTable(bt.getName());   // 忽略大小写
                if (ci != null) {
                    matchedLiveTables.add(ci.getName());
                    out.add(DiffItem.of(DiffType.CASE_MISMATCH, DiffLevel.MANUAL, bt.getName(), null)
                            .note("现场表名为 " + ci.getName() + "，与基准仅大小写不同。"
                                    + "自动建表会建出第二张表，必须人工确认后处理"));
                    continue;
                }
                DiffItem d = DiffItem.of(DiffType.TABLE_MISSING, DiffLevel.AUTO, bt.getName(), null);
                d.setBaselineDef(bt.getColumns().size() + " 列");
                renderer.createTable(bt).forEach(d::sql);
                out.add(d);
                continue;
            }

            matchedLiveTables.add(lt.getName());
            diffColumns(bt, lt, opt, out);
            if (opt.isCheckIndex()) {
                diffIndexes(bt, lt, opt, out);
            }
            if (opt.isCheckConstraint()) {
                diffConstraints(bt, lt, out);
            }
            if (opt.isCheckComment()) {
                diffTableComment(bt, lt, out, opt);
            }
        }

        diffSequences(baseline, live, out);
        diffViews(baseline, live, opt, out);

        if (opt.isReportExtra()) {
            for (TableModel lt : live.getTables()) {
                if (!matchedLiveTables.contains(lt.getName())) {
                    out.add(DiffItem.of(DiffType.TABLE_EXTRA, DiffLevel.INFO, lt.getName(), null)
                            .note("现场多出的表，只报告不处理"));
                }
            }
        }
        return out;
    }

    // ────────────────────────── 列 ──────────────────────────

    /**
     * 比对视图。与 CK 侧同一套语义：<b>只补不删</b>。
     *
     * <p>缺了就建；同名但定义不同的只报告 —— 替换视图会把现场的调整整体抹掉；
     * 现场多出的不动。
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
                d.note("建视图：无数据、不动表，纯元数据操作");
                out.add(d);
                continue;
            }
            if (bv.isMaterialized() != lv.isMaterialized()) {
                out.add(DiffItem.of(DiffType.VIEW_DIFF, DiffLevel.MANUAL, bv.getName(), null)
                        .note("普通视图与物化视图不能互转，需人工处理"));
                continue;
            }
            if (!bv.normalizedQuery().equals(lv.normalizedQuery())) {
                DiffItem d = DiffItem.of(DiffType.VIEW_DIFF, DiffLevel.INFO, bv.getName(), null);
                d.setBaselineDef(shortQuery(bv.getQuery()));
                d.setLiveDef(shortQuery(lv.getQuery()));
                d.note("视图定义与基准不同。替换视图是整体覆盖，会抹掉现场的调整，只报告不处理");
                out.add(d);
            }
        }
        if (opt.isReportExtra()) {
            for (com.doenv.schema.model.ViewModel lv : live.getViews()) {
                if (baseline.findView(lv.getName()) == null) {
                    out.add(DiffItem.of(DiffType.VIEW_EXTRA, DiffLevel.INFO, lv.getName(), null)
                            .note("现场多出的视图，只报告不处理"));
                }
            }
        }
    }

    private static String shortQuery(String q) {
        String one = q == null ? "" : q.replaceAll("\\s+", " ").trim();
        return one.length() > 120 ? one.substring(0, 120) + "…" : one;
    }

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
                    // 当前目标结构本身同时保留新旧两列时，不属于冲突。
                    // 这类清单只服务于更老现场：缺目标列时把旧数据迁到新名，
                    // 下一轮再按基准补回仍需保留的来源列。
                    if (findExactColumn(bt, rename.getFrom()) != null) {
                        diffOneColumn(bt, bc, lc, opt, out);
                        continue;
                    }
                    out.add(DiffItem.of(DiffType.COLUMN_RENAME_CONFLICT, DiffLevel.MANUAL,
                                    bt.getName(), bc.getName())
                            .note("rename 清单要求 " + rename.getFrom() + " → " + rename.getTo()
                                    + "，但现场新旧列同时存在。为避免覆盖数据，禁止自动改名或删旧列"));
                    diffOneColumn(bt, bc, lc, opt, out);
                    continue;
                }

                DiffItem d = DiffItem.of(DiffType.COLUMN_RENAME, DiffLevel.CONFIRM,
                        bt.getName(), bc.getName());
                d.setLiveDef(rename.getFrom());
                d.setBaselineDef(rename.getTo());
                d.sql(renderer.renameColumn(bt.getName(), rename.getFrom(), rename.getTo()));
                d.note("按 rename 清单改名，保留原列数据"
                        + (rename.getReason() == null || rename.getReason().isBlank()
                        ? "" : "：" + rename.getReason()));
                out.add(d);
                // 改名项先执行；后续类型、注释修正使用目标列名，顺序天然正确。
                diffOneColumn(bt, bc, old, opt, out);
                continue;
            }

            if (lc == null) {
                ColumnModel ci = lt.findColumn(bc.getName());
                if (ci != null) {
                    matched.add(ci.getName());
                    out.add(DiffItem.of(DiffType.CASE_MISMATCH, DiffLevel.MANUAL,
                                    bt.getName(), bc.getName())
                            .note("现场列名为 " + ci.getName() + "，与基准仅大小写不同。"
                                    + "ADD COLUMN IF NOT EXISTS 按精确名匹配，会加出重复列"));
                    continue;
                }
                DiffItem d = DiffItem.of(DiffType.COLUMN_MISSING, DiffLevel.AUTO,
                        bt.getName(), bc.getName());
                d.setBaselineDef(describe(bc));
                d.sql(renderer.addColumn(bt.getName(), bc));
                // 注释和加列放在同一项里一起下发。分开的话新列这一轮没注释，
                // 要等下一轮比对才被检出，白白多跑一遍
                if (bc.getComment() != null && !bc.getComment().isEmpty()) {
                    d.sql(renderer.commentOnColumn(bt.getName(), bc.getName(), bc.getComment()));
                }
                if (bc.isNotNull() && !bc.canAddWithNotNull()) {
                    d.note("基准为 NOT NULL 但无常量默认值，存量行无法回填，已降级为可空");
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
                    DiffItem d = DiffItem.of(DiffType.COLUMN_DROP, DiffLevel.CONFIRM,
                            bt.getName(), lc.getName());
                    d.setLiveDef(describe(lc));
                    d.sql(renderer.dropColumn(bt.getName(), lc.getName()));
                    d.note("现场多出的列，按纯净库强一致规则删除；不使用 CASCADE。"
                            + "若被视图等外部对象依赖，PostgreSQL 会拒绝删除，本项回滚并登记失败");
                    out.add(d);
                }
            }
        }
    }

    private void diffOneColumn(TableModel bt, ColumnModel bc, ColumnModel lc,
                               DiffOptions opt, List<DiffItem> out) {
        String table = bt.getName();

        if (!Objects.equals(bc.getNormType(), lc.getNormType())) {
            DiffType type = classifyTypeChange(lc, bc);
            DiffLevel level = switch (type) {
                case TYPE_WIDEN_CHEAP -> DiffLevel.AUTO;
                case TYPE_WIDEN_HEAVY -> DiffLevel.CONFIRM;
                default -> DiffLevel.MANUAL;
            };
            DiffItem d = DiffItem.of(type, level, table, bc.getName());
            d.setLiveDef(describe(lc));
            d.setBaselineDef(describe(bc));
            d.sql(renderer.alterColumnType(table, bc));
            d.precheck(PrecheckRule.info(renderer.precheckRowCount(table), "目标表行数"));

            switch (type) {
                case TYPE_WIDEN_HEAVY -> d.note("需要重写全表并持有 ACCESS EXCLUSIVE 锁，执行前请确认行数与磁盘");
                case TYPE_NARROW -> {
                    d.note("类型收窄，存量超长数据会触发 ERROR 22001 且整事务回滚，不自动执行");
                    if (bc.getLength() != null) {
                        d.precheck(PrecheckRule.expectZero(
                                renderer.precheckOverlong(table, bc.getName(), bc.getLength()),
                                bc.getName() + " 超长行数",
                                "存在超过目标长度的数据，收窄会触发 ERROR 22001"));
                    }
                }
                case TYPE_CROSS_FAMILY -> d.note("跨类型族变更（"
                        + lc.getTypeFamily() + " → " + bc.getTypeFamily() + "），必须人工处理");
                default -> {
                }
            }
            out.add(d);
        }

        if (bc.isNotNull() && !lc.isNotNull()) {
            out.add(DiffItem.of(DiffType.NULLABLE_TIGHTEN, DiffLevel.MANUAL, table, bc.getName())
                    .precheck(PrecheckRule.expectZero(
                            renderer.precheckNullCount(table, bc.getName()),
                            bc.getName() + " 的 NULL 行数",
                            "存量存在 NULL，SET NOT NULL 必然失败，需先回填"))
                    .sql(renderer.setNotNull(table, bc.getName()))
                    .note("收紧非空。存量存在 NULL 时必然失败，需先回填"));
        } else if (!bc.isNotNull() && lc.isNotNull()) {
            // 只补键、不删键：NOT NULL 也是约束，现场有而基准没有，属于现场多出来的东西，
            // 与「现场多出的表/列/约束一律不 DROP」同一条原则，只报告不处理。
            //
            // 真实现场踩过：基准里 vao_case_attribute_folder 无主键、id 可空，现场 id 是主键。
            // 主键隐含 NOT NULL，DROP NOT NULL 被 PG 直接拒绝（42P16），而且每轮重试永不收敛 ——
            // 主键本身已被单向原则豁免，它隐含的 NOT NULL 却被当普通列属性去放宽，是同一约束的两半处理不一致。
            out.add(DiffItem.of(DiffType.NULLABLE_RELAX, DiffLevel.INFO, table, bc.getName())
                    .note("现场为 NOT NULL、基准可空。只补键不删键，保留现场约束不处理"
                            + "（该列若是主键，DROP NOT NULL 也会被 PG 拒绝）"));
        }

        if (opt.isCheckDefault() && !Objects.equals(bc.getDefaultNorm(), lc.getDefaultNorm())) {
            DiffItem d = DiffItem.of(DiffType.DEFAULT_DIFF, DiffLevel.AUTO, table, bc.getName());
            d.setLiveDef(String.valueOf(lc.getDefaultNorm()));
            d.setBaselineDef(String.valueOf(bc.getDefaultNorm()));
            d.sql(renderer.setColumnDefault(table, bc));
            out.add(d);
        }

        // 注释：只在「基准有注释」时下发。基准为空一律不动现场 ——
        // 既避免抹掉现场自己补的注释，也不会因为基准大量缺注释而刷出上千条噪音差异。
        if (opt.isCheckComment()
                && bc.getComment() != null && !bc.getComment().isEmpty()
                && !Objects.equals(bc.getComment(), nz(lc.getComment()))) {
            DiffItem d = DiffItem.of(DiffType.COLUMN_COMMENT_DIFF, DiffLevel.AUTO, table, bc.getName());
            d.setLiveDef(nz(lc.getComment()));
            d.setBaselineDef(bc.getComment());
            d.sql(renderer.commentOnColumn(table, bc.getName(), bc.getComment()));
            out.add(d);
        }
    }

    /**
     * 类型变更分级。只按类型族与长度判断，不猜业务意图。
     */
    private DiffType classifyTypeChange(ColumnModel live, ColumnModel target) {
        if (!Objects.equals(live.getTypeFamily(), target.getTypeFamily())) {
            // varchar -> text 属于同族放宽，不是跨族
            if (isStringFamily(live) && isStringFamily(target)) {
                return DiffType.TYPE_WIDEN_CHEAP;
            }
            return DiffType.TYPE_CROSS_FAMILY;
        }

        if ("STRING".equals(target.getTypeFamily())) {
            if ("text".equals(target.getNormType())) {
                return DiffType.TYPE_WIDEN_CHEAP;
            }
            if ("text".equals(live.getNormType())) {
                return DiffType.TYPE_NARROW;
            }
            Integer lLen = live.getLength();
            Integer tLen = target.getLength();
            if (lLen == null || tLen == null) {
                return DiffType.TYPE_WIDEN_CHEAP;
            }
            // varchar 扩长是二进制兼容的，不重写表
            return tLen >= lLen ? DiffType.TYPE_WIDEN_CHEAP : DiffType.TYPE_NARROW;
        }

        if ("INT".equals(target.getTypeFamily())) {
            int lb = intBits(live.getNormType());
            int tb = intBits(target.getNormType());
            return tb >= lb ? DiffType.TYPE_WIDEN_HEAVY : DiffType.TYPE_NARROW;
        }

        if ("DECIMAL".equals(target.getTypeFamily()) || "FLOAT".equals(target.getTypeFamily())) {
            return DiffType.TYPE_WIDEN_HEAVY;
        }

        // 同族其余情况（时间精度等）保守处理
        return DiffType.TYPE_WIDEN_HEAVY;
    }

    private boolean isStringFamily(ColumnModel c) {
        return "STRING".equals(c.getTypeFamily());
    }

    private int intBits(String normType) {
        return switch (normType) {
            case "int2" -> 16;
            case "int4" -> 32;
            case "int8" -> 64;
            default -> 32;
        };
    }

    // ────────────────────────── 索引 ──────────────────────────

    private void diffIndexes(TableModel bt, TableModel lt, DiffOptions opt, List<DiffItem> out) {
        Set<String> liveFingerprints = new HashSet<>();
        for (IndexModel li : lt.getIndexes()) {
            liveFingerprints.add(li.getFingerprint());
        }

        for (IndexModel bi : bt.getIndexes()) {
            if (liveFingerprints.contains(bi.getFingerprint())) {
                continue;
            }
            boolean unique = bi.isUnique();
            DiffItem d = DiffItem.of(
                    unique ? DiffType.UNIQUE_INDEX_MISSING : DiffType.INDEX_MISSING,
                    DiffLevel.CONFIRM, bt.getName(), bi.getName());
            d.setBaselineDef(bi.getFingerprint());
            // 先清理可能存在的 INVALID 残留，再 CONCURRENTLY 建
            d.sql(renderer.dropInvalidIndex(bi.getName()));
            d.sql(renderer.createIndex(bt.getName(), bi));
            d.precheck(PrecheckRule.info(renderer.precheckRowCount(bt.getName()), "目标表行数"));
            d.note("CREATE INDEX CONCURRENTLY 必须在事务外执行");
            if (unique) {
                d.precheck(PrecheckRule.expectEmpty(
                        renderer.precheckDuplicates(bt.getName(),
                                bi.getColumns().stream().map(IndexModel.IndexColumn::getName).toList()),
                        "唯一索引列的重复值",
                        "存量存在重复值，创建唯一索引必然失败"));
                d.note("唯一索引：存量存在重复值时会失败，执行前会自动跑重复值探测");
            }
            out.add(d);
        }

        if (opt.isReportExtra()) {
            Set<String> baseFingerprints = new HashSet<>();
            bt.getIndexes().forEach(i -> baseFingerprints.add(i.getFingerprint()));
            for (IndexModel li : lt.getIndexes()) {
                if (!baseFingerprints.contains(li.getFingerprint())) {
                    out.add(DiffItem.of(DiffType.INDEX_EXTRA, DiffLevel.INFO,
                                    bt.getName(), li.getName())
                            .note("现场多出的索引，只报告不处理"));
                }
            }
        }
    }

    // ────────────────────────── 约束 ──────────────────────────

    private void diffConstraints(TableModel bt, TableModel lt, List<DiffItem> out) {
        Set<String> liveSigs = new HashSet<>();
        for (ConstraintModel lc : lt.getConstraints()) {
            liveSigs.add(constraintSig(lc));
        }
        for (ConstraintModel bc : bt.getConstraints()) {
            if (liveSigs.contains(constraintSig(bc))) {
                continue;
            }
            // 外键单独处理：它的失败条件是「子表有孤儿引用」，探测方式与键约束不同
            if (bc.isForeignKey()) {
                diffOneForeignKey(bt, bc, out);
                continue;
            }

            // 主键/唯一约束判 CONFIRM 而不是 MANUAL：失败条件是可以被程序判定的
            // （重复值、NULL），执行器会先跑探测，通过才建，不通过就跳过并说明原因。
            // CHECK 约束的表达式无法通用探测，仍归 MANUAL。
            boolean keyLike = "PRIMARY_KEY".equals(bc.getType()) || "UNIQUE".equals(bc.getType());
            DiffItem d = DiffItem.of(DiffType.CONSTRAINT_MISSING,
                    keyLike ? DiffLevel.CONFIRM : DiffLevel.MANUAL,
                    bt.getName(), bc.getName());
            d.setBaselineDef(constraintSig(bc));
            String sql = renderer.addConstraint(bt.getName(), bc);
            if (sql != null) {
                d.sql(sql);
            }
            if (keyLike) {
                d.precheck(PrecheckRule.expectEmpty(
                        renderer.precheckDuplicates(bt.getName(), bc.getColumns()),
                        String.join("+", bc.getColumns()) + " 的重复值",
                        "存量存在重复值，加约束必然失败，需先去重"));
                bc.getColumns().forEach(c -> d.precheck(PrecheckRule.expectZero(
                        renderer.precheckNullCount(bt.getName(), c),
                        c + " 的 NULL 行数",
                        "主键列不允许 NULL，需先回填")));
                d.note("执行前会自动探测重复值与 NULL，全部通过才建约束");
            } else {
                d.note("CHECK 约束的表达式无法通用探测，需人工确认后执行");
            }
            out.add(d);
        }
    }

    /**
     * 约束等价签名。
     *
     * <p>CHECK 只按表达式比 —— 基准侧从 SQL 文本解析不出「涉及哪些列」，
     * 而现场侧能从 {@code pg_constraint.conkey} 拿到，把列算进签名会让
     * 每一条现存的 CHECK 都被误判为缺失。
     */
    private String constraintSig(ConstraintModel c) {
        if ("CHECK".equals(c.getType())) {
            return "CHECK:" + normalizeExpr(c.getExpression());
        }
        return c.getType() + "(" + String.join(",",
                c.getColumns().stream().map(x -> x.toLowerCase(Locale.ROOT)).toList()) + ")";
    }

    private String normalizeExpr(String e) {
        // 与索引谓词共用同一套归一化，两边口径必须一致
        return com.doenv.schema.baseline.IndexDefParser.normalizeExpression(e);
    }

    // ────────────────────────── 表注释 ──────────────────────────

    /**
     * 外键差异。判 CONFIRM：加外键会校验存量数据，
     * 子表有孤儿引用就必然失败，而孤儿是可以被探测出来的。
     */
    private void diffOneForeignKey(TableModel bt, ConstraintModel bc, List<DiffItem> out) {
        DiffItem d = DiffItem.of(DiffType.FOREIGN_KEY_MISSING, DiffLevel.CONFIRM,
                bt.getName(), bc.getName());
        d.setBaselineDef(constraintSig(bc));
        d.sql(renderer.addForeignKey(bt.getName(), bc));

        if (!bc.getColumns().isEmpty() && !bc.getRefColumns().isEmpty() && bc.getRefTable() != null) {
            d.precheck(PrecheckRule.expectZero(
                    renderer.precheckOrphanRows(bt.getName(), bc.getColumns(),
                            bc.getRefTable(), bc.getRefColumns()),
                    "引用 " + bc.getRefTable() + " 的孤儿行数",
                    "子表存在引用不到父表的值，加外键必然失败，需先清理或补父行"));
        }
        d.note("执行前会自动探测孤儿引用，为 0 才建外键");
        out.add(d);
    }

    /**
     * 序列差异。<b>只前推不后退</b> —— 现场值若已大于基准（现场用得更多），
     * 后退会让后续取号撞上已有主键。
     */
    private void diffSequences(SchemaModel baseline, SchemaModel live, List<DiffItem> out) {
        for (SequenceModel bs : baseline.getSequences()) {
            if (bs.getLastValue() == null) {
                continue;   // 基准没给当前值，无从比较
            }
            SequenceModel ls = live.findSequence(bs.getName());
            if (ls == null) {
                out.add(DiffItem.of(DiffType.SEQUENCE_VALUE_BEHIND, DiffLevel.INFO,
                                bs.getName(), null)
                        .note("现场缺该序列。序列随建表脚本创建，此处不单独补建"));
                continue;
            }
            if (ls.nextValue() >= bs.nextValue()) {
                continue;   // 现场不落后，什么都不做
            }
            DiffItem d = DiffItem.of(DiffType.SEQUENCE_VALUE_BEHIND, DiffLevel.CONFIRM,
                    bs.getName(), null);
            d.setLiveDef("下次取号 " + ls.nextValue());
            d.setBaselineDef("下次取号 " + bs.nextValue());
            d.sql(renderer.advanceSequence(bs.getName(), bs.getLastValue(), bs.isCalled()));
            d.note("序列值落后会导致取号撞上存量主键。语句用 GREATEST 兜底，只前推不后退");
            out.add(d);
        }
    }

    private void diffTableComment(TableModel bt, TableModel lt, List<DiffItem> out, DiffOptions opt) {
        // 同列注释：只在基准有注释时下发
        if (!opt.isCheckComment()
                || bt.getComment() == null || bt.getComment().isEmpty()
                || Objects.equals(bt.getComment(), nz(lt.getComment()))) {
            return;
        }
        DiffItem d = DiffItem.of(DiffType.TABLE_COMMENT_DIFF, DiffLevel.AUTO, bt.getName(), null);
        d.setLiveDef(nz(lt.getComment()));
        d.setBaselineDef(bt.getComment());
        d.sql(renderer.commentOnTable(bt.getName(), bt.getComment()));
        out.add(d);
    }

    // ────────────────────────── helpers ──────────────────────────

    private TableModel findExact(SchemaModel m, String name) {
        for (TableModel t : m.getTables()) {
            if (t.getName().equals(name)) {
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

    private String describe(ColumnModel c) {
        return c.getNormType()
                + (c.isNotNull() ? " NOT NULL" : "")
                + (c.getDefaultNorm() == null ? "" : " DEFAULT " + c.getDefaultNorm());
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }
}
