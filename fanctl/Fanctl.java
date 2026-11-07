// Fanctl — read XGIMI projector temperatures and control the cooling fan.
//
// Runs ON the projector via the adb "shell" user (whose SELinux policy is
// allowed to call xgimi.hardware.gmpf@1.0::IGmpf), using XGIMI's own
// com.xgimi.gmpf.api.TemperatureManager from /system_ext/framework/com.xgimi.api.jar.
// Everything goes through reflection so it compiles with a plain JDK.
//
//   probe                 read-only: all temperature sensors, fan mode, fan speeds
//   watch [seconds]       print temperatures + fan speeds every 2 s
//   setfan <fan> <rate>   setFanRotationRate(fan, rate)
//   setmode <mode>        setFanMode(mode)
//   stand <true|false>    setFanStandMode(...)
import java.lang.reflect.Method;

public class Fanctl {
    static final String API_JAR = "/system_ext/framework/com.xgimi.api.jar";
    static Class<?> C;
    static Object tm;

    static ClassLoader sharedLoader(String dexPath, String libPath) throws Throwable {
        Class<?> f = Class.forName("com.android.internal.os.ClassLoaderFactory");
        ClassLoader boot = ClassLoader.getSystemClassLoader().getParent();
        for (Method m : f.getDeclaredMethods()) {
            if (!m.getName().equals("createClassLoader")) continue;
            Class<?>[] p = m.getParameterTypes();
            // (dexPath, librarySearchPath, libraryPermittedPath, parent, targetSdk, isNamespaceShared, name[, sharedLibs])
            if (p.length >= 7 && p[0] == String.class && p[1] == String.class && p[5] == boolean.class) {
                m.setAccessible(true);
                Object[] a = new Object[p.length];
                a[0] = dexPath; a[1] = libPath; a[2] = libPath; a[3] = boot; a[4] = 30; a[5] = true; a[6] = null;
                return (ClassLoader) m.invoke(null, a);
            }
        }
        throw new NoSuchMethodException("ClassLoaderFactory.createClassLoader");
    }

    public static void main(String[] args) throws Throwable {
        try { Class.forName("android.os.Looper").getMethod("prepareMainLooper").invoke(null); } catch (Throwable ignored) { }

        // XGIMI's JNI lib depends on non-public system libs (libcutils...). A plain app_process
        // CLASSPATH loader gets an isolated "classloader-namespace" that can't see them, so load
        // XGIMI's API jar through a class loader with a *shared* linker namespace — the same
        // kind XGIMI's own system apps get.
        ClassLoader xg = sharedLoader(API_JAR, "/system_ext/lib:/system/lib");
        C = Class.forName("com.xgimi.gmpf.api.TemperatureManager", true, xg);
        tm = C.getMethod("getInstance").invoke(null);

        String cmd = args.length > 0 ? args[0] : "probe";
        switch (cmd) {
            case "probe": probe(); break;
            case "status": System.out.println(line()); break;
            case "watch": {
                int secs = args.length > 1 ? Integer.parseInt(args[1]) : 60;
                long end = System.currentTimeMillis() + secs * 1000L;
                while (System.currentTimeMillis() < end) { System.out.println(line()); Thread.sleep(2000); }
                break;
            }
            case "setfan":
                System.out.println("setFanRotationRate(" + args[1] + "," + args[2] + ") -> "
                        + call("setFanRotationRate", (byte) Integer.parseInt(args[1]), (byte) Integer.parseInt(args[2])));
                break;
            case "setmode":
                call("setFanMode", (byte) Integer.parseInt(args[1]));
                System.out.println("setFanMode(" + args[1] + ") done; fanMode now " + call("getFanMode"));
                break;
            case "stand":
                System.out.println("setFanStandMode(" + args[1] + ") -> " + call("setFanStandMode", Boolean.parseBoolean(args[1])));
                break;
            default:
                System.err.println("usage: probe | watch [s] | setfan <fan> <rate> | setmode <m> | stand <bool>");
                System.exit(2);
        }
        System.exit(0);
    }

    static void probe() {
        System.out.println("systemTemperature = " + safe("getSystemTemperature"));
        for (int i = 0; i < 8; i++) {
            System.out.println("sensor[" + i + "] = " + safe("getTemperatureFromSensor", (byte) i)
                    + "   float=" + safe("getTemperatureFromSensorFloat", (byte) i));
        }
        System.out.println("fanMode = " + safe("getFanMode"));
        System.out.println("fanStandMode = " + safe("getFanStandMode"));
        for (int i = 0; i < 4; i++) System.out.println("fan[" + i + "] rotationRate = " + safe("getFanRotationRate", (byte) i));
    }

    static String line() {
        StringBuilder sb = new StringBuilder(new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()));
        sb.append("  sys=").append(safe("getSystemTemperature"));
        for (int i = 0; i < 4; i++) sb.append(" s").append(i).append('=').append(safe("getTemperatureFromSensor", (byte) i));
        sb.append("  fanMode=").append(safe("getFanMode"));
        for (int i = 0; i < 2; i++) sb.append(" fan").append(i).append('=').append(safe("getFanRotationRate", (byte) i));
        return sb.toString();
    }

    static Object call(String name, Object... a) throws Throwable {
        for (Method m : C.getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == a.length) return m.invoke(tm, a);
        }
        throw new NoSuchMethodException(name);
    }

    static String safe(String name, Object... a) {
        try { return String.valueOf(call(name, a)); }
        catch (Throwable t) { Throwable c = t.getCause() != null ? t.getCause() : t; return "ERR(" + c.getClass().getSimpleName() + ")"; }
    }
}
