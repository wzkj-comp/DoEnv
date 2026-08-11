package com.doenv.schema;

import com.doenv.schema.baseline.BaselineBundle;
import com.doenv.schema.baseline.CkBaselineParser;
import com.doenv.schema.diff.CkSchemaDiffEngine;
import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffLevel;
import com.doenv.schema.diff.DiffOptions;
import com.doenv.schema.live.CkLiveSchemaReader;
import com.doenv.schema.live.CkTopology;
import com.doenv.schema.model.SchemaModel;
import com.doenv.schema.render.CkDdlRenderer;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CK 端到端比对。需要一个可连的 ClickHouse：
 *
 * <pre>
 * mvn test -Dtest=CkDiffIntegrationTest \
 *   -Ddoenv.ck.url=jdbc:clickhouse://host:8123/db \
 *   -Ddoenv.ck.user=default -Ddoenv.ck.password=xxx
 * </pre>
 */
class CkDiffIntegrationTest {

    private static final Path BASELINE_DIR =
            Path.of("../../datao-main/src/main/resources/install/clickhouse-sql");

    @Test
    void 比对真实CK库并检查生成物() throws Exception {
        String url = System.getProperty("doenv.ck.url");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "未设置 -Ddoenv.ck.url，跳过");

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(System.getProperty("doenv.ck.user", "default"));
        ds.setPassword(System.getProperty("doenv.ck.password", ""));
        ds.setDriverClassName("ru.yandex.clickhouse.ClickHouseDriver");
        ds.setMaximumPoolSize(2);

        try (ds) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            CkLiveSchemaReader reader = new CkLiveSchemaReader(jdbc);

            CkTopology topo = reader.detectTopology();
            Map<String, String> macros = reader.macros();
            List<String> clusters = reader.clusterCandidates();

            System.out.println("===== CK 环境 =====");
            System.out.println("库        = " + reader.currentDatabase());
            System.out.println("版本      = " + reader.version());
            System.out.println("拓扑      = " + topo);
            System.out.println("宏        = " + macros);
            System.out.println("集群候选  = " + clusters);
            System.out.println("表总数    = " + reader.countAllTables());
            System.out.println("vax_ 表数 = " + reader.countVaxTables());

            // 连错库硬闸：vax_ 表为 0 或表总数过少，禁止一切 CREATE
            int vax = reader.countVaxTables();
            boolean suspectWrongDb = vax == 0 || reader.countAllTables() < 100;
            System.out.println("疑似连错库 = " + suspectWrongDb);

            Path file = locate();
            Assumptions.assumeTrue(file != null, "未找到 CK 纯净库");
            BaselineBundle b = new CkBaselineParser()
                    .parse(Files.readString(file), "it", file.getFileName().toString());

            SchemaModel live = reader.read(topo);
            System.out.println("基准 表/列 = " + b.getSchema().getTableCount()
                    + " / " + b.getSchema().getColumnCount());
            System.out.println("现场 表/列 = " + live.getTableCount()
                    + " / " + live.getColumnCount());

            String clusterRef = macros.containsKey("cluster") ? "'{cluster}'"
                    : (clusters.size() == 1 ? "'" + clusters.get(0) + "'" : null);
            boolean zkOk = macros.containsKey("database") && macros.containsKey("shard")
                    && macros.containsKey("replica");

            CkDdlRenderer renderer = new CkDdlRenderer(topo, clusterRef, zkOk);
            List<DiffItem> diffs = new CkSchemaDiffEngine(renderer)
                    .diff(b.getSchema(), live, new DiffOptions());

            Map<String, Integer> byType = new TreeMap<>();
            Map<String, Integer> byLevel = new TreeMap<>();
            diffs.forEach(d -> {
                byType.merge(d.getType().name(), 1, Integer::sum);
                byLevel.merge(d.getLevel().name(), 1, Integer::sum);
            });
            System.out.println("===== 差异 =====");
            System.out.println("总数     = " + diffs.size());
            System.out.println("按类型   = " + byType);
            System.out.println("按档位   = " + byLevel);

            System.out.println("===== 非 AUTO 差异明细 =====");
            diffs.stream()
                    .filter(d -> d.getLevel() == DiffLevel.AUTO)
                    .limit(4)
                    .forEach(d -> {
                        System.out.println("  [" + d.getLevel() + "/" + d.getType() + "] "
                                + d.getTableName()
                                + (d.getObjectName() == null ? "" : "." + d.getObjectName()));
                        if (d.getLiveDef() != null) {
                            System.out.println("       现场: " + d.getLiveDef());
                        }
                        if (d.getBaselineDef() != null) {
                            System.out.println("       基准: " + d.getBaselineDef());
                        }
                    });

            List<DiffItem> execItems = diffs.stream()
                    .filter(d -> d.getLevel() == DiffLevel.AUTO || d.getLevel() == DiffLevel.CONFIRM)
                    .toList();
            List<String> sqls = execItems.stream().flatMap(d -> d.getDdl().stream()).toList();

            System.out.println("===== 样例 SQL =====");
            sqls.stream().limit(3).forEach(s ->
                    System.out.println("  " + s.replaceAll("\\s+", " ").substring(0,
                            Math.min(220, s.replaceAll("\\s+", " ").length())) + " …"));

            // ── 生成物自检：CK 侧红线 ──
            List<String> errors = CkDdlRenderer.validateItems(execItems);
            errors.forEach(e -> System.out.println("  自检错误: " + e));
            assertTrue(errors.isEmpty(), "生成的 CK SQL 未通过自检：" + errors);

            // 单机形态下绝不能出现 ON CLUSTER / _local / Replicated
            if (topo == CkTopology.SINGLE) {
                sqls.forEach(s -> {
                    assertTrue(!s.contains("ON CLUSTER"), "单机不该生成 ON CLUSTER：" + head(s));
                    assertTrue(!s.contains("_local"), "单机不该生成 _local：" + head(s));
                    assertTrue(!s.contains("Replicated"), "单机不该生成 Replicated：" + head(s));
                });
            }
            // 表引用一律不带库名
            sqls.forEach(s -> assertTrue(!s.contains(reader.currentDatabase() + "."),
                    "CK 语句不该带库名：" + head(s)));
        }
    }

    @Test
    void 分片键推导规则与gen_dist一致() throws Exception {
        Path file = locate();
        Assumptions.assumeTrue(file != null, "未找到 CK 纯净库");
        BaselineBundle b = new CkBaselineParser()
                .parse(Files.readString(file), "it", "baseline");

        Map<String, Integer> dist = new TreeMap<>();
        b.getSchema().getTables().forEach(t ->
                dist.merge(CkDdlRenderer.deriveShardKey(t), 1, Integer::sum));
        System.out.println("分片键分布 = " + dist);

        // 不写死表数：基准会随发版增长（实测已从 503 涨到 507），
        // 真正的不变量是「每一张表都推得出分片键，一张都不能漏」
        assertEquals(b.getSchema().getTableCount(),
                dist.values().stream().mapToInt(Integer::intValue).sum(),
                "分片键必须覆盖基准里的每一张表");
        assertNotNull(dist.get("javaHash(caseid)"), "应有 javaHash(caseid)");
        assertTrue(dist.getOrDefault("javaHash(caseid)", 0) > 250,
                "caseid 分片应占多数，实际 " + dist.get("javaHash(caseid)"));
    }

    private static Path locate() throws Exception {
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

    private static String head(String s) {
        String one = s.replaceAll("\\s+", " ");
        return one.length() > 120 ? one.substring(0, 120) + "…" : one;
    }
}
