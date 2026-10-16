package com.doenv.schema;

import com.doenv.schema.diff.CkSchemaDiffEngine;
import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffOptions;
import com.doenv.schema.diff.DiffType;
import com.doenv.schema.live.CkTopology;
import com.doenv.schema.rename.ColumnRenameRule;
import com.doenv.schema.render.CkDdlRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CkRenameColumnTest {

    @Test
    void 单机命中清单时生成一条Rename() {
        List<DiffItem> diffs = diff(CkTopology.SINGLE);
        DiffItem rename = diffs.stream()
                .filter(d -> d.getType() == DiffType.COLUMN_RENAME)
                .findFirst().orElseThrow();

        assertEquals(1, rename.getDdl().size());
        assertTrue(rename.getDdl().get(0)
                .contains("ALTER TABLE `vax_demo` RENAME COLUMN IF EXISTS `old_name` TO `new_name`"));
        assertFalse(diffs.stream().anyMatch(d -> d.getType() == DiffType.COLUMN_MISSING));
    }

    @Test
    void 分布式命中清单时本地表和路由表成对改名() {
        List<DiffItem> diffs = diff(CkTopology.DISTRIBUTED);
        DiffItem rename = diffs.stream()
                .filter(d -> d.getType() == DiffType.COLUMN_RENAME)
                .findFirst().orElseThrow();

        assertEquals(2, rename.getDdl().size());
        assertTrue(rename.getDdl().get(0).contains("`vax_demo_local`"));
        assertTrue(rename.getDdl().get(1).contains("`vax_demo`"));
        assertTrue(CkDdlRenderer.validateItems(List.of(rename)).isEmpty());
    }

    @Test
    void 纯净库同时保留新旧列时现场两列齐全不算冲突() {
        ColumnRenameRule rule = new ColumnRenameRule(
                "vax_address", "totalusdamount", "usdbalance", "字段改名后兼容保留旧列");
        CkDdlRenderer renderer = new CkDdlRenderer(CkTopology.SINGLE, null, false);
        List<DiffItem> diffs = new CkSchemaDiffEngine(renderer, t -> 10L, List.of(rule))
                .diff(PgRenameColumnTest.schema(PgRenameColumnTest.table(
                                "vax_address", "id", "usdbalance", "totalusdamount")),
                        PgRenameColumnTest.schema(PgRenameColumnTest.table(
                                "vax_address", "id", "usdbalance", "totalusdamount")),
                        new DiffOptions());

        assertFalse(diffs.stream().anyMatch(d -> d.getType() == DiffType.COLUMN_RENAME));
        assertFalse(diffs.stream().anyMatch(d -> d.getType() == DiffType.COLUMN_RENAME_CONFLICT));
    }

    @Test
    void 纯净库保留新旧列但老现场只有旧列时先改名保住数据() {
        ColumnRenameRule rule = new ColumnRenameRule(
                "vax_address", "totalusdamount", "usdbalance", "字段改名后兼容保留旧列");
        CkDdlRenderer renderer = new CkDdlRenderer(CkTopology.SINGLE, null, false);
        List<DiffItem> diffs = new CkSchemaDiffEngine(renderer, t -> 10L, List.of(rule))
                .diff(PgRenameColumnTest.schema(PgRenameColumnTest.table(
                                "vax_address", "id", "usdbalance", "totalusdamount")),
                        PgRenameColumnTest.schema(PgRenameColumnTest.table(
                                "vax_address", "id", "totalusdamount")),
                        new DiffOptions());

        assertTrue(diffs.stream().anyMatch(d -> d.getType() == DiffType.COLUMN_RENAME));
        assertFalse(diffs.stream().anyMatch(d -> d.getType() == DiffType.COLUMN_MISSING),
                "同一轮不能先 ADD 目标列，否则会挡住 RENAME；来源列在下一轮补回");
    }

    private static List<DiffItem> diff(CkTopology topology) {
        ColumnRenameRule rule = new ColumnRenameRule(
                "vax_demo", "old_name", "new_name", "字段改名");
        CkDdlRenderer renderer = new CkDdlRenderer(topology, "'{cluster}'", true);
        return new CkSchemaDiffEngine(renderer, t -> 10L, List.of(rule))
                .diff(PgRenameColumnTest.schema(PgRenameColumnTest.table("vax_demo", "id", "new_name")),
                        PgRenameColumnTest.schema(PgRenameColumnTest.table("vax_demo", "id", "old_name")),
                        new DiffOptions());
    }
}
