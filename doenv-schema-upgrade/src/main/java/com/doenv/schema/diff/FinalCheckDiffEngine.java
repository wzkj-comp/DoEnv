package com.doenv.schema.diff;

import com.doenv.schema.baseline.CkTokens;
import com.doenv.schema.live.CkTopology;
import com.doenv.schema.model.CkTableModel;
import com.doenv.schema.model.TableModel;
import com.doenv.schema.render.CkDdlRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 升级末尾的固定收尾检查。
 *
 * <p>这里刻意只放已经明确授权的特殊修复，不能把普通结构差异混进来。
 */
public final class FinalCheckDiffEngine {

    public static final String SYSTEMAUTHS_TABLE = "vao_user";
    public static final String ITEM_LABEL_TABLE = "vax_item_label";
    private static final String TEMP_SUFFIX = "__doenv_new";
    private static final String BACKUP_SUFFIX = "__doenv_backup";

    private FinalCheckDiffEngine() {
    }

    public static DiffItem systemauths(String schema, TableModel liveUser, Long nullRows) {
        if (liveUser == null || liveUser.findColumn("systemauths") == null) {
            return DiffItem.of(DiffType.FINAL_SYSTEMAUTHS_CHECK, DiffLevel.INFO,
                            SYSTEMAUTHS_TABLE, "systemauths")
                    .note("收尾检查未执行：表或字段尚未存在，等待结构升级完成后下一轮复查");
        }
        if (nullRows == null) {
            return DiffItem.of(DiffType.FINAL_SYSTEMAUTHS_CHECK, DiffLevel.INFO,
                            SYSTEMAUTHS_TABLE, "systemauths")
                    .note("收尾检查未执行：systemauths 空值探测失败，为保护现场数据未生成 UPDATE");
        }
        if (nullRows <= 0) {
            return DiffItem.of(DiffType.FINAL_SYSTEMAUTHS_CHECK, DiffLevel.INFO,
                            SYSTEMAUTHS_TABLE, "systemauths")
                    .note("收尾检查通过：systemauths 不存在空值");
        }
        String table = "\"" + schema.replace("\"", "\"\"") + "\".\"" + SYSTEMAUTHS_TABLE + "\"";
        DiffItem d = DiffItem.of(DiffType.FINAL_SYSTEMAUTHS_CHECK, DiffLevel.AUTO,
                        SYSTEMAUTHS_TABLE, "systemauths")
                .sql("UPDATE " + table + " SET \"systemauths\" = '0' WHERE \"systemauths\" IS NULL;")
                .note("收尾检查：发现 systemauths 空值 " + nullRows + " 行，自动补 0");
        d.setEstimatedRows(nullRows);
        return d;
    }

