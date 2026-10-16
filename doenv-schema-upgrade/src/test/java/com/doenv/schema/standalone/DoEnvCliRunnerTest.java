package com.doenv.schema.standalone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DoEnvCliRunnerTest {

    @Test
    void 报告地址必须去掉查询参数并遮蔽密码() {
        assertEquals("jdbc:postgresql://db.example:5432/datao",
                DoEnvCliRunner.safeAddress(
                        "jdbc:postgresql://db.example:5432/datao?user=datao&password=secret"));
        assertEquals("jdbc:clickhouse://datao:***@ck.example:8123/datao",
                DoEnvCliRunner.safeAddress(
                        "jdbc:clickhouse://datao:secret@ck.example:8123/datao"));
    }
}
