package com.doenv.schema.seed;

import com.doenv.schema.baseline.CkTokens;
import com.doenv.schema.diff.DiffItem;
import com.doenv.schema.diff.DiffLevel;
import com.doenv.schema.diff.DiffType;
import com.doenv.schema.model.SchemaModel;
import com.doenv.schema.model.SeedTable;
import com.doenv.schema.model.TableModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ClickHouse 配置数据比对。
 *
 * <p>已明确的 7 张配置表按 id 只补不改；后续新数据表按 id 补缺并更新差异列，
 * 四个审计字段不覆盖。没有 id 的新表不猜排序键，直接转人工。
 *
 * <p>CK 没有 {@code INSERT ... WHERE NOT EXISTS} 这种写法（相关子查询支持有限），
 * 所以做法是：先把现场已有的主键读出来，在内存里算出缺哪些，再拼一条批量 INSERT。
 */
public class CkSeedDiffEngine {

    private static final Logger log = LoggerFactory.getLogger(CkSeedDiffEngine.class);

    /** 单条 INSERT 的行数上限，避免语句过大 */
    private static final int BATCH = 200;

    private final JdbcTemplate jdbc;
    private final boolean distributed;
    private final String clusterRef;
    private final SeedManifest manifest;

    public CkSeedDiffEngine(JdbcTemplate jdbc, boolean distributed) {
        this(jdbc, distributed, distributed ? "'{cluster}'" : null, loadDefaultManifest());
    }

    public CkSeedDiffEngine(JdbcTemplate jdbc, boolean distributed, String clusterRef) {
        this(jdbc, distributed, clusterRef, loadDefaultManifest());
    }

    public CkSeedDiffEngine(JdbcTemplate jdbc, boolean distributed,
                            String clusterRef, SeedManifest manifest) {
        this.jdbc = jdbc;
        this.distributed = distributed;
        this.clusterRef = clusterRef;
        this.manifest = manifest;
    }

    private static SeedManifest loadDefaultManifest() {
        try {
            return SeedManifest.load(new org.springframework.core.io.ClassPathResource(
                    "doenv/ck-seed-manifest.json"));
        } catch (Exception e) {
            throw new IllegalStateException("读取 CK 数据规则失败：" + e.getMessage(), e);
        }
    }

    /**
     * @param baselineSeeds 基准种子，key 为小写表名
     * @param live          现场结构，用于确认表存在
     */
    public List<DiffItem> diff(Map<String, SeedTable> baselineSeeds, SchemaModel live) {
        List<DiffItem> out = new ArrayList<>();

        for (SeedTable seed : baselineSeeds.values()) {
            if (seed.getRowCount() == 0) {
                continue;
            }
            String table = seed.getTableName();
            SeedTableRule rule = manifest.ruleFor(table);
            TableModel lt = live.findTable(table);
            if (lt == null) {
                out.add(DiffItem.of(DiffType.SEED_ROW_MISSING, DiffLevel.INFO, table, null)
                        .note("现场缺表，配置数据待建表后再下发"));
                continue;
            }
            String keyCol = resolveIdColumn(seed);
            if (keyCol == null) {
                out.add(DiffItem.of(DiffType.SEED_ROW_MISSING, DiffLevel.MANUAL, table, null)
                        .note("后续新数据表没有 id 列，按规则不执行任何数据更新，等待人工选择稳定键"));
                continue;
            }
            try {
                diffOneTable(seed, table, keyCol, rule, out);
            } catch (RuntimeException e) {
                out.add(DiffItem.of(DiffType.SEED_ROW_MISSING, DiffLevel.MANUAL, table, null)
                        .note("配置数据比对失败：" + e.getMessage()));
            }
        }
        return out;
    }

