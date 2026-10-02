# Entity Capture — 生物 → 3D 方块像素画

一个 Fabric 客户端 mod，把 Minecraft 里的**任意实体**（含模组生物）在 1:1 原生分辨率下
转成带颜色的体素捕获包 `.mcvox`；再由 `mc-block-studio` 把它放大、选空心/填充、匹配方块，
导出 `.litematic` 投影。

## 分工

| 侧 | 负责 | 不负责 |
|---|---|---|
| **mod**（本仓库 `capture/`） | Minecraft 域：模型 → 四边形采集 → 占用 + 表面色 → `.mcvox` | 放大、空心、方块匹配、litematic |
| **studio**（`../mc-block-studio`） | 调色板域：导入 `.mcvox` → 放大 N 倍 → 空心/填充 → 颜色匹配方块 → litematic | 模型 / UV / 动画 |

两个项目**只通过 `.mcvox` 一份格式耦合**，可独立开发、独立测试。

## 已锁定决策

- 平台：Fabric **1.21.1**，客户端，Java 21 + Fabric API（`environment: client`）。
- 中间体：`.mcvox`，**1:1 原生体素**（1 体素 = 1 模型像素）。
- 放大 / 空心等呈现选择全部在 studio 完成。
- studio 默认：放大 **2×**、**空心**。
- v1 捕获范围：含覆盖层（羊的毛、马的花纹等），**不含装备 / 手持物**。
- mod 输出：游戏根目录 `entity-capture/`，文件名 `<实体id>_<时间戳>.mcvox`。
- 实体入口：GUI 选择器（主）/ `/capturemob` 指令 / 键位捕获准星实体（带 NBT）。
- 捕获技术：自定义 `VertexConsumer` 拦截 `renderer.render(...)` 输出的四边形，
  从 `ResourceManager` 读原始贴图按 UV 采色（无光照污染）。

## `.mcvox` 规范 v1（provisional）

单文件自定义二进制容器，两边都无需 zip 依赖：

```
[0..3]   magic "MCVX" (0x4D 0x43 0x56 0x58)
[4]      uint8  formatVersion = 1
[5]      uint8  flags (bit0 = 负载 zlib/deflate 压缩)
[6..7]   uint16 reserved
[8..11]  uint32 headerLength (LE)
[12..]   header JSON (UTF-8)
然后     occupancy: ceil(n / 8) 字节（位图，LSB 优先，n = sizeX*sizeY*sizeZ）
然后     colors:    n * 4 字节 RGBA
```

`header.json`：

```json
{ "formatVersion": 1, "entityId": "minecraft:zombie", "entityName": "Zombie",
  "mcVersion": "1.21.1", "modLoader": "fabric", "unitsPerBlock": 16,
  "dimensions": [16, 32, 16], "solid": true, "animatedTick": 0, "generatedAt": "..." }
```

- 坐标：`+Y` 上、`+Z` 南、`+X` 东，原点为模型 AABB 最小角；
  体素索引 `x + z*sizeX + y*sizeX*sizeZ`，与 studio `VoxelModel` 完全一致。
- `occupancy` 位 = 1 表示模型占据该空间格子。
- `colors`：**表面体素**存贴图原色（无光照，A>0）；内部体素与空体素全为 `0,0,0,0`。
- 体积参考：僵尸约 16×32×16 → 占用约 1 KB、颜色 32 KB。

> 规范标为 provisional：路线验证后可能微调，尤其「内部占用」的表达。

## mod 侧捕获管线

1. `EntityPickerScreen` 列出 `BuiltInRegistries.ENTITY_TYPE`（含模组，可搜索）；
   或 `/capturemob`、键位捕获准星实体（带完整 NBT / 变体）。
2. 构造实体实例，设固定姿态与 `tickDelta`。
3. `QuadCollector`（自定义 `VertexConsumerProvider`）拦截 `renderer.render(...)`，
   按 `RenderLayer` 收集 `(位置, UV, 贴图 id)` 四边形。
4. `TextureSampler` 从 `ResourceManager` 读该层贴图 PNG，按 UV 采样原色。
5. `Voxelizer` 光栅化四边形到原生网格 → 生成 `occupancy` + 表面 `colors`。
6. `McvoxWriter` 写出到 `entity-capture/`。

## 待验证的技术缺口（spike 优先）

- **采四边形只给表面，给不出内部占用。** 内部判定需「外部泛洪填充」，
  表面有缝隙时会漏水。默认**空心**正好绕过；只有「填充/实心」才受影响。
- **备选路线**（若四边形捕获不成立）：
  1. 遍历 `ModelPart.Cube` 光栅化实体盒子 → 占用精确，但覆盖面窄（丢覆盖层/非模型渲染器）；
  2. 多视图截图视觉外壳 → 最通用但精度最低。
