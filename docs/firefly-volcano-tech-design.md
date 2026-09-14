# Firefly Volcano 模块技术设计

> 状态：设计评审修订，待实现与 Staging 合同验证
> 版本：v1.6
> 日期：2026-09-15
> 目标代码库：Firefly（Java 25、Spring Boot 3.5、Maven 多模块）

## 1. 背景与目标

当前仓库没有独立的 `firefly-volcano` Maven 模块。火山引擎相关代码只存在于
`firefly-app`：

- `volcano_engine`、`volcano_config` 和 `volcano_trigger` 保存 AK/SK，其中部分表和
  消息模型直接保存或传递明文凭据。
- `VolcanoTriggerOriginServiceImpl` 只负责把 AK/SK 放入触发消息，不调用火山引擎
  OpenAPI。
- 没有 TOS 对象查询、流式读取、文件下载、校验、ECS 实例检查、云助手命令执行和
  部署状态恢复能力。

本设计新增独立的 `firefly-volcano` 集成模块，并在 `firefly-app` 中接入持久化、HTTP
管理接口和 Pipeline Plugin，使 Firefly 能够：

1. 在创建 Pipeline 时配置流水线级火山引擎身份，支持长期 AK/SK、直接输入 STS
   临时凭证或通过 AssumeRole 动态获取 STS。
2. 查询 TOS 对象元数据和对象列表。
3. 以流方式读取对象内容，或安全下载为本地文件。
4. 将 TOS 中的压缩包或二进制文件部署到指定 ECS 实例，并执行用户配置的部署指令。
5. 对下载、准备制品、用户指令执行、健康检查和回滚全过程进行持久化审计和失败恢复。

### 1.1 MVP 范围

- 中国区火山引擎；创建 Pipeline 时建立一个全局 Volcano Binding，所有 Volcano Plugin
  共享该身份。
- 凭据模式：`STATIC_AK_SK`、`STS_SESSION` 和 `STS_ASSUME_ROLE`。
- 私有 TOS Bucket。
- Linux ECS、单实例；服务管理方式由管理员脚本决定。
- 制品处理类型：`FILE`、`TAR_GZ`、`ZIP`；`FILE` 覆盖 JAR 和其他原始二进制。
- TOS 对象读取、下载和单对象部署。
- 用户可为 Plugin 提供部署指令；Firefly 为每个脚本版本创建不可变的云助手自定义命令，
  运行期通过 `InvokeCommand` 执行。
- Pipeline Plugin 类型 `VOLCANO_DEPLOY`。
- 部署 Job 配置只保存 TOS Region + Bucket + Prefix 制品范围，不保存具体制品。
- 用户真正手动执行 Pipeline 时，每个部署 Job 必须从该 Prefix 当前层按
  `LastModified` 倒序的最近 10 个制品中选择一个。
- 部署插件按 Region 查询可部署 ECS，使用 `Region + InstanceId` 保存目标。
- 部署插件允许配置 `tos://<bucket>/<prefix>/` 和受控的 ECS 部署路径；手动执行
  时再选择 Prefix 下的具体对象，Bootstrap 把经过校验的制品原子发布到该路径
  后再执行用户脚本。
- 可信 Bootstrap 将部署终态作为 JSON 结果对象上传至平台专用 TOS Bucket，TOS
  `ObjectCreated` 事件通过 Kafka 驱动 Firefly 状态机，正常执行路径不轮询云助手。
- 部署超时、重试、状态查询、健康检查和可选用户回滚指令；不承诺停止已派发的 Once 命令。

### 1.2 不在 MVP 范围

- 创建、启动、停止或删除 ECS 实例。
- Windows 实例。
- 多实例滚动发布、灰度发布、负载均衡摘挂、Auto Scaling Group。
- Windows Bat、PowerShell 或 Python 部署脚本；MVP 只支持 Linux Bash。
- 用户制品 Bucket 的上传、删除、覆盖对象；平台 Result Store 的内部结果上传除外。
- Firefly User/Tenant/RBAC 模型；管理 API 仍由部署层管理员认证保护。
- 把 SSH 私钥或长期 AK/SK 下发到 ECS。
- 不可信脚本沙箱、通过 `StopInvocation` 中断 Once 部署、由超时自动撤销已发生的业务变更。

## 2. 核心设计决策

### 2.1 云协议与 Firefly 业务分层

`firefly-volcano` 只实现火山引擎协议适配、标准模型、错误转换和 Spring Boot
自动装配，不依赖 `firefly-app`。数据库、Pipeline、Plugin、任务调度和管理 HTTP API
继续放在 `firefly-app`。

这与现有 `firefly-github` 的模块边界一致，也便于在单元测试中替换 TOS/ECS Client。

### 2.2 部署时由 ECS 直拉 TOS 对象

部署主链路不采用“Firefly 下载后再上传到 ECS”，而是：

1. Firefly 用 AK/SK 调用 TOS `HeadObject`，锁定对象版本和校验信息。
2. Firefly 生成短期、只允许 `GET` 指定对象的预签名 URL。
3. Firefly 调用 ECS 云助手 `InvokeCommand`。
4. ECS 内经过 Firefly 包装的不可变命令使用预签名 URL 下载制品并校验 SHA-256。
5. 包装命令按配置安全解压压缩包或准备原始文件，再执行用户给定的部署指令。

这样可以避免大文件占用 Firefly 的磁盘、内存和出口带宽，并确保 ECS 永远拿不到长期
AK/SK。管理端“下载对象”接口仍由 Firefly 以流式代理方式提供，满足人工下载和调试
需求。

### 2.3 用户指令固化为 Command Revision，再使用 `InvokeCommand`

用户保存 Plugin 时，Firefly 校验部署脚本，将可信 Bootstrap 与用户脚本组合成完整 Bash
内容，通过 `CreateCommand` 创建一条不可变的云助手自定义命令，并保存 Command ID、用户
脚本 SHA-256 和渲染后命令 SHA-256。修改脚本必须创建新的 Command Revision，禁止
`ModifyCommand` 原地覆盖，确保已经创建的 Deployment Attempt 始终指向相同代码。

运行期只调用 `InvokeCommand`，传递严格校验后的制品参数；不会在每次部署时创建或修改
命令。Firefly 页面必须显示脚本内容、Hash、创建人和最后批准时间，保存前明确提示：拥有
脚本编辑权限等价于拥有目标 ECS 上指定 `runAsUser` 的代码执行权限。

不使用 `RunCommand`，原因是它可以直接提交任意命令内容，难以用 IAM Policy 把权限
限制到已持久化的脚本版本。`CreateCommand` / `DeleteCommand` 只允许在配置发布和垃圾
回收路径使用；执行 Deployment 的运行角色只需要 `InvokeCommand` 等运行权限。

### 2.4 Volcano 是部署 Plugin，不是凭据型 Trigger

“从 TOS 取制品并部署 ECS”属于 Job 的执行动作，应新增 `VOLCANO_DEPLOY` Plugin。
Trigger 只说明 Pipeline 为什么启动，不应携带云凭据。现有 `VOLCANO` Trigger 在兼容
期内保留，但必须移除 AK/SK 在触发消息和 `volcano_trigger` 运行记录中的传播。

### 2.5 异步执行由 TOS 结果事件驱动

`InvokeCommand` 返回 Invocation ID 后立即结束当前调用。可信 Bootstrap 在下载、部署、
健康检查和可选回滚完成后，使用只允许 `PUT` 一个固定 Key 的短期预签名 URL，
把受限 JSON 结果上传到平台专用 TOS Bucket。TOS 对该 Prefix 的 `ObjectCreated`
事件投递到 Kafka，Firefly Result Consumer 获取并验证结果对象，然后在同一数据库
事务中更新 Attempt/Plugin Build 并写入现有 Outbox。

正常路径不定时调用 `DescribeInvocations` / `DescribeInvocationResults`。只有在
`result_deadline_at` 过期且仍未收到合法结果时，Deadline Reconciler 才先对预期 TOS Key
执行一次直查，仍不存在时再对云助手执行一次终态对账。这是异常补偿而不是
运行期轮询；没有结果事件时不能默认部署成功。

禁止在 Kafka Consumer 线程中等待几分钟甚至几小时，否则会占用 Listener、触发
`max.poll.interval.ms` 风险，并使进程重启后的任务无法恢复。

### 2.6 凭据在创建 Pipeline 时输入，但不嵌入 Pipeline JSON

Pipeline 创建向导必须提供“火山引擎全局配置”步骤。用户可以新输入 AK/SK、输入一组
STS 临时 AK/SK/Session Token，或配置 AssumeRole。后端验证后创建加密 Connection，
再把 `pipeline_id -> connection_id` 写入 `volcano_pipeline_binding`。

Pipeline、Job 的 `plugin_raw`、Kafka 消息和查询响应只保存或返回 Connection 引用与
脱敏信息，不保存明文凭据。这样既满足“创建流水线时输入”，又允许在不修改 Pipeline
拓扑的情况下独立轮换密钥。

### 2.7 ECS 目标使用 `Region + InstanceId`

无公网 IP 不影响云助手部署。Firefly 调用的是火山引擎 ECS OpenAPI，不会连接 ECS 的
公网或私网 IP；云助手服务根据 Instance ID 把命令下发给实例内 Agent。因此持久化目标
必须是 `(connectionId, region, instanceId)`，其中 `connectionId` 确定账号身份，Region
确定 API Endpoint，Instance ID 确定实例。

私网 IP 只用于 UI 展示和运行前一致性检查，不能作为主标识：IP 可能变化、释放或在不同
VPC 中重复。无公网 IP 的实例仍必须满足两个网络条件：云助手 Agent 能出站访问云助手
服务，实例能通过内网 Endpoint/VPC Endpoint 访问 TOS。完全无出站能力时，Instance ID
也无法让云助手工作，此时需另行部署 Firefly Agent，不属于 MVP。

### 2.8 下载与用户部署脚本分层

默认使用 `MANAGED_DOWNLOAD`：可信 Bootstrap 负责预签名 URL 下载、大小/SHA-256 校验、
安全解压和工作目录准备，随后才执行用户的 `deployScript`。环境变量契约只主动传入本地
路径，不主动导出预签名 URL；这减少意外泄露，但不能隔离同 UID 脚本对 URL/Token 的访问。
两种模式均要求脚本作者和目标主机管理员完全可信，具体信任边界见 8.9。

确实需要自定义下载工具或完整安装流程时，可选择 `CUSTOM_FULL_SCRIPT`。该模式把短期
预签名 URL 作为环境变量提供给脚本，由脚本自行下载、校验、解压和部署。Firefly 只能
审计脚本及退出码，不能保证其完整性校验、原子切换或自动回滚，因此必须由管理员显式
开启并再次确认风险。两种模式都不会把长期 AK/SK 或 STS 凭据发送到实例。

### 2.9 Job 配置制品范围，手动执行锁定具体制品

Plugin 配置可以让用户输入 `tos://<bucket>/<prefix>/`，但只持久化规范化的
Region、Bucket、Prefix、当前层规则和允许的制品处理类型。Job 配置中不允许出现
Object Key、Version ID、ETag、大小、LastModified 或 SHA-256 快照，因为这些字段在配置
时尚未选定。系统不保存预签名 URL，也不接受任意 `http://` / `https://`
地址。

具体 Object 只能在用户提交手动执行时选择。后端必须根据 Job 中的 Prefix 重新校验
Key，再执行 `HeadObject` 生成不可变的运行快照。该快照归属 Pipeline Build，不回写
Pipeline 或 Job 配置。对象 Key 始终是不透明标识，不能直接拼成本地路径。

用户配置的“部署路径”拆成管理员允许的绝对 `deployRoot`、用户可选的相对
`relativePath` 和明确的 `layout`。后端生成并展示最终路径预览，ECS Bootstrap 再次执行
相同校验。制品不能直接写入正在使用的目标：必须先下载到目标文件系统内的独占临时
目录，完成大小、SHA-256 和归档安全校验后，再通过原子 rename 或版本目录发布，最后才
执行用户脚本。这样“自动下载到指定路径”不会退化成任意路径覆盖能力。

## 3. 总体架构

```mermaid
flowchart LR
    UI["Firefly 管理端"] --> API["Volcano 管理 API"]
    PIPE["Pipeline / VOLCANO_DEPLOY"] --> DS["Deployment Service"]
    PIPE --> PBIND["Pipeline Volcano Binding"]
    PBIND --> CS
    API --> CS["Connection Service"]
    API --> OS["Object Service"]
    CS --> VAULT["AES-256-GCM Credential Store"]
    OS --> TOSC["TOS Client"]
    DS --> TOSC
    DS --> ECSC["ECS Cloud Assistant Client"]
    TOSC --> TOS["Volcengine TOS"]
    ECSC --> ECSAPI["Volcengine ECS OpenAPI"]
    ECSAPI --> AGENT["ECS Cloud Assistant Agent"]
    AGENT -->|GET 制品| TOS
    AGENT --> HOST["Release Directory + User Deployment Script"]
    AGENT -->|PUT 结果 JSON| RESULT_TOS["Platform Result TOS"]
    RESULT_TOS -->|ObjectCreated| RESULT_KAFKA["Volcano Result Kafka Topic"]
    RESULT_KAFKA --> INBOX["Result Event Inbox / commit then ACK"]
    INBOX --> CONSUMER["Deployment Result Processor"]
    CONSUMER --> STATE["Attempt State Service"]
    STATE --> OUTBOX["MySQL Outbox"]
    OUTBOX --> PLUGIN_KAFKA["Plugin Topic"]
    DEADLINE["Deadline Reconciler"] -. overdue only .-> RESULT_TOS
    DEADLINE -. one-shot reconciliation .-> ECSC
```

职责边界：

| 组件 | 职责 | 禁止事项 |
| --- | --- | --- |
| `firefly-volcano` | SDK Client、请求/响应模型、Endpoint、超时、错误标准化 | 数据库、Pipeline 状态、HTTP Controller |
| `firefly-app` Connection | 加密保存 AK/SK、轮换、连接验证 | 把明文凭据返回给 API |
| `firefly-app` Object | 列表、元数据、流式读取和本地下载编排 | 将整个对象读入 `byte[]` |
| `firefly-app` Deployment | 锁定制品、版本化用户脚本、调用云助手、状态机和恢复 | 运行未持久化、未审计的临时命令；周期查询云助手状态 |
| Platform Result Store | 签发固定 Key PUT URL，读取并校验结果对象 | 使用 Pipeline Connection 身份；接受任意 Bucket/Key |
| Result Event Inbox / Processor | 拆分通知、先持久化再 ACK、验证信封、CAS 更新终态和写 Outbox | 原样调用业务消息解析器；信任事件体中的状态；查询云助手 |
| Deadline Reconciler | 对逾期 Attempt 直查结果 Key，并做一次云端异常对账 | 对运行中 Attempt 周期轮询；自动重发未知 Invoke |
| ECS Command Revision | 下载/校验/准备制品，执行固定版本的用户部署和回滚指令 | 获取长期云凭据；运行其他脚本版本 |

## 4. Maven 模块设计

### 4.1 Reactor

根 `pom.xml`：

```xml
<modules>
    <module>firefly-github</module>
    <module>firefly-volcano</module>
    <module>firefly-app</module>
</modules>
```

`firefly-app/pom.xml` 新增对 `firefly-volcano` 的依赖。

### 4.2 SDK 依赖

建议以属性集中锁定版本，并通过 Maven Enforcer 做依赖收敛：

```xml
<properties>
    <volcengine.openapi.sdk.version>2.0.24</volcengine.openapi.sdk.version>
    <volcengine.tos.sdk.version>2.9.8</volcengine.tos.sdk.version>
</properties>

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
```

`firefly-volcano/pom.xml`：

```xml
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
        <groupId>javax.annotation</groupId>
        <artifactId>javax.annotation-api</artifactId>
        <version>1.3.2</version>
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

实施前在 Java 25 下执行 `mvn dependency:tree`。TOS SDK 自带较旧 Jackson 版本时，应
排除其 Jackson 传递依赖并使用 Spring Boot BOM 管理的版本；只有通过 SDK 合同测试后
才能合并，不能在没有验证时静默保留多个 Jackson 版本。

### 4.3 包结构

```text
firefly-volcano/src/main/java/firefly/volcano
├── api
│   ├── VolcanoObjectStorageClient.java
│   └── VolcanoEcsCommandClient.java
├── auth
│   ├── VolcanoCredentials.java
│   └── VolcanoCredentialsProvider.java
├── config
│   ├── FireflyVolcanoAutoConfiguration.java
│   └── VolcanoClientProperties.java
├── ecs
│   ├── VolcengineEcsCommandClient.java
│   └── model/...
├── tos
│   ├── VolcengineTosObjectStorageClient.java
│   └── model/...
└── error
    ├── VolcanoIntegrationException.java
    ├── VolcanoErrorCode.java
    └── VolcanoRequestMetadata.java
```

Spring 自动装配文件：

```text
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

## 5. 公共 Java 接口

Vendor SDK 类型不得穿透到 `firefly-app`。模块对外只暴露 Firefly 自己的模型。

### 5.1 凭据

```java
public record VolcanoCredentials(
    String accessKeyId,
    String secretAccessKey,
    String sessionToken
) {
    // 禁止生成包含字段值的 toString。
}

@FunctionalInterface
public interface VolcanoCredentialsProvider {
    VolcanoCredentials resolve();
}
```

Client 按 `(connectionId, region, endpoint profile)` 缓存底层 SDK 实例，但凭据 Provider
必须支持轮换。连接池和 HTTP Client 复用，凭据明文只在一次调用所需的最短时间存在。

### 5.2 对象存储接口

```java
public interface VolcanoObjectStorageClient extends AutoCloseable {
    ObjectPage listObjects(ListObjectsCommand command);

    ObjectMetadata headObject(HeadObjectCommand command);

    ObjectContent getObject(GetObjectCommand command);

    DownloadedObject downloadObject(DownloadObjectCommand command);

    PresignedDownload presignGet(PresignGetCommand command);

    PresignedUpload presignPut(PresignPutCommand command);
}
```

模型要求：

- `ObjectContent` 同时持有元数据和可关闭的 `InputStream`，实现 `AutoCloseable`。
- `DownloadedObject` 返回最终路径、字节数、ETag、Version ID、CRC64、SHA-256 和
  TOS Request ID。
- `DownloadObjectCommand.destination` 必须是调用方传入的已解析绝对路径。
- `PresignedDownload` 的 `toString()` 只输出过期时间，不输出 URL。
- `PresignedUpload` 只允许向平台根据 Deployment ID 生成的确定 Key 执行 `PUT`；签名
  固定 `Content-Type: application/json`，不授予 List、Get、Delete 或其他 Key 的权限。
- `PresignedUpload` 与 `PresignedDownload` 都禁止在 `toString()`、异常和日志中输出 URL。
- `GetObjectCommand` 支持可选 `versionId`、`rangeStart`、`rangeEnd` 和 `ifMatch`。
- 对象 Key 是不透明字符串，不能转换成本地路径，也不能用 `Path.resolve(key)`。

### 5.3 ECS 云助手接口

```java
public interface VolcanoEcsCommandClient {
    InstancePage listInstances(ListInstancesCommand command);

    EcsInstance describeInstance(DescribeInstanceCommand command);

    CloudAssistantStatus describeCloudAssistant(
        DescribeCloudAssistantCommand command);

    CommandDefinition describeCommand(DescribeCommand command);

    CreatedCommand createCommand(CreateCommand command);

    DeleteCommandResult deleteCommand(DeleteCommand command);

    CommandInvocation invokeCommand(InvokeCommand command);

    InvocationStatus describeInvocation(DescribeInvocation command);

    InvocationResult describeInvocationResult(
        DescribeInvocationResultCommand command);

}
```

`CreateCommand` 只在 Plugin 配置发布时调用，输入包含已验证的完整 Bash、参数定义、
`runAsUser`、工作目录、超时和 Firefly Tag；创建时校验完整正文，派发时再次按 8.5.1
校验正文与实际参数的综合 Base64 字节预算，不能只检查编码前正文为 16 KiB。
`InvokeCommand` 只包含已持久化 Command ID、Instance ID、固定参数映射、超时
和 Deployment ID，不提供命令正文。`DeleteCommand` 只能由引用计数为零且没有活动
Attempt 的垃圾回收任务调用。

