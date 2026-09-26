package cn.blockforge.gtmjeifix;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;

/**
 * 本模组的<b>配置文件</b>（游戏根目录 {@code config/gtm_jei_startup_fix.toml}），注释全部<b>中英双语</b>。
 *
 * <p>r32 起管两件事，r34 起一共管四组：
 *
 * <ol>
 * <li><b>{@code messages.joinChatReminder}</b> —— 每次<b>进入游戏（世界/服务器）时聊天栏弹出的那几行
 * 提醒消息</b>要不要发。</li>
 * <li><b>{@code logs.startupLogKeep}</b> —— {@code gtm_jei_logs/} 里<b>保留几份启动日志</b>。
 * 例：填 40 就只留最近 40 份；填 <b>-1 表示一份都不删</b>。</li>
 * <li><b>r34 新增 {@code fixes.*}</b> —— 五个修复<b>各自的独立开关</b>（启动崩溃修复 / 格雷分类补注册 /
 * RecipeSlot 字段补丁 / 多方块 3D 预览校正 / 弹出菜单不出屏）。将来 GTM 或 ModularUI 官方把某个 bug
 * 修好了，就只关掉对应那一条，其余照旧。默认全部 true，不配置时行为与 r32 一字不差。</li>
 * <li><b>r34 新增 {@code logs.writeStartupLog} 与 {@code report.enabled}</b> —— 两个<b>写文件总开关</b>。
 * 关掉后模组照样干活，只是不往游戏目录里写那两种文件（整合包发布给别人时用得上）。</li>
 * </ol>
 *
 * <p><b>用哪套配置系统</b>：NeoForge 自带的 {@link ModConfigSpec}（不是自己造轮子解析文本）。
 * 好处：① 文件由游戏<b>自动生成</b>；② 注释由代码里的 {@code comment(...)} 生成，中英两行都写全；
 * ③ 填错类型/超出范围时游戏会<b>自动改回合法值</b>并在文件里留一行说明，不会因此崩；
 * ④ 改完保存即生效（下面那条时序坑 + NeoForge 自带的文件监视）。
 *
 * <p><b>为什么是 COMMON 而不是 CLIENT</b>：日志文件夹在服务端也会有，聊天提示只在客户端有；
 * COMMON 一份文件两边共用，玩家只需要记一个路径。
 *
 * <p><b>生效时机的坑（r32 现场核实过，别改回去）</b>：NeoForge 21.1 的加载顺序是
 * 「建模组实例（本构造函数）→ 加载配置 → 注册 → CommonSetup」，也就是
 * <b>模组构造函数里配置还没读</b>，这时调 {@code ConfigValue#get()} 会直接抛
 * {@code IllegalStateException: Cannot get config value before config is loaded.}
 * （用真 jar 跑过一遍确认）。所以：
 * <ul>
 * <li>所有取值一律走本类的 {@code xxxEnabled()} / {@link #startupLogKeep()}，
 *     未加载时返回默认值，绝不抛错；</li>
 * <li>旧日志的清理、日志/报告文件的建立<b>都不在构造时做</b>，而是等配置到手再做
 *     （{@link ModConfigEvent.Loading} / {@link ModConfigEvent.Reloading} 里回调
 *     {@link #onConfigApplied(String)}）——否则玩家把份数从 20 调到 40，
 *     启动瞬间就会先按默认 20 删掉一批；写了 {@code writeStartupLog=false} 也会被先建一次文件；</li>
 * <li>再留一条兜底：万一哪天配置事件不来了，{@link ClientIconFixupPolling} 每 10 tick
 *     会调一次 {@link StartupLog#ensureRetentionApplied()}（超过 5 秒还没读到配置就按默认值决定，
 *     保证诊断文件在最坏情况下仍然会写）。</li>
 * </ul>
 *
 * <p><b>改完文件多久生效</b>：NeoForge（FML loader 4.x，21.1 全系列）自带配置文件监视
 * （{@code ConfigTracker} 注册了 {@code ConfigWatcher}），保存后约一秒就会自动重读并广播
 * {@code ModConfigEvent.Reloading}，本模组的所有挂钩跟着重跑——所以 {@code /gtmfix reload}
 * 主要是「立刻强制重读一次＋把结果念给你听」，它用反射走 FML 内部同一条重读路径，
 * 反射不到就退回提示「等待自动监视」。全程反射失败只降级，绝不抛。
 *
 * <p><b>开关的生效边界（写进注释与状态命令，省得玩家猜）</b>：
 * ① {@code enableRecipeSlotCompat} 作用在「JEI 的 RecipeSlot 类第一次被加载转换」那一刻；
 * 那个类只会被转换一次，所以这一条实际以<b>游戏启动后第一次打开配方页之前</b>的取值为准（下次重启彻底重算）；
 * ② {@code enableStartupCrashFix}：GTM 初始化配方的时机可能早于配置读取，
 * 那种情况下按默认值（开）执行——真要彻底关掉它，请把 jar 移出 mods 文件夹。
 */