- 三条路线产出都是同一种体素网格，**格式不变**——这正是把 `.mcvox` 定成
  「通用网格」而非「生物模型」的价值。
- 捕获必须在渲染线程、正确渲染上下文内进行，涉及 mixin / accessor，尚未实跑验证。

## studio 侧改动（已在 `../mc-block-studio` 完成 M0）

- `src/lib/voxel/mcvox.ts`：`.mcvox` 解析 / 编码。
- `src/lib/voxel/from-capture.ts`：原生体素 → 颜色匹配 → 按倍率 N³ 展开 → 空心/填充 → `VoxelModel`。
- `src/state/store.ts`：新增 `mob` 模式与捕获状态。
- `src/state/useGeneration.tsx`：`mob` 生成分支。
- `src/components/ImportMobTab.tsx` + `App.tsx` 第三个标签页。
- 3D 预览 / 材质统计 / litematic 导出**零改动复用**。

## 里程碑与验收

- **M0** ✅ 定 `.mcvox` 规范 + studio mob 模式 / 导入器。验收：导入 → 3D 预览 → 导出 `.litematic`。
- **M-1（spike）** ✅（代码）最小 Fabric mod，捕获僵尸管线（四边形 + UV + 原色 → `.mcvox`）；待游戏内实跑验证。
- **M1** mod 骨架 + 实体选择器 + 僵尸端到端。
- **M2** 覆盖层（羊的毛、马的花纹）。
- **M3** 一个模组生物端到端。
- **M4** 非标准渲染器实体（船 / 画）；失败则降级 2D 截图。
- **M5（可选）** studio 导出调色板 JSON → mod 游戏内直接摆放 / 出 litematic。

## 风险

- **版本**：渲染 / 顶点 API 随 MC 版本变动，锁 1.21.1，用最小 mixin/accessor。
- **特殊渲染器 / 着色器**异常 → 降级 2D。
- **高清贴图模组生物**：按实际贴图分辨率采样，天然支持。
- **大模型（末影龙等）**：studio 设体积上限并提示。
- **版权**：捕获包含实体贴图，仅本地使用，不随发行版分发。

## 进度记录

### 2026-10-01 — 工程脚手架 + 工具链决策

**已完成（仓库骨架，位于 `capture/`）：**

- Gradle wrapper 已放入（`gradlew` / `gradlew.bat` / `gradle/wrapper/*`），取自官方
  `FabricMC/fabric-example-mod@1.21.1`。
- 元数据确认：mod id `entity-capture`，包名 `com.yunfang.entitycapture`，
  显示名 `Entity Capture`。
- 已写文件：
  - `settings.gradle`（`rootProject.name = 'entity-capture'`）
  - `gradle.properties`（MC `1.21.1` / loader `0.19.5` / Fabric API `0.116.17+1.21.1`）
  - `build.gradle`
  - `src/main/resources/fabric.mod.json`（`environment: client`，client entrypoint）
  - `src/main/java/com/yunfang/entitycapture/EntityCaptureClient.java`（空壳 `ClientModInitializer`）

**踩坑与决策：**

- 官方新模板当前把 Loom 固定为 `1.18-SNAPSHOT`（解析到 `fabric-loom 1.18.2`），
  其 Gradle module metadata 声明 `org.gradle.jvm.version = 25` → **需要 JDK 25 + Gradle 9.7**。
  本机 `JAVA_HOME` 与 PATH 上的 Java 都是 **21**（Minecraft 自带 `java-runtime-delta`），无 25。
- Gradle 发行包从 `services.gradle.org` 下载时 TLS 报 `decode_error`；
  改用**腾讯镜像** `https://mirrors.cloud.tencent.com/gradle/` 下载成功。
- **决策：路线 B —— 回退到 1.21.1 经典工具链**（无需安装任何东西）：
  - Gradle **8.8**（本机 `~/.gradle/wrapper/dists/gradle-8.8-bin` 已有完整缓存 + `.ok`）
  - Loom **1.7.4**（插件 id 用 `fabric-loom`，要求 Gradle 8.8 + Java 21）
  - MC 1.21.1 / Mojang 官方映射 / Fabric API 不变

### 2026-10-01 — 路线 B 落盘 + 空构建 + M-1 spike 实现

**工具链（✅ 已落盘并验证）：**

1. `gradle/wrapper/gradle-wrapper.properties`：`distributionUrl` → `gradle-8.8-bin.zip`（腾讯镜像）。
2. `gradle.properties`：`loom_version` → `1.7.4`。
3. `build.gradle`：插件 id `net.fabricmc.fabric-loom-remap` → `fabric-loom`。

