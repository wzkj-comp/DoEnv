package com.doenv.schema.live;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CkLiveSchemaReaderTest {

    @Test
    void 新版优先使用完整索引类型列() {
        assertEquals("type_full", CkLiveSchemaReader.resolveIndexTypeColumn(Set.of("type", "type_full")));
    }

    @Test
    void ClickHouse22点8回退到type列() {
        assertEquals("type", CkLiveSchemaReader.resolveIndexTypeColumn(Set.of("database", "type", "expr")));
    }

    @Test
    void 没有类型列时也不能中断整次结构比对() {
        assertNull(CkLiveSchemaReader.resolveIndexTypeColumn(Set.of("database", "expr", "name")));
    }
}
