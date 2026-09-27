package cn.blockforge.gtmjeifix;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * r36 新增：<b>修复「静默失效」的自检</b>，顺带把「本模组版本」与「mods 里装了多份同名 jar」
 * 这两件玩家最容易踩空的事汇总成一行行结论。
 *
 * <h3>要治的病</h3>
 * <p>{@code gtm_jei_startup_fix_muicompat.mixins.json} 里写的是
 * {@code "defaultRequire": 0}——注入目标（ModularUI / GTM 的类和方法）找不到时<b>不许崩，
 * 宁可跳过</b>。这是当年为了不拖垮启动特意设的，但副作用是：格雷 / ModularUI / JEI 一旦更新、
 * 类名或方法签名一变，对应的界面校正就<b>一声不响地不干活</b>。玩家只会发现
 * 「多方块预览又偏了」，可模组自己完全不知道，报告里也查不出来。
 *
 * <h3>两道判据，缺一不可</h3>
 * <ol>
 * <li><b>注入目标还在不在（预检）</b>：用 {@code loadClass}（只加载、不初始化，无副作用）
 *     把每条校正的钉住目标类取出来，再用反射核对方法名与参数个数。类没了 / 方法签名变了
 *     ＝这条校正<b>本次必然不会生效</b>——不需要等玩家去点界面就能下结论；</li>
 * <li><b>挂钩真跑过没有（运行时）</b>：各校正自己都有「回调第一次执行」的计数
 *     （{@code hookFired}），跑过＝注入落地，这是最强证据。两条合起来覆盖四种状态：
 *     落地并已跑 / 落地但还没用到（正常）/ 目标变了必不生效（报警）/ 目标在但「界面明明用过
 *     挂钩却没跑」（报警，弹出菜单那条特别加了这条判据：只要玩家显示过任何 ModularUI 界面，
 *     {@code postFullResize} 就必然被调到）。</li>
 * </ol>
 *
 * <h3>结论往哪儿送</h3>
 * <p>第一次执行时把逐行结论写进<b>现场报告与本次启动日志</b>（{@code [校正自检]} 开头的几行），
 * 有报警时再各追加一条 {@code ⚠} 行；进世界聊天栏与 {@code /gtmfix status} 都读同一份结果。
 * 报警文案按当初的约定：「⚠ 界面校正本次未生效，可能是游戏版本更新，请把日志发给作者」。
 *
 * <h3>铁律不变</h3>
 * <p>本类只是<b>读</b>与<b>说</b>，不改变任何修复行为；全部动作 try/catch 包裹，
 * 自检本身坏到什么都读不到时也只是少说几行，绝不拖垮游戏。只在客户端路径被调用
 * （聊天摘要 / status 命令 / 客户端轮询），专用服务端上无事发生。
 */
final class FixHealth {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 只做一次（结果进 report/日志只写一轮；状态行每次现读，聊天与命令随时可查）。 */
    private static volatile boolean ranOnce = false;

    /** 玩家是否显示过任何 ModularUI 界面——「弹出菜单」那条自检的「明明该用到了」证据。 */
    private static volatile boolean muiScreenSeen = false;

    /** 每个修复一条结论（{@link #runOnce()} 里一次性填好）。 */
    private static final List<Item> ITEMS = new ArrayList<>();

    /** mods 文件夹里的重复 jar 提示；没有重复时为 null。 */
    private static volatile String jarDupWarning;

    private FixHealth() {}

    // ---------------- 状态模型 ----------------

    /** 一条修复的自检判据结果。 */
    enum Verdict {
        /** 已确认落地（挂钩真跑过，或本来就属「不落地的话游戏根本起不来」的硬挂钩）。 */
        LANDED,
        /** 注入目标在、方法签名没变，只是这次还没用到——正常。 */
        WAIT_USE,
        /** 已按配置关闭——不是坏了，是玩家关的。 */
        OFF_BY_CONFIG,
        /** 依赖的那个模组没装，本就没有可打补丁的目标——正常。 */
        NO_TARGET_MOD,
        /** 目标类或方法签名变了——这条校正本次【必然】不生效。 */
        TARGET_GONE,
        /** 目标看着还在，但玩家明明用过对应界面、挂钩却一次没跑——多半没落地。 */
        FIRED_NEVER,
        /** 校正自己连续出错停用了（不是版本问题，也得上报）。 */
        BROKEN,
        /** 反射都读不到，判不了——不下结论，只如实说。 */
        UNCLEAR
    }

