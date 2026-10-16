package com.doenv.schema.service;

import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffLevel;
import com.doenv.schema.diff.DiffType;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 用户侧固定四文件报告：一个结果汇总、成功 SQL、失败 SQL、待审核 SQL。
 */
public final class UpgradeResultFiles {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private UpgradeResultFiles() {
    }

    public record Bundle(String summary, String successSql, String failedSql,
                         String pendingSql, int pendingCount) {
    }

    public static Bundle render(List<UpgradeResultDocument.Section> sections) {
        List<StatementLog> all = sections.stream()
                .flatMap(s -> s.rounds().stream())
                .flatMap(r -> r.getStatements().stream())
                .toList();
        List<StatementLog> ok = all.stream().filter(x -> "OK".equals(x.getStatus())).toList();
        List<StatementLog> failed = all.stream().filter(UpgradeResultFiles::isFailure).toList();

        LinkedHashMap<String, PendingSql> pending = new LinkedHashMap<>();
        all.stream().filter(x -> "SKIPPED".equals(x.getStatus()) && !isFailure(x))
                .filter(x -> hasText(x.getSql()))
                .forEach(x -> pending.putIfAbsent(x.getSql(),
                        new PendingSql(x.getSql(), x.getTable(), x.getError())));
        for (UpgradeResultDocument.Section section : sections) {
            for (DiffItem item : section.remaining()) {
                if (item.getLevel() == DiffLevel.MANUAL && item.getDdl().isEmpty()) {
                    String reason = item.getNotes().isEmpty() ? "未生成 SQL，等待人工选择处理规则"
                            : String.join("；", item.getNotes());
                    String key = "NO_SQL|" + section.dbKind() + "|" + item.getTableName()
                            + "|" + nz(item.getObjectName()) + "|" + reason;
                    pending.putIfAbsent(key, new PendingSql("", item.getTableName(), reason));
                    continue;
                }
                if (!needsReview(item)) {
                    continue;
                }
                for (String sql : item.getDdl()) {
                    if (hasText(sql)) {
                        String reason = item.getNotes().isEmpty() ? item.getLevel().name()
                                : String.join("；", item.getNotes());
                        pending.putIfAbsent(sql, new PendingSql(sql, item.getTableName(), reason));
                    }
                }
            }
        }

        Stats successStats = Stats.from(ok, true);
        Stats failedStats = Stats.from(failed, false);
        List<String> blockers = new ArrayList<>();
        sections.forEach(s -> {
            s.blockers().forEach(x -> blockers.add(s.dbKind() + "：" + x));
            s.generatorErrors().forEach(x -> blockers.add(s.dbKind() + " 生成物自检：" + x));
        });
        int pendingSqlCount = (int) pending.values().stream().filter(x -> hasText(x.sql())).count();
        int pendingChoiceCount = pending.size() - pendingSqlCount;
        String summary = renderSummary(sections, successStats, failedStats,
                pendingSqlCount, pendingChoiceCount, blockers);
        return new Bundle(summary,
                renderSqlFile("执行成功 SQL", ok),
                renderFailedSql(failed, blockers),
                renderPending(pending.values()),
                pending.size());
    }

    /** 比对/连接/基准解析阶段就崩溃时，仍产出固定四文件。 */
    public static Bundle fatal(String dbKind, Throwable error) {
        return fatal(dbKind, "未知", error);
    }

    public static Bundle fatal(String dbKind, String address, Throwable error) {
        String message = error == null ? "未知错误"
                : error.getClass().getSimpleName() + "：" + nz(error.getMessage());
        String now = LocalDateTime.now().format(TS);
        String summary = "DoEnv 数据库升级执行结果汇总\n"
                + "生成时间：" + now + "\n"
                + "数据库地址：" + nz(address) + "\n"
                + "数据库：" + nz(dbKind) + "\n\n"
                + "执行失败：\n    启动/比对阶段异常：" + message + "\n"
                + "    流程未正常收尾，无法仅凭本文件确认是否已有部分 SQL 落库；"
                + "请保留运行日志并重跑比对\n";
        String success = "-- 执行成功 SQL\n-- 生成时间：" + now + "\n-- 共 0 条\n";
        String failed = "-- 执行失败 SQL\n-- 生成时间：" + now + "\n-- 共 0 条\n\n"
                + "-- 未执行 SQL：" + message + "\n";
        String pending = "-- 待审核 SQL\n-- 生成时间：" + now + "\n-- 共 0 条\n";
        return new Bundle(summary, success, failed, pending, 0);
    }

