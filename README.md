# GTM JEI Startup Crash Fix（r32）

装了这个模组，**GregTech CEu Modern（GTM）8.0.0-SNAPSHOT 在 Minecraft 1.21.1 / NeoForge 上就不会在启动时崩溃，JEI 里也能重新看到格雷配方分类，点开配方页（如搅拌机）也不再必崩**（r17 修复，见版本历程）。本模组只在客户端生效。

适用环境（实测）：

| 项目 | 版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x；依赖下界声明为 **21.1.235**（编译与实测在 21.1.244 / 251） |
| GregTech CEu Modern（`gtceu`） | 8.0.0-SNAPSHOT |
| JEI | 19.x（实测 `19.56.0.441` 与 `19.57.0.448`；r17 起 ModularUI 不兼容崩溃也已修复） |

本模组版本 `1.0.0-r32`，mod id `gtm_jei_startup_fix`，成品是 `build/libs/gtm_jei_startup_fix-1.0.0-r32.jar`。

r32 只做一件事：**加了一个配置文件**，把两件原本写死的行为交给玩家控制（注释中英双语，见下面
「配置文件」一节）：① 每次进入游戏时聊天栏弹的那几行提醒可以整体关掉；② `gtm_jei_logs/` 里
保留几份启动日志——以前固定 20 份，现在填几份是几份，填 `-1` 就一份都不删。
r31 及以前的五项修复，这一版代码一字未动。

（r31 那一版重写的是**「弹出菜单不出屏」**：拿格雷与 ModularUI 两边源码取到真凭据——那份缺失部件
列表是可滚动的，而滚动从不写进子部件坐标，菜单照的是一份「没滚动」的位置，r28/r30 少的就是这一个量；
同时删掉「实在放不下就往屏幕边贴」的兜底（「顶到左上角」就是这么来的）和一条目标类根本不存在的死挂钩。
详见「问题四」。r24/r28/r30/r31 那几项几何逻辑这一版都没再动。）

先解释几个名词（后面还会用到）：

- **Mixin**：在运行时给别人的代码打补丁的机制。
- **JEI**：游戏内查看「这个物品怎么做出来」的模组（默认按 R 看用途、U 看来源）。
- **NPE（空指针异常）**：代码拿到一个 `null`（空）却直接当对象用，游戏因此崩溃。
- **描述符（descriptor）**：JVM 里精确描述一个方法「参数是什么类型、返回什么类型」的字符串；Mixin 用它来认方法。

## 这个模组修四件事

1. **启动崩溃**：GTM 的 `GTRecipeCategories` 在静态初始化时就构造分类图标，图标当场调用 `GTJEIPlugin.getRuntime()`，而 JEI 要到初始化的最后才把运行时交给 GTM，于是拿到的是 `null` → NPE，游戏起不来。
2. **JEI 里看不到格雷配方分类**：GTM 这个快照把 `registerCategories` 里注册主分类的那一行**自己注释掉了**，只注册 6 个特殊分类（多方块信息、矿脉图等）。配方还在交，但 JEI 对「没有分类的类型」直接丢弃，玩家就一个格雷分类都看不到。
3. **JEI 里格雷的多方块 3D 结构图偏左上角**（r24）：页面里的槽位、按钮、滑块都长在正确位置，只有中间那张结构图整体往左上角跑，鼠标点到的和看到的也对不上。根因在 ModularUI——那张图是真用 OpenGL 视口渲的，而 GL 视口不跟 `GuiGraphics` 的位姿走。见下面「问题三」。
4. **控制器里「可使用的类型」那排方块开到屏幕外面**（r28 引入，r30、r31 两次重写摆法）：机器结构没长对时，控制器界面逐行列出缺失的方块（一份**可滚动**的列表），鼠标放到某行旁边的小按钮上就展开「这个位置能用哪些方块」。**越往下的行，展开出来的位置也越往下，最后整个开出屏幕外**，看不见也就点不着，玩家以为「查配方」这个功能没了。根因有两层：弹出菜单的方向在 ModularUI 里是写死的（永远开在按钮下方，全库没有自动翻转），而它锚的是那份**不含滚动位移**的按钮坐标。r31 的摆法是「先把滚动位移减掉，按按钮在屏幕上真正的位置放，放不下就翻到按钮上方；任何一环认不准就完全不动」。见下面「问题四」。

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

## 问题三：JEI 里格雷的多方块 3D 结构图偏左上角（r24）

打开 JEI 的「多方块结构信息」页（电力高炉、蒸馏塔那一类），中间那张可以转可以缩的结构图会整体偏到左上角，
而同一页里的部件列表、滑块、按钮都在原位。

**渲染链（三方源码逐段核对）**：

```
GTM  MultiblockPreviewWidget（200x180 的 ModularUI 部件树，页面尺寸由 getMaxWidth/getMaxHeight 给出）
 └─ MUI  SchemaWidget.draw()
     └─ MUI  BaseSchemaRenderer.draw(context, x, y, w, h, theme)
         └─ MUI  Viewport.calculateOpenGLViewportFromRectangle(x', y', w, h)
             └─ RenderSystem.viewport(...)      ← 直接设 GL 视口
```

JEI 画一张配方页的约定是：先把 `GuiGraphics` 的位姿**平移到配方页在屏幕上的绝对位置**，再把鼠标坐标
**减去**这个位置、以「页内相对坐标」交给部件去画（JEI 19.56 源码 `RecipeLayoutDrawableErrored`、
`OffsetJeiInputHandler` 两处都是这个写法）。于是走位姿的 2D 内容自然落在正确位置。

但 GL 视口是**窗口像素级**状态，不吃位姿：格雷那张 3D 图的位置全靠 MUI 自己的坐标栈算，
而那份页面是一个 `UIType.EMBED` 的「虚拟屏」——主面板 `pos(0,0)`、屏幕区域原点也是 `(0,0)`，
MUI 3.3.1 里负责告诉它「你被摆在屏幕哪里」的 `EmbedHandler.EmbedWrapper.updateGuiArea(Rectangle)`
**是个空方法**。于是视口按「页面左上角 = 窗口左上角」来算，图整体偏左上角，
偏移量正好等于配方页在屏幕上的原点。

