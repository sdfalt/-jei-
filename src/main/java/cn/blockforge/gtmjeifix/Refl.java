package cn.blockforge.gtmjeifix;

import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 反射小工具：全部「失败返回 null / false」，绝不向外抛异常。
 *
 * <p>GTM / JEI 的类只按字符串名字加载，本模组不依赖它们的编译期 API；
 * 类加载器优先用「调用方给的对象所在的加载器」（保证拿到的 Class 与 JEI 使用的是同一个），
 * 再退回 {@link JeiReadiness#loadClass} 的多加载器试探。
 */
final class Refl {

    private Refl() {}

    /** 按名加载类；先试 {@code preferred}（可空），再走通用多加载器查找。失败返回 null。 */
    static Class<?> load(String name, ClassLoader preferred) {
        if (preferred != null) {
            try {
                return Class.forName(name, false, preferred);
            } catch (Throwable ignored) {
                // 继续通用查找
            }
        }
        try {
            return JeiReadiness.loadClass(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 读实例字段（含父类）。失败返回 null。 */
    static Object field(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {
                // 试父类
            }
        }
        return null;
    }

    /** 读静态字段。失败返回 null。 */
    static Object staticField(Class<?> cls, String name) {
        if (cls == null) return null;
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 调无参方法（含父类，接口方法也算）。失败返回 null。 */
    static Object call0Quiet(Object target, String name) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 无参 String 方法。 */
    static String callString0Quiet(Object target, String name) {
        Object v = call0Quiet(target, name);
        return v instanceof String s ? s : null;
    }

    /** 取 GTRecipeCategory 的 id：新快照是公开字段 {@code id}，旧快照是 {@code registryKey}，再退到 getId()。 */
    static ResourceLocation resourceIdOf(Object gtCategory) {
        Object v = field(gtCategory, "id");
        if (!(v instanceof ResourceLocation)) v = field(gtCategory, "registryKey");
        if (!(v instanceof ResourceLocation)) v = call0Quiet(gtCategory, "getId");
        return v instanceof ResourceLocation rl ? rl : null;
    }

    /** 这个 uid（ResourceLocation 或 toString 形如 gtceu:xxx 的对象）是不是格雷科技命名空间。 */
    static boolean isGtceu(Object uid) {
        if (uid instanceof ResourceLocation rl) return rl.getNamespace().equals("gtceu");
        return uid != null && uid.toString().startsWith("gtceu:");
    }
}
