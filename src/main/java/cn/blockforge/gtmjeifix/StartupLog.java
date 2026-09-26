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
 * r16：<b>每次启动留一份独立的启动日志</b>，放在游戏根目录的
 * {@code gtm_jei_logs} 文件夹里（与 {@code mods} 文件夹同级）。
 *
 * <p><b>为什么需要它</b>：{@link FixReport} 那份 {@code gtm_jei_fix_report.txt}
 * 每次启动都会被<b>整份重写</b>——玩家想对比「上一次能进、这一次进不去」，
 * 或者一次启动崩在半路、第二次启动又把它盖掉了，现场就永远拿不到了。
 * 本类改成<b>只追加、不覆盖</b>：一次启动一个文件，文件名带启动时刻
 * （例：{@code gtm_jei_logs/startup-20260924-162009.log}）。
 *
 * <p><b>r32：留几份由配置文件说了算</b>——{@link FixConfig#startupLogKeep()}：
 * 填 40 就留最近 40 份，填 <b>-1 一份都不删</b>。
 *
 * <p><b>r34：建不建这个文件本身也可以配置</b>——{@code logs.writeStartupLog=false}
 * 时一个字节都不写。为了做到「说不写就真的一个字都不写」，<b>文件的创建从构造时
 * 推迟到配置到手那一刻</b>（{@link #onConfigReady(String)}）：NeoForge 在模组构造之后
 * 才加载配置，构造时就建文件的话，玩家明明关了开关，本次启动还是会多出半份文件。
 * 推迟期间的行不丢：先攒在内存里（{@link #EARLY_LINE_CAP} 行封顶），
 * 决定「建」的那一刻按原顺序补写进去。
 * 万一配置事件迟迟不来（异常环境），5 秒后的轮询兜底会按默认值（建）决定，
 * 诊断能力在最坏情况下也不如 r32 少。
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

    private static final String PREFIX = "startup-";
    private static final String SUFFIX = ".log";

    /** 文件名里的启动时刻。 */
    private static final DateTimeFormatter NAME_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** 每行前面的时间戳，用来看「卡在哪一步、那一步之间隔了多久」。 */
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 决定建文件之前攒行的上限（正常启动这段窗口只有毫秒级，几百行都到不了）。 */
    private static final int EARLY_LINE_CAP = 2000;

    /** 配置事件迟迟不来时，最晚多久之后按默认值决定（毫秒）。 */
    private static final long DECIDE_FALLBACK_MS = 5_000L;

    private static final Object LOCK = new Object();

    /** null = 还没建立（或决定不建立、或建立失败）本次启动的日志文件。 */
    private static BufferedWriter writer;
    private static Path file;
    /** 只允许 open 一次（重复调用直接忽略）。 */
    private static boolean attempted = false;
    /** r34：是否已经按配置决定过「这次建不建文件」（决定前所有行先进内存）。 */
    private static boolean decided = false;
    /** r34：decided 之后——本次是否因 {@code logs.writeStartupLog=false} 而主动跳过。 */
    private static boolean skippedByConfig = false;
    /** r34：open() 传进来的文件头（延迟到决定之后再写）。 */
    private static List<String> pendingPreamble;
    /** r34：决定之前攒下的行（带各自的时间戳，补写时原样输出）。 */
    private static final List<String> earlyLines = new ArrayList<>();
    /** 本次启动有没有按配置清过一次旧日志（只给「轮询兜底」那条路用，配置事件本身每次都跑）。 */
    private static boolean retentionApplied = false;
    /** 第一次「配置还没到手就想决定」的时刻（DECIDE_FALLBACK_MS 兜底的起点）。 */
    private static long firstWaitMillis = 0;

    private StartupLog() {}

    /**
     * 登记本次启动要写的文件头。r34 起这里<b>不再立刻建文件</b>：
     * NeoForge 在模组构造之后才加载配置，而 {@code logs.writeStartupLog} 这个开关
     * 要求「说不写就一个字节都不写」，所以建文件推迟到 {@link #onConfigReady(String)}。
     * 任何异常都吞掉，绝不影响模组加载。
     *
     * @param preamble 版本信息那几行（与根目录报告的开头一致），由 {@link FixReport} 提供
     */
    static void open(List<String> preamble) {
        synchronized (LOCK) {
            if (attempted) return;
            attempted = true;
            pendingPreamble = new ArrayList<>(preamble);
            if (FixConfig.isLoaded()) {
                decide("建日志入口时配置已在手");
            }
        }
    }

    /** 记一行（带时间戳）。还没决定建不建就先进内存；决定不建/建失败就什么都不做。 */
    static void line(String text) {
        synchronized (LOCK) {
            String stamped = CLOCK.format(LocalDateTime.now()) + "  " + text;
            if (writer != null) {
                try {
                    writer.write(stamped);
                    writer.newLine();
                    writer.flush();
                } catch (Throwable t) {
                    file = null;
                    closeQuietly();
                    LOGGER.debug("[gtm_jei_startup_fix] 启动日志写不动了（不影响游戏）：{}", t.toString());
                }
                return;
            }
            if (!decided && earlyLines.size() < EARLY_LINE_CAP) {
                earlyLines.add(stamped);   // 补写时保持原时间戳，不装作发生在决定之后
            }
        }
    }

    /** 收尾：写最后一行并关闭。重复调用只有第一次有效。 */
    static void finish(String lastLine) {
        synchronized (LOCK) {
            if (writer == null) return;
            try {
                writer.write(CLOCK.format(LocalDateTime.now()) + "  " + lastLine);
                writer.newLine();
            } catch (Throwable ignored) {
                // 收尾行写不进就算了
            }
            closeQuietly();
        }
    }

    /** 本次启动这份日志的「文件夹/文件名」，给报告、聊天栏、日志里指路用。 */
    static String dirHint() {
        synchronized (LOCK) {
            if (writer != null && file != null) {
                return DIR_NAME + "/" + file.getFileName().toString();
            }
            if (!decided) {
                return DIR_NAME + "/（等配置读到后按 " + FixConfig.filePathHint()
                        + " 里 logs.writeStartupLog 决定建不建）";
            }
            if (skippedByConfig) {
                return DIR_NAME + "/（已按配置关闭 logs.writeStartupLog=false，本次不建）";
            }
            return DIR_NAME + "/（这次没建起来）";
        }
    }

    /** 本次启动那份日志的绝对路径（拿不到就返回「(未知)」）。 */
    static String absolutePath() {
        try {
            synchronized (LOCK) {
                return file == null ? "(未知)" : file.toAbsolutePath().normalize().toString();
            }
        } catch (Throwable e) {
            return "(未知)";
        }
    }

    /** r34：/gtmfix status 用——本次启动到底建没建这份文件。 */
    static String writeSwitchState() {
        synchronized (LOCK) {
            if (!decided) return "还没到配置读取那一步（暂按默认=会建）";
            if (skippedByConfig) return "已按配置关闭，本次一个字都没写";
            if (writer != null) return "正常写入中";
            return "想建，但这次没建起来（目录不可写？）";
        }
    }

    // ---------------- 配置到手：决定 + 清理 ----------------

    /**
     * 配置事件（加载完成 / 重新读取）回调：
     * ① 第一次到达时按 {@code logs.writeStartupLog} 决定建不建本次那份文件（含补写攒的行）；
     * ② 按配置里的份数清一次旧日志，并把结论写进这份日志。
     *
     * <p>为什么不在 {@link #open(List)} 里建、在这里之前也不清：NeoForge 21.1 是
     * 「先构造模组、后加载配置」，构造那一刻还拿不到玩家的设置（详见 {@link FixConfig} 类注释）。
     *
     * <p>配置没读到时（异常环境）：超过 {@link #DECIDE_FALLBACK_MS} 就按默认值决定——
     * 建文件照常、清理不做（宁可不删，也别删错）。
     *
     * @param reason 触发来源，只写进日志给人看（「配置加载完成」「配置已重新读取」「轮询兜底」…）
     */
    static void onConfigReady(String reason) {
        synchronized (LOCK) {
            boolean loaded = FixConfig.isLoaded();
            if (!decided) {
                if (loaded) {
                    decide(reason);
                } else {
                    long now = System.currentTimeMillis();
                    if (firstWaitMillis == 0) {
                        firstWaitMillis = now;
                    } else if (now - firstWaitMillis >= DECIDE_FALLBACK_MS) {
                        decide("配置在 " + (DECIDE_FALLBACK_MS / 1000)
                                + " 秒内没读到，按默认值（建）决定");
                    }
                }
            }
            if (loaded) {
                applyRetention(reason);
            }
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
     * 按 {@code logs.writeStartupLog} 决定本次启动那份文件的去留。调用方持锁，只会执行一次。
     * 决定「建」时把攒下的 {@link #earlyLines} 原顺序补写；决定「不建」时一个字节都不落盘。
     */
    private static void decide(String reason) {
        if (decided) return;
        decided = true;
        boolean want = FixConfig.writeStartupLogEnabled();
        if (!want) {
            skippedByConfig = true;
            earlyLines.clear();
            pendingPreamble = null;
            LOGGER.info("[gtm_jei_startup_fix] 逐行启动日志已按配置关闭（{} 里 logs.writeStartupLog=false），"
                    + "本次启动不会在 {} 里建任何文件。根目录那份小报告由 report.enabled 单独控制。",
                    FixConfig.filePathHint(), DIR_NAME);
            FixReport.note("[启动日志] 已按配置关闭（" + FixConfig.filePathHint()
                    + " 里 logs.writeStartupLog=false），本次一个字都不写；"
                    + "现场报告" + (FixConfig.reportEnabled() ? "照常写。" : "也关了，本次不往游戏目录写任何文件。"));
            return;
        }
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
            if (pendingPreamble != null) {
                for (String line : pendingPreamble) {
                    writeRaw(line);
                }
            }
            writeRaw("----------------------------------------");
            // r34：把「决定之前攒的行」按原时间戳补写，早发生的诊断一行不丢
            for (String stamped : earlyLines) {
                writer.write(stamped);
                writer.newLine();
            }
            earlyLines.clear();
            pendingPreamble = null;
            writer.flush();
            LOGGER.info("[gtm_jei_startup_fix] 本次启动的逐行日志：{}（{}）", absolutePath(), reason);
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
                + "这份文件本身建不建由 logs.writeStartupLog 决定（r34 新增，这次是「建」）。"
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
        } catch (Throwable e) {
            return Path.of(".");
        }
    }

    private static String safeGameDir() {
        try {
            return gameDir().toAbsolutePath().normalize().toString();
        } catch (Throwable e) {
            return "(未知)";
        }
    }

    private static String safeSide() {
        try {
            return net.neoforged.fml.loading.FMLEnvironment.dist.isClient()
                    ? "客户端（能进游戏）" : "专用服务端";
        } catch (Throwable e) {
            return "(未知)";
        }
    }

    /**
     * 按配置清理旧日志（含 r34 的写开关语义：没建文件时也照样按份数清，玩家说留几份就几份）。
     * 调用方持锁。
     *
     * <p>语义（与配置文件里的注释一字不差）：
     * {@code -1} 或任何负数 = 一份都不删；{@code 0} = 只留最新一份；
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
                + (skippedByConfig ? "（本次那份按配置没建，清理照做）" : "")
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
        } catch (Throwable e) {
            try {
                return file != null && p.getFileName().equals(file.getFileName());
            } catch (Throwable e2) {
                return false;
            }
        }
    }

    private static boolean isOurLog(Path p) {
        try {
            String name = p.getFileName().toString();
            return name.startsWith(PREFIX) && name.endsWith(SUFFIX) && Files.isRegularFile(p);
        } catch (Throwable e) {
            return false;
        }
    }
}
