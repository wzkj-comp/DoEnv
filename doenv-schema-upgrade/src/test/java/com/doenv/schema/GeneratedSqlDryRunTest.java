package com.doenv.schema;

import com.doenv.schema.baseline.BaselineBundle;
import com.doenv.schema.baseline.PgBaselineParser;
import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffLevel;
import com.doenv.schema.diff.DiffOptions;
import com.doenv.schema.diff.SchemaDiffEngine;
import com.doenv.schema.live.PgLiveSchemaReader;
import com.doenv.schema.model.SchemaModel;
import com.doenv.schema.render.PgDdlRenderer;
import com.doenv.schema.render.SeedSqlRenderer;
import com.doenv.schema.seed.SeedDiffEngine;
import com.doenv.schema.seed.SeedManifest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 把生成的每一条 SQL 真送进 PG 执行再回滚，验证语法确实成立。
 *
 * <p>这一层是必要的：曾经出过「用归一化后的谓词去渲染」的缺陷，
 * 生成了 {@code WHERE recipient_idisnotnullandnotification_type='x'} ——
 * 纯字符串断言看不出来，只有真丢给数据库解析才会暴露。
 *
 * <p>每条语句独立事务并立即回滚，不会对目标库产生任何持久影响。
 * 刻意<b>不</b>把几十条 DDL 放进同一个大事务：那样整个 dry-run 期间会一直
 * 持有 ACCESS EXCLUSIVE 锁，对生产的冲击反而大于真执行。
 *
 * <pre>
 * mvn test -Dtest=GeneratedSqlDryRunTest \
 *   -Ddoenv.it.url=jdbc:postgresql://host:5432/db -Ddoenv.it.user=u -Ddoenv.it.password=p
 * </pre>
 */
class GeneratedSqlDryRunTest {

    private static final Path BASELINE_DIR =
            Path.of("../../datao-main/src/main/resources/install/pg-sql");

    @Test
    void 生成的SQL必须能被数据库解析() throws Exception {
        String url = System.getProperty("doenv.it.url");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "未设置 -Ddoenv.it.url，跳过");

        DriverManagerDataSource ds = new DriverManagerDataSource(url,
                System.getProperty("doenv.it.user", "postgres"),
                System.getProperty("doenv.it.password", "postgres"));
        ds.setDriverClassName("org.postgresql.Driver");
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        Path f;
        try (Stream<Path> s = Files.list(BASELINE_DIR)) {
            Optional<Path> newest = s
                    .filter(p -> p.getFileName().toString().startsWith("PG系统纯净库-"))
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()));
            f = newest.orElse(null);
        }
        Assumptions.assumeTrue(f != null, "未找到纯净库文件");

        BaselineBundle b = new PgBaselineParser()
                .parse(Files.readString(f), "dryrun", f.getFileName().toString());

        PgLiveSchemaReader reader = new PgLiveSchemaReader(jdbc);
        String schema = reader.currentSchema();
        SchemaModel live = reader.read(schema);

        List<DiffItem> diffs = new ArrayList<>(
                new SchemaDiffEngine(new PgDdlRenderer(schema))
                        .diff(b.getSchema(), live, new DiffOptions()));
        diffs.addAll(new SeedDiffEngine(jdbc, new SeedSqlRenderer(schema),
                SeedManifest.load(new ClassPathResource(SeedManifest.DEFAULT_LOCATION)))
                .diff(b.getSeeds(), live));

        // 按 DiffItem 分组，不要摊平 —— 同一项里 CREATE TABLE 与它的约束/索引/注释
        // 必须在同一个事务里验证，摊平后逐条回滚会让后面的语句找不到表
        List<List<String>> groups = diffs.stream()
                .filter(d -> d.getLevel() == DiffLevel.AUTO || d.getLevel() == DiffLevel.CONFIRM)
                .map(DiffItem::getDdl)
                .filter(x -> !x.isEmpty())
                .toList();
        List<String> statements = groups.stream().flatMap(List::stream).toList();

        Assumptions.assumeTrue(!statements.isEmpty(), "本次比对没有产生可执行语句，跳过");

        List<String> failures = new ArrayList<>();
        int checked = 0;

        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            for (List<String> group : groups) {
                boolean groupFailed = false;
                for (String raw : group) {
                    // CREATE INDEX CONCURRENTLY 不能在事务里跑，
                    // 去掉 CONCURRENTLY 只为验证语法，回滚后不留痕
                    String sql = raw.replace(" CONCURRENTLY ", " ");
                    if (groupFailed) {
                        break;   // 同组前序已失败，后面的必然连带失败，不重复报
                    }
                    try (Statement st = conn.createStatement()) {
                        st.execute(sql);
                        checked++;
                    } catch (SQLException e) {
                        // 42P07 表已存在、42701 列已存在之类属于幂等守卫的正常结果，不算语法问题
                        String state = e.getSQLState();
                        if (!isBenign(state)) {
                            failures.add("[" + state + "] " + e.getMessage()
                                    + "\n    SQL: " + head(sql));
                        }
                        groupFailed = true;
                    }
                }
                conn.rollback();   // 一组验完整体回滚
            }
        }

        System.out.println("已 dry-run 语句数 = " + statements.size() + "，成功执行 " + checked);
        if (!failures.isEmpty()) {
            System.out.println("失败明细：");
            failures.forEach(x -> System.out.println("  " + x));
        }
        assertTrue(failures.isEmpty(), "有 " + failures.size() + " 条生成的 SQL 无法被数据库解析");
    }

    private boolean isBenign(String sqlState) {
        if (sqlState == null) {
            return false;
        }
        return switch (sqlState) {
            case "42P07",  // duplicate_table
                 "42701",  // duplicate_column
                 "42P16",  // invalid_table_definition
                 "23505"   // unique_violation：补行时撞已有唯一约束，属数据问题不是语法问题
                    -> true;
            default -> false;
        };
    }

    private String head(String s) {
        String one = s.replaceAll("\\s+", " ");
        return one.length() > 200 ? one.substring(0, 200) + "…" : one;
    }
}
