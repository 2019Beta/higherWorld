# HigherWorld

HigherWorld 是面向 Minecraft 1.21.11 / Fabric 的稀疏立方区块运行层。它保留原版
维度类型和已有 `.mca` 存档，不再把维度的 `min_y/height` 当成可放置方块、移动、
保存或渲染的硬边界；边界之外的数据按独立的 `16 x 16 x 16` cube 按需加载。

## 当前实现

- cube 坐标使用三个 32 位整数，不把 Y 压回二维区块键，也不分配整根高度数组。
- 每个 region 包含 `16 x 16 x 16` 个 cube 槽位。
- region 是带 CRC32 校验的追加日志；进程在记录中途退出时，之前的数据仍可读取。
- cube 记录保存 block/biome palette、生成器版本、方块实体 NBT 和稀疏光照；旧 HWC2-HWC5
  记录可向后兼容读取，HWC6 还会记录光照是否已经完成，使可持久化的 PAYLOAD 在重载时仍会补算光照，
  脏 cube 会定期、离开视距及关服时保存。
- 服务端按玩家位置维护三维 cube 视距，客户端接收加载、卸载和方块更新包。
- 客户端渲染节数组改成以相机为中心的三维环，原版高度之外也能重建网格。
- 方块访问、流体、碰撞、邻居更新、随机刻、方块实体刻和高度查询均会路由到 cube。
- Cube 使用延迟分配的 4-bit 方块光/天空光，支持六向跨 Cube 增减传播、边界重校验和客户端同步。
- Cube 生命周期使用可取消、可重建的分阶段 future；`FEATURES` 严格依赖邻居 `TERRAIN`，`LIGHT`
  严格依赖邻居 `FEATURES`。Ticket 只声明目标状态，依赖节点会主动推进，异步 I/O 和纯地形计算
  在 worker 上执行，Minecraft 状态按阶段在服务器线程的每 tick 预算内提交。
- 原版 BlockPos 网络字段在超出 12 位 Y 时使用转义编码，因此客户端和服务端都必须安装本模组。
- 原版 `.mca` 继续负责原版高度带内的数据与实体兼容；外部方块由 `.hwr` 文件负责。
- 自定义世界的纯噪声采样支持可选 OpenCL GPU 加速；自定义噪声在 GPU 上完成采样和体素分类，
  无限向下/非标准密度函数路径可复用 GPU 栅格体素化阶段。原版高度以下的完整 64 格批次会
  复用密度采样并在 GPU 上一次完成栅格化和回读；GPU 不可用时走同一套 CPU 稀疏栅格，
  原版高度带仍保留原版精确生成。方块写入、洞穴、结构和光照仍沿用现有安全路径。首次启动会生成
  `config/higherworld.properties`，将 `higherworld.gpu.enabled` 改为 `true` 并重启服务器即可启用；
  没有兼容设备或内核失败时默认自动回退 CPU。可选项还包括 `higherworld.gpu.device_index`、
  `higherworld.gpu.allow_cpu_devices` 和 `higherworld.gpu.fallback_on_error`。自定义稀疏 cube 会按最多
  16 个请求批量提交，GPU 端按 X/Z 列缓存深度、基准高度和波动项，减少重复噪声采样和 JNI/队列往返。

cube 文件位于：

```text
<world>/hw_chunks/<dimension namespace>/<dimension path>/region3d/r.<x>.<y>.<z>.hwr
```

实现结构参考了 1.12.2 Forge 的第一代 Cubic Chunks（稀疏 CubeMap、三维玩家视距、
BlockPos 包转义）以及 CubicChunks3 的现代缓存/渲染设计，但运行代码保持 Fabric
1.21.11/Yarn API。

## 构建

```powershell
.\gradlew.bat build
```
