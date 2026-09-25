package cn.blockforge.gtmjeifix;

import com.mojang.logging.LogUtils;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.drawable.IDrawableAnimated;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import org.slf4j.Logger;

import java.util.List;

/**
 * r8：格雷主配方分类的兜底页面，**编译期实现** JEI 的 {@code IRecipeCategory}。
 *
 * <h3>为什么需要它</h3>
 * <p>与用户同一构建的 GTM 快照源码里，{@code GTRecipeJEICategory} 是 {@code abstract class}
 * （构造器还是 {@code protected}，参数是 ModularUI 的 widget 工厂），GTM 自己没有任何具体子类，
 * 并且把 {@code registerCategories} 里那行注册代码注释掉了。结果：格雷配方分类在 JEI 里
 * 「有配方、没页面」。本类提供一个能看的最小页面：标题与图标取自 GTM 的数据驱动分类，
 * 槽位按「左边输入、右边输出」把物品与流体摆成网格。
 *
 * <h3>为什么这一版不再用 JDK 动态代理</h3>
 * <p>r6/r7 用 {@code Proxy} 实现 JEI 接口（那时编译期没有 JEI API）。问题是
 * <b>接口里每个方法都得手写分支，漏一个就静默给错值</b>。举个已核对到的实例：
 * JEI 19 的 {@code IRecipeCategory.getWidth()} 默认实现是「取 {@code getBackground()} 的宽；
 * 背景为 null 就抛 {@code IllegalStateException: getWidth() and getHeight() must be
 * overridden if background is null}」——代理版本没有背景，只能靠通用的 {@code defaultValue(int)}
 * 蒙一个 16。现在 JEI 官方 api jar 进了编译类路径（见 {@code build.gradle}），
 * 直接 {@code implements IRecipeCategory<Object>}：少实现方法编译器就报错，
 * 类型也没有任何猜测成分。
 *
 * <p>{@code Object} 是刻意的：GTM 的 {@code GTRecipe} 不在编译期依赖里，而泛型在字节码里被完全擦除，
 * JEI 传进来的 {@code GTRecipe} 会以 {@code Object} 落到我们手上。
 *
 * <h3>失败面</h3>
 * <p>只有「从 GTM 配方里取槽位」那一段是反射（见 {@link GtRecipeAccess}），取不到就是少摆几格；
 * 本类每个公开方法内部都不向外抛异常，不会把问题带回 JEI 的注册流程。
 */
public final class GtFallbackCategory implements IRecipeCategory<Object> {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 页面宽高：与 GTM 自己的配方页一致。背景图为空时 JEI 要求必须自己给出宽高。 */
    private static final int WIDTH = 176;
    private static final int HEIGHT = 166;
    /** 每侧最多 8 格（2 列 x 4 行）；格雷配方动辄十几个槽，全摆会把界面撑坏。 */
    private static final int SLOTS_PER_SIDE = 8;
    private static final int ROWS_PER_COLUMN = 4;
    private static final int SLOT_PITCH = 18;

    /** GTM 的数据驱动分类（{@code com.gregtechceu.gtceu.api.recipe.category.GTRecipeCategory}）。 */
    private final Object gtCategory;
    /** 与 GTM 的 {@code GTRecipeJEICategory.TYPES} 完全同一个 RecipeType 实例——GTM 后面就按它交配方。 */
    @SuppressWarnings("rawtypes")
    private final RecipeType recipeType;
    private final IJeiHelpers helpers;
    private final ResourceLocation uid;

    private volatile IDrawable icon;
    private volatile boolean iconResolved;
    /** 中间的动画箭头（JEI 自带贴图），第一次画的时候再创建；创建失败就干脆不画。 */
    private volatile IDrawableAnimated arrow;
    private volatile boolean arrowTried;

    @SuppressWarnings("rawtypes")
    public GtFallbackCategory(Object gtCategory, RecipeType recipeType, IJeiHelpers helpers, ResourceLocation uid) {
        this.gtCategory = gtCategory;
        this.recipeType = recipeType;
        this.helpers = helpers;
        this.uid = uid;
    }

    @Override
    @SuppressWarnings("unchecked")
    public RecipeType<Object> getRecipeType() {
        return recipeType;
    }

    @Override
    public Component getTitle() {
        Object name = Refl.call0Quiet(gtCategory, "getName");
        if (name instanceof Component c) return c;
        return Component.literal(uid == null ? "GregTech" : uid.getPath());
    }

    @Override
    public IDrawable getIcon() {
        if (!iconResolved) {
            synchronized (this) {
                if (!iconResolved) {
                    try {
                        // GTM: GTRecipeCategory.getIcon() -> CategoryIcon，它的 get() 才是 JEI 的 IDrawable
                        Object categoryIcon = Refl.call0Quiet(gtCategory, "getIcon");
                        Object drawable = categoryIcon == null ? null : Refl.call0Quiet(categoryIcon, "get");
                        if (drawable instanceof IDrawable d) icon = d;
                    } catch (RuntimeException | LinkageError e) {
                        LOGGER.debug("[gtm_jei_startup_fix] 取 {} 的图标失败，改用空图标：{}", uid, e.toString());
                    }
                    if (icon == null) {
                        try {
                            icon = helpers.getGuiHelper().createBlankDrawable(16, 16);
                        } catch (RuntimeException | LinkageError e) {
                            LOGGER.debug("[gtm_jei_startup_fix] 连空图标都造不出来（{}）：{}", uid, e.toString());
                        }
                    }
                    iconResolved = true;
                }
            }
        }
        return icon;
    }

