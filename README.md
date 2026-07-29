# Firefly

[简体中文](#简体中文) · [English](#english)

Firefly 是一个基于 Spring Boot、Kafka 和 MySQL 的流水线编排服务。仓库包含流水线配置、Stage/Job 拓扑、构建记录、Kafka 消息归档以及状态推进逻辑。

Firefly is a pipeline orchestration service built with Spring Boot, Kafka, and MySQL. The repository contains pipeline configuration, Stage/Job topology, build records, Kafka message archiving, and state-transition logic.

## 简体中文

### 项目概览

Firefly 使用四层领域模型描述执行过程：

```text
Pipeline
└── Stage（按 stageOrder 串行）
    └── Job Chain（多个链可并行）
        └── Job（同一链内串行）
            └── Plugin（由 Job 触发）
```

当前仓库实现了：

- 创建和查询 Pipeline 配置。
- 按请求中的 Stage 顺序持久化 `stageOrder`。
- 使用二维 `jobConfigs` 表达 Job 拓扑：外层是可并行的 Job 链，内层是串行 Job。
- 手动创建 Pipeline、Stage、Job 和 Plugin 构建记录。
- 生成 Pipeline、Stage、Job 和 Plugin 业务消息 UUID。
- 分别批量保存四类 Kafka 消息，并在数据库事务成功后手动 ACK。
- 通过数据库唯一约束和业务 UUID 实现消息归档幂等。
- 在 `MessageCenter` 中实现 Pipeline、Stage、Job 和 Plugin 的状态转换与后续消息生成。
- 使用 Spring Data JPA 访问数据库。

### 当前消息处理边界

Kafka 有四个 Topic，每个 Topic 对应一个批量监听器和一张消息表：

| Topic | 监听方法 | 归档表 |
| --- | --- | --- |
| `pipeline_message` | `onPipelineMessage` | `pipeline_message` |
| `stage_message` | `onStageMessage` | `stage_message` |
| `job_message` | `onJobMessage` | `job_message` |
| `plugin_message` | `onPluginMessage` | `plugin_message` |

当前监听链路是：

```text
Kafka 批量拉取
  -> 按 messageUUID 去重
  -> 分类写入消息表
  -> 数据库事务提交
  -> 手动立即 ACK
  -> 反序列化并调用 MessageCenter
```

Listener 在消息成功归档并 ACK 后调用 `MessageCenter`。`MessageCenter` 负责状态更新、Stage 顺序推进、Job 链推进和后续 Kafka 消息发送。单条消息解析或业务处理失败不会撤销 ACK，也不会阻塞同批其他消息；归档记录保留用于人工处理。

消息归档规则：

- 每次拉取最多 `200` 条消息。
- Listener 使用批量模式和手动立即 ACK，单个 Listener 的 `concurrency` 为 `1`。
- 每个应用实例包含四个 Listener 容器，分别消费四个 Topic。
- `messageUUID` 必须是合法的 Java UUID。
- 同一批次内按 `messageUUID` 去重。
- 入库前查询已经存在的 UUID，仅保存新消息。
- 每张消息表同时约束 `message_uuid` 和 `(topic, kafka_partition, kafka_offset)` 唯一。
- 数据库写入成功后 ACK；写入异常时不会执行 ACK。
- ACK 后再执行状态推进，因此业务处理失败不会触发 Kafka 重投。

业务消息 UUID 由消息类型、构建 ID、状态等稳定字段生成，因此同一个业务事件重复生成时仍可被幂等识别。

### Stage 与 Job 顺序

- Pipeline 中的 Stage 按 `stage_order` 从小到大执行。
- Stage 成功后，`MessageCenter` 只查找同一个 `pipelineBuild` 中的下一个 Stage。
- 最后一个 Stage 成功后生成 Pipeline 成功消息。
- 任意 Stage 失败后生成 Pipeline 失败消息。
- `jobConfigs` 的每个内层数组是一条串行链。
- 不同内层数组代表不同 Job 链，可以并行。
- Job 成功后推进同一链中的下一个 Job。
- 每条链的尾 Job 完成后参与 Stage 完成状态汇总。
- 当前唯一的 Plugin 类型是 `TEXT`，Plugin 由 Job 触发。

### 技术栈

| 组件 | 版本或说明 |
| --- | --- |
| Java | 25 |
| Maven | 3.9+ |
| Spring Boot | 3.5.4 |
| Spring Data JPA | 数据持久化 |
| Spring Kafka | 批量消费与消息生产 |
| MySQL | 推荐 8.4 |
| Kafka | 示例使用 3.9.1，KRaft 单节点 |
| Druid | 1.2.27 |

### 目录结构

```text
src/main/java/firefly
├── FireflyApplication.java
├── bean/
│   ├── dto/                # 服务层数据传输对象
│   └── vo/                 # API 请求与响应对象
├── constant/               # 状态、触发方式、Topic 和 Plugin 类型
├── controller/             # Pipeline 配置与手动触发接口
├── dao/                    # Spring Data JPA Repository
├── model/                  # JPA Entity
└── service/                # 配置、构建、触发、消息归档和 MessageCenter

src/main/resources
├── application.yaml        # 默认配置及环境变量入口
└── v1.sql                  # 完整的新库初始化脚本
```

### 快速开始

#### 1. 环境要求

请先安装：

- JDK 25
- Maven 3.9 或更高版本
- Docker

确认版本：

```bash
java -version
mvn -version
docker version
```

#### 2. 启动 MySQL

以下命令会创建数据库 `firefly`，并在数据卷第一次初始化时执行 `v1.sql`：

```bash
docker volume create firefly-mysql-data

docker run -d \
  --name firefly-mysql \
  --restart unless-stopped \
  -p 127.0.0.1:3306:3306 \
  -e MYSQL_ROOT_PASSWORD=zou \
  -e MYSQL_ROOT_HOST=% \
  -e MYSQL_DATABASE=firefly \
  -v firefly-mysql-data:/var/lib/mysql \
  -v "$PWD/src/main/resources/v1.sql:/docker-entrypoint-initdb.d/001-schema.sql:ro" \
  mysql:8.4
```

`docker-entrypoint-initdb.d` 只会在空数据目录初始化时执行。已经存在的数据库需要单独执行最新的 `v1.sql`，或者重新创建开发数据卷。

#### 3. 启动 Kafka

```bash
docker run -d \
  --name firefly-kafka \
  --restart unless-stopped \
  -p 127.0.0.1:9092:9092 \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
  -e KAFKA_NUM_PARTITIONS=3 \
  apache/kafka:3.9.1
```

创建四个 Topic：

```bash
for topic in pipeline_message stage_message job_message plugin_message; do
  docker exec firefly-kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 \
    --create \
    --if-not-exists \
    --topic "$topic" \
    --partitions 3 \
    --replication-factor 1
done
```

检查容器：

```bash
docker exec firefly-mysql mysqladmin ping -uroot -pzou
docker exec firefly-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --list
```

#### 4. 构建并启动

```bash
mvn clean verify
mvn spring-boot:run
```

也可以先打包再运行：

```bash
mvn clean package
java -jar target/firefly-0.0.1-SNAPSHOT.jar
```

服务默认监听 `http://localhost:9999`。

### 配置

主要配置都可以通过环境变量覆盖：

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `SERVER_PORT` | `9999` | HTTP 端口 |
| `MYSQL_URL` | `jdbc:mysql://localhost:3306/firefly` | JDBC 地址 |
| `SPRING_DATASOURCE_USERNAME` | `root` | MySQL 用户名 |
| `SPRING_DATASOURCE_PASSWORD` | `zou` | MySQL 密码 |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka Broker |

例如：

```bash
SERVER_PORT=8080 \
SPRING_DATASOURCE_PASSWORD=zou \
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
mvn spring-boot:run
```

JPA 的 `ddl-auto` 设置为 `none`，应用不会自动建表，数据库结构以 `src/main/resources/v1.sql` 为准。

### API

#### 创建 Pipeline 配置

```http
POST /create/pipeline
Content-Type: application/json
```

下面的示例包含两个顺序 Stage：

- 第一个 Stage 有一条 Job 链，链内两个 Job 串行。
- 第二个 Stage 有两条 Job 链，每条链一个 Job，两条链可并行。

```bash
curl -X POST http://localhost:9999/create/pipeline \
  -H 'Content-Type: application/json' \
  -d '{
    "uuid": "1111111111111111111111111111111111111111111111111111111111111111",
    "name": "demo-pipeline",
    "stageConfigs": [
      {
        "uuid": "2222222222222222222222222222222222222222222222222222222222222222",
        "name": "stage-one-demo",
        "jobConfigs": [
          [
            {
              "uuid": "4444444444444444444444444444444444444444444444444444444444444444",
              "name": "stage1-job1",
              "pluginType": "TEXT",
              "pluginRaw": "{\"text\":\"stage 1 job 1\"}"
            },
            {
              "uuid": "5555555555555555555555555555555555555555555555555555555555555555",
              "name": "stage1-job2",
              "pluginType": "TEXT",
              "pluginRaw": "{\"text\":\"stage 1 job 2\"}"
            }
          ]
        ]
      },
      {
        "uuid": "3333333333333333333333333333333333333333333333333333333333333333",
        "name": "stage-two-demo",
        "jobConfigs": [
          [
            {
              "uuid": "6666666666666666666666666666666666666666666666666666666666666666",
              "name": "stage2-job1",
              "pluginType": "TEXT",
              "pluginRaw": "{\"text\":\"stage 2 chain 1\"}"
            }
          ],
          [
            {
              "uuid": "7777777777777777777777777777777777777777777777777777777777777777",
              "name": "stage2-job2",
              "pluginType": "TEXT",
              "pluginRaw": "{\"text\":\"stage 2 chain 2\"}"
            }
          ]
        ]
      }
    ],
    "triggerModel": "MANUAL",
    "triggerMatch": "ACCURATE",
    "triggerOrigin": "VOLCANO",
    "originInfo": {
      "ak": "local-ak",
      "sk": "local-sk"
    }
  }'
```

接口返回请求中的 Pipeline UUID。

#### 查询 Pipeline 配置

```bash
curl 'http://localhost:9999/pipeline?uuid=1111111111111111111111111111111111111111111111111111111111111111'
```

响应包含：

- Pipeline 的 `id`、`uuid` 和 `name`。
- 按 `stageOrder` 排序的 Stage。
- 以二维数组返回的 Job 链。
- Plugin 配置中的 `jobConfigID`。
- `triggerOrigin`、`triggerMode` 和 `triggerMatch`。

#### 手动触发 Pipeline

请求中的 `pipelineId` 是查询 Pipeline 配置时返回的数据库 ID，`uuid` 是本次构建的 64 位业务标识：

```bash
curl -X POST http://localhost:9999/manual_trigger/pipeline \
  -H 'Content-Type: application/json' \
  -d '{
    "pipelineId": 1,
    "uuid": "8888888888888888888888888888888888888888888888888888888888888888",
    "triggerModel": "MANUAL",
    "triggerMatch": "ACCURATE",
    "triggerOrigin": "VOLCANO"
  }'
```

接口会创建完整的构建记录，保存 Volcano 触发记录，发送 Pipeline `RUNNING` 消息，并返回 Pipeline Build ID。该消息随后会进入 `pipeline_message` 表完成归档，并由 `MessageCenter` 开始推进构建状态。

#### 重新执行失败的 Pipeline

```bash
curl -X POST http://localhost:9999/pipeline-builds/1/retry
```

只有状态为 `FAILURE` 的 Pipeline Build 可以重新执行，否则接口返回 HTTP `409`。重试会复用原有构建记录并递增 `executionAttempt`，跳过已经成功的 Stage 和 Job，将失败或尚未执行的记录重置为 `PENDING`，然后从第一个未成功的 Stage 继续执行。响应示例：

```json
{
  "pipelineBuildID": 1,
  "executionAttempt": 1
}
```

### 参数校验

- Pipeline、Stage、Job 和构建请求的 `uuid` 长度必须是 `64`。
- Pipeline、Stage 和 Job 名称长度必须是 `10` 到 `64`。
- Stage 和 Job 使用级联校验。
- Trigger、Plugin 类型和必要的集合字段不能为空。
- Controller 使用 `@Valid`，非法请求返回 HTTP `400`。

### 数据库

`v1.sql` 是完整的新库建表脚本，共包含 17 张表：

| 类型 | 表 |
| --- | --- |
| 配置与拓扑 | `pipeline_config`, `stage_config`, `job_config`, `job_relation`, `text_plugin_config`, `volcano_engine` |
| 触发记录 | `github_trigger`, `volcano_trigger`, `volcano_config` |
| 构建记录 | `pipeline_build`, `stage_build`, `job_build`, `text_plugin_build` |
| Kafka 消息归档 | `pipeline_message`, `stage_message`, `job_message`, `plugin_message` |

其中：

- `stage_config` 使用 `(pipeline_id, stage_order)` 保证同一 Pipeline 内 Stage 顺序唯一。
- `stage_build` 使用 `(pipeline_build_id, stage_id)` 避免同一构建重复创建 Stage Build。
- `text_plugin_config` 使用 `job_config_id` 关联 Job 配置。
- 四张构建表使用 `execution_attempt` 区分同一构建记录的不同执行轮次。
- 四张消息表保存 Topic、Partition、Offset、Key、Payload、接收时间和业务消息 UUID。

### 测试

运行完整测试：

```bash
mvn clean verify
```

测试覆盖应用启动、参数校验、Pipeline 响应组装、Stage 顺序、消息 UUID、批量去重、ACK 行为、Plugin 映射以及 `MessageCenter` 状态转换。Spring 集成测试使用 Testcontainers 自动启动并初始化独立的 MySQL 8.4；消息接线测试使用 Embedded Kafka 验证四个 Topic 从真实 Listener 到 `MessageCenter` 的生产调用链。

### 容器管理

```bash
# 查看状态
docker ps --filter name=firefly

# 停止
docker stop firefly-mysql firefly-kafka

# 再次启动
docker start firefly-mysql firefly-kafka

# 查看日志
docker logs firefly-mysql
docker logs firefly-kafka
```

---

## English

### Overview

Firefly models an execution as four domain layers:

```text
Pipeline
└── Stage (serial by stageOrder)
    └── Job Chain (multiple chains may run in parallel)
        └── Job (serial within a chain)
            └── Plugin (triggered by a Job)
```

The repository currently implements:

- Pipeline configuration creation and lookup.
- Persistence of Stage order from the request as `stageOrder`.
- A two-dimensional `jobConfigs` structure: the outer list contains parallel-capable chains, while each inner list is a serial Job chain.
- Manual creation of Pipeline, Stage, Job, and Plugin build records.
- Business message UUID generation for Pipeline, Stage, Job, and Plugin events.
- Classified batch persistence of all four Kafka message types, followed by manual ACK after a successful database transaction.
- Idempotent message archiving through business UUIDs and database unique constraints.
- Pipeline, Stage, Job, and Plugin state-transition and follow-up message logic in `MessageCenter`.
- Database access through Spring Data JPA.

### Current message-processing boundary

Kafka uses four topics. Each topic has one batch listener and one archive table:

| Topic | Listener method | Archive table |
| --- | --- | --- |
| `pipeline_message` | `onPipelineMessage` | `pipeline_message` |
| `stage_message` | `onStageMessage` | `stage_message` |
| `job_message` | `onJobMessage` | `job_message` |
| `plugin_message` | `onPluginMessage` | `plugin_message` |

The current listener flow is:

```text
Kafka batch poll
  -> deduplicate by messageUUID
  -> persist in the classified message table
  -> commit the database transaction
  -> acknowledge immediately
  -> deserialize and invoke MessageCenter
```

After a message has been archived and acknowledged, the listener invokes `MessageCenter`. `MessageCenter` performs status updates, ordered Stage progression, Job-chain progression, and follow-up Kafka publishing. A parsing or business-processing failure does not undo the ACK or block other records in the batch; the archived record remains available for manual handling.

Archiving rules:

- A poll returns at most `200` records.
- Listeners use batch mode and manual immediate acknowledgment, with `concurrency: 1` per listener.
- Each application instance has four listener containers, one for each topic.
- `messageUUID` must be a valid Java UUID.
- Duplicate UUIDs within the same batch are removed.
- Existing UUIDs are queried before only new messages are saved.
- Every message table uniquely constrains both `message_uuid` and `(topic, kafka_partition, kafka_offset)`.
- ACK occurs after the database write succeeds; a database exception prevents ACK.
- Status progression runs after ACK, so a business-processing failure does not cause Kafka redelivery.

Business message UUIDs are derived from stable fields such as message type, build ID, and status. Repeated generation of the same business event can therefore be recognized idempotently.

### Stage and Job ordering

- Stages in a Pipeline execute in ascending `stage_order`.
- After a Stage succeeds, `MessageCenter` finds only the next Stage in the same `pipelineBuild`.
- Completion of the last Stage produces a successful Pipeline message.
- Failure of any Stage produces a failed Pipeline message.
- Each inner array in `jobConfigs` is one serial chain.
- Different inner arrays represent different Job chains and may run in parallel.
- A successful Job advances the next Job in the same chain.
- The tail Job of every chain participates in the Stage completion aggregation.
- `TEXT` is currently the only Plugin type, and a Plugin is triggered by a Job.

### Technology stack

| Component | Version or purpose |
| --- | --- |
| Java | 25 |
| Maven | 3.9+ |
| Spring Boot | 3.5.4 |
| Spring Data JPA | Database persistence |
| Spring Kafka | Batch consumption and message production |
| MySQL | 8.4 recommended |
| Kafka | The example uses 3.9.1 in single-node KRaft mode |
| Druid | 1.2.27 |

### Project layout

```text
src/main/java/firefly
├── FireflyApplication.java
├── bean/
│   ├── dto/                # Service-layer data transfer objects
│   └── vo/                 # API request and response models
├── constant/               # Statuses, triggers, topics, and Plugin types
├── controller/             # Pipeline configuration and manual-trigger APIs
├── dao/                    # Spring Data JPA repositories
├── model/                  # JPA entities
└── service/                # Configuration, builds, triggers, archiving, MessageCenter

src/main/resources
├── application.yaml        # Defaults and environment-variable entry points
└── v1.sql                  # Complete schema for a new database
```

### Quick start

#### 1. Prerequisites

Install:

- JDK 25
- Maven 3.9 or newer
- Docker

Check the installations:

```bash
java -version
mvn -version
docker version
```

#### 2. Start MySQL

The following commands create the `firefly` database and execute `v1.sql` when the volume is initialized for the first time:

```bash
docker volume create firefly-mysql-data

docker run -d \
  --name firefly-mysql \
  --restart unless-stopped \
  -p 127.0.0.1:3306:3306 \
  -e MYSQL_ROOT_PASSWORD=zou \
  -e MYSQL_ROOT_HOST=% \
  -e MYSQL_DATABASE=firefly \
  -v firefly-mysql-data:/var/lib/mysql \
  -v "$PWD/src/main/resources/v1.sql:/docker-entrypoint-initdb.d/001-schema.sql:ro" \
  mysql:8.4
```

Scripts in `docker-entrypoint-initdb.d` run only when an empty data directory is initialized. For an existing database, execute the latest `v1.sql` separately or recreate the development volume.

#### 3. Start Kafka

```bash
docker run -d \
  --name firefly-kafka \
  --restart unless-stopped \
  -p 127.0.0.1:9092:9092 \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
  -e KAFKA_NUM_PARTITIONS=3 \
  apache/kafka:3.9.1
```

Create the four topics:

```bash
for topic in pipeline_message stage_message job_message plugin_message; do
  docker exec firefly-kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 \
    --create \
    --if-not-exists \
    --topic "$topic" \
    --partitions 3 \
    --replication-factor 1
done
```

Check the containers:

```bash
docker exec firefly-mysql mysqladmin ping -uroot -pzou
docker exec firefly-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --list
```

#### 4. Build and run

```bash
mvn clean verify
mvn spring-boot:run
```

Alternatively, package and run the executable JAR:

```bash
mvn clean package
java -jar target/firefly-0.0.1-SNAPSHOT.jar
```

The service listens on `http://localhost:9999` by default.

### Configuration

The main settings can be overridden with environment variables:

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `9999` | HTTP port |
| `MYSQL_URL` | `jdbc:mysql://localhost:3306/firefly` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `root` | MySQL username |
| `SPRING_DATASOURCE_PASSWORD` | `zou` | MySQL password |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers |

Example:

```bash
SERVER_PORT=8080 \
SPRING_DATASOURCE_PASSWORD=zou \
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
mvn spring-boot:run
```

JPA uses `ddl-auto: none`, so the application does not create tables automatically. The schema is defined by `src/main/resources/v1.sql`.

### API

#### Create a Pipeline configuration

```http
POST /create/pipeline
Content-Type: application/json
```

The following example contains two ordered Stages:

- The first Stage has one Job chain with two serial Jobs.
- The second Stage has two Job chains with one Job each; those chains may run in parallel.

```bash
curl -X POST http://localhost:9999/create/pipeline \
  -H 'Content-Type: application/json' \
  -d '{
    "uuid": "1111111111111111111111111111111111111111111111111111111111111111",
    "name": "demo-pipeline",
    "stageConfigs": [
      {
        "uuid": "2222222222222222222222222222222222222222222222222222222222222222",
        "name": "stage-one-demo",
        "jobConfigs": [
          [
            {
              "uuid": "4444444444444444444444444444444444444444444444444444444444444444",
              "name": "stage1-job1",
              "pluginType": "TEXT",
              "pluginRaw": "{\"text\":\"stage 1 job 1\"}"
            },
            {
              "uuid": "5555555555555555555555555555555555555555555555555555555555555555",
              "name": "stage1-job2",
              "pluginType": "TEXT",
              "pluginRaw": "{\"text\":\"stage 1 job 2\"}"
            }
          ]
        ]
      },
      {
        "uuid": "3333333333333333333333333333333333333333333333333333333333333333",
        "name": "stage-two-demo",
        "jobConfigs": [
          [
            {
              "uuid": "6666666666666666666666666666666666666666666666666666666666666666",
              "name": "stage2-job1",
              "pluginType": "TEXT",
              "pluginRaw": "{\"text\":\"stage 2 chain 1\"}"
            }
          ],
          [
            {
              "uuid": "7777777777777777777777777777777777777777777777777777777777777777",
              "name": "stage2-job2",
              "pluginType": "TEXT",
              "pluginRaw": "{\"text\":\"stage 2 chain 2\"}"
            }
          ]
        ]
      }
    ],
    "triggerModel": "MANUAL",
    "triggerMatch": "ACCURATE",
    "triggerOrigin": "VOLCANO",
    "originInfo": {
      "ak": "local-ak",
      "sk": "local-sk"
    }
  }'
```

The endpoint returns the Pipeline UUID supplied in the request.

#### Query a Pipeline configuration

```bash
curl 'http://localhost:9999/pipeline?uuid=1111111111111111111111111111111111111111111111111111111111111111'
```

The response contains:

- Pipeline `id`, `uuid`, and `name`.
- Stages sorted by `stageOrder`.
- Job chains represented as a two-dimensional array.
- `jobConfigID` in the Plugin configuration.
- `triggerOrigin`, `triggerMode`, and `triggerMatch`.

#### Manually trigger a Pipeline

`pipelineId` is the database ID returned by the Pipeline query endpoint. `uuid` is the 64-character business identifier of this build:

```bash
curl -X POST http://localhost:9999/manual_trigger/pipeline \
  -H 'Content-Type: application/json' \
  -d '{
    "pipelineId": 1,
    "uuid": "8888888888888888888888888888888888888888888888888888888888888888",
    "triggerModel": "MANUAL",
    "triggerMatch": "ACCURATE",
    "triggerOrigin": "VOLCANO"
  }'
```

The endpoint creates the complete build records, stores the Volcano trigger record, publishes a Pipeline `RUNNING` message, and returns the Pipeline Build ID. That message is then archived in `pipeline_message`, after which `MessageCenter` starts advancing the build state.

#### Retry a failed Pipeline

```bash
curl -X POST http://localhost:9999/pipeline-builds/1/retry
```

Only a Pipeline Build in `FAILURE` can be retried; otherwise, the endpoint returns HTTP `409`. A retry reuses the existing build records and increments `executionAttempt`. Successful Stages and Jobs are skipped, while failed or unexecuted records are reset to `PENDING`, and execution resumes from the first unfinished Stage. Example response:

```json
{
  "pipelineBuildID": 1,
  "executionAttempt": 1
}
```

### Validation

- Pipeline, Stage, Job, and build-request `uuid` values must contain exactly `64` characters.
- Pipeline, Stage, and Job names must contain between `10` and `64` characters.
- Stage and Job objects use cascading validation.
- Trigger values, Plugin types, and required collection fields cannot be null.
- Controllers use `@Valid`, and invalid requests return HTTP `400`.

### Database

`v1.sql` is the complete schema for a new database and creates 17 tables:

| Category | Tables |
| --- | --- |
| Configuration and topology | `pipeline_config`, `stage_config`, `job_config`, `job_relation`, `text_plugin_config`, `volcano_engine` |
| Trigger records | `github_trigger`, `volcano_trigger`, `volcano_config` |
| Build records | `pipeline_build`, `stage_build`, `job_build`, `text_plugin_build` |
| Kafka message archive | `pipeline_message`, `stage_message`, `job_message`, `plugin_message` |

Notable constraints:

- `stage_config` uses `(pipeline_id, stage_order)` to keep Stage order unique within a Pipeline.
- `stage_build` uses `(pipeline_build_id, stage_id)` to prevent duplicate Stage Builds in the same Pipeline Build.
- `text_plugin_config` references the Job configuration through `job_config_id`.
- The four build tables use `execution_attempt` to distinguish retries of the same build records.
- The four message tables store the topic, partition, offset, key, payload, received time, and business message UUID.

### Tests

Run the complete test suite:

```bash
mvn clean verify
```

The suite covers application startup, request validation, Pipeline response assembly, Stage ordering, message UUIDs, batch deduplication, ACK behavior, Plugin mapping, and `MessageCenter` state transitions. Spring integration tests use Testcontainers to start and initialize an isolated MySQL 8.4 instance. The message-wiring test uses Embedded Kafka to verify the production path from all four topics through the real listeners to `MessageCenter`.

### Container management

```bash
# Show status
docker ps --filter name=firefly

# Stop
docker stop firefly-mysql firefly-kafka

# Start again
docker start firefly-mysql firefly-kafka

# Inspect logs
docker logs firefly-mysql
docker logs firefly-kafka
```
