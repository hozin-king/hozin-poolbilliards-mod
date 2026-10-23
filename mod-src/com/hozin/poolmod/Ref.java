package com.hozin.poolmod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Helper refleksi untuk mengakses kelas game (com.forthblue.pool /
 * com.fruitsmobile.basket) tanpa dependensi kompilasi. Semua akses
 * gagal-aman: nilai default dikembalikan, tidak pernah melempar.
 * Field dicari menyusuri rantai superclass (mis. x/y ada di
 * DrawableObject, scale/id di BallSprite, vx di PoolBall).
 */
public final class Ref {
    private static final Map<String, Field> FIELD_CACHE = new HashMap<String, Field>();
    private static final Map<String, Method> METHOD_CACHE = new HashMap<String, Method>();

    private Ref() {
    }

    public static Class<?> cls(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Field findField(Class<?> c, String name) {
        String key = c.getName() + "#" + name;
        Field f = FIELD_CACHE.get(key);
        if (f != null) {
            return f;
        }
        Class<?> cur = c;
        while (cur != null) {
            try {
                f = cur.getDeclaredField(name);
                f.setAccessible(true);
                FIELD_CACHE.put(key, f);
                return f;
            } catch (Throwable t) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    public static Method findMethod(Class<?> c, String name, Class<?>... params) {
        StringBuilder sb = new StringBuilder(c.getName()).append('#').append(name).append('(');
        for (Class<?> p : params) {
            sb.append(p.getName()).append(',');
        }
        String key = sb.append(')').toString();
        Method m = METHOD_CACHE.get(key);
        if (m != null) {
            return m;
        }
        Class<?> cur = c;
        while (cur != null) {
            try {
                m = cur.getDeclaredMethod(name, params);
                m.setAccessible(true);
                METHOD_CACHE.put(key, m);
                return m;
            } catch (Throwable t) {
                cur = cur.getSuperclass();
            }
        }
        // metode publik dari interface (PoolRule) / superclass publik
        try {
            m = c.getMethod(name, params);
            m.setAccessible(true);
            METHOD_CACHE.put(key, m);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object get(Object o, String field) {
        if (o == null) {
            return null;
        }
        Field f = findField(o.getClass(), field);
        if (f == null) {
            return null;
        }
        try {
            return f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    public static float getF(Object o, String field) {
        Object v = get(o, field);
        return v instanceof Number ? ((Number) v).floatValue() : 0.0f;
    }

    public static float getF(Object o, String field, float def) {
        Object v = get(o, field);
        return v instanceof Number ? ((Number) v).floatValue() : def;
    }

    public static int getI(Object o, String field) {
        Object v = get(o, field);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    public static boolean getB(Object o, String field) {
        Object v = get(o, field);
        return v instanceof Boolean && (Boolean) v;
    }

    /** Tulis field objek (mis. PoolBall.inHole -> null). Gagal-aman. */
    public static void setObj(Object o, String field, Object val) {
        if (o == null) {
            return;
        }
        Field f = findField(o.getClass(), field);
        if (f == null) {
            return;
        }
        try {
            f.set(o, val);
        } catch (Throwable t) {
            // abaikan
        }
    }

    public static void setF(Object o, String field, float val) {
        if (o == null) {
            return;
        }
        Field f = findField(o.getClass(), field);
        if (f == null) {
            return;
        }
        try {
            f.setFloat(o, val);
        } catch (Throwable t) {
            // abaikan
        }
    }

    public static Object invoke(Object o, String name, Class<?>[] sig, Object... args) {
        if (o == null) {
            return null;
        }
        Method m = findMethod(o.getClass(), name, sig);
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(o, args);
        } catch (Throwable t) {
            return null;
        }
    }

    public static float invokeF(Object o, String name) {
        Object v = invoke(o, name, new Class<?>[0]);
        return v instanceof Number ? ((Number) v).floatValue() : 0.0f;
    }

    public static float invokeF(Object o, String name, float def) {
        Object v = invoke(o, name, new Class<?>[0]);
        return v instanceof Number ? ((Number) v).floatValue() : def;
    }

    public static int invokeI(Object o, String name, Class<?>[] sig, Object... args) {
        Object v = invoke(o, name, sig, args);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    public static boolean invokeB(Object o, String name) {
        Object v = invoke(o, name, new Class<?>[0]);
        return v instanceof Boolean && (Boolean) v;
    }

    public static boolean invokeB(Object o, String name, Class<?>[] sig, Object... args) {
        Object v = invoke(o, name, sig, args);
        return v instanceof Boolean && (Boolean) v;
    }

    public static Object newInstance(Class<?> c) {
        if (c == null) {
            return null;
        }
        try {
            java.lang.reflect.Constructor<?> ctor = c.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object newInstanceInt(Class<?> c, int arg) {
        if (c == null) {
            return null;
        }
        try {
            java.lang.reflect.Constructor<?> ctor = c.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            return ctor.newInstance(arg);
        } catch (Throwable t) {
            return null;
        }
    }
}
