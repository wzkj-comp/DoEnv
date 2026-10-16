package com.doenv.schema;

import com.doenv.schema.baseline.BaselineBundle;
import com.doenv.schema.baseline.PgBaselineParser;
import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffLevel;
import com.doenv.schema.diff.DiffOptions;
import com.doenv.schema.diff.DiffType;
import com.doenv.schema.diff.SchemaDiffEngine;
import com.doenv.schema.live.PgLiveSchemaReader;
import com.doenv.schema.model.SchemaModel;
import com.doenv.schema.render.PgDdlRenderer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端集成测试：基准文件 → 解析 → 读现场 → 差异 → 生成 SQL。
 *
 * <p>需要一个可连的 PG。默认不跑，用系统属性开启：
 * <pre>
 * mvn test -Dtest=SchemaDiffIntegrationTest \
 *   -Ddoenv.it.url=jdbc:postgresql://127.0.0.1:5432/datao_dev \
 *   -Ddoenv.it.user=postgres -Ddoenv.it.password=postgres
 * </pre>
 */
class SchemaDiffIntegrationTest {

    private static final Path BASELINE_DIR =
            Path.of("../../datao-main/src/main/resources/install/pg-sql");

    private JdbcTemplate jdbc() {
        String url = System.getProperty("doenv.it.url");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "未设置 -Ddoenv.it.url，跳过集成测试");
        DriverManagerDataSource ds = new DriverManagerDataSource(url,
                System.getProperty("doenv.it.user", "postgres"),
                System.getProperty("doenv.it.password", "postgres"));
        ds.setDriverClassName("org.postgresql.Driver");
        return new JdbcTemplate(ds);
    }

    private BaselineBundle baseline() throws IOException {
        Path f;
        try (Stream<Path> s = Files.list(BASELINE_DIR)) {
            Optional<Path> newest = s
                    .filter(p -> p.getFileName().toString().startsWith("PG系统纯净库-"))
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
            f = newest.orElse(null);
        }
        Assumptions.assumeTrue(f != null, "未找到纯净库文件");
        return new PgBaselineParser().parse(Files.readString(f), "it", f.getFileName().toString());
    }

    @Test
    void 端到端比对并生成SQL() throws Exception {
        JdbcTemplate jdbc = jdbc();
        BaselineBundle b = baseline();

        PgLiveSchemaReader reader = new PgLiveSchemaReader(jdbc);
        String schema = reader.currentSchema();
        SchemaModel live = reader.read(schema);

        System.out.println("===== 环境 =====");
        System.out.println("库        = " + reader.currentDatabase());
        System.out.println("schema    = " + schema);
        System.out.println("vao_ 表数 = " + reader.countVaoTables(schema));
        System.out.println("可建表    = " + reader.canCreateTable(schema));
        System.out.println("INVALID 索引 = " + reader.findInvalidIndexes(schema));
        System.out.println("基准 表/列 = " + b.getSchema().getTableCount()
                + " / " + b.getSchema().getColumnCount());
        System.out.println("现场 表/列 = " + live.getTableCount() + " / " + live.getColumnCount());

        PgDdlRenderer renderer = new PgDdlRenderer(schema);
        DiffOptions opt = new DiffOptions();
        List<DiffItem> diffs = new SchemaDiffEngine(renderer).diff(b.getSchema(), live, opt);

        Map<DiffType, Long> byType = diffs.stream()
                .collect(Collectors.groupingBy(DiffItem::getType, Collectors.counting()));
        Map<DiffLevel, Long> byLevel = diffs.stream()
                .collect(Collectors.groupingBy(DiffItem::getLevel, Collectors.counting()));

        System.out.println("\n===== 差异汇总 =====");
        System.out.println("总数 = " + diffs.size());
        byType.entrySet().stream()
                .sorted(Map.Entry.<DiffType, Long>comparingByValue().reversed())
                .forEach(e -> System.out.printf("  %-22s %d%n", e.getKey(), e.getValue()));
        System.out.println("按档位 = " + byLevel);

        System.out.println("\n===== 前 15 条非 INFO 差异 =====");
        diffs.stream()
                .filter(d -> d.getLevel() != DiffLevel.INFO)
                .limit(15)
                .forEach(d -> System.out.printf("  [%s/%s] %s.%s  现场=%s  基准=%s%n",
                        d.getLevel(), d.getType(), d.getTableName(),
                        d.getObjectName() == null ? "" : d.getObjectName(),
                        d.getLiveDef(), d.getBaselineDef()));

        System.out.println("\n===== 样例 SQL =====");
        diffs.stream()
                .filter(d -> d.getLevel() == DiffLevel.AUTO && !d.getDdl().isEmpty())
                .limit(5)
                .forEach(d -> d.getDdl().forEach(s -> System.out.println("  " + s)));

        // ── 安全红线断言 ──
        String allSql = diffs.stream()
                .flatMap(d -> d.getDdl().stream())
                .collect(Collectors.joining("\n"))
                .toUpperCase();

        assertFalse(allSql.contains("DROP TABLE"), "生成的 SQL 不允许出现 DROP TABLE");
        assertFalse(allSql.contains("TRUNCATE"), "生成的 SQL 不允许出现 TRUNCATE");
        diffs.stream()
                .flatMap(d -> d.getDdl().stream().map(sql -> java.util.Map.entry(d, sql)))
                .filter(x -> x.getValue().toUpperCase().contains("DROP COLUMN"))
                .forEach(x -> {
                    assertTrue(x.getKey().getType() == DiffType.COLUMN_DROP,
                            "只有 COLUMN_DROP 可以生成删列 SQL");
                    assertFalse(x.getValue().toUpperCase().contains("CASCADE"),
                            "删列不得使用 CASCADE");
                });

        // 现场多出的对象只能是 INFO 档，且不得生成任何 SQL
        diffs.stream()
                .filter(d -> d.getType() == DiffType.TABLE_EXTRA
                        || d.getType() == DiffType.COLUMN_EXTRA
                        || d.getType() == DiffType.INDEX_EXTRA)
                .forEach(d -> {
                    assertTrue(d.getLevel() == DiffLevel.INFO, d.getKey() + " 应为 INFO 档");
                    assertTrue(d.getDdl().isEmpty(), d.getKey() + " 不应生成 SQL");
                });

        // 所有 PG 语句必须带 schema 限定符
        diffs.stream().flatMap(d -> d.getDdl().stream())
                .filter(s -> s.startsWith("ALTER TABLE ") || s.startsWith("CREATE TABLE ")
                        || s.startsWith("COMMENT ON "))
                .forEach(s -> assertTrue(s.contains('"' + schema + '"' + "."),
                        "语句缺少 schema 限定符：" + s.substring(0, Math.min(120, s.length()))));
    }
}