**r24 的修法**（两条客户端 mixin，独立配置 `gtm_jei_startup_fix_muicompat.mixins.json`，`defaultRequire = 0`）：

| 位置 | 做什么 |
| --- | --- |
| `ModularUIJeiCategory$UIWrapperWidget#drawWidget` 的 HEAD | 读 `GuiGraphics` 位姿的平移分量＝这块内嵌界面在屏幕上的真实原点，写进 `MultiblockEmbedOffset`；TAIL 收回 |
| `schema/Viewport#calculateOpenGLViewportFromRectangle` 的第 0/1 个入参 | 把上面那个原点加进去（MUI 自己那套「乘 GUI 缩放、Y 轴翻转」的逻辑一字未动） |

刻意只做「读＋加」，**不碰 JEI 传给 MUI 的鼠标坐标**：MUI 在 JEI 里的整套部件坐标本来就是页内相对的，
槽位悬停、提示、按钮点击都自洽，改了反而会全坏。结构图的射线拾取用的是同一个视口矩形做换算，
视口摆正后「看到的方块」和「点到的方块」自然对上。

三道保险：读数不像屏幕坐标（负数、超过一屏）就整帧不做位移；捕获连续失败 20 次就彻底停用；
偏移只在内嵌绘制的 100 毫秒有效期内非 0，所以玩家在自己机器里点开多方块主控方块的那个 3D 预览
（走的同一个渲染方法）偏移恒为 0，行为与没打补丁时一字不差。

## 问题四：控制器里「可使用的类型」菜单越往下越往下，甚至开出屏幕外（r28 引入，r30、r31 两次重写）

机器结构没长对时，控制器的界面里逐行列出「缺了哪些方块」，每行右边一个小按钮（格雷的 `ContextMenuButton`），
鼠标放上去展开「这个位置可以用哪些方块」。现象是：**行越靠下，展开出来的那排方块位置也越靠下，最后整个开到
屏幕外面**——看不见，也就点不着（玩家的说法是「配方查询功能修没了」）。

### 真正的根因：两层，缺一层都修不好（r31 用两边源码逐行确证）

```
第一层（r28 就查到的）：方向写死
  MUI  AbstractMenuButton.getMenu() → menu.resizer().relative(this)   // 菜单的「排版父」＝那个按钮
       默认 Direction.DOWN → resizer.topRel(1f)                        // 永远开在按钮下方，不会自动翻面

第二层（r30 的现场日志才暴露出来的）：菜单锚的是一份「没滚动」的坐标
  GTM  WorkableMultiblockMachine.getMainTextPanel
        → new ListWidget<>().width(166).height(130)          ← 缺失部件列表：130 高的可滚动视口
  MUI  AbstractScrollWidget.transformChildren(stack)
        → stack.translate(-getScrollX(), -getScrollY())      ← 滚动只是绘制/命中时的矩阵平移
  MUI  ListWidget.layoutWidgets
        → child.getArea().setRelativePoint(axis, p)          ← 子部件坐标按内容空间累加，永远不含滚动位移
```

合起来：玩家把列表**滚**到下面那些行（靠滚动它们是看得见的），菜单却按**没滚动**的坐标摆。
玩家日志里那句「按钮 306..324、可视区 0..240」就是铁证——按钮的绝对坐标确实在窗口外，可它在屏幕上看得见。
**少的就是这一个滚动位移。**

顺带确证的几条（都不是猜的）：

- 菜单是 `Menu<>().widthRel(1f).coverChildrenHeight()`，`widthRel(1f)` 相对的是「排版父」＝按钮 →
  **菜单宽永远等于按钮宽**（日志里的 18 就是它），`maxSize(40)` 是竖向滚动区的高度上限 → 高固定 40；
- 格雷在控制器界面里一次都没调用过 `direction(...) / openUp() / openDown() / openCustom()`
  （只在 JEI 预览页的智能过滤器封面上用 `openRightDown()`），所以清一色是默认 DOWN；
- MUI 的 `Direction` 只有一组静态定位器，全库 grep `fitToScreen / clampToScreen / restrictToScreen`
  一个都没有——它压根不问屏幕多大；
- `WidgetResizeNode` 确实自己声明了 `postFullResize()`，而 **`brachy.modularui.widget.WidgetNode`
  这个类在该版本（525 个 java 文件全量清单）里根本不存在** → r28~r30 那条「同类保险」mixin
  从来没命中过一次，r31 删除。

### r28 与 r30 各错在哪（为什么玩家看到「一律跑到同一个位置」「顶到左上角」）

| 版本 | 规则 | 后果 |
| --- | --- | --- |
| r28 | 越出可视区多少就往回挪多少（贴屏幕边） | 落点只由「可视区远端 - 菜单高」决定，越界 1 像素和 200 像素都落到同一个位置 |
| r30 | 按按钮矩形摆（4 个竖向候选位），放不下就翻面 | 按钮矩形仍然是**没滚动**的：「原样位」和「翻面位」**全都**在屏幕外 → 只能走兜底 `可视区远端 - 菜单高`，玩家日志 8 条清一色「比可视区高,尽量贴行」钉死在 y=196；窗口再小一点，兜底退回 `可视区近端` = **左上角** |
| r30 另一处 | 「宽≥600 或高≥400 才算面板」这种绝对像素阈值来认按钮 | 小窗口里整块面板都比这阈值小 → 把**面板**当成按钮摆，同样会把菜单推到角落 |

r30 还有一个已经修掉的隐患（保留在代码里，别再改回去）：MUI 的 `rx/ry` 在一次排版里会被**换两种含义**——
`Area.applyPos(px,py)` 把它当「相对排版父（按钮）」的规格读，紧接着 `StandardResizer.applyPos` 又改写成
「相对部件父（那块 `MenuPanel`）」，而 `InternalWidgetTree.resize` 里
`isSelfFullyCalculated() || resize(...)` 让已算过的节点**不会**重算规格。所以「往 `ry` 上加位移」等于
把位移喂给下一轮当规格读走。r28 看着稳定只是因为贴边位恰好是那个反馈的不动点。r30/r31 一律写绝对值。

