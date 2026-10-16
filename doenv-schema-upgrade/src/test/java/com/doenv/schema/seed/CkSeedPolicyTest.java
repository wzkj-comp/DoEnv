package com.doenv.schema.seed;

import com.doenv.schema.diff.DiffLevel;
import com.doenv.schema.model.SchemaModel;
import com.doenv.schema.model.SeedTable;
import com.doenv.schema.model.TableModel;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class CkSeedPolicyTest {

    @Test
    void 既有七表保持只补未来新表默认按Id更新() throws Exception {
        SeedManifest manifest = SeedManifest.load(
                new ClassPathResource("doenv/ck-seed-manifest.json"));
        assertEquals(SeedStrategy.INSERT_ONLY,
                manifest.ruleFor("dx_clean_keyword_conf").getStrategy());

        SeedTableRule future = manifest.ruleFor("dx_future_conf");
        assertEquals(SeedStrategy.MERGE_FREEZE, future.getStrategy());
        assertEquals(List.of("id"), future.getKey());
        assertEquals(List.of("creatorid", "createdtime", "lastupdatorid", "lastupdatedtime"),
                future.getFreeze());
    }

    @Test
    void 新表没有Id时不猜排序键直接待审核() throws Exception {
        SeedTable seed = new SeedTable();
        seed.setTableName("dx_no_id");
        seed.setColumns(List.of("code", "name"));
        seed.addRow(List.of("A", "名称"), List.of("'A'", "'名称'"));

        TableModel table = new TableModel();
        table.setName("dx_no_id");
        SchemaModel live = new SchemaModel();
        live.addTable(table);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SeedManifest manifest = SeedManifest.load(
                new ClassPathResource("doenv/ck-seed-manifest.json"));

        var diffs = new CkSeedDiffEngine(jdbc, false, null, manifest)
                .diff(Map.of("dx_no_id", seed), live);

        assertEquals(1, diffs.size());
        assertEquals(DiffLevel.MANUAL, diffs.get(0).getLevel());
        assertTrue(diffs.get(0).getNotes().get(0).contains("没有 id 列"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void 差异更新跳过四个审计字段且分布式只改本地表() throws Exception {
        SeedTable seed = new SeedTable();
        seed.setTableName("dx_future_conf");
        seed.setColumns(List.of("id", "Name", "CreatorID", "CreatedTime",
                "LastUpdatorID", "LastUpdatedTime", "Enabled"));
        seed.addRow(
                List.of("1", "基准名称", "base-c", "2026-01-01 00:00:00",
                        "base-u", "2026-01-02 00:00:00", "1"),
                List.of("'1'", "'基准名称'", "'base-c'", "'2026-01-01 00:00:00'",
                        "'base-u'", "'2026-01-02 00:00:00'", "1"));

        Map<String, String> live = new LinkedHashMap<>();
        live.put("id", "1");
        live.put("Name", "现场名称");
        live.put("CreatorID", "site-c");
        live.put("CreatedTime", "2020-01-01 00:00:00");
        live.put("LastUpdatorID", "site-u");
        live.put("LastUpdatedTime", "2020-01-02 00:00:00");
        live.put("Enabled", "0");

        SeedManifest manifest = SeedManifest.load(
                new ClassPathResource("doenv/ck-seed-manifest.json"));
        SeedTableRule rule = manifest.ruleFor("dx_future_conf");
        List<String> changed = CkSeedDiffEngine.changedColumns(seed, 0, live, rule, "id");
        assertEquals(List.of("Name", "Enabled"), changed);

        CkSeedDiffEngine engine = new CkSeedDiffEngine(
                mock(JdbcTemplate.class), true, "'cluster_a'", manifest);
        String sql = engine.renderUpdate("dx_future_conf", seed, 0, changed, "id");
        assertTrue(sql.startsWith("ALTER TABLE `dx_future_conf_local` ON CLUSTER 'cluster_a' UPDATE"), sql);
        assertTrue(sql.contains("`Name` = '基准名称'"), sql);
        assertTrue(sql.contains("`Enabled` = 1"), sql);
        assertTrue(sql.contains("WHERE `id` = '1' SETTINGS mutations_sync = 2"), sql);
        assertFalse(sql.toLowerCase().contains("creatorid"), sql);
        assertFalse(sql.toLowerCase().contains("createdtime"), sql);
    }

    @Test
    void 基准Id重复时必须零Sql转待审核() throws Exception {
        SeedTable seed = new SeedTable();
        seed.setTableName("dx_future_conf");
        seed.setColumns(List.of("id", "name"));
        seed.addRow(List.of("1", "A"), List.of("'1'", "'A'"));
        seed.addRow(List.of("1", "B"), List.of("'1'", "'B'"));

        TableModel table = new TableModel();
        table.setName("dx_future_conf");
        SchemaModel live = new SchemaModel();
        live.addTable(table);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SeedManifest manifest = SeedManifest.load(
                new ClassPathResource("doenv/ck-seed-manifest.json"));

        var diffs = new CkSeedDiffEngine(jdbc, false, null, manifest)
                .diff(Map.of("dx_future_conf", seed), live);

        assertEquals(1, diffs.size());
        assertEquals(DiffLevel.MANUAL, diffs.get(0).getLevel());
        assertTrue(diffs.get(0).getDdl().isEmpty());
        assertTrue(diffs.get(0).getNotes().get(0).contains("基准 id 存在重复"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void 现场Id重复时必须零Sql转待审核() throws Exception {
        SeedTable seed = new SeedTable();
        seed.setTableName("dx_future_conf");
        seed.setColumns(List.of("id", "name"));
        seed.addRow(List.of("1", "A"), List.of("'1'", "'A'"));

        TableModel table = new TableModel();
        table.setName("dx_future_conf");
        SchemaModel live = new SchemaModel();
        live.addTable(table);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of("1"));
        SeedManifest manifest = SeedManifest.load(
                new ClassPathResource("doenv/ck-seed-manifest.json"));

        var diffs = new CkSeedDiffEngine(jdbc, false, null, manifest)
                .diff(Map.of("dx_future_conf", seed), live);

        assertEquals(1, diffs.size());
        assertEquals(DiffLevel.MANUAL, diffs.get(0).getLevel());
        assertTrue(diffs.get(0).getDdl().isEmpty());
        assertTrue(diffs.get(0).getNotes().get(0).contains("现场 id 存在重复"));
    }
}
