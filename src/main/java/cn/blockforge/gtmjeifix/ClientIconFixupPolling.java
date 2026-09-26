package cn.blockforge.gtmjeifix;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * 客户端 tick 轮询。r9 起身兼三职：
 * <ol>
 * <li><b>提前补建图标</b>（r5 起就有）：JEI 就绪后尽快把占位图标换成真实图标，
 *     玩家第一次打开界面就看不到空档；</li>
 * <li><b>诊断兜底</b>（r9 新增）：{@code JeiDiagnostics.ensureProbe()}——
 *     万一 GTM 的 {@code onRuntimeAvailable} 回调因版本差异没命中，探测照样会做、
 *     现场报告照样落盘。r8 的「没效果」之所以定位不了，就是所有诊断都挂在回调后面；</li>
 * <li><b>聊天摘要</b>（r9 新增）：玩家进世界后把结论用两三行聊天字打出来，
 *     手机上不用翻日志就能看到「到底生效没有、下一步该发什么」。
 *     <b>r32 起这一步受配置控制</b>：{@code config/gtm_jei_startup_fix.toml} 里
 *     {@code messages.joinChatReminder = false} 就一个字都不发，
 *     结论照旧写进现场报告与本次启动日志（关掉它不会损失任何排查能力）。</li>
 * </ol>
 *
 * <p>任何一步失败都只记日志，不影响游戏；到期自动注销，不留常驻开销。
 */
final class ClientIconFixupPolling {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ClientIconFixupPolling INSTANCE = new ClientIconFixupPolling();

    /** 每多少个 tick 干一次活（恢复 + 探测）。 */
    private static final int INTERVAL_TICKS = 10;
    /** 超过这个 tick 数还没结论（≈3 分钟），聊天摘要照发（内容会是「卡在哪」）。 */
    private static final int CHAT_FALLBACK_TICKS = 20 * 60 * 3;
    /** 硬上限（≈20 分钟）：到点无条件注销——占位图标被用到时本来就会自愈。 */
    private static final int HARD_CAP_TICKS = 20 * 60 * 20;

    private long elapsed = 0;
    private int healedTotal = 0;
    /** 已经看到过占位图标（JEI 已开始初始化）；没看到之前不算「补建完成」。 */
    private boolean sawPendingOnce = false;
    private boolean healReported = false;
    private boolean chatShown = false;
    /** finish() 只走一次（注销失败时也不会重复写收尾行）。 */
    private boolean finished = false;

    private ClientIconFixupPolling() {}

    static void register() {
        NeoForge.EVENT_BUS.register(INSTANCE);
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        elapsed++;
        if (elapsed % INTERVAL_TICKS == 0) {
            try {
                if (!LazyIcons.isDisabled()) {
                    healedTotal += LazyIcons.healAll();
                    if (LazyIcons.hasPending()) {
                        sawPendingOnce = true;
                    } else if (sawPendingOnce && !healReported) {
                        healReported = true;
                        LOGGER.info("[gtm_jei_startup_fix] 分类图标补建完成：本轮共恢复 {} 个，"
                                + "JEI 分类标签与配方界面已恢复正常显示。", healedTotal);
                    }
                }
                // 诊断兜底：不依赖 GTM 的 onRuntimeAvailable 是否命中，内部全部幂等；
                // 挂钩覆盖度的结论只在「注册流程确实跑过（拿到运行时）」或到兜底时限后才允许下（r13 修）
                JeiDiagnostics.ensureProbe(elapsed >= CHAT_FALLBACK_TICKS);
                // r32 兜底：万一配置事件没来，这里补做一次「按配置清理旧日志」；清过就立刻返回
                StartupLog.ensureRetentionApplied();
            } catch (RuntimeException | LinkageError e) {
                LOGGER.debug("[gtm_jei_startup_fix] 轮询补建/诊断出错（不影响游戏）：{}", e.toString());
            }
        }
        maybeShowChat();
        hardCapFinish();
    }

