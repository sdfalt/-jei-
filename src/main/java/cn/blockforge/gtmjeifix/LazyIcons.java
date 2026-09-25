package cn.blockforge.gtmjeifix;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * r5 的核心：「会自动恢复的占位图标」（lazy self-healing icon）。
 *
 * <h3>为什么 r4 的「先跳过、后补建」没效果</h3>
 * <p>GTM 的 {@code CategoryIcon} 在构造那一刻就把结果存进私有字段
 * {@code wrappedValue}；JEI 在<b>注册分类</b>时就把 {@code icon.get()} 的返回值
 * 取走并缓存到自己的分类表里（分类标签、布局都按这个引用画）。
 * r4 让它在 JEI 就绪前等于 {@code null}，事后用反射把真图标写回
 * {@code wrappedValue} —— 但 JEI 手里缓存的那个引用<b>永远是当初的 null</b>，
 * 写回字段改变不了已经交出去的空值，所以启动不崩了、图标却还是空的。
 *
 * <h3>r5 的做法</h3>
 * <p>延后时不再返回 {@code null}，而是返回一个「占位图标」对象：它实现了 JEI 的
 * {@code mezz.jei.api.gui.drawable.IDrawable} 接口（用 JDK 动态代理实现，
 * 本模组不需要编译期依赖 JEI），并且<b>把自己留在原地</b>。
 * 之后任何人（JEI 分类标签、配方界面）用到它——查宽高、绘制——它都会先做一次
 * 「现在 JEI 就绪了吗？」的检查，就绪了就当场用 GTM 自己的
 * {@code CategoryIcon$JeiCallWrapper.getRenderable(...)} 造出真实图标，
 * 并把后续调用全部转发过去。
 *
 * <p>关键在于：JEI 缓存的那个引用就是这个占位对象本身，所以它<b>一旦被用到就自动生效</b>，
 * 与「就绪时刻」的先后顺序完全无关；再配合 {@code onRuntimeAvailable} 回调与客户端
 * tick 轮询的提前恢复，正常情况下界面第一次打开时图标就已经是真的了。
 *
 * <h3>安全边界</h3>
 * <ul>
 * <li>占位对象的任何方法都不抛异常：没恢复时宽高返回 16（GTM 的分类图标本来就是
 *     {@code drawableBuilder(..., 16, 16)} 与物品图标 16x16，尺寸与真值一致，
 *     不会造成布局跳变），绘制什么都不画。</li>
 * <li>反射结构对不上时整体降级为「返回 null」，也就是 r4 的行为：不崩、图标为空。</li>
 * <li>恢复有次数上限，绝不出现每帧抛异常的开销。</li>
 * </ul>
 */
public final class LazyIcons {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 单个图标最多尝试恢复的次数（界面每帧都会用到，几百次足够跨越数秒）。 */
    private static final int MAX_ATTEMPTS = 600;
    /** 全局最多创建多少个占位图标（GTM 只有几百个分类；异常刷量时保护内存）。 */
    private static final int MAX_LIVE = 4000;
    /** 相邻两次恢复尝试的最小间隔，避免 GUI 每帧都反射一次。 */
    private static final long MIN_ATTEMPT_INTERVAL_NANOS = 50_000_000L;

    /** 所有存活的占位图标（弱引用式：恢复成功后会被摘除）。 */
    private static final List<Holder> LIVE = new ArrayList<>();
    private static final AtomicInteger CREATED = new AtomicInteger();
    private static final AtomicInteger HEALED = new AtomicInteger();
    /** 恢复过程中抛异常的次数；连续多次说明结构真的不对，才整体降级。 */
    private static final AtomicInteger RECOVER_FAILURES = new AtomicInteger();
    private static final int MAX_RECOVER_FAILURES = 5;

    /** 置真后不再尝试任何占位/恢复，行为退回「返回 null」。 */
    private static volatile boolean permanentlyDisabled = false;
    private static volatile boolean loggedDeferred = false;
    private static volatile boolean loggedFirstHeal = false;
    private static volatile boolean loggedGiveUp = false;
    private static volatile boolean loggedForwardError = false;

    /**
     * 重入保护：占位图标内部会直调 GTM 的 {@code getRenderable}，
     * 此时 {@code CategoryIconJeiWrapperMixin} 必须放行，否则会自己拦自己。
     */
    private static final ThreadLocal<Boolean> BYPASS = new ThreadLocal<>();

    private LazyIcons() {}

    /** 是否处于「直调 GTM 原始方法」的保护中（Mixin 据此放行）。 */
    public static boolean isBypassed() {
        return BYPASS.get() != null;
    }

