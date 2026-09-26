package cn.blockforge.gtmjeifix;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * r24：记录「格雷多方块结构预览被 JEI 摆在屏幕的哪个位置」。
 *
 * <h3>要修的现象</h3>
 * <p>在 JEI 里翻格雷的多方块结构页（电力高炉、蒸馏塔那一类），页面里的槽位、按钮、滑块都在正确位置，
 * 只有<b>中间那张 3D 结构图</b>整体往<b>左上角</b>跑，点到的方块和看到的对不上。
 *
 * <h3>根因（三方源码逐段核对，依据见末尾「依据」栏）</h3>
 * <p>那张 3D 图不是贴图，是真的用 OpenGL 渲出来的。渲染链路：
 * <pre>
 * GTM  MultiblockPreviewWidget（200x180 的 ModularUI 部件树）
 *  └─ MUI  SchemaWidget.draw()
 *      └─ MUI  BaseSchemaRenderer.draw(context, x, y, w, h, theme)
 *          └─ MUI  Viewport.calculateOpenGLViewportFromRectangle(x', y', w, h)
 *              └─ RenderSystem.viewport(...)     ← 直接设 GL 视口
 * </pre>
 * <p>关键点：<b>GL 视口是窗口像素级的状态，不跟着 {@code GuiGraphics} 的位姿（pose）走</b>。
 * JEI 画一张配方页的约定（JEI 19.56 源码 {@code RecipeLayoutDrawableErrored}、
 * {@code OffsetJeiInputHandler} 两处都是这个写法）是：先把位姿平移到配方页在屏幕上的绝对位置，
 * 再把鼠标坐标<b>减去</b>这个位置、以「页内相对坐标」交给部件去画。
 * 于是走位姿的 2D 内容（槽位、文字、按钮）自然落在正确位置；
 * 而 3D 结构图的位置是靠 MUI 自己的坐标栈算的——那份页面是一个 {@code UIType.EMBED} 的「虚拟屏」，
 * 主面板 {@code pos(0,0)}、屏幕区域原点也是 {@code (0,0)}，
 * MUI 从头到尾<b>不知道 JEI 把这块虚拟屏平移到了哪里</b>
 * （MUI 3.3.1 里 {@code EmbedHandler.EmbedWrapper.updateGuiArea(Rectangle)} 是个空方法）。
 * 结果视口按「页面左上角＝窗口左上角」来算，图整体偏到左上角，偏移量正好等于配方页的原点。
 *
 * <h3>本类的作用</h3>
 * <p>只当一块<b>共享黑板</b>：进入 JEI 里那块内嵌界面绘制的瞬间，把当时的位姿平移量
 * （＝这块界面在屏幕上的真实原点，单位 GUI 缩放坐标）写上去；MUI 算 GL 视口时把它加上。
 * 不在「内嵌绘制中」时读出来恒为 0，所以在真正的机器 GUI 里（用的是同一个预览部件）
 * 行为与没打补丁时一字不差。
 *
 * <h3>为什么是「一次覆盖＋限时有效」而不是一叠保存值</h3>
 * <p>绘制只发生在客户端渲染线程，同一条链上是严格的先进后出，本不需要栈；
 * 但如果 JEI 在 {@code drawWidget} 中途抛异常，挂在 TAIL 的收尾就不会跑，
 * 一块「脏」的原点会留到下一次——那有可能已经是玩家机器 GUI 里的一次绘制，反而把好的画歪。
 * 现在这两条各挡一半：① 每次 {@link #push} 都<b>整块覆盖</b>，脏值最多活一次；
 * ② {@link #x()}/{@link #y()} 带<b>时效</b>（默认 100 毫秒，比一帧最慢的 50 毫秒还宽一倍），
 * 过期的值一律当 0。开着 JEI 页面时每帧都会重写，正常路径永远读到新鲜值。
 *
 * <h3>依据</h3>
 * <ul>
 * <li>ModularUI 3.3.1（仓库 ModularUI-Modern 分支 {@code 1.21.1}，其 {@code gradle.properties}
 *     里 {@code mod_version = 3.3.1}，与用户实装同版本）：{@code EmbedHandler}、
 *     {@code ModularScreen.createEmbed}、{@code ModularUIJeiCategory.UIWrapperWidget}、
 *     {@code drawable/schema/BaseSchemaRenderer}、{@code drawable/schema/Viewport}；</li>
 * <li>GTM 8.0.0-SNAPSHOT 官方 sources jar（build 95）：{@code MultiblockInfoJeiCategory}
 *     构造 {@code new MultiblockPreviewWidget(v, null, 200, 180)} 与 {@code getMaxWidth/getMaxHeight}；</li>
 * <li>JEI 19.56.0.441 官方 sources jar：{@code mezz/jei/common/gui/} 下的位姿平移＋相对鼠标约定。</li>
 * </ul>
 */
public final class MultiblockEmbedOffset {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 一次原点的有效期（纳秒）。见类注释「限时有效」。 */
    private static final long FRESH_NANOS = 100_000_000L;

    private static int offX = 0;
    private static int offY = 0;
    private static long stamp = 0L;
    private static boolean inside = false;

    /** 捕获原点连续抛异常的次数；到阈值彻底停用（不刷屏，也不反复付异常的钱）。 */
    private static int captureFailures = 0;
    private static volatile boolean disabled = false;

    // ==== 以下只给现场报告 / 聊天摘要用 ====
    private static volatile boolean hookFired = false;
    private static volatile int lastDx = 0;
    private static volatile int lastDy = 0;
    private static volatile long appliedCount = 0;
    private static volatile boolean statusNoted = false;

    private MultiblockEmbedOffset() {}

    /**
     * 进入一次「JEI 里的内嵌界面绘制」：写下这块界面在屏幕上的真实原点（整块覆盖上一次的）。
     *
     * @param x 原点 X（GUI 缩放坐标，向右为正）
     * @param y 原点 Y（GUI 缩放坐标，向下为正）
     */
    public static void push(int x, int y) {
        if (disabled) return;
        offX = x;
        offY = y;
        inside = true;
        stamp = System.nanoTime();
        hookFired = true;
    }

    /** 退出一次内嵌界面绘制：把「正在内嵌绘制」这个状态关掉（值留着，反正读的时候会判时效）。 */
    public static void pop() {
        inside = false;
    }

    /**
     * 现在是不是正画在「JEI 里的内嵌界面」当中。
     *
     * <p>r31 起另一个用途：{@code MenuKeepOnScreen} 拿它当第二道闸，配合 MUI 自己的 {@code UIType} 标记
     * 分辨当前这次排版属不属于真实窗口——虚拟屏的「可视区」跟窗口不同源，
     * 摆位置没有可信依据，我们就不摆（详见那个类的 {@code isRealWindowScreen()} 注释）。
     */
    public static boolean isInsideEmbedDraw() {
        return inside;
    }

    /** 当前该加到 GL 视口上的 X 偏移；不在内嵌绘制中（或值已过期、或 r34 开关关了）就是 0。 */
    public static int x() {
        return valid() && FixConfig.multiblockOffsetEnabled() ? offX : 0;
    }

    /** 当前该加到 GL 视口上的 Y 偏移；不在内嵌绘制中（或值已过期、或 r34 开关关了）就是 0。 */
    public static int y() {
        return valid() && FixConfig.multiblockOffsetEnabled() ? offY : 0;
    }

    private static boolean valid() {
        // 只在真正画 3D 结构图时才会被调用，一次比较的代价可以忽略。
        return inside && (System.nanoTime() - stamp) < FRESH_NANOS;
    }

    /** 补丁是否已停用（停用后行为与没打补丁时完全一致）。 */
    public static boolean isDisabled() {
        return disabled;
    }

    /** 由 {@code MultiblockViewportShiftMixin} 在真的加过偏移之后回调，只用于统计与报告。 */
    public static void noteApplied(int dx, int dy) {
        appliedCount++;
        lastDx = dx;
        lastDy = dy;
        // 只在第一次真正生效时写报告：每帧都写会把 gtm_jei_fix_report.txt 刷爆。
        if (!statusNoted) {
            statusNoted = true;
            FixReport.note("[多方块预览校正] 已生效：把 JEI 页面的屏幕原点 (" + dx + ", " + dy
                    + ") 补进了 3D 结构图的 OpenGL 视口，这张图不再偏左上角。");
        }
    }

    /** 捕获失败计数；连续 20 次就停用整块校正（说明 MUI 的结构对不上，别再做无谓尝试）。 */
    public static void noteCaptureFailure(Throwable t) {
        captureFailures++;
        if (captureFailures == 1) {
            LOGGER.debug("[gtm_jei_startup_fix] 捕获 JEI 页面原点失败（第 1 次）：{}", t.toString());
        }
        if (captureFailures >= 20 && !disabled) {
            disabled = true;
            LOGGER.warn("[gtm_jei_startup_fix] 多方块预览位置校正停用（游戏与配方页不受影响，"
                    + "只是 3D 结构图仍会偏左上角）：连续 {} 次捕获原点异常，最后一条 {}", captureFailures, t.toString());
            FixReport.note("[多方块预览校正] 停用：捕获页面原点连续 " + captureFailures
                    + " 次异常（" + t + "）。通常是 ModularUI 换了结构，其余功能不受影响。");
        }
    }

    /** 给现场报告 / 聊天摘要用的一行结论（四种情况都覆盖）。 */
    public static String statusLine() {
        if (!FixConfig.multiblockOffsetEnabled()) {
            return "多方块预览校正：已按配置关闭（fixes.enableMultiblockEmbedOffset=false）";
        }
        if (disabled) {
            return "多方块预览校正：已停用（连续捕获异常，已退回未打补丁的行为）";
        }
        if (appliedCount > 0) {
            return "多方块预览校正：已生效 " + appliedCount + " 次，最近一次平移 ("
                    + lastDx + ", " + lastDy + ") GUI 坐标";
        }
        if (hookFired) {
            return "多方块预览校正：捕获端已挂上，但这次还没画过多方块结构页（打开一次就好）";
        }
        return "多方块预览校正：未触发（没装 ModularUI，或 ModularUI 的类名与本模组核对过的版本不同）";
    }
}
