package cn.blockforge.gtmjeifix;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * GTM (GregTech: CEu Modern) 8.0.0-SNAPSHOT 启动崩溃修复 —— r28。
 *
 * <p><b>崩溃链（原文报错 {@code Cannot invoke "mezz.jei.api.runtime.IJeiRuntime.getJeiHelpers()"
 * because the return of "...GTJEIPlugin.getRuntime()" is null}）</b>：
 * GTM 的 {@code GTRecipeCategories} 在静态初始化时构造 {@code CategoryIcon}，
 * 其内部 {@code JeiCallWrapper.getRenderable(...)} 立刻调用
 * {@code GTJEIPlugin.getRuntime().getJeiHelpers().getGuiHelper()...}。
 * 这条链发生在 JEI 把 {@code IJeiRuntime} 交给 GTM 之前，于是空指针、游戏无法启动。
 *
 * <p><b>r5 的修法（一直在生效）</b>：JEI 未就绪时不返回 {@code null}，而是返回
 * {@link LazyIcons} 造的<b>占位图标</b>（实现 JEI 的 {@code IDrawable}，16x16 与真值一致）。
 * JEI 分类表缓存的就是这个占位对象本身，因此它在<b>被用到的那一刻</b>自动换成真实图标，
 * 与「就绪时刻」的先后顺序无关。{@code GTJEIPluginMixin}（{@code onRuntimeAvailable} 之后）与
 * {@link ClientIconFixupPolling}（客户端 tick）负责提前恢复，玩家看不到空档。
 *
 * <p><b>r8 的修法（一直在生效）</b>：GTM 这个快照把 {@code registerCategories} 里注册主配方
 * 分类的那行自己注释掉了（只注册 6 个特殊分类），玩家因此在 JEI 里看不到研磨、分离等页面。
 * {@code GTJEIPluginBackfillMixin} 在 GTM 注册出口把缺的分类用 {@code GtFallbackCategory} 补上，
 * RecipeType 取 GTM 自己 memoize 的 {@code GTRecipeJEICategory.TYPES}，与它后面交配方同一实例。
 * 编译期吃 JEI 官方 api jar（compileOnly、绝不打包），类名写错连 class 都产不出来。
 *
 * <p><b>r9 新增：现场自 reporting —— 为了终结「能进游戏，但不知道有没有效果」</b>：
 * <ol>
 * <li>{@link FixReport}：每个里程碑（挂钩命中/没命中、补了几个分类、图标恢复数、
 *     诊断卡在哪一步）实时写进<b>游戏根目录的 {@code gtm_jei_fix_report.txt}</b>。
 *     不用翻几万行日志，把这个小文件发回来就能定位。</li>
 * <li>玩家进世界后，聊天栏自动打两三行结论（生效没生效、下一步做什么）。</li>
 * <li>诊断探测改由客户端轮询<b>兜底驱动</b>：以前只在 GTM 的 {@code onRuntimeAvailable}
 *     回调命中后才跑——回调一旦因 JEI 版本差异没命中，就什么反馈都没有（r8 的盲区）。
 *     现在就算回调没命中，也会照常出结论、进报告。</li>
 * <li>编译期 JEI api 升到与用户实装同版本 <b>19.56.0.441</b>（已逐字节核对：
 *     本模组用到的接口在 19.27→19.56 之间零变化，行为与 r8 一致）。</li>
 * </ol>
 *
 * <p><b>r16 新增：每次启动留一份独立日志</b>——{@link StartupLog} 在游戏根目录的
 * {@code gtm_jei_logs/} 里按启动时刻建文件（{@code startup-yyyyMMdd-HHmmss.log}），
 * 逐行带时间戳、行到即落盘，<b>不会</b>被下一次启动顶掉；根目录那份
 * {@code gtm_jei_fix_report.txt} 照旧保留为「最新一份」，两份内容一致。
 *
 * <p><b>r17 新增：ModularUI ↔ 新版 JEI 的 RecipeSlot 兼容补丁</b>——玩家点开格雷配方页
 * （如搅拌机）必崩：{@code MixinApplyError / InvalidAccessorException: allIngredients}。
 * 根因（拿 modmaven 的 JEI 19.25/19.27/19.56/19.57 真 jar 逐版 javap 核对）：
 * ModularUI 3.3.1 编译对照的是 JEI 19.25，其 {@code RecipeSlotAccessor} 按名字往
 * {@code RecipeSlot} 的 {@code allIngredients}/{@code displayIngredients} 两个 List 字段写数据；
 * JEI 19.3x 起重构掉了这两个字段（19.56、19.57 实测均已不存在），@Accessor 定位失败是硬错误。
 * 修法：{@code JeiRecipeSlotCompatPlugin}（独立 mixin 配置，priority 900，
 * 小于 ModularUI 的默认 1000——Mixin 自然顺序是数字小的先应用）
 * 在 {@code RecipeSlot} 类转换的最早时机直接补回这两个字段，让 ModularUI 的 accessor 能落地；
 * 老 JEI 字段还在则原样跳过，零影响。详见该类注释。
 *
 * <p><b>r24 新增：修「JEI 里格雷的多方块 3D 结构图偏左上角」</b>——页面里的槽位、按钮、滑块都长在
 * 正确位置，只有中间那张结构图整体往左上角跑，点到的方块和看到的对不上。根因不在格雷也不在 JEI：
 * 那张图是 ModularUI 直接设 OpenGL 视口（{@code RenderSystem.viewport}）渲出来的，
 * <b>GL 视口是窗口像素级状态，不跟 {@code GuiGraphics} 的位姿走</b>；而 MUI 3.3.1 给 JEI 用的
 * 内嵌界面（{@code UIType.EMBED}，主面板 {@code pos(0,0)}）从头到尾不知道 JEI 把这块页面平移到了
 * 屏幕哪里（{@code EmbedHandler.EmbedWrapper.updateGuiArea} 在 3.3.1 里是个空方法），
 * 于是视口按「页面左上角＝窗口左上角」算，偏移量正好是配方页的原点。
 * 修法：{@code JeiEmbedWidgetMixin} 在 JEI 画内嵌界面的入口
 * （{@code ModularUIJeiCategory$UIWrapperWidget#drawWidget}）读到位姿平移量，
 * {@code MultiblockViewportShiftMixin} 把它加到 {@code Viewport.calculateOpenGLViewportFromRectangle}
 * 的入参上；两者都在独立的客户端配置 {@code gtm_jei_startup_fix_muicompat.mixins.json} 里，
 * {@code defaultRequire = 0}。同一个预览部件在真正的机器 GUI 里偏移恒为 0，行为不变。详见该类注释。
 *
 * <p><b>r28 引入、r31 重写的第四件事：修「控制器里缺失方块的『可使用的类型』菜单越往下越往下、
 * 甚至开到屏幕外」</b>——格雷在结构没长对时，会在控制器界面里逐行列出缺失的方块，每行旁边一个
 * {@code ContextMenuButton}，鼠标放上去展开「这个位置能用哪些方块」。两份源码合起来给出根因：
 * <pre>
 * GTM  WorkableMultiblockMachine.getMainTextPanel → new ListWidget&lt;&gt;().width(166).height(130)
 *                                                      ↑ 缺失部件列表：只有 130 高的<b>可滚动视口</b>
 * MUI  AbstractScrollWidget.transformChildren → stack.translate(-getScrollX(), -getScrollY())
 *      ↑ 滚动只是绘制/命中时的矩阵平移，<b>绝不回填进子部件的绝对坐标</b>
 * MUI  AbstractMenuButton.getMenu() → menu.resizer().relative(this) + Direction.DOWN（topRel(1f)）
 *      ↑ 菜单永远开在「没滚动」的按钮下方
 * </pre>
 * 于是玩家把列表滚到下面那些行（靠滚动它们是看得见的），菜单却按未滚动的坐标摆 → 行越靠下掉得越远。
 * 这也解释了玩家日志里那句「按钮 306..324、可视区 0..240」：按钮的绝对坐标确实在窗口外，
 * 但它在屏幕上看得见。<b>r28 与 r30 都少了这一个量</b>，所以两版只是把菜单钉到屏幕边某个固定位置。
 * {@code MenuKeepOnScreen}（钉在 {@code WidgetResizeNode#postFullResize} 的 TAIL）r31 的做法：
 * 沿父链累加可滚动部件的 {@code getScrollX()/getScrollY()}，把按钮矩形换算成<b>屏幕上真正的位置</b>
 * （菜单自己那条链也累加一次再相减，共同父辈的滚动自动抵消），再按真实矩形摆——
 * 原样放得下一个字节不改，放不下就翻到按钮另一侧（往上开）。三条不掰坏的硬条件：
 * 锚点必须是 {@code AbstractMenuButton} 的实例（不再用「宽高大过多少像素就算面板」这种绝对阈值）；
 * 这张界面必须是「窗口本身那张屏」（读 MUI 自己的 {@code getContext().getUIType().isScreen}；
 * JEI 里那份 {@code UIType.EMBED} 虚拟屏坐标与窗口不同源，摆不准就一概不动）；
 * 任何一环拿不准就<b>完全不动</b>——r28/r30 那种「实在放不下就往屏幕边贴」的兜底已删除，
 * 玩家说的「调过窗口大小后显示直接顶到左上角」正是它。坐标仍写绝对值，
 * 详见 {@link MenuKeepOnScreen} 的注释（{@code rx/ry} 在一拍里会被 {@code preApplyPos} 与
 * {@code applyPos} 换两种含义，而 {@code resize()} 只由模组侧触发才重算）。
 *
 * <p>任何一步结构对不上都只降级、不崩溃。对 GTM 全程按字符串类名反射访问；
 * 对 JEI 用官方 api jar 作 compileOnly 依赖，成品 jar 不打包它们。
 *
 * <p>版本历程（都是真实踩过的坑，别再改回去）：r6 回调参数写 {@code Object} → 描述符不符，加载期崩溃；
 * r7 回调参数写「凭印象的 JEI 接口名」→ 类名在 JEI 19 里不存在，转换 Mixin 类时崩溃；
 * r8 编译期用真 JEI api、描述符逐条对照真 jar 核实；
 * r9 加现场报告与聊天摘要（诊断不再只挂在 GTM 回调后面）；
 * r16 现场报告改为「每次启动一份」（游戏根目录 gtm_jei_logs/）；
 * r17 补 ModularUI 需要的 RecipeSlot 旧字段（修「点配方页就崩」）；
 * r24 把 JEI 页面的屏幕原点补进多方块 3D 预览的 GL 视口（修「结构图偏左上角」）；
 * r28 在 ModularUI 排版收尾处把开到屏幕外的弹出菜单拉回来（修「越靠下的缺失方块，
 * 『可使用的类型』菜单越靠下、甚至看不见」）；
 * r31 按格雷与 ModularUI 两边源码取证，给这条补上一直缺的那个「滚动位移」，
 * 并删掉贴屏幕边的兜底与一条目标类不存在的死挂钩（r28/r30 的「菜单钉死一处」
 * 「调窗口后顶到左上角」都出自这两处）。
 */
@Mod(GtmJeiStartupFix.MOD_ID)
public final class GtmJeiStartupFix {

    public static final String MOD_ID = "gtm_jei_startup_fix";

    private static final Logger LOGGER = LogUtils.getLogger();

    public GtmJeiStartupFix() {
        FixReport.start(FixReport.modVersion(MOD_ID));
        LOGGER.info("[gtm_jei_startup_fix] 已加载（{}）：修启动崩溃 + 补格雷配方分类 + 现场报告。"
                + "每一步干没干活都会实时写进游戏根目录的 "
                + FixReport.FILE_NAME + "，并且同步写一份到本次启动专属的 "
                + FixReport.logHint() + "（每次启动一份、不覆盖上一次，"
                + "完整路径 " + FixReport.logPath() + "）；进世界后聊天栏还会自动打两三行结论。"
                + "如果还是「没效果」，把那份文件的内容发回来就能直接定位卡在哪一步。"
                + "（编译期 JEI api 与实装同版本 19.56.0.441，接口逐字节核对无变化。）",
                FixReport.selfTag());
        FixReport.note("[已加载]（NeoForge " + FixReport.modVersion("neoforge")
                + "，JEI " + FixReport.modVersion("jei")
                + "，gtceu " + FixReport.modVersion("gtceu") + "）");
        FixReport.note("[兼容补丁] r17 的「RecipeSlot 字段补丁」已就位：第一次打开配方页时才生效，"
                + "生效时会在这份日志里记一条「已为 ModularUI 补回字段」；没记就是没触发（老 JEI 或没装 ModularUI）。");
        FixReport.note("[多方块预览校正] r24 的位置补丁已就位（客户端）：打开 JEI 的格雷多方块结构页时生效，"
                + "生效时会在这份日志里记一条「已生效」。当前 ModularUI 版本 "
                + FixReport.modVersion("modularui") + "。");
        FixReport.note("[弹出菜单不出屏] r28 引入、r30 与 r31 各重写一次摆放算法（客户端）："
                + "修「控制器里越靠下的缺失方块，展开『可使用的类型』那排方块时越靠下、甚至超出屏幕」。"
                + "r31 拿到两边源码的确证：缺失部件列表是一个 130 像素高的可滚动列表，"
                + "而 ModularUI 的滚动只是绘制/命中时的矩阵平移，子部件的绝对坐标永远不含滚动位移，"
                + "菜单又照着那份没滚动的坐标摆——r28 与 r30 都少了这一个量，"
                + "于是两版都把菜单钉死在屏幕边某一个位置上（r30 日志里 8 条「比可视区高,尽量贴行」就是它）。"
                + "r31 先把那一行按钮的滚动位移减掉（沿父链累加可滚动部件的 getScrollX/getScrollY，"
                + "菜单自己那条链也算一次再相减），再按按钮在屏幕上真正的位置摆："
                + "原样放得下一个字节不动，放不下就翻到按钮另一侧（往上开）。"
                + "同时删掉两样会掰坏界面的东西：「实在放不下就往屏幕边贴」的兜底"
                + "（玩家说的「调窗口后顶到左上角」就是这么来的），"
                + "以及那条目标类根本不存在的第二条挂钩（brachy.modularui.widget.WidgetNode 该版本查无此类）。"
                + "现在只承认「窗口本身那张屏」的界面（MUI 自己标的 UIType.EMBED 虚拟屏一概不动），"
                + "而且必须认出那一行是 AbstractMenuButton 才动手。"
                + "它挂在 ModularUI 排版收尾那一刻（WidgetResizeNode#postFullResize 的 TAIL），"
                + "要等你真的打开一次带这种菜单的界面才会记「挂钩已落地」；"
                + "之后每次调整各记一行「第 N 次调整：分支 / 现场 y / 目标 y / 按钮矩形 / 滚动补偿 / 菜单尺寸 / 可视区」，"
                + "最多 " + MenuKeepOnScreen.reportQuota() + " 条，其余只进日志文件。当前 ModularUI 版本 "
                + FixReport.modVersion("modularui") + "。");
        if (FMLEnvironment.dist.isClient()) {
            ClientIconFixupPolling.register();
        }
    }
}
