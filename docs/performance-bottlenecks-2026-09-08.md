# 当前性能瓶颈核查（2026-09-08）

检查基线：提交 `4944510`。本次核对当前源码，并重新统计 `research/6/mc-load-20260906-restart/raw/exec-samples.txt`；没有修改生产代码，没有重新启动游戏采样。以下优先级是下一轮优化/测量顺序，不是修复后实测耗时排名。

## 结论

范围聚焦区块生成、加载、渲染缓慢。主线为生成请求 → TERRAIN → FEATURES → PAYLOAD → 服务端编码/发送 → 客户端解码/发布 → mesh 构建/上传 → 首次可见。模拟 tick 仅作为主线程竞争的背景证据。

目前最值得优先验证的是主线程特征生成、加载链路的同步编解码/光照，以及客户端深层建模的逐方块全局缓存查询。旧采样不支持把自定义 GPU 噪声计算或磁盘吞吐当成首要瓶颈。现有背压会主动压低发送速度：生成端空闲不等于生成能力不足，也可能是客户端消费不过来。

## 旧数据重新统计

有效执行样本共 23,941 个，约 360 秒；服务器线程 7,867、渲染线程 4,487、Worker-Main 合计 11,117。按栈内包含方法统计，每个样本对同一方法只计一次；不同方法可重叠，不能相加，截断栈也可能漏掉较深调用。

| 线程 | 栈内方法 | 样本数 | 占该线程组样本 |
| --- | --- | ---: | ---: |
| Server | ServerWorld.tickChunk | 904 | 11.49% |
| Server | SparseCubeLightEngine.propagate | 843 | 10.72% |
| Server | CubeTaskScheduler.refreshTargets | 756 | 9.61% |
| Server | CustomCubeGenerator.applyTerrain | 656 | 8.34% |
| Server | CubeTaskScheduler.requestGraph | 397 | 5.05% |
| Server | CustomLakeGenerator.highestSurfaceY | 149 | 1.89% |
| Render | WorldRenderer.renderBlockLayers | 1,250 | 27.86% |
| Render | SparseCubeLightEngine.propagate | 431 | 9.61% |
| Worker-Main | SectionBuilder.build | 2,122 | 19.09% |
| Worker-Main | ClientCubeCache.section | 874 | 7.86% |

重现：在项目根目录运行 `powershell -File research/6/check-current-hotspots.ps1`。此脚本只读历史数据，不覆盖原始分析。

原有 `analysis-derived` 汇总同时显示：

- 周期 tick 指标均值 12.703 ms、P95 20.4 ms、最大 86.4 ms；它不是逐 tick 分布。
- 348 个 FPS 指标中 67 个低于 60，最低 5；不能据此计算精确卡顿时长。
- GCPhasePause 均值 8.613 ms、P95 19 ms、最大 116 ms。GC collection duration 包含并发阶段，不能当作停顿时长。
- 进程平均使用约 2.06 个 CPU 核；设备 GPU 平均利用率 17.1%。设备指标包含渲染，Java 执行采样也不能测量 GPU 内核或完整 IO 等待。

## 优先检查的当前路径

### 1. 客户端建模仍逐方块查询全局缓存

位置：`src/client/java/org/devt/higherworld/client/ChunkRendererRegionMixin.java:34`、`ClientCubeCache.java:224`、`ClientCubeCache.java:470`。

深层 `getBlockState/getFluidState` 在 region 查询入口直接访问 `ClientCubeCache`，随后计算 CubePos、检查 owner、查 ConcurrentHashMap、读 palette。SectionBuilder、邻面查询和光照/AO 计算会多次经过这些路径。`RenderedChunkMixin` 虽然复制了 section palette，但深层 region 方块读取仍绕过该快照。

旧 Worker 栈中 874 个样本含 `ClientCubeCache.section`，为这条方向提供证据；CubePos 的哈希已优化，不能把旧树桶成本继续全部归因于当前代码。

建议：为一次 mesh 任务绑定本 cube 和邻接 section 的一致快照或局部读取缓存，减少逐体素全局 Map 查询。必须保留迟到 payload、邻块卸载和 revision 变化后的重建通知，不能简单删除现有重定向。验证应同时看 mesh 时间、缓存查询次数和边界画面正确性。

### 2. 主线程特征生成仍是串行吞吐限制

位置：`CubicWorldState.java:1130`、`CustomCubeGenerator.java:176`、`CubeWatchManager.java:941`（均位于 `src/main/java/org/devt/higherworld/world/`）。

纯地形采样异步执行，但地形落盘前的内存提交，以及洞穴、峡谷、结构、湖泊、地牢、矿石特征仍在服务器线程执行。`finishGeneration` 只在完整阶段之间检查 deadline；一个重阶段可以超过整段预算。自适应提交预算正常最多 6 ms/tick，欠账期间请求 0.5 ms/tick；首项前进保障也意味着这不是硬实时上限。

因此 GPU 更快不必然提高交付速度：主线程 FEATURES 积压就会限制后续 PAYLOAD。旧的逐块 palette 加锁已改为每 section 一次锁，湖泊高度扫描也已优化，需重新测量每个特征阶段，不能复用旧占比估算收益。

