package com.doenv.schema.seed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 配置数据下发规则清单。
 *
 * <p>清单是<b>人工审校的 SSOT</b>，不自动生成 —— 每一条策略背后都是对
 * 「后端有没有运行时写路径、现场会不会改这一列」的取证结论。
 *
 * <p>未在清单里列出的、但纯净库带初始化数据的表使用 {@link #defaultRule}。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SeedManifest {

    public static final String DEFAULT_LOCATION = "doenv/seed-manifest.json";

    private String manifestVersion;
    private String note;
    private SeedTableRule defaultRule;
    private List<SeedTableRule> tables = new ArrayList<>();

    private transient Map<String, SeedTableRule> index;

    public static SeedManifest load(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new ObjectMapper().readValue(in, SeedManifest.class);
        }
    }

    public static SeedManifest loadDefault() throws IOException {
        return load(new ClassPathResource(DEFAULT_LOCATION));
    }

    public SeedTableRule ruleFor(String tableName) {
        if (index == null) {
            Map<String, SeedTableRule> m = new LinkedHashMap<>();
            for (SeedTableRule r : tables) {
                m.put(r.getTable().toLowerCase(Locale.ROOT), r);
            }
            index = m;
        }
        SeedTableRule r = index.get(tableName == null ? null : tableName.toLowerCase(Locale.ROOT));
        if (r != null) {
            return r;
        }
        SeedTableRule fallback = copyRule(defaultRule);
        fallback.setTable(tableName);
        if (defaultRule == null) {
            fallback.setStrategy(SeedStrategy.STRUCTURE_ONLY);
            fallback.setReason("未在 manifest 中登记，默认只做结构检查、不下发数据");
        }
        return fallback;
    }

    private static SeedTableRule copyRule(SeedTableRule source) {
        SeedTableRule copy = new SeedTableRule();
        if (source == null) {
            return copy;
        }
        copy.setCategory(source.getCategory());
        copy.setStrategy(source.getStrategy());
        copy.setKey(new ArrayList<>(source.getKey()));
        copy.setFreeze(new ArrayList<>(source.getFreeze()));
        copy.setInsertMustSet(new ArrayList<>(source.getInsertMustSet()));
        copy.setDeletePredicate(source.getDeletePredicate());
        copy.setBaselineFilter(new LinkedHashMap<>(source.getBaselineFilter()));
        copy.setKeyBlacklist(new ArrayList<>(source.getKeyBlacklist()));
        copy.setDependsOn(new ArrayList<>(source.getDependsOn()));
        copy.setRebuildCheckColumns(new ArrayList<>(source.getRebuildCheckColumns()));
        copy.setPreserve(new ArrayList<>(source.getPreserve()));
        copy.setReason(source.getReason());
        return copy;
    }

    /** 按 dependsOn 做拓扑排序，父表排在子表前面 */
    public List<SeedTableRule> inDependencyOrder() {
        List<SeedTableRule> ordered = new ArrayList<>();
        List<SeedTableRule> pending = new ArrayList<>(tables);
        java.util.Set<String> done = new java.util.LinkedHashSet<>();

        int guard = pending.size() + 1;
        while (!pending.isEmpty() && guard-- > 0) {
            List<SeedTableRule> next = new ArrayList<>();
            for (SeedTableRule r : pending) {
                boolean ready = r.getDependsOn().stream()
                        .allMatch(d -> done.contains(d.toLowerCase(Locale.ROOT))
                                || tables.stream().noneMatch(
                                        t -> t.getTable().equalsIgnoreCase(d)));
                if (ready) {
                    ordered.add(r);
                    done.add(r.getTable().toLowerCase(Locale.ROOT));
                } else {
                    next.add(r);
                }
            }
            if (next.size() == pending.size()) {
                // 存在环，按原顺序兜底，不阻塞
                ordered.addAll(next);
                break;
            }
            pending = next;
        }
        return ordered;
    }
}
