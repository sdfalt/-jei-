package cn.blockforge.gtmjeifix.mixin.lang;

import cn.blockforge.gtmjeifix.PreviewTextZh;

import net.minecraft.network.chat.MutableComponent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * r69：把格雷「JEI 多方块 3D 结构预览页」上三句**硬编码英文**的悬浮提示换成中文
 * （仅当玩家游戏语言是 zh 系；其余语言一字不动）。
 *
 * <p>目标（GTM 8.0.0-SNAPSHOT build 140/147 字节码核实，类名非混淆，{@code remap = false}）：
 * {@code com.gregtechceu.gtceu.integration.recipeviewer.widgets.MultiblockPreviewWidget}
 * 构造器里的三个 tooltip 消费者 lambda：
 * <pre>
 * lambda$new$12(RichTooltip r): r.addLine(Component.literal("Press to display preview in world!"));
 * lambda$new$15(RichTooltip r): r.addLine(Component.literal("Press to flip structure"));
 * lambda$new$20(RichTooltip r): r.addLine(Component.literal("Layer Selection"));
 * </pre>
 * 每个 lambda 体内恰好只有一次 {@code Component.literal(String)} 调用，
 * {@code @Redirect} 把这次调用整体换成 {@link PreviewTextZh} 的同名入口——
 * 入口里先逐字符比对原句、再看语言，两关都过才换 {@code translatable}，否则原样
 * {@code literal(原文)}，行为与不装本模组完全一致。
 *
 * <p>快照兼容：lambda 编号（{@code $12}/{@code $15}/{@code $20}）在 d5bafc8(build 140) 与
 * 809dda7(build 147) 上逐字节一致（本轮 javap 取证）；更老或更新的快照若编号变了，
 * 配置里的 {@code injectors.defaultRequire = 0} 让这三条挂钩整体静默跳过——
 * 预览页提示退回官方英文，绝不报错、更不影响其余修复。
 *
 * <p>只挂客户端：目标类是纯客户端 UI 类，专用服务端永不加载，挂钩不会在服务端出现。
 */
@Mixin(targets = "com.gregtechceu.gtceu.integration.recipeviewer.widgets.MultiblockPreviewWidget",
        remap = false)
public final class PreviewTextZhMixin {

    /** 世界预览按钮（图标：世界标记方块 + 屏障）的提示。 */
    @Redirect(method = "lambda$new$12",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/chat/Component;literal(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;"))
    private static MutableComponent gtmjeifix$zhWorldPreview(String text) {
        return PreviewTextZh.worldPreview(text);
    }

    /** 镜像翻转按钮（图标：搅拌机进度条）的提示。 */
    @Redirect(method = "lambda$new$15",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/chat/Component;literal(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;"))
    private static MutableComponent gtmjeifix$zhFlip(String text) {
        return PreviewTextZh.flip(text);
    }

    /** 分层按钮（图标：当前层号 A/1/2…）的提示。 */
    @Redirect(method = "lambda$new$20",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/chat/Component;literal(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;"))
    private static MutableComponent gtmjeifix$zhLayer(String text) {
        return PreviewTextZh.layer(text);
    }
}
