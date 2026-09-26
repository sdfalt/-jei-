package cn.blockforge.gtmjeifix.mixin;

import cn.blockforge.gtmjeifix.FixConfig;
import cn.blockforge.gtmjeifix.JeiReadiness;
import cn.blockforge.gtmjeifix.LazyIcons;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把 GTM 的「现在就造图标」改成「先给占位、用到时自愈」。
 *
 * <p>目标（GTM 8.0.0-SNAPSHOT 官方映射源码，未混淆，故全程 {@code remap = false}）：
 * <pre>
 * com.gregtechceu.gtceu.integration.recipeviewer.CategoryIcon$JeiCallWrapper
 *   public static IDrawable getRenderable(ResourceLocation location) {
 *       return GTJEIPlugin.getRuntime().getJeiHelpers().getGuiHelper()...;   // runtime 为 null 就 NPE
 *   }
 *   public static IDrawable getRenderable(ItemStack stack) {
 *       return GTJEIPlugin.getRuntime().getJeiHelpers().getGuiHelper().createDrawableItemStack(stack);
 *   }
 * </pre>
 *
 * <p>{@code GTJEIPlugin.getRuntime()} 还没被 JEI 填入时，这两个方法必定 NPE。
 * 此时取消调用，返回 {@link LazyIcons} 造的<b>占位图标</b>（不是 null）：
 * GTM 会把它存进 {@code wrappedValue}，JEI 注册分类时缓存的也就是这个占位对象；
 * 等 JEI 真就绪后，占位对象在自己被用到的那一刻用同一个 GTM 方法造出真实图标并接管转发。
 * 因此与「就绪时刻先后」完全无关，图标不会永久为空。
 *
 * <p>判据只有一条：{@code GTJEIPlugin.getRuntime()} 还是不是 null（{@link JeiReadiness}）。
 * 不附加「物理客户端 / 只装了 JEI」等条件——这两个私有方法被调到，就说明 GTM 已经决定
 * 走 JEI 分支了，条件多加只会漏拦；{@link LazyIcons#create} 内部自己判断是否只做客户端占位。
 *
 * <p>{@link LazyIcons#isBypassed()} 是重入保护：占位图标内部会直调这两个方法做恢复，
 * 那时必须放行，不能自己拦自己。
 */
@Mixin(targets = "com.gregtechceu.gtceu.integration.recipeviewer.CategoryIcon$JeiCallWrapper", remap = false)
public class CategoryIconJeiWrapperMixin {

    @Inject(
            method = "getRenderable(Lnet/minecraft/resources/ResourceLocation;)Lmezz/jei/api/gui/drawable/IDrawable;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void gtmjeifix$deferTextureIcon(ResourceLocation location, CallbackInfoReturnable<Object> cir) {
        if (!FixConfig.crashFixEnabled()) return;       // r34：开关关了 → 完全不拦，行为与没装本模组一致
        if (LazyIcons.isBypassed()) return;              // 自家恢复用的直调，放行
        if (JeiReadiness.isRuntimeStored()) return;      // JEI 已就绪，照常真建图标
        // 未就绪：原调用必定 NPE，取消它，给一个会自动恢复的占位图标（服务端/降级时为 null）
        cir.setReturnValue(LazyIcons.create(location));
    }

    @Inject(
            method = "getRenderable(Lnet/minecraft/world/item/ItemStack;)Lmezz/jei/api/gui/drawable/IDrawable;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void gtmjeifix$deferStackIcon(ItemStack stack, CallbackInfoReturnable<Object> cir) {
        if (!FixConfig.crashFixEnabled()) return;       // r34：同上
        if (LazyIcons.isBypassed()) return;
        if (JeiReadiness.isRuntimeStored()) return;
        cir.setReturnValue(LazyIcons.create(stack));
    }
}
