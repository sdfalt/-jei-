package cn.blockforge.gtmjeifix;

import com.mojang.blaze3d.platform.Window;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * r28 引入、r30 重写一次、<b>r31 按两边源码取证后再重写一次</b>：把格雷控制器里「可使用的类型」那排方块
 * 放回到<b>玩家真正看到的那一行</b>旁边。
 *
 * <h3>玩家看到的现象</h3>
 * <p>多方块结构没长对时，控制器的界面里逐行列出「缺了哪些方块」（一个可滚动的列表）。每行右边有个
 * 小按钮（格雷的 {@code ContextMenuButton}），鼠标放上去展开「这个位置能用哪些方块」。
 * <b>行越靠下，展开出来的那排方块位置也越靠下，最后直接开出屏幕外</b>——看不见也就点不着，
 * 玩家于是以为「查配方的功能坏了」。
 *
 * <h3>根因（r31 用格雷与 ModularUI 两边源码逐行确证，不再是推测）</h3>
 * <pre>
 * GTM  WorkableMultiblockMachine.getMainTextPanel
 *       → new ListWidget&lt;&gt;().width(166).height(130)   ← 缺失部件列表：130 高的<b>可滚动视口</b>
 * GTM  PartAbilityError.getPatternErrorUIModifier
 *       → Flow.row() + new ContextMenuButton&lt;&gt;(name).menuList(l -&gt; l.maxSize(40)...)
 *          menuList 内部 = new Menu&lt;&gt;().widthRel(1f).coverChildrenHeight().child(l)
 *                            ↑ 菜单宽＝按钮宽（日志里的 18），高＝40（滚动区上限）
 * MUI  ListWidget.layoutWidgets
 *       → child.getArea().setRelativePoint(axis, p)      ← 子部件坐标按<b>内容空间</b>累加
 * MUI  AbstractScrollWidget.transformChildren(stack)
 *       → stack.translate(-getScrollX(), -getScrollY())  ← 滚动<b>只是绘制/命中时的矩阵平移</b>，
 *                                                           绝不会回填到上面那份坐标里
 * MUI  AbstractMenuButton.getMenu()
 *       → menu.resizer().relative(this); Direction.DOWN → topRel(1f)
 *                                                         ← 菜单照<b>没滚动</b>的按钮矩形摆
 * </pre>
 * <p>合起来：玩家把列表<b>滚</b>到下面那些行（行本身靠滚动是看得见的），菜单却是按<b>没滚动</b>的坐标摆的。
 * 用户日志里那句「按钮 306..324、可视区 0..240」正是这件事——按钮的绝对坐标在窗口外，但它在屏幕上看得见。
 * <b>缺的那一个量就是滚动位移。</b>
 *
 * <h3>r28 / r30 为什么都没修好（r31 把两版的副作用一起去掉）</h3>
 * <ul>
 * <li><b>r28</b>：「越出可视区多少就往回挪多少」，落点只由屏幕边决定 → 底部各行一律黏到同一条边，
 * 脱离自己那一行；</li>
 * <li><b>r30</b>：改成按按钮矩形摆，可那个矩形仍然是没滚动的内容空间坐标 → 「原样位置」和
 * 「翻到按钮上方」<b>两个候选位全都落在屏幕外</b>（用户日志 8 条清一色「比可视区高,尽量贴行」），
 * 只能走兜底「可视区远端 - 菜单高」→ 各行仍然钉死在 y=196，玩家看到的还是没修好；窗口更小一些时
 * 兜底会退回「可视区近端」＝<b>左上角</b>，这就是「小→大→小之后显示直接顶到左上角」。
 * 另外 r30 判断「这个矩形像不像按钮」用的是绝对像素阈值（宽≥600 或高≥400 才算面板），
 * 小窗口里整块面板都比这阈值小，于是会把<b>面板</b>当成按钮来摆——同样是顶角落的成因之一。</li>
 * </ul>
 *
 * <h3>r31 的三条硬规矩</h3>
 * <ol>
 * <li><b>锚点先减掉滚动位移</b>：从按钮往上走到根，沿途每个可滚动部件问一次
 * {@code getScrollX()/getScrollY()}（MUI 里只有 {@code AbstractScrollWidget} 会平移子部件，
 * {@code IViewport.transformChildren} 默认是空实现，已确证）；再对菜单自己走一遍同样的链，
 * 两者相减 —— 父子共同的滚动自动抵消，不会重复补偿。得到按钮<b>在屏幕上真正占据</b>的矩形；</li>
 * <li><b>只认「真是菜单按钮」的锚点</b>：{@code menu.resizer().getParent()} 上的部件必须是
 * {@code brachy.modularui.widgets.menu.AbstractMenuButton} 的实例（按类名取 {@code Class} 判，
 * 字节码里不出现任何 MUI 类型）。这条有源码依据：{@code IPositioned} 里
 * {@code relative(IWidget w) → relative(w.resizer()) → resizer().relative(node)}，
 * 也就是说 {@code relative(按钮)} 存进排版父的就是<b>那个按钮自己的排版节点</b>
 * （{@code StandardResizer extends WidgetResizeNode}，所以 {@code getWidget()} 拿到的正是按钮部件）。
 * 另一个重载 {@code relative(Area)} 走的是 {@code AreaResizer}（一块矩形，没有 {@code getWidget()}），
 * 那种形态认不出部件 → 按规矩③完全不动。
 * {@code AbstractMenuButton} 这个类取不到时才退回面积判据（长宽都不到可视区一半）；</li>
 * <li><b>拿不准就一个字节都不动</b>：认不出按钮、按钮本体就不在界内、竖向或横向的朝向对不上、
 * 两个候选位都放不下、位移幅度不合理 —— 一律<b>保持 ModularUI 原样</b>。
 * r28/r30 那种「实在没办法就往屏幕边贴」的兜底全部删掉：
 * 玩家宁可看到原版的越界，也不要一个我们凭空摆出来的位置。</li>
 * </ol>
 *
 * <h3>摆放本身</h3>
 * <p>竖向 4 个候选位、横向 4 个候选位，一一对应 MUI {@code AbstractMenuButton.Direction} 的真实写法
 * （a＝按钮在屏幕上真正占据的矩形，w/h＝菜单宽高）：
 * <pre>
 * 竖向 0 顶边贴按钮底   a.bottom        （DOWN：topRel(1f)）
 * 竖向 1 底边贴按钮底   a.bottom - h    （UP：bottomRel(1f)）
 * 竖向 2 顶边贴按钮顶   a.y             （RIGHT__DOWN 这类横开菜单的 top(0)）
 * 竖向 3 底边贴按钮顶   a.y - h         （LEFT__UP 这类横开菜单的 bottom(0)）
 * 横向 0 左边对齐按钮左 a.x             （默认／widthRel(1f) 的对齐）
 * 横向 1 右边对齐按钮右 a.right - w     （LEFT__*：rightRel(1f)）
 * 横向 2 左边贴按钮右   a.right         （RIGHT__*：leftRel(1f)）
 * 横向 3 右边贴按钮左   a.x - w         （镜像位）
 * </pre>
 * 先用<b>没滚动</b>的按钮矩形把「原方向＋模组自己加的 offset」认出来（MUI 刚摆完时现场坐标必然落在某个
 * 候选位上，容差 2 像素），再把这套方向＋offset 作用到<b>滚动补偿后</b>的矩形上：
 * 原样位放得下就用原样位（等于一个字节不动），放不下就翻到按钮另一侧。两条轴各判各的，一条认不出不拖累另一条。
 *
 * <h3>写的仍然是绝对值</h3>
 * <p>MUI 一次排版的顺序是 {@code resize → preApplyPos → applyPos → postFullResize}
 * （{@code WidgetTree.resizeInternal} 确证），而 {@code Area.applyPos(px,py)} 把 {@code rx/ry}
 * 当成「相对<b>排版父</b>（对菜单就是那个按钮）」的规格读，紧接着 {@code StandardResizer.applyPos}
 * 又把 {@code rx/ry} 改写成「相对<b>部件父</b>（那块 {@code MenuPanel}）」——同一个字段在一拍里换两种含义，
 * 已算过的节点下一轮还不会被 {@code resize()} 重算规格。r28 往 {@code ry} 上加位移，等于把位移喂回下一轮
 * 当规格读走。r31 与 r30 一样：<b>每轮按现场的按钮矩形与滚动量重新算出绝对目标位</b>再写；
 * 绘制与命中走的是 {@code rx/ry} 变换链（{@code IWidget.transform} 里 {@code stack.translate(area.rx, area.ry)}），
 * 所以 {@code x/y} 与 {@code rx/ry} 必须同时写、指同一格。
 *
 * <h3>老保险一条没删</h3>
 * <ul>
 * <li>同一块面板里有两层菜单（父菜单＋子菜单）时整块不动：父子各挪各的会断线；</li>
 * <li>位移超过 512 像素不动，并记一条「可疑位移」——最后一道闸；</li>
 * <li>全程反射，取不到需要的类/方法/字段就整块停用（行为退回没打补丁）；反射连续失败 10 次也停用；</li>
 * <li>挂钩 {@code require = 0}：ModularUI 改了方法名/签名就整条跳过，绝不参与游戏崩溃。</li>
 * </ul>
 *
 * <h3>现场取数</h3>
 * <p>{@code FixReport.note} 每条校正都带「现场 y、目标 y、按钮内容空间矩形、滚动补偿、按钮真实矩形、
 * 菜单尺寸、可视区、走哪条分支」，最多记 8 条，其余只进日志文件。下一轮再报「位置还不对」时，
 * 只看「滚动补偿」那一项是 0 还是有值，就能判断这个 MUI 版本的滚动是不是我们确证的这一条路。
 */
