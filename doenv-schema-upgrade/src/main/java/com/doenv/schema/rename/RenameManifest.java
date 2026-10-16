package com.doenv.schema.rename;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** PG 与 ClickHouse 共用的显式字段改名清单。 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RenameManifest {

    public static final String DEFAULT_LOCATION = "doenv/rename-manifest.json";

    private String manifestVersion;
    private String note;
    private List<ColumnRenameRule> pg = new ArrayList<>();
    private List<ColumnRenameRule> ck = new ArrayList<>();

    public static RenameManifest load(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            RenameManifest manifest = new ObjectMapper().readValue(in, RenameManifest.class);
            manifest.pg = manifest.pg == null ? new ArrayList<>() : manifest.pg;
            manifest.ck = manifest.ck == null ? new ArrayList<>() : manifest.ck;
            return manifest;
        }
    }

    public static RenameManifest loadDefault() throws IOException {
        return load(new ClassPathResource(DEFAULT_LOCATION));
    }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        validateGroup("PG", pg, errors);
        validateGroup("CK", ck, errors);
        return errors;
    }

    private static void validateGroup(String kind, List<ColumnRenameRule> rules,
                                      List<String> errors) {
        Set<String> fromKeys = new HashSet<>();
        Set<String> toKeys = new HashSet<>();
        for (ColumnRenameRule rule : rules) {
            if (blank(rule.getTable()) || blank(rule.getFrom()) || blank(rule.getTo())) {
                errors.add(kind + " rename 规则的 table/from/to 不能为空");
                continue;
            }
            if (rule.getFrom().equals(rule.getTo())) {
                errors.add(kind + " rename 规则新旧列名相同：" + rule.getTable()
                        + "." + rule.getFrom());
            }
            String fromKey = key(rule.getTable(), rule.getFrom());
            String toKey = key(rule.getTable(), rule.getTo());
            if (!fromKeys.add(fromKey)) {
                errors.add(kind + " rename 旧列重复登记：" + fromKey);
            }
            if (!toKeys.add(toKey)) {
                errors.add(kind + " rename 目标列重复登记：" + toKey);
            }
        }
    }

    private static String key(String table, String column) {
        return table.toLowerCase(Locale.ROOT) + "." + column.toLowerCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
