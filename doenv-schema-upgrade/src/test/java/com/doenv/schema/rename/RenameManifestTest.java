package com.doenv.schema.rename;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenameManifestTest {

    @Test
    void 默认清单可以加载且分开维护Pg和Ck() throws Exception {
        RenameManifest manifest = RenameManifest.loadDefault();
        assertTrue(manifest.validate().isEmpty());
        assertEquals(List.of(), manifest.getPg());
        assertEquals(2, manifest.getCk().size());
        assertEquals("KS_PARTICIPANT", manifest.getCk().get(0).getTable());
        assertEquals("userid", manifest.getCk().get(0).getFrom());
        assertEquals("examineeid", manifest.getCk().get(0).getTo());
        assertEquals("vax_address", manifest.getCk().get(1).getTable());
        assertEquals("totalusdamount", manifest.getCk().get(1).getFrom());
        assertEquals("usdbalance", manifest.getCk().get(1).getTo());
    }

    @Test
    void 重复目标和空字段会阻断() {
        RenameManifest manifest = new RenameManifest();
        manifest.setPg(List.of(
                new ColumnRenameRule("vao_demo", "old_a", "new_name", ""),
                new ColumnRenameRule("vao_demo", "old_b", "new_name", ""),
                new ColumnRenameRule("", "old_c", "new_c", "")));

        List<String> errors = manifest.validate();
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("目标列重复")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("不能为空")));
    }
}
