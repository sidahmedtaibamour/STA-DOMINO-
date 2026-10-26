package com.sta.domino;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** قلب الاتصال المحلي: خادم TCP للمضيف + عميل + اكتشاف UDP. لا يعتمد على أندرويد (قابل للاختبار). */
public class LanCore {
    public interface Sink { void event(String json); }

    public static final int TCP_PORT = 47321, UDP_PORT = 47322;
    private static final int MAX_LINE = 262144, MAX_CLIENTS = 3;

    private final Sink sink;
    private final ConcurrentHashMap<Integer, Conn> conns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, long[]> fails = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger(0);
    private final SecureRandom rnd = new SecureRandom();
    private volatile ServerSocket server;
    private volatile DatagramSocket udp;
    private volatile String pin = "";
    private volatile boolean hosting = false;

    public LanCore(Sink s) { sink = s; }

    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') b.append("\\\"");
            else if (c == '\\') b.append("\\\\");
            else if (c < 0x20 || c == 0x2028 || c == 0x2029) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
    private void ev(String json) { try { sink.event(json); } catch (Throwable t) { /* */ } }
    private void err(String msg) { ev("{\"e\":\"error\",\"msg\":" + q(msg) + "}"); }

    static String sha(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format("%02x", x));
        return b.toString();
    }
    static String readLine(InputStream in, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return b.toString("UTF-8");
            if (b.size() >= max) throw new IOException("line too long");
            b.write(c);
        }
        return null;
    }

    private static class Conn {
        final int id; final Socket sock; final OutputStream out; final ExecutorService wr = Executors.newSingleThreadExecutor();
        Conn(int id, Socket s) throws IOException { this.id = id; sock = s; out = s.getOutputStream(); }
        void write(String line) {
            final byte[] data = (line + "\n").getBytes(StandardCharsets.UTF_8);
            try { wr.execute(() -> { try { synchronized (out) { out.write(data); out.flush(); } } catch (IOException e) { try { sock.close(); } catch (IOException x) { /* */ } } }); } catch (RejectedExecutionException e) { /* */ }
        }
        void close() { try { sock.close(); } catch (IOException e) { /* */ } wr.shutdown(); }
    }

    /* ---------------- المضيف ---------------- */
    public synchronized void startHost(final String pinCode, final int tcpPort, final int udpPort) {
        stopHost();
        pin = pinCode; hosting = true;
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(tcpPort));
            server = ss;
        } catch (IOException e) { hosting = false; err("تعذّر فتح المنفذ: " + e.getMessage()); return; }
        final ServerSocket ss = server;
        new Thread(() -> {
            while (hosting && !ss.isClosed()) {
                try { final Socket s = ss.accept(); new Thread(() -> handleClient(s)).start(); }
                catch (IOException e) { break; }
            }
        }, "lan-accept").start();
        try {
            DatagramSocket ds = new DatagramSocket(null);
            ds.setReuseAddress(true); ds.setBroadcast(true); ds.bind(new InetSocketAddress(udpPort));
            udp = ds;
            new Thread(() -> {
                byte[] buf = new byte[64];
                while (hosting && !ds.isClosed()) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length); ds.receive(p);
                        String m = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                        if (m.startsWith("DOMINO?")) { byte[] r = "DOMINO!".getBytes(StandardCharsets.UTF_8); ds.send(new DatagramPacket(r, r.length, p.getAddress(), p.getPort())); }
                    } catch (IOException e) { break; }
                }
            }, "lan-udp").start();
        } catch (IOException e) { /* الاكتشاف اختياري */ }
        ev("{\"e\":\"hosting\"}");
    }

    public synchronized void stopHost() {
        hosting = false;
        try { if (server != null) server.close(); } catch (IOException e) { /* */ }
        try { if (udp != null) udp.close(); } catch (Exception e) { /* */ }
        server = null; udp = null;
        for (Conn c : conns.values()) c.close();
    }

    private void handleClient(Socket s) {
        String ip = s.getInetAddress().getHostAddress();
        Conn c = null;
        try {
            long[] f = fails.computeIfAbsent(ip, k -> new long[]{0, 0});
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            if (System.currentTimeMillis() < f[1]) { out.write("BLOCKED\n".getBytes()); out.flush(); s.close(); return; }
            if (conns.size() >= MAX_CLIENTS) { out.write("FULL\n".getBytes()); out.flush(); s.close(); return; }
            s.setSoTimeout(6000);
            byte[] nb = new byte[16]; rnd.nextBytes(nb);
            StringBuilder nh = new StringBuilder(); for (byte x : nb) nh.append(String.format("%02x", x));
            out.write(("HI " + nh + "\n").getBytes()); out.flush();
            String a = readLine(in, 200);
            boolean ok = a != null && a.startsWith("AUTH ") && a.substring(5).trim().equals(sha(nh + ":" + pin));
            if (!ok) {
                synchronized (f) { f[0]++; if (f[0] >= 5) { f[1] = System.currentTimeMillis() + 60000; f[0] = 0; } }
                out.write("NO\n".getBytes()); out.flush(); s.close(); return;
            }
            f[0] = 0;
            out.write("OK\n".getBytes()); out.flush();
            s.setSoTimeout(0); s.setTcpNoDelay(true);
            c = new Conn(ids.incrementAndGet(), s);
            conns.put(c.id, c);
            ev("{\"e\":\"conn\",\"id\":" + c.id + "}");
            readLoop(c, in);
        } catch (Exception e) { /* انقطاع */ }
        finally { if (c != null) { conns.remove(c.id); c.close(); ev("{\"e\":\"closed\",\"id\":" + c.id + "}"); } else { try { s.close(); } catch (IOException x) { /* */ } } }
    }

    private void readLoop(Conn c, InputStream in) throws IOException {
        String line;
        while ((line = readLine(in, MAX_LINE)) != null) ev("{\"e\":\"msg\",\"id\":" + c.id + ",\"line\":" + q(line) + "}");
    }

    /* ---------------- الضيف ---------------- */
    public void join(final String pinCode, final List<String> hosts, final List<String> broadcasts, final int tcpPort, final int udpPort) {
        new Thread(() -> {
            String last = "notfound";
            LinkedHashSet<String> tried = new LinkedHashSet<>();
            for (String h : hosts) {
                if (h == null || h.isEmpty() || !tried.add(h)) continue;
                String r = tryHost(h, pinCode, tcpPort);
                if (r == null) return;
                if (!r.equals("notfound")) { last = r; if (r.equals("pin") || r.equals("full") || r.equals("blocked")) { fail(last); return; } }
            }
            String found = discover(broadcasts, udpPort);
            if (found != null && tried.add(found)) {
                String r = tryHost(found, pinCode, tcpPort);
                if (r == null) return;
                last = r;
            }
            fail(last);
        }, "lan-join").start();
    }
    private void fail(String m) { ev("{\"e\":\"joinfail\",\"msg\":" + q(m) + "}"); }

    /** يعيد null عند النجاح، وإلا سبب الفشل */
    private String tryHost(String host, String pinCode, int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 2500);
            s.setSoTimeout(6000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String hi = readLine(in, 200);
            if (hi == null) { s.close(); return "notfound"; }
            if (hi.equals("FULL")) { s.close(); return "full"; }
            if (hi.equals("BLOCKED")) { s.close(); return "blocked"; }
            if (!hi.startsWith("HI ")) { s.close(); return "notfound"; }
            out.write(("AUTH " + sha(hi.substring(3).trim() + ":" + pinCode) + "\n").getBytes()); out.flush();
            String r = readLine(in, 50);
            if (r == null || !r.equals("OK")) { s.close(); return "pin"; }
            s.setSoTimeout(0); s.setTcpNoDelay(true);
            final Conn c = new Conn(ids.incrementAndGet(), s);
            conns.put(c.id, c);
            ev("{\"e\":\"joined\",\"id\":" + c.id + "}");
            new Thread(() -> {
                try { readLoop(c, in); } catch (IOException e) { /* */ }
                finally { conns.remove(c.id); c.close(); ev("{\"e\":\"closed\",\"id\":" + c.id + "}"); }
            }, "lan-read").start();
            return null;
        } catch (Exception e) { try { s.close(); } catch (IOException x) { /* */ } return "notfound"; }
    }

    private String discover(List<String> broadcasts, int udpPort) {
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setBroadcast(true); ds.setSoTimeout(1800);
            byte[] q = "DOMINO?".getBytes(StandardCharsets.UTF_8);
            for (String b : broadcasts) { try { ds.send(new DatagramPacket(q, q.length, InetAddress.getByName(b), udpPort)); } catch (Exception e) { /* */ } }
            byte[] buf = new byte[32];
            long end = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                ds.receive(p);
                if (new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).startsWith("DOMINO!")) return p.getAddress().getHostAddress();
            }
        } catch (Exception e) { /* */ }
        return null;
    }

    /* ---------------- مشترك ---------------- */
    public void send(int id, String line) { Conn c = conns.get(id); if (c != null && line != null && line.length() < MAX_LINE) c.write(line.replace("\n", " ")); }
    public void close(int id) { Conn c = conns.remove(id); if (c != null) c.close(); }
}
