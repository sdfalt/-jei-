package cn.blockforge.gtmjeifix;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * r34 新增：游戏内命令 <b>{@code /gtmfix}</b>（客户端命令，注册在
 * {@code RegisterClientCommandsEvent} 上——不要求作弊、不要求 OP，任何物理客户端都能敲）。
 *
 * <p>三条子命令，全部只读或只做本模组内部的事，不碰世界数据：
 * <ul>
 * <li><b>{@code /gtmfix status}</b> —— 不改任何文件，把现在的实际状态念给你听：
 *     五个修复开关各是什么、聊天提醒/日志份数/两个写文件开关、配置读到没有、
 *     多方块预览校正与弹出菜单校正各自的实时战况、各方块模组的版本号。</li>
 * <li><b>{@code /gtmfix reload}</b> —— 记事本改完 {@code config/gtm_jei_startup_fix.toml}
 *     保存后敲一下：强制 NeoForge 立刻重读（反射走它自己 {@code ConfigWatcher} 同一条路，
 *     见 {@link FixConfig#reloadNow()}），然后按新值把该重挂的钩子/清理重新做一遍，
 *     再把结果念给你——不用重进世界，更不用重启游戏。</li>
 * <li><b>{@code /gtmfix report}</b> —— 把「现场报告」「本次启动逐行日志」「配置文件」
 *     三份文件的绝对路径和存在状态打出来。反馈问题时照着第一条复制即可。</li>
 * </ul>
 *
 * <p>输出走 {@code Minecraft.getInstance().player.displayClientMessage}（拿不到本地玩家
 * 才退回 {@code source.sendSystemMessage}），每行前面带 {@code [gtmfix]} 前缀，
 * 并且同步 {@code LOGGER.info} 一份进 {@code latest.log} 与本模组的启动日志。
 *
 * <p>只在客户端注册：专用服务端的控制台没有「本地玩家」可打字，而排查问题的永远是人——
 * 服务端要看的文件（报告与日志）本来就在服务端自己的根目录里，路径会在启动日志里写全。
 */
public final class GtmfixCommand {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PREFIX = "§b[" + GtmJeiStartupFix.MOD_ID + "/gtmfix] §r";

    private GtmfixCommand() {}

    /** 由 {@code RegisterClientCommandsEvent} 挂钩调用。 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gtmfix")
                .then(Commands.literal("status").executes(GtmfixCommand::status))
                .then(Commands.literal("reload").executes(GtmfixCommand::reload))
                .then(Commands.literal("report").executes(GtmfixCommand::report))
                .executes(GtmfixCommand::usage));
    }

    // ---------------- 子命令 ----------------

    private static int usage(CommandContext<CommandSourceStack> ctx) {
        send(ctx, List.of(
                "用法：/gtmfix status ｜ /gtmfix reload ｜ /gtmfix report",
                "  status = 看现在哪几个修复开着、配置读到没有（不改动任何东西）",
                "  reload = 改完 " + FixConfig.filePathHint() + " 保存后敲这个，立刻重读并生效",
                "  report = 告诉你现场报告和本次启动日志的完整路径"));
        return success();
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        List<String> lines = new ArrayList<>();
        lines.add("版本：本模组 " + FixReport.selfTag()
                + "（" + FixReport.modVersion(GtmJeiStartupFix.MOD_ID) + "）"
                + "｜NeoForge " + FixReport.modVersion("neoforge")
                + "｜JEI " + FixReport.modVersion("jei")
                + "｜gtceu " + FixReport.modVersion("gtceu")
                + "｜ModularUI " + FixReport.modVersion("modularui"));
        lines.add("配置文件：" + FixConfig.absoluteFilePath()
                + "（" + (FixConfig.isLoaded() ? "已读到" : "还没读到，下面全是默认值") + "）");
        lines.add("修复开关：" + FixConfig.fixesSummary());
        boolean crash = FixConfig.crashFixEnabled();
        boolean backfill = FixConfig.categoryBackfillEnabled();
        boolean compat = FixConfig.recipeSlotCompatEnabled();
        boolean offset = FixConfig.multiblockOffsetEnabled();
        boolean menu = FixConfig.menuKeepOnScreenEnabled();
        if (crash && backfill && compat && offset && menu) {
            lines.add("五个修复全开着，与以前的版本行为一致。");
        }
        lines.add("消息与日志：" + FixConfig.describe()
                + "｜写逐行日志=" + StartupLog.writeSwitchState()
                + "｜写现场报告=" + (FixConfig.reportEnabled() ? "开" : "关"));
        if (offset) {
            lines.add(MultiblockEmbedOffset.statusLine());
        }
        if (menu) {
            lines.add(MenuKeepOnScreen.statusLine());
        }
        if (!compat) {
            lines.add("提醒：RecipeSlot 字段补丁以「本次启动第一次打开配方页之前」的取值为准，改完这条要重启才彻底重算。");
        }
        lines.add("想改动：上面每一项都在配置文件里（[fixes]/[messages]/[logs]/[report] 四组，注释中英双语）；"
                + "改完保存后敲 /gtmfix reload 立刻生效。");
        send(ctx, lines);
        return success();
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        List<String> lines = new ArrayList<>();
        lines.add(FixConfig.reloadNow());
        // reloadNow() 成功时 NeoForge 会广播 ModConfigEvent.Reloading，FixConfig.onConfigApplied
        // 已把「该重挂的重新挂、该清的重新清」并记进日志；失败/版本差异时下面这条兜底保证同等的效果。
        FixConfig.onConfigApplied("手动 /gtmfix reload");
        lines.add("重读后的状态：" + FixConfig.fullStatus());
        send(ctx, lines);
        return success();
    }

    private static int report(CommandContext<CommandSourceStack> ctx) {
        List<String> lines = new ArrayList<>();
        lines.add("现场报告（最新现场，每次启动重写）：");
        lines.add("　" + FixReport.reportPath());
        lines.add("　" + FixReport.reportStateLine());
        lines.add("本次启动逐行日志（每次启动一份，不覆盖）：");
        lines.add("　" + FixReport.logPath());
        lines.add("配置文件：");
        lines.add("　" + FixConfig.absoluteFilePath());
        lines.add("反馈问题时把「现场报告」那份的内容整个发出来就够定位了；"
                + "逐行日志用来对比上一次与这一次启动的差别。");
        send(ctx, lines);
        return success();
    }

    // ---------------- 输出 ----------------

    private static int success() {
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    /** 一串文字 → 聊天栏（没有本地玩家时退回命令源），同时每种都抄一份进日志。 */
    private static void send(CommandContext<CommandSourceStack> ctx, List<String> lines) {
        Player player = null;
        try {
            player = Minecraft.getInstance().player;
        } catch (Throwable ignored) {
            // 理论不可能（本命令只在客户端注册），保险起见走 ctx.getSource()
        }
        for (String line : lines) {
            Component msg = Component.literal(PREFIX + line);
            try {
                if (player != null) {
                    player.displayClientMessage(msg, false);
                } else if (ctx != null && ctx.getSource() != null) {
                    ctx.getSource().sendSystemMessage(msg);
                }
            } catch (Throwable t) {
                LOGGER.debug("[gtm_jei_startup_fix] /gtmfix 往聊天发一行失败：{}", t.toString());
            }
            LOGGER.info("[gtm_jei_startup_fix] [gtmfix] {}", line);
        }
    }
}
