[简体中文](#-简体中文) | [English](#-english)

<details open>
<summary>简体中文</summary>

# Firefly

Firefly 是一个基于 Spring Boot 和 Kafka 构建的轻量级、消息驱动的流水线编排引擎。它旨在帮助开发者快速定义和执行由多个阶段（Stage）和作业（Job）组成的复杂工作流，并通过外部事件或手动操作进行触发。

这个项目的核心思想是将流水线的每个环节（Pipeline, Stage, Job, Plugin）都视为独立的单元，通过 Kafka 消息进行解耦和状态推进，从而实现高度的灵活性和可扩展性。

## 项目解决什么问题？

在许多业务场景中，一个完整的任务通常由一系列相互依赖的步骤构成。例如，一个 CI/CD 流程可能包括代码拉取、编译、测试、打包和部署等多个阶段。Firefly 提供了一套标准化的模型来描述和执行此类流水线任务，它解决了以下核心问题：

- **流水线编排**：提供一种结构化的方式来定义包含多个阶段（串行）和作业（并行/串行）的复杂工作流。
- **事件驱动**：能够响应来自不同系统（如火山引擎 VOLCANO 事件、GitHub Webhook 等）的事件，自动触发相应的流水线。
- **状态追踪**：通过将流水线、阶段和作业的执行状态持久化到数据库，并利用 Kafka 消息驱动状态流转，实现对工作流进度的可靠追踪。
- **插件化扩展**：允许通过插件（Plugin）机制扩展流水线作业的具体功能，目前提供了一个简单的 `TEXT` 插件作为示例。

## 架构与核心概念

为了更好地理解 Firefly 的工作方式，以下是一些核心概念的简介：

- **Pipeline**：流水线是最高层级的执行单元，代表一个完整的工作流。它由一个或多个 `Stage` 组成。
- **Stage**：阶段是流水线中的一个独立步骤，多个 `Stage` 按顺序串行执行。每个 `Stage` 内部可以包含一个或多个 `Job`。
- **Job**：作业是 `Stage` 中最小的可执行单元，负责执行具体的任务。在同一个 `Stage` 内，`Job` 可以被组织成并行或串行执行。
- **Plugin**：插件是 `Job` 背后实际的执行逻辑。例如，一个 `Job` 可以配置使用一个 `Git` 插件来拉取代码，或者使用一个 `Shell` 插件来执行脚本。目前项目内实现了一个 `TEXT` 插件，用于演示插件机制。
- **触发来源 (TriggerOrigin)**：定义了触发流水线的事件来源。目前支持 `VOLCANO`（火山引擎）和 `GITHUB` 两种来源。
- **触发器 (Trigger)**：负责处理来自特定 `TriggerOrigin` 的原始事件，解析并构建标准化的消息，然后启动流水线执行流程。

### 消息驱动的状态推进

Firefly 的核心是围绕 Kafka 消息构建的。当一个流水线被触发后，它的状态推进完全由消息驱动：

1.  **触发**：外部事件（如手动触发）进入系统后，`Trigger` 会生成一条启动流水线的消息，发送到 `pipeline_message` 主题。
2.  **Pipeline -> Stage**：`MessageCenter` 监听到 `pipeline_message`，将流水线状态更新为“运行中”，然后为该流水线的第一个 `Stage` 创建一条启动消息，发送到 `stage_message` 主题。
3.  **Stage -> Job**：`MessageCenter` 监听到 `stage_message`，将阶段状态更新为“运行中”，然后为该阶段内的“头节点” `Job`（没有前置依赖的 Job）创建启动消息，发送到 `job_message` 主题。
4.  **Job -> Plugin**：`MessageCenter` 监听到 `job_message`，将作业状态更新为“运行中”，并调用对应的 `Plugin` 执行逻辑。在当前实现中，`Plugin` 会发送一条消息到 `plugin_topic` 来模拟执行。
5.  **Plugin -> Job -> Stage -> Pipeline**：插件执行完成后，会发送一条结果消息，触发 `Job` 状态更新。当一个 `Job` 完成后，系统会检查其后续 `Job` 并触发它们。当一个 `Stage` 内的所有 `Job` 都成功完成，系统会发送消息更新 `Stage` 状态，并触发下一个 `Stage`，直至整个 `Pipeline` 完成。

这种设计使得各组件之间高度解耦，易于独立扩展和维护。

## 目录结构概览

```
firefly
├── src
│   ├── main
│   │   ├── java/firefly
│   │   │   ├── bean           # VO/DTO 定义
│   │   │   ├── configuration  # 应用配置 (如 Kafka)
│   │   │   ├── constant       # 枚举与常量
│   │   │   ├── controller     # REST API 入口
│   │   │   ├── dao            # 数据访问层 (JPA Repository)
│   │   │   ├── model          # 数据库实体
│   │   │   └── service        # 核心业务逻辑
│   │   │       ├── messagecenter # Kafka 消息处理中心
│   │   │       ├── pipelinebuild # 流水线执行服务
│   │   │       ├── pipelineconfig# 流水线配置服务
│   │   │       ├── pluginbuild   # 插件执行服务
│   │   │       ├── pluginconfig  # 插件配置服务
│   │   │       ├── trigger       # 触发器实现
│   │   │       └── triggerorigin # 触发来源实现
│   │   └── resources
│   │       ├── application.properties # Spring Boot 配置文件
│   │       └── v1.sql                 # 数据库 Schema
│   └── test
└── pom.xml                  # Maven 依赖配置
```

## 前置条件与快速开始

在本地运行 Firefly 之前，请确保你的开发环境中已安装以下软件：

- **JDK 25**
- **Maven 3.6+**
- **MySQL 8.0+**
- **Kafka 2.x+**

### 1. 数据库与中间件配置

- **MySQL**：
    - 在本地 MySQL 中创建一个名为 `firefly` 的数据库。
    - 执行 `src/main/resources/v1.sql` 文件中的 SQL 语句，创建所需的表结构。
    - 根据你的 MySQL 配置，修改 `src/main/resources/application.properties` 文件中的数据库连接信息：
      ```properties
      spring.datasource.url=jdbc:mysql://localhost:3306/firefly
      spring.datasource.username=root
      spring.datasource.password=your_password
      ```

- **Kafka**：
    - 确保 Kafka 服务正在运行，并且默认监听地址为 `127.0.0.1:9092`。
    - Firefly 会自动使用以下 topic，你无需手动创建：
      - `pipeline_message`
      - `stage_message`
      - `job_message`
      - `plugin_topic`

### 2. 启动项目

你可以通过以下两种方式启动项目：

- **使用 Maven 插件**（推荐用于开发）：
  ```bash
  mvn spring-boot:run
  ```
- **打包为 JAR 文件后运行**：
  ```bash
  # 首先，编译并打包项目
  mvn clean package
  
  # 然后，运行 JAR 文件
  java -jar target/firefly-0.0.1-SNAPSHOT.jar
  ```

项目成功启动后，将在 `9999` 端口上提供服务。

</details>

<details>
<summary>English</summary>

# Firefly

Firefly is a lightweight, message-driven pipeline orchestration engine built on Spring Boot and Kafka. It is designed to help developers quickly define and execute complex workflows composed of multiple stages and jobs, which can be triggered by external events or manual operations.

The core idea of this project is to treat every part of the pipeline (Pipeline, Stage, Job, Plugin) as an independent unit, decoupled via Kafka messages to drive state transitions, achieving high flexibility and scalability.

## What Problem Does This Project Solve?

In many business scenarios, a complete task is often composed of a series of interdependent steps. For example, a CI/CD process might include stages like fetching code, compiling, testing, packaging, and deploying. Firefly provides a standardized model to describe and execute such pipeline tasks, addressing the following core problems:

- **Pipeline Orchestration**: Provides a structured way to define complex workflows containing multiple stages (sequential) and jobs (parallel/sequential).
- **Event-Driven**: Capable of responding to events from various systems (e.g., VOLCANO events from Volcano Engine, GitHub Webhooks) to automatically trigger corresponding pipelines.
- **State Tracking**: Reliably tracks workflow progress by persisting the execution state of pipelines, stages, and jobs to a database and using Kafka messages to drive state transitions.
- **Pluggable Extension**: Allows for the extension of job functionalities through a plugin mechanism. A simple `TEXT` plugin is currently provided as an example.

## Architecture and Core Concepts

To better understand how Firefly works, here is a brief introduction to its core concepts:

- **Pipeline**: The highest-level execution unit, representing a complete workflow. It consists of one or more `Stage`s.
- **Stage**: An independent step within a pipeline. Multiple `Stage`s are executed sequentially. Each `Stage` can contain one or more `Job`s.
- **Job**: The smallest executable unit within a `Stage`, responsible for performing a specific task. Jobs within the same `Stage` can be organized to run in parallel or sequentially.
- **Plugin**: The actual execution logic behind a `Job`. For example, a `Job` can be configured to use a `Git` plugin to pull code or a `Shell` plugin to execute a script. The project currently implements a `TEXT` plugin to demonstrate the mechanism.
- **TriggerOrigin**: Defines the source of the event that triggers a pipeline. Currently, `VOLCANO` (Volcano Engine) and `GITHUB` are supported.
- **Trigger**: Processes raw events from a specific `TriggerOrigin`, parses them to build standardized messages, and then initiates the pipeline execution process.

### Message-Driven State Progression

The core of Firefly is built around Kafka messages. Once a pipeline is triggered, its state progression is entirely message-driven:

1.  **Trigger**: An external event (e.g., a manual trigger) enters the system. The `Trigger` generates a message to start the pipeline and sends it to the `pipeline_message` topic.
2.  **Pipeline -> Stage**: The `MessageCenter` listens for `pipeline_message`, updates the pipeline's status to "running," creates a start message for the pipeline's first `Stage`, and sends it to the `stage_message` topic.
3.  **Stage -> Job**: The `MessageCenter` listens for `stage_message`, updates the stage's status to "running," and creates start messages for the "head" `Job`s in that stage (those with no preceding dependencies), sending them to the `job_message` topic.
4.  **Job -> Plugin**: The `MessageCenter` listens for `job_message`, updates the job's status to "running," and invokes the corresponding `Plugin`'s execution logic. In the current implementation, the `Plugin` sends a message to the `plugin_topic` to simulate execution.
5.  **Plugin -> Job -> Stage -> Pipeline**: After the plugin finishes execution, it sends a result message, which triggers a `Job` status update. When a `Job` completes, the system checks for its subsequent `Job`s and triggers them. When all `Job`s in a `Stage` have successfully completed, the system sends a message to update the `Stage`'s status and triggers the next `Stage`, continuing until the entire `Pipeline` is finished.

This design ensures that components are highly decoupled, making them easy to extend and maintain independently.

## Directory Structure Overview

```
firefly
├── src
│   ├── main
│   │   ├── java/firefly
│   │   │   ├── bean           # VO/DTO definitions
│   │   │   ├── configuration  # Application configuration (e.g., Kafka)
│   │   │   ├── constant       # Enums and constants
│   │   │   ├── controller     # REST API entrypoints
│   │   │   ├── dao            # Data access layer (JPA Repository)
│   │   │   ├── model          # Database entities
│   │   │   └── service        # Core business logic
│   │   │       ├── messagecenter # Kafka message handling center
│   │   │       ├── pipelinebuild # Pipeline execution service
│   │   │       ├── pipelineconfig# Pipeline configuration service
│   │   │       ├── pluginbuild   # Plugin execution service
│   │   │       ├── pluginconfig  # Plugin configuration service
│   │   │       ├── trigger       # Trigger implementations
│   │   │       └── triggerorigin # Trigger origin implementations
│   │   └── resources
│   │       ├── application.properties # Spring Boot configuration file
│   │       └── v1.sql                 # Database Schema
│   └── test
└── pom.xml                  # Maven dependency configuration
```

## Prerequisites and Quick Start

Before running Firefly locally, please ensure that the following software is installed in your development environment:

- **JDK 25**
- **Maven 3.6+**
- **MySQL 8.0+**
- **Kafka 2.x+**

### 1. Database and Middleware Configuration

- **MySQL**:
    - Create a database named `firefly` in your local MySQL instance.
    - Execute the SQL statements from the `src/main/resources/v1.sql` file to create the required table structure.
    - Modify the database connection information in the `src/main/resources/application.properties` file according to your MySQL setup:
      ```properties
      spring.datasource.url=jdbc:mysql://localhost:3306/firefly
      spring.datasource.username=root
      spring.datasource.password=your_password
      ```

- **Kafka**:
    - Ensure that the Kafka service is running and listening on the default address `127.0.0.1:9092`.
    - Firefly will automatically use the following topics; you do not need to create them manually:
      - `pipeline_message`
      - `stage_message`
      - `job_message`
      - `plugin_topic`

### 2. Starting the Project

You can start the project in one of two ways:

- **Using the Maven Plugin** (recommended for development):
  ```bash
  mvn spring-boot:run
  ```
- **Packaging as a JAR File and Running**:
  ```bash
  # First, compile and package the project
  mvn clean package
  
  # Then, run the JAR file
  java -jar target/firefly-0.0.1-SNAPSHOT.jar
  ```

After the project starts successfully, it will be available on port `9999`.

</details>

