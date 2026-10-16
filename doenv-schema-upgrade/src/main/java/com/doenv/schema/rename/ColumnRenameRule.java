package com.doenv.schema.rename;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一条显式字段改名规则。只有进入清单的改名才允许自动执行。 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ColumnRenameRule {

    private String table;
    private String from;
    private String to;
    private String reason;

    public ColumnRenameRule(String table, String from, String to, String reason) {
        this.table = table;
        this.from = from;
        this.to = to;
        this.reason = reason;
    }
}
