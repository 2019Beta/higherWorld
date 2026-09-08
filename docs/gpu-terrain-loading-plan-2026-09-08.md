# GPU 地形生成与区块加载优化方案（2026-09-08）

本轮完成当前源码检查、附加游戏进程采样和方案设计。允许修改引擎的范围已纳入设计；本轮交付方案及诊断材料，没有修改游戏源码、热替换类或重启游戏。

## 结论

**优先优化移动时的 ticket／优先级发布和 ready 队列更新，再处理原版密度准备与 GPU 批处理。** 当前确实启用了 GPU，但现场走的是原版深层地形路径，密度函数仍在 CPU 执行。不能把“GPU 已启用”理解为地形、特征、光照和渲染全由 GPU 完成。

与上午报告相比，当前源码已经修复多项问题：按 owner 维护优先级、生成任务和 IO 重排、依赖图展开缓存、准备与发送背压解耦、waiting 队列限额、预算欠账期间保持前进。应继续降低这些机制的成本，不能再次把它们全部列为未实现。

本次没有固定路线 A/B 对照，没有逐 cube 请求到首次可见的完整追踪，也没有 OpenCL 设备事件计时。因此以下区分实测热点、源码事实与待验证设计，不给出已实现的提速百分比。

## 1. 当前游戏与采样证据

- 源码 HEAD：`ce7d7b6765cbc78ea4cd9a575ab786a4aead94c8`；调查开始时 `git status --short` 为空。
- 游戏 PID：5172，Java 25.0.1，Fabric 开发启动。本次并未逐类核对内存字节码与磁盘源码一致性。
- `run/config/higherworld.properties`：GPU enabled=true、fallback_on_error=true。
- `run/logs/latest.log:151–153`：枚举 NVIDIA 和 Intel 两个设备，最终初始化的是 **NVIDIA GeForce RTX 5070 Laptop GPU**。
- 最近进入世界的位置约 Y=-673；视距 16、模拟距离 12。日志曾出现暂停，之后切换旁观模式；本次采样有大量生成事件，不能归类为全程暂停，但未记录精确移动轨迹。
- JFR：北京时间 **17:58:10–17:59:10**，60 秒、profile 配置；另保存一次线程快照。
- 线程快照瞬间，vanilla batcher 与 IO 线程在等任务，服务端在等下一 tick，渲染线程在绘制区块。这只代表一个时刻，不能否定记录期间存在突发积压。

原始数据在 `research/gpu-loading-20260908/`：`live.jfr`、`threads.txt`、`AnalyzeRecording.java`、`summary-java.txt`。research 目录受项目忽略规则管理，保存在本地，不因文档变更自动进入版本控制。

复算命令（项目根目录，使用 Java 25）：

```powershell
& 'C:/jdk-25.0.1/bin/java.exe' research/gpu-loading-20260908/AnalyzeRecording.java research/gpu-loading-20260908/live.jfr
```

分析程序直接流式读取 JFR，避免展开类加载器元数据生成巨大的 JSON。执行成功；本次未构建或测试游戏代码。

### 执行样本

共 6,859 个 Java 执行样本，792 个栈截断。表中计数是“栈中包含该方法”的样本数，每个样本对同一方法只计一次；**包含嵌套调用，不能相加，也不等于准确 CPU 时间或整个记录的墙钟占比**。

| 线程／分母 | 栈内方法 | 样本数 | 比例 |
| --- | --- | ---: | ---: |
| Server thread / 3,618 | CubeTaskScheduler.replaceTicket | 1,901 | 52.54% |
| 同上 | CubeTaskScheduler.publishPriorities | 1,578 | 43.62% |
| 同上 | CubeTaskScheduler.enqueueReady | 1,253 | 34.63% |
| 同上 | CubeTaskScheduler.refreshTargets | 764 | 21.12% |
| 同上 | CubeTaskScheduler.requestGraph | 441 | 12.19% |
| vanilla batcher / 659 | DensityFunction$Noise.sample | 447 | 67.83% |
| cube-io / 119 | PriorityBlockingQueue.indexOf | 101 | 84.87% |
| Worker-Main 合计 / 1,565 | ChunkRenderingDataPreparer.update | 576 | 36.81% |
| 同上 | SectionBuilder.build | 495 | 31.63% |
| Render thread / 831 | WorldRenderer.renderBlockLayers | 231 | 27.80% |

IO 线程的样本主要落在 future 完成回调→holderChanged→enqueueReady→队列删除，而不是文件读取。这提示“IO 线程忙”不能直接解释为磁盘慢；该样本量较小，仍需队列与 IO 延迟指标验证。