`describeInvocation*` 不属于正常部署状态推进链路，只允许 Deadline Reconciler 在
结果超时或 `DISPATCH_UNKNOWN` 时单次调用。代码层将其放在独立的
`VolcanoDeploymentReconciliationService`，避免 Result Consumer 误用为定时轮询。

`DescribeInvocations` 返回结构包含执行时传入的 `Parameters`。ECS Adapter 使用字段白名单
映射 `InvocationStatus` / `InvocationResult`，显式丢弃 `Parameters`；禁止 Vendor 响应对象
进入数据库、日志、异常或 HTTP 响应。SDK HTTP wire/debug 日志同样关闭，不能依赖 DTO
脱敏补救此前的日志泄露。拥有对应 Invocation 查询权限的身份仍能绕过 Firefly 直接读回
参数，必须纳入该 Attempt 的结果可信域，见 8.9 和 13。

MVP 不暴露 `stopInvocation` 方法。官方 `StopInvocation` 使用限制针对定时/周期任务，
且已开始的命令仍会继续；不得把它当作 Once 命令的 kill API，见 9.4.1。

### 5.4 SDK 错误转换

所有 SDK 异常统一转换为：

```java
public final class VolcanoIntegrationException extends RuntimeException {
    private final VolcanoErrorCode code;
    private final Integer httpStatus;
    private final String providerCode;
    private final String requestId;
    private final boolean retryable;
}
```

日志和 API 可以输出 `requestId`、Firefly `deploymentId` 和已脱敏资源标识；不得输出
AK、SK、Session Token、Authorization Header、预签名 URL 或完整云助手参数。

## 6. 凭据和连接管理

### 6.0 DDL 与缺省值约定

本模块按本次评审要求统一使用 `NOT NULL`，这是 Volcano 新表的设计约定，不声称现有
全仓库都禁止 NULL，也不把 MySQL 可空唯一索引误判为只能存一条未派发记录。

| 字段/场景 | 未产生值时的持久化表示 | 读取和状态判断 |
| --- | --- | --- |
| 未发生的校验、批准、执行、接收、对账时间和未占用 Lease | `1970-01-01 00:00:00.000000` | 按状态判断，不把哨兵展示为真实事件时间 |
| 静态 AK/SK、AssumeRole 源 AK/SK 的 Connection 过期时间 | `9999-12-31 23:59:59.999999` | 仅表示源凭据无已知到期日，不代表不可撤销 |
| `STS_SESSION.credential_expires_at` | 必填真实到期日，无有效凭据则禁止创建/派发 | AssumeRole 缓存的临时凭据另用实际 expiry 校验 |
| `current_attempt_id` 尚未创建 | `0` | 正常主键从 1 开始；创建 Attempt 与绑定正整数 ID 同事务 |
| `exit_code` 尚未知 | `-1` | 不能用 `0` 占位，更不能由默认值推断成功 |
| 云端 Command / Invocation 尚未创建 | 每行唯一 `pending-<operation_id>` / `pending-<attempt_public_id>` | CAS 补写真实 ID；禁止空串占用唯一索引 |

不对缺失的真实业务事实编造时间或退出码。API 层将哨兵映射为明确的可选字段/未发生状态，
不向用户显示 `pending-*` 或假日期；JSON 可选字段仍可为 null，SQL 列不可为 NULL。
所有时间按 UTC 保存。增加 CHECK/应用校验约束非法哨兵与状态组合；迁移旧值时按此表
逐字段转换，不使用一次全列替换。

### 6.1 Connection 与 Pipeline Binding 模型

一个 Connection 表示一组火山引擎身份。创建 Pipeline 时可以创建新 Connection，也可
选择已有 Connection；随后通过 `volcano_pipeline_binding` 绑定到 Pipeline。Plugin 不再
单独选择凭据，而是根据所属 Pipeline 解析唯一的全局 Binding。

```sql
CREATE TABLE `firefly`.`volcano_connection`
(
    `id`                    BIGINT(20) NOT NULL AUTO_INCREMENT,
    `public_id`             VARCHAR(64) NOT NULL,
    `connection_name`       VARCHAR(128) NOT NULL,
    `credential_type`       VARCHAR(32) NOT NULL,
    `credential_ciphertext` TEXT NOT NULL,
    `credential_nonce`      VARBINARY(32) NOT NULL,
    `encryption_key_version` VARCHAR(64) NOT NULL,
    `credential_expires_at` DATETIME(6) NOT NULL,
    `default_region`        VARCHAR(64) NOT NULL,
    `tos_endpoint`          VARCHAR(512) NOT NULL DEFAULT '',
    `ecs_endpoint`          VARCHAR(512) NOT NULL DEFAULT '',
    `status`                VARCHAR(32) NOT NULL,
    `last_validated_at`     DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    `last_error`            VARCHAR(2048) NOT NULL DEFAULT '',
    `created_at`            DATETIME(6) NOT NULL,
    `updated_at`            DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_volcano_connection_public` (`public_id`),
    UNIQUE INDEX `uidx_volcano_connection_name` (`connection_name`),
    INDEX `idx_volcano_connection_status` (`status`)
);

