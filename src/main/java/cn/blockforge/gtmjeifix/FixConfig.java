package cn.blockforge.gtmjeifix;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;

/**
 * r32 新增：本模组的<b>配置文件</b>（游戏根目录 {@code config/gtm_jei_startup_fix.toml}），
 * 里面就管玩家提的两件事，注释全部<b>中英双语</b>：
 *
 * <ol>
 * <li><b>{@code messages.joinChatReminder}</b> —— 每次<b>进入游戏（世界/服务器）时聊天栏弹出的那几行
 * 提醒消息</b>要不要发。开关只影响聊天栏，不影响写日志与 {@code gtm_jei_fix_report.txt}
 * （现场报告照旧，只是不再往聊天里打）。</li>
 * <li><b>{@code logs.startupLogKeep}</b> —— {@code gtm_jei_logs/} 里<b>保留几份启动日志</b>。
 * 例：填 40 就只留最近 40 份，更早的由本模组自己删掉；填 <b>-1 表示一份都不删</b>
 * （想留多少留多少）。</li>
 * </ol>
 *
 * <p><b>用哪套配置系统</b>：NeoForge 自带的 {@link ModConfigSpec}（不是自己造轮子解析文本）。
 * 好处：① 文件由游戏<b>自动生成</b>，第一次装完进一次游戏就能在 {@code config} 里看到它，
 * 不需要手动下载；② 每个选项上方的注释由代码里的 {@code comment(...)} 生成，中英两行都写全；
 * ③ 填错类型/超出范围时游戏会<b>自动改回合法值</b>并在文件里留一行说明，不会因此崩；
 * ④ 改完保存即生效（下面那条 Loading/Reloading 挂钩），不用重装模组。
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
 * <li>所有取值一律走本类的 {@link #joinChatReminder()} / {@link #startupLogKeep()}，
 *     未加载时返回默认值，绝不抛错；</li>
 * <li>旧日志的清理<b>不在开日志时做</b>，而是等配置到手再做
 *     （{@link ModConfigEvent.Loading} / {@link ModConfigEvent.Reloading} 里回调
 *     {@link StartupLog#onConfigReady(String)}）——否则玩家把份数从 20 调到 40，
 *     启动瞬间就会先按默认 20 删掉一批，越改越少；</li>
 * <li>再留一条兜底：万一哪天配置事件不来了，{@link ClientIconFixupPolling} 每 10 tick
 *     会调一次 {@link StartupLog#ensureRetentionApplied()}（已做过就立刻返回）。</li>
 * </ul>
 */
public final class FixConfig {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 配置文件名（在 {@code config} 文件夹里）。写进日志/报告指路用。 */
    public static final String FILE_NAME = "gtm_jei_startup_fix.toml";

    /** 进世界聊天提醒的默认值：开。 */
    public static final boolean DEFAULT_CHAT_REMINDER = true;
    /** 启动日志保留份数的默认值：与 r16～r31 一直写死的 20 份保持一致，装了这版行为不变。 */
    public static final int DEFAULT_LOG_KEEP = 20;
    /** -1 = 永不删除（本模组唯一承认的「不删」写法）。 */
    public static final int LOG_KEEP_UNLIMITED = -1;
    /** 上限只是防手滑（填几万也没意义），并不会限制「全部保留」——那用 -1。 */
    private static final int LOG_KEEP_MAX = 10000;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue JOIN_CHAT_REMINDER;
    private static final ModConfigSpec.IntValue STARTUP_LOG_KEEP;

    static {
        // ---------- 第一组：进游戏时的消息提醒 ----------
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
                        "改完保存即可生效（重新进一次世界就会按新设置走）。",
                        "Save the file and it applies - the next world you join uses the new value.")
                .define("joinChatReminder", DEFAULT_CHAT_REMINDER);
        BUILDER.pop();

        // ---------- 第二组：启动日志保留份数 ----------
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
        } catch (RuntimeException | LinkageError e) {
            // 注册失败也绝不能拖垮模组：所有取值都有默认值兜底，日志只是不再自动清理
            LOGGER.warn("[gtm_jei_startup_fix] 配置文件注册失败（不影响游戏，按默认值走）：{}", e.toString());
        }
        if (!hooked) {
            hooked = true;
            try {
                modBus.addListener((ModConfigEvent.Loading event) ->
                        StartupLog.onConfigReady("配置加载完成"));
                modBus.addListener((ModConfigEvent.Reloading event) ->
                        StartupLog.onConfigReady("配置已重新读取"));
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("[gtm_jei_startup_fix] 配置事件挂钩失败（不影响游戏，"
                        + "日志份数改完需要重启一次才生效）：{}", e.toString());
            }
        }
    }

    /** 配置文件完整路径的提示文字（报告/日志里指路用）。 */
    public static String filePathHint() {
        return "config/" + FILE_NAME;
    }

    // ---------------- 取值（全部带兜底，任何时刻调用都不会抛） ----------------

    /** 配置是否已经读进来了（未读到时一切按默认值走）。 */
    public static boolean isLoaded() {
        try {
            return SPEC.isLoaded();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 进世界要不要发聊天提醒。默认：要。 */
    public static boolean joinChatReminder() {
        try {
            return isLoaded() ? JOIN_CHAT_REMINDER.get() : DEFAULT_CHAT_REMINDER;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("[gtm_jei_startup_fix] 读 joinChatReminder 失败，按默认值 {} 走：{}",
                    DEFAULT_CHAT_REMINDER, e.toString());
            return DEFAULT_CHAT_REMINDER;
        }
    }

    /** 启动日志保留份数。默认 20；-1 = 不删。 */
    public static int startupLogKeep() {
        try {
            return isLoaded() ? STARTUP_LOG_KEEP.get() : DEFAULT_LOG_KEEP;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("[gtm_jei_startup_fix] 读 startupLogKeep 失败，按默认值 {} 走：{}",
                    DEFAULT_LOG_KEEP, e.toString());
            return DEFAULT_LOG_KEEP;
        }
    }

    /** 当前这份配置的人话摘要，供日志/报告/聊天用。 */
    public static String describe() {
        int keep = startupLogKeep();
        return "进世界聊天提醒=" + (joinChatReminder() ? "开" : "关")
                + "，启动日志保留=" + (keep < 0 ? "-1（一份都不删）" : keep + " 份")
                + "（来自 " + filePathHint() + (isLoaded() ? "" : "，还没读到，暂按默认值") + "）";
    }
}
