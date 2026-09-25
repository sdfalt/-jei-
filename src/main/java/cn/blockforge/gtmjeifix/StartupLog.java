package cn.blockforge.gtmjeifix;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * r16 新增：<b>每次启动留一份独立的启动日志</b>，放在游戏根目录的
 * {@code gtm_jei_logs} 文件夹里（与 {@code mods} 文件夹同级）。
 *
 * <p><b>为什么需要它</b>：{@link FixReport} 那份 {@code gtm_jei_fix_report.txt}
 * 每次启动都会被<b>整份重写</b>——玩家想对比「上一次能进、这一次进不去」，
 * 或者一次启动崩在半路、第二次启动又把它盖掉了，现场就永远拿不到了。
 * 本类改成<b>只追加、不覆盖</b>：一次启动一个文件，文件名带启动时刻
 * （例：{@code gtm_jei_logs/startup-20260924-162009.log}），
 * 老文件全部原样留着（只保留最近 20 份，防止积几百个）。
 *
 * <p><b>与根目录那份的关系</b>：内容完全一致——{@link FixReport#note(String)}
 * 的每行同时写两处。根目录那份适合「随手发最新现场」，
 * 这个文件夹适合「翻上一次/上上次启动到底发生了什么」。
 *
 * <p><b>写盘策略</b>：每来一行就立刻 flush，所以游戏崩了、被强杀了、
 * 手机 launcher 直接关掉了，已经发生的那几行也都还在文件里。
 * 全程只降级不抛错：目录不可写（只读挂载、权限问题）就安静地不写，
 * 根目录那份报告与聊天摘要照常。
 */
final class StartupLog {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 文件夹名（游戏根目录下，与 {@code mods}、{@code logs} 同级）。 */
    static final String DIR_NAME = "gtm_jei_logs";

    /** 最多留几份。超出时删最旧的，只动本模组自己创建的文件名。 */
    private static final int KEEP = 20;

    private static final String PREFIX = "startup-";
    private static final String SUFFIX = ".log";

    /** 文件名里的启动时刻。 */
    private static final DateTimeFormatter NAME_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** 每行前面的时间戳，用来看「卡在哪一步、那一步之间隔了多久」。 */
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Object LOCK = new Object();

    /** null = 这次启动没能建立日志文件（之后每一行都静默跳过）。 */
    private static BufferedWriter writer;
    private static Path file;
    /** 只允许建立一次（重复调用 open 直接忽略）。 */
    private static boolean attempted = false;

    private StartupLog() {}

    /**
     * 建立本次启动的日志文件并写入文件头。任何异常都吞掉，绝不影响模组加载。
     *
     * @param preamble 版本信息那几行（与根目录报告的开头一致），由 {@link FixReport} 提供
     */
    static void open(List<String> preamble) {
        synchronized (LOCK) {
            if (attempted) return;
            attempted = true;
            try {
                Path dir = gameDir().resolve(DIR_NAME);
                Files.createDirectories(dir);
                LocalDateTime now = LocalDateTime.now();
                String base = PREFIX + NAME_STAMP.format(now);
                Path target = dir.resolve(base + SUFFIX);
                // 同一秒内重复启动（脚本重启、快速重开）时另开一份，绝不覆盖已有文件
                for (int dup = 2; Files.exists(target) && dup < 100; dup++) {
                    target = dir.resolve(base + "-" + dup + SUFFIX);
                }
                file = target;
                writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8);
                for (String line : systemLines(now, target.getFileName().toString())) {
                    writeRaw(line);
                }
                // preamble 就是根目录那份报告的开头（自带分隔线），原样再抄一遍
                for (String line : preamble) {
                    writeRaw(line);
                }
                writeRaw("----------------------------------------");
                writer.flush();
                prune(dir);
                // 进程退出时（正常关游戏/崩溃后被 launcher 收尸）补一行收尾，标记这份日志到此为止
                Runtime.getRuntime().addShutdownHook(
                        new Thread(() -> finish("=== 进程退出（游戏关闭或异常终止） ==="),
                                GtmJeiStartupFix.MOD_ID + "-log-close"));
            } catch (Throwable t) {
                file = null;
                writer = null;
                LOGGER.debug("[gtm_jei_startup_fix] 启动日志建不出来（不影响游戏，仍会写根目录那份）：{}",
                        t.toString());
            }
        }
    }

    /** 记一行（带时间戳）。拿不到文件就什么都不做。 */
    static void line(String text) {
        synchronized (LOCK) {
            if (writer == null) return;
            try {
                writeRaw(CLOCK.format(LocalDateTime.now()) + "  " + text);
                writer.flush();
            } catch (Throwable t) {
                file = null;
                closeQuietly();
                LOGGER.debug("[gtm_jei_startup_fix] 启动日志写不动了（不影响游戏）：{}", t.toString());
            }
        }
    }

    /** 收尾：写最后一行并关闭。重复调用只有第一次有效。 */
    static void finish(String lastLine) {
        synchronized (LOCK) {
            if (writer == null) return;
            try {
                writeRaw(CLOCK.format(LocalDateTime.now()) + "  " + lastLine);
            } catch (Throwable ignored) {
                // 收尾行写不进就算了
            }
            closeQuietly();
        }
    }

    /** 本次启动这份日志的「文件夹/文件名」，给报告、聊天栏、日志里指路用。 */
    static String dirHint() {
        synchronized (LOCK) {
            return file == null ? DIR_NAME + "/（这次没建起来）"
                    : DIR_NAME + "/" + file.getFileName().toString();
        }
    }

    /** 本次启动这份日志的绝对路径（拿不到就返回「(未知)」）。 */
    static String absolutePath() {
        try {
            synchronized (LOCK) {
                return file == null ? "(未知)" : file.toAbsolutePath().normalize().toString();
            }
        } catch (RuntimeException | LinkageError e) {
            return "(未知)";
        }
    }

    // ---------------- 内部 ----------------

    private static void writeRaw(String line) throws IOException {
        writer.write(line);
        writer.newLine();
    }

    private static void closeQuietly() {
        try {
            if (writer != null) writer.close();
        } catch (Throwable ignored) {
            // 已经不需要了，关不掉也无所谓（进程退出时系统会回收）
        }
        writer = null;
    }

    private static List<String> systemLines(LocalDateTime now, String actualFileName) {
        List<String> out = new ArrayList<>();
        out.add("=== " + GtmJeiStartupFix.MOD_ID + " 启动日志（每次启动一份，不会覆盖上一次） ===");
        out.add("启动时刻：" + HUMAN.format(now));
        out.add("这份文件：" + DIR_NAME + "/" + actualFileName
                + "　（游戏根目录 = " + safeGameDir() + "）");
        out.add("保留最近 " + KEEP + " 份，更早的本模组自己删掉（只删 " + DIR_NAME + "/" + PREFIX
                + "*.log，绝不动别的文件）。");
        out.add("系统｜Java " + System.getProperty("java.version", "(未知)")
                + " ｜ " + System.getProperty("os.name", "(未知)")
                + " " + System.getProperty("os.version", "")
                + " (" + System.getProperty("os.arch", "(未知)") + ")"
                + " ｜ 最大堆 " + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MB"
                + " ｜ 可用处理器 " + Runtime.getRuntime().availableProcessors() + " 个");
        out.add("运行侧：" + safeSide());
        return out;
    }

    /** 游戏根目录；拿不到（极端早期/异常环境）就用当前目录兜底。 */
    private static Path gameDir() {
        try {
            return FMLPaths.GAMEDIR.get();
        } catch (RuntimeException | LinkageError e) {
            return Path.of(".");
        }
    }

    private static String safeGameDir() {
        try {
            return gameDir().toAbsolutePath().normalize().toString();
        } catch (RuntimeException | LinkageError e) {
            return "(未知)";
        }
    }

    private static String safeSide() {
        try {
            return net.neoforged.fml.loading.FMLEnvironment.dist.isClient()
                    ? "客户端（能进游戏）" : "专用服务端";
        } catch (RuntimeException | LinkageError e) {
            return "(未知)";
        }
    }

    /** 只保留最近 20 份；删不掉就算了，绝不影响游戏。 */
    private static void prune(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> logs = new ArrayList<>();
            for (Path p : (Iterable<Path>) files.filter(StartupLog::isOurLog)::iterator) {
                logs.add(p);
            }
            // 文件名开头就是启动时刻，倒序排列 = 最新在前
            logs.sort(Comparator.reverseOrder());
            for (int i = KEEP; i < logs.size(); i++) {
                try {
                    Files.deleteIfExists(logs.get(i));
                } catch (Throwable ignored) {
                    // 删不动就留着，多几个文件不影响任何东西
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("[gtm_jei_startup_fix] 清理旧启动日志时出错（可忽略）：{}", t.toString());
        }
    }

    private static boolean isOurLog(Path p) {
        try {
            String name = p.getFileName().toString();
            return name.startsWith(PREFIX) && name.endsWith(SUFFIX) && Files.isRegularFile(p);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
