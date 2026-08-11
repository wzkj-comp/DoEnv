# DoEnv

DataO 的环境工具集。目前只有一个模块：`doenv-schema-upgrade`（数据库结构升级）。

## doenv-schema-upgrade

把现场的 PostgreSQL 和 ClickHouse **一次性升到纯净库基准**，不用再按顺序执行几百个增量 SQL 文件。

### 现场怎么用

把 jar 放到 **和 DataO 的 jar 同一个目录**，然后：

```bash
java -jar doenv-schema-upgrade-1.0.0-exec.jar
```

不需要任何参数、不需要额外的配置文件。原因：

- Spring Boot 优先读 jar 同级的 `application.properties`，所以它读到的是 **DataO 那份配置**；
- 启动时 `DataOConfigBridge` 把 DataO 的连接信息映射成本模块的配置，两边操作的必然是同一套库：

  | DataO 现有 key | 映射到 |
  | --- | --- |
  | `spring.datasource.jdbc-url` | `doenv.schema-upgrade.target.url` |
  | `spring.datasource.username` / `password` / `driver-class-name` | `…target.*` |
  | `spring.datasource.clickhouse.address`（不带库名时用 `.db` 补齐） | `doenv.schema-upgrade.ck.url` |
  | `spring.datasource.clickhouse.username` / `password` / `driver-class-name` | `…ck.*` |

  > DataO 用的是非标准 key（PG 是 `jdbc-url` 不是 `url`，CK 是 `address` 不是 `url`），所以必须显式映射。
  > 桥接只补「当前没值」的键 —— 显式配了 `doenv.schema-upgrade.*` 就以显式的为准。
  > 桥接**只在独立运行时生效**；被 datao-main 以 pom 引入时，模块复用宿主的 DataSource，不另建连接池。

- 两份基准 SQL（PG + CK 单机分区表）已经打进 jar，不用另外放文件。

### 换新基准

**不需要重新打包 jar**。在 jar 同级目录放一个 `install/` 就行：

```
/opt/datao/
├── datao.jar
├── doenv-schema-upgrade-1.0.0-exec.jar
├── application.properties
└── install/
    ├── pg-sql/PG系统纯净库-2026-09-01.sql
    └── clickhouse-sql/CK单机分区表纯净库-2026-09-01.sql
```

查找顺序：**jar 同级的 `install/`** → 进程工作目录的 `install/` → jar 内置的那份。
同一目录下有多个基准时，按文件名取最大的（文件名带日期，最大即最新），
所以放新文件不必删旧文件。日志里会打印实际用的是哪一份：

```
使用 jar 外部基准目录 /opt/datao/install/pg-sql
已加载基准 PG系统纯净库-2026-09-01.sql：171 张表 / ...
```

认的是 **jar 自身所在目录**而不是工作目录，所以 `java -jar /opt/datao/doenv.jar`
在任何路径下执行都能找到同一份基准。

跑完在当前目录生成 `doenv-report/`：

| 文件 | 内容 |
| --- | --- |
| `升级结果-<时间戳>.txt` | 第一部分＝执行的每条 SQL 明细（表名/动作/耗时/影响行数）；第二部分＝跳过的表和原因 |
| `升级SQL原文-<时间戳>.sql` | 上表按序号可检索的 SQL 全文 |

退出码：`0` 成功，`2` 有失败语句。

### 常用参数

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `--doenv.schema-upgrade.cli.mode` | `execute` | `compare` 只比对不改库；`dry-run` 执行后回滚（PG）；`execute` 真执行 |
| `--doenv.schema-upgrade.cli.db` | `both` | `pg` / `ck` / `both` |
| `--doenv.schema-upgrade.cli.levels` | `AUTO,CONFIRM` | 执行哪些档位的差异 |
| `--doenv.schema-upgrade.cli.out` | `./doenv-report` | 报告输出目录 |
| `--doenv.schema-upgrade.cli.maxRounds` | `8` | 自动收敛的轮次上限 |

先看会改什么、不落库：

```bash
java -jar doenv-schema-upgrade-1.0.0-exec.jar --doenv.schema-upgrade.cli.mode=compare
```

### 升级规则

**结构**：缺表补表、缺列补列、缺键补键、注释对齐。严格单向 —— 现场多出来的表/列**永远不会**生成 DROP。

**CK 的不可变属性**（ENGINE / PARTITION BY / ORDER BY / PRIMARY KEY）改不了：

- 表是空的 → 直接重建；
- 表有数据 → 跳过，并在结果文档第二部分列出表名和具体原因。

**配置数据**：

- PG 侧按 `seed-manifest.json` 定的策略走（补充 / 全量覆盖 / 按稳定键更新）。稳定键在现场重复时**整表跳过**并报出重复样例 —— 按不唯一的键更新会一次刷中多行、抹掉行间差异。
- CK 侧只有一条规则：**按主键补缺，已存在的一律不动**。空表就全量插入，有数据就只补主键不存在的行，从不更新、不删除。基准里的 `NULL` / `now()` / 裸数字按 SQL 原文下发，不会被当成字符串。

### 作为依赖引入

```xml
<dependency>
  <groupId>com.doenv</groupId>
  <artifactId>doenv-schema-upgrade</artifactId>
  <version>1.0.0</version>
</dependency>
```

主构件是普通 jar（可执行的那份带 `exec` classifier），依赖都标了 `optional`，不会传染给宿主。
引入后自动装配 `SchemaUpgradeService` 和 `/schemaUpgrade/*` 接口；CLI 不会启动（它的开关
`doenv.schema-upgrade.cli.mode` 只有独立入口才会设），宿主启动不会顺手改库。

### 构建

```bash
cd DoEnv && ./mvnw -pl doenv-schema-upgrade -am package
```

基准 SQL 从 `../datao-main/src/main/resources/install/` 打包进 jar。
在没有 datao-main 的环境里构建会跳过这一步，那样的 jar 需要用
`--doenv.schema-upgrade.baseline-dir` / `--doenv.schema-upgrade.ck.baseline-dir` 指外部目录。
