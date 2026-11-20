package cn.blockforge.gtmjeifix;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * r69：汉化格雷「JEI 多方块 3D 结构预览页」上三句**写死在字节码里**的英文提示。
 *
 * <p>这三句出现在 GTM 客户端类
 * {@code com.gregtechceu.gtceu.integration.recipeviewer.widgets.MultiblockPreviewWidget}
 * 的构造器 lambda 里（build 140/147 的字节码里分别是 {@code lambda$new$12/$15/$20}）：
 * <ul>
 * <li>{@code "Press to display preview in world!"} —— 世界预览按钮的悬浮提示；</li>
 * <li>{@code "Press to flip structure"} —— 镜像翻转按钮的悬浮提示；</li>
 * <li>{@code "Layer Selection"} —— 分层按钮的悬浮提示。</li>
 * </ul>
 * 它们走的是 {@code Component.literal(原句)}，<b>不经过任何翻译键</b>——官方语言文件里补键
 * 对它们无效，只能在调用现场把原句换掉（{@link cn.blockforge.gtmjeifix.mixin.lang.PreviewTextZhMixin}
 * 三条 {@code @Redirect}，注入配置 {@code gtm_jei_startup_fix_lang.mixins.json}，
 * {@code defaultRequire=0}：lambda 名在别的快照对不上时整条静默跳过，绝不影响游戏）。
 *
 * <p>替换是三重保守的：① 只有传入文本与上面的英文原句<b>逐字符相等</b>才动手，
 * 官方将来改了措辞就原样透传；② 只有当前游戏语言以 {@code zh} 开头（简中/繁中）才替换，
 * 其他语言玩家看到的与原版一字不差；③ 替换产物是 {@code Component.translatable(本模组自有键)}，
 * 文案放在 {@code assets/gtm_jei_startup_fix/lang/}（zh_cn/zh_tw/en_us 三份），
 * 出错兜底一律回 {@code Component.literal(原文)}。
 *
 * <p>同页另有两句按钮提示走的是正规翻译键
 * {@code gtceu.terminal.clear_multi} / {@code gtceu.terminal.clear_preference}，
 * 但格雷官方 8.0.0-SNAPSHOT（至少 543bc4e→809dda7）的 9 份语言文件里<b>一条都没生成</b>
 * （与 r68 修的 recipe_category 同一类官方漏发，r69 取证：build 147 真 jar 全语言验证缺失）。
 * 这两句不需要挂钩——按 FML 的跨 jar lang 合并（r68 已用 {@code ClientLanguage.loadFrom}
 * 真字节码核实），本模组随 jar 在 {@code gtceu} 命名空间补上八份语言即可，
 * 见 {@code src/main/resources/assets/gtceu/lang/}。
 *
 * <p>纯显示层补丁：无配置开关（与 r63/r65 的重定向、r68 的 lang 补齐同款做法），
 * 不修任何东西、不改任何东西，只改这三处悬浮提示的措辞。
 */
public final class PreviewTextZh {

    /** 官方字节码里写死的原句（逐字符比较用）。 */
    static final String WORLD_PREVIEW_EN = "Press to display preview in world!";
    static final String FLIP_EN = "Press to flip structure";
    static final String LAYER_EN = "Layer Selection";

    private static final String KEY_WORLD_PREVIEW = "gtm_jei_startup_fix.preview.tooltip.world_preview";
    private static final String KEY_FLIP = "gtm_jei_startup_fix.preview.tooltip.flip";
    private static final String KEY_LAYER = "gtm_jei_startup_fix.preview.tooltip.layer";

    /** 本次运行里真正替换成功的次数（首次成功时在日志/现场报告里明说一句，方便玩家核对生效）。 */
    private static final AtomicInteger APPLIED = new AtomicInteger();
    private static volatile boolean reported = false;

    private PreviewTextZh() {
    }

    /** {@code lambda$new$12} 现场：世界预览按钮提示。 */
    public static MutableComponent worldPreview(String text) {
        return replace(text, WORLD_PREVIEW_EN, KEY_WORLD_PREVIEW);
    }

    /** {@code lambda$new$15} 现场：镜像翻转按钮提示。 */
    public static MutableComponent flip(String text) {
        return replace(text, FLIP_EN, KEY_FLIP);
    }

    /** {@code lambda$new$20} 现场：分层按钮提示。 */
    public static MutableComponent layer(String text) {
        return replace(text, LAYER_EN, KEY_LAYER);
    }

    private static MutableComponent replace(String text, String expected, String key) {
        try {
            if (text != null && text.equals(expected) && isChinese()) {
                if (APPLIED.incrementAndGet() == 1 && !reported) {
                    reported = true;
                    FixReport.note("3D 预览页文字汉化挂钩：本次已生效（简中/繁中环境下替换官方写死的英文提示，"
                            + "见预览页按钮的悬浮提示）");
                }
                return Component.translatable(key);
            }
        } catch (Throwable ignored) {
            // 显示层的事，天塌下来也不许影响游戏：原样透传。
        }
        return Component.literal(text == null ? "" : text);
    }

    /**
     * 当前客户端语言是否 zh 系。取的是 {@code Minecraft.getInstance().options.languageCode}
     * （1.21.1 官方映射里该字段为 public String、由 LanguageSelectScreen 写回；这个精简映射集里
     * {@code I18n} 没有 getLocale，别凭旧版本记忆写）。读不到（如服务端误调）按 false 走——
     * 本类全部入口都有 try 兜底，最坏情况就是不替换。
     */
    private static boolean isChinese() {
        try {
            Minecraft mc = Minecraft.getInstance();
            String locale = mc == null ? null : mc.options.languageCode;
            return locale != null && locale.startsWith("zh");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 调试/status 用：本次替换成功过几次。 */
    public static int appliedCount() {
        return APPLIED.get();
    }
}