CREATE TABLE `firefly`.`volcano_pipeline_binding`
(
    `id`                    BIGINT(20) NOT NULL AUTO_INCREMENT,
    `pipeline_id`           BIGINT(20) NOT NULL,
    `connection_id`         BIGINT(20) NOT NULL,
    `default_region`        VARCHAR(64) NOT NULL,
    `project_name`          VARCHAR(128) NOT NULL DEFAULT '',
    `created_at`            DATETIME(6) NOT NULL,
    `updated_at`            DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_volcano_binding_pipeline` (`pipeline_id`),
    INDEX `idx_volcano_binding_connection` (`connection_id`)
);
```

`credential_type`：

| 类型 | 创建时输入 | 运行方式 | 适用性 |
| --- | --- | --- | --- |
| `STATIC_AK_SK` | AK、SK | 直接签名 TOS/ECS API | 简单，但必须定期轮换 |
| `STS_SESSION` | 临时 AK、临时 SK、Session Token、Expires At | 有效期内直接签名 | 仅适合短期/一次性 Pipeline；过期后必须更新 |
| `STS_ASSUME_ROLE` | IAM 子用户 AK/SK、Role TRN、Session Name、Duration | 每次运行前调用 `AssumeRole`，缓存临时凭据到过期前 | 生产推荐 |

`credential_ciphertext` 加密前是带版本的内部 JSON。静态模式示例：

```json
{
  "schemaVersion": 1,
  "accessKeyId": "AKLT...",
  "secretAccessKey": "...",
  "sessionToken": null
}
```

STS AssumeRole 模式示例：

```json
{
  "schemaVersion": 1,
  "sourceAccessKeyId": "AKLT...",
  "sourceSecretAccessKey": "...",
  "roleTrn": "trn:iam::<account-id>:role/FireflyDeployRole",
  "roleSessionName": "firefly",
  "durationSeconds": 3600
}
```

STS Provider 必须在临时凭据过期前 5 分钟刷新，并用 Connection 级互斥避免并发请求同时
刷新。刷新失败时已有未过期凭据可继续使用；凭据已经过期则阻止新的对象查询和部署。

### 6.2 加密要求

- 使用 AES-256-GCM，每次写入生成新的 12 字节随机 Nonce。
- 环境变量 `VOLCANO_ENCRYPTION_KEY` 是 Base64 编码的 32 字节密钥。
- 使用 AAD 绑定 `public_id`、记录用途 `volcano-credential` 和 Key Version，防止密文
  被复制到其他记录后仍可解密。
- API 读取 Connection 时仅返回 `accessKeyIdMask`，例如 `AKLT****82KD`。
- 新建/轮换请求 DTO 禁止 Lombok `@Data`、`@ToString`；显式实现脱敏 `toString()`。
- 密钥轮换采用“新 Key Version 可读写、旧 Key Version 只读、后台重加密、确认完成后
  移除旧密钥”的两阶段流程。
- `STS_SESSION` 的 `expiresAt` 必须落在密文中，同时单独保存非敏感的
  `credential_expires_at` 便于状态提示和调度，但 API 不返回 Token。
- 生产环境优先使用 `STS_ASSUME_ROLE`；后续可接入 KMS Envelope Encryption，数据库
  结构无需改变。

### 6.3 Pipeline 创建契约

创建请求在 Pipeline 顶层增加全局 `volcanoConfig`，不能放在某个 Deploy Plugin 内：

```json
{
  "uuid": "<pipeline-uuid>",
  "name": "order-service-pipeline",
  "volcanoConfig": {
    "connectionName": "order-service-prod",
    "credential": {
      "type": "STS_ASSUME_ROLE",
      "sourceAccessKeyId": "<ak>",
      "sourceSecretAccessKey": "<sk>",
      "roleTrn": "trn:iam::<account-id>:role/FireflyDeployRole",
      "roleSessionName": "firefly",
      "durationSeconds": 3600
    },
    "defaultRegion": "cn-beijing",
    "projectName": "production"
  },
  "stageConfigs": []
}
```

后端处理顺序：

1. 校验字段和 Endpoint allowlist。
2. 使用输入身份调用 STS（如适用），再对管理员选择的 TOS/ECS 资源执行最小权限验证。
3. 加密凭据；开启数据库事务。
4. 保存 Connection、Pipeline、Binding、Stage、Job 和 Plugin 配置。
5. 任一步失败则整体回滚，不产生只有凭据或只有 Pipeline 的半成品。

查询 Pipeline 时只返回：

```json
{
  "connectionId": "vc_01J...",
  "connectionName": "order-service-prod",
  "credentialType": "STS_ASSUME_ROLE",
  "accessKeyIdMask": "AKLT****82KD",
  "defaultRegion": "cn-beijing",
  "credentialStatus": "ACTIVE"
}
```

### 6.4 Connection API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/volcano/connections` | 加密保存 AK/SK、STS Session 或 AssumeRole 配置 |
| `GET` | `/api/volcano/connections` | 分页查询脱敏 Connection |
| `GET` | `/api/volcano/connections/{id}` | 查询单个脱敏 Connection |
| `PUT` | `/api/volcano/connections/{id}/credentials` | 原子轮换凭据或切换凭据类型 |
| `POST` | `/api/volcano/connections/{id}/validate` | 对指定 TOS/ECS 资源做最小权限验证 |
| `DELETE` | `/api/volcano/connections/{id}` | 无活动配置引用时禁用并删除密文 |

不能通过 `ListBuckets` 或其他宽权限接口判断凭据“是否有效”。Validate 请求应携带管理员
明确选择的 `region`、`bucket`、`objectKey`、`instanceId`，分别执行 `HeadObject`、
`DescribeInstances` 和 `DescribeCloudAssistantStatus`。如果校验已有 Command，可以额外传
`commandId` 执行 `DescribeCommands`；`CreateCommand` 权限在第一次发布 Plugin 时以真实
创建结果验证，不能为了探测权限创建不可追踪的测试命令。

## 7. TOS 对象读取与下载

### 7.1 管理 API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/volcano/connections/{id}/tos/objects` | 按 Bucket/Prefix 分页列举对象 |
| `GET` | `/api/volcano/connections/{id}/tos/object-metadata` | 查询单个对象元数据 |
| `GET` | `/api/volcano/connections/{id}/tos/object-content` | 流式读取或下载单个对象 |
| `GET` | `/api/volcano/pipelines/{pipelineId}/jobs/{jobUuid}/artifacts/recent` | 手动执行弹窗查询该 Job 允许的最近制品 |

使用 Query Parameter 传递 `bucket`、`key`、`region` 和可选 `versionId`。不要把 Object
Key 放在 Path Variable 中，因为 Key 可以包含 `/`、空格和编码字符，代理层也可能错误
归一化路径。

### 7.2 “当前路径最近 10 个制品”语义

手动执行弹窗在已保存的 Pipeline 上按 Job 调用：

```http
GET /api/volcano/pipelines/{pipelineId}/jobs/{jobUuid}/artifacts/recent?limit=10
```

固定规则：

- Connection 从 Pipeline Binding 解析，Region、Bucket、Prefix 和当前层规则从
  `{pipelineId} + {jobUuid}` 对应的 `VOLCANO_DEPLOY` Job 配置解析。前端不能传
  `connectionId`、`region`、`bucket` 或 `prefix` 切换资源范围。
- `prefix` 表示 TOS 当前目录，后端调用 `ListObjectsV2` 时设置 `delimiter=/`，只返回
  当前层对象，不递归进入 `CommonPrefixes` 子目录。
- 排除以 `/` 结尾的目录占位对象、0 字节对象、不支持的扩展名、归档未恢复对象和缺少
  SHA-256 元数据的对象。
- 以 `LastModified DESC, Key ASC` 排序，最多返回 10 条。
- 返回供人阅读的 `key`、`versionId`、`etag`、`sha256`、`size`、`lastModified`、
  `storageClass` 和 `displayName`。提交手动执行时前端只回传选中的 `key`、可选
  `versionId` 和 `handling`；后端不信任客户端回传的 ETag、大小或校验和。

TOS List API 根据 Key 字典序分页，而不是按 `LastModified` 倒序，`max-keys=10` 不能保证
得到“最新 10 个”。MVP 后端必须遍历该 Prefix 的所有分页，并用大小为 10 的最小堆计算
Top-K；设置 `max-artifact-scan`（默认 10,000）和 30 秒缓存。超过扫描上限返回
`ARTIFACT_SCAN_LIMIT_EXCEEDED`，不能把不完整结果标为“最近 10 个”。

当单 Prefix 长期超过扫描上限时，生产方案二选一：

1. 由制品发布流程写入 Firefly `volcano_artifact` 索引表，查询直接按
   `(pipeline_id, prefix, last_modified)` 索引取 10 条。
2. 强制 Key 使用可排序时间/版本前缀，并维护一个受签名保护的 `manifest.json`。

用户提交手动执行后，后端对选中对象立即调用 `HeadObject` 并以服务端响应为准生成
不可变快照。MVP 是“运行时显式选择”，不是隐式部署最新对象；手动执行记录和
后续重试始终引用该快照，因此 Pipeline 配置本身不锁定制品也不会牺牲可审计性。

### 7.3 对象内容流式读取

`object-content`：

- Spring 使用 `StreamingResponseBody`，固定 64 KiB 缓冲区，不构造 `byte[]` 全量内容。
- 透传安全的 `Content-Type`、`Content-Length`、`ETag` 和 `Last-Modified`。
- `Content-Disposition` 文件名取 Object Key 最后一段，经 CR/LF、引号和路径字符清洗。
- 支持单段 HTTP Range；多段 Range MVP 返回 `416`。
- 客户端断开时立即关闭 TOS InputStream，不继续后台下载。
- 默认限制对象最大 5 GiB，可由 `firefly.volcano.tos.max-download-size` 调整。
- Controller 和 Access Log 不记录包含敏感 Query 的预签名 URL；管理下载接口本身不把
  预签名 URL返回浏览器。

### 7.4 下载到 Firefly 本地文件

内部下载过程：

1. 调用 `HeadObject` 获取大小、ETag、Version ID、CRC64、自定义元数据和存储类型。
2. 校验对象大小、存储类型和允许的 Content Type。
3. 在配置的工作目录下创建随机 `.part` 文件；目录不得由 API 调用者指定。
4. 使用 TOS SDK `downloadFile` 进行大文件断点续传，或用 `GetObject` 流式写入小文件。
5. 同步计算 SHA-256；如果 TOS 返回 CRC64，同时执行 CRC64 一致性检查。
6. 校验成功后使用同一文件系统内的原子移动发布最终文件。
7. 失败时保留 checkpoint 供同一任务续传，删除不完整最终文件。
8. 任务结束或超过 TTL 后由清理器删除 `.part`、checkpoint 和本地制品。

不能把 ETag 一律当作 MD5。分片上传或对象修改后 ETag 的语义可能不同；部署完整性使用
SHA-256，CRC64 只作为额外的传输一致性校验。

### 7.5 对象版本锁定

部署不能只记录 Bucket + Key，因为同名对象可能在部署中被覆盖。创建部署尝试时必须
保存以下快照：

- Bucket、Key、Region。
- Version ID；未启用版本控制时保存 HeadObject 返回的 ETag。
- Content Length、ETag、CRC64。
- 预期 SHA-256。

下载时设置 `versionId`；没有 Version ID 时设置 `If-Match: <etag>`。若条件不再满足，
部署以 `TOS_OBJECT_CHANGED` 失败，不能悄悄部署新内容。

### 7.6 SHA-256 来源

生产部署要求 SHA-256 必填，优先顺序：

1. TOS 自定义元数据 `x-tos-meta-firefly-sha256`。
2. 手动执行选择流程中由服务端受信任的制品索引提供，并写入运行快照。
3. 仅限管理端人工下载：Firefly 下载完成后计算；不能用于 ECS 直拉前的信任判断。

如果元数据和受信任索引同时存在但不一致，直接失败。SHA-256 必须是 64 位小写
十六进制。Job 配置不接受 `expectedSha256`，避免把某个具体制品的快照重新引入配置层。

## 8. ECS 部署设计

### 8.1 前置条件

- ECS 实例处于 `RUNNING`。
- 已安装并运行云助手 Agent。
- 实例与 TOS Bucket 默认要求同 Region；跨 Region 方案需显式开启并接受公网、费用和
  带宽风险。
- ECS 能访问 TOS Endpoint；优先使用 VPC 内网接入或 VPC Endpoint。
- 部署 Region 在平台批准的事件通知能力清单内，且对应 Result Bucket、通知规则、Kafka
  消费端和权限已通过 8.6.1 的就绪检查；不满足时拒绝新部署，不能退化为常态 Deadline 对账。
- Connection 的配置发布身份有权创建 Firefly 管理的云助手 Command，运行身份有权执行
  该 Command；禁止 `RunCommand` 和原地 `ModifyCommand`。
- 目标机存在 allowlist 中的 `runAsUser`，且该用户具备用户部署指令实际需要的最小权限；
  是否使用 systemd、容器或自定义进程管理器由部署指令决定。
- 目标机安装 `curl`、`sha256sum`、`flock` 和支持进程组超时控制的 GNU `timeout`；按包类型
  安装 `tar` 或 `unzip`。主机时钟必须同步，启动前校验 12.1 的绝对最晚启动时间。

### 8.2 无公网 IP 的实例发现与选择

Plugin 编辑器通过 Pipeline Binding 查询目标账号下的 ECS：

```http
GET /api/volcano/pipelines/{pipelineId}/ecs/instances
    ?region=cn-beijing
    &projectName=production
    &vpcId=vpc-xxx
    &status=RUNNING
    &pageNumber=1
    &pageSize=50
```

后端先调用 `DescribeInstances`，再批量调用 `DescribeCloudAssistantStatus`，只将以下实例
标记为 `deployable=true`：

- 与所选 TOS Bucket 位于允许的 Region。
- 实例状态为 `RUNNING`。
- 操作系统为 MVP 支持的 Linux。
- 云助手 Agent 已安装且在线。
- Instance ID、Project、VPC/Tag 满足 Connection 的 IAM 和管理员 allowlist。
- 平台对应 Region 的结果事件通道为 `READY`，否则返回不可部署原因。

选择框不能只展示 Instance ID。推荐显示：

```text
order-prod-01 | 10.0.12.34 | cn-beijing-a | production-vpc | i-ycxxxx
```

返回模型：

```json
{
  "region": "cn-beijing",
  "instanceId": "i-ycxxxx",
  "instanceName": "order-prod-01",
  "privateIp": "10.0.12.34",
  "vpcId": "vpc-xxx",
  "subnetId": "subnet-xxx",
  "zoneId": "cn-beijing-a",
  "projectName": "production",
  "tags": {"env": "prod", "app": "order-service"},
  "status": "RUNNING",
  "cloudAssistantStatus": "ONLINE",
  "deployable": true,
  "unavailableReason": null
}
```

保存时以 `Region + InstanceId` 为真实目标，同时保存名称、私网 IP、VPC 和 Zone 快照供
审计。执行前再次 `DescribeInstances(instanceId)`：实例不存在、Region 不匹配、已停止、
Agent 离线或关键 Tag 已改变时停止部署。私网 IP 变化只产生审计告警，不改变目标身份。

网络路径如下：

```text
Firefly --HTTPS--> ecs.<region>.volcengineapi.com --控制面--> Cloud Assistant Agent
ECS VM  --内网/出站 HTTPS--> TOS Endpoint
```

Firefly 不通过公网 IP 或私网 IP SSH/复制文件到实例，因此目标 ECS 没有公网 IP是正常且
推荐的部署形态。若安全组禁止所有入站也不影响本方案；但不能阻断 Agent 和 TOS 所需的
出站或 VPC Endpoint。

### 8.3 Plugin 配置

`PluginType` 新增 `VOLCANO_DEPLOY`。Pipeline 请求示例：

```json
{
  "uuid": "<64-char-job-uuid>",
  "name": "deploy-order-service",
  "pluginType": "VOLCANO_DEPLOY",
  "pluginRaw": {
    "artifactSource": {
      "selectionMode": "MANUAL_AT_RUN",
      "tosUri": "tos://firefly-artifacts/order-service/releases/",
      "region": "cn-beijing",
      "bucket": "firefly-artifacts",
      "prefix": "order-service/releases/",
      "currentLevelOnly": true,
      "allowedHandling": ["TAR_GZ", "ZIP", "FILE"],
      "defaultHandling": "TAR_GZ"
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
      "deployRoot": "/opt/firefly/apps/order-service",
      "relativePath": "releases",
      "layout": "VERSIONED_DIRECTORY",
      "outputFileName": null,
      "executable": false,
      "retainReleases": 5
    },
    "execution": {
      "mode": "MANAGED_DOWNLOAD",
      "interpreter": "BASH",
      "runAsUser": "firefly-deploy",
      "workingDirectory": "RELEASE_DIR",
      "deployScript": "install -m 0755 \"$FIREFLY_ARTIFACT_PATH/bin/order-service\" /opt/order-service/bin/order-service\nsudo -n systemctl restart order-service.service",
      "rollbackScript": "sudo -n systemctl restart order-service.service",
      "commandTimeoutSeconds": 900,
      "healthCheck": {
        "mode": "HTTP",
        "url": "http://127.0.0.1:8080/actuator/health",
        "timeoutSeconds": 60,
        "successCount": 2
      }
    }
  }
}
```

配置中不再接受 `connectionId`、`ak`、`sk` 或 Session Token；Connection 只能从所属
Pipeline Binding 得到。`artifactSource` 只定义可选制品的范围；不包含 `key`、
`versionId`、`etag`、`size`、`lastModified` 或 `expectedSha256`。动态消费上游 Job
产物需要先设计 Pipeline Artifact Contract，不在本次通过任意字符串模板拼接实现。

`artifactSource.tosUri` 是创建/编辑界面的便捷输入，后端以 `region + bucket +
prefix` 为权威配置。URI 只接受 `tos` Scheme，Bucket 位于 Authority，路径按 UTF-8
Prefix 解析；拒绝 UserInfo、Port、Fragment、Query 和重复百分号解码。保存时只验证 Prefix
属于 Pipeline Binding 允许范围，并使用 `ListObjectsV2` 确认凭据具有必要的列举权限；
不对某个具体 Object 执行 `HeadObject`。

`destination.layout`：

| 类型 | 最终部署路径 | 适用制品 |
| --- | --- | --- |
| `VERSIONED_DIRECTORY` | `<deployRoot>/<relativePath>/<deploymentId>/` | `TAR_GZ`、`ZIP`、`FILE`，生产推荐 |
| `FIXED_FILE` | `<deployRoot>/<relativePath>` | 仅 `FILE`，兼容必须使用固定文件名的服务 |

`VERSIONED_DIRECTORY` 中，压缩包解压到最终版本目录，普通文件以 `outputFileName` 放入
版本目录。`FIXED_FILE` 的 `relativePath` 本身包含文件名；发布时先生成同目录临时文件，
校验后用原子 rename 替换，并保留一个受控的 previous 文件提供给用户回滚脚本；不能
仅凭文件恢复就声称业务回滚成功。MVP 不支持覆盖非空固定目录，因为跨目录树无法可靠
原子替换。

`handling` 必须在手动执行时由用户确认，且必须位于 Job 配置的
`allowedHandling` 内，不能只根据扩展名自动决定：

| 类型 | 准备结果 | `FIREFLY_ARTIFACT_PATH` |
| --- | --- | --- |
| `TAR_GZ` | 校验归档成员后解压到新 Release 目录 | 解压后的 Release 目录 |
| `ZIP` | 校验归档成员后解压到新 Release 目录 | 解压后的 Release 目录 |
| `FILE` | 按 Destination Layout 放入版本目录或固定文件路径 | 最终文件绝对路径 |

`FILE` 同时覆盖 JAR、ELF、Go/Rust 可执行文件和其他不可解压的二进制。只有
`destination.executable=true` 时 Bootstrap 才把模式设置为 `0750`，否则使用 `0640`；
文件名必须是单个安全文件名，不能包含 `/`、`..` 或控制字符。UI 可以根据 `.tar.gz`、
`.zip`、`.jar` 给出建议，但保存前必须让用户确认。

字段校验：

- `applicationName`：`[a-z][a-z0-9-]{1,62}`。
- `instanceId`：按火山资源 ID 格式和长度白名单校验。
- `artifactSource.tosUri` 解析结果必须与 `bucket`、`prefix` 一致；Bucket 和 Prefix
  必须落在 Pipeline Binding 的 IAM/管理员 allowlist 内。
- Artifact Region 与 Target Region 必须一致，除非管理员显式开启跨 Region 部署。
- `deployRoot`：必须位于管理员配置的根目录，例如 `/opt/firefly/apps/`，规范化后仍在
  根目录内；禁止 `..`、NUL 和符号链接逃逸。
- `relativePath`：必须是非空相对路径，禁止前导 `/`、`.`/`..` 段、NUL、控制字符、Shell
  模板和 `${...}`；UTF-8 编码后最长 1024 字节。
- 最终路径按组件检查现有父目录，任何组件是符号链接都拒绝；执行用户必须对受控临时
  目录和目标父目录有权限，但不能写 allowlist 之外的目录。
- `FIXED_FILE` 要求 `allowedHandling` 和 `defaultHandling` 均为 `FILE`；
  `VERSIONED_DIRECTORY` 允许包含 `FILE`，但必须提供安全的 `outputFileName`，不能包含
  `/`、`..` 或控制字符。
- `runAsUser`：不能由普通 Plugin 任意填写，必须来自管理员 allowlist；默认禁止 `root`。
- `deployScript` 必填，UTF-8、无 NUL；`rollbackScript` 可选。脚本、Bootstrap 和参数的
  编码总预算必须符合 8.5.1；仅满足用户脚本局部大小限制不代表能够成功派发。
- `healthCheck.url`：MVP 只允许 `http://127.0.0.1` 或 `http://localhost`，禁止 SSRF。
- `commandTimeoutSeconds` 是业务步骤总预算，不是云助手硬超时。默认有效范围为
  30～3120 秒；按 12.1 的上传、排队、交付和凭据余量动态收敛，UI 显示实际上限。
- `retainReleases` 范围 2～20。

编辑器交互顺序固定为：

1. 读取 Pipeline 全局 Volcano Binding；凭据无效或 STS 已过期时禁用 Plugin 保存。
2. 选择 Region、Bucket 和 Prefix，设置当前层规则、允许的处理类型和默认值。编辑器
   不查询“最近 10 个制品”，也不展示具体 Object 选择器。
3. 请求同 Region 的 ECS 列表，默认只显示 `deployable=true`，可切换查看不可用原因。
4. 用户根据实例名、私网 IP、Zone、VPC、Tag 和 Instance ID 选择一台机器。
5. 配置 Deploy Root、相对路径和 Layout；后端返回规范化 Prefix URI 和最终路径
   预览，页面明确区分临时下载路径与用户脚本看到的最终路径。
6. 填写部署指令、可选回滚指令、执行用户、超时和健康检查。
7. 页面显示“该指令将在目标实例执行”的高风险确认，同时展示生成的 Script SHA-256。
8. 保存前后端校验 Prefix、Instance、Agent 和结果事件通道就绪状态，不要求选择制品。
9. 后端渲染并校验命令，通过 `CreateCommand` 创建不可变 Command Revision；只有云端命令
   和本地配置均保存成功后 Plugin 才进入 `READY`。失败时删除孤儿命令或交给 GC 回收。

MVP 的管理 API 已由部署层管理员认证保护，因此管理员保存脚本即视为批准，
`created_by` 和 `approved_by` 可以相同；接入 Firefly RBAC 后再扩展为编写人与批准人分离，
数据结构无需改变。系统不提供脱离 Pipeline/Plugin 的“立即执行任意脚本”接口。

数据库和 ECS OpenAPI 不能组成一个事务，Command 发布采用可恢复 Saga：

1. 数据库事务写入 `PROVISIONING` Revision，保存渲染 Hash 和唯一
   `provisioning_operation_id`，`command_id=pending-<operation_id>`。
2. 事务外调用 `CreateCommand`；命令名限制为 32 字符内的
   `firefly-<hash12>-<suffix>`，Tag 保存完整操作 ID 和 Hash。
3. 第二个数据库事务按 Revision ID、操作 ID、`PROVISIONING` 和原 `pending-*` 做 CAS，
   写入真实 `cmd-*`、把 Revision 改为 `READY` 并绑定 Plugin Config。
4. 请求超时或进程崩溃时，恢复器按 Tag + Hash 查询云端命令：唯一匹配则补写，多个匹配
   则告警并禁止发布，没有匹配才允许重试创建。
5. 只有 `READY` Revision 可以执行；失败 Revision 标记 `ORPHANED`，24 小时后由 GC 在
   确认零引用、零活动 Attempt 且 Tag 匹配后删除。

恢复器按持久化的操作 ID/Tag 查询，而不是把占位 Command ID 发给云 API；GC 同样只对
已经核实的真实 `cmd-*` 调用 Delete。孤儿记录仍为 `pending-*` 时先核对云端归属，不能
盲删或直接丢弃尚可能关联云资源的本地记录。重复响应与恢复器竞争时，只接受相同真实
Command ID，冲突则告警；占位值和操作 ID 不得被其他 Revision 复用。

### 8.4 手动执行时选择制品

现有代码中手动执行入口是 `POST /manual_trigger/pipeline`，请求类为
`PipelineBuildRequest`。当前请求只包含 `pipelineId`、`uuid`、`triggerModel`、
`triggerMatch` 和 `triggerOrigin`，本设计在保持路径兼容的前提下增加按 Job UUID 索引的
`jobInputs`：

```json
{
  "pipelineId": 1001,
  "uuid": "<64-char-request-uuid>",
  "triggerModel": "MANUAL",
  "triggerMatch": "ACCURATE",
  "triggerOrigin": "VOLCANO",
  "jobInputs": {
    "<64-char-volcano-job-uuid>": {
      "artifact": {
        "key": "order-service/releases/order-service-1.8.2.tar.gz",
        "versionId": null,
        "handling": "TAR_GZ"
      }
    }
  }
}
```

`jobInputs` 使用 Job 的稳定 64 位 UUID，不使用前端拖拽节点 ID 或数据库自增 ID。一个
Pipeline 有多个 `VOLCANO_DEPLOY` Job 时，弹窗逐个调用 7.2 的最近制品接口，并要求
每个 Job 各选一个制品。运行时选择不修改已保存 Pipeline，也不触发“编辑 Pipeline”
状态。

后端在调度任何 Stage 前必须完成以下校验：

1. `triggerModel` 必须为 `MANUAL`，并且 Pipeline 属于当前用户可执行范围。
2. 通过 Job UUID 解析唯一 `JobConfig`，确认其 `PluginType=VOLCANO_DEPLOY`。拒绝未知 Job、
   重复选择、非 Volcano Job 输入和漏选。
3. 从 Pipeline Binding 和 Job 配置解析 Connection、Region、Bucket、Prefix 和允许处理
   类型，不从请求体接受这些边界字段。
4. 规范化 `key`，确认其在配置的 Prefix/当前层内，并重新计算该 Job 的最近 10 个
   制品，选中 Key 必须仍在集合中；列表已变化时返回 `VOLCANO_ARTIFACT_SELECTION_INVALID`
   并要求刷新。`handling` 必须属于 `allowedHandling`，且扩展名与处理方式没有明显冲突。
5. 使用服务端凭据调用 `HeadObject`，获得并校验 Version ID/ETag、CRC64、大小、
   LastModified 和 SHA-256。请求中即使出现这些字段也必须拒绝，不能接受客户端快照。
6. 一次数据库事务中创建 `PipelineBuild`、`StageBuild`、`JobBuild`、`PluginBuild`
   和每个 Job 的制品选择快照。任一校验/落库失败则不创建半成品 Build；事务提交
   后再通过现有 Dispatch/Outbox 启动执行。

当前 `PipelineBuildServiceImpl.parsePipelineBuildRequest` 没有把 `triggerModel` 和 `triggerMatch`
复制到 `PipelineBuildDto`，`PipelineBuildDto`、`PipelineBuild` 和 `JobBuildContext` 也没有运行输入。
实现时必须显式补齐这条传递链：`PipelineBuildRequest -> PipelineBuildDto -> PipelineBuild`
持久化触发模式，并且由 `JobBuildContext` 携带 `artifactSelectionId`，或由 Volcano
Build Service 按 `(pipelineBuildId, jobConfigId)` 唯一解析选择记录。不能在
`IPluginBuild.savePluginBuild(JobBuildContext)` 之后再从临时 HTTP 请求中取值。

包含 `MANUAL_AT_RUN` Job 的 Pipeline 在 MVP 中不允许被 GitHub Webhook 等自动触发启动，
因为自动触发没有人工制品选择。系统在创建 Build 前返回
`VOLCANO_MANUAL_ARTIFACT_SELECTION_ONLY`，不得隐式取最新对象。未来只能通过受信任的上游
Artifact Contract/制品索引为自动触发提供显式输入。

### 8.5 部署命令参数

每个 Command Revision 的命令正文由“可信 Bootstrap + 固定用户脚本”组成，运行时只接受
以下参数：

```text
deployment_id
execution_attempt
instance_id
command_revision_public_id
artifact_url_b64_chunk_count
artifact_url_b64_1 ... artifact_url_b64_4
artifact_sha256
artifact_size
artifact_handling
destination_output_name_b64
application_name
deploy_root_b64
destination_relative_path_b64
destination_layout
destination_executable
execution_mode
command_timeout_seconds
result_upload_reserve_seconds
start_before_epoch_seconds
upload_deadline_epoch_seconds
health_url_b64
health_timeout_seconds
retain_releases
result_schema_version
result_url_b64_chunk_count
result_url_b64_1 ... result_url_b64_4
result_token
result_deadline_epoch_seconds
```

云助手 String 自定义参数单值最大 1000 字符。预签名 URL 使用标准 Base64 后按每段最多
900 字符拆成 1～4 个只含 Base64 字符的参数；Bootstrap 校验段数、拼接、解码并检查
`https`、TOS Host allowlist 和长度。超出总容量则配置失败，不能截断 URL。路径也使用
Base64 传入以减少参数替换造成的 Shell 解析风险，但 Base64 不是安全校验。

Bootstrap 自身禁止 `eval` 和 `set -x`，所有变量引用加双引号。用户的 `deployScript` 和
`rollbackScript` 是 Command Revision 的静态正文，不通过 `{{parameter}}` 或字符串替换
拼入参数值。`MANAGED_DOWNLOAD` 在进入用户脚本前执行 `unset FIREFLY_ARTIFACT_URL`；
`CUSTOM_FULL_SCRIPT` 才导出短期 URL，并将该 URL 的原文和 Base64 值都加入本次日志
精确脱敏集合。

#### 8.5.1 正文与参数的综合编码预算

`CreateCommand` 通过不代表之后任意一组 `InvokeCommand.Parameters` 都能执行。官方
InvokeCommand 限制是原始命令内容与自定义参数在 Base64 编码后的综合长度不超过
16 KB；不能简化成“编码前正文不超过 16 KiB”或仅校验替换后的脚本。

`VolcanoCommandSizeValidator` 在配置保存和派发前分别执行：

1. 保存：固定 Bootstrap 版本，渲染完整正文（包含部署/回滚脚本、包装和参数占位符），
   校验 CreateCommand 限制并记录原始 UTF-8 字节数、编码字节数及 Hash。用户脚本与回滚
   脚本合计的局部上限默认 4096 字节，它只是提前拒绝阈值，不是可执行容量保证。
2. 派发：确认重新渲染的正文 Hash 与 Revision 相同；生成两套实际预签名 URL 后再计算
   最终 Parameters，包含 Token、Deadline、所有默认值和空分片。禁止只按 URL 平均长度
   估算；参数最多 60 个，Key 最长 64 字符，String 值按已验证的 1000 字符上限校验。
3. Firefly 先采用保守本地预算：`B64(originalCommandUtf8).bytes +
   B64(compactParametersJsonUtf8).bytes <= 16384`，JSON 包含完整键名、转义和默认值；
   同时独立检查 `B64(effectiveCommandUtf8).bytes <= 16384`，覆盖重复占位符扩张。
   Base64 不换行，`B64` 长度为 `4 * ceil(utf8Bytes / 3)`，不能用 Java 字符数代替字节数。
4. 上述是 Firefly 的保守保护线，不宣称等同于服务端序列化算法。启用部署前必须以
   固定 SDK/API 的 Staging 合同测试确认综合计数口径、KB 单位和边界；若云端更严格，
   收紧并版本化预算配置。未通过合同测试时不得将通道标记 READY。
5. 任一局部或综合预算超限均在 Invoke 前以 `ECS_COMMAND_PARAMETERS_TOO_LARGE` 拒绝，
   不截断脚本/URL，不自动改成其他执行模式；校验响应只返回字节数和字段名，不回显秘密。

### 8.6 TOS 结果对象与 Kafka 事件协议

#### 8.6.1 平台结果存储

结果对象不写入用户制品 Bucket。平台管理员仅为已获准部署的 Region 预置私有、平台自有的
Result Bucket，例如 `firefly-control-cn-beijing`，并对固定 Prefix 配置一条而不是每次部署
动态创建 TOS 事件通知规则：

```text
prefix = firefly/deployment-results/v1/
suffix = .json
event  = tos:ObjectCreated:*
target = Volcengine Kafka topic firefly-volcano-result-v1
```

TOS 原生 Kafka Destination 需要火山引擎消息队列 Kafka 实例、Topic、PLAIN 用户和授权
TOS 访问 Kafka 的 IAM Role；当前控制台授权会创建 `TOSNotiKafkaRole`。该 Kafka 可与
Firefly 已有业务 Kafka 是不同集群；
`firefly-app` 为结果 Topic 配置独立 Consumer Factory 和独立的消费 SASL 身份，注入方式见 12。
如平台不使用火山引擎 Kafka，则必须
使用 TOS -> VeFaaS -> Firefly Kafka 的受控转发适配器，不得让 ECS 直接持有 Kafka 长期凭据。

结果 Key 由 Firefly 生成，不接受用户输入：

```text
firefly/deployment-results/v1/
    <deployment-public-id>/<execution-attempt>/<128-bit-report-object-id>.json
```

Result Bucket 开启服务端加密、版本控制和 7～30 天生命周期清理。Pipeline Connection
的 AK/SK 不需要结果 Bucket 权限；Firefly 使用独立的平台 Result Store 身份生成精确 Key
的预签名 PUT URL 并读取结果。这样即使 Pipeline 绑定不同账号，也不需要用户修改
制品 Bucket 的事件通知配置。

截至 2026-09-15，官方事件通知概述列出的支持地域为华北 2（北京）、华南 1（广州）、
华东 2（上海）、亚太东南（柔佛）。本设计中国区 MVP 默认只允许前三个 Region；柔佛
不是默认开放范围。能力清单必须随官方能力变化经管理员验证后更新，不能推断所有 TOS
Region 都支持事件通知，VeFaaS 转发也不能绕过源 Region 的事件能力限制。

`VolcanoResultChannelReadinessService` 在配置保存与每次 Invoke 前检查：

- Region 在平台 allowlist，Result Bucket 映射存在且地域正确，ECS 可达其 Endpoint。
- 读取通知规则的有效配置，核对固定 Prefix/Suffix、ObjectCreated、目标集群/Topic、
  SASL/服务角色授权、启用状态；规则需启用/保持有效，不能只确认 Bucket 存在。
- 官方说明规则创建后约 5 分钟生效。Provisioner 必须等待生效并通过一条受控测试部署
  的 PUT -> Kafka -> Consumer 全链路验收，记录 `configurationHash`、`verifiedAt` 和
  验收版本。变更规则/Topic/凭据后旧证据失效，不能仅凭等待时间判定成功。
- 应用的独立 Consumer 已分配分区、鉴权成功、无阻断告警；最近只读健康检查需在配置的
  5 分钟新鲜度内。全链路验收证据绑定配置版本，不要求每次 Invoke 写探测对象。
  GetBucketNotification 等检查使用独立只读预检身份，不给运行身份增加修改权限。

任一检查失败返回 `DEPLOYMENT_RESULT_CHANNEL_NOT_READY`；不支持的 Region 返回
`DEPLOYMENT_RESULT_REGION_UNSUPPORTED`。只读检查不能保证未来不发生故障，运行中发生的
事件丢失仍走 Deadline 异常补偿；不得因通道未配置而主动退化为对账部署。

#### 8.6.2 结果信封

Firefly 在分发前用 CSPRNG 生成 32 字节随机值，转为 64 字符小写 hex `resultToken`。
唯一哈希口径为 `SHA256(US_ASCII(resultToken))`，输出同样为小写 hex；不是对原始随机
32 字节求哈希。服务端生成端和消费端共享测试向量，Bootstrap 原样回传 Token，不自行
重新编码。数据库只保存 Token Hash，拒绝大写 hex、空白和非 64 字符输入，再做常量时间比较。

固定测试向量（仅测试，绝不可用于真实部署）：

```text
resultToken = 000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f
SHA256(US_ASCII(resultToken)) = 6c86c6aac5fb24bcf5d9939cb7d7d5645ce39418f449e03b262dd4fa14b4b92b
```

URL 和 Token 通过云助手参数传给 Bootstrap，默认不向用户脚本环境导出、不写日志，
也不放入 Kafka 事件；这不是对同 UID、主机管理员或 Invocation 查询者的隔离保证。
Token 只用于对应 Attempt 的结果认证与关联，不是长期凭据或主机执行证明；终态后不再
授权状态变更。Deadline 对账可验证在上传期限内形成的原结果；Attempt 已结束后只允许
迟到审计，不能重新推进状态机。另需校验 TOS 对象 LastModified 不晚于上传期限，而非
只信任信封自报的时间。
结果对象最大 16 KiB，拒绝未知字段，Schema v1 为：

```json
{
  "schemaVersion": 1,
  "deploymentId": "dep_01J...",
  "executionAttempt": 0,
  "resultToken": "<64 lowercase hex>",
  "instanceId": "i-yc...",
  "artifactSha256": "<64 lowercase hex>",
  "commandRevisionPublicId": "vcr_01J...",
  "status": "SUCCESS",
  "errorCode": "",
  "failedPhase": null,
  "exitCode": 0,
  "rolledBack": false,
  "destinationPathB64": "L29wdC9maXJlZmx5L2FwcHMvLi4u",
  "outputExcerptB64": "",
  "errorMessageB64": "",
  "startedAt": "2026-09-01T10:00:00Z",
  "finishedAt": "2026-09-01T10:01:12Z"
}
```

`status` 只允许 `SUCCESS`、`FAILURE`、`ROLLED_BACK` 和
`MANUAL_INTERVENTION_REQUIRED`。`errorCode` 只能为空或 14.2 中的受控错误码，不能据此
跳过状态/Token 校验。用户可控文本使用 Base64 字段，避免 Bootstrap 用不可靠的
Shell 字符串拼接生成 JSON；Firefly 解码后仍要执行 UTF-8、长度、控制字符和脱敏校验。
ECS 不知道 `InvokeCommand` 返回的 Invocation ID，因此结果协议不要求 ECS 回传该字段；
Firefly 通过 Deployment ID + Attempt 唯一定位已持久化的 Attempt，即使 Invocation ID
还处于 `pending-*` 也不影响结果关联。Instance ID、Attempt 和 Revision Public ID 由
受控 Invoke 参数传入；Bootstrap 不从用户输出推断它们。

#### 8.6.3 上传与消费

Bootstrap 使用 `trap` 捕获 `EXIT`、`TERM` 和 `INT`，把最终状态先原子写入本地
`result.json`，再使用 `curl --request PUT --data-binary @result.json` 上传；上传重试只使用
同一 Key 和同一内容。成功 PUT 是 Bootstrap 的最后一个外部副作用。上传最多 3 次
（包含首次），每次连接、传输和退避均受 12.1 的剩余上传预算约束，不能一直重试到 URL
过期。预算耗尽仍失败时以专用退出码 `44` 结束，不能把“结果未送达”报告为“业务部署失败”。
`SIGKILL`、内核崩溃和实例断电无法被 Trap，由 Deadline Reconciler 处理。

Result Consumer 将 TOS 通知视为“某 Key 发生变化”的提示，不把事件体当作部署结果。
处理顺序：

1. 按 8.6.4 拆分 TOS `events[]`，使用专用 Inbox 事务持久化全部子事件/拒绝记录后再
   ACK；解析失败不能调用现有 `extractMessageUUID`，也不能无记录地吞掉消息。
2. 异步 Processor 领取 Inbox，校验 Bucket、Prefix、Suffix、事件类型并按完整
   `result_bucket_name + result_object_key` 唯一找到 Attempt；未知 Key 不推进 Pipeline。
3. 根据事件中的 Version ID 读取对象，限制 16 KiB，校验 Content Type、JSON Schema
   和对象 SHA-256。事件未提供 Version ID 时，使用 ETag 条件读取。
4. 对 `resultToken` 计算 SHA-256 并使用常量时间比较，再校验 Deployment ID、Attempt、
   Instance ID、Artifact Hash、Command Revision Public ID 和时间边界。
5. 以 Attempt + `execution_attempt` + 当前 Build 引用 + 预期非终态为 CAS 条件，在一个
   MySQL 事务中写入去除 Token 的结果快照、更新 Plugin Build、完成 Inbox 并生成 Outbox。
   相同 Payload Hash 的重复版本或乱序事件幂等完成；同一 Attempt 出现不同 Payload Hash
   时记录 `DEPLOYMENT_RESULT_CONFLICT` 安全
   告警，不能用后到对象覆盖已经接受的结果。

CAS 未胜出时读取最新状态：相同已接受结果幂等完成，旧 Attempt 或已结束的未知结果
仅记迟到审计，不向 Pipeline 再发终态。不能把正常并发竞争当作可无限重试的 FAILURE。

Attempt 及结果 Key/Token Hash 在 `InvokeCommand` 之前提交。因此即使 ECS 很快完成、
结果事件早于 Invoke HTTP 响应到达，Consumer 也能在 `DISPATCHING` 状态处理它。后到的
Invocation ID 只允许 CAS 更新本 Attempt 的 `pending-<attempt_public_id>`，不能把终态
Attempt 回退到运行态。结果 Payload Hash 在内存中对原始对象字节计算；对象原文和 Token
不得随 Inbox、快照、异常或日志进入数据库。

#### 8.6.4 专用结果 Inbox 与 ACK 契约

现有 `KafkaMessageStore` 只服务 Pipeline/Stage/Job/Plugin 四张领域表，要求消息顶层存在
`messageUUID`；TOS 的 `events[]` 不满足这个协议。本方案新增 `volcano_result_event_inbox`
和专用 Store/Processor，不把原始 TOS 通知交给 `plugin_message` 或业务消息反序列化器。
`MessageCategory` 增加 `VOLCANO_RESULT`，管理恢复路由接入专用适配器；原四类消息处理不变。

- 一个 Kafka Record 可以包含多个事件，按数组下标持久化 `event_index=0..n-1`；在
  同一事务写完该 Record 的所有子事件才允许 ACK。批量 Listener 必须等本批所有 Record
  完成归档后再 ACK；数据库失败不 ACK。
- 合法事件的 `message_uuid` 使用固定、版本化的 UUIDv5 namespace，对
  `schemaVersion + bucket + exactKey + (VERSION,versionId)/(ETAG,etag)` 的长度前缀 UTF-8
  编码求值。Key 仅按官方协议解码一次，不按文件路径归一化；无 Version/ETag 则拒绝。
  持久化完整规范身份并在 UUID 命中后逐字节比较，避免把哈希命中当作身份相等。
- 同时以 `(source_cluster_id, topic, kafka_partition, kafka_offset, event_index)` 唯一
  定位投递位置。不同集群 ID 不可复用；同位置冲突必须校验身份。不同 offset 的同一对象
  版本可以幂等跳过，因为首份 Inbox 已持久化；不要求为每次重复投递新增业务记录。
- 语法错误、空/超限数组或超限 Kafka Record 使用 `event_index=-1` 写一条 `REJECTED`；
  某个子事件非法只拒绝该子项，不丢弃合法同胞。拒绝 UUID 使用另一固定 namespace 对
  源位置求值。保留原文 SHA-256、受限原因和安全字段，不存不受控原文/秘密，随后 ACK。
- 正常记录 `ARCHIVED -> PROCESSING -> SUCCESS`。领取在独立事务 CAS；对象读取和验证
  不持有长数据库事务；最终 Inbox SUCCESS、Attempt/Build 和 Outbox 在同一业务事务提交。
  网络/数据库等可恢复异常写 `FAILURE`；协议/Token/不可变字段错误写 `REJECTED` 并告警。
- ACK 只证明归档完成。ACK 后崩溃遗留 `ARCHIVED/PROCESSING` 或业务 `FAILURE` 不依靠
  Kafka 重投或应用重启自动运行；管理员经 11.5 恢复。重复投递不会重跑已拒绝对象版本。
  Deadline Reconciler 独立保证 Attempt 有界结束，不能把“有 Inbox”写成自动恢复保证。
- 同一 Inbox 最多处理 3 次（含初次和人工 retry）；第 3 次仍失败或崩溃遗留则转
  `REJECTED`，保留告警，
  禁止自动重试和无限 reset。`REJECTED/SUCCESS` 不可重开；新合法对象版本使用新 Inbox，
  仍须经过 Attempt CAS，不能反转已结束的 Pipeline。

这里保证的是 MySQL 原子状态变更和消息幂等，不是 Kafka/MySQL 严格 exactly-once。

### 8.7 实例内目录

```text
/opt/firefly/apps/<application>/
├── current -> releases/<deployment-id>
├── previous -> releases/<previous-deployment-id>
├── .firefly-staging/
│   └── <deployment-id>/
│       ├── artifact.part
│       └── prepared/
├── releases/
│   ├── <deployment-id>/
│   └── ...
└── shared/

/var/lock/firefly-deploy-<application>.lock
```

临时目录放在 `deployRoot` 所在文件系统，是为了保证发布阶段可以使用同文件系统原子
rename。若管理员把 TOS 下载缓存放在 `/var/lib/firefly`，缓存只能作为下载源，最终仍需
先复制到目标父目录的临时文件并完成校验，不能假设跨文件系统 rename 具有原子性。

用户部署脚本只依赖稳定环境变量：

```text
FIREFLY_DEPLOYMENT_ID
FIREFLY_APPLICATION_NAME
FIREFLY_ARTIFACT_TYPE
FIREFLY_DOWNLOAD_FILE       # MANAGED_DOWNLOAD 下已校验的原始下载文件
FIREFLY_RELEASE_DIR         # 本次独占的新 Release 目录
FIREFLY_ARTIFACT_PATH       # 压缩包为解压目录，FILE 为最终文件
FIREFLY_DESTINATION_PATH    # 规范化后的最终文件或版本目录
FIREFLY_CURRENT_LINK
FIREFLY_PREVIOUS_RELEASE
FIREFLY_PREVIOUS_FILE      # FIXED_FILE 被替换前的受控备份；首次部署为空
FIREFLY_SHARED_DIR
```

`CUSTOM_FULL_SCRIPT` 额外提供 `FIREFLY_ARTIFACT_URL`、`FIREFLY_ARTIFACT_SHA256` 和
`FIREFLY_ARTIFACT_SIZE`。环境变量名构成版本化契约；新增变量允许向后兼容，删除或修改
语义必须提升 Command Schema Version。

### 8.8 Bootstrap 与用户指令执行步骤

1. `set -Eeuo pipefail`、`umask 027`，关闭命令回显。
2. 解码并校验全部参数，按 `deployRoot + relativePath + layout` 重新计算最终路径，确认它和
   Plugin 保存的规范化预览一致且仍位于允许根目录内；检查现有父目录不存在符号链接。
   任何下载/发布/用户指令前，检查 `start_before_epoch_seconds` 和剩余上传预算；过晚
   启动禁止业务副作用，尽力上传 `FAILURE/DEPLOYMENT_START_WINDOW_EXPIRED`，失败则等 Deadline。
3. 使用 `flock -n` 获取应用级部署锁；冲突返回明确退出码。
4. 在 `deployRoot/.firefly-staging/<deployment-id>` 创建权限为 `0700` 的独占临时目录；
   相同 Deployment ID 已存在时先核对 Attempt 和内容，禁止复用未知残留目录。
5. `MANAGED_DOWNLOAD` 使用 `curl --fail --location --retry 3 --connect-timeout 10` 下载到
   `.part`，校验实际大小和 `sha256sum` 后原子改名为 `.artifact`；失败立即清理。
6. `TAR_GZ` / `ZIP` 先列出全部归档成员，拒绝绝对路径、`..`、设备文件和越界符号链接，
   解包到临时 `prepared/` 时不保留原 UID/GID；`FILE` 在临时目录生成安全文件并设置
   `0640` 或 `0750`。
7. 对文件执行 `fsync`，再按 Layout 发布：`VERSIONED_DIRECTORY` 把 `prepared/` 原子
   rename 为带 Deployment ID 的最终目录；`FIXED_FILE` 在同一父目录原子替换目标并保留
   previous。发布后设置 `FIREFLY_DESTINATION_PATH` 和 `FIREFLY_ARTIFACT_PATH`。
8. 把 Command Revision 中固定的 `deployScript` 写入权限为 `0700` 的临时文件，以
   `env -i` 仅注入稳定业务环境变量，并在独立的 `bash --noprofile --norc` 子进程执行；
   `MANAGED_DOWNLOAD` 的 Artifact URL 以及所有模式的 Result URL/Token 保留在未导出的父
   Bootstrap 变量中，仅减少意外继承，不是对同 UID 的秘密隔离。以脚本退出码作为
   `USER_DEPLOY` 结果，不使用 `eval` 包装用户正文。
9. `CUSTOM_FULL_SCRIPT` 跳过托管下载、准备和发布步骤，向用户脚本提供短期 URL、期望
   大小、SHA-256 和规范化 Destination；
   用户脚本必须自行下载和准备制品，Firefly 在页面和审计中标记 `integrityManaged=false`。
10. 用户脚本成功后执行配置的本机健康检查。Firefly 不假设服务由 systemd 管理，重启、
   容器更新、软链接切换等业务动作都由用户指令明确完成。
11. 部署指令或健康检查失败时执行可选 `rollbackScript`。未配置回滚，或回滚失败时进入
    `MANUAL_INTERVENTION_REQUIRED`；不能声称已经自动恢复。
12. 仅在全部成功后清理超过 `retainReleases` 的旧 Release；活动版本、上一版本和任何
    Attempt 正在引用的目录不得删除。
13. 根据受信任步骤计算终态，将用户 stdout/stderr 截断、脱敏并以 Base64 放入结果
    信封，原子写本地 `result.json`；完整输出不得进入 TOS 或 Kafka。
14. 使用 12.1 预留的上传预算 PUT `result.json`，收到 TOS 2xx 后清除本地结果文件，再以与
    业务终态对应的退出码结束。stdout 只输出 Deployment ID 和“result delivered”标记，
    不输出 Token、URL 或完整结果信封。

步骤 2～12 共用一个业务预算，不能每一步重新计时；健康检查、回滚和进程组终止也计算在
该预算内。父 Bootstrap 用独立 watchdog 监督业务子进程组，提前发送 TERM 并在业务
预算耗尽前完成有界 KILL；之后才使用上传预算。不能把整个 Bootstrap 包在只覆盖业务
时长的 `timeout` 中。回滚来不及完成时上报人工介入，不侵占结果上传预算，见 12.1。

成功结果中的安全业务摘要示例（非 stdout 格式）：

```json
{"schemaVersion":1,"deploymentId":"dep_...","status":"SUCCESS","destination":"/opt/firefly/apps/order-service/releases/dep_...","rolledBack":false}
```

脚本使用约定退出码：

| 退出码 | 含义 |
| --- | --- |
| `10` | 参数或路径校验失败 |
| `11` | 部署锁冲突 |
| `20` | 下载失败或 URL 过期 |
| `21` | 文件大小不一致 |
| `22` | SHA-256 不一致 |
| `30` | 包类型、文件名或解包安全校验失败 |
| `40` | 用户部署指令失败 |
| `41` | 部署或健康检查失败，但用户回滚指令成功 |
| `42` | 用户回滚指令失败，需要人工介入 |
| `43` | 用户指令输出超限或结果信封无效 |
| `44` | 业务步骤已结束，但 TOS 结果对象上传失败，等待 Deadline 对账 |

### 8.9 用户指令的安全边界

允许用户 Bash 意味着该用户能够以 `runAsUser` 权限在目标实例执行代码。正则校验、Shell
lint 或关键字黑名单都不能把任意脚本变成安全脚本，因此本设计不宣称对恶意脚本提供
沙箱。MVP 明确选择“脚本作者、审批者和目标主机管理员完全可信”的模型：

- `env -i`、未导出父变量、`0700` 文件和独立 Bash 子进程不能阻止同 UID 代码通过文件、
  进程访问或后台进程获取 Token/URL。两种执行模式都不承诺抵御同机恶意脚本伪造结果。
- Token 用于识别持有本次秘密的结果提交者、阻止不知 Token 的主体伪造对应 Attempt，
  不是只防传输错误，也不是可信执行证明。持有 Token/有效 PUT URL 的脚本或能查询对应
  Invocation 参数的身份处于结果可信域内；批准部署必须接受这个边界。
- 如未来要运行不可信脚本，必须另行设计独立的受保护 supervisor/uploader 身份、文件/
  进程访问控制及 sudo 边界，保证用户不能读取参数/秘密、修改结果源或冒用上传器。
  单纯换 UID 或追加一次同 UID 上传步骤不算隔离；不在本 MVP 中假定已实现。

- 只有部署管理员可以创建或修改脚本；每次修改生成新 Revision 和新 Hash。
- 使用无登录、最小权限的专用 `firefly-deploy` 用户；仅通过受控 `sudoers` 允许必要动作，
  例如重启指定服务，禁止通配符命令和任意 root Shell。
- 实例上不保存 AK/SK、STS Token；`MANAGED_DOWNLOAD` 不主动导出预签名 URL，
  `CUSTOM_FULL_SCRIPT` 主动提供制品 URL。精确脱敏只能防止意外打印，不能防止编码、
  拆分或网络外传；两种模式都只能授予可信管理员并尽量使用无公网出口实例。
- stdout/stderr 属于不可信数据，必须截断、脱敏并作为纯文本展示，不能当 JSON、HTML、
  Shell 或后续 Pipeline 参数再次执行。
- 脚本执行前展示完整 diff；运行审计固定记录 Revision、Hash、批准人和 Instance ID。

### 8.10 部署顺序

```mermaid
sequenceDiagram
    participant P as Pipeline Plugin
    participant D as Deployment Service
    participant AT as Artifact TOS
    participant E as ECS OpenAPI
    participant A as Cloud Assistant Agent
    participant RT as Result TOS
    participant K as Result Kafka Topic
    participant C as Result Consumer
    participant R as Deadline Reconciler

    P->>D: start(deployBuildId, attempt)
    D->>AT: HeadObject(bucket, key, version)
    AT-->>D: version/etag/size/crc64/metadata
    D->>E: DescribeInstance + Agent + Command
    E-->>D: ready
    D->>D: check result channel + budget + credential expiry
    D->>AT: Presign GET artifact (short TTL)
    AT-->>D: artifact URL
    D->>RT: Presign PUT exact result key (short TTL)
    RT-->>D: result URL
    D->>D: validate combined bytes; persist attempt + pending ID + key/hash + deadlines
    D->>E: InvokeCommand(commandId, instanceId, safe params)
    E-->>D: invocationId
    D-->>P: DISPATCHED
    A->>AT: GET artifact URL (managed or user script)
    A->>A: start gate; bounded business -> deployScript -> health/rollback
    A->>RT: PUT result envelope within upload reserve
    RT-->>K: ObjectCreated(bucket, key, version/etag)
    K->>C: result event
    C->>C: archive all events[] in Result Inbox transaction
    C-->>K: ACK after commit
    C->>RT: GetObject(exact key, version/etag)
    RT-->>C: result envelope
    C->>C: validate schema + token + immutable fields
    C->>C: CAS terminal state + enqueue Outbox
    alt no valid result before result_deadline_at
        R->>RT: one Head/Get on expected key
        opt result object still missing
            R->>E: one DescribeInvocation/Result reconciliation
            E-->>R: terminal/running/unknown
        end
        R->>R: persist reconciled or RESULT_UNKNOWN state
    end
```

图中的 Deadline Reconciler 只领取已经超过 `result_deadline_at` 的 Attempt，不按执行中
状态周期轮询。正常结果对象即使先于 `InvokeCommand` HTTP 响应到达，也能通过 Deployment
ID 与 execution attempt 完成关联；后到的 Invocation ID 只 CAS 更新本 Attempt 的占位值，
不得覆盖终态。图中省略了非法对象、ACK 后崩溃的手动 Inbox 恢复及 9.4 的有界失败分支。

## 9. 部署持久化和状态机

### 9.1 配置表

```sql
CREATE TABLE `firefly`.`volcano_command_revision`
(
    `id`                       BIGINT(20) NOT NULL AUTO_INCREMENT,
    `public_id`                VARCHAR(64) NOT NULL,
    `connection_id`            BIGINT(20) NOT NULL,
    `region`                   VARCHAR(64) NOT NULL,
    `provisioning_operation_id` VARCHAR(64) NOT NULL,
    `command_id`               VARCHAR(128) NOT NULL,
    `command_schema_version`   INT NOT NULL,
    `execution_mode`           VARCHAR(32) NOT NULL,
    `run_as_user`              VARCHAR(64) NOT NULL,
    `working_directory`        VARCHAR(32) NOT NULL,
    `deploy_script`            TEXT NOT NULL,
    `rollback_script`          TEXT NOT NULL,
    `deploy_script_sha256`     CHAR(64) NOT NULL,
    `rendered_command_sha256`  CHAR(64) NOT NULL,
    `status`                   VARCHAR(32) NOT NULL,
    `created_by`               VARCHAR(128) NOT NULL,
    `approved_by`              VARCHAR(128) NOT NULL DEFAULT '',
    `approved_at`              DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    `created_at`               DATETIME(6) NOT NULL,
    `updated_at`               DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_volcano_command_revision_public` (`public_id`),
    UNIQUE INDEX `uidx_volcano_command_operation` (`provisioning_operation_id`),
    UNIQUE INDEX `uidx_volcano_command_revision_cloud`
        (`connection_id`, `region`, `command_id`),
    INDEX `idx_volcano_command_revision_hash`
        (`connection_id`, `region`, `rendered_command_sha256`)
);