- **空构建 ✅ 通过**（`gradlew build`，JDK 21 / Gradle 8.8 / Loom 1.7.4）。
- 踩坑：本机访问 `maven.fabricmc.net` 时 Gradle(Java) 的 TLS 会被 Cloudflare 偶发重置
  （“Remote host terminated the handshake”）。curl / 浏览器 / 裸 Java 均正常，属并发下载限流。
  解决：重试即可，Gradle 缓存会累积；后续 `compileJava` 增量编译约 20 秒。

**M-1 spike ✅ 代码落盘并编译通过（尚未实跑游戏）：**

- `mixin/`：`CompositeRenderTypeAccessor`（`RenderType$CompositeRenderType.state`）、
  `CompositeStateAccessor`（`textureState`）、`EmptyTextureStateShardAccessor`（`cutoutTexture`）。
  `entity-capture.mixins.json` + `fabric.mod.json` 已挂载；Loom 生成的
  `entity-capture-refmap.json` 已核对，三条映射均指向正确的 intermediary 名。
- `capture/`：`Vertex` / `Quad` / `CapturingVertexConsumer`（四边形收集）/
  `CapturingMultiBufferSource` / `RenderTypeTextures`（从 RenderType 取贴图）/
  `RenderCaptureService`（姿态 → `EntityRenderDispatcher.render` → 体素化 → 写出）/`CaptureManager`。
- `texture/`：`TextureSampler`（经 `ResourceManager` 读 PNG，`NativeImage` ABGR→ARGB，按 UV 采原色）。
- `voxel/`：`VoxelGrid`（占用位图 + RGBA 表面）/ `Voxelizer`（四边形按主轴投影 + 重心插值光栅化）。
- `mcvox/`：`McvoxHeader` / `McvoxWriter`（格式 v1，默认 zlib 压缩，`Deflater`）。
- 入口：`/capturemob [entity]` 与键位 `G`（默认）；实体在客户端 tick（即渲染线程）处理。

**端到端格式验证 ✅：** 用 mod 的 `McvoxWriter` 生成压缩 / 未压缩两份 `.mcvox`，
交给 studio 的 `parseMcvox` 解析，占用位 + 表面色断言全部通过（临时测试已删）。

**已知未验证 / 待办：**

- 从未在游戏内实跑；姿态朝向（`+Z` 南 / 屏幕左右镜像）需实跑后微调。
- `/capturemob`（无参）与键位当前**新建同类型新实体**捕获，避免改动准星活体姿态；
  带变体 / NBT 的捕获留到 M1/M2。覆盖层（羊毛、马纹）路径已具备（每 RenderType 单独取贴图），待 M2 验证。
- 内部占用仍只来自表面四边形（与 PLAN 的“外部泛洪填充”缺口一致），默认空心不受影响。
- `EntityRenderer` 若走特殊渲染路径（`RenderSystem`、手持物、火焰）需在实跑时确认；M-1 以僵尸为准。

**下一步：** 游戏内实跑 `/capturemob minecraft:zombie` → 确认输出 `.mcvox` →
studio `mob` 模式导入预览 → 校正朝向 → 进入 M1（实体选择器 GUI）。

### 2026-10-01 — 实跑前诊断 + 资源预下载

- `CapturingMultiBufferSource` / `RenderTypeTextures` / `RenderCaptureService` 增加诊断：
  四边形总数、按贴图的四边形计数、无法解析贴图的 RenderType、顶点包围盒、网格尺寸、体素数、耗时。
- 四边形 dump：设环境变量 `ENTITYCAPTURE_DUMP_QUADS=true` 后，输出旁会写 `*.quads.txt`（前 200 个四边形的 pos/uv/rgba/贴图）。
- `gradlew downloadAssets` ✅ 成功，首次 `runClient` 不必再等整包资源下载。

### 2026-10-01 — 实跑发现问题 + Voxelizer 对称性修复

实跑 10 个生物后由镜像差异量化确认两类 `Voxelizer` bug：

1. **平面→体素映射未考虑面朝向**：`max` 面比 `min` 面多占一层 → 偶数网格必不对称（猪/铁傀儡）。
2. **网格原点取全局 AABB 最小值，可能是分数像素**（抬臂/旋转部件）→ 体素中心错位，丢失共享棱与眼睛（僵尸棱、羊右眼）。

修复（仅 `Voxelizer`）：

- **原点与末端都吸附到最近整数像素**：`originPx = round(minPx)`、`endPx = round(maxPx)`，
  `size = endPx - originPx`；由于对称生物中轴恒在渲染 `x=0`，取整后中轴落在体素边界 → 天然对称。
- **面平面按外法线做半体素内移**：`ic = floor((pc-origin)*scale - 0.5*sign(nDominant))`，
  使 `max` 面落进盒内层、`min` 面落在边界层，相邻面共享棱/角都被覆盖。

