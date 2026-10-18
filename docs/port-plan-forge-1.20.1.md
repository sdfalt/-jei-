# 移植方案：把 GTM JEI 补丁搬到 Forge 1.20.1（只方案，未动工）

日期：r53 之后。依据：从格雷官方 Maven（maven.gtceu.com）实拉的
`gtceu-1.20.1-8.0.0.jar`（SHA-256 `05dbde46…4570ca` 与官方 `.sha256` 一字对上）逐类 `javap`。

## 0. 结论先行

**可以移植，但这不是「改改 import 重新编译」式的移植，而是「复用骨架、证据全部重推」。**
更要紧的一条：按 1.20.1 官方 8.0.0 的真字节码，**本模组最核心的两项修复（启动崩溃、分类补注册）
在 1.20.1 上很可能根本不存在**——所以「移植前先确认病还在」是第 0 步，见 §1。

## 1. 取证发现（全部来自真 jar 反汇编，不是猜）

取证覆盖 1.20.1 构件里**现役的两个正式版本**：`8.0.0`（latest，SHA-256 `05dbde46…4570ca`）
与 `7.5.3`（release 指针，SHA-256 `d6560d8b…05df1a0d`），两者在下列各条上结论一致
（1.x 老线未取证：它的界面栈与 7.x/8.x 不同，若用户装的是老线，WP2 用同一套流程重跑一遍即可）。

1. **加载器**：`gtceu-1.20.1-8.0.0.jar` 的 `META-INF/mods.toml` 写 `loaderVersion=[47,)`、依赖 `forge`
   ——是**纯 MinecraftForge**（NeoForge 分支的 1.21.1 用 neoforge.mods.toml）。混挂方式也复古：
   manifest 里 `MixinConfigs: gtceu.mixins.json`。
2. **界面库换血**：1.20.1 的格雷**不内嵌 ModularUI，也没有任何 `brachy.modularui` 类**
   （jar 内文件名搜 modularui/brachy 零命中）。它的界面库是**LDLib**（`META-INF/jarjar/
   ldlib-forge-1.20.1-1.0.40.b.jar`，包 `com.lowdragmc.lowdraglib`），`GTRecipeCategory` 的图标类型是
   `com.lowdragmc.lowdraglib.gui.texture.IGuiTexture`，JEI 分类基类是
   `com.lowdragmc.lowdraglib.jei.ModularUIRecipeCategory`（名字里带 ModularUI 只是历史包袱）。
3. **崩溃链不存在**：1.21.1 快照里裸调 `GTJEIPlugin.getRuntime()` 的
   `integration/recipeviewer/CategoryIcon$JeiCallWrapper` **这个类在 1.20.1 8.0.0 里压根没有**
   （连 `integration/recipeviewer/` 包都没有，JEI 集成在老路径 `integration/jei/`）；
   1.20.1 的 `GTJEIPlugin` 没有 `getRuntime()`/`onRuntimeAvailable()` 方法；
   `GTRecipeCategory.getIcon()` 是**用时才建**的懒构造（图标字段 null 才现做），
   `GTRecipeCategories.<clinit>` 只调 `register(String, GTRecipeType)`（不带图标参数）。
   → **修复①在 1.20.1 大概率无事可修。**
4. **分类注册是完整的**：1.20.1 的 `GTJEIPlugin.registerCategories` 字节码里有对
   `GTRegistries.RECIPE_CATEGORIES` 的循环、逐个 `new GTRecipeJEICategory(...)` 交给 JEI——
   1.21.1 快照那句「被注释掉的主分类注册」在 1.20.1 不存在。→ **修复②大概率无事可修。**