### r31 的修法

挂钩位置仍然只有一条：`WidgetResizeNode#postFullResize` 的 TAIL（整棵树绝对坐标刚算完、还没开画那一拍）。
后面做的事：

1. **认锚点**：`menu.resizer().getParent()` 上的部件必须是 `AbstractMenuButton` 的实例（按类名
   `Class.forName` 判，字节码里不出现任何 MUI 类型）。不再用尺寸阈值猜。
2. **补滚动**：从按钮沿 `getParent()` 走到根，把沿途每个可滚动部件的 `getScrollX()/getScrollY()` 累加；
   对菜单自己走同一条链再累加一次，**两者相减**——父子共同的滚动自动抵消，不会重复补偿。
   认「谁平移了子部件」用的是特征（有一对返回 `int` 的 `getScrollX/getScrollY`），不认类名：
   MUI 里只有 `AbstractScrollWidget` 重写了 `transformChildren`，接口默认实现是空的。
3. **验坐标空间**：这张界面必须是「窗口本身那张屏」——读 MUI 自己的结构标记
   `screen.getContext().getUIType().isScreen`（`MODULAR_SCREEN=true`、`EMBED=false`），再叠一道「此刻不在 JEI 内嵌绘制中」。
   虚拟屏的坐标与窗口不同源（而且 `createEmbed` 存进去的还是**建页那一刻**的窗口尺寸），摆不准 → **整个菜单不动**，报告里留一条取证。
   这条链万一读不到，才退回「可视区与真实窗口尺寸重合」的兜底比对。
4. **摆位置**：竖向 4 个候选位、横向 4 个候选位（一一对应 `Direction` 的真实写法）。先用**没补偿**的
   按钮矩形把「原方向＋模组自己加的 offset」认出来，再把这套方向作用到**补偿后**的矩形上：
   原样位放得下就用原样位（通常等于一个字节不动），放不下就翻到按钮另一侧（往上开）。
5. **写绝对值**：`x/y = 目标`，`rx/ry = 目标 - 部件父原点`（绘制与命中走 `rx/ry` 变换链）。

### 「宁可不摆」具体是哪些情况

r31 把 r28/r30 那条「实在放不下就往屏幕边贴」的兜底**整条删掉**了。以下情况一律保持 ModularUI 原样，
一个字节不改：

- 认不出那一行的按钮（锚点不是 `AbstractMenuButton`、或拿不到排版父）；
- 按钮本体就在可视区外（补偿完还在界外，说明我们对这份坐标的理解不对）；
- 竖向／横向的朝向对不上任何候选位（别的模组用 `openCustom()` 自己摆过坐标）；
- 原样位和翻面位**都**放不进可视区（菜单比可视区还长）；
- 这张界面不是「窗口本身那张屏」（JEI 里的内嵌虚拟屏，`UIType.EMBED`）；
- 位移幅度超过 4096 像素（比任何真实屏幕都大，只能是我们算错）；
- 同一块面板里挂着两层菜单（父菜单＋子菜单）——父子各挪各的会断线。

每一种都会累计进聊天栏那行摘要（`完全没动 N 次／其中 K 次是因为那张界面不是窗口本身…`），
前两类还会各留最多 2 条现场报告，好让下一轮判断「闸是不是卡错了」。

**教训（三条，都别再犯）**：

1. 修「越界」不能只看落点在不在界内，还要看它**还属不属于原来那个控件**；而"属于"的判据必须是
   控件**在屏幕上真正的位置**，不是它在内容空间里的坐标。
2. 往别人的排版字段里「加位移」之前，得先确认这个字段在下一个周期会不会被它自己当成规格值读回去。
3. 拿不准就**不动**。给用户一个「原版那样的越界」远好过给一个我们凭空算出来的位置——
   前者他知道是模组的问题，后者会以为我们把别的功能弄坏了。

## 安装

1. 把 `build/libs/gtm_jei_startup_fix-1.0.0-r32.jar` 放进 `mods` 文件夹。
2. **删掉所有旧的同名 jar**（r1 ~ r30，尤其是 r28/r30 那两份带弹出菜单摆法的）：多个版本会同时打补丁，行为不确定。

本模组把 `gtceu` 与 `jei` 声明为必需依赖（`neoforge.mods.toml`）：缺任意一个都没有可打补丁的目标。

## 说明文字分别在哪（改修复时别漏）

「本模组做了什么」这句话在项目里有**四份**载体，各自给不同的人看，很容易只改一处：

| 文件 | 谁读它 | 写作约束 |
| --- | --- | --- |
| `README.md`（本文件） | 看源码 / 网页的人 | 无约束，写得越细越好 |
| `src/main/resources/META-INF/neoforge.mods.toml` 的 `description` | 游戏内「模组列表」、NeoForge 系启动器 | 三段引号的字面量字符串，可换行 |
| `src/main/resources/META-INF/mods.toml` 的 `description` | 只认旧文件名的 HMCL | **必须是单行普通字符串，且整份文件不能有任何注释**，否则老解析器会读挂 |
| `src/main/resources/mcmod.info` 的 `description` | PCL2 社区版 | 必须是**合法 JSON**（一个逗号/引号错了整份就没了），也是单行 |

前三份的 `version` 字段都写的是 `${mod_version}`，由 `build.gradle` 的 `processResources` 从
`gradle.properties` 现读——所以**改版本只改 `gradle.properties` 一处**，日志、报告、聊天摘要里显示的
版本（`FixReport.selfTag()` 也是从模组元数据现读）永远和 jar 文件名一致。
`description` 则是四份各写各的，改完记得四份一起过一遍（r29 就是补这一课）。

## 配置文件（r32 新增）

第一次进游戏之后，游戏根目录会出现 **`config/gtm_jei_startup_fix.toml`**（和 `mods` 文件夹同级的
`config` 里）。它由 NeoForge 自带的配置系统自动生成，**每个选项上面的注释都是中英双语**，
直接用记事本改就行。里面管两件事：

