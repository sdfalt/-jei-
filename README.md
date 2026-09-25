# GTM JEI Startup Crash Fix（r17）

本模组为AI编写  需要最新版的格雷科技现代版快照  还有jei物品管理器

装了这个模组，**GregTech CEu Modern（GTM）8.0.0-SNAPSHOT 在 Minecraft 1.21.1 / NeoForge 上就不会在启动时崩溃，JEI 里也能重新看到格雷配方分类，点开配方页（如搅拌机）也不再必崩**（r17 修复，见版本历程）。本模组只在客户端生效。

适用环境（实测）：

| 项目 | 版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x；依赖下界声明为 **21.1.235**（编译与实测在 21.1.244 / 251） |
| GregTech CEu Modern（`gtceu`） | 8.0.0-SNAPSHOT |
| JEI | 19.x（实测 `19.56.0.441` 与 `19.57.0.448`；r17 起 ModularUI 不兼容崩溃也已修复） |

本模组版本 `1.0.0-r17`，mod id `gtm_jei_startup_fix`，成品是 `build/libs/gtm_jei_startup_fix-1.0.0-r17.jar`。

先解释几个名词（后面还会用到）：

- **Mixin**：在运行时给别人的代码打补丁的机制。
- **JEI**：游戏内查看「这个物品怎么做出来」的模组（默认按 R 看用途、U 看来源）。
- **NPE（空指针异常）**：代码拿到一个 `null`（空）却直接当对象用，游戏因此崩溃。
- **描述符（descriptor）**：JVM 里精确描述一个方法「参数是什么类型、返回什么类型」的字符串；Mixin 用它来认方法。

## 这个模组修两件事

1. **启动崩溃**：GTM 的 `GTRecipeCategories` 在静态初始化时就构造分类图标，图标当场调用 `GTJEIPlugin.getRuntime()`，而 JEI 要到初始化的最后才把运行时交给 GTM，于是拿到的是 `null` → NPE，游戏起不来。
2. **JEI 里看不到格雷配方分类**：GTM 这个快照把 `registerCategories` 里注册主分类的那一行**自己注释掉了**，只注册 6 个特殊分类（多方块信息、矿脉图等）。配方还在交，但 JEI 对「没有分类的类型」直接丢弃，玩家就一个格雷分类都看不到。

## 问题一：启动崩溃（r5 的修法，一直在生效）

GTM 的 `GTRecipeCategories` 静态初始化时会构造一批分类图标，每个图标当场调用 `GTJEIPlugin.getRuntime().getJeiHelpers()...`。JEI 是在自己初始化的**最后**才把运行时（`IJeiRuntime`）交给 GTM 的，这条链发生时 `getRuntime()` 还是 `null` → NPE 崩在启动路上。

r5 的做法：未就绪时不返回 `null`，而是返回一个**会自动恢复的占位图标**（尺寸与真值一致）。GTM 会把它存进自己的字段，JEI 注册分类时缓存的也是这个占位对象；等 JEI 就绪后，占位对象在**被用到的那一刻**换成真实图标，之后透明转发。恢复不依赖时机先后——只要有人用到它，它就自愈。

## 问题二：JEI 里看不到格雷分类（r6 起补注册，r8 修对目标类）

GTM 这个快照的 `registerCategories` 源码长这样（本模组核对的是与实装同一构建的快照）：

```
for (GTRecipeCategory category : GTRegistries.RECIPE_CATEGORIES) {
    if (category.shouldRegisterDisplays()) {
    }
    // registry.addRecipeCategories(new GTRecipeJEICategory(jeiHelpers, category));   ← GTM 注释掉了
}
```

也就是说：研磨、分离、化学合成……这些主配方分类一个都没进 JEI。**r8 修的正是这里最关键的一步**：GTM 的 `GTRecipeJEICategory` 实际在 `com.gregtechceu.gtceu.integration.recipeviewer.jei.recipe` 包下（注意最后多一层 `recipe`），r6/r7 按 `...jei` 包名去找，所以补注册从来没命中过——这才是「格雷分类补不上」的真正缺口。r8 改成正确包名，并把取 `RecipeType` 的方式钉死。