### 阶段时长

`higherworld.CubeWork` 阈值为 1 ms，以下是被记录的慢调用，不是所有调用；累计时长不是阶段总耗时，事件条数也不是生成 cube 数。

| 阶段 | 记录数 | 累计 ms | P95 ms | 最大 ms |
| --- | ---: | ---: | ---: | ---: |
| vanilla.density.sample | 1,143 | 5,731.570 | 6.174 | 28.742 |
| vanilla.noise.config | 356 | 1,180.723 | 5.221 | 22.347 |
| vanilla.features.batch | 825 | 2,737.822 | 8.590 | 32.994 |
| client.unload | 3 | 47.856 | 16.399 | 16.399 |

30 次 GC 暂停：平均 11.671 ms、最大 23.523 ms。58 条 `minecraft.ServerTickTime` 周期平均值：均值 34.815 ms、P95 80.739 ms、最大 103.782 ms。**这不是逐 tick 分位数**，但说明记录期间确实有较重的服务端负载。3 次客户端卸载慢事件值得补测，不能据此认定卸载是持续性主瓶颈。

## 2. 第一优先级：降低调度成本

### 2.1 ready 队列改为可更新索引堆

位置：`CubeTaskScheduler.java:366–401`、`removeQueuedReady`、`readyForCommit`。

当前每次快照变化调用 `PriorityBlockingQueue.remove(previous)`，其任意元素查找需要线性扫描。移动会更新大量 cube 的 rank，IO 完成也会触发这条路径。实测 `enqueueReady` 和 IO 回调中的 `indexOf` 与这一机制吻合。

建议新增 `CubeReadyQueue`：维护 holder→堆下标，支持插入、删除、优先级增大或减小，单项更新 O(log N)。条目继续带 holder epoch／changeVersion，保持原来的阶段比较与排序语义。堆和下标必须在同一锁内更新，避免并发 holder 回调与服务端 poll 破坏索引；制定统一锁顺序，队列锁内不得回调 holder。

可选后续架构是让 worker 只提交去重的完成通知，由服务端集中更新堆；但这需要额外的通知处理预算和延迟统计，不能无界排队后声称成本已消失。

不建议仅删除 `remove(previous)`：虽然消费端已有过期条目检查，旧条目仍会消耗扫描预算，需要同时解决物理堆膨胀与新近处任务的及时出队。索引堆更便于控制这一点。

验收：随机更新／删除对照简单参考排序；旧优先级增大、共享 owner、取消重入、失败、相同快照、worker 并发完成均保持正确。记录更新数量、物理／有效条目数、更新耗时与 ready 最老年龄。

### 2.2 owner 优先级和需求按增量发布

位置：`CubePriorityIndex.snapshot`、`CubeTaskScheduler.replaceTicket/publishPriorities/retainPrefetches`，`CubeWatchManager.rebuildQueue`。

当前 owner 的正确性已建立，但 snapshot 会聚合所有 owner；发布会构造完整 Map、比较旧新全集并重排相关任务；simulation ticket 移动也会重新构造覆盖与闭包。现场 `replaceTicket/publishPriorities` 是主要服务端热点。

建议每个位置维护有效 owner 的 rank 多重集或等价计数索引，只更新本轮 owner 变更涉及的位置；需求状态保留各阶段引用计数。单 tick 合并所有玩家和预取变化后发布一次，返回实际变化位置集合，避免每个 owner 都触发全局扫描。

**成员集合与距离变化必须分开处理：**平移窗口的新增／移除位置可用边界薄片差集；重叠区的距离 rank 也会改变，不能只更新薄片而留下旧 rank。可先重算受影响 owner 的重叠区，再用索引堆批量更新实际变化项；以后再考虑距离分桶。全量重建保留给换世界、半径大幅变化与一致性校验。

宽范围 feature 依赖的优先级继承必须继续支持多 owner 的最小值，以及最小 owner 撤销后的升高。避免把当前实现退回覆盖 rank 或历史 Math.min。

### 2.3 精确唤醒依赖等待者

当前 `recheckWaitingHolders` 已限制每次最多 512 个，但使用全局 dependencyEpoch，任意 cube 前进都可能让无关等待者重新检查。

在前两项后评估反向依赖表：以 `(cube, stage, epoch)` 注册 waiter，阶段完成只唤醒直接依赖者；失败／撤销／重建统一解除登记。记录边数及内存上限，覆盖宽范围 feature 依赖，保留低频一致性扫描作为诊断手段。这是候选项，尚无证据证明应优先于索引堆。

