package com.doenv.schema.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpgradeResultDocumentTest {

    @Test
    void 首页优先汇总结构对象和数据行数() {
        ExecutionResult pg = result(
                log("CREATE_TABLE", "vao_new", null, -1,
                        "CREATE TABLE vao_new (id text)"),
                log("ADD_COLUMN", "vao_old", "new_col", -1,
                        "ALTER TABLE vao_old ADD COLUMN new_col text"),
                log("DROP_COLUMN", "vao_old", "legacy_col", -1,
                        "ALTER TABLE vao_old DROP COLUMN legacy_col"),
                log("RENAME_COLUMN", "vao_old", "new_name", -1,
                        "ALTER TABLE vao_old RENAME COLUMN old_name TO new_name"),
                log("CREATE_INDEX", "vao_old", "idx_vao_old", -1,
                        "CREATE INDEX idx_vao_old ON vao_old(new_col)"),
                log("INSERT", "vao_conf", null, 5, "INSERT INTO vao_conf VALUES (...)"),
                log("UPDATE", "vao_conf", null, 3, "UPDATE vao_conf SET x=1"),
                log("DELETE", "vao_conf", null, 2, "DELETE FROM vao_conf WHERE x=0"));

        ExecutionResult ck = result(
                log("CREATE_TABLE", "vax_new", null, -1, """
                        CREATE TABLE vax_new (
                          id String,
                          INDEX idx_a id TYPE minmax GRANULARITY 1,
                          INDEX idx_b id TYPE set(1000) GRANULARITY 4
                        ) ENGINE = MergeTree ORDER BY id
                        """),
                // 分布式 local + route 是两条 SQL，但属于同一个逻辑列，只能计 1 列。
                log("ADD_COLUMN", "vax_old", "new_col", -1, "ALTER TABLE vax_old_local ADD COLUMN new_col String"),
                log("ADD_COLUMN", "vax_old", "new_col", -1, "ALTER TABLE vax_old ADD COLUMN new_col String"),
                log("ADD_INDEX", "vax_old", "idx_new", -1, "ALTER TABLE vax_old ADD INDEX idx_new id TYPE minmax"),
                log("INSERT", "dx_conf", null, 7, "INSERT INTO dx_conf VALUES (...)"));

        String doc = UpgradeResultDocument.render(List.of(
                section("PG", pg), section("CK", ck)), "sql.sql");
        String oneLine = doc.replaceAll("\\s+", " ");

        assertTrue(oneLine.contains("PG 1 1 1 1 1 5行 3行 2行"), doc);
        assertTrue(oneLine.contains("CK 1 1 0 0 3 7行 0行 0行"), doc);
        assertTrue(oneLine.contains("合计 2 2 1 1 4 12行 3行 2行"), doc);
        assertTrue(doc.indexOf("核心升级结果") < doc.indexOf("执行明细统计"),
                "核心变更数量必须排在 SQL 条数之前");
    }

    @Test
    void clickHouse批量Insert能准确统计顶层行数() {
        String sql = """
                INSERT INTO dx_conf (id, conf) VALUES
                  ('1', '{"x":"(a,b)"}'),
                  ('2', array(1, 2)),
                  ('3', 'it\\'s ok');
                """;
        assertEquals(3, CkUpgradeExecutor.countInsertRows(sql));
    }

    private static UpgradeResultDocument.Section section(String db, ExecutionResult result) {
        return new UpgradeResultDocument.Section(db, "datao", "", "baseline.sql",
                1, 2, List.of(result), List.of(), List.of(), List.of(), List.of());
    }

    private static ExecutionResult result(StatementLog... logs) {
        ExecutionResult result = new ExecutionResult();
        result.setRound(1);
        result.getStatements().addAll(List.of(logs));
        result.setTotal(logs.length);
        result.setOk(logs.length);
        return result;
    }

    private static StatementLog log(String action, String table, String object,
                                    int rows, String sql) {
        StatementLog log = new StatementLog();
        log.setStatus("OK");
        log.setAction(action);
        log.setTable(table);
        log.setObjectName(object);
        log.setRowsAffected(rows);
        log.setSql(sql);
        return log;
    }
}
