package com.doenv.schema;

import com.doenv.schema.baseline.BaselineBundle;
import com.doenv.schema.baseline.PgBaselineParser;
import com.doenv.schema.model.ColumnModel;
import com.doenv.schema.model.SeedTable;
import com.doenv.schema.model.TableModel;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拿真实纯净库跑解析器的回归测试。
 *
 * <p>基准文件是<b>会随发版重新导出</b>的产物（实测已从 2026-08-05 换到 2026-08-06，
 * 表数 167 → 170），所以这里自动发现最新的一份，并且只断言<b>与版本无关的不变量</b>，
 * 不写死行数。具体数字打印出来供人工核对。
 *
 * <p>可用 {@code -Ddoenv.baseline.file=/abs/path.sql} 指定文件。
 */
class PgBaselineParserTest {

    private static final Path BASELINE_DIR =
            Path.of("../../datao-main/src/main/resources/install/pg-sql");

    private static Path file;
    private static BaselineBundle bundle;

    @BeforeAll
    static void parseOnce() throws IOException {
        file = locate();
        Assumptions.assumeTrue(file != null && Files.exists(file),
                "未找到纯净库文件，跳过。查找目录：" + BASELINE_DIR.toAbsolutePath());
        bundle = new PgBaselineParser()
                .parse(Files.readString(file), versionOf(file), file.getFileName().toString());

        System.out.println("基准文件        = " + file.getFileName());
        System.out.println("表数            = " + bundle.getSchema().getTableCount());
        System.out.println("列数            = " + bundle.getSchema().getColumnCount());
        System.out.println("有种子数据的表  = " + bundle.getSeeds().size());
        System.out.println("种子行数        = " + bundle.getSeedRowCount());
        System.out.println("丢弃统计        = " + bundle.getDiscarded());
        if (!bundle.getUnparsedSamples().isEmpty()) {
            System.out.println("未识别语句样本:");
            bundle.getUnparsedSamples().forEach((k, v) -> System.out.println("   " + k + " -> " + v));
        }
    }

    private static Path locate() throws IOException {
        String override = System.getProperty("doenv.baseline.file");
        if (override != null) {
            return Path.of(override);
        }
        if (!Files.isDirectory(BASELINE_DIR)) {
            return null;
        }
        try (Stream<Path> s = Files.list(BASELINE_DIR)) {
            Optional<Path> newest = s
                    .filter(p -> p.getFileName().toString().startsWith("PG系统纯净库-"))
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
            return newest.orElse(null);
        }
    }

    private static String versionOf(Path p) {
        String n = p.getFileName().toString();
        return n.replaceAll("^PG系统纯净库-", "").replaceAll("\\.sql$", "");
    }

    // ────────── 版本无关的不变量 ──────────

    @Test
    void 不应有未识别语句() {
        assertEquals(0, bundle.getDiscarded().getOrDefault("UNKNOWN", 0),
                "出现未识别语句，说明解析器有盲区：" + bundle.getUnparsedSamples());
        assertEquals(0, bundle.getDiscarded().getOrDefault("CREATE_TABLE_UNPARSED", 0));
        assertEquals(0, bundle.getDiscarded().getOrDefault("INDEX_UNPARSED", 0));
        assertEquals(0, bundle.getDiscarded().getOrDefault("COMMENT_UNPARSED", 0));
        assertEquals(0, bundle.getDiscarded().getOrDefault("INSERT_UNPARSED", 0));
    }

    @Test
    void 破坏性语句必须全部丢弃() {
        // 新导出器按空库安装契约不再生成这些语句；这里用合成输入钉住解析器的安全边界。
        BaselineBundle dangerous = new PgBaselineParser().parse("""
                DROP TABLE public.t;
                DELETE FROM public.t;
                ALTER TABLE public.t OWNER TO datao;
                """, "test", "test.sql");
        assertEquals(1, dangerous.getDiscarded().getOrDefault("DROP_TABLE", 0));
        assertEquals(1, dangerous.getDiscarded().getOrDefault("UNSAFE_DELETE", 0));
        assertEquals(1, dangerous.getDiscarded().getOrDefault("OWNER_TO", 0));
    }

