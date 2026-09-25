package cn.blockforge.gtmjeifix;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * GTM (GregTech: CEu Modern) 8.0.0-SNAPSHOT 启动崩溃修复 —— r17。
 *
 * <p><b>崩溃链（原文报错 {@code Cannot invoke "mezz.jei.api.runtime.IJeiRuntime.getJeiHelpers()"
 * because the return of "...GTJEIPlugin.getRuntime()" is null}）</b>：
 * GTM 的 {@code GTRecipeCategories} 在静态初始化时构造 {@code CategoryIcon}，
 * 其内部 {@code JeiCallWrapper.getRenderable(...)} 立刻调用
 * {@code GTJEIPlugin.getRuntime().getJeiHelpers().getGuiHelper()...}。
 * 这条链发生在 JEI 把 {@code IJeiRuntime} 交给 GTM 之前，于是空指针、游戏无法启动。
 *
 * <p><b>r5 的修法（一直在生效）</b>：JEI 未就绪时不返回 {@code null}，而是返回
 * {@link LazyIcons} 造的<b>占位图标</b>（实现 JEI 的 {@code IDrawable}，16x16 与真值一致）。
 * JEI 分类表缓存的就是这个占位对象本身，因此它在<b>被用到的那一刻</b>自动换成真实图标，
 * 与「就绪时刻」的先后顺序无关。{@code GTJEIPluginMixin}（{@code onRuntimeAvailable} 之后）与
 * {@link ClientIconFixupPolling}（客户端 tick）负责提前恢复，玩家看不到空档。
 *
 * <p><b>r8 的修法（一直在生效）</b>：GTM 这个快照把 {@code registerCategories} 里注册主配方
 * 分类的那行自己注释掉了（只注册 6 个特殊分类），玩家因此在 JEI 里看不到研磨、分离等页面。
 * {@code GTJEIPluginBackfillMixin} 在 GTM 注册出口把缺的分类用 {@code GtFallbackCategory} 补上，
 * RecipeType 取 GTM 自己 memoize 的 {@code GTRecipeJEICategory.TYPES}，与它后面交配方同一实例。
 * 编译期吃 JEI 官方 api jar（compileOnly、绝不打包），类名写错连 class 都产不出来。
 *
 * <p><b>r9 新增：现场自 reporting —— 为了终结「能进游戏，但不知道有没有效果」</b>：
 * <ol>
 * <li>{@link FixReport}：每个里程碑（挂钩命中/没命中、补了几个分类、图标恢复数、
 *     诊断卡在哪一步）实时写进<b>游戏根目录的 {@code gtm_jei_fix_report.txt}</b>。
 *     不用翻几万行日志，把这个小文件发回来就能定位。</li>
 * <li>玩家进世界后，聊天栏自动打两三行结论（生效没生效、下一步做什么）。</li>
 * <li>诊断探测改由客户端轮询<b>兜底驱动</b>：以前只在 GTM 的 {@code onRuntimeAvailable}
 *     回调命中后才跑——回调一旦因 JEI 版本差异没命中，就什么反馈都没有（r8 的盲区）。
 *     现在就算回调没命中，也会照常出结论、进报告。</li>
 * <li>编译期 JEI api 升到与用户实装同版本 <b>19.56.0.441</b>（已逐字节核对：
 *     本模组用到的接口在 19.27→19.56 之间零变化，行为与 r8 一致）。</li>
 * </ol>
 *
 * <p><b>r16 新增：每次启动留一份独立日志</b>——{@link StartupLog} 在游戏根目录的
 * {@code gtm_jei_logs/} 里按启动时刻建文件（{@code startup-yyyyMMdd-HHmmss.log}），
 * 逐行带时间戳、行到即落盘，<b>不会</b>被下一次启动顶掉；根目录那份
 * {@code gtm_jei_fix_report.txt} 照旧保留为「最新一份」，两份内容一致。
 *
 * <p><b>r17 新增：ModularUI ↔ 新版 JEI 的 RecipeSlot 兼容补丁</b>——玩家点开格雷配方页
 * （如搅拌机）必崩：{@code MixinApplyError / InvalidAccessorException: allIngredients}。
 * 根因（拿 modmaven 的 JEI 19.25/19.27/19.56/19.57 真 jar 逐版 javap 核对）：
 * ModularUI 3.3.1 编译对照的是 JEI 19.25，其 {@code RecipeSlotAccessor} 按名字往
 * {@code RecipeSlot} 的 {@code allIngredients}/{@code displayIngredients} 两个 List 字段写数据；
 * JEI 19.3x 起重构掉了这两个字段（19.56、19.57 实测均已不存在），@Accessor 定位失败是硬错误。
 * 修法：{@code JeiRecipeSlotCompatPlugin}（独立 mixin 配置，priority 900，
 * 小于 ModularUI 的默认 1000——Mixin 自然顺序是数字小的先应用）
 * 在 {@code RecipeSlot} 类转换的最早时机直接补回这两个字段，让 ModularUI 的 accessor 能落地；
 * 老 JEI 字段还在则原样跳过，零影响。详见该类注释。
 *
 * <p>任何一步结构对不上都只降级、不崩溃。对 GTM 全程按字符串类名反射访问；
 * 对 JEI 用官方 api jar 作 compileOnly 依赖，成品 jar 不打包它们。
 *
 * <p>版本历程（都是真实踩过的坑，别再改回去）：r6 回调参数写 {@code Object} → 描述符不符，加载期崩溃；
 * r7 回调参数写「凭印象的 JEI 接口名」→ 类名在 JEI 19 里不存在，转换 Mixin 类时崩溃；
 * r8 编译期用真 JEI api、描述符逐条对照真 jar 核实；
 * r9 加现场报告与聊天摘要（诊断不再只挂在 GTM 回调后面）；
 * r16 现场报告改为「每次启动一份」（游戏根目录 gtm_jei_logs/）；
 * r17 补 ModularUI 需要的 RecipeSlot 旧字段（修「点配方页就崩」）。
 */
