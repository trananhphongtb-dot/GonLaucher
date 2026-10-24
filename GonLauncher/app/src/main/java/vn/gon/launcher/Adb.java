package vn.gon.launcher;

import android.content.SharedPreferences;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * ADB client tối giản: tự kết nối tới adbd của chính tivi (127.0.0.1:5555)
 * để chạy lệnh pm disable-user / pm enable mà không cần root hay máy tính.
 */
final class Adb {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;

    interface Listener { void needApprove(); }

    private static final class Msg { int cmd, a0, a1; byte[] data; }

    private Socket sock;
    private DataInputStream in;
    private OutputStream out;
    private int nextId = 1;

    boolean isOpen() { return sock != null && sock.isConnected() && !sock.isClosed(); }

    void close() {
        try { if (sock != null) sock.close(); } catch (IOException ignored) { }
        sock = null;
    }

    void connect(KeyPair kp, Listener l) throws Exception {
        close();
        sock = new Socket();
        sock.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
        sock.setSoTimeout(8000);
        in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
        out = sock.getOutputStream();
        send(CNXN, 0x01000000, 4096, "host::gonlauncher\0".getBytes("UTF-8"));
        boolean sentSig = false, sentKey = false;
        while (true) {
            Msg m = read();
            if (m.cmd == CNXN) {
                sock.setSoTimeout(60000);
                return;
            }
            if (m.cmd == AUTH && m.a0 == 1) {
                if (!sentSig) {
                    send(AUTH, 2, 0, sign(kp.getPrivate(), m.data));
                    sentSig = true;
                } else if (!sentKey) {
                    send(AUTH, 3, 0, adbPublicKey((RSAPublicKey) kp.getPublic()));
                    sentKey = true;
                    sock.setSoTimeout(120000); // chờ người dùng bấm "Cho phép" trên tivi
                    if (l != null) l.needApprove();
                } else {
                    throw new IOException("Tivi từ chối kết nối");
                }
            }
        }
    }

    /** Chạy 1 lệnh shell, trả về toàn bộ kết quả. */
    synchronized String shell(String cmd) throws IOException {
        int local = nextId++;
        send(OPEN, local, 0, ("shell:" + cmd + "\0").getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        int remote = 0;
        while (true) {
            Msg m = read();
            if (m.a1 != local) continue;
            if (m.cmd == OKAY) {
                remote = m.a0;
            } else if (m.cmd == WRTE) {
                sb.append(new String(m.data, "UTF-8"));
                send(OKAY, local, m.a0, null);
            } else if (m.cmd == CLSE) {
                if (remote != 0) send(CLSE, local, remote, null);
                return sb.toString();
            }
        }
    }

    // ------------------------------------------------------------ giao thức

    private void send(int cmd, int a0, int a1, byte[] data) throws IOException {
        if (data == null) data = new byte[0];
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        ByteBuffer bb = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(cmd).putInt(a0).putInt(a1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        out.write(bb.array());
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = bb.getInt();
        m.a0 = bb.getInt();
        m.a1 = bb.getInt();
        int len = bb.getInt();
        if (len < 0 || len > 1024 * 1024) throw new IOException("Dữ liệu ADB lỗi");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ------------------------------------------------------------ khoá RSA

    private static final byte[] SHA1_PREFIX = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private static byte[] sign(PrivateKey k, byte[] token) throws Exception {
        // PKCS#1 v1.5 kiểu 1, token được coi là SHA-1 digest (đúng như adb gốc)
        byte[] block = new byte[256];
        block[0] = 0x00;
        block[1] = 0x01;
        int psEnd = 256 - SHA1_PREFIX.length - token.length - 1;
        for (int i = 2; i < psEnd; i++) block[i] = (byte) 0xff;
        block[psEnd] = 0x00;
        System.arraycopy(SHA1_PREFIX, 0, block, psEnd + 1, SHA1_PREFIX.length);
        System.arraycopy(token, 0, block, psEnd + 1 + SHA1_PREFIX.length, token.length);
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, k);
        return c.doFinal(block);
    }

    private static byte[] adbPublicKey(RSAPublicKey pub) throws Exception {
        final int words = 64;
        BigInteger r32 = BigInteger.ZERO.setBit(32);
        BigInteger n = pub.getModulus();
        BigInteger r = BigInteger.ZERO.setBit(words * 32);
        BigInteger rr = r.modPow(BigInteger.valueOf(2), n);
        BigInteger n0inv = n.remainder(r32).modInverse(r32);

        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(words);
        bb.putInt(n0inv.negate().intValue());
        BigInteger t = n;
        for (int i = 0; i < words; i++) {
            BigInteger[] d = t.divideAndRemainder(r32);
            t = d[0];
            bb.putInt(d[1].intValue());
        }
        t = rr;
        for (int i = 0; i < words; i++) {
            BigInteger[] d = t.divideAndRemainder(r32);
            t = d[0];
            bb.putInt(d[1].intValue());
        }
        bb.putInt(pub.getPublicExponent().intValue());
        String s = Base64.encodeToString(bb.array(), Base64.NO_WRAP) + " gon@launcher\0";
        return s.getBytes("UTF-8");
    }

    /** Lấy hoặc tạo cặp khoá cố định để tivi chỉ hỏi "Cho phép" 1 lần. */
    static KeyPair keys(SharedPreferences p) throws Exception {
        String pr = p.getString("adb_priv", null), pu = p.getString("adb_pub", null);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (pr != null && pu != null) {
            PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(pr, Base64.DEFAULT)));
            PublicKey pubk = kf.generatePublic(new X509EncodedKeySpec(Base64.decode(pu, Base64.DEFAULT)));
            return new KeyPair(pubk, priv);
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        p.edit()
                .putString("adb_priv", Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP))
                .putString("adb_pub", Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP))
                .apply();
        return kp;
    }
}