public final class MenuKeepOnScreen {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** MUI 内部名：弹出菜单那个部件（GTM 的「可使用的类型」就是它）。 */
    static final String MENU_CLASS = "brachy.modularui.widgets.menu.Menu";

    /**
     * MUI 内部名：菜单按钮基类。只有 {@code menu.resizer().getParent()} 上的部件是它的实例，
     * 我们才承认「这个矩形就是玩家鼠标停的那一行的小按钮」。
     */
    static final String MENU_BUTTON_CLASS = "brachy.modularui.widgets.menu.AbstractMenuButton";

    /**
     * MUI 内部名：排版节点。r31 起<b>只</b>钉它一条——官方仓库 1.21.1 分支里
     * {@code postFullResize()} 正是它声明的（{@code WidgetResizeNode extends ResizeNode}，
     * 方法体 {@code super.postFullResize(); widget.postResize();}）。
     * r28/r30 的第二条「同类保险」钉在 {@code brachy.modularui.widget.WidgetNode} 上，
     * 而该版本 525 个 java 文件的全量清单里<b>查无此类</b>：那条从来没命中过，删。
     */
    static final String NODE_CLASS = "brachy.modularui.widget.sizer.WidgetResizeNode";

    /** MUI 内部名：部件公共接口，{@code getParent()/getChildren()} 这种人人都有、但声明在接口上的方法用它取。 */
    private static final String IWIDGET_CLASS = "brachy.modularui.api.widget.IWidget";

    /** 菜单边缘与可视区边缘至少留这么多（GUI 缩放坐标）。 */
    private static final int MARGIN = 4;

    /** 「现场坐标就是某个候选位」的容差（像素）；超过就认为这个方向认不出，宁可不摆。 */
    private static final int TOL = 2;

    /** 明显不合理的尺寸（未排版、或算出天量）直接不管，别把界面掰飞。 */
    private static final int MAX_SIZE = 4096;

    /**
     * 单次位移的上限（像素）。这条只是「拦住算疯了」的最后一道闸：
     * 合法补偿本来就可以很大（长列表滚到中间时，按钮的内容空间坐标能比窗口原点低几千像素），
     * 所以阈值放到任何真实屏幕都不可能达到的量级——比这更离谱的值只能是我们自己算错。
     */
    private static final int MAX_SHIFT = 4096;

    /** 反射连续失败这么多次就永久停用：结构对不上，再试只是白付异常的钱。 */
    private static final int MAX_FAILURES = 10;

    /** 现场报告里最多记几条校正（其余只进日志文件，别把报告刷满）。 */
    private static final int MAX_REPORTED = 8;

    /** 「坐标空间对不上」这条闸最多单独记几条（它不是校正，但下一轮判断闸是不是卡错了全靠它）。 */
    private static final int MAX_SPACER_REPORTS = 2;

    /** 往上走到根的最大层数（防环）。 */
    private static final int MAX_HOPS = 64;

    // ==== 反射缓存：只在第一次真正用到时解析，解析失败即停用 ====
    private static Class<?> menuType;
    /** 可能为 null（那个版本没有这个类）：那时退回「按钮矩形必须明显小于可视区」的面积判据。 */
    private static Class<?> menuButtonType;
    private static Method mdGetArea;
    private static Method mdGetScreen;
    private static Method mdGetScreenArea;
    /** {@code ModularScreen.getContext()} → {@code ModularGuiContext.getUIType()} → {@code UIType.isScreen}。 */
    private static Method mdGetContext;
    private static Method mdGetUIType;
    private static Field fdIsScreen;
    private static Method mdWidgetParent;
    private static Method mdGetChildren;
    private static Field fdChildren;
    private static Field fdX;
    private static Field fdY;
    private static Field fdRx;
    private static Field fdRy;
    private static Field fdWidth;
    private static Field fdHeight;
    /** 下面三个只服务「按按钮算位置」；任一个取不到就整块不动（r31 起没有贴边兜底了）。 */
    private static Method mdResizer;
    private static Method mdNodeParent;
    private static Method mdGetParentArea;

    private static boolean ready;
    private static volatile boolean disabled;
    private static int failures;

