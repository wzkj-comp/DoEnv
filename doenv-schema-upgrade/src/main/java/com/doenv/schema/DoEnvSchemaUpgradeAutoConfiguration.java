package com.doenv.schema;

import com.doenv.schema.api.SchemaUpgradeController;
import com.doenv.schema.config.SchemaUpgradeProperties;
import com.doenv.schema.service.SchemaUpgradeService;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 自动装配。
 *
 * <p><b>为什么本模块的包名不在 {@code com.datao} 下</b>：datao-main 的
 * {@code @SpringBootApplication} 扫描 {@code com.datao} 与 {@code com.lab}。
 * 如果本模块落在这两个包里，宿主启动时会连同独立运行入口
 * {@code DoEnvApplication} 一起扫进去，造成嵌套的 SpringBootApplication。
 * 放在 {@code com.doenv} 下则完全靠本类接入，宿主侧零改动。
 *
 * <p>数据源解析：
 * <ul>
 *   <li>配了 {@code doenv.schema-upgrade.target.url} → 自建独立数据源（独立运行）</li>
 *   <li>没配 → 直接复用宿主的主 DataSource（被 datao-main 引用）</li>
 * </ul>
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnProperty(prefix = "doenv.schema-upgrade", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SchemaUpgradeProperties.class)
@Import(SchemaUpgradeController.class)
public class DoEnvSchemaUpgradeAutoConfiguration {

    /**
     * 模块专用的 JdbcTemplate。
     *
     * <p>刻意不叫 jdbcTemplate、也不标 @Primary —— 宿主里已经有一堆数据源，
     * 抢名字或抢 primary 会污染宿主的注入。
     */
    @Bean("doenvSchemaUpgradeJdbcTemplate")
    @ConditionalOnMissingBean(name = "doenvSchemaUpgradeJdbcTemplate")
    @org.springframework.context.annotation.Lazy
    public JdbcTemplate doenvSchemaUpgradeJdbcTemplate(SchemaUpgradeProperties props,
                                                       ObjectProvider<DataSource> hostDataSource) {
        DataSource ds;
        if (props.getTarget().isConfigured()) {
            HikariDataSource hikari = new HikariDataSource();
            hikari.setJdbcUrl(props.getTarget().getUrl());
            hikari.setUsername(props.getTarget().getUsername());
            hikari.setPassword(props.getTarget().getPassword());
            hikari.setDriverClassName(props.getTarget().getDriverClassName());
            hikari.setPoolName("doenv-schema-upgrade");
            hikari.setMaximumPoolSize(4);
            hikari.setReadOnly(false);
            ds = hikari;
        } else {
            ds = hostDataSource.getIfAvailable();
            if (ds == null) {
                throw new IllegalStateException(
                        "既没有配置 doenv.schema-upgrade.target.url，也找不到宿主的 DataSource");
            }
        }
        return new JdbcTemplate(ds);
    }

    // @Lazy：只跑 CK（--cli.db=ck）时不该因为没有 PG 连接就启动失败，
    // 所以整条 PG 链路推迟到真正被用到时才建
    @Bean
    @ConditionalOnMissingBean
    @org.springframework.context.annotation.Lazy
    public SchemaUpgradeService schemaUpgradeService(
            JdbcTemplate doenvSchemaUpgradeJdbcTemplate,
            SchemaUpgradeProperties props,
            ResourceLoader resourceLoader) {
        return new SchemaUpgradeService(doenvSchemaUpgradeJdbcTemplate, props, resourceLoader);
    }
}