public final class FixConfig {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 配置文件名（在 {@code config} 文件夹里）。写进日志/报告指路用。 */
    public static final String FILE_NAME = "gtm_jei_startup_fix.toml";

    // ---------------- 默认值 ----------------
    /** 进世界聊天提醒的默认值：开。 */
    public static final boolean DEFAULT_CHAT_REMINDER = true;
    /** 启动日志保留份数的默认值：与 r16～r31 一直写死的 20 份保持一致，装了这版行为不变。 */
    public static final int DEFAULT_LOG_KEEP = 20;
    /** -1 = 永不删除（本模组唯一承认的「不删」写法）。 */
    public static final int LOG_KEEP_UNLIMITED = -1;
    /** 上限只是防手滑（填几万也没意义），并不会限制「全部保留」——那用 -1。 */
    private static final int LOG_KEEP_MAX = 10000;
    /** r34：五个修复开关与两个写文件开关的默认值统一为「开」，不配置时行为与 r32 完全一致。 */
    public static final boolean DEFAULT_ALL_ON = true;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue JOIN_CHAT_REMINDER;
    private static final ModConfigSpec.IntValue STARTUP_LOG_KEEP;
    private static final ModConfigSpec.BooleanValue WRITE_STARTUP_LOG;

    private static final ModConfigSpec.BooleanValue FIX_CRASH;
    private static final ModConfigSpec.BooleanValue FIX_CATEGORY_BACKFILL;
    private static final ModConfigSpec.BooleanValue FIX_RECIPE_SLOT_COMPAT;
    private static final ModConfigSpec.BooleanValue FIX_MULTIBLOCK_OFFSET;
    private static final ModConfigSpec.BooleanValue FIX_MENU_KEEP_ON_SCREEN;

    private static final ModConfigSpec.BooleanValue REPORT_ENABLED;

