package cn.blockforge.gtmjeifix.mixin.muicompat;

import cn.blockforge.gtmjeifix.MenuKeepOnScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * r28 引入：<b>钉在 ModularUI「整棵树的绝对坐标刚算完、还没开始画」那一拍</b>上，
 * 让 {@link MenuKeepOnScreen} 有机会把弹出菜单摆回它所属的那一行。
 *
 * <p>目标（ModularUI 3.3.1，非混淆，故类级 {@code remap = false}）：
 * <pre>
 * // brachy.modularui.widget.sizer.WidgetResizeNode
 * public void postFullResize() {
 *     super.postFullResize();
 *     this.widget.postResize();          // ← 官方注释：整棵树排版完、绝对坐标算完之后回调
 * }
 * </pre>
 *
 * <p><b>为什么挂在这里，而不是直接挂给菜单部件 {@code Menu}</b>：
 * 挂钩要落在「绝对坐标已经算好、还没开始画」这一拍上，MUI 只有这一处是这个时机
 * （{@code WidgetTree.resizeInternal} 的源码顺序：
 * {@code resize → preApplyPos → applyPos → postFullResize}）。
 * 而 {@code Menu} 自己不声明 {@code postResize()}（那是 {@code IWidget} 的空默认方法），
 * 没有可注入的目标方法；用「往目标类里合一个没注解的方法」来顶替默认实现虽然可行，
 * 但一旦将来的 ModularUI 真的自己声明了同名方法，就是硬冲突（{@code require=0} 救不了冲突，
 * 那是 r7/r12 已经踩过并记进 README 的同一类坑）。所以钉在声明得明明白白的
 * {@code postFullResize()V} 上，签名对不上就整条跳过。
 *
 * <p><b>r31 删掉了第二条「同类保险」</b>（原来叫 {@code MenuResizeClampBaseMixin}，
 * 钉在 {@code brachy.modularui.widget.WidgetNode} 上）：那条是当年「不确定 {@code postFullResize}
 * 声明在哪一层」时买的保险，而 r31 拿到了用户实装同版本（MUI-Modern 分支 1.21.1、
 * {@code mod_version = 3.3.1}）的<b>全量源码清单</b>——525 个 java 文件里<b>根本没有
 * {@code WidgetNode} 这个类</b>，{@code postFullResize()} 就是 {@code WidgetResizeNode}
 * 自己声明的（{@code WidgetResizeNode extends ResizeNode}）。也就是说那条 mixin
 * 从 r28 到 r30 一次都没命中过，留着只是白白让 Mixin 去解析一个不存在的目标类。
 *
 * <p><b>本回调的代价</b>：每个部件排版完都会走一次这里，所以第一件事是问
 * {@code getWidget()} 是不是那个菜单部件（{@link MenuKeepOnScreen} 里按现场对象的 class loader
 * 取 {@code Class} ＋缓存的 {@code Method} 判断），不是就立刻返回。
 * 一次 {@code Method.invoke} ＋一次 {@code isInstance}，相对 MUI 自己的排版开销可以忽略。
 *
 * <p><b>为什么只碰 {@code Menu}</b>：{@link MenuKeepOnScreen} 只改菜单自己那块 {@code Area} 的
 * {@code x/y/rx/ry}，而且 r31 起条件收得很紧——必须能认出「玩家鼠标停的那一行的小按钮」
 * （{@code AbstractMenuButton} 的实例）、并把该按钮的<b>滚动位移</b>减掉之后仍然认为摆得进可视区，
 * 才会动手；任何一环拿不准就一个字节不改。机器界面里的槽位、进度条、文字、滚动条一个都不经过这里。
 *
 * <p><b>另一道闸（r31）</b>：这张界面还得是「窗口本身那张屏」，判据是 MUI 自己给的
 * {@code screen.getContext().getUIType().isScreen}（内嵌在 JEI 页面里的那一份是
 * {@code UIType.EMBED}＝虚拟屏，坐标与窗口不同源，摆不准，所以一概不动）。
 * 详见 {@link MenuKeepOnScreen} 的 {@code isRealWindowScreen()} 注释。
 *
 * <p>{@code require = 0}（注解写死 + 配置里 {@code injectors.defaultRequire = 0}）：
 * ModularUI 换了方法名/签名就整条跳过——最坏情况只是菜单照旧开到屏幕外，绝不会把游戏带崩。
 *
 * <p>本类的字节码里<b>不出现任何 ModularUI 的类型</b>：{@code targets} 是字符串，
 * 回调参数只有 {@code CallbackInfo}，转出去的对象是 {@code Object}。
 * 这是 r7 那次「Mixin 类自身引用了不存在的类 → 整个模组加载失败」留下的规矩。
 */
@Mixin(targets = "brachy.modularui.widget.sizer.WidgetResizeNode", remap = false)
public final class MenuResizeClampMixin {

    /**
     * 结尾注入：{@code widget.postResize()} 已经跑完，这一帧的绝对坐标定型了。
     * 实例回调里的 {@code this} 就是那个排版节点对象本身（Mixin 把方法合进目标类），
     * 交给 {@link MenuKeepOnScreen} 用反射问它要 {@code getWidget()}。
     */
    @Inject(method = "postFullResize()V", at = @At("TAIL"), require = 0)
    private void gtmjeifix$clampMenuOnScreen(CallbackInfo ci) {
        MenuKeepOnScreen.afterResize(this);
    }
}
