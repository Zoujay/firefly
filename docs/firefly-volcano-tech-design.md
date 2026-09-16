# Firefly Volcano 模块最小技术设计

> 状态：Implementation Ready（最小 MVP）
> 版本：v2.0-minimal
> 日期：2026-09-16
> 目标代码库：Firefly（Java 25、Spring Boot 3.5、Maven 多模块）

## 1. 背景与范围

当前仓库没有独立的 `firefly-volcano` 模块，已有火山相关代码只保存和传递明文 AK/SK，不调用
TOS/ECS OpenAPI。本次只实现 Volcano 部署最小闭环：

1. 凭据加密保存；
2. TOS 制品列举、元数据查询和预签名 GET；
3. ECS 云助手 `RunCommand` 部署；
4. 手动执行 Pipeline 时选择具体制品。

### 1.1 本次必须实现

- 新建 `firefly-volcano` Maven 模块和 Spring Boot 自动装配。
- Pipeline 级 Volcano Connection，AK/SK 使用 AES-256-GCM 加密落库。
- TOS 对象列表、`HeadObject`、预签名 GET URL。
- ECS 实例列表、云助手 Agent 状态检查、`RunCommand`、`DescribeInvocations`。
- Pipeline Plugin 类型 `VOLCANO_DEPLOY`。
- 手动执行时，从 Job 配置的 TOS Prefix 当前层选择最近 10 个制品之一。
- 后端对选中对象执行一次 `HeadObject`，生成不可变制品快照。
- ECS 从预签名 URL 直拉制品，校验 SHA-256，完成 `TAR_GZ`/`ZIP`/`FILE` 准备后执行用户部署脚本。
- 运行期只轮询云助手状态，不做自动重试。

### 1.2 明确不做

- 不自动重试、不自动恢复、不自动重派部署。
- 不提供 Pipeline Retry、部署停止、取消或回滚。
- 不实现 TOS 结果对象、结果 Bucket、TOS 事件通知、结果 Kafka、Result Inbox、Deadline Reconciler。
- 不实现健康检查脚本、`rollbackScript`、`CUSTOM_FULL_SCRIPT`。
- 不实现 Firefly 本地流式下载/大文件断点续传。
- 不实现 `STS_SESSION`、`STS_ASSUME_ROLE`、凭据轮换和自动刷新。
- 不实现全仓库 NOT NULL 迁移；只保证 Volcano 新表字段全部 `NOT NULL`。
- 不支持 Windows ECS、多实例滚动发布、自动触发。
- 不承诺停止已派发的 Once 命令。

## 2. 核心设计决策

### 2.1 模块分层

`firefly-volcano` 只放火山协议适配和 Spring Boot 自动装配，不依赖 `firefly-app`；
数据库、Pipeline、Plugin、任务调度和管理 API 放在 `firefly-app`。该边界与现有
`firefly-github` 保持一致。

### 2.2 凭据加密，不嵌入 Pipeline JSON

Pipeline 创建时建立 Pipeline 级 Volcano Binding。Connection 密文只保存 AK/SK，
Pipeline、Job 的 `plugin_raw`、Kafka 消息和查询响应只保存 Connection 引用或脱敏信息。

### 2.3 ECS 直拉 TOS 制品

Firefly 不下载制品后再上传 ECS，而是：

1. Firefly 用 Connection 的 AK/SK 调 `HeadObject` 锁定版本；
2. Firefly 生成短期、只允许 `GET` 指定对象的预签名 URL；
3. Firefly 调 `RunCommand` 执行可信 Bootstrap；
4. ECS 在本地下载、校验、解压和部署。

### 2.4 使用 `RunCommand` + 状态轮询，不引入 Command Revision

最小版本不创建和回收云端自定义命令，直接使用 `RunCommand` 提交渲染后的 Bootstrap。
每次部署都有自己的 `invocation_id`，后台 Scheduler 调 `DescribeInvocations` 直到终态。

`RunCommand` 允许提交任意命令内容，IAM 无法限制到某个已持久化命令版本。本设计接受这一
取舍：脚本只能由部署管理员配置，所有脚本修改、审批和运行 Hash 必须写审计；如果后续要
收紧 IAM，再切换为 `CreateCommand` + `InvokeCommand`。

### 2.5 手动选择制品

`VOLCANO_DEPLOY` Job 只保存制品范围：`Region + Bucket + Prefix`。具体对象只能在
手动执行时选择，后端对选中对象重新 `HeadObject` 并生成快照，配置层不保存具体 Key。

### 2.6 无自动重试

