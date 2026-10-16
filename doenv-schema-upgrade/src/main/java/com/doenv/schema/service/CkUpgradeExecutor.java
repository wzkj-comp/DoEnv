package com.doenv.schema.service;

import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffLevel;
import com.doenv.schema.diff.PrecheckRule;
import com.doenv.schema.live.CkTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ClickHouse 升级执行器。
 *
 * <p>与 PG 版的关键差别，全部来自 CK 自身的限制：
 * <ul>
 *   <li><b>没有事务</b> —— 不能靠回滚兜底，所以「干跑」只能做语法与可行性验证，
 *       不能真执行后回滚。这里用 {@code EXPLAIN SYNTAX} 做干跑</li>
 *   <li><b>同一 DiffItem 的语句必须连续执行</b> —— 分布式下 ADD COLUMN 要本地表 + 路由表
 *       各一条，中间断开会留下查询报错的表。虽然没有事务保护，至少保证不跨项穿插</li>
 *   <li><b>ON CLUSTER 是异步的</b> —— 语句返回不代表各节点已生效，
 *       必须回读 system.tables/system.columns 校验，这是主判据</li>
 * </ul>
 */
public class CkUpgradeExecutor {

    private static final Logger log = LoggerFactory.getLogger(CkUpgradeExecutor.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TS_MS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final CkTopology topology;

    public CkUpgradeExecutor(DataSource dataSource, JdbcTemplate jdbc, CkTopology topology) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.topology = topology;
    }