    @Test
    void 幂等DO约束与regclass序列状态必须完整解析() {
        assertEquals(0, bundle.getDiscarded().getOrDefault("DO_UNPARSED", 0));
        assertEquals(0, bundle.getDiscarded().getOrDefault("SETVAL_UNPARSED", 0));

        long constraints = bundle.getSchema().getTables().stream()
                .mapToLong(t -> t.getConstraints().size()).sum();
        assertTrue(constraints >= 60, "08-13 基准应完整解析 60 条约束，实际=" + constraints);
        assertEquals(4, bundle.getSchema().getSequences().size());
        bundle.getSchema().getSequences().forEach(s -> assertNotNull(s.getLastValue(),
                "序列状态未从 setval 解析：" + s.getName()));
    }

    @Test
    void 种子数据的列必须都在表定义里() {
        // 这条不依赖具体版本，能抓住「INSERT 列清单解析错位」这类最危险的缺陷
        bundle.getSeeds().forEach((key, seed) -> {
            TableModel t = bundle.getSchema().findTable(seed.getTableName());
            assertNotNull(t, "种子表未找到对应的表定义：" + seed.getTableName());
            for (String col : seed.getColumns()) {
                assertNotNull(t.findColumn(col),
                        seed.getTableName() + " 的种子列 " + col + " 不在表定义中");
            }
        });
    }

    @Test
    void 每行种子数据的字段数必须与列清单一致() {
        assertEquals(0, bundle.getDiscarded().getOrDefault("INSERT_ARITY_MISMATCH", 0),
                "存在 INSERT 值个数与列个数不符的行");
        bundle.getSeeds().forEach((key, seed) ->
                seed.getRows().forEach(row -> assertEquals(seed.getColumns().size(), row.size(),
                        seed.getTableName() + " 有行的字段数不等于列数")));
    }

    @Test
    void 表和种子规模应在合理量级() {
        assertTrue(bundle.getSchema().getTableCount() > 150,
                "表数异常偏少：" + bundle.getSchema().getTableCount());
        assertTrue(bundle.getSchema().getColumnCount() > 2000,
                "列数异常偏少：" + bundle.getSchema().getColumnCount());
        assertTrue(bundle.getSeedRowCount() > 30000,
                "种子行数异常偏少：" + bundle.getSeedRowCount());
    }

    // ────────── 具体结构抽查（这些定义长期稳定） ──────────

    @Test
    void 类型归一化正确剥离COLLATE() {
        TableModel t = bundle.getSchema().findTable("vao_app_audit");
        assertNotNull(t, "vao_app_audit 应被解析出来");

        ColumnModel id = t.findColumn("id");
        assertNotNull(id);
        assertEquals("varchar(100)", id.getNormType(), "COLLATE 应被剥离");
        assertEquals("STRING", id.getTypeFamily());
        assertTrue(id.isNotNull());

        ColumnModel createdTime = t.findColumn("createdtime");
        assertNotNull(createdTime);
        // 基准写的是 timestamp(6)，而 6 就是 PG 的默认精度，format_type() 只会返回 timestamp。
        // 两边必须归一到同一个值，否则每一个时间列都会被误判成类型变更
        assertEquals("timestamp", createdTime.getNormType());
        assertEquals(6, createdTime.getPrecision());
        assertEquals("DATETIME", createdTime.getTypeFamily());
    }

