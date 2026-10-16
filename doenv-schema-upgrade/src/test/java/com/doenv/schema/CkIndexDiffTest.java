package com.doenv.schema;

import com.doenv.schema.baseline.BaselineBundle;
import com.doenv.schema.baseline.CkBaselineParser;
import com.doenv.schema.diff.CkSchemaDiffEngine;
import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffLevel;
import com.doenv.schema.diff.DiffOptions;
import com.doenv.schema.diff.DiffType;
import com.doenv.schema.live.CkTopology;
import com.doenv.schema.model.CkIndexModel;
import com.doenv.schema.model.SchemaModel;
import com.doenv.schema.model.TableModel;
import com.doenv.schema.render.CkDdlRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CK 跳数索引比对的回归测试。
 *
 * <p>补索引是 {@code ALTER TABLE ... ADD INDEX}，纯元数据操作 —— 不重建表、不重写数据，
 * 所以可以自动补。但它<b>只对之后写入的数据生效</b>，存量数据要 MATERIALIZE 才建索引文件，
 * 那是扫全表的重操作，不能由工具替现场决定。
 *
 * <p>真实缺口：现场实测基准 143 张表 647 个索引，现场只有 90 张表 534 个，
 * 而工具此前完全不比对索引 —— 删掉一个索引再跑，报告说「无差异」。
 */