离线验证（node 复刻算法 + 合成盒子）：整数原点、对称分数原点两种情形镜像差异均 **0**
（修复前分别为 212 / 40）；不对称模型不受影响。

**下一步：** 带 `ENTITYCAPTURE_DUMP_QUADS=true` 复跑同一组生物，用镜像分析器复测差异→0，
并据 `.quads.txt` 离线复核法线方向。

### 2026-10-01 — 对称性修复第二轮（法线/内移规则/镜像回填）

实跑复核后进一步定位（`iron_golem`/`pig` 已归零，`sheep`/`zombie` 残留）：

- 模型自带法线与叉积**完全一致**（72/72），`mirror` 不是元凶。
- 真正的规则错误：半体素内移对 **min 侧也用 `+0.5`**，仅在整数像素坐标下恰好正确；
  羊/铁傀儡的面落在分数像素，min 侧多推一格。
  正确规则：**max 侧 `floor(off-0.5)`、min 侧 `floor(off)`**；
  网格用 **`floor(min*scale)` / `ceil(max*scale)`**（带 epsilon），合成验证整数/半整数/任意分数均对称。
- 剩余 `sheep`/`zombie` 残差来自**亚像素重叠（僵尸两腿中心重叠 0.2px）与倾斜手臂**，
  以及**透明像素导致单侧丢体素**。
- 新增：`Voxelizer` 检测四边形是否关于 `x=0` 镜像对称（质心容差 0.02 blocks），
  对称则 `VoxelGrid.enforceXMirrorSymmetry()` 对镜像体素对取**并集**（只补不删）。
- 诊断 dump 精度提升为 `Float.toString`（可精确离线复现）。

### 2026-10-01 — 对称性收尾 + 眼睛（棱上深色像素）保留

镜像差异已全部归零，但猪/羊**眼睛消失**。用本地 `minecraft-client.jar` 取真实贴图 + 高精度
quads 离线复现定位：

- `pig.png` 只有 2 个深色像素 `(8,11)/(15,11)`，正是头正面 UV `u∈[8,16]` 的**最左/最右列（棱上）**。
- 棱上体素被相邻侧面（肤色）后写入覆盖；而并集回填又“偏好 +x 侧颜色”，把唯一残留的那只眼也抹了。

修复（`VoxelGrid`）：**较暗者胜**——写入体素时保留亮度更低的颜色；镜像合并时同样取较暗一侧。
这样棱上的深色特征（眼睛/嘴）不会被亮面覆盖，也不会被镜像抹掉。

离线回归（真实贴图 + 并集）：pig / sheep / zombie / golem 的 **占用与颜色镜像差异均 0**，
深色体素数分别为 2 / 42 / 94 / 86（眼睛已恢复）。

### 2026-10-01 — 取色规则改为「正面优先 + 暗色兜底」

应用户要求，把「最暗获胜」升级为更可预测的朝向规则：

- 每个面由模型法线算 `priority = pz*4 + py*2 + px`，其中
  `pz` 偏向 `+Z` 正面（2/1/0）、`py` 偏向 `+Y` 顶面、`px` 取 `|nx|`。
- `VoxelGrid.set(...)` 新增 `facePriority`：优先级更高者覆盖；**并列**（如羊毛层与皮肤层
  同为 +Z）才用“较暗者胜”；更低者跳过。新增 `byte[] priority` 记录每体素获胜面的朝向。
- 镜像并集 `mergeMirrorPair` 同步改为“先比优先级、并列再比亮度”。
- 离线回归（真实贴图）：pig/sheep 双眼保留，四生物镜像差异仍为 0；
  僵尸/铁傀儡的深色体素由 94/86 降到 82/76（非正面深色不再抢色）。

**下一步：** 复跑四个生物，studio 预览确认双眼、棱、对称均正常 → 进 M1。

---

## 阶段性存档（2026-10-02）

### 当前状态

- **M0 ✅**：`.mcvox` v1 规范 + studio `mob` 模式/导入器（`../mc-block-studio`）。
- **M-1 spike ✅ 游戏内跑通**：`/capturemob [entity]` 与键位 `G` 均可用；
  已实跑僵尸、史莱姆、羊、山羊、村民、鸡、猪、铁傀儡、恶魂等 10+ 生物，
  输出 `.mcvox` 并能被 studio 的 `parseMcvox` / 3D 预览读取。
- 捕获成功率良好：`quads` 完整、`nullTex=0`、贴图解析正确（僵尸 42、羊 72、铁傀儡 48…）。

