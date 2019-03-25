package com.swift.sandhook;

import android.os.Build;

import com.swift.sandhook.annotation.HookMode;
import com.swift.sandhook.blacklist.HookBlackList;
import com.swift.sandhook.utils.ReflectionUtils;
import com.swift.sandhook.utils.Unsafe;
import com.swift.sandhook.wrapper.HookErrorException;
import com.swift.sandhook.wrapper.HookWrapper;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SandHook {

    static Map<Member,HookWrapper.HookEntity> globalHookEntityMap = new ConcurrentHashMap<>();
    static Map<Method,HookWrapper.HookEntity> globalBackupMap = new ConcurrentHashMap<>();

    private static HookModeCallBack hookModeCallBack;
    public static void setHookModeCallBack(HookModeCallBack hookModeCallBack) {
        SandHook.hookModeCallBack = hookModeCallBack;
    }

    private static HookResultCallBack hookResultCallBack;
    public static void setHookResultCallBack(HookResultCallBack hookResultCallBack) {
        SandHook.hookResultCallBack = hookResultCallBack;
    }

    public static Class artMethodClass;

    public static Field nativePeerField;
    public static Method testOffsetMethod1;
    public static Method testOffsetMethod2;
    public static Object testOffsetArtMethod1;
    public static Object testOffsetArtMethod2;

    public static int testAccessFlag;

    static {
        SandHookConfig.libLoader.loadLib();
        init();
    }

    private static boolean init() {
        initTestOffset();
        initThreadPeer();
        SandHookMethodResolver.init();
        return initNative(SandHookConfig.SDK_INT, SandHookConfig.DEBUG);
    }

    private static void initThreadPeer() {
        try {
            nativePeerField = getField(Thread.class, "nativePeer");
        } catch (NoSuchFieldException e) {

        }
    }

    public static void addHookClass(Class... hookWrapperClass) throws HookErrorException {
        HookWrapper.addHookClass(hookWrapperClass);
    }

    public static void addHookClass(ClassLoader classLoader, Class... hookWrapperClass) throws HookErrorException {
        HookWrapper.addHookClass(classLoader, hookWrapperClass);
    }

    public static synchronized void hook(HookWrapper.HookEntity entity) throws HookErrorException {

        if (entity == null)
            throw new HookErrorException("null hook entity");

        Member target = entity.target;
        Method hook = entity.hook;
        Method backup = entity.backup;

        if (target == null || hook == null)
            throw new HookErrorException("null input");

        if (globalHookEntityMap.containsKey(entity.target))
            throw new HookErrorException("method <" + entity.target.toString() + "> has been hooked!");

        if (HookBlackList.canNotHook(target))
            throw new HookErrorException("method <" + entity.target.toString() + "> can not hook, because of in blacklist!");

        resolveStaticMethod(target);
        resolveStaticMethod(backup);
        if (backup != null && entity.resolveDexCache) {
            SandHookMethodResolver.resolveMethod(hook, backup);
        }
        if (target instanceof Method) {
            ((Method)target).setAccessible(true);
        }

        int mode = HookMode.AUTO;
        if (hookModeCallBack != null) {
            mode = hookModeCallBack.hookMode(target);
        }

        globalHookEntityMap.put(entity.target, entity);

        int res;
        if (mode != HookMode.AUTO) {
            res = hookMethod(target, hook, backup, mode);
        } else {
            HookMode hookMode = hook.getAnnotation(HookMode.class);
            res = hookMethod(target, hook, backup, hookMode == null ? HookMode.AUTO : hookMode.value());
        }

        if (res > 0 && backup != null) {
            backup.setAccessible(true);
        }

        entity.hookMode = res;

        if (hookResultCallBack != null) {
            hookResultCallBack.hookResult(res > 0, entity);
        }

        if (res < 0) {
            globalHookEntityMap.remove(entity.target);
            throw new HookErrorException("hook method <" + entity.target.toString() + "> error in native!");
        }

        if (entity.backup != null) {
            globalBackupMap.put(entity.backup, entity);
        }

        HookLog.d("method <" + entity.target.toString() + "> hook <" + (res == HookMode.INLINE ? "inline" : "replacement") + "> success!");
    }

    public static Object callOriginMethod(Member originMethod, Object thiz, Object... args) throws Throwable {
        HookWrapper.HookEntity hookEntity = globalHookEntityMap.get(originMethod);
        if (hookEntity == null || hookEntity.backup == null)
            return null;
        return callOriginMethod(originMethod, hookEntity.backup, thiz, args);
    }

    public static Object callOriginByBackup(Method backupMethod, Object thiz, Object... args) throws Throwable {
        HookWrapper.HookEntity hookEntity = globalBackupMap.get(backupMethod);
        if (hookEntity == null)
            return null;
        return callOriginMethod(hookEntity.target, backupMethod, thiz, args);
    }

    public static Object callOriginMethod(Member originMethod, Method backupMethod, Object thiz, Object[] args) throws Throwable {
        //holder in stack to avoid moving gc
        Class originClassHolder = originMethod.getDeclaringClass();
        //reset declaring class
        if (SandHookConfig.SDK_INT >= Build.VERSION_CODES.N) {
            ensureDeclareClass(originMethod, backupMethod);
        }
        if (Modifier.isStatic(originMethod.getModifiers())) {
            try {
                return backupMethod.invoke(null, args);
            } catch (InvocationTargetException throwable) {
                if (throwable.getCause() != null) {
                    throw throwable.getCause();
                } else {
                    throw throwable;
                }
            }
        } else {
            try {
                return backupMethod.invoke(thiz, args);
            } catch (InvocationTargetException throwable) {
                if (throwable.getCause() != null) {
                    throw throwable.getCause();
                } else {
                    throw throwable;
                }
            }
        }
    }

    public static void ensureBackupMethod(Method backupMethod) {
        if (SandHookConfig.SDK_INT < Build.VERSION_CODES.N)
            return;
        HookWrapper.HookEntity entity = globalBackupMap.get(backupMethod);
        if (entity != null) {
            ensureDeclareClass(entity.target, backupMethod);
        }
    }

    public static void resolveStaticMethod(Member method) {
        //ignore result, just call to trigger resolve
        if (method == null)
            return;
        try {
            if (method instanceof Method && Modifier.isStatic(method.getModifiers())) {
                ((Method) method).setAccessible(true);
                ((Method) method).invoke(new Object(), getFakeArgs((Method) method));
            }
        } catch (Throwable throwable) {
        }
    }

    private static Object[] getFakeArgs(Method method) {
        Class[] pars = method.getParameterTypes();
        if (pars == null || pars.length == 0) {
            return new Object[]{new Object()};
        } else {
            return null;
        }
    }

    public static Object getObject(long address) {
        long threadSelf = getThreadId();
        if (address == 0 || threadSelf == 0)
            return null;
        return getObjectNative(threadSelf, address);
    }

    public static boolean canGetObjectAddress() {
        return Unsafe.support();
    }

    public static long getObjectAddress(Object object) {
        return Unsafe.getObjectAddress(object);
    }

    private static void initTestOffset() {
        // make test methods sure resolved!
        ArtMethodSizeTest.method1();
        ArtMethodSizeTest.method2();
        // get test methods
        try {
            testOffsetMethod1 = ArtMethodSizeTest.class.getDeclaredMethod("method1");
            testOffsetMethod2 = ArtMethodSizeTest.class.getDeclaredMethod("method2");
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("SandHook init error", e);
        }
        initTestAccessFlag();
    }

    private static void initTestAccessFlag() {
        if (hasJavaArtMethod()) {
            try {
                loadArtMethod();
                Field fieldAccessFlags = getField(artMethodClass, "accessFlags");
                testAccessFlag = (int) fieldAccessFlags.get(testOffsetArtMethod1);
            } catch (Exception e) {
            }
        } else {
            try {
                Field fieldAccessFlags = getField(Method.class, "accessFlags");
                testAccessFlag = (int) fieldAccessFlags.get(testOffsetMethod1);
            } catch (Exception e) {
            }
        }
    }

    private static void loadArtMethod() {
        try {
            Field fieldArtMethod = getField(Method.class, "artMethod");
            testOffsetArtMethod1 = fieldArtMethod.get(testOffsetMethod1);
            testOffsetArtMethod2 = fieldArtMethod.get(testOffsetMethod2);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
    }


    public static boolean hasJavaArtMethod() {
        if (SandHookConfig.SDK_INT >= Build.VERSION_CODES.O)
            return false;
        if (artMethodClass != null)
            return true;
        try {
            if (SandHookConfig.initClassLoader == null) {
                artMethodClass = Class.forName("java.lang.reflect.ArtMethod");
            } else {
                artMethodClass = Class.forName("java.lang.reflect.ArtMethod", true, SandHookConfig.initClassLoader);
            }
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static Field getField(Class topClass, String fieldName) throws NoSuchFieldException {
        while (topClass != null && topClass != Object.class) {
            try {
                Field field = topClass.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (Exception e) {
            }
            topClass = topClass.getSuperclass();
        }
        throw new NoSuchFieldException(fieldName);
    }

    public static long getThreadId() {
        if (nativePeerField == null)
            return 0;
        try {
            if (nativePeerField.getType() == int.class) {
                return nativePeerField.getInt(Thread.currentThread());
            } else {
                return nativePeerField.getLong(Thread.currentThread());
            }
        } catch (IllegalAccessException e) {
            return 0;
        }
    }

    public static boolean passApiCheck() {
        return ReflectionUtils.passApiCheck();
    }

    private static native boolean initNative(int sdk, boolean debug);

    public static native void setHookMode(int hookMode);

    //default on!
    public static native void setInlineSafeCheck(boolean check);
    public static native void skipAllSafeCheck(boolean skip);

    private static native int hookMethod(Member originMethod, Method hookMethod, Method backupMethod, int hookMode);

    public static native void ensureMethodCached(Method hook, Method backup);
    public static native void ensureDeclareClass(Member origin, Method backup);

    public static native boolean compileMethod(Member member);
    public static native boolean deCompileMethod(Member member, boolean disableJit);

    public static native boolean canGetObject();
    public static native Object getObjectNative(long thread, long address);

    public static native boolean is64Bit();

    public static native boolean disableVMInline();

    @FunctionalInterface
    public interface HookModeCallBack {
        int hookMode(Member originMethod);
    }

    @FunctionalInterface
    public interface HookResultCallBack {
        void hookResult(boolean success, HookWrapper.HookEntity hookEntity);
    }

}
