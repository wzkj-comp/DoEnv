package com.doenv.schema.seed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 单张表的配置数据下发规则。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SeedTableRule {

    private String table;

    /** A / B / C / EMPTY，仅用于报告分组 */
    private String category;

    private SeedStrategy strategy = SeedStrategy.STRUCTURE_ONLY;

    /**
     * 稳定键。<b>不一定是 id</b> —— 实测 11 张表的 id 跨现场不稳定
     * （代码里存在 setId(getNewID()) 且现场可触发），按 id 对齐会静默插出重复行。
     */
    private List<String> key = new ArrayList<>();

    /** 冻结列：承载现场态，UPDATE 时绝不出现在 SET 子句里 */
    private List<String> freeze = new ArrayList<>();

    /** INSERT 时必须显式写值的列（有的列不在后端的字段常量里，不写就会被重置为默认值） */
    private List<String> insertMustSet = new ArrayList<>();

    /** FULL_OVERWRITE 专用：删除基准来源旧行的谓词。为空则拒绝执行，不允许无条件删 */
    private String deletePredicate;

    /** 不下发的 key 值（配合 INSERT_ONLY，如 vao_system_opt 里的开发机地址） */
    private List<String> keyBlacklist = new ArrayList<>();

    /** 依赖的表，决定执行顺序（父表先于子表） */
    private List<String> dependsOn = new ArrayList<>();

    /** 定策理由与风险提示，直接展示在报告里 */
    /**
     * REBUILD 的触发判据：只比这些列，全一致就不重建。
     * 留空则退化为「比对全部基准列」。行集合本身的差异（多行/少行）永远触发。
     */
    private List<String> rebuildCheckColumns = new ArrayList<>();

    /**
     * REBUILD 时要保住的运行时列，如 count。
     * 重建前按 id 取现场值，重灌后再 UPDATE 回去 —— 基准里这些列恒为 0，直接覆盖等于清零。
     */
    private List<String> preserve = new ArrayList<>();

    private String reason;

    public boolean isFrozen(String column) {
        return freeze.stream().anyMatch(f -> f.equalsIgnoreCase(column));
    }
}
