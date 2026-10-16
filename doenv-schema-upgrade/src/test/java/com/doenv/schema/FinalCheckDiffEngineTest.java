package com.doenv.schema;

import com.doenv.schema.baseline.CkBaselineParser;
import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffLevel;
import com.doenv.schema.diff.DiffType;
import com.doenv.schema.diff.FinalCheckDiffEngine;
import com.doenv.schema.live.CkTopology;
import com.doenv.schema.model.ColumnModel;
import com.doenv.schema.model.TableModel;
import com.doenv.schema.render.CkDdlRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalCheckDiffEngineTest {

    private static final String ITEM_LABEL = """
            CREATE TABLE IF NOT EXISTS vax_item_label
            (
                `id` String,
                `caseid` String,
                `distinctvalue` String,
                `ver` Int64
            )
            ENGINE = ReplacingMergeTree(ver)
            PARTITION BY caseid
            PRIMARY KEY (caseid, distinctvalue)
            ORDER BY (caseid, distinctvalue);
            """;

    @Test
    void systemauths空值生成精确的自动修复SQL() {
        TableModel user = new TableModel();
        user.setName("vao_user");
        ColumnModel c = new ColumnModel();
        c.setName("systemauths");
        user.addColumn(c);

        DiffItem d = FinalCheckDiffEngine.systemauths("public", user, 3L);

        assertEquals(DiffType.FINAL_SYSTEMAUTHS_CHECK, d.getType());
        assertEquals(DiffLevel.AUTO, d.getLevel());
        assertEquals("UPDATE \"public\".\"vao_user\" SET \"systemauths\" = '0' WHERE \"systemauths\" IS NULL;",
                d.getDdl().get(0));
        assertEquals(3L, d.getEstimatedRows());
    }

    @Test
    void itemLabel引擎一致时只登记检查通过不生成SQL() {
        TableModel base = new CkBaselineParser().parse(ITEM_LABEL, "t", "t.sql")
                .getSchema().findTable("vax_item_label");
        List<DiffItem> diffs = FinalCheckDiffEngine.itemLabelEngine(base, base,
                new CkDdlRenderer(CkTopology.SINGLE, null, false), CkTopology.SINGLE, 8);

        assertEquals(1, diffs.size());
        assertEquals(DiffLevel.INFO, diffs.get(0).getLevel());
        assertTrue(diffs.get(0).getDdl().isEmpty());
        assertTrue(diffs.get(0).getNotes().get(0).contains("引擎一致"));
    }

    @Test
    void itemLabel引擎不一致必须先复制核验再换名() {
        TableModel base = new CkBaselineParser().parse(ITEM_LABEL, "t", "t.sql")
                .getSchema().findTable("vax_item_label");
        TableModel live = new CkBaselineParser().parse(ITEM_LABEL.replace("ReplacingMergeTree(ver)", "MergeTree"),
                "t", "t.sql").getSchema().findTable("vax_item_label");

        List<DiffItem> diffs = FinalCheckDiffEngine.itemLabelEngine(base, live,
                new CkDdlRenderer(CkTopology.SINGLE, null, false), CkTopology.SINGLE, 8);

        assertEquals(3, diffs.size());
        assertTrue(diffs.stream().allMatch(x -> x.getType() == DiffType.FINAL_ITEM_LABEL_ENGINE_MIGRATION));
        assertTrue(diffs.stream().allMatch(x -> x.getLevel() == DiffLevel.CONFIRM));
        assertTrue(diffs.get(0).getDdl().get(0).contains("vax_item_label__doenv_new"));
        assertTrue(diffs.get(1).getDdl().get(0).contains("INSERT INTO `vax_item_label__doenv_new`"));
        assertTrue(diffs.get(2).getPrechecks().get(0).getSql().contains("count()"));
        assertTrue(diffs.get(2).getDdl().stream().anyMatch(s -> s.contains("RENAME TABLE `vax_item_label`")));
        assertFalse(diffs.get(2).getDdl().stream().anyMatch(s -> s.contains("DROP TABLE")));
        assertTrue(CkDdlRenderer.validateItems(diffs).isEmpty(), "收尾迁移 SQL 应通过生成物安全校验");
    }
}
