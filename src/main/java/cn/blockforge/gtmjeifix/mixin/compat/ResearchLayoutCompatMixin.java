package cn.blockforge.gtmjeifix.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * 字节码插件的锚点，不直接注入逻辑。
 *
 * <p>目标类中的研究信息布局由静态 lambda 组成，实际改写在
 * {@link JeiRecipeSlotCompatPlugin#preApply} 中完成。</p>
 */
@Mixin(targets = "com.gregtechceu.gtceu.common.recipe.gui.GTRecipeUIModifiers", remap = false)
public final class ResearchLayoutCompatMixin {

    @Unique
    private static final String gtmjeifix$anchor = "research-layout";
}
