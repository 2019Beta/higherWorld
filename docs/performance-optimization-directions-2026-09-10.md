# 进程采样后的优化方向（2026-09-10）

## 本次结论与范围

已按授权附加正在运行的游戏 PID **27100**，完成 60 秒 JFR profile 录制和线程栈采集。优先推进：**原版 NoiseConfig 生命周期复用、主线程特征批次细分、优先级增量发布**。客户端 mesh 和可见性准备仍需跟踪，但本次不支持继续把旧版逐方块全局缓存查询列为第一热点。

源码基线为 `e77d921`，工作区已有 `Higherworld.java`、`CubeTaskScheduler.java` 未提交修改；本次保留原样，仅新增调查文件。现场为 JDK 25.0.1 开发启动进程，未重建或替换游戏代码，也未验证 JVM 加载字节码与当前工作区完全一致。

录制时间：北京时间 **09:36:15–09:37:15**。采样包含进入世界后的早期负载，未固定玩家路线、移动速度及冷热缓存，不能与 9 月 8 日数据直接计算优化收益。未操控玩家或修改世界；JFR 和线程转储本身有观测开销。

## 现场证据

共 5,558 个 Java 执行样本，其中服务器 1,531、渲染线程 712、Worker-Main 合计 2,200、模组生成线程合计 964。1,130 个栈被截断。下表按栈内包含方法统计，每样本对同一方法只计一次；方法可嵌套，占比不可相加，也不是墙钟耗时占比。

| 线程组 | 方法 | 样本 | 组内占比 |
| --- | --- | ---: | ---: |
| Server | CubeTaskScheduler.publishPriorities | 224 | 14.63% |
| Server | CubeWatchManager.rebuildQueue | 231 | 15.09% |
| Server | CubicWorldState.commitFeaturesBody | 214 | 13.98% |
| 生成线程 | VanillaCubeTerrainGenerator.sampleDensityGridMeasured | 250 | 25.93% |
| 生成线程 | VanillaCubeTerrainGenerator.createNoiseConfig | 51 | 5.29% |
| Worker-Main | SectionBuilder.build | 442 | 20.09% |
| Worker-Main | ChunkRenderingDataPreparer.update | 327 | 14.86% |
| Worker-Main | ClientCubeCache.RenderAccess.entry | 45 | 2.05% |
| Render | WorldRenderer.renderBlockLayers | 170 | 23.88% |
| Render | SparseCubeLightEngine.propagate | 18 | 2.53% |

`higherworld.CubeWork` 使用源码默认 1 ms 阈值。以下为**已记录事件**分布，缺少低于阈值的调用，不能代表全部调用均值；累计时长可能涉及线程并行，不能解释为整段加载时间或独占 CPU 时间。

| 阶段 | 事件数 | 累计 ms | 均值 ms | P95 ms | 最大 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| vanilla.noise.config | 1,548 | 6,160.610 | 3.980 | 8.148 | 29.574 |
| vanilla.density.sample | 1,548 | 8,360.645 | 5.401 | 8.679 | 18.451 |
| vanilla.features.batch | 759 | 2,828.844 | 3.727 | 9.282 | 59.708 |
| terrain.commit | 8 | 36.979 | 4.622 | 14.079 | 14.079 |
| client.decode_publish | 6 | 11.666 | 1.944 | 2.426 | 2.426 |

另外记录 100 次 GCPhasePause，累计 618.332 ms、P95 13.881 ms、最大 19.062 ms。4 次 JavaMonitorEnter 均为 `java.lang.Object`，最大约 10.241 ms；这不足以断言队列锁是主要瓶颈，也不能排除阈值以下竞争。线程转储中的服务器线程处于 tick 末尾等待，单点等待不代表整段空闲。

## 1. 复用原版噪声配置，减少重复构造

入口：`VanillaCubeTerrainGenerator.java:338` 的 `createNoiseConfig` 每次调用 `NoiseConfig.create(settings, noiseParameters, seed)`。CPU 深层批次路径会调用它；GPU 多批路径已在一次调用内复用一个配置，不能重复宣称尚未做批内复用。

新事件显示配置构造累计 6.16 秒，与密度采样累计 8.36 秒处于同一量级，值得独立优化。执行样本因深层密度函数栈截断而可能漏掉入口，不能仅以入口 51 个样本否定事件证据。

