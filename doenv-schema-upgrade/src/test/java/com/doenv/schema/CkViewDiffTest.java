package com.doenv.schema;

import com.doenv.schema.baseline.CkBaselineParser;
import com.doenv.schema.diff.CkSchemaDiffEngine;
import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffLevel;
import com.doenv.schema.diff.DiffOptions;
import com.doenv.schema.diff.DiffType;
import com.doenv.schema.live.CkTopology;
import com.doenv.schema.model.SchemaModel;
import com.doenv.schema.model.ViewModel;
import com.doenv.schema.render.CkDdlRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CK 视图比对的回归测试。
 *
 * <p>此前视图两头都被跳过：基准解析直接 {@code discard("VIEW")}，现场读取
 * {@code engine NOT IN ('View',...)} 排除。基准里那个 {@code vax_trans_view}
 * （vax_trans 的付款/收款方规范化视图）因此从没被下发过。
 */
class CkViewDiffTest {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS vax_trans (`id` String, `caseid` String)
            ENGINE = MergeTree ORDER BY id;

            CREATE OR REPLACE VIEW vax_trans_view AS
            SELECT * EXCEPT (transflag), 1 AS transflag FROM vax_trans;
            """;

    private static SchemaModel baseline() {
        return new CkBaselineParser().parse(DDL, "t", "t.sql").getSchema();
    }

    private static List<DiffItem> diff(SchemaModel base, SchemaModel live) {
        return new CkSchemaDiffEngine(new CkDdlRenderer(CkTopology.SINGLE, null, false), t -> 0L)
                .diff(base, live, new DiffOptions());
    }

    @Test
    void 基准里的视图不再被丢弃() {
        SchemaModel m = baseline();
        assertEquals(1, m.getViews().size(), "CREATE OR REPLACE VIEW 也要认");
        ViewModel v = m.getViews().get(0);
        assertEquals("vax_trans_view", v.getName());
        assertFalse(v.isMaterialized());
        assertTrue(v.getQuery().startsWith("SELECT"), "只留 AS 之后的查询体：" + v.getQuery());
        assertEquals(1, m.getTableCount(), "视图不能混进表模型，否则引擎级比对会误判");
    }

    @Test
    void 缺视图会生成CREATE_VIEW() {
        SchemaModel live = baseline();
        live.getViews().clear();

        DiffItem d = diff(baseline(), live).stream()
                .filter(x -> x.getType() == DiffType.VIEW_MISSING)
                .findFirst().orElseThrow(() -> new AssertionError("没检出缺失的视图"));
        assertEquals(DiffLevel.AUTO, d.getLevel(), "建视图不动数据，可自动执行");
        String sql = d.getDdl().get(0);
        assertTrue(sql.startsWith("CREATE VIEW IF NOT EXISTS `vax_trans_view`"), sql);
        assertTrue(sql.contains("SELECT"));
    }

    @Test
    void 普通视图定义不同自动覆盖为基准定义() {
        SchemaModel live = baseline();
        live.getViews().get(0).setQuery("SELECT * FROM vax_trans");

        DiffItem d = diff(baseline(), live).stream()
                .filter(x -> x.getType() == DiffType.VIEW_DIFF)
                .findFirst().orElseThrow();
        assertEquals(DiffLevel.AUTO, d.getLevel());
        assertEquals(1, d.getDdl().size());
        assertTrue(d.getDdl().get(0).startsWith("CREATE OR REPLACE VIEW `vax_trans_view`"),
                d.getDdl().get(0));
        assertTrue(d.getDdl().get(0).contains("SELECT * EXCEPT (transflag)"),
                "必须使用纯净库定义覆盖现场视图");
    }

    @Test
    void 现场多出的视图不删() {
        SchemaModel base = baseline();
        base.getViews().clear();

        List<DiffItem> diffs = diff(base, baseline());
        assertFalse(diffs.stream().flatMap(d -> d.getDdl().stream())
                        .anyMatch(s -> s.toUpperCase().contains("DROP VIEW")),
                "只补不删");
    }

    @Test
    void 空白差异不该判成定义不同() {
        SchemaModel live = baseline();
        // CK 回读的是规范化后的形式，空白与基准原文必然不同
        live.getViews().get(0).setQuery(
                "SELECT  *   EXCEPT (transflag),\n 1 AS transflag\nFROM vax_trans");

        assertFalse(diff(baseline(), live).stream()
                        .anyMatch(x -> x.getType() == DiffType.VIEW_DIFF),
                "只是空白不同就报差异的话，每轮都刷同样的噪声且永远消不掉");
    }

    @Test
    void 基准带库名时要剥掉() {
        // 实测 2026-08-11 那份基准是 CREATE VIEW datao_pure.vax_trans_view ... FROM datao_pure.vax_trans。
        // 各现场库名不同，照搬会报 Code 81 Database datao_pure does not exist
        String ddl = """
                CREATE TABLE IF NOT EXISTS vax_trans (`id` String) ENGINE = MergeTree ORDER BY id;
                CREATE VIEW datao_pure.vax_trans_view AS
                SELECT * FROM datao_pure.vax_trans;
                """;
        SchemaModel m = new CkBaselineParser().parse(ddl, "t", "t.sql").getSchema();
        ViewModel v = m.getViews().get(0);
        assertEquals("vax_trans_view", v.getName(), "视图名不能带库名");
        assertFalse(v.getQuery().contains("datao_pure"), "查询体里的库名也要剥掉：" + v.getQuery());

        String sql = new CkDdlRenderer(CkTopology.SINGLE, null, false).createView(v);
        assertFalse(sql.contains("datao_pure"), sql);
    }

    @Test
    void 分布式下建视图要带ON_CLUSTER但不改表名() {
        ViewModel v = baseline().getViews().get(0);
        String sql = new CkDdlRenderer(CkTopology.DISTRIBUTED, "'{cluster}'", true).createView(v);
        assertTrue(sql.contains("ON CLUSTER '{cluster}'"), sql);
        assertFalse(sql.contains("_local"),
                "视图查的是路由表，换成 _local 会让它只看到本分片的数据");
    }
}