    /** 一条修复的自检记录。 */
    private static final class Item {
        final String name;      // 「修复④·多方块预览」这样的短名
        final Verdict verdict;
        final String detail;    // 给人看的一句话

        Item(String name, Verdict verdict, String detail) {
            this.name = name;
            this.verdict = verdict;
            this.detail = detail;
        }

        /** 这条要不要在聊天栏/报告里拉响 ⚠。 */
        boolean suspicious() {
            return verdict == Verdict.TARGET_GONE
                    || verdict == Verdict.FIRED_NEVER
                    || verdict == Verdict.BROKEN;
        }

        private String mark() {
            switch (verdict) {
                case LANDED:        return "✅";
                case WAIT_USE:
                case NO_TARGET_MOD: return "…";
                case OFF_BY_CONFIG: return "（已按配置关闭）";
                case TARGET_GONE:
                case FIRED_NEVER:
                case BROKEN:        return "⚠";
                default:            return "?";
            }
        }
    }

    // ---------------- 钉住的注入目标（与 mixin 配置里的完全一致） ----------------

    /** 修复⑤ 的挂钩目标：ModularUI 排版收尾那一拍。 */
    private static final String MUI_RESIZE_NODE = "brachy.modularui.widget.sizer.WidgetResizeNode";
    private static final String MUI_RESIZE_METHOD = "postFullResize";

    /** 修复④ 的两半：读 JEI 页面原点的入口 ＋ 把原点加进 GL 视口的方法。 */
    private static final String MUI_EMBED_WIDGET =
            "brachy.modularui.integration.jei.recipe.ModularUIJeiCategory$UIWrapperWidget";
    private static final String MUI_EMBED_METHOD = "drawWidget";
    private static final String MUI_VIEWPORT = "brachy.modularui.drawable.schema.Viewport";
    private static final String MUI_VIEWPORT_METHOD = "calculateOpenGLViewportFromRectangle";

    /** 修复② 的挂钩目标：GTM 的 JEI 插件类。 */
    private static final String GT_PLUGIN =
            "com.gregtechceu.gtceu.integration.recipeviewer.jei.GTJEIPlugin";

    // ---------------- 入口 ----------------

    /**
     * 幂等：把五项修复各判一遍、写进报告与日志。重复调用只有第一次会落盘，
     * 但返回值（{@link #summaryLine()} 等）永远现算，配置开关中途变了也能如实反映。
     */
    static void runOnce() {
        boolean first;
        synchronized (FixHealth.class) {
            first = !ranOnce;
            ranOnce = true;
        }
        try {
            ITEMS.clear();
            ITEMS.add(checkCrashFix());
            ITEMS.add(checkCategoryBackfill());
            ITEMS.add(checkRecipeSlotCompat());
            ITEMS.add(checkMultiblockOffset());
            ITEMS.add(checkMenuKeepOnScreen());
            jarDupWarning = findDuplicateJars();
        } catch (Throwable t) {
            LOGGER.debug("[gtm_jei_startup_fix] 校正自检没跑完（不影响游戏）：{}", t.toString());
        }
        if (first) {
            try {
                FixReport.note("[校正自检（r36 一次性体检）] " + summaryLine());
                String warn = warningLine();
                if (warn != null) {
                    FixReport.note(warn);
                    LOGGER.warn("[gtm_jei_startup_fix] {}", warn);
                }
                if (jarDupWarning != null) {
                    FixReport.note(jarDupWarning);
                    LOGGER.warn("[gtm_jei_startup_fix] {}", jarDupWarning);
                }
                LOGGER.info("[gtm_jei_startup_fix] [校正自检] {}", summaryLine());
            } catch (Throwable t) {
                LOGGER.debug("[gtm_jei_startup_fix] 校正自检写报告失败（不影响游戏）：{}", t.toString());
            }
        }
    }

    /** 客户端轮询里顺带探测：玩家显示过 ModularUI 界面没有（修复⑤自检的证据之一）。 */
    static void observeModularScreen() {
        if (muiScreenSeen) return;
        try {
            net.minecraft.client.gui.screens.Screen s =
                    net.minecraft.client.Minecraft.getInstance().screen;
            if (s == null) return;
            for (Class<?> c = s.getClass(); c != null; c = c.getSuperclass()) {
                if ("brachy.modularui.screen.ModularScreen".equals(c.getName())) {
                    muiScreenSeen = true;
                    return;
                }
            }
        } catch (Throwable ignored) {
            // 探测失败顶多是少一条证据，绝不影响游戏
        }
    }

    // ---------------- 五项修复逐条体检 ----------------

