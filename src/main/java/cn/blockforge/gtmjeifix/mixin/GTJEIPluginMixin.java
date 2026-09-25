package cn.blockforge.gtmjeifix.mixin;

import cn.blockforge.gtmjeifix.FixReport;
import cn.blockforge.gtmjeifix.JeiDiagnostics;
import cn.blockforge.gtmjeifix.JeiReadiness;
import cn.blockforge.gtmjeifix.LazyIcons;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 监听 GTM 拿到 JEI 运行时的时刻：{@code GTJEIPlugin#onRuntimeAvailable}。
 *
 * <p>GTM 源码里就是这个方法把 {@code IJeiRuntime} 存进静态字段
 * {@code runtime}（{@code @Getter} 生成 {@code getRuntime()}）。
 * 我们在方法执行完毕后：
 * <ol>
 * <li>置「已就绪」标记（{@link JeiReadiness} 的另一条判据，反射探测仍保留为双保险）；</li>
 * <li>立刻把所有占位图标提前恢复成真实图标，这样界面第一次打开就是完整的。</li>
 * </ol>
 *
 * <p>即使这个回调因为环境差异没被调到，也不影响结果：{@link JeiReadiness} 会直接反射探测
 * {@code getRuntime()} 的真实值，而每个占位图标在自己被用到时同样会自愈——
 * 这正是 r4「依赖单次回调」的缺陷被修掉的地方。
 */
@Mixin(targets = "com.gregtechceu.gtceu.integration.recipeviewer.jei.GTJEIPlugin", remap = false)
public class GTJEIPluginMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Inject(
            method = "onRuntimeAvailable(Lmezz/jei/api/runtime/IJeiRuntime;)V",
            at = @At("TAIL"),
            require = 0,           // 描述符已钉死：匹配不到只跳过，不再像 r6 那样把游戏崩掉
            remap = false)
    private void gtmjeifix$onJeiRuntimeReady(CallbackInfo ci) {
        JeiReadiness.markHookFired();
        FixReport.note("[挂钩命中] GTM#onRuntimeAvailable（JEI 已把运行时交给 GTM）");
        try {
            if (LazyIcons.createdCount() > 0) {
                LazyIcons.healAllAndReport("收到 JEI 运行时（onRuntimeAvailable）");
            }
            // 现场探针：JEI 到底收到多少个 gtceu 分类、图标恢复情况如何
            JeiDiagnostics.probeAndHeal();
            // 结构体检：我们的挂钩有没有命中；没命中就把 GTM 真实的方法签名打出来，便于定位
            JeiDiagnostics.reportHookCoverage();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("[gtm_jei_startup_fix] 提前补建分类图标时出错，占位图标会在被用到时自行恢复：{}",
                    e.toString());
        }
    }
}
