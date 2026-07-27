# Firefly

[简体中文](#简体中文) | [English](#english)

## 简体中文

Firefly 是一个使用 Spring Boot、Kafka 和 MySQL 构建的轻量级消息驱动流水线编排引擎。它通过 Pipeline、Stage、Job 和 Plugin 四层模型描述工作流，并通过 Kafka 消息推进每一层的执行状态。

### 核心能力

- Pipeline 由多个按顺序执行的 Stage 组成。
- 每个 Stage 可以包含一条或多条 Job 链，不同 Job 链可以并行推进。
- Pipeline、Stage、Job 和 Plugin 的执行状态持久化到 MySQL。
- Kafka 负责组件解耦和状态消息传递。
- 提供 `TEXT` 插件用于演示插件配置和执行流程。
- 提供 Pipeline 创建、查询和手动触发 API。
- 支持 Volcano 触发来源及其访问配置。

### 执行流程

```text
Manual Trigger
      |
      v
Pipeline Message
      |
      v
 Stage Message
      |
      v
  Job Message
      |
      v
Plugin Message
      |
      v
Job -> Stage -> Pipeline status completion
```

1. 手动触发接口创建一次 Pipeline Build，并发送 Pipeline 消息。
2. Pipeline 消息将 Pipeline 标记为运行中，并启动第一个 Stage。
3. Stage 消息启动该 Stage 中的所有头部 Job。
4. Job 消息执行对应插件。
5. 插件完成后更新 Job；Job 链完成后更新 Stage。
6. 当前 Stage 成功后启动下一个 Stage，直到 Pipeline 完成。
7. 任一 Job 或 Stage 失败时，失败状态向上游传播。

### 技术栈

- Java 25
- Spring Boot 3.5.4
- Spring Data JPA
- Spring Kafka
- MySQL 8.4
- Apache Kafka 3.9
- Maven 3.9+
- Docker

### 项目结构

```text
firefly
├── pom.xml
├── README.md
└── src
    ├── main
    │   ├── java/firefly
    │   │   ├── bean             # DTO、请求和响应对象
    │   │   ├── constant         # 状态、插件和触发类型
    │   │   ├── controller       # REST API
    │   │   ├── dao              # JPA Repository
    │   │   ├── model            # 数据库实体
    │   │   └── service          # 配置、构建、触发和消息处理
    │   └── resources
    │       ├── application.yaml # 应用配置
    │       └── v1.sql           # MySQL 表结构
    └── test                     # Spring 上下文和单元测试
```

### 本地环境

请先确认 Java 和 Maven 使用 Java 25：

```bash
java -version
mvn -version
```

#### 启动 MySQL

在仓库根目录执行：

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

该命令会创建 `firefly` 数据库，并在首次启动时执行 `v1.sql`。

#### 启动 Kafka

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

创建 Firefly 使用的 Topic：

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

检查容器状态：

```bash
docker ps --filter name=firefly-mysql --filter name=firefly-kafka
```

### 构建与测试

```bash
mvn test
mvn clean package
```

构建成功后，可执行 JAR 位于：

```text
target/firefly-0.0.1-SNAPSHOT.jar
```

### 启动应用

使用 Maven：

```bash
mvn spring-boot:run
```

或运行打包后的 JAR：

```bash
java -jar target/firefly-0.0.1-SNAPSHOT.jar
```

服务默认监听：

```text
http://localhost:9999
```

### API

#### 创建 Pipeline

```http
POST /create/pipeline
Content-Type: application/json
```

示例：

```bash
curl -X POST http://localhost:9999/create/pipeline \
  -H 'Content-Type: application/json' \
  -d '{
    "uuid": "1111111111111111111111111111111111111111111111111111111111111111",
    "name": "demo-pipeline",
    "triggerModel": "MANUAL",
    "triggerMatch": "ACCURATE",
    "triggerOrigin": "VOLCANO",
    "originInfo": {
      "ak": "local-ak",
      "sk": "local-sk"
    },
    "stageConfigs": [
      {
        "uuid": "2222222222222222222222222222222222222222222222222222222222222222",
        "name": "build-stage",
        "jobConfigs": [
          [
            {
              "uuid": "3333333333333333333333333333333333333333333333333333333333333333",
              "name": "first-text-job",
              "pluginType": "TEXT",
              "pluginRaw": {
                "text": "hello firefly"
              }
            },
            {
              "uuid": "4444444444444444444444444444444444444444444444444444444444444444",
              "name": "second-text-job",
              "pluginType": "TEXT",
              "pluginRaw": {
                "text": "pipeline completed"
              }
            }
          ]
        ]
      }
    ]
  }'
```

`jobConfigs` 的外层列表表示并行 Job 链，内层列表表示同一条链中按顺序执行的 Job。

#### 查询 Pipeline

```bash
curl 'http://localhost:9999/pipeline?uuid=1111111111111111111111111111111111111111111111111111111111111111'
```

响应中的 `id` 是手动触发时使用的 `pipelineId`。

#### 手动触发 Pipeline

```bash
curl -X POST http://localhost:9999/manual_trigger/pipeline \
  -H 'Content-Type: application/json' \
  -d '{
    "pipelineId": 1,
    "uuid": "5555555555555555555555555555555555555555555555555555555555555555",
    "triggerModel": "MANUAL",
    "triggerMatch": "ACCURATE",
    "triggerOrigin": "VOLCANO"
  }'
```

接口返回本次执行的 Pipeline Build ID。

### 容器管理

```bash
docker stop firefly-mysql firefly-kafka
docker start firefly-mysql firefly-kafka
```

---

## English

Firefly is a lightweight, message-driven pipeline orchestration engine built with Spring Boot, Kafka, and MySQL. It models workflows through four layers—Pipeline, Stage, Job, and Plugin—and advances their execution state through Kafka messages.

### Core capabilities

- A Pipeline contains multiple Stages that run in sequence.
- A Stage can contain one or more Job chains, and separate chains can advance in parallel.
- Pipeline, Stage, Job, and Plugin execution states are persisted in MySQL.
- Kafka decouples components and transports state messages.
- A `TEXT` plugin demonstrates plugin configuration and execution.
- REST APIs are available for creating, querying, and manually triggering Pipelines.
- Volcano is supported as a trigger origin with its access configuration.

### Execution flow

```text
Manual Trigger
      |
      v
Pipeline Message
      |
      v
 Stage Message
      |
      v
  Job Message
      |
      v
Plugin Message
      |
      v
Job -> Stage -> Pipeline status completion
```

1. The manual trigger API creates a Pipeline Build and publishes a Pipeline message.
2. The Pipeline message marks the Pipeline as running and starts its first Stage.
3. The Stage message starts every head Job in that Stage.
4. Each Job message executes its configured plugin.
5. Plugin completion updates the Job, and completed Job chains update the Stage.
6. A successful Stage starts the next Stage until the Pipeline completes.
7. A Job or Stage failure is propagated to its parent execution.

### Technology stack

- Java 25
- Spring Boot 3.5.4
- Spring Data JPA
- Spring Kafka
- MySQL 8.4
- Apache Kafka 3.9
- Maven 3.9+
- Docker

### Project structure

```text
firefly
├── pom.xml
├── README.md
└── src
    ├── main
    │   ├── java/firefly
    │   │   ├── bean             # DTOs, request, and response objects
    │   │   ├── constant         # Status, plugin, and trigger types
    │   │   ├── controller       # REST APIs
    │   │   ├── dao              # JPA repositories
    │   │   ├── model            # Database entities
    │   │   └── service          # Configuration, builds, triggers, and messaging
    │   └── resources
    │       ├── application.yaml # Application configuration
    │       └── v1.sql           # MySQL schema
    └── test                     # Spring context and unit tests
```

### Local environment

Verify that Java and Maven both use Java 25:

```bash
java -version
mvn -version
```

#### Start MySQL

Run the following commands from the repository root:

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

This creates the `firefly` database and runs `v1.sql` during the first startup.

#### Start Kafka

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

Create the topics used by Firefly:

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
docker ps --filter name=firefly-mysql --filter name=firefly-kafka
```

### Build and test

```bash
mvn test
mvn clean package
```

The executable JAR is generated at:

```text
target/firefly-0.0.1-SNAPSHOT.jar
```

### Run the application

With Maven:

```bash
mvn spring-boot:run
```

Or with the packaged JAR:

```bash
java -jar target/firefly-0.0.1-SNAPSHOT.jar
```

The service listens on:

```text
http://localhost:9999
```

### API

#### Create a Pipeline

```http
POST /create/pipeline
Content-Type: application/json
```

Example:

```bash
curl -X POST http://localhost:9999/create/pipeline \
  -H 'Content-Type: application/json' \
  -d '{
    "uuid": "1111111111111111111111111111111111111111111111111111111111111111",
    "name": "demo-pipeline",
    "triggerModel": "MANUAL",
    "triggerMatch": "ACCURATE",
    "triggerOrigin": "VOLCANO",
    "originInfo": {
      "ak": "local-ak",
      "sk": "local-sk"
    },
    "stageConfigs": [
      {
        "uuid": "2222222222222222222222222222222222222222222222222222222222222222",
        "name": "build-stage",
        "jobConfigs": [
          [
            {
              "uuid": "3333333333333333333333333333333333333333333333333333333333333333",
              "name": "first-text-job",
              "pluginType": "TEXT",
              "pluginRaw": {
                "text": "hello firefly"
              }
            },
            {
              "uuid": "4444444444444444444444444444444444444444444444444444444444444444",
              "name": "second-text-job",
              "pluginType": "TEXT",
              "pluginRaw": {
                "text": "pipeline completed"
              }
            }
          ]
        ]
      }
    ]
  }'
```

The outer `jobConfigs` list represents parallel Job chains. Jobs in each inner list run sequentially.

#### Query a Pipeline

```bash
curl 'http://localhost:9999/pipeline?uuid=1111111111111111111111111111111111111111111111111111111111111111'
```

The response `id` is the `pipelineId` used by the manual trigger API.

#### Manually trigger a Pipeline

```bash
curl -X POST http://localhost:9999/manual_trigger/pipeline \
  -H 'Content-Type: application/json' \
  -d '{
    "pipelineId": 1,
    "uuid": "5555555555555555555555555555555555555555555555555555555555555555",
    "triggerModel": "MANUAL",
    "triggerMatch": "ACCURATE",
    "triggerOrigin": "VOLCANO"
  }'
```

The response is the Pipeline Build ID for this execution.

### Container management

```bash
docker stop firefly-mysql firefly-kafka
docker start firefly-mysql firefly-kafka
```