class CkIndexDiffTest {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS vax_demo
            (
                `id` String,
                `caseid` String,
                `phoneno` Nullable(String),
                `ownername` Nullable(String),
                INDEX idx_phone phoneno TYPE minmax GRANULARITY 5,
                INDEX idx_multi (caseid, id) TYPE minmax GRANULARITY 1
            )
            ENGINE = MergeTree
            PARTITION BY caseid
            ORDER BY id;
            """;

    private static SchemaModel baseline() {
        BaselineBundle b = new CkBaselineParser().parse(DDL, "t", "t.sql");
        return b.getSchema();
    }

    /** 造现场：拷贝基准，然后按名字剔除若干索引 */
    private static SchemaModel liveWithout(String... dropIndexNames) {
        SchemaModel m = baseline();
        TableModel t = m.findTable("vax_demo");
        List<String> drop = List.of(dropIndexNames);
        t.getCk().getIndexes().removeIf(i -> drop.contains(i.getName()));
        return m;
    }

    private static List<DiffItem> diff(SchemaModel base, SchemaModel live, long rows) {
        return new CkSchemaDiffEngine(
                new CkDdlRenderer(CkTopology.SINGLE, null, false), t -> rows)
                .diff(base, live, new DiffOptions());
    }

    @Test
    void 基准的索引能被解析出来() {
        TableModel t = baseline().findTable("vax_demo");
        assertEquals(2, t.getCk().getIndexes().size());
        CkIndexModel multi = t.getCk().getIndexes().stream()
                .filter(i -> "idx_multi".equals(i.getName())).findFirst().orElseThrow();
        assertEquals("minmax", multi.getType());
        assertEquals(1, multi.getGranularity());
        // 带括号的多列表达式必须完整保留，否则生成的 ADD INDEX 是废的
        assertEquals("caseid,id", multi.normalizedExpression());
    }

    @Test
    void 缺索引会生成ADD_INDEX() {
        List<DiffItem> diffs = diff(baseline(), liveWithout("idx_phone"), 0);
        DiffItem d = diffs.stream()
                .filter(x -> x.getType() == DiffType.CK_INDEX_MISSING)
                .findFirst().orElseThrow(() -> new AssertionError("没检出缺失的索引"));

        assertEquals(DiffLevel.AUTO, d.getLevel(), "补索引不重建表，可自动执行");
        assertEquals(1, d.getDdl().size());
        String sql = d.getDdl().get(0).toUpperCase();
        assertTrue(sql.contains("ADD INDEX IF NOT EXISTS"));
        assertTrue(sql.contains("TYPE MINMAX"));
        assertTrue(sql.contains("GRANULARITY 5"));
    }

    @Test
    void 绝不生成MATERIALIZE或DROP() {
        List<DiffItem> diffs = diff(baseline(), liveWithout("idx_phone", "idx_multi"), 100_000);
        List<String> all = diffs.stream().flatMap(d -> d.getDdl().stream()).toList();

        assertFalse(all.stream().anyMatch(s -> s.toUpperCase().contains("MATERIALIZE")),
                "MATERIALIZE 要扫全表，是小时级重操作，不能自动跑");
        assertFalse(all.stream().anyMatch(s -> s.toUpperCase().contains("DROP INDEX")),
                "只补不删");
    }

    @Test
    void 有存量数据时要提示索引只对新数据生效() {
        DiffItem d = diff(baseline(), liveWithout("idx_phone"), 12_345).stream()
                .filter(x -> x.getType() == DiffType.CK_INDEX_MISSING)
                .findFirst().orElseThrow();
        assertTrue(d.getNotes().stream().anyMatch(n -> n.contains("MATERIALIZE")),
                "有数据时必须提示存量数据不会自动建索引，否则会误以为索引已全面生效");
        assertEquals(12_345L, d.getEstimatedRows());
    }

    @Test
    void 分布式下ADD_INDEX只能打本地表() {
        // 项目既有约定：Distributed 引擎不存索引，对路由表 ADD INDEX 直接报 Code 48。
        // 之前的实现打在了路由表上，分布式现场必然失败
        CkIndexModel i = new CkIndexModel();
        i.setName("idx_phone");
        i.setExpression("phoneno");
        i.setType("minmax");
        i.setGranularity(5);

        String dist = new CkDdlRenderer(CkTopology.DISTRIBUTED, "'{cluster}'", true)
                .addIndex("vax_person", i);
        assertTrue(dist.contains("`vax_person_local`"), "必须打在本地表上：" + dist);
        assertFalse(dist.contains("TABLE `vax_person` "), "不能打在路由表上：" + dist);
        assertTrue(dist.contains("ON CLUSTER '{cluster}'"));

        String single = new CkDdlRenderer(CkTopology.SINGLE, null, false)
                .addIndex("vax_person", i);
        assertTrue(single.contains("`vax_person`") && !single.contains("_local"),
                "单机没有本地表之分：" + single);
    }

    @Test
    void 建表语句必须带上索引() {
        // 漏掉的话新建表没索引、重建表（DROP+CREATE）索引全丢 ——
        // 实测现场 645 个索引升级后只剩 534 个就是这么来的
        TableModel t = baseline().findTable("vax_demo");
        List<String> sql = new CkDdlRenderer(CkTopology.SINGLE, null, false).createTable(t);
        String create = sql.get(0);
        assertTrue(create.contains("INDEX `idx_phone`"), "建表语句缺索引：" + create);
        assertTrue(create.contains("INDEX `idx_multi`"));
        assertTrue(create.contains("TYPE minmax GRANULARITY 5"));
    }

    @Test
    void Decimal省略scale要与回读结果等价() {
        // 基准写 Decimal(38)，CK 存下来回读是 Decimal(38,0)。不归一的话每轮都会
        // 生成同一条 MODIFY COLUMN 却永远改不掉 —— 实测 vax_swap_bridges 的
        // fromamount/toamount 就这么把整次升级卡成「未收敛」
        assertEquals(com.doenv.schema.baseline.CkTokens.normalizeType("Nullable(Decimal(38))"),
                com.doenv.schema.baseline.CkTokens.normalizeType("Nullable(Decimal(38, 0))"));
        assertEquals(com.doenv.schema.baseline.CkTokens.normalizeType("Decimal(38)"),
                com.doenv.schema.baseline.CkTokens.normalizeType("Decimal(38,0)"));
        // 显式写了 scale 的不能被改动
        assertEquals("Decimal(18,4)", com.doenv.schema.baseline.CkTokens.normalizeType("Decimal(18, 4)"));
        // Decimal128(4) 里的 4 是精度不是 scale，不能被当成 Decimal(P) 补零
        assertEquals("Decimal128(4)", com.doenv.schema.baseline.CkTokens.normalizeType("Decimal128(4)"));
    }

    @Test
    void 带时区的时间字面量要转成CK能存的格式() throws Exception {
        // 基准里有 Java ZonedDateTime.toString() 直接写进 SQL 的值（实测 217 处）。
        // CK 的 DateTime 不带时区，原样下发一律报 Code 6，那 4 张表的配置数据就永远进不去
        var m = com.doenv.schema.seed.CkSeedDiffEngine.class
                .getDeclaredMethod("adaptDateTime", String.class);
        m.setAccessible(true);

        assertEquals("'2026-01-25 08:10:30'",
                m.invoke(null, "'2026-01-25T08:10:30+08:00[Asia/Shanghai]'"));
        assertEquals("'2026-03-19 01:44:27'", m.invoke(null, "'2026-03-19T01:44:27+08:00'"));
        assertEquals("'2026-01-25 08:10:30'", m.invoke(null, "'2026-01-25T08:10:30Z'"));
        // 秒为 0 时 Java 会省略它，CK 的 DateTime 要补满 HH:MM:SS
        assertEquals("'2024-01-01 08:00:00'", m.invoke(null, "'2024-01-01T08:00+08:00[Asia/Shanghai]'"));
        assertEquals("'2024-01-01 08:00:00'", m.invoke(null, "'2024-01-01T08:00'"));

        // 其余一律原样，不做任何加工 —— now() 被当字符串那个坑就是这么来的
        assertEquals("'2026-01-25 08:10:30'", m.invoke(null, "'2026-01-25 08:10:30'"));
        assertEquals("now()", m.invoke(null, "now()"));
        assertEquals("NULL", m.invoke(null, "NULL"));
        assertEquals("'普通字符串'", m.invoke(null, "'普通字符串'"));
    }

    @Test
    void 现场多出的索引不处理() {
        SchemaModel base = liveWithout("idx_phone");   // 基准少一个
        List<DiffItem> diffs = diff(base, baseline(), 0);   // 现场反而多一个
        assertFalse(diffs.stream().anyMatch(d -> d.getType() == DiffType.CK_INDEX_MISSING),
                "现场多出的索引属于「现场自己加的」，只补不删");
        assertFalse(diffs.stream().flatMap(d -> d.getDdl().stream())
                        .anyMatch(s -> s.toUpperCase().contains("DROP INDEX")));
    }

    @Test
    void 有数据的不可变结构差异只留跳过原因() {
        String baseDdl = """
                CREATE TABLE vax_demo (`id` String, `caseid` String)
                ENGINE = MergeTree PARTITION BY caseid ORDER BY (id, caseid);
                """;
        String liveDdl = """
                CREATE TABLE vax_demo (`id` String, `caseid` String)
                ENGINE = MergeTree ORDER BY id;
                """;
        SchemaModel base = new CkBaselineParser().parse(baseDdl, "base", "base.sql").getSchema();
        SchemaModel live = new CkBaselineParser().parse(liveDdl, "live", "live.sql").getSchema();

        DiffItem item = diff(base, live, 10).stream()
                .filter(d -> d.getType() == DiffType.CK_IMMUTABLE_DIFF)
                .findFirst().orElseThrow();

        assertEquals(DiffLevel.MANUAL, item.getLevel());
        assertTrue(item.getDdl().isEmpty(), "待审核只应保留原因，不应输出迁移 SQL");
        assertTrue(item.getNotes().stream().anyMatch(n -> n.contains("表有数据（10 行）")));
        assertFalse(item.getNotes().stream().anyMatch(n -> n.contains("解决方案")));
    }
}