| 选项 | 默认 | 管什么 |
| --- | --- | --- |
| `messages.joinChatReminder` | `true` | **每次进入游戏时聊天栏弹的那几行提醒**发不发。填 `false` 就一个字都不发 |
| `logs.startupLogKeep` | `20` | **`gtm_jei_logs/` 里保留几份启动日志**。填 40 就只留最近 40 份、更早的自动删掉；填 `-1` 表示**一份都不删** |

生成出来的文件**逐字**长这样（下面这份是拿成品 jar 真跑一遍 `ModConfigSpec` 打出来的，不是手抄的近似值；
注释由模组写进去，玩家只管改等号后面那个值）：

```toml
[messages]
	#提示消息 / Reminder messages
	#本模组在聊天栏里主动打给你的那几行字。
	#The lines this mod prints into chat by itself.
	#每次进入游戏（单机世界、服务器）后，要不要在聊天栏弹出本模组的提醒消息。
	#Whether to show this mod's reminder messages in chat after you join a world or server.
	#
	#true  = 弹提醒（默认，方便确认模组有没有干活、卡在哪一步）。
	#false = 完全不弹，聊天栏干干净净；现场报告和逐行日志照样写，不影响排查问题。
	#true  = show them (default; handy to confirm the fix is doing its job).
	#false = stay silent; the report file and the per-startup log are still written.
	#
	#改完保存即可生效（重新进一次世界就会按新设置走）。
	#Save the file and it applies - the next world you join uses the new value.
	joinChatReminder = true

[logs]
	#日志 / Logs
	#游戏根目录 gtm_jei_logs 文件夹里「每次启动一份」的日志。
	#Per-launch log files inside the gtm_jei_logs folder of your game directory.
	#保留最近多少份启动日志。填 40 就只留最近 40 份，超过的旧文件由本模组自动删掉。
	#How many startup log files to keep. 40 = keep the 40 newest and delete older ones automatically.
	#
	#-1 = 一份都不删，全部保留（想留多少留多少，代价是文件夹会一直变大）。
	#0  = 只保留本次启动这一份。
	#正整数 N = 保留最近 N 份（本次这份一定在内，绝不会被自己删掉）。
	#-1 = never delete anything, keep every log file (the folder just keeps growing).
	#0  = keep only the log of this launch.
	#Any positive N = keep the N newest files; the file of this launch is always counted as kept.
	#
	#只删本模组自己生成的 gtm_jei_logs/startup-*.log，别的文件一个字节都不碰。
	#Only gtm_jei_logs/startup-*.log files created by this mod are ever deleted; nothing else is touched.
	#
	#改完保存后：本模组会立刻按新份数再清一次（不用重启游戏）。
	#After you change this, the mod prunes the folder again right away - no restart needed.
	# Default: 20
	# Range: -1 ~ 10000
	startupLogKeep = 20
```


几点要说清楚的：

- **关掉聊天提醒不损失任何排查能力**：`gtm_jei_fix_report.txt`、`gtm_jei_logs/` 那份逐行日志、
  游戏日志全都照写；报告里还会多一行 `[进世界提示] 已按配置关闭…`，用来证明这个开关确实读到了。
- **份数改完不用重启**：配置被重新读到时会自动再清一次，并在本次启动日志里记一行
  `[日志保留] 配置已重新读取：… 现有 N 份，按「留 M 份」删掉了 K 份更早的`。
- **本次正在写的这一份永远不会被删**（所以 `0` 的含义是「只留这一次」，不是「删光」）。
- 填错会被游戏自动纠正：类型不对/超出范围 → 回到合法值，并在文件里留一行说明，不会因此崩。
- 删掉这个文件不影响任何东西，下次启动自动生成默认值。

