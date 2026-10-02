# 深层 16×16×16 cube 加载优化方案

调查日期：2026-10-01。源码基线：`32914a1`，调查开始时工作区无未提交改动。
本次为源码研究，未修改运行代码、启动游戏、运行 Gradle 或录制新的性能数据。
调查期间其他任务修改了地形 batcher 和部分客户端表单；本次保留这些修改。最新工作区已抽取 CubeTerrainBatcher，队列上限为 1,024，溢出回退 CPU；仍为 FIFO 收集，本文主要结论不受该抽取影响。运行代码是否已包含这些修改未经验证。

## 判断

优先检查 FEATURES 主线程长尾、宽地形依赖和原版地形提交；已有存档重访则优先检查依赖展开、I/O、解码及客户端建模。不能仅凭“加载慢”把两种情况归为一个瓶颈。

当前已有异步 I/O、最多 8 个地形 worker、64 高原版地形批次去重、线程内 NoiseConfig 复用、GPU 批处理、优先级差量发布、indexed ready heap、阻塞队列限额复查、PAYLOAD 与 LIGHT 分离、编码缓存和客户端背压。继续重复这些改造的收益有限。

现存历史证据见 `performance-optimization-directions-2026-09-10.md`：当时已记录的 vanilla.features.batch 事件 P95 为 9.282 ms，最大为 59.708 ms。事件有 1 ms 阈值，且调查之后源码已有优化；数字仅支持定位方向，不是当前性能或预计提速。

## 当前链路与规模

请求 → IO_READY → TERRAIN → FEATURES → PAYLOAD → 编码/发送 → 客户端解码发布 → 网格构建/上传。
FULL 模拟 ticket 还要求 LIGHT；虽然首次 PAYLOAD 无需等光照，模拟工作仍共享服务器资源。

- CubeWatchManager 垂直半径为 4，即 9 层。完全位于深层时，水平视距 r 的观看窗口为 `(2r+1)^2 × 9` 个 cube。
- r=16 时为 9,801 个观看 cube，不含额外依赖；16×16×16 是单个 cube 的方块尺寸，并不是观看窗口尺寸。
- 世界每 tick 发起预取上限 128，每玩家 active-unsent 上限 512，世界发送上限 96；实际发送受 EWMA 和客户端字节背压约束。
- 在 20 TPS、全部数据已就绪、单玩家独占预算且每 tick 发满 96 个的理想条件下，9,801 个 cube 仅发送也至少需要 103 tick，约 5.15 秒。这不是实际加载预测；首屏无需等全部窗口完成。
- 原版新生成 FEATURES 常规依赖为 3×3×3 TERRAIN；额外装饰读取集合为 3×3 水平列、5 个 64 高 band，共最多 180 个 terrain cube。临近原版底部时会排除原版高度内的依赖。
- 每个目标 cube 按固定顺序应用 3 个来源 band × 3×3 来源列，共 27 个 feature batch key。来源批次已有缓存，180/27 不能解释为每个 cube 都独立重生成同样多份数据。

## 第一优先级：原版地形批量提交

入口：VanillaCubeTerrainGenerator.TerrainBatchSnapshot.applyTo、LoadedCube.setGeneratedBlockState。

当前遍历 4,096 个方块调用 section.setBlockState 默认路径；自定义地形 applyTerrain 已采用 section 单次 lock/unlock，并通过 `setBlockState(..., false)` 写入。

方案：把相同的批量加锁方式用于原版 terrain snapshot 提交；新建空 section 可跳过空气写入，修复/覆盖已有 section 时仍必须清空气。进一步评估均匀 palette 或 worker 预构建 section 的可行性，但后者涉及所有权转交和注册表线程安全，单独实施。

验收：相同快照得到相同 block states、非空/流体/随机刻计数；覆盖全空气、全实心、混合与覆写。记录 terrain.commit P95、每 tick 提交量和锁调用成本。静态上这是明确的重复工作，实际收益待测。

## 第二优先级：将 feature batch 内部切片

入口：VanillaPlacedFeatureGenerator.generateBatched/generateBatchMeasured，InfiniteDownwardGenerator.generateFeatures。

当前只在 batch key 间检查 deadline；一个未缓存 batch 会完整执行所有 placed feature，单个结构 pass 也完整执行。服务器 commit 预算为最多约 6 ms，超时后缩至至少 0.5 ms；这些预算不能中断正在执行的重批次。

分两阶段：

1. 建立完整且固定的地形/群系读取视图，先明确原版高度内读取、方块实体及代理访问的所有实时世界依赖。保留同一 batch 内前序写入对后序 feature 的可见性。
2. 引入未完成 batch job，持有 writer、调用游标和确定的随机数状态，按 placed feature 边界续跑。完整结束后才发布不可变 batch cache；取消、失败或世界卸载时释放 job。

feature.generate 本身仍可能超过预算；若新 JFR 证明单次调用长尾显著，再为特定 feature 增加内部游标，或将已证明纯计算的部分移到 worker。不能把包含实时世界访问的整段装饰直接异步化。

