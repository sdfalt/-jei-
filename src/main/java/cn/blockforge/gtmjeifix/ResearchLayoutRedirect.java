package cn.blockforge.gtmjeifix;

/**
 * 把格雷研究信息行导入 GTRecipeViewerWidget 的正文文字流布局。
 *
 * <p>543bc4e 的 GTRecipeUIModifiers.RESEARCH_INFO 原本直接调用
 * GTRecipeViewerWidget.child(row)，这会绕过正文 Flow；带研究数据的装配线配方因此把
 * 数据槽摆到页面左侧。Muicompat 的字节码插件把那一次调用改到这里，避免在编译期依赖
 * 格雷和内嵌 ModularUI 的类。</p>
 */
public final class ResearchLayoutRedirect {

    private ResearchLayoutRedirect() {}

    /**
     * 运行期收到 GTRecipeViewerWidget 和研究信息行，改为挂到正文 textComponents Flow。
     * 返回值保留为原调用需要的 IParentWidget 对象；拿不到时退回原调用，避免影响配方页。
     */
    public static Object redirect(Object widget, Object researchRow) {
        try {
            Object textComponents = Refl.field(widget, "textComponents");
            Object redirected = Refl.call1Quiet(textComponents, "child", researchRow);
            if (redirected != null) return redirected;
        } catch (RuntimeException | LinkageError ignored) {
            // 交给下面的原调用退回路径，不能让 JEI 配方注册被这个兼容补丁打断。
        }
        return Refl.call1Quiet(widget, "child", researchRow);
    }
}