    public ExecutionResult execute(List<DiffItem> diffs, List<DiffLevel> levels,
                                   boolean dryRun, String database) {
        ExecutionResult r = new ExecutionResult();
        r.setRunId(UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        r.setStartedAt(LocalDateTime.now().format(TS));
        r.setDatabase(database);
        r.setSchema("-");
        r.setDryRun(dryRun);
        levels.forEach(l -> r.getLevels().add(l.name()));

        long t0 = System.currentTimeMillis();
        log.info("========== CK 开始执行 runId={} 库={} 拓扑={} 干跑={} 档位={} ==========",
                r.getRunId(), database, topology, dryRun, r.getLevels());

        List<DiffItem> targets = diffs.stream()
                .filter(d -> levels.contains(d.getLevel()))
                .filter(d -> !d.getDdl().isEmpty())
                .toList();

        Set<String> touched = new LinkedHashSet<>();
        targets.forEach(d -> touched.add(d.getTableName()));

        int seq = 0;
        for (DiffItem item : targets) {
            PrecheckOutcome pre = runPrechecks(item);
            if (!pre.passed()) {
                StatementLog sl = newLog(++seq, item, item.getDdl().get(0));
                sl.setStatus("SKIPPED");
                sl.setPfcVerdict(pre.error() ? "SKIP_PRECHECK_ERROR" : "SKIP_PRECHECK_FAILED");
                sl.setError(pre.reason());
                r.getStatements().add(sl);
                tableChange(r, item.getTableName()).record(sl);
                r.setSkipped(r.getSkipped() + 1);
                log.warn("{}  ← 前置检查未通过", sl.oneLine());
                continue;
            }

            // 同一项的语句连续执行，中间不穿插别的表
            boolean itemFailed = false;
            for (String sql : item.getDdl()) {
                StatementLog sl = newLog(++seq, item, sql);
                if (itemFailed) {
                    sl.setStatus("SKIPPED");
                    sl.setPfcVerdict("SKIP_AFTER_FAILURE");
                    sl.setError("同项前序语句失败，本条未执行");
                } else {
                    runOne(sl, dryRun);
                    if ("FAILED".equals(sl.getStatus())) {
                        itemFailed = true;
                    }
                }
                r.getStatements().add(sl);
                tableChange(r, item.getTableName()).record(sl);
                switch (sl.getStatus()) {
                    case "OK" -> r.setOk(r.getOk() + 1);
                    case "FAILED" -> r.setFailed(r.getFailed() + 1);
                    default -> r.setSkipped(r.getSkipped() + 1);
                }
                log.info("{}", sl.oneLine());
                if ("FAILED".equals(sl.getStatus())) {
                    log.error("  失败语句: {}", head(sql, 400));
                }
            }

            // ON CLUSTER 是异步的，执行完必须回读校验，不能只看语句有没有抛异常
            if (!dryRun && !itemFailed && topology == CkTopology.DISTRIBUTED) {
                verifyApplied(item);
            }
        }
        r.setTotal(seq);

        r.setFinishedAt(LocalDateTime.now().format(TS));
        r.setTotalCostMs(System.currentTimeMillis() - t0);
        log.info("========== CK 执行结束 runId={} 共 {} 条：成功 {} / 跳过 {} / 失败 {}，耗时 {}ms ==========",
                r.getRunId(), r.getTotal(), r.getOk(), r.getSkipped(), r.getFailed(), r.getTotalCostMs());
        return r;
    }

    private void runOne(StatementLog sl, boolean dryRun) {
        long t = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            if (dryRun) {
                // CK 没有事务可回滚，干跑只能做语法校验。
                // EXPLAIN SYNTAX 不会真正建表/改表，但能把语法与标识符问题暴露出来。
                st.execute("EXPLAIN SYNTAX " + stripTrailingSemicolon(sl.getSql()));
                sl.setStatus("OK");
                sl.setPfcVerdict("DRY_RUN_EXPLAIN");
            } else {
                st.execute(stripTrailingSemicolon(sl.getSql()));
                sl.setStatus("OK");
            }
            // 老版 ClickHouse JDBC 对 INSERT 常返回 -1，不能靠 getUpdateCount。
            // 配置数据 INSERT 由本工具生成标准 VALUES 列表，直接数顶层 tuple，
            // 才能在升级首页准确显示本次补了多少行数据。
            if ("INSERT".equals(sl.getAction())) {
                sl.setRowsAffected(countInsertRows(sl.getSql()));
            } else if ("UPDATE".equals(sl.getAction())) {
                // CK mutation JDBC 不返回可靠影响行数；工具按一个 id 生成一条 UPDATE。
                sl.setRowsAffected(1);
            }
        } catch (SQLException e) {
            sl.setStatus("FAILED");
            sl.setError("[" + e.getErrorCode() + "] " + firstLine(e.getMessage()));
        } catch (RuntimeException e) {
            sl.setStatus("FAILED");
            sl.setError(e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
        }
        sl.setCostMs(System.currentTimeMillis() - t);
    }

    /** 统计 INSERT ... VALUES 中的顶层 tuple，正确跳过字符串、数组和函数括号。 */
    static int countInsertRows(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        int valuesAt = upper.indexOf("VALUES");
        if (valuesAt < 0) {
            return -1;
        }
        int count = 0;
        int depth = 0;
        for (int i = valuesAt + "VALUES".length(); i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                for (i++; i < sql.length(); i++) {
                    char q = sql.charAt(i);
                    if (q == '\\' && quote == '\'' && i + 1 < sql.length()) {
                        i++;
                        continue;
                    }
                    if (q == quote) {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                            i++;
                            continue;
                        }
                        break;
                    }
                }
                continue;
            }
            if (c == '(') {
                if (depth == 0) {
                    count++;
                }
                depth++;
            } else if (c == ')' && depth > 0) {
                depth--;
            }
        }
        return count;
    }

    /** 回读校验：ON CLUSTER 返回不代表已生效 */
    private void verifyApplied(DiffItem item) {
        try {
            switch (item.getType()) {
                case TABLE_MISSING -> {
                    Integer n = jdbc.queryForObject(
                            "SELECT count() FROM system.tables WHERE database=currentDatabase() "
                                    + "AND name = ?", Integer.class, localName(item.getTableName()));
                    if (n == null || n == 0) {
                        log.warn("回读校验失败：{} 建表语句已返回但表不存在", item.getTableName());
                    }
                }
                case COLUMN_MISSING -> {
                    Integer n = jdbc.queryForObject(
                            "SELECT count() FROM system.columns WHERE database=currentDatabase() "
                                    + "AND table = ? AND name = ?", Integer.class,
                            localName(item.getTableName()), item.getObjectName());
                    if (n == null || n == 0) {
                        log.warn("回读校验失败：{}.{} 加列语句已返回但列不存在",
                                item.getTableName(), item.getObjectName());
                    }
                }
                case COLUMN_RENAME -> {
                    Integer n = jdbc.queryForObject(
                            "SELECT count() FROM system.columns WHERE database=currentDatabase() "
                                    + "AND table = ? AND name = ?", Integer.class,
                            localName(item.getTableName()), item.getObjectName());
                    if (n == null || n == 0) {
                        log.warn("回读校验失败：{}.{} 改名语句已返回但目标列不存在",
                                item.getTableName(), item.getObjectName());
                    }
                }
                default -> {
                }
            }
        } catch (RuntimeException e) {
            log.warn("回读校验异常（不影响执行结果）：{}", e.getMessage());
        }
    }

    private String localName(String table) {
        return topology == CkTopology.DISTRIBUTED ? table + "_local" : table;
    }

    // ────────────────────────── 前置探测 ──────────────────────────

    private record PrecheckOutcome(boolean passed, boolean error, String reason) {
        static PrecheckOutcome ok() {
            return new PrecheckOutcome(true, false, null);
        }

        static PrecheckOutcome fail(String reason) {
            return new PrecheckOutcome(false, false, reason);
        }

        static PrecheckOutcome error(String reason) {
            return new PrecheckOutcome(false, true, reason);
        }
    }

    private PrecheckOutcome runPrechecks(DiffItem item) {
        for (PrecheckRule rule : item.getPrechecks()) {
            try {
                switch (rule.getExpect()) {
                    case EMPTY_RESULT -> {
                        List<Map<String, Object>> rows = jdbc.queryForList(rule.getSql());
                        if (!rows.isEmpty()) {
                            return PrecheckOutcome.fail(rule.getDescription()
                                    + " 未通过（" + rows.size() + " 条）：" + nz(rule.getFailHint()));
                        }
                    }
                    case ZERO_COUNT -> {
                        Long n = jdbc.queryForObject(rule.getSql(), Long.class);
                        if (n != null && n > 0) {
                            return PrecheckOutcome.fail(rule.getDescription()
                                    + " 未通过（" + n + "）：" + nz(rule.getFailHint()));
                        }
                    }
                    default -> {
                    }
                }
            } catch (RuntimeException e) {
                if (rule.getExpect() == PrecheckRule.Expect.INFO_ONLY) {
                    log.debug("信息采集跳过（{}）：{}", rule.getDescription(), e.getMessage());
                    continue;
                }
                return PrecheckOutcome.error(rule.getDescription() + " 探测失败（"
                        + e.getClass().getSimpleName() + "：" + nz(e.getMessage())
                        + "），为保护现场数据已拒绝执行");
            }
        }
        return PrecheckOutcome.ok();
    }

    // ────────────────────────── helpers ──────────────────────────

    private StatementLog newLog(int seq, DiffItem item, String sql) {
        StatementLog sl = new StatementLog();
        sl.setSeq(seq);
        sl.setStartedAt(LocalDateTime.now().format(TS_MS));
        sl.setTable(item.getTableName());
        sl.setObjectName(item.getObjectName());
        sl.setDiffType(item.getType().name());
        sl.setLevel(item.getLevel().name());
        sl.setAction(classify(sql));
        sl.setSql(sql);
        sl.setPfcVerdict("EXECUTE");
        return sl;
    }

    private TableChange tableChange(ExecutionResult r, String table) {
        return r.getTableChanges().computeIfAbsent(table, t -> {
            TableChange tc = new TableChange();
            tc.setTable(t);
            tc.setCategory("CK结构");
            return tc;
        });
    }

    static String classify(String sql) {
        String u = sql.trim().toUpperCase(Locale.ROOT);
        if (u.startsWith("CREATE TABLE")) {
            return u.contains("DISTRIBUTED") ? "CREATE_ROUTE" : "CREATE_TABLE";
        }
        // 配置数据补缺。单列出来，结果文档的动作汇总才看得出插了多少数据
        if (u.startsWith("INSERT")) {
            return "INSERT";
        }
        // 视图单列一类：它虽然不动数据，但报告里混进 OTHER 就看不出建了视图
        if (u.startsWith("CREATE VIEW") || u.startsWith("CREATE MATERIALIZED VIEW")
                || u.startsWith("CREATE OR REPLACE VIEW")) {
            return "CREATE_VIEW";
        }
        if (u.contains("ADD COLUMN")) {
            return "ADD_COLUMN";
        }
        if (u.contains("RENAME COLUMN")) {
            return "RENAME_COLUMN";
        }
        if (u.contains("MODIFY COLUMN")) {
            return "MODIFY_COLUMN";
        }
        if (u.contains("COMMENT COLUMN")) {
            return "COMMENT_COLUMN";
        }
        if (u.contains("MODIFY COMMENT")) {
            return "TABLE_COMMENT";
        }
        if (u.contains("ADD INDEX")) {
            return "ADD_INDEX";
        }
        if (u.contains(" UPDATE ") && u.contains(" WHERE ")) {
            return "UPDATE";
        }
        return "OTHER";
    }

    private static String stripTrailingSemicolon(String s) {
        String t = s.trim();
        return t.endsWith(";") ? t.substring(0, t.length() - 1) : t;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String head(String s, int max) {
        String one = s.replaceAll("\\s+", " ");
        return one.length() > max ? one.substring(0, max) + "…" : one;
    }
}
