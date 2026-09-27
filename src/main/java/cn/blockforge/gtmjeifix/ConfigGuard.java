package cn.blockforge.gtmjeifix;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableCommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * r38 新增：配置防呆 —— <b>「这一项看不懂，本次按默认 X 走」</b>。
 *
 * <p><b>要修的盲区</b>：手改 {@code config/gtm_jei_startup_fix.toml} 时把
 * {@code startupLogKeep} 填成 {@code abc}、{@code "40"}（带引号）、{@code -5}、{@code 4.5}，
 * 或者把某个开关键名拼错——NeoForge 都<b>处理得掉、也绝不崩</b>，但它的处理是
 * <b>悄悄改掉再写回文件</b>：你以为按 40 份在留日志，其实游戏早已按 20 份在跑，
 * 一个字都不会主动告诉你（原版只往 {@code latest.log} 里按它的模板打一句
 * {@code Incorrect key ... corrected from ...}，新手根本不会去翻）。
 *
 * <p><b>原理（21.1.233 / FML 4.0.43 反编译核实，见 tools/ground-truth/ 那份记录）</b>：
 * NeoForge 每次读配置文件，都<b>先</b>把我们注册的 {@link IConfigSpec} 拿去做
 * {@code isCorrect(原始解析结果)}，判定不通过才会做「备份 → 纠正 → 写回」三连。
 * 所以本类<b>包一层 {@link IConfigSpec} 挂在模组与 NeoForge 之间</b>：
 * 在它问「这份配置对不对」的那一刻，原始值（还没被任何人改过）正躺在传进来的对象里，
 * 我们替每一项跑一遍<b> NeoForge 自己的判据</b>（{@code ValueSpec#test} / {@code correct}，
 * 不是我们另写一套，避免「我以为的规则」和游戏实际的规则打架），把没被原样采用的项
 * 逐条翻译成一句人话存起来（{@link #current()}），由 {@link FixConfig#onConfigApplied(String)}
 * 播进逐行日志 / 现场报告 / 聊天栏 / {@code /gtmfix}。其余方法一律原样转发，
 * 游戏侧行为与不包这一层<b>完全一致</b>。
 *
 * <p><b>四条判据（全部实测过，别凭想象改）</b>：
 * <ol>
 * <li><b>值没写</b>（整项删掉了）→ 游戏自动补默认值。这是正常用法，<b>不吭声</b>；</li>
 * <li><b>值看不懂</b>（{@code abc}、{@code "40"} 带引号、布尔写了 {@code yes}）→
 *     游戏整项按默认走 → 报「这一项看不懂，本次按默认 X 走」；</li>
 * <li><b>值超出允许范围</b>（{@code startupLogKeep = -5}）→ 注意：NeoForge 对超范围的
 *     <b>数字</b>是<b>夹到边界</b>（-5 → -1「一份都不删」）而不是回默认 20！
 *     所以这句报「本次夹到 -1 走」，并提醒那<b>不是</b>默认值——这正是最容易骗人的一条；</li>
 * <li><b>test 能通过、但运行时读数会变样</b>（{@code 4.5}）→ NeoForge 认为没毛病、
 *     连文件都不改，而 {@code getInt} 会把它<b>截断成 4</b> 用——最隐蔽的静默。
 *     本类用 {@code ConfigValue#getRaw}（与游戏运行时<b>同一个</b>取法）复核一遍才下结论。</li>
 * </ol>
 * 另附赠两条：<b>不认识的项</b>（拼错的键名，游戏会顺手从文件里删掉）逐条点破并给
 * 最接近的正确拼写；<b>整份文件语法坏掉</b>（比如 {@code = abc} 连引号都没了）时
 * NeoForge 连解析都过不了，会把你原文备份成 {@code gtm_jei_startup_fix-1.toml.bak}
 * 再重建一份全默认的新文件——这条也单独说清（这种时候 isCorrect 根本不会被叫到，
 * 判据就是「本次 load 里没叫过我们 + 文件此前存在」）。
 *
 * <p><b>绝对不许干的事</b>：把游戏自己的流程碰坏。本类所有入口全程 try/catch，
 * 审计出任何错都只是「少一句提示」，绝不向上抛、绝不影响 {@code delegate} 的回答。
 */
public final class ConfigGuard implements IConfigSpec {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 真正干活的 spec（NeoForge 的全部语义都走它，这里只旁观）。 */
    private final ModConfigSpec delegate;

    // ---------------- 每项设置的人话名字（键路径 -> 中文短名） ----------------
    // 与 FixConfig 里的 define 清单一一对应；新加配置项记得补一行，漏了也不会错，
    // 只是提示语里少个括号注释。
    private static final Map<String, String> NICE_NAMES = new LinkedHashMap<>();
    static {
        NICE_NAMES.put("fixes.enableStartupCrashFix", "启动崩溃修复开关");
        NICE_NAMES.put("fixes.enableCategoryBackfill", "格雷分类补注册开关");
        NICE_NAMES.put("fixes.enableRecipeSlotCompat", "RecipeSlot 字段补丁开关");
        NICE_NAMES.put("fixes.enableMultiblockEmbedOffset", "多方块预览校正开关");
        NICE_NAMES.put("fixes.enableMenuKeepOnScreen", "弹出菜单不出屏开关");
        NICE_NAMES.put("messages.joinChatReminder", "进世界聊天提醒开关");
        NICE_NAMES.put("logs.startupLogKeep", "启动日志保留份数");
        NICE_NAMES.put("logs.writeStartupLog", "逐行启动日志写文件开关");
        NICE_NAMES.put("report.enabled", "现场报告写文件开关");
    }

    private ConfigGuard(ModConfigSpec delegate) {
        this.delegate = delegate;
    }

    /** 给 {@link FixConfig}：把真 spec 包一层再交给 NeoForge 登记。 */
    public static ConfigGuard wrap(ModConfigSpec spec) {
        return new ConfigGuard(spec);
    }

    // ---------------- 结论的存放（静态：全模组就一份配置一个包装器） ----------------

    /**
     * 非空发现要在手里攥多久。来历（21.1.233 / FML 4.0.43 反编译核实）：
     * 游戏发现文件「不对」时会纠正后<b>自己把文件写回去</b>，而它自己的文件监视器
     * 会把这次回写当成「玩家又改了」再触发一次重读——第二次审计看到的已经是纠正后的
     * 文件，findings 变空，聊天/status 若读得晚就被冲掉了。所以短时间内的「空审计」
     * 一律当作回写回声，保留上一份非空结论；过了这个窗口再空，才认账。
     */
    private static final long ECHO_WINDOW_MS = 15_000L;

    private static final Object LOCK = new Object();

    /** 上一次读档审计出的全部「没被原样采用」的行（[] = 全部读懂）。给 status/聊天用。 */
    private static volatile List<String> lastFindings = List.of();
    /** 上一次已经播报出去的行；与新审计结果相同就不重复喊。 */
    private static List<String> announced = List.of();
    /** 最近一次「非空审计」发生的时刻（回声窗口的起点；0 = 从没报过）。 */
    private static long lastNonEmptyAt = 0L;
    /** 本次 load 里 NeoForge 有没有来问过 isCorrect（问过 = 文件语法是通的）。 */
    private static boolean sawIsCorrectThisLoad = false;
    /** 模组构造那一刻配置文件存不存在（区分「第一次生成」与「原文读挂了」）。 */
    private static volatile boolean fileExistedAtRegister = false;
    /** 本次进程里已经吃完过至少一次 load。 */
    private static boolean firstLoadDone = false;

    /** 审计结果落库（含回声保护）。只在 LOCK 里调。 */
    private static void storeFindings(List<String> findings) {
        long now = System.currentTimeMillis();
        if (findings.isEmpty() && !lastFindings.isEmpty() && now - lastNonEmptyAt < ECHO_WINDOW_MS) {
            // 上一次游戏刚自动改回过文件，紧接着又读到一份「全对」的——多半是它自己的回写回声，
            // 保留刚报出来的警告，别把玩家最该看的那几句冲掉。
            return;
        }
        lastFindings = findings;
        if (!findings.isEmpty()) {
            lastNonEmptyAt = now;
        }
    }

    /** {@link FixConfig#register} 在登记配置前调用：文件当时在不在。 */
    static void noteFileExistedBeforeLoad(boolean existed) {
        fileExistedAtRegister = existed;
    }

    /** 当前（最近一次读档的）审计结论；不消费，聊天/status 随时可读。永不为 null。 */
    public static List<String> current() {
        return lastFindings;
    }

    /**
     * 取出「还没播报过」的审计结论并标记为已播报（{@link FixConfig#onConfigApplied} 用）。
     * /gtmfix reload 先手动回调、事件回调随后又到，靠这里去重，免得一句「看不懂」喊两遍。
     */
    public static List<String> takeUnannounced() {
        synchronized (LOCK) {
            List<String> cur = lastFindings;
            if (cur.equals(announced)) {
                return List.of();
            }
            announced = cur;
            return cur;
        }
    }

    /** 一行总括（fullStatus / status 命令用）；没有结论时也给一句「全读懂了」。 */
    public static String summaryLine() {
        List<String> f = lastFindings;
        if (f.isEmpty()) {
            return "配置防呆：最近一次读配置，每一行都按你写的原样生效";
        }
        return "⚠ 配置防呆：最近一次读配置有 " + f.size() + " 处没按原样生效（明细见下）";
    }

    // ---------------- IConfigSpec：只旁观，不动手 ----------------

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public void validateSpec(ModConfig config) {
        delegate.validateSpec(config);
    }

    /**
     * NeoForge 每次读完文件都会先来问这一句——<b>此刻传进来的就是玩家手写的原始值</b>
     * （它还没来得及纠正）。审计在这里做，异常一律吞掉：审计失败只等于少一句提示。
     */
    @Override
    public boolean isCorrect(UnmodifiableCommentedConfig config) {
        sawIsCorrectThisLoad = true;
        try {
            List<String> findings = audit(config);
            synchronized (LOCK) {
                storeFindings(findings);
            }
        } catch (Throwable t) {
            LOGGER.debug("[gtm_jei_startup_fix] 配置防呆审计没跑完（不影响游戏，只是这次少几句提示）：{}",
                    t.toString());
        }
        return delegate.isCorrect(config);
    }

    @Override
    public void correct(CommentedConfig config) {
        delegate.correct(config);
    }

    @Override
    public void acceptConfig(IConfigSpec.ILoadedConfig config) {
        boolean saw;
        synchronized (LOCK) {
            saw = sawIsCorrectThisLoad;
            sawIsCorrectThisLoad = false;
        }
        try {
            if (config != null) {
                boolean wasFirstLoad = !firstLoadDone;
                firstLoadDone = true;
                // 没被问过 isCorrect 的一次 load 只有两种可能：第一次生成文件（正常），
                // 或者文件整体 parse 失败——NeoForge 已把原文备份成 *-1.toml.bak 并重建了默认文件。
                if (!saw && !(wasFirstLoad && !fileExistedAtRegister)) {
                    synchronized (LOCK) {
                        storeFindings(List.of(wholeFileUnreadableLine()));
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("[gtm_jei_startup_fix] 配置防呆（整读失败判定）出错（不影响游戏）：{}", t.toString());
        }
        delegate.acceptConfig(config);
    }

    // ---------------- 审计本体 ----------------

    /** 逐条挑出「这次没按原样生效」的行；返回不可变列表（可能为空）。 */
    private List<String> audit(UnmodifiableCommentedConfig cfg) {
        List<String> out = new ArrayList<>();

        // ① 已知项：拿 NeoForge 自己的判据过一遍
        forEachLeafValue(cv -> {
            try {
                List<String> path = cv.getPath();
                Object raw = cfg.get(path);
                if (raw == null) {
                    return;   // 判据①：整项没写 = 正常（游戏会补默认并回填文件），不吭声
                }
                ModConfigSpec.ValueSpec vs = cv.getSpec();
                if (!vs.test(raw)) {
                    out.add(wrongValueLine(cv, raw));
                    return;
                }
                // test 通过 ≠ 一定原样生效：4.5 能过 IntValue 的范围判据，
                // 但运行时 getInt 会把它截断成 4，游戏连文件都不改。用与运行时同一个取法复核。
                Object effective = runtimeRead(cfg, cv);
                if (effective != null && !semanticallyEqual(raw, effective)) {
                    out.add(truncatedLine(cv, raw, effective));
                }
            } catch (Throwable t) {
                LOGGER.debug("[gtm_jei_startup_fix] 配置防呆跳过一项（不影响游戏）：{}", t.toString());
            }
        });

        // ② 不认识的项：键名拼错时值会整个作废（游戏还会把该行从文件里删掉），必须点破
        try {
            out.addAll(unknownKeyLines(cfg));
        } catch (Throwable t) {
            LOGGER.debug("[gtm_jei_startup_fix] 配置防呆（陌生项扫描）出错（不影响游戏）：{}", t.toString());
        }

        return List.copyOf(out);
    }

    /** 「看不懂」行。两种结局分开说：数字越界是夹到边界（≠默认！），其余才是回默认。 */
    private String wrongValueLine(ModConfigSpec.ConfigValue<?> cv, Object raw) {
        ModConfigSpec.ValueSpec vs = cv.getSpec();
        Object corrected = vs.correct(raw);
        boolean clamped = raw instanceof Number
                && vs.getRange() != null
                && !String.valueOf(corrected).equals(String.valueOf(vs.getDefault()));
        String kindHint = kindHint(cv, vs);
        String body;
        if (raw instanceof String s && parsesAsNumber(s)) {
            // 最典型：把 40 加了引号 —— 差一个引号就整项作废，必须点破
            body = "写成了带引号的 \"" + s + "\"——引号要去掉，" + kindHint;
        } else {
            body = "这一项看不懂——写的是 " + show(raw) + "（" + kindHint + "）";
        }
        if (clamped) {
            return prefix(cv) + body + "；超出允许范围，本次按夹到边界后的 " + show(corrected)
                    + " 走（注意：这不是默认值 " + show(vs.getDefault()) + "）";
        }
        return prefix(cv) + body + "，本次按默认 " + show(vs.getDefault()) + " 走（文件里这行会被游戏自动改回默认值）";
    }

    /** 「值被用另一种读法采用」行（4.5 → 4 这种：游戏认为没毛病，连文件都不改）。 */
    private String truncatedLine(ModConfigSpec.ConfigValue<?> cv, Object raw, Object effective) {
        return prefix(cv) + "写的是 " + show(raw) + "——这一项只认整数，本次按 " + show(effective)
                + " 走（小数被抹平；游戏不会把文件里这行改掉，建议手动写成整数）";
    }

    private static boolean parsesAsNumber(String s) {
        try {
            Double.parseDouble(s.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 陌生分组 / 陌生键名行（附最接近的正确拼写）。 */
    private List<String> unknownKeyLines(UnmodifiableCommentedConfig cfg) {
        List<String> out = new ArrayList<>();
        UnmodifiableConfig specTree = delegate.getSpec();
        List<String> allKnownKeys = new ArrayList<>();
        forEachLeafValue(cv -> allKnownKeys.add(cv.getPath().get(cv.getPath().size() - 1)));
        for (Map.Entry<String, Object> top : cfg.valueMap().entrySet()) {
            String section = top.getKey();
            Object specSection = specTree.valueMap().get(section);
            Object cfgSection = top.getValue();
            boolean isTable = cfgSection instanceof UnmodifiableConfig;
            if (!(specSection instanceof UnmodifiableConfig)) {
                if (isTable) {
                    out.add("[配置防呆] 不认识 [" + section + "] 这一段——本模组没有这个分组"
                            + "（一共就 [fixes] / [messages] / [logs] / [report] 四段）；这一段里的东西"
                            + "不会生效，游戏还会顺手把整段从文件里去掉（下次再「看不懂」的可能是你真正想改的那行）");
                } else if (specSection == null) {
                    // 值写在了任何 [段] 之外（最常见：把 startupLogKeep = 40 直接写在文件开头）
                    out.add("[配置防呆] 不认识文件开头的 " + section + " = " + show(cfgSection)
                            + "——这一项要么拼错了、要么没写在对应的 [分组] 里"
                            + nearestHint(section, allKnownKeys)
                            + "；它不会生效，游戏还会顺手把这行从文件里去掉");
                } else {
                    out.add("[配置防呆] " + section + " 这一项被写成了分组的样子（" + show(cfgSection)
                            + "），本模组要的是它落在对应 [分组] 里的一行「名字 = 值」；它不会生效");
                }
                continue;
            }
            UnmodifiableConfig sectionSpec = (UnmodifiableConfig) specSection;
            if (!isTable) {
                continue;   // 段本身被写成了值：值判据那边会处理（test 失败 → 补回整段）
            }
            for (Map.Entry<String, Object> e : ((UnmodifiableConfig) cfgSection).valueMap().entrySet()) {
                if (!(sectionSpec.valueMap().get(e.getKey()) instanceof ModConfigSpec.ValueSpec)) {
                    out.add("[配置防呆] 不认识 [" + section + "] 里的 " + e.getKey() + " = "
                            + show(e.getValue()) + "——本模组没有这一项"
                            + nearestHint(e.getKey(), allKnownKeys)
                            + "；它不会生效，游戏还会顺手把这行从文件里去掉（下次再「看不懂」的可能是你真正想改的那行）");
                }
            }
        }
        return out;
    }

    /** 整文件 parse 失败专用行（NeoForge：备份 .bak + 重建全默认）。bak 名按 FML backUpConfig 的规则拼。 */
    private static String wholeFileUnreadableLine() {
        String bak = "-1.toml.bak";
        try {
            String name = FixConfig.FILE_NAME;
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                bak = name.substring(0, dot) + "-1." + name.substring(dot + 1) + ".bak";
            }
        } catch (Throwable ignored) {
            // 拼不出就退回通用后缀，只影响措辞
        }
        return "[配置防呆] ⚠ 这次整份配置文件都没能读懂（某一行的写法不是合法 TOML——最常见的是 = 右边的值"
                + "既没引号也不是数字）。游戏已经把你的原文备份成 config/" + bak
                + "，并重建了一份全默认的新文件——你之前改过的设置这次都回到默认了。"
                + "想找回：打开那份 .bak，把你改过的行抄回新文件保存即可（或游戏里敲 /gtmfix reload 看生效没有）";
    }

    // ---------------- 小工具 ----------------

    /** 行首：「[配置防呆] [logs] startupLogKeep（启动日志保留份数）：」 */
    private static String prefix(ModConfigSpec.ConfigValue<?> cv) {
        List<String> path = cv.getPath();
        String dotted = String.join(".", path);
        String key = path.isEmpty() ? dotted : path.get(path.size() - 1);
        String section = path.size() > 1 ? "[" + path.get(0) + "] " : "";
        String nice = NICE_NAMES.get(dotted);
        return "[配置防呆] " + section + key + (nice == null ? "" : "（" + nice + "）") + "：";
    }

    /** 这一项「应该怎么写」的一句话；从类型与范围现拼，新加配置项自动就有。 */
    private static String kindHint(ModConfigSpec.ConfigValue<?> cv, ModConfigSpec.ValueSpec vs) {
        if (cv instanceof ModConfigSpec.BooleanValue) {
            return "这里只能写 true 或 false（yes/no/1/0 这种都不认值）";
        }
        String ints = intKindHint(cv);
        try {
            if (vs.getRange() != null) {
                Object min = ((ModConfigSpec.Range<?>) vs.getRange()).getMin();
                Object max = ((ModConfigSpec.Range<?>) vs.getRange()).getMax();
                return "这里只能填 " + min + " ~ " + max + " 的整数" + (ints.contains("整数") ? "" : "（不写小数、不加引号）");
            }
        } catch (Throwable ignored) {
            // 拿不到范围就只说类型
        }
        return ints;
    }

    private static String intKindHint(ModConfigSpec.ConfigValue<?> cv) {
        if (cv instanceof ModConfigSpec.IntValue) {
            return "整数（不写小数、不加引号）";
        }
        if (cv instanceof ModConfigSpec.BooleanValue) {
            return "true / false";
        }
        return "本来的那种写法";
    }

    /** 与游戏运行时<b>同一个取法</b>（ConfigValue#getRaw），复现「实际会被用成什么」。拿不到返回 null。 */
    private static Object runtimeRead(UnmodifiableCommentedConfig cfg, ModConfigSpec.ConfigValue<?> cv) {
        try {
            Config mutable;
            if (cfg instanceof Config c) {
                mutable = c;
            } else {
                // 万一哪天传进来的只是个只读视图：复制一份能跑的，判据不变
                mutable = CommentedConfig.copy(cfg, LinkedHashMap::new,
                        com.electronwill.nightconfig.core.InMemoryCommentedFormat.defaultInstance());
            }
            return runtimeReadCaptured(mutable, cv);
        } catch (Throwable ignored) {
            // 转换炸了 = 这行本来就在「看不懂」分支里报过了，这里安静退出
        }
        return null;
    }

    /**
     * 泛型捕获小助手：{@code getRaw} 的默认值供给参数绑在类自身的类型参数上，
     * 直接对着 {@code ConfigValue<?>} 调用会出「两个互不相干的通配捕获」编译错误；
     * 走这个具型方法，捕获只发生一次。
     */
    private static <T> Object runtimeReadCaptured(Config cfg, ModConfigSpec.ConfigValue<T> cv) {
        return cv.getRaw(cfg, cv.getPath(), cv::getDefault);
    }

    /** 语义相同（40L≡40、"false"≡false）：值没变样就不值得喊。 */
    private static boolean semanticallyEqual(Object raw, Object effective) {
        if (raw instanceof Number n && effective instanceof Number m) {
            return n.doubleValue() == m.doubleValue();
        }
        if (raw instanceof String s && effective instanceof Boolean b) {
            return s.equalsIgnoreCase(b ? "true" : "false");
        }
        if (raw instanceof Boolean && effective instanceof String) {
            return semanticallyEqual(effective, raw);
        }
        return raw.equals(effective);
    }

    private static String show(Object v) {
        if (v == null) {
            return "（空）";
        }
        if (v instanceof String) {
            return "\"" + v + "\"";
        }
        return String.valueOf(v);
    }

    /** 「是不是 XXX 拼错了？」提示：编辑距离 ≤2 才给，宁可不说也不瞎说。 */
    private static String nearestHint(String typo, List<String> candidates) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        String low = typo.toLowerCase(Locale.ROOT);
        for (String c : candidates) {
            int d = editDistance(low, c.toLowerCase(Locale.ROOT));
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        if (best == null || bestDist > 2 || best.equalsIgnoreCase(typo)) {
            return "";
        }
        return "（是不是 " + best + " 拼错了？）";
    }

    /** 教科书 Levenshtein，只用在几个短键名上，不值得引库。 */
    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    /** 遍历 spec 里全部叶子 ConfigValue（sections → keys 两层为主，写法上按树递归兜住任意层）。 */
    private void forEachLeafValue(java.util.function.Consumer<ModConfigSpec.ConfigValue<?>> action) {
        forEachLeafIn(delegate.getValues(), action);
    }

    private static void forEachLeafIn(UnmodifiableConfig node,
                                      java.util.function.Consumer<ModConfigSpec.ConfigValue<?>> action) {
        for (Object v : node.valueMap().values()) {
            if (v instanceof ModConfigSpec.ConfigValue<?> cv) {
                action.accept(cv);
            } else if (v instanceof UnmodifiableConfig inner) {
                forEachLeafIn(inner, action);
            }
        }
    }

    /** 只给单测/自检用：当前全部键路径（防「NICE_NAMES 漏了新项」）。 */
    static List<String> knownPathsForSelfCheck() {
        List<String> paths = new ArrayList<>();
        try {
            new ConfigGuard(FixConfig.SPEC).forEachLeafValue(cv -> paths.add(String.join(".", cv.getPath())));
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
        return paths;
    }
}