> ⚠️ **重要**：对称性/取色的最后一版（“正面优先 + 暗色兜底”，见下）**已构建通过、
> 离线验证通过，但尚未在游戏内复跑确认**。最后一次游戏内验证的构建是上一版
> “floor/ceil + 内移规则 + 镜像并集(+x 优先)”，其结果为四个生物镜像差异 0、但猪/羊眼睛丢失。

### 关键 bug 与修复（按发现顺序）

1. **工具链 TLS 限流**：Gradle(Java) 拉 `maven.fabricmc.net` 偶发 `Remote host terminated the
   handshake`；curl/浏览器正常。对策：重试，缓存累积。空构建与后续增量构建均通过。
2. **Voxelizer 平面→体素映射未考虑面朝向**：`max` 面多占一层 → 偶数网格必不对称。
3. **网格原点吸附**：一度用 `round/ceil`，在分数几何下会切掉/多留像素。
   最终采用 **`floor(min*scale)` / `ceil(max*scale)`（epsilon 吸收整数抖动）**。
4. **半体素内移规则**：正确为 **max 面 `floor(off-0.5)`、min 面 `floor(off)`**；
   旧的对 min 面 `+0.5` 仅在整数像素几何下成立。
5. **亚像素残差**（僵尸两腿中心重叠 0.2px、倾斜手臂、透明像素单侧丢体素）：
   新增**左右镜像对称检测**（四边形质心、容差 0.02）+ `VoxelGrid.enforceXMirrorSymmetry()`
   对镜像体素对取**并集**（只补不删），保证对称。
6. **眼睛消失（猪/羊）**：真实贴图 `pig.png` 仅 2 个深色像素，位于头正面 UV 的**最左/最右列（棱上）**，
   被相邻侧面覆盖。修复演进：
   - 先改 `set()`/镜像合并为**“较暗者胜”**（保留深色特征）；
   - 应用户要求升级为 **“正面优先 + 暗色兜底”**：
     每面由法线算 `priority = pz*4 + py*2 + px`（`+Z` 正面最高，其次 `+Y`，再 `|nx|`）；
     `VoxelGrid.set(...)` 按优先级覆盖，并列再比亮度；`byte[] priority` 记录每体素获胜面朝向；
     镜像合并同理。

### 关于僵尸手臂“倾斜”

- **是 vanilla 默认姿态，不是 bug**。`AbstractZombieModel.setupAnim` → 
  `AnimationUtils.animateZombieArms`：`xRot=-π/2.25≈-80°`（前抬）、`yRot=±0.1`（外撇），
  再叠加 `bobArms` 待机摆动（`zRot/xRot` 各 ±~0.05–0.1 rad）。
- 实测捕获中 42 个四边形有 **12 个非轴对齐**（两条手臂 ×6 面），其余轴对齐。
- ✅ **已修复**（2026-10-02，见文末“僵尸系中性姿态”）：加 mixin 取消
  `animateZombieArms`，捕获时手臂改为**水平向前、轴对齐**（同类僵尸系一并覆盖）。

### 关键文件

- 入口/命令：`EntityCaptureClient`、`command/CaptureCommand`、`capture/CaptureManager`
- 捕获管线：`capture/RenderCaptureService`、`CapturingMultiBufferSource`、`CapturingVertexConsumer`、
  `RenderTypeTextures`（+ `mixin/*Accessor`）、`texture/TextureSampler`
- 体素化：`voxel/Voxelizer`、`voxel/VoxelGrid`（占用位图 + RGBA + priority）
- 输出：`mcvox/McvoxWriter`、`mcvox/McvoxHeader`
- 诊断：每次捕获旁写 `*.mcvox.quads.txt`（精确浮点 + 法线）

### 如何继续（命令备忘）

```powershell
# 客户端实跑（自动编译）
cd D:\Coding\minecraft-pixel-art-generator\capture
.\gradlew.bat runClient
# 输出目录： capture\run\entity-capture\   （.mcvox 与 .quads.txt）
# 日志：     capture\run\logs\latest.log

# studio 预览
cd D:\Coding\minecraft-pixel-art-generator\mc-block-studio
pnpm dev            # 拖入 .mcvox 到 Mob 标签页
```

### 待办

1. **游戏内复跑** 确认“正面优先 + 暗色兜底”版：猪/羊双眼、四生物镜像差异 0、棱完整。
2. **M1**：`EntityPickerScreen` ✅ 已实现（见文末）；`/capturemob <entity>` 补全 ✅ 已加；
   剩余：键位捕获改为带 NBT/变体（`saveWithoutId` + `EntityType.create(tag, level)`）与 GUI 实跑确认。
3. **M2**：覆盖层（羊的毛、马的花纹）——管线已具备（按 RenderType 分别取贴图），实跑顺带验证。
4. 可选：大模型上限提示；i18n 文案补全。
5. 注意：`run/entity-capture/` 里是大量测试产物，可随时清理。

