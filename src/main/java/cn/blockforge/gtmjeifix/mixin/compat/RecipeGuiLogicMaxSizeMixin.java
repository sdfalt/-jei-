package cn.blockforge.gtmjeifix.mixin.compat;

import cn.blockforge.gtmjeifix.RecipeGuiLogicCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Function;

/**
 * r70 新增：<b>接管</b> ModularUI 旧快照里那条被新版 JEI（19.7x 网格滚动重构）打掉目标的
 * {@code modularui.mixins.json:jei.RecipeGuiLogicMixin}。
 *
 * <p>坏的那条由 {@link RecipeGuiLogicCompat} 在客户端初始化时从 ModularUI 的 mixin 配置里
 * 摘除（否则它照样崩），摘除成立时本类才由插件放行（{@code shouldApplyMixin}）。
 *
 * <p>注入内容与 ModularUI 官方第 9 号快照（2026-11-25）的修复<b>逐句对齐</b>（其字节码
 * 见 {@code tools/ground-truth/}）：挂在
 * {@code RecipeGuiLogic#getVisibleRecipeLayoutsWithButtons} 里
 * {@code Optional.map(Function)} 调用点的尺寸函数参数上，把返回值换成——
 * 分类是 {@code ModularUIJeiCategory}（格雷所有机器配方页）时，
 * 用 {@code getMaxWidth()/getMaxHeight()} 加上「原尺寸−布局矩形」的边距差重算；
 * 其余分类原样透传。逻辑本体在 {@link RecipeGuiLogicCompat#wrapSizeFunction}，
 * 全程反射，不编译依赖 JEI 内部类与 ModularUI。
 *
 * <p>本配置 {@code defaultRequire = 0}：JEI 将来再重构、锚点对不上时整条静默跳过，
 * 最多布局退回 JEI 默认（坏 mixin 已被摘除，不会再崩），并在自检里说明。
 */
@Mixin(targets = "mezz.jei.gui.recipes.RecipeGuiLogic", priority = 900, remap = false)
public class RecipeGuiLogicMaxSizeMixin {

    /** 与官方同款锚点：方法名匹配 + INVOKE Optional.map(Function) 的第 0 个参数。 */
    @ModifyArg(
            method = "getVisibleRecipeLayoutsWithButtons",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/Optional;map(Ljava/util/function/Function;)Ljava/util/Optional;"),
            index = 0)
    private Function<Object, Object> gtmfix$useCategoryMaxHeightForLayout(Function<Object, Object> original) {
        // 恒挂但按运行时标志生效：只有 RecipeGuiLogicCompat 确认「旧 ModularUI × 新 JEI」
        // 并把坏 mixin 摘掉后，这里才真的换函数。否则（老 JEI 上方法体里也有 Optional.map
        // 调用点、或 ModularUI 已是修好的官方快照）一律原样透传——绝不与 MUI 自己那条双包。
        if (!RecipeGuiLogicCompat.takeoverWanted()) {
            return original;
        }
        return RecipeGuiLogicCompat.wrapSizeFunction(original);
    }

    /** mixin 类至少要有个自己的成员才算「有效转换」；对原类无任何影响。 */
    @Unique
    private boolean gtmjeifix$recipeGuiLogicMaxSizeAnchor;
}