**为什么清理旧日志不在「建日志文件」那一刻做**（这是个真坑，改回去就会误删）：NeoForge 21.1 的
加载顺序是「构造模组 → 加载配置 → 注册 → CommonSetup」，构造函数里配置**还没读到**，
`ConfigValue#get()` 甚至直接抛 `IllegalStateException: Cannot get config value before config is loaded.`
（用 21.1.244 真 jar 跑过确认）。所以 r32 的做法是：取值一律走 `FixConfig` 的兜底方法（未加载时返回默认），
清理动作挂在 `ModConfigEvent.Loading/Reloading` 上。否则玩家把 20 改成 40，启动瞬间会先按 20 删掉一批——越改越少。

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
- **保留几份由配置文件决定**（r32 起，见上面「配置文件」一节）：默认与以前一样留最近 20 份，超出的自动删最旧——且只删自己创建的 `gtm_jei_logs/startup-*.log`，绝不碰别的文件；填 `-1` 就一份都不删，填 40 就留 40 份。**本次正在写的这一份永远不参与删除。**
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
- 自检命令（r29 新增）：`python3 tools/check_mod_metadata.py`。专门拦「代码改了、说明没跟着改」这一类：
  1. 三份元数据的 `${...}` 占位符是否都能在 `gradle.properties` 里找到（Gradle 的 `expand` 只认这张表）；
  2. 字符串定界是否合法——`'''` 是否闭合、HMCL 兼容副本有没有混进 `#` 注释或多行字符串、`mcmod.info` 展开后是否仍是**合法 JSON**；
  3. 每一项**已发布**的修复，是否在三份 `description` 里都提到了（漏哪份报哪份，r29 就是被这条抓出来的）；
  4. README 的标题/成品名/版本历程行、以及 `.blockforge-deliverable.json` 的 `path`，是否都和 `gradle.properties` 里的 `mod_version` 对齐；
  5. `[[mixins]]` 声明的三份 mixin 配置文件是否真实存在（声明了但文件不在＝补丁静默不生效）。
  它是**结构检查**不是通用 TOML 解析器：运行环境是 Python 3.10 且没有 `tomllib`/`tomli`，所以按我们实际用到的写法逐条核对；
  唯一真解析的是 `mcmod.info`（走标准库 `json`）。**教训**：这脚本第一次跑就报了 2 条 FAIL，查下来是被检查的文件没错、
  **检查器自己错了**（`re.search` 带了 `re.S`，`(.+)$` 一路吃到文件结尾，后面的定界符判断全失真）——
  报警先怀疑报警的人，别顺手去改真相。
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
| r16 | **每次启动留一份独立日志**：游戏根目录新建 `gtm_jei_logs/`，一次启动一个文件（`startup-yyyyMMdd-HHmmss.log`，同秒重开自动加 `-2` 后缀，绝不覆盖），逐行带 `HH:mm:ss.SSS` 时间戳、行到即落盘；文件头写启动时刻/Java/系统/最大堆/运行侧，尾部由关闭钩子补「进程退出」一行；只保留最近 20 份（仅删本模组自己的 `startup-*.log`；这个 20 从 r32 起变成配置项 `logs.startupLogKeep`，默认值仍是 20）。根目录 `gtm_jei_fix_report.txt` 照旧保留为「最新一份」 | — |
| r17 | **修「一点开配方页就崩」**：崩溃根因不在格雷也不在 JEI 本体——ModularUI 3.3.1 是对着 JEI 19.25 编译的，它的 `RecipeSlotAccessor` 按名字往 `RecipeSlot.allIngredients / displayIngredients` 两个 List 字段写数据；JEI 19.3x 起重构掉了这两个字段（用 modmaven 的 19.25/19.27/19.56/19.57 真 jar 逐版 javap 核对），`@Accessor` 定位失败在 sponge-mixin 里是硬错误 → `MixinApplyError` 崩游戏。本模组用独立 mixin 配置（`gtm_jei_startup_fix_jeicompat.mixins.json`，**priority 900：Mixin 自然顺序是数字小的先应用**——反编译 NeoForge 实用的 sponge-mixin 0.15.2 字节码 + 官方 `@Mixin` javadoc 双重核实，别凭直觉写更大的数；且查实单写配置 `priority` 不会传进 `MixinInfo.readPriority`，必须同时配 `mixinPriority`/注解 `priority`）+ 插件 `preApply`，在 `RecipeSlot` 类转换的最早时机用 ASM 把缺字段补回去；老 JEI（字段还在）原样跳过，没装 ModularUI 不补，任何异常只记日志不抛出。**教训**：sponge-mixin 分阶段应用——所有 mixin 的 `preApply` 先整体跑完，`@Accessor` 的字段定位在最后 ACCESSORS 阶段且读实时 `ClassNode`，所以补字段必然赶得上 | — |
| r18–r20 | 启动器封面一揽子：jar 根目录放 `logo.png` 并把 `logoFile` 指到它（游戏内 Mods 列表与各启动器都读得到）；额外写一份**无注释、无多行字符串**的 `META-INF/mods.toml` 兼容副本给只认旧文件名的 HMCL（已反汇编 FML 4.0.43 确认它不会因此把本模组判成 Forge 模组）；再补一份 `mcmod.info` 给 PCL2 社区版（官方 PCL2 根本不打开 jar，只按 sha1 联网配图，这一点已如实告知用户） | 启动器里能看到封面 |
| r24 | **修「JEI 里格雷的多方块 3D 结构图偏左上角」**：先按「JEI 把页面居中」的猜想用常量近似位移，用户实测仍有残留；改回按现场取值——从 `GuiGraphics` 位姿直接读出这块内嵌界面在屏幕上的真实原点，加到 MUI 设 GL 视口的入参上。**教训**：几何错位这种问题，拿常量去凑＝把「我算出的值」当成「游戏里真正用的值」，游戏分辨率一改变量就全变；凡是在绘制现场，就读现场的值（位姿、视口、窗口尺寸），不要猜常量 | — |
| r28 | **修「控制器里越靠下的缺失方块，展开『可使用的类型』那排方块越靠下、甚至超出屏幕」**：根因是 ModularUI 3.3.1 的弹出菜单方向写死（`Direction.DOWN → resizer.topRel(1f)`），全仓库没有任何贴屏幕边界/自动翻转的逻辑（`fitToScreen / clampToScreen / restrictToScreen / autoSize` grep 全为空），格雷也从不改方向，于是菜单顶边＝按钮底边，跟着行号一路开出屏幕。修法：在 MUI 排版链最后一拍 `postFullResize()` 的 TAIL 读现场值——菜单 `Area` 的 `x/y/width/height` 对比 `ModularScreen.getScreenArea()` 的**原点＋尺寸**（不是窗口尺寸），上/下/左/右四条边越出多少就挪多少，且**绝对坐标与相对偏移同步改**（绘制与鼠标命中都走 `rx/ry` 变换链，画面和点击一起回来）。同面板里有两层菜单时整块不动；连子节点列表都拿不到时整块停用。**另外两个教训**：① javadoc 里写 `LEFT_*/RIGHT_*` 这种含 `*/` 的字符串会**提前把注释关掉**，后面每个中文标点都变成 `illegal character`，一百条报错只指回一行——类型检查放过它，只有真编译能拦；② 「这个方法声明在哪一层」在没有 jar 可 `javap` 时属于**单一来源的事实**（ModularUI 是格雷 jar 里的 jar-in-jar），所以同一个回调向 `WidgetResizeNode` 与其父类 `WidgetNode` 各钉一条（钳位幂等，双命中无副作用），并在自检脚本里把没确证的那条如实报成「未确证（require=0）」，而不是为了输出好看塞进真值表 | — |
| r29 | **只改说明文字**：启动器与游戏内模组列表读的那三段 `description`（`neoforge.mods.toml` / HMCL 兼容副本 `mods.toml` / `mcmod.info`）里根本没提 r24 和 r28 这两项修复，玩家在游戏里看到的还是「修了启动崩溃和分类缺失」——补全为五项；顺带修掉描述里一句 r13 之前的旧说法（写的是「在 GTM 注册流程**出口**补齐」，而 r13 起早已改成**进门口 HEAD 接管**，因为原方法体半路抛异常根本走不到出口）。另在本文件新增「说明文字分别在哪」一节，列出这四份载体的格式约束（HMCL 那份不能带注释、mcmod.info 必须是合法 JSON），避免以后再漏。**另外加了一条自动闸门**：新脚本 `tools/check_mod_metadata.py`，专拦「修复改了、描述没跟着改」和「三份元数据格式被改坏」这两类（详见「构建」一节） | 修复逻辑与字节码一字未动；仍升版本号，因为描述文字打进 jar，不升就会出现「同名不同货」 |