---

### 2026-10-02 — 僵尸系中性姿态（手臂水平向前、轴对齐）

用户反馈僵尸默认攻击姿态导致手臂永远倾斜。实跑 + `javap` 定位到**单一汇集点**：

- 所有僵尸系手臂最终都走
  `AnimationUtils.animateZombieArms(leftArm, rightArm, aggressive, attackTime, ageInTicks)`；
  无论是否 aggressive 都会把 `xRot` 设为 `-π/1.5`（攻击，≈-120°）或 `-π/2.25`（≈-80°），
  再叠加 `bobArms` 待机摇摆 —— 所以既倾斜又摆。
- 调用方（均已在字节码确认）：
  - `AbstractZombieModel` → 僵尸 / 尸壳 / 溺尸 / 巨人；
  - `ZombieVillagerModel`（**直接继承 `HumanoidModel`**，不继承 `AbstractZombieModel`）；
  - `PiglinModel`（**仅 `type == ZOMBIFIED_PIGLIN`** 时调）。
  - 骷髅系（`SkeletonModel`）是另一套**内联**挥臂逻辑，只在 `isAggressive()` 时触发；
    普通猪灵、Illager（仅 ATTACKING+空手）均不受影响。

**修复（只动 `capture/`）：**

- 新增 `capture/CapturePose`：捕获期间的 `neutralPose` 开关（正常游戏渲染不受影响）。
- `RenderCaptureService.capture` 的 `try/finally` 置位 / 清除该开关。
- 新增 `mixin/AnimationUtilsMixin`：注入 `animateZombieArms` 的 `HEAD` 并 `cancel`，
  捕获时把两臂设 `xRot=-π/2, yRot=0, zRot=0`（水平向前、轴对齐），顺带去掉摇摆。
- `entity-capture.mixins.json` 注册 `AnimationUtilsMixin`。

**实跑验证（2026-10-02 02:31 UTC）** 捕获 zombie / husk / drowned / zombie_villager / zombified_piglin：

- 手臂四边形全部**水平向前且轴对齐**（如僵尸 #12-23 `x[±0.25,±0.50] y[1.25,1.50] z[-0.12,0.62]`，法线纯轴）。
- 最大残余倾斜仅 **0.405°**（腿部），与改动前旧 dump 完全一致 → 属既有、可忽略。
- 僵尸猪灵的 **34.55°** 是**耳朵**（模型固有旋转），非手臂，无需处理。
- 日志无 mixin / 异常报错。
- 巨人未实跑，但 `GiantZombieModel` 同样走 `animateZombieArms`，同一 mixin 覆盖。

**未做：** 骷髅系中性姿态（默认不触发，暂不需要）；巨人实跑确认（可选）。

---

### 2026-10-02 — M1 实体选择器 GUI

主入口从"只有命令/键位"升级为可视化选择器。全部为 vanilla `Screen`，无第三方 UI 依赖。

**新增 / 改动：**

- 新增 `gui/EntityPickerScreen`：搜索框（名称 + `namespace:id`，忽略大小写）、
  可滚动 `ObjectSelectionList`、右侧 3D 预览面板 + 元数据、`[捕获] [显示非生物] [取消]`。
  - 数据源 `BuiltInRegistries.ENTITY_TYPE.stream()`，按本地化名排序；默认隐藏 `MobCategory.MISC`。
  - 选中即预览：`EntityRenderer` 全亮渲染、缓慢自转、按 `bbHeight/bbWidth` 自适应缩放；
    预览创建/渲染均 `try/catch` 兜底（失败显示"预览失败"，不崩屏）。
  - 交互：单击选中、双击 / 回车 / `[捕获]` 触发；`CaptureManager.requestType(type)` 后关屏。
  - 宽度 `< 520` 或非世界场景时自动隐藏预览，列表占满。
- `EntityCaptureClient`：新增键位 `H`（`key.entity-capture.picker`）打开选择器；
  `G`（捕获准星）保留。
- `command/CaptureCommand`：`/capturemob <entity>` 增加 tab 补全
  （`SharedSuggestionProvider.suggestResource(ENTITY_TYPE.keySet(), builder)`）。
- `lang/zh_cn|en_us`：补充 GUI 全部文案。

**状态：** `gradlew build` ✅ 通过。**尚未在游戏内实跑**——需确认列表渲染、预览朝向/缩放、
搜索过滤、点击捕获链路与关屏后的反馈。

**未做：** 行内实体小图标（当前纯文字）；按分类/来源筛选（当前仅"显示非生物"开关）；
键位捕获带 NBT/变体（仍留 M1 收尾）。

---

### 2026-10-02 — 选择器「多选」模式