## 3. 第二优先级：原版 CPU 密度准备与 GPU 批处理

### 3.1 修复 GPU 队列的优先级断点

位置：`CubeTaskScheduler.java:992` 和 `1171` 附近的两个 batcher。

两者都是 `LinkedBlockingQueue`、最多 16 请求、收集窗口 750 μs。普通 generationExecutor 会重排，而两个 batcher 仍按 FIFO 消费；请求携带 priority 但不是出队排序依据。已启动工作会做 epoch 检查，仍不能让新近处请求越过旧的有效远处请求。

改为有界、可更新 rank 的请求队列；同 seed／settings／noiseParameters／网格形状才能合批。取首项、组批与提交前清理失效 epoch；紧急近处任务缩短组批等待，普通任务用年龄防饥饿。明确限制扫描不兼容请求的数量和总时间，当前 deadline 只在队列暂空时检查，不是完整组批硬上限。

采集 batchSize 分布、排队年龄、过期丢弃比例、CPU fallback 次数后，再决定 750 μs 和 16 是否合适；不直接把 batch 扩大数倍。

### 3.2 将原版密度准备从单 batcher 拆出

位置：`VanillaCubeTerrainGenerator.prepareGpuTerrainBatch:207–218`。当前在一个 vanilla batcher 中创建 NoiseConfig，顺序采样多个深层 64 高 batch，再调用 GPU raster。CPU 密度采样是实测工作量。

分成有界流水线：批次键去重／缓存命中→CPU density worker→GPU raster dispatcher→不可变地形结果→主线程提交。先试 2 个准备 worker，根据 CPU、帧时间和排队延迟调节；每个 worker 独占可变采样状态。对同 batch key 使用 single-flight future，避免拆成并行后重复采样或互相 join 阻塞。

NoiseConfig 缓存以世界／种子／生成器配置／注册表代次为键并设上限，世界关闭或资源重载清理。**先审查 NoiseConfig 和 DensityFunction 包装器可变缓存，再缓存线程私有实例；不能直接跨线程共享当前对象。**当前已经在一个 batch 内复用 NoiseConfig，优化目标是跨批次的安全复用。

现有 64 高批次已整批 raster／回读，不再建议把四次回读合为一次作为新改动。

### 3.3 OpenCL 异步提交作为测量驱动的后续项

位置：`OpenClTerrainAccelerator.java:268–315,396–418,672`。当前单共享 accelerator、queueLock、复用 host/device buffer，队列属性为 0；回读使用 blocking=true。Java 样本不能测量设备 kernel 时间，也不能判断 GPU 利用率。

先加入可选设备 profiling 与 batch JFR：分别记录 host 排队、锁等待、H2D、kernel、D2H、结果转换、bytes 和 batchSize。设备事件显式释放；生产环境默认关闭细粒度 profiling。

若证实传输／等待限制吞吐，再建立 2 个起步、最多 3 个 slot，每个 slot 独占 host staging 和 device buffer，以事件依赖管理完成，完成前不复用或释放。共享 kernel 的参数设置须串行化或每 slot 独立 kernel；参数缓存也必须纳入资源归属。异步化可释放提交线程，但单个 in-order queue 本身不保证传输与计算重叠，是否使用多队列由设备能力和实测决定。

关闭、设备异常、取消、fallback 都必须恰好完成一次 future；回退要保留种子和结果一致性。限定在途内存并根据渲染帧耗时限制地形 GPU 工作量，因为当前游戏渲染与地形使用同一独显。不要以独占 GPU 微基准代替共载时的游戏体验验收。

把任意原版 NoiseRouter 全面编译到 GPU 属于长期引擎项目：只对支持节点编译、保持数值与边界一致性、缓存程序并提供 CPU fallback。当前证据不支持把它作为第一轮改动。

## 4. 主线程提交、加载与引擎渲染

### 原版特征进一步分片

`VanillaPlacedFeatureGenerator.generateBatchedSafe` 已在 batch key 之间让出预算，但单次 `vanilla.features.batch` 最大约 33 ms，高于正常 6 ms 提交预算。增加 step／placed feature 级可续跑游标，保留随机流状态、原始索引、写入顺序和中间 writer；单个复杂 feature 仍需更细的算法游标才能形成严格上限。

世界写入保持服务端线程。若以后把 feature 计算移到后台，应使用不可变依赖快照生成写集，在主线程校验版本并按确定顺序提交；不能把持有 ServerWorld 的现有调用直接扔进线程池。

### 加载、编解码与方向预取

