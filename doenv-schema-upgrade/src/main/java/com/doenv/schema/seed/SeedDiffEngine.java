package com.doenv.schema.seed;

import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffLevel;
import com.doenv.schema.diff.DiffType;
import com.doenv.schema.diff.PrecheckRule;
import com.doenv.schema.model.SchemaModel;
import com.doenv.schema.model.SeedTable;
import com.doenv.schema.model.TableModel;
import com.doenv.schema.render.SeedSqlRenderer;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 配置数据差异引擎。按 manifest 的策略逐表比对。
 */
public class SeedDiffEngine {

    private final JdbcTemplate jdbc;
    private final SeedSqlRenderer renderer;
    private final SeedManifest manifest;

    public SeedDiffEngine(JdbcTemplate jdbc, SeedSqlRenderer renderer, SeedManifest manifest) {
        this.jdbc = jdbc;
        this.renderer = renderer;
        this.manifest = manifest;
    }

    /**
     * @param baselineSeeds 基准种子数据，key 为小写表名
     * @param live          现场结构（用于确认表是否存在）
     */
    public List<DiffItem> diff(Map<String, SeedTable> baselineSeeds, SchemaModel live) {
        List<DiffItem> out = new ArrayList<>();

        for (SeedTableRule rule : manifest.inDependencyOrder()) {
            SeedTable seed = baselineSeeds.get(rule.getTable().toLowerCase(Locale.ROOT));
            if (seed == null || seed.getRowCount() == 0) {
                continue;
            }
            if (rule.getStrategy() == SeedStrategy.STRUCTURE_ONLY) {
                out.add(DiffItem.of(DiffType.SEED_ROW_MISSING, DiffLevel.INFO, rule.getTable(), null)
                        .note("策略 STRUCTURE_ONLY：基准有 " + seed.getRowCount()
                                + " 行，按规则不下发。" + nz(rule.getReason())));
                continue;
            }
            TableModel lt = live.findTable(rule.getTable());
            if (lt == null) {
                out.add(DiffItem.of(DiffType.SEED_ROW_MISSING, DiffLevel.INFO, rule.getTable(), null)
                        .note("现场缺表，配置数据待建表后再下发"));
                continue;
            }
            try {
                diffOneTable(seed, rule, out);
            } catch (RuntimeException e) {
                out.add(DiffItem.of(DiffType.SEED_ROW_MISSING, DiffLevel.MANUAL, rule.getTable(), null)
                        .note("配置数据比对失败：" + e.getMessage()));
            }
        }
        return out;
    }