应用户要求，在选择器里加手动多选，替代"一键自动全捕"。

- `EntityPickerScreen` 新增 `[多选]` 开关：
  - 开启后每行左侧出现复选框，**单击 = 勾选/取消**（非 `Mob` 的实体不可勾）；
  - `[全选]` = 勾选**当前筛选后可见**的行（与搜索联动）；`[全不选]` = 清空；
  - 主按钮变为 `[捕获已选]`；多选模式下**禁用单捕**（双击/回车都走批量）；
  - 勾选在切换搜索/过滤时**保留**，关屏/退出多选时清空；底栏显示 `已选 N`。
- `CaptureManager` 新增批量队列 `requestBatch(...)`：单发请求优先，之后**每 client tick 处理 1 个**，
  逐项 `try/catch` 计数并 actionbar 报进度，队列清空后发汇总 `批量捕获完成：成功 X / 失败 Y`；
  `level == null` 时中止并提示。
- `lang/zh_cn|en_us`：新增 `multi / multi_on / select_all / deselect_all / capture_selected / selected_count`。

**状态：** `gradlew build` ✅ 通过，**已游戏内实跑并确认**（见下方"实跑确认"）。

**实跑（2026-10-02 03:56 UTC）：** 多选 `[全选]` → `[捕获已选]` 成功批量捕获 **79 只**，
`批量捕获完成：成功 79 / 失败 0`，逐 tick 平滑无卡顿。多选核心功能 ✅。

**Bug 修复 ①（同日）：** 多选复选框点击/全选全部无效。根因：`isCheckable` 用了
`EntityType.getBaseClass()`，而 1.21.1 该方法是**桩**（`javap` 确认恒返回 `Entity.class`），
`Mob.class.isAssignableFrom(Entity.class)` 恒 false。改为**实例化一次 + `instanceof Mob`**
并按类型缓存（`checkableCache`，`init()` 清空）。非生物行在多选下**不画复选框**（避免"能点却没反应"的误导）。

**Bug 修复 ②（同日）：** 默认只捕到 **79** 而非 82，少了 `villager` / `iron_golem` / `snow_golem`。
根因：列表"隐藏非生物"用的是 `category == MISC`，而这 3 个**是 `Mob` 但 category 恰为 MISC**，
被误隐藏。改用统一的 `isMob(type)`（`category != MISC` 直接为真，MISC 才实例化判 `instanceof Mob`）
作为"是否生物/是否可勾/是否默认显示"的唯一判据 → 默认列表正好 82 个生物，`[显示非生物]` 才列全部 130。

**实跑确认（2026-10-02 04:02 UTC）：** `[全选]` → `[捕获已选]` 批量捕获 **82** 只，
日志 `批量捕获完成：成功 82 / 失败 0`；离线核对落盘的 distinct 生物 id = 82，
与"原版 82 生物"清单**完全一致（无缺失、无多余）**。多选 + 批量 ✅ 完成。

---

### 2026-10-02 — studio 内置 82 生物库（`mc-block-studio`）

把 82 份 `.mcvox` 作为**默认内置**进 studio，免去拖文件。

- 资源：`public/mobs/<id>.mcvox`（82 个，共 ~308 KB，最大末影龙 85 KB）。
- 清单：`src/lib/voxel/builtin-mobs.ts`（`BUILTIN_MOBS: { id, name, category, file }[]`
  + `mobUrl(file)`），名称取 capture 头里的中文 `entityName`。
- 生成脚本：`scripts/build-mob-library.mjs`（`pnpm build:mobs -- "<capture 输出目录>"`），
  **源目录由参数传入**（两仓库不硬编码耦合）；按 id 取最新一份并重写清单。
- UI：`ImportMobTab` 改造为单一"导入生物"标签，内部分段 `[内置生物 | 上传文件]`：
  内置视图 = 搜索 + 分类筛选（全部/被动/水生/敌对）+ 可滚动列表；上传视图 = 原拖拽区；
  下方缩放/空心填充/尺寸信息两者共用。**不新增第 4 标签、不改 `Mode`、`PreviewPanel` 不动**。
- 验证：`pnpm typecheck` ✅、`pnpm test`（80 passed）✅、`pnpm build` ✅（`dist/mobs` 含 82）。
  待用户 `pnpm dev` 实点确认。

---

### 2026-10-02 — 眼白丢失修复 + 外层优先（layer 参与取色）

**现象：** 内置库里豹猫眼白、悦灵白色眼睛不见（studio 3D 预览里被毛色/瞳色覆盖）。

