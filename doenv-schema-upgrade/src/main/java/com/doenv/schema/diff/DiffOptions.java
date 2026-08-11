package com.doenv.schema.diff;

import lombok.Data;

/**
 * 比对选项。
 */
@Data
public class DiffOptions {

    /**
     * 是否把注释差异纳入比对。默认开启 ——
     * 策略是「只在基准有注释时下发，基准为空一律不动现场」，
     * 所以不会因为基准大量缺注释而刷出噪音，也不会抹掉现场自己补的注释。
     */
    private boolean checkComment = true;

    /** 是否比对默认值 */
    private boolean checkDefault = true;

    /** 是否比对索引 */
    private boolean checkIndex = true;

    /** 是否比对约束（主键/唯一/CHECK）。基准里大量表本就无主键，缺主键是常态 */
    private boolean checkConstraint = true;

    /** 是否报告现场多出的对象（只报告，永不生成 DDL） */
    private boolean reportExtra = true;
}
