# 区块加载性能调查（2026-09-05）

本报告记录用户反馈“移动到未生成区域时深层地形不生成”后的运行态证据，以及 GPU 栅格阶段的独立验证。旧基线实例是 PID 33872，Minecraft/Fabric 客户端于 19:48:56 启动；随后重启的当前实例是 PID 29500，于 20:16:11 启动。采样期间没有终止进程、调用区块请求或驱逐，也没有修改存档。

## 结论

当前停滞点在调度器的宽 feature terrain halo，而不是 RTX GPU 栅格吞吐。20:07:55 在 overworld 的 server thread 上读取到：

| 指标 | 数值 |
| --- | ---: |
| holders / requiredPositions | 14,467 / 14,467 |
| prefetchPositions | 4,356 |
| readyQueue / queuedReady | 430,902 / 13,133 |
| holder status | IO_READY 11,250；TERRAIN 2,783；FEATURES 49；PAYLOAD 296；FULL 89 |
| holder target | TERRAIN 5,008；FEATURES 1,978；PAYLOAD 1,856；FULL 5,625 |
| feature owners | 258 |
| feature terrain dependencies | 27,864 |
| dependency references still below TERRAIN | 4,448 条引用（全部对应 IO_READY；引用可能重复） |
| dependencies already at least TERRAIN | 23,416 |
| failed / missing dependency holders | 0 / 0 |
| generation executor active / pool / queue | 0 / 0 / 0 |
| vanilla/custom batcher pending | 0 / 0 |

更具定位意义的是，258 个 feature owner 全部停在 `TERRAIN`；258/258 的普通 3×3×3 邻域已经满足，而其宽 feature halo 仍被阻塞。遍历 owner 列表得到 4,448 条仍低于 `TERRAIN` 的宽依赖引用（引用可能指向重复 holder），这些引用对应的 holder 已完成磁盘读取并停在 `IO_READY`，等待 terrain 调度，因此没有进入 feature 阶段。`readyQueue` 的物理条目约为 `queuedReady` 的 32.8 倍，显示出大量过期/重复唤醒条目和队列扫描压力。完整只读输出见 [runtime-state-v6-20260905-200800.txt](../diagnostics/runtime-state-v6-20260905-200800.txt)；线程栈见 [jcmd-33872-threads.txt](../diagnostics/jcmd-33872-threads.txt)。

这解释了“GPU 已启用但深层不生成”：GPU kernel 没有持续获得可处理的 terrain batch，服务器端先被宽 halo 中已完成磁盘读取但等待 terrain 调度的 IO_READY 引用和 ready 队列 churn 卡住。当前证据也不支持把 CPU 生成密度或 GPU 算力单独称为根因。

## 重启后的当前实例（PID 29500）

20:32:29 读取到当前 overworld 的调度器状态如下：

| 指标 | 数值 |
| --- | ---: |
| holders / requiredPositions | 19,346 / 19,346 |
| prefetchPositions | 6,705 |
| readyQueue / queuedReady | 121,448 / 11,599 |
| holder status | IO_READY 6,495；TERRAIN 10,468；FEATURES 664；PAYLOAD 1,285；FULL 434 |
| holder target | TERRAIN 8,883；FEATURES 1,827；PAYLOAD 3,011；FULL 5,625 |
| feature owners / featureReadyOwners | 2,434 / 777 |
| featureRequiredEntries | 11,312 |
| feature terrain dependencies | 323,928 条引用 |
| dependency references still below TERRAIN | 21,923 条（全部为 IO_READY） |
| dependencies already at least TERRAIN | 302,005 |
| blocked feature owners / normalReadyWideBlockedOwners | 1,639 / 1,639 |
| failed / missing dependency holders | 0 / 0 |
| generation executor active / pool / queue | 0 / 0 / 0 |
| vanilla/custom batcher pending | 0 / 0 |

`featureReadyOwners=777` 说明重启后的实例已经有部分 feature owner 完成推进；仍有 1,639 个 owner 被宽 feature halo 阻塞。物理 `readyQueue` 仍约为 `queuedReady` 的 10.5 倍（121,448 对 11,599），虽较旧基线的 32.8 倍收敛，队列扫描压力仍然存在。当前 scheduler 已加载 `featureTerrainRequiredCounts` 字段，说明这次重启确实使用了带宽 halo 依赖计数的运行版本。完整只读输出见 [runtime-state-v6-20260905-2028-new.txt](../diagnostics/runtime-state-v6-20260905-2028-new.txt)。

当前 PID 的 30 秒 JFR（20:33:01–20:33:31）在 Server thread 采样到 `CubicWorldState.tryAdvance` 80 次、`commitFeatures` 116 次和 `commitFeaturesBody` 142 次，并反复看到 `VanillaPlacedFeatureGenerator.generateBatch` 调用栈，因此可以确认窗口内存在非零的 feature 提交执行。JFR 的 `ExecutionSample` 是统计采样而非调用计数；这些数字不能证明每个 ready holder 都提交成功，也不能排除部分 holder 在预算边界被 requeue。`AdaptiveBudget.recordCommit` 等短方法未出现在采样栈中，不能由此判断其是否被调用。JFR 文件见 [higherworld-jfr-current-2032.jfr](../diagnostics/higherworld-jfr-current-20260905-2032.jfr)。

