package com.doenv.schema.live;

import com.doenv.schema.baseline.CkTokens;
import com.doenv.schema.model.CkIndexModel;
import com.doenv.schema.model.CkTableModel;
import com.doenv.schema.model.ColumnModel;
import com.doenv.schema.model.SchemaModel;
import com.doenv.schema.model.TableModel;
import com.doenv.schema.model.ViewModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 读取 ClickHouse 现场结构。
 *
 * <p>分布式现场做<b>逻辑归一化</b>：把 {@code T_local} 的结构映射回逻辑名 {@code T}，
 * 路由表的存在性与列集单独记录。这样差异引擎完全不需要知道现场是单机还是分布式，
 * 拓扑只在渲染期重新出现 —— 否则每一类差异都要写两遍。
 */
public class CkLiveSchemaReader {

    /** 分布式现场里本地表的后缀 */
    public static final String LOCAL_SUFFIX = "_local";

    private final JdbcTemplate jdbc;

    public CkLiveSchemaReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String currentDatabase() {
        return jdbc.queryForObject("SELECT currentDatabase()", String.class);
    }

    public String version() {
        return jdbc.queryForObject("SELECT version()", String.class);
    }

    /**
     * 拓扑判定。<b>以事实为准</b>：看有没有 Distributed 引擎的表，
     * 不读 vao_system_opt 里的开关 —— 那个 key 在纯净库里根本没被 seed，取不到时恒为 false。
     */
    public CkTopology detectTopology() {
        Integer dist = jdbc.queryForObject(
                "SELECT count() FROM system.tables WHERE database = currentDatabase() "
                        + "AND engine = 'Distributed'", Integer.class);
        Integer localReplicated = jdbc.queryForObject(
                "SELECT count() FROM system.tables WHERE database = currentDatabase() "
                        + "AND engine LIKE 'Replicated%'", Integer.class);
        int d = dist == null ? 0 : dist;
        int l = localReplicated == null ? 0 : localReplicated;
        if (d > 0 && l > 0) {
            return CkTopology.DISTRIBUTED;
        }
        if (d == 0 && l == 0) {
            return CkTopology.SINGLE;
        }
        return CkTopology.UNKNOWN;
    }

    /** 读宏。分布式渲染要靠 {cluster}/{shard}/{replica}/{database} 展开 */
    public Map<String, String> macros() {
        Map<String, String> m = new LinkedHashMap<>();
        jdbc.query("SELECT macro, substitution FROM system.macros",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> m.put(rs.getString("macro"), rs.getString("substitution")));
        return m;
    }

    public List<String> clusterCandidates() {
        return jdbc.queryForList(
                "SELECT DISTINCT cluster FROM system.clusters ORDER BY cluster", String.class);
    }

    public SchemaModel read(CkTopology topology) {
        SchemaModel model = new SchemaModel();
        model.setSource("LIVE");
        model.setSchemaName(null);   // CK 不写库名

        Map<String, TableModel> byPhysical = readTables(model, topology);
        readColumns(byPhysical, topology, model);
        readIndexes(byPhysical);
        readViews(model);
        return model;
    }

    /**
     * 读视图。
     *
     * <p>{@code readTables} 刻意把视图排除在表之外（视图没有引擎/分区/排序键，
     * 混进表模型会让所有引擎级比对误判），所以这里单独读一遍。
     */
    private void readViews(SchemaModel model) {
        String sql = "SELECT name, engine, create_table_query FROM system.tables "
                + "WHERE database = currentDatabase() AND engine IN ('View','MaterializedView') "
                + "ORDER BY name";
        jdbc.query(sql, rs -> {
            ViewModel v = new ViewModel();
            v.setName(rs.getString("name"));
            v.setMaterialized("MaterializedView".equals(rs.getString("engine")));
            String q = rs.getString("create_table_query");
            int as = q == null ? -1 : com.doenv.schema.baseline.CkTokens.indexOfKeyword(q, "AS");
            v.setQuery(as >= 0 ? q.substring(as + 2).trim() : q);
            model.getViews().add(v);
        });
    }