准备和发送信用已解耦，activeUnsent 仍最多 512；这只限制根请求，不限制依赖闭包总内存。补充待采样 bytes、地形快照 bytes、待提交结果和依赖节点总量的水位控制。

当前视距 16、垂直半径 4 的深层视窗最多 `33×33×9=9801` 个位置；沿水平轴跨一 cube 新增 297 个，垂直轴新增 1089 个。此为视窗位置需求，不等于全新生成数。预算 96 次工作／tick 也不是 96 个可见 cube／tick。

在调度成本下降后，再做有界方向走廊预取：距离由速度×实测交付 P95 推导，设置上下限、转向撤销和脚下紧急预算；预取只声明需要的 PAYLOAD，避免无意扩成 FULL。比较废弃生成与首屏提前量，再决定是否扩大范围。

当前样本不支持把磁盘或编解码列为首要瓶颈。新增 read／decode／encode 延迟与 bytes 后，只把纯字节转换放后台；实时 palette／方块实体仍需快照与 revision 校验。

### 允许修改 Minecraft 渲染引擎的实施范围

采样显示客户端可见性更新和 SectionBuilder 都有显著工作量。先在现有 Fabric Mixin 接入点补充：DATA 应用→mesh 排队／开始／完成→GPU upload→首次可见；每个记录携带 worldEpoch、cube、revision、meshGeneration。

随后可修改 `ChunkBuilder`、`ChunkRenderingDataPreparer`、`WorldRenderer` 的调度：

1. 构网格优先级按当前相机距离、运动方向及最老年龄更新；迟到的旧 mesh 完成不得覆盖新 revision 或复用后的环形槽位。
2. 合并同 cube 同代次的重复失效，保留邻居到达、光照、透明面和遮挡变化的必要重建；动态邻居变化不能只靠本 cube revision 检查。
3. 可见性图按受影响节点增量更新；拓扑不完整时使用保守可见，验证无洞、无漏绘。避免只为省时间跳过传播。
4. 上传按字节和帧剩余时间限额，并为近处可见区块保留份额。GPU 地形预算与上传／绘制争用一起观察。

协议端不得无约束重排 DATA/LIGHT/UNLOAD；跨世界代次、cube 内 revision 顺序与取消语义仍须保持。原版引擎修改优先用小范围 Mixin，若必须维护引擎分支，再单独确认映射、其他模组兼容及升级成本。

## 5. 分阶段实施和验收

| 批次 | 具体交付 | 必须通过的检查 |
| --- | --- | --- |
| A | 队列／阶段／首次可见指标；索引 ready 堆 | 排序参考模型、取消重入、并发完成；移动时不因过期条目耗尽预算 |
| B | owner 与 ticket 增量发布；GPU batcher 重排 | 多玩家共享依赖、rank 增大／减小、离开世界；近处请求及时出队 |
| C | NoiseConfig 安全缓存、密度准备流水线 | 固定种子 CPU／GPU 栅格一致性、64 高接缝、负坐标、fallback、缓存释放 |
| D | 原版 feature 续跑；按观测结果调整客户端引擎 | 特征确定性、边界写入、光照、旧 mesh 拒绝和透明／遮挡正确性 |
| E | 经设备计时证明有必要的异步 OpenCL 和方向预取 | 同显卡渲染共载、在途资源上限、转向废弃、关闭和设备失败 |

对照使用同种子、同路线、同视距与模拟距离，区分自定义世界／原版深层、已生成路线／全新路线。每种至少三轮，包含水平匀速、垂直升降、急转和折返；新地形测试使用同一未探索起点的存档副本，避免第二轮因缓存命中自然更快。记录实际速度和暂停区间，启动预热与正式区间分开。

核心指标：请求到首次可见 P50/P95/P99、到达前可见比例、等待次数及总时长、各队列最老年龄、逐 tick／逐帧 P95/P99、内存峰值、过期工作比例、GPU 各阶段时间。不要用 GPU 利用率高、已发送字节多或平均 FPS 高单独判定成功。

建议预先约定的实验目标（不是当前已达到的成绩）：同路线可见延迟 P95 至少下降 20%；逐帧 P99 不恶化超过 5%；服务端调度成本明显下降且持续移动时近处队列年龄不持续发散；全部正确性检查通过。若收益未达到或增加内存／抖动，按批次回退，保留指标寻找下一瓶颈。

现有 `tools/performance/run-gpu-raster-validation.ps1` 可用于 raster 阶段一致性与微基准；正式代码改动后还需 Gradle 测试／构建、Mixin 启动验证和真实世界端到端复测。本轮只运行了记录分析程序，未宣称这些实现验收已经完成。
