package cn.blockforge.gtmjeifix.mixin.muicompat;

import cn.blockforge.gtmjeifix.MultiblockEmbedOffset;

import com.mojang.logging.LogUtils;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.platform.Window;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * r24 第一半：<b>问出「这块内嵌界面到底被 JEI 画在屏幕哪里」</b>。
 *
 * <p>目标（ModularUI 3.3.1，非混淆，故全程 {@code remap = false}）：
 * <pre>
 * // brachy.modularui.integration.jei.recipe.ModularUIJeiCategory$UIWrapperWidget
 * public void drawWidget(GuiGraphics graphics, double mouseX, double mouseY) {
 *     EmbedHandler.drawEmbed(this.screen, graphics, (int) mouseX, (int) mouseY, partialTick);
 * }
 * </pre>
 * <p>这个方法就是「格雷在 JEI 里的整块界面（含 3D 结构预览）开始绘制」的唯一入口。
 * JEI 在调它之前已经把 {@code graphics} 的位姿平移到了配方页在屏幕上的绝对位置
 * （JEI 19.56 源码 {@code RecipeLayoutDrawableErrored}：
 * {@code poseStack.translate(position.x(), position.y(), 0);} 紧接
 * {@code drawWidget(guiGraphics, recipeMouseX - position.x(), recipeMouseY - position.y());}），
 * 所以<b>此刻位姿矩阵的平移分量就是我们要的答案</b>：把它写进 {@link MultiblockEmbedOffset}，
 * 另一头的 {@link MultiblockViewportShiftMixin} 负责加到 GL 视口上。
 *
 * <p>用位姿而不是去反射 JEI 的内部字段，是为了「跟着现场走」：配方页上下滚动、JEI 窗口移动、
 * 页面尺寸随配置变化时，读到的平移量天然就是当时的真实值。
 *
 * <h3>刻意只做「读」，不碰 JEI 传给 MUI 的鼠标坐标</h3>
 * <p>MUI 在 JEI 里整套部件坐标都是<b>页内相对</b>的（虚拟屏主面板 {@code pos(0,0)}），
 * JEI 给的鼠标也是页内相对，两者口径一致——槽位悬停高亮、提示、按钮点击<b>本来就是好的</b>。
 * 把鼠标坐标改成绝对反而会破坏这套一致性。唯一口径错位的就是 GL 视口那一条（它要的是窗口绝对坐标），
 * 所以只在它那一头补，代价是改动面只有一处。
 * 顺带被修好的还有结构图的射线拾取：它用的就是同一个视口矩形做换算，视口摆正后
 * 「看到的方块」和「点到的方块」错位从整页原点（几百像素）缩到部件自身偏移（≤几像素）。
 *
 * <p>挂钩都是 {@code require = 0}（注解写死 + 配置里 {@code injectors.defaultRequire = 0}）：
 * ModularUI 换了方法名/签名就整块跳过，绝不把游戏带崩——那时只是维持「偏左上角」这个原状。
 */
@Mixin(targets = "brachy.modularui.integration.jei.recipe.ModularUIJeiCategory$UIWrapperWidget", remap = false)
public final class JeiEmbedWidgetMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String TARGET = "drawWidget(Lnet/minecraft/client/gui/GuiGraphics;DD)V";

    /** 首次成功读数时提示一次；读数不合理时最多提示几条，都不刷日志。 */
    private static boolean logged = false;
    private static boolean loggedSuspicious = false;
    /** 现场报告里最多记几条「可疑读数」。 */
    private static final int SUSPICIOUS_REPORTS = 3;

    /** 被「不像屏幕坐标」这条判据挡掉的次数（r31 起会写进现场报告，用来判断这条判据有没有误杀）。 */
    private static int suspiciousCount = 0;

    @Inject(method = TARGET, at = @At("HEAD"), require = 0)
    private void gtmjeifix$captureOrigin(GuiGraphics guiGraphics, double mouseX, double mouseY, CallbackInfo ci) {
        try {
            Matrix4f m = guiGraphics.pose().last().pose();
            double dx = m.m30();
            double dy = m.m31();
            // 万一哪天宿主在位姿里还叠了缩放，把平移量换算回 GUI 单位（现在 JEI 只有平移，分支不触发）。
            float sx = m.m00();
            float sy = m.m11();
            if (Math.abs(sx - 1.0f) > 1.0e-3f && Math.abs(sy - 1.0f) > 1.0e-3f) {
                dx /= sx;
                dy /= sy;
            }
            int px = (int) Math.round(dx);
            int py = (int) Math.round(dy);
            if (looksOffScreen(px, py)) {
                // 读数明显不是「屏幕内的一个原点」。宁可不校正，也不能把一张好端端的图画到屏幕外去。
                MultiblockEmbedOffset.push(0, 0);
                suspiciousCount++;
                if (!loggedSuspicious) {
                    loggedSuspicious = true;
                    LOGGER.warn("[gtm_jei_startup_fix] 多方块预览：读到的页面原点 ({}, {}) 不像屏幕坐标，"
                            + "本次及之后一律不做位移（配方页其余部分不受影响）。", px, py);
                }
                if (suspiciousCount <= SUSPICIOUS_REPORTS) {
                    // 玩家说「调过窗口大小之后结构图又跑回左上角」时，这几行就是判据：
                    // 有这几行＝这条合理性判据把合法读数误杀了；没有＝另有原因。
                    cn.blockforge.gtmjeifix.FixReport.note("[多方块预览校正] 第 " + suspiciousCount
                            + " 次认为读到的页面原点不像屏幕坐标（读数 " + px + ", " + py
                            + "；窗口 GUI 尺寸 " + windowHint() + "），本次不做位移。"
                            + (suspiciousCount == SUSPICIOUS_REPORTS ? "（同类只记这么多条）" : ""));
                }
                return;
            }
            MultiblockEmbedOffset.push(px, py);
            if (!logged) {
                logged = true;
                LOGGER.info("[gtm_jei_startup_fix] 多方块预览：已能读取 JEI 页面原点（本次读数 {}, {}），"
                        + "3D 结构图会按它摆正。", px, py);
            }
        } catch (RuntimeException | LinkageError e) {
            MultiblockEmbedOffset.pop();
            MultiblockEmbedOffset.noteCaptureFailure(e);
        }
    }

    @Inject(method = TARGET, at = @At("TAIL"))
    private void gtmjeifix$releaseOrigin(GuiGraphics guiGraphics, double mouseX, double mouseY, CallbackInfo ci) {
        MultiblockEmbedOffset.pop();
    }

    /**
     * 读数合理性检查：配方页在屏幕上的原点必定是「非负、且不超过一屏」。
     * 越界基本可以判定是读错了坐标系（比如混进了 GL 帧缓冲的原点）——那就别动。
     */
    private static boolean looksOffScreen(int x, int y) {
        if (x < 0 || y < 0) return true;
        try {
            Window window = Minecraft.getInstance().getWindow();
            return x > window.getGuiScaledWidth() || y > window.getGuiScaledHeight();
        } catch (RuntimeException | LinkageError e) {
            return false;   // 拿不到屏幕尺寸就不设限，宁可放过
        }
    }

    /** 报告里带一句当时的窗口 GUI 尺寸（判断上面那条判据是不是被窗口大小卡住用）。 */
    private static String windowHint() {
        try {
            Window window = Minecraft.getInstance().getWindow();
            return window.getGuiScaledWidth() + "x" + window.getGuiScaledHeight();
        } catch (RuntimeException | LinkageError e) {
            return "取不到";
        }
    }
}
