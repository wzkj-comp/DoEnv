package com.doenv.schema;

import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffLevel;
import com.doenv.schema.diff.DiffOptions;
import com.doenv.schema.diff.DiffType;
import com.doenv.schema.diff.SchemaDiffEngine;
import com.doenv.schema.model.ColumnModel;
import com.doenv.schema.model.ConstraintModel;
import com.doenv.schema.model.SchemaModel;
import com.doenv.schema.model.TableModel;
import com.doenv.schema.render.PgDdlRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「只补键、不删键」的回归测试。
 *
 * <p>来自真实现场（湖北那套库）的事故：基准里 {@code vao_case_attribute_folder}
 * 没有主键、{@code id} 可空，而现场 {@code id} 是主键。比对器把「现场 NOT NULL、
 * 基准可空」当成列属性差异，生成了 {@code ALTER COLUMN id DROP NOT NULL} ——
 * PG 对主键列直接拒绝（42P16），而且每轮重试、永不收敛。
 *
 * <p>根子上是同一个约束的两半被不一致地处理：主键本身已被「严格单向」豁免，
 * 它隐含的 NOT NULL 却没有。现在统一成只补不删。
 */
class OnlyAddKeysTest {

    /** 造一张表：一列 id */
    private static TableModel table(String name, boolean idNotNull, boolean idIsPk) {
        TableModel t = new TableModel();
        t.setName(name);
        ColumnModel id = new ColumnModel();
        id.setName("id");
        id.setRawType("varchar(100)");
        id.setNormType("varchar");
        id.setNotNull(idNotNull);
        t.getColumns().add(id);
        if (idIsPk) {
            ConstraintModel pk = new ConstraintModel();
            pk.setType("PRIMARY_KEY");
            pk.setName(name + "_pkey");
            pk.getColumns().add("id");
            t.getConstraints().add(pk);
        }
        return t;
    }

    private static SchemaModel schema(TableModel... tables) {
        SchemaModel s = new SchemaModel();
        for (TableModel t : tables) {
            s.addTable(t);
        }
        return s;
    }

    private static List<DiffItem> diff(TableModel baseline, TableModel live) {
        return new SchemaDiffEngine(new PgDdlRenderer("public"))
                .diff(schema(baseline), schema(live), new DiffOptions());
    }

    @Test
    void 现场主键列的NOT_NULL不生成DROP() {
        // 基准：无主键、id 可空；现场：id 是主键（隐含 NOT NULL）
        List<DiffItem> diffs = diff(
                table("vao_case_attribute_folder", false, false),
                table("vao_case_attribute_folder", true, true));

        List<DiffItem> relax = diffs.stream()
                .filter(d -> d.getType() == DiffType.NULLABLE_RELAX)
                .toList();
        assertEquals(1, relax.size(), "应报告这个差异，而不是装作没看见");
        assertEquals(DiffLevel.INFO, relax.get(0).getLevel(),
                "只报告不处理；任何可执行档位都会让它每轮重试、永不收敛");
        assertTrue(relax.get(0).getDdl().isEmpty(), "不能生成任何 DDL");

        assertFalse(
                diffs.stream().flatMap(d -> d.getDdl().stream())
                        .anyMatch(s -> s.toUpperCase().contains("DROP NOT NULL")),
                "整批产物里都不该出现 DROP NOT NULL");
    }

    @Test
    void 非主键列的NOT_NULL同样不删() {
        // 现场自己加严的普通列 —— 也是「现场多出来的约束」，同样不动
        List<DiffItem> diffs = diff(
                table("vao_demo", false, false),
                table("vao_demo", true, false));

        assertEquals(DiffLevel.INFO, diffs.stream()
                .filter(d -> d.getType() == DiffType.NULLABLE_RELAX)
                .findFirst().orElseThrow().getLevel());
        assertFalse(
                diffs.stream().flatMap(d -> d.getDdl().stream())
                        .anyMatch(s -> s.toUpperCase().contains("DROP NOT NULL")));
    }

    @Test
    void 反向仍然要补上缺的NOT_NULL() {
        // 基准 NOT NULL、现场可空 —— 这是「补键」，必须保留
        List<DiffItem> diffs = diff(
                table("vao_demo", true, false),
                table("vao_demo", false, false));

        DiffItem tighten = diffs.stream()
                .filter(d -> d.getType() == DiffType.NULLABLE_TIGHTEN)
                .findFirst().orElseThrow();
        assertTrue(tighten.getDdl().stream()
                        .anyMatch(s -> s.toUpperCase().contains("SET NOT NULL")),
                "补非空约束的能力不能被一起砍掉");
    }
}
