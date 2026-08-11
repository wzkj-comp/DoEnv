package com.doenv.schema.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 约束定义（主键 / 唯一 / CHECK）。
 *
 * <p>注意：基准 167 张表里只有约 50 张有主键 —— 「缺主键」在这个系统里是常态而非缺陷，
 * 所以约束类差异一律只报告不自动执行。
 */
@Data
public class ConstraintModel {

    /** PRIMARY_KEY / UNIQUE / CHECK / FOREIGN_KEY */
    private String type;

    private String name;

    /** PK / UNIQUE / FK 本表侧的列清单；CHECK 为空 */
    private List<String> columns = new ArrayList<>();

    /** CHECK 的表达式原文 */
    private String expression;

    // ── 以下仅 FOREIGN_KEY 用 ──

    /** 被引用的表 */
    private String refTable;

    /** 被引用的列 */
    private List<String> refColumns = new ArrayList<>();

    /** ON DELETE 动作，如 CASCADE；无则为空 */
    private String onDelete;

    /** ON UPDATE 动作 */
    private String onUpdate;

    public boolean isForeignKey() {
        return "FOREIGN_KEY".equals(type);
    }
}