任何失败都只写终态：

- TOS/ECS 调用失败：部署失败；
- `RunCommand` 失败：部署失败；
- 云助手终态非 0：部署失败；
- 超时无法确认：标记 `UNKNOWN`，人工检查；
- 用户要重来：重新发起一次手动执行。

系统不自动重发 `RunCommand`，不自动创建新 Attempt，不自动重放任何消息。

## 3. 总体架构

```mermaid
flowchart LR
    UI["Firefly 管理端"] --> API["Volcano 管理 API"]
    PIPE["Pipeline / VOLCANO_DEPLOY"] --> DS["Deployment Service"]
    PIPE --> BIND["Pipeline Volcano Binding"]
    BIND --> CS
    API --> CS["Connection Service"]
    API --> OS["Object Service"]
    CS --> VAULT["AES-256-GCM 加密存储"]
    OS --> TOSC["TOS Client"]
    DS --> TOSC
    DS --> ECSC["ECS Cloud Assistant Client"]
    TOSC --> TOS["Volcengine TOS"]
    ECSC --> ECSAPI["Volcengine ECS OpenAPI"]
    ECSAPI --> AGENT["ECS Cloud Assistant Agent"]
    AGENT -->|GET 预签名 URL| TOS
    AGENT --> HOST["Release Directory + 用户部署脚本"]
    SCHED["Invocation Polling Scheduler"] --> ECSC
    SCHED --> DB["volcano_deploy_build"]
```

## 4. Maven 模块

根 `pom.xml` 增加：

```xml
<modules>
    <module>firefly-github</module>
    <module>firefly-volcano</module>
    <module>firefly-app</module>
</modules>
```

`firefly-app/pom.xml` 增加对 `firefly-volcano` 的依赖。

