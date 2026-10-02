# Entity Capture

一个 Fabric **客户端**模组：把 Minecraft 里的**任意实体**（含模组生物）在 **1:1 原生分辨率**下
转成带颜色的体素捕获包 `.mcvox`。

它是像素画工具 [**MC Block Studio**](https://github.com/yunfang1718128/mc-block-studio) 的配套采集端。

## 配套项目（联动）

两个仓库通过 `.mcvox` 一份格式解耦，可独立开发：

```
[Entity Capture 模组]                         [MC Block Studio]
游戏内捕获实体  ──►  .mcvox  ──►  导入 → 放大 N 倍 → 空心/填充 → 颜色匹配方块 → 导出 .litematic
```

- 本模组只负责 **Minecraft 域**：模型 → 四边形采集 → 占用 + 表面色 → `.mcvox`。
- 放大、空心/填充、方块匹配、`.litematic` 导出全在 [MC Block Studio](https://github.com/yunfang1718128/mc-block-studio) 完成。
- Studio 已内置 **82 种原版生物**的 `.mcvox`，可直接使用；本模组用于补录模组生物、重新捕获或私有实例。

## 环境要求

- Minecraft **1.21.1**、Fabric Loader **0.19.5**、Fabric API **0.116.17+1.21.1**
- Java **21**

## 构建与运行

```bash
./gradlew build        # 产物：build/libs/entity-capture-<版本>.jar
./gradlew runClient    # 启动开发客户端
```

## 使用

进游戏后：

| 入口 | 说明 |
|---|---|
| **`H`**（默认键） | 打开**实体选择器 GUI**：可搜索（名称 / ID）、按分类筛选（全部 / 被动 / 水生 / 敌对）、右侧 3D 预览；开启**多选**后可勾选任意多个生物、`全选` / `全不选`，一次批量捕获 |
| `/capturemob <entity>` | 捕获指定实体类型，如 `/capturemob minecraft:zombie`（带 tab 补全） |
| `/capturemob`（无参） | 捕获准星所瞄的实体 |
| **`G`**（默认键） | 快速捕获准星实体 |

捕获在客户端渲染线程完成，**逐个 / 每 tick 一个**，不卡帧；完成后聊天栏给出汇总。

## 输出

写入游戏根目录 `entity-capture/`：

- `minecraft_zombie_<时间戳>.mcvox` —— 捕获包
- `minecraft_zombie_<时间戳>.mcvox.quads.txt` —— 诊断用四边形转储（精确浮点 + 法线）

## `.mcvox` 格式 v1（provisional）

单文件二进制，两边都不需要 zip 依赖：

```
[0..3]   magic "MCVX"
[4]      uint8  formatVersion = 1
[5]      uint8  flags (bit0 = 负载 zlib/deflate 压缩)
[6..7]   uint16 reserved
[8..11]  uint32 headerLength (LE)
[12..]   header JSON (UTF-8)
然后     occupancy: ceil(n / 8) 字节（位图，LSB 优先，n = sizeX*sizeY*sizeZ）
然后     colors:    n * 4 字节 RGBA
```

- 坐标：`+Y` 上、`+Z` 南、`+X` 东，原点为模型 AABB 最小角；体素索引 `x + z*sizeX + y*sizeX*sizeZ`。
- `occupancy` 位 = 1 表示模型占据该格子。
- `colors`：**表面体素**存贴图原色（无光照）；内部与空体素为 `0,0,0,0`。

实现细节、诊断记录与路线规划见 [`PLAN.md`](./PLAN.md)。

## 许可证

MIT，见 [`LICENSE`](./LICENSE)。
