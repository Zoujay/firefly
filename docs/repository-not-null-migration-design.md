# Firefly 全仓库 MySQL NOT NULL 改造设计

> 状态：待实现的技术方案。本次仅新增文档，不修改业务代码、建库脚本或实际数据库。
>
> 源码基线：`main` / `78a6cba934d600a12cd74f15cf9d2cc2b19649bf`；核对日期：2026-09-16。
>
> 目标：所有 Firefly 业务表的所有列均为 `NOT NULL`，持久化数据中不再存在 SQL NULL，且不破坏身份校验、唯一性、幂等和状态机语义。

## 1. 范围与关键决策

本方案落实 [Volcano 设计中的全仓库规范](firefly-volcano-tech-design.md#60-全仓库-not-null-规范与缺省值约定)，不是只修复 Volcano 或 GitHub 的某几列。

范围包括：

- 全量建库基线、前向迁移、新增表、测试建库脚本和文档里的目标表结构。
- 所有实体及继承映射、INSERT/UPDATE、JPQL/native SQL、DTO 到持久化模型的转换。
- 既有数据库的 NULL 数据、空值依赖逻辑、唯一索引、查询索引和升级链。
- 全新安装、历史升级、ORM 生成结构和数据库实际元数据的验收。

本方案选择以下实现路线：

1. 可选文本用空字符串；非唯一的可选关联 ID 用 `0`；未发生/无法还原的时间用已有的 `PersistenceDefaults.UNSET_TIME`。每种替代值必须有明确业务语义。
2. 必填的业务事实仍须校验。不能把未知仓库、未知身份或缺失的有效凭据统一填成“成功”的默认值。
3. 两个“可选且唯一”的关联拆为独立绑定表：Subscription → Webhook、Trigger → Pipeline Build。关联缺失用“不存在绑定行”表示；绑定表只允许真实的正数 ID。
4. OAuth 保留当前“条件删除、独立事务、最多消费一次”的模型；不借本次改造改成另一套 OAuth 状态机。
5. 默认采用有维护窗口的升级，停止旧写入方后完成转换；不承诺旧程序和新程序混跑兼容。
6. 当前维护的 `v1.sql` 更新为最终结构；已执行的 `v2_github_oauth.sql` 不篡改，通过新增前向迁移消除其最终 Schema 中的可空列。

“禁 NULL”指数据库列和 SQL NULL 数据，不是删除 Java 的 `null` 关键字。API 中的缺失值、`Optional.empty()`、原始 Webhook JSON 中的 JSON `null` 可以保留原契约，但不能直接成为数据库列的 SQL NULL。不得修改原始签名载荷来消除 JSON `null`。

## 2. 基线事实与完整问题清单

### 2.1 扫描结论

- [v1.sql](../firefly-app/src/main/resources/v1.sql)：24 张表、218 个字段，其中 17 个字段显式允许 NULL，分布在 5 张 GitHub 表。
- [v2_github_oauth.sql](../firefly-app/src/main/resources/v2_github_oauth.sql)：包含上述 17 个字段，还允许 `github_trigger.created_at` 为 NULL，共 18 个不同的可空字段。
- 当前 `v1.sql` 是含 GitHub 表的完整建库基线，不能先执行它再执行 `v2_github_oauth.sql`；后者针对更早的安装结构。
- [application.yaml](../firefly-app/src/main/resources/application.yaml) 使用 `ddl-auto: none`。仅修改实体注解不会升级已有数据库。
- [MySqlTestcontainersConfiguration](../firefly-app/src/test/java/firefly/support/MySqlTestcontainersConfiguration.java) 使用 `mysql:8.4` 和 `v1.sql`，当前初始化方式不能证明历史升级正确。
- Volcano 文档中的 8 张设计表、175 个字段已经声明 `NOT NULL`，但这些设计表尚不能代替对实际 SQL 和存量库的验收。

没有连接任何部署环境数据库；下表是源码定义清单，不是生产 NULL 行数报告。实施前还必须做第 7 节的实际库清查。

### 2.2 18 个字段的目标契约

`U` 表示 `1970-01-01 00:00:00.000000`，对应现有 `UNSET_TIME`；它不是实际事件发生时间。

| # | 表.字段 | 原类型；v1 / v2 行号 | 改造后的持久化表示与规则 |
| --- | --- | --- | --- |
| 1 | `github_trigger.delivery_id` | VARCHAR(64)；66 / 9 | `NOT NULL DEFAULT ''`；只允许历史不完整记录使用空串，新事件必须有真实 Delivery ID |
| 2 | `github_trigger.pipeline_id` | BIGINT(20)；67 / 10 | `NOT NULL DEFAULT 0`；历史未知为 0，新 Trigger 必须大于 0 |
| 3 | `github_trigger.pipeline_build_id` | BIGINT(20)，UNIQUE；68 / 11 | 移入 `github_trigger_build_binding.pipeline_build_id NOT NULL UNIQUE`；未知关联无绑定行，见第 4 节 |
| 4 | `github_trigger.github_repository_id` | BIGINT(20)；69 / 12 | `NOT NULL DEFAULT 0`；历史未知为 0，新事件必须大于 0 |
| 5 | `github_trigger.event_type` | VARCHAR(64)；71 / 13 | `NOT NULL DEFAULT ''`；历史未知为空串，新事件须为校验通过的事件类型 |
| 6 | `github_trigger.action` | VARCHAR(64)；72 / 14 | `NOT NULL DEFAULT ''`；push 不适用时为空串，PR 仍按事件规则校验 action |
| 7 | `github_trigger.source_branch` | VARCHAR(512)；73 / 15 | `NOT NULL DEFAULT ''`；仅不适用或历史未知时为空串，不把空串当匹配所有分支 |
| 8 | `github_trigger.target_branch` | VARCHAR(512)；74 / 16 | `NOT NULL DEFAULT ''`；push 无目标分支时为空串；PR 分支校验不放宽 |
| 9 | `github_trigger.head_sha` | VARCHAR(64)；75 / 17 | `NOT NULL DEFAULT ''`；按事件类型区分不适用/未知与真实 SHA，不生成假 SHA |
| 10 | `github_trigger.created_at` | DATETIME(6)；77 非空 / 19 可空 | `NOT NULL`，不设自动时间默认值；新记录填真实接收时间，历史不可还原时显式填 U 并保留历史标记 |
| 11 | `github_oauth_state.consumed_at` | DATETIME(6)；91 / 31 | `NOT NULL DEFAULT U`；U 表示未消费；当前消费成功会删除整行，而非更新时间 |
| 12 | `github_repository_subscription.connection_id` | BIGINT(20)；123 / 63 | `NOT NULL DEFAULT 0`；0 表示无 Connection，不允许授权、创建 Hook 或恢复 ACTIVE |
| 13 | `github_repository_subscription.webhook_id` | BIGINT(20)，UNIQUE；132 / 72 | 移入 `github_subscription_webhook_binding.webhook_id NOT NULL UNIQUE`；未绑定时无绑定行，见第 4 节 |
| 14 | `github_webhook_delivery.action` | VARCHAR(64)；173 / 113 | `NOT NULL DEFAULT ''`；仅将可选 action 规范化，不改动 `payload` |
| 15 | `github_webhook_delivery.processing_started_at` | DATETIME(6)；179 / 119 | `NOT NULL DEFAULT U`；U 表示当前没有可用的开始时间，PROCESSING 新记录必须填真实时间 |
| 16 | `github_webhook_delivery.next_retry_at` | DATETIME(6)；180 / 120 | `NOT NULL DEFAULT U`；U 表示没有安排重试，不表示“立即重试”；RETRYABLE 必须有真实计划时间 |
| 17 | `github_webhook_delivery.processing_finished_at` | DATETIME(6)；183 / 123 | `NOT NULL DEFAULT U`；U 表示本次尚未完成或历史时间未知，不能按 U 判断处理成功 |
| 18 | `github_delivery_pipeline.pipeline_build_id` | BIGINT(20)，非 UNIQUE；195 / 135 | `NOT NULL DEFAULT 0`；创建 Build 前为 0，SUCCESS 必须大于 0 |

第 3、13 项是关联模型迁移，最终不保留原来的可空列。最终删去旧父表列，所有关联列在新表中均为 `NOT NULL`；外部响应仍可通过 DTO 返回原来的可选 ID。

## 3. 缺省值与校验原则

### 3.1 类型约定

| 场景 | 持久化值 | 边界约束 |
| --- | --- | --- |
| 可选说明、错误信息、无适用 action | `''` | 只对契约允许的字段转换；必填名称、真实身份、有效密文不得用空串代替真实值 |
| 非唯一的未绑定 ID | `0L` | 真实 ID 必须大于 0；外部 API 调用、Repository 查找和权限校验前拒绝 0 |
| 未发生/未知时间 | `UNSET_TIME` | UTC、DATETIME(6)；展示层转为“未发生/未知”或原有可选值，禁止展示 1970 为真实时间 |
| 没有适用项的集合 | 与类型匹配的 `[]` / `{}` | 仅对允许空集合的字段使用；不能把 SQL NULL 改成 JSON 标量 `null` 当作整改 |
| 可选的一对一唯一关联 | 没有绑定记录 | 有记录时每列非空且关联 ID 真实有效 |
| 必填布尔事实未知 | 显式 UNKNOWN 状态或缺少事实行 | 不得将“未知是否私有”映射成公开或 false |

已有 [PersistenceDefaults](../firefly-app/src/main/java/firefly/constant/PersistenceDefaults.java) 定义 `UNSET_TIME`。复用该常量，可增加具名的 `UNBOUND_ID = 0L`，但不要新增“任何 null 都转换成默认值”的全局转换器。

### 3.2 实体、SQL 与 API 三层同时约束

- 全部持久化属性显式声明 `@Column(nullable = false)` 或对应的非空关联列映射，覆盖 `@MappedSuperclass` 继承字段。
- `@Id` 的 Java 包装类型在自增 ID 生成前可以为 null；数据库自增主键仍为 `NOT NULL`，不能因此取消 `GenerationType.IDENTITY`。
- 仅有 SQL DEFAULT 不足以覆盖显式传入 null 的业务路径；在构造实体、保存前和执行批量 JPQL/native UPDATE 时按契约赋值/拒绝输入。
- 字段初始化不能防止 Lombok setter 后续写入 null。使用具名工厂/服务转换及保存前校验；批量更新必须单独检查，不能依赖实体回调。
- 必填字段校验失败应报错，不得依靠数据库宽松模式悄悄补值。应用连接与迁移连接均核对严格 SQL mode。
- DTO/API 可以继续表达缺失值；例如未绑定 webhook 在响应中保持缺失/null，但数据库使用无绑定行，不能把假 Hook ID 返回给调用方。

`@Column.nullable` 的默认值是 true，因此现有 SQL 已非空的模块也需核对映射，而不是只改上表涉及的实体。[Jakarta Persistence Column 文档](https://jakarta.ee/specifications/persistence/3.1/apidocs/jakarta.persistence/jakarta/persistence/column)

## 4. 两个可选唯一关联的改造

### 4.1 为什么采用绑定表

原索引为：

- `github_trigger.uidx_github_trigger_pipeline_build (pipeline_build_id)`。
- `github_repository_subscription.uidx_github_subscription_webhook (webhook_id)`。

MySQL 唯一索引允许多个 NULL，但不允许多个相同的普通值。直接改成 `NOT NULL DEFAULT 0` 会使第二条未绑定记录发生冲突。[MySQL 唯一索引说明](https://dev.mysql.com/doc/refman/8.4/en/create-index.html)

也不采用以下绕过：删除真实 ID 的唯一约束；把父记录 ID 拼进联合唯一键而放任同一真实 ID 多次绑定；用返回 NULL 的生成列实现过滤唯一索引；借负数伪造云端 ID。

本方案将“是否存在关联”与“真实唯一 ID”分开建模，不新增数据库外键，继续使用应用层逻辑关联和分表查询。

### 4.2 目标 DDL

以下是拟实施结构，不代表本分支已经创建这些表。`created_at` 表示本地绑定行创建时间，迁移时填迁移执行的真实 UTC 时间，不能将它冒充旧 Trigger/Hook 的创建时间。

```sql
CREATE TABLE `firefly`.`github_subscription_webhook_binding`
(
    `subscription_id` BIGINT NOT NULL,
    `webhook_id`      BIGINT NOT NULL,
    `created_at`      DATETIME(6) NOT NULL,
    PRIMARY KEY (`subscription_id`),
    UNIQUE INDEX `uidx_github_binding_webhook` (`webhook_id`),
    CONSTRAINT `chk_github_binding_subscription_positive` CHECK (`subscription_id` > 0),
    CONSTRAINT `chk_github_binding_webhook_positive` CHECK (`webhook_id` > 0)
);

CREATE TABLE `firefly`.`github_trigger_build_binding`
(
    `trigger_id`        BIGINT NOT NULL,
    `pipeline_build_id` BIGINT NOT NULL,
    `created_at`        DATETIME(6) NOT NULL,
    PRIMARY KEY (`trigger_id`),
    UNIQUE INDEX `uidx_github_binding_pipeline_build` (`pipeline_build_id`),
    CONSTRAINT `chk_github_binding_trigger_positive` CHECK (`trigger_id` > 0),
    CONSTRAINT `chk_github_binding_build_positive` CHECK (`pipeline_build_id` > 0)
);
```

不存在绑定行是合法的“未关联”，不是可空字段，也不是把 NULL 转移到新表。新表中的每一个值都必须真实、非空；重复 Hook ID 或 Build ID 仍由数据库拒绝。

### 4.3 Subscription / Webhook 的事务与并发

新增 `GitHubSubscriptionWebhookBindingEntity/Repository` 和短事务绑定服务，替代父实体上的持久化 `webhookId`。

1. 创建 Subscription 时不插入绑定行；MANUAL 和 AUTO 均允许多个未绑定 Subscription 共存。
2. 根据 Hook ID 定位时先查绑定表，再按 `subscription_id` 查 Subscription；不得因为找到了绑定就跳过 Repository ID、签名、Hook ID 和父记录状态校验。
3. MANUAL 首次 ping 的候选仍须满足 Repository ID 一致、状态为 PROVISIONING、无绑定行。两次分表读取只用于定位，不能作为最终并发判定。
4. 最终绑定在短事务内锁定同一个 Subscription 行，重新检查状态、身份和已有绑定；随后插入绑定并更新状态。锁顺序固定为父记录 → 绑定记录。
5. 已绑定相同 Hook ID 是幂等成功；已绑定其他 ID、Hook ID 已归属其他 Subscription、父记录已删除/删除中均拒绝。唯一键异常导致当前事务回滚后，才能在新事务重新读取并区分幂等与冲突。
6. AUTO 的远端 create/update/ping 不持有上述数据库锁；远端操作完成后以短事务补写绑定。处理“ping 先于 API 返回”的竞态：相同绑定可复用，已被有效 ping 激活的 ACTIVE 不能降回 PROVISIONING，不得覆盖另一绑定或恢复已删除记录。
7. 删除/解绑 Connection 与绑定服务使用相同的父记录锁和状态约束。远端删除成功后 `connection_id` 可以置 0；有真实 Hook 的绑定行保留为审计记录。绑定存在不等于 Hook 仍然存在或 Subscription 可运行。
8. 远端创建成功但父记录已变为 DELETING/DELETED 时不恢复 ACTIVE；记录真实远端 Hook ID、操作标识和错误，由受控的删除恢复/人工处理流程核对清理。不得只记一个无远端 ID 的错误导致资源无法追踪；此处不新增轮询机制。

需要调整的现有入口：

- [GitHubRepositorySubscriptionRepository](../firefly-app/src/main/java/firefly/github/dao/GitHubRepositorySubscriptionRepository.java)：`findByWebhookId`、`findAllByGithubRepositoryIdAndWebhookIdIsNullAndStatus`、`bindWebhookIfUnbound`、`activateBoundWebhook`。
- [GitHubSubscriptionService](../firefly-app/src/main/java/firefly/github/service/GitHubSubscriptionService.java)：AUTO 创建/更新、MANUAL 响应、ping、delete、DTO 组装。
- [GitHubWebhookIngressService](../firefly-app/src/main/java/firefly/github/service/GitHubWebhookIngressService.java) 与 [GitHubWebhookDeliveryWriter](../firefly-app/src/main/java/firefly/github/service/GitHubWebhookDeliveryWriter.java)：定位、验签后的绑定事务、拒绝记录及并发冲突分类。
- [GitHubConnectionDisconnectService](../firefly-app/src/main/java/firefly/github/service/GitHubConnectionDisconnectService.java)、[GitHubSubscriptionDeletionStateService](../firefly-app/src/main/java/firefly/github/service/GitHubSubscriptionDeletionStateService.java)：读取删除目标、保留真实 Hook ID、解绑 Connection 的非空写入。

### 4.4 Trigger / Build 的事务

新增 `GitHubTriggerBuildBindingEntity/Repository`，由 [GithubTrigger.saveRealTrigger](../firefly-app/src/main/java/firefly/service/trigger/impl/GithubTrigger.java) 在保存 Trigger 并取得自增 ID 后插入绑定。

- 当前 [AbstractTrigger.dispatch](../firefly-app/src/main/java/firefly/service/trigger/AbstractTrigger.java) 已要求 `pipelineBuildID > 0`。新事件必须同时创建 Trigger 和真实 Build 绑定，不能走“未知关联”分支。
- Trigger、绑定、Pipeline Build 及相关 Outbox 仍处于原业务事务边界内；绑定唯一性失败必须回滚本次 Trigger 与 Outbox，不能留下可执行的半条记录。
- 历史 `legacy_record = 1` 且关联未知的 Trigger 保留原审计行但无绑定行，禁止据此重放部署或 Pipeline。
- 历史已知的真实 Build ID 原样迁移，唯一性保持；不要为了补历史关联而创建假 Build。
- 查询真实 Build 与 Trigger 的关系时先查绑定表再查实体；若相应 Build 已按既有保留策略删除，保留历史 ID 并显示关联已不可用，不能重指向其他 Build。

## 5. 保留列的业务逻辑调整

### 5.1 OAuth State：保留条件删除的单次消费

源码实际行为是 [GitHubOAuthStateWriter.take](../firefly-app/src/main/java/firefly/github/service/GitHubOAuthStateWriter.java) 在 `REQUIRES_NEW` 事务内调用条件 DELETE；只有删除成功的调用者取得 State。

改造要求：

- 创建 State 时显式设 `consumedAt = UNSET_TIME`。
- [GitHubOAuthStateRepository.consumePending](../firefly-app/src/main/java/firefly/github/dao/GitHubOAuthStateRepository.java) 把 `consumedAt is null` 改为具名参数等值条件。
- [GitHubOAuthStateService.consume](../firefly-app/src/main/java/firefly/github/service/GitHubOAuthStateService.java) 把“非 null 已消费”改为“不等于 UNSET_TIME 已消费”。过期时间、session hash 的常量时间比较和先取走再校验的行为不变。
- 存量 NULL 变成 U；已有真实消费时间保持原值，不能重置成 U 使 State 重新可用。

目标 JPQL 片段：

```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("""
    delete from GitHubOAuthStateEntity s
     where s.id = :id
       and s.consumedAt = :unsetTime
    """)
int consumePending(@Param("id") Long id,
    @Param("unsetTime") LocalDateTime unsetTime);
```

两个并发回调仍只能有一个删除成功；不能改成“先查未消费，再无条件返回验证码”。

### 5.2 Webhook Delivery：明确未调度与已到期

当前 [GitHubWebhookDeliveryRepository](../firefly-app/src/main/java/firefly/github/dao/GitHubWebhookDeliveryRepository.java) 的 claim/恢复会主动写 NULL；[GitHubDeliveryStateService](../firefly-app/src/main/java/firefly/github/service/GitHubDeliveryStateService.java) 还会给无后续重试的完成操作传入 null。这些路径必须一起修改。

| 操作/状态 | started_at | finished_at | next_retry_at | 必须保留的约束 |
| --- | --- | --- | --- | --- |
| 新 RECEIVED | U | U | U | 未被领取，不是重试任务 |
| claim → PROCESSING | 当前时间 | U | U | attempt + 1；写入非空 owner；原子条件更新 |
| 完成 → SUCCESS / IGNORED / REJECTED / DEAD | 保留本次开始时间，无处理则为 U | 当前时间 | U | 正常 worker 完成必须校验 owner；ping/入口拒绝可直接终结 |
| 完成 → RETRYABLE | 保留本次开始时间 | 本次结束时间 | 当前时间 + 延迟 | 不能超过最大次数后仍留在 RETRYABLE |
| 租约过期恢复 → RETRYABLE | U | U | 重新安排的真实时间 | 保留 attempt；清除 owner；不能让旧 owner 提交完成 |
| 租约耗尽 → DEAD | U | 当前时间 | U | 不得留下可被自动调度的时间 |
| 手动 requestRetry | U | U | 当前时间 | 只允许既有可重试状态；按当前契约重置 attempt |

`started_at` / `finished_at` 在此表中指 `processing_started_at` / `processing_finished_at`。

目标 claim 条件（其余 attempt 上限、owner 校验保持）：

```sql
WHERE delivery_id = :deliveryId
  AND processing_attempt < :maxAttempts
  AND (
      status = 'RECEIVED'
      OR (
          status = 'RETRYABLE'
          AND next_retry_at > :unsetTime
          AND next_retry_at <= :now
      )
  )
```

- [GitHubDeliveryRecoveryScheduler](../firefly-app/src/main/java/firefly/github/service/GitHubDeliveryRecoveryScheduler.java) 的到期查询也必须同时限定 `status = RETRYABLE`、`next_retry_at > U`、`next_retry_at <= now`，保留最多 100 条及既有索引利用方式。
- 不能只把 `IS NULL OR <= now` 改成 `<= now`，否则 U 会被错误地当作已到期任务。
- [GitHubWebhookDeliveryWriter](../firefly-app/src/main/java/firefly/github/service/GitHubWebhookDeliveryWriter.java) 的正常入库、ping 成功、ping 拒绝和绑定冲突记录都要初始化三个时间字段；action 转空串，原始 payload 原样保存。
- [GitHubDeliveryController](../firefly-app/src/main/java/firefly/github/controller/GitHubDeliveryController.java) 不能把 U 原样输出为完成时间；保留原 API 的可选时间语义。
- 此改造保留当前 GitHub 恢复调度，不给通用 Inbox/Outbox 或 Volcano 增加新轮询器。

### 5.3 Delivery → Pipeline Build 的占位

[GitHubPipelineInvocationService.invoke](../firefly-app/src/main/java/firefly/github/service/GitHubPipelineInvocationService.java) 在创建 Build 之前先 `saveAndFlush` 一条 PROCESSING 记录。必须在首次 flush 前显式设置 `pipelineBuildId = 0L`，否则仅改 DDL 会直接破坏这条路径。

- 已有正数 Build ID 不随重试初始化被覆盖为 0。
- 新 Build 创建成功后在原事务内写真实 ID；`SUCCESS ⇒ pipeline_build_id > 0`。
- `uidx_github_delivery_pipeline (delivery_id, pipeline_id)` 保持，用于防止重复投递重复创建业务关联。
- Build、Trigger 绑定、Outbox 的失败回滚关系不变；不得在独立新事务提前确认 SUCCESS。

### 5.4 Connection 解除关联与历史 Trigger

- `GitHubConnectionDisconnectService` 中 `.setConnectionId(null)` 改为具名的未绑定值 0；仅在已完成解除关联的业务路径执行。远端清理失败时保留真实 Connection ID 以便恢复。
- 0 不能用于查询有效 Connection、解密凭据或调用 GitHub。ACTIVE/PROVISIONING 等正常使用身份的记录必须有正数 Connection ID；历史异常记录先停止使用并人工核对。
- `GithubTrigger` 的可选 action/branch/SHA 在事件匹配完成后、落库前按契约转换；不要修改外部事件解析器的全局 null 语义而改变分支匹配结果。
- 历史 Trigger 仅对确实的 `legacy_record = 1` 记录使用历史缺省规则。非历史记录缺失必填 Delivery、Pipeline、Repository 或 Build 关联时阻断迁移，必须从可信记录修复或审批隔离方案；不能自动改成 legacy 来掩盖错误。
- 历史未知 `created_at` 用 U；不得统一填迁移执行时间。正常新记录使用真实接收时间或明确的 UTC 接收时钟，移除依赖服务器本地时区的隐式补时行为。

## 6. 目标表结构与迁移 SQL 边界

以下 DDL 片段只展示保留列的最终约束，必须在第 7 节的数据回填、绑定迁移和预检查全部完成之后执行。完整迁移必须保留现场列的字符集、排序规则、长度、注释和其他属性，不可直接把片段当作生产脚本粘贴运行。

```sql
ALTER TABLE `firefly`.`github_trigger`
    MODIFY COLUMN `delivery_id` VARCHAR(64) NOT NULL DEFAULT '',
    MODIFY COLUMN `pipeline_id` BIGINT(20) NOT NULL DEFAULT 0,
    MODIFY COLUMN `github_repository_id` BIGINT(20) NOT NULL DEFAULT 0,
    MODIFY COLUMN `event_type` VARCHAR(64) NOT NULL DEFAULT '',
    MODIFY COLUMN `action` VARCHAR(64) NOT NULL DEFAULT '',
    MODIFY COLUMN `source_branch` VARCHAR(512) NOT NULL DEFAULT '',
    MODIFY COLUMN `target_branch` VARCHAR(512) NOT NULL DEFAULT '',
    MODIFY COLUMN `head_sha` VARCHAR(64) NOT NULL DEFAULT '',
    MODIFY COLUMN `created_at` DATETIME(6) NOT NULL;

ALTER TABLE `firefly`.`github_oauth_state`
    MODIFY COLUMN `consumed_at` DATETIME(6) NOT NULL
        DEFAULT '1970-01-01 00:00:00.000000';

ALTER TABLE `firefly`.`github_repository_subscription`
    MODIFY COLUMN `connection_id` BIGINT(20) NOT NULL DEFAULT 0;

ALTER TABLE `firefly`.`github_webhook_delivery`
    MODIFY COLUMN `action` VARCHAR(64) NOT NULL DEFAULT '',
    MODIFY COLUMN `processing_started_at` DATETIME(6) NOT NULL
        DEFAULT '1970-01-01 00:00:00.000000',
    MODIFY COLUMN `next_retry_at` DATETIME(6) NOT NULL
        DEFAULT '1970-01-01 00:00:00.000000',
    MODIFY COLUMN `processing_finished_at` DATETIME(6) NOT NULL
        DEFAULT '1970-01-01 00:00:00.000000',
    ADD CONSTRAINT `chk_github_delivery_retry_due`
        CHECK (`status` <> 'RETRYABLE'
            OR `next_retry_at` > '1970-01-01 00:00:00.000000');

ALTER TABLE `firefly`.`github_delivery_pipeline`
    MODIFY COLUMN `pipeline_build_id` BIGINT(20) NOT NULL DEFAULT 0,
    ADD CONSTRAINT `chk_github_delivery_pipeline_success_build`
        CHECK (`pipeline_build_id` >= 0
            AND (`status` <> 'SUCCESS' OR `pipeline_build_id` > 0));
```

绑定表已具备唯一保护、正数数据迁移已验证且旧写入方停机之后，才在 contract 阶段删除父表的 `webhook_id`、`pipeline_build_id` 列及其原唯一索引。不能先删除唯一保护再复制关联。

保留 `idx_github_delivery_status_retry (status, next_retry_at)`、Delivery/Pipeline 幂等索引以及其他业务索引。给新查询做 EXPLAIN；不得以“消除 NULL”为理由删除现有查询与去重保障。

CHECK 不能替代 NOT NULL；MySQL CHECK 对 UNKNOWN 的处理意味着仍需显式非空列约束。[MySQL CHECK 文档](https://dev.mysql.com/doc/refman/8.4/en/create-table-check-constraints.html)

## 7. 既有数据库迁移与发布

### 7.1 实施前的只读审计

以部署库为准确认：实际 MySQL 版本、SQL mode、所有业务 schema、表引擎、DDL、索引、约束、数据量、迁移版本/校验和及正在运行的应用版本。不能把开发机的 18 个字段当作生产库完整白名单。

对每个可空字段记录：

- SQL NULL 行数、主键批次范围、已有非空值分布和不合法的 0/负数/空串。
- 所属状态；是否可以确定为历史数据；对应读写代码和替代规则。
- 是否参与唯一键、普通索引、逻辑关联、时间排序、身份校验和幂等键。
- 回填来源、不可还原的数据、责任人和阻断原因。禁止把 Token、Secret、PKCE verifier 或完整敏感 payload 打进审计日志。

还要检查所有已有非空字段是否包含非法占位；“不是 NULL”不等于数据有效。

迁移前必须拦截：

1. 非历史 Trigger 缺必填事实；SUCCESS 的 Delivery/Pipeline 没有正数 Build ID。
2. 待迁移的绑定 ID 非正数、重复、与父记录身份矛盾；若关联资源已按保留策略删除，需区分历史审计 ID 与错误关联，不能随意抹掉真实值。
3. ACTIVE/PROVISIONING Subscription 没有有效 Connection；ACTIVE 没有真实 Hook 绑定。
4. 未清理的活跃 PROCESSING、未决外部调用以及无法确认停写的旧应用实例。

### 7.2 发布顺序：默认维护窗口

| 阶段 | 动作 | 继续条件 |
| --- | --- | --- |
| P0 演练 | 从备份恢复到隔离库，运行审计、回填、约束修改、对比和恢复演练 | 记录耗时、锁等待、磁盘需求、异常行数，全部在审批范围内 |
| P1 停写与快照 | 暂停所有应用写入口、Kafka 消费、GitHub 定时恢复、后台管理重试和相关脚本；等待在途事务结束；保存一致备份及重放边界 | 无旧进程继续写入；维护窗口和备份可验证 |
| P2 扩展 | 创建第 4 节的两个全非空绑定表；保留旧列/索引 | 新表约束存在，暂不切换应用 |
| P3 数据转换 | 按 PK 小批事务回填保留列；仅为旧列中真实的正数 ID 创建绑定；逐批比对 | 已知关联集合完全一致；必填错误已解决；剩余 SQL NULL 仅限即将删除的旧关联列 |
| P4 收紧 | 修改保留列为 NOT NULL，添加状态约束；验证后删除旧关联列及原索引 | 新绑定表唯一保护持续有效；所有最终业务列非空；无遗留可空影子列 |
| P5 切换 | 部署匹配新 Schema 的代码；写入口保持关闭，先运行只读 Schema 校验 | 新代码可启动，Schema/迁移版本匹配，所有实例版本一致 |
| P6 恢复 | 受控恢复入口、消费者及原有恢复任务；验证事件去重、OAuth、ping、Pipeline 及 Outbox | 冒烟测试通过，异常指标无新增后再结束维护 |

停写范围包括所有使用同一业务库的 Firefly 实例，而不只是 GitHub HTTP Controller。处理在途工作时先等待正常结束；无法结束的 PROCESSING 任务在确认原 owner 不会再写入后，按已有最大次数和恢复语义转为 RETRYABLE/DEAD，并记录原因，不能同时由旧 worker 与迁移脚本处理。

上述 PROCESSING 转换只针对 GitHub Delivery。通用 Inbox/Outbox 的处理中状态按其现有人工恢复契约保留；特别是 Outbox PUBLISHING 必须核对是否已发送，不能由 NULL 迁移程序批量重置并重发。

Webhook 维护期间不能“先返回 2xx 后丢弃”；入口关闭应明确失败，维护前准备受信任的投递保存/人工重投方案。不假设上游一定自动重发。Kafka 使用既有消费位点与持久化去重记录恢复，不重置 topic/offset 来规避迁移。

本次文档不授权执行上述停写、数据更新、删列或生产恢复。真正实施时须另外确认环境、窗口和备份。

### 7.3 数据转换规则

- 文本：只将 NULL 转为表 2.2 中允许的空串，已有真实文本不修改。
- 非唯一 ID：只对合法的未绑定状态填 0；成功/有效状态缺 ID 必须先修复。
- OAuth：只将 `consumed_at IS NULL` 转为 U，不覆盖已消费时间。
- 时间：未发生或历史不可还原的时间填 U；不得用 `NOW()` 编造历史开始/结束时间。
- RETRYABLE：缺少 `next_retry_at` 的记录不能直接填 U。按当前重试上限决定转 DEAD 或在恢复窗口重新安排真实的 retry 时间，并记录这次调度决策。
- 终态的未知完成时间：允许 U 表示历史未知，但不改变终态、不自动重新执行；新产生的终态必须由业务写入真实结束时间。
- 绑定：从旧列读取真实 ID 逐行插入；迁移重跑时，同一个父 ID 和相同真实 ID 视为已完成，不同映射立即失败。禁止 `INSERT IGNORE` 吞掉冲突。
- 批次记录须包含 run ID、表/字段、PK 范围、转换行数和规则版本。数值相等之外还需核对集合；不能只比新旧行数。

对两个绑定迁移，删除旧列前分别验证：旧列非空正数的 `(父 ID, 真实 ID)` 集合与新表完全一致，原本无关联的父记录在新表中无行，所有真实 ID 的唯一性保持。普通业务查询仍采用分表读取。

### 7.4 迁移文件与安装路径

后续实现建议新增以下受版本控制的迁移产物；具体序号在实施分支基于最新 main 再确认，不能覆盖别人已占用的版本：

- `v3_not_null_expand.sql`：创建绑定表和所需的全非空审计元数据。
- 独立的受审计回填程序：执行状态感知转换、批次校验、重跑与中断恢复。
- `v4_not_null_contract.sql`：验证前置条件后收紧保留列、移除已迁移旧列，写入最终版本记录。

当前代码中没有本方案的迁移 runner。实现时提供显式运维命令，运行前验证目标库、预期 Schema 签名、迁移校验和及步骤状态；不要让应用每次启动自动执行未经批准的回填。

安装链分开维护：

1. 全新安装：执行更新后的完整 `v1.sql`，直接得到最终全非空结构，不再执行 v2/v3/v4 的历史升级操作。
2. 基线时刻已经完整建库的安装：在实际 Schema 与基线匹配后执行 expand → 回填 → contract。
3. 更早的 pre-GitHub 安装：先按其原升级记录执行 `v2_github_oauth.sql`，再执行本次前向迁移；最终结果必须与全新安装语义一致。

完整建库基线是维护中的快照；若某部署把旧 v1 也登记为不可变迁移，保留旧版本校验和并走升级链，不强行用新文件覆盖记录。历史 v2 中保留的 NULL 定义只能作为已冻结输入，不能豁免最终数据库非空验收；新迁移不得引入新的可空业务列。

### 7.5 DDL 失败与恢复

MySQL 的 ALTER TABLE 等 DDL 会隐式提交，不能声称用一个事务就能回滚整次多表改造。[MySQL 隐式提交规则](https://dev.mysql.com/doc/refman/8.4/en/implicit-commit.html)

改为 NOT NULL 可能重建表，且存在 NULL 时修改失败；不能假设 `ALGORITHM=INSTANT`、无锁或固定耗时。实施前在同版本、同结构和相近数据量上验证算法、元数据锁、磁盘空间和严格 SQL mode。[MySQL 在线 DDL 说明](https://dev.mysql.com/doc/refman/8.4/en/innodb-online-ddl-operations.html)

- 每个 DDL/DML 步骤先查当前状态，校验预期后执行，并在成功后登记；重跑不能重复增加已存在的约束或覆盖不同的绑定。
- P2～P4 失败：保持停写，根据已登记步骤前向修复或从 P1 的一致快照恢复整个受影响业务数据集；不得只凭退出码宣称数据库已回滚。
- P5 后发现问题：不能直接切回依赖 nullable 字段/旧关联列的旧应用。优先部署兼容新 Schema 的修复版本。
- 若必须恢复旧版本：继续停写，将恢复点之后的写入和 Kafka/Outbox 发布影响纳入恢复方案；不得仅恢复 MySQL 却忽略已发送消息和外部副作用。恢复到旧 Schema 是回到未达标版本，不代表本次目标完成。
- 原始备份放受控备份存储；不要在在线业务库里长期保留含 NULL 的备份/影子表来绕过全库门禁。备份文件保留原始事实不属于在线业务表。

## 8. 全仓库代码和文档一致性

### 8.1 不只修改 5 个实体

逐个检查 `firefly-app/src/main/java/firefly/model/**`、`firefly-app/src/main/java/firefly/github/model/**` 以及继承映射，覆盖 Pipeline、Stage、Job、Plugin、Trigger、四类 Inbox、Outbox 和 GitHub 全部持久化模型。

- 数据库已非空、ORM 未显式约束的属性补齐映射及必填校验。
- 检查 service 的 save/saveAndFlush、DAO 更新参数、原生 INSERT、批处理和测试 fixture。
- 不改变通用 Inbox/Outbox 的事务、唯一 UUID、owner CAS 和人工恢复契约；其已有非空实现可作为参考，不能作为其他模块的豁免。
- SQL NULL 检测与业务缺值校验分开。SQL 列非空但 payload 内容不合法、关联为 0 却被当作真实 ID，仍必须被拒绝。

### 8.2 旧技术文档中的可空定义

以下是文档中的设计债务，不能把尚未实现的字段混入第 2 节“实际 SQL 18 列”的数量，也不能在未来落地时继续引入可空列：

| 来源 / 字段 | 同步后的设计 |
| --- | --- |
| GitHub OAuth2：Subscription 的 `connection_id`、`webhook_id` | 分别使用未绑定 ID 契约和第 4 节绑定模型；同步 ping、删除及唯一性说明 |
| GitHub OAuth2：`pending_secret_ciphertext`、`pending_secret_nonce`、`pending_secret_key_version`、`previous_secret_ciphertext`、`previous_secret_nonce`、`previous_secret_expires_at` | 后续实现轮换时采用独立 Secret 版本/角色记录；没有 pending/previous 就无对应行；存在时密文、nonce、密钥版本和所需有效期全部必填，不能用空密文冒充有效 Secret |
| GitHub OAuth2：`github_trigger_config.disabled_reason` | 与实际 SQL 一致：`NOT NULL DEFAULT ''`，禁用理由按业务必填 |
| GitHub OAuth2：`github_webhook_delivery.processor_id` 与三个处理时间 | 空 owner 为 `''`，时间采用第 5.2 节状态契约；同步租约和重试描述 |
| GitHub OAuth2：`github_delivery_pipeline.pipeline_build_id` | 使用 0 / 正数 Build ID；SUCCESS 强制正数 |
| GitHub OAuth2：Delivery/Pipeline 设计中的 `processor_id`、`processing_started_at`、`next_retry_at` | 当前 SQL 没有这三个列；如未来新增，必须以 `''` / U 加状态约束设计，不直接复制旧文档的 NULL 定义 |
| GitHub Repository Selection：`private_repository` 的“先增加可空字段再回填” | 改成独立的权威可见性快照记录；未取得 GitHub 响应时无快照行，鉴权按未知拒绝，不能按公开处理；存在的快照行中 `private_repository`、采集时间等全部非空 |

后续实现需同步修改 [GitHub OAuth2 设计](GitHub%20OAuth2%20Tech%20Design.md)、[仓库选择设计](GitHub%20Repository%20Selection%20Tech%20Design.md) 和 [Volcano 设计](firefly-volcano-tech-design.md) 中的存量整改状态与关联模型引用。本分支只新增本方案，旧文档尚未声称已同步或已实施。

对于全非空的 Volcano 草案 DDL，继续保留已有字段特定规则：执行退出码未知不能填成功码 0；尚未获取的唯一云资源 ID 不能统一填空串；凭据到期时间与未发生时间不能混用。

## 9. 验证与持续门禁

### 9.1 实际 Schema 检查

在目标业务数据库执行以下只读查询，最终必须返回零行。对所有 Firefly 管理的业务 schema 分别执行，不能只过滤 GitHub 表名：

```sql
SELECT c.TABLE_NAME, c.COLUMN_NAME, c.COLUMN_TYPE
FROM information_schema.COLUMNS c
WHERE c.TABLE_SCHEMA = DATABASE()
  AND c.IS_NULLABLE = 'YES'
  AND c.TABLE_NAME IN (
      SELECT t.TABLE_NAME
      FROM information_schema.TABLES t
      WHERE t.TABLE_SCHEMA = DATABASE()
        AND t.TABLE_TYPE = 'BASE TABLE'
  )
ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION;
```

该查询是元数据审计，不是业务跨表读取。工具启动时还必须确认 `DATABASE()` 是批准的目标库，不能因未选择数据库而把“零行”误判为通过。

数据审计程序先读取全部业务表/列元数据，再按已验证并正确引用的标识符生成每列 `COUNT(*) ... WHERE column IS NULL`。不要把用户输入直接拼成表名；批量审计大表时评估执行窗口，并将同表检查合并或按主键分批。新结构检查为零的同时仍输出迁移前后数据核对报告。

额外检查：

- 新绑定表内所有 ID 为正数、没有重复真实 ID；迁移前后的已知关联集合一致。
- SUCCESS 的 `github_delivery_pipeline.pipeline_build_id > 0`。
- RETRYABLE 的 `next_retry_at > U`；ACTIVE Subscription 有真实绑定和有效 Connection。
- 除明确的历史不完整记录外，新 Trigger 的必填事实、Build 绑定和时间完整。
- 新安装和升级安装的列类型、默认值、主键、唯一键、CHECK、索引集合语义一致；不比较 AUTO_INCREMENT 当前值等运行时差异。

### 9.2 必需的自动化测试

| 测试层 | 必须覆盖的情况 |
| --- | --- |
| 全新 Schema | 修改后的 v1 初始化；全业务库可空列为 0；所有最终持久化列显式 NOT NULL |
| 基线升级 | 用本方案基线提交的原 v1 创建旧结构，灌入上述 17 类 NULL、合法非空、未绑定多行和冲突数据，再执行迁移 |
| 更早升级 | 从真实历史 Git 版本/部署 DDL 固定 pre-v2 fixture 与校验和，执行原 v2，再迁移；覆盖额外的 `github_trigger.created_at` NULL；禁止对当前完整 v1 再跑 v2 |
| 迁移恢复 | 批次中断重跑、DDL 部分成功、版本/校验和不匹配、错误映射、非历史缺必填事实时必须停止 |
| 唯一关联 | 多条未绑定记录可共存；重复真实 Hook/Build 被拒绝；相同绑定幂等；不同绑定冲突 |
| Webhook 并发 | AUTO 返回与 ping 竞态、两个 ping、删除与绑定竞态、重复 Delivery、错误签名/Repository/Hook 不得绑定 |
| OAuth | 两个并发 take 只有一个成功；已消费/过期/错误 session 不能通过；条件删除失败不返回 State |
| Delivery | 新入库、入口拒绝、ping、claim、finish、requestRetry、recoverExpired、expireDead 均无 SQL NULL；U 不被当作到期重试 |
| Pipeline | 创建前 0、成功后真实 ID、重复投递幂等；绑定失败时 Build/Trigger/Outbox 事务回滚 |
| API | 不泄露 0/U 作为真实 ID/时间；保留可选响应语义；原始 payload 不被改写 |
| ORM 与写入 | 全实体生成结构独立建库核验；包括继承映射、未显式 @Column 字段、JPQL/native 更新；显式 null 插入/更新最终非空列应失败 |
| 回归 | 原消息 Inbox/Outbox、Stage/Job/Pipeline 状态流转与 GitHub 删除/断开连接测试继续通过 |

优先扩展现有 `GitHubSchemaIntegrationTests`、`GitHubOAuthStateWriterTests`、`GitHubWebhookDeliveryWriterTests`、`GitHubWebhookIngressServiceTests`、`GitHubDeliveryStateServiceTests`、`GitHubSubscriptionDeletionTests` 和 `TriggerTransactionIntegrationTests`，并新增全库 Schema、历史迁移、绑定并发集成测试。不能只用 Mockito 证明数据库唯一性和事务正确。

在 Docker 可用的隔离测试环境，使用仓库当前 Java 25 / Maven 工具链执行：

```sh
mvn clean verify
```

本次只有文档，没有新增上述测试或运行生产迁移；该命令是后续实现的验收要求，不是本次已执行的结果。

### 9.3 CI 规则

1. 解析维护中的 DDL 与文档目标 DDL，不依赖简单搜索 `NULL`；每列要求显式 `NOT NULL`，区分注释、SQL NULL 检查和 JSON 示例。
2. 冻结历史迁移仅按明确文件路径、版本和校验和登记，不接受宽泛的 `v*.sql` 豁免；修改历史文件或新增可空定义直接失败。
3. 全量建库与每条受支持的历史升级链执行完成后运行实际元数据门禁，均要求所有业务表可空列为 0。
4. 在另一个临时库检验完整 ORM 生成结果；不得用 Hibernate 自动建库替代生产显式迁移。
5. 唯一性、状态机、缺省值和显式 null 写入的负向测试必须通过；数据库元数据通过不等于业务正确。
6. 新版本启动只做只读 Schema 兼容性校验，缺最终迁移版本或仍有可空业务列时拒绝进入可写就绪状态；不自动迁移生产。

## 10. 实施拆分与完成标准

建议后续实现按以下顺序组织可审核提交，并作为一次协调发布交付：

1. 固定旧 Schema fixture、18 列清单和全库检查；准备失败用例及回填审计格式。
2. 引入两个全非空绑定模型和统一缺省值，改造所有相关实体、服务、DAO、DTO 与并发测试。
3. 更新维护中的全量建库基线，实现 expand / 状态感知回填 / contract 及恢复测试，保留冻结历史迁移。
4. 同步旧 GitHub/Volcano 文档，接入全库 Schema/ORM/升级门禁和只读启动检查。
5. 隔离库演练通过后，另行批准实际环境维护窗口，执行并产出数据核对与恢复验证报告。

最终验收必须同时满足：

- 所有受管理业务表不存在可空列；在线业务库及长期保留的业务影子表中 SQL NULL 数据为 0。
- 18 个基线问题全部关闭：16 个保留列已收紧，2 个可选唯一关联已迁入非空绑定表且旧列已删除。
- 真实 Hook/Build 唯一性、OAuth 单次消费、Webhook 绑定权限、Delivery owner CAS 与 Pipeline/Outbox 原子性不退化。
- 无新程序向非空列写 SQL NULL；所有缺省值都有状态语义，未知事实不会被当作成功、有效身份或公开仓库。
- 新安装与全部受支持升级链的最终约束一致，历史数据已校验，完整回归与迁移恢复测试通过。
- 文档、实体映射、DDL、迁移和 CI 的规则一致；不得只合并本方案文档就宣布全仓库改造完成。
