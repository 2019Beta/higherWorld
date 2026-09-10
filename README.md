# HigherWorld

Minecraft 1.21.11 / Fabric 的稀疏立方区块模组。原版把维度的 `min_y`/`height` 当硬边界，
超出去就放不了方块、存不了档、也没法渲染。这里把这个限制去掉，边界之外按
`16 x 16 x 16` 的 cube 按需加载。

维度类型和已有的 `.mca` 存档保持原样，不用转换。

**客户端和服务端都要装。** 原版 BlockPos 的网络字段装不下 12 位以外的 Y，这里用了转义编码，
单边装会直接对不上。

## 当前实现

cube 坐标是三个独立的 32 位整数，Y 不压回二维区块键，也不按整根高度分配数组。
一个 region 管 `16 x 16 x 16` 个 cube 槽位。

region 是追加日志，每条记录带 CRC32。进程在写记录的中途退出，之前的数据照样能读。

cube 记录里存 block/biome palette、生成器版本、方块实体 NBT 和稀疏光照。
HWC2–HWC5 的旧记录还能读。HWC6 多存一位"光照算完了没有"：cube 可能在 PAYLOAD
阶段就被持久化，那时延迟光照还没跑，不记这一位的话下次加载会把黑着的工作区当成有效光照，
初始光照就再也不会重算了。脏 cube 定期存，玩家离开视距时存，关服时也存。

服务端按玩家位置维护三维 cube 视距，客户端收加载、卸载和方块更新包。

客户端渲染节数组改成以相机为中心的三维环，原版高度之外也能重建网格。

方块访问、流体、碰撞、邻居更新、随机刻、方块实体刻、高度查询，都路由到 cube。

光照是延迟分配的 4-bit 方块光/天空光，六向跨 cube 增减传播，边界重校验，客户端同步。

cube 加载用可取消、可重建的分阶段 future，`FEATURES` 依赖邻居的 `TERRAIN`，
`LIGHT` 依赖邻居的 `FEATURES`。ticket 只声明目标状态，依赖节点会自己推进。异步 I/O
和纯地形计算放 worker，游戏状态按阶段在服务器线程的每 tick 预算内提交。

数据分工没变：原版高度带内的方块和实体兼容仍走 `.mca`，外面的方块写 `.hwr`。

## GPU 生成

自定义世界的纯噪声采样可以用 OpenCL 加速，默认关着。首次启动会生成
`config/higherworld.properties`，把 `higherworld.gpu.enabled` 改成 `true` 再重启就行。
没有兼容设备、或者内核起不来，会自动回退 CPU。

打开之后，自定义噪声的采样和体素分类都在 GPU 上做；无限向下和非标准密度函数这类路径
复用同一套 GPU 栅格体素化阶段。原版高度以下的完整 64 格批次会复用密度采样，
一次做完栅格化和回读。回退到 CPU 时走的是同一套稀疏栅格，原版高度带始终保留原版精确生成。
方块写入、洞穴、结构和光照没走 GPU，还是原来的路径。

自定义稀疏 cube 按最多 16 个请求一批提交。GPU 端按 X/Z 列缓存深度、基准高度和波动项，
省掉重复的噪声采样和 JNI/队列往返。

其余开关：`higherworld.gpu.device_index`、`higherworld.gpu.allow_cpu_devices`、
`higherworld.gpu.fallback_on_error`。

## 存档位置

```text
<world>/hw_chunks/<dimension namespace>/<dimension path>/region3d/r.<x>.<y>.<z>.hwr
```

## 参考

第一代 Cubic Chunks（1.12.2 Forge）的稀疏 CubeMap、三维玩家视距和 BlockPos 包转义，
以及 CubicChunks3 的现代缓存和渲染设计，都影响过这里的结构。运行代码本身是
Fabric 1.21.11 + Yarn API。

## 构建

```powershell
.\gradlew.bat build
```
