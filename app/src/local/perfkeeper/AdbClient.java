package local.perfkeeper;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import javax.crypto.Cipher;

/**
 * Minimal client for the Android Debug Bridge wire protocol, used to talk to the
 * projector's own adbd on 127.0.0.1:5555 (the same thing a computer's `adb` does).
 * Supports RSA authentication and "shell:" commands.
 */
final class AdbClient implements Closeable {
    private static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_CLSE = 0x45534c43, A_WRTE = 0x45545257;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final int VERSION = 0x01000000, MAX_PAYLOAD = 4096;
    private static final int KEY_WORDS = 64; // 2048-bit

    // PKCS#1 v1.5 padding + SHA-1 DigestInfo; adbd verifies the 20-byte token as a SHA-1 "digest".
    private static final byte[] SIG_PADDING = buildSigPadding();

    interface ApprovalListener { void onApprovalNeeded(); }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextId = 1;

    private static final class Msg { int cmd, a0, a1; byte[] data; }

    private AdbClient(Socket s) throws IOException {
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
    }

    static AdbClient connect(String host, int port, KeyPair keys, String keyName,
                             int approvalTimeoutMs, ApprovalListener listener) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 3000);
        s.setTcpNoDelay(true);
        s.setSoTimeout(15000);
        AdbClient c = new AdbClient(s);
        try {
            c.send(A_CNXN, VERSION, MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean sentSig = false, sentKey = false;
            while (true) {
                Msg m = c.read();
                if (m.cmd == A_CNXN) { s.setSoTimeout(30000); return c; }
                if (m.cmd != A_AUTH || m.a0 != AUTH_TOKEN) continue;
                if (!sentSig) {
                    c.send(A_AUTH, AUTH_SIGNATURE, 0, sign(keys, m.data));
                    sentSig = true;
                } else if (!sentKey) {
                    c.send(A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyPayload((RSAPublicKey) keys.getPublic(), keyName));
                    sentKey = true;
                    s.setSoTimeout(approvalTimeoutMs);       // user must press OK on the dialog
                    if (listener != null) listener.onApprovalNeeded();
                } else {
                    throw new IOException("debugging not allowed");
                }
            }
        } catch (SocketTimeoutException e) {
            c.close();
            throw new IOException("timed out waiting for 'Allow debugging' approval");
        } catch (IOException | RuntimeException e) {
            c.close();
            throw e;
        }
    }

    /** Runs a shell command and returns its combined output. */
    synchronized String shell(String command) throws IOException {
        int local = nextId++;
        send(A_OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int remote = 0;
        while (true) {
            Msg m = read();
            if (m.a1 != local) continue;                    // not our stream
            if (m.cmd == A_OKAY) {
                remote = m.a0;
            } else if (m.cmd == A_WRTE) {
                buf.write(m.data);
                send(A_OKAY, local, m.a0, new byte[0]);
            } else if (m.cmd == A_CLSE) {
                if (remote == 0 && buf.size() == 0) throw new IOException("shell open refused");
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    private void send(int cmd, int a0, int a1, byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : data) sum += x & 0xff;
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        out.write(b.array());
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt(); m.a0 = b.getInt(); m.a1 = b.getInt();
        int len = b.getInt();
        if (len < 0 || len > 1024 * 1024) throw new EOFException("bad adb packet");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    @Override public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }

    // ---------- RSA helpers ----------

    private static byte[] buildSigPadding() {
        byte[] prefix = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        int total = KEY_WORDS * 4 - 20;                     // 256 - SHA1 length
        byte[] p = new byte[total];
        p[0] = 0x00; p[1] = 0x01;
        int ffEnd = total - prefix.length - 1;
        for (int i = 2; i < ffEnd; i++) p[i] = (byte) 0xff;
        p[ffEnd] = 0x00;
        System.arraycopy(prefix, 0, p, ffEnd + 1, prefix.length);
        return p;
    }

    private static byte[] sign(KeyPair keys, byte[] token) throws IOException {
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, keys.getPrivate());
            c.update(SIG_PADDING);
            return c.doFinal(token);
        } catch (Exception e) {
            throw new IOException("signing failed: " + e);
        }
    }

    /** adbd's public key format: little-endian {len, n0inv, n[64], rr[64], e}, base64 + " name\0". */
    static byte[] publicKeyPayload(RSAPublicKey pub, String name) {
        BigInteger r32 = BigInteger.ZERO.setBit(32);
        BigInteger n = pub.getModulus();
        BigInteger rr = BigInteger.ZERO.setBit(KEY_WORDS * 32).modPow(BigInteger.valueOf(2), n);
        BigInteger n0inv = n.remainder(r32).modInverse(r32);
        int[] nWords = new int[KEY_WORDS], rrWords = new int[KEY_WORDS];
        for (int i = 0; i < KEY_WORDS; i++) {
            BigInteger[] r = rr.divideAndRemainder(r32); rr = r[0]; rrWords[i] = r[1].intValue();
            r = n.divideAndRemainder(r32); n = r[0]; nWords[i] = r[1].intValue();
        }
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + KEY_WORDS * 8 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(KEY_WORDS).putInt(n0inv.negate().intValue());
        for (int w : nWords) b.putInt(w);
        for (int w : rrWords) b.putInt(w);
        b.putInt(pub.getPublicExponent().intValue());
        String s = Base64.getEncoder().encodeToString(b.array()) + " " + name + "\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
