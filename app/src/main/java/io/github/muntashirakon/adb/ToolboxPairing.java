// SPDX-License-Identifier: Apache-2.0
// Adapter for LibADB Android 3.1.1 (Muntashir Al-Islam, Apache-2.0 option).
// Uses its Android ADB public-key and SPAKE2 protocol implementations with bounded, cancellable I/O.
package io.github.muntashirakon.adb;

import org.conscrypt.Conscrypt;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;
import java.security.Principal;

public final class ToolboxPairing implements Closeable {
    private volatile Socket socket;
    private volatile boolean closed;

    public void pair(int port, byte[] code, PrivateKey privateKey, X509Certificate certificate) throws Exception {
        if (port < 1 || port > 65535 || closed) throw new IOException("Invalid pairing state");
        Socket raw = new Socket();
        socket = raw;
        if (closed) { raw.close(); throw new IOException("Cancelled"); }
        raw.connect(new InetSocketAddress("127.0.0.1", port), 4000);
        raw.setSoTimeout(4000);
        SSLContext context = SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider());
        X509ExtendedKeyManager keys = new X509ExtendedKeyManager() {
            public String[] getClientAliases(String type, Principal[] issuers) { return new String[]{"toolbox"}; }
            public String chooseClientAlias(String[] types, Principal[] issuers, Socket peer) { return "toolbox"; }
            public String[] getServerAliases(String type, Principal[] issuers) { return null; }
            public String chooseServerAlias(String type, Principal[] issuers, Socket peer) { return null; }
            public X509Certificate[] getCertificateChain(String alias) { return new X509Certificate[]{certificate}; }
            public PrivateKey getPrivateKey(String alias) { return privateKey; }
        };
        // ADB pairing has a self-signed peer, authenticated by the six-digit SPAKE2 exchange below.
        // The socket is hard-coded to this device's loopback interface, never a user-supplied host.
        @android.annotation.SuppressLint("TrustAllX509TrustManager")
        X509TrustManager trust = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        context.init(new KeyManager[]{keys}, new TrustManager[]{trust}, new SecureRandom());
        byte[] password = null;
        PairingAuthCtx auth = null;
        try (SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(raw, "127.0.0.1", port, true)) {
            socket = tls;
            if (closed) throw new IOException("Cancelled");
            tls.setSoTimeout(4000);
            tls.startHandshake();
            byte[] material = Conscrypt.exportKeyingMaterial(tls, "adb-label\u0000", null, 64);
            password = new byte[code.length + material.length];
            System.arraycopy(code, 0, password, 0, code.length);
            System.arraycopy(material, 0, password, code.length, material.length);
            Arrays.fill(material, (byte) 0);
            auth = PairingAuthCtx.createAlice(password);
            if (auth == null) throw new IOException("Pairing initialization failed");
            DataInputStream input = new DataInputStream(tls.getInputStream());
            DataOutputStream output = new DataOutputStream(tls.getOutputStream());
            write(output, 0, auth.getMsg());
            if (!auth.initCipher(read(input, 0))) throw new IOException("Invalid pairing response");
            byte[] peer = new byte[8192];
            byte[] publicKey = AndroidPubkey.encodeWithName((RSAPublicKey) certificate.getPublicKey(), "Toolbox");
            System.arraycopy(publicKey, 0, peer, 1, publicKey.length);
            byte[] encrypted = auth.encrypt(peer);
            if (encrypted == null) throw new IOException("Pairing encryption failed");
            write(output, 1, encrypted);
            byte[] verified = auth.decrypt(read(input, 1));
            if (verified == null || verified.length != 8192 || verified[0] != 0) throw new IOException("Pairing rejected");
            Arrays.fill(verified, (byte) 0);
        } finally {
            if (auth != null) auth.destroy();
            if (password != null) Arrays.fill(password, (byte) 0);
            Arrays.fill(code, (byte) 0);
            raw.close();
        }
    }

    private static void write(DataOutputStream out, int type, byte[] payload) throws IOException {
        out.writeByte(1); out.writeByte(type); out.writeInt(payload.length); out.write(payload); out.flush();
    }

    private static byte[] read(DataInputStream in, int expectedType) throws IOException {
        if (in.readUnsignedByte() != 1 || in.readUnsignedByte() != expectedType) throw new IOException("Invalid pairing packet");
        int length = in.readInt();
        if (length < 1 || length > 16384) throw new IOException("Invalid pairing size");
        byte[] result = new byte[length];
        in.readFully(result);
        return result;
    }

    @Override public void close() throws IOException {
        closed = true;
        Socket current = socket;
        if (current != null) current.close();
    }
}