    private void diffOneTable(SeedTable seed, SeedTableRule rule, List<DiffItem> out) {
        String table = rule.getTable();

        if (rule.getStrategy() == SeedStrategy.FULL_OVERWRITE) {
            // 先确认是否真有差异 —— 内容已经一致还照样 DELETE+INSERT 全表，
            // 会在报告里制造「改了 3445 行」的假象，也白白锁表
            if (fullOverwriteAlreadyMatches(table, seed, rule)) {
                out.add(DiffItem.of(DiffType.SEED_ROW_DIFF, DiffLevel.INFO, table, null)
                        .note("配置数据已与基准一致（" + seed.getRowCount() + " 行），无需覆盖"));
                return;
            }
            DiffItem d = DiffItem.of(DiffType.SEED_ROW_DIFF, DiffLevel.CONFIRM, table, null);
            d.setBaselineDef(seed.getRowCount() + " 行");
            renderer.fullOverwrite(table, seed.getColumns(), seed.getRows(), rule).forEach(d::sql);
            d.note("全量覆盖：按 deletePredicate 删除基准来源旧行后重灌。" + nz(rule.getReason()));
            out.add(d);
            return;
        }

        if (rule.getStrategy() == SeedStrategy.REBUILD) {
            diffRebuild(seed, rule, out);
            return;
        }

        if (rule.getKey().isEmpty()) {
            out.add(DiffItem.of(DiffType.SEED_ROW_MISSING, DiffLevel.MANUAL, table, null)
                    .note("未配置稳定键，无法安全比对，已跳过"));
            return;
        }

        // ── 稳定键重复度自检 ──
        // 多数配置表没有主键/唯一索引，按不唯一的键做 UPDATE 会一次刷中多行、
        // 把本来不同的行抹成同一个值；按它 INSERT 又会静默插出重复行。
        // 只要基准侧或现场侧存在重复键，这些键对应的行一律不碰，转人工。
        Set<String> dupKeys = new LinkedHashSet<>();
        dupKeys.addAll(duplicateKeysInBaseline(seed, rule));
        dupKeys.addAll(duplicateKeysInLive(table, seed, rule));
        if (!dupKeys.isEmpty()) {
            out.add(DiffItem.of(DiffType.SEED_ROW_DIFF, DiffLevel.MANUAL, table, null)
                    .note("稳定键 (" + String.join(", ", rule.getKey()) + ") 存在 "
                            + dupKeys.size() + " 组重复，这些行已跳过不处理。"
                            + "按不唯一的键更新会一次刷中多行并抹掉行间差异，需人工先去重")
                    .note("重复键样例：" + dupKeys.stream().limit(5)
                            .map(k -> k.replace(KEY_SEP, '|')).toList()));
        }

        Map<String, Map<String, String>> liveRows = loadLiveRows(table, seed, rule);

        int missing = 0;
        int skippedDup = 0;
        int diffCount = 0;
        List<String> insertSql = new ArrayList<>();
        List<String> updateSql = new ArrayList<>();

        for (Map<String, String> row : seed.getRows()) {
            if (isBlacklisted(row, rule)) {
                continue;
            }
            String k = keyOf(row, rule.getKey());
            if (dupKeys.contains(k)) {
                skippedDup++;
                continue;
            }
            Map<String, String> liveRow = liveRows.get(k);

            if (liveRow == null) {
                missing++;
                insertSql.add(renderer.insertIfAbsent(table, seed.getColumns(), row, rule.getKey()));
                continue;
            }
            if (rule.getStrategy() == SeedStrategy.INSERT_ONLY) {
                continue;   // 已有行一个字段都不改
            }
            // MERGE_FREEZE：只比非冻结列
            List<String> changed = new ArrayList<>();
            for (String col : seed.getColumns()) {
                if (rule.isFrozen(col) || rule.getKey().stream().anyMatch(x -> x.equalsIgnoreCase(col))) {
                    continue;
                }
                if (!Objects.equals(nz(row.get(col)), nz(liveRow.get(col)))) {
                    changed.add(col);
                }
            }
            if (!changed.isEmpty()) {
                String sql = renderer.updateNonFrozen(table, row, changed, rule);
                if (sql != null) {
                    diffCount++;
                    updateSql.add(sql);
                }
            }
        }

        if (missing > 0) {
            DiffItem d = DiffItem.of(DiffType.SEED_ROW_MISSING, DiffLevel.AUTO, table, null);
            d.setBaselineDef("基准 " + seed.getRowCount() + " 行");
            d.setLiveDef("现场缺 " + missing + " 行");
            insertSql.forEach(d::sql);
            d.note("按 " + String.join("+", rule.getKey()) + " 补缺行，已有行不动");
            out.add(d);
        }
        if (diffCount > 0) {
            DiffItem d = DiffItem.of(DiffType.SEED_ROW_DIFF, DiffLevel.CONFIRM, table, null);
            d.setLiveDef(diffCount + " 行的非冻结列与基准不一致");
            updateSql.forEach(d::sql);
            if (!rule.getFreeze().isEmpty()) {
                d.note("冻结列不参与更新：" + String.join(", ", rule.getFreeze()));
            }
            out.add(d);
        }
        if (missing == 0 && diffCount == 0) {
            out.add(DiffItem.of(DiffType.SEED_ROW_DIFF, DiffLevel.INFO, table, null)
                    .note("配置数据已与基准一致（" + seed.getRowCount() + " 行"
                            + (skippedDup > 0 ? "，另有 " + skippedDup + " 行因键重复跳过" : "")
                            + "）"));
        }
    }