    /** 修复①：启动崩溃。它是 require=1 的硬挂钩——不落地的话游戏当场就崩，没有「静默」可言。 */
    private static Item checkCrashFix() {
        if (!FixConfig.crashFixEnabled()) {
            return new Item("修复①·启动崩溃", Verdict.OFF_BY_CONFIG,
                    "已按配置关闭（fixes.enableStartupCrashFix=false）");
        }
        return new Item("修复①·启动崩溃", Verdict.LANDED,
                "硬挂钩（require=1）：游戏能进就说明已落地");
    }

    /** 修复②：格雷分类补注册。挂钩命中与否有现成计数；没命中时再用反射看 GTM 类的方法还在不在。 */
    private static Item checkCategoryBackfill() {
        if (!FixConfig.categoryBackfillEnabled()) {
            return new Item("修复②·格雷分类补注册", Verdict.OFF_BY_CONFIG,
                    "已按配置关闭（fixes.enableCategoryBackfill=false）");
        }
        if (GtRegistrationBackfill.categoriesHookFired()) {
            return new Item("修复②·格雷分类补注册", Verdict.LANDED,
                    "挂钩已命中（接管新增 " + GtRegistrationBackfill.addedCount()
                            + " 页，格雷原生 " + GtRegistrationBackfill.nativeCount() + " 页）");
        }
        if (!JeiDiagnostics.settled()) {
            return new Item("修复②·格雷分类补注册", Verdict.WAIT_USE,
                    "JEI 注册流程还没跑完（启动早期属正常）");
        }
        // JEI 都就绪了、挂钩还没命中：看目标方法是不是变了
        TargetCheck t = checkMethod(GT_PLUGIN, "registerCategories", 1);
        if (t == TargetCheck.CLASS_GONE) {
            return new Item("修复②·格雷分类补注册", Verdict.TARGET_GONE,
                    "找不到 GTM 的 " + GT_PLUGIN + "（GTM 快照结构变了）");
        }
        if (t == TargetCheck.METHOD_GONE) {
            return new Item("修复②·格雷分类补注册", Verdict.TARGET_GONE,
                    "GTM 的 GTJEIPlugin#registerCategories 签名变了（本模组钉的是 1 参数版本）");
        }
        return new Item("修复②·格雷分类补注册", Verdict.FIRED_NEVER,
                "JEI 已就绪但挂钩没命中——详见报告里 [GTM方法扫描] 那行");
    }

    /** 修复③：RecipeSlot 字段补丁。它只在类转换那一刻干一次，插件自己记了结果。 */
    private static Item checkRecipeSlotCompat() {
        String outcome = cn.blockforge.gtmjeifix.mixin.compat.JeiRecipeSlotCompatPlugin.outcome();
        if (outcome.startsWith("还没触发")) {
            return new Item("修复③·RecipeSlot字段补丁", Verdict.WAIT_USE, outcome);
        }
        if (outcome.startsWith("已按配置关闭")) {
            return new Item("修复③·RecipeSlot字段补丁", Verdict.OFF_BY_CONFIG, outcome);
        }
        if (outcome.startsWith("执行出错")) {
            return new Item("修复③·RecipeSlot字段补丁", Verdict.BROKEN, outcome);
        }
        return new Item("修复③·RecipeSlot字段补丁", Verdict.LANDED, outcome);
    }

    /** 修复④：多方块 3D 预览位置校正（两条 mixin 各查一遍目标，再读运行时计数）。 */
    private static Item checkMultiblockOffset() {
        if (!FixConfig.multiblockOffsetEnabled()) {
            return new Item("修复④·多方块预览校正", Verdict.OFF_BY_CONFIG,
                    "已按配置关闭（fixes.enableMultiblockEmbedOffset=false）");
        }
        if (MultiblockEmbedOffset.isRuntimeDisabled()) {
            return new Item("修复④·多方块预览校正", Verdict.BROKEN,
                    "捕获页面原点连续异常后自动停用了（见报告里的「停用」行）");
        }
        if (MultiblockEmbedOffset.isHookFired()) {
            return new Item("修复④·多方块预览校正", Verdict.LANDED, "挂钩已命中（JEI 内嵌绘制入口已跑到）");
        }
        if (muiAbsent()) {
            return new Item("修复④·多方块预览校正", Verdict.NO_TARGET_MOD,
                    "没装 ModularUI，格雷配方页不走那条渲染链——本就没有要打的目标");
        }
        TargetCheck a = checkMethod(MUI_EMBED_WIDGET, MUI_EMBED_METHOD, 3);
        TargetCheck b = checkMethod(MUI_VIEWPORT, MUI_VIEWPORT_METHOD, 4);
        if (a == TargetCheck.CLASS_GONE || b == TargetCheck.CLASS_GONE) {
            return new Item("修复④·多方块预览校正", Verdict.TARGET_GONE,
                    "ModularUI 的类找不到（" + (a == TargetCheck.CLASS_GONE ? MUI_EMBED_WIDGET : MUI_VIEWPORT)
                            + "），ModularUI 可能更新了");
        }
        if (a == TargetCheck.METHOD_GONE || b == TargetCheck.METHOD_GONE) {
            return new Item("修复④·多方块预览校正", Verdict.TARGET_GONE,
                    "ModularUI 的方法签名变了（drawWidget / calculateOpenGLViewportFromRectangle 有一处对不上）");
        }
        if (a == TargetCheck.UNKNOWN || b == TargetCheck.UNKNOWN) {
            return new Item("修复④·多方块预览校正", Verdict.UNCLEAR, "反射读不了这条链，不下结论");
        }
        return new Item("修复④·多方块预览校正", Verdict.WAIT_USE,
                "注入目标都在，只是本次还没打开过格雷的多方块结构页（打开一次即生效）");
    }

