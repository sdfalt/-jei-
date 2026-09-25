package cn.blockforge.gtmjeifix;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * 「JEI 运行时到底准备好了没有」的唯一权威判据。
 *
 * <p>GTM 8.0.0-SNAPSHOT 的源码（官方映射，未混淆）：
 * <pre>
 * com.gregtechceu.gtceu.integration.recipeviewer.jei.GTJEIPlugin
 *     &#64;Getter private static IJeiRuntime runtime = null;
 *     public void onRuntimeAvailable(IJeiRuntime jeiRuntime) { runtime = jeiRuntime; }
 * </pre>
 * 也就是说：<b>{@code GTJEIPlugin.getRuntime() != null} 就是「JEI 已就绪」的定义本身</b>。
 *
 * <p>上一版（r4）把「已就绪」当成只有 {@code onRuntimeAvailable} 回调才置位的一个标记，
 * 那个回调一旦没被调到（JEI 版本/插件顺序差异都可能），整套补建就永远不会发生——
 * 这正是本次「启动成功但图标仍然没有」的直接原因之一。现在改成<b>两条路同时判断</b>：
 * <ol>
 * <li>回调标记（由 {@code GTJEIPluginMixin} 在 {@code onRuntimeAvailable} 之后置位，最快）；</li>
 * <li>反射直接探测 {@code getRuntime()} 的真实值（不依赖任何回调，兜底且绝对可靠）。</li>
 * </ol>
 * 任一为真即视为已就绪。探测失败（类没加载、结构不符）一律视为「未就绪」，绝不抛异常。
 */
public final class JeiReadiness {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** GTM 的图标包装类。 */
    public static final String CATEGORY_ICON =
            "com.gregtechceu.gtceu.integration.recipeviewer.CategoryIcon";
    /** GTM 图标类里负责调用 JEI 的私有静态内部类。 */
    public static final String JEI_CALL_WRAPPER = CATEGORY_ICON + "$JeiCallWrapper";
    /** GTM 的 JEI 插件类（{@code runtime} 静态字段的宿主）。 */
    public static final String GT_JEI_PLUGIN =
            "com.gregtechceu.gtceu.integration.recipeviewer.jei.GTJEIPlugin";
    /** JEI 的图标接口（r5 的占位图标实现它）。 */
    public static final String IDRAWABLE = "mezz.jei.api.gui.drawable.IDrawable";

    /** 由 {@code GTJEIPluginMixin} 在 {@code onRuntimeAvailable} 执行完毕后置位。 */
    private static volatile boolean hookFired = false;
    /** 探测用的 {@code GTJEIPlugin.getRuntime()}，懒解析并缓存。 */
    private static volatile Method getRuntimeMethod;
    /** 反射探测结构不符时置真，停止探测（只降级，不影响游戏）。 */
    private static volatile boolean probeUnavailable = false;

    private JeiReadiness() {}

    /** Mixin 回调：GTM 已经拿到 JEI 运行时。 */
    public static void markHookFired() {
        hookFired = true;
    }

    /**
     * GTM 静态字段 {@code GTJEIPlugin.runtime} 是否已经被 JEI 填入。
     * 这是决定「图标能不能真的建出来」的唯一依据。
     */
    public static boolean isRuntimeStored() {
        if (hookFired) return true;
        return runtime() != null;
    }

    /**
     * 反射读取 {@code GTJEIPlugin.getRuntime()} 的真实值。
     * 结构对不上或还没填入时返回 null（不抛异常）。
     */
    public static Object runtime() {
        if (probeUnavailable) return null;
        try {
            Method m = getRuntimeMethod;
            if (m == null) {
                Class<?> plugin = loadClass(GT_JEI_PLUGIN);
                m = plugin.getMethod("getRuntime");
                m.setAccessible(true);
                getRuntimeMethod = m;
            }
            return m.invoke(null);
        } catch (NoSuchMethodException | ClassNotFoundException | NoClassDefFoundError e) {
            // 结构真的对不上：停止探测，之后只认 onRuntimeAvailable 回调
            probeUnavailable = true;
            LOGGER.warn("[gtm_jei_startup_fix] 找不到 GTJEIPlugin.getRuntime()（GTM 结构与本模组核对的不符），"
                    + "分类图标的补建将只依赖 onRuntimeAvailable 回调：{}", e.toString());
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // 只是这一次没读到（类尚未初始化等）：下次继续探测，不永久放弃
            LOGGER.debug("[gtm_jei_startup_fix] 本次探测 GTJEIPlugin.getRuntime() 未成功，稍后重试：{}",
                    e.toString());
            return null;
        }
    }

    /** 按多个候选类加载器查找类（NeoForge 下 mod 类加载器互不可见时也能命中）。 */
    static Class<?> loadClass(String name) throws ClassNotFoundException {
        ClassNotFoundException first = null;
        ClassLoader[] candidates = {
                Thread.currentThread().getContextClassLoader(),
                JeiReadiness.class.getClassLoader(),
                ClassLoader.getSystemClassLoader(),
        };
        for (ClassLoader cl : candidates) {
            if (cl == null) continue;
            try {
                return Class.forName(name, false, cl);
            } catch (ClassNotFoundException e) {
                if (first == null) first = e;
            } catch (LinkageError e) {
                if (first == null) first = new ClassNotFoundException(name, e);
            }
        }
        throw (first != null ? first : new ClassNotFoundException(name));
    }
}