建议先做每生成 worker 的有界配置缓存，缓存键包含 seed、生成设置及噪声注册表身份/生命周期；世界卸载或资源重载时失效。审计 NoiseConfig 内部惰性状态及 sampler 可变性后再决定共享范围，不直接把一个实例交给全部线程。GPU batcher 可单独持有其线程内配置。

验收：相同种子、坐标和设置逐块比较输出；覆盖换世界、资源重载、取消和 CPU 回退；记录配置创建次数、命中率、 retained heap、生成批次 P95 和请求到 PAYLOAD 延迟。事件累计时长不能当成预计节省时长。

## 2. 在特征批次内部切片，降低主线程长尾

入口：`VanillaPlacedFeatureGenerator.java:487` 的 `generateBatched`，以及 `:535` 的 `generateBatch`。当前在 batch key 之间检查 deadline；单个 batch 内仍完整执行。已有跨 key 游标并未消除 batch 内超预算。

此次批次最大 59.708 ms、已记录事件 P95 9.282 ms，服务器栈也出现特征提交。它比简单增加生成线程更直接影响主线程响应。墙钟事件可能包含 GC 或调度暂停，不能将全部时间归因于特征算法。

建议先按 generation step、placed feature 或批次内部稳定单元细分事件，找出长尾配置，再引入可恢复游标。保持随机序列、特征执行顺序、跨 cube 写入和缓存发布的一致性；结果完整前不得发布为已完成快照。依赖实时世界状态的写入不能直接整体移到 worker。

验收：逐 tick 耗时与超预算次数、最大单片时间、FEATURES 最老等待年龄、取消后的资源释放；同种子跨区块边界及结构输出对比。现有 59.7 ms 最大值是定位依据，不是稳定基准。

## 3. 优先级由全量发布转向变化集合

入口：`CubeTaskScheduler.java:205` 的 `publishPriorities` 和 `CubePriorityIndex.java:42` 的 `snapshot`。当前每次发布复制 effective 全图，传播宽特征依赖，创建新的 ConcurrentHashMap，再构造新旧键集合比较。`CubeWatchManager` 的 ticket 替换和预取刷新会经过这些路径。

`publishPriorities` 出现在 224/1,531 个服务器样本中，是本轮有直接现场证据的调度候选。当前 owner 优先级已经可替换，不应继续把旧版“历史最小 rank 永不回升”作为当前主要缺陷。

建议让 owner 更新返回 effective rank 真正变化的位置，合并同 tick 多次变更后发布一次；仅对受影响 holder、IO 和生成任务重新排序。宽依赖传播必须同时支持提权和降权，共享依赖需要保存其他 owner 的最小贡献，不能仅传播更小值。

验收：发布次数、扫描位置数、分配量与发布 P95；多玩家共享依赖、折返、取消/重入时的真实出队顺序。优先级正确性应与速度一起验收。

## 4. 后续候选及不宜先做的调整

- **精准唤醒等待依赖。** `recheckWaitingHolders` 仍使用全局 dependencyEpoch，任意 holder 变化都可能使无关等待者重新检查。可记录扫描/实际解阻比例，再决定缓存首个阻塞依赖或维护有界反向依赖。当前已有每轮 512 和时间预算，不应按旧版无界轮询描述。
- **GPU 批次的当前优先级。** `VanillaTerrainBatcher` 使用 LinkedBlockingQueue FIFO 收集，现有 `reprioritizeGenerationTasks` 只调整 CPU executor 队列。若 GPU 路径实际形成积压，可在有界批次窗口按当前优先级选任务；保留兼容分组和饥饿保障。本次没有足够 GPU batcher 样本，属于源码候选，不是已确认现场瓶颈。
- **客户端建模与可见性。** 同时跟踪 mesh 排队、构建、上传和可见性准备。当前 RenderAccess 已有局部缓存，不再按旧版未缓存路径规划。添加每 cube/revision 的首次可见追踪，再评估重复重建合并与近处优先。
- **避免盲调预算。** 此次没有队列/反馈探针，不能判断发送背压占比；少量 IO 执行样本也不能测量磁盘等待。增加窗口、线程或 GPU 并发之前先补充队列年龄、等待时间与废弃任务比例。

