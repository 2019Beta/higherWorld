# 玩家移动时区块追不上：调查记录（2026-09-08）

## 结论与适用范围

**补充验证后的结论：已复现生成优先级错位，并在实际移动中观测到请求集中堵在 TERRAIN→FEATURES 的依赖/提交路径。** 第二轮 30 个现场采样点中，活动请求平均 511.47 个，其中平均 507.23 个停在 TERRAIN；28 个采样点后台生成线程活跃数为 0，客户端反馈背压为 false。优先排查该阶段的依赖协调、旧优先级和主线程提交；方向预取及背压解耦仍是后续设计项，但本次现场没有证据把背压列为主要触发原因。验证细节见文末。

当前最应优先处理的是**移动后的生成优先级更新、重复依赖图请求，以及前方区块的提前准备**。源码存在“发送队列按新位置排序，但生成调度保留历史最小优先级”的不一致；同时，预取和发送共用客户端背压开关，积压时无法继续为前方准备新请求。这些机制能解释为什么停下来后加载会逐渐追上，但尚未取得逐区块到达时间，不能把每次等待都归因于其中某一项。

本次附加正在运行的游戏，取得 60 秒 JFR。新采样中 `CubeTaskScheduler.requestGraph` 是明显的服务端热点，原版噪声采样和原版特征生成也实际发生。因此，不宜直接沿用旧报告“主要优化自定义生成器/光照”的优先顺序，更不宜先无条件增加发送量。

本报告主要针对原版高度范围以外的 HigherWorld cube 流式路径，每个 cube 为 16×16×16。原版高度内的区块还有原版加载器参与，不能将这里的结论直接推广到所有地表加载问题。

本轮仅调查和验证，未修改游戏源码、未替换运行中的游戏构建或重启游戏。后续在独立目录编译了全部主端/客户端源码并运行测试。工作区已有未提交优化，按当前源码检查，未覆盖原有修改。

## 调查基线与新运行证据

- Git HEAD：`4944510`，另有当前工作区修改，不能把运行结果标作纯 HEAD 基准。
- 游戏 PID：35528；Java 25.0.1；Fabric 开发启动，启动类路径包含本项目 `build/classes/java/main` 和 `client`。
- 本地 `CubeWorkEvent.class`、`CubeTaskScheduler.class` 编译时间为当天 12:50:16。采样栈含 `ClientCubeCache$RenderAccess.blockState`，说明运行程序包含局部渲染访问器；未逐类验证全部源码与内存字节码一致。
- 日志及 `run/options.txt`：视距 16、模拟距离 12；最近登录位置 Y≈−436，13:00:50 切换旁观模式。未接管玩家输入，也未记录每 tick 的坐标、速度、视线与画面，因此这是一段现场活动采样，**不是固定路线的移动对照实验**。
- JFR 时间：2026-09-08 13:00:50–13:01:50（北京时间），配置 `profile`，自动结束，无需停止游戏。

原始数据与复算入口：

- `research/movement-20260908/movement.jfr`：原始记录。
- `research/movement-20260908/events.json`：筛选后的执行样本、CubeWork、GC 暂停和周期 tick 事件。
- `research/movement-20260908/analyze.ps1`、`summary.txt`：复算脚本与输出。

在项目根目录复算：

```powershell
& 'C:/jdk-25.0.1/bin/jfr.exe' print --json --events 'jdk.ExecutionSample,higherworld.CubeWork,jdk.GCPhasePause,minecraft.ServerTickTime' research/movement-20260908/movement.jfr | Set-Content -Encoding utf8 research/movement-20260908/events.json
& ./research/movement-20260908/analyze.ps1
```

执行样本共 2,677 个。以下为“一个样本的栈中包含该方法”的计数，每个样本对同一方法只计一次；方法之间可重叠，比例不能相加，也不是精确 CPU 时间。662 个样本栈被截断，较深调用可能漏计。

| 线程组 | 栈内方法 | 样本 / 线程组总样本 | 比例 |
| --- | --- | ---: | ---: |
| Server thread | CubeTaskScheduler.requestGraph | 188 / 1,129 | 16.65% |
| Server thread | CubeTaskScheduler.readyForCommit | 84 / 1,129 | 7.44% |
| Server thread | ServerWorld.tickChunk | 72 / 1,129 | 6.38% |
| Server thread | VanillaPlacedFeatureGenerator.invoke | 61 / 1,129 | 5.40% |
| higherworld-cube-generation（同名线程合计） | DensityFunction$Noise.sample | 343 / 624 | 54.97% |
| Render thread | WorldRenderer.renderBlockLayers | 124 / 505 | 24.55% |
| Render thread | SparseCubeLightEngine.propagate | 16 / 505 | 3.17% |
| Worker-Main-* | ChunkRenderingDataPreparer.update | 143 / 388 | 36.86% |
| Worker-Main-* | SectionBuilder.build | 41 / 388 | 10.57% |