    private void diffOneTable(SeedTable seed, String table, String keyCol,
                              SeedTableRule rule, List<DiffItem> out) {
        Set<String> baselineDuplicates = duplicateBaselineKeys(seed, keyCol);
        if (!baselineDuplicates.isEmpty()) {
            out.add(DiffItem.of(DiffType.SEED_ROW_DIFF, DiffLevel.MANUAL, table, null)
                    .note("基准 id 存在重复（" + baselineDuplicates.size()
                            + " 组），本表不生成任何数据 SQL，请先清理纯净库")
                    .note("重复 id 样例：" + baselineDuplicates.stream().limit(5).toList()));
            return;
        }
        Set<String> liveDuplicates = duplicateLiveKeys(seed, table, keyCol);
        if (!liveDuplicates.isEmpty()) {
            out.add(DiffItem.of(DiffType.SEED_ROW_DIFF, DiffLevel.MANUAL, table, null)
                    .note("现场 id 存在重复（" + liveDuplicates.size()
                            + " 组），按 id UPDATE 会一次改多行，本表已全部转待审核")
                    .note("重复 id 样例：" + liveDuplicates.stream().limit(5).toList()));
            return;
        }
        // 路由表可以直接查（Distributed 会转发到本地表），读主键走逻辑名即可
        Set<String> liveKeys = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT DISTINCT " + CkTokens.ident(keyCol) + " FROM " + CkTokens.ident(table),
                String.class));

        // 按下标筛，才能取到与之对齐的 SQL 原始词法
        List<Map<String, String>> missing = new ArrayList<>();
        List<Integer> existingIndexes = new ArrayList<>();
        for (int i = 0; i < seed.getRowCount(); i++) {
            String k = seed.getRows().get(i).get(keyCol);
            if (k != null && !liveKeys.contains(k)) {
                missing.add(seed.rawRow(i));
            } else if (k != null) {
                existingIndexes.add(i);
            }
        }

        if (!missing.isEmpty()) {
            appendMissing(seed, table, keyCol, liveKeys, missing, out);
        }

        int updatedValues = 0;
        if (rule.getStrategy() == SeedStrategy.MERGE_FREEZE && !existingIndexes.isEmpty()) {
            Map<String, Map<String, String>> liveRows = loadLiveRows(
                    seed, table, keyCol, existingIndexes);
            List<String> updates = new ArrayList<>();
            for (Integer rowIndex : existingIndexes) {
                Map<String, String> base = seed.getRows().get(rowIndex);
                Map<String, String> live = liveRows.get(base.get(keyCol));
                if (live == null) {
                    continue;
                }
                List<String> changed = changedColumns(seed, rowIndex, live, rule, keyCol);
                if (!changed.isEmpty()) {
                    updates.add(renderUpdate(table, seed, rowIndex, changed, keyCol));
                    updatedValues += changed.size();
                }
            }
            if (!updates.isEmpty()) {
                DiffItem d = DiffItem.of(DiffType.SEED_ROW_DIFF, DiffLevel.CONFIRM, table, null);
                d.setLiveDef(updates.size() + " 行存在 " + updatedValues + " 个差异值");
                d.note("按 id 更新差异列；不更新 creatorid、createdtime、lastupdatorid、lastupdatedtime；不删除现场数据");
                updates.forEach(d::sql);
                out.add(d);
            }
        }

        if (missing.isEmpty() && updatedValues == 0) {
            out.add(DiffItem.of(DiffType.SEED_ROW_DIFF, DiffLevel.INFO, table, null)
                    .note("配置数据已齐（基准 " + seed.getRowCount() + " 行，现场已有 "
                            + liveKeys.size() + " 个 " + keyCol + "），无需更新"));
        }
    }

    private void appendMissing(SeedTable seed, String table, String keyCol, Set<String> liveKeys,
                               List<Map<String, String>> missing, List<DiffItem> out) {
        DiffItem d = DiffItem.of(DiffType.SEED_ROW_MISSING, DiffLevel.AUTO, table, null);
        d.setBaselineDef("基准 " + seed.getRowCount() + " 行");
        d.setLiveDef(liveKeys.isEmpty()
                ? "现场空表，全量插入 " + missing.size() + " 行"
                : "现场已有 " + liveKeys.size() + " 行，缺 " + missing.size() + " 行");
        d.note("按 " + keyCol + " 补缺行；现场多出的行不删除");
        long adapted = missing.stream()
                .flatMap(r -> r.values().stream())
                .filter(v -> v != null && !v.equals(adaptDateTime(v)))
                .count();
        if (adapted > 0) {
            d.note("其中 " + adapted + " 个带时区的时间字面量已转成 CK 的 DateTime 格式"
                    + "（如 2026-01-25T08:10:30+08:00[Asia/Shanghai] → 2026-01-25 08:10:30）——"
                    + "CK 的 DateTime 不存时区，原样下发会报 Code 6");
        }
        d.setEstimatedRows((long) missing.size());

        for (int i = 0; i < missing.size(); i += BATCH) {
            List<Map<String, String>> slice = missing.subList(i, Math.min(i + BATCH, missing.size()));
            d.sql(renderInsert(table, seed.getColumns(), slice));
        }
        out.add(d);
        log.debug("CK 配置数据 {}：缺 {} 行", table, missing.size());
    }

    private Map<String, Map<String, String>> loadLiveRows(SeedTable seed, String table,
                                                           String keyCol,
                                                           List<Integer> rowIndexes) {
        String cols = seed.getColumns().stream().map(CkTokens::ident)
                .collect(Collectors.joining(", "));
        Map<String, Map<String, String>> rows = new LinkedHashMap<>();
        for (int from = 0; from < rowIndexes.size(); from += BATCH) {
            List<Integer> slice = rowIndexes.subList(from,
                    Math.min(from + BATCH, rowIndexes.size()));
            String keys = slice.stream()
                    .map(i -> adaptDateTime(seed.rawRow(i).get(keyCol)))
                    .collect(Collectors.joining(", "));
            jdbc.query("SELECT " + cols + " FROM " + CkTokens.ident(table)
                    + " WHERE " + CkTokens.ident(keyCol) + " IN (" + keys + ")", rs -> {
                Map<String, String> row = new LinkedHashMap<>();
                for (String column : seed.getColumns()) {
                    row.put(column, rs.getString(column));
                }
                rows.put(row.get(keyCol), row);
            });
        }
        return rows;
    }

    private static Set<String> duplicateBaselineKeys(SeedTable seed, String keyCol) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, String> row : seed.getRows()) {
            String key = row.get(keyCol);
            if (key != null) {
                counts.merge(key, 1, Integer::sum);
            }
        }
        Set<String> duplicates = new LinkedHashSet<>();
        counts.forEach((key, count) -> {
            if (count > 1) {
                duplicates.add(key);
            }
        });
        return duplicates;
    }

    private Set<String> duplicateLiveKeys(SeedTable seed, String table, String keyCol) {
        Set<String> duplicates = new LinkedHashSet<>();
        List<String> keys = seed.getRawRows().stream()
                .map(row -> row.get(keyCol))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        for (int from = 0; from < keys.size(); from += BATCH) {
            String in = keys.subList(from, Math.min(from + BATCH, keys.size())).stream()
                    .map(CkSeedDiffEngine::adaptDateTime)
                    .collect(Collectors.joining(", "));
            String sql = "SELECT toString(" + CkTokens.ident(keyCol) + ") FROM "
                    + CkTokens.ident(table) + " WHERE " + CkTokens.ident(keyCol)
                    + " IN (" + in + ") GROUP BY " + CkTokens.ident(keyCol)
                    + " HAVING count() > 1";
            duplicates.addAll(jdbc.queryForList(sql, String.class));
        }
        return duplicates;
    }

    static List<String> changedColumns(SeedTable seed, int rowIndex,
                                       Map<String, String> live, SeedTableRule rule,
                                       String keyCol) {
        List<String> changed = new ArrayList<>();
        Map<String, String> base = seed.getRows().get(rowIndex);
        Map<String, String> raw = seed.rawRow(rowIndex);
        for (String column : seed.getColumns()) {
            if (column.equalsIgnoreCase(keyCol) || rule.isFrozen(column)) {
                continue;
            }
            String expected = comparableBaseline(base.get(column), raw.get(column));
            if (!java.util.Objects.equals(expected, live.get(column))) {
                changed.add(column);
            }
        }
        return changed;
    }

    private static String comparableBaseline(String parsed, String raw) {
        if (parsed == null || raw == null || raw.equalsIgnoreCase("NULL")) {
            return null;
        }
        String adapted = adaptDateTime(raw);
        return adapted.equals(raw) ? parsed : CkTokens.unquoteLiteral(adapted);
    }

    String renderUpdate(String table, SeedTable seed, int rowIndex,
                        List<String> changed, String keyCol) {
        Map<String, String> raw = seed.rawRow(rowIndex);
        String set = changed.stream()
                .map(c -> CkTokens.ident(c) + " = " + adaptDateTime(raw.get(c)))
                .collect(Collectors.joining(", "));
        String physical = distributed ? table + "_local" : table;
        String cluster = distributed ? " ON CLUSTER " + clusterRef : "";
        return "ALTER TABLE " + CkTokens.ident(physical) + cluster
                + " UPDATE " + set + " WHERE " + CkTokens.ident(keyCol) + " = "
                + adaptDateTime(raw.get(keyCol)) + " SETTINGS mutations_sync = 2;";
    }

    /**
     * 显式列名的标准批量 INSERT。CK 无事务，分批只为控制单条语句体积。
     *
     * <p>值一律用基准原文。基准里既有 {@code '字符串'}，也有 {@code NULL}、{@code now()}、
     * 裸数字 —— 统一当字符串重新加引号会把 {@code now()} 插成字面量 "now()"，
     * DateTime 列直接报 CANNOT_PARSE_DATETIME。
     *
     * <p>分布式现场必须带 {@code insert_distributed_sync = 1}：往 Distributed 路由表
     * 写入默认是异步分发，收敛循环紧接着的下一轮比对可能还看不到刚插的行，
     * 会把同一批行再插一遍 —— MergeTree 表上就是永久重复行。
     */
    private String renderInsert(String table, List<String> columns,
                                List<Map<String, String>> rows) {
        String cols = columns.stream().map(CkTokens::ident).collect(Collectors.joining(", "));
        String values = rows.stream()
                .map(r -> "(" + columns.stream()
                        .map(c -> {
                            String v = r.get(c);
                            // 原文缺失（理论上不会）才退回 NULL，保证不会插出裸文本
                            return v == null ? "NULL" : adaptDateTime(v);
                        })
                        .collect(Collectors.joining(", ")) + ")")
                .collect(Collectors.joining(",\n       "));
        String settings = distributed ? "\nSETTINGS insert_distributed_sync = 1" : "";
        return "INSERT INTO " + CkTokens.ident(table) + " (" + cols + ")"
                + settings + "\nVALUES " + values + ";";
    }

    /**
     * 把 CK 存不下的时间字面量转成它认得的形式。
     *
     * <p>基准里有 Java {@code ZonedDateTime.toString()} 直接写进 SQL 的值：
     * {@code '2026-01-25T08:10:30+08:00[Asia/Shanghai]'}（实测 2026-08-11 那份有 217 处）。
     * CK 的 DateTime 不带时区，这种写法一律报 Code 6 Cannot parse，
     * 那 4 张表的配置数据就永远下发不了。
     *
     * <p>转成 {@code '2026-01-25 08:10:30'} 是无损的 —— 时区信息在 CK 的 DateTime 里
     * 本来就无处安放，而现场全是 Asia/Shanghai，保留本地时间即可。
     * 除此之外一律按原文下发，不做任何加工。
     */
    static String adaptDateTime(String literal) {
        java.util.regex.Matcher m = ZONED.matcher(literal);
        if (!m.matches()) {
            return literal;
        }
        // 秒可能被省略：Java 在秒为 0 时输出 2024-01-01T08:00 而不是 08:00:00，
        // CK 的 DateTime 必须补满 HH:MM:SS
        String sec = m.group(3) == null ? ":00" : m.group(3);
        return "'" + m.group(1) + " " + m.group(2) + sec + "'";
    }

    private static final java.util.regex.Pattern ZONED = java.util.regex.Pattern.compile(
            "'(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2})(:\\d{2})?(?:\\.\\d+)?"
                    + "(?:Z|[+-]\\d{2}:?\\d{2})?(?:\\[[^\\]]*\\])?'");

    /** 只认显式 id；没有 id 不猜 ORDER BY，转人工。 */
    private String resolveIdColumn(SeedTable seed) {
        for (String c : seed.getColumns()) {
            if (c.equalsIgnoreCase("id")) {
                return c;
            }
        }
        return null;
    }
}