    /** 节点类 → 它的 {@code getWidget()}。取不到的类缓存 empty，不再重复找。 */
    private static final Map<Class<?>, Optional<Method>> WIDGET_ACCESSORS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 节点类 → 它的 {@code getArea()}。同上。 */
    private static final Map<Class<?>, Optional<Method>> NODE_AREAS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 部件类 → 它那对滚动 getter（MUI 里就是 {@code AbstractScrollWidget} 的 getScrollX/getScrollY）。 */
    private static final Map<Class<?>, ScrollGetters> SCROLL_ACCESSORS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 每个菜单实例判定出来的朝向（连同判定依据：按钮矩形、滚动补偿、菜单尺寸，任一变了即失效）。 */
    private static final Map<Object, Placement> PLACEMENTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    // ==== 以下只给现场报告 / 聊天摘要用 ====
    private static volatile boolean hookFired;
    private static volatile long checked;
    private static volatile long movedY;
    private static volatile long movedX;
    private static volatile long kept;
    /** 靠滚动补偿才摆对了几次（这条数为 0 就说明这个 MUI 版本没有可补的滚动位移）。 */
    private static volatile long scrollCorrected;
    /** 认不出按钮／朝向／本体越界，因而<b>完全没动</b>的次数（r31 起不再有贴屏幕边兜底）。 */
    private static volatile long untouched;
    /** 其中「这张界面不是窗口本身（JEI 里的内嵌虚拟屏）」拦下的次数。 */
    private static volatile long spaceSkipped;
    /** 两个候选位都放不下（菜单比可视区还长），因而没动的次数。 */
    private static volatile long noRoom;
    /** 位移幅度不合理，因而没动的次数。 */
    private static volatile long suspicious;
    private static volatile int lastShiftY;
    private static volatile int lastShiftX;
    private static volatile int lastScrollY;
    private static volatile int lastMenuH;
    private static volatile int lastScreenH;
    private static volatile int reported;
    private static volatile String lastFailure;
    /** 最近一次读到的窗口 GUI 尺寸（{@code matchesWindow} 里顺手记的，报告用）。 */
    private static volatile String lastWindow;

    private MenuKeepOnScreen() {}

    /** 现场报告里最多记几条校正（启动文案用，避免两处各写一个数字）。 */
    public static int reportQuota() {
        return MAX_REPORTED;
    }

    /**
     * 由 {@code MenuResizeClampMixin} 在 {@code postFullResize()} 结尾调用。传进来的是那个排版节点对象
     * 本身（Mixin 实例回调里 {@code this} 就是目标实例），我们只用反射问它要 {@code getWidget()}，
     * 非菜单部件立刻返回。
     */
    public static void afterResize(Object resizeNode) {
        if (disabled) {
            return;
        }
        hookFired = true;
        try {
            if (!ready && !resolve(resizeNode.getClass().getClassLoader())) {
                return;
            }
            Object widget = widgetOf(resizeNode);
            if (widget == null || !menuType.isInstance(widget)) {
                return;             // 绝大多数节点在这里就出去了
            }
            clamp(widget);
        } catch (Throwable t) {
            noteFailure(t);
        }
    }

    /**
     * 问排版节点要它承载的部件。必须按<b>实例自己的类</b>去找 {@code getWidget()}：
     * 拿另一个类的 {@code Method} 去 invoke 会抛 {@code IllegalArgumentException}，
     * 那种异常和「ModularUI 结构对不上」长得一模一样，会把整块校正误判成失效而停用。
     */
    private static Object widgetOf(Object node) {
        Method m = cached(WIDGET_ACCESSORS, node.getClass(), "getWidget");
        return m == null ? null : invokeOrNull(m, node);
    }

    /** 同上，按实例自己的类取排版节点的 {@code getArea()}。拿不到返回 null。 */
    private static Anchor areaOfNode(Object node) {
        Method m = cached(NODE_AREAS, node.getClass(), "getArea");
        Object area = m == null ? null : invokeOrNull(m, node);
        return area == null ? null : toAnchor(area);
    }

    // ==================== 核心 ====================