补分类时以「JEI 里已有分类的 uid 集合」去重，GTM 已注册的一个不碰；每条补录单独 try/catch，单个失败不影响其它分类，更不影响启动。

## 分工：谁做什么（r8 的行为）

| 事情 | 谁做 | 说明 |
| --- | --- | --- |
| 格雷配方分类页面 | **本模组补** | 用 GTM 自己 `memoize`（把函数结果缓存起来，同样输入永远返回同一个实例）的 `GTRecipeJEICategory.TYPES` 拿 `RecipeType`，保证与 GTM 后续交配方、交机器定位用的是**同一个实例**，天然对接 |
| 配方内容 | **GTM 自己提交**（默认） | 本模组只在检测到「同时装了 EMI，且 GTM 被它自己的 `isEMILoaded()` 守卫整体跳过」时才代交，通常什么都不做 |
| 机器定位（催化剂） | **GTM 自己登记** | 催化剂就是 JEI 里「哪台机器能看这个分类」的小图标；本模组不再重复登记（旧版重复登记会出双份） |

## 安装

1. 把 `build/libs/gtm_jei_startup_fix-1.0.0-r17.jar` 放进 `mods` 文件夹。
2. **删掉所有旧的同名 jar**（r1 ~ r16）：多个版本会同时打补丁，行为不确定。

本模组把 `gtceu` 与 `jei` 声明为必需依赖（`neoforge.mods.toml`）：缺任意一个都没有可打补丁的目标。

## 启动日志在哪（r16 起）

游戏根目录（就是 `mods` 文件夹所在那一层）里会有**两处**现场记录：

| 位置 | 是什么 | 会不会被下一次启动顶掉 |
| --- | --- | --- |
| `gtm_jei_fix_report.txt` | 最新一次启动的完整现场（r9 起就有） | 会。整份重写，只留最新 |
| `gtm_jei_logs/startup-20260924-162009.log` | **每次启动一份**的逐行日志（r16 新增），文件名就是启动时刻 | **不会**。上一次、上上次的都原样留着 |

`gtm_jei_logs/` 里每一份的规则：

- **一次启动一个文件**，名字 `startup-yyyyMMdd-HHmmss.log`；同一秒内重开游戏会自动加 `-2`、`-3` 后缀，绝不覆盖已有文件。
- **每行前面带 `HH:mm:ss.SSS` 时间戳**，能看出「挂钩命中」和「补分类」之间隔了几秒、卡在哪一步之后就再没有下文。
- **文件头**记录启动时刻、游戏目录绝对路径、Java 版本、操作系统与架构、最大堆、运行侧（客户端/专用服务端）＋各模组版本；**文件尾**由 JVM 关闭钩子补一行「进程退出」，所以文件停在哪儿就说明现场到哪儿为止。
- **行到即落盘**（写完立刻 flush）：崩溃、强杀、手机启动器直接关掉，已经发生的那几行都还在。
- **只保留最近 20 份**，超出的自动删最旧——且只删自己创建的 `gtm_jei_logs/startup-*.log`，绝不碰别的文件。
- 两边内容一致，写不进去时（目录只读等）自动只保留根目录那份与聊天摘要，不影响游戏。

反馈问题时发哪份：默认发**最新那份**（两份任选其一，内容一样）。要说「上次能进、这次进不去」，就把 `gtm_jei_logs/` 里**前后两次启动**的两份一起发。

不想要这个文件夹：直接删掉即可，下次启动会重建；它不影响任何修复逻辑。

## 构建

