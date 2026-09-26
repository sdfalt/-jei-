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
 * （例：{@code gtm_jei_logs/startup-20260924-162009.log}）。
 *
 * <p><b>r32：留几份由配置文件说了算</b>——以前这里写死 20 份，玩家没法改。
 * 现在份数取自 {@link FixConfig#startupLogKeep()}（{@code config/gtm_jei_startup_fix.toml}
 * 里的 {@code logs.startupLogKeep}）：填 40 就留最近 40 份，填 <b>-1 一份都不删</b>。
 * 清理<b>不在建文件时做</b>，而是等配置真正读进来再做（{@link #onConfigReady(String)}）：
 * NeoForge 的配置是在模组构造之后才加载的，构造时就按默认 20 清一遍的话，
 * 玩家明明填了 40，多出来的那些也会被误删。
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

    // r32：这里原来写死 private static final int KEEP = 20;
    // 现在份数取自配置（FixConfig.startupLogKeep()，未读到配置时它返回默认 20），见 applyRetention()。

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
    /** 本次启动有没有按配置清过一次旧日志（只给「轮询兜底」那条路用，配置事件本身每次都跑）。 */
    private static boolean retentionApplied = false;

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
                // r32：这里<b>不</b>清理旧日志。此刻配置文件还没加载（NeoForge 在模组构造之后才读配置），
                // 拿默认值去删会误删玩家指定要保留的份数。改由 onConfigReady() 在配置到手后清一次。
                if (FixConfig.isLoaded()) {
                    applyRetention("建日志时配置已在手");
                }
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
        out.add("保留几份由配置决定：" + FixConfig.filePathHint()
                + " 里的 logs.startupLogKeep（-1 = 一份都不删）；"
                + "配置到手后会在这份日志里补一行「[日志保留] …」说明实际按几份在清。"
                + "删的时候只动 " + DIR_NAME + "/" + PREFIX + "*.log，绝不动别的文件。");
        out.add("系统｜Java " + System.getProperty("java.version", "(未知)")
                + " ｜ " + System.getProperty("os.name", "(未知)")
                + " " + System.getProperty("os.version", "")
                + " (" + System.getProperty("os.arch", "(未知)") + ")"
                + " ｜ 最大堆 " + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MB"
                + " ｜ 可用处理器 " + Runtime.getRuntime().availableProcessors() + " 个");
        out.add("运行侧：" + safeSide());
        return out;
    }

    /**
     * 游戏根目录；拿不到（极端早期/异常环境）就用当前目录兜底。
     *
     * <p>注意要挡两种拿不到：<b>抛异常</b>，以及 <b>返回 {@code null}</b>——后者是 r32 拿成品 jar
     * 离线跑清理逻辑时撞出来的（环境属性没喂给 FMLPaths 时 {@code GAMEDIR.get()} 不抛而是给 null），
     * 只挡异常的话，下面那句 {@code resolve} 会直接 NPE，日志与清理就整个静默失效。
     */
    private static Path gameDir() {
        try {
            Path p = FMLPaths.GAMEDIR.get();
            return p == null ? Path.of(".") : p;
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

    /**
     * 配置事件（加载完成 / 重新读取）回调：按配置里的份数清一次旧日志，并把结论写进这份日志。
     *
     * <p>为什么不在 {@link #open(List)} 里清：NeoForge 21.1 是「先构造模组、后加载配置」，
     * 建日志那一刻还拿不到玩家的设置。详见 {@link FixConfig} 的类注释。
     *
     * @param reason 触发来源，只写进日志给人看（「配置加载完成」「配置已重新读取」「轮询兜底」…）
     */
    static void onConfigReady(String reason) {
        synchronized (LOCK) {
            if (!FixConfig.isLoaded()) return;   // 还没读到就什么都不做（宁可不删，也别删错）
            applyRetention(reason);
        }
    }

    /** 兜底：万一哪天配置事件没来（版本差异等），轮询里调一次；已经清过就直接返回。 */
    static void ensureRetentionApplied() {
        synchronized (LOCK) {
            if (retentionApplied) return;
            onConfigReady("轮询兜底");
        }
    }

    /**
     * 按配置清理旧日志。调用方持锁。
     *
     * <p>语义（与配置文件里的注释一字不差）：
     * {@code -1} 或任何负数 = 一份都不删；{@code 0} = 只留本次这一份；
     * {@code N} = 留最近 N 份。<b>本次正在写的这一份永远不参与删除</b>
     * （否则填 0 就会把当场要用的文件删掉，Windows 上还会占用失败）。
     */
    private static void applyRetention(String reason) {
        int keep = FixConfig.startupLogKeep();
        int found;
        int deleted;
        if (keep < 0) {
            found = countOurLogs();
            deleted = 0;
        } else {
            int[] r = prune(keep);
            found = r[0];
            deleted = r[1];
        }
        retentionApplied = true;
        String text = "[日志保留] " + reason + "：" + FixConfig.describe()
                + "；" + DIR_NAME + " 里现有 " + found + " 份"
                + (keep < 0 ? "，按配置一份都不删"
                        : "，按「留 " + keep + " 份」删掉了 " + deleted + " 份更早的")
                + "（只删 " + DIR_NAME + "/" + PREFIX + "*.log）。";
        FixReport.note(text);   // 根目录那份与本次启动这份都拿到（note 内部回到 line，同一把锁可重入）
        LOGGER.info("[gtm_jei_startup_fix] {}", text);
    }

    /** 数一下文件夹里有几份本模组的日志（数不动返回 -1，只影响措辞，不影响功能）。 */
    private static int countOurLogs() {
        try (Stream<Path> files = Files.list(gameDir().resolve(DIR_NAME))) {
            int n = 0;
            for (Path p : (Iterable<Path>) files.filter(StartupLog::isOurLog)::iterator) {
                n++;
            }
            return n;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 只保留最近 {@code keep} 份（本次这份一定在内）。
     *
     * @return {@code {夹子里原有几份, 删掉了几个}}；数不出来时第一个元素是 -1
     */
    private static int[] prune(int keep) {
        int deleted = 0;
        try {
            Path dir = gameDir().resolve(DIR_NAME);
            List<Path> logs = new ArrayList<>();
            try (Stream<Path> files = Files.list(dir)) {
                for (Path p : (Iterable<Path>) files.filter(StartupLog::isOurLog)::iterator) {
                    logs.add(p);
                }
            } catch (java.nio.file.NoSuchFileException noDir) {
                return new int[] {0, 0};   // 文件夹还没有，等于一份都没有
            }
            // 文件名开头就是启动时刻，倒序排列 = 最新在前
            logs.sort(Comparator.reverseOrder());
            int kept = 0;
            for (Path p : logs) {
                if (isCurrentFile(p)) {
                    kept++;          // 本次正在写的这份，永远算「保留」
                    continue;
                }
                if (kept < keep) {
                    kept++;
                    continue;
                }
                try {
                    Files.deleteIfExists(p);
                    deleted++;
                } catch (Throwable ignored) {
                    // 删不动（文件被编辑器打开着等）就留着，多几个文件不影响任何东西
                }
            }
            return new int[] {logs.size(), deleted};
        } catch (Throwable t) {
            LOGGER.debug("[gtm_jei_startup_fix] 清理旧启动日志时出错（可忽略）：{}", t.toString());
            return new int[] {-1, deleted};
        }
    }

    /** 是不是本次启动正在写的那一份（路径规范化后比较，拿不到就退化成文件名比较）。 */
    private static boolean isCurrentFile(Path p) {
        try {
            if (file == null) return false;
            return file.toAbsolutePath().normalize().equals(p.toAbsolutePath().normalize());
        } catch (RuntimeException | LinkageError e) {
            try {
                return file != null && p.getFileName().equals(file.getFileName());
            } catch (RuntimeException | LinkageError e2) {
                return false;
            }
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