    /**
     * 仅比较引擎族及业务参数；分区、排序、主键仍由常规 CK 差异流程处理。
     * 迁移拆成三项，复制后的行数核验通过，才会执行换名切换。
     */
    public static List<DiffItem> itemLabelEngine(TableModel baseline, TableModel live,
                                                  CkDdlRenderer renderer, CkTopology topology,
                                                  long sourceRows) {
        if (baseline == null || baseline.getCk() == null || live == null || live.getCk() == null) {
            return List.of(DiffItem.of(DiffType.FINAL_ITEM_LABEL_ENGINE_MIGRATION, DiffLevel.INFO,
                            ITEM_LABEL_TABLE, "表引擎")
                    .note("收尾检查未执行：vax_item_label 尚未完整存在，等待常规建表流程完成后下一轮复查"));
        }
        String expected = engineSignature(baseline.getCk());
        String actual = engineSignature(live.getCk());
        if (expected.equals(actual)) {
            return List.of(DiffItem.of(DiffType.FINAL_ITEM_LABEL_ENGINE_MIGRATION, DiffLevel.INFO,
                            ITEM_LABEL_TABLE, "表引擎")
                    .note("收尾检查通过：vax_item_label 表引擎一致（" + expected + "）"));
        }

        String temp = ITEM_LABEL_TABLE + TEMP_SUFFIX;
        String backup = ITEM_LABEL_TABLE + BACKUP_SUFFIX;
        String columns = baseline.getColumns().stream()
                .map(c -> CkTokens.ident(c.getName()))
                .collect(Collectors.joining(", "));
        String names = topology == CkTopology.DISTRIBUTED
                ? "'" + temp + "','" + temp + "_local','" + backup + "','" + backup + "_local'"
                : "'" + temp + "','" + backup + "'";
        String absent = "SELECT count() FROM system.tables WHERE database = currentDatabase() "
                + "AND name IN (" + names + ")";
        int expectedTempObjects = topology == CkTopology.DISTRIBUTED ? 2 : 1;
        String tempReady = "SELECT if((SELECT count() FROM system.tables WHERE database = currentDatabase() "
                + "AND name IN ('" + temp + "'" + (topology == CkTopology.DISTRIBUTED
                ? ", '" + temp + "_local'" : "") + ")) = " + expectedTempObjects + ", 0, 1)";
        String copied = "SELECT if((SELECT count() FROM " + CkTokens.ident(temp) + ") "
                + "= (SELECT count() FROM " + CkTokens.ident(ITEM_LABEL_TABLE) + "), 0, 1)";

        List<DiffItem> out = new ArrayList<>();
        DiffItem create = DiffItem.of(DiffType.FINAL_ITEM_LABEL_ENGINE_MIGRATION, DiffLevel.CONFIRM,
                        ITEM_LABEL_TABLE, "新建迁移临时表")
                .precheck(PrecheckRule.expectZero(absent, "迁移临时表/备份表名称占用检查",
                        "检测到遗留迁移表，为避免覆盖已有数据，已拒绝本次引擎迁移"))
                .note("收尾检查：表引擎 " + actual + " → " + expected
                        + "，开始新建临时表迁移（源表约 " + Math.max(0, sourceRows) + " 行）");
        TableModel tempModel = copyWithName(baseline, temp);
        renderer.createTable(tempModel).forEach(create::sql);
        out.add(create);

        DiffItem copy = DiffItem.of(DiffType.FINAL_ITEM_LABEL_ENGINE_MIGRATION, DiffLevel.CONFIRM,
                        ITEM_LABEL_TABLE, "复制迁移数据")
                .precheck(PrecheckRule.expectZero(tempReady, "迁移临时表存在检查",
                        "临时表未成功创建，禁止复制或切换"))
                .sql("INSERT INTO " + CkTokens.ident(temp) + " (" + columns + ") SELECT " + columns
                        + " FROM " + CkTokens.ident(ITEM_LABEL_TABLE)
                        + (topology == CkTopology.DISTRIBUTED ? " SETTINGS insert_distributed_sync = 1" : "")
                        + ";")
                .note("收尾检查：复制 vax_item_label 全量数据到临时表");
        out.add(copy);

        DiffItem swap = DiffItem.of(DiffType.FINAL_ITEM_LABEL_ENGINE_MIGRATION, DiffLevel.CONFIRM,
                        ITEM_LABEL_TABLE, "核验并切换表名")
                .precheck(PrecheckRule.expectZero(copied, "迁移前行数核验",
                        "临时表与原表行数不一致，禁止切换表名"))
                .note("收尾检查：行数核验通过后，将原表改名为备份表、临时表切换为正式表");
        if (topology == CkTopology.DISTRIBUTED) {
            swap.sql(renderer.renameTable(ITEM_LABEL_TABLE, backup));
            swap.sql(renderer.renameTable(ITEM_LABEL_TABLE + "_local", backup + "_local"));
            swap.sql(renderer.renameTable(temp + "_local", ITEM_LABEL_TABLE + "_local"));
            swap.sql(renderer.dropTable(temp));
            renderer.createTable(baseline).forEach(swap::sql);
        } else {
            swap.sql(renderer.renameTable(ITEM_LABEL_TABLE, backup));
            swap.sql(renderer.renameTable(temp, ITEM_LABEL_TABLE));
        }
        out.add(swap);
        return out;
    }

    private static TableModel copyWithName(TableModel source, String name) {
        TableModel copy = new TableModel();
        copy.setName(name);
        copy.setComment(source.getComment());
        copy.setColumns(source.getColumns());
        copy.setCk(source.getCk());
        return copy;
    }

    private static String engineSignature(CkTableModel ck) {
        String args = ck.getEngineArgs() == null ? "" : ck.getEngineArgs().stream()
                .map(x -> x == null ? "" : x.replaceAll("\\s+", ""))
                .collect(Collectors.joining(","));
        return (ck.getEngineFamily() == null ? "" : ck.getEngineFamily().replaceAll("\\s+", ""))
                .toLowerCase(Locale.ROOT) + "(" + args.toLowerCase(Locale.ROOT) + ")";
    }
}