    /**
     * 读跳数索引。
     *
     * <p>{@code system.data_skipping_indices} 里的 expr 是 CK 规范化过的形式，
     * 与基准 SQL 原文不会逐字相同，比对时必须走 {@link CkIndexModel#normalizedExpression()}。
     */
    private void readIndexes(Map<String, TableModel> byPhysical) {
        // 必须读 type_full 而不是 type：type 只有类型名（set），带参数的完整定义在
        // type_full（set(1000)）。读错列会让所有带参数的索引永远判为「定义不同」
        String sql = "SELECT table, name, expr, type_full, granularity "
                + "FROM system.data_skipping_indices WHERE database = currentDatabase() "
                + "ORDER BY table, name";
        jdbc.query(sql, rs -> {
            TableModel t = byPhysical.get(rs.getString("table"));
            if (t == null || t.getCk() == null) {
                return;
            }
            CkIndexModel idx = new CkIndexModel();
            idx.setName(rs.getString("name"));
            idx.setExpression(rs.getString("expr"));
            idx.setType(rs.getString("type_full"));
            idx.setGranularity(rs.getInt("granularity"));
            t.getCk().getIndexes().add(idx);
        });
    }

    private Map<String, TableModel> readTables(SchemaModel model, CkTopology topology) {
        Map<String, TableModel> byPhysical = new LinkedHashMap<>();
        Set<String> routeTables = new LinkedHashSet<>();

        String sql = "SELECT name, engine, engine_full, partition_key, sorting_key, "
                + "primary_key, sampling_key, comment "
                + "FROM system.tables WHERE database = currentDatabase() "
                + "AND engine NOT IN ('View','MaterializedView','LiveView','Dictionary') "
                + "ORDER BY name";

        List<Object[]> rows = new ArrayList<>();
        jdbc.query(sql, rs -> {
            rows.add(new Object[]{
                    rs.getString("name"), rs.getString("engine"), rs.getString("engine_full"),
                    rs.getString("partition_key"), rs.getString("sorting_key"),
                    rs.getString("primary_key"), rs.getString("sampling_key"),
                    rs.getString("comment")});
        });

        for (Object[] r : rows) {
            String name = (String) r[0];
            String engine = (String) r[1];
            if ("Distributed".equals(engine)) {
                routeTables.add(name);
                continue;   // 路由表不建模，只记存在性
            }
            // 分布式现场：本地表映射回逻辑名
            String logical = name;
            if (topology == CkTopology.DISTRIBUTED && name.endsWith(LOCAL_SUFFIX)) {
                logical = name.substring(0, name.length() - LOCAL_SUFFIX.length());
            }

            TableModel t = new TableModel();
            t.setName(logical);
            t.setComment(emptyToNull((String) r[7]));

            CkTableModel ck = new CkTableModel();
            // 去掉 Replicated 前缀，还原成逻辑引擎名
            String family = engine.startsWith("Replicated") ? engine.substring("Replicated".length()) : engine;
            ck.setEngineFamily(family);
            ck.setEngineArgs(replicatedArgsStripped((String) r[2], engine));
            ck.setPartitionBy(emptyToNull((String) r[3]));
            ck.setOrderBy(CkTokens.keyList(emptyToNull((String) r[4])));
            ck.setPrimaryKey(CkTokens.keyList(emptyToNull((String) r[5])));
            ck.setSampleBy(emptyToNull((String) r[6]));
            t.setCk(ck);

            model.addTable(t);
            byPhysical.put(name, t);
        }

        // 回填路由表存在性
        if (topology == CkTopology.DISTRIBUTED) {
            for (TableModel t : model.getTables()) {
                t.setDistRouteExists(routeTables.contains(t.getName()));
            }
        }
        return byPhysical;
    }