- 编译需要 JEI 官方 api jar，已经放在 `libs/` 下（离线也能编译），与用户实装同版本 `19.56.0.441`：
  - `jei-1.21.1-common-api-19.56.0.441.jar`（约 152 KB）
  - `jei-1.21.1-neoforge-api-19.56.0.441.jar`（约 2.7 KB）
  - 下载地址：https://modmaven.dev/mezz/jei/
- 这两个 jar 只用于**编译期**（`compileOnly`），**绝不打包进成品**。好处是让编译器替我们把关类名：名字写错直接编译不过（r7 的崩溃根源就是少了这一步）。
- 自检命令：`python3 tools/check_mixin_descriptors.py`。它查两件事：
  1. Mixin 回调方法的描述符是否与真实签名**逐字节一致**；
  2. 我们 class 里引用的每一个 `mezz/**` 类型是否**真实存在于 `libs/` 的 JEI api jar 里**（这一条专门拦 r7 那种「接口名凭印象写」的错）。
- 事实依据放在 `tools/ground-truth/` 的两份 javap 摘要里（哪份来自哪个 jar、哪个版本，都写在文件头）：
  - `gtceu-8.0.0-snapshot.javap.txt`：与实装同一构建的 GTM 快照；
  - `jei-19.27.0.340.javap.txt`：与实装同版本的 JEI api。

## 版本历程（都是真踩过的坑）

