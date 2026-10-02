# Entity Capture

一个 **Fabric / NeoForge / Forge** 的 **客户端**模组：把 Minecraft 里的**任意实体**（含模组生物）
在 **1:1 原生分辨率**下转成带颜色的体素捕获包 `.mcvox`。

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

## 支持的加载器 / 版本

| 加载器 | Minecraft | Java | 额外前置 | 发布产物 |
|---|---|---|---|---|
| Fabric | 1.21.1 | 21 | Fabric API | `entity-capture-<版本>-fabric.jar` |
| NeoForge | 1.21.1 | 21 | 无 | `entity-capture-<版本>-neoforge.jar` |
| Forge | 1.20.1 | 17 | 无 | 计划中 |

三种产物写出的 `.mcvox` 格式完全一致，仅头部 `modLoader` 字段区分为 `fabric` / `neoforge` / `forge`；MC Block Studio 无需改动即可读取。

## 下载 / 安装

- 下载：[GitHub Releases](https://github.com/yunfang1718128/entity-capture/releases) 里对应加载器的 jar。
- Fabric：把 `entity-capture-<版本>-fabric.jar` 放进 `.minecraft/mods/`，并装好 **Fabric API**。
- NeoForge：把 `entity-capture-<版本>-neoforge.jar` 放进 `.minecraft/mods/`，需要 NeoForge **21.1.x**（Minecraft **1.21.1**）。

## 环境要求

- Fabric：Minecraft **1.21.1**、Loader **0.19.5**、Fabric API **0.116.17+1.21.1**、Java **21**
- NeoForge：Minecraft **1.21.1**、NeoForge **21.1.x**、Java **21**
- Forge：Minecraft **1.20.1**、Java **17**（计划中）

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

- `minecraft_zombie_<时间戳>.mcvox` —— 捕获包（导入 MC Block Studio 使用）
- 头部包含 `mcVersion`、`modLoader`、`unitsPerBlock`、`dimensions`、`solid` 等字段，供 Studio 识别与还原。

所有生物（含被放大渲染的，如尸壳、巨人）统一按原生贴图 **1:1** 捕获，轮廓不会多一层或出现缺口。

## 许可证

MIT，见 [`LICENSE`](./LICENSE)。
