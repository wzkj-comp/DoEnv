package com.doenv.schema.standalone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 独立运行入口。
 *
 * <p>只在 {@code java -jar doenv-schema-upgrade-*-exec.jar} 时生效。
 * 被 datao-main 以 pom 依赖引入时，宿主的组件扫描范围是 com.datao / com.lab，
 * 扫不到本类，所以不会产生嵌套的 SpringBootApplication。
 *
 * <p>刻意只扫 {@code com.doenv.schema.standalone} 这一个包：
 * 业务 bean 全部走自动装配注册，两种形态下的装配路径完全一致，
 * 避免「独立能跑、嵌进宿主就少 bean」这类只在集成时才暴露的问题。
 */
@SpringBootApplication(scanBasePackages = "com.doenv.schema.standalone")
public class DoEnvApplication {

    /**
     * 独立运行的缺省行为：{@code java -jar} 不带任何参数，就把 PG + CK 一起升到基准。
     *
     * <p>这些默认值<b>只在本入口生效</b>。CLI 的开关是
     * {@code doenv.schema-upgrade.cli.mode}，被 datao-main 依赖时没人设它，
     * {@link DoEnvCliRunner} 上的 {@code @ConditionalOnProperty} 就不成立 ——
     * 宿主启动不会顺手升级数据库。
     *
     * <p>用 {@code setDefaultProperties} 而不是写死：优先级最低，
     * 命令行 {@code --doenv.schema-upgrade.cli.mode=compare} 之类照样能覆盖。
     */
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(DoEnvApplication.class);
        app.setDefaultProperties(java.util.Map.of(
                "doenv.schema-upgrade.cli.mode", "execute",
                "doenv.schema-upgrade.cli.db", "both",
                "doenv.schema-upgrade.cli.levels", "AUTO,CONFIRM",
                // 一次性任务，不用起 Tomcat：现场共用 DataO 那份配置，
                // 里面的 server.port=8083 正被 DataO 占着，起 web 必然撞端口
                "spring.main.web-application-type", "none",
                // 现场配置里必然有 spring.datasource.*，但 DataO 用的是非标准的
                // jdbc-url，Boot 的 DataSourceAutoConfiguration 认不出来会报错。
                // 本模块的连接一律自己建，不需要它
                "spring.autoconfigure.exclude",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "logging.level.com.doenv", "INFO"));
        app.run(args);
    }
}
