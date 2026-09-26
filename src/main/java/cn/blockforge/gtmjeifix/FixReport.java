package cn.blockforge.gtmjeifix;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * r9 新增：把「这个模组到底干没干活、干到哪一步」写进<b>游戏根目录的一张小报告</b>
 * （{@code gtm_jei_fix_report.txt}），让玩家不用在几万行日志里搜关键字。
 *
 * <p>为什么需要它：r8 的全部诊断都走 {@code LOGGER.info}，而新手（尤其手机启动器）
 * 很难从 {@code latest.log} 里把带 {@code [gtm_jei_startup_fix]} 的行捞出来；
 * 用户只会说「没效果」，我们看不到现场。现在每个里程碑 {@link #note(String)} 一行，
 * 并且<b>每写一行就立刻落盘</b>——就算后面某步炸了没走到，已发生的事实也都在文件里。
 *
 * <p>全程只降级不抛错：游戏目录不可写（只读挂载等）就悄悄停写文件，
 * 聊天栏摘要与日志不受影响。
 *
 * <p><b>r16 起：每行同时写两份</b>——① 本文件（永远是最新现场，会被下次启动重写）；
 * ② 游戏根目录 {@link #LOG_DIR_NAME} 文件夹里<b>本次启动专属</b>的一份
 * （文件名带启动时刻、每行带时间戳，见 {@link StartupLog}）。
 * 于是「上一次能进、这一次进不去」这种问题也有据可查，旧现场不会被顶掉。
 *
 * <p><b>r34 起：这份文件的存在本身由 {@code report.enabled} 决定</b>（{@code FixConfig}）。
 * NeoForge 在模组构造之后才读配置，所以「配置读到之前」进来的行先攒在内存（本类的
 * {@link #LINES}），读到那一刻才按开关落盘或从此不再落盘——
 * 保证玩家说「不写」时，本次启动这个文件一个字节都不会变。
 * 万一配置事件迟迟不来（异常环境），5 秒后按默认值（写）放行，诊断能力不打折。
 * 关掉后<b>已存在的旧文件保持原样</b>，本模组不去删别人的历史。
 */
public final class FixReport {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 文件名（放游戏根目录，和 mods 文件夹同级）。 */
    public static final String FILE_NAME = "gtm_jei_fix_report.txt";

    /**
     * r16：每次启动一份独立日志所在的文件夹（游戏根目录下，与 mods 同级）。
     * 根目录那份 {@value #FILE_NAME} 仍是「最新现场」，会被下次启动重写；
     * 这个文件夹里的每一份都代表一次启动，永不覆盖。
     */
    public static final String LOG_DIR_NAME = StartupLog.DIR_NAME;

    /** 攒着的全部行：内存里始终完整（聊天摘要、{@code /gtmfix} 都读它）。 */
    private static final List<String> LINES = new ArrayList<>();
    /** 落盘失败过一次就不再重试（不影响 note 继续累积，供聊天摘要用）。 */
    private static boolean fileDisabled = false;
    /** r34：配置已读到且 {@code report.enabled=true}（或兜底时限已过）才允许落盘。 */
    private static boolean live = false;
    /** r34：第一次想落盘但配置还没读到的时刻（5 秒兜底时限的起点）。 */
    private static long firstWantMillis = 0;
    /** 与 {@link StartupLog#DECIDE_FALLBACK_MS} 同义；那边是包私有，这里自己写一个。 */
    private static final long LIVE_FALLBACK_MS = 5_000L;

    private FixReport() {}

    /** 模组构造时调用：写报告头（各家模组的实际版本一目了然）。 */
    public static void start(String modVersion) {
        List<String> head = new ArrayList<>();
        head.add("=== " + GtmJeiStartupFix.MOD_ID + " " + modVersion + " 现场报告 ===");
        head.add("生成时间：" + new Date());
        head.add("本模组 " + modVersion
                + " ｜ NeoForge " + modVersion("neoforge")
                + " ｜ Minecraft " + modVersion("minecraft"));
        head.add("JEI " + modVersion("jei")
                + " ｜ 格雷科技(gtceu) " + modVersion("gtceu"));
        head.add("—— 反馈问题时把本文件内容整个发出来即可定位 ——");
        head.add("----------------------------------------");
        synchronized (LINES) {
            LINES.clear();
            LINES.addAll(head);
        }
        // r16：同一次启动再写一份「按启动留档」的日志（每行都带时间戳，只追加不覆盖）
        // r34：这两处都不再当场落盘——配置读到后按 report.enabled / logs.writeStartupLog 决定。
        StartupLog.open(head);
        flush();
        note("[启动日志] 本次这一份：" + StartupLog.dirHint()
                + "　（游戏根目录；上一次启动的还留在 " + LOG_DIR_NAME + " 里，没被顶掉）");
    }

    /** 指路用：本次启动那份日志的相对路径（{@code gtm_jei_logs/startup-xxxx.log}）。 */
    public static String logHint() {
        return StartupLog.dirHint();
    }

    /** 本次启动那份日志的绝对路径（聊天栏/日志里给新手直接抄）。 */
    public static String logPath() {
        return StartupLog.absolutePath();
    }

    /** r34：/gtmfix 用——根目录那份报告的绝对路径（拿不到游戏根目录时 "(未知)"）。 */
    public static String reportPath() {
        Path f = reportFile();
        try {
            return f == null ? "(未知)" : f.toAbsolutePath().normalize().toString();
        } catch (Throwable e) {
            return "(未知)";
        }
    }

    /** r34：/gtmfix 用——那份报告文件现在在不在（{@code report.enabled=false} 时本次不会有新的）。 */
    public static String reportStateLine() {
        Path f = reportFile();
        if (f == null) return "(拿不到游戏根目录)";
        try {
            if (!Files.exists(f)) {
                return "本次没写（文件还不存在或 " + (live ? "没建" : "report.enabled=关/还没读到配置") + "）";
            }
            long size = Files.size(f);
            return "存在，" + size + " 字节" + (live ? "（本次一直在更新）" : "（本次的旧内容，现在没在更新）");
        } catch (Throwable e) {
            return "存在（大小读不了）";
        }
    }

    /** 记一行进度并尝试立刻落盘（r34：配置读到前、或 {@code report.enabled=false} 时只留内存）。
     *  任何调用方都不需要自己 try/catch。 */
    public static void note(String line) {
        try {
            synchronized (LINES) {
                LINES.add(line);
            }
            flush();
            StartupLog.line(line);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("[gtm_jei_startup_fix] 写现场报告一行失败（不影响游戏）：{}", e.toString());
        }
    }

    /** 已累积的全部行（聊天摘要用）。 */
    public static String text() {
        synchronized (LINES) {
            return String.join("\n", LINES);
        }
    }

    /**
     * 本模组的短版本标记，例如 {@code 1.0.0-r12 -> r12}。
     * 从模组元数据（mods.toml 里 Gradle 戳进去的版本）运行时读取，
     * 日志/报告/聊天里显示的版本永远和 jar 文件名一致，不再硬编码。
     */
    public static String selfTag() {
        String v = modVersion(GtmJeiStartupFix.MOD_ID);
        int dashR = v.indexOf("-r");
        return dashR >= 0 ? v.substring(dashR + 1) : v;
    }

    /** 取某个 mod 的实际安装版本；没装/拿不到就返回「(没装)」。 */
    public static String modVersion(String modId) {
        try {
            return ModList.get().getModContainerById(modId)
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("(没装)");
        } catch (RuntimeException | LinkageError e) {
            return "(未知)";
        }
    }

    private static void flush() {
        if (fileDisabled) return;
        if (!writesAllowed()) return;   // r34：配置没读到 / 开关说别写 —— 一个字都不落盘，行留在 LINES 里
        try {
            Path file = reportFile();
            if (file == null) {
                fileDisabled = true;   // 拿不到游戏根目录（r32 实测：FMLPaths 可能直接给 null）
                return;
            }
            Files.write(file, text().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            fileDisabled = true;
            LOGGER.debug("[gtm_jei_startup_fix] 现场报告写不进游戏目录（{}），"
                    + "改为只保留聊天与日志输出：{}", String.valueOf(pathOrNull()), t.toString());
        }
    }

    /**
     * r34：现在许不许往根目录写这份报告。判定顺序：
     * ① 配置已读到 → 就按 {@code report.enabled}（中途从关拧回开也在这里翻回来）；
     * ② 配置还没读到 → 先不许（这就是「关了开关就一个字节都不写」能成立的原因——
     *    构造期的那些行都攒在内存，等这一刻才定）；
     * ③ 但最多等 {@link #LIVE_FALLBACK_MS}：异常环境里配置事件一直不来时按默认值（写）放行，
     *    保证最坏情况也保有 r32 的诊断能力。
     */
    private static boolean writesAllowed() {
        try {
            if (FixConfig.isLoaded()) {
                live = FixConfig.reportEnabled();
                return live;
            }
            long now = System.currentTimeMillis();
            if (firstWantMillis == 0) {
                firstWantMillis = now;
            } else if (now - firstWantMillis >= LIVE_FALLBACK_MS) {
                live = FixConfig.reportEnabled();   // 默认 true；真读到了也会在下面第一路纠正
                return live;
            }
            return false;
        } catch (Throwable e) {
            return live;   // 判定本身出错就维持上一次结论，绝不因此抛异常
        }
    }

    /** 根目录报告文件的完整路径；游戏根目录拿不到时返回 {@code null}（调用方负责降级）。 */
    private static Path reportFile() {
        try {
            Path gameDir = FMLPaths.GAMEDIR.get();
            return gameDir == null ? null : gameDir.resolve(FILE_NAME);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static String pathOrNull() {
        Path f = reportFile();
        return f == null ? "(未知)" : f.toString();
    }
}
