package cn.blockforge.gtmjeifix;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 现场探针 + 额外的一次提前补建。
 *
 * <p>通过 JEI 自己的公开接口（全程反射，无编译期依赖）在 JEI 就绪的那一刻统计：
 * <ul>
 * <li>JEI 一共收到了多少个配方分类；</li>
 * <li>其中命名空间是 {@code gtceu:} 的有几个（用来判断「格雷科技的分类到底有没有注册进 JEI」
 *     ——这是图标之外的另一种「没效果」，需要区分）；</li>
 * <li>这些分类的图标是 null、还是我们尚未恢复的占位图标、还是已经是真实图标。</li>
 * </ul>
 *
 * <p>顺带做一次「顺着 JEI 的缓存摸一遍」的补建：对每个分类调用一次
 * {@code getIcon().getWidth()}，占位图标被摸到的那一刻就会恢复成真实图标，
 * 比等界面打开更早，且覆盖的正好是 JEI 实际会去画的那些对象。
 *
 * <p>任何一步失败都只写一行 debug 日志，绝不影响游戏。
 */
public final class JeiDiagnostics {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 已给出结论（统计成功，或中途拿到一半发现 JEI 结构对不上）。结论只报一次。 */
    private static volatile boolean settled = false;
    /** 统计成功时给聊天摘要用的一行字；失败时为 null。 */
    private static volatile String okSummary = null;
    /** 结论是「失败」时记原因（结构对不上的具体哪一步）。 */
    private static volatile String failReason = null;
    /** 挂钩覆盖度体检只报一次（它可能被 mixin 与轮询各调一次）。 */
    private static volatile boolean hookCoverageReported = false;

    /** GTM 的 JEI 插件类在两代快照里的位置（只为打诊断签名用，逐个试探）。 */
    private static final String[] GT_PLUGIN_CANDIDATES = {
            JeiReadiness.GT_JEI_PLUGIN,
            "com.gregtechceu.gtceu.integration.jei.GTJEIPlugin",
    };

    private JeiDiagnostics() {}

