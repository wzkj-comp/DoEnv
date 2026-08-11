package com.doenv.schema;

import com.doenv.schema.render.SeedSqlRenderer;
import com.doenv.schema.seed.SeedManifest;
import com.doenv.schema.seed.SeedStrategy;
import com.doenv.schema.seed.SeedTableRule;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 元数据表整表重建（{@link SeedStrategy#REBUILD}）的回归测试。
 *
 * <p>这四张表（实体/记录类型及其属性）的数据全靠增量 SQL 维护 —— 2026 一年 6798 条
 * 数据操作里 5300+ 条打在它们身上，逐行 UPDATE 既慢又会被不唯一的稳定键卡死。
 * 改成「比对关键列，有差异就整表重建」。
 */
class RebuildStrategyTest {

    private static final List<String> COLS =
            List.of("id", "name", "ord", "tablename", "count");

    private static Map<String, String> row(String id, String name, String ord,
                                           String tablename, String count) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("ord", ord);
        m.put("tablename", tablename);
        m.put("count", count);
        return m;
    }

    // ────────── 渲染 ──────────

    @Test
    void 重建先清空再全量重灌() {
        List<String> sql = new SeedSqlRenderer("public").rebuild(
                "vao_entitytype", COLS,
                List.of(row("a", "账户", "1", "vax_account", "0")),
                Map.of());

        assertEquals(2, sql.size());
        assertTrue(sql.get(0).startsWith("DELETE FROM \"public\".\"vao_entitytype\";"),
                "第一条必须是无条件清空：语义就是整表以基准为准");
        assertTrue(sql.get(1).startsWith("INSERT INTO \"public\".\"vao_entitytype\""));
        assertTrue(sql.get(1).contains("'账户'"));
    }

    @Test
    void 运行时列在重灌之后回填() {
        Map<String, Map<String, String>> preserved = new LinkedHashMap<>();
        preserved.put("a", Map.of("count", "12345"));

        List<String> sql = new SeedSqlRenderer("public").rebuild(
                "vao_entitytype", COLS,
                List.of(row("a", "账户", "1", "vax_account", "0")),
                preserved);

        String last = sql.get(sql.size() - 1);
        assertTrue(last.startsWith("UPDATE"), "回填必须排在 INSERT 之后，否则会被重灌覆盖");
        assertTrue(last.contains("\"count\" = '12345'"));
        assertTrue(last.contains("\"id\" = 'a'"));
    }

    @Test
    void 不生成任何DROP或结构变更() {
        List<String> sql = new SeedSqlRenderer("public").rebuild(
                "vao_recordtype", COLS,
                List.of(row("a", "银行流水", "1", "vax_trans", "0")),
                Map.of());
        assertFalse(sql.stream().anyMatch(s -> s.toUpperCase().contains("DROP ")),
                "重建只动数据，绝不碰表结构");
    }

    // ────────── 配置 ──────────

    @Test
    void 四张元数据表已配置为重建且父表排在子表前() throws Exception {
        SeedManifest m = SeedManifest.loadDefault();
        List<SeedTableRule> ordered = m.inDependencyOrder();

        List<String> targets = List.of("vao_entitytype", "vao_entitytype_property",
                "vao_recordtype", "vao_recordtype_column");
        for (String t : targets) {
            SeedTableRule r = ordered.stream().filter(x -> t.equals(x.getTable()))
                    .findFirst().orElseThrow(() -> new AssertionError("manifest 里没有 " + t));
            assertEquals(SeedStrategy.REBUILD, r.getStrategy(), t + " 应为 REBUILD");
            assertFalse(r.getRebuildCheckColumns().isEmpty(),
                    t + " 必须指定 rebuildCheckColumns，否则退化成比对全部列");
        }

        // 子表重建依赖父表的 id，父表必须先重建
        assertTrue(indexOf(ordered, "vao_entitytype") < indexOf(ordered, "vao_entitytype_property"));
        assertTrue(indexOf(ordered, "vao_recordtype") < indexOf(ordered, "vao_recordtype_column"));
    }

    @Test
    void 带count的表必须声明preserve() throws Exception {
        SeedManifest m = SeedManifest.loadDefault();
        for (String t : List.of("vao_entitytype", "vao_recordtype")) {
            SeedTableRule r = m.inDependencyOrder().stream().filter(x -> t.equals(x.getTable()))
                    .findFirst().orElseThrow();
            assertTrue(r.getPreserve().contains("count"),
                    t + " 的 count 是运行时统计、基准恒为 0，不 preserve 就会被重建清零");
        }
    }

    @Test
    void 基准里的统计值不可信必须能被归零覆盖() {
        // 基准新增的行带着导出环境的 count（实测 2026-08-08 有 5 行非 0，合计 39020），
        // 现场这些表一行数据都没有，直接重灌会让界面凭空显示 7805 条
        Map<String, Map<String, String>> preserved = new LinkedHashMap<>();
        preserved.put("newid", Map.of("count", "0"));

        List<String> sql = new SeedSqlRenderer("public").rebuild(
                "vao_recordtype", COLS,
                List.of(row("newid", "证券持有", "1", "vax_stock_hold", "7805")),
                preserved);

        String insert = sql.get(1);
        String last = sql.get(sql.size() - 1);
        assertTrue(insert.contains("'7805'"), "重灌仍按基准原文，归零靠后面的 UPDATE");
        assertTrue(last.startsWith("UPDATE") && last.contains("\"count\" = '0'"),
                "必须有一条把 count 拉回 0 的 UPDATE，且排在 INSERT 之后");
    }

    @Test
    void 重建必须带执行时刻的并发防护() {
        // 比对(T0)到执行(T1)之间 DataO 主应用还在跑，管理员可能新配了记录类型。
        // T0 算出的「现场独有 N 行」到 T1 已不作数，而 DELETE 不可逆。
        // 判据必须是「不超过 T0 的 N」而不是「等于 0」—— 等于 0 会把正常重建全拦掉
        com.doenv.schema.diff.PrecheckRule r = com.doenv.schema.diff.PrecheckRule.expectAtMost(
                3, "SELECT count(*) FROM t WHERE id NOT IN ('a')", "现场独有行数", "提示");
        assertEquals(com.doenv.schema.diff.PrecheckRule.Expect.AT_MOST, r.getExpect());
        assertEquals(3, r.getMaxValue());
    }

    @Test
    void 探测SQL要能数出基准没有的行() {
        String sql = new SeedSqlRenderer("public")
                .countRowsNotIn("vao_recordtype", "id", List.of("a", "b"));
        assertTrue(sql.contains("NOT IN ('a', 'b')"), sql);
        assertTrue(sql.startsWith("SELECT count(*) FROM \"public\".\"vao_recordtype\""), sql);

        // 基准一行都没有时，整表都算「现场独有」，不能生成 NOT IN () 这种语法错误
        String empty = new SeedSqlRenderer("public")
                .countRowsNotIn("vao_recordtype", "id", List.of());
        assertFalse(empty.contains("NOT IN"), empty);
    }

    private static int indexOf(List<SeedTableRule> rules, String table) {
        for (int i = 0; i < rules.size(); i++) {
            if (table.equals(rules.get(i).getTable())) {
                return i;
            }
        }
        throw new AssertionError("没找到 " + table);
    }
}
