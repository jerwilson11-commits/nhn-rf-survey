import android.os.IBinder;
import android.os.PersistableBundle;

import java.lang.reflect.Method;

/**
 * VonrCfg - read, set or clear a NON-persistent carrier-config override for VoNR. Research tool,
 * not shipped in the app.
 *
 * Why this instead of `cmd phone cc`: that shell command answers "Permission denied" on this build
 * even as root, because the carrier-config override commands only work on debuggable builds. The
 * underlying service call, ICarrierConfigLoader.overrideConfig, is guarded by MODIFY_PHONE_STATE,
 * which root passes. It is hidden from the SDK, so everything here is reflection.
 *
 * Talks to the "carrier_config" service over Binder directly. An earlier version bootstrapped a
 * system Context through ActivityThread.systemMain() and was killed by the system on this handset.
 *
 * `set` requests a non-persistent override: it is dropped on reboot or SIM change, so a bad change
 * cannot outlive a restart. `setp` requests a persistent one, which survives reboots. `clear`
 * removes both kinds, so either can be undone.
 *
 * Run as root, from a dex:
 *   su -c 'CLASSPATH=/data/local/tmp/vonrcfg.dex app_process /system/bin VonrCfg <subId> show|set|setp|clear'
 */
public class VonrCfg {
    private static final String[] KEYS = {
        "vonr_enabled_bool",
        "vonr_setting_visibility_bool",
        "vonr_on_by_default_bool",
        "carrier_vonr_backoff",
        "carrier_vonr_call_fail_threshold",
    };

    private static Method find(Class<?> c, String name) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("ERR usage: VonrCfg <subId> show|set|setp|clear");
            return;
        }
        int subId = Integer.parseInt(args[0]);
        String cmd = args[1];

        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "carrier_config");
        if (binder == null) {
            System.out.println("ERR no carrier_config service");
            return;
        }
        Class<?> stub = Class.forName("com.android.internal.telephony.ICarrierConfigLoader$Stub");
        Object svc = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);

        if (cmd.equals("set") || cmd.equals("setp") || cmd.equals("clear")) {
            PersistableBundle values = null;
            if (!cmd.equals("clear")) {
                values = new PersistableBundle();
                values.putBoolean("vonr_enabled_bool", true);
                values.putBoolean("vonr_setting_visibility_bool", true);
            }
            Method o = find(svc.getClass(), "overrideConfig");
            if (o == null) {
                System.out.println("ERR no overrideConfig on the service");
                return;
            }
            Class<?>[] p = o.getParameterTypes();
            if (p.length == 3) {
                if (cmd.equals("clear")) {
                    // Remove both kinds, so a persistent override cannot be left behind.
                    o.invoke(svc, subId, null, false);
                    o.invoke(svc, subId, null, true);
                } else {
                    o.invoke(svc, subId, values, cmd.equals("setp"));
                }
            } else {
                if (cmd.equals("setp")) {
                    System.out.println("ERR this build has no persistent overrideConfig");
                    return;
                }
                o.invoke(svc, subId, values);
            }
            System.out.println("OK " + cmd + " (overrideConfig with " + p.length + " parameters)");
        }

        Method g = find(svc.getClass(), "getConfigForSubIdWithFeature");
        PersistableBundle now = null;
        if (g != null) {
            now = (PersistableBundle) g.invoke(svc, subId, "android", null);
        } else {
            g = find(svc.getClass(), "getConfigForSubId");
            if (g != null) now = (PersistableBundle) g.invoke(svc, subId, "android");
        }
        if (now == null) {
            System.out.println("ERR could not read the config back");
            return;
        }
        for (String k : KEYS) {
            System.out.println(k + " = " + now.get(k));
        }
    }
}