    static {
        // ---------- 第一组：修复开关（r34 新增，每个修复各自一条命） ----------
        BUILDER.push("fixes");
        BUILDER.comment(
                "修复开关 / Fix switches",
                "这个模组一共在修 5 个 bug，这里每个各给一个开关。",
                "This mod fixes 5 separate bugs; each one gets its own switch here.",
                "",
                "全部保持 true（默认）= 行为与以前所有版本一字不差。",
                "只有当 GTM / ModularUI / JEI 官方修好了对应的问题，才需要关掉那一条；",
                "关错了不会崩游戏——最多是那个老 bug 再次出现，改回 true 就恢复。",
                "Leave all true (default) for exactly the same behaviour as every previous version.",
                "Turn one off only when the official mod has fixed that particular bug; worst case the",
                "old symptom comes back and flipping the switch back to true restores the fix.");
        FIX_CRASH = BUILDER
                .comment(
                        "修「游戏启动崩溃（Cannot invoke IJeiRuntime.getJeiHelpers() because ... is null）」",
                        "Fixes the startup crash caused by GT building category icons before JEI is ready",
                        "JEI 还没就绪时先给分类一个会自动恢复的占位图标，而不是让 GTM 空指针。",
                        "Gives GT categories a self-healing placeholder icon until JEI is ready.",
                        "",
                        "⚠ 关掉它的后果：如果你的 GTM 快照自己还没修好这个空指针，老崩溃会原样回来。",
                        "只有确认你手上的 GTM 版本已经不崩了，再关它；不确定就保持 true。",
                        "WARNING: if your GTM build still has that null pointer, turning this off brings the",
                        "crash back. Only disable it after you have confirmed the current GTM no longer crashes.")
                .define("enableStartupCrashFix", DEFAULT_ALL_ON);
        FIX_CATEGORY_BACKFILL = BUILDER
                .comment(
                        "修「JEI 里看不到格雷的研磨/分离等配方分类」 / Fixes missing GT recipe categories in JEI",
                        "GTM 某些快照把自己注册分类的那行注释掉了，本模组在出口处补上。",
                        "Some GTM snapshots commented out their own category registration; this mod re-adds them.",
                        "官方修好后可以把这条改成 false。",
                        "Set to false once GTM registers its categories again on its own.")
                .define("enableCategoryBackfill", DEFAULT_ALL_ON);
        FIX_RECIPE_SLOT_COMPAT = BUILDER
                .comment(
                        "修「一打开格雷配方页就崩（RecipeSlot 缺字段）」 / Fixes crash when opening a GT recipe page (missing RecipeSlot fields)",
                        "ModularUI 按老版 JEI 的字段名写数据，新版 JEI 把字段删了，本模组补回去。",
                        "ModularUI writes to RecipeSlot fields that newer JEI removed; this mod adds them back.",
                        "注意：这一条以「游戏启动后第一次打开配方页之前」的取值为准，",
                        "那个类只会被转换一次；开关改动下次重启才彻底重算。",
                        "Note: the value in effect when JEI's RecipeSlot class is first transformed wins",
                        "(that happens once, the first time a recipe page opens); restart to fully re-evaluate.")
                .define("enableRecipeSlotCompat", DEFAULT_ALL_ON);
        FIX_MULTIBLOCK_OFFSET = BUILDER
                .comment(
                        "修「JEI 里格雷的多方块 3D 结构图偏左上角」 / Fixes the offset 3D multiblock preview inside JEI",
                        "把 JEI 配方页在屏幕上的真实原点补进那张 3D 图的 OpenGL 视口。",
                        "Adds the recipe page's real screen origin into the 3D preview's GL viewport.",
                        "只影响 JEI 里的那张预览图；机器界面里的同一个预览本来就不受影响。",
                        "Only the preview embedded in JEI is affected; the same widget in a real machine GUI never was.")
                .define("enableMultiblockEmbedOffset", DEFAULT_ALL_ON);
        FIX_MENU_KEEP_ON_SCREEN = BUILDER
                .comment(
                        "修「控制器里越靠下的缺失方块，『可使用的类型』菜单越往下、甚至开到屏幕外」",
                        "Keeps the missing-blocks context menu from falling off-screen",
                        "在 ModularUI 排版收尾时把菜单摆回它那一行在屏幕上的真实位置（含滚动位移）。",
                        "Re-places the popup onto the real on-screen position of its row (scroll offset included).",
                        "任何一环拿不准它都完全不动手，所以关掉它最多是恢复老 bug，不会掰坏界面。",
                        "It never touches anything when unsure, so turning it off only restores the old symptom.")
                .define("enableMenuKeepOnScreen", DEFAULT_ALL_ON);
        BUILDER.pop();

        // ---------- 第二组：进游戏时的消息提醒 ----------
        BUILDER.push("messages");
        BUILDER.comment(
                "提示消息 / Reminder messages",
                "本模组在聊天栏里主动打给你的那几行字。",
                "The lines this mod prints into chat by itself.");
        JOIN_CHAT_REMINDER = BUILDER
                .comment(
                        "每次进入游戏（单机世界、服务器）后，要不要在聊天栏弹出本模组的提醒消息。",
                        "Whether to show this mod's reminder messages in chat after you join a world or server.",
                        "",
                        "true  = 弹提醒（默认，方便确认模组有没有干活、卡在哪一步）。",
                        "false = 完全不弹，聊天栏干干净净；现场报告和逐行日志照样写，不影响排查问题。",
                        "true  = show them (default; handy to confirm the fix is doing its job).",
                        "false = stay silent; the report file and the per-startup log are still written.",
                        "",
                        "改完保存即可生效（重新进一次世界就会按新设置走；也可以游戏里敲 /gtmfix reload）。",
                        "Save the file and it applies - the next world you join uses the new value,",
                        "or run /gtmfix reload in game.")
                .define("joinChatReminder", DEFAULT_CHAT_REMINDER);
        BUILDER.pop();

        // ---------- 第三组：启动日志 ----------
        BUILDER.push("logs");
        BUILDER.comment(
                "日志 / Logs",
                "游戏根目录 gtm_jei_logs 文件夹里「每次启动一份」的日志。",
                "Per-launch log files inside the gtm_jei_logs folder of your game directory.");
        STARTUP_LOG_KEEP = BUILDER
                .comment(
                        "保留最近多少份启动日志。填 40 就只留最近 40 份，超过的旧文件由本模组自动删掉。",
                        "How many startup log files to keep. 40 = keep the 40 newest and delete older ones automatically.",
                        "",
                        "-1 = 一份都不删，全部保留（想留多少留多少，代价是文件夹会一直变大）。",
                        "0  = 只保留本次启动这一份。",
                        "正整数 N = 保留最近 N 份（本次这份一定在内，绝不会被自己删掉）。",
                        "-1 = never delete anything, keep every log file (the folder just keeps growing).",
                        "0  = keep only the log of this launch.",
                        "Any positive N = keep the N newest files; the file of this launch is always counted as kept.",
                        "",
                        "只删本模组自己生成的 gtm_jei_logs/startup-*.log，别的文件一个字节都不碰。",
                        "Only gtm_jei_logs/startup-*.log files created by this mod are ever deleted; nothing else is touched.",
                        "",
                        "改完保存后：本模组会立刻按新份数再清一次（不用重启游戏）。",
                        "After you change this, the mod prunes the folder again right away - no restart needed.")
                .defineInRange("startupLogKeep", DEFAULT_LOG_KEEP, LOG_KEEP_UNLIMITED, LOG_KEEP_MAX);
        WRITE_STARTUP_LOG = BUILDER
                .comment(
                        "要不要往游戏目录写「每次启动一份」的逐行日志文件（r34 新增）。",
                        "Whether this mod may create the per-startup log files in your game directory (new in r34).",
                        "",
                        "false = 本模组从此一个日志文件都不建、也不清理旧的（整合包发布给别人时用）。",
                        "true  = 正常写（默认）。",
                        "false = no gtm_jei_logs files are ever created by this mod (handy for modpacks).",
                        "true  = normal behaviour (default).",
                        "",
                        "关掉不影响修复本身，只是排查问题时少了这份文件；根目录那份小报告由 report.enabled 管。",
                        "Turning it off changes nothing about the fixes themselves; the small report file",
                        "next to it is controlled by report.enabled.")
                .define("writeStartupLog", DEFAULT_ALL_ON);
        BUILDER.pop();

        // ---------- 第四组：现场报告 ----------
        BUILDER.push("report");
        BUILDER.comment(
                "现场报告 / On-site report",
                "游戏根目录那份小报告文件 gtm_jei_fix_report.txt（每次启动重写为最新现场）。",
                "The small gtm_jei_fix_report.txt in your game directory (rewritten with the latest state).");
        REPORT_ENABLED = BUILDER
                .comment(
                        "要不要往游戏目录写那份现场报告（r34 新增）。",
                        "Whether this mod may write the report file into your game directory (new in r34).",
                        "",
                        "false = 不写。本次启动一个字都不会落进那个文件（配置读到前不写，读到后按这里办）；",
                        "已经存在的旧文件保持原样，本模组不会去删它。",
                        "true  = 正常写（默认）。反馈问题时最有用就是这份文件。",
                        "false = the file gets no new content this launch at all (nothing is written before",
                        "the config is read, then this switch decides); an existing old file is left untouched.",
                        "true  = normal behaviour (default); this file is the single most useful thing to send us.",
                        "",
                        "关掉后模组照样干活；聊天提醒（messages.joinChatReminder）不受影响。",
                        "The fixes keep working either way; the chat reminder is a separate switch.")
                .define("enabled", DEFAULT_ALL_ON);
        BUILDER.pop();
    }