验收：相同种子输出、调用顺序、随机序列、矿脉和滴水石跨边界连续性；超预算次数、tick P99、最老 FEATURES 等待年龄及取消后内存。此项风险中高，但最直接针对卡顿长尾。

## 第三优先级：按来源 band 组织装饰任务与依赖

入口：terrainBatchPositions、featureBatchTerrainReady、featureTerrainDependencies、FeatureCache。

当前多个目标 cube 各自注册宽读取集合；terrain holder 和 batch 内容已共享，但依赖列表、检查及优先级传播仍按目标维护。

方案：以 `(world, sourceX, sourceZ, sourceBand, generation/settings identity)` 为共享 batch job 的身份，目标 cube 订阅结果；在同一 64 高 band 内适度聚合提交，减少缓存反复访问和重复依赖检查。仍以玩家附近优先，并提供远处任务老化保障。

首先保持现有 180 个读取范围和输出不变，只共享管理工作。再根据各 placed feature 的实际读取范围，为已知安全 feature 分类缩小依赖；未知/模组 feature 保留完整 halo。不能统一把依赖缩成 27 或任意砍掉邻居来源，否则会改变边界装饰。

FeatureCache 当前上限 2,048，terrain batch cache 上限 4,096。应增加命中率、重算次数和按字节估算的占用指标，按活跃需求保护批次并维持内存上限。不要直接扩大固定条目数；完整 r=16 窗口的来源工作集可能明显超过现有 feature cache。

## 第四优先级：已有存档加载快路径

入口：CubeTaskScheduler.requestGraph、nextCommitDependenciesReady，CubicWorldState.commitTerrainBody/commitFeaturesBody。

当前请求在尚未获知 I/O 结果时就展开目标阶段的邻居依赖；已有记录虽不运行新装饰，FEATURES 阶段仍有通用邻居门槛。新原版装饰的额外 180 读取集合只在无存档 payload 时注册，不能把它误归因于全部存档重访。

方案：I/O 就绪后按记录版本和有效性判定后续需求。当前版本且可直接恢复的 PAYLOAD 记录走恢复快路径，不等待新装饰邻居；需要迁移、记录损坏或真正缺失时回到完整生成图。FULL 的光照/模拟依赖仍按需建立，保留 hasLight=false 时重算光照。

这要求 demand closure 与 lifecycle 保持一致，不能只在 holder 上跳过状态而让依赖继续后台生成。验收覆盖旧版本升级、缺光记录、方块实体、scheduled ticks、取消重入及损坏记录。

## 第五优先级：精准唤醒依赖等待者

入口：CubeTaskScheduler.recheckWaitingHolders、nextCommitDependenciesReady。

当前全局 dependencyEpoch 变化触发有界轮询，任意 cube 进展都可能让无关等待者重新检查 27/180 个依赖。

方案：登记每个阶段首个阻塞依赖，或维护有界的反向依赖与剩余计数；依赖到达所需状态后只唤醒实际 owner。采用 epoch/version 防止取消和重建后的旧完成通知误唤醒。保留限额兜底复查，防止丢通知永久等待。

先记录“依赖检查次数/真正解阻次数”，只有轮询占用显著时再排到更高优先级。

## 第六优先级：客户端首次可见与近处优先

当前客户端每 tick 解码发布预算 4 ms、最多 512 更新，渲染失效提交上限 192；光照另有 1 ms 预算。渲染失效已按插入顺序去重，高度更新和 RenderAccess 局部缓存也已实现。

方案：记录每 cube/revision 从 DATA 到首次可见的延迟，区分解码、失效等待、mesh 构建和上传。若远处任务挡住近处，给数据 drain 和重建队列增加距离/等待年龄优先级；保留 DATA、LIGHT、UNLOAD 的版本和顺序语义。依据邻居数据/光照变化的实际影响合并重建，但不能漏掉边界面的出现与消失。

只有反馈确认客户端队列经常触发背压时才调整 drain/发送窗口；提高发送量不能解决 mesh 构建慢。

## GPU 和 I/O 的位置

run/config/higherworld.properties 当前写入 gpu.enabled=false；它不证明所有启动配置或正在运行游戏的状态。原版 GPU 路径仍在 CPU 采样 NoiseRouter，只加速插值/符号栅格化；目前已有 64 高批次和最多 16 个批次的合并。自定义地形 GPU 可覆盖更多采样工作，应分别测量。

优先研究 batch future 的非阻塞订阅，避免重复 cube 请求占用 CPU worker 等待同一批次；GPU FIFO 若有积压，按当前优先级调度兼容批次并防止饥饿。GPU 提速可能被 FEATURES 主线程或客户端建模吞掉。

存档 I/O 应按冷加载单独看 region 打开/索引、CRC、读取等待与解码。当前已有异步与 region 批处理，不能先断言是磁盘问题或直接增加线程。

