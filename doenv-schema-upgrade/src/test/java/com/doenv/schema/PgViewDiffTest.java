package com.doenv.schema;

import com.doenv.schema.baseline.PgBaselineParser;
import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffLevel;
import com.doenv.schema.diff.DiffOptions;
import com.doenv.schema.diff.DiffType;
import com.doenv.schema.diff.SchemaDiffEngine;
import com.doenv.schema.model.SchemaModel;
import com.doenv.schema.render.PgDdlRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PG 视图比对的回归测试。
 *
 * <p>PG 基准目前一个视图都没有，但解析器此前遇到 {@code CREATE VIEW} 会<b>静默丢弃</b> ——
 * 将来基准加了视图，现场就永远缺它且无人知晓。CK 那边的 vax_trans_view 正是这么漏掉的，
 * 这里提前把两侧对齐。
 */
class PgViewDiffTest {

    private static final String DDL = """
            CREATE TABLE "vao_trans" ("id" varchar(100), "amount" numeric);
            CREATE OR REPLACE VIEW "v_trans_summary" AS SELECT id, amount FROM vao_trans;
            CREATE MATERIALIZED VIEW "mv_trans_daily" AS SELECT id FROM vao_trans;
            """;

    private static SchemaModel baseline() {
        return new PgBaselineParser().parse(DDL, "t", "t.sql").getSchema();
    }

    private static List<DiffItem> diff(SchemaModel base, SchemaModel live) {
        return new SchemaDiffEngine(new PgDdlRenderer("public")).diff(base, live, new DiffOptions());
    }

    @Test
    void 视图不再被静默丢弃() {
        SchemaModel m = baseline();
        assertEquals(2, m.getViews().size(), "普通视图和物化视图都要认");
        assertEquals(1, m.getTableCount(), "视图不能混进表模型");
        assertTrue(m.getViews().stream().anyMatch(v -> v.isMaterialized()));
    }

    @Test
    void 缺视图会生成建视图语句() {
        SchemaModel live = baseline();
        live.getViews().clear();

        List<DiffItem> vs = diff(baseline(), live).stream()
                .filter(d -> d.getType() == DiffType.VIEW_MISSING).toList();
        assertEquals(2, vs.size());
        assertTrue(vs.stream().allMatch(d -> d.getLevel() == DiffLevel.AUTO));

        String all = String.join("\n", vs.stream().flatMap(d -> d.getDdl().stream()).toList());
        // 普通视图：PG 没有 CREATE VIEW IF NOT EXISTS，用 OR REPLACE 兼顾幂等
        assertTrue(all.contains("CREATE OR REPLACE VIEW \"public\".\"v_trans_summary\""), all);
        // 物化视图：有 IF NOT EXISTS，但没有 OR REPLACE
        assertTrue(all.contains("CREATE MATERIALIZED VIEW IF NOT EXISTS \"public\".\"mv_trans_daily\""), all);
    }

    @Test
    void 定义不同只报告不替换() {
        SchemaModel live = baseline();
        live.getViews().get(0).setQuery("SELECT id FROM vao_trans");

        DiffItem d = diff(baseline(), live).stream()
                .filter(x -> x.getType() == DiffType.VIEW_DIFF).findFirst().orElseThrow();
        assertEquals(DiffLevel.INFO, d.getLevel());
        assertTrue(d.getDdl().isEmpty(), "替换视图是整体覆盖，会抹掉现场调整");
    }

    @Test
    void 普通视图与物化视图不互转() {
        SchemaModel live = baseline();
        live.getViews().forEach(v -> v.setMaterialized(!v.isMaterialized()));

        assertTrue(diff(baseline(), live).stream()
                        .filter(x -> x.getType() == DiffType.VIEW_DIFF)
                        .allMatch(x -> x.getLevel() == DiffLevel.MANUAL),
                "物化视图自带存储，互转必须人工");
    }

    @Test
    void 现场多出的视图不删() {
        SchemaModel base = baseline();
        base.getViews().clear();
        assertFalse(diff(base, baseline()).stream().flatMap(d -> d.getDdl().stream())
                        .anyMatch(s -> s.toUpperCase().contains("DROP VIEW")),
                "只补不删");
    }
}