17 次 `jdk.GCPhasePause`：平均 9.05 ms，最大 15.63 ms。58 个 `minecraft.ServerTickTime` 周期平均值：均值 9.80 ms、P95 21.77 ms、最大 30.77 ms。后者不是逐 tick 分布，不能据此声称每个 tick 都低于 50 ms；本记录也不支持把等待主要归因于长 GC 暂停。

`higherworld.CubeWork` 仅有 2 条 `terrain.commit`，分别约 2.09、12.60 ms。事件有 1 ms 阈值，覆盖的入口也有限；不能用这两条推断只生成了两个 cube，或认定未记录的 FEATURES/客户端解码没有成本。当前原版特征调用没有对应的细分 CubeWork 事件，这是观测缺口。

## 1. 移动后的优先级没有贯穿生成链路（源码确认）

位置：`CubeWatchManager.java:468,611,772`；`CubeTaskScheduler.java:131,174,238,742`，均在 `src/main/java/org/devt/higherworld/world/` 下。

玩家跨 section 后，`updateWindowIncrementally` 保留重叠请求，调用 `rebuildQueues` 更新 watcher 的 rank。距离公式为：

```text
rank = max(abs(dx), abs(dz))² × 5 + abs(dy)²
```

但生成调度器在 `requestGraph` 中执行 `requestPriorities.merge(pos, priority, Math::min)`，位置离玩家更远时不会增加旧 rank。最终调度优先级仍取 ticket priority 与该历史值的最小值。清理发生在移除/取消或需求变成 EMPTY 等路径，持续被引用的位置会保留历史值。

静态例子：某 cube 原来就在玩家中心，历史 rank=0；玩家沿 X 移动一个 cube 后，其当前距离 rank 应为 5，但调度器仍可能取 0。与此同时，新进入前方边缘的请求以较大 rank 加入。watcher 的重新排序并没有同步改写已在生成中的请求优先级。

**影响判断：**存在旧任务抢占前方紧急任务的可能，特别是在持续移动、老任务尚未完成时。尚未测得这种排序造成的具体等待时间，也不能称为必然永久饥饿。

建议按当前有效请求所有者维护优先级，并在移动/撤销时重新求最小值，传播到 ready 队列及尚未开始的生成/IO 任务。只改 `Math::min` 为覆盖会破坏多玩家和共享依赖优先级，不能这样直接修。

## 2. 预取缺少移动预测，且与发送背压绑定（源码确认）

位置：`CubeWatchManager.java:266–315,576,611`；`CubeStreamBudget.java:31`；`CubeStreamFeedbackPayload.java:40`。

`readAhead` 是当前视距内的提前请求，不是移动方向的视距外预测。视图在 cube 中心坐标或视距变化时更新；没有使用速度、方向、预计到达时间。前方刚进入窗口的位置和同距离侧后方位置采用相同距离等级。

读请求入口和发送入口都要求 `stream.canSend(0, tick)`。字节窗口耗尽、反馈过期或客户端任一积压指标触发后，两者均暂停：

| 限制 | 当前值 |
| --- | ---: |
| 未确认字节窗口 | 4 MiB |
| 客户端待处理字节背压 | ≥2 MiB |
| 待更新消息数 | ≥512 |
| 最老消息年龄 | ≥250 ms |
| 待提交渲染失效数 | ≥768 |
| 待光照 cube 数 | ≥512 |
| 反馈失效 | 超过 40 个服务端 tick |
| 每玩家 activeUnsent | 最多 512，包含 ready-to-send |

于是客户端正在消化旧数据时，新位置的预取也可能断供。已经启动的工作仍会继续，并非整个生成器立即停机；但持续背压会降低下一批的准备重叠程度。

建议分开“发送字节信用”和“服务端有界准备预算”，为即将到达的前方走廊保留少量准备容量。预测距离可由速度 × 实测 P95 交付时间决定并设上下限；同时保留脚下/近处最高优先级，限制转向后的废弃工作和内存。不要直接移除背压或扩大整个立方体视距。

## 3. 移动的新增需求量大，重复图请求成为实测热点