    /** FULL_OVERWRITE 前的等价性检查：行数与逐行逐列内容都一致才算已匹配 */
    private boolean fullOverwriteAlreadyMatches(String table, SeedTable seed, SeedTableRule rule) {
        if (rule.getKey().isEmpty()) {
            return false;
        }
        Map<String, Map<String, String>> liveRows;
        try {
            liveRows = loadLiveRows(table, seed, rule);
        } catch (RuntimeException e) {
            return false;
        }
        if (liveRows.size() != seed.getRowCount()) {
            return false;
        }
        for (Map<String, String> row : seed.getRows()) {
            Map<String, String> live = liveRows.get(keyOf(row, rule.getKey()));
            if (live == null) {
                return false;
            }
            for (String col : seed.getColumns()) {
                if (!Objects.equals(nz(row.get(col)), nz(live.get(col)))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 基准侧按稳定键重复的键集合 */
    private Set<String> duplicateKeysInBaseline(SeedTable seed, SeedTableRule rule) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (Map<String, String> row : seed.getRows()) {
            count.merge(keyOf(row, rule.getKey()), 1, Integer::sum);
        }
        Set<String> dup = new LinkedHashSet<>();
        count.forEach((k, n) -> {
            if (n > 1) {
                dup.add(k);
            }
        });
        return dup;
    }

    /** 现场侧按稳定键重复的键集合 */
    private Set<String> duplicateKeysInLive(String table, SeedTable seed, SeedTableRule rule) {
        String cols = rule.getKey().stream()
                .map(com.doenv.schema.render.SqlQuoter::ident)
                .collect(java.util.stream.Collectors.joining(", "));
        String sql = "SELECT " + cols + " FROM "
                + com.doenv.schema.render.SqlQuoter.table(schemaOf(), table)
                + " GROUP BY " + cols + " HAVING count(*) > 1";
        Set<String> dup = new LinkedHashSet<>();
        jdbc.query(sql, rs -> {
            Map<String, String> row = new LinkedHashMap<>();
            for (String k : rule.getKey()) {
                row.put(k, rs.getString(k));
            }
            dup.add(keyOf(row, rule.getKey()));
        });
        return dup;
    }

    /**
     * 整表重建。
     *
     * <p>先按 id 对齐比一遍，确认真有差异才重建 —— 一致时零操作，报告里也不会
     * 凭空多出「改了 7000 行」的噪声。判据是 {@code rebuildCheckColumns}
     * 指定的关键列，外加行集合本身的出入（行集合差异必须触发，否则基准新增的行永远进不来）。
     *
     * <p>重建会删掉现场独有的行，这是「严格以基准为准」的应有之义；
     * 只有 {@code preserve} 列（count 这类运行时统计）会被取出来重建后回填。
     */
    private void diffRebuild(SeedTable seed, SeedTableRule rule, List<DiffItem> out) {
        String table = rule.getTable();
        Map<String, Map<String, String>> liveById = loadLiveRowsById(table, seed);

        // 基准侧按 id 索引
        Map<String, Map<String, String>> baseById = new LinkedHashMap<>();
        for (Map<String, String> r : seed.getRows()) {
            String id = r.get("id");
            if (id != null) {
                baseById.put(id, r);
            }
        }

        List<String> checkCols = rule.getRebuildCheckColumns().isEmpty()
                ? seed.getColumns() : rule.getRebuildCheckColumns();

        int onlyBase = 0;
        for (String id : baseById.keySet()) {
            if (!liveById.containsKey(id)) {
                onlyBase++;
            }
        }
        int onlyLive = 0;
        for (String id : liveById.keySet()) {
            if (!baseById.containsKey(id)) {
                onlyLive++;
            }
        }

        // 关键列逐个比，记下第一批不同的列名供报告用
        Map<String, Integer> colDiff = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : baseById.entrySet()) {
            Map<String, String> lr = liveById.get(e.getKey());
            if (lr == null) {
                continue;
            }
            for (String c : checkCols) {
                if (rule.getPreserve().contains(c)) {
                    continue;   // 运行时列本就会不同，不作为重建理由
                }
                if (!Objects.equals(norm(e.getValue().get(c)), norm(lr.get(c)))) {
                    colDiff.merge(c, 1, Integer::sum);
                }
            }
        }

        if (onlyBase == 0 && onlyLive == 0 && colDiff.isEmpty()) {
            out.add(DiffItem.of(DiffType.SEED_ROW_DIFF, DiffLevel.INFO, table, null)
                    .note("与基准逐行一致（" + seed.getRowCount() + " 行，比对列 "
                            + String.join("/", checkCols) + "），无需重建"));
            return;
        }

        // 取现场的运行时列，重建后回填
        Map<String, Map<String, String>> preserved = new LinkedHashMap<>();
        int zeroed = 0;
        if (!rule.getPreserve().isEmpty()) {
            for (Map.Entry<String, Map<String, String>> e : liveById.entrySet()) {
                if (!baseById.containsKey(e.getKey())) {
                    continue;   // 这行重建后不存在，回填无意义
                }
                Map<String, String> vals = new LinkedHashMap<>();
                for (String c : rule.getPreserve()) {
                    String v = e.getValue().get(c);
                    if (v != null && !Objects.equals(norm(v), norm(baseById.get(e.getKey()).get(c)))) {
                        vals.put(c, v);
                    }
                }
                if (!vals.isEmpty()) {
                    preserved.put(e.getKey(), vals);
                }
            }
            // 基准新增、现场还没有的行：基准里的统计值不能信 —— 实测 2026-08-08 的
            // vao_recordtype 有 5 行 count 非 0（合计 39020），是导出时带上了某个环境的
            // 运行时统计。现场这些表一行数据都没有，count 必须归零，否则界面上凭空显示 7805 条
            for (Map.Entry<String, Map<String, String>> e : baseById.entrySet()) {
                if (liveById.containsKey(e.getKey())) {
                    continue;
                }
                Map<String, String> vals = new LinkedHashMap<>();
                for (String c : rule.getPreserve()) {
                    if (!"0".equals(norm(e.getValue().get(c)))) {
                        vals.put(c, "0");
                    }
                }
                if (!vals.isEmpty()) {
                    preserved.put(e.getKey(), vals);
                    zeroed++;
                }
            }
        }

        DiffItem d = DiffItem.of(DiffType.SEED_REBUILD, DiffLevel.CONFIRM, table, null);
        d.setBaselineDef("基准 " + seed.getRowCount() + " 行");
        d.setLiveDef("现场 " + liveById.size() + " 行");
        d.setEstimatedRows((long) seed.getRowCount());
        d.note("整表重建：清空后按基准全量重灌。触发原因 —— "
                + (onlyBase > 0 ? "基准多 " + onlyBase + " 行；" : "")
                + (onlyLive > 0 ? "现场多 " + onlyLive + " 行（重建后删除）；" : "")
                + (colDiff.isEmpty() ? "" : "关键列有差异 " + colDiff + "；")
                + nz(rule.getReason()));
        if (!preserved.isEmpty()) {
            d.note("重建后回填 " + String.join("/", rule.getPreserve())
                    + " 共 " + preserved.size() + " 行（保住现场统计）"
                    + (zeroed > 0 ? "，其中 " + zeroed
                        + " 行是基准新增、现场尚无数据，已强制归零（基准里的统计值来自导出环境，不可信）" : ""));
        }
        if (onlyLive > 0) {
            d.note("现场独有的 " + onlyLive + " 行将被删除 —— 严格以基准为准");
        }
        // 执行时刻复检：比对(T0)到执行(T1)之间 DataO 主应用还在跑，管理员可能新配了
        // 记录类型/实体属性。T0 算出的「现场独有 N 行」到 T1 已经不作数，
        // 而 DELETE 是不可逆的 —— CK 侧的空表重建早有 ZERO_COUNT 探测，这里要对齐
        d.precheck(PrecheckRule.expectAtMost(onlyLive,
                renderer.countRowsNotIn(table, "id", baseById.keySet()),
                table + " 现场独有行数（比对时为 " + onlyLive + "）",
                "比对之后现场又新增了行 —— 这些行不在报告的删除清单里，"
                        + "重建会把它们静默删掉。请重新比对确认"));
        renderer.rebuild(table, seed.getColumns(), seed.getRows(), preserved).forEach(d::sql);
        out.add(d);
    }

    /** 按 id 取现场整行。REBUILD 用 id 对齐，不走稳定键 */
    private Map<String, Map<String, String>> loadLiveRowsById(String table, SeedTable seed) {
        String sql = renderer.selectByKeys(table, seed.getColumns(), List.of("id"));
        Map<String, Map<String, String>> map = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            Map<String, String> row = new LinkedHashMap<>();
            for (String c : seed.getColumns()) {
                row.put(c, rs.getString(c));
            }
            String id = rs.getString("id");
            if (id != null) {
                map.put(id, row);
            }
        });
        return map;
    }

