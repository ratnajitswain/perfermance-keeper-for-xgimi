package local.perfkeeper;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Talks to the projector's own adbd to install, start, stop and configure keeper.sh. */
final class Keeper {
    static final String DIR = "/data/local/tmp/xgimi-keeper";
    static final String PREFS = "keeper";

    /** Settings stored in DIR/config (sourced by keeper.sh), with defaults. */
    static final String[][] SETTINGS = {
            {"FAN_HOLD", "255"}, {"STD_FAN", "0"}, {"TARGET_SYS", "51"},
            {"TARGET_ENV", "35"}, {"MAX_WAIT", "240"}, {"HOT_SYS", "55"},
    };

    private final Context ctx;
    private AdbClient adb;

    Keeper(Context c) { ctx = c.getApplicationContext(); }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    // ---------- connection ----------

    synchronized void connect(AdbClient.ApprovalListener l) throws IOException {
        if (adb != null) return;
        adb = AdbClient.connect("127.0.0.1", 5555, keys(), "performance-keeper@projector", 120000, l);
    }

    synchronized String sh(String cmd) throws IOException {
        if (adb == null) connect(null);
        try {
            return adb.shell(cmd);
        } catch (IOException e) {               // reconnect once (adbd restarted, etc.)
            close();
            connect(null);
            return adb.shell(cmd);
        }
    }

    synchronized void close() {
        if (adb != null) { adb.close(); adb = null; }
    }

    private KeyPair keys() throws IOException {
        File priv = new File(ctx.getFilesDir(), "adbkey"), pub = new File(ctx.getFilesDir(), "adbkey.pub");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (priv.exists() && pub.exists()) {
                PrivateKey p = kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(new FileInputStream(priv))));
                PublicKey q = kf.generatePublic(new X509EncodedKeySpec(readAll(new FileInputStream(pub))));
                return new KeyPair(q, p);
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            try (FileOutputStream o = new FileOutputStream(priv)) { o.write(kp.getPrivate().getEncoded()); }
            try (FileOutputStream o = new FileOutputStream(pub)) { o.write(kp.getPublic().getEncoded()); }
            return kp;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("key error: " + e);
        }
    }

    // ---------- install / start / stop ----------

    /** Copies keeper.sh + fanctl.jar from the app's assets to DIR if they differ. */
    void install() throws IOException {
        sh("mkdir -p " + DIR);
        for (String name : new String[]{"keeper.sh", "fanctl.jar"}) {
            byte[] data = readAll(ctx.getAssets().open(name));
            String want = md5(data);
            String have = sh("md5sum " + DIR + "/" + name + " 2>/dev/null").trim();
            if (have.startsWith(want)) continue;
            String b64 = Base64.getEncoder().encodeToString(data);
            String tmp = DIR + "/" + name + ".b64";
            sh(": > " + tmp);
            for (int i = 0; i < b64.length(); i += 3000) {
                sh("echo -n '" + b64.substring(i, Math.min(b64.length(), i + 3000)) + "' >> " + tmp);
            }
            sh("base64 -d " + tmp + " > " + DIR + "/" + name + " && rm -f " + tmp);
            String now = sh("md5sum " + DIR + "/" + name).trim();
            if (!now.startsWith(want)) throw new IOException("copy of " + name + " failed");
        }
        sh("chmod 755 " + DIR + "/keeper.sh; [ -f " + DIR + "/desired ] || echo performance > " + DIR + "/desired");
    }

    boolean running() throws IOException {
        return sh("p=$(cat " + DIR + "/keeper.pid 2>/dev/null); [ -n \"$p\" ] && kill -0 $p 2>/dev/null && echo yes").contains("yes");
    }

    void stop() throws IOException {
        // keeper's TERM trap restores the firmware fan speeds; wait for it to exit
        sh("p=$(cat " + DIR + "/keeper.pid 2>/dev/null); if [ -n \"$p\" ]; then kill $p 2>/dev/null; i=0; "
                + "while kill -0 $p 2>/dev/null && [ $i -lt 30 ]; do sleep 0.5; i=$((i+1)); done; fi; "
                + "pkill -f 'logcat -v brief -T 1 WindowSystem' 2>/dev/null; rm -f " + DIR + "/keeper.pid; echo ok");
    }

    void start() throws IOException {
        install();
        stop();
        sh("cd " + DIR + " && setsid sh " + DIR + "/keeper.sh </dev/null >/dev/null 2>&1 &");
    }

    void request(String what) throws IOException { sh("echo '" + what + "' >> " + DIR + "/events"); }

    // ---------- settings ----------

    Map<String, String> readConfig() throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        for (String[] s : SETTINGS) m.put(s[0], s[1]);
        for (String line : sh("cat " + DIR + "/config 2>/dev/null").split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) m.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return m;
    }

    void writeConfig(Map<String, String> m) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!e.getKey().matches("[A-Z_]+") || !e.getValue().matches("[0-9]+")) continue;
            sb.append(e.getKey()).append('=').append(e.getValue()).append("\\n");
        }
        sh("printf '" + sb + "' > " + DIR + "/config");
    }

    // ---------- status ----------

    static final class Status {
        boolean running; String desired = "?", mode = "?", sensors = "", log = "";
        Map<String, String> config;
    }

    Status status() throws IOException {
        String out = sh("D=" + DIR + "; p=$(cat $D/keeper.pid 2>/dev/null); "
                + "if [ -n \"$p\" ] && kill -0 $p 2>/dev/null; then echo RUN=1; else echo RUN=0; fi; "
                + "echo DESIRED=$(cat $D/desired 2>/dev/null); "
                + "echo MODE=$(gmpfUnit getDlpLumensMode 2>/dev/null | tr -dc 0-9); "
                + "echo SENS=$(CLASSPATH=$D/fanctl.jar app_process32 /system/bin Fanctl status 2>/dev/null); "
                + "echo ---LOG---; tail -n 40 $D/keeper.log 2>/dev/null");
        Status s = new Status();
        String[] parts = out.split("---LOG---", 2);
        for (String line : parts[0].split("\n")) {
            line = line.trim();
            if (line.startsWith("RUN=")) s.running = line.endsWith("1");
            else if (line.startsWith("DESIRED=")) s.desired = line.substring(8);
            else if (line.startsWith("MODE=")) s.mode = line.substring(5);
            else if (line.startsWith("SENS=")) s.sensors = line.substring(5);
        }
        s.log = parts.length > 1 ? parts[1].trim() : "";
        s.config = readConfig();
        return s;
    }

    // ---------- utils ----------

    static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    static String md5(byte[] d) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte x : MessageDigest.getInstance("MD5").digest(d)) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