5. **RecipeSlot 类问题可能有新形态**：LDLib 的 JEI 桥自带 `core/mixins/jei/RecipeSlotMixin`、
   `IRecipeSlotBuilderAccessor`、`IngredientIO` 这些**靠名字取 JEI 内部件**的类
   （和 ModularUI 的毛病同款结构）。格雷声明的 JEI 下界是 `[15.20.0.115,)`，
   而 1.20.1 的 JEI 现在已出到 `15.71.0.224`（modmaven 实查）——中间隔了五十多个小版本，
   `mezz.jei.library.gui.ingredients.RecipeSlot`（类名在 15.71 仍在，已实查）字段有没有被删，
   **必须拿用户实装的那对版本 javap 比对才能定**。→ 修复③「待证」。
6. **两处界面校正对不上号**：修复④（多方块 3D 图偏移）钉的是 `brachy.modularui.drawable.schema.Viewport`，
   修复⑤（弹出菜单不出屏）钉的是 `brachy.modularui.widgets.menu.Menu`/`WidgetResizeNode`——
   1.20.1 全都没有。LDLib 是另一套渲染管线，同样的症状在不在、在的话病灶在哪，**只能实测重找**。→ 修复④⑤「症状与病灶都要重新定位」。
7. **/gtmfix 命令能保留**：Forge 官方 1.20.x 分支源码树里有
   `net/minecraftforge/client/event/RegisterClientCommandsEvent.java`（GitHub raw 查证 200）。

## 2. 第 0 步（动工前必问用户）

用户说的「1.20.1 的格雷也有相同的问题」需要落到证据：

- mods 文件夹里格雷 jar 的**完整文件名**（`gtceu-1.20.1-X.Y.Z.jar`，1.x 老线 / 7.x、8.x 新线是**两代 UI**，结论完全不同）；
- JEI 的完整文件名（`jei-1.20.1-forge-15.x.jar`）、Forge 版本号；
- 实际症状是哪一种（可多选）：
  A 启动就崩（请拖 crash-*.txt 进来，我们只看 Caused by）；
  B JEI 里看不到格雷分类；
  C 点开格雷配方页崩溃；
  D 多方块结构图偏移 / 弹出菜单开出屏外。

**若他跑的是 1.x 老线（2024 年那些 `1.4.x`~`1.6.x`）**：那代格雷的界面栈与 7.x/8.x 不同，
修复③④⑤的病灶是否存在、目标类叫什么，**必须用 WP2 的同一套 javap 流程对老线重跑一遍才能定**——
不能拿 1.21.1 的经验直接套。
**若跑的是 7.x/8.x（LDLib 线）**：①②大概率直接砍，④⑤需要重新定位，工作量重心挪到③和防呆/排查体系。

## 3. 工作包（确认后执行）

### WP1 新工程与工具链
- 本工程脚手架被 TARGET.md 钉死在 NeoForge 1.21.1，**一坑不能两占**——1.20.1 版要**新开一个工程**
  （新会话里选 Minecraft 1.20.1 + Forge），Java 17 编译线；平台若无该工具链组合，mc_target 会列出可用版本，届时如实报告。
- 源码作为骨架整包搬过去（17 个主类 + 8 个 mixin + 3 份 mixin json + 元数据），不是重写。

### WP2 证据重立（移植的主体，方法照抄 r39~r53 那套）
- 拿**用户实装**的 gtceu-1.20.1 / （嵌套或独立）ldlib / （若 1.x 线）modularui / jei 真 jar，
  逐条 `javap`，重建 1.20.1 的 GROUND_TRUTH 钉点表：
  - 老路径包名：`integration.jei.*`（不是 `integration.recipeviewer.jei.*`）；
  - FML 内部观察点（ConfigGuard 的 `ConfigTracker`/`ModConfigs`）在 Forge 47 的 FML 里行为未必与 NeoForge 一致，
    「配置静默纠错」判据要在 1.20.1 重测一遍（当年 `neoforge-1.21.1-config-correction-facts.md` 那份实测不能照抄）。

### WP3 平台层翻译（机械、量小）
- `net.neoforged.*` → `net.minecraftforge.*` 包名替换；bus 事件、ModConfigSpec、ModConfigEvent、
  FMLPaths/FMLEnvironment/ModList 都是同名同形，改动以 import 行为主；
