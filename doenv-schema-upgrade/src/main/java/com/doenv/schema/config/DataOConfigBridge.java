package com.doenv.schema.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 DataO 的数据源配置桥接成 DoEnv 的配置。
 *
 * <p>现场部署形态：doenv jar 和 datao jar 放在同一个目录，共用外层那份
 * {@code application.properties}（Spring Boot 优先读 jar 同级的配置文件）。
 * 所以运维<b>不需要</b>为 DoEnv 单独写一份连接信息 —— 直接复用 DataO 已有的：
 *
 * <table border="1">
 *   <tr><th>DataO 现有 key</th><th>映射到</th></tr>
 *   <tr><td>{@code spring.datasource.jdbc-url}</td><td>{@code doenv.schema-upgrade.target.url}</td></tr>
 *   <tr><td>{@code spring.datasource.clickhouse.address}</td><td>{@code doenv.schema-upgrade.ck.url}</td></tr>
 * </table>
 *
 * <p>注意 DataO 用的是非标准 key：PG 是 {@code jdbc-url} 不是 {@code url}，
 * CK 是 {@code address} 不是 {@code url}，所以不能指望 Spring 自己认。
 *
 * <p>桥接<b>只补当前没值的键</b>，然后以最高优先级挂入：显式配了
 * {@code doenv.schema-upgrade.*} 的场合（命令行、外部配置）根本不会进桥接的 map，
 * 依然是显式的赢；而 jar 内若混进空串占位，也压不住桥接出来的真实值。
 * 且只在独立入口（{@code DoEnvApplication}）生效 —— 被 datao-main 引用时不桥接，
 * 模块复用宿主的 DataSource，不另建连接池。
 */
public class DataOConfigBridge implements EnvironmentPostProcessor {

    private static final String SOURCE_NAME = "doenvDataOBridge";

    /**
     * EnvironmentPostProcessor 在日志系统初始化之前就跑了，普通 Logger 打不出来。
     * Spring Boot 为此提供 DeferredLogFactory：日志先攒着，日志系统就绪后补打。
     */
    private final org.apache.commons.logging.Log log;

    public DataOConfigBridge(org.springframework.boot.logging.DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(DataOConfigBridge.class);
    }

    /** 独立运行入口的全限定名。写字符串而不是引类，避免 config 包反向依赖 standalone 包 */
    private static final String STANDALONE_MAIN = "com.doenv.schema.standalone.DoEnvApplication";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        // 只在独立运行时桥接。被 datao-main 以 pom 引入时，模块本来就复用宿主的
        // DataSource，桥接反而会让它照着同一个 url 另建一个连接池 —— 白占连接、
        // 还绕开了宿主对数据源的所有定制
        Class<?> main = app.getMainApplicationClass();
        if (main == null || !STANDALONE_MAIN.equals(main.getName())) {
            return;
        }

        // Spring Boot 只扫「进程工作目录」的 application.properties，不是 jar 所在目录。
        // 运维从别处执行 java -jar /opt/datao/doenv.jar 时，两者不是一回事，
        // DataO 那份配置根本没进 env，最后报「找不到 DataSource」——
        // 承诺的「放同级目录零参数运行」就落空了。这里主动补上 jar 同级那份。
        loadSideCarProperties(env);

        Map<String, Object> m = new LinkedHashMap<>();

        // ── PG ──
        String pgUrl = first(env, "spring.datasource.jdbc-url", "spring.datasource.url");
        if (pgUrl != null) {
            m.put("doenv.schema-upgrade.target.url", pgUrl);
            put(m, "doenv.schema-upgrade.target.username",
                    first(env, "spring.datasource.username"));
            put(m, "doenv.schema-upgrade.target.password",
                    first(env, "spring.datasource.password"));
            put(m, "doenv.schema-upgrade.target.driver-class-name",
                    first(env, "spring.datasource.driver-class-name"));
        }

