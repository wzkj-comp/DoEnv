package com.doenv.schema.service;

import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffLevel;
import com.doenv.schema.diff.DiffType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class UpgradeResultFilesTest {

    @Test
    void 生成用户指定的四类内容并按对象汇总() {
        ExecutionResult result = new ExecutionResult();
        result.setRound(1);
        result.getStatements().addAll(List.of(
                log("OK", "CREATE_TABLE", "vao_new", null, -1,
                        "CREATE TABLE vao_new(id text)"),
                log("OK", "ADD_COLUMN", "vao_a", "new_col", -1,
                        "ALTER TABLE vao_a ADD COLUMN new_col text"),
                log("OK", "UPDATE", "vao_a", null, 1,
                        "UPDATE vao_a SET name='n', ord='2' WHERE id='1'"),
                log("FAILED", "ALTER_TYPE", "vao_bad", "amount", -1,
                        "ALTER TABLE vao_bad ALTER COLUMN amount TYPE bigint"),
                skipped("vao_wait", "ALTER TABLE vao_wait DROP COLUMN old_col")));

        DiffItem manual = DiffItem.of(DiffType.TYPE_CROSS_FAMILY, DiffLevel.MANUAL,
                "vao_manual", "value");
        manual.sql("ALTER TABLE vao_manual ALTER COLUMN value TYPE bigint");

        UpgradeResultDocument.Section section = new UpgradeResultDocument.Section(
                "PG", "datao", "", "baseline.sql", 1, 2,
                List.of(result), List.of(manual), List.of(), List.of(), List.of(),
                "jdbc:postgresql://10.0.0.8:5432/datao");

        UpgradeResultFiles.Bundle files = UpgradeResultFiles.render(List.of(section));
        assertTrue(files.summary().contains("数据库地址：PG=jdbc:postgresql://10.0.0.8:5432/datao"));
        assertTrue(files.summary().contains("执行成功："));
        assertTrue(files.summary().contains("新建1个表（vao_new）"));
        assertTrue(files.summary().contains("新建1个列（vao_a.new_col）"));
        assertTrue(files.summary().contains("更新2个值（vao_a 2个）"));
        assertTrue(files.summary().contains("执行失败："));
        assertTrue(files.summary().contains("更新1个列的数据类型（vao_bad.amount改成bigint）"));
        assertTrue(files.summary().contains("待审核的sql语句2条"));
        assertTrue(files.successSql().contains("CREATE TABLE vao_new"));
        assertTrue(files.successSql().contains("UPDATE vao_a"));
        assertTrue(files.failedSql().contains("ALTER TABLE vao_bad"));
        assertTrue(files.pendingSql().contains("ALTER TABLE vao_wait"));
        assertTrue(files.pendingSql().contains("ALTER TABLE vao_manual"));
    }

    @Test
    void 没有Id而未生成Sql的表也必须进入待审核文件() {
        DiffItem manual = DiffItem.of(DiffType.SEED_ROW_MISSING, DiffLevel.MANUAL,
                "dx_no_id", null);
        manual.note("后续新数据表没有 id 列，等待人工选择稳定键");
        UpgradeResultDocument.Section section = new UpgradeResultDocument.Section(
                "CK", "datao", "", "baseline.sql", 1, 1,
                List.of(), List.of(manual), List.of(), List.of(), List.of());

        UpgradeResultFiles.Bundle files = UpgradeResultFiles.render(List.of(section));
        assertTrue(files.summary().contains("待审核的sql语句0条"));
        assertTrue(files.summary().contains("无SQL、已跳过的项目1项"));
        assertTrue(files.pendingSql().contains("dx_no_id"));
        assertTrue(files.pendingSql().contains("未生成 SQL：工具已跳过该项"));
    }

    @Test
    void 普通视图定义差异是自动替换而非待审核() {
        DiffItem view = DiffItem.of(DiffType.VIEW_DIFF, DiffLevel.AUTO,
                "vax_trans_view", null);
        view.sql("CREATE OR REPLACE VIEW `vax_trans_view` AS SELECT 1");
        view.note("普通视图定义与基准不同：使用 CREATE OR REPLACE VIEW 对齐");
        UpgradeResultDocument.Section section = new UpgradeResultDocument.Section(
                "CK", "datao", "", "baseline.sql", 1, 1,
                List.of(), List.of(view), List.of(), List.of(), List.of());

        UpgradeResultFiles.Bundle files = UpgradeResultFiles.render(List.of(section));

        assertFalse(files.pendingSql().contains("vax_trans_view"),
                "普通 View 是 AUTO，不得误写入待审核 SQL");
    }

    @Test
    void ClickHouseMutation必须正确统计更新值数() {
        ExecutionResult result = new ExecutionResult();
        result.getStatements().add(log("OK", "UPDATE", "dx_future_conf", null, 1,
                "ALTER TABLE `dx_future_conf` UPDATE `name` = '基准', `enabled` = 1 "
                        + "WHERE `id` = '1' SETTINGS mutations_sync = 2"));
        UpgradeResultDocument.Section section = new UpgradeResultDocument.Section(
                "CK", "datao", "", "baseline.sql", 1, 1,
                List.of(result), List.of(), List.of(), List.of(), List.of());

        UpgradeResultFiles.Bundle files = UpgradeResultFiles.render(List.of(section));

        assertTrue(files.summary().contains("更新2个值（dx_future_conf 2个）"));
    }

    @Test
    void 汇总必须明确写出两个收尾检查() {
        ExecutionResult result = new ExecutionResult();
        StatementLog pg = log("OK", "UPDATE", "vao_user", "systemauths", 2,
                "UPDATE vao_user SET systemauths='0' WHERE systemauths IS NULL");
        pg.setDiffType(DiffType.FINAL_SYSTEMAUTHS_CHECK.name());
        StatementLog ck = log("OK", "OTHER", "vax_item_label", "切换", -1,
                "RENAME TABLE `vax_item_label__doenv_new` TO `vax_item_label`");
        ck.setDiffType(DiffType.FINAL_ITEM_LABEL_ENGINE_MIGRATION.name());
        result.getStatements().addAll(List.of(pg, ck));
        UpgradeResultDocument.Section section = new UpgradeResultDocument.Section(
                "PG", "datao", "", "baseline.sql", 1, 1,
                List.of(result), List.of(), List.of(), List.of(), List.of());

        String summary = UpgradeResultFiles.render(List.of(section)).summary();

        assertTrue(summary.contains("PG vao_user.systemauths 空值检查：已补 0，影响 2 行"));
        assertTrue(summary.contains("CK vax_item_label 表引擎检查：已通过新表、数据复制、行数核验、改名切换完成迁移"));
        assertFalse(summary.contains("新建1个表（vax_item_label）"));
    }

    @Test
    void 启动级异常也要产出四文件内容() {
        UpgradeResultFiles.Bundle files = UpgradeResultFiles.fatal("both",
                new IllegalStateException("连接纯净库失败"));

        assertTrue(files.summary().contains("执行失败"));
        assertTrue(files.summary().contains("连接纯净库失败"));
        assertTrue(files.failedSql().contains("未执行 SQL"));
        assertTrue(files.successSql().contains("共 0 条"));
        assertTrue(files.pendingSql().contains("共 0 条"));
    }

    private static StatementLog log(String status, String action, String table,
                                    String object, int rows, String sql) {
        StatementLog log = new StatementLog();
        log.setStatus(status);
        log.setAction(action);
        log.setTable(table);
        log.setObjectName(object);
        log.setRowsAffected(rows);
        log.setSql(sql);
        log.setError("FAILED".equals(status) ? "test error" : null);
        return log;
    }

    private static StatementLog skipped(String table, String sql) {
        StatementLog log = log("SKIPPED", "DROP_COLUMN", table, "old_col", -1, sql);
        log.setPfcVerdict("SKIP_PRECHECK_FAILED");
        log.setError("需要人工确认");
        return log;
    }
}
