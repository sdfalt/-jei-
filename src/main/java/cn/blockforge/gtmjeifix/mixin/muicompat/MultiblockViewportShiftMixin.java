package cn.blockforge.gtmjeifix.mixin.muicompat;

import cn.blockforge.gtmjeifix.MultiblockEmbedOffset;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * r24 第二半：<b>把 JEI 页面的真实原点补进 3D 结构图的 OpenGL 视口</b>。
 *
 * <p>目标（ModularUI 3.3.1，非混淆，故类级 {@code remap = false}）：
 * <pre>
 * // brachy.modularui.drawable.schema.Viewport
 * public void calculateOpenGLViewportFromRectangle(int x, int y, int width, int height) {
 *     Window window = Minecraft.getInstance().getWindow();
 *     double guiScale = window.getGuiScale();
 *     setX(Mth.ceil(x * guiScale));
 *     setY(window.getHeight() - Mth.ceil((y + height) * guiScale));   // GL 的原点在左下
 *     ...
 * }
 * </pre>
 * <p>全工程只有 {@code BaseSchemaRenderer.draw} 一处调它（已在 MUI 源码里 grep 核实），
 * 传的 x/y 是 MUI 自己坐标栈算出的「页内坐标」——那份内嵌虚拟屏的原点被 MUI 当成窗口原点(0,0)，
 * 于是格雷的多方块 3D 结构图整体偏到左上角。这里只做一件事：<b>在入参上加回真实原点</b>。
 *
 * <p>用 {@code @ModifyVariable(argsOnly = true)} 直接改入参，是三种写法里影响面最小的：
 * <ul>
 * <li>不改方法体、不取消原调用，MUI 自己那套「乘 GUI 缩放、Y 轴翻转」的逻辑一字未动；</li>
 * <li>回调只有 {@code int}，参数表里没有任何 ModularUI / JEI 的类型 → 编译期零依赖，
 *     也不会出现 r7 那种「Mixin 类自身引用了不存在的类，转换阶段就崩」的问题；</li>
 * <li>偏移只在「正在画 JEI 内嵌界面」时非 0（{@link MultiblockEmbedOffset} 里判时效），
 *     玩家在自己机器里点开多方块主控方块的那个 3D 预览走的同一个方法，偏移恒为 0 → 完全不受影响。</li>
 * </ul>
 *
 * <p>顺带修好的还有<b>点击拾取</b>：射线拾取（拖拽旋转、点某个方块看它是什么）用的就是同一个视口
 * 矩形做「屏幕坐标 ↔ 世界坐标」换算，视口摆正之后，看到的结构和点到的位置自然对上。
 */
@Mixin(targets = "brachy.modularui.drawable.schema.Viewport", remap = false)
public final class MultiblockViewportShiftMixin {

    private static final String TARGET = "calculateOpenGLViewportFromRectangle(IIII)V";

    @ModifyVariable(method = TARGET, at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static int gtmjeifix$shiftX(int x) {
        int dx = MultiblockEmbedOffset.x();
        if (dx != 0) {
            MultiblockEmbedOffset.noteApplied(dx, MultiblockEmbedOffset.y());
            return x + dx;
        }
        return x;
    }

    @ModifyVariable(method = TARGET, at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private static int gtmjeifix$shiftY(int y) {
        int dy = MultiblockEmbedOffset.y();
        return dy != 0 ? y + dy : y;
    }
}
