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
import com.doenv.schema.service.CkUpgradeExecutor;
import com.doenv.schema.service.ExecutionResult;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CK 端到端升级：比对 → 执行 → 循环到收敛 → 校验。
 *
 * <pre>
 * mvn test -Dtest=CkUpgradeE2ETest \
 *   -Ddoenv.ck.url=jdbc:clickhouse://host:8123/testdb \
 *   -Ddoenv.ck.user=u -Ddoenv.ck.password=p -Ddoenv.ck.execute=true
 * </pre>
 *
 * <p>{@code doenv.ck.execute=true} 才会真改库，缺省只比对。
 */
class CkUpgradeE2ETest {

    private static final Path BASELINE_DIR =
            Path.of("../../datao-main/src/main/resources/install/clickhouse-sql");

    @Test
    void 升级到收敛并校验() throws Exception {
        String url = System.getProperty("doenv.ck.url");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "未设置 -Ddoenv.ck.url，跳过");
        boolean doExecute = Boolean.parseBoolean(System.getProperty("doenv.ck.execute", "false"));

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(System.getProperty("doenv.ck.user", "default"));
        ds.setPassword(System.getProperty("doenv.ck.password", ""));
        ds.setDriverClassName("ru.yandex.clickhouse.ClickHouseDriver");
        ds.setMaximumPoolSize(4);

        try (ds) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            CkLiveSchemaReader reader = new CkLiveSchemaReader(jdbc);
            CkTopology topo = reader.detectTopology();
            String db = reader.currentDatabase();

            Path file = locate();
            Assumptions.assumeTrue(file != null, "未找到 CK 纯净库");
            BaselineBundle b = new CkBaselineParser()
                    .parse(Files.readString(file), "e2e", file.getFileName().toString());

            System.out.println("===== 升级前 =====");
            System.out.println("库=" + db + " 拓扑=" + topo
                    + " 表数=" + reader.countAllTables());
            System.out.println("基准表数=" + b.getSchema().getTableCount());

            Map<String, String> macros = reader.macros();
            List<String> clusters = reader.clusterCandidates();
            String clusterRef = macros.containsKey("cluster") ? "'{cluster}'"
                    : (clusters.size() == 1 ? "'" + clusters.get(0) + "'" : null);
            boolean zkOk = macros.containsKey("database") && macros.containsKey("shard")
                    && macros.containsKey("replica");

            int tablesBefore = reader.countAllTables();

            // 行数探测器：空表可重建，有数据必须跳过
            CkSchemaDiffEngine.TableRowCounter counter = table -> {
                try {
                    Long n = jdbc.queryForObject(
                            "SELECT sum(rows) FROM system.parts WHERE database=currentDatabase() "
                                    + "AND table = ? AND active", Long.class, table);
                    return n == null ? 0L : n;
                } catch (RuntimeException e) {
                    return -1L;   // 取不到就按有数据保守处理
                }
            };

            List<ExecutionResult> rounds = new ArrayList<>();
            List<DiffItem> executedItems = new ArrayList<>();
            int maxRounds = 6;

            for (int round = 1; round <= maxRounds; round++) {
                CkDdlRenderer renderer = new CkDdlRenderer(topo, clusterRef, zkOk);
                SchemaModel live = reader.read(topo);
                List<DiffItem> diffs = new CkSchemaDiffEngine(renderer, counter)
                        .diff(b.getSchema(), live, new DiffOptions());

                // 生成物自检：DROP TABLE 只对「已确认空表重建」放行
                List<DiffItem> exec = diffs.stream()
                        .filter(d -> d.getLevel() == DiffLevel.AUTO || d.getLevel() == DiffLevel.CONFIRM)
                        .toList();
                List<String> errors = CkDdlRenderer.validateItems(exec);
                assertTrue(errors.isEmpty(), "生成的 CK SQL 未通过自检：" + errors);
                List<String> sqls = exec.stream().flatMap(d -> d.getDdl().stream()).toList();
                executedItems.addAll(exec);

                if (!doExecute) {
                    Map<String, Integer> byLevel = new TreeMap<>();
                    diffs.forEach(d -> byLevel.merge(d.getLevel().name(), 1, Integer::sum));
                    System.out.println("只比对模式，差异按档位 = " + byLevel
                            + "，可执行语句 " + sqls.size() + " 条");
                    return;
                }

                ExecutionResult r = new CkUpgradeExecutor(ds, jdbc, topo)
                        .execute(diffs, List.of(DiffLevel.AUTO, DiffLevel.CONFIRM), false, db);
                r.setRound(round);
                rounds.add(r);
                System.out.println("第 " + round + " 轮：" + r.getTotal() + " 条，成功 " + r.getOk()
                        + "，跳过 " + r.getSkipped() + "，失败 " + r.getFailed()
                        + "，耗时 " + r.getTotalCostMs() + "ms");

                if (r.getTotal() == 0) {
                    System.out.println("已收敛");
                    break;
                }
                if (r.getOk() == 0) {
                    System.out.println("本轮一条都没成功，停止");
                    break;
                }
            }

            // ── 收敛后校验 ──
            SchemaModel live = reader.read(topo);
            List<DiffItem> rest = new CkSchemaDiffEngine(
                    new CkDdlRenderer(topo, clusterRef, zkOk), counter)
                    .diff(b.getSchema(), live, new DiffOptions());

            long missingTable = rest.stream()
                    .filter(d -> d.getType() == com.doenv.schema.diff.DiffType.TABLE_MISSING).count();
            long missingCol = rest.stream()
                    .filter(d -> d.getType() == com.doenv.schema.diff.DiffType.COLUMN_MISSING).count();

            System.out.println("===== 升级后 =====");
            System.out.println("表数=" + reader.countAllTables()
                    + "  剩余缺表=" + missingTable + "  剩余缺列=" + missingCol);

            Map<String, Integer> byLevel = new TreeMap<>();
            rest.forEach(d -> byLevel.merge(d.getLevel().name(), 1, Integer::sum));
            System.out.println("剩余差异按档位 = " + byLevel);

            int totalOk = rounds.stream().mapToInt(ExecutionResult::getOk).sum();
            int totalFailed = rounds.stream().mapToInt(ExecutionResult::getFailed).sum();
            System.out.println("合计执行 " + totalOk + " 条成功，" + totalFailed + " 条失败");

            // ── 输出完整升级结果日志 ──
            String report = com.doenv.schema.service.CkUpgradeReportRenderer.render(
                    db, topo.name(), file.getFileName().toString(),
                    tablesBefore, reader.countAllTables(), rounds, rest)
                    + com.doenv.schema.service.CkUpgradeReportRenderer.renderRebuilds(executedItems);
            System.out.println();
            System.out.println(report);
            Path out = Path.of("../reports/ck");
            Files.createDirectories(out);
            Files.writeString(out.resolve("ck-upgrade-" + db + ".txt"), report);
            System.out.println("升级结果日志已写入 " + out.resolve("ck-upgrade-" + db + ".txt").toAbsolutePath());

            assertEquals(0, totalFailed, "执行过程中有语句失败");
            assertEquals(0, missingTable, "升级后仍有缺表");
            assertEquals(0, missingCol, "升级后仍有缺列");
        }
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
}
