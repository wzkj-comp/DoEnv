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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ClickHouse 配置数据比对。
 *
 * <p>规则很简单，只有一条：<b>按主键补缺，已存在的一律不动</b>。
 * <ul>
 *   <li>表是空的 → 基准的行全部插入</li>
 *   <li>表有数据 → 逐行看主键在不在，不在才补；在就跳过，<b>不更新、不删除</b></li>
 * </ul>
 *
 * <p>之所以敢这么简单，是因为这 7 张表实测全部以 {@code id} 为排序键且 id 100% 唯一，
 * 不存在 PG 那边「稳定键不唯一会刷中多行」的问题。
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

    public CkSeedDiffEngine(JdbcTemplate jdbc, boolean distributed) {
        this.jdbc = jdbc;
        this.distributed = distributed;
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
            TableModel lt = live.findTable(table);
            if (lt == null) {
                out.add(DiffItem.of(DiffType.SEED_ROW_MISSING, DiffLevel.INFO, table, null)
                        .note("现场缺表，配置数据待建表后再下发"));
                continue;
            }
            String keyCol = resolveKeyColumn(seed, lt);
            if (keyCol == null) {
                out.add(DiffItem.of(DiffType.SEED_ROW_MISSING, DiffLevel.MANUAL, table, null)
                        .note("找不到可用主键（种子列里无 id、排序键也不在种子列中），无法安全补数据"));
                continue;
            }
            try {
                diffOneTable(seed, table, keyCol, out);
            } catch (RuntimeException e) {
                out.add(DiffItem.of(DiffType.SEED_ROW_MISSING, DiffLevel.MANUAL, table, null)
                        .note("配置数据比对失败：" + e.getMessage()));
            }
        }
        return out;
    }

    private void diffOneTable(SeedTable seed, String table, String keyCol, List<DiffItem> out) {
        // 路由表可以直接查（Distributed 会转发到本地表），读主键走逻辑名即可
        Set<String> liveKeys = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT DISTINCT " + CkTokens.ident(keyCol) + " FROM " + CkTokens.ident(table),
                String.class));

        // 按下标筛，才能取到与之对齐的 SQL 原始词法
        List<Map<String, String>> missing = new ArrayList<>();
        for (int i = 0; i < seed.getRowCount(); i++) {
            String k = seed.getRows().get(i).get(keyCol);
            if (k != null && !liveKeys.contains(k)) {
                missing.add(seed.rawRow(i));
            }
        }

        if (missing.isEmpty()) {
            out.add(DiffItem.of(DiffType.SEED_ROW_DIFF, DiffLevel.INFO, table, null)
                    .note("配置数据已齐（基准 " + seed.getRowCount() + " 行，现场已有 "
                            + liveKeys.size() + " 个 " + keyCol + "），无需补充"));
            return;
        }

        DiffItem d = DiffItem.of(DiffType.SEED_ROW_MISSING, DiffLevel.AUTO, table, null);
        d.setBaselineDef("基准 " + seed.getRowCount() + " 行");
        d.setLiveDef(liveKeys.isEmpty()
                ? "现场空表，全量插入 " + missing.size() + " 行"
                : "现场已有 " + liveKeys.size() + " 行，缺 " + missing.size() + " 行");
        d.note("按 " + keyCol + " 补缺行；已存在的行不更新、不删除");
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

    /**
     * 定主键列。优先用种子里的 id，其次用表的排序键（要求是单列且在种子列里）。
     * 都没有就返回 null，转人工 —— 宁可不做，也不能按不确定的键去插。
     */
    private String resolveKeyColumn(SeedTable seed, TableModel lt) {
        for (String c : seed.getColumns()) {
            if (c.equalsIgnoreCase("id")) {
                return c;
            }
        }
        if (lt.getCk() != null && lt.getCk().getOrderBy().size() == 1) {
            String ob = lt.getCk().getOrderBy().get(0);
            for (String c : seed.getColumns()) {
                if (c.equalsIgnoreCase(ob)) {
                    return c;
                }
            }
        }
        return null;
    }
}
