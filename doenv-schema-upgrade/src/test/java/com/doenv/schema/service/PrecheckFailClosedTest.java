package com.doenv.schema.service;

import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffLevel;
import com.doenv.schema.diff.DiffType;
import com.doenv.schema.diff.PrecheckRule;
import com.doenv.schema.live.CkTopology;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PrecheckFailClosedTest {

    @Test
    void Pg安全检查异常时必须跳过破坏性操作() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class)))
                .thenThrow(new IllegalStateException("探测超时"));
        DataSource dataSource = mock(DataSource.class);

        DiffItem rebuild = DiffItem.of(DiffType.SEED_REBUILD, DiffLevel.CONFIRM,
                "vao_recordtype", null);
        rebuild.precheck(PrecheckRule.expectAtMost(0,
                "SELECT count(*) FROM vao_recordtype", "现场独有行", "不允许新增"));
        rebuild.sql("DELETE FROM \"public\".\"vao_recordtype\";");
        UpgradeReport report = new UpgradeReport();
        report.setReportId("r1");
        report.setDatabase("datao");
        report.setSchema("public");
        report.setSeedDiffs(List.of(rebuild));

        ExecutionResult result = new UpgradeExecutor(dataSource, jdbc, "public")
                .execute(report, List.of(DiffLevel.CONFIRM), false);

        assertEquals(1, result.getSkipped());
        assertEquals("SKIP_PRECHECK_ERROR", result.getStatements().get(0).getPfcVerdict());
        assertTrue(result.getStatements().get(0).getError().contains("探测失败"));
        verifyNoInteractions(dataSource);
    }

    @Test
    void Ck安全检查异常时必须跳过DropTable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class)))
                .thenThrow(new IllegalStateException("探测超时"));
        DataSource dataSource = mock(DataSource.class);

        DiffItem rebuild = DiffItem.of(DiffType.CK_REBUILD_EMPTY, DiffLevel.CONFIRM,
                "vax_case", null);
        rebuild.precheck(PrecheckRule.expectZero(
                "SELECT count() FROM vax_case", "当前行数", "有数据不能重建"));
        rebuild.sql("DROP TABLE IF EXISTS `vax_case`");

        ExecutionResult result = new CkUpgradeExecutor(dataSource, jdbc, CkTopology.SINGLE)
                .execute(List.of(rebuild), List.of(DiffLevel.CONFIRM), false, "datao");

        assertEquals(1, result.getSkipped());
        assertEquals("SKIP_PRECHECK_ERROR", result.getStatements().get(0).getPfcVerdict());
        assertTrue(result.getStatements().get(0).getError().contains("探测失败"));
        verifyNoInteractions(dataSource);
    }
}