    /** 交给 NeoForge 注册的那份 spec（含上面所有注释）。 */
    public static final ModConfigSpec SPEC = BUILDER.build();

    /** 是否已经挂过配置事件（防重复注册）。 */
    private static boolean hooked = false;

    private FixConfig() {}

    /**
     * 在模组构造函数<b>最前面</b>调用：注册配置文件 + 挂上「配置读到了 / 配置被改了」两个事件。
     *
     * @param modBus 模组自己的事件总线（{@code ModConfigEvent} 是 IModBusEvent，只在这条总线上走）
     */
    public static void register(IEventBus modBus) {
        try {
            ModLoadingContext.get().getActiveContainer()
                    .registerConfig(ModConfig.Type.COMMON, SPEC, FILE_NAME);
        } catch (Throwable e) {
            // 注册失败也绝不能拖垮模组：所有取值都有默认值兜底，日志只是不再自动清理
            LOGGER.warn("[gtm_jei_startup_fix] 配置文件注册失败（不影响游戏，按默认值走）：{}", e.toString());
        }
        if (!hooked) {
            hooked = true;
            try {
                modBus.addListener((ModConfigEvent.Loading event) -> onConfigApplied("配置加载完成"));
                modBus.addListener((ModConfigEvent.Reloading event) -> onConfigApplied("配置已重新读取"));
            } catch (Throwable e) {
                LOGGER.warn("[gtm_jei_startup_fix] 配置事件挂钩失败（不影响游戏，"
                        + "改动要重启一次才生效）：{}", e.toString());
            }
        }
    }

