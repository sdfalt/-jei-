package cn.blockforge.gtmjeifix.mixin.compat;

import cn.blockforge.gtmjeifix.FixReport;

import com.mojang.logging.LogUtils;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * r17 新增：ModularUI ↔ 新版 JEI 的「RecipeSlot 缺字段」兼容补丁。
 *
 * <h3>玩家看到的现象</h3>
 * <p>启动一切正常（r16 已修好），但一<b>点开格雷配方的配方页</b>（例如搅拌机）游戏立刻崩溃，
 * 崩溃报告的核心一行是：
 * <pre>
 * InvalidAccessorException: No candidates were found matching allIngredients:Ljava/util/List;
 *   in mezz/jei/library/gui/ingredients/RecipeSlot
 *   for modularui.mixins.json:jei.RecipeSlotAccessor ...
 * </pre>
 *
 * <h3>根因（已用 modmaven 上的真 jar 逐版核对）</h3>
 * <ul>
 * <li>ModularUI 3.3.x（格雷界面库，GTM 的配方页依赖它）编译时对照的是 <b>JEI 19.25.1.328</b>。
 *     那个年代的 {@code RecipeSlot} 里有两个字段：
 *     {@code List allIngredients} 和 {@code List displayIngredients}；</li>
 * <li>JEI 从 19.3x 起把槽位内容重构成了独立的 {@code RecipeSlotIngredients} 对象，
 *     这两个 List 字段<b>被移除</b>（19.56.0.441、19.57.0.448 均实测不存在）；</li>
 * <li>ModularUI 用 Mixin 的 {@code @Accessor} 按<b>名字</b>往这两个字段里写数据。字段没了，
 *     {@code @Accessor} 定位失败。这不是「JEI 版本不够新」或「太新」的问题——只要 JEI ≥ 重构版本，
 *     拿 3.3.1 的 ModularUI 打开任意配方页都会炸；</li>
 * <li>{@code @Accessor} 的定位失败在 sponge-mixin 里是<b>硬错误</b>：同批还有别的 mixin 时整批中止，
 *     直接抛 {@code MixinApplyError} → 游戏崩溃。我们改不了 ModularUI 的 jar，
 *     但可以在它之前把字段补回去。</li>
 * </ul>
 *
 * <h3>做法</h3>
 * <p>本插件挂在独立的 mixin 配置 {@code gtm_jei_startup_fix_jeicompat.mixins.json} 上，
 * 该配置 {@code priority/mixinPriority: 900}、锚 mixin 注解 {@code @Mixin(priority = 900)}，
 * 都低于 ModularUI / 格雷的 1000——注意 Mixin 的自然顺序是
 * <b>数字小的先应用</b>（{@code @Mixin} javadoc 原文 "lower priority mixins being applied first"；
 * 已反编译 NeoForge 21.1 实用的 sponge-mixin 0.15.2+mixin.0.8.7 的 {@code MixinInfo.compareTo}
 * 与 {@code MixinApplicatorStandard.apply} 逐条核实，别按直觉写成更大的数；
 * 另外查过 {@code MixinInfo.readPriority}：单写配置 {@code priority} 不会传到
 * {@code MixinInfo}，必须配 {@code mixinPriority}（或注解 {@code priority}）才影响真正的先后）。
 * 当 {@code RecipeSlot} 这个类第一次被加载转换时（即玩家第一次打开配方页的那一刻），
 * {@link #preApply} 先于所有更高 priority 的配置执行，直接操作类的字节码节点：
 * 目标类里缺哪个字段就补哪个（名字、描述符 {@code Ljava/util/List;} 与 JEI 19.25 完全一致）。
 * 之后 ModularUI 的 {@code @Accessor} 就能找到字段，转换成功，不再崩溃。
 *
 * <p>为什么这一步必然来得及（同样实证过）：sponge-mixin 对同一个目标类的所有 mixin 分阶段应用——
 * 先是<b>全部</b> mixin 的 {@code preApply} 回调（本插件在这里加字段），之后才是各注入阶段，
 * 而 {@code @Accessor} 的字段定位发生在最后的 ACCESSORS 阶段、且读的就是这块实时的
 * {@code ClassNode}（{@code AccessorInfo.findTargetField → ElementNode.fieldList(classNode)}）。
 * 所以无论顺序如何，字段一定在 MUI 找它之前已经存在；900 只是让本配置整体再靠前一档。
 *
 * <h3>边界情况</h3>
 * <ul>
 * <li><b>老 JEI（字段还在）</b>：检测到同名字段就原样跳过，一个字节都不动；</li>
 * <li><b>没装 ModularUI</b>：不补（补了也没人用；格雷的 MUI 分类本来就依赖它）——
 *     连 ModList 本身都取不到时按「装」处理，宁可多补不可漏补；</li>
 * <li><b>服务端</b>：{@code RecipeSlot} 是纯客户端类，服务端永远不会加载它，插件不触发；</li>
 * <li><b>副作用</b>：补出来的是「空转字段」——ModularUI 往里写的新配料列表，新版 JEI 的绘制
 *     已经不读了（它读自己的 RecipeSlotIngredients）。配方页<b>打开、显示、翻页</b>都正常
 *     （初始内容走的是 JEI 公开 API，不受影响），受影响的只有「同一页内配料被 MUI 动态改写」
 *     这类刷新（槽位里的 + 循环角标等）。与必崩相比这是明显更好的结果。
 *     官方一旦发布适配新版 JEI 的 ModularUI，本补丁会因「字段已存在」自然退化为无操作。</li>
 * </ul>
 */
public class JeiRecipeSlotCompatPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 被修的类（内部名）。 */
    private static final String TARGET = "mezz/jei/library/gui/ingredients/RecipeSlot";

    private static final String LIST_DESC = "Ljava/util/List;";

    /** ModularUI 的 RecipeSlotAccessor 按名字定位、且新版 JEI 删掉的两个字段。 */
    private static final String FIELD_ALL_INGREDIENTS = "allIngredients";
    private static final String FIELD_DISPLAY_INGREDIENTS = "displayIngredients";

    /** 只做一次（RecipeSlot 一辈子只会被类加载转换一次，双保险）。 */
    private static boolean done = false;

    @Override
    public void onLoad(String mixinPackage) {
        // 配置注册比模组构造还早，这里什么都不做，动手都在 preApply。
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    /**
     * 本配置（priority 900，数字小者先）的任何 mixin 应用到目标类<b>之前</b>回调；
     * 且整个 preApply 阶段先于 @Accessor 定位字段的 ACCESSORS 阶段。
     * 我们配置里唯一的 mixin 锚定 RecipeSlot，因此这里只会收到 RecipeSlot——
     * 不放心再按名字判一次，缺哪个字段补哪个。
     */
    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        try {
            if (done || targetClass == null || !TARGET.equals(targetClass.name)) {
                return;
            }
            // r34：独立开关。RecipeSlot 一辈子只被类转换一次，所以这一条以本次启动
            // 第一次打开配方页那一刻读到的值为准，改开关下次重启才彻底重算（配置注释里也写了）。
            // 注意不能顺手置 done：那会把「稍后才读到配置说是开」的路也堵死。
            if (!cn.blockforge.gtmjeifix.FixConfig.recipeSlotCompatEnabled()) {
                LOGGER.info("[gtm_jei_startup_fix] RecipeSlot 兼容补丁已按配置关闭"
                        + "（{} 里 fixes.enableRecipeSlotCompat=false），本次不补字段。"
                        + "若你的 ModularUI 还会往缺字段里写数据，打开配方页会像以前一样崩溃——改回 true 并重启即可。",
                        cn.blockforge.gtmjeifix.FixConfig.filePathHint());
                FixReport.note("[兼容补丁] 已按配置关闭（fixes.enableRecipeSlotCompat=false），"
                        + "本次不向 RecipeSlot 补字段。");
                return;
            }
            done = true;

            if (!modularuiPresent()) {
                LOGGER.info("[gtm_jei_startup_fix] RecipeSlot 兼容补丁：没有检测到 ModularUI，本次不补字段。");
                return;
            }

            int added = 0;
            if (findField(targetClass, FIELD_ALL_INGREDIENTS) == null) {
                addListField(targetClass, FIELD_ALL_INGREDIENTS);
                added++;
            }
            if (findField(targetClass, FIELD_DISPLAY_INGREDIENTS) == null) {
                addListField(targetClass, FIELD_DISPLAY_INGREDIENTS);
                added++;
            }

            if (added > 0) {
                String msg = "[兼容补丁] 已为 ModularUI 向 RecipeSlot 补回 " + added
                        + " 个被新版 JEI 移除的字段（allIngredients/displayIngredients），"
                        + "配方页不再因此崩溃（JEI " + FixReport.modVersion("jei")
                        + "，ModularUI " + FixReport.modVersion("modularui") + "）";
                LOGGER.info("[gtm_jei_startup_fix] {}", msg);
                FixReport.note(msg);
            } else {
                LOGGER.info("[gtm_jei_startup_fix] RecipeSlot 兼容补丁：字段还在（老版 JEI），无需处理。");
            }
        } catch (Throwable t) {
            // 无论出什么岔子都不能让类转换本身炸掉——最坏情况退回到「没有本补丁」的原状态。
            LOGGER.warn("[gtm_jei_startup_fix] RecipeSlot 兼容补丁执行出错（不影响其余功能）：{}", t.toString());
        }
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    /** 装了 ModularUI 才需要补；探测不了就当作装了（多补无害）。 */
    private static boolean modularuiPresent() {
        try {
            return net.neoforged.fml.ModList.get().isLoaded("modularui");
        } catch (Throwable t) {
            return true;
        }
    }

    private static FieldNode findField(ClassNode target, String name) {
        if (target.fields == null) {
            return null;
        }
        for (Object o : target.fields) {
            FieldNode f = (FieldNode) o;
            if (name.equals(f.name)) {
                return f;
            }
        }
        return null;
    }

    /** 补一个 private 非 final 的 java.util.List 字段（与 JEI 19.25 的名字/描述符一致即可）。 */
    private static void addListField(ClassNode target, String name) {
        if (target.fields == null) {
            target.fields = new java.util.ArrayList<>();
        }
        target.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, name, LIST_DESC, null, null));
    }
}
