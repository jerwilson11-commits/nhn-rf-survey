import android.content.ClipData;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * ClipSet - sets the device's primary clipboard text as root, over Binder. Research/ops tool, not
 * shipped in the app.
 *
 * Why this exists: there is no `cmd clipboard` shell command, and `adb shell input text` typing a
 * multi-line ASN.1-shaped decode into a Compose text field is fragile (special characters, and a
 * stray tap elsewhere steals focus). Every app's own "Paste from clipboard" button is the reliable
 * path once the clipboard actually holds the text.
 *
 * Talks to the "clipboard" Binder service directly (android.content.IClipboard), same pattern as
 * VonrCfg: resolved by name via ServiceManager, method resolved by reflection since the exact
 * setPrimaryClip signature (extra String/attribution-source/userId parameters) varies by Android
 * version and is not part of the public SDK.
 *
 * Run as root, from a dex:
 *   su -c 'CLASSPATH=/data/local/tmp/clipset.dex app_process /system/bin ClipSet "<text>"'
 */
public class ClipSet {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("ERR usage: ClipSet <text>");
            return;
        }
        String text = args[0];

        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "clipboard");
        if (binder == null) {
            System.out.println("ERR no clipboard service");
            return;
        }
        Class<?> stub = Class.forName("android.content.IClipboard$Stub");
        Object svc = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);

        ClipData clip = ClipData.newPlainText("sib1", text);

        Method chosen = null;
        for (Method m : svc.getClass().getMethods()) {
            if (m.getName().equals("setPrimaryClip")) {
                chosen = m;
                break;
            }
        }
        if (chosen == null) {
            System.out.println("ERR no setPrimaryClip method found");
            return;
        }

        Parameter[] params = chosen.getParameters();
        Object[] callArgs = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Class<?> t = params[i].getType();
            if (t == ClipData.class) {
                callArgs[i] = clip;
            } else if (t == String.class) {
                callArgs[i] = "com.android.shell";
            } else if (t == int.class) {
                callArgs[i] = 0;
            } else if (t.getName().equals("android.content.AttributionSource")) {
                // Build one via its Builder: uid + packageName is enough on the versions that need it.
                Class<?> asCls = t;
                Class<?> bCls = Class.forName("android.content.AttributionSource$Builder");
                Object builder = bCls.getConstructor(int.class).newInstance(android.os.Process.myUid());
                bCls.getMethod("setPackageName", String.class).invoke(builder, "com.android.shell");
                callArgs[i] = bCls.getMethod("build").invoke(builder);
            } else {
                callArgs[i] = null;
            }
        }
        chosen.invoke(svc, callArgs);
        System.out.println("OK set clipboard (" + params.length + " parameters), " + text.length() + " chars");
    }
}