        // ── CK ──
        String ckUrl = first(env, "spring.datasource.clickhouse.address",
                "spring.datasource.clickhouse.url",
                "spring.datasource.clickhouse.jdbc-url");
        if (ckUrl != null) {
            m.put("doenv.schema-upgrade.ck.url", withDatabase(ckUrl,
                    first(env, "spring.datasource.clickhouse.db")));
            put(m, "doenv.schema-upgrade.ck.username",
                    first(env, "spring.datasource.clickhouse.username"));
            put(m, "doenv.schema-upgrade.ck.password",
                    first(env, "spring.datasource.clickhouse.password"));
            put(m, "doenv.schema-upgrade.ck.driver-class-name",
                    first(env, "spring.datasource.clickhouse.driver-class-name"));
        }

        // 只补「当前确实没值」的键。已经显式配了的（命令行、外部配置）原样保留
        m.keySet().removeIf(k -> {
            String cur = env.getProperty(k);
            return cur != null && !cur.isBlank();
        });
        if (m.isEmpty()) {
            log.info("连接配置已由显式配置提供，或未发现 DataO 的 spring.datasource.*，不做桥接");
            return;
        }
        // 放最高优先级：只装了「本来就没值」的键，压不到任何真实配置，
        // 但压得住 jar 内可能存在的空占位（空串也是值，addLast 会输给它）
        env.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, m));
        log.info("已桥接 DataO 数据源配置：PG=" + mask(pgUrl) + "，CK=" + mask(ckUrl));

    }

    /**
     * 读 jar 同级目录的 {@code application.properties}（含 {@code config/} 子目录）。
     *
     * <p>Spring Boot 默认只找进程工作目录，而运维完全可能从别处执行
     * {@code java -jar /opt/datao/doenv.jar}。工作目录那份（如果有）优先级更高，
     * 这里用 {@code addLast} 挂在最后，只作为兜底，压不掉任何已有配置。
     */
    private void loadSideCarProperties(ConfigurableEnvironment env) {
        // 已经能读到 DataO 的连接配置就不必再找
        if (first(env, "spring.datasource.jdbc-url", "spring.datasource.url") != null) {
            return;
        }
        java.nio.file.Path jarDir = com.doenv.schema.service.BaselineLocator.jarDirectory();
        if (jarDir == null) {
            return;
        }
        for (String rel : new String[]{"application.properties", "config/application.properties"}) {
            java.nio.file.Path p = jarDir.resolve(rel);
            if (!java.nio.file.Files.isRegularFile(p)) {
                continue;
            }
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(p)) {
                java.util.Properties props = new java.util.Properties();
                // DataO 的配置文件是 UTF-8；Properties 默认按 ISO-8859-1 读会毁掉中文注释里的值
                props.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                Map<String, Object> src = new LinkedHashMap<>();
                props.forEach((k, v) -> src.put(String.valueOf(k), v));
                env.getPropertySources().addLast(
                        new MapPropertySource("doenvJarSideCar:" + p, src));
                log.info("已加载 jar 同级配置 " + p + "（" + src.size() + " 项）");
                return;
            } catch (Exception e) {
                log.warn("读取 " + p + " 失败，跳过：" + e.getMessage());
            }
        }
    }

    /**
     * CK 连接串补库名。
     *
     * <p>{@code address} 有时只写到端口（{@code jdbc:clickhouse://ip:8123}），库名单独放在
     * {@code .db} 里。而库名对本模块是生死攸关的 —— 连不带库名会落到 default，
     * 现场探测会把 503 张表全判成缺失。所以这里补齐。
     */
    public static String withDatabase(String url, String db) {
        if (db == null || db.isBlank()) {
            return url;
        }
        String body = url;
        String query = "";
        int q = body.indexOf('?');
        if (q >= 0) {
            query = body.substring(q);
            body = body.substring(0, q);
        }
        int scheme = body.indexOf("//");
        // 已经带了库名（"//host:port/db"）就不动
        if (scheme >= 0 && body.indexOf('/', scheme + 2) >= 0) {
            return url;
        }
        return body + "/" + db + query;
    }

    private static String first(ConfigurableEnvironment env, String... keys) {
        for (String k : keys) {
            String v = env.getProperty(k);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    /** 连接串可能带账号密码，日志里只留到库名 */
    private static String mask(String url) {
        if (url == null) {
            return "（未配置）";
        }
        int q = url.indexOf('?');
        return q > 0 ? url.substring(0, q) : url;
    }

    private static void put(Map<String, Object> m, String k, String v) {
        if (v != null) {
            m.put(k, v);
        }
    }
}
