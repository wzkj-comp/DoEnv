package com.doenv.schema;

import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffLevel;
import com.doenv.schema.diff.DiffOptions;
import com.doenv.schema.diff.DiffType;
import com.doenv.schema.diff.SchemaDiffEngine;
import com.doenv.schema.model.ColumnModel;
import com.doenv.schema.model.SchemaModel;
import com.doenv.schema.model.TableModel;
import com.doenv.schema.render.GeneratedSqlValidator;
import com.doenv.schema.render.PgDdlRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgDropColumnTest {

    @Test
    void 现场多余列生成不带Cascade的幂等删除() {
        TableModel baseline = table("vao_demo", "id", "name");
        TableModel live = table("vao_demo", "id", "legacy_column", "name");

        PgDdlRenderer renderer = new PgDdlRenderer("public");
        List<DiffItem> diffs = new SchemaDiffEngine(renderer)
                .diff(schema(baseline), schema(live), new DiffOptions());

        DiffItem drop = diffs.stream()
                .filter(d -> d.getType() == DiffType.COLUMN_DROP)
                .findFirst().orElseThrow();

        assertEquals(DiffLevel.CONFIRM, drop.getLevel());
        assertEquals("legacy_column", drop.getObjectName());
        assertEquals(List.of(
                "ALTER TABLE \"public\".\"vao_demo\" DROP COLUMN IF EXISTS \"legacy_column\";"),
                drop.getDdl());
        assertTrue(drop.getDdl().stream().noneMatch(sql -> sql.toUpperCase().contains("CASCADE")));
        assertTrue(GeneratedSqlValidator.validate(diffs, "public").isEmpty());
    }

    @Test
    void 列顺序不同不产生任何差异() {
        TableModel baseline = table("vao_demo", "id", "name", "description");
        TableModel live = table("vao_demo", "description", "id", "name");

        List<DiffItem> diffs = new SchemaDiffEngine(new PgDdlRenderer("public"))
                .diff(schema(baseline), schema(live), new DiffOptions());

        assertTrue(diffs.isEmpty(), "列顺序不属于结构差异");
    }

    @Test
    void 安全校验拒绝Cascade和其他类型夹带删列() {
        DiffItem cascade = DiffItem.of(DiffType.COLUMN_DROP, DiffLevel.CONFIRM,
                "vao_demo", "legacy_column");
        cascade.sql("ALTER TABLE \"public\".\"vao_demo\" DROP COLUMN IF EXISTS \"legacy_column\" CASCADE;");

        DiffItem disguised = DiffItem.of(DiffType.COLUMN_MISSING, DiffLevel.AUTO,
                "vao_demo", "legacy_column");
        disguised.sql("ALTER TABLE \"public\".\"vao_demo\" DROP COLUMN IF EXISTS \"legacy_column\";");

        assertTrue(GeneratedSqlValidator.validate(List.of(cascade), "public").stream()
                .anyMatch(e -> e.contains("DROP COLUMN")));
        assertTrue(GeneratedSqlValidator.validate(List.of(disguised), "public").stream()
                .anyMatch(e -> e.contains("DROP COLUMN")));
    }

    private static TableModel table(String name, String... columns) {
        TableModel table = new TableModel();
        table.setName(name);
        for (String nameOfColumn : columns) {
            ColumnModel column = new ColumnModel();
            column.setName(nameOfColumn);
            column.setRawType("text");
            column.setNormType("text");
            table.getColumns().add(column);
        }
        return table;
    }

    private static SchemaModel schema(TableModel table) {
        SchemaModel schema = new SchemaModel();
        schema.addTable(table);
        return schema;
    }
}
