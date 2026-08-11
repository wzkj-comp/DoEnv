package com.doenv.schema.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模块配置。前缀 {@code doenv.schema-upgrade}。
 */
@Data
@ConfigurationProperties(prefix = "doenv.schema-upgrade")
public class SchemaUpgradeProperties {

    /** 是否启用本模块。被 datao-main 引用时可用它整体关掉 */
    private boolean enabled = true;

    /**
     * 基准文件位置。支持 {@code classpath:} 与 {@code file:} 前缀，也支持普通路径。
     * 留空则按 {@link #baselineDir} 自动挑选文件名最大（即日期最新）的一份。
     */
    private String baselineFile;

    /** 自动发现基准文件的目录 */
    private String baselineDir = "classpath:install/pg-sql";

    /** 基准文件名前缀，用于自动发现 */
    private String baselineNamePrefix = "PG系统纯净库-";

    /**
     * 目标库 schema。留空则用连接的 {@code current_schema()} ——
     * 不硬编码 public，现场不一定是 public。
     */
    private String schema;

    /** 配置数据规则清单位置 */
    private String seedManifest = "classpath:doenv/seed-manifest.json";

    /** 报告缓存条数上限 */
    private int reportCacheSize = 8;

    /**
     * 独立运行时连接目标库用的数据源配置。被宿主引用时留空，
     * 直接复用宿主的主 DataSource。
     */
    private Target target = new Target();

    /** ClickHouse 侧配置。与 PG 分开，两边可以指向不同实例 */
    private Ck ck = new Ck();

    @Data
    public static class Ck {
        private String url;
        private String username;
        private String password;
        private String driverClassName = "ru.yandex.clickhouse.ClickHouseDriver";
        /** CK 基准目录。默认取 jar 内打包的那份，现场无需另配路径 */
        private String baselineDir = "classpath:install/clickhouse-sql";
        private String baselineNamePrefix = "CK单机";
        /** 显式指定 CK 基准文件，填了就不走自动发现 */
        private String baselineFile;

        public boolean isConfigured() {
            return url != null && !url.isBlank();
        }
    }

    @Data
    public static class Target {
        private String url;
        private String username;
        private String password;
        private String driverClassName = "org.postgresql.Driver";

        public boolean isConfigured() {
            return url != null && !url.isBlank();
        }
    }
}