    private static boolean needsReview(DiffItem item) {
        if (item.getDdl().isEmpty()) {
            return false;
        }
        // AUTO / CONFIRM 是工具能够执行的差异，不应因为最终回读仍有差异
        // 就被伪装成「待人工审核」。这类未收敛项会在汇总的“未收敛”中体现；
        // 真正被前置检查拦下的 SQL 已在上方按 SKIPPED 收集到这里。
        // 普通 View 定义差异属于 AUTO，必须直接 CREATE OR REPLACE VIEW，
        // 不能落进待审核文件。
        return item.getLevel() == DiffLevel.MANUAL;
    }

    private static boolean isFailure(StatementLog log) {
        if ("FAILED".equals(log.getStatus())) {
            return true;
        }
        String verdict = nz(log.getPfcVerdict());
        return "ROLLED_BACK".equals(verdict) || "SKIP_AFTER_FAILURE".equals(verdict);
    }

    private static String renderSummary(List<UpgradeResultDocument.Section> sections,
                                        Stats success, Stats failed, int pendingSqlCount,
                                        int pendingChoiceCount,
                                        List<String> blockers) {
        StringBuilder sb = new StringBuilder();
        sb.append("DoEnv 数据库升级执行结果汇总\n");
        sb.append("生成时间：").append(LocalDateTime.now().format(TS)).append('\n');
        sb.append("数据库地址：");
        sb.append(String.join("，", sections.stream()
                .map(s -> s.dbKind() + "=" + (hasText(s.address()) ? s.address() : "未知"))
                .toList()));
        sb.append('\n');
        sb.append("数据库：");
        sb.append(String.join("，", sections.stream()
                .map(s -> s.dbKind() + "/" + s.database()).toList()));
        sb.append("\n\n");
        appendStats(sb, "执行成功", success);
        sb.append('\n');
        appendStats(sb, "执行失败", failed);
        appendFinalChecks(sb, sections, allLogs(sections));
        if (!blockers.isEmpty()) {
            sb.append("    阻断原因：\n");
            blockers.forEach(x -> sb.append("        ").append(x).append('\n'));
        }
        sb.append("\n待审核的sql语句").append(pendingSqlCount)
                .append("条（具体看待审核sql.txt）\n");
        if (pendingChoiceCount > 0) {
            sb.append("无SQL、已跳过的项目").append(pendingChoiceCount)
                    .append("项（具体看待审核sql.txt）\n");
        }
        return sb.toString();
    }

    private static List<StatementLog> allLogs(List<UpgradeResultDocument.Section> sections) {
        return sections.stream().flatMap(s -> s.rounds().stream())
                .flatMap(r -> r.getStatements().stream()).toList();
    }

    private static void appendFinalChecks(StringBuilder sb, List<UpgradeResultDocument.Section> sections,
                                          List<StatementLog> logs) {
        sb.append("    收尾检查：\n");
        appendSystemauthsCheck(sb, sections, logs);
        appendItemLabelCheck(sb, sections, logs);
    }

    private static void appendSystemauthsCheck(StringBuilder sb, List<UpgradeResultDocument.Section> sections,
                                               List<StatementLog> logs) {
        List<StatementLog> rows = logs.stream()
                .filter(x -> DiffType.FINAL_SYSTEMAUTHS_CHECK.name().equals(x.getDiffType())).toList();
        long repaired = rows.stream().filter(x -> "OK".equals(x.getStatus()))
                .mapToLong(x -> Math.max(0, x.getRowsAffected())).sum();
        boolean failed = rows.stream().anyMatch(UpgradeResultFiles::isFailure);
        String note = finalNote(sections, DiffType.FINAL_SYSTEMAUTHS_CHECK);
        if (failed) {
            sb.append("        PG vao_user.systemauths 空值检查：修复失败（详见 sql-failed.txt）\n");
        } else if (repaired > 0) {
            sb.append("        PG vao_user.systemauths 空值检查：已补 0，影响 ").append(repaired).append(" 行\n");
        } else {
            sb.append("        PG vao_user.systemauths 空值检查：")
                    .append(note.isEmpty() ? "未发现空值" : note).append('\n');
        }
    }