    /** 没有背景图，所以必须自己给出宽高（否则 JEI 的默认实现会抛 IllegalStateException）。 */
    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, Object recipe, IFocusGroup focuses) {
        if (recipe == null) return;
        int filled = 0;
        filled += placeSide(builder, GtRecipeAccess.collect(recipe, "getInputContents"),
                RecipeIngredientRole.INPUT);
        filled += placeSide(builder, GtRecipeAccess.collect(recipe, "getOutputContents"),
                RecipeIngredientRole.OUTPUT);
        GtRecipeAccess.noteRecipeViewed(filled);
    }

    /** r13：页面中央画 JEI 自带的动画箭头，底部补一行「耗时 / EU/t」，把原来的「黑屏格子」变成能看懂的配方页。 */
    @Override
    public void draw(Object recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics guiGraphics,
                     double mouseX, double mouseY) {
        try {
            IDrawableAnimated a = arrowDrawable();
            if (a != null) {
                a.draw(guiGraphics, (WIDTH - a.getWidth()) / 2, (HEIGHT - a.getHeight()) / 2);
            }
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("[gtm_jei_startup_fix] 画箭头失败（不影响页面其余部分）：{}", e.toString());
        }
        try {
            String info = recipeInfoLine(recipe);
            if (info != null) {
                guiGraphics.drawString(Minecraft.getInstance().font, Component.literal(info),
                        8, HEIGHT - 12, 0xFF_8A_8A_8A);
            }
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("[gtm_jei_startup_fix] 画耗时信息失败（不影响页面其余部分）：{}", e.toString());
        }
    }

    private IDrawableAnimated arrowDrawable() {
        if (!arrowTried) {
            synchronized (this) {
                if (!arrowTried) {
                    arrowTried = true;
                    try {
                        arrow = helpers.getGuiHelper().createAnimatedRecipeArrow(20);
                    } catch (RuntimeException | LinkageError e) {
                        LOGGER.debug("[gtm_jei_startup_fix] 创建动画箭头失败：{}", e.toString());
                    }
                }
            }
        }
        return arrow;
    }

    /** 「3.5 秒 · 32 EU/t」这样的一行；GTM 的数据字段拿不到就返回 null（整行不画）。 */
    private static String recipeInfoLine(Object recipe) {
        StringBuilder sb = new StringBuilder();
        Integer duration = asInt(Refl.field(recipe, "duration"));
        if (duration != null && duration > 0) {
            sb.append(String.format("%.1f 秒", duration / 20.0));
        }
        if (Refl.field(recipe, "data") instanceof CompoundTag tag) {
            int euPerTick = tag.getInt("EU/t");
            if (euPerTick > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(euPerTick).append(" EU/t");
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static Integer asInt(Object v) {
        return v instanceof Integer i ? i : null;
    }

    /**
     * 一格一格摆：物品用 {@code VanillaTypes.ITEM_STACK}，流体用 {@code NeoForgeTypes.FLUID_STACK}。
     * 单格失败就停在这一侧（其余格子少摆），绝不把异常抛给 JEI。
     */
    private int placeSide(IRecipeLayoutBuilder builder, List<GtRecipeAccess.Slot> slots,
                          RecipeIngredientRole role) {
        int placed = 0;
        for (GtRecipeAccess.Slot slot : slots) {
            if (placed >= SLOTS_PER_SIDE) break;
            int col = placed / ROWS_PER_COLUMN;
            int row = placed % ROWS_PER_COLUMN;
            int x = role == RecipeIngredientRole.INPUT
                    ? 8 + col * SLOT_PITCH
                    : WIDTH - 8 - SLOT_PITCH - col * SLOT_PITCH;
            int y = 12 + row * SLOT_PITCH;
            try {
                IRecipeSlotBuilder target = builder.addSlot(role, x, y);
                // 每格补上标准槽位底（输入侧）/成品槽底（输出侧）——之前没背景时整页发黑，
                // 就是用户说的「页面都变暗了」里最主要的一条。
                try {
                    if (role == RecipeIngredientRole.INPUT) {
                        target.setStandardSlotBackground();
                    } else {
                        target.setOutputSlotBackground();
                    }
                } catch (RuntimeException | LinkageError e) {
                    LOGGER.debug("[gtm_jei_startup_fix] 槽位背景设置失败（不影响内容显示）：{}", e.toString());
                }
                if (slot.items()) {
                    target.addIngredients(VanillaTypes.ITEM_STACK, castItemStacks(slot.stacks()));
                } else {
                    List<FluidStack> fluids = castFluidStacks(slot.stacks());
                    target.setFluidRenderer(totalAmount(fluids), false, 16, 16);
                    target.addIngredients(NeoForgeTypes.FLUID_STACK, fluids);
                }
                placed++;
            } catch (RuntimeException | LinkageError e) {
                GtRecipeAccess.noteSlotFailure(e);
                break;
            }
        }
        return placed;
    }

    /**
     * 元素类型已由 {@link GtRecipeAccess} 用 {@code instanceof} 逐个过滤过，
     * 这里的转换只是给编译器看；万一是别的东西，最多这一格画不出来（外层有 try/catch）。
     */
    @SuppressWarnings("unchecked")
    private static List<ItemStack> castItemStacks(List<?> stacks) {
        return (List<ItemStack>) stacks;
    }

    @SuppressWarnings("unchecked")
    private static List<FluidStack> castFluidStacks(List<?> stacks) {
        return (List<FluidStack>) stacks;
    }

    private static long totalAmount(List<FluidStack> stacks) {
        long total = 0;
        for (FluidStack s : stacks) {
            if (s != null) total += s.getAmount();
        }
        return total > 0 ? total : 1000;
    }

    ResourceLocation uid() {
        return uid;
    }

    @Override
    public String toString() {
        return "gtm_jei_startup_fix:GtFallbackCategory(" + uid + ")";
    }
}
