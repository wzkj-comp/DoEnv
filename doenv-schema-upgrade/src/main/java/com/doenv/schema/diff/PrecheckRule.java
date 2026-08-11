package com.doenv.schema.diff;

import lombok.Data;

/**
 * 可机器判定的前置探测。
 *
 * <p>光生成一条探测 SQL 是不够的 —— 执行器必须知道「什么结果算通过」，
 * 否则只能把差异推给人工。带上期望之后，CONFIRM 档就能自动放行：
 * 探测通过就执行，不通过就跳过并说明原因。
 */
@Data
public class PrecheckRule {

    public enum Expect {
        /** 期望查不出任何行。用于「有没有重复值」这类探测 */
        EMPTY_RESULT,
        /** 期望第一列的数值为 0。用于「NULL 有几行」「超长有几行」这类计数 */
        ZERO_COUNT,
        /**
         * 期望第一列的数值不超过 {@code maxValue}。
         *
         * <p>用于「比对时算出的数量，到执行时刻不能变多」这类 TOCTOU 防护 ——
         * 数量本身不为 0 是正常的（比如整表重建本就要删掉现场独有的行），
         * 变多才说明比对之后现场又写入了新数据，那些行不在报告里、删了没人知道。
         */
        AT_MOST,
        /** 只做信息采集，永远算通过。用于表行数、磁盘估算 */
        INFO_ONLY
    }

    private String sql;

    private Expect expect;

    /** 人能读懂的检查名，如「id 列重复值」 */
    private String description;

    /** 不通过时给运维看的提示 */
    private String failHint;

    /** AT_MOST 的上限值 */
    private long maxValue;

    public static PrecheckRule expectEmpty(String sql, String description, String failHint) {
        return build(sql, Expect.EMPTY_RESULT, description, failHint);
    }

    public static PrecheckRule expectZero(String sql, String description, String failHint) {
        return build(sql, Expect.ZERO_COUNT, description, failHint);
    }

    public static PrecheckRule expectAtMost(long max, String sql, String description, String failHint) {
        PrecheckRule r = build(sql, Expect.AT_MOST, description, failHint);
        r.maxValue = max;
        return r;
    }

    public static PrecheckRule info(String sql, String description) {
        return build(sql, Expect.INFO_ONLY, description, null);
    }

    private static PrecheckRule build(String sql, Expect expect, String description, String failHint) {
        PrecheckRule r = new PrecheckRule();
        r.sql = sql;
        r.expect = expect;
        r.description = description;
        r.failHint = failHint;
        return r;
    }
}