    /**
     * 从 engine_full 里剥掉 Replicated 的前两个参数（ZK 路径与副本名），
     * 只留下业务参数 —— 比如 ReplicatedReplacingMergeTree('/ck/..','{replica}', ver) 的 ver。
     */
    private List<String> replicatedArgsStripped(String engineFull, String engineName) {
        if (engineFull == null || engineName == null) {
            return List.of();
        }
        // 括号必须<b>紧跟</b>在引擎名后面才是引擎参数。
        // engine_full 长这样：ReplacingMergeTree ORDER BY (a, b) SETTINGS ...
        // 直接找第一个 '(' 会把 ORDER BY 的括号当成引擎参数，凭空造出差异。
        String rest = engineFull.trim();
        if (!rest.startsWith(engineName)) {
            return List.of();
        }
        rest = rest.substring(engineName.length());
        if (!rest.startsWith("(")) {
            return List.of();   // 引擎无参数
        }
        int offset = engineFull.length() - rest.length();
        int p = offset;
        int q = com.doenv.schema.baseline.SqlTokens.matchParen(engineFull, p);
        if (q <= p) {
            return List.of();
        }
        List<String> args = new ArrayList<>(
                com.doenv.schema.baseline.SqlTokens.splitTopLevel(engineFull.substring(p + 1, q), ','));
        boolean replicated = engineFull.startsWith("Replicated");
        if (replicated && args.size() >= 2) {
            args = args.subList(2, args.size());
        }
        return args.stream().map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private void readColumns(Map<String, TableModel> byPhysical, CkTopology topology,
                             SchemaModel model) {
        String sql = "SELECT table, name, type, comment, default_kind, default_expression, position "
                + "FROM system.columns WHERE database = currentDatabase() ORDER BY table, position";

        Map<String, Set<String>> routeCols = new LinkedHashMap<>();

        jdbc.query(sql, rs -> {
            String table = rs.getString("table");
            TableModel t = byPhysical.get(table);
            if (t == null) {
                // 可能是 Distributed 路由表的列，单独收集用于检测 local/route 列漂移
                if (topology == CkTopology.DISTRIBUTED) {
                    routeCols.computeIfAbsent(table, k -> new LinkedHashSet<>())
                            .add(rs.getString("name"));
                }
                return;
            }
            ColumnModel c = new ColumnModel();
            c.setName(rs.getString("name"));   // 大小写原样，CK 敏感
            c.setPosition(rs.getInt("position"));
            String type = rs.getString("type");
            c.setRawType(type);
            c.setNormType(CkTokens.normalizeType(type));
            c.setTypeFamily(CkTokens.familyOf(type));
            c.setNotNull(!type.toUpperCase().startsWith("NULLABLE("));
            c.setComment(emptyToNull(rs.getString("comment")));
            String dk = rs.getString("default_kind");
            if (dk != null && "DEFAULT".equalsIgnoreCase(dk)) {
                String de = rs.getString("default_expression");
                c.setDefaultRaw(de);
                c.setDefaultNorm(de);
            }
            t.addColumn(c);
        });

        if (topology == CkTopology.DISTRIBUTED) {
            for (TableModel t : model.getTables()) {
                Set<String> cols = routeCols.get(t.getName());
                if (cols != null) {
                    t.setRouteColumns(cols);
                }
            }
        }
    }

    /** 现场 vax_ 表数量，用于「疑似连错库」硬闸 */
    public int countVaxTables() {
        Integer n = jdbc.queryForObject(
                "SELECT count() FROM system.tables WHERE database = currentDatabase() "
                        + "AND name LIKE 'vax\\_%'", Integer.class);
        return n == null ? 0 : n;
    }

    public int countAllTables() {
        Integer n = jdbc.queryForObject(
                "SELECT count() FROM system.tables WHERE database = currentDatabase()", Integer.class);
        return n == null ? 0 : n;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