@Mod(GtmJeiStartupFix.MOD_ID)
public final class GtmJeiStartupFix {

    public static final String MOD_ID = "gtm_jei_startup_fix";

    private static final Logger LOGGER = LogUtils.getLogger();

    public GtmJeiStartupFix() {
        FixReport.start(FixReport.modVersion(MOD_ID));
        LOGGER.info("[gtm_jei_startup_fix] 已加载（{}）：修启动崩溃 + 补格雷配方分类 + 现场报告。"
                + "每一步干没干活都会实时写进游戏根目录的 "
                + FixReport.FILE_NAME + "，并且同步写一份到本次启动专属的 "
                + FixReport.logHint() + "（每次启动一份、不覆盖上一次，"
                + "完整路径 " + FixReport.logPath() + "）；进世界后聊天栏还会自动打两三行结论。"
                + "如果还是「没效果」，把那份文件的内容发回来就能直接定位卡在哪一步。"
                + "（编译期 JEI api 与实装同版本 19.56.0.441，接口逐字节核对无变化。）",
                FixReport.selfTag());
        FixReport.note("[已加载]（NeoForge " + FixReport.modVersion("neoforge")
                + "，JEI " + FixReport.modVersion("jei")
                + "，gtceu " + FixReport.modVersion("gtceu") + "）");
        FixReport.note("[兼容补丁] r17 的「RecipeSlot 字段补丁」已就位：第一次打开配方页时才生效，"
                + "生效时会在这份日志里记一条「已为 ModularUI 补回字段」；没记就是没触发（老 JEI 或没装 ModularUI）。");
        if (FMLEnvironment.dist.isClient()) {
            ClientIconFixupPolling.register();
        }
    }
}
