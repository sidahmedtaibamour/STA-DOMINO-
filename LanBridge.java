package com.sta.domino;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** الجسر بين لعبة الويب (JavaScript) والاتصال المحلي. يُعرَض للصفحة باسم AndroidLAN. */
public class LanBridge {
    private final Context ctx;
    private final MainActivity act;
    private final WebView web;
    private final LanCore core;

    public LanBridge(MainActivity c, WebView w) {
        act = c;
        ctx = c.getApplicationContext();
        web = w;
        core = new LanCore(new LanCore.Sink() {
            @Override public void event(final String json) {
                web.post(new Runnable() {
                    @Override public void run() { web.evaluateJavascript("window.__lan&&window.__lan(" + json + ")", null); }
                });
            }
        });
    }

    public void shutdown() { core.stopHost(); }

    private static String ip(int v) { return (v & 0xff) + "." + ((v >> 8) & 0xff) + "." + ((v >> 16) & 0xff) + "." + ((v >> 24) & 0xff); }

    private DhcpInfo dhcp() {
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            return wm == null ? null : wm.getDhcpInfo();
        } catch (Exception e) { return null; }
    }

    private List<String> localIps() {
        List<String> out = new ArrayList<String>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (Exception e) { /* */ }
        return out;
    }

    /** معلومات الشبكة للعرض: {"ips":[...],"gw":"..."} */
    @JavascriptInterface
    public String info() {
        StringBuilder b = new StringBuilder("{\"ips\":[");
        List<String> ips = localIps();
        for (int i = 0; i < ips.size(); i++) { if (i > 0) b.append(','); b.append('"').append(ips.get(i)).append('"'); }
        b.append("],\"gw\":\"");
        DhcpInfo d = dhcp();
        if (d != null && d.gateway != 0) b.append(ip(d.gateway));
        return b.append("\"}").toString();
    }

    @JavascriptInterface
    public void startHost(String pin) {
        if (pin == null || !pin.matches("[0-9]{4,8}")) { return; }
        core.startHost(pin, LanCore.TCP_PORT, LanCore.UDP_PORT);
    }

    @JavascriptInterface
    public void stopHost() { core.stopHost(); }

    @JavascriptInterface
    public void join(String pin, String manualIp) {
        if (pin == null || !pin.matches("[0-9]{4,8}")) return;
        List<String> hosts = new ArrayList<String>();
        if (manualIp != null && manualIp.matches("\\d{1,3}(\\.\\d{1,3}){3}")) hosts.add(manualIp);
        List<String> bc = new ArrayList<String>();
        bc.add("255.255.255.255");
        DhcpInfo d = dhcp();
        if (d != null) {
            if (d.gateway != 0) hosts.add(ip(d.gateway));
            if (d.ipAddress != 0 && d.netmask != 0) bc.add(ip((d.ipAddress & d.netmask) | ~d.netmask));
        }
        core.join(pin, hosts, bc, LanCore.TCP_PORT, LanCore.UDP_PORT);
    }

    /** تُستدعى من اللعبة: true عند بدء مباراة، false عند الخروج. */
    @JavascriptInterface
    public void setPlaying(final boolean on) {
        act.runOnUiThread(new Runnable() { @Override public void run() { act.setPlaying(on); } });
    }

    @JavascriptInterface
    public void send(int id, String line) { core.send(id, line); }

    @JavascriptInterface
    public void close(int id) { core.close(id); }
}