- mixin json：`compatibilityLevel: JAVA_21` → `JAVA_17`；
- 元数据：只留 `META-INF/mods.toml`（Forge 原生就叫这个，r19 的「兼容副本」技巧不再需要）；
  依赖声明 gtceu/jei `side="CLIENT"` **必须大写**（r45 的大小写教训对 Forge 47 的 FML 同样成立，它是同一套枚举 valueOf）；
- JEI API：`mezz.jei.api.neoforge.NeoForgeTypes` → `mezz.jei.api.forge.ForgeTypes`
  （已在 jei-1.20.1-forge-api-15.71.0.224.jar 里实查存在）；libs/ 换成
  `jei-1.20.1-common-api` + `jei-1.20.1-forge-api` 两个 compileOnly jar；
- `ResourceLocation`：1.20.1 走老构造器路线，比 1.21 的 `parse` 简单；
- 兜底分类 `GtFallbackCategory`：按 JEI 15.x 的 `IRecipeCategory` 实际接口重写适配层
  （15.x 有 `getRegistryName`，`getWidth/getHeight` 这套是新版 JEI 的，逐条对着 api jar 编）。

### WP4 修复本体的取舍与重写（依 §2 的回答裁剪）
- ①②：若取证确认无病 → 从 mixin、配置开关、FixHealth 钉点里整体删除，不留空转修复；
- ③：比对用户那对 LDLib×JEI 版本的 `RecipeSlot` 字段/方法差异；若确被删 → compat mixin 保留，
  加载门从 `isLoaded("modularui")` 改 `isLoaded("ldlib")`；若没删 → 也删；
- ④⑤：症状存在才做，钉点从 brachy 重定向到 `com.lowdragmc.lowdraglib.gui.widget.*` 的对应部件，
  几何分析重做（历史上这是 r24~r31 花了 8 轮的部分，是最大的不确定项，也可能整段不需要）。

### WP5 配套体系平移（量小但有清单）
- StartupLog/FixReport/FixHealth/ConfigGuard/JeiReadiness/JeiDiagnostics/GtmfixCommand：跟着包名与钉点表平移；
- tools/：`check_mixin_descriptors.py`、`check_mod_metadata.py`、`analyze_cover.py` 等守门脚本改 1.20.1 字段；
- README 重写（1.20.1 线的「版本历程」从零开始）。

### WP6 构建与发布策略
- mc_build 出 `gtm_jei_startup_fix-1.0.0-f1201-r1.jar`（建议独立版本前缀，避免和 1.21.1 线的 r 号互相打架）；
- 维护节奏：1.20.1 格雷走**正式版**发布流（7.5.x/8.0.x），不必像 1.21.1 那样追连续快照——
  按正式版本号钉真值、逐版验证即可。

## 4. 工作量与风险

| 部分 | 量级 | 说明 |
| --- | --- | --- |
| WP3 平台翻译 | 小 | 大半是包名/import 替换，工具链换 Java 17 |
| WP2 证据重立 | 中 | 方法现成，对象换一批 |
| WP5 配套平移 | 小中 | 有清单可对照 |
| WP4 界面校正重写 | **未知（0～大）** | 取决于症状是否真的存在、LDLib 病灶深浅 |
| 最大风险 | | **病不在**：1.20.1 8.0.0 实证无启动崩溃链、分类注册完整；移植若不做 §2 就可能造出一个修空气的模组 |

## 5. 两条路线

- **路线 A（推荐）**：用户先报 §2 的版本三件套 + 症状 → 我拉他实装的那套 jar 做一轮快速取证 →
  确认「还剩哪些病」→ 新开 Forge 1.20.1 工程按 WP1~WP6 动工。返工风险最低。
- **路线 B**：不等消息，直接把整套骨架平移到 1.20.1、五项修复全带上再裁剪。
  不推荐：①②按实证大概率是白做，④⑤目标类在 1.20.1 不存在，等于先写必删代码。
