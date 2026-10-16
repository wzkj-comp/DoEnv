package com.doenv.schema;

import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.model.ColumnModel;
import com.doenv.schema.model.SchemaModel;
import com.doenv.schema.model.SeedTable;
import com.doenv.schema.model.TableModel;
import com.doenv.schema.render.SeedSqlRenderer;
import com.doenv.schema.seed.SeedDiffEngine;
import com.doenv.schema.seed.SeedManifest;
import com.doenv.schema.seed.SeedStrategy;
import com.doenv.schema.seed.SeedTableRule;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SeedFullOverwriteTest {

    @Test
    void 系统模板只删除并重灌level3() {
        SeedTable seed = new SeedTable();
        seed.setTableName("vao_recordtype_import_template");
        seed.setColumns(List.of("id", "name", "level"));
        seed.addRow(List.of("system", "系统模板", "3"));
        seed.addRow(List.of("personal", "个人模板", "1"));

        SeedTableRule rule = new SeedTableRule();
        rule.setTable(seed.getTableName());
        rule.setStrategy(SeedStrategy.FULL_OVERWRITE);
        rule.setKey(List.of("id"));
        rule.setBaselineFilter(Map.of("level", "3"));
        rule.setDeletePredicate("\"level\" = 3");

        SeedManifest manifest = new SeedManifest();
        manifest.setTables(List.of(rule));

        TableModel table = new TableModel();
        table.setName(seed.getTableName());
        for (String name : seed.getColumns()) {
            ColumnModel column = new ColumnModel();
            column.setName(name);
            table.addColumn(column);
        }
        SchemaModel live = new SchemaModel();
        live.addTable(table);

        List<DiffItem> diffs = new SeedDiffEngine(mock(JdbcTemplate.class),
                new SeedSqlRenderer("public"), manifest)
                .diff(Map.of(seed.getTableName(), seed), live);

        List<String> sql = diffs.stream().flatMap(d -> d.getDdl().stream()).toList();
        assertEquals(2, sql.size());
        assertTrue(sql.get(0).contains("WHERE \"level\" = 3"));
        assertTrue(sql.get(1).contains("'system', '系统模板', '3'"));
        assertFalse(sql.get(1).contains("personal"));
        assertFalse(sql.get(1).contains("个人模板"));
    }
}