    /** 玩家进世界后发一次聊天摘要；有结论了（或到点了）才发。r32 起这一步受配置控制。 */
    private void maybeShowChat() {
        if (chatShown) return;
        if (!JeiDiagnostics.settled() && elapsed < CHAT_FALLBACK_TICKS) return;
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        chatShown = true;
        // r32：这一步整块可以被配置关掉（config/gtm_jei_startup_fix.toml → messages.joinChatReminder）。
        // 关掉只是不打字到聊天栏：现场报告、本次启动日志、游戏日志一律照写，排查问题的能力不打折。
        if (!FixConfig.joinChatReminder()) {
            FixReport.note("[进世界提示] 已按配置关闭（" + FixConfig.filePathHint()
                    + " 里 messages.joinChatReminder = false），聊天栏这次什么都不发。"
                    + "结论仍然写进本文件与 " + FixReport.logHint() + "。");
            LOGGER.info("[gtm_jei_startup_fix] 进世界的聊天提醒已按配置关闭，结论只进日志/报告：{}；"
                    + "补注册状态：{}", JeiDiagnostics.chatSummary(), GtRegistrationBackfill.summary());
            return;
        }
        try {
            String head = "§b[" + GtmJeiStartupFix.MOD_ID + " " + FixReport.selfTag() + "] §r";
            player.displayClientMessage(Component.literal(head + JeiDiagnostics.chatSummary()), false);
            player.displayClientMessage(Component.literal(head + "补注册状态："
                    + GtRegistrationBackfill.summary()), false);
            // r24：多方块 3D 结构图位置校正的状态。只在装了 ModularUI（格雷界面库）时才说，
            // 没装的话格雷配方页本来就不走那条渲染链，讲这句只会让人多看一行废话。
            if (!"(没装)".equals(FixReport.modVersion("modularui"))) {
                player.displayClientMessage(Component.literal(head
                        + MultiblockEmbedOffset.statusLine()
                        + "；" + MenuKeepOnScreen.statusLine()), false);
            }
            player.displayClientMessage(Component.literal(head + "现场报告：游戏根目录 "
                    + FixReport.FILE_NAME + "；本次启动逐行日志："
                    + FixReport.logHint() + "（发回任意一份即可定位）"), false);
            // r32：顺手告诉玩家这个开关在哪，省得他以为只能忍着看这几行
            player.displayClientMessage(Component.literal(head + "这几行提示、以及启动日志留几份，"
                    + "都能在游戏根目录 " + FixConfig.filePathHint() + " 里改"
                    + "（不想看到这几行就把 joinChatReminder 改成 false；"
                    + "日志份数改 startupLogKeep，-1 = 一份都不删）。注释是中英双语的。"), false);
            LOGGER.info("[gtm_jei_startup_fix] 聊天摘要已发送：{}", JeiDiagnostics.chatSummary());
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("[gtm_jei_startup_fix] 发送聊天摘要失败（不影响游戏）：{}", e.toString());
        }
    }

    private void hardCapFinish() {
        if (elapsed >= HARD_CAP_TICKS || (chatShown && JeiDiagnostics.settled() && !LazyIcons.hasPending())) {
            if (!JeiDiagnostics.settled()) {
                LOGGER.info("[gtm_jei_startup_fix] 停止轮询（当前结论：{}）；"
                        + "占位图标被用到时仍会自行恢复，不影响游戏。", JeiDiagnostics.chatSummary());
            }
            finish();
        }
    }

    private void finish() {
        if (finished) return;
        finished = true;
        // r16：在「本次启动专属」那份日志里留个收尾标记，
        // 这样看文件就知道启动阶段到这儿已经全部结束（后面再有的行都是运行中产生的）
        FixReport.note("[启动阶段结束] 轮询已注销；这份日志到上面一行为止就是本次启动的全部过程。");
        try {
            NeoForge.EVENT_BUS.unregister(INSTANCE);
        } catch (RuntimeException e) {
            LOGGER.debug("[gtm_jei_startup_fix] 注销轮询监听时出错（可忽略）：{}", e.toString());
        }
    }
}
