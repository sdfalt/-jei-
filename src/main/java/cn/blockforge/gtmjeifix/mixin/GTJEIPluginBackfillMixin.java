package cn.blockforge.gtmjeifix.mixin;

import cn.blockforge.gtmjeifix.FixConfig;
import cn.blockforge.gtmjeifix.GtRegistrationBackfill;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把 GTM 的 JEI 注册流程整体接管过来：GTM 这个快照漏掉/跑不完的部分由本模组补齐。
 *
 * <h3>为什么 r12 的两个挂钩「明明钉对了」却始终不命中（r13 的改动依据）</h3>
 * <p>r12 现场报告给出的三条事实放在一起，结论只有一个：
 * <ol>
 * <li>方法扫描确认 GTM 类里真实存在
 *     {@code registerCategories(IRecipeCategoryRegistration)} 与
 *     {@code registerRecipes(IRecipeRegistration)}，描述符与钉死的完全一致；</li>
 * <li>同一个 Mixin 类里的 {@code registerRecipeCatalysts} TAIL 挂钩正常命中；</li>
 * <li>但 JEI 里一个 GTM 特殊分类都没有（多方块信息等 6 个全部缺席）。</li>
 * </ol>
 * 签名对、类也转换了，TAIL 却不执行——唯一可能是 <b>GTM 这两个方法的方法体在运行期中途抛出异常</b>，
 * 永远走不到「结尾」。JEI 19.56 的 {@code PluginCaller} 会把每个插件的 RuntimeException
 * 捕获后只记日志继续启动（日志里那行 {@code Caught an error from mod plugin} 就是它），
 * 所以游戏不崩、但 GTM 的分类和配方全军覆没——这正是用户看到的「JEI 里格雷页面全空/灰暗」。
 *
 * <h3>为什么 GTM 方法体会抛（与用户同一构建的源码 + JEI 19.56 字节码可核对）</h3>
 * <pre>
 * // GTJEIPlugin#registerCategories（快照 34f02a6）
 * registry.addRecipeCategories(new MultiblockInfoJeiCategory(jeiHelpers));   ← 第一句就可能在
 * ...                                                                        （JEI 19.56 新增的宽高校验下）炸掉
 * for (GTRecipeCategory category : GTRegistries.RECIPE_CATEGORIES) {
 *     // registry.addRecipeCategories(new GTRecipeJEICategory(...));         ← GTM 自己注释掉了
 * }
 * // GTJEIPlugin#registerRecipes：第一句 MultiblockInfoJeiCategory.registerRecipes(...)
 * //   —— 若上面的分类没注册成功，这里给「没有分类的 RecipeType」交配方就抛
 * //     {@code Recipe type ... not registered}，方法体同样走不完。
 * </pre>
 *
 * <h3>r13 的做法：不再指望 GTM 跑完整，直接在 HEAD 取消原方法、由本模组重放它该做的一切</h3>
 * <p>HEAD 挂钩在方法体第一行之前执行，无论 GTM 内部坏在哪都一定命中。取消原方法后，
 * 由 {@link GtRegistrationBackfill} 逐个、带独立容错地补做：
 * 先补 GTM 的 6 个特殊分类（能成几个成几个，失败的把真实异常写进现场报告），
 * 再给格雷主配方分类挂上 {@code GtFallbackCategory} 页面，最后按 GTM 自己的静态方法交配方。
 * 每一步的成败都会出现在 {@code gtm_jei_fix_report.txt} 里——就算再出现环境差异，
 * 发报告回来就能精确定位到「哪一个分类、哪一行、什么异常」。
 *
 * <p>仍保留「方法名 + 完整描述符」钉死与 {@code require = 0}：万一以后 GTM 换了方法名，
 * 后果是「这项不补、日志里说明原因」，而不是游戏打不开。
 *
 * <h3>r14 补充（适配 GTM 快照 17e1700）</h3>
 * <p>新版快照官方已把主分类注册解开（{@code GTRecipeJEICategory} 变成具体类并自带真实配方界面）。
 * HEAD 接管保持不变——但 {@link GtRegistrationBackfill} 现在<b>优先构造格雷自己的页面类</b>，
 * 只有它失败/不可用（老快照是 abstract 类）才换我们的兜底页。装老快照、新快照都是最优表现。
 */
@Mixin(targets = "com.gregtechceu.gtceu.integration.recipeviewer.jei.GTJEIPlugin", remap = false)
public class GTJEIPluginBackfillMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** GTM 注册分类的入口：取消它跑不完的原方法，由本模组带容错地整体重放。 */
    @Inject(
            method = "registerCategories(Lmezz/jei/api/registration/IRecipeCategoryRegistration;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false)
    private void gtmjeifix$takeOverRegisterCategories(IRecipeCategoryRegistration registration, CallbackInfo ci) {
        // r34：开关关了 → 不取消、不接管，GTM 原方法照跑（它自己修好了就该长这样）
        if (!FixConfig.categoryBackfillEnabled()) {
            return;
        }
        ci.cancel();
        LOGGER.debug("[gtm_jei_startup_fix] 挂钩命中（HEAD 接管）：GTM#registerCategories");
        GtRegistrationBackfill.replaceCategories(registration);
    }

    /** GTM 登记配方的入口：同上，取消并整体重放（不然它第一句抛完，机器配方一条都进不了 JEI）。 */
    @Inject(
            method = "registerRecipes(Lmezz/jei/api/registration/IRecipeRegistration;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false)
    private void gtmjeifix$takeOverRegisterRecipes(IRecipeRegistration registration, CallbackInfo ci) {
        if (!FixConfig.categoryBackfillEnabled()) {   // r34：与 registerCategories 同一条开关
            return;
        }
        ci.cancel();
        LOGGER.debug("[gtm_jei_startup_fix] 挂钩命中（HEAD 接管）：GTM#registerRecipes");
        GtRegistrationBackfill.replaceRecipes(registration);
    }

    /** GTM 登记机器定位的出口：只观测它跑没跑完（不取消、不代登，避免重复催化剂）。 */
    @Inject(
            method = "registerRecipeCatalysts(Lmezz/jei/api/registration/IRecipeCatalystRegistration;)V",
            at = @At("TAIL"),
            require = 0,
            remap = false)
    private void gtmjeifix$afterRegisterCatalysts(IRecipeCatalystRegistration registration, CallbackInfo ci) {
        if (!FixConfig.categoryBackfillEnabled()) {   // r34：关了就不再顺带补催化剂（那条路径也属于补注册）
            return;
        }
        LOGGER.debug("[gtm_jei_startup_fix] 挂钩命中：GTM#registerRecipeCatalysts");
        GtRegistrationBackfill.onCatalystsTail(registration);
    }
}