| r30 | **重写「弹出菜单不出屏」的摆法**（两条 mixin 的挂钩位置一字未动）：r28 的规则是「越出可视区哪条边就往回挪多少」，落点只由「可视区远端 - 菜单高」决定 → 底部那一行的菜单顶边从 y=362 被挪到 y=309，**离自己那一行 37 像素，而且越界 1 像素和 200 像素都落到同一个位置**，玩家反映「越往下的行越是固定飘到一处」。r30 改成按**那一行的按钮**摆：从 `menu.resizer().getParent().getArea()` 拿按钮矩形（MUI 里 `relative(按钮)` 就是 `setParentOverride(按钮的节点)`），竖向四个候选位、横向两个——原样放得下就一个字节不动，放不下就翻到按钮另一侧（往上开），现场与候选位的差当作模组自己的 offset 保留。同时**改成写绝对值**（`y = 目标`、`ry = 目标 - 部件父.y`）：查清 MUI 的 `Area.applyPos` 用「排版父」把 `ry` 换算成 `y`，`StandardResizer.applyPos` 紧接着又把 `ry` 改写成「部件父」的相对值，而 `InternalWidgetTree.resize` 里 `isSelfFullyCalculated() \|\| resize(...)` 会让已算过的节点跳过规格重算——于是我们加进 `ry` 的位移会被下一次排版当成规格读走再推一遍；r28 看着稳定只是因为「贴边位恰好是那个反馈的不动点」。认不出朝向（`openCustom()` 自摆、父节点是整块面板）时退回 r28 的越界回挪，最坏情况与上一版一字不差；`resizer()/getParent()/getParentArea()` 也改成取不到只是不用这个能力，不再让 `Class.forName` 抛出去把整块校正停掉。**教训**：① 修「越界」不能只看落点在不在界内，还要看它**还属不属于原来那个控件**——脱离锚点的位置照样是 bug；② 往别人的排版字段里「加位移」之前，得先确认这个字段在下一个周期会不会被它自己当成规格值读回去，否则改的是反馈量不是位置 | — |
| r31 | **第二次重写「弹出菜单不出屏」**，并且第一次拿到两边的源码级凭据（格雷 `GregTechCEu/GregTech-Modern` 分支 `1.21`＝用户那个 `17e1700` 构建；ModularUI `brachy84/ModularUI-Modern` 分支 `1.21.1`＝`3.3.1-SNAPSHOT`）：缺失部件列表是一个 **130 高的可滚动 `ListWidget`**，而 `AbstractScrollWidget.transformChildren` 只做 `stack.translate(-scrollX, -scrollY)`，**滚动永远不写进子部件的绝对坐标**，菜单又正是照那份没滚动的坐标摆的 —— r28/r30 少的都是这一个量，所以两版都把菜单钉死在屏幕边同一个位置（r30 日志 8 条「比可视区高,尽量贴行」→ y=196），窗口小一点还会退回「可视区近端」= 玩家说的**顶到左上角**。r31：① 沿父链累加可滚动部件的 `getScrollX/getScrollY`（菜单自己那条链也累加一次再**相减**，共同父辈自动抵消）算出按钮**在屏幕上真正**的矩形；② 锚点改用类型判据（必须是 `AbstractMenuButton` 的实例，不再用「宽≥600 或高≥400」这种绝对像素阈值——小窗口里面板都比这阈值小）；③ 只承认「窗口本身那张屏」（读 MUI 自己的 `UIType.isScreen`，读不到才退回尺寸比对），JEI 里的内嵌虚拟屏一概不动；④ **删掉「往屏幕边贴」的兜底**，认不准就一个字节不改；⑤ 删掉目标类 `brachy.modularui.widget.WidgetNode` 的第二条挂钩（该版本 525 个 java 文件里查无此类，从 r28 到 r30 一次都没命中）。另给 r24 那条「读数不像屏幕坐标就不校正」的判据加上现场取证（最多 3 条，带当时的窗口尺寸）。**教训**：见「问题四」末尾三条 | — |
| r32 | **新增配置文件 `config/gtm_jei_startup_fix.toml`（注释中英双语）**，把玩家提的两件事变成可设置：① `messages.joinChatReminder` = 每次进入游戏时聊天栏弹的那几行提醒发不发（默认 true；关掉只影响聊天栏，报告与两份日志照写）；② `logs.startupLogKeep` = `gtm_jei_logs/` 保留几份启动日志（默认 20，也就是 r16～r31 一直写死的那个值；填 40 留 40 份，**填 -1 一份都不删**；本次正在写的这一份永远不参与删除）。用 NeoForge 自带的 `ModConfigSpec`（`net.neoforged.neoforge.common.ModConfigSpec`）而非自己解析文本：文件由游戏自动生成、注释由 `comment(...)` 写进 TOML、填错类型/超范围会被自动纠正而不是崩。**踩到并绕开的真坑**：NeoForge 21.1 的加载顺序是「构造模组 → 加载配置 → 注册 → CommonSetup」（反汇编 `net.neoforged.neoforge.internal.CommonModLoader` 看到 `loadConfigs(CLIENT)`/`loadConfigs(COMMON)` 在 `load()` 阶段），也就是**构造函数里配置还没读**，此时 `ConfigValue#get()` 直接抛 `IllegalStateException: Cannot get config value before config is loaded.`（用 21.1.244 真 jar 实测）。所以：取值全部走带兜底的方法（未加载返回默认），清理旧日志从「建日志文件时」改到 `ModConfigEvent.Loading/Reloading` 回调里做——否则玩家把 20 改成 40，启动瞬间会先按默认 20 删掉一批，越改越少；另外再留一条轮询兜底，防哪天事件不来。`ModConfigEvent` 是 `IModBusEvent`，只能站在模组总线上收（反汇编 `ModConfig#setConfig` 确认它走 `ModContainer.acceptEvent`），故 `@Mod` 构造函数改为注入 `IEventBus` | 只加配置与两处开关接线；五项修复的几何/注册逻辑一字未动 |