    /** 反射取 GTM 手里的 {@code IJeiRuntime}（Object，避免编译期依赖 JEI），然后统计。幂等：出过结论就直接返回。 */
    public static void probeAndHeal() {
        if (settled) return;
        Object runtime = JeiReadiness.runtime();
        if (runtime == null) return;
        try {
            Object manager = invoke(runtime, "getRecipeManager");
            if (manager == null) {
                fail("拿不到 IRecipeManager（getRecipeManager() 返回 null，JEI 结构可能已变）");
                return;
            }
            Object lookup = invoke(manager, "createRecipeCategoryLookup");
            if (lookup == null) {
                fail("拿不到 createRecipeCategoryLookup()（JEI 结构可能已变）");
                return;
            }
            Object withHidden = invoke(lookup, "includeHidden");
            Object stream = invoke(withHidden != null ? withHidden : lookup, "get");
            if (!(stream instanceof Stream<?> raw)) {
                fail("分类清单不可读（get() 不是 Stream，JEI 结构可能已变）");
                return;
            }
            List<?> categories = raw.toList();

            int total = categories.size();
            int gtceu = 0;
            int nullIcon = 0;
            int unhealedBefore = 0;
            int unhealedAfter = 0;
            List<String> samples = new ArrayList<>();

            for (Object category : categories) {
                ResourceLocation uid = uidOf(category);
                if (uid == null || !"gtceu".equals(uid.getNamespace())) continue;
                gtceu++;
                if (samples.size() < 5) samples.add(uid.toString());
                Object icon;
                try {
                    icon = invoke(category, "getIcon");
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    icon = null;
                }
                if (icon == null) {
                    nullIcon++;
                    continue;
                }
                boolean pending = LazyIcons.isUnhealedPlaceholder(icon);
                if (pending) unhealedBefore++;
                try {
                    invoke(icon, "getWidth");   // 摸一下就自愈
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    // 单个图标失败不影响统计
                }
                if (LazyIcons.isUnhealedPlaceholder(icon)) unhealedAfter++;
            }

            String line = String.format(
                    "JEI 现有配方分类 %d 个，其中 gtceu 的 %d 个%s；图标为空 %d 个，摸一遍后仍未恢复 %d 个"
                            + "（本模组已补建 %d/%d 个占位图标）。接管情况：%s",
                    total, gtceu, samples.isEmpty() ? "" : "（例：" + String.join(", ", samples) + "）",
                    nullIcon, unhealedAfter, LazyIcons.healedCount(), LazyIcons.createdCount(),
                    GtRegistrationBackfill.summary());
            LOGGER.info("[gtm_jei_startup_fix] 诊断：{}", line);
            okSummary = "统计完成：JEI 里共 " + total + " 个配方分类，gtceu 的有 " + gtceu + " 个"
                    + "（本模组补了 " + GtRegistrationBackfill.addedCount() + " 个分类页）。";
            settled = true;
            FixReport.note("[诊断] " + line);
            if (gtceu == 0) {
                LOGGER.warn("[gtm_jei_startup_fix] 注意：JEI 里仍然一个 gtceu 配方分类都没有。GTM 这个快照既没把分类"
                        + "注册进 JEI，本模组的补注册也没生效（多半是 GTM 快照结构与核对的不一致）——"
                        + "把现场报告 gtm_jei_fix_report.txt 发回来即可继续适配。");
                FixReport.note("[!] JEI 里没有任何 gtceu 分类，且补注册也没生效——看下面的挂钩/GTM方法扫描行定位缺口。");
            } else if (unhealedAfter == 0 && nullIcon == 0) {
                LOGGER.info("[gtm_jei_startup_fix] JEI 里已有 {} 个 gtceu 配方分类且图标齐全，"
                        + "分类标签应可在 JEI 中正常显示（打开 JEI 搜任意格雷配方即可验证）。", gtceu);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.debug("[gtm_jei_startup_fix] JEI 分类诊断跳过：{}", e.toString());
            fail("诊断过程抛异常：" + e);
        }
    }

    /** 出一个「失败结论」（只出一次），同时落现场报告。 */
    private static void fail(String reason) {
        if (settled) return;
        settled = true;
        failReason = reason;
        LOGGER.warn("[gtm_jei_startup_fix] 诊断未能完成：{}", reason);
        FixReport.note("[诊断未完成] " + reason);
    }

    /**
     * 轮询用的统一入口：到点就把该报的都报一遍（内部全部幂等）。
     *
     * <p>r12 的坑：挂钩覆盖度体检曾被标题界面的轮询<b>抢跑</b>——那时 JEI 还没开始调
     * 插件注册，三个挂钩当然都「未命中」，报告里就出现了一行假的「挂钩未命中」。
     * 现在只有两种时刻允许下这个结论：JEI 已经交出运行时（注册流程必然跑过），
     * 或者到了 {@code late}（轮询的兜底时限）。
     */
    public static void ensureProbe(boolean late) {
        probeAndHeal();
        if (late || JeiReadiness.runtime() != null) {
            reportHookCoverage();
        }
    }

    /** 是否已经给出结论（成功或失败）。 */
    public static boolean settled() {
        return settled;
    }

    /** 给聊天栏用的一行结论；还没结论时给出「目前卡在哪」。 */
    public static String chatSummary() {
        String ok = okSummary;
        if (ok != null) return ok;
        if (failReason != null) return "诊断没走完：" + failReason;
        if (JeiReadiness.runtime() == null) {
            return "还没等到 JEI 就绪（GTM 没拿到运行时）——你的 GTM/JEI 组合结构可能和核对的不一致";
        }
        return "统计还没跑完";
    }

    /**
     * r7 新增：补注册挂钩的覆盖度体检。
     *
     * <p>钉死方法描述符是双刃剑——快照一变，Mixin 就「找不到方法」（r6 那次是「找到了但参数类型不符」，
     * 直接把游戏崩了；现在只会静默跳过）。为了下一次适配不再靠猜，这里把 GTM 插件类
     * <b>真实的方法签名</b>原样打进日志：用户只要把带 {@code [gtm_jei_startup_fix]} 的行发回来，
     * 我们就能照抄成正确的描述符。
     */
    public static void reportHookCoverage() {
        if (hookCoverageReported) return;
        hookCoverageReported = true;
        try {
            if (GtRegistrationBackfill.categoriesHookFired()) {
                FixReport.note("[挂钩] GTM#registerCategories 命中，接管注册已执行。");
                return;
            }
            LOGGER.warn("[gtm_jei_startup_fix] 接管的挂钩没命中：GTM 这个快照里注册方法的签名与本模组"
                    + "钉住的不一致（已自动降级为「只修图标、不接管注册」，不影响启动）。真实签名清单：{}",
                    describeGtPluginMethods());
            FixReport.note("[挂钩未命中] GTM#registerCategories 的 HEAD 钩子没触发——GTM 插件方法结构与钉住"
                    + "的不一致，已降级为「只修图标」。下面把 GTM 插件类真实方法原样抄出来。");
            FixReport.note("[GTM方法扫描] " + describeGtPluginMethods());
        } catch (Throwable t) {
            LOGGER.debug("[gtm_jei_startup_fix] 挂钩覆盖度体检跳过：{}", t.toString());
        }
    }

    /** 反射列出 GTM 的 JEI 插件类里跟注册有关的方法（名字(参数全名):返回全名）。 */
    private static String describeGtPluginMethods() {
        ClassLoader loader = null;
        Object runtime = JeiReadiness.runtime();
        if (runtime != null) loader = runtime.getClass().getClassLoader();
        for (String name : GT_PLUGIN_CANDIDATES) {
            Class<?> cls = Refl.load(name, loader);
            if (cls == null) continue;
            List<String> sigs = new ArrayList<>();
            for (Method m : cls.getDeclaredMethods()) {
                String n = m.getName();
                if (!(n.startsWith("register") || n.startsWith("apply")
                        || n.startsWith("onRuntime") || n.startsWith("getRuntime"))) continue;
                StringBuilder sb = new StringBuilder(n).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(ps[i].getName());
                }
                sigs.add(sb.append(')').append(':').append(m.getReturnType().getName()).toString());
                if (sigs.size() >= 24) {
                    sigs.add("...(已截断)");
                    break;
                }
            }
            return cls.getName() + " = [" + String.join(" | ", sigs) + "]";
        }
        return "（没找到 GTJEIPlugin 类，GTM 的包结构可能已经变了）";
    }

    private static ResourceLocation uidOf(Object category) {
        try {
            Object type = invoke(category, "getRecipeType");
            if (type == null) return null;
            Object uid = invoke(type, "getUid");
            return uid instanceof ResourceLocation rl ? rl : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static Object invoke(Object target, String method) throws ReflectiveOperationException {
        if (target == null) throw new NoSuchMethodException(method);
        Method m = target.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }
}