位置：`CubeWatchManager.java:423,468,576`；`CubeTicket.java:46`；`CubeTaskScheduler.java:131,153`；`CubeTicketManager.java:49`。

完全位于深层时，视距 16、垂直半径 4 的观察窗口最多包含 `33×33×9 = 9,801` 个 cube 位置。沿 X/Z 单轴跨一个 cube 边界，新增 `33×9 = 297` 个；沿 Y 跨一个边界，新增 `33×33 = 1,089` 个。这些是窗口位置数，不代表等量的新磁盘读取、非空包或全新地形生成，已有缓存和共享依赖会减少实际工作。

模拟距离 12 对应 `25×25×9 = 5,625` 个 FULL 需求位置，还需外扩 FEATURES/TERRAIN 依赖。watcher 视窗已按差集更新，不能再把它描述为每次移动重建所有视图；但 simulation ticket 的 `replaceTicket` 仍计算整块闭包，并遍历旧/新 ticket 覆盖位置更新优先级。

`requestGraph` 每次根请求新建 visited 集合；递归遇到重复节点时，**先**合并 priority、添加 dirty target、查 holder、request、refreshPriority、startIo，**后**检查 visited。IO future 有复用，不能说每次调用都真的重新读盘，但共享依赖仍有重复协调开销。

本次 `requestGraph` 占服务器执行样本约 16.65%，比仅依据旧记录把它列为次要候选更值得优先调查。建议统计每 tick 请求数、唯一 `(pos,stage)` 数及依赖命中数，批量合并根请求，避免已满足状态的重复展开；必须保留目标升级、优先级降低、失败重试与取消后重建语义。

对于新增需求速度，可用 `297×水平速度/16` 或 `1089×垂直速度/16` 估算单轴位置需求率（速度单位：方块/秒）。例如假设垂直速度为 16 方块/秒，就会新增约 1,089 个视图位置/秒。若持续交付低于需求增长，队列会老化；停下来后新增需求消失，已有积压才能追上。这个模型解释现象，但这里没有测量玩家实际速度或有效交付率。

## 4. 当前运行的原版生成路径仍有 CPU 和主线程工作

位置：`VanillaCubeTerrainGenerator.java:123,133,209,334`；`VanillaPlacedFeatureGenerator.java:487,520,535`；`CubicWorldState.java:931,1131`。

新样本显示后台线程执行原版 DensityFunction/Perlin 噪声，服务器线程执行原版矿石和 placed feature。即使启用了 GPU，原版任意 NoiseRouter 的密度采样仍在 CPU 上；GPU raster 路径主要承接插值与实体判定，并不把所有噪声和特征搬到 GPU。

原版特征已支持按 batch key 续跑，但 deadline 在 key 之间检查，单个 `generateBatch` 仍可能超过时间片。当前自定义生成器按配置项拆分的优化不等于原版特征路径已获得同样粒度的拆分。提交预算正常最多 6 ms/tick，欠账期间请求 0.5 ms/tick；首项前进保障使其不是硬时间上限。

建议为原版 batch、NoiseConfig 构造、密度采样和 ready 等待增加分段数据，再决定缓存线程私有 NoiseConfig、复用批次或进一步拆分特征。不能单凭采样占比承诺收益，也不能假设这些涉及状态的对象可安全跨线程共享。

## 5. 已收到方块数据不等于已经看见区块

位置：`src/client/java/org/devt/higherworld/client/ClientCubeCache.java:323,353,510,529`；服务端 `CubeStreamQueue.java:6`、`CubicWorldState.java:950,1188`。

客户端更新采用 FIFO，每 tick 最多 512 条且预算 4 ms；光照另有 1 ms；每 tick 最多向渲染器提交 192 个失效位置。本 cube 与六邻居会一同失效，集合只能去除尚未提交的重复项，后续新光照仍可能再次要求重建。FIFO 消息和失效集合没有按照新的行进方向重新排序。

`pendingRenders` 统计的是模组未提交的失效集合，不包括原版内部 mesh 构建、可见性更新、上传的全部积压。此次 Worker 栈中可见性准备 `ChunkRenderingDataPreparer.update` 明显，说明后续调查不能只盯住 SectionBuilder 或网络字节数。

PAYLOAD 位于 LIGHT 之前；光照全局收敛限制 FULL，不直接要求首包等到 FULL。但 LIGHT/FULL 和首次交付共享主线程时间，所以仍可形成间接竞争。

建议补齐 DATA 应用→首次 mesh 构建→上传→可见的时间线，再判断是否优先调度近处渲染、合并重复重建。对消息重排须保证同 cube 的 DATA/LIGHT/UNLOAD、revision 与世界切换顺序，不能直接改为不受约束的优先队列。