| 版本 | 干了什么 | 结果 |
| --- | --- | --- |
| r5 | 用「会自动恢复的占位图标」顶住 JEI 就绪前的空运行时 | 游戏能启动了 |
| r6 | 给 GTM 的注册流程挂尾巴，但 Mixin 回调参数写成了 `Object` | 与目标方法的字节码描述符不符 → 启动期 `InvalidInjectionException`，模组加载失败 |
| r7 | 把回调参数类型换成「凭印象写的 JEI 接口名」，其中 `mezz.jei.api.registration.ICatalystRegistration` 与 `IMenuCategoryDataRegistrar` 在 JEI 19 里根本不存在（前者现名 `IRecipeCatalystRegistration`，后者已移除） | 转换 Mixin 自己的类时就要解析回调里出现的每个类名（连局部变量表都要解析），解析不到直接抛 `ClassMetadataNotFoundException` → 模组加载失败。**这一步跟「目标方法有没有匹配上」无关，所以 `require = 0` 救不了它** |
| r8 | 编译期直接依赖 JEI 官方 api jar（`libs/` 下两个 jar，与实装同版本 `19.27.0.340`，`compileOnly`、绝不打包），类名写错就编译不过；三条注入描述符逐条对照与实装同一构建的 GTM jar 核实后钉死；另外修掉 `GTRecipeJEICategory` 包名找错（实际在 `...jei.recipe` 包下）这个真正的功能 bug | — |
| r9 | 现场报告（游戏根目录 `gtm_jei_fix_report.txt`）+ 进世界聊天摘要 + 轮询兜底诊断；编译期 JEI api 对齐用户实装的 `19.56.0.441` | 第一次能拿到用户机器的真实结论 |
| r12 | 版本号从模组元数据现读（日志/报告与 jar 文件名一致） | 现场报告揭示真相：分类/配方两条 **TAIL 挂钩永不命中**（同一 Mixin 的催化剂 TAIL 正常命中），JEI 里 GTM 特殊分类 0 个 |
| r13 | 用与用户同一构建的 GTM 官方 sources jar + JEI 19.56 实现字节码钉死根因：**GTM 这两个方法的方法体运行期中途抛异常，永远走不到结尾**（快照自己注释掉了主分类注册 → 分类缺失又让配方阶段第一句就抛「Recipe type not registered」→ JEI 19.56 的 `PluginCaller` 把插件的 RuntimeException 吞掉只记日志）。修法：`registerCategories/registerRecipes` 改在 **HEAD 接管**——`ci.cancel()` 后由本模组按 GTM 原顺序重放（6 个特殊分类逐个带配置守卫构造注册、主配方分类挂页、配方逐分类代交、程序电路内联补上），每步独立 try/catch，成败与真实异常逐条写进现场报告；兜底分类页补槽位底、动画箭头、耗时/EU·t 文本（治「页面变暗」「箭头没有」）；修诊断抢跑（注册流程没跑完前不许下「挂钩未命中」结论） | — |
| r14 | 适配 GTM 快照 17e1700：官方已自行恢复主分类注册，接管时**优先放行格雷原生页面类**，构造失败（老快照是抽象类）才退回本模组兜底页——新老快照都拿最优表现；程序电路页两种形态兼容；报告新增「格雷原生 X 页 + 本模组兜底 Y 页」账目 | 当前支持 34f02a6 与 17e1700 两个快照 |
| r15 | NeoForge 依赖下界与编译版本解耦（`gradle.properties` 新增 `neo_version_range`），运行要求从 `[21.1.244,)` 放宽到 **`[21.1.235,)`**：本模组用到的全部 NF 接口在 21.1.x 全系列零变化，放宽无兼容风险 | — |
| r16 | **每次启动留一份独立日志**：游戏根目录新建 `gtm_jei_logs/`，一次启动一个文件（`startup-yyyyMMdd-HHmmss.log`，同秒重开自动加 `-2` 后缀，绝不覆盖），逐行带 `HH:mm:ss.SSS` 时间戳、行到即落盘；文件头写启动时刻/Java/系统/最大堆/运行侧，尾部由关闭钩子补「进程退出」一行；只保留最近 20 份（仅删本模组自己的 `startup-*.log`）。根目录 `gtm_jei_fix_report.txt` 照旧保留为「最新一份」 | — |
| r17 | **修「一点开配方页就崩」**：崩溃根因不在格雷也不在 JEI 本体——ModularUI 3.3.1 是对着 JEI 19.25 编译的，它的 `RecipeSlotAccessor` 按名字往 `RecipeSlot.allIngredients / displayIngredients` 两个 List 字段写数据；JEI 19.3x 起重构掉了这两个字段（用 modmaven 的 19.25/19.27/19.56/19.57 真 jar 逐版 javap 核对），`@Accessor` 定位失败在 sponge-mixin 里是硬错误 → `MixinApplyError` 崩游戏。本模组用独立 mixin 配置（`gtm_jei_startup_fix_jeicompat.mixins.json`，**priority 900：Mixin 自然顺序是数字小的先应用**——反编译 NeoForge 实用的 sponge-mixin 0.15.2 字节码 + 官方 `@Mixin` javadoc 双重核实，别凭直觉写更大的数；且查实单写配置 `priority` 不会传进 `MixinInfo.readPriority`，必须同时配 `mixinPriority`/注解 `priority`）+ 插件 `preApply`，在 `RecipeSlot` 类转换的最早时机用 ASM 把缺字段补回去；老 JEI（字段还在）原样跳过，没装 ModularUI 不补，任何异常只记日志不抛出。**教训**：sponge-mixin 分阶段应用——所有 mixin 的 `preApply` 先整体跑完，`@Accessor` 的字段定位在最后 ACCESSORS 阶段且读实时 `ClassNode`，所以补字段必然赶得上 | 当前版本 |

这几段经验一句话总结：**Mixin 的错要分成三种——「方法没匹配上」（可以 `require = 0` 降级成「这项不补」）、「类转换时解析不到类名」（整个模组加载失败，`require = 0` 毫无用处），以及 r12 学到的最阴险的一种：「挂钩钉对了，但目标方法体半路抛异常，TAIL 永远执行不到」——JEI 把插件异常吞掉记日志，表面上什么都不发生。要接管一个不可靠的方法，就得站在它进门口（HEAD）取消原方法自己重放，而不是等它走到出口（TAIL）。**

## 验证：看日志里这几行

启动后在日志里搜 `[gtm_jei_startup_fix]`，r13 起最要紧的几行（同样都会实时写进游戏根目录的 `gtm_jei_fix_report.txt`）：

