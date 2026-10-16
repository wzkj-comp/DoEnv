package com.doenv.schema.seed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SeedValueNormalizationTest {

    @Test
    void pg布尔值使用完整文本避免与基准TrueFalse误判() {
        assertEquals("true", SeedDiffEngine.jdbcText(Boolean.TRUE, "t"));
        assertEquals("false", SeedDiffEngine.jdbcText(Boolean.FALSE, "f"));
    }

    @Test
    void 非布尔类型保持Jdbc原文本() {
        assertEquals("001", SeedDiffEngine.jdbcText("001", "001"));
        assertNull(SeedDiffEngine.jdbcText(null, null));
    }
}
