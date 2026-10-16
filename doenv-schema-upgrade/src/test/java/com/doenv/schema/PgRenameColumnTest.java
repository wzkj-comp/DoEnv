package com.doenv.schema;

import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffOptions;
import com.doenv.schema.diff.DiffType;
import com.doenv.schema.diff.SchemaDiffEngine;
import com.doenv.schema.model.ColumnModel;
import com.doenv.schema.model.SchemaModel;
import com.doenv.schema.model.TableModel;
import com.doenv.schema.rename.ColumnRenameRule;
import com.doenv.schema.render.GeneratedSqlValidator;
import com.doenv.schema.render.PgDdlRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgRenameColumnTest {

    @Test
    void 清单命中时只生成Rename而不是Add加Drop() {
        SchemaModel baseline = schema(table("vao_demo", "id", "new_name"));
        SchemaModel live = schema(table("vao_demo", "id", "old_name"));
        ColumnRenameRule rule = new ColumnRenameRule("vao_demo", "old_name", "new_name", "字段改名");

        List<DiffItem> diffs = new SchemaDiffEngine(
                new PgDdlRenderer("public"), List.of(rule))
                .diff(baseline, live, new DiffOptions());

        DiffItem rename = diffs.stream()
                .filter(d -> d.getType() == DiffType.COLUMN_RENAME)
                .findFirst().orElseThrow();
        assertEquals("new_name", rename.getObjectName());
        assertTrue(rename.getDdl().get(0).contains("RENAME COLUMN \"old_name\" TO \"new_name\""));
        assertFalse(diffs.stream().anyMatch(d -> d.getType() == DiffType.COLUMN_MISSING));
        assertFalse(diffs.stream().anyMatch(d -> d.getType() == DiffType.COLUMN_DROP));
        assertTrue(GeneratedSqlValidator.validate(diffs, "public").isEmpty());
    }

    @Test
    void 新旧列同时存在时转人工且不删除旧列() {
        SchemaModel baseline = schema(table("vao_demo", "id", "new_name"));
        SchemaModel live = schema(table("vao_demo", "id", "old_name", "new_name"));
        ColumnRenameRule rule = new ColumnRenameRule("vao_demo", "old_name", "new_name", "字段改名");

        List<DiffItem> diffs = new SchemaDiffEngine(
                new PgDdlRenderer("public"), List.of(rule))
                .diff(baseline, live, new DiffOptions());

        assertTrue(diffs.stream().anyMatch(d -> d.getType() == DiffType.COLUMN_RENAME_CONFLICT));
        assertFalse(diffs.stream().anyMatch(d -> d.getType() == DiffType.COLUMN_DROP
                && "old_name".equals(d.getObjectName())));
    }

    @Test
    void 安全校验拒绝与清单来源不一致的Rename() {
        DiffItem forged = DiffItem.of(DiffType.COLUMN_RENAME,
                com.doenv.schema.diff.DiffLevel.CONFIRM, "vao_demo", "new_name");
        forged.setLiveDef("old_name");
        forged.sql(new com.doenv.schema.render.PgDdlRenderer("public")
                .renameColumn("vao_demo", "another_old", "new_name"));

        assertTrue(GeneratedSqlValidator.validate(List.of(forged), "public").stream()
                .anyMatch(e -> e.contains("RENAME COLUMN")));
    }

    static TableModel table(String name, String... columns) {
        TableModel table = new TableModel();
        table.setName(name);
        for (String columnName : columns) {
            ColumnModel column = new ColumnModel();
            column.setName(columnName);
            column.setRawType("text");
            column.setNormType("text");
            table.getColumns().add(column);
        }
        return table;
    }

    static SchemaModel schema(TableModel table) {
        SchemaModel schema = new SchemaModel();
        schema.addTable(table);
        return schema;
    }
}