建议先记录各阶段执行时间、待提交年龄及超预算次数，再对最慢的阶段增加可恢复游标，或将纯计算移到 worker。世界写入、随机数顺序和跨 cube 特征依赖必须保持原语义。

### 3. 光照每节点重复查找，FULL 还受全局收敛约束

位置：`SparseCubeLightEngine.java:80`、`CubicWorldState.java:1186`、`ClientCubeCache.java:42`。

光照传播中，一个节点会查询 managed、opacity、emitted、skySource，透明节点再读取六邻居的 block/sky，最后写回。底层访问反复定位 cube。当前已有位图去重、每 cube 64 步轮转和时间预算，但没有消除这部分查询成本。旧采样中服务器/渲染线程分别有 10.72%/9.61% 样本落在此路径。

`commitLightBody` 只有在 `Result.complete()` 为真才推进 LIGHT；这里 complete 指整个引擎 pending 为空。因此持续产生远处光照工作时，已请求 FULL 的 cube 可能延迟进入 FULL。PAYLOAD 在 LIGHT 前发送，这一约束不直接阻塞首批方块数据，主要影响模拟就绪和后续光照。

建议先减少每节点和六邻居的重复 section 查找；若改成局部完成判定，必须证明跨 cube 光照增减已收敛，不能只看本 cube 队列为空。

### 4. 客户端消费预算和反馈节奏会限制可见加载速度

位置：`ClientCubeCache.java:317`、`ClientCubeCache.java:347`、`ClientCubeCache.java:498`、`CubeStreamBudget.java:31`。

客户端每 tick 解码/发布预算为 4 ms、最多 512 更新；光照预算 1 ms、最多 8,192 节点；最多提交 192 个渲染失效。DATA/LIGHT/UNLOAD 会使本 cube 和六邻居失效，去重只覆盖仍在待处理集合中的请求；跨 tick 的新光照变化仍可能再次重建。

流式发送使用 4 MiB 未确认窗口，客户端每 5 tick 反馈；积压达到字节、消息年龄、渲染或光照阈值后暂停新增发送/预取。在正常 20 tick/s 下反馈间隔约 250 ms，因此短时背压恢复也有反馈粒度。服务器最多 96 个发送工作项/tick，这只是数量上限，并非承诺吞吐；空位置也消耗工作项。

已有背压能防止无界流入，但不能提高实际 mesh 处理能力；pendingRenders 也只统计本模组尚未提交的失效，不包括香草渲染器内部全部任务。建议加入 mesh 待处理/上传指标及背压原因计数，再判断是否要调预算。直接提高发送并发可能只会增大排队延迟。

## 已改善的旧热点与次级候选

加载链路另一个明确的串行环节是编解码：`CubicWorldState.commitTerrainBody` 在主线程恢复已存 cube 的 palette/NBT/光照/计划刻；`encodePayload` 在缓存未命中时同步编码，随后 `ClientCubeCache.put` 在客户端线程再次解码并计算高度索引。异步 IO 完成并不代表 cube 已加载，更不代表已经可见。当前编码有 revision 缓存，不能说每次发送都重新编码；历史数据也不足以把编解码列为第一耗时。下一轮应分开测量读盘等待、服务端 decode/encode、客户端 decode/高度索引，避免仅依据 IO 线程低 CPU 就断言加载足够快。

- `CubePos.hashCode` 已做三轴混合，旧哈希碰撞不是尚未修复的问题。
- `CustomCubeGenerator.applyTerrain` 已使用一次 palette lock；`CustomLakeGenerator.highestSurfaceY` 已自顶向下提前退出。
- `CubeDemandIndex` 已按根闭包引用计数更新；`refreshTargets` 已改为处理变化位置；ready 扫描已有数量和 deadline 限制。
- FULL 模拟索引、维护续扫、欠账期间最小推进预算和客户端背压均已存在。
- 仍可测量 `requestGraph`：每个新根独立展开 DAG，visited 检查位于 priority merge、holder.request/startIo 之后，重叠依赖仍会重复对账；移动时 ticket 闭包也重新计算。属于次级候选，需新采样确认是否超过前述客户端/光照成本。
- 原版 `ServerWorld.tickChunk` 在历史服务器样本中占 11.49%，它属于与模组共享主线程的模拟负载，不能把这部分收益算成优化 GPU 地形可获得的收益。

## 下一轮测量

使用当前构建、固定种子/设置/路线，分别记录进入存档、静止加载、持续进入新区块三个阶段；CPU/GPU 路径分开比较。至少记录 request→TERRAIN→FEATURES→PAYLOAD→客户端应用→首次 mesh 可见的时间，以及 FULL 完成时间。

同步记录阶段队列长度/最老年龄、提交欠账、客户端更新/光照/mesh 积压、背压触发原因和持续时间。若 FEATURES 队列老化，先拆重阶段；若数据已应用但 mesh 延迟，先优化客户端查询与重建；若 FULL 等待而 PAYLOAD 正常，定位光照收敛。

本轮仅做源码核查和历史执行样本复算，没有运行生产代码测试或测得当前版本的 FPS、加载吞吐及优化提升百分比。
