# DoEnv Jar 使用文档

## 1. 当前版本

| 项目 | 内容 |
| --- | --- |
| JAR | `DoEnv20260929.jar` |
| Java | 21 |
| PG 基准 | `PG系统纯净库-2026-09-29.sql` |
| CK 基准 | `CK单机分区表纯净库-2026-09-29.sql` |
| 默认范围 | PostgreSQL + ClickHouse |
| 默认行为 | 比对后执行升级，直到收敛 |

JAR 内置的 PG、CK 基准来自同一日期的 `datao_pure`。CK 分布式现场由程序根据单机基准转换处理，不需要另带分布式 SQL 文件。

为保证本版本严格使用 2026-09-29 基准，部署目录不要保留旧版本遗留的 `install/pg-sql` 或 `install/clickhouse-sql` 外部基准目录；程序发现外部基准时会优先使用外部文件。

## 2. 部署方式

支持下面两种方式，配置文件名都必须是 `application.properties`。

### 方式一：DoEnv 使用自己的配置文件

```text
部署目录/
├── DoEnv20260929.jar
└── application.properties
```

将本项目提供的 `application.properties` 模板复制到 JAR 同级目录，填写真实的 PG、CK 地址、库名、账号和密码。

### 方式二：与 DataO 共用配置文件

```text
DataO部署目录/
├── datao.jar
├── DoEnv20260929.jar
└── application.properties
```

DoEnv 可直接读取 DataO 已有的同级配置文件，无需增加 DoEnv 专用配置项。即使从其他目录执行绝对路径形式的 `java -jar`，仍会按 JAR 所在目录查找配置文件。

如果进程工作目录和 JAR 同级目录各有一份配置，进程工作目录中由 Spring Boot 加载的配置优先。正式部署建议只保留一份，避免连接错库。

## 3. 启动

```bash
java -jar DoEnv20260929.jar
```

启动后依次升级 PG 和 CK。执行结束后在当前工作目录的 `doenv-report/` 固定生成四个文件（下一次运行覆盖上一份）：

- `执行结果汇总.txt`：按成功/失败分别汇总表、列、索引、约束、类型及数据影响；
- `sql-success.txt`：真正执行成功的 SQL；
- `sql-failed.txt`：执行失败或因同组失败而回滚的 SQL，包含失败原因；
- `待审核sql.txt`：MANUAL、执行后仍未收敛以及被前置检查拦下的 SQL。

汇总文件优先展示新表、新列、删列、列描述、列改名、索引、约束、类型，以及新增/删除数据行数和更新值数量；SQL 原文分别进入后三个明细文件。

退出码 `0` 表示执行成功；出现失败语句时返回非零退出码。一项失败会登记日志并继续处理其他差异项，不会因为单表失败直接放弃全部流程。

## 4. 结构更新范围

DoEnv 会处理：

- 缺少的表、列、索引、PG 约束、PG 序列和视图；
- PG 现场多出、纯净库不存在的列：直接删除，使列集合与纯净库强一致，列顺序不参与比对；
- `rename-manifest.json` 明确登记的 PG、CK 字段改名：优先执行 `RENAME COLUMN`，保留原列数据；
- 表注释、列注释；
- 可以安全修改的字段类型、默认值和 NOT NULL；
- CK 的引擎、分区键、排序键或主键不一致时：空表允许重建，有数据的表跳过并写明原因。

PG 删除多余列时使用 `DROP COLUMN IF EXISTS`，不使用 `CASCADE`。如果该列被视图等外部对象依赖，PostgreSQL 会拒绝本项，本项回滚并登记失败，其他升级项继续执行。

DoEnv 不删除现场多出的表、索引、约束或数据；ClickHouse 多余列也仍然只报告不删除。现场比基准更严格的 NOT NULL 不会移除。

### 字段改名清单

字段改名不能只靠结构猜测，必须登记在 JAR 内的 `doenv/rename-manifest.json`：

```json
{
  "pg": [
    {"table": "vao_demo", "from": "old_name", "to": "new_name", "reason": "业务字段改名"}
  ],
  "ck": [
    {"table": "vax_demo", "from": "old_name", "to": "new_name", "reason": "业务字段改名"}
  ]
}
```

处理顺序和安全规则：

1. 纯净库有 `to`、现场只有 `from`：执行改名，不走“新增新列 + 删除旧列”；
2. 现场 `from` 和 `to` 同时存在：转人工并保留两列，避免覆盖数据；
3. 清单的目标列不在纯净库：转人工并保留旧列；
4. PG 使用带存在性判断的 `DO` 块，重复执行幂等；
5. CK 分布式版本会对本地表和路由表成对改名；
6. 清单存在空字段、重复来源或重复目标时，生成物自检阻断执行。

新增改名映射属于升级语义变化，需要修改清单并重新打包 JAR；普通新增表、列、索引仍只需更换纯净库基准。

## 5. 涉及数据更新

### 5.1 PostgreSQL（当前 37 张）

#### 强一致重建（4 张）

- `vao_entitytype`
- `vao_entitytype_property`
- `vao_recordtype`
- `vao_recordtype_column`