    /**
     * 造一个占位图标。
     *
     * @param source GTM 构造 {@code CategoryIcon} 时传的参数：贴图路径或 {@code ItemStack}
     * @return 实现了 JEI {@code IDrawable} 的占位对象；结构对不上时返回 {@code null}
     */
    public static Object create(Object source) {
        if (permanentlyDisabled || source == null) return null;
        // 动态代理要实现的接口签名里带客户端类（GuiGraphics），服务端不做占位，交回 null 即可
        if (!net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) return null;
        if (CREATED.get() >= MAX_LIVE) return null;
        try {
            // 关键：代理的接口直接取 GTM 那个方法的<b>返回类型</b>，
            // 保证与 Mixin 生成的 checkcast 是同一个 Class 对象（不同类加载器各加载一份
            // IDrawable 时，按名字找到的接口会判为不同类型而抛 ClassCastException）。
            Method getRenderable = wrapperMethod(source);
            Class<?> drawable = getRenderable.getReturnType();
            if (!drawable.isInterface()) {
                drawable = JeiReadiness.loadClass(JeiReadiness.IDRAWABLE);
            }
            if (!drawable.isInterface()) {
                disable("JEI 的 IDrawable 不是接口，占位图标不可用");
                return null;
            }
            Holder holder = new Holder(source, getRenderable);
            Object proxy = Proxy.newProxyInstance(
                    drawable.getClassLoader(), new Class<?>[] {drawable}, holder);
            holder.self = proxy;
            synchronized (LIVE) {
                LIVE.add(holder);
            }
            CREATED.incrementAndGet();
            if (!loggedDeferred) {
                loggedDeferred = true;
                LOGGER.warn("[gtm_jei_startup_fix] 检测到 GTM 分类图标在 JEI 就绪前被创建："
                        + "已换成会自动恢复的占位图标（不崩溃、不永久空白，JEI 就绪后自动显示真实图标）。");
                FixReport.note("[拦截生效] 首次拦到「JEI 未就绪时建图标」→ 换成占位图标（崩溃路径已被顶住）。");
            }
            return proxy;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            disable("占位图标创建失败：" + e);
            return null;
        }
    }

    /** GTM 的 {@code CategoryIcon$JeiCallWrapper.getRenderable(贴图路径 / ItemStack)}。 */
    private static Method wrapperMethod(Object source) throws ReflectiveOperationException {
        Class<?> wrapper = JeiReadiness.loadClass(JeiReadiness.JEI_CALL_WRAPPER);
        Class<?> param = source instanceof ItemStack ? ItemStack.class : ResourceLocation.class;
        Method m = wrapper.getDeclaredMethod("getRenderable", param);
        m.setAccessible(true);
        return m;
    }

    /**
     * 尝试恢复所有还没恢复的占位图标（由 {@code onRuntimeAvailable} 回调和
     * 客户端 tick 轮询调用；即使一次都不调，图标也会在第一次被使用时自行恢复）。
     *
     * @return 本次恢复出的真实图标数量
     */
    public static int healAll() {
        if (permanentlyDisabled) return 0;
        int healedNow = 0;
        synchronized (LIVE) {
            Iterator<Holder> it = LIVE.iterator();
            while (it.hasNext()) {
                Holder h = it.next();
                if (h.real != null) {
                    it.remove();
                    continue;
                }
                if (h.resolve() != null) {
                    it.remove();
                    healedNow++;
                }
            }
        }
        return healedNow;
    }

    /** 还有没有等待恢复的占位图标。 */
    public static boolean hasPending() {
        if (permanentlyDisabled) return false;
        synchronized (LIVE) {
            return !LIVE.isEmpty();
        }
    }

    /** 已创建的占位图标总数（日志核对用）。 */
    public static int createdCount() {
        return CREATED.get();
    }

    /** 已恢复的图标总数（日志核对用）。 */
    public static int healedCount() {
        return HEALED.get();
    }