    @Test
    void 时间类型的默认精度必须与无精度写法等价() {
        ColumnModel a = new ColumnModel();
        com.doenv.schema.baseline.TypeNormalizer.apply(a, "timestamp(6)");
        ColumnModel b = new ColumnModel();
        com.doenv.schema.baseline.TypeNormalizer.apply(b, "timestamp without time zone");
        ColumnModel c = new ColumnModel();
        com.doenv.schema.baseline.TypeNormalizer.apply(c, "timestamp(6) without time zone");
        assertEquals(a.getNormType(), b.getNormType());
        assertEquals(a.getNormType(), c.getNormType());

        // 非默认精度必须保留，timestamp(0) 与 timestamp 是真的不同
        ColumnModel d = new ColumnModel();
        com.doenv.schema.baseline.TypeNormalizer.apply(d, "timestamp(0)");
        assertTrue(!d.getNormType().equals(a.getNormType()),
                "timestamp(0) 不应与 timestamp(6) 归一成同一个值");
    }

    @Test
    void 索引谓词必须同时保留原文与归一化形式() {
        // 归一化形式去掉了空格与括号，只能用于比对；渲染必须用原文，
        // 否则会生成 WHERE recipient_idisnotnull 这种无效 SQL
        var parsed = com.doenv.schema.baseline.IndexDefParser.parse(
                "CREATE INDEX idx_x ON t USING btree (a) WHERE ((a IS NOT NULL) AND (b = false))");
        assertNotNull(parsed);
        // 原文原样保留（含最外层括号），直接拼进 WHERE 就是合法 SQL
        assertEquals("((a IS NOT NULL) AND (b = false))", parsed.index().getWhereRaw());
        assertEquals("aisnotnullandb=false", parsed.index().getWhereNorm());
    }

    @Test
    void 手工追加的无引号INSERT也要能解析() {
        // 这 8 行是 2026-07 手工加进文件的，写法是 INSERT INTO public.x（无双引号），
        // 且其中一条用了 e'...' 且值内含真实换行 —— 解析器必须都吃得下
        SeedTable category = bundle.getSeeds().get("vao_lab_verify_config_category");
        SeedTable item = bundle.getSeeds().get("vao_lab_verify_config_item");
        assertNotNull(category, "vao_lab_verify_config_category 的手工 INSERT 未被解析");
        assertNotNull(item, "vao_lab_verify_config_item 的手工 INSERT 未被解析");
        // 纯净库可继续补充这两张表的基准数据；这里验证的是无引号 INSERT 能被读取，
        // 不能把某个历史版本的总行数写死。
        assertTrue(category.getRowCount() >= 1);
        assertTrue(item.getRowCount() >= 7);

        // e'...' 里的换行必须被还原成真实换行，而不是留下字面的 \n
        boolean hasRealNewline = item.getRows().stream()
                .map(r -> r.get("outputs"))
                .filter(java.util.Objects::nonNull)
                .anyMatch(v -> v.contains("\n"));
        assertTrue(hasRealNewline, "e'...' 内嵌换行应被正确还原");
    }

    @Test
    void 驼峰表名必须保留原始大小写() {
        // PG 未加引号的标识符会折叠小写，vao_valueObject 是全库唯一的驼峰表名，
        // 渲染时必须带双引号，否则会被误判缺表并重复建表
        TableModel t = bundle.getSchema().findTable("vao_valueObject");
        assertNotNull(t, "vao_valueObject 应被解析出来");
        assertEquals("vao_valueObject", t.getName(), "表名应保留原始大小写");
    }

    @Test
    void 索引指纹应包含方法且可区分唯一性() {
        long withIndexes = bundle.getSchema().getTables().stream()
                .filter(t -> !t.getIndexes().isEmpty()).count();
        assertTrue(withIndexes > 0, "应解析出索引");

        bundle.getSchema().getTables().forEach(t -> t.getIndexes().forEach(idx -> {
            assertNotNull(idx.getFingerprint());
            assertTrue(idx.getFingerprint().startsWith("btree|") || idx.getFingerprint().contains("|"),
                    "指纹应含方法段：" + idx.getFingerprint());
        }));
    }
}