这几段经验一句话总结：**Mixin 的错要分成三种——「方法没匹配上」（可以 `require = 0` 降级成「这项不补」）、「类转换时解析不到类名」（整个模组加载失败，`require = 0` 毫无用处），以及 r12 学到的最阴险的一种：「挂钩钉对了，但目标方法体半路抛异常，TAIL 永远执行不到」——JEI 把插件异常吞掉记日志，表面上什么都不发生。要接管一个不可靠的方法，就得站在它进门口（HEAD）取消原方法自己重放，而不是等它走到出口（TAIL）。**

## 验证：看日志里这几行

启动后在日志里搜 `[gtm_jei_startup_fix]`，r13 起最要紧的几行（同样都会实时写进游戏根目录的 `gtm_jei_fix_report.txt`）：

```
[gtm_jei_startup_fix] 已加载（r29）：修启动崩溃 + 补格雷配方分类 + 现场报告。……
[gtm_jei_startup_fix] 挂钩命中（HEAD 接管）：GTM#registerCategories
[gtm_jei_startup_fix] 特殊分类「矿石处理流程图页」注册失败（跳过它，其余不受影响）：<真实异常>   ← 若某一步坏，异常原样贴出
[gtm_jei_startup_fix] 格雷配方分类页接管完成：新增 N 个（已存在跳过 0 个，失败 0 个）；配方由本模组按 GTM 的注册表代交。
[gtm_jei_startup_fix] [接管配方] 机器分类交给 JEI：N 个成功（其中空配方页 E 个）、失败 F、无页面跳过 S；特殊分类代交 K 个。
[gtm_jei_startup_fix] 诊断：JEI 现有配方分类 T 个，其中 gtceu 的 M 个（例：gtceu:macerator ……）；…… 接管情况：……
[兼容补丁] 已为 ModularUI 向 RecipeSlot 补回 2 个被新版 JEI 移除的字段（allIngredients/displayIngredients），配方页不再因此崩溃（JEI ……，ModularUI ……）   ← r17：第一次点开任意配方页时才出现这行
[多方块预览] 已生效：把 JEI 页面的屏幕原点 (112, 41) 补进了 3D 结构图的 OpenGL 视口，这张图不再偏左上角。   ← r24：第一次打开多方块结构页时出现
[弹出菜单不出屏] 挂钩已落地：找到 ModularUI 的菜单部件（先把那一行按钮的滚动位移减掉，再按按钮在屏幕上真正的位置摆；放不进可视区就完全不动）。   ← 第一次排版任何界面时出现
[弹出菜单不出屏] 第 1 次调整：菜单按它那一行的按钮重新摆放（分支：随滚动贴回那一行；现场 y=324 → 目标 y=138，位移 -186；按钮内容空间 306..324，滚动补偿 186，按钮真实位置 120..138，菜单 18x40，可视区 y=4..236（总高 240））   ← 每调整一次记一行，最多 8 条
[弹出菜单不出屏] 这不是「窗口自己那一屏」的界面（可视区 0,0 426x240，窗口 640x360，类型判据 EMBED（isScreen=false））——坐标与窗口不同源，r31 一律不动这个菜单。   ← 只在闸真的卡错时才需要看这条；正常不会连着出现
```


看什么：

- **`M` 应该远大于 0**：打开 JEI 按 R 搜「研磨」「装配」之类，应能看到格雷分类页与配方。
- **每一条「失败」都会带真实异常原文**——这一步坏在哪、什么异常，报告里一行一个，发回来即可逐条修。
- 如果 HEAD 挂钩仍没命中（GTM 又改了方法名），`[挂钩未命中]` 行后面会有一行真实的 GTM 方法签名清单（形如 `GTJEIPlugin = [registerCategories(...):void | ...]`），把它发回来就能一次改对。
- 如果装了 EMI，配方改由本模组代交，会另打一行 `[接管配方] 装了 EMI：……`。
- **r17**：如果一点开配方页就崩，看本次启动日志（`gtm_jei_logs/`）里有没有那行 `[兼容补丁]`——没有这行说明崩溃发生在补丁字段之外的新地方，把崩溃报告原样发回来。
- **r24**：开了 JEI 的格雷多方块结构页之后，报告里应当出现 `[多方块预览] 已生效：…… 屏幕原点 (x, y) ……`。那对括号里的数字就是「补丁认为的页面原点」——如果图还偏，把这两个数字连同截图一起发回来，一眼能判断是取值错了还是另有第二处位移。完全没有这行 = 两条 MUI mixin 没落地（ModularUI 版本结构与核对的 3.3.1 不同），进世界时聊天栏那行「多方块预览校正：……」会说明是哪一种。
- **r28 引入、r31 第二次重写**：打开一次控制器「缺失的方块」界面（或任意带弹出菜单的格雷界面），报告里应先出现 `[弹出菜单不出屏] 挂钩已落地`；某行菜单被挪动时，每调整一次多一行 `[弹出菜单不出屏] 第 N 次调整：…`。**这一行里有全套现场数字**：`分支`（"原样放得下 / 贴回按钮那一行 / 随滚动贴回那一行 / 翻到按钮上方 / 翻到按钮下方 / 两个候选位都放不下,不动 / 竖向朝向认不出,不动"）、`现场 y → 目标 y`、`按钮内容空间 起..止`、`滚动补偿`、`按钮真实位置 起..止`、`菜单 宽x高`、`可视区 起..止`。看这四件事就够定位：
  - **`滚动补偿` 有没有值**：这是 r31 新加的那一个量。列表滚下去以后它应该是个正的像素数；如果**永远是 0 而 `按钮内容空间` 明显在 `可视区` 外面**，说明这个 MUI 版本的滚动不走 `getScrollX/getScrollY` 这条路（那时我们只会「不动」，不会摆错）。
  - **`按钮真实位置` 对不对**：它应该正好是你鼠标停留那一行**在屏幕上**的纵向范围。对不上＝父链上还有别的平移没算进来。
  - **`分支` 是不是"翻到按钮上方"**：往下开不出来的行应该翻面；显示"不动"的几条属于上面「宁可不摆」那七种情况，聊天栏摘要里有对应的计数。
  - **`目标 y` 与按钮的关系**：往上开时 `目标 y + 菜单高` 应该等于按钮顶边（±格雷自己设的 offset）。
  进世界时聊天栏那行「弹出菜单校正：……」会报总次数与分类计数（挪动过几次 / 其中几次是靠滚动补偿 / 原位没动几次 / 完全没动几次——里面还单列「不是窗口本身那张屏（JEI 内嵌虚拟屏）」几条），写着「未落地」就是 ModularUI 的结构与本模组核对过的 3.3.1 不同，其余修复照旧生效。