## 建议实施顺序与验收

1. **先修优先级传播并减少重复 DAG 协调。** 覆盖移动后旧 rank 增大、多玩家共享依赖、取消/重入及紧急近处请求；本次证据最直接。
2. **加入有界方向预取，拆开发送信用与准备容量。** 用实测到达时间控制前方准备距离，保留近处和转向保障，观察废弃生成比例。
3. **针对现场原版路径测量并优化 batch/噪声。** 增加原版细分事件；自定义和原版世界分别验证，不能混用收益数据。
4. **补齐客户端可见性和 mesh 队列，按结果调整消费/渲染预算。** 不以“已发送”或“已应用”替代“首次可见”。

正式复测保持种子、存档副本、视距、模拟距离一致，分别跑已生成路线和全新路线；覆盖静止、连续水平移动、连续升降、急转弯和折返。记录世界/cube/revision、位置与速度、首次请求、进入各阶段、背压原因及持续时间、客户端应用和首次可见时间；窗口按相同路线对齐。

验收核心是：预计到达前是否已有可见区块、需要等待的次数/总时长、请求到可见 P50/P95/P99，以及持续移动时队列最老年龄是否不断增长。同时约束帧时间、服务端 tick、内存、无效生成比例，并检查区块边界、光照和跨世界数据正确性。性能门槛应由本机同路线基线设定；本次没有对照修复，未测得提速百分比。

## 补充验证结果（同日 13:09–13:13）

### A. 编译与自动化：168 项通过，缺陷实验可重复

使用项目已有的本地依赖和 Fabric Loader JUnit 启动方式：

- 全部 `src/main/java` 源码通过 Java 21 `--release 21` 编译。
- 全部 `src/client/java` 源码通过相同版本独立编译；仅有 deprecated API 提示。
- 165 项现有 `*Test.java` 测试 + 3 项本次诊断测试，共 **168 项成功，0 失败、0 跳过**，39 个测试容器全部成功。
- 原有损坏存储/非法数据测试会在日志打印异常；它们最终测试结果通过，不能仅凭异常文本认定测试失败。
- Gradle 离线测试受沙箱回环连接限制阻断，沙箱外重试未获批准。这是独立编译/Fabric JUnit 验证，**不是 Gradle build、Mixin 注解处理、remapJar 或新 JAR 启动验收**。

新增 `research/movement-20260908/MovementDiagnosisTest.java` 为问题特征验证：断言当前错误行为确实存在，测试通过不表示问题已修复，也不应把这些断言原样作为未来修复后的正确性标准。

| 实验 | 执行结果 |
| --- | --- |
| watcher 与生成队列比较 | 玩家中心移动到 X=8 后，旧 cube X=0 的当前 rank=320，前方 cube X=9 的 rank=5；watcher 先选前方，但真实 `readyForCommit(1)` 先选旧 cube，其历史 priority 仍为 0。确认差异落在生成队列，不只是公式推测。 |
| simulation ticket 替换 | 移动 ticket 后，独立 ticket 查询中旧位置优先级已增大，调度器最终仍取历史 0。确认 ticket 重排不能消除该问题。 |
| 背压与恢复 | render pressure=768 时 `canSend(0)` 返回 false，恢复反馈后立即为 true；当前 `CubeFeedbackCadence` 会立即报告压力转换，**不能继续归因于必须等 5 tick 才恢复**。 |

复现命令（项目根目录）：

```powershell
& ./research/movement-20260908/run-validation.ps1
& ./research/movement-20260908/compile-client.ps1
```

输出：`research/movement-20260908/validation.log`、`client-compile.log`；独立 class/dependency 目录为 `build/movement-validation`。客户端首次尝试使用参数文件内的 wildcard classpath 未解析出依赖，改为显式列出本地 JAR 后编译成功；不涉及游戏源码修复。

### B. 真实进程：位置变化与队列停滞同时被记录

再次附加 PID 35528，使用 `MovementProbe.java` 和 `MovementStageProbe.java` 只读探针。未转换/替换任何游戏类，未移动玩家、修改世界或预算。探针将读取任务提交到服务器 executor，每约 1 秒采样一次，后台线程写文件；分别取得 60 和 30 行后自动退出。反射只读取当前状态。