    private static void appendItemLabelCheck(StringBuilder sb, List<UpgradeResultDocument.Section> sections,
                                             List<StatementLog> logs) {
        List<StatementLog> rows = logs.stream()
                .filter(x -> DiffType.FINAL_ITEM_LABEL_ENGINE_MIGRATION.name().equals(x.getDiffType())).toList();
        boolean failed = rows.stream().anyMatch(UpgradeResultFiles::isFailure);
        boolean completed = !failed && rows.stream().anyMatch(x -> "OK".equals(x.getStatus())
                && x.getSql() != null && (x.getSql().contains("RENAME TABLE `vax_item_label__doenv_new` "
                + "TO `vax_item_label`") || x.getSql().contains("RENAME TABLE `vax_item_label__doenv_new_local` "
                + "TO `vax_item_label_local`")));
        String note = finalNote(sections, DiffType.FINAL_ITEM_LABEL_ENGINE_MIGRATION);
        if (failed) {
            sb.append("        CK vax_item_label 表引擎检查：迁移失败（详见 sql-failed.txt）\n");
        } else if (completed) {
            sb.append("        CK vax_item_label 表引擎检查：已通过新表、数据复制、行数核验、改名切换完成迁移\n");
        } else {
            sb.append("        CK vax_item_label 表引擎检查：")
                    .append(note.isEmpty() ? "未发现引擎差异" : note).append('\n');
        }
    }

    private static String finalNote(List<UpgradeResultDocument.Section> sections, DiffType type) {
        return sections.stream().flatMap(s -> s.remaining().stream())
                .filter(d -> d.getType() == type)
                .flatMap(d -> d.getNotes().stream()).findFirst().orElse("");
    }

    private static void appendStats(StringBuilder sb, String title, Stats s) {
        sb.append(title).append("：\n");
        sb.append("    表：\n");
        line(sb, 8, "新建", "个表", s.tables);
        sb.append("    列：\n");
        line(sb, 8, "新建", "个列", s.addedColumns);
        line(sb, 8, "删除", "个列", s.droppedColumns);
        line(sb, 8, "更新", "个列描述", s.comments);
        line(sb, 8, "更新", "个表描述", s.tableComments);
        line(sb, 8, "更新", "个列名称", s.renamedColumns);
        line(sb, 8, "新建", "个列索引", s.addedIndexes);
        line(sb, 8, "删除", "个列索引", s.droppedIndexes);
        line(sb, 8, "更新", "个约束", s.constraints);
        line(sb, 8, "更新", "个列的数据类型", s.types);
        sb.append("    数据更新：\n");
        dataLine(sb, "新增", "条数据", s.insertedRows, s.insertedByTable);
        dataLine(sb, "删除", "条数据", s.deletedRows, s.deletedByTable);
        dataLine(sb, "更新", "个值", s.updatedValues, s.updatedByTable);
    }

    private static void line(StringBuilder sb, int indent, String verb, String unit,
                             Set<String> details) {
        sb.append(" ".repeat(indent)).append(verb).append(details.size()).append(unit)
                .append('（').append(details.isEmpty() ? "无" : String.join("，", details))
                .append("）\n");
    }

    private static void dataLine(StringBuilder sb, String verb, String unit, long total,
                                 Map<String, Long> byTable) {
        List<String> details = new ArrayList<>();
        byTable.forEach((table, count) -> details.add(table + " " + count + (unit.contains("值") ? "个" : "条")));
        sb.append("        ").append(verb).append(total).append(unit).append('（')
                .append(details.isEmpty() ? "无" : String.join("，", details)).append("）\n");
    }

