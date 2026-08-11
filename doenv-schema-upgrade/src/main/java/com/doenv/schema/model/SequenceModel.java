package com.doenv.schema.model;

import lombok.Data;

/**
 * 序列。
 *
 * <p>只关心当前值 —— 序列对象本身随建表脚本一起存在，真正会出问题的是
 * {@code setval} 没同步：现场序列停在初始位置，而表里已经有存量数据，
 * 下一次取号就撞主键。
 */
@Data
public class SequenceModel {

    private String name;

    /** 当前值。基准来自 setval 的第二个参数，现场来自 pg_sequences.last_value */
    private Long lastValue;

    /**
     * setval 的第三个参数。true 表示「已被调用过」，下次取号返回 lastValue+1；
     * false 表示下次取号直接返回 lastValue。
     */
    private boolean called = true;

    /** 下次会取到的号 —— 比较两边时用这个，而不是裸的 lastValue */
    public long nextValue() {
        if (lastValue == null) {
            return 1L;
        }
        return called ? lastValue + 1 : lastValue;
    }
}
