# DoEnv

DataO 的 PostgreSQL + ClickHouse 结构与初始化数据升级工具。

- 现场使用说明：[DoEnv Jar使用文档.md](DoEnv%20Jar使用文档.md)
- 当前内置基准：`2026-09-29`
- 可执行包：`DoEnv20260929.jar`
- Java：21

构建命令：

```bash
./mvnw -pl doenv-schema-upgrade clean package
```

构建同时保留普通模块 JAR（供 Maven 引用），并在 `doenv-schema-upgrade/target/`
生成按纯净库日期命名的现场可执行 JAR。