仅在行集合或 `name`、`icon`、`ord`、`tablename` 等关键列存在差异时重建。`vao_entitytype.count`、`vao_recordtype.count` 会在重建前保存并在重建后回填；基准新增行的 `count` 归零。

#### 按 `entitytypeid` 补缺并更新（当前 1 张，另预留 1 张）

- `vao_case_table_field_conf`：当前纯净库有初始化数据；
- `vao_entity_chart_conf`：当前纯净库没有初始化数据，未来加入后自动应用同一规则。

没有对应 `entitytypeid` 时 INSERT；已有时更新差异列，但不覆盖 `creatorid`、`createdtime`、`lastupdatorid`、`lastupdatedtime`。

#### 只补缺行、不更新（3 张）

- `vao_system_opt`：物理表没有 `id`，按实际唯一标识 `key` 判断；
- `vao_user`：按 `id`；
- `vao_usergroup`：按 `id`。

#### 默认按 `id` 补缺并更新（29 张）

- `vao_archive_related_entitytype`
- `vao_archive_related_recordtype`
- `vao_archive_related_statistics`
- `vao_category`
- `vao_connector`
- `vao_datax2datao`
- `vao_datax2datao_tablemapping`
- `vao_dictionary_data`
- `vao_dictionary_table`
- `vao_display_options`
- `vao_expand_logic`
- `vao_expand_logic_parameter`
- `vao_expand_menu`
- `vao_fxq_module_authorization`
- `vao_holiday_config`
- `vao_import_entry_conf`
- `vao_lab_verify_config_category`
- `vao_lab_verify_config_item`
- `vao_lab_version`
- `vao_linktype`
- `vao_link_model`
- `vao_one_step_import_conf`
- `vao_recordtype_import_template`
- `vao_recordtype_mapping_dictionary`
- `vao_record_type_import_strategy`
- `vao_region_coordinate`
- `vao_template`
- `vao_video`
- `vao_video_folder`

缺少基准 `id` 时 INSERT；已有 `id` 时把差异列更新为纯净库值，但跳过四个审计字段。现场多出的 `id` 不删除。后续纯净库新增带初始化数据的普通表，也自动使用该默认规则；如果没有 `id`，会进入待审核而不是生成错误 SQL。

### 5.2 ClickHouse（7 张）

| 表 | 基准原始行数 | 处理方式 |
| --- | ---: | --- |
| `dx_authorization_relationship` | 198 | 按 `id` 补缺 |
| `dx_clean_keyword_conf` | 104 | 按 `id` 补缺 |
| `dx_user_role` | 17 | 按 `id` 补缺 |
| `dx_lab` | 1 | 按 `id` 补缺 |
| `dx_lab_Member` | 1 | 按 `id` 补缺 |
| `dx_user` | 1 | 按 `id` 补缺 |
| `dx_user_role_relationship` | 1 | 按 `id` 补缺 |

CK 配置数据合计 323 个原始 tuple。空表补全部；有数据时只补不存在的 `id`，已有行不更新，现场独有行不删除。

这 7 张表是已经明确登记的历史规则，因此继续保持“只补不改”。后续 CK 纯净库出现新的带数据表时：

- 有 `id`：按 `id` 补缺；已有 `id` 的其他字段有差异时更新；
- UPDATE 跳过 `CreatorID`、`CreatedTime`、`LastUpdatorID`、`LastUpdatedTime`（大小写不敏感）；
- 没有 `id`：不 INSERT、不 UPDATE，SQL 进入 `待审核sql.txt`，等待人工指定稳定键；
- 分布式环境更新 `_local` 表并使用 `ON CLUSTER`，不会对 Distributed 路由表执行 mutation。

## 6. 不涉及数据更新

“不涉及数据更新”不等于完全不处理：这些表仍会执行缺表、缺列、缺索引、注释及安全类型差异等结构升级，只是不执行 `INSERT`、`UPDATE` 或配置数据重建。

### 6.1 其他表

- PG：除第 5.1 节当前 37 张表外，其余 133 张当前纯净库没有初始化数据，因此只处理结构。`vao_entity_chart_conf` 已预留数据规则，但当前不会产生数据 SQL。
- CK：除第 5.2 节 7 张表外，其余 522 张表以及视图只处理结构，不执行数据更新。

这个分类以 JAR 内置的 2026-09-29 纯净库和数据规则为准。后续 PG、CK 纯净库新增普通表、列、索引、注释或带 `id` 的初始化数据时，更新基准即可；新数据表会自动按 `id` 补缺并更新、排除四个审计字段。只有无 `id` 表需要指定其他稳定键，或新增只补不更新、强一致重建、字段改名等特殊语义时，才需要修改清单并重新打包。

## 7. 执行前后检查

执行前：

1. 备份 PG、CK；
2. 确认配置中的库名是现场业务库，不是 `default`、`postgres` 或纯净库；
3. 停止会修改四张强一致元数据表的后台配置操作；
4. 确保 Java 21 可用。

执行后：

1. 查看控制台退出码；
2. 打开 `执行结果汇总.txt`；
3. 重点检查 `sql-failed.txt` 和 `待审核sql.txt`；
4. 再启动或验证 DataO 服务。