**根因（真实贴图 + quads 离线核对）：** 奇数宽模型（豹猫/悦灵头正面各 5 像素）在
`floor(min)` 网格原点下被铺成 **6 格**，导致同一正面上的眼白/毛色像素**错位配成镜像对**；
`mergeMirrorPair` 同级"较暗者胜"再把亮的眼白刷成暗色 → 双眼同时消失。
（`set()` 阶段没问题：正面 `+Z` 优先级最高，眼白已写入。）

**修复（`capture/`）：**

- `VoxelGrid.mergeMirrorPair`：**同级（rank 相同）不再覆盖，两侧各自保留颜色**；
  仅当一侧无色时才补色；占用仍取并集。不同 rank 仍取高者。
- **外层优先（layer）**：`Quad` 增加 `layer`（渲染顺序，0=基础模型、越大越外层）；
  `CapturingMultiBufferSource` 按首次请求 RenderType 的顺序号分配；
  `VoxelGrid` 用 `(layer << 6) | facePriority` 作为统一 rank——**外层覆盖内层**，
  同层再比面向（正面优先），完全同级才用"较暗者胜"兜底（保住猪/羊等深色特征）。
- 单层生物（豹猫/悦灵/僵尸…）`layer=0`，rank 退化为原面向优先级，行为不变，只有合并规则变为"同级不覆盖"。

**预期：** 豹猫/悦灵双眼回归；羊的毛、溺尸外层、僵尸村民袍子、猪灵衣服等覆盖层颜色更准确地压过内层。

**验证：** `gradlew build` ✅；重捕 82 只 ✅（`distinct=82`）；`pnpm build:mobs` 已刷新内置库 ✅。
离线核对新 `.mcvox`：豹猫白眼体素 **2**、悦灵 **4**（2×2 双眼）、猪 **2**（均恢复正常）；
`pnpm typecheck` ✅。**待 studio 目视终检**（豹猫/悦灵眼白 + 覆盖层生物）。

---

### 2026-10-02 — 密度自适应超采样（修猫的"独眼"）

**现象：** 黑猫眼睛并成中间一条（独眼）。用户怀疑是"强制对称/取消中间格"。

**根因（贴图 vs 体素逐行比对）：** 猫模型渲染约 0.8×，脸只有 **4 渲染像素宽**，而贴图这张脸有
**5 个纹素**（`白(5) 绿(6) 暗(7) 绿(8) 白(9)`）；5→4 压缩时**中间"暗"格被采样跳过**，
两只绿眼并成中间一条。**与对称合并无关**（猫网格宽 4 为偶数，镜像对 (1,2) 两边都绿）。
豹猫是 1:1（5↔5）所以不丢。

**修复（`capture/`，`Voxelizer`）：**

- 栅格化前计算每个四边形边的**纹素密度** `texels/voxel = |Δuv×贴图尺寸| / (|Δxyz|×16)`，取全局最大。
- 密度 `>1` 时整数超采样 `N = ceil(密度)`（`MAX_SUPERSAMPLE=4`），`scale = 16×N`；
  全局体素超 `MAX_SUPERSAMPLE_VOXELS=8_000_000` 时回退 N=1。
- 密度=1 的模型（僵尸/末影龙等）N=1，**行为不变**；只对被缩小的小模型生效。
- 猫 N=2 → 脸 8 格，正面行变为 `[白,白,绿,暗,暗,绿,白,白]`：双眼分开、间隔保留、左右对称。

**验证：** `gradlew build` ✅；重捕 82 只 ✅；离线核对：
- 猫网格 `4×11×29 → 8×21×57`，正面行 = `[白,白,绿,暗,暗,绿,白,白]` —— **双眼分开、中间暗格回归、对称**。
- 超采样按预期只对缩小模型生效：豹猫 `6×14×35→10×26×70`、悦灵 `8×12×13→16×22×25`；
  僵尸/猪/羊尺寸不变，末影龙 `202×66×252`（N=1，未爆）。
- `pnpm build:mobs` 已刷新内置库（studio `cat.mcvox` 8×21×57、绿眼体素 4），`pnpm typecheck` ✅。
**待 studio 目视终检**（猫/豹猫/悦灵，及大模型倍率）。

### 2026-10-02 — v0.2.1：诊断转储改为按需（默认关闭）

**现象：** 每次捕获在 `.mcvox` 之外还多写一份 `<名字>.mcvox.quads.txt`。
**根因：** `RenderCaptureService.capture` 里 `dumpQuads(...)` 被**无条件**调用；
PLAN 早先提到的 `ENTITYCAPTURE_DUMP_QUADS` 开关从未接上。
**修复：** 默认关闭，仅当环境变量 `ENTITYCAPTURE_DUMP_QUADS=true`（或 `1`）时才转储；
正常捕获只产出 `.mcvox`。只动 `RenderCaptureService` 一处。版本 `0.2.0 → 0.2.1`（bugfix）。
