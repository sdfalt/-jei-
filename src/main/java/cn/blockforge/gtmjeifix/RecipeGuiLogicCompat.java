package cn.blockforge.gtmjeifix;

import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.Rect2i;
import org.slf4j.Logger;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * r70 新增：修复「JEI 升到 19.7x 后一打开配方页就崩」——ModularUI 3.3.1 旧快照里
 * {@code modularui.mixins.json:jei.RecipeGuiLogicMixin} 这条 mixin 的崩溃。
 *
 * <h3>玩家看到的现象</h3>
 * <p>进世界（配方同步事件）或按 R 打开 JEI 手册的那一刻游戏崩溃，崩溃报告底部的
 * {@code Caused by} 固定是这一行：
 * <pre>
 * InjectionError: Critical injection failure: Variable modifier method
 *   modularui$useCategoryMaxHeightForLayout(ILmezz/jei/api/recipe/category/IRecipeCategory;)I
 *   in modularui.mixins.json:jei.RecipeGuiLogicMixin from mod modularui
 *   failed injection check, (0/1) succeeded. Scanned 0 target(s).
 * </pre>
 *
 * <h3>根因（全部拿真 jar 字节码核对过，见 tools/ground-truth/）</h3>
 * <ul>
 * <li>ModularUI 3.3.1-SNAPSHOT（11-14 号 build 及更早，即格雷内嵌的那颗）对 JEI 的
 *     {@code mezz.jei.gui.recipes.RecipeGuiLogic} 打的是
 *     {@code @ModifyVariable(method="getVisibleRecipeLayoutsWithButtons", at=@At("STORE"),
 *     name="recipeHeight")}＋MixinExtras {@code @Local} 捕获 {@code IRecipeCategory}——
 *     它依赖<b>老方法体里一个名叫 recipeHeight 的 int 局部变量</b>；</li>
 * <li>JEI 19.7x 把整个配方页布局重构成网格滚动制：该方法签名从
 *     {@code (int, int, AbstractContainerMenu, BookmarkList, RecipesGui)} 变成
 *     {@code (ImmutableSize2i, AbstractContainerMenu, BookmarkList, RecipesGui)}，
 *     方法体里没有 {@code recipeHeight} 了 → 注入扫到 0 个目标 → sponge-mixin 判
 *     「关键注入失败」→ 游戏崩。这与 r17 的 RecipeSlot 缺字段是同一类问题，只是换到了
 *     配方页尺寸计算这条链上；</li>
 * <li>ModularUI 官方已在 3.3.1-SNAPSHOT 第 9 号 build（2026-11-25，
 *     maven {@code brachy.modularui:modularui-mc1.21.1}）把这条 mixin 改写成
 *     {@code @ModifyArg} 挂到 {@code Optional.map(Function)} 上——但格雷 8.0.0 快照
 *     （截至 build 150/+ddc74f）内嵌/配套的分发件还是旧的 ≤8 号 build，玩家换文件还可能
 *     被内嵌件顶掉，所以本模组在运行时接管：谁的件生效就修谁的崩。</li>
 * </ul>
 *
 * <h3>做法（两件事，判定成立才动手）</h3>
 * <ol>
 * <li><b>停用坏 mixin（外科手术）</b>：从 mixin 框架已解析的 ModularUI 配置里，把
 *     {@code RecipeGuiLogicMixin} 这一条摘掉（{@code MixinConfig} 的 {@code mixins} 列表、
 *     {@code mixinMapping} 各目标桶、以及 {@code mixinClasses}/{@code mixinClassesClient}/
 *     {@code mixinClassesServer} 原始名单一起摘）。摘掉后 JEI 不再因此崩，
 *     ModularUI 其余的 mixin 一条不动。时机在客户端初始化事件——早于
 *     {@code RecipeGuiLogic} 第一次被类转换（打开配方页/配方同步）。</li>
 * <li><b>接管布局修正（等价移植）</b>：按官方第 9 号 build 的做法，用我们自己的
 *     {@code @ModifyArg}（{@code gtm_jei_startup_fix_jeicompat.mixins.json}，priority 900，
 *     defaultRequire=0）把 {@code getVisibleRecipeLayoutsWithButtons} 里传给
 *     {@code Optional.map} 的尺寸函数包一层：配方分类是 {@code ModularUIJeiCategory}
 *     （格雷所有机器配方页都是）时，改用它的 {@code getMaxWidth()/getMaxHeight()} 加
 *     「原尺寸−布局矩形」的边距差作每格尺寸——布局效果与官方修好的版本一致。
 *     目标类一律按字符串锚定、成员用反射取，本模组不编译依赖 JEI 内部类。</li>
 * </ol>
 *
 * <h3>安全边界</h3>
 * <ul>
 * <li>只在「装的是旧版 ModularUI（mixin 字节码里没有 {@code ImmutableSize2i} 引用）」×
 *     「新签名 JEI（{@code RecipeGuiLogic} 原始字节码里有
 *     {@code (Lmezz/jei/common/util/ImmutableSize2i;Lnet/minecraft/world/inventory/AbstractContainerMenu;}
 *     描述符）」同时成立时才动手。老 JEI 上旧 mixin 照样能用，绝不摘（老方法体里也有
 *     {@code Optional.map} 调用点，误挂会把别的函数包错——所以我们的接管件由插件按
 *     本次判定结果决定 {@code shouldApplyMixin}，判定不成立整体不应用）；</li>
 * <li>官方 ModularUI 更新到第 9 号 build 后自动退化为「什么都不做」；</li>
 * <li>配置文件 {@code fixes.enableRecipeGuiLogicCompat=false} 可整体关闭（含停用与接管）；</li>
 * <li>任何一步抛异常都只记日志、写报告，绝不从本类抛出——最坏情况退回「没有本补丁」的原状。</li>
 * </ul>
 */
public final class RecipeGuiLogicCompat {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 被转换的目标类（内部名）。 */
    static final String JEI_LOGIC_SLASH = "mezz/jei/gui/recipes/RecipeGuiLogic";
    /** ModularUI 那份 mixin 配置的文件名（[[mixins]] 登记名）。 */
    private static final String MUI_CONFIG_NAME = "modularui.mixins.json";
    /** 要停用的那条 mixin（配置里的相对名后缀 / 类名后缀都能匹配上）。 */
    private static final String MUI_MIXIN_SUFFIX = "jei.RecipeGuiLogicMixin";
    /** 新签名 JEI 的判据：getVisibleRecipeLayoutsWithButtons 的第一个参数类型描述符片段。 */
    private static final String NEW_JEI_MARKER =
            "(Lmezz/jei/common/util/ImmutableSize2i;Lnet/minecraft/world/inventory/AbstractContainerMenu;";
    /** 修好的 ModularUI（第 9 号 build）的 mixin 字节码里必有这个内部类引用。 */
    private static final String FIXED_MUI_MARKER = "mezz/jei/common/util/ImmutableSize2i";
    /** 接管逻辑要认的 ModularUI 配方分类（格雷机器页都是它的实例）。 */
    private static final String MUI_CATEGORY =
            "brachy.modularui.integration.jei.recipe.ModularUIJeiCategory";

    private static volatile boolean tried = false;
    private static volatile boolean takeoverWanted = false;
    private static volatile String outcome =
            "还没触发（本次启动还没走到客户端初始化，或判定条件没碰上）";

    /** 接管注入的运行时命中计数（第一次命中把「已生效」写进报告）。 */
    private static final AtomicInteger WRAP_HITS = new AtomicInteger();
    private static volatile boolean wrapReported = false;

    private RecipeGuiLogicCompat() {}

    /** 给自检 / 命令读的一句话结论。 */
    public static String outcome() {
        return outcome;
    }

    /** /gtmfix status 用的一行（现状＋版本＋开关）。 */
    public static String statusLine() {
        return "配方页布局兼容（修复⑥·r70）：开关=" + (FixConfig.recipeGuiLogicCompatEnabled() ? "开" : "关")
                + "，JEI " + FixReport.modVersion("jei")
                + " × ModularUI " + FixReport.modVersion("modularui")
                + "；本次判定：" + outcome;
    }

    /** 插件用它决定「接管用的 @ModifyArg mixin 本次要不要应用」。 */
    public static boolean takeoverWanted() {
        return takeoverWanted;
    }

    /** 幂等入口：客户端初始化时调用；任何异常只降级不抛出。 */
    public static void install() {
        if (tried) {
            return;
        }
        synchronized (RecipeGuiLogicCompat.class) {
            if (tried) {
                return;
            }
            tried = true;
            try {
                doInstall();
            } catch (Throwable t) {
                takeoverWanted = false;
                outcome = "执行出错（已退回「没有本补丁」的原状）：" + t;
                LOGGER.warn("[gtm_jei_startup_fix] RecipeGuiLogic 兼容处理出错（不影响其余功能）：{}",
                        t.toString());
                FixReport.note("[配方页布局兼容] 处理出错、本次未生效：" + t);
            }
        }
    }

    private static void doInstall() throws Exception {
        boolean jeiLoaded;
        boolean muiLoaded;
        try {
            jeiLoaded = net.neoforged.fml.ModList.get().isLoaded("jei");
            muiLoaded = net.neoforged.fml.ModList.get().isLoaded("modularui");
        } catch (Throwable t) {
            outcome = "读不到已装模组清单，本次什么都没做：" + t;
            return;
        }
        if (!jeiLoaded) {
            outcome = "没有检测到 JEI，本次没有可修的东西（属正常）";
            return;
        }
        if (!muiLoaded) {
            outcome = "没有检测到 ModularUI（坏 mixin 不在），本次什么都没做（属正常）";
            return;
        }
        if (!FixConfig.recipeGuiLogicCompatEnabled()) {
            outcome = "已按配置关闭（fixes.enableRecipeGuiLogicCompat=false），本次没动 ModularUI 的 mixin";
            LOGGER.info("[gtm_jei_startup_fix] RecipeGuiLogic 兼容补丁已按配置关闭，"
                    + "旧 ModularUI × 新 JEI 的组合下次打开配方页仍会崩——改回 true 并重启即恢复保护。");
            return;
        }

        // ---------- 1. 找到 ModularUI 那份已解析的 mixin 配置 ----------
        Class<?> configClass = Class.forName("org.spongepowered.asm.mixin.transformer.Config");
        Field allField = configClass.getDeclaredField("allConfigs");
        allField.setAccessible(true);
        Object allRaw = allField.get(null);
        if (!(allRaw instanceof Map<?, ?> all)) {
            outcome = "mixin 框架的内部结构与预检不符（allConfigs 不是 Map），本次什么都没做";
            return;
        }
        Object muiConfig = null;
        for (Object c : all.values()) {
            if (c == null) {
                continue;
            }
            Object name = invokeQuiet(c, "getName");
            if (name instanceof String s && s.endsWith(MUI_CONFIG_NAME)) {
                muiConfig = c;
                break;
            }
        }
        if (muiConfig == null) {
            outcome = "在 mixin 框架里没找到 " + MUI_CONFIG_NAME
                    + "（可能 ModularUI 换了配置名），本次什么都没做";
            return;
        }
        Object iConfig = invokeQuiet(muiConfig, "getConfig"); // IMixinConfig → MixinConfig 实例
        if (iConfig == null) {
            outcome = "取不到 ModularUI 的 MixinConfig 实例，本次什么都没做";
            return;
        }

        // ---------- 2. 摘出那条 mixin 的 MixinInfo，判定新旧 ----------
        List<Object> infos = collectMixinInfos(iConfig, MUI_MIXIN_SUFFIX);
        if (infos.isEmpty()) {
            outcome = "ModularUI 的配置里已经查不到 RecipeGuiLogicMixin（官方已删/改名），本次什么都没做";
            return;
        }
        boolean fixedMui = false;
        boolean brokenMui = false;
        for (Object info : infos) {
            Object bytes = invokeQuiet(info, "getClassBytes");
            if (bytes instanceof byte[] b) {
                String pool = new String(b, StandardCharsets.ISO_8859_1);
                if (pool.contains(FIXED_MUI_MARKER)) {
                    fixedMui = true;
                } else {
                    brokenMui = true;
                }
            }
        }
        if (fixedMui && !brokenMui) {
            outcome = "ModularUI 已是官方修好的快照（mixin 里带 ImmutableSize2i 引用，对照第 9 号 build），无需处理";
            LOGGER.info("[gtm_jei_startup_fix] RecipeGuiLogic 兼容补丁：检测到的 ModularUI 已自带新版 JEI "
                    + "适配，本模组不插手。");
            return;
        }

        // ---------- 3. 判定 JEI 是不是「新签名」（只读原始字节码，绝不触发类转换） ----------
        String logicBytes = readRawClassBytes();
        if (logicBytes == null) {
            outcome = "读不到 RecipeGuiLogic 的原始字节码（JEI " + FixReport.modVersion("jei")
                    + "），为求稳本次什么都没做";
            LOGGER.warn("[gtm_jei_startup_fix] RecipeGuiLogic 兼容补丁：取类原始字节失败，本次停用与接管都不做。");
            return;
        }
        if (!logicBytes.contains(NEW_JEI_MARKER)) {
            outcome = "JEI 还是旧签名（" + FixReport.modVersion("jei")
                    + "），ModularUI 的老挂钩本来就能用，无需处理";
            return;
        }

        // ---------- 4. 外科手术：从配置里摘掉坏 mixin ----------
        int removed = stripMixinFromConfig(iConfig, MUI_MIXIN_SUFFIX);
        if (removed <= 0) {
            outcome = "确认是「旧 ModularUI × 新 JEI」，但从配置里摘 mixin 没成功（结构对不上），"
                    + "本次什么都没做——打开配方页仍会崩，请把日志发回来";
            LOGGER.warn("[gtm_jei_startup_fix] RecipeGuiLogic 兼容补丁：mixin 摘除失败（removed=0）。");
            return;
        }
        takeoverWanted = true;

        String msg = "[配方页布局兼容] 已停用 ModularUI 对新版 JEI 失效的 RecipeGuiLogicMixin（旧配方页布局挂钩），"
                + "并由本模组按官方修复方案接管（效果与 ModularUI 第 9 号快照一致）："
                + "JEI " + FixReport.modVersion("jei")
                + "，ModularUI " + FixReport.modVersion("modularui")
                + "。关掉它：配置 fixes.enableRecipeGuiLogicCompat=false（会退回崩溃原状，不建议）。";
        outcome = "已停用坏 mixin（摘掉 " + removed
                + " 处引用），接管注入已挂上，第一次打开配方页时见真章";
        LOGGER.info("[gtm_jei_startup_fix] {}", msg);
        FixReport.note(msg);
    }

    // ---------------- 配置摘除辅助 ----------------

    /** 在 MixinConfig 的 mixins 列表 / mixinMapping 各桶里找出目标 MixinInfo。 */
    private static List<Object> collectMixinInfos(Object iConfig, String suffix) {
        List<Object> out = new ArrayList<>();
        List<?> mixins = asList(readField(iConfig, "mixins"));
        for (Object info : mixins) {
            if (matchesMixin(info, suffix)) {
                out.add(info);
            }
        }
        Object mapping = readField(iConfig, "mixinMapping");
        if (mapping instanceof Map<?, ?> m) {
            for (Object v : m.values()) {
                for (Object info : asList(v)) {
                    if (matchesMixin(info, suffix) && !containsIdentity(out, info)) {
                        out.add(info);
                    }
                }
            }
        }
        return out;
    }

    private static boolean containsIdentity(List<Object> list, Object o) {
        for (Object x : list) {
            if (x == o) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesMixin(Object info, String suffix) {
        return endsWithMixin(invokeQuiet(info, "getClassName"), suffix)
                || endsWithMixin(invokeQuiet(info, "getClassRef"), suffix)
                || endsWithMixin(invokeQuiet(info, "getName"), suffix);
    }

    /** 把 '/' 归一成 '.' 再按「jei.RecipeGuiLogicMixin」这类后缀匹配。 */
    private static boolean endsWithMixin(Object maybeName, String suffix) {
        if (!(maybeName instanceof String s)) {
            return false;
        }
        String norm = s.replace('/', '.').replace(".class", "");
        return norm.equals(suffix) || norm.endsWith("." + suffix);
    }

    /** 从 mixins 列表、mixinMapping 各桶、mixinClasses* 原始名单里摘掉它；返回摘掉的处数。 */
    private static int stripMixinFromConfig(Object iConfig, String suffix) {
        int removed = 0;
        removed += removeFromList(readField(iConfig, "mixins"), suffix);
        Object mapping = readField(iConfig, "mixinMapping");
        if (mapping instanceof Map<?, ?> m) {
            for (Object v : m.values()) {
                removed += removeFromList(v, suffix);
            }
        }
        for (String raw : new String[]{"mixinClasses", "mixinClassesClient", "mixinClassesServer"}) {
            Object list = readField(iConfig, raw);
            if (list instanceof Collection<?>) {
                removed += removeRawStrings(list, suffix);
            }
        }
        return removed;
    }

    private static int removeFromList(Object listObj, String suffix) {
        if (!(listObj instanceof List<?> list)) {
            return 0;
        }
        int n = 0;
        for (Object o : new ArrayList<>(list)) {
            if (matchesMixin(o, suffix)) {
                list.remove(o);
                n++;
            }
        }
        return n;
    }

    private static int removeRawStrings(Object listObj, String suffix) {
        if (!(listObj instanceof List<?> list)) {
            return 0;
        }
        int n = 0;
        for (Object o : new ArrayList<>(list)) {
            if (o instanceof String && endsWithMixin(o, suffix)) {
                list.remove(o);
                n++;
            }
        }
        return n;
    }

    /** 只读资源流拿原始字节码（不加载、不转换，绝不触发崩溃路径）。 */
    private static String readRawClassBytes() {
        try {
            Object service = Class.forName("org.spongepowered.asm.service.MixinService")
                    .getMethod("getService").invoke(null);
            Object in = service.getClass().getMethod("getResourceAsStream", String.class)
                    .invoke(service, JEI_LOGIC_SLASH + ".class");
            if (!(in instanceof InputStream stream)) {
                return null;
            }
            try (InputStream is = stream) {
                return new String(is.readAllBytes(), StandardCharsets.ISO_8859_1);
            }
        } catch (Throwable t) {
            LOGGER.debug("[gtm_jei_startup_fix] 走 mixin 服务读字节失败：{}", t.toString());
        }
        // 兜底：常见类加载器的资源视图
        for (ClassLoader cl : new ClassLoader[]{
                RecipeGuiLogicCompat.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader()}) {
            if (cl == null) {
                continue;
            }
            try (InputStream is = cl.getResourceAsStream(JEI_LOGIC_SLASH + ".class")) {
                if (is != null) {
                    return new String(is.readAllBytes(), StandardCharsets.ISO_8859_1);
                }
            } catch (Throwable ignored) {
                // 试下一个
            }
        }
        return null;
    }

    // ---------------- 接管注入的实际逻辑（等价移植 ModularUI 第 9 号 build） ----------------

    /**
     * 把 JEI 传给 {@code Optional.map} 的尺寸函数包一层：配方分类是 ModularUIJeiCategory 时
     * 用它的 {@code getMaxWidth()/getMaxHeight()}＋（原尺寸−布局矩形）的边距差重算格子尺寸。
     * 与官方修复逐句对齐；任何一环取不到就把原函数原样送回。
     */
    @SuppressWarnings("unchecked")
    public static Function<Object, Object> wrapSizeFunction(Function<Object, Object> original) {
        if (WRAP_HITS.incrementAndGet() == 1 && !wrapReported) {
            wrapReported = true;
            String msg = "[配方页布局兼容] 接管注入已生效（第一次为格雷/ModularUI 配方页算每格尺寸）。";
            LOGGER.info("[gtm_jei_startup_fix] {}", msg);
            FixReport.note(msg);
            outcome = "已停用坏 mixin 并完成接管（配方页布局修正已在本次启动里实际生效）";
        }
        return layout -> applyWrapped(original, layout);
    }

    private static Object applyWrapped(Function<Object, Object> original, Object layout) {
        try {
            Object size = original.apply(layout);
            if (size == null || layout == null) {
                return size;
            }
            Object drawable = invokeQuiet(layout, "getRecipeLayout");
            if (drawable == null) {
                return size;
            }
            Object category = invokeQuiet(drawable, "getRecipeCategory");
            if (category == null || !isModularUiCategory(category.getClass())) {
                return size;
            }
            int maxW = callInt(category, "getMaxWidth");
            int maxH = callInt(category, "getMaxHeight");
            if (maxW < 0 || maxH < 0) {
                return size;
            }
            int rectW = 0;
            int rectH = 0;
            Object rect = invokeQuiet(drawable, "getRect");
            if (rect instanceof Rect2i r) {
                rectW = r.getWidth();
                rectH = r.getHeight();
            }
            int sizeW = callInt(size, "width");
            int sizeH = callInt(size, "height");
            if (sizeW < 0 || sizeH < 0) {
                return size;
            }
            Constructor<?> ctor = size.getClass().getConstructor(int.class, int.class);
            return ctor.newInstance(maxW + (sizeW - rectW), maxH + (sizeH - rectH));
        } catch (Throwable t) {
            // 任何一步都不值得为它把配方页弄崩：退回 JEI 自己算的尺寸。
            return original.apply(layout);
        }
    }

    /** 类名沿父链找 ModularUIJeiCategory（不加载它、不依赖编译期符号）。 */
    private static boolean isModularUiCategory(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            if (MUI_CATEGORY.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    private static int callInt(Object target, String name) {
        Object v = invokeQuiet(target, name);
        return v instanceof Integer i ? i : -1;
    }

    // ---------------- 反射小工具（全静默失败） ----------------

    private static Object invokeQuiet(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object readField(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {
                // 试父类
            }
        }
        return null;
    }

    private static List<?> asList(Object o) {
        return o instanceof List<?> l ? new ArrayList<>(l) : List.of();
    }
}