| 指标 | 13:11:01–13:12:00（60 点） | 13:12:22–13:12:52（30 点） |
| --- | ---: | ---: |
| 采样坐标折线距离（真实路线长度下界） | 321.58 方块 | 356.30 方块 |
| 位移 >1 方块的相邻采样区间 | 12 / 59 | 12 / 29 |
| activeUnsent 范围 | 511–512 | 503–512 |
| PENDING_START 范围 | 8,381–9,206 | 8,936–9,189 |
| READY_SEND 快照 | 全部为 0 | 全部为 0 |
| 客户端报告背压 | 0 / 60 | 0 / 30 |
| 未确认字节最大值 | 12,485 B | 54,515 B |
| customWorld | false | false |

坐标记录确认这两段包含实际移动，也包含停留。未操控路线，因此不能当作固定速度、固定种子的修复前后比较。`sent` 是当前窗口中的位置集合，移动时会卸载，不能把它的差值直接当吞吐；READY_SEND 为 0 是离散快照，也不代表两次采样之间没有成功发送。

这组数据不支持“窗口满/客户端背压导致这段加载慢”：未确认字节远低于 4 MiB，客户端背压均为 false，活动请求却几乎一直占满 512 个名额。窗口中约九千个未启动位置因此等候前一批完成。

### C. 阶段定位：主要请求停在 TERRAIN，而非后台地形队列排满

第二段额外读取活动根 holder 状态和队列：

- 活动根中 **TERRAIN 状态平均 507.23 个、范围 485–510 个**，相对平均活动根 511.47 个约占 99.2%。已完成 TERRAIN 的根仍需等 FEATURES 依赖或实际提交，不是已取得可发送 payload。
- 后台 `generationExecutor` 活跃线程 28/30 点为 0，范围 0–8；待执行生成任务范围 0–3。不能据此断言整段从不忙，但没有看到持续的后台排队。
- `waitingSinceEpoch` 范围 1,378–3,365，说明存在大量等待依赖的 holder；`readyQueue` 物理条目 8,607–30,876。后者含惰性失效条目，**不是等量唯一可运行 cube**，也不能把该数量直接解释成完成积压。
- 一段坐标基本稳定的停留中，根状态仍多次为 `{IO_READY=2 | TERRAIN=510}`；停下来不会立即消除阶段堵塞。

因此当前证据支持把主要调查位置放在 **TERRAIN→FEATURES 的依赖满足、重复协调与主线程提交**，并检查 FULL 需求竞争。尚未追踪每个阻塞根的依赖边/提交欠账，不能进一步断言全部是 feature 执行慢、全部是缺依赖或全部由优先级错误造成。

同步的 `stages.jfr`（30 秒）有 1,527 个执行样本：服务器 668 个，其中 `requestGraph` 110 个（16.47%）、`readyForCommit` 43 个（6.44%）。与首次 JFR 对重复依赖协调的发现一致。周期平均 tick 的均值 11.52 ms、P95 24.58 ms、最大 27.58 ms；4 次 GC 暂停最大 35.24 ms。这些并非逐 tick/逐帧数据。

### D. 优先级现场指标的解释边界

第一段每点有 479–499 个活动根的历史 request priority 小于当前纯距离 rank；第二段为 498–512 个。它说明调度中普遍存在低于当前几何距离的优先级，**不能把每一个都称为错误**：某位置还可能被更紧急的共享依赖或其他请求者合法提权。优先级缺陷成立的直接证据是 A 中单所有者、无共享干扰、真实调度器出队实验；现场计数只说明需进一步追踪所有者和依赖优先级。

探针本身也有开销：第一段每点平均 1.49 ms、最大 13.54 ms（首次）；第二段平均 1.75 ms、最大 5.84 ms。1 Hz 采样适合判断持续状态，不适合精确微基准或捕获短暂背压，所有现场数据应带此限制解读。

原始补充文件均在 `research/movement-20260908/`：`live-validation.csv`、`live-stages.csv`、`live-summary.txt`、`stages.jfr`、`stages-events.json`、`stages-summary.txt`，以及探针源码和 `analyze-live.ps1`、`analyze-stages.ps1`。`research` 为本地调查目录，当前 Git 状态未将其列作待提交文件；正式共享时需另行包含所需证据。

### 验证完成度

本次完成了源码编译、现有自动化测试、可重复缺陷实验，以及含真实玩家移动的进程队列/阶段观测。调查结论已从静态推测推进到可复现缺陷和现场阶段定位。由于本任务尚未实施修复，也未接入首次 mesh 可见的逐 cube 事件，**未验证修复后的加载提升、画面边界或请求→首次可见分位数**；这些应在修复后按前述验收路线执行。
