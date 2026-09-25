package cn.blockforge.gtmjeifix;

import com.mojang.logging.LogUtils;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 从 GTM 的一条 {@code GTRecipe} 里取「能交给 JEI 的槽位内容」。
 *
 * <p>GTM 不在编译期依赖里（它是 SNAPSHOT，版本随时会变），所以这一段是反射；
 * 但**链路与 GTM 自己 EMI 桥接里的写法逐字一致**（对照
 * {@code com.gregtechceu.gtceu.integration.recipeviewer.emi.recipe.GTEmiRecipe}）：
 * <pre>
 * recipe.getInputContents(ItemRecipeCapability.CAP)      // List&lt;Content&gt;
 *   → ItemRecipeCapability.mapIngredientToEntryList(
 *         ItemRecipeCapability.CAP.of(content.content())) // ItemEntryList
 *     → .getStacks()                                      // List&lt;ItemStack&gt;
 * </pre>
 * 三个名字（{@code CAP} 静态字段、{@code getInputContents/getOutputContents}、
 * {@code mapIngredientToEntryList}、{@code getStacks}）都已用与用户同一构建的
 * {@code gtceu-1.21.1-8.0.0-SNAPSHOT+20260916} 的 class 文件核对过。
 *
 * <p>取不到的一律返回空集合（页面少摆几格），不抛异常。元素类型不匹配时会被
 * {@code instanceof} 过滤掉，所以也不存在 {@code ClassCastException} 冒到 JEI 的风险。
 */
final class GtRecipeAccess {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String ITEM_CAP = "com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability";
    private static final String FLUID_CAP = "com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability";

    /** 两个能力单例（GTM 里是 {@code public static final ItemRecipeCapability CAP}）。 */
    private static volatile Object itemCapInstance;
    private static volatile Object fluidCapInstance;
    private static volatile boolean resolved;

    private static volatile boolean warnedOnce = false;
    private static volatile boolean firstRecipeHandled = false;

    /** 一格槽位的内容：{@code items=true} 是 {@code List<ItemStack>}，否则是 {@code List<FluidStack>}。 */
    record Slot(boolean items, List<?> stacks) {}

    private GtRecipeAccess() {}

    /** 一条配方的某一侧（输入/输出）：先物品后流体。 */
    static List<Slot> collect(Object recipe, String getter) {
        List<Slot> out = new ArrayList<>();
        if (recipe == null) return out;
        resolve();
        addSide(out, recipe, getter, itemCapInstance, true);
        addSide(out, recipe, getter, fluidCapInstance, false);
        return out;
    }

    private static void addSide(List<Slot> out, Object recipe, String getter, Object cap, boolean items) {
        if (cap == null) return;
        try {
            Method getContents = find1(recipe.getClass(), getter, cap);
            if (getContents == null) {
                noteFailure("找不到 GTRecipe." + getter + "(RecipeCapability)");
                return;
            }
            if (!(getContents.invoke(recipe, cap) instanceof Collection<?> contents)) return;
            for (Object content : contents) {
                Object raw = Refl.call0Quiet(content, "content");
                if (raw == null) raw = Refl.field(content, "content");
                if (raw == null) continue;
                List<?> stacks = stacksOf(cap, raw, items);
                if (stacks != null && !stacks.isEmpty()) out.add(new Slot(items, stacks));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            noteFailure(getter + "：" + e);
        }
    }

    /** CAP.of(raw) → 能力类上的静态 mapIngredientToEntryList(...) → getStacks()，最后按类型过滤。 */
    private static List<?> stacksOf(Object capInstance, Object raw, boolean items) {
        try {
            Object ingredient = null;
            Method of = find1(capInstance.getClass(), "of", raw);
            if (of != null) ingredient = of.invoke(capInstance, raw);
            Object entryList = ingredient == null ? null : mapToEntryList(capInstance.getClass(), ingredient);
            Object stacks = entryList == null ? null : Refl.call0Quiet(entryList, "getStacks");
            if (!(stacks instanceof List<?> list) || list.isEmpty()) return null;
            Class<?> wanted = items ? ItemStack.class : FluidStack.class;
            List<Object> filtered = new ArrayList<>(list.size());
            for (Object o : list) {
                if (wanted.isInstance(o)) filtered.add(o);
            }
            return filtered.isEmpty() ? null : filtered;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            noteFailure("取槽位内容：" + e);
            return null;
        }
    }

    private static Object mapToEntryList(Class<?> capClass, Object ingredient) throws ReflectiveOperationException {
        for (Class<?> c = capClass; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("mapIngredientToEntryList") || !Modifier.isStatic(m.getModifiers())
                        || m.getParameterCount() != 1 || !m.getParameterTypes()[0].isInstance(ingredient)) {
                    continue;
                }
                m.setAccessible(true);
                return m.invoke(null, ingredient);
            }
        }
        return null;
    }

    /** 解析两个能力单例；失败只报一次并放弃（页面退化成「只有标题与图标」）。 */
    private static void resolve() {
        if (resolved) return;
        synchronized (GtRecipeAccess.class) {
            if (resolved) return;
            // 传 null：交给 Refl 的多加载器试探（GTM 的类我们只在运行期按名字找）
            itemCapInstance = Refl.staticField(Refl.load(ITEM_CAP, null), "CAP");
            fluidCapInstance = Refl.staticField(Refl.load(FLUID_CAP, null), "CAP");
            resolved = itemCapInstance != null;
            if (!resolved) noteFailure("没找到 " + ITEM_CAP + ".CAP，兜底分类页不摆槽位");
        }
    }

    /** 本模组补的分类第一次被 JEI 打开时给一条 info，便于判断「槽位取不到」是不是普遍现象。 */
    static void noteRecipeViewed(int slotsFilled) {
        if (firstRecipeHandled) return;
        firstRecipeHandled = true;
        if (slotsFilled == 0) {
            LOGGER.info("[gtm_jei_startup_fix] 兜底分类页第一次被打开，但没能从这条 GTRecipe 取出槽位内容"
                    + "（GTM 的配方能力结构与核对的不一致）。页面仍有标题与图标，配方照常可搜可点；"
                    + "把这一行前后的日志发回来即可继续适配。");
        }
    }

    private static Method find1(Class<?> cls, String name, Object arg) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1
                    && (arg == null || m.getParameterTypes()[0].isInstance(arg))) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    static void noteSlotFailure(Throwable e) {
        noteFailure("摆放槽位：" + e);
    }

    private static void noteFailure(String what) {
        if (!warnedOnce) {
            warnedOnce = true;
            LOGGER.warn("[gtm_jei_startup_fix] 兜底分类页取配方槽位失败（只影响页面显示，游戏与配方检索不受影响；"
                    + "同类问题不再重复提醒）：{}", what);
        } else {
            LOGGER.debug("[gtm_jei_startup_fix] {}", what);
        }
    }
}
