package com.doenv.schema;

import com.doenv.schema.baseline.BaselineBundle;
import com.doenv.schema.baseline.CkBaselineParser;
import com.doenv.schema.model.CkTableModel;
import com.doenv.schema.model.ColumnModel;
import com.doenv.schema.model.TableModel;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拿真实 CK 单机纯净库跑解析器的回归测试。
 *
 * <p>与 PG 那份同样只断言<b>版本无关的不变量</b> —— 基准会随发版重新导出。
 */
class CkBaselineParserTest {

    private static final Path BASELINE_DIR =
            Path.of("../../datao-main/src/main/resources/install/clickhouse-sql");

    private static BaselineBundle bundle;

    @BeforeAll
    static void parseOnce() throws IOException {
        Path file = locate();
        Assumptions.assumeTrue(file != null && Files.exists(file),
                "未找到 CK 纯净库，跳过。查找目录：" + BASELINE_DIR.toAbsolutePath());
        bundle = new CkBaselineParser()
                .parse(Files.readString(file), "test", file.getFileName().toString());

        System.out.println("基准文件      = " + file.getFileName());
        System.out.println("表数          = " + bundle.getSchema().getTableCount());
        System.out.println("列数          = " + bundle.getSchema().getColumnCount());
        System.out.println("有种子的表    = " + bundle.getSeeds().size());
        System.out.println("种子行数      = " + bundle.getSeedRowCount());
        System.out.println("丢弃统计      = " + bundle.getDiscarded());

        Map<String, Integer> engines = new TreeMap<>();
        bundle.getSchema().getTables().forEach(t ->
                engines.merge(t.getCk() == null ? "<无>" : t.getCk().getEngineFamily(), 1, Integer::sum));
        System.out.println("引擎分布      = " + engines);

        if (!bundle.getUnparsedSamples().isEmpty()) {
            System.out.println("未识别样本:");
            bundle.getUnparsedSamples().forEach((k, v) -> System.out.println("   " + k + " -> " + v));
        }
    }

    private static Path locate() throws IOException {
        String override = System.getProperty("doenv.ck.baseline.file");
        if (override != null) {
            return Path.of(override);
        }
        if (!Files.isDirectory(BASELINE_DIR)) {
            return null;
        }
        try (Stream<Path> s = Files.list(BASELINE_DIR)) {
            Optional<Path> newest = s
                    .filter(p -> p.getFileName().toString().startsWith("CK单机"))
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
            return newest.orElse(null);
        }
    }

    @Test
    void 不应有未识别语句() {
        assertEquals(0, bundle.getDiscarded().getOrDefault("UNKNOWN", 0),
                "解析器有盲区：" + bundle.getUnparsedSamples());
        assertEquals(0, bundle.getDiscarded().getOrDefault("CREATE_TABLE_UNPARSED", 0));
        assertEquals(0, bundle.getDiscarded().getOrDefault("CREATE_TABLE_NO_ENGINE", 0),
                "每张表都必须解析出 ENGINE");
    }

    @Test
    void 规模应在合理量级() {
        assertTrue(bundle.getSchema().getTableCount() > 450,
                "表数异常：" + bundle.getSchema().getTableCount());
        assertTrue(bundle.getSchema().getColumnCount() > 5000,
                "列数异常：" + bundle.getSchema().getColumnCount());
    }

    @Test
    void 每张表都要有引擎且属于已知两族() {
        bundle.getSchema().getTables().forEach(t -> {
            assertNotNull(t.getCk(), t.getName() + " 缺 CK 定义");
            assertNotNull(t.getCk().getEngineFamily(), t.getName() + " 缺 ENGINE");
            String e = t.getCk().getEngineFamily();
            assertTrue(e.equals("MergeTree") || e.equals("ReplacingMergeTree"),
                    t.getName() + " 引擎超出预期：" + e);
        });
    }

    @Test
    void 标识符大小写必须原样保留() {
        // CK 大小写敏感，同一份基准里三种写法并存，折叠成小写会导致加出重复列
        assertNotNull(bundle.getSchema().findTable("AI_ANALYSIS_OBJECT"), "大写表名应存在");
        TableModel t = bundle.getSchema().findTable("AI_ANALYSIS_OBJECT");
        assertEquals("AI_ANALYSIS_OBJECT", t.getName(), "表名应原样保留");

        ColumnModel c = t.findColumn("CASE_ID");
        assertNotNull(c, "CASE_ID 列应被解析出来");
        assertEquals("CASE_ID", c.getName(), "列名应原样保留大写");
    }

    @Test
    void 列类型与可空性解析正确() {
        TableModel t = bundle.getSchema().findTable("AI_ANALYSIS_OBJECT");
        assertNotNull(t);

        ColumnModel id = t.findColumn("ID");
        assertEquals("String", id.getRawType());
        assertTrue(id.isNotNull(), "String 非 Nullable，应判为非空");

        ColumnModel abbr = t.findColumn("ABBREVIATED_NAME");
        assertEquals("Nullable(String)", abbr.getRawType());
        assertTrue(!abbr.isNotNull(), "Nullable(String) 应判为可空");
        assertEquals("STRING", abbr.getTypeFamily(), "剥掉 Nullable 后仍是 STRING 族");
    }

    @Test
    void 列注释应被解析() {
        TableModel t = bundle.getSchema().findTable("AI_ANALYSIS_OBJECT");
        assertEquals("分析对象集合ID", t.findColumn("ID").getComment());
        assertEquals("任务ID", t.findColumn("CASE_ID").getComment());
    }

    @Test
    void 引擎尾部子句解析正确() {
        TableModel t = bundle.getSchema().findTable("AI_ANALYSIS_OBJECT");
        CkTableModel ck = t.getCk();
        assertEquals("ReplacingMergeTree", ck.getEngineFamily());
        assertEquals(java.util.List.of("ID"), ck.getOrderBy());
        assertEquals(java.util.List.of("ID"), ck.getPrimaryKey());
        assertEquals("8192", ck.getSettings().get("index_granularity"));
        assertEquals("分析对象登记表", t.getComment(), "表注释应从尾部 COMMENT 子句取到");
    }

    @Test
    void 主键必须是排序键的前缀() {
        // CK 的硬要求。基准若违反，说明导出有问题
        bundle.getSchema().getTables().forEach(t -> {
            CkTableModel ck = t.getCk();
            if (ck == null || ck.getPrimaryKey().isEmpty() || ck.getOrderBy().isEmpty()) {
                return;
            }
            var pk = ck.getPrimaryKey();
            var ob = ck.getOrderBy();
            assertTrue(pk.size() <= ob.size(),
                    t.getName() + " 主键比排序键长：PK=" + pk + " ORDER=" + ob);
            for (int i = 0; i < pk.size(); i++) {
                assertEquals(ob.get(i), pk.get(i),
                        t.getName() + " 主键不是排序键前缀：PK=" + pk + " ORDER=" + ob);
            }
        });
    }
}