    /**
     * 配置到手（第一次读 / 之后每次热重载）的统一入口：
     * ① 让 {@link StartupLog} 按开关决定「这次要不要建逐行日志」并按份数清理；
     * ② 在根目录报告与本次启动日志里各记一行「本次哪几个修复开着、哪几个被关了」。
     */
    public static void onConfigApplied(String reason) {
        try {
            StartupLog.onConfigReady(reason);
        } catch (Throwable e) {
            LOGGER.debug("[gtm_jei_startup_fix] 配置到手回调（日志侧）出错（不影响游戏）：{}", e.toString());
        }
        try {
            FixReport.note("[修复开关] " + reason + "：" + fixesSummary()
                    + "；逐行启动日志=" + yesNo(writeStartupLogEnabled())
                    + "；现场报告文件=" + yesNo(reportEnabled())
                    + "（开关都在 " + filePathHint() + " 的 [fixes]/[logs]/[report] 里，注释中英双语）");
        } catch (Throwable e) {
            LOGGER.debug("[gtm_jei_startup_fix] 配置到手回调（报告侧）出错（不影响游戏）：{}", e.toString());
        }
    }

    /** 配置文件完整路径的提示文字（报告/日志里指路用）。 */
    public static String filePathHint() {
        return "config/" + FILE_NAME;
    }

    /**
     * r34：配置文件的<b>绝对路径</b>（{@code /gtmfix} 指路用）。
     * 优先问 NeoForge 的登记表（它记着真正读写的那条路径），拿不到就用游戏根目录拼一个。
     */
    public static String absoluteFilePath() {
        try {
            for (ModConfig c : ModConfigs.getModConfigs(GtmJeiStartupFix.MOD_ID)) {
                if (c != null && FILE_NAME.equals(c.getFileName()) && c.getFullPath() != null) {
                    return c.getFullPath().toAbsolutePath().normalize().toString();
                }
            }
        } catch (Throwable ignored) {
            // 登记表不可用就走下面的兜底拼接
        }
        try {
            return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve(FILE_NAME)
                    .toAbsolutePath().normalize().toString();
        } catch (Throwable e) {
            return "(拿不到绝对路径；文件在游戏根目录的 " + filePathHint() + ")";
        }
    }