    /** 这个对象是不是本模组造的占位图标。 */
    public static boolean isPlaceholder(Object maybe) {
        if (maybe == null || !Proxy.isProxyClass(maybe.getClass())) return false;
        try {
            return Proxy.getInvocationHandler(maybe) instanceof Holder;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 是占位图标、但还没恢复成真实图标？ */
    public static boolean isUnhealedPlaceholder(Object maybe) {
        if (!isPlaceholder(maybe)) return false;
        try {
            return ((Holder) Proxy.getInvocationHandler(maybe)).real == null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 是否已经永久降级（不再尝试占位/恢复）。 */
    public static boolean isDisabled() {
        return permanentlyDisabled;
    }

    /** 立即尝试补建并汇报结果；返回是否已无待补建项。 */
    public static boolean healAllAndReport(String reason) {
        int healed = 0;
        try {
            healed = healAll();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("[gtm_jei_startup_fix] {}：补建分类图标出错（占位图标仍会自愈）：{}",
                    reason, e.toString());
        }
        int created = createdCount();
        if (!hasPending()) {
            LOGGER.info("[gtm_jei_startup_fix] {}：分类图标补建完成，{}/{} 个已恢复为真实图标"
                    + "（JEI 分类标签与配方界面可正常显示）。", reason, healedCount(), created);
            FixReport.note(String.format("[图标] 分类图标补建完成：%d/%d 已恢复为真实图标（%s）。",
                    healedCount(), created, reason));
            return true;
        }
        LOGGER.info("[gtm_jei_startup_fix] {}：本次恢复 {} 个分类图标，已恢复 {}/{} 个，"
                + "其余会在被用到时自行恢复。", reason, healed, healedCount(), created);
        return false;
    }

    private static void disable(String why) {
        if (permanentlyDisabled) return;
        permanentlyDisabled = true;
        synchronized (LIVE) {
            LIVE.clear();
        }
        LOGGER.error("[gtm_jei_startup_fix] {}；已降级为「空图标」行为："
                + "游戏可以正常启动，但提前创建的 GTM 分类图标不会显示。", why);
        FixReport.note("[降级] 占位图标整体停用：" + why);
    }

    /**
     * 一个占位图标：实现 {@code IDrawable} 的所有方法，未恢复时给出安全默认值，
     * 恢复后全部转发给 GTM/JEI 造出来的真实图标。
     */
    private static final class Holder implements InvocationHandler {

        private final Object source;
        /** GTM 的 {@code JeiCallWrapper.getRenderable(...)}，创建占位时已解析好。 */
        private final Method getRenderable;
        /** 真实图标；null 表示还没恢复出来。 */
        private volatile Object real;
        private Object self;
        private int attempts;
        private boolean attempted;
        private long lastAttemptNanos;

        private Holder(Object source, Method getRenderable) {
            this.source = source;
            this.getRenderable = getRenderable;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return switch (name) {
                    case "equals" -> args != null && args.length == 1 && args[0] == this.self;
                    case "hashCode" -> System.identityHashCode(this.self);
                    case "toString" -> "gtm_jei_startup_fix:LazyIcon(" + describeSource() + ")";
                    default -> null;
                };
            }
            Object target = resolve();
            if (target == null) {
                // 还没恢复：给出与真值一致的默认尺寸 / 什么都不画，绝不让 JEI 崩溃。
                return defaultValue(method.getReturnType());
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException | IllegalAccessException | RuntimeException e) {
                Throwable cause = e instanceof InvocationTargetException ite ? ite.getCause() : e;
                if (!loggedForwardError) {
                    loggedForwardError = true;
                    LOGGER.error("[gtm_jei_startup_fix] 转发分类图标调用给 JEI 真实图标时出错，"
                            + "该图标保持为空（不影响游戏运行）：{}", String.valueOf(cause));
                }
                return defaultValue(method.getReturnType());
            }
        }

        /** 尝试拿到真实图标；拿不到返回 null（下次再试）。 */
        private Object resolve() {
            Object cached = real;
            if (cached != null) return cached;
            if (permanentlyDisabled) return null;
            long now = System.nanoTime();
            // 节流：GUI 每帧都会用到图标，不能每帧都反射一次（首次尝试不受限）
            if (attempted && now - lastAttemptNanos < MIN_ATTEMPT_INTERVAL_NANOS) return null;
            attempted = true;
            lastAttemptNanos = now;
            // 只有真的发起一次恢复才计数，避免被 GUI 高帧率瞬间耗光额度
            if (++attempts > MAX_ATTEMPTS) {
                if (!loggedGiveUp) {
                    loggedGiveUp = true;
                    LOGGER.warn("[gtm_jei_startup_fix] 分类图标连续 {} 次恢复未果，停止重试"
                            + "（已恢复 {}/{} 个；不影响游戏运行）。",
                            MAX_ATTEMPTS, HEALED.get(), CREATED.get());
                }
                return null;
            }
            if (!JeiReadiness.isRuntimeStored()) return null;

            BYPASS.set(Boolean.TRUE);
            try {
                Object created = getRenderable.invoke(null, source);
                if (created != null) {
                    real = created;
                    HEALED.incrementAndGet();
                    if (!loggedFirstHeal) {
                        loggedFirstHeal = true;
                        LOGGER.info("[gtm_jei_startup_fix] JEI 已就绪，分类图标开始自动恢复"
                                + "（首个：{}）。", describeSource());
                    }
                }
                return created;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                Throwable cause = e instanceof InvocationTargetException ite ? ite.getCause() : e;
                int failures = RECOVER_FAILURES.incrementAndGet();
                if (failures >= MAX_RECOVER_FAILURES) {
                    disable("连续 " + failures + " 次恢复分类图标失败（最近一次：" + cause + "）");
                } else {
                    LOGGER.debug("[gtm_jei_startup_fix] 本次恢复分类图标未果（第 {} 次，稍后继续重试）：{}",
                            failures, String.valueOf(cause));
                }
                return null;
            } finally {
                BYPASS.remove();
            }
        }

        private String describeSource() {
            return source instanceof ResourceLocation rl ? rl.toString() : String.valueOf(source);
        }

        private static Object defaultValue(Class<?> returnType) {
            if (returnType == int.class) {
                // GTM 分类图标真实尺寸就是 16x16，未恢复时报 16 与真值一致，不会造成布局错位。
                return 16;
            }
            if (returnType == boolean.class) return Boolean.FALSE;
            if (returnType == long.class) return 0L;
            if (returnType == float.class) return 0f;
            if (returnType == double.class) return 0d;
            if (returnType == short.class) return (short) 0;
            if (returnType == byte.class) return (byte) 0;
            if (returnType == char.class) return (char) 0;
            return null;
        }
    }
}
