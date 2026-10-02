# HANDOFF — `entity-capture` 多加载器化

> 给"新对话/新协作者"的交接简报。假设你**对本项目一无所知**。先读本文件，再读
> [`PLAN.md`](./PLAN.md)（完整开发史）与 [`README.md`](./README.md)（功能说明）。

## 0. 已定决策（不要更改，除非另有指示）

- **仓库结构：方案 B** —— 按 MC 版本分文件夹的多 Gradle 子项目（共享核心 + 各加载器模块），
  不做 Stonecutter。
- **每个加载器独立 tag** 发版（如 `fabric-v0.3.0`、`neoforge-v0.3.0`、`forge-v0.3.0`）。
- **两个新对话**：先 **NeoForge 1.21.1**，再 **Forge 1.20.1**（各自开一个）。
- **GUI 允许按版本做细微差异**（`EntityPickerScreen` 在 1.20.1 需要少量改写）。
- `.mcvox` 格式跨加载器**完全不变**；`McvoxHeader.modLoader` 填 `fabric` / `neoforge` / `forge`。
  MC Block Studio 无需任何改动即可读取三种产物。

## 1. 目标

把现有 **Fabric 1.21.1 客户端模组 `entity-capture`** 扩展到 **NeoForge 1.21.1** 与
**Forge 1.20.1**，三者**同仓**。功能一致：把任意实体（含模组生物）按 1:1 原生分辨率捕获为
`.mcvox`，供像素画工具 [MC Block Studio](https://github.com/yunfang1718128/mc-block-studio) 消费。

## 2. 仓库现状

- 仓库：<https://github.com/yunfang1718128/entity-capture>（public，当前仅 Fabric）。
- 本地：`D:\Coding\minecraft-pixel-art-generator\capture`。
- 版本 `0.2.0`；Fabric 1.21.1 / Loader 0.19.5 / Fabric API `0.116.17+1.21.1`。
- 工具链：**Gradle 8.8 + Loom 1.7.4 + Mojang 官方映射 + Java 21**。
- 发布：`.github/workflows/release.yml`（`tag v*` → `./gradlew build` → 上传 `build/libs/entity-capture-*.jar`）。
- `.gitattributes`：`* text=auto eol=lf`；**`gradlew` 必须保持 LF**（Linux CI 要执行）。

## 3. 目标矩阵

| 目标 | MC | Java | 构建插件 | 元数据 |
|---|---|---|---|---|
| Fabric | 1.21.1 | **21** | Loom | `fabric.mod.json` + mixins json |
| NeoForge | 1.21.1 | **21** | NeoGradle (`net.neoforged.gradle.userdev`) | `META-INF/neoforge.mods.toml` |
| Forge | 1.20.1 | **17** | ForgeGradle 6 | `META-INF/mods.toml` |

> 两个 MC 版本 + 两套 Java（21/17）是最大难点。三者都用 **Mojang 官方映射**，
> `net.minecraft.*` 层代码基本逐字复用。

## 4. 目录结构（方案 B）

```
capture/
  settings.gradle            # 声明所有子项目
  build.gradle               # 根：公共配置（按需）
  common-1.21/               # 供 1.21.1 的 fabric + neoforge 共享的"与加载器无关"核心
    src/main/java/com/yunfang/entitycapture/{voxel,mcvox,texture,capture}/...
  fabric-1.21/               # Fabric 入口 / mixin / fabric.mod.json
  neoforge-1.21/             # NeoForge 入口 / mixin / neoforge.mods.toml
  common-1.20/               # 1.20.1 的共享核心（与 common-1.21 约 90% 相同）
  forge-1.20/                # Forge 入口 / mixin / mods.toml（Java 17）
  .github/workflows/release.yml
  HANDOFF.md  PLAN.md  README.md  LICENSE
```

`common-1.21` 里的代码只依赖 `net.minecraft.*`（官方映射），**不依赖任何加载器**，
因此能同时被 Loom 与 NeoGradle 编译。

## 5. 代码清单

**A. 与加载器/版本无关，可原样复用（核心）**
- `voxel/VoxelGrid.java`、`voxel/Voxelizer.java`（含密度自适应超采样、`(layer<<6)|facePriority` rank、镜像"同级不覆盖"合并）
- `mcvox/McvoxHeader.java`、`mcvox/McvoxWriter.java`
- `capture/Vertex.java`、`capture/Quad.java`（Quad 带 `layer`）、`capture/CapturingVertexConsumer.java`、`capture/CapturingMultiBufferSource.java`
- `texture/TextureImage.java`、`texture/TextureSampler.java`（用 `Minecraft.getResourceManager()` + `NativeImage`，三加载器都有）

**B. 与 MC 版本相关（需少量适配）**
- `capture/RenderCaptureService.java`、`capture/CapturePose.java`
- `capture/RenderTypeTextures.java` + 三个 accessor mixin
- `mixin/AnimationUtilsMixin.java`
- `gui/EntityPickerScreen.java`（GUI API 有差异，见 §6）

**C. 与加载器强相关（每个加载器各写一份）**
- `EntityCaptureClient.java`（入口 + 键位 + tick 循环）
- `command/CaptureCommand.java`（客户端命令注册）
- 资源元数据、mixin 配置

## 6. 已验证的版本/加载器 API 差异（基于字节码实测）

**1.20.1 ↔ 1.21.1**
- ✅ **一致，可直接移植**：
  - accessor 目标：`RenderType$CompositeRenderType.state`（字段）、`CompositeState.textureState`（字段）、
    `EmptyTextureStateShard.cutoutTexture()`（方法）；
  - `AnimationUtils.animateZombieArms(ModelPart, ModelPart, boolean, float, float)`；
  - `EntityType.getBaseClass()` 两版都是**桩**（恒返回 `Entity`）→ GUI 判"是否生物"必须用 `instanceof Mob`（现实现已如此）；
  - `EntityRenderDispatcher.render(...)`；`LightTexture.FULL_BRIGHT`；`NativeImage.getPixelRGBA`；
  - `GuiGraphics`: `bufferSource()/flush()/enableScissor()/disableScissor()/drawString()/drawCenteredString()/pose()`；
  - `Screen`: `render/addRenderableWidget/isPauseScreen/setInitialFocus/onClose`；`EditBox` 构造；`Button.builder`。
- ⚠️ **不同，需分版本处理**：
  - `ObjectSelectionList` 构造：1.21.1 = **5 参** `(Minecraft,w,h,y,itemHeight)`；1.20.1 = **6 参**（多一个 int）。
  - `Checkbox`：1.21.1 用 `Checkbox.builder(...)`；1.20.1 **无 builder**，只有构造
    `(x,y,w,h,Component,boolean[,boolean])`。
- 结论：**accessor/AnimationUtils mixin 基本可直接移植**（只换 refmap）；GUI 的
  `EntityPickerScreen` 需要为 1.20.1 改这两处（可能用不同源文件或很小的分支）。

**加载器注册差异**
- 入口：Fabric `ClientModInitializer#onInitializeClient`；NeoForge `@Mod` + 事件总线；
  Forge 1.20.1 `@Mod` + `FMLJavaModLoadingContext`。
- 键位：Fabric `KeyBindingHelper.registerKeyBinding`；NeoForge/Forge `RegisterKeyMappingsEvent`。
- 客户端命令：Fabric `ClientCommandRegistrationCallback`；NeoForge/Forge `RegisterClientCommandsEvent`。
- tick：Fabric `ClientTickEvents.END_CLIENT_TICK`；NeoForge `ClientTickEvent.Post`；
  Forge 1.20.1 `TickEvent.ClientTickEvent`（END 阶段）。
- mixin：`compatibilityLevel` 1.21.1 用 `JAVA_21`，**Forge 1.20.1 用 `JAVA_17`**；refmap 名不同
  （Fabric intermediary vs Forge SRG/NeoForm）。

## 7. 构建与工具链要点

- 每个子项目各自设 Java toolchain：`21`（1.21.1）/ `17`（1.20.1）。
- Forge 1.20.1：ForgeGradle 6 + `official`（Mojang）+ 可选 Parchment。
- NeoForge 1.21.1：NeoGradle + `official`。
- 现有 wrapper 是 Gradle 8.8；**确认三套插件都能在所选 Gradle 上工作**（必要时统一 Gradle 版本）。
- 保留 `.gitattributes`（LF / `gradlew`）。

## 8. 发布（每个加载器独立 tag）

- tag 规范建议：`fabric-vX.Y.Z`、`neoforge-vX.Y.Z`、`forge-vX.Y.Z`。
  现有 `v0.2.0` 视为 Fabric 的历史 tag，可保留。
- `release.yml` 改为按 tag 前缀选择目标构建（矩阵 + 按目标切 JDK 21/17），上传对应 jar：
  `entity-capture-<version>-fabric.jar` / `-neoforge.jar` / `-forge.jar`。
- README 增补"支持加载器/版本"表；保留与 MC Block Studio 的互链。

## 9. 验收标准（每个目标）

1. `runClient` 能启动，无 mixin 注入失败。
2. `H` 打开实体选择器：搜索 / 分类筛选 / 多选批量可用；`/capturemob`（含补全）与 `G` 可用。
3. 捕获出的 `.mcvox` 能被 MC Block Studio 正常导入（occupancy / 颜色 / 眼白 / 覆盖层正确）。
4. 三加载器对同一实体的 `.mcvox` 结果一致（尺寸 / 体素数 / 颜色）。

## 10. 新对话怎么开

- **对话 1（NeoForge 1.21.1）**：读本文件后，先重构为方案 B 的 Gradle 多子项目
  （`common-1.21` + `fabric-1.21` + `neoforge-1.21`），保证 Fabric 仍能构建运行；再接 NeoForge 入口/注册/mixin。
- **对话 2（Forge 1.20.1）**：新增 `common-1.20` + `forge-1.20`（Java 17），处理 GUI 差异、mixin refmap、元数据。

## 11. 首轮（NeoForge）建议步骤

1. 建 Gradle 多子项目骨架：根 `settings.gradle` 声明 `common-1.21` / `fabric-1.21` / `neoforge-1.21`。
2. 把现有 `src/main/java` 中 **A 类核心**移入 `common-1.21`；Fabric 专属（入口/命令/元数据/mixin 配置）移入 `fabric-1.21`。
3. 验 Fabric 子项目仍能 `./gradlew :fabric-1.21:build` + `runClient`。
4. 新建 `neoforge-1.21`：`neoforge.mods.toml`、`@Mod` 入口、`RegisterKeyMappingsEvent`、
   `RegisterClientCommandsEvent`、`ClientTickEvent.Post`；复用同一套 mixin。
5. NeoForge `runClient` 验收，捕获若干实体并导入 studio 对比。

## 12. 参考

- 本仓库 `PLAN.md`（对称性/取色/layer/超采样/GUI/内置库的全部结论与踩坑）。
- Fabric 官方模板、NeoForge `ModDevGradle`/NeoGradle、ForgeGradle 6 文档。
- 多加载器思路参考：`FabricMC/fabric-example-mod`、`neoforged/ModDevGradle`、
  `Jaredlll08/MultiLoader-Template`（common/fabric/forge/neoforge 同版本共享结构）。