    /** 修复⑤：弹出菜单不出屏。挂钩在「任何 MUI 界面排版」时都会跑，所以「用过界面没」能当证据。 */
    private static Item checkMenuKeepOnScreen() {
        if (!FixConfig.menuKeepOnScreenEnabled()) {
            return new Item("修复⑤·弹出菜单不出屏", Verdict.OFF_BY_CONFIG,
                    "已按配置关闭（fixes.enableMenuKeepOnScreen=false）");
        }
        if (MenuKeepOnScreen.isFailDisabled()) {
            return new Item("修复⑤·弹出菜单不出屏", Verdict.BROKEN,
                    "校正过程连续出错后自动停用了（见报告里的「停用」行）");
        }
        if (MenuKeepOnScreen.isHookFired()) {
            return new Item("修复⑤·弹出菜单不出屏", Verdict.LANDED, "挂钩已落地（postFullResize 真跑过）");
        }
        if (muiAbsent()) {
            return new Item("修复⑤·弹出菜单不出屏", Verdict.NO_TARGET_MOD,
                    "没装 ModularUI，弹出菜单是格雷自己那套之外的东西——本就没有要打的目标");
        }
        TargetCheck t = checkMethod(MUI_RESIZE_NODE, MUI_RESIZE_METHOD, 0);
        if (t == TargetCheck.CLASS_GONE) {
            return new Item("修复⑤·弹出菜单不出屏", Verdict.TARGET_GONE,
                    "找不到 ModularUI 的 " + MUI_RESIZE_NODE + "（ModularUI 可能更新了）");
        }
        if (t == TargetCheck.METHOD_GONE) {
            return new Item("修复⑤·弹出菜单不出屏", Verdict.TARGET_GONE,
                    "ModularUI 的 WidgetResizeNode#postFullResize 签名变了（本模组钉的是无参版本）");
        }
        if (t == TargetCheck.UNKNOWN) {
            return new Item("修复⑤·弹出菜单不出屏", Verdict.UNCLEAR, "反射读不了这条链，不下结论");
        }
        if (muiScreenSeen) {
            return new Item("修复⑤·弹出菜单不出屏", Verdict.FIRED_NEVER,
                    "玩家已显示过 ModularUI 界面、挂钩却没跑过一次——这条大概率没落地");
        }
        return new Item("修复⑤·弹出菜单不出屏", Verdict.WAIT_USE,
                "注入目标都在，只是本次还没打开过任何 ModularUI 界面");
    }

    // ---------------- 预检的小工具 ----------------

    private enum TargetCheck { OK, CLASS_GONE, METHOD_GONE, UNKNOWN }

    /** 类没了还是方法对不上？只加载不初始化（无副作用；mixin 本来也在这时应用）。 */
    private static TargetCheck checkMethod(String className, String methodName, int paramCount) {
        try {
            ClassLoader loader = FixHealth.class.getClassLoader();
            Class<?> c = loader == null ? Class.forName(className)
                    : Class.forName(className, false, loader);
            for (Method m : c.getDeclaredMethods()) {
                if (methodName.equals(m.getName()) && m.getParameterCount() == paramCount) {
                    return TargetCheck.OK;
                }
            }
            return TargetCheck.METHOD_GONE;
        } catch (ClassNotFoundException e) {
            return TargetCheck.CLASS_GONE;
        } catch (Throwable t) {
            return TargetCheck.UNKNOWN;
        }
    }

