package com.doenv.schema.diff;

/**
 * 结构差异类型。表和索引保持单向补充；PG 列按纯净库强一致，
 * 现场多余列使用 COLUMN_DROP 删除。
 */
public enum DiffType {

    /** 基准有表，现场缺 */
    TABLE_MISSING,

    /** 基准有列，现场缺 */
    COLUMN_MISSING,

    /** PG 现场有、基准无的列。为列结构强一致而删除；不使用 CASCADE */
    COLUMN_DROP,

    /** 清单明确登记的字段改名 */
    COLUMN_RENAME,

    /** 清单登记了改名，但现场新旧列同时存在，禁止自动覆盖 */
    COLUMN_RENAME_CONFLICT,

    /** 类型放宽且代价低：varchar(n) -> varchar(m>n)、varchar -> text。二进制兼容，不重写表 */
    TYPE_WIDEN_CHEAP,

    /** 类型放宽但要重写全表：int4 -> int8 等。ACCESS EXCLUSIVE 锁 */
    TYPE_WIDEN_HEAVY,

    /** 类型收窄：varchar(100) -> varchar(50)。存量超长即 ERROR 22001 整事务回滚 */
    TYPE_NARROW,

    /** 跨类型族：STRING -> INT 之类 */
    TYPE_CROSS_FAMILY,

    /** 现场可空、基准非空。存量有 NULL 时 SET NOT NULL 必失败 */
    NULLABLE_TIGHTEN,

    /** 现场非空、基准可空 */
    NULLABLE_RELAX,

    /** 默认值不一致 */
    DEFAULT_DIFF,

    /** 列注释不一致 */
    COLUMN_COMMENT_DIFF,

    /** 表注释不一致 */
    TABLE_COMMENT_DIFF,

    /** 基准有索引，现场按指纹找不到等价索引 */
    INDEX_MISSING,

    /** 基准有唯一索引，现场缺。必须先探测重复值 */
    UNIQUE_INDEX_MISSING,

    /** 基准有主键/唯一/CHECK 约束，现场缺 */
    CONSTRAINT_MISSING,

    /** 基准有外键，现场缺。加外键会校验存量数据，有孤儿引用就失败 */
    FOREIGN_KEY_MISSING,

    /** 序列当前值落后于基准。只前推不后退——后退会造成 id 重复 */
    SEQUENCE_VALUE_BEHIND,

    // ── ClickHouse 专属 ──

    /** 引擎/分区键/排序键/主键不一致，且表<b>有数据</b>。只能人工迁移，工具跳过 */
    CK_IMMUTABLE_DIFF,

    /**
     * 引擎/分区键/排序键/主键不一致，但表<b>是空的</b>。
     * 空表重建零数据风险，可自动执行 —— 但执行时刻必须再验一次行数，
     * 比对到执行之间可能有数据写入。
     */
    CK_REBUILD_EMPTY,

    /** 分布式拓扑不完整：有本地表无路由表，或反之 */
    CK_TOPOLOGY_MISMATCH,

    /** 分布式下本地表与路由表的列集漂移。补路由表是纯元数据操作，可自动修 */
    DIST_ROUTE_DRIFT,

    /** 表名/列名仅大小写不同。绝不自动改 —— 加列会加出重复列 */
    CASE_MISMATCH,

    /** 现场多出的表 */
    TABLE_EXTRA,

    /** 现场多出的列（ClickHouse 只报告；PG 使用 COLUMN_DROP） */
    COLUMN_EXTRA,

    /** 现场多出的索引 */
    INDEX_EXTRA,

    // ── 配置数据（种子）──

    /** 基准有该行，现场缺 */
    SEED_ROW_MISSING,

    /** 两边都有该行，但非冻结列的值不一致 */
    SEED_ROW_DIFF,

    /**
     * 元数据表整表重建（清空 + 按基准全量重灌）。
     *
     * <p>单独一个类型而不是复用 SEED_ROW_DIFF，是为了让生成物自检能<b>精确</b>放行
     * 这里的无 WHERE DELETE —— 靠匹配 SQL 文本来猜「这条 DELETE 是不是故意的」
     * 迟早会放错。同时报告也能据此把重建的表单独摊开。
     */
    SEED_REBUILD,

    /** CK 跳数索引缺失。补索引是元数据操作，不重建表、不重写数据 */
    /** 视图缺失。建视图是纯元数据操作，不动表也不动数据 */
    VIEW_MISSING,

    /** 视图定义不同。改视图等于整体替换，属覆盖操作，只报告 */
    VIEW_DIFF,

    /** 现场多出的视图，只报告不处理 */
    VIEW_EXTRA,

    CK_INDEX_MISSING,

    /** CK 跳数索引同名但定义不同。改索引要先 DROP，属删除操作，只报告 */
    CK_INDEX_DIFF,

    /** 现场多出的行 */
    SEED_ROW_EXTRA,

    /** 收尾检查：vao_user.systemauths 的 NULL 统一补为字符串 0 */
    FINAL_SYSTEMAUTHS_CHECK,

    /** 收尾检查：vax_item_label 引擎不一致时的安全迁移（新表、复制、核验、换名） */
    FINAL_ITEM_LABEL_ENGINE_MIGRATION
}