进一步核查发现：CubeIoScheduler 最多 4 个 worker，每批最多 16 个同 region 请求；CubeStorage.readBatch 仍逐 cube 调用读文件，CubeRegionFile 每条记录执行 seek/readFully、解压与 CRC。region 打开时扫描追加日志重建索引，而且打开/扫描位于 CubeStorage 的全局缓存锁内。冷加载跨 region 时，这是一条值得单独测量的串行路径。

如果 region-open 等待显著，可用每 region 的 opening future 去重，在全局 LRU 锁外扫描，再以短锁发布句柄；关服、淘汰和打开失败要明确所有权。如果读解压占主导，可先按记录偏移排序合并读取，再把不可变压缩记录交给独立解压任务，避免把解压也放在共享文件位置锁中。需保留 CRC、截断日志恢复和读写一致性，是否增加持久索引应另行设计，不能牺牲崩溃恢复。

客户端还存在均匀光照判定的最多 4,096 格扫描，以及视距 16 时 17 层渲染环（观看 cube 窗口只有 9 层）。若 profiling 指向这些路径，可缓存随 revision 失效的均匀分类，并核对相机移动时渲染环遍历开销；不应直接缩减渲染环导致视野缺块。

## 测量与实施顺序

先建立相同种子、视距、模拟距离、坐标及路线的四组对照：新区域首次生成、已有存档冷加载、原地热加载、连续水平/垂直移动；原版深层与自定义世界分开测。

利用现有 higherworld.CubeWork 事件，补充请求到 IO/TERRAIN/FEATURES/PAYLOAD 的阶段等待时间、队列最老年龄、cache hit/miss/recompute、发送字节及背压持续时间、客户端首次可见。默认 1 ms 事件阈值会漏掉短调用，不能以事件数代表总调用数；嵌套事件不能简单累加。

建议实施顺序：测量探针 → 原版 terrain 批量提交 → 固定读取视图与 feature 内部切片 → 按 band 共享依赖/缓存 → 存档恢复快路径 → 按实测选择精准唤醒或客户端调度。

验收同时看附近首屏 P50/P95/P99、完整视距耗时、cube/s、tick/帧 P95/P99、内存与废弃任务比例；提速不能以改坏地形边界、读档数据或制造持续卡顿为代价。

## 本轮实施记录

已完成以下修改：

- 原版 terrain 快照整段提交仅获取一次 section 锁。空 section 可跳过 air，非空 section 仍覆盖全部 4,096 格，避免残留旧方块。
- 深层原版装饰批次保存随机数、writer 和调用游标，在 feature 调用之间按预算续跑；terrain 与 quart 群系读取使用固定快照。只发布完整批次，未完成任务缓存上限 32。模组来源、与原版高度相交的批次、越界或不支持的查询回退整批生成。单个 feature 调用及快照捕获仍可能超过预算，不能宣称消除了所有长尾。
- 同一四 section band 共享装饰 terrain 依赖列表和就绪判断，保留每个 owner 的需求引用；完成或取消后释放共享组。
- PAYLOAD 请求先读 IO。可恢复记录不启动 FEATURES terrain 邻域；缺失或旧记录再展开生成依赖。记录头仅用于调度分类，安装前仍执行完整 codec 解码和版本迁移。FULL 的光照/模拟依赖继续保留。
- 阻塞任务订阅具体依赖阶段完成通知，回调只入队，由服务端核对 owner 与 epoch 后唤醒；保留缺失/异常依赖的扫描兜底。
- 客户端渲染失效采用距离优先，每轮至少八分之一预算处理 FIFO 旧任务，维持去重与每 tick 192 上限。DATA/LIGHT/UNLOAD 顺序保持原有处理。
- region 打开按 future 去重，索引扫描移出全局缓存锁；批读按物理偏移排序读取压缩记录，锁外解压与 CRC 校验。关闭等待已开始的打开和读者，失败与淘汰路径释放句柄。没有增加持久索引，也未实现连续记录合并读取。
- 增加可选 `lifecycle.request_to_payload` CubeWork 事件，标识 ready/failed/cancelled，便于后续对照 JFR。

验证：使用本地缓存的 Minecraft 1.21.11 映射依赖直接 javac 编译本轮主端修改、客户端接入和回归测试；53 项调度、存储、渲染队列和切片边界/游标检查通过（本地反射 harness 调用 JUnit 断言）。独立 StorageIoRegression 通过乱序批读、并发首次打开、20 轮关闭/读取竞态、截断尾恢复。全仓 Java 语法解析 166 文件、0 错误，`git diff --check` 通过。

TerrainCommitRegressionTest 已编译，独立 Minecraft bootstrap 因映射 JAR 的访问权限/字节码验证问题未能执行；这部分没有运行通过的结论。没有运行 Gradle、打包或启动游戏。实际速度、完整生成结果一致性、模组兼容性和边界光照需在 Fabric 运行环境进行前述路线对照，本轮不提供未经测量的提速百分比。