```
[gtm_jei_startup_fix] 已加载（r13）：修启动崩溃 + HEAD 接管格雷的 JEI 注册 + 现场报告。……
[gtm_jei_startup_fix] 挂钩命中（HEAD 接管）：GTM#registerCategories
[gtm_jei_startup_fix] 特殊分类「矿石处理流程图页」注册失败（跳过它，其余不受影响）：<真实异常>   ← 若某一步坏，异常原样贴出
[gtm_jei_startup_fix] 格雷配方分类页接管完成：新增 N 个（已存在跳过 0 个，失败 0 个）；配方由本模组按 GTM 的注册表代交。
[gtm_jei_startup_fix] [接管配方] 机器分类交给 JEI：N 个成功（其中空配方页 E 个）、失败 F、无页面跳过 S；特殊分类代交 K 个。
[gtm_jei_startup_fix] 诊断：JEI 现有配方分类 T 个，其中 gtceu 的 M 个（例：gtceu:macerator ……）；…… 接管情况：……
[兼容补丁] 已为 ModularUI 向 RecipeSlot 补回 2 个被新版 JEI 移除的字段（allIngredients/displayIngredients），配方页不再因此崩溃（JEI ……，ModularUI ……）   ← r17：第一次点开任意配方页时才出现这行
```

看什么：

- **`M` 应该远大于 0**：打开 JEI 按 R 搜「研磨」「装配」之类，应能看到格雷分类页与配方。
- **每一条「失败」都会带真实异常原文**——这一步坏在哪、什么异常，报告里一行一个，发回来即可逐条修。
- 如果 HEAD 挂钩仍没命中（GTM 又改了方法名），`[挂钩未命中]` 行后面会有一行真实的 GTM 方法签名清单（形如 `GTJEIPlugin = [registerCategories(...):void | ...]`），把它发回来就能一次改对。
- 如果装了 EMI，配方改由本模组代交，会另打一行 `[接管配方] 装了 EMI：……`。
- **r17**：如果一点开配方页就崩，看本次启动日志（`gtm_jei_logs/`）里有没有那行 `[兼容补丁]`——没有这行说明崩溃发生在补丁字段之外的新地方，把崩溃报告原样发回来。

## 已知边界

- **r17 字段补丁是「止血」不是「根治」**：补回去的两个字段成了只写不读的摆设——新版 JEI 画配方页改读自己的 `RecipeSlotIngredients`，配方页的打开/显示/翻页全部正常（初始内容走 JEI 公开 API，不受影响），受影响的只有「同一页内配料列表被 ModularUI 动态改写」这类刷新（如槽位上的循环角标）。真正的根治要等 ModularUI 适配新版 JEI：给 [ModularUI-Modern](https://github.com/brachy84/ModularUI-Modern) 报 issue，附上 `InvalidAccessorException: allIngredients ... RecipeSlot` 这段崩溃原文即可。
- 兜底分类页有**标题 + 图标 + 带槽位底的输入输出网格 + 动画箭头 + 耗时/EU·t 一行**；没有多方块机器动画、没有配方转移跳转（点输出跳到下一页）。
- 它显示的槽位内容来自 GTM 的配方能力链（与 GTM 自己的 EMI 桥接同一条数据链），取不到就少摆几格；页面仍然可搜、可点。
- 只在客户端生效：专用服务器上不产生任何效果。

## 万一还报 Mixin 相关错误

- 从 r7 起，补注册的三条尾巴全部「描述符钉死 + `require = 0`」，**结构对不上只会跳过并记日志，不再可能把模组加载搞崩**。
- 仍在硬要求命中的只剩图标拦截那两处（`getRenderable`）——那是让游戏能启动的地基，落空就等于回到 r5 之前的 NPE，宁可显式报错也不要静默无效。
- 真出现这类报错，把完整段落发回来即可；本地也可以先跑 `python3 tools/check_mixin_descriptors.py`，它就是照这个规则做的守卫。
