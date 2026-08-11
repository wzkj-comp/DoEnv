package com.doenv.schema;

import com.doenv.schema.baseline.BaselineBundle;
import com.doenv.schema.baseline.CkBaselineParser;
import com.doenv.schema.model.SeedTable;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.doenv.schema.config.DataOConfigBridge.withDatabase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 现场部署形态相关的回归测试。
 *
 * <p>覆盖两个真实踩过的坑：
 * <ol>
 *   <li>CK 连接串的库名 —— 连不带库名会落到 default，把整库判成缺失</li>
 *   <li>种子值的 SQL 原始词法 —— {@code now()} 被当字符串重新加引号，
 *       插 DateTime 列直接 CANNOT_PARSE_DATETIME</li>
 * </ol>
 */
class DeployFormTest {

    // ────────── CK 连接串补库名 ──────────

    @Test
    void 地址不带库名时用db补上() {
        assertEquals("jdbc:clickhouse://1.2.3.4:8123/datao_dev",
                withDatabase("jdbc:clickhouse://1.2.3.4:8123", "datao_dev"));
    }

    @Test
    void 地址已带库名时不覆盖() {
        assertEquals("jdbc:clickhouse://1.2.3.4:8123/real_db",
                withDatabase("jdbc:clickhouse://1.2.3.4:8123/real_db", "other_db"));
    }

    @Test
    void 补库名要落在查询串之前() {
        assertEquals("jdbc:clickhouse://1.2.3.4:8123/datao_dev?socket_timeout=60000",
                withDatabase("jdbc:clickhouse://1.2.3.4:8123?socket_timeout=60000", "datao_dev"));
    }

    @Test
    void 没配db就原样返回() {
        assertEquals("jdbc:clickhouse://1.2.3.4:8123",
                withDatabase("jdbc:clickhouse://1.2.3.4:8123", null));
        assertEquals("jdbc:clickhouse://1.2.3.4:8123",
                withDatabase("jdbc:clickhouse://1.2.3.4:8123", "  "));
    }

    // ────────── 种子值保留原始词法 ──────────

    @Test
    void 种子值保留NULL与函数调用的原样() {
        String sql = """
                CREATE TABLE dx_demo
                (
                    `id` String,
                    `Name` String,
                    `CreatorID` Nullable(String),
                    `CreatedTime` DateTime,
                    `isBuiltin` UInt8
                )
                ENGINE = MergeTree
                ORDER BY id;

                INSERT INTO dx_demo (`id`, `Name`, `CreatorID`, `CreatedTime`, `isBuiltin`)
                VALUES ('a1', '复核审核员', NULL, now(), 1);
                """;
        BaselineBundle b = new CkBaselineParser().parse(sql, "t", "t.sql");

        SeedTable seed = b.getSeeds().get("dx_demo");
        assertNotNull(seed, "应解析出 dx_demo 的种子数据");
        assertEquals(1, seed.getRowCount());

        // 反引号后的值：给主键比对用，看的是「值」
        Map<String, String> cooked = seed.getRows().get(0);
        assertEquals("a1", cooked.get("id"));
        assertEquals("复核审核员", cooked.get("Name"));

        // 原始词法：给渲染 INSERT 用，NULL / now() / 裸数字都不能被当成字符串
        Map<String, String> raw = seed.rawRow(0);
        assertEquals("'a1'", raw.get("id"));
        assertEquals("NULL", raw.get("CreatorID"), "SQL NULL 不能变成字符串 'NULL'");
        assertEquals("now()", raw.get("CreatedTime"), "函数调用不能变成字符串 'now()'");
        assertEquals("1", raw.get("isBuiltin"), "裸数字不该被加引号");
    }
}