    private static void clamp(Object menu) throws Exception {
        checked++;
        Object parent = mdWidgetParent.invoke(menu);
        if (parent == null) {
            return;
        }
        if (hasSiblingMenu(parent, menu)) {
            untouched++;            // 父子两层菜单同面板：各挪各的会断线，整块不动
            return;
        }
        Object area = mdGetArea.invoke(menu);
        if (area == null) {
            return;
        }
        int x = read(fdX, area);
        int y = read(fdY, area);
        int w = read(fdWidth, area);
        int h = read(fdHeight, area);
        if (w <= 0 || h <= 0 || w > MAX_SIZE || h > MAX_SIZE) {
            return;                 // 还没排版 / 数值明显不对：一律不动
        }
        Object screen = mdGetScreen.invoke(menu);
        if (screen == null) {
            return;
        }
        Object screenArea = mdGetScreenArea.invoke(screen);
        if (screenArea == null || !area.getClass().isInstance(screenArea)) {
            return;                 // 不是一套单位（不是同一个 Area 类），不敢比
        }
        int sx = read(fdX, screenArea);
        int sy = read(fdY, screenArea);
        int sw = read(fdWidth, screenArea);
        int sh = read(fdHeight, screenArea);
        if (sw <= 0 || sh <= 0) {
            return;                 // 内嵌界面还没把 screen area 填好：不猜
        }
        lastScreenH = sh;
        lastMenuH = h;
        // 两道闸：这份 Area 到底是不是「窗口这一屏」。不是就直接不动，见 isRealWindowScreen() 的注释。
        if (!isRealWindowScreen(screen, sx, sy, sw, sh) || MultiblockEmbedOffset.isInsideEmbedDraw()) {
            untouched++;
            spaceSkipped++;
            if (spaceSkipped <= MAX_SPACER_REPORTS) {
                report("这不是「窗口自己那一屏」的界面（可视区 " + sx + "," + sy + " " + sw + "x" + sh
                        + "，窗口 " + lastWindowHint() + "，类型判据 " + uiTypeHint(screen)
                        + (MultiblockEmbedOffset.isInsideEmbedDraw() ? "，而且此刻正画在 JEI 的内嵌页里" : "")
                        + "）——坐标与窗口不同源，r31 一律不动这个菜单。");
            }
            return;
        }
        int nearY = sy + MARGIN;
        int farY = sy + sh - MARGIN;
        int nearX = sx + MARGIN;
        int farX = sx + sw - MARGIN;

        // ---- 规矩②：锚点必须是真正的菜单按钮 ----
        Object resizer = mdResizer == null ? null : invokeOrNull(mdResizer, menu);
        Object node = resizer == null ? null : invokeOrNull(mdNodeParent, resizer);
        if (node == null || node == menu) {
            untouched++;
            return;
        }
        Object button = widgetOf(node);
        if (button == null || button == menu) {
            untouched++;
            return;
        }
        Anchor raw = areaOfNode(node);          // 按钮的「内容空间」矩形（MUI 就是照它摆的）
        if (raw == null || raw.w <= 0 || raw.h <= 0 || raw.w > MAX_SIZE || raw.h > MAX_SIZE) {
            untouched++;
            return;
        }
        if (!looksLikeMenuButton(button, raw, sw, sh)) {
            untouched++;
            return;
        }

        // ---- 规矩①：把滚动位移减掉，得到按钮在屏幕上真正占据的矩形 ----
        int[] buttonScroll = scrollSum(button);
        int[] menuScroll = scrollSum(menu);
        if (buttonScroll == null || menuScroll == null) {
            untouched++;            // 有一层问不出来：整个补偿作废，宁可不摆
            return;
        }
        int compX = buttonScroll[0] - menuScroll[0];
        int compY = buttonScroll[1] - menuScroll[1];
        Anchor vis = new Anchor(raw.x - compX, raw.y - compY, raw.w, raw.h);
        lastScrollY = compY;
        if (!onScreen(vis, nearX, farX, nearY, farY)) {
            untouched++;            // 按钮本体就在界外：没有可信的落点，不动
            return;
        }

        // ---- 朝向：先用没补偿的矩形认，再用补偿后的（那是我们上一帧留下的样子） ----
        Placement p = placementFor(menu, raw, vis, compX, compY, x, y, w, h);
        if (p == null) {
            untouched++;
            return;
        }

        // ---- 两条轴各摆各的：放不下就不动 ----
        int targetY = y;
        int targetX = x;
        StringBuilder branch = new StringBuilder();

        if (p.vertical < 0) {
            branch.append("竖向朝向认不出,不动");
        } else {
            int natY = p.naturalY(h, vis);
            int flipY = p.flippedY(h, vis);
            if (fits(natY, h, nearY, farY)) {
                targetY = natY;
                branch.append(natY == y ? "原样放得下" : (compY == 0 ? "贴回按钮那一行" : "随滚动贴回那一行"));
            } else if (fits(flipY, h, nearY, farY)) {
                targetY = flipY;
                branch.append(p.vertical == 0 || p.vertical == 2 ? "翻到按钮上方" : "翻到按钮下方");
            } else {
                noRoom++;
                branch.append("两个候选位都放不下,不动");
            }
        }

        if (p.horizontal < 0) {
            branch.append(" 横向朝向认不出");
        } else {
            int natX = p.naturalX(w, vis);
            int flipX = p.flippedX(w, vis);
            if (fits(natX, w, nearX, farX)) {
                targetX = natX;
            } else if (fits(flipX, w, nearX, farX)) {
                targetX = flipX;                        // 横向越界：改贴按钮另一条竖边
                branch.append(" 横向翻面");
            } else {
                noRoom++;
                branch.append(" 横向放不下,不动");
            }
        }

        int deltaY = targetY - y;
        int deltaX = targetX - x;
        if (deltaY == 0 && deltaX == 0) {
            kept++;                 // 原位就放得下（或这条轴根本没判出来）：一个字节不动
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[gtm_jei_startup_fix] 弹出菜单原位核对：现场 ({}, {}) 尺寸 {}x{}，按钮真实位置 {}，"
                        + "滚动补偿 ({}, {})，无需调整（分支 {}）", x, y, w, h, vis, compX, compY, branch);
            }
            return;
        }
        if (Math.abs(deltaY) > MAX_SHIFT || Math.abs(deltaX) > MAX_SHIFT) {
            suspicious++;           // 最后一道闸：这个量级说明我们算错了，不动
            report("位移幅度可疑（" + deltaX + ", " + deltaY + "，超过 " + MAX_SHIFT + " 像素），保持原样："
                    + "现场 y=" + y + "，按钮内容空间 " + raw.y + ".." + raw.bottom()
                    + "，滚动补偿 " + compY + "，真实 " + vis.y + ".." + vis.bottom()
                    + "，菜单 " + w + "x" + h + "，可视区 y=" + nearY + ".." + farY);
            return;
        }

        writeAbsolute(menu, area, targetX, targetY);
        if (deltaY != 0) {
            movedY++;
            lastShiftY = deltaY;
        }
        if (deltaX != 0) {
            movedX++;
            lastShiftX = deltaX;
        }
        if (compX != 0 || compY != 0) {
            scrollCorrected++;
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[gtm_jei_startup_fix] 弹出菜单校正：现场 ({}, {}) 尺寸 {}x{}，按钮内容空间 {}，"
                            + "滚动补偿 ({}, {})，按钮真实位置 {}，可视区 y={}..{} → 目标 ({}, {})，位移 ({}, {})，分支 {}",
                    x, y, w, h, raw, compX, compY, vis, nearY, farY, targetX, targetY, deltaX, deltaY, branch);
        }
        report("菜单按它那一行的按钮重新摆放（分支：" + branch
                + "；现场 y=" + y + " → 目标 y=" + targetY + "，位移 " + deltaY
                + "；按钮内容空间 " + raw.y + ".." + raw.bottom() + "，滚动补偿 " + compY
                + "，按钮真实位置 " + vis.y + ".." + vis.bottom()
                + "，菜单 " + w + "x" + h + "，可视区 y=" + nearY + ".." + farY + "（总高 " + sh + "）"
                + (deltaX == 0 ? "" : "，横向位移 " + deltaX) + "）");
    }

    // ==================== 判据与小工具 ====================

    /** 放在 {@code pos}、长 {@code size} 的块是否完整落在 [near, far] 里。 */
    private static boolean fits(int pos, int size, int near, int far) {
        return pos >= near && pos + size <= far;
    }

    /**
     * 这份菜单所在的是不是「窗口自己那一屏」（而不是 JEI 里那种虚拟内嵌屏）。
     *
     * <p>首选<b>结构判据</b>：{@code screen.getContext().getUIType().isScreen}。MUI 3.3.1 里
     * {@code enum UIType { MODULAR_SCREEN(true), EMBED(false), NONE(false) }}，而
     * {@code ModularScreen.createEmbed(owner, panel)} 造出来的正是 {@code UIType.EMBED} 那一份——
     * 也就是说它自己就带着「我是一张虚拟屏」的标记，比任何尺寸比对都可靠。
     *
     * <p>为什么非判不可：内嵌页的坐标从 (0,0) 起算，真实位置是绘制时靠位姿平移过去的
     * （r24 修的「3D 结构图偏左上角」就是同一件事的另一面）；而且 {@code createEmbed} 拿的是
     * <b>建页那一刻</b>的窗口尺寸去 {@code onResize}，之后玩家改窗口大小，这份「可视区」就整个过期了。
     * 在这种界面上摆菜单，判据是假的，摆出来会被 JEI 裁掉，还可能把玩家说的「顶到左上角」再复现一遍。
     * 所以 r31：虚拟屏里的弹出菜单一概不动。
     *
     * <p>取不到这条链（版本改名、字段挪位置）时退回尺寸比对 {@link #matchesWindow}；
     * 两个都拿不到就<b>不动</b>。
     */
    private static boolean isRealWindowScreen(Object screen, int x, int y, int w, int h) {
        Boolean structural = uiTypeIsScreen(screen);
        if (structural != null) {
            return structural;
        }
        return matchesWindow(x, y, w, h);
    }

    /** 走 {@code getContext().getUIType().isScreen} 这条链；任何一环拿不到都返回 null（＝不知道）。 */
    private static Boolean uiTypeIsScreen(Object screen) {
        if (mdGetContext == null || mdGetUIType == null || fdIsScreen == null) {
            return null;
        }
        try {
            Object context = mdGetContext.invoke(screen);
            if (context == null) {
                return null;
            }
            Object uiType = mdGetUIType.invoke(context);
            if (uiType == null) {
                return null;
            }
            Object flag = fdIsScreen.get(uiType);
            return flag instanceof Boolean b ? b : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError t) {
            return null;
        }
    }

    /** 报告里把这条链读到的东西原样写出来（判据有没有卡错，一眼可辨）。 */
    private static String uiTypeHint(Object screen) {
        if (mdGetContext == null || mdGetUIType == null || fdIsScreen == null) {
            return "读不到 UIType";
        }
        try {
            Object context = mdGetContext.invoke(screen);
            Object uiType = context == null ? null : mdGetUIType.invoke(context);
            if (uiType == null) {
                return "UIType 为空";
            }
            return uiType + "（isScreen=" + fdIsScreen.get(uiType) + "）";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError t) {
            return "读 UIType 失败:" + t.getClass().getSimpleName();
        }
    }

    /**
     * 兜底判据：MUI 报给我们的这块「可视区」是不是就是真实窗口。
     *
     * <p>只在 {@link #isRealWindowScreen} 读不到 {@code UIType} 时才用它。内嵌在 JEI 页面里的那份界面
     * 是一张<b>虚拟屏</b>——它的坐标从 (0,0) 起算，而 JEI 把它平移到了屏幕上的某个位置
     * （r24 修的「3D 结构图偏左上角」根因就是这件事：
     * MUI 3.3.1 的 {@code EmbedHandler.EmbedWrapper.updateGuiArea(Rectangle)} 是个空方法，
     * 虚拟屏永远不知道自己被摆在哪儿）。在这种界面上：
     * <ul>
     * <li>菜单与按钮的坐标是<b>虚拟屏</b>的，可视区却是当年 {@code onResize} 传进去的窗口尺寸，
     * 两者不同源时「摆得下」这个判断就是假的，摆出来的位置会被 JEI 裁掉半截；</li>
     * <li>玩家小→大→小调过窗口之后，这份可视区还可能整个<b>过期</b>（虚拟屏不会再收到 onResize），
     * r30 那种「实在放不下就往界上贴」的写法就会把菜单钉到过期的边上——「顶到左上角」的另一条成因。</li>
     * </ul>
     * 所以 r31 只承认「与真实窗口重合」的这一种坐标空间（原点 (0,0)、尺寸与窗口的 GUI 缩放尺寸
     * 相差不到 8 像素），也就是玩家真正在用的机器/控制器界面。
     * JEI 里的内嵌页一概不动：宁可留着原版的越界，也不摆一个我们算不准的位置。
     *
     * <p>取不到窗口（极少数环境差异）时按「不重合」处理——同样是不动。
     *
     * <p><b>但这条挡不住「刚建好的内嵌页」</b>：{@code ModularScreen.createEmbed(owner, panel)} 内部就是
     * {@code onResize(window.getGuiScaledWidth(), window.getGuiScaledHeight())}，尺寸恰好与窗口一模一样。
     * 所以首选判据必须是结构标记 {@link #isRealWindowScreen}（{@code UIType.isScreen}），
     * 这条只当兜底。
     */
    private static boolean matchesWindow(int x, int y, int w, int h) {
        try {
            Window window = Minecraft.getInstance().getWindow();
            int gw = window.getGuiScaledWidth();
            int gh = window.getGuiScaledHeight();
            lastWindow = gw + "x" + gh;
            return x == 0 && y == 0 && Math.abs(w - gw) <= 8 && Math.abs(h - gh) <= 8;
        } catch (RuntimeException | LinkageError t) {
            lastWindow = "取不到窗口";
            return false;
        }
    }

    /** 报告里带一句窗口尺寸（判断上面那条闸有没有卡错全靠它）。 */
    private static String lastWindowHint() {
        return lastWindow == null ? "未知" : lastWindow;
    }

    /** 这个矩形有没有一部分落在可视区里（完全在界外就没有可信的落点可言）。 */
    private static boolean onScreen(Anchor a, int nearX, int farX, int nearY, int farY) {
        return a.x < farX && a.right() > nearX && a.y < farY && a.bottom() > nearY;
    }

    /**
     * 「这个矩形真是玩家鼠标停的那一行的小按钮吗」。
     * 首选按类型判（{@code AbstractMenuButton} 的实例——MUI 里 {@code relative(this)} 传的就是它）；
     * 那个类取不到时才退回面积判据：按钮的长宽都不得超过可视区的一半。
     * <b>绝不使用绝对像素阈值</b>：r30 用「宽≥600 或高≥400 才算面板」，小窗口里整块面板都比这阈值小，
     * 于是把面板当成按钮，摆出来的位置就直接贴到左上角。
     */
    private static boolean looksLikeMenuButton(Object button, Anchor raw, int screenW, int screenH) {
        if (menuButtonType != null) {
            return menuButtonType.isInstance(button);
        }
        return raw.w * 2 <= screenW && raw.h * 2 <= screenH;
    }

    /**
     * 沿父链累加滚动位移（＝这个部件的坐标在屏幕上会被往上/左推多少）。
     *
     * <p>MUI 里只有 {@code AbstractScrollWidget} 重写了 {@code transformChildren}
     * （接口默认实现是空的），而它的写法就是 {@code stack.translate(-getScrollX(), -getScrollY())}，
     * 所以「谁平移了子部件」等价于「谁有一对返回 int 的 {@code getScrollX()/getScrollY()}」。
     * 按这个特征问，不认类名，将来 MUI 改名最多是补不到位移（那时按钮真实矩形仍在界外 → 我们不动），
     * 不会摆出错位。
     *
     * @return 长度为 2 的数组（横向、纵向累计位移）；父链上有一层问不出来时返回 null（调用方整块不动）
     */
    private static int[] scrollSum(Object widget) {
        int sx = 0;
        int sy = 0;
        Object w = widget;
        try {
            for (int hop = 0; hop < MAX_HOPS; hop++) {
                Object p = mdWidgetParent.invoke(w);
                if (p == null || p == w) {
                    return new int[]{sx, sy};   // 走到根了
                }
                int[] s = scrollOf(p);
                if (s == null) {
                    return null;
                }
                sx += s[0];
                sy += s[1];
                w = p;
            }
        } catch (Throwable t) {
            return null;
        }
        return null;                            // 层数超限：宁可不算
    }

    /** 这个部件自己平移了多少（不是可滚动部件就是 0,0；问不出来返回 null）。 */
    private static int[] scrollOf(Object widget) {
        ScrollGetters g = scrollAccessors(widget.getClass());
        if (g.x == null || g.y == null) {
            return new int[]{0, 0};
        }
        try {
            // Method 只能 invoke 之后再取数（getInt 那是 Field 的 API）。
            // noArgInt() 已经限定返回类型是 int，这里再兜一次：拿到的不是数字就当问不出来。
            Object vx = g.x.invoke(widget);
            Object vy = g.y.invoke(widget);
            if (!(vx instanceof Number nx) || !(vy instanceof Number ny)) {
                return null;
            }
            return new int[]{nx.intValue(), ny.intValue()};
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按<b>实例自己的类</b>找那对滚动 getter：两个都在才算可滚动部件。 */
    private static ScrollGetters scrollAccessors(Class<?> owner) {
        ScrollGetters hit = SCROLL_ACCESSORS.get(owner);
        if (hit == null) {
            hit = new ScrollGetters();
            Method mx = noArgInt(owner, "getScrollX");
            Method my = noArgInt(owner, "getScrollY");
            if (mx != null && my != null) {
                hit.x = mx;
                hit.y = my;
            }
            SCROLL_ACCESSORS.put(owner, hit);
        }
        return hit;
    }

    private static Method noArgInt(Class<?> owner, String name) {
        try {
            Method m = owner.getMethod(name);
            if (m.getParameterCount() != 0 || m.getReturnType() != int.class) {
                return null;
            }
            try {
                m.setAccessible(true);
            } catch (Throwable ignored) {
                // public 方法在 public 类上，正常用不着 setAccessible
            }
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 写绝对值：{@code x/y} 直接给目标，{@code rx/ry} 按 MUI 自己 {@code applyPos} 的口径
     * ＝「目标 - 部件父原点」算（绘制与命中走的是 {@code rx/ry} 变换链）。
     * 部件父原点拿不到时退回「当前 x 减当前 rx」这个等价式。
     */
    private static void writeAbsolute(Object menu, Object area, int targetX, int targetY) throws Exception {
        int px = 0;
        int py = 0;
        Object parentArea = mdGetParentArea == null ? null : invokeOrNull(mdGetParentArea, menu);
        Anchor pa = parentArea == null ? null : toAnchor(parentArea);
        if (pa != null) {
            px = pa.x;
            py = pa.y;
        } else {
            px = read(fdX, area) - read(fdRx, area);
            py = read(fdY, area) - read(fdRy, area);
        }
        set(fdX, area, targetX);
        set(fdY, area, targetY);
        set(fdRx, area, targetX - px);
        set(fdRy, area, targetY - py);
    }

    // ==================== 锚点矩形与朝向 ====================

    /** 一个矩形（同一块 Area 上的四个数，避免各处反复反射读字段）。 */
    private static final class Anchor {
        final int x;
        final int y;
        final int w;
        final int h;

        Anchor(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        int bottom() {
            return y + h;
        }

        int right() {
            return x + w;
        }

        @Override
        public String toString() {
            return x + "," + y + " " + w + "x" + h;
        }
    }

    /**
     * 某个菜单实例判定出来的朝向。竖向 4 个候选位、横向 4 个候选位（定义见类注释的表）。
     *
     * <p>记住它的原因：我们的写回会让下一帧的现场坐标落在「补偿后」的候选位上，
     * 而 MUI 自己摆的位置落在「没补偿」的候选位上——两种都认，但必须先有一个稳定的朝向，
     * 否则同一个菜单会在「往下开／往上开」之间来回弹。
     */
    private static final class Placement {
        int anchorX;
        int anchorY;
        int anchorW;
        int anchorH;
        int compX;
        int compY;
        int menuW;
        int menuH;
        /** 0 顶贴按钮底｜1 底贴按钮底｜2 顶贴按钮顶｜3 底贴按钮顶；-1＝未知。 */
        int vertical = -1;
        /** 0 左对齐按钮左｜1 右对齐按钮右｜2 左贴按钮右｜3 右贴按钮左；-1＝未知。 */
        int horizontal = -1;
        /** 判定时现场坐标与候选位的差：那是模组自己加的 offset，作用到真实矩形时原样保留。 */
        int offsetY;
        int offsetX;

        boolean sameAs(Anchor raw, int cx, int cy, int w, int h) {
            return anchorX == raw.x && anchorY == raw.y && anchorW == raw.w && anchorH == raw.h
                    && compX == cx && compY == cy && menuW == w && menuH == h;
        }

        boolean usable() {
            return vertical >= 0 || horizontal >= 0;
        }

        static int candY(int v, int h, Anchor a) {
            return switch (v) {
                case 0 -> a.bottom();
                case 1 -> a.bottom() - h;
                case 2 -> a.y;
                case 3 -> a.y - h;
                default -> Integer.MIN_VALUE / 4;
            };
        }

        /** 竖向翻面：往下开的翻成「底边贴按钮顶」，往上开的翻成「顶边贴按钮底」。 */
        static int flipY(int v, int h, Anchor a) {
            return switch (v) {
                case 0 -> a.y - h;
                case 1, 2, 3 -> a.bottom();
                default -> Integer.MIN_VALUE / 4;
            };
        }

        static int candX(int hx, int w, Anchor a) {
            return switch (hx) {
                case 0 -> a.x;
                case 1 -> a.right() - w;
                case 2 -> a.right();
                case 3 -> a.x - w;
                default -> Integer.MIN_VALUE / 4;
            };
        }

        /** 横向翻面：贴右边的改成贴左边，对齐左边的改成对齐右边。 */
        static int flipX(int hx, int w, Anchor a) {
            return switch (hx) {
                case 0, 1 -> a.right() - w;
                case 2 -> a.x - w;
                case 3 -> a.right();
                default -> Integer.MIN_VALUE / 4;
            };
        }

        int naturalY(int h, Anchor a) {
            return candY(vertical, h, a) + offsetY;
        }

        int flippedY(int h, Anchor a) {
            return flipY(vertical, h, a) + offsetY;
        }

        int naturalX(int w, Anchor a) {
            return candX(horizontal, w, a) + offsetX;
        }

        int flippedX(int w, Anchor a) {
            return flipX(horizontal, w, a) + offsetX;
        }
    }

    /** 一对滚动 getter（都拿到才算这个部件会平移子部件）。 */
    private static final class ScrollGetters {
        Method x;
        Method y;
    }

    /**
     * 这个菜单实例该按哪个朝向摆。
     *
     * <p>三轮认定顺序：① 记忆（按钮内容空间矩形、滚动补偿、菜单尺寸三者都没变就直接复用）；
     * ② 用<b>没补偿</b>的按钮矩形比现场坐标（MUI 刚摆完时必然命中，顺便学出模组自己的 offset）；
     * ③ 用<b>补偿后</b>的矩形比（那是我们上一帧摆放留下的样子，此时 offset 记 0）。
     * 两条轴各认各的；一条都没认出来的那条轴，后面就不会动那条轴。
     */
    private static Placement placementFor(Object menu, Anchor raw, Anchor vis, int compX, int compY,
                                         int x, int y, int w, int h) {
        Placement known = PLACEMENTS.get(menu);
        if (known != null && known.sameAs(raw, compX, compY, w, h)
                && known.vertical >= 0 && known.horizontal >= 0) {
            return known;
        }
        int v = -1;
        int offY = 0;
        int mv = matchY(y, h, raw);
        if (mv >= 0) {
            v = mv;
            offY = y - Placement.candY(mv, h, raw);
        } else {
            int mv2 = matchY(y, h, vis);
            if (mv2 >= 0) {
                v = mv2;
            } else if (known != null && known.sameAs(raw, compX, compY, w, h)) {
                v = known.vertical;
                offY = known.offsetY;
            }
        }
        int hx = -1;
        int offX = 0;
        int mh = matchX(x, w, raw);
        if (mh >= 0) {
            hx = mh;
            offX = x - Placement.candX(mh, w, raw);
        } else {
            int mh2 = matchX(x, w, vis);
            if (mh2 >= 0) {
                hx = mh2;
            } else if (known != null && known.sameAs(raw, compX, compY, w, h)) {
                hx = known.horizontal;
                offX = known.offsetX;
            }
        }
        if (v < 0 && hx < 0) {
            return null;            // 两条轴都对不上：这个菜单不是我们认得的摆法，不动
        }
        Placement p = known == null || !known.sameAs(raw, compX, compY, w, h) ? new Placement() : known;
        p.anchorX = raw.x;
        p.anchorY = raw.y;
        p.anchorW = raw.w;
        p.anchorH = raw.h;
        p.compX = compX;
        p.compY = compY;
        p.menuW = w;
        p.menuH = h;
        if (v >= 0) {
            p.vertical = v;
            p.offsetY = offY;
        }
        if (hx >= 0) {
            p.horizontal = hx;
            p.offsetX = offX;
        }
        PLACEMENTS.put(menu, p);
        return p;
    }

    /** 现场 y 落在哪个竖向候选位上（0..3），都不像返回 -1。 */
    private static int matchY(int y, int h, Anchor a) {
        int best = -1;
        int bestDiff = Integer.MAX_VALUE;
        for (int v = 0; v <= 3; v++) {
            int d = Math.abs(y - Placement.candY(v, h, a));
            if (d < bestDiff) {
                bestDiff = d;
                best = v;
            }
        }
        return bestDiff <= TOL ? best : -1;
    }

    /** 现场 x 落在哪个横向候选位上（0..3），都不像返回 -1。 */
    private static int matchX(int x, int w, Anchor a) {
        int best = -1;
        int bestDiff = Integer.MAX_VALUE;
        for (int hx = 0; hx <= 3; hx++) {
            int d = Math.abs(x - Placement.candX(hx, w, a));
            if (d < bestDiff) {
                bestDiff = d;
                best = hx;
            }
        }
        return bestDiff <= TOL ? best : -1;
    }

    // ==================== 现场报告 ====================

    private static void report(String line) {
        if (reported >= MAX_REPORTED) {
            return;                 // 报告里只留前几条，其余进日志文件
        }
        reported++;
        FixReport.note("[弹出菜单不出屏] 第 " + reported + " 次调整：" + line);
    }

    // ==================== 兄弟菜单判定 ====================

    /** 同一块面板里还有第二个菜单（父菜单＋子菜单）→ 这次完全不动，避免父子错位。 */
    private static boolean hasSiblingMenu(Object parent, Object menu) throws Exception {
        Object children = mdGetChildren == null ? null : invokeOrNull(mdGetChildren, parent);
        if (children == null && fdChildren != null) {
            children = fdChildren.get(parent);
        }
        if (!(children instanceof List<?> list) || list.size() < 2) {
            return false;
        }
        int menus = 0;
        for (Object o : list) {
            if (o != null && menuType.isInstance(o)) {
                menus++;
                if (menus > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==================== 数值字段读写（不假定 int 还是 float） ====================

    /** 读一个数值字段，四舍五入成整数像素。 */
    private static int read(Field f, Object o) throws IllegalAccessException {
        Class<?> t = f.getType();
        if (t == int.class) {
            return f.getInt(o);
        }
        if (t == float.class) {
            return Math.round(f.getFloat(o));
        }
        if (t == double.class) {
            return (int) Math.round(f.getDouble(o));
        }
        return f.getInt(o);      // resolve() 已挡掉非数值类型，正常走不到这里
    }

    /** 写一个数值字段为绝对值，按字段自身类型写。 */
    private static void set(Field f, Object o, int v) throws IllegalAccessException {
        Class<?> t = f.getType();
        if (t == int.class) {
            f.setInt(o, v);
        } else if (t == float.class) {
            f.setFloat(o, v);
        } else if (t == double.class) {
            f.setDouble(o, v);
        }
    }

    /** 把一个 Area 读成矩形；不是 Area 类或字段读不到时返回 null。 */
    private static Anchor toAnchor(Object area) {
        if (area == null) {
            return null;
        }
        try {
            return new Anchor(read(fdX, area), read(fdY, area), read(fdWidth, area), read(fdHeight, area));
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field numericField(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getField(name);
        Class<?> t = f.getType();
        if (t != int.class && t != float.class && t != double.class) {
            throw new NoSuchFieldException(name + " 不是数值字段（实际 " + t.getName() + "）");
        }
        return f;
    }

    /**
     * 按<b>实例自己的类</b>找那个 public 无参方法（每个 cache 只服务一个方法名，所以键只需要类）。
     * 找不到就缓存 empty（不算失败，只是这块能力没有）。
     */
    private static Method cached(Map<Class<?>, Optional<Method>> cache, Class<?> owner, String name) {
        Optional<Method> hit = cache.get(owner);
        if (hit == null) {
            try {
                hit = Optional.of(owner.getMethod(name));
            } catch (Throwable ignored) {
                hit = Optional.empty();
            }
            cache.put(owner, hit);
        }
        return hit.orElse(null);
    }

    private static Object invokeOrNull(Method m, Object target) {
        try {
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 反射解析 ====================

    /**
     * 只做一次；任何一步取不到就停用整块校正（等同没打补丁）。
     *
     * @param preferred 加载排版节点用的那个 class loader。ModularUI 是格雷 jar 里的 jar-in-jar，
     *                  由模组自己的 loader 提供；用「现场对象的 loader」优先，才不会出现
     *                  「我们加载到另一个 Class、反射读到的字段却是另一份」这种事。
     */
    private static synchronized boolean resolve(ClassLoader preferred) {
        if (disabled) {
            return false;
        }
        if (ready) {
            return true;
        }
        try {
            Class<?> menu = need(MENU_CLASS, preferred);
            Class<?> area = need("brachy.modularui.widget.sizer.Area", preferred);
            Class<?> iwidget = need(IWIDGET_CLASS, preferred);

            menuType = menu;
            mdGetArea = menu.getMethod("getArea");
            mdGetScreen = menu.getMethod("getScreen");
            mdWidgetParent = iwidget.getMethod("getParent");
            Class<?> screenCls = need("brachy.modularui.screen.ModularScreen", preferred);
            mdGetScreenArea = screenCls.getMethod("getScreenArea");
            // 结构判据（r31）：screen.getContext().getUIType().isScreen。
            // MUI 3.3.1 里 enum UIType { MODULAR_SCREEN(true), EMBED(false), NONE(false) }，
            // 而 ModularScreen.createEmbed(...) 造的正是 EMBED 那份虚拟屏——它自己就带着标记。
            // 这条链任何一环取不到都只是「退回尺寸比对」（isRealWindowScreen），不会停掉整块校正。
            mdGetContext = findNoArgMethod(screenCls, "getContext");
            Class<?> ctxCls = returnTypeOrNull(mdGetContext);
            mdGetUIType = ctxCls == null ? null : findNoArgMethod(ctxCls, "getUIType");
            Class<?> uiTypeCls = returnTypeOrNull(mdGetUIType);
            fdIsScreen = uiTypeCls == null ? null : findField(uiTypeCls, "isScreen");

            // 子节点列表：优先 getChildren()，没有就退到 children 字段。
            // 两者都拿不到 → 无法判断是否存在嵌套菜单，宁可不掰（见类注释）。
            mdGetChildren = findNoArgMethod(iwidget, "getChildren");
            fdChildren = findField(menu, "children");
            if (mdGetChildren == null && fdChildren == null) {
                throw new NoSuchMethodException("取不到子节点列表（getChildren()/children 都没有）");
            }

            fdX = numericField(area, "x");
            fdY = numericField(area, "y");
            fdRx = numericField(area, "rx");
            fdRy = numericField(area, "ry");
            fdWidth = numericField(area, "width");
            fdHeight = numericField(area, "height");

            // 「按按钮算位置」这套能力在 r31 已经是<b>必需</b>的（没有贴边兜底了）：取不到就整块停用。
            menuButtonType = findClass(MENU_BUTTON_CLASS, preferred);
            mdResizer = findNoArgMethod(menu, "resizer");
            mdNodeParent = findMethodOf("brachy.modularui.widget.sizer.ResizeNode", "getParent", preferred);
            mdGetParentArea = findNoArgMethod(iwidget, "getParentArea");
            if (mdResizer == null || mdNodeParent == null) {
                throw new NoSuchMethodException("取不到菜单的排版父节点（resizer()/ResizeNode.getParent()），"
                        + "r31 起没有可信锚点就完全不动，所以整块停用");
            }

            ready = true;
            LOGGER.info("[gtm_jei_startup_fix] 弹出菜单不出屏校正：已挂上 ModularUI 的菜单排版"
                    + "（ModularUI {}，坐标字段 {}，按钮类型判据 {}）。", FixReport.modVersion("modularui"),
                    fdY.getType() == int.class ? "int" : fdY.getType().getSimpleName(),
                    menuButtonType == null ? "退化为面积判据" : "AbstractMenuButton");
            FixReport.note("[弹出菜单不出屏] 挂钩已落地：找到 ModularUI 的菜单部件（先把那一行按钮的"
                    + "滚动位移减掉，再按按钮在屏幕上真正的位置摆；放不进可视区就完全不动）。");
            return true;
        } catch (Throwable t) {
            disabled = true;
            lastFailure = t.toString();
            LOGGER.debug("[gtm_jei_startup_fix] 弹出菜单不出屏校正停用（ModularUI 结构与核对过的 3.3.1 不同，"
                    + "其余功能不受影响）：{}", t.toString());
            FixReport.note("[弹出菜单不出屏] 未落地：在 ModularUI 里找不到需要的菜单部件或字段"
                    + "（" + t.getClass().getSimpleName() + ": " + t.getMessage()
                    + "）。行为与没装本补丁时一致。");
            return false;
        }
    }

    /** 按类名找类：找不到就抛（必需能力用它，走调用方的停用流程）。 */
    private static Class<?> need(String name, ClassLoader preferred) throws ClassNotFoundException {
        Class<?> c = Refl.load(name, preferred);
        if (c == null) {
            throw new ClassNotFoundException(name);
        }
        return c;
    }

    /** 按类名找类：找不到只返回 null（用于「两种形态都兼容」的可选判据）。 */
    private static Class<?> findClass(String name, ClassLoader preferred) {
        return Refl.load(name, preferred);
    }

    /** {@code Method.getReturnType()} 要把那个类装载进来，失败就只能当「不知道」。 */
    private static Class<?> returnTypeOrNull(Method m) {
        if (m == null) {
            return null;
        }
        try {
            Class<?> t = m.getReturnType();
            return t == void.class || t.isPrimitive() ? null : t;
        } catch (LinkageError ignored) {
            return null;
        }
    }

    /** 找一个 public 无参方法，没有就返回 null。 */
    private static Method findNoArgMethod(Class<?> owner, String name) {
        try {
            return owner.getMethod(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 按<b>类名</b>找一个 public 无参方法：类不存在也<b>只返回 null</b>，
     * 不能让 {@code Class.forName} 抛出去把整块校正停掉（r30 的教训）。
     */
    private static Method findMethodOf(String className, String name, ClassLoader preferred) {
        Class<?> owner = findClass(className, preferred);
        return owner == null ? null : findNoArgMethod(owner, name);
    }

    /** 找一个 public 字段，没有就返回 null。类型是否可用留给调用方判断。 */
    private static Field findField(Class<?> owner, String name) {
        try {
            return owner.getField(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void noteFailure(Throwable t) {
        failures++;
        lastFailure = t.toString();
        if (failures == 1) {
            LOGGER.debug("[gtm_jei_startup_fix] 弹出菜单校正第一次出错：{}", t.toString());
        }
        if (failures >= MAX_FAILURES && !disabled) {
            disabled = true;
            LOGGER.warn("[gtm_jei_startup_fix] 弹出菜单校正已停用（连续 {} 次出错，最后一条 {}）；"
                    + "菜单会回到「开到屏幕外」的原样，游戏与其余修复不受影响。", failures, t);
            FixReport.note("[弹出菜单不出屏] 停用：校正过程连续 " + failures + " 次出错（" + t
                    + "）。菜单会回到未打补丁的样子，其余功能不受影响。");
        }
    }

    /** 给现场报告 / 聊天摘要用的一行结论。 */
    public static String statusLine() {
        if (disabled) {
            return "弹出菜单校正：已停用" + (lastFailure == null ? ""
                    : "（" + lastFailure + "）") + "，菜单行为回到未打补丁的原样";
        }
        if (!ready) {
            return "弹出菜单校正：还没开始（打开一次带缺失方块的控制器界面就会落地）";
        }
        long moved = movedY + movedX;
        StringBuilder sb = new StringBuilder();
        if (moved > 0) {
            sb.append("弹出菜单校正：已调整 ").append(moved).append(" 次")
                    .append("（最近一次纵向 ").append(lastShiftY)
                    .append(" 横向 ").append(lastShiftX).append(" 像素，其中 ")
                    .append(scrollCorrected).append(" 次是靠滚动位移补偿摆回来的（最近补偿 ")
                    .append(lastScrollY).append(" 像素）；原位就放得下、一个字节没动的 ").append(kept)
                    .append(" 次；菜单 ").append(lastMenuH).append(" 高／可视区 ").append(lastScreenH)
                    .append(" 高；检查过 ").append(checked).append(" 个菜单）");
        } else if (hookFired) {
            sb.append("弹出菜单校正：挂钩已落地，本次没有需要挪的菜单（检查过 ")
                    .append(checked).append(" 个，原位放得下 ").append(kept).append(" 个）");
        } else {
            sb.append("弹出菜单校正：未触发（没打开过带菜单的界面，或 ModularUI 的菜单类与本模组核对过的 3.3.1 不同）");
        }
        if (untouched > 0 || noRoom > 0 || suspicious > 0) {
            sb.append("；有 ").append(untouched).append(" 次完全没动");
            if (spaceSkipped > 0) {
                sb.append("（其中 ").append(spaceSkipped)
                        .append(" 次是因为那张界面不是窗口本身（JEI 里的内嵌虚拟屏），见报告里的原话）");
            }
            sb.append("、").append(noRoom).append(" 次因菜单比可视区长而没动");
            if (suspicious > 0) {
                sb.append("、").append(suspicious).append(" 次因位移可疑而没动");
            }
            sb.append("（r31 起宁可不挪也不往屏幕边上贴）");
        }
        return sb.toString();
    }
}