CREATE TABLE `firefly`.`volcano_deploy_config`
(
    `id`                           BIGINT(20) NOT NULL AUTO_INCREMENT,
    `job_config_id`                BIGINT(20) NOT NULL,
    `region`                       VARCHAR(64) NOT NULL,
    `bucket_name`                  VARCHAR(255) NOT NULL,
    `artifact_prefix`              VARCHAR(2048) NOT NULL,
    `artifact_selection_mode`      VARCHAR(32) NOT NULL,
    `current_level_only`           TINYINT(1) NOT NULL DEFAULT 1,
    `allowed_artifact_handling`    VARCHAR(128) NOT NULL,
    `default_artifact_handling`    VARCHAR(32) NOT NULL,
    `destination_output_file_name` VARCHAR(255) NOT NULL DEFAULT '',
    `destination_executable`       TINYINT(1) NOT NULL DEFAULT 0,
    `instance_id`                  VARCHAR(128) NOT NULL,
    `instance_name_snapshot`       VARCHAR(255) NOT NULL DEFAULT '',
    `private_ip_snapshot`          VARCHAR(64) NOT NULL DEFAULT '',
    `vpc_id_snapshot`              VARCHAR(128) NOT NULL DEFAULT '',
    `zone_id_snapshot`             VARCHAR(128) NOT NULL DEFAULT '',
    `command_revision_id`          BIGINT(20) NOT NULL,
    `application_name`             VARCHAR(64) NOT NULL,
    `deploy_root`                  VARCHAR(1024) NOT NULL,
    `destination_relative_path`    VARCHAR(1024) NOT NULL,
    `destination_layout`           VARCHAR(32) NOT NULL,
    `health_check_mode`            VARCHAR(32) NOT NULL,
    `health_check_url`             VARCHAR(2048) NOT NULL,
    `health_check_timeout_seconds` INT NOT NULL,
    `health_check_success_count`   INT NOT NULL,
    `command_timeout_seconds`      INT NOT NULL,
    `retain_releases`              INT NOT NULL,
    `created_at`                   DATETIME(6) NOT NULL,
    `updated_at`                   DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_volcano_deploy_job` (`job_config_id`),
    INDEX `idx_volcano_deploy_instance` (`region`, `instance_id`),
    INDEX `idx_volcano_deploy_command` (`command_revision_id`)
);

CREATE TABLE `firefly`.`volcano_artifact_selection`
(
    `id`                    BIGINT(20) NOT NULL AUTO_INCREMENT,
    `public_id`             VARCHAR(64) NOT NULL,
    `pipeline_build_id`     BIGINT(20) NOT NULL,
    `job_config_id`         BIGINT(20) NOT NULL,
    `region`                VARCHAR(64) NOT NULL,
    `bucket_name`           VARCHAR(255) NOT NULL,
    `object_key`            VARCHAR(2048) NOT NULL,
    `object_version_id`     VARCHAR(512) NOT NULL DEFAULT '',
    `object_etag`           VARCHAR(512) NOT NULL,
    `object_crc64`          VARCHAR(64) NOT NULL DEFAULT '',
    `object_sha256`         CHAR(64) NOT NULL,
    `object_size`           BIGINT NOT NULL,
    `object_last_modified`  DATETIME(6) NOT NULL,
    `artifact_handling`     VARCHAR(32) NOT NULL,
    `selection_source`      VARCHAR(32) NOT NULL,
    `selected_by`           VARCHAR(128) NOT NULL,
    `selected_at`           DATETIME(6) NOT NULL,
    `created_at`            DATETIME(6) NOT NULL,
    `updated_at`            DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_volcano_artifact_selection_public` (`public_id`),
    UNIQUE INDEX `uidx_volcano_artifact_selection_build_job`
        (`pipeline_build_id`, `job_config_id`),
    INDEX `idx_volcano_artifact_selection_object`
        (`region`, `bucket_name`, `object_key`(255))
);
```

`volcano_deploy_config` 只保存可选制品范围，`artifact_selection_mode` 在 MVP 中只能为
`MANUAL_AT_RUN`。`allowed_artifact_handling` 使用排序后的受控枚举集合序列化，不接受
任意字符串。`volcano_artifact_selection` 是手动执行创建的不可变快照；
`selection_source=MANUAL`，且 `(pipeline_build_id, job_config_id)` 唯一约束保证每个部署
Job 在一次 Build 中恰好选择一个制品。

### 9.2 Plugin Build 与 Attempt

```sql
CREATE TABLE `firefly`.`volcano_deploy_build`
(
    `id`                    BIGINT(20) NOT NULL AUTO_INCREMENT,
    `plugin_id`             BIGINT(20) NOT NULL,
    `job_build_id`          BIGINT(20) NOT NULL,
    `artifact_selection_id` BIGINT(20) NOT NULL,
    `deploy_status`         VARCHAR(32) NOT NULL,
    `execution_attempt`     INT NOT NULL DEFAULT 0,
    `current_attempt_id`    BIGINT(20) NOT NULL DEFAULT 0,
    `created_at`            DATETIME(6) NOT NULL,
    `updated_at`            DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_volcano_deploy_build_job` (`job_build_id`),
    UNIQUE INDEX `uidx_volcano_deploy_build_selection` (`artifact_selection_id`),
    INDEX `idx_volcano_deploy_build_plugin` (`plugin_id`)
);

CREATE TABLE `firefly`.`volcano_deployment_attempt`
(
    `id`                    BIGINT(20) NOT NULL AUTO_INCREMENT,
    `public_id`             VARCHAR(64) NOT NULL,
    `deploy_build_id`       BIGINT(20) NOT NULL,
    `artifact_selection_id` BIGINT(20) NOT NULL,
    `execution_attempt`     INT NOT NULL,
    `phase`                 VARCHAR(32) NOT NULL,
    `status`                VARCHAR(32) NOT NULL,
    `region`                VARCHAR(64) NOT NULL,
    `bucket_name`           VARCHAR(255) NOT NULL,
    `object_key`            VARCHAR(2048) NOT NULL,
    `object_version_id`     VARCHAR(512) NOT NULL DEFAULT '',
    `object_etag`           VARCHAR(512) NOT NULL DEFAULT '',
    `object_crc64`          VARCHAR(64) NOT NULL DEFAULT '',
    `object_sha256`         CHAR(64) NOT NULL,
    `object_size`           BIGINT NOT NULL,
    `object_last_modified`  DATETIME(6) NOT NULL,
    `instance_id`           VARCHAR(128) NOT NULL,
    `command_revision_id`   BIGINT(20) NOT NULL,
    `command_id`            VARCHAR(128) NOT NULL,
    `deploy_script_sha256`  CHAR(64) NOT NULL,
    `execution_mode`        VARCHAR(32) NOT NULL,
    `integrity_managed`     TINYINT(1) NOT NULL,
    `invocation_id`         VARCHAR(128) NOT NULL,
    `provider_request_id`   VARCHAR(128) NOT NULL DEFAULT '',
    `destination_layout`    VARCHAR(32) NOT NULL,
    `destination_path`      VARCHAR(1024) NOT NULL,
    `rolled_back`           TINYINT(1) NOT NULL DEFAULT 0,
    `exit_code`             INT NOT NULL DEFAULT -1,
    `output_excerpt`        VARCHAR(8192) NOT NULL DEFAULT '',
    `error_code`            VARCHAR(64) NOT NULL DEFAULT '',
    `error_message`         VARCHAR(2048) NOT NULL DEFAULT '',
    `result_bucket_name`    VARCHAR(255) NOT NULL,
    `result_object_key`     VARCHAR(2048) NOT NULL,
    `result_object_key_sha256` CHAR(64) NOT NULL,
    `result_object_version_id` VARCHAR(512) NOT NULL DEFAULT '',
    `result_object_etag`    VARCHAR(512) NOT NULL DEFAULT '',
    `result_payload_sha256` CHAR(64) NOT NULL DEFAULT '',
    `result_token_sha256`   CHAR(64) NOT NULL,
    `result_schema_version` INT NOT NULL,
    `command_timeout_seconds` INT NOT NULL,
    `result_upload_reserve_seconds` INT NOT NULL,
    `invocation_timeout_seconds` INT NOT NULL,
    `dispatch_started_at`   DATETIME(6) NOT NULL,
    `start_before_at`       DATETIME(6) NOT NULL,
    `upload_deadline_at`    DATETIME(6) NOT NULL,
    `result_deadline_at`    DATETIME(6) NOT NULL,
    `result_received_at`    DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    `reconciliation_status` VARCHAR(32) NOT NULL DEFAULT 'NOT_DUE',
    `reconciliation_attempted_at` DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    `reconciliation_claim_count` INT NOT NULL DEFAULT 0,
    `provider_check_started_at` DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    `reconciler_id`         VARCHAR(128) NOT NULL DEFAULT '',
    `reconcile_lease_expires_at` DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    `started_at`            DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    `finished_at`           DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    `created_at`            DATETIME(6) NOT NULL,
    `updated_at`            DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_volcano_deployment_public` (`public_id`),
    UNIQUE INDEX `uidx_volcano_deployment_attempt`
        (`deploy_build_id`, `execution_attempt`),
    UNIQUE INDEX `uidx_volcano_deployment_invocation` (`invocation_id`),
    UNIQUE INDEX `uidx_volcano_deployment_result_object`
        (`result_bucket_name`, `result_object_key_sha256`),
    INDEX `idx_volcano_deployment_selection` (`artifact_selection_id`),
    INDEX `idx_volcano_deployment_deadline`
        (`status`, `result_deadline_at`, `reconcile_lease_expires_at`)
);
```

与现有项目风格一致，表之间使用逻辑引用，不创建数据库外键。应用服务校验引用存在性、
归属关系和删除顺序；不使用 `findFirst` 掩盖重复或悬空数据。

`volcano_deployment_attempt` 保留对象字段的副本用于独立审计，但其值必须从
`volcano_artifact_selection` 复制，不得再从 Job 配置或 HTTP 请求解析。同时在现有
`pipeline_build` 中增加并持久化 `trigger_model`，以便在数据库层审计手动/自动执行边界。
`result_object_key_sha256` 只用于索引；读取记录后仍必须逐字节比较完整
`result_object_key`，不能把 Hash 相等直接视为 Key 相等。结果事件使用 9.2.1 的独立 Inbox，
业务 UUID 与 Kafka 源位置按照 8.6.4 分别去重。
表中没有 `next_poll_at`：Deadline 字段只用于挑选已经逾期、需要一次性对账的异常记录。
`reconciliation_status` 只允许 `NOT_DUE`、`CLAIMED`、`TOS_RECOVERED`、
`PROVIDER_CHECKED` 和 `RESULT_UNKNOWN`，领取和完成必须使用带旧值条件的 CAS 更新。
成功结果没有经过对账时保留 `NOT_DUE`，不要求所有终态都调用 Reconciler。

`invocation_id` 初值必须为本行 `pending-<public_id>`；只有尚未 Invoke 的已验证 Attempt
才能获得派发执行权。响应/恢复器按 ID 和占位值 CAS 补写 `ivk-*`；匹配终态时仅补 ID，
重复真实 ID 需一致，否则告警。`pending-*` 禁止传给任何云查询 API；不确定的派发不得重发。

### 9.2.1 结果事件 Inbox

```sql
CREATE TABLE `firefly`.`volcano_result_event_inbox`
(
    `id`                     BIGINT(20) NOT NULL AUTO_INCREMENT,
    `message_uuid`           VARCHAR(36) NOT NULL,
    `source_cluster_id`      VARCHAR(64) NOT NULL,
    `topic`                  VARCHAR(249) NOT NULL,
    `kafka_partition`        INT NOT NULL,
    `kafka_offset`           BIGINT NOT NULL,
    `event_index`            INT NOT NULL,
    `event_identity`         LONGTEXT NOT NULL,
    `payload`                LONGTEXT NOT NULL,
    `source_payload_sha256`  CHAR(64) NOT NULL,
    `attempt_id`             BIGINT(20) NOT NULL DEFAULT 0,
    `processing_status`      VARCHAR(32) NOT NULL DEFAULT 'ARCHIVED',
    `processing_attempt`     INT NOT NULL DEFAULT 0,
    `processor_id`           VARCHAR(128) NOT NULL DEFAULT '',
    `processing_started_at`  DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    `processing_finished_at` DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    `last_error`             VARCHAR(2048) NOT NULL DEFAULT '',
    `received_at`            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uidx_volcano_result_message` (`message_uuid`),
    UNIQUE INDEX `uidx_volcano_result_position`
        (`source_cluster_id`, `topic`, `kafka_partition`, `kafka_offset`, `event_index`),
    INDEX `idx_volcano_result_processing` (`processing_status`, `received_at`, `id`),
    INDEX `idx_volcano_result_attempt` (`attempt_id`, `processing_status`)
);
```

`payload` 仅保存白名单化的 TOS 通知子事件；语法无效时存 `{}` 与安全拒绝原因，
`source_payload_sha256` 对原始 Record 求哈希用于审计，不存原始秘密。`event_identity`
存完整规范身份（拒绝记录为源位置身份），UUID 命中时比较它而非忽略冲突。
`attempt_id=0` 仅允许尚未关联或拒绝事件，处理已知 Key 后绑定真实 ID。
两个固定 namespace 在实现常量/协议测试中锁定，不随配置重启变化；既有事件需升级时
使用新协议版本，不能悄悄改变去重规则。

### 9.3 状态

Attempt `phase`：

```text
CREATED
VALIDATING_ARTIFACT
VALIDATING_TARGET
DISPATCHING
DISPATCH_UNKNOWN
WAITING_RESULT_EVENT
PROCESSING_RESULT
RECONCILING
DOWNLOADING
PREPARING_ARTIFACT
RUNNING_DEPLOY_SCRIPT
HEALTH_CHECKING
RUNNING_ROLLBACK_SCRIPT
FINISHED
```

云助手在命令结束前不保证返回可解析的实时阶段，所以 Firefly 分发后保持
`WAITING_RESULT_EVENT`。`DOWNLOADING` 到 `RUNNING_ROLLBACK_SCRIPT` 是可信 Bootstrap
在终态信封中上报的 `failedPhase`，用于失败定位；MVP 不根据 stdout 或定时查询猜测实时
阶段。Kafka Consumer 领取事件后短暂进入 `PROCESSING_RESULT`，仅逾期异常进入
`RECONCILING`。

Attempt `status`：

```text
PENDING -> RUNNING -> SUCCESS
                   -> FAILURE
                   -> ROLLED_BACK
                   -> CANCELLED
                   -> MANUAL_INTERVENTION_REQUIRED
                   -> RESULT_UNKNOWN
```

`ROLLED_BACK` 对 Pipeline 是失败，因为目标版本未成功上线；它与
`MANUAL_INTERVENTION_REQUIRED` 分开，便于值班人员判断线上是否已经恢复旧版本。
`RESULT_UNKNOWN` 表示缺少可信结果：包括对象缺失、协议/认证失败、读取失败、派发未知
或对账中断；对 Pipeline 按失败处理并告警，绝不把“没有失败证据”解释为成功。
它不是“进程已经停止”。MVP 不为已派发的 Once 命令产生 `CANCELLED`；该值仅预留，
不得因 UI 请求停止、API 超时或未知结果而写入。

所有状态更新必须带当前 `execution_attempt` 和期望旧状态条件，更新行数不是 1 时按并发
冲突处理。Plugin 终态消息的 UUID 继续使用现有 `BusinessMessageUUID.plugin(...)` 规则，
依靠 Inbox/Outbox 保证重复消息不重复推进 Job。

### 9.4 事件消费、超时与恢复

- Result Consumer 使用独立 Consumer Group 与专用结果 Inbox，按 8.6.4 先持久化、再 ACK、
  后异步校验。归档不等于业务成功，ACK 后崩溃/FAILURE 需要人工恢复或 Deadline 收敛。
- Attempt、预期结果 Key、Token Hash 和 Deadline 必须在 `InvokeCommand` 前提交。因此结果
  事件先于 Invoke HTTP 响应、服务实例重启或 Kafka 短暂不可用都不会丢失关联。
- 正常链路不调用 `DescribeInvocations` / `DescribeInvocationResults`。Deadline Reconciler
  只扫描 `result_deadline_at <= now` 且仍非终态的记录，用
  `reconciler_id + reconcile_lease_expires_at` CAS 领取异常处理任务，同时递增
  `reconciliation_claim_count`，默认最多 2 次领取（初次 + 一次崩溃接管）。
- 对逾期 Attempt，执行一次有界 Head/Get 计划，直查唯一预期结果 Key 的当前版本并校验
  上传期限。合法结果按正常 CAS 提交，覆盖通知丢失；读取不受长数据库事务保护。
  对同一 Key/Version 已有 REJECTED 的记录直接使用拒绝结论，不反复解析。
- 对象存在但 Schema/Token/不可变字段/上传期限无效：隔离该版本并将仍非终态 Attempt
  CAS 到 `RESULT_UNKNOWN`、写安全原因和 Outbox，结束对账，不再退回可领取状态。
  不枚举历史版本寻找“某个成功结果”；后续合法事件只能按迟到规则审计。
- 对象明确缺失时，允许一个逻辑 Cloud Assistant 诊断计划。必须先 CAS 设置
  `provider_check_started_at`，再按需要各调用至多一次 DescribeInvocations / Results；
  传输层至多 2 次尝试，整体时限短于 Lease。启动标记已写入的计划不会因 Lease 接管
  再次发起，避免崩溃后无限 Describe；缺少诊断结果则以未知结束。
- 云端仍在运行、已终态但没有可信信封、读取错误或权限错误时，记录安全诊断并进入
  `RESULT_UNKNOWN`；不能从云助手退出码伪造业务成功，也不调用 StopInvocation。
- `InvokeCommand` 超时且仍是占位 ID 时进入 `DISPATCH_UNKNOWN`。上述单次诊断只可按
  持久化 Deployment Tag/操作标记做唯一性核对；找到唯一真实 ID 可 CAS 补写，找不到、
  多个匹配或 API 不支持精确筛选均不重发部署，以 `RESULT_UNKNOWN` 结束。
- 外部调用全部设总时限，必须在 Lease 到期前提交或放弃；写库带 owner、当前 Lease 和
  领取次数 fencing 条件。崩溃遗留 Lease 最多接管一次；第二次 Lease 仍过期时，下一次
  扫描只执行本地 CAS，将非终态置为 `RESULT_UNKNOWN`，不再访问 TOS/ECS。
- 已终态 Attempt 不再访问云 API；Outbox 可独立恢复终态消息发布。Deadline 扫描频率只
  影响异常发现延迟，不构成运行期轮询。
- Reconciler 直接以 Attempt CAS + Outbox 事务结束部署，不伪造 Kafka Record 或抢写其他
  Processor 持有的 Inbox；已归档事件之后恢复时按最新终态幂等完成/仅追加审计。
- Result Consumer 与 Reconciler 竞争同一 Attempt 时，以状态 CAS 决定唯一胜者。进入
  `RESULT_UNKNOWN` 后收到的合法迟到结果只追加到安全审计并触发人工复核，不自动反转已
  发送给 Pipeline 的失败终态，也不能覆盖更新 Attempt 的已接受结果 Hash。

在 Deadline 之前，未知 Key、畸形 Kafka 消息或无效对象版本只使对应 Inbox REJECTED，
不能凭不可信消息立即将合法 Attempt 判失败。该 Attempt 可以等待其他合法版本到达，
但不会无限重试同一版本；到期后必须走上述明确终态分支。无效通知的隔离与 Attempt 的
最终处置是两件事，防止攻击者仅投递假事件就中断部署。

### 9.4.1 Once 超时与停止语义

官方 StopInvocation 只能停止定时/周期任务的后续执行，不能为本设计的 Once 部署提供
可靠的即时进程终止保证。MVP 不调用该 API、不暴露停止按钮/成功停止响应，也不授予
`ecs:StopInvocation`。

业务超时由 Bootstrap 的业务 watchdog 处理；云助手 `InvokeCommand.Timeout` 是整个命令
的最后硬上限。硬超时或主机故障可能导致无法上传结果，因此最后以 Deadline 进入
`RESULT_UNKNOWN`/人工核查，不能把硬超时当作所有后台服务已停止或变更已回滚的证明。
未确认旧执行和线上状态前，不允许对未知部署自动重试。未来如要提供立即取消，必须先
设计独立的受控终止协议，并用实际进程、子进程与副作用的 Staging 测试证明；单纯 API
返回成功不算满足取消契约。

## 10. Firefly Plugin 接入

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
├── service/VolcanoCommandRevisionService.java
├── service/VolcanoCommandGarbageCollector.java
├── service/VolcanoArtifactSelectionService.java
├── service/VolcanoDeploymentService.java
├── service/VolcanoDeploymentResultStore.java
├── service/VolcanoDeploymentResultConsumer.java
├── service/VolcanoResultEventInboxStore.java
├── service/VolcanoResultEventProcessor.java
├── service/VolcanoResultChannelReadinessService.java
├── service/VolcanoCommandSizeValidator.java
├── service/VolcanoDeploymentDeadlineReconciler.java
├── service/VolcanoDeploymentReconciliationService.java
├── service/VolcanoDeploymentStateService.java
├── model/...
└── dao/...

firefly-app/src/main/java/firefly/service/pluginconfig/impl/
└── VolcanoDeployPluginConfigService.java

firefly-app/src/main/java/firefly/service/pluginbuild/impl/
└── VolcanoDeployPluginBuildService.java
```

修改点：

1. `PluginType` 增加 `VOLCANO_DEPLOY`。
2. `VolcanoDeployPluginConfigService` 实现 `IPluginConfig`，保存并查询
   `volcano_deploy_config` 中的制品范围；脚本发生变化时调用
   `VolcanoCommandRevisionService` 创建新的不可变 Command Revision。
3. `VolcanoArtifactSelectionService` 查询每个 Job 的最近制品，并在手动执行创建
   Build 前完成 Job/Prefix/HeadObject 校验与快照持久化。
4. `PipelineBuildRequest`、`PipelineBuildDto`、`PipelineBuild` 和 `JobBuildContext` 增加手动运行
   输入传递所需字段；`PipelineBuildServiceImpl.buildPipeline` 在创建 Plugin Build 前为每个
   Volcano Job 解析唯一 `artifactSelectionId`。
5. `VolcanoDeployPluginBuildService` 实现 `IPluginBuild`；`executePluginBuild` 只创建
   Attempt、校验并分发云助手命令，不同步等待结果。
6. `VolcanoDeploymentResultConsumer` 在同一归档事务中保存 `events[]` 后 ACK，
   再交给专用 Processor 读取并校验 TOS 对象；Processor 在另一业务事务更新
   Inbox、Attempt、Plugin Build 并向现有 Plugin Topic 写 Outbox，正常路径不查询云助手。
7. `VolcanoDeploymentDeadlineReconciler` 只处理逾期 Attempt，先读预期 TOS Key，再执行至多
   一次 Cloud Assistant 对账。
8. `PipelineWorkspaceService` 删除 Pipeline 时先校验没有运行中的部署，再按逻辑引用顺序
   删除 Volcano Plugin 配置和 Pipeline Binding；Command Revision 先标记 `ORPHANED`，待
   没有配置和 Attempt 引用后由 GC 删除云端 Command；仅当 Connection 标记为 Pipeline
   私有且不再被其他 Binding 引用时，才删除其凭据密文。
9. 新增结果 Inbox DAO/实体与 `MessageCategory.VOLCANO_RESULT`；管理恢复接口按该 category
   路由到专用 Store/Processor，不复用要求业务消息 JSON 的解析器。Result Kafka Consumer
   Factory 独立配置集群身份、SASL、手动 ACK 和安全消息大小限制。

当前静态 `PluginServiceParser.PLUGIN_MAP` / `PLUGIN_BUILD_MAP` 可先兼容，但建议改为构造器
注入后生成不可变 Map，并在启动时检测重复 `PluginType`，避免静态可变状态影响测试。

## 11. 管理与查询 API

### 11.1 手动执行 API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/volcano/pipelines/{pipelineId}/jobs/{jobUuid}/artifacts/recent?limit=10` | 手动执行弹窗查询该 Job 范围内的最近制品 |
| `POST` | `/manual_trigger/pipeline` | 提交手动执行及每个 Volcano Job 的制品选择，成功返回 Pipeline Build ID |

弹窗打开时先读取 Pipeline 中所有 `MANUAL_AT_RUN` Job，并行请求各自的最近 10 个
制品。确认页必须展示 Job 名称、TOS Prefix、Key、大小、LastModified、Version ID/ETag、
SHA-256 和 handling。任一 Job 没有选择或制品被替换时，整个手动执行失败，不部分创建
Build。

### 11.2 Deployment API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/volcano/deployments/{publicId}` | 查询部署快照和当前状态 |
| `GET` | `/api/volcano/deployments` | 按状态、实例、应用分页查询 |

MVP 不提供 `/api/volcano/deployments/{publicId}/stop`；前端不显示可用停止按钮。若兼容层
保留旧设计路径，必须明确返回 `409 DEPLOYMENT_STOP_UNSUPPORTED` 且不改变部署状态，
不得返回“停止成功”。异常运行的处理见 9.4.1。

部署重试沿用 `/pipeline-builds/{pipelineBuildID}/retry`，不另建绕过 Pipeline 状态机的
重试入口。重试必须复用原 Build 的 `volcano_artifact_selection` 和对象 Version ID/ETag，
不再展示制品选择器，也不重新计算“最近制品”。若需部署另一制品，用户必须发起新的
手动执行。

`RESULT_UNKNOWN` 或人工介入状态必须先由管理员核实旧命令/服务状态并记录处置证据，
才允许通过现有 Retry 发起新 Attempt；不能因旧 Attempt 在数据库终态就假定实例上无执行。

响应只包含安全输出摘要：

```json
{
  "id": "dep_01J...",
  "status": "SUCCESS",
  "phase": "FINISHED",
  "artifact": {
    "tosUri": "tos://firefly-artifacts/order-service/1.8.2/order-service.tar.gz",
    "region": "cn-beijing",
    "bucket": "firefly-artifacts",
    "key": "order-service/1.8.2/order-service.tar.gz",
    "versionId": "...",
    "sha256": "...",
    "size": 18342190
  },
  "target": {
    "instanceId": "i-yc...",
    "commandRevisionId": "vcr_01J...",
    "commandId": "cmd-yc...",
    "invocationId": "ivk-yc..."
  },
  "execution": {
    "mode": "MANAGED_DOWNLOAD",
    "deployScriptSha256": "...",
    "integrityManaged": true
  },
  "destination": {
    "layout": "VERSIONED_DIRECTORY",
    "path": "/opt/firefly/apps/order-service/releases/dep_01J..."
  },
  "rolledBack": false,
  "startedAt": "2026-08-28T10:00:00Z",
  "finishedAt": "2026-08-28T10:01:12Z",
  "error": null
}
```

### 11.3 Command Revision API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/volcano/pipelines/{pipelineId}/commands/validate` | 只校验/渲染，不创建云端命令；返回字节数、Hash、风险提示 |
| `GET` | `/api/volcano/command-revisions/{publicId}` | 管理员查询脚本、Hash、Command ID、状态和审计信息 |

创建 Command Revision 是保存 `VOLCANO_DEPLOY` Plugin 的内部原子流程，不提供独立
“创建后立即执行”接口。查询接口默认返回 Script Hash 和摘要；只有具备配置读取权限时才
返回完整脚本正文，且所有读取操作写审计日志。

### 11.4 制品范围与部署路径预检 API

```http
POST /api/volcano/pipelines/{pipelineId}/deploy-config/validate
```

请求携带 `artifactSource`、`target` 和 `destination`，但不创建 Command 或 Deployment。
后端解析 TOS Prefix URI、执行受限 `ListObjectsV2` 权限检查、验证实例和 Agent、规范化
部署路径，不对具体对象执行 Head Object，并返回：

```json
{
  "canonicalPrefixUri": "tos://firefly-artifacts/order-service/releases/",
  "artifactSourceValid": true,
  "resultChannelReady": true,
  "effectiveCommandTimeoutMaxSeconds": 3120,
  "destinationPreview": "/opt/firefly/apps/order-service/releases/<deployment-id>",
  "atomicPublishSupported": true,
  "warnings": []
}
```

预检结果只用于交互提示，不能替代保存时的 Prefix/实例校验，也不能替代手动
执行提交时对具体制品的 Head Object 和快照锁定。

### 11.5 结果 Inbox 人工恢复

扩展现有管理接口的 category 路由，`VOLCANO_RESULT` 的 URL 值为 `volcano-result`：

| 方法 | 路径 | 约束 |
| --- | --- | --- |
| `POST` | `/admin/kafka-messages/volcano-result/{messageUUID}/retry` | 仅 ARCHIVED/FAILURE、剩余处理次数大于 0 且 Attempt 未终态时允许 |
| `POST` | `/admin/kafka-messages/volcano-result/{messageUUID}/reset-processing` | 先核实旧 worker 已失效，再 CAS 释放 PROCESSING；不清零处理次数 |

两条路径都需要管理员认证与审计。SUCCESS/REJECTED 不可重开，达到 3 次处理上限后拒绝
恢复；Attempt 已终态只可查看/追加迟到审计，不通过重放反转 Pipeline。没有一个“重试”
按钮会再次调用 InvokeCommand。Deadline 处理与人工恢复仍由 Attempt/Inbox CAS 仲裁。

## 12. 配置项

```yaml
firefly:
  volcano:
    storage:
      encryption-key: ${VOLCANO_ENCRYPTION_KEY:}
      key-version: ${VOLCANO_ENCRYPTION_KEY_VERSION:v1}
    tos:
      connect-timeout: ${VOLCANO_TOS_CONNECT_TIMEOUT:3s}
      read-timeout: ${VOLCANO_TOS_READ_TIMEOUT:60s}
      max-download-size: ${VOLCANO_TOS_MAX_DOWNLOAD_SIZE:5GB}
      multipart-threshold: ${VOLCANO_TOS_MULTIPART_THRESHOLD:100MB}
      part-size: ${VOLCANO_TOS_PART_SIZE:20MB}
      task-count: ${VOLCANO_TOS_TASK_COUNT:4}
      max-artifact-scan: ${VOLCANO_TOS_MAX_ARTIFACT_SCAN:10000}
      artifact-list-cache-ttl: ${VOLCANO_TOS_ARTIFACT_CACHE_TTL:30s}
      workspace: ${VOLCANO_TOS_WORKSPACE:/var/lib/firefly/tos}
    sts:
      refresh-before-expiry: ${VOLCANO_STS_REFRESH_BEFORE_EXPIRY:5m}
    ecs:
      connect-timeout: ${VOLCANO_ECS_CONNECT_TIMEOUT:3s}
      read-timeout: ${VOLCANO_ECS_READ_TIMEOUT:15s}
      command-provisioning-enabled: ${VOLCANO_COMMAND_PROVISIONING_ENABLED:true}
      command-name-prefix: ${VOLCANO_COMMAND_NAME_PREFIX:firefly-}
      max-user-script-bytes: ${VOLCANO_MAX_USER_SCRIPT_BYTES:4096}
      max-encoded-command-bytes: ${VOLCANO_MAX_ENCODED_COMMAND_BYTES:16384}
      allowed-run-as-users: ${VOLCANO_ALLOWED_RUN_AS_USERS:firefly-deploy}
      allowed-deploy-roots: ${VOLCANO_ALLOWED_DEPLOY_ROOTS:/opt/firefly/apps}
      allowed-destination-layouts: ${VOLCANO_ALLOWED_DESTINATION_LAYOUTS:VERSIONED_DIRECTORY,FIXED_FILE}
      max-relative-path-bytes: ${VOLCANO_MAX_RELATIVE_PATH_BYTES:1024}
      reject-symlink-path-components: ${VOLCANO_REJECT_SYMLINK_PATH_COMPONENTS:true}
      custom-full-script-enabled: ${VOLCANO_CUSTOM_FULL_SCRIPT_ENABLED:false}
      command-gc-grace-period: ${VOLCANO_COMMAND_GC_GRACE_PERIOD:24h}
    deployment:
      result-upload-reserve: ${VOLCANO_RESULT_UPLOAD_RESERVE:60s}
      dispatch-queue-grace: ${VOLCANO_DISPATCH_QUEUE_GRACE:120s}
      result-deadline-grace: ${VOLCANO_RESULT_DEADLINE_GRACE:5m}
      max-presign-ttl: ${VOLCANO_MAX_PRESIGN_TTL:1h}
      credential-expiry-safety-margin: ${VOLCANO_CREDENTIAL_EXPIRY_SAFETY_MARGIN:30s}
      deadline-scan-interval: ${VOLCANO_DEADLINE_SCAN_INTERVAL:30s}
      reconcile-lease-timeout: ${VOLCANO_RECONCILE_LEASE_TIMEOUT:60s}
      reconcile-io-budget: ${VOLCANO_RECONCILE_IO_BUDGET:40s}
      output-excerpt-bytes: ${VOLCANO_OUTPUT_EXCERPT_BYTES:8192}
    result-store:
      enabled: ${VOLCANO_RESULT_STORE_ENABLED:true}
      object-prefix: ${VOLCANO_RESULT_OBJECT_PREFIX:firefly/deployment-results/v1/}
      buckets: ${VOLCANO_RESULT_BUCKETS:cn-beijing=firefly-control-cn-beijing}
      schema-version: ${VOLCANO_RESULT_SCHEMA_VERSION:1}
      token-bytes: ${VOLCANO_RESULT_TOKEN_BYTES:32}
      max-object-bytes: ${VOLCANO_RESULT_MAX_OBJECT_BYTES:16384}
      lifecycle-days: ${VOLCANO_RESULT_LIFECYCLE_DAYS:14}
      enabled-deployment-regions: ${VOLCANO_RESULT_DEPLOYMENT_REGIONS:cn-beijing,cn-shanghai,cn-guangzhou}
      channel-health-max-age: ${VOLCANO_RESULT_CHANNEL_HEALTH_MAX_AGE:5m}
    result-events:
      source-cluster-id: ${VOLCANO_RESULT_KAFKA_CLUSTER_ID:}
      bootstrap-servers: ${VOLCANO_RESULT_KAFKA_BOOTSTRAP_SERVERS:}
      topic: ${VOLCANO_RESULT_KAFKA_TOPIC:firefly-volcano-result-v1}
      group-id: ${VOLCANO_RESULT_KAFKA_GROUP_ID:firefly-volcano-result-v1}
      security-protocol: ${VOLCANO_RESULT_KAFKA_SECURITY_PROTOCOL:SASL_SSL}
      sasl-mechanism: ${VOLCANO_RESULT_KAFKA_SASL_MECHANISM:PLAIN}
      sasl-username: ${VOLCANO_RESULT_KAFKA_SASL_USERNAME:}
      sasl-password-file: ${VOLCANO_RESULT_KAFKA_SASL_PASSWORD_FILE:}
      max-record-bytes: ${VOLCANO_RESULT_KAFKA_MAX_RECORD_BYTES:1048576}
      max-events-per-record: ${VOLCANO_RESULT_KAFKA_MAX_EVENTS:100}
      max-processing-attempts: ${VOLCANO_RESULT_MAX_PROCESSING_ATTEMPTS:3}
```

Endpoint 默认按 SDK 的 Region 规则生成。自定义 Endpoint 只允许 `https`，Host 必须在
管理员 allowlist 中，防止通过 Connection 配置制造 SSRF。测试环境可显式开启 HTTP。

Result Consumer 使用独立账号，不复用 TOS 投递 Topic 的 SASL 用户。官方 Kafka 推送配置
要求投递用户使用 PLAIN 并满足其 All Permitted 前提，该权限边界由基础设施管理员审核，
不能把它等同于单 Topic 的最小写权限。消费身份也必须与 Broker 配置匹配，仅授予指定 Topic/Group
的必要读取权限。生产要求 `SASL_SSL`、服务器证书和主机名验证，不以关闭 TLS 绕过错误。
`sasl-password-file` 指向由部署系统/Secret Store 挂载的只读秘密文件；配置仅保存路径，
文件不入库、不进 Git、不可被普通用户读取，账号或文件缺失直接启动校验失败。
Consumer Factory 在内存构造转义后的 JAAS 配置，禁止打印密码、JAAS、完整配置 Bean 或
通过 Actuator 暴露这些值；轮换后重建对应 Consumer 并重新校验通道就绪。

### 12.1 业务、上传、派发与交付预算

配置与派发使用同一 `VolcanoDeploymentBudget` 校验器。令 B 为用户配置的业务总秒数，
U 为结果上传保留秒数，Q 为签名准备/数据库提交/HTTP 派发/云助手排队的合计余量，G 为
结果通知交付余量。`t0` 是生成第一条预签名 URL 前固定的 `dispatch_started_at`，而非
收到 Invoke 响应的时间，所有预算快照在 Invoke 前持久化：

```text
invocationTimeoutSeconds = B + U
InvokeCommand.Timeout = invocationTimeoutSeconds
start_before_at = t0 + Q
upload_deadline_at = t0 + Q + invocationTimeoutSeconds
result_deadline_at = upload_deadline_at + G
artifact_presign_expires_at = result_put_expires_at = result_deadline_at
requiredPresignTtl = Q + B + U + G

30 <= B
30 <= invocationTimeoutSeconds <= 86400
requiredPresignTtl <= maxPresignTtl
B <= min(86400 - U, maxPresignTtl - Q - U - G)
```

默认 U=60、Q=120、G=300、maxPresignTtl=3600 秒，因此 B 最大 3120 秒（52 分钟），
不是 86400 秒。B=900 时云助手 Timeout=960 秒，最晚启动为 t0+120 秒，最晚上传为
t0+1080 秒，结果 Deadline/两条 URL 过期时间为 t0+1380 秒。提高业务上限必须同时满足
平台 TTL、安全策略和 API 限制，不能自动延长签名或截断用户超时。

- B 包含下载、解包、用户指令、健康检查、必要回滚、清理与终止子进程的全部时间。父
  Bootstrap 监督业务进程组，例如提前 5 秒 TERM、B 耗尽前 KILL；上传器保留在父进程。
  这个上限只约束命令步骤，不证明已启动的服务/逃逸后台进程被撤销。
- 实例必须在 `start_before_at` 前启动业务，并检查剩余的绝对上传窗口；Q 是平台假设，
  不是火山引擎排队 SLA。超过该窗口拒绝业务副作用，能上报则上传失败结果，否则未知。
  实际签名/提交耗时已消耗 Q；Invoke 前窗口不足时直接拒绝，不重置 t0 扩展旧 URL。
- U 同时覆盖结果生成、最多 3 次 PUT、连接/传输/退避；每次使用剩余预算设置 curl 总
  时限，不能用 G 继续部署或上传。API 硬超时仍可能杀死上传器，Trap 不能保证所有路径送达。
- 两条 URL 有效期需分别受各自签名凭据的实际 expiry 限制：`credentialExpiry >=
  result_deadline_at + credentialExpirySafetyMargin`。AssumeRole 不足时先刷新并重新
  计算两条 URL；STS_SESSION 不足时拒绝并提示轮换。凭据 TTL 比平台限制更短时，进一步
  下调有效 B 上限，不能只检查 URL 参数上的过期时间。
- 主机需同步时钟；云助手 Timeout 使用相对时长，业务 watchdog 使用单调计时，绝对
  启动/上传门限采用受校时保证的 UTC。时钟偏差超出部署环境验收界限则禁止部署。
- Reconciler 的 I/O 总预算默认 40 秒，必须小于 60 秒 Lease；它不能沿用更长的普通
  TOS 下载 read-timeout。Lease fencing、最多两次领取与最终无 I/O 收敛见 9.4。

任一预算不成立时在 Invoke 前返回 `DEPLOYMENT_TIMEOUT_BUDGET_INVALID` 或
`DEPLOYMENT_CREDENTIAL_TTL_INSUFFICIENT`。UI 预检显示有效上限，派发仍再次校验。
`deadline-scan-interval` 只影响异常发现延迟，不跟踪运行中命令。

## 13. IAM 最小权限

建议创建 Firefly 专用子用户和专用 AK/SK，不使用主账号 AK/SK。制品访问身份、平台结果
存储身份和 Bucket 通知配置身份必须分离；Pipeline Connection 的 AK/SK/STS 不需要访问
Firefly 平台结果 Bucket。

Pipeline Connection 对制品 TOS 只授予目标 Bucket/Prefix：

```text
tos:ListObjects            仅管理页面需要
tos:HeadObject
tos:GetObject
tos:GetObjectVersion      使用版本控制时
```

Firefly 平台 Result Store 身份按 Region 只授予固定结果 Bucket/Prefix：

```text
tos:PutObject             仅用于签发精确 Key 的短期 PUT URL
tos:HeadObject
tos:GetObject
tos:GetObjectVersion      开启版本控制时
```

运行期身份不需要 List、Delete 或修改通知规则。Bucket 生命周期负责过期清理。只有独立的
基础设施 Provisioner 才能修改 Bucket Notification，独立预检身份仅能 Get；TOS 投递火山
Kafka 使用官方授权流程的 `TOSNotiKafkaRole`，该角色不授予 Firefly 应用或 ECS。ECS 只拿单次 Attempt 的
精确 Key PUT URL，不拿 TOS 或 Kafka 长期凭据。

部署运行角色只授予：

```text
ecs:DescribeInstances
ecs:DescribeCloudAssistantStatus
ecs:DescribeCommands
ecs:InvokeCommand
ecs:DescribeInvocations          仅 Deadline 单次对账
ecs:DescribeInvocationResults    仅 Deadline 单次对账
```

配置发布/垃圾回收角色额外授予：

```text
ecs:CreateCommand
ecs:DeleteCommand
```

生产推荐在 `STS_ASSUME_ROLE` 下配置独立的 `FireflyCommandProvisionerRole` 和
`FireflyDeployRuntimeRole`；若 MVP 暂时共用一个 Connection，则身份权限是两组权限的
并集，但应用代码仍必须隔离配置发布与运行执行服务。任何角色都不授予
`ecs:RunCommand`、`ecs:ModifyCommand`，也不授予 ECS 创建、删除或关机权限。

`ecs:InvokeCommand` 尽可能用 Resource、项目或 Tag 限制到名称前缀为 `firefly-` 且由
Firefly 创建的 Command 和目标实例。云端 Command 的 Tag 至少包含
`managed-by=firefly`、Pipeline UUID、Plugin UUID 和 Script Hash；删除时必须校验这些 Tag，
禁止 GC 删除非 Firefly 命令。

`ecs:DescribeInvocations` 可读回自定义 Parameters。可查询对应 Invocation 的账号/角色、
云控制台管理员，以及主机上能读执行脚本的主体均纳入该 Attempt 的结果可信域。
仅做状态展示的 Firefly 用户只读脱敏本地快照，不继承这项云权限。不能声称“只读 API”
不会接触秘密；Result Store 身份分离也不能消除这条间接读取路径。参数只携带每 Attempt
短期 Token/URL，禁止长期结果凭据；DTO 丢弃和日志白名单的落实见 5.3。

通道预检使用独立只读身份读取通知配置，Provisioner 才能修改；Kafka 生产与消费凭据
分别管理。运行角色不授予 `ecs:StopInvocation`，不将它作为超时恢复依赖。

制品与结果 TOS Bucket 均保持私有。制品 GET 和结果 PUT 的 TTL 严格使用 12.1 的
`Q + B + U + G` 及实际签名凭据 expiry 校验，不另写一套公式。
结果 PUT URL 必须固定 HTTP 方法、Bucket、完整 Key 和 `Content-Type: application/json`；
URL 与 `resultToken` 不得进入日志、Kafka、stdout/stderr 或普通异常。

## 14. 重试和错误处理

### 14.1 重试规则

| 操作 | 自动重试 | 规则 |
| --- | --- | --- |
| `ListObjects` / `HeadObject` / `GetObject` | 是 | 网络错误、429、部分 5xx；指数退避和抖动 |
| 本地断点下载 | 是 | 使用同一 Version ID/ETag 和 checkpoint |
| ECS 内结果 `PutObject` | 有界 | 共最多 3 次尝试，同一 Key/内容；受 U、绝对上传期限与凭据有效期共同限制 |
| TOS `ObjectCreated` 投递 | 可能重复 | 专用 Inbox 对同一版本幂等；ACK 后业务失败不会触发 Kafka 重投 |
| 结果 Inbox FAILURE/遗留 PROCESSING | 不自动重试 | 管理员恢复；含初次最多 3 次，REJECTED/SUCCESS 不可重开 |
| `DescribeInvocation*` | 不调度重试 | 一个持久化诊断计划；每种读取至多一次逻辑调用，传输层共最多 2 次尝试且受 I/O 总预算限制 |
| `CreateCommand` | 条件重试 | 超时后先按 Firefly Tag + rendered Hash 对账，确认不存在才重建 |
| `DeleteCommand` | 是 | 仅 GC 调用；Not Found 视为幂等成功 |
| `InvokeCommand` | 否，除非能证明未创建 | 超时进入 `DISPATCH_UNKNOWN` 并先对账 |
| 实例内制品 `curl` | 有界 | `--retry 3` 表示首次加至多 3 次重试，共最多 4 次；仍受业务 B 与原 URL TTL 约束 |

403、404、参数错误、校验失败和脚本安全校验失败不自动重试。

Pipeline Retry 是对原 Pipeline Build 的恢复，不属于新的手动执行：它只能使用原
`volcano_artifact_selection`，并根据该快照重新生成短期预签名 URL。如果无 Version ID
且 ETag 条件已不匹配，重试以 `TOS_OBJECT_CHANGED` 失败，不能要求用户在重试中换制品。

### 14.2 Firefly 错误码

```text
VOLCANO_ENCRYPTION_NOT_CONFIGURED
VOLCANO_CONNECTION_NOT_FOUND
VOLCANO_CREDENTIAL_INVALID
VOLCANO_STS_EXPIRED
VOLCANO_ASSUME_ROLE_FAILED
VOLCANO_ACCESS_DENIED
VOLCANO_ENDPOINT_REJECTED
VOLCANO_PIPELINE_BINDING_NOT_FOUND
VOLCANO_ARTIFACT_SELECTION_REQUIRED
VOLCANO_ARTIFACT_SELECTION_INVALID
VOLCANO_MANUAL_ARTIFACT_SELECTION_ONLY
TOS_OBJECT_NOT_FOUND
TOS_OBJECT_ARCHIVED
TOS_OBJECT_TOO_LARGE
TOS_OBJECT_CHANGED
TOS_CHECKSUM_MISSING
TOS_CHECKSUM_MISMATCH
TOS_URI_INVALID
ARTIFACT_SCAN_LIMIT_EXCEEDED
ECS_INSTANCE_NOT_FOUND
ECS_INSTANCE_NOT_RUNNING
ECS_INSTANCE_REGION_MISMATCH
ECS_CLOUD_ASSISTANT_UNAVAILABLE
ECS_COMMAND_NOT_ALLOWED
ECS_COMMAND_CREATE_FAILED
ECS_COMMAND_CONTENT_TOO_LARGE
ECS_COMMAND_PARAMETERS_TOO_LARGE
ECS_COMMAND_REVISION_NOT_READY
DEPLOYMENT_SCRIPT_INVALID
DEPLOYMENT_CUSTOM_SCRIPT_DISABLED
DEPLOYMENT_PATH_INVALID
DEPLOYMENT_PATH_OUTSIDE_ALLOWLIST
DEPLOYMENT_PATH_SYMLINK_REJECTED
DEPLOYMENT_LAYOUT_INCOMPATIBLE
DEPLOYMENT_ATOMIC_PUBLISH_UNAVAILABLE
DEPLOYMENT_LOCKED
DEPLOYMENT_DISPATCH_UNKNOWN
DEPLOYMENT_TIMEOUT
DEPLOYMENT_TIMEOUT_BUDGET_INVALID
DEPLOYMENT_CREDENTIAL_TTL_INSUFFICIENT
DEPLOYMENT_START_WINDOW_EXPIRED
DEPLOYMENT_STOP_UNSUPPORTED
DEPLOYMENT_DOWNLOAD_FAILED
DEPLOYMENT_ARTIFACT_PREPARE_FAILED
DEPLOYMENT_USER_SCRIPT_FAILED
DEPLOYMENT_OUTPUT_INVALID
DEPLOYMENT_HEALTH_CHECK_FAILED
DEPLOYMENT_ROLLED_BACK
DEPLOYMENT_ROLLBACK_FAILED
DEPLOYMENT_RESULT_UPLOAD_FAILED
DEPLOYMENT_RESULT_EVENT_INVALID
DEPLOYMENT_RESULT_REGION_UNSUPPORTED
DEPLOYMENT_RESULT_CHANNEL_NOT_READY
DEPLOYMENT_RESULT_PROCESSING_EXHAUSTED
DEPLOYMENT_RESULT_TOKEN_INVALID
DEPLOYMENT_RESULT_OBJECT_MISSING
DEPLOYMENT_RESULT_CONFLICT
DEPLOYMENT_RESULT_UNKNOWN
```

外部 API 错误响应：

```json
{
  "code": "TOS_OBJECT_NOT_FOUND",
  "message": "The requested artifact does not exist",
  "requestId": "provider-request-id",
  "retryable": false
}
```

Provider 原始响应体一律不写日志；只输出白名单化状态、Provider Code 和 Request ID。
尤其不能先记录 DescribeInvocations/SDK 对象再做字段脱敏，Parameters 和执行参数不得落盘。

## 15. 可观测性和审计

结构化日志统一字段：

```text
connectionPublicId
pipelineBuildId
jobBuildId
deployBuildId
deploymentPublicId
executionAttempt
region
bucketHash
objectKeyHash
instanceId
commandRevisionId
deployScriptSha256
executionMode
invocationId
providerRequestId
resultBucketHash
resultObjectKeyHash
resultEventDedupKey
resultDeliveryLatencyMs
reconciliationReason
phase
status
durationMs
```

Bucket/Key 默认以 Hash 记录，查询审计表时才展示完整值。审计必须记录脚本创建人、批准
人、脚本 Hash、云端 Command ID、目标 Instance ID 和每次 Attempt 的退出码。严禁记录
凭据和预签名 URL；用户脚本正文只在受权限保护的配置查询接口返回，不进入普通运行日志。

指标：

```text
firefly_volcano_api_requests_total{service,operation,result}
firefly_volcano_api_latency_seconds{service,operation}
firefly_volcano_download_bytes_total
firefly_volcano_deployments_total{status,phase}
firefly_volcano_deployment_duration_seconds{status}
firefly_volcano_deployments_active
firefly_volcano_result_events_total{result}
firefly_volcano_result_delivery_seconds
firefly_volcano_result_invalid_total{reason}
firefly_volcano_result_deadline_reconciliations_total{outcome}
firefly_volcano_rollback_total{result}
```

告警：

- `MANUAL_INTERVENTION_REQUIRED > 0` 立即告警。
- `DISPATCH_UNKNOWN` 超过 2 分钟告警。
- `RESULT_UNKNOWN > 0` 或结果 Token 校验失败立即告警。
- Result Kafka Consumer Lag 持续超过 60 秒告警。
- 结果交付超过该 Attempt 持久化的 `result_deadline_at` 或 Deadline 对账持续出现告警。
- Result Inbox FAILURE/遗留 PROCESSING、REJECTED 增长、处理次数耗尽或通道不 READY 告警。
- 同一 Connection 连续 5 次鉴权失败告警并暂停新部署。
- 同一应用连续 3 次健康检查失败告警。

## 16. 测试策略

### 16.1 `firefly-volcano` 单元测试

- AK/SK 不出现在 `toString()`、异常和日志中。
- Region/Endpoint 构造和 allowlist。
- TOS SDK 响应到 Firefly 模型的映射。
- TOS 404、403、429、5xx、网络超时和 Request ID 映射。
- Range、Version ID、If-Match 和预签名 TTL。
- 结果 PUT 只能签发固定 Bucket、完整 Key、方法与 Content-Type；URL 和 Token 不出现在
  `toString()`、异常与日志。
- 结果信封 Schema、16 KiB 上限、未知字段、Base64 字段、Token 常量时间比较和不可变字段
  校验。
- Token 小写 hex 的 ASCII 哈希跨生成端/消费端固定向量；拒绝对原始 32 字节求哈希的
  错误实现。日志、数据库快照不包含 Token/结果原文。
- DescribeInvocations 返回含 URL/Token 的 Parameters 时，白名单 DTO 不含该字段，
  Vendor 对象/SDK debug/异常/Actuator 均不泄露参数。
- 原始正文 Base64 + 完整 Parameters JSON Base64、有效正文的双重保守预算；测试 UTF-8
  多字节、JSON 转义、重复占位符、两套最长 URL、默认值和边界上下各 1 字节。
- ECS Invocation 状态、退出码和输出映射。
- SDK 重试只覆盖幂等操作。

### 16.2 `firefly-app` 集成测试

使用 Testcontainers MySQL/Kafka，并使用 Fake `VolcanoObjectStorageClient` 和
`VolcanoEcsCommandClient`：

- Connection 加密落库、解密、轮换和错误 Key Version。
- 执行全部新表 DDL，检查所有列 NOT NULL；验证 1970/9999/0/-1 哨兵的读写语义与 API
  映射。并发创建多条 PROVISIONING Revision/Attempt 不发生占位唯一键冲突。
- `pending-*` 向真实 ID 的 CAS、重复/冲突响应、结果先到/响应后到；恢复器与 GC 不把
  占位 ID 发给云 API，操作 ID 可用于唯一恢复。
- Pipeline 创建时 `STATIC_AK_SK`、`STS_SESSION`、`STS_ASSUME_ROLE` 三种 Binding 的原子保存。
- STS 刷新并发互斥、提前刷新、过期和 AssumeRole 失败。
- API 永不返回明文 AK/SK。
- TOS Prefix 跨多页按 LastModified 计算真实 Top 10、相同时间排序和扫描上限。
- `tos://` URI 规范化、百分号编码、非法 Scheme/Query，以及 Job 配置 URI 与
  Bucket/Prefix 不一致。
- Object Content 大文件流式传输和客户端中断关闭。
- Plugin Config 保存和读取只包含 Region/Bucket/Prefix 范围，提交 Key、Version ID、
  ETag、大小或 SHA-256 快照必须被拒绝。修改用户脚本必须创建新 Command Revision，
  旧 Attempt 仍引用旧 Command ID 和 Script Hash。
- 手动执行对单个/多个 Volcano Job 逐个选择制品；漏选、多选、未知 Job UUID、越出
  Prefix/当前层、不允许 handling 和客户端伪造快照均被拒绝。
- 手动提交的 `HeadObject` 快照与 Pipeline/Stage/Job/Plugin Build 在同一事务中落库；
  中途失败不留半成品 Build。
- 包含 `MANUAL_AT_RUN` Job 的 Pipeline 自动触发被拒绝，不会隐式选择最新制品。
- `CreateCommand` 超时按 Tag + rendered Hash 对账、配置事务补偿和无引用命令 GC。
- 创建时静态预算与派发时实际参数综合预算、用户脚本长度、URL 超过 4 片时拒绝；
  创建校验通过但实际 URL 太长时，断言 Invoke 调用次数为 0。
- 默认 B=900 时 Timeout=960、上传期限 t0+1080、Deadline t0+1380；B=3120/3121 边界，
  STS 剩余时长不足、刷新失败、签名/提交消耗 Q、云端超时上限及启动窗口过期。
- Pipeline Build 创建 Volcano Deploy Build。
- `InvokeCommand` 成功、失败、超时和未知结果。
- 无公网 IP 实例可按 Region + Instance ID 部署；私网 IP 变化不改变目标，Region/Agent
  状态变化会阻止部署。
- 正常结果事件推进终态，并断言 Fake ECS 的 `DescribeInvocation*` 调用次数为 0。
- 结果事件先于 `InvokeCommand` HTTP 响应、重复事件、乱序事件和相同内容重投均只推进
  一次状态机。
- 非预期 Key、无效 Token、错误 Schema、超限对象、篡改不可变字段和同一 Attempt 的冲突
  结果全部拒绝并告警。
- TOS `events[]` 多子项同 offset 全部归档后 ACK；合法/非法混合项相互不丢失；语法
  错误/超限 Record 有安全 REJECTED 记录再 ACK；数据库失败不 ACK。
- 同对象版本跨 offset/集群的重复通知只产生一次处理；源位置包含 cluster 和 event_index，
  UUID 冲突比较完整身份。失败归档不得把任意通知正文或结果 Token 落库。
- ACK 后分别在调度前、PROCESSING 和业务提交前崩溃：确认 Kafka 不因业务失败重投，
  管理员恢复或 Deadline 可以结束 Attempt，且均不重新 Invoke。
- 同版本无效结果只 REJECTED 一次；不同合法版本在 Deadline 前可完成部署。伪造消息
  不立即终止 Attempt；到期仍没有可信结果进入 RESULT_UNKNOWN 并写一次 Outbox。
- 对象存在但校验失败、TOS 读取错误、两个 Lease 均崩溃、诊断 ticket 已写但未收到响应
  都有有界终态；重复扫描不再读云 API，第三次领取不允许产生外部调用。
- 人工 retry/reset 不重置计数，最多 3 次；REJECTED/SUCCESS 不可恢复，终态 Attempt
  的迟到结果不反转 Pipeline。Consumer/Reconciler CAS 竞争不进入无限 FAILURE。
- Kafka 事件丢失时，Deadline Reconciler 直接读取预期 TOS Key 并恢复，不查询 ECS；结果
  对象也缺失时才执行一次 `DescribeInvocation*`。
- Deadline Lease/fencing 抢占与一个逻辑诊断 ticket；重启后 Kafka 从已提交 Offset 继续，
  已 ACK 未完成 Inbox 走显式人工恢复/Deadline，而不是假定重启就会自动重放。
- 不支持的 Region、无 Result Bucket、错误规则/Topic、规则尚未生效、验收配置 Hash
  失效、健康检查过期和 SASL 文件缺失均阻止派发；运行期故障仍走有界 Deadline。
- 未授予 StopInvocation 仍可完成正常部署和超时收敛；兼容 `/stop` 明确拒绝，不写 CANCELLED。
- 终态 Outbox 重放不重复推进 Job/Stage/Pipeline。
- Pipeline Retry 创建新的 Attempt、不覆盖旧 Attempt 审计，并且复用原制品选择
  快照，不查询/选择新制品。
- 删除 Pipeline/Connection 时的活动部署和逻辑引用校验。

### 16.3 部署脚本测试

在临时 Linux 容器中测试：

- `MANAGED_DOWNLOAD` 下 tar.gz、zip、JAR 和原始可执行二进制准备正确，并将预期环境变量
  传给用户部署指令。
- `VERSIONED_DIRECTORY` 和 `FIXED_FILE` 的最终路径计算、路径预览与实际 Bootstrap 结果
  一致。
- 绝对 relative path、`..`、控制字符、超长路径、现有符号链接父目录和 allowlist 逃逸
  全部被拒绝。
- 下载/准备失败不会修改最终路径；同文件系统原子 rename 成功，跨文件系统场景不会
  错误声称原子发布。
- `CUSTOM_FULL_SCRIPT` 默认禁用；开启后能执行用户自定义下载/部署指令，并标记
  `integrityManaged=false`。
- 错误 SHA-256、错误大小、URL 过期、磁盘空间不足。
- 归档中的绝对路径、`../`、危险符号链接和设备文件被拒绝。
- 两个并发部署只能有一个取得 `flock`。
- 用户部署指令退出非零、超时、stdout/stderr 超限和特殊字符脚本。
- 业务用完 B 后仍有 U 生成/上传结果；健康检查/回滚不能重置业务预算，curl 每次尝试和
  退避均有剩余时限。超过启动窗口无业务副作用，超时无法回滚时明确人工介入。
- 健康检查失败后用户回滚指令成功；回滚缺失或失败时返回人工介入。
- 每条可捕获终态路径都生成并上传结果信封；结果上传使用同一 Key/内容有界重试，最终失败
  返回 44；`SIGKILL`/主机重启导致无结果时由 Deadline 对账覆盖。
- 制品和结果预签名 URL、Result Token、完整信封不出现在 stdout/stderr。
- 安全测试注明可信脚本前提：不宣称 env -i 能抵御同 UID 主动窃取/编码外传；若未来
  引入不可信模式，必须新增独立身份、进程/文件/权限逃逸与结果伪造测试后才能启用。

### 16.4 火山引擎 Staging 合同测试

准备专用测试账号、私有 Bucket、测试 ECS 和受限 Command：

1. List/Head/Get/Range/Download、制品 Presign GET 和固定 Key 结果 Presign PUT。
2. 版本对象和 If-Match 失败。
3. 100 MB 以上对象断点续传及 CRC64/SHA-256。
4. 在允许 Region 配置 TOS `ObjectCreated` 到 Kafka，确认规则约 5 分钟生效后全链路
   通过，验证多事件格式、重复投递、原生 PLAIN 投递身份和独立 TLS 消费身份/轮换。
5. 分别验证 CreateCommand 正文与 InvokeCommand 综合 Base64 预算、实际参数序列化、
   String 长度、最长 STS URL 和临界值；记录 SDK/API 版本，不以本地估算代替合同结果。
6. 分别使用压缩包、JAR 和原始可执行文件执行用户给定的部署指令。
7. 真实部署、健康检查、用户回滚指令和进程重启恢复。
8. 人为抑制结果事件，验证 Deadline 直接读 TOS；再删除结果对象，验证只发生一次
   Describe Result 对账。
9. 用 IAM 明确验证未授权 Bucket、实例、RunCommand 和非 Firefly Command 均被拒绝。
10. 用合成秘密验证 DescribeInvocations 会回显 Parameters，而 Firefly DTO/数据库/日志
    不保留它；单独核验只读云查询角色的实际资源范围，不使用生产凭据测试。
11. 验证业务 watchdog、上传余量和 InvokeCommand.Timeout；令 Once 超时/主机故障并
    确认未知终态，不将 StopInvocation 响应当作进程终止证明。
12. 延迟启动、STS 提前失效、结果对象非法或事件丢失，验证拒绝副作用及有限次 Deadline
    收敛，确认超时不会错误显示为成功或已回滚。

最终必须执行：

```bash
mvn clean verify
```

Docker/Testcontainers 的完整 `verify` 是后端实现合并前的必要结果；不能替代上述云端
合同测试。本 PR 仅修改设计文档，不声称这些尚未实现的测试已经执行通过。

## 17. 数据迁移与兼容

现有明文表不能直接继续使用。迁移采用三阶段：

### 阶段 A：新增能力

- 新建 `firefly-volcano` 模块和上述新表。
- 新代码只写加密的 `volcano_connection`。
- 新 Pipeline 使用全局 Volcano Binding，`VOLCANO_DEPLOY` Plugin 不再携带 Connection。
- 旧 `VOLCANO` Trigger 暂时只读兼容。
- 若环境曾按旧版草案建表，先审计并按 6.0 转换 NULL 与占位 ID、补齐操作 ID，再执行
  NOT NULL/唯一索引迁移；未知历史云资源需人工核对，不伪造真实 ID。新结果 Inbox 独立
  建表，不原地改变四张业务 Inbox 的载荷语义。

### 阶段 B：凭据迁移

- 提供一次性、可审计的应用迁移任务，读取 `volcano_engine` / `volcano_config` 中的
  明文 AK/SK，加密写入 Connection。
- 迁移任务输出记录数和 Hash，不输出凭据。
- Pipeline 配置切换到 `volcano_pipeline_binding`，旧明文凭据按 `STATIC_AK_SK` 迁移。
- 完成业务核对和回滚快照后，清空旧表 AK/SK 字段。

SQL 不能完成应用级 AES-GCM 和 AAD，所以不得只靠 DDL 把明文复制到新表。

### 阶段 C：删除遗留传播

- 从 `VolcanoMessageEntity`、`VolcanoTriggerDto`、`VolcanoTriggerEntity` 删除 AK/SK。
- 删除 `volcano_trigger.idx_ak`。
- 确认没有旧版本实例后，删除 `volcano_engine` / `volcano_config` 的凭据列或整表。
- 更新 README 和前端，禁止 `originInfo` 再提交 AK/SK。

迁移完成前，任何读取旧明文凭据的路径都必须标记 Deprecated，并在日志中告警，但不能
打印凭据。

## 18. 实施顺序

### Milestone 1：基础模块与安全凭据

- 创建 `firefly-volcano` Maven 模块和自动装配。
- 实现三种凭据模式、Pipeline Binding、AES-GCM、STS Provider、Endpoint 校验和错误模型。
- 引入 TOS/ECS SDK并通过 Java 25 构建。

验收：能加密保存 Connection，能对指定对象和实例进行只读验证，无明文泄露。

### Milestone 2：TOS 读取与下载

- 实现 List、Head、Get、Range、Download、制品 Presign GET 和结果 Presign PUT。
- 实现按 Pipeline + Job UUID 限定 Prefix 当前层的最近 10 个制品查询、Top-K 和
  扫描上限，该接口只供手动执行交互使用。
- 实现管理 API、限流、大小限制、CRC64/SHA-256 和临时文件清理。

验收：小对象可流式读取，大对象可断点下载，版本和校验不一致能明确失败。

### Milestone 3：ECS 云助手与用户部署指令

- 实现可信 Bootstrap 渲染、用户脚本校验、不可变 Command Revision 创建与垃圾回收。
- 实现 ECS 列表、Region + Instance ID 选择、Agent 过滤、Create、Describe、Invoke、Result
  和 Delete；Once 不提供 Stop，读取响应使用安全字段白名单。
- 完成压缩包安全解包、原始文件准备、环境变量契约、用户部署/回滚指令和健康检查测试。
- 实现 TOS URI 解析、Destination Layout、路径预检、同文件系统暂存和原子发布。
- 实现 Result Token、终态信封、固定 Key 预签名 PUT 和 Bootstrap 有界上传重试。
- 实现完整编码预算与业务/上传双预算、实际凭据 expiry 校验和最晚启动门限。

验收：测试 ECS 能从私有 TOS 直拉压缩包或二进制，执行用户给定指令并完成部署；实例内
不存在长期 AK/SK，运行结果可关联到不可变 Script Hash。

### Milestone 4：Pipeline Plugin、结果事件与恢复

- 新增 `VOLCANO_DEPLOY` 配置、运行制品选择、build/attempt 表和服务。
- 扩展 `/manual_trigger/pipeline` 的按 Job UUID 运行输入，在一个事务内固化制品快照并创建
  Build；包含手动选择 Job 时拒绝自动触发。
- 接入现有 Plugin、Outbox 和 Pipeline Retry，新增专用结果 Inbox、category 适配和人工恢复。
- 配置平台结果 Bucket 的 `ObjectCreated` 到 Kafka，实现 Result Consumer、严格协议校验、
  幂等状态推进和告警指标。
- 实现 Region/结果通道就绪门禁、SASL 秘密文件注入、无效消息隔离与有界处理。
- 实现只处理逾期 Attempt 的 Deadline Reconciler：先读唯一结果 Key，对象缺失时至多一次
  Cloud Assistant 对账，不实现运行期定时轮询。

验收：正常部署由 TOS 结果对象与 Kafka 事件完成且云助手 Describe 调用为 0；Firefly 在
部署中重启后仍能通过事件、明确的人工 Inbox 恢复或 Deadline 收敛；重复消息、事件丢失
和重试不造成重复状态推进或意外的第二次 Invoke。

### Milestone 5：旧数据迁移与生产灰度

- 加密迁移旧 AK/SK，移除消息与审计表中的凭据。
- 使用非生产 Connection、Bucket 和 ECS 做完整合同测试。
- 生产先开放一个应用和一台实例，观察失败率、耗时和回滚结果，再扩大范围。

## 19. 完成标准

以下条件全部满足，才认为 `firefly-volcano` 完成：

- `firefly-volcano` 是独立 Maven 模块，`firefly-app` 不直接引用 Vendor SDK 类型。
- 创建 Pipeline 时可输入 AK/SK、STS Session 或 AssumeRole，并只持久化加密 Connection
  与 Pipeline Binding。
- AK/SK 只以 AES-256-GCM 密文落库，不存在于 Pipeline JSON、Kafka、日志或运行审计中。
- 可分页列举、Head、流式读取和下载 TOS 对象。
- Job 配置只保存规范化的 `tos://bucket/prefix/` 制品范围，不包含任何具体 Object
  Key、版本、ETag、大小或校验和。
- 用户手动执行时，每个 Volcano 部署 Job 可在自身 TOS Prefix 中准确选择按
  LastModified 排序的最近 10 个制品之一。
- 后端通过 Head Object 把选中对象的版本、ETag、大小和校验和锁定为归属 Pipeline
  Build 的不可变执行快照；客户端不能提交自己的快照字段。
- 包含 `MANUAL_AT_RUN` Job 的 Pipeline 只能在显式制品选择后手动启动；自动触发不会
  隐式选择最新制品。
- 下载支持对象版本锁定、大小限制、断点续传和完整性校验。
- 用户可提供 Bash 部署指令；每个版本固化为不可变 Command Revision，运行期只通过
  `InvokeCommand` 执行，审计能定位到 Script Hash 和 Command ID。
- `MANAGED_DOWNLOAD` 能从私有 TOS 直拉、校验并安全准备 TAR.GZ、ZIP 或普通文件；
  `CUSTOM_FULL_SCRIPT` 只有管理员显式开启后才能把短期 URL 提供给用户脚本。
- 用户可以配置 allowlist 内的 Deploy Root、相对路径和 Destination Layout；下载失败不
  影响现有版本，成功制品通过同文件系统原子发布后才执行用户脚本。
- 实例不会接收长期 AK/SK 或 STS 凭据。
- 无公网 IP 的 ECS 可按 Region + Instance ID 发现和部署，并在执行前验证实例与 Agent。
- 结果事件 Region、Bucket/规则、Kafka 鉴权/消费端及验收证据不就绪时拒绝新派发。
- 部署具有应用级锁、路径/归档安全检查、用户指令超时、健康检查和可选用户回滚指令；
  无法证明回滚成功时进入人工介入状态。
- Invocation 和每次 Pipeline Retry 都有独立、可恢复的持久化审计；Retry 始终复用
  原 Build 制品快照，更换制品必须创建新的手动执行。
- 可信 Bootstrap 把受限终态信封写入平台私有 TOS；TOS `ObjectCreated` 通过 Kafka 推进
  正常终态，协议具备随机 Token、固定 Key、Schema、大小限制和幂等校验。
- Token 哈希口径固定；同 UID/主机管理员/Invocation 查询者的可信边界明确，不宣称
  不可信脚本隔离；Parameters 与结果秘密不进入 DTO、数据库、日志或普通 API。
- 完整正文与参数通过综合字节预算；业务超时保留上传窗口，两条 URL 同时满足实际
  凭据有效期；超晚启动不执行业务步骤。
- 正常部署期间不轮询 Cloud Assistant；只有超过结果 Deadline 且结果对象仍缺失时，才做
  一次 Cloud Assistant 对账，无法证明业务终态则进入 `RESULT_UNKNOWN`。
- 专用结果 Inbox 正确拆分 events[]、提交后 ACK；ACK 后崩溃可人工恢复且不会重复 Invoke。
- 无效对象/读取失败/崩溃接管最终有界进入未知状态并只推进一次失败，不无限 FAILURE/对账。
- DDL 全列 NOT NULL，缺省值语义明确，占位云 ID 唯一且不会误传云 API；Once 不提供停止承诺。
- IAM 分离制品访问、平台结果存储和 Bucket 通知身份，只覆盖指定 TOS Prefix、Command
  和必要的只读/异常对账 API；ECS 不持有 TOS/Kafka 长期凭据。
- 单元、集成、脚本安全、Staging 合同测试全部通过，`mvn clean verify` 成功。

## 20. 官方资料基线

- 火山引擎 Java OpenAPI SDK：<https://github.com/volcengine/volcengine-java-sdk>
- TOS Java SDK：<https://github.com/volcengine/ve-tos-java-sdk>
- TOS Java SDK 快速入门：<https://www.volcengine.com/docs/6349/79896>
- TOS Java SDK 断点续传下载：<https://www.volcengine.com/docs/6349/158830>
- TOS 数据一致性校验：<https://www.volcengine.com/docs/6349/136729>
- TOS ListObjectsV2：<https://www.volcengine.com/docs/6349/74861>
- TOS 普通上传：<https://www.volcengine.com/docs/6349/92800>
- TOS 事件通知到 Kafka：<https://www.volcengine.com/docs/6349/1817509>
- TOS 事件通知概述（地域与 events[] 格式）：<https://docs.volcengine.com/docs/6349/128981?lang=zh>
- TOS PutBucketNotificationV2：<https://www.volcengine.com/docs/6349/1183362>
- STS AssumeRole 临时授权：<https://www.volcengine.com/docs/6720/1144521>
- 云助手 API 概览：<https://api.volcengine.com/api-docs/view/115526>
- 云助手 InvokeCommand：<https://www.volcengine.com/docs/6396/170898>
- 查询任务及 Parameters 返回结构：<https://docs.volcengine.com/docs/6396/170906?lang=zh>
- StopInvocation 使用限制：<https://docs.volcengine.com/docs/6396/170897?lang=zh>
- 创建自定义命令：<https://www.volcengine.com/docs/6396/170743>
- 查看命令执行结果：<https://www.volcengine.com/docs/6396/170924>
- 云助手运维概述：<https://www.volcengine.com/docs/6396/164682>
- 自定义命令 IAM 权限：<https://www.volcengine.com/docs/6396/1153135>

SDK 版本和 API 参数在实施时必须再次以 Maven Central、官方仓库和 API Explorer 为准；
本文的架构边界、安全规则、数据状态机和验收标准不依赖某个生成 SDK 的具体方法名。

## 21. PR #50 评审闭环（v1.6）

| 评论 | 设计修订 | 对应章节 |
| --- | --- | --- |
| 1. NULL 与派发前云 ID | Volcano 新表统一 NOT NULL，明确哨兵、唯一 pending ID、操作 ID 和 CAS/GC 边界；不声称现有全库禁 NULL | 6.0、8.3、9.1～9.2 |
| 2. Inbox 不可原样复用 | 新增结果 Inbox、events[] 子项归档、源位置/业务版本去重、category 和人工恢复 | 8.6.4、9.2.1、10、11.5 |
| 3. Bootstrap 秘密边界 | 明确可信管理员模型，env -i 非沙箱，Token 是 Attempt 认证而非执行证明 | 2.8、8.6.2、8.8～8.9 |
| 4. Parameters 回显 | ECS 白名单映射、禁止 SDK 原文日志，Invocation 查询者纳入对应结果可信域 | 5.3、13、14.2 |
| 5. Once 停止不成立 | 移除 Stop 方法/权限/重试承诺，依靠硬超时和未知终态，不等同回滚 | 9.4.1、11.2、13 |
| 6. 上传与 TTL 预算 | 业务/上传双预算，启动门限，默认业务上限 3120 秒，校验两种签名凭据 expiry | 8.3、8.8、12.1 |
| 7. 无效结果无终态 | REJECTED 版本不重开，人工恢复次数有界，Deadline 各分支与崩溃接管最终收敛 | 8.6.4、9.4、11.5 |
| 8. Region 与通道能力 | 中国区 MVP allowlist、通知生效/配置验收及只读健康门禁，不静默降级 | 8.1、8.6.1、12 |
| 9. 综合参数大小 | 创建/派发分开校验，实际 URL/默认值计入双重保守预算，云端口径需合同验证 | 8.5.1、16.4 |
| Token/SASL 补充 | 小写 hex ASCII 的 SHA-256 契约，独立 SASL 消费身份与只读秘密文件注入 | 8.6.2、12 |

本节表示评审要求已落实到设计与测试计划，不表示实现、云账号权限配置或 Staging 测试
已经完成；不会因为文档闭环就自动合并 PR 或关闭评审人的评论。