    // ---------------- 取值（全部带兜底，任何时刻调用都不会抛） ----------------

    /** 配置是否已经读进来了（未读到时一切按默认值走）。 */
    public static boolean isLoaded() {
        try {
            return SPEC.isLoaded();
        } catch (Throwable e) {
            return false;
        }
    }

    /** 统一的布尔取值：配置没读到时回默认值，任何异常也回默认值。 */
    private static boolean bool(ModConfigSpec.BooleanValue value, boolean def) {
        try {
            return isLoaded() ? value.get() : def;
        } catch (Throwable e) {
            LOGGER.debug("[gtm_jei_startup_fix] 读配置项失败，按默认值 {} 走：{}", def, e.toString());
            return def;
        }
    }

    /** 进世界要不要发聊天提醒。默认：要。 */
    public static boolean joinChatReminder() {
        return bool(JOIN_CHAT_REMINDER, DEFAULT_CHAT_REMINDER);
    }

    /** 启动日志保留份数。默认 20；-1 = 不删。 */
    public static int startupLogKeep() {
        try {
            return isLoaded() ? STARTUP_LOG_KEEP.get() : DEFAULT_LOG_KEEP;
        } catch (Throwable e) {
            LOGGER.debug("[gtm_jei_startup_fix] 读 startupLogKeep 失败，按默认值 {} 走：{}",
                    DEFAULT_LOG_KEEP, e.toString());
            return DEFAULT_LOG_KEEP;
        }
    }

    /** r34：要不要在 {@code gtm_jei_logs} 里建本次启动那份逐行日志。默认要。 */
    public static boolean writeStartupLogEnabled() {
        return bool(WRITE_STARTUP_LOG, DEFAULT_ALL_ON);
    }

    /** r34：要不要写根目录那份现场报告。默认要。 */
    public static boolean reportEnabled() {
        return bool(REPORT_ENABLED, DEFAULT_ALL_ON);
    }

    /** r34：修复①——启动崩溃（JEI 未就绪时先给占位图标）。 */
    public static boolean crashFixEnabled() {
        return bool(FIX_CRASH, DEFAULT_ALL_ON);
    }

    /** r34：修复②——格雷配方分类补注册。 */
    public static boolean categoryBackfillEnabled() {
        return bool(FIX_CATEGORY_BACKFILL, DEFAULT_ALL_ON);
    }

    /** r34：修复③——ModularUI 需要的 RecipeSlot 旧字段补丁。 */
    public static boolean recipeSlotCompatEnabled() {
        return bool(FIX_RECIPE_SLOT_COMPAT, DEFAULT_ALL_ON);
    }

    /** r34：修复④——JEI 里多方块 3D 预览的位置校正。 */
    public static boolean multiblockOffsetEnabled() {
        return bool(FIX_MULTIBLOCK_OFFSET, DEFAULT_ALL_ON);
    }

    /** r34：修复⑤——弹出菜单不出屏幕。 */
    public static boolean menuKeepOnScreenEnabled() {
        return bool(FIX_MENU_KEEP_ON_SCREEN, DEFAULT_ALL_ON);
    }

    // ---------------- 人话摘要（日志 / 报告 / /gtmfix status 共用） ----------------

    private static String yesNo(boolean b) {
        return b ? "开" : "关";
    }

    /** 当前这份配置里日志/聊天相关的人话摘要（r32 起就有，给「[日志保留]」那行用）。 */
    public static String describe() {
        int keep = startupLogKeep();
        return "进世界聊天提醒=" + yesNo(joinChatReminder())
                + "，启动日志保留=" + (keep < 0 ? "-1（一份都不删）" : keep + " 份")
                + "（来自 " + filePathHint() + (isLoaded() ? "" : "，还没读到，暂按默认值") + "）";
    }