`firefly-volcano/pom.xml`：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.volcengine</groupId>
            <artifactId>volcengine-java-sdk-bom</artifactId>
            <version>${volcengine.openapi.sdk.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>com.volcengine</groupId>
        <artifactId>ve-tos-java-sdk</artifactId>
        <version>${volcengine.tos.sdk.version}</version>
    </dependency>
    <dependency>
        <groupId>com.volcengine</groupId>
        <artifactId>volcengine-java-sdk-ecs</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-autoconfigure</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

包结构：

```text
firefly-volcano/src/main/java/firefly/volcano
├── api
│   ├── VolcanoObjectStorageClient.java
│   └── VolcanoEcsCommandClient.java
├── auth
│   └── VolcanoCredentials.java
├── config
│   ├── FireflyVolcanoAutoConfiguration.java
│   └── VolcanoClientProperties.java
├── ecs
│   └── VolcengineEcsCommandClient.java
├── tos
│   └── VolcengineTosObjectStorageClient.java
└── error
    ├── VolcanoIntegrationException.java
    └── VolcanoErrorCode.java
```

## 5. 公共接口

### 5.1 对象存储

```java
public interface VolcanoObjectStorageClient extends AutoCloseable {
    ObjectPage listObjects(ListObjectsCommand command);

    ObjectMetadata headObject(HeadObjectCommand command);

    PresignedDownload presignGet(PresignGetCommand command);
}
```

- `ListObjectsCommand`：bucket、prefix、delimiter、maxKeys、continuationToken。
- `HeadObjectCommand`：bucket、key、可选 versionId。
- `PresignGetCommand`：bucket、key、versionId/etag、过期时间。
- `PresignedDownload.toString()` 只输出过期时间，不输出 URL。
- 对象 Key 是不透明字符串，禁止转成本地路径或使用 `Path.resolve(key)`。

### 5.2 ECS 云助手

```java
public interface VolcanoEcsCommandClient {
    InstancePage listInstances(ListInstancesCommand command);

    EcsInstance describeInstance(DescribeInstanceCommand command);

    CloudAssistantStatus describeCloudAssistant(DescribeCloudAssistantCommand command);

    RunCommandResult runCommand(RunCommand command);

    InvocationStatus describeInvocation(DescribeInvocationCommand command);
}
```

- `RunCommand` 输入：InstanceId、命令正文、工作目录、`runAsUser`、超时、InvocationName、
  Tag。
- `DescribeInvocations` 响应使用白名单映射，只取 InvocationId、InvocationStatus、ExitCode、
  Output 等安全字段，必须丢弃 `CommandContent`、`Parameters` 等可能包含预签名 URL 的字段。
- 不提供 `StopInvocation`、`CreateCommand`、`DeleteCommand`。

### 5.3 错误转换

SDK 异常统一转成 `VolcanoIntegrationException`，包含 Firefly 错误码、HTTP 状态、Provider
错误码、RequestId 和是否可重试标记。日志不输出 AK、SK、Authorization、预签名 URL、
完整命令正文中的 URL 或命令参数。

## 6. 凭据管理

### 6.1 Connection 模型

```sql
CREATE TABLE `firefly`.`volcano_connection`
(
    `id`                      BIGINT(20) NOT NULL AUTO_INCREMENT,
    `public_id`               VARCHAR(64) NOT NULL,
    `connection_name`         VARCHAR(128) NOT NULL,
    `credential_type`         VARCHAR(32) NOT NULL DEFAULT 'STATIC_AK_SK',
    `credential_ciphertext`   TEXT NOT NULL,
    `credential_nonce`        VARBINARY(32) NOT NULL,
    `encryption_key_version`  VARCHAR(64) NOT NULL,
    `default_region`          VARCHAR(64) NOT NULL,
    `tos_endpoint`            VARCHAR(512) NOT NULL DEFAULT '',
    `ecs_endpoint`            VARCHAR(512) NOT NULL DEFAULT '',
    `status`                  VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    `created_at`              DATETIME(6) NOT NULL,
    `updated_at`              DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_volcano_connection_public` (`public_id`),
    UNIQUE INDEX `uidx_volcano_connection_name` (`connection_name`)
);
```

`credential_ciphertext` 明文结构：

```json
{
  "schemaVersion": 1,
  "accessKeyId": "AKLT...",
  "secretAccessKey": "..."
}
```

### 6.2 加密要求

- AES-256-GCM，每次写入生成新的 12 字节随机 Nonce。
- 环境变量 `VOLCANO_ENCRYPTION_KEY` 是 Base64 编码的 32 字节密钥。
- AAD 绑定 `public_id + "volcano-credential" + encryption_key_version`。
- API 返回 Connection 时只返回 `accessKeyIdMask`，例如 `AKLT****82KD`。
- 新建/修改 DTO 禁止 Lombok `@Data`/`@ToString`，显式实现脱敏 `toString()`。
- 本次不做凭据轮换、STS/AssumeRole、Connection 自动校验；后续扩展时新增字段或新表。

### 6.3 Pipeline Binding

```sql
CREATE TABLE `firefly`.`volcano_pipeline_binding`
(
    `id`             BIGINT(20) NOT NULL AUTO_INCREMENT,
    `pipeline_id`    BIGINT(20) NOT NULL,
    `connection_id`  BIGINT(20) NOT NULL,
    `default_region` VARCHAR(64) NOT NULL,
    `created_at`     DATETIME(6) NOT NULL,
    `updated_at`     DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_volcano_binding_pipeline` (`pipeline_id`),
    INDEX `idx_volcano_binding_connection` (`connection_id`)
);
```

Pipeline 查询只返回 Connection 引用、名称、`credentialType`、脱敏 AK 和默认 Region。

## 7. TOS 制品能力

### 7.1 对象列表和元数据

管理 API：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/volcano/connections/{id}/tos/objects` | Bucket/Prefix 分页列举 |
| `GET` | `/api/volcano/connections/{id}/tos/object-metadata` | 查询单个对象元数据 |

Query Parameter 传递 `bucket`、`key`、`region`、`versionId`。

### 7.2 手动选择“最近 10 个制品”

```http
GET /api/volcano/pipelines/{pipelineId}/jobs/{jobUuid}/artifacts/recent?limit=10
```

规则：

- Connection 从 Pipeline Binding 解析，Region/Bucket/Prefix 从 Job 配置解析。
- `delimiter=/`，只返回 Prefix 当前层对象。
- 排除目录占位对象、0 字节对象和不支持的扩展名。
- 按 `LastModified` 倒序、Key 正序排序，最多返回 10 条。
- 返回 `key`、`size`、`lastModified`、`etag`、`displayName`；此阶段不返回 SHA-256。
- 后端最多扫描 10000 个对象，超出返回 `ARTIFACT_SCAN_LIMIT_EXCEEDED`，不返回不完整结果。
- 前端只回传选中 `key`、可选 `versionId` 和 `handling`，后端不接受 ETag、大小、SHA 等客户端快照。

### 7.3 选中对象快照

用户提交手动执行后，后端按 Job 配置重新校验 Key 仍在 Prefix 当前层内，然后：

1. 调用 `HeadObject` 获取 VersionId、ETag、大小、LastModified 和自定义元数据；
2. 从 `x-tos-meta-firefly-sha256` 读取 SHA-256；
3. SHA-256 必须存在且为 64 位小写十六进制，否则拒绝部署；
4. 生成不可变快照并写入 `volcano_artifact_selection`。

### 7.4 预签名 GET

- 制品 Bucket 保持私有。
- TTL 默认 10 分钟，覆盖云助手排队和 ECS 下载时间，不需要覆盖整个部署命令时长。
- 同时设置 VersionId 或 `If-Match`，避免同名对象在部署中被覆盖。
- 预签名 URL 只出现在渲染后的命令中，不落 Firefly 日志、数据库、Kafka 或 API 响应。

## 8. ECS 部署

### 8.1 前置条件

- ECS 处于 `RUNNING`，已安装且在线运行云助手 Agent。
- 目标机存在 allowlist 内的 `runAsUser`，本次默认 `firefly-deploy`，默认禁止 `root`。
- 目标机安装 `curl`、`sha256sum`、`flock`，按包类型安装 `tar` 或 `unzip`。
- ECS 能访问制品 TOS Endpoint，优先使用同 Region 内网或 VPC Endpoint。
- 目标机时钟同步，命令超时使用相对时长。

### 8.2 实例选择

```http
GET /api/volcano/pipelines/{pipelineId}/ecs/instances
    ?region=cn-beijing
    &projectName=production
    &status=RUNNING
    &pageNumber=1
    &pageSize=50
```

后端调用 `DescribeInstances`，再批量调用 `DescribeCloudAssistantStatus`，只展示：

- `RUNNING` 的 Linux 实例；
- 云助手 Agent 在线；
- InstanceId、Project、VPC/Tag 满足管理员 allowlist。

保存目标使用 `Region + InstanceId`，私网 IP、VPC、Zone 只做审计快照和执行前一致性检查。

### 8.3 Plugin 配置

`PluginType` 增加 `VOLCANO_DEPLOY`。配置示例：

```json
{
  "artifactSource": {
    "tosUri": "tos://firefly-artifacts/order-service/releases/",
    "region": "cn-beijing",
    "bucket": "firefly-artifacts",
    "prefix": "order-service/releases/",
    "currentLevelOnly": true
  },
  "target": {
    "region": "cn-beijing",
    "instanceId": "i-yc...",
    "instanceNameSnapshot": "order-prod-01",
    "privateIpSnapshot": "10.0.12.34",
    "vpcIdSnapshot": "vpc-xxx",
    "zoneIdSnapshot": "cn-beijing-a"
  },
  "destination": {
    "applicationName": "order-service",
    "deployRoot": "/opt/firefly/apps",
    "runAsUser": "firefly-deploy"
  },
  "execution": {
    "deployScript": "sudo -n systemctl restart order-service.service",
    "commandTimeoutSeconds": 900
  }
}
```

- Connection 只能从 Pipeline Binding 解析。
- Job 配置不保存具体 Object Key、VersionId、ETag、大小或 SHA。
- 采用唯一目录布局：`<deployRoot>/<applicationName>/releases/<deploymentId>/`。
- `deployRoot` 必须位于管理员配置的 `allowed-deploy-roots` 内。
- `runAsUser` 必须来自 `allowed-run-as-users`。
- 路径校验禁止 `..`、NUL、控制字符和符号链接逃逸。
- `deployScript` 必填，UTF-8、无 NUL。
- 不支持 `FIXED_FILE`、`rollbackScript`、健康检查和 `CUSTOM_FULL_SCRIPT`。

### 8.4 Bootstrap 执行步骤

Firefly 在派发时把 Bootstrap 与用户脚本渲染成一条 Bash：

1. `set -Eeuo pipefail`、`umask 027`；
2. 校验 `deploymentId`、路径和时长配置；
3. `flock -n` 获取应用级部署锁；
4. 在目标目录创建 `0700` 临时目录；
5. 使用 `curl --fail --location` 下载预签名 URL；
6. 校验大小和 SHA-256；
7. `TAR_GZ`/`ZIP` 列出成员，拒绝绝对路径、`..`、设备文件和越界符号链接后解压；
8. `FILE` 按安全文件名放入发布目录；
9. 原子 rename 到 `<deployRoot>/<applicationName>/releases/<deploymentId>`；
10. 注入稳定环境变量，执行用户 `deployScript`；
11. 退出码即命令退出码。

稳定环境变量：

```text
FIREFLY_DEPLOYMENT_ID
FIREFLY_APPLICATION_NAME
FIREFLY_ARTIFACT_PATH
FIREFLY_RELEASE_DIR
FIREFLY_DESTINATION_PATH
FIREFLY_CURRENT_LINK
FIREFLY_PREVIOUS_RELEASE
```

用户在脚本中自行切换 `current` 软链、重启服务或更新容器。Firefly 不假设 systemd。

### 8.5 命令正文大小与 URL

- 渲染后命令正文编码前不得超过云助手限制，默认阈值 16 KiB。
- 预签名 URL 直接作为 shell 变量写入命令正文，渲染时必须做单引号转义并校验 `https` 与 TOS Host。
- Firefly 不持久化完整命令正文，不打印 URL；Cloud Assistant 侧保存的 Invocation 内容由厂商管理。
- ECS 拉取制品后，URL 即可失效，不影响后续部署步骤。

## 9. 持久化设计

### 9.1 部署配置

```sql
CREATE TABLE `firefly`.`volcano_deploy_config`
(
    `id`                      BIGINT(20) NOT NULL AUTO_INCREMENT,
    `job_config_id`           BIGINT(20) NOT NULL,
    `region`                  VARCHAR(64) NOT NULL,
    `bucket_name`             VARCHAR(255) NOT NULL,
    `artifact_prefix`         VARCHAR(2048) NOT NULL,
    `current_level_only`      TINYINT(1) NOT NULL DEFAULT 1,
    `instance_id`             VARCHAR(128) NOT NULL,
    `instance_name_snapshot`  VARCHAR(255) NOT NULL DEFAULT '',
    `private_ip_snapshot`     VARCHAR(64) NOT NULL DEFAULT '',
    `vpc_id_snapshot`         VARCHAR(128) NOT NULL DEFAULT '',
    `zone_id_snapshot`        VARCHAR(128) NOT NULL DEFAULT '',
    `application_name`        VARCHAR(64) NOT NULL,
    `deploy_root`             VARCHAR(512) NOT NULL,
    `run_as_user`             VARCHAR(64) NOT NULL,
    `deploy_script`           TEXT NOT NULL,
    `command_timeout_seconds` INT NOT NULL,
    `created_at`              DATETIME(6) NOT NULL,
    `updated_at`              DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_volcano_deploy_config_job` (`job_config_id`),
    INDEX `idx_volcano_deploy_config_target` (`region`, `instance_id`)
);
```

### 9.2 制品选择

```sql
CREATE TABLE `firefly`.`volcano_artifact_selection`
(
    `id`                   BIGINT(20) NOT NULL AUTO_INCREMENT,
    `public_id`            VARCHAR(64) NOT NULL,
    `pipeline_build_id`    BIGINT(20) NOT NULL,
    `job_config_id`        BIGINT(20) NOT NULL,
    `region`               VARCHAR(64) NOT NULL,
    `bucket_name`          VARCHAR(255) NOT NULL,
    `object_key`           VARCHAR(2048) NOT NULL,
    `object_version_id`    VARCHAR(512) NOT NULL DEFAULT '',
    `object_etag`          VARCHAR(512) NOT NULL,
    `object_sha256`        CHAR(64) NOT NULL,
    `object_size`          BIGINT NOT NULL,
    `object_last_modified` DATETIME(6) NOT NULL,
    `artifact_handling`    VARCHAR(32) NOT NULL,
    `selected_by`          VARCHAR(128) NOT NULL,
    `selected_at`          DATETIME(6) NOT NULL,
    `created_at`           DATETIME(6) NOT NULL,
    `updated_at`           DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_volcano_artifact_selection_public` (`public_id`),
    UNIQUE INDEX `uidx_volcano_artifact_selection_build_job`
        (`pipeline_build_id`, `job_config_id`)
);
```

### 9.3 部署执行

```sql
CREATE TABLE `firefly`.`volcano_deploy_build`
(
    `id`                    BIGINT(20) NOT NULL AUTO_INCREMENT,
    `public_id`             VARCHAR(64) NOT NULL,
    `plugin_id`             BIGINT(20) NOT NULL,
    `job_build_id`          BIGINT(20) NOT NULL,
    `artifact_selection_id` BIGINT(20) NOT NULL,
    `status`                VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    `invocation_id`         VARCHAR(128) NOT NULL DEFAULT '',
    `exit_code`             INT NOT NULL DEFAULT -1,
    `output_excerpt`        VARCHAR(8192) NOT NULL DEFAULT '',
    `error_code`            VARCHAR(64) NOT NULL DEFAULT '',
    `error_message`         VARCHAR(2048) NOT NULL DEFAULT '',
    `dispatched_at`         DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    `finished_at`           DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    `created_at`            DATETIME(6) NOT NULL,
    `updated_at`            DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_volcano_deploy_build_public` (`public_id`),
    UNIQUE INDEX `uidx_volcano_deploy_build_job` (`job_build_id`),
    UNIQUE INDEX `uidx_volcano_deploy_build_selection` (`artifact_selection_id`),
    INDEX `idx_volcano_deploy_build_status` (`status`, `dispatched_at`)
);
```

- 所有时间按 UTC 保存；API 将 `1970-01-01` 和 `exit_code=-1` 映射为未发生/未知。
- 一个 `job_build_id` 只允许一个 `volcano_deploy_build`，失败后不自动创建第二个。
- `invocation_id=''` 表示尚未拿到云助手 InvocationId，不参与任何云 API 查询。
- 不创建 `volcano_deployment_attempt`、`volcano_command_revision`、结果 Inbox 等表。

## 10. 状态机与轮询

状态：

```text
PENDING -> DISPATCHING -> RUNNING -> SUCCESS
        -> FAILURE                 -> FAILURE
        -> UNKNOWN                 -> UNKNOWN
```

- `PENDING`：已创建，尚未调用 `RunCommand`。
- `DISPATCHING`：`RunCommand` 可能已经提交，但 InvocationId 尚未落库或调用结果未知。
- `RUNNING`：InvocationId 已落库，等待云助手终态。
- `SUCCESS`：云助手终态为成功且 `ExitCode=0`。
- `FAILURE`：`RunCommand` 明确失败，或云助手终态为失败且退出码非 0。
- `UNKNOWN`：超时/网络异常/云助手返回无法归类，不自动重试，人工查看。

Scheduler 简单逻辑：

1. 每 5～10 秒查询 `status = 'RUNNING'` 且 `invocation_id LIKE 'ivk-%'` 的记录；
2. 调用 `DescribeInvocations(InvocationId)`；非终态保持 `RUNNING`；
3. 终态更新 `volcano_deploy_build`，并通过现有 Outbox 发送 Plugin 终态消息；
4. `dispatched_at + commandTimeout + 5分钟` 仍非终态则置 `UNKNOWN`，不重复调用 `RunCommand`。

对 `PENDING`/`DISPATCHING` 的记录，`RunCommand` 的 `InvocationName` 固定为部署记录
`public_id`。Scheduler 在创建或派发后等待 2 分钟仍未拿到 InvocationId 时，按
`InvocationName` 调用一次 `DescribeInvocations`：

- 唯一匹配：CAS 写入真实 InvocationId，进入 `RUNNING`；
- 无匹配：置 `FAILURE`，不重新提交；
- 多个匹配：置 `UNKNOWN`，人工核对，不重新提交。

Scheduler 允许多实例重复查询云助手，`DescribeInvocations` 是只读操作；最终状态更新必须带旧
状态条件，Outbox 消息 UUID 继续使用现有 `BusinessMessageUUID.plugin(...)` 规则。

## 11. Plugin 接入

新增类：

```text
firefly-app/src/main/java/firefly/volcano
├── config/VolcanoAppProperties.java
├── controller/VolcanoConnectionController.java
├── controller/VolcanoObjectController.java
├── controller/VolcanoDeploymentController.java
├── security/VolcanoCredentialCipher.java
├── service/VolcanoConnectionService.java
├── service/VolcanoObjectService.java
├── service/VolcanoDeployService.java
├── service/VolcanoInvocationPollingScheduler.java
├── model/...
└── dao/...

firefly-app/src/main/java/firefly/service/pluginconfig/impl/
└── VolcanoDeployPluginConfigService.java

firefly-app/src/main/java/firefly/service/pluginbuild/impl/
└── VolcanoDeployPluginBuildService.java
```

修改点：

1. `PluginType` 增加 `VOLCANO_DEPLOY`。
2. `VolcanoDeployPluginConfigService` 实现 `IPluginConfig`，保存 `volcano_deploy_config`。
3. `PipelineBuildRequest` 增加按 Job UUID 索引的 `jobInputs`。
4. `PipelineBuildServiceImpl` 在创建 Build 前解析每个 Volcano Job 的 `artifactSelectionId`。
5. `VolcanoDeployPluginBuildService` 实现 `IPluginBuild`：
   - 创建 `volcano_deploy_build`，状态 `PENDING`；
   - 生成预签名 URL，渲染 Bootstrap，设置 `InvocationName=public_id`，发起派发前设置 `dispatched_at=now`；
   - 调 `RunCommand`；拿到 InvocationId 后用 CAS 写入并进入 `RUNNING`；
   - 调用明确失败：置 `FAILURE`；超时/未知：置 `DISPATCHING`；
   - 不自动重发、不自动创建新的部署记录；`PENDING/DISPATCHING` 由 Scheduler 按
     `InvocationName` 做一次只读核对。
6. `VolcanoInvocationPollingScheduler` 轮询终态并写 Outbox，只查状态，不重派。
7. Pipeline 删除时校验没有非终态部署，再按逻辑引用顺序删除 Volcano 配置和 Binding。

## 12. API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/volcano/connections` | 加密保存 AK/SK |
| `GET` | `/api/volcano/connections` | 分页查询脱敏 Connection |
| `GET` | `/api/volcano/connections/{id}` | 查询单个脱敏 Connection |
| `DELETE` | `/api/volcano/connections/{id}` | 无 Pipeline Binding 时删除 |
| `GET` | `/api/volcano/connections/{id}/tos/objects` | 列举 TOS 对象 |
| `GET` | `/api/volcano/connections/{id}/tos/object-metadata` | 查询对象元数据 |
| `GET` | `/api/volcano/pipelines/{pipelineId}/jobs/{jobUuid}/artifacts/recent` | 查询最近 10 个制品 |
| `GET` | `/api/volcano/pipelines/{pipelineId}/ecs/instances` | 查询可部署 ECS |
| `POST` | `/manual_trigger/pipeline` | 提交手动执行和 Job 制品选择 |
| `GET` | `/api/volcano/deployments` | 分页查询部署 |
| `GET` | `/api/volcano/deployments/{publicId}` | 查询详情 |

不提供 `/stop`、Retry、Command Revision 查询、对象内容下载和结果 Inbox 恢复接口。

## 13. 配置项

```yaml
firefly:
  volcano:
    storage:
      encryption-key: ${VOLCANO_ENCRYPTION_KEY:}
      key-version: ${VOLCANO_ENCRYPTION_KEY_VERSION:v1}
    tos:
      connect-timeout: ${VOLCANO_TOS_CONNECT_TIMEOUT:3s}
      read-timeout: ${VOLCANO_TOS_READ_TIMEOUT:60s}
      presign-ttl: ${VOLCANO_TOS_PRESIGN_TTL:10m}
      max-artifact-scan: ${VOLCANO_TOS_MAX_ARTIFACT_SCAN:10000}
    ecs:
      connect-timeout: ${VOLCANO_ECS_CONNECT_TIMEOUT:3s}
      read-timeout: ${VOLCANO_ECS_READ_TIMEOUT:15s}
      max-command-bytes: ${VOLCANO_MAX_COMMAND_BYTES:16384}
      default-run-as-user: ${VOLCANO_DEFAULT_RUN_AS_USER:firefly-deploy}
      allowed-run-as-users: ${VOLCANO_ALLOWED_RUN_AS_USERS:firefly-deploy}
      allowed-deploy-roots: ${VOLCANO_ALLOWED_DEPLOY_ROOTS:/opt/firefly/apps}
    deployment:
      poll-interval: ${VOLCANO_POLL_INTERVAL:5s}
      unknown-after-timeout: ${VOLCANO_UNKNOWN_AFTER_TIMEOUT:5m}
```

自定义 Endpoint 只允许 `https`，Host 必须在管理员 allowlist 中。

## 14. IAM 最小权限

TOS：

```text
tos:ListObjects
tos:HeadObject
tos:GetObject
tos:GetObjectVersion
```

ECS：

```text
ecs:DescribeInstances
ecs:DescribeCloudAssistantStatus
ecs:RunCommand
ecs:DescribeInvocations
```

不授予 `ecs:StopInvocation`、`ecs:CreateCommand`、`ecs:DeleteCommand`。`RunCommand` 的
Resource/Tag 限制尽量收敛到目标实例和 `firefly-` Invocation 前缀；脚本编辑权限等价于目标
实例指定用户的代码执行权限，必须由管理员审批并审计。

## 15. 错误码

```text
VOLCANO_ENCRYPTION_NOT_CONFIGURED
VOLCANO_CONNECTION_NOT_FOUND
VOLCANO_CREDENTIAL_INVALID
VOLCANO_ACCESS_DENIED
VOLCANO_ENDPOINT_REJECTED
VOLCANO_PIPELINE_BINDING_NOT_FOUND
ARTIFACT_SCAN_LIMIT_EXCEEDED
TOS_OBJECT_NOT_FOUND
TOS_OBJECT_CHANGED
TOS_CHECKSUM_MISSING
TOS_URI_INVALID
ECS_INSTANCE_NOT_FOUND
ECS_INSTANCE_NOT_RUNNING
ECS_CLOUD_ASSISTANT_UNAVAILABLE
ECS_RUN_COMMAND_FAILED
ECS_COMMAND_TOO_LARGE
ECS_INVOCATION_UNKNOWN
DEPLOYMENT_PATH_INVALID
DEPLOYMENT_PATH_OUTSIDE_ALLOWLIST
DEPLOYMENT_SCRIPT_INVALID
DEPLOYMENT_DOWNLOAD_FAILED
DEPLOYMENT_CHECKSUM_MISMATCH
DEPLOYMENT_ARTIFACT_PREPARE_FAILED
DEPLOYMENT_USER_SCRIPT_FAILED
```

错误响应只返回安全错误码、RequestId 和脱敏消息，不返回凭据、URL 或完整命令正文。

## 16. 测试

### 16.1 单元测试

- 凭据加密/解密、AAD、错误密钥版本、`toString()` 不含明文。
- TOS 列表、Head、预签名 TTL、404/403/5xx/网络异常映射。
- 手动选制品 Top-10、前缀边界、客户伪造快照拒绝。
- 命令渲染大小校验、URL shell 转义和 Host allowlist。
- ECS Invocation 状态、退出码映射和终态 CAS。

### 16.2 集成测试

- Testcontainers MySQL + Fake TOS/ECS Client。
- Connection、Binding、Deploy Config、Artifact Selection 落库。
- 手动执行缺选、无 SHA、越 Prefix、非当前层被拒绝。
- `RunCommand` 成功、明确失败、超时未知。
- Scheduler 不重复重派，只更新终态；Outbox 幂等。
- Pipeline/Plugin Build 创建和删除路径。

### 16.3 脚本测试

- `TAR_GZ`、`ZIP`、`FILE` 下载、校验、准备和用户脚本执行。
- 错误 SHA、错误大小、磁盘空间不足。
- 归档绝对路径、`..`、危险符号链接和设备文件被拒绝。
- 两个并发部署只能一个取得 `flock`。
- 用户脚本非零退出、超时、输出截断。

### 16.4 合同测试

- TOS Presign GET、VPC Endpoint、VersionId/If-Match。
- Cloud Assistant `RunCommand` 正文 16 KiB 限制、`DescribeInvocations` 终态和退出码。
- 100 MB 以上制品直拉；无公网 IP ECS 可部署。
- 明确验证 `StopInvocation`、`CreateCommand` 权限未被授予。

最终执行：

```bash
mvn clean verify
```

## 17. 兼容与迁移

- 新建 `volcano_*` 表，全部字段 `NOT NULL`。
- 旧 `volcano_engine`、`volcano_config`、`volcano_trigger` 的明文 AK/SK 迁移不阻塞本次
  Volcano 最小闭环；新代码只写加密 Connection。
- 旧 `VOLCANO` Trigger 保留只读兼容，不再向消息或审计记录传播 AK/SK。
- 全仓库 NOT NULL 整改是独立项目，不在本最小设计范围内。

## 18. 实施顺序

1. `firefly-volcano` 模块、TOS/ECS Client、错误模型、自动装配。
2. Connection 加密存储和 Pipeline Binding。
3. TOS 列表/Head/预签名和手动选制品 API。
4. ECS 实例查询、Bootstrap 渲染、`RunCommand`。
5. `VOLCANO_DEPLOY` Plugin Config/Build 接入和 Invocation Scheduler。
6. 单元、集成、脚本和 Staging 合同测试。

## 19. 完成标准

- `firefly-volcano` 独立 Maven 模块，`firefly-app` 不直接引用 Vendor SDK 类型。
- AK/SK 只以 AES-256-GCM 密文落库，不出现在 Pipeline JSON、Kafka、日志或 API。
- TOS 对象可列举、Head 和预签名 GET；Firefly 不实现本地制品下载。
- 手动执行时每个 Volcano Job 必须选择一个当前层制品，后端 HeadObject 生成不可变快照。
- ECS 能从私有 TOS 直拉制品，校验 SHA-256 后执行用户部署脚本。
- `RunCommand` 和 `DescribeInvocations` 可覆盖成功、失败和未知终态。
- 不实现任何自动重试、自动恢复、Pipeline Retry、停止/取消/回滚和结果事件链路。
