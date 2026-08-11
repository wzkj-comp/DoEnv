package com.doenv.schema.service;

import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffLevel;
import com.doenv.schema.diff.PrecheckRule;
import com.doenv.schema.render.SqlQuoter;
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
 * 升级执行器。
 *
 * <p>执行策略：
 * <ul>
 *   <li>只执行调用方明确选定的档位（默认 AUTO），<b>MANUAL / INFO 永远不执行</b></li>
 *   <li>每个 DiffItem 一个事务 —— 一条失败只影响该项，不牵连其他表；
 *       刻意不用一个大事务，那样会长时间持有 ACCESS EXCLUSIVE 锁</li>
 *   <li>{@code CREATE INDEX CONCURRENTLY} 必须在事务外单独执行</li>
 *   <li>干跑模式下每条执行完立刻回滚，不留任何痕迹</li>
 * </ul>
 *
 * <p>日志粒度到单条语句：谁、对哪张表、执行了什么、成没成、影响几行、花了多久。
 */
public class UpgradeExecutor {

    private static final Logger log = LoggerFactory.getLogger(UpgradeExecutor.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TS_MS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final String schema;

    public UpgradeExecutor(DataSource dataSource, JdbcTemplate jdbc, String schema) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.schema = schema;
    }

    public ExecutionResult execute(UpgradeReport report, List<DiffLevel> levels, boolean dryRun) {
        ExecutionResult r = new ExecutionResult();
        r.setRunId(UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        r.setReportId(report.getReportId());
        r.setStartedAt(LocalDateTime.now().format(TS));
        r.setDatabase(report.getDatabase());
        r.setSchema(report.getSchema());
        r.setBaselineFile(report.getBaselineFile());
        r.setDryRun(dryRun);
        levels.forEach(l -> r.getLevels().add(l.name()));

        long t0 = System.currentTimeMillis();

        log.info("========== 开始执行 runId={} 目标={}/{} 干跑={} 档位={} ==========",
                r.getRunId(), r.getDatabase(), r.getSchema(), dryRun, r.getLevels());

        // ── 执行前闸门 ──
        if (!report.getBlockers().isEmpty()) {
            report.getBlockers().forEach(b -> r.getAbortedReasons().add("环境阻断：" + b));
        }
        if (!report.getGeneratorErrors().isEmpty()) {
            report.getGeneratorErrors().forEach(e -> r.getAbortedReasons().add("生成物自检未通过：" + e));
        }
        if (r.isAborted()) {
            r.getAbortedReasons().forEach(x -> log.error("执行中止：{}", x));
            r.setFinishedAt(LocalDateTime.now().format(TS));
            r.setTotalCostMs(System.currentTimeMillis() - t0);
            return r;
        }

        List<DiffItem> targets = report.allDiffs().stream()
                .filter(d -> levels.contains(d.getLevel()))
                .filter(d -> !d.getDdl().isEmpty())
                .toList();

        // 变更前的行数快照，用于最终归纳
        Set<String> touchedTables = new LinkedHashSet<>();
        targets.forEach(d -> touchedTables.add(d.getTableName()));
        touchedTables.forEach(t -> {
            TableChange tc = tableChange(r, t, report);
            tc.setRowsBefore(safeCount(t));
        });

        int seq = 0;
        // 整表重建按父表→子表顺序执行，父表失败时子表必须一起停：
        // 父表已把行拉回基准、子表却保留旧行的话，子表的 entitytypeid/recordtypeid
        // 会指向已不存在的父行。这几张表之间没有外键，PG 不会替我们拦住
        boolean rebuildBroken = false;
        for (DiffItem item : targets) {
            if (rebuildBroken && item.getType() == com.doenv.schema.diff.DiffType.SEED_REBUILD) {
                StatementLog sl = newLog(++seq, item,
                        item.getDdl().isEmpty() ? "" : item.getDdl().get(0), false);
                sl.setStatus("SKIPPED");
                sl.setPfcVerdict("SKIP_PARENT_REBUILD_FAILED");
                sl.setError("同一依赖链上前一张表的重建未成功，跳过以免父子数据错位");
                r.getStatements().add(sl);
                tableChange(r, item.getTableName(), report).record(sl);
                r.setSkipped(r.getSkipped() + 1);
                log.warn("{}  ← 前置重建未成功，已跳过", sl.oneLine());
                continue;
            }
            // ── PFC：执行前置检查 ──
            // CONFIRM 档的失败条件是可判定的（重复值、NULL、超长），
            // 先跑探测再决定放不放行，通过就自动执行，不通过就跳过并说明原因。
            PrecheckOutcome pre = runPrechecks(item);
            if (!pre.passed()) {
                StatementLog sl = newLog(++seq, item,
                        item.getDdl().isEmpty() ? "" : item.getDdl().get(0), false);
                sl.setStatus("SKIPPED");
                sl.setPfcVerdict("SKIP_PRECHECK_FAILED");
                sl.setError(pre.reason());
                r.getStatements().add(sl);
                tableChange(r, item.getTableName(), report).record(sl);
                r.setSkipped(r.getSkipped() + 1);
                if (item.getType() == com.doenv.schema.diff.DiffType.SEED_REBUILD) {
                    rebuildBroken = true;
                }
                log.warn("{}  ← 前置检查未通过，已跳过", sl.oneLine());
                continue;
            }
            if (!pre.infos().isEmpty()) {
                log.info("  [{}] 前置检查: {}", item.getTableName(), String.join("; ", pre.infos()));
            }

            List<StatementLog> logs = runItem(seq, item, dryRun);
            seq += logs.size();
            if (item.getType() == com.doenv.schema.diff.DiffType.SEED_REBUILD
                    && logs.stream().anyMatch(x -> !"OK".equals(x.getStatus()))) {
                rebuildBroken = true;
            }
            for (StatementLog sl : logs) {
                r.getStatements().add(sl);
                tableChange(r, item.getTableName(), report).record(sl);
                switch (sl.getStatus()) {
                    case "OK" -> r.setOk(r.getOk() + 1);
                    case "FAILED" -> r.setFailed(r.getFailed() + 1);
                    default -> r.setSkipped(r.getSkipped() + 1);
                }
                log.info("{}", sl.oneLine());
                if ("FAILED".equals(sl.getStatus())) {
                    log.error("  失败语句: {}", head(sl.getSql(), 400));
                }
            }
        }
        r.setTotal(seq);

        // 变更后的行数
        touchedTables.forEach(t -> tableChange(r, t, report).setRowsAfter(safeCount(t)));

        r.setFinishedAt(LocalDateTime.now().format(TS));
        r.setTotalCostMs(System.currentTimeMillis() - t0);

        log.info("========== 执行结束 runId={} 共 {} 条：成功 {} / 跳过 {} / 失败 {}，耗时 {}ms ==========",
                r.getRunId(), r.getTotal(), r.getOk(), r.getSkipped(), r.getFailed(), r.getTotalCostMs());
        return r;
    }

    // ────────────────────────── 单条执行 ──────────────────────────

    /**
     * 执行一个 DiffItem 的全部语句。
     *
     * <p><b>事务边界是 DiffItem，不是单条语句</b> —— 这一点关乎数据安全：
     * {@code FULL_OVERWRITE} 会先 DELETE 再分批 INSERT，若逐条提交，
     * 一旦 INSERT 中途失败，表就停在「已清空、没灌回去」的状态上。
     * 整个 item 一个事务，要么全成要么全回滚。
     *
     * <p>{@code CREATE INDEX CONCURRENTLY} 不能在事务里跑，单独用自动提交执行。
     */
    private List<StatementLog> runItem(int baseSeq, DiffItem item, boolean dryRun) {
        List<String> concurrentStmts = new ArrayList<>();
        List<String> txStmts = new ArrayList<>();
        for (String sql : item.getDdl()) {
            if (sql.toUpperCase(Locale.ROOT).contains("CONCURRENTLY")) {
                if (dryRun) {
                    // 干跑必须把索引语句并进同一事务：同组的 CREATE TABLE 会被回滚，
                    // 若索引另开连接执行，表还不存在，必然报 relation does not exist——
                    // 那是干跑自身造出来的假失败，会把真问题淹掉
                    txStmts.add(sql.replace(" CONCURRENTLY ", " "));
                } else {
                    concurrentStmts.add(sql);
                }
            } else {
                txStmts.add(sql);
            }
        }

        List<StatementLog> logs = new ArrayList<>();
        int seq = baseSeq;

        // ── 事务内的语句：整组同一个事务 ──
        if (!txStmts.isEmpty()) {
            List<StatementLog> group = new ArrayList<>();
            for (String sql : txStmts) {
                group.add(newLog(++seq, item, sql, false));
            }
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                boolean anyFailed = false;
                for (StatementLog sl : group) {
                    if (anyFailed) {
                        sl.setStatus("SKIPPED");
                        sl.setPfcVerdict("SKIP_AFTER_FAILURE");
                        sl.setError("同组前序语句失败，本条未执行");
                        continue;
                    }
                    long t = System.currentTimeMillis();
                    try (Statement st = conn.createStatement()) {
                        boolean hasRs = st.execute(sl.getSql());
                        if (!hasRs) {
                            sl.setRowsAffected(st.getUpdateCount());
                        }
                        sl.setStatus("OK");
                    } catch (SQLException e) {
                        sl.setStatus("FAILED");
                        sl.setError("[" + e.getSQLState() + "] " + firstLine(e.getMessage()));
                        anyFailed = true;
                    }
                    sl.setCostMs(System.currentTimeMillis() - t);
                }

                if (anyFailed) {
                    conn.rollback();
                    // 整组回滚：已成功的那些实际上也没落库，如实标注
                    group.stream().filter(s -> "OK".equals(s.getStatus())).forEach(s -> {
                        s.setStatus("SKIPPED");
                        s.setPfcVerdict("ROLLED_BACK");
                        s.setError("同组有语句失败，整组已回滚");
                    });
                } else if (dryRun) {
                    conn.rollback();
                    group.forEach(s -> s.setPfcVerdict("DRY_RUN_ROLLBACK"));
                } else {
                    conn.commit();
                }
            } catch (SQLException e) {
                group.forEach(s -> {
                    if (s.getStatus() == null) {
                        s.setStatus("FAILED");
                        s.setError("连接失败: " + e.getMessage());
                    }
                });
            }
            logs.addAll(group);
        }

        // ── CONCURRENTLY：只能自动提交、单独执行 ──
        for (String sql : concurrentStmts) {
            StatementLog sl = newLog(++seq, item, sql, true);
            String toRun = sql;
            if (dryRun) {
                // 干跑时去掉 CONCURRENTLY 才能放进事务回滚，只验证语法与可行性
                toRun = sql.replace(" CONCURRENTLY ", " ");
                sl.setOutsideTransaction(false);
                sl.setPfcVerdict("DRY_RUN_ROLLBACK");
            }
            long t = System.currentTimeMillis();
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(!dryRun);
                try (Statement st = conn.createStatement()) {
                    st.execute(toRun);
                    sl.setStatus("OK");
                    if (dryRun) {
                        conn.rollback();
                    }
                } catch (SQLException e) {
                    if (dryRun) {
                        try {
                            conn.rollback();
                        } catch (SQLException ignore) {
                            // 回滚失败不覆盖原始错误
                        }
                    }
                    sl.setStatus("FAILED");
                    sl.setError("[" + e.getSQLState() + "] " + firstLine(e.getMessage()));
                }
            } catch (SQLException e) {
                sl.setStatus("FAILED");
                sl.setError("连接失败: " + e.getMessage());
            }
            sl.setCostMs(System.currentTimeMillis() - t);
            logs.add(sl);
        }
        return logs;
    }

    /** 前置检查结论 */
    private record PrecheckOutcome(boolean passed, String reason, List<String> infos) {
        static PrecheckOutcome ok(List<String> infos) {
            return new PrecheckOutcome(true, null, infos);
        }

        static PrecheckOutcome fail(String reason) {
            return new PrecheckOutcome(false, reason, List.of());
        }
    }

    /**
     * 逐条执行前置探测。任一不通过即整项不放行。
     *
     * <p>探测本身出错（比如表还不存在）不算不通过 —— 那通常是同批里表还没建，
     * 判成失败会误伤，交给后续执行时的真实报错去暴露。
     */
    private PrecheckOutcome runPrechecks(DiffItem item) {
        if (item.getPrechecks().isEmpty()) {
            return PrecheckOutcome.ok(List.of());
        }
        List<String> infos = new ArrayList<>();
        for (PrecheckRule rule : item.getPrechecks()) {
            try {
                switch (rule.getExpect()) {
                    case EMPTY_RESULT -> {
                        List<Map<String, Object>> rows = jdbc.queryForList(rule.getSql());
                        if (!rows.isEmpty()) {
                            return PrecheckOutcome.fail(rule.getDescription()
                                    + " 检查未通过（查出 " + rows.size() + " 组）："
                                    + nz(rule.getFailHint()));
                        }
                        infos.add(rule.getDescription() + "=无");
                    }
                    case ZERO_COUNT -> {
                        Long n = jdbc.queryForObject(rule.getSql(), Long.class);
                        if (n != null && n > 0) {
                            return PrecheckOutcome.fail(rule.getDescription()
                                    + " 检查未通过（" + n + " 行）：" + nz(rule.getFailHint()));
                        }
                        infos.add(rule.getDescription() + "=0");
                    }
                    case AT_MOST -> {
                        Long n = jdbc.queryForObject(rule.getSql(), Long.class);
                        if (n != null && n > rule.getMaxValue()) {
                            return PrecheckOutcome.fail(rule.getDescription()
                                    + " 检查未通过（现为 " + n + "，上限 " + rule.getMaxValue()
                                    + "）：" + nz(rule.getFailHint()));
                        }
                        infos.add(rule.getDescription() + "=" + n);
                    }
                    case INFO_ONLY -> {
                        Object v = jdbc.queryForObject(rule.getSql(), Object.class);
                        infos.add(rule.getDescription() + "=" + v);
                    }
                    default -> {
                    }
                }
            } catch (RuntimeException e) {
                infos.add(rule.getDescription() + "=探测失败(" + e.getClass().getSimpleName() + ")");
            }
        }
        return PrecheckOutcome.ok(infos);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private StatementLog newLog(int seq, DiffItem item, String sql, boolean outsideTx) {
        StatementLog sl = new StatementLog();
        sl.setSeq(seq);
        sl.setStartedAt(LocalDateTime.now().format(TS_MS));
        sl.setTable(item.getTableName());
        sl.setDiffType(item.getType().name());
        sl.setLevel(item.getLevel().name());
        sl.setAction(classify(sql));
        sl.setSql(sql);
        sl.setPfcVerdict("EXECUTE");
        sl.setOutsideTransaction(outsideTx);
        return sl;
    }

    private static String firstLine(String msg) {
        if (msg == null) {
            return "";
        }
        int i = msg.indexOf('\n');
        return i < 0 ? msg : msg.substring(0, i);
    }

    // ────────────────────────── helpers ──────────────────────────

    private TableChange tableChange(ExecutionResult r, String table, UpgradeReport report) {
        return r.getTableChanges().computeIfAbsent(table, t -> {
            TableChange tc = new TableChange();
            tc.setTable(t);
            boolean isSeed = report.getSeedDiffs().stream()
                    .anyMatch(d -> t.equals(d.getTableName()));
            tc.setCategory(isSeed ? "配置数据" : "结构");
            return tc;
        });
    }

    private long safeCount(String table) {
        try {
            Long n = jdbc.queryForObject(
                    "SELECT count(*) FROM " + SqlQuoter.table(schema, table), Long.class);
            return n == null ? -1 : n;
        } catch (RuntimeException e) {
            return -1;   // 表还不存在等情况，不影响执行
        }
    }

    /** 把 SQL 归类成人能读的动作名 */
    static String classify(String sql) {
        String u = sql.trim().toUpperCase(Locale.ROOT);
        if (u.startsWith("CREATE TABLE")) {
            return "CREATE_TABLE";
        }
        if (u.startsWith("ALTER TABLE") && u.contains("ADD COLUMN")) {
            return "ADD_COLUMN";
        }
        if (u.startsWith("ALTER TABLE") && u.contains("ALTER COLUMN") && u.contains("TYPE")) {
            return "ALTER_TYPE";
        }
        if (u.startsWith("ALTER TABLE") && u.contains("DEFAULT")) {
            return "SET_DEFAULT";
        }
        if (u.startsWith("ALTER TABLE")) {
            return "ALTER_TABLE";
        }
        if (u.startsWith("CREATE") && u.contains("INDEX")) {
            return "CREATE_INDEX";
        }
        if (u.startsWith("COMMENT ON")) {
            return "COMMENT";
        }
        if (u.startsWith("INSERT")) {
            return "INSERT";
        }
        if (u.startsWith("UPDATE")) {
            return "UPDATE";
        }
        if (u.startsWith("DELETE")) {
            return "DELETE";
        }
        if (u.startsWith("DO ")) {
            // DO $$ 守卫块，看内部真实动作
            if (u.contains("ADD CONSTRAINT")) {
                return "ADD_CONSTRAINT";
            }
            if (u.contains("DROP INDEX")) {
                return "CLEAN_INVALID_INDEX";
            }
            if (u.contains("ALTER COLUMN")) {
                return "ALTER_TYPE";
            }
            return "DO_BLOCK";
        }
        return "OTHER";
    }

    private static String head(String s, int max) {
        String one = s.replaceAll("\\s+", " ");
        return one.length() > max ? one.substring(0, max) + "…" : one;
    }

    /** 生成人可读的执行报告文本 */
    public static String renderReport(ExecutionResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("════════════════════════════════════════════════════════════════\n");
        sb.append("  DoEnv PG 升级执行报告\n");
        sb.append("════════════════════════════════════════════════════════════════\n");
        sb.append("runId       : ").append(r.getRunId()).append('\n');
        sb.append("目标库      : ").append(r.getDatabase()).append(" / ").append(r.getSchema()).append('\n');
        sb.append("基准        : ").append(r.getBaselineFile()).append('\n');
        sb.append("模式        : ").append(r.isDryRun() ? "干跑（执行后回滚）" : "实际执行").append('\n');
        sb.append("执行档位    : ").append(r.getLevels()).append('\n');
        sb.append("开始/结束   : ").append(r.getStartedAt()).append("  →  ").append(r.getFinishedAt()).append('\n');
        sb.append("总耗时      : ").append(r.getTotalCostMs()).append(" ms\n");
        sb.append("语句统计    : 共 ").append(r.getTotal())
                .append(" 条，成功 ").append(r.getOk())
                .append("，跳过 ").append(r.getSkipped())
                .append("，失败 ").append(r.getFailed()).append('\n');

        if (r.isAborted()) {
            sb.append("\n【执行被中止】\n");
            r.getAbortedReasons().forEach(x -> sb.append("  ✗ ").append(x).append('\n'));
            return sb.toString();
        }

        sb.append("\n───────────────── 一、对哪些表做了什么 ─────────────────\n");
        List<TableChange> changed = new ArrayList<>(r.getTableChanges().values());
        if (changed.isEmpty()) {
            sb.append("  （无变更）\n");
        }
        for (TableChange tc : changed) {
            sb.append("  ").append(tc.summaryLine()).append('\n');
            tc.getDetails().forEach(d -> sb.append("      · ").append(d).append('\n'));
        }

        sb.append("\n───────────────── 二、逐语句执行流水 ─────────────────\n");
        for (StatementLog sl : r.getStatements()) {
            sb.append("  ").append(sl.oneLine()).append('\n');
        }

        List<StatementLog> failures = r.getStatements().stream()
                .filter(s -> "FAILED".equals(s.getStatus())).toList();
        if (!failures.isEmpty()) {
            sb.append("\n───────────────── 三、失败明细 ─────────────────\n");
            for (StatementLog sl : failures) {
                sb.append("  #").append(sl.getSeq()).append("  ").append(sl.getTable()).append('\n');
                sb.append("     错误: ").append(sl.getError()).append('\n');
                sb.append("     SQL : ").append(head(sl.getSql(), 500)).append('\n');
            }
        }
        return sb.toString();
    }
}
