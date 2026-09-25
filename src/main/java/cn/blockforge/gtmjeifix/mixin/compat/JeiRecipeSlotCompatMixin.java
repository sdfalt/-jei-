package cn.blockforge.gtmjeifix.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * r17：锚定用的空 mixin——它自己不注入任何东西，
 * 存在的唯一意义是让 {@code gtm_jei_startup_fix_jeicompat.mixins.json}（priority/mixinPriority 900，
 * 数字小者先应用，早于 ModularUI 的默认 1000）
 * 在 {@code mezz.jei.library.gui.ingredients.RecipeSlot} 这个类被转换时触发，
 * 从而调用 {@link JeiRecipeSlotCompatPlugin#preApply} 把新版 JEI 删掉、
 * 而 ModularUI 的 {@code @Accessor} 又按名字需要的字段补回来。
 *
 * <p>用 {@code targets} 字符串而不是类引用：本模组不（也无法，官方根本没发布）编译期依赖
 * JEI 的内部实现类。那个年代的内部类名不参与混淆映射，{@code remap = false} 直写即可。
 */
@Mixin(targets = "mezz.jei.library.gui.ingredients.RecipeSlot", priority = 900, remap = false)
public class JeiRecipeSlotCompatMixin {

    /** mixin 类至少要有一个成员才算「有效转换」；这个字段被重命名注入，对原类无任何影响。 */
    @Unique
    private boolean gtmjeifix$recipeSlotCompatAnchor;
}