同一 JFR 中 197 个 Server thread 样本包含 `VanillaPlacedFeatureGenerator`：169 个包含 `createFeatureChunk`，其中 143 个同时落在 `PalettedContainer`、`ChunkSection.setBlockState` 或 `PackedIntegerArray` 的 palette 拷贝路径；189 个包含 `generateBatch`，其中 23 个没有进入 `createFeatureChunk`。`PlacedFeature` 和 `OreFeature` 分别出现在 194 和 177 个样本中，registry/feature collection 只有 1 个样本，不能视为当前热点。`minecraft.ChunkGeneration` 和 `minecraft.ChunkRegionRead` 事件均为 0，说明这一窗口的可见 CPU 热点主要是 feature batch 的临时 chunk/palette 读取拷贝与锁竞争，而不是持续的 GPU terrain batch 生成。

## 运行版本边界

日志确认 OpenCL 在 19:49:09 选择了 NVIDIA GeForce RTX 5070 Laptop GPU（OpenCL 3.0 CUDA）；启动时的 49 个区块在 714 ms 内完成。线程 dump 随后显示 Server thread 在 `MinecraftServer.waitForTasks`，`higherworld-vanilla-terrain-batcher` 在 `LinkedBlockingQueue.take`，Worker-Main 线程在 ForkJoinPool 等待。

旧基线实例加载的 `build/classes/java/main/org/devt/higherworld/world/CubeTaskScheduler.class` 时间为 15:13:10，而工作树中的 `src/main/java/org/devt/higherworld/world/CubeTaskScheduler.java` 在 19:55:21 更新；旧实例的 scheduler 字段没有 `featureTerrainRequiredCounts`。当前 PID 29500 对应 class 时间为 20:16:11，V6 反射已看到该字段及 `priorityEpoch`，因此当前 snapshot 可以用于检查这次重启后的宽 halo 行为。它仍然只是一个时间点的对象状态，不能替代持续进度曲线或逐次提交计数。

## GPU 栅格独立验证

`tools/performance/run-gpu-raster-validation.ps1` 与 `GpuRasterValidation.java` 在真实 RTX 5070 Laptop GPU 上验证了 250 个合法 `(stepX, stepY, stepZ, height)` 组合，每组两个 batch，CPU sign 结果全部一致。10 次 warmup、30 次迭代的同一栅格阶段中位数为：旧 4×16 布局 0.246 ms，新 1×64 布局 0.100 ms，约 2.45×；2/5 次短跑为 0.259 ms 对 0.144 ms，约 1.81×，说明短样本存在测量波动。

这些数字只包括 host-to-device、kernel、device-to-host 的栅格阶段，不包括 noise 输入捕获、区块 IO、feature halo、主线程提交、lighting、payload 编码/网络和客户端 mesh rebuild，所以不能换算成整体区块加载提速。运行时 `nvidia-smi` 采样曾见 GPU 约 32–58%、显存约 0.6 GiB/8 GiB；这同样不能证明完整加载链路已饱和。

## 验证限制

- Gradle 在本机仍在任务执行前因 `Unable to establish loopback connection` 失败；没有把它伪装成通过。
- 早期 `build/` 诊断文件被外部 clean 删除，所以不引用旧进程 JFR；本报告引用当前 PID 的 30 秒 JFR、线程 dump、server-thread 对象反射和 GPU harness 结果。
- 当前重启实例已不再满足“所有 feature owner 都被 wide halo 卡住”：777/2,434 个 owner 已 ready，但 1,639 个仍被阻塞。后续验证仍应检查这些 owner、`readyQueue/queuedReady` 和 generation executor/batcher 是否随移动操作持续推进；本次 JFR 的非零提交采样不足以给出完整提交成功率。

## 最终修复与收尾验证（2026-09-06）

`VanillaPlacedFeatureGenerator.createFeatureChunk` 的 `FeatureBatchWriter` 分支现对每个临时 `ChunkSection` 只获取一次 palette lock，在 `try/finally` 中用 `setBlockState(..., false)` 完成 4096 个方块写入，再释放锁，最后才包装为 `TrackingChunkSection`。`false` 只选择 `PalettedContainer.swapUnsafe()`，`ChunkSection` 仍更新非空方块、随机刻和流体计数；原有 translated 分支保持逐方块 setter。当前生产源码用独立 `javac` 编译成功（退出码 0），`javap` 已确认生成的字节码包含一次 `lock`、带异常清理的 `unlock`，以及五参数 `setBlockState` 的 `false` 参数。

当前已有的调度修复会即时保留宽 halo 引用并在就绪提交时过滤无效候选；`markInFlight` 会发布 prefetch 变更；当 `readyQueue` 超过 `4 * live + 1024` 时压缩过期对象；epoch 刷新会补足扫描预算；存在正预算时允许首个候选推进，避免首轮扫描把可执行工作全部跳过。

当前版本的独立手工 harness 已通过 23 个调度、watch 和预算测试，结果为 `tests=23 failures=0`。按要求执行的 Gradle 定向测试仍在任务启动前因 `Unable to establish loopback connection` 失败，因此没有把 Gradle 结果记为通过。

临时 Minecraft palette smoke 未能在普通独立 JVM 中执行完成：缓存的 Minecraft 类需要游戏启动器提供的运行时转换，初始化阶段触发 `VerifyError`（`MobEntity.isInAttackRange`）；这次没有据此给出 palette 等价性或 setter 提速数字。当前结果也尚未经过游戏内重新加载后的实测，区块移动时的 feature 推进、总体加载时间和客户端表现仍需用包含该生产类的重启实例复测。