## 已知边界

- **r17 字段补丁是「止血」不是「根治」**：补回去的两个字段成了只写不读的摆设——新版 JEI 画配方页改读自己的 `RecipeSlotIngredients`，配方页的打开/显示/翻页全部正常（初始内容走 JEI 公开 API，不受影响），受影响的只有「同一页内配料列表被 ModularUI 动态改写」这类刷新（如槽位上的循环角标）。真正的根治要等 ModularUI 适配新版 JEI：给 [ModularUI-Modern](https://github.com/brachy84/ModularUI-Modern) 报 issue，附上 `InvalidAccessorException: allIngredients ... RecipeSlot` 这段崩溃原文即可。
- 兜底分类页有**标题 + 图标 + 带槽位底的输入输出网格 + 动画箭头 + 耗时/EU·t 一行**；没有多方块机器动画、没有配方转移跳转（点输出跳到下一页）。
- 它显示的槽位内容来自 GTM 的配方能力链（与 GTM 自己的 EMI 桥接同一条数据链），取不到就少摆几格；页面仍然可搜、可点。
- **r24 的位置校正只补 GL 视口这一处**：ModularUI 在 JEI 里的整套部件坐标仍是「页内相对」，所以槽位悬停、提示、按钮点击维持原状（本来就是好的）；结构图内部的「按部件自身位置」那一点点拾取误差属于 MUI 自己的算法，没有一并改动。ModularUI 一旦原生适配这个场景（把 `updateGuiArea` 实现掉），本补丁读到的原点会被它自己用上，两条 mixin 的影响自然收敛。
- **r31 改的是「菜单这一帧摆在哪」，不是 ModularUI 的方向逻辑**：`Direction.DOWN` 仍然是 `DOWN`，
  我们只是在排版收尾那一刻按按钮**在屏幕上真正的位置**重摆一次（"往下的行放不下"就变成"往上开"）。
  本模组刻意不去改写 MUI 的方向枚举或 `resizer` 规格——那会同时改变所有界面的手感，风险远大于收益。
  r31 起「宁可不摆」的清单就是上面那七条，其中最要紧的两种：
  ① **同一个面板里有两层菜单时整块不动**（格雷在 JEI 预览页点候选方块会套一层子菜单）；
  ② **JEI 里内嵌的那份虚拟屏一概不动**（MUI 自己标着 `UIType.EMBED`）——它的坐标从 (0,0) 起算、真实位置靠 JEI
  的位姿平移（正是 r24 那件事），而且那份「可视区」是建页那一刻的窗口尺寸，玩家改过窗口大小之后整个过期。
  所以「在 JEI 的多方块预览页里点候选方块的子菜单」这一处仍是原版行为。
  根治方案还是那句：给 ModularUI 提 issue，请它在 `Direction` 里做「哪边空间大往哪边开」，
  并且把 `transformChildren` 的位移一并算进 `relative(...)` 的锚点。
- **这条的证据来源与残留风险**：ModularUI 是格雷 jar 里的 jar-in-jar，本地没有 jar 可 `javap`，
  所以下面这些事实取自**两边源码**（`brachy84/ModularUI-Modern` 分支 `1.21.1`＝`3.3.1-SNAPSHOT`；
  `GregTechCEu/GregTech-Modern` 分支 `1.21`，并且核对过 `PartAbilityError` 等文件在该分支与用户的
  `17e1700` 构建之间字节级一致）：`postFullResize()` 由 `WidgetResizeNode` 声明、`WidgetNode` 不存在、
  `Direction` 的六个定位器写法、`AbstractScrollWidget.transformChildren` 的平移、`ListWidget.layoutWidgets`
  的坐标累加、GTM 那个 166x130 的可滚动列表。仍然没有确证的是：用户机器上那份 jar 里这些类的
  **运行时字节码**与源码完全一致（版本号为 `3.3.1-SNAPSHOT` 的快照理论上可能与分支 HEAD 有差异）。
  因此挂钩依旧整块 `require = 0`，且所有摆放都必须先过「锚点类型 + 界面是不是窗口本身」两道闸；
  任何一环取不到就是**不动**，现场报告与聊天摘要会各留一行说明（不会崩，也不会误改别的界面）。
- 只在客户端生效：专用服务器上不产生任何效果。

## 万一还报 Mixin 相关错误

- 从 r7 起，补注册的三条尾巴全部「描述符钉死 + `require = 0`」，**结构对不上只会跳过并记日志，不再可能把模组加载搞崩**。
- 仍在硬要求命中的只剩图标拦截那两处（`getRenderable`）——那是让游戏能启动的地基，落空就等于回到 r5 之前的 NPE，宁可显式报错也不要静默无效。
- 真出现这类报错，把完整段落发回来即可；本地也可以先跑 `python3 tools/check_mixin_descriptors.py`，它就是照这个规则做的守卫。