    private static String renderSqlFile(String title, List<StatementLog> logs) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- ").append(title).append('\n');
        sb.append("-- 生成时间：").append(LocalDateTime.now().format(TS)).append('\n');
        sb.append("-- 共 ").append(logs.size()).append(" 条\n");
        for (int i = 0; i < logs.size(); i++) {
            StatementLog log = logs.get(i);
            sb.append("\n-- #").append(i + 1).append(" [").append(nz(log.getStatus()))
                    .append("] ").append(nz(log.getAction())).append(' ')
                    .append(nz(log.getTable()));
            if (log.getRowsAffected() >= 0) {
                sb.append(" 影响").append(log.getRowsAffected()).append("行");
            }
            sb.append('\n');
            if (hasText(log.getError())) {
                sb.append("-- 失败原因：").append(log.getError()).append('\n');
            }
            sb.append(nz(log.getSql()).trim()).append('\n');
        }
        return sb.toString();
    }

    private static String renderFailedSql(List<StatementLog> logs, List<String> blockers) {
        String sql = renderSqlFile("执行失败 SQL", logs);
        if (blockers.isEmpty()) {
            return sql;
        }
        StringBuilder sb = new StringBuilder(sql);
        sb.append("\n-- 执行阻断原因（未产生可执行 SQL）\n");
        blockers.forEach(x -> sb.append("-- ").append(x).append('\n'));
        return sb.toString();
    }

    private static String renderPending(java.util.Collection<PendingSql> pending) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- 待审核 SQL\n");
        sb.append("-- 生成时间：").append(LocalDateTime.now().format(TS)).append('\n');
        sb.append("-- 共 ").append(pending.size()).append(" 条\n");
        int i = 0;
        for (PendingSql item : pending) {
            sb.append("\n-- #").append(++i).append(' ').append(nz(item.table())).append('\n');
            if (hasText(item.reason())) {
                sb.append("-- 待审核原因：").append(item.reason()).append('\n');
            }
            if (hasText(item.sql())) {
                sb.append(item.sql().trim()).append('\n');
            } else {
                sb.append("-- 未生成 SQL：工具已跳过该项。\n");
            }
        }
        return sb.toString();
    }

    private record PendingSql(String sql, String table, String reason) {
    }

    private static final class Stats {
        private final Set<String> tables = new LinkedHashSet<>();
        private final Set<String> addedColumns = new LinkedHashSet<>();
        private final Set<String> droppedColumns = new LinkedHashSet<>();
        private final Set<String> comments = new LinkedHashSet<>();
        private final Set<String> tableComments = new LinkedHashSet<>();
        private final Set<String> renamedColumns = new LinkedHashSet<>();
        private final Set<String> addedIndexes = new LinkedHashSet<>();
        private final Set<String> droppedIndexes = new LinkedHashSet<>();
        private final Set<String> constraints = new LinkedHashSet<>();
        private final Set<String> types = new LinkedHashSet<>();
        private final Map<String, Long> insertedByTable = new LinkedHashMap<>();
        private final Map<String, Long> deletedByTable = new LinkedHashMap<>();
        private final Map<String, Long> updatedByTable = new LinkedHashMap<>();
        private long insertedRows;
        private long deletedRows;
        private long updatedValues;

        private static Stats from(List<StatementLog> logs, boolean actual) {
            Stats stats = new Stats();
            for (StatementLog log : logs) {
                // vax_item_label 收尾迁移的 CREATE/INSERT 是同一批数据的临时搬运，
                // 不能误报为“新增表/新增数据”。它会在“收尾检查”区单独说明。
                if (DiffType.FINAL_ITEM_LABEL_ENGINE_MIGRATION.name().equals(log.getDiffType())) {
                    continue;
                }
                String table = nz(log.getTable());
                String object = nz(log.getObjectName());
                String qualified = object.isEmpty() ? table : table + "." + object;
                switch (nz(log.getAction())) {
                    case "CREATE_TABLE", "CREATE_ROUTE" -> {
                        stats.tables.add(table);
                        inlineIndexes(log.getSql()).forEach(index ->
                                stats.addedIndexes.add(table + "." + index));
                    }
                    case "ADD_COLUMN" -> stats.addedColumns.add(qualified);
                    case "DROP_COLUMN" -> stats.droppedColumns.add(qualified);
                    case "RENAME_COLUMN" -> stats.renamedColumns.add(renameDetail(log));
                    case "COMMENT", "COMMENT_COLUMN" -> stats.comments.add(commentDetail(log));
                    case "TABLE_COMMENT" -> stats.tableComments.add(commentDetail(log));
                    case "CREATE_INDEX", "ADD_INDEX" -> stats.addedIndexes.add(qualified);
                    case "DROP_INDEX", "CLEAN_INVALID_INDEX" -> stats.droppedIndexes.add(qualified);
                    case "ADD_CONSTRAINT", "ALTER_TABLE", "SET_DEFAULT" -> stats.constraints.add(qualified);
                    case "ALTER_TYPE", "MODIFY_COLUMN" -> stats.types.add(typeDetail(log));
                    case "INSERT" -> {
                        long n = dmlRows(log, actual, true);
                        stats.insertedRows += n;
                        stats.insertedByTable.merge(table, n, Long::sum);
                    }
                    case "DELETE" -> {
                        long n = dmlRows(log, actual, false);
                        stats.deletedRows += n;
                        stats.deletedByTable.merge(table, n, Long::sum);
                    }
                    case "UPDATE" -> {
                        long rows = actual ? Math.max(0, log.getRowsAffected()) : 1;
                        long n = (long) countUpdateAssignments(log.getSql()) * rows;
                        stats.updatedValues += n;
                        stats.updatedByTable.merge(table, n, Long::sum);
                    }
                    default -> {
                        if (nz(log.getDiffType()).contains("CONSTRAINT")
                                || nz(log.getDiffType()).contains("NULLABLE")
                                || nz(log.getDiffType()).contains("DEFAULT")) {
                            stats.constraints.add(qualified);
                        }
                    }
                }
            }
            return stats;
        }
    }

    private static long dmlRows(StatementLog log, boolean actual, boolean insert) {
        if (actual) {
            return Math.max(0, log.getRowsAffected());
        }
        if (!insert) {
            return 1;
        }
        int count = CkUpgradeExecutor.countInsertRows(nz(log.getSql()));
        return count > 0 ? count : 1;
    }

    static int countUpdateAssignments(String sql) {
        if (sql == null) {
            return 0;
        }
        String upper = sql.toUpperCase(Locale.ROOT);
        int set = upper.indexOf(" SET ");
        int bodyStart = set < 0 ? upper.indexOf(" UPDATE ") : set;
        int where = upper.lastIndexOf(" WHERE ");
        if (bodyStart < 0 || where <= bodyStart) {
            return 0;
        }
        String body = sql.substring(bodyStart + (set < 0 ? 8 : 5), where);
        int count = 1;
        boolean single = false;
        boolean quoted = false;
        int paren = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (single) {
                if (c == '\'' && i + 1 < body.length() && body.charAt(i + 1) == '\'') {
                    i++;
                } else if (c == '\'') {
                    single = false;
                }
                continue;
            }
            if (quoted) {
                if (c == '"' && i + 1 < body.length() && body.charAt(i + 1) == '"') {
                    i++;
                } else if (c == '"') {
                    quoted = false;
                }
                continue;
            }
            if (c == '\'') single = true;
            else if (c == '"') quoted = true;
            else if (c == '(') paren++;
            else if (c == ')') paren--;
            else if (c == ',' && paren == 0) count++;
        }
        return count;
    }

    private static String renameDetail(StatementLog log) {
        String sql = nz(log.getSql()).replaceAll("\\s+", " ");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)RENAME\\s+COLUMN\\s+[\"`]?([^\"` ]+)[\"`]?\\s+TO\\s+[\"`]?([^\"` ;]+)")
                .matcher(sql);
        return matcher.find() ? nz(log.getTable()) + "." + matcher.group(1) + "改成" + matcher.group(2)
                : nz(log.getTable()) + "." + nz(log.getObjectName());
    }

    private static String commentDetail(StatementLog log) {
        String sql = nz(log.getSql()).replaceAll("\\s+", " ").trim();
        java.util.regex.Matcher pg = java.util.regex.Pattern
                .compile("(?i)\\sIS\\s+'((?:''|[^'])*)'").matcher(sql);
        java.util.regex.Matcher ck = java.util.regex.Pattern
                .compile("(?i)\\sCOMMENT\\s+'((?:''|[^'])*)'").matcher(sql);
        String value = pg.find() ? pg.group(1).replace("''", "'")
                : ck.find() ? ck.group(1).replace("''", "'") : "";
        String name = nz(log.getObjectName()).isEmpty() ? nz(log.getTable())
                : nz(log.getTable()) + "." + log.getObjectName();
        return value.isEmpty() ? name : name + "改成“" + value + "”";
    }

    private static String typeDetail(StatementLog log) {
        String sql = nz(log.getSql()).replaceAll("\\s+", " ").trim();
        java.util.regex.Matcher pg = java.util.regex.Pattern
                .compile("(?i)\\sTYPE\\s+(.+?)(?:\\s+USING|;|$)").matcher(sql);
        String type = pg.find() ? pg.group(1).trim() : "";
        String name = nz(log.getObjectName()).isEmpty() ? nz(log.getTable())
                : nz(log.getTable()) + "." + log.getObjectName();
        return type.isEmpty() ? name : name + "改成" + type;
    }

    private static List<String> inlineIndexes(String sql) {
        List<String> out = new ArrayList<>();
        if (sql == null) {
            return out;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?im)^\\s*INDEX\\s+[\"`]?([^\"` ]+)").matcher(sql);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return out;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