    /** ModularUI 装没装（读不到 ModList 时当装了，宁可多查不误报）。 */
    private static boolean muiAbsent() {
        try {
            return net.neoforged.fml.ModList.get().getModContainerById("modularui").isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- 对外结论 ----------------

    /** 一行总览（报告 / 聊天 / status 共用）。 */
    static String summaryLine() {
        if (ITEMS.isEmpty()) return "自检还没跑";
        StringBuilder sb = new StringBuilder();
        for (Item i : ITEMS) {
            if (sb.length() > 0) sb.append("｜");
            sb.append(i.mark()).append(i.name).append('：').append(i.detail);
        }
        return sb.toString();
    }

    /** 有可疑项时的一行 ⚠ 报警；全都正常返回 null（聊天栏就少一行）。 */
    static String warningLine() {
        List<String> bad = new ArrayList<>();
        for (Item i : ITEMS) {
            if (i.suspicious()) bad.add(i.name + "：" + i.detail);
        }
        if (bad.isEmpty()) return null;
        return "⚠ 界面校正本次可能未生效（" + String.join("；", bad)
                + "）。多半是格雷 / ModularUI / JEI 更新导致的，其余修复不受影响；"
                + "请把 " + FixReport.logHint() + "（或 /gtmfix report 指路的文件）发给作者，一眼能定位。";
    }

    /** mods 文件夹里同名 jar 只有一份（或读不了）时返回 null，否则返回一句 ⚠ 提示。 */
    static String jarDupWarning() {
        if (jarDupWarning == null && ranOnce) {
            jarDupWarning = findDuplicateJars();   // 首次没扫到就现在补扫一次
        }
        return jarDupWarning;
    }

    // ---------------- 重复 jar 检测 ----------------

    private static final Pattern JAR_TAG = Pattern.compile("-r(\\d+)");

    /**
     * 扫 {@code mods} 文件夹里所有 {@code gtm_jei_startup_fix*.jar}：装了不止一份就提示
     * 只留最新——多份同名 jar 会同时打补丁，行为不可预测，这个冲突几乎人人都踩。
     * 读不动目录（权限/启动器魔改路径）就安静地返回 null。
     */
    private static String findDuplicateJars() {
        try {
            Path mods = FMLPaths.MODSDIR.get();
            if (mods == null || !Files.isDirectory(mods)) return null;
            List<String> names = new ArrayList<>();
            int maxOther = -1;
            final int mine = currentRNumber();
            try (var files = Files.list(mods)) {
                for (Path p : (Iterable<Path>) files::iterator) {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!n.contains(GtmJeiStartupFix.MOD_ID) || !n.endsWith(".jar")) continue;
                    names.add(p.getFileName().toString());
                    int r = rNumberOf(n);
                    if (mine >= 0 && r > maxOther && r != mine) maxOther = r;
                }
            }
            if (names.size() <= 1) return null;
            StringBuilder sb = new StringBuilder("⚠ mods 文件夹里有 " + names.size()
                    + " 份本模组的 jar（" + String.join("、", names)
                    + "）：同一个模组的多份副本会同时打补丁、行为不可预测——请只保留最新那一份，其余删掉。");
            if (maxOther > mine) {
                sb.append("（注意：里面有一份 r").append(maxOther)
                        .append("，比正在运行的 r").append(mine).append(" 更新，可能装反了。）");
            }
            return sb.toString();
        } catch (Throwable t) {
            LOGGER.debug("[gtm_jei_startup_fix] 扫描 mods 文件夹失败（跳过重复 jar 提示）：{}", t.toString());
            return null;
        }
    }

    /** 正在运行的这份的 r 号（"1.0.0-r36" → 36；拿不到返回 -1）。 */
    private static int currentRNumber() {
        try {
            return rNumberOf(FixReport.modVersion(GtmJeiStartupFix.MOD_ID).toLowerCase(Locale.ROOT));
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int rNumberOf(String lowerName) {
        Matcher m = JAR_TAG.matcher(lowerName);
        int last = -1;
        while (m.find()) {
            try {
                last = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                // 文件名里的 r 号读不出就当没有
            }
        }
        return last;
    }

    /** 供 /gtmfix status 用：把重复 jar 提示与自检一并讲清楚时用的短版本文案。 */
    static String selfVersionLine() {
        return "本模组版本：" + FixReport.modVersion(GtmJeiStartupFix.MOD_ID)
                + "（与 jar 文件名一致；日志头部、现场报告、/gtmfix status 都会出现这一行）";
    }
}