    /** 五个修复开关的一行摘要；关掉崩溃修复时附一句警告（这行会进日志、报告和状态命令）。 */
    public static String fixesSummary() {
        boolean crash = crashFixEnabled();
        return "启动崩溃修复=" + yesNo(crash)
                + (crash ? "" : "（⚠ 若你的 GTM 还没自己修好那个空指针，游戏仍会像以前一样启动崩溃，改回 true 即恢复）")
                + "，格雷分类补注册=" + yesNo(categoryBackfillEnabled())
                + "，RecipeSlot字段补丁=" + yesNo(recipeSlotCompatEnabled())
                + "，多方块预览校正=" + yesNo(multiblockOffsetEnabled())
                + "，弹出菜单不出屏=" + yesNo(menuKeepOnScreenEnabled());
    }

    /** 全部设置的一行摘要（/gtmfix 的 reload 回显用；status 命令自己按行展开）。 */
    public static String fullStatus() {
        return "配置" + (isLoaded() ? "已读到" : "还没读到（暂按默认值）")
                + "｜修复：" + fixesSummary()
                + "｜" + describe()
                + "｜写逐行日志=" + yesNo(writeStartupLogEnabled())
                + "，写现场报告=" + yesNo(reportEnabled());
    }

    // ---------------- /gtmfix reload 的「强制重读」 ----------------

    /**
     * 尽力把 {@code config/}{@link #FILE_NAME} 立刻重读一遍：反射走 NeoForge（FML）自己那条
     * 「文件变了→重新加载→广播 {@code ModConfigEvent.Reloading}」的路
     * （{@code ConfigTracker.loadConfig}，见 {@code ConfigWatcher.run} 的字节码）。
     * 反射不到（版本差异）也绝不抛，只回一句人话说明——反正 FML 自带文件监视，
     * 保存后约一秒新值通常早就生效了。
     *
     * @return 给人看的结果行（聊天栏原样打印）
     */
    public static String reloadNow() {
        try {
            List<ModConfig> cfgs = ModConfigs.getModConfigs(GtmJeiStartupFix.MOD_ID);
            ModConfig ours = null;
            for (ModConfig c : cfgs) {
                if (c != null && FILE_NAME.equals(c.getFileName())) {
                    ours = c;
                    break;
                }
            }
            if (ours == null) {
                return "没在 NeoForge 登记表里找到 " + filePathHint()
                        + "（可能注册配置那步失败了）；本模组所有取值都按默认值走。";
            }
            Class<?> tracker = Class.forName("net.neoforged.fml.config.ConfigTracker");
            Method loadConfig = tracker.getDeclaredMethod("loadConfig",
                    ModConfig.class, java.nio.file.Path.class, Function.class);
            loadConfig.setAccessible(true);
            Constructor<ModConfigEvent.Reloading> evtCtor = asSubclassCtor();
            Field lockField = ModConfig.class.getDeclaredField("lock");
            lockField.setAccessible(true);
            Lock lock = (Lock) lockField.get(ours);
            final Constructor<ModConfigEvent.Reloading> ctor = evtCtor;
            Function<ModConfig, ModConfigEvent> factory = mc -> {
                try {
                    return ctor.newInstance(mc);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            };
            lock.lock();
            try {
                loadConfig.invoke(null, ours, ours.getFullPath(), factory);
            } finally {
                lock.unlock();
            }
            return "已强制重读 " + filePathHint() + "，本模组的挂钩也已按新值重新生效。";
        } catch (Throwable t) {
            LOGGER.debug("[gtm_jei_startup_fix] 强制重读配置失败（不影响游戏）：{}", t.toString());
            return "这个 NeoForge 版本没给到强制重读的入口（" + t.getClass().getSimpleName() + "），"
                    + "不影响使用：NeoForge 自己会盯着配置文件，你保存后约一秒新值就已生效。";
        }
    }

    @SuppressWarnings("unchecked")
    private static Constructor<ModConfigEvent.Reloading> asSubclassCtor() throws NoSuchMethodException {
        return ModConfigEvent.Reloading.class.getDeclaredConstructor(ModConfig.class);
    }
}
