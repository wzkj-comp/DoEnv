package com.doenv.schema.live;

/**
 * ClickHouse 部署形态。
 *
 * <p>判定以<b>事实</b>为准（system.tables 里有没有 Distributed 引擎表），
 * 不读配置开关 —— 那个开关在纯净库里根本没被 seed，取不到时恒返回 false，只信它必错。
 */
public enum CkTopology {
    /** 单机：无 Distributed 表、无 Replicated 表 */
    SINGLE,
    /** 分布式：Distributed 路由表 + Replicated 本地表并存 */
    DISTRIBUTED,
    /** 两者只占其一，形态不明。此时禁止执行，只出脚本 */
    UNKNOWN
}