当前 `CubeReadyQueue` 已是 indexed heap，不能把旧文档中的物理堆惰性条目膨胀当成当前事实。工作区 `enqueueReady` 的同步修改和旧注释也存在不一致；本次没有验证并发正确性，不能仅靠性能样本认定应恢复或移除锁。

## 复现与下一轮验收

本地证据位于 `research/`（被 Git 忽略，分享时需单独附带）：

- `optimization-20260910.jfr`：原始 60 秒录制。
- `optimization-20260910-threads.txt`：完整线程转储。
- `optimization-20260910-summary.txt`：热点与事件统计。
- `SummarizeOptimization.java`：使用 JDK RecordingFile 流式读取，避免依赖巨大的 JSON 导出。

在项目根目录复算：

```powershell
& C:/jdk-25.0.1/bin/java.exe research/SummarizeOptimization.java research/optimization-20260910.jfr
```

下一轮分开记录进入世界、静止加载和固定路线持续移动；相同存档副本、种子、视距、模拟距离下对照。优先验证上述前三项，再按新数据调整排序。核心指标为请求到 PAYLOAD/首次可见 P50/P95/P99、tick/帧时间、队列最老年龄及内存。此次完成现场采样和源码调查，未实施优化、未运行构建或回归测试，未测得修复后收益。

## 后续实施记录（同日）

用户随后授权根据调查优化，并要求完成后仅进行静态语法检查，不编译验证。以下为该后续修改，前面的现场数字仍属于修改前录制。

### 已实现

- **按世界上下文、工作线程复用 NoiseConfig。** 配置保存在该上下文的 AsyncBatchCache，CPU worker 与 GPU batcher 各用自己的实例；构造在缓存锁外完成，缓存命中不再记录构造事件。线程键采用弱引用，最多保留 16 个条目，超限清空仅影响复用；不使用长寿命线程的 ThreadLocal 持有世界配置。世界 Context 释放后，缓存随剩余在途请求释放。缓存生命周期沿用已有 Context，未新增独立资源重载机制。
- **优先级发布改用有效 rank 差量。** CubePriorityIndex 记录变更前有效值并在发布时与最终值比较，抵消无实际优先级变化的共享 owner 更新。调度器不再复制完整基础 rank 图、重建整个 ConcurrentHashMap 或合并所有旧/新键。宽特征依赖仍从基础值重新传播，支持降权及 owner 移除；只重新排序实际变化的位置，无变化时跳过 CPU 队列扫描。宽依赖传播本身尚未改为完整的反向依赖增量算法。
- **已缓存特征批次也遵守 key 间预算。** 原逻辑仅在未缓存 key 前检查 deadline，连续应用缓存中的方块及方块实体可能越过时间片。现在每处理完一个 key 都可返回原有续跑游标，同时保留首个 key 的前进保障。
- **单特征定位事件。** 新增 `vanilla.features.call`，使用 CubeWork 的 `detail` 字段记录特征注册名；沿用 1 ms 阈值，仅事件启用时解析名称。它嵌套于 batch 事件，分析时不能把二者累计相加。

### 尚未实施及原因

没有把单个特征 batch 拆到多个 tick：FeatureBatchWriter 惰性读取并缓存世界 section，普通高度仍读实时世界，跨 tick 暂停会引入读取一致性问题。因此本轮保留 batch 内调用顺序、随机数和完整快照发布方式。单个重特征/batch 仍可能超预算；需要先设计固定读取视图或证明读取生命周期，再继续细分。

没有改变 GPU 队列顺序、发送窗口、线程数或光照预算。原有断线回调和 readyQueue 同步修改保留。

### 静态检查

使用 `tools/check-java-syntax.jsh` 调用 `JavacTask.parse()`，按 Java 21 语法解析 `src` 下 **158 个 Java 文件，0 个语法错误**。仅构建语法树，没有执行符号/类型归属分析、注解处理或 class 生成；这不等同于编译通过。JShell 在沙箱内读取偏好注册表时有权限警告，不影响最终语法解析结果。

`git diff --check` 通过。遵照用户要求，没有运行 Gradle、编译、单元测试、基准或游戏复测，没有生成新 JAR，也没有宣称实测提速。
