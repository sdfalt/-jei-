package cn.blockforge.gtmjeifix;

import com.mojang.logging.LogUtils;

import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;

import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * r13 的注册接管：GTM 这个快照跑不完的 JEI 注册流程，由本模组在 HEAD 取消原方法后整体重放。
 *
 * <h3>事实依据（与用户同一构建 {@code gtceu ...+34f02a6} 的官方 sources jar 逐行核对）</h3>
 * <pre>
 * // GTJEIPlugin#registerCategories —— GTM 把主配方分类的注册注释掉了：
 * registry.addRecipeCategories(new MultiblockInfoJeiCategory(jeiHelpers));   ← 6 个特殊分类
 * ...
 * for (GTRecipeCategory c : GTRegistries.RECIPE_CATEGORIES) { /* 空转，注册行被注释 *\/ }
 * // GTJEIPlugin#registerRecipes —— 一旦上面没成，第一句就给「没有分类的 RecipeType」交配方，
 * //   JEI 19.56 直接抛 IllegalArgumentException，方法体中途夭折。
 * </pre>
 * <p>r12 现场报告证实：两个 TAIL 挂钩从未命中（方法体没跑完），而 JEI 的 {@code PluginCaller}
 * 会把插件的 RuntimeException 吞掉记日志——游戏不崩，但格雷页面全空。
 * r13 因此改在 HEAD 接管，并<b>把每一步拆成独立的 try/catch</b>：
 * 哪一步坏、坏在什么异常，逐条写进 {@code gtm_jei_fix_report.txt}。
 *
 * <h3>r14：GTM 新版快照（17e1700 起）官方自己修好了，接管策略改成「原生优先、兜底补位」</h3>
 * <p>官方仓库 34f02a6→17e1700 的对比（5 个提交，JEI 集成正是改动区）：
 * <ul>
 * <li>{@code GTJEIPlugin#registerCategories} 里被注释掉的主分类注册<b>解开了</b>——GTM 现在自己
 *     给每个机器分类注册原生页面（ModularUI 真实配方界面，带槽位/箭头/流体，比我们的兜底页好）；</li>
 * <li>{@code GTRecipeJEICategory} 由 abstract 类变成具体类，构造器 {@code (IJeiHelpers, GTRecipeCategory)}，
 *     静态 {@code TYPES} 与静态 {@code registerRecipes} 都还在（我们钉的结构全部不变）；</li>
 * <li>{@code ProgrammedCircuitJeiCategory} 的内部类 {@code GTProgrammedCircuitWrapper} 被删，
 *     GTM 现在交 {@code new Object()} 当那条"配方"；</li>
 * <li>6 个特殊分类的构造器 {@code (IJeiHelpers)} 与静态 {@code registerRecipes} 都没变。</li>
 * </ul>
 * <p>所以 r14 在分类阶段先试 GTM 自己的 {@code GTRecipeJEICategory(helpers, category)}：
 * 构造并注册成功就用<b>格雷原生页</b>（老快照上该类是 abstract，试一下必然失败，自动退回兜底页）；
 * 单个分类失败只影响该分类。程序电路页两种快照形态都能交。
 *
 * <h3>接管后做的事（顺序即 GTM 原方法的顺序）</h3>
 * <ol>
 * <li>特殊分类 6 个逐个补（{@link #addSpecial}）：能成几个成几个；失败的连异常一起报告，
 *     并按 GTM 原方法体的配置开关（hideOreProcessingDiagrams / doBedrockOres）跳过对应步骤。</li>
 * <li>格雷主配方分类逐个挂页面（r14 起优先 GTM 原生页，失败才挂 {@link GtFallbackCategory}）；RecipeType 优先取 GTM 自己的
 *     {@code GTRecipeJEICategory.TYPES}（memoize，与它交配方/交催化剂同一实例），
 *     取不到才按 uid 现造——并且把每个分类实际用的 type 记进 {@link #TYPE_BY_CATEGORY}，
 *     配方阶段用同一个对象，绝不出现「页面挂了 A 类型、配方交给 B 类型」。</li>
 * <li>配方登记（{@code replaceRecipes}）：照 GTM 静态方法的算法自己跑两遍循环（主分类先、
 *     子分类后），每个分类独立容错；特殊分类的静态 registerRecipes 只在
 *     「它的分类这一步确实注册上了」时才代交。装了 EMI 时保持 GTM 原逻辑：整体跳过。</li>
 * <li>催化剂（{@code onCatalystsTail}）：GTM 自己能跑完（r12 已验证），只观测不代登。</li>
 * </ol>
 *
 * <p>去重策略不变：以注册器里已有分类的 uid 集合为基准跳过；每次 {@code addRecipeCategories}
 * 单独 try/catch——JEI 收到重复 uid 抛的 IllegalArgumentException 会被吞掉并记进报告。
 */
public final class GtRegistrationBackfill {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ---- GTM 类名（全部与用户实装构建的 class 文件核对过）----
    private static final String GT_REGISTRIES = "com.gregtechceu.gtceu.api.registry.GTRegistries";
    private static final String GT_RECIPE = "com.gregtechceu.gtceu.api.recipe.GTRecipe";
    private static final String GT_JEI_CATEGORY =
            "com.gregtechceu.gtceu.integration.recipeviewer.jei.recipe.GTRecipeJEICategory";
    private static final String GT_RECIPE_CATEGORY =
            "com.gregtechceu.gtceu.api.recipe.category.GTRecipeCategory";
    private static final String GTCEU = "com.gregtechceu.gtceu.GTCEu";
    private static final String CONFIG_HOLDER = "com.gregtechceu.gtceu.config.ConfigHolder";
    private static final String JEI_PKG = "com.gregtechceu.gtceu.integration.recipeviewer.jei.";

    /** GTM 原方法体里的 6 个特殊分类（顺序、配置守卫与 GTJEIPlugin#registerCategories 一致）。 */
    private static final String SPECIAL_MULTIBLOCK = JEI_PKG + "MultiblockInfoJeiCategory";
    private static final String SPECIAL_ORE_PROCESSING = JEI_PKG + "GTOreProcessingJeiCategory";
    private static final String SPECIAL_ORE_VEIN = JEI_PKG + "orevein.GTOreVeinInfoCategory";
    private static final String SPECIAL_BEDROCK_FLUID = JEI_PKG + "orevein.GTBedrockFluidInfoCategory";
    private static final String SPECIAL_BEDROCK_ORE = JEI_PKG + "orevein.GTBedrockOreInfoCategory";
    private static final String SPECIAL_PROGRAMMED_CIRCUIT = JEI_PKG + "ProgrammedCircuitJeiCategory";

    /** 配方阶段要代交静态 registerRecipes 的特殊分类（程序电路没有静态方法，单独处理）。 */
    private static final String[] SPECIAL_RECIPE_HOSTS = {
            SPECIAL_MULTIBLOCK, SPECIAL_ORE_PROCESSING, SPECIAL_ORE_VEIN,
            SPECIAL_BEDROCK_FLUID, SPECIAL_BEDROCK_ORE,
    };

    /** 装了 EMI 时，GTM 整体跳过配方登记，这些静态 {@code registerRecipes} 由我们代调。 */
    private static final String[] RECIPE_HOSTS = {
            GT_JEI_CATEGORY,
            SPECIAL_MULTIBLOCK, SPECIAL_ORE_PROCESSING, SPECIAL_ORE_VEIN,
            SPECIAL_BEDROCK_FLUID, SPECIAL_BEDROCK_ORE, SPECIAL_PROGRAMMED_CIRCUIT,
    };

    /** 我们补上去的分类页数（给诊断与聊天摘要用）。 */
    private record Added(ResourceLocation uid) {}

    /** 特殊分类注册结果：类全名 → true 注册上了 / false 失败或按配置跳过。保持原顺序。 */
    private static final Map<String, Boolean> SPECIAL_STATE = new LinkedHashMap<>();

    /** 分类对象 → 我们页面实际用的 RecipeType（配方阶段必须用同一个实例）。 */
    private static final Map<Object, RecipeType<?>> TYPE_BY_CATEGORY = new IdentityHashMap<>();

    private static volatile boolean categoriesRan = false;
    private static volatile boolean recipesRan = false;
    private static volatile boolean loggedFailure = false;

    /** 三个挂钩各自是否真的命中（描述符与快照不符时就是 false，靠它才能在日志里说清楚）。 */
    private static volatile boolean categoriesHookFired = false;
    private static volatile boolean recipesHookFired = false;
    private static volatile boolean catalystsHookFired = false;

    private static final List<Added> ADDED = new ArrayList<>();
    /** 我们动手前 JEI 里已有的分类 uid 里属于 gtceu 的个数。 */
    private static volatile int gtceuBefore = 0;
    private static volatile int specialOk = 0;
    /** r14：用格雷自己的页面类（GTRecipeJEICategory）注册成功的机器页数。 */
    private static volatile int nativeOk = 0;
    private static volatile boolean nativeNoted = false;
    private static volatile String recipeSummary = "还没走到";
    private static volatile ClassLoader gtmLoader;

    private GtRegistrationBackfill() {}

    /** 分类挂钩是否命中（供诊断汇总：没命中就把 GTM 真实签名打出来）。 */
    public static boolean categoriesHookFired() {
        return categoriesHookFired;
    }

    /** 三个挂钩各自的命中情况，一行。 */
    public static String hookStatus() {
        return "挂钩命中[分类=" + on(categoriesHookFired)
                + " 配方=" + on(recipesHookFired)
                + " 机器定位=" + on(catalystsHookFired) + "]";
    }

    private static String on(boolean b) {
        return b ? "命中" : "未命中";
    }

    /** 我们补上去的分类页数（给聊天摘要用）。 */
    public static int addedCount() {
        synchronized (GtRegistrationBackfill.class) {
            return ADDED.size();
        }
    }

    /** r14：格雷自己页面类注册成功的机器页数（给聊天摘要用）。 */
    public static int nativeCount() {
        return nativeOk;
    }

    // ==================== 阶段一：分类（HEAD 整体接管） ====================

    /** 取代 GTM 的 registerCategories：6 个特殊分类逐个补，再挂格雷主配方分类页。 */
    public static void replaceCategories(IRecipeCategoryRegistration registration) {
        categoriesHookFired = true;
        FixReport.note("[挂钩命中] GTM#registerCategories（r13 起在 HEAD 整体接管）");
        if (registration == null) return;
        synchronized (GtRegistrationBackfill.class) {
            if (categoriesRan) return;
            categoriesRan = true;
            try {
                Class<?> plugin = Refl.load(JeiReadiness.GT_JEI_PLUGIN, null);
                gtmLoader = plugin == null ? null : plugin.getClassLoader();
                IJeiHelpers helpers = null;
                try {
                    helpers = registration.getJeiHelpers();
                } catch (RuntimeException | LinkageError e) {
                    FixReport.note("[异常] 拿 JEI helpers 失败：" + brief(e));
                }
                // 1) GTM 自己的 6 个特殊分类（守卫条件与 GTM 原方法体一致）
                addSpecial(registration, helpers, SPECIAL_MULTIBLOCK, "多方块结构信息页", true);
                addSpecial(registration, helpers, SPECIAL_ORE_PROCESSING, "矿石处理流程图页",
                        !configBool("compat", "hideOreProcessingDiagrams", false));
                addSpecial(registration, helpers, SPECIAL_ORE_VEIN, "矿脉图表页", true);
                addSpecial(registration, helpers, SPECIAL_BEDROCK_FLUID, "基岩水溶液图表页", true);
                addSpecial(registration, helpers, SPECIAL_BEDROCK_ORE, "基岩矿脉图表页",
                        configBool("machines", "doBedrockOres", true));
                addSpecial(registration, helpers, SPECIAL_PROGRAMMED_CIRCUIT, "程序电路页", true);
                // 2) 格雷主配方分类（研磨/分离/化学合成……）挂兜底页
                runCategories(registration);
                FixReport.note(String.format("[接管分类] 特殊分类成功 %d/6；机器分类页：格雷原生 %d 个 + "
                        + "本模组兜底 %d 个（接管前 JEI 里已有 %d 个 gtceu 分类）。",
                        specialOk, nativeOk, ADDED.size(), gtceuBefore));
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("[gtm_jei_startup_fix] 接管分类注册时出错（游戏不受影响）：{}", e.toString());
                LOGGER.debug("[gtm_jei_startup_fix] 接管分类失败详情", e);
                FixReport.note("[接管分类异常] " + brief(e));
            }
        }
    }

    /** 反射构造 GTM 的一个特殊分类并注册；失败连真实异常写进报告，成功记状态给配方阶段。 */
    private static void addSpecial(IRecipeCategoryRegistration registration, IJeiHelpers helpers,
                                   String className, String label, boolean wanted) {
        if (!wanted) {
            SPECIAL_STATE.put(className, false);
            FixReport.note("[特殊分类] " + label + "：GTM 配置里就是关的，跳过（与原版行为一致）。");
            return;
        }
        if (helpers == null) {
            SPECIAL_STATE.put(className, false);
            return;
        }
        try {
            Class<?> cls = Refl.load(className, gtmLoader);
            if (cls == null) throw new ClassNotFoundException(className);
            Constructor<?> ctor = cls.getDeclaredConstructor(IJeiHelpers.class);
            ctor.setAccessible(true);
            Object instance = ctor.newInstance(helpers);
            if (!(instance instanceof IRecipeCategory<?> category)) {
                throw new IllegalStateException(className + " 不是 JEI 的 IRecipeCategory");
            }
            registration.addRecipeCategories(category);
            SPECIAL_STATE.put(className, true);
            specialOk++;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            SPECIAL_STATE.put(className, false);
            LOGGER.warn("[gtm_jei_startup_fix] 特殊分类「{}」注册失败（跳过它，其余不受影响）：{}",
                    label, brief(e));
            LOGGER.debug("[gtm_jei_startup_fix] 特殊分类失败详情：{}", className, e);
            FixReport.note("[特殊分类失败] " + label + "（" + simpleName(className) + "）：" + brief(e));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void runCategories(IRecipeCategoryRegistration registration) {
        IJeiHelpers helpers = null;
        try {
            helpers = registration.getJeiHelpers();
        } catch (RuntimeException | LinkageError ignored) {
            // addSpecial 里已经报过
        }
        Set<ResourceLocation> existing = collectExistingUids(registration);

        Function<Object, Object> types = resolveTypes();
        Constructor<?> nativeCtor = resolveNativeCategoryCtor();
        Class<?> recipeClass = Refl.load(GT_RECIPE, gtmLoader);
        Object registry = Refl.staticField(Refl.load(GT_REGISTRIES, gtmLoader), "RECIPE_CATEGORIES");
        if (!(registry instanceof Iterable<?> all)) {
            LOGGER.warn("[gtm_jei_startup_fix] 找不到 GTM 的配方分类注册表 {}，补分类跳过。", GT_REGISTRIES);
            FixReport.note("[缺口] 找不到 GTM 配方分类注册表 " + GT_REGISTRIES + "#RECIPE_CATEGORIES，补分类跳过。");
            return;
        }
        for (ResourceLocation uid : existing) {
            if (Refl.isGtceu(uid)) gtceuBefore++;
        }

        int failed = 0;
        int skippedExisting = 0;
        for (Object cat : all) {
            try {
                if (cat == null || !shouldRegisterDisplays(cat)) continue;
                ResourceLocation uid = Refl.resourceIdOf(cat);
                if (uid == null) continue;
                if (!existing.add(uid)) {            // 已经有人注册了这个 uid（GTM 自己或别的模组）
                    skippedExisting++;
                    RecipeType t = recipeTypeFor(types, recipeClass, cat, uid);   // 记住实例，配方要交给它
                    if (t != null) TYPE_BY_CATEGORY.put(cat, t);
                    continue;
                }
                RecipeType type = recipeTypeFor(types, recipeClass, cat, uid);
                if (type == null) {
                    existing.remove(uid);
                    continue;
                }
                TYPE_BY_CATEGORY.put(cat, type);
                // r14：先让格雷自己的页面类上（新版快照它已恢复注册且是真实配方界面）；
                // 老快照该类是 abstract，拿不到构造器，直接走兜底页——行为与 r13 一致。
                IRecipeCategory<?> page = null;
                try {
                    page = tryNativeCategory(nativeCtor, helpers, cat);
                    if (page != null) {
                        registration.addRecipeCategories(page);
                        nativeOk++;
                    }
                } catch (RuntimeException | LinkageError e) {
                    noteNativeOnce(e);
                    page = null;                     // 原生页失败：这一类退回兜底页，不影响别的
                }
                if (page == null) {
                    registration.addRecipeCategories(new GtFallbackCategory(cat, type, helpers, uid));
                    ADDED.add(new Added(uid));
                }
            } catch (RuntimeException | LinkageError e) {
                failed++;
                noteFailure("补分类", e);
            }
        }

        if (nativeOk > 0 || !ADDED.isEmpty()) {
            LOGGER.info("[gtm_jei_startup_fix] 格雷配方分类页接管完成：格雷原生 {} 个、兜底 {} 个"
                            + "（已存在跳过 {} 个，失败 {} 个）；配方由本模组按 GTM 的注册表代交。",
                    nativeOk, ADDED.size(), skippedExisting, failed);
        } else {
            LOGGER.info("[gtm_jei_startup_fix] 格雷配方分类页接管：没有需要补的缺口"
                    + "（已存在 {} 个，失败 {} 个）。", skippedExisting, failed);
        }
    }

    /**
     * r14：GTM 新版快照（17e1700 起）的 {@code GTRecipeJEICategory} 是具体类，
     * 构造器 {@code (IJeiHelpers, GTRecipeCategory)}。拿到就返回构造器，拿不到返回 null（老快照）。
     */
    private static Constructor<?> resolveNativeCategoryCtor() {
        try {
            Class<?> cls = Refl.load(GT_JEI_CATEGORY, gtmLoader);
            Class<?> catCls = Refl.load(GT_RECIPE_CATEGORY, gtmLoader);
            if (cls == null || catCls == null || Modifier.isAbstract(cls.getModifiers())) return null;
            Constructor<?> ctor = cls.getDeclaredConstructor(IJeiHelpers.class, catCls);
            ctor.setAccessible(true);
            return ctor;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.debug("[gtm_jei_startup_fix] 本快照没有可用的 GTM 原生配方页类，用兜底页：{}", e.toString());
            return null;
        }
    }

    /** 用 GTM 自己的页面类构造原生配方页；构造器不可用时返回 null。构造失败抛给调用方记一次报告。 */
    private static IRecipeCategory<?> tryNativeCategory(Constructor<?> ctor, IJeiHelpers helpers, Object cat) {
        if (ctor == null || helpers == null) return null;
        try {
            Object page = ctor.newInstance(helpers, cat);
            return page instanceof IRecipeCategory<?> c ? c : null;
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new RuntimeException(e);
        }
    }

    /** 「原生页用不了」只在报告里写一次，避免每个分类刷一行。 */
    private static void noteNativeOnce(Throwable t) {
        if (nativeNoted) return;
        nativeNoted = true;
        FixReport.note("[原生页退回兜底] GTM 的 GTRecipeJEICategory 构造/注册失败（首个异常："
                + brief(t) + "），该分类及其余机器分类改用本模组兜底页。");
    }

    /** {@code GTRecipeJEICategory.TYPES}：GTM 自己 memoize 的 GTRecipeCategory → RecipeType。 */
    @SuppressWarnings("unchecked")
    private static Function<Object, Object> resolveTypes() {
        Object types = Refl.staticField(Refl.load(GT_JEI_CATEGORY, gtmLoader), "TYPES");
        return types instanceof Function<?, ?> f ? (Function<Object, Object>) f : null;
    }

    /** 优先用 GTM 的 TYPES（与它交配方同一实例）；拿不到才按 uid 现造一个。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static RecipeType recipeTypeFor(Function<Object, Object> types, Class<?> recipeClass,
                                            Object gtCategory, ResourceLocation uid) {
        RecipeType cached = TYPE_BY_CATEGORY.get(gtCategory);
        if (cached != null) return cached;
        if (types != null) {
            try {
                Object t = types.apply(gtCategory);
                if (t instanceof RecipeType rt) return rt;
            } catch (RuntimeException | LinkageError e) {
                noteFailure("取 GTM 的 RecipeType（TYPES.apply）", e);
            }
        }
        if (recipeClass != null) {
            try {
                return new RecipeType(uid, recipeClass);
            } catch (RuntimeException | LinkageError e) {
                noteFailure("现造 RecipeType", e);
            }
        }
        return null;
    }

    /** GTM 的分类里 {@code shouldRegisterDisplays()} 为 false 的不显示，我们同样跳过。 */
    private static boolean shouldRegisterDisplays(Object gtCategory) {
        Object v = call0Quiet(gtCategory, "shouldRegisterDisplays");
        return !(v instanceof Boolean b) || b;      // 方法不存在时按「显示」处理（GTM 新版可能去掉守卫）
    }

    // ==================== 阶段二：配方（HEAD 整体接管） ====================

    /** 取代 GTM 的 registerRecipes：逐分类代交配方，每个分类独立容错，成败都进报告。 */
    public static void replaceRecipes(IRecipeRegistration registration) {
        recipesHookFired = true;
        FixReport.note("[挂钩命中] GTM#registerRecipes（r13 起在 HEAD 整体接管）");
        if (registration == null) return;
        synchronized (GtRegistrationBackfill.class) {
            if (recipesRan) return;
            recipesRan = true;
            try {
                if (gtmLoader == null) {
                    Class<?> plugin = Refl.load(JeiReadiness.GT_JEI_PLUGIN, null);
                    gtmLoader = plugin == null ? null : plugin.getClassLoader();
                }
                if (isLoaded(GTCEU, "isEMILoaded")) {
                    recipeSummary = "装了 EMI：按 GTM 原版逻辑跳过机器配方（只代交静态方法）";
                    int called = 0;
                    for (String host : RECIPE_HOSTS) {
                        if (callStaticRegisterRecipes(host, registration)) called++;
                    }
                    FixReport.note("[接管配方] " + recipeSummary + "，代交静态方法 " + called + " 个。");
                    return;
                }
                runRecipes(registration);
            } catch (RuntimeException | LinkageError e) {
                recipeSummary = "接管配方时出错（见报告）";
                FixReport.note("[接管配方异常] " + brief(e));
                noteFailure("接管配方", e);
            }
        }
    }

    /** 照抄 GTM 静态 registerRecipes 的两段式算法，但每个分类独立 try/catch。 */
    private static void runRecipes(IRecipeRegistration registration) {
        int added = 0, empty = 0, failed = 0, noPage = 0;
        String firstFail = null;
        Object registry = Refl.staticField(Refl.load(GT_REGISTRIES, gtmLoader), "RECIPE_CATEGORIES");
        if (registry instanceof Iterable<?> all) {
            List<Object> subCategories = new ArrayList<>();
            // 主分类先（与 GTM 一致：主分类负责 buildRepresentativeRecipes）
            for (Object cat : all) {
                try {
                    if (cat == null || !shouldRegisterDisplays(cat)) continue;
                    Object gtType = call0Quiet(cat, "getRecipeType");
                    if (gtType == null) continue;
                    if (call0Quiet(gtType, "getCategory") == cat) {
                        call0Quiet(gtType, "buildRepresentativeRecipes");
                    } else {
                        subCategories.add(cat);
                        continue;
                    }
                    switch (addRecipesFor(cat, gtType, registration)) {
                        case OK -> added++;
                        case EMPTY -> { added++; empty++; }
                        case NO_PAGE -> noPage++;
                        case FAIL -> failed++;
                    }
                } catch (RuntimeException | LinkageError e) {
                    failed++;
                    if (firstFail == null) firstFail = brief(e);
                }
            }
            // 子分类后
            for (Object cat : subCategories) {
                try {
                    Object gtType = call0Quiet(cat, "getRecipeType");
                    if (gtType == null) continue;
                    switch (addRecipesFor(cat, gtType, registration)) {
                        case OK -> added++;
                        case EMPTY -> { added++; empty++; }
                        case NO_PAGE -> noPage++;
                        case FAIL -> failed++;
                    }
                } catch (RuntimeException | LinkageError e) {
                    failed++;
                    if (firstFail == null) firstFail = brief(e);
                }
            }
        } else {
            FixReport.note("[缺口] 找不到 GTM 配方分类注册表，机器配方无法代交。");
        }

        // 特殊分类的静态 registerRecipes：只在它的分类注册上了才代交
        int specialCalled = 0;
        for (String host : SPECIAL_RECIPE_HOSTS) {
            if (!Boolean.TRUE.equals(SPECIAL_STATE.get(host))) continue;
            if (callStaticRegisterRecipes(host, registration)) specialCalled++;
        }
        if (Boolean.TRUE.equals(SPECIAL_STATE.get(SPECIAL_PROGRAMMED_CIRCUIT))) {
            specialCalled += addProgrammedCircuitRecipes(registration) ? 1 : 0;
        }

        StringBuilder line = new StringBuilder(String.format(
                "[接管配方] 机器分类交给 JEI：%d 个成功（其中空配方页 %d 个）、失败 %d、无页面跳过 %d；"
                        + "特殊分类代交 %d 个。",
                added, empty, failed, noPage, specialCalled));
        if (firstFail != null) line.append(" 首个失败：").append(firstFail);
        recipeSummary = String.format("机器分类 %d 个已交（空 %d/失败 %d），特殊分类代交 %d 个",
                added, empty, failed, specialCalled);
        LOGGER.info("[gtm_jei_startup_fix] {}", line);
        FixReport.note(line.toString());
    }

    private enum AddResult { OK, EMPTY, NO_PAGE, FAIL }

    /** 把一个 GTM 分类的配方交给它页面对应的 RecipeType（必须是我们注册过页面的那个实例）。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static AddResult addRecipesFor(Object cat, Object gtType, IRecipeRegistration registration) {
        RecipeType type = TYPE_BY_CATEGORY.get(cat);
        if (type == null) {
            // 分类页没挂上（或注册失败）：交过去 JEI 必抛「没有分类」，直接跳过并计数
            return AddResult.NO_PAGE;
        }
        Object recipes = call1Quiet(gtType, "getRecipesInCategory", cat);
        List list = new ArrayList();
        if (recipes instanceof Collection<?> col) {
            for (Object r : col) {
                if (r != null) list.add(r);
            }
        }
        try {
            registration.addRecipes(type, list);
            return list.isEmpty() ? AddResult.EMPTY : AddResult.OK;
        } catch (RuntimeException | LinkageError e) {
            noteFailure("交配方", e);
            return AddResult.FAIL;
        }
    }

    /** 程序电路页的配方：老快照交内部包装对象，17e1700 起包装类删了、GTM 自己交 {@code new Object()}。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean addProgrammedCircuitRecipes(IRecipeRegistration registration) {
        try {
            Class<?> cls = Refl.load(SPECIAL_PROGRAMMED_CIRCUIT, gtmLoader);
            if (cls == null) return false;
            Object type = Refl.staticField(cls, "RECIPE_TYPE");
            Object sample;
            try {
                Class<?> wrapper = Class.forName(SPECIAL_PROGRAMMED_CIRCUIT + "$GTProgrammedCircuitWrapper",
                        true, cls.getClassLoader());
                Constructor<?> ctor = wrapper.getDeclaredConstructor();
                ctor.setAccessible(true);
                sample = ctor.newInstance();
            } catch (ReflectiveOperationException goneInNewSnapshot) {
                sample = new Object();               // 与 17e1700 的 GTJEIPlugin#registerRecipes 等价
            }
            registration.addRecipes((RecipeType) type, List.of(sample));
            return true;
        } catch (RuntimeException | LinkageError e) {
            noteFailure("代交程序电路配方", e);
            return false;
        }
    }

    /** 调某个类上的静态 {@code registerRecipes(IRecipeRegistration)}；找不到/抛了就返回 false。 */
    private static boolean callStaticRegisterRecipes(String className, IRecipeRegistration registration) {
        Class<?> cls = Refl.load(className, gtmLoader);
        if (cls == null) return false;
        try {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals("registerRecipes") && m.getParameterCount() == 1
                        && Modifier.isStatic(m.getModifiers())
                        && m.getParameterTypes()[0].isInstance(registration)) {
                    m.setAccessible(true);
                    m.invoke(null, registration);
                    return true;
                }
            }
        } catch (RuntimeException | LinkageError | ReflectiveOperationException e) {
            LOGGER.warn("[gtm_jei_startup_fix] 代交配方 {} 失败：{}", simpleName(className), brief(e));
            FixReport.note("[代交配方失败] " + simpleName(className) + "：" + brief(e));
        }
        return false;
    }

    // ==================== 阶段三：机器定位（GTM 自己能跑完，只观测） ====================

    public static void onCatalystsTail(IRecipeCatalystRegistration registration) {
        catalystsHookFired = true;
        FixReport.note("[挂钩命中] GTM#registerRecipeCatalysts（机器定位出口，只观测不代登）");
        if (registration == null) return;
        LOGGER.debug("[gtm_jei_startup_fix] 机器定位阶段：GTM 自己的 registerRecipeCatalysts 已跑完。");
    }

    // ==================== 小工具 ====================

    /** GTM 的 ConfigHolder：{@code ConfigHolder.INSTANCE.<group>.<key>}（boolean 字段）；拿不到用默认值。 */
    private static boolean configBool(String group, String key, boolean fallback) {
        try {
            Class<?> holder = Refl.load(CONFIG_HOLDER, gtmLoader);
            Object instance = Refl.staticField(holder, "INSTANCE");
            Object groupObj = Refl.field(instance, group);
            Object v = Refl.field(groupObj, key);
            if (v instanceof Boolean b) return b;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("[gtm_jei_startup_fix] 读 GTM 配置 {}.{} 失败，按默认值 {} 处理：{}",
                    group, key, fallback, e.toString());
        }
        return fallback;
    }

    /** 调无参方法（含接口）。失败返回 null。 */
    private static Object call0Quiet(Object target, String name) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 调一个参数的方法（按参数实例匹配）。失败返回 null。 */
    private static Object call1Quiet(Object target, String name, Object arg) {
        if (target == null) return null;
        try {
            for (Method m : target.getClass().getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1
                        && (arg == null || m.getParameterTypes()[0].isInstance(arg))) {
                    m.setAccessible(true);
                    return m.invoke(target, arg);
                }
            }
        } catch (Throwable ignored) {
            // 少摆一页不影响其它
        }
        return null;
    }

    /**
     * JEI 这个版本只给了 {@code addRecipeCategories}，没给「已注册分类」的查询口，
     * 所以从它的实现类上尽力摸一次（摸不到就当空集合：最坏是白试一个被 JEI 拒收，不会重复显示）。
     */
    private static Set<ResourceLocation> collectExistingUids(IRecipeCategoryRegistration registration) {
        Set<ResourceLocation> out = new LinkedHashSet<>();
        Collection<?> categories = null;
        try {
            Method get = null;
            for (Method m : registration.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && Collection.class.isAssignableFrom(m.getReturnType())
                        && (m.getName().equals("getRecipeCategories") || m.getName().equals("getCategories"))) {
                    get = m;
                    break;
                }
            }
            if (get != null) {
                categories = (Collection<?>) get.invoke(registration);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // 走字段兜底
        }
        if (categories == null) {
            for (Field f : allFields(registration.getClass())) {
                try {
                    if (Map.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        if (f.get(registration) instanceof Map<?, ?> map) categories = map.keySet();
                    } else if (Collection.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        if (f.get(registration) instanceof Collection<?> col) categories = col;
                    }
                    if (categories != null) break;
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    // 下一个字段
                }
            }
        }
        if (categories == null) return out;
        for (Object c : categories) {
            ResourceLocation uid = uidOf(c);
            if (uid != null) out.add(uid);
        }
        return out;
    }

    private static ResourceLocation uidOf(Object categoryOrType) {
        if (categoryOrType instanceof RecipeType<?> rt) return rt.getUid();
        Object type = call0Quiet(categoryOrType, "getRecipeType");
        if (type instanceof RecipeType<?> rt) return rt.getUid();
        Object uid = type == null ? null : call0Quiet(type, "getUid");
        return uid instanceof ResourceLocation rl ? rl : null;
    }

    private static List<Field> allFields(Class<?> cls) {
        List<Field> out = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) out.add(f);
            }
        }
        return out;
    }

    /** 装了 EMI 时，GTM 会整体跳过 JEI 配方登记（它的原版逻辑），这些静态注册由我们代交。 */
    private static boolean isLoaded(String gtceuClass, String methodName) {
        Class<?> mods = Refl.load(gtceuClass + "$Mods", null);
        if (mods == null) {
            Class<?> outer = Refl.load(gtceuClass, null);
            if (outer == null) return false;
            for (Class<?> inner : outer.getDeclaredClasses()) {
                if (inner.getSimpleName().equals("Mods")) {
                    mods = inner;
                    break;
                }
            }
        }
        if (mods == null) return false;
        try {
            Method m = mods.getMethod(methodName);
            m.setAccessible(true);
            return Boolean.TRUE.equals(m.invoke(null));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 给诊断用的进度摘要。 */
    public static String summary() {
        synchronized (GtRegistrationBackfill.class) {
            if (!categoriesHookFired) {
                return hookStatus() + " 接管钩子没触发（GTM 的注册方法结构与钉住的不同，"
                        + "已自动降级为「只修图标」）";
            }
            return String.format("%s 特殊分类 %d/6 成功；补分类页 %d 个（格雷原生 %d 个、"
                            + "本模组兜底 %d 个；接管前已有 %d 个）；配方：%s",
                    hookStatus(), specialOk, nativeOk + ADDED.size(), nativeOk, ADDED.size(),
                    gtceuBefore, recipeSummary);
        }
    }

    /** 异常一句话：类名 + 消息 + 根因，压进报告的一行里。 */
    private static String brief(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = t.getClass().getSimpleName() + ": " + t.getMessage()
                + (root != t ? " ← " + root.getClass().getName() + ": " + root.getMessage() : "");
        return msg.length() > 320 ? msg.substring(0, 320) + "…" : msg;
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }

    private static void noteFailure(String what, Throwable t) {
        if (!loggedFailure) {
            loggedFailure = true;
            LOGGER.warn("[gtm_jei_startup_fix] 接管「{}」首次失败（同类问题后续只记 debug；"
                    + "不影响其它分类与启动）：{}", what, t.toString());
        }
        LOGGER.debug("[gtm_jei_startup_fix] 接管 {} 失败：", what, t);
    }
}