    /** 比对用的归一：null 与空串等价，两侧来源不同（文件文本 vs JDBC）容易在这上面误报 */
    private static String norm(String v) {
        return v == null || v.isEmpty() ? null : v.trim();
    }

    private Map<String, Map<String, String>> loadLiveRows(String table, SeedTable seed,
                                                          SeedTableRule rule) {
        String sql = renderer.selectByKeys(table, seed.getColumns(), rule.getKey());
        Map<String, Map<String, String>> map = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            Map<String, String> row = new LinkedHashMap<>();
            for (String c : seed.getColumns()) {
                row.put(c, rs.getString(c));
            }
            for (String k : rule.getKey()) {
                if (!row.containsKey(k)) {
                    row.put(k, rs.getString(k));
                }
            }
            map.put(keyOf(row, rule.getKey()), row);
        });
        return map;
    }

    private boolean isBlacklisted(Map<String, String> row, SeedTableRule rule) {
        if (rule.getKeyBlacklist().isEmpty() || rule.getKey().isEmpty()) {
            return false;
        }
        String v = row.get(rule.getKey().get(0));
        return v != null && rule.getKeyBlacklist().contains(v);
    }

    /** 用不可见分隔符拼复合键，避免 "a"+"bc" 与 "ab"+"c" 撞键 */
    private static final char KEY_SEP = '\u0001';

    private String keyOf(Map<String, String> row, List<String> keyColumns) {
        StringBuilder sb = new StringBuilder();
        for (String k : keyColumns) {
            sb.append(nz(row.get(k))).append(KEY_SEP);
        }
        return sb.toString();
    }

    private String schemaOf() {
        return renderer.getSchema();
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }
}
