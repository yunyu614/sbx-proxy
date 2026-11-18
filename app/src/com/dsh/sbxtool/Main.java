package com.dsh.sbxtool;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Main {
    static final String DATA = "/data/adb/sbx";

    public static void main(String[] a) throws Exception {
        String mode = a.length > 0 ? a[0] : "labels";
        if ("labels".equals(mode)) labels();
        else if ("subs".equals(mode)) subs();
    }

    static void labels() throws Exception {
        HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityThread;");
        if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object th = at.getMethod("systemMain").invoke(null);
        Context sys = (Context) at.getMethod("getSystemContext").invoke(th);
        Context ctx = sys.createPackageContext("android", 0);
        PackageManager pm = ctx.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (ApplicationInfo ai : apps) {
            if (ai.uid < 10000) continue;
            String label;
            try { label = String.valueOf(ai.loadLabel(pm)); } catch (Throwable e) { label = ai.packageName; }
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"uid\":").append(ai.uid)
              .append(",\"pkg\":").append(q(ai.packageName))
              .append(",\"label\":").append(q(label)).append("}");
        }
        sb.append("]");
        write(DATA + "/apps_labels.json", sb.toString());
        System.out.println("labels: " + (first ? 0 : sb.toString().split("\\{\"uid\"").length - 1));
    }

    static void subs() throws Exception {
        File f = new File(DATA + "/subs.txt");
        if (!f.exists()) { System.out.println("no subs.txt"); return; }
        List<String[]> subs = new ArrayList<>();
        for (String line : readAll(f)) {
            int i = line.indexOf('|');
            if (i > 0) subs.add(new String[]{line.substring(0, i), line.substring(i + 1)});
        }
        List<String> outbounds = new ArrayList<>();
        List<String> nodes = new ArrayList<>();
        int n = 0;
        for (String[] s : subs) {
            String body;
            try { body = resolveBody(fetch(s[1])); } catch (Throwable e) { System.out.println("fetch fail " + s[0] + ": " + e); continue; }
            List<Map<String, String>> proxies = parseProxies(body);
            if (proxies.isEmpty() && body.contains("://")) proxies = parseLinks(body);
            System.out.println("SUB|" + s[0] + "|" + proxies.size());
            for (Map<String, String> p : proxies) {
                String tag = "n" + (++n < 10 ? "0" : "") + n;
                String o = outbound(p, tag);
                if (o == null) continue;
                outbounds.add(o);
                nodes.add("{\"tag\":\"" + tag + "\",\"name\":" + q(p.get("name")) + ",\"sub\":" + q(s[0]) + "}");
            }
        }
        if (outbounds.isEmpty()) { System.out.println("no nodes"); return; }
        StringBuilder tags = new StringBuilder();
        for (String o : outbounds) {
            int i = o.indexOf("\"tag\":\"");
            String tag = o.substring(i + 7, o.indexOf('"', i + 7));
            if (tags.length() > 0) tags.append(",");
            tags.append("\"").append(tag).append("\"");
        }
        outbounds.add("{\"type\":\"selector\",\"tag\":\"proxy\",\"outbounds\":[\"auto\"," + tags + "],\"default\":\"auto\"}");
        outbounds.add("{\"type\":\"urltest\",\"tag\":\"auto\",\"outbounds\":[" + tags + "],\"url\":\"https://www.gstatic.com/generate_204\",\"interval\":\"10m\"}");
        outbounds.add("{\"type\":\"direct\",\"tag\":\"direct\"}");
        String cfg = "{\"log\":{\"level\":\"info\",\"timestamp\":true,\"output\":\"/data/adb/sbx/log/sing-box.log\"},"
            + "\"dns\":{\"servers\":[{\"type\":\"udp\",\"tag\":\"agh\",\"server\":\"__AGH_HOST__\",\"server_port\":__AGH_PORT__},"
            + "{\"type\":\"udp\",\"tag\":\"direct\",\"server\":\"223.5.5.5\"}],\"final\":\"agh\"},"
            + "\"inbounds\":[{\"type\":\"tun\",\"tag\":\"tun-in\",\"address\":[\"172.19.0.1/30\"],\"mtu\":9000,\"auto_route\":true,\"auto_redirect\":true,\"strict_route\":false}],"
            + "\"outbounds\":[" + String.join(",", outbounds) + "],"
            + "\"route\":{\"rules\":[{\"action\":\"sniff\",\"timeout\":\"300ms\"},{\"protocol\":\"dns\",\"action\":\"hijack-dns\"},{\"domain_suffix\":[\"connect.rom.miui.com\",\"connectivitycheck.gstatic.com\",\"connectivitycheck.android.com\"],\"outbound\":\"direct\"},\"__APP_RULES__\",\"__DIRECT_RULES__\",{\"ip_is_private\":true,\"outbound\":\"direct\"}],\"final\":\"__ROUTE_FINAL__\",\"auto_detect_interface\":true,\"default_domain_resolver\":{\"server\":\"agh\"},"
            + "\"rule_set\":[{\"type\":\"local\",\"tag\":\"geosite-cn\",\"format\":\"binary\",\"path\":\"/data/adb/sbx/rules/geosite-cn.srs\"},{\"type\":\"local\",\"tag\":\"geoip-cn\",\"format\":\"binary\",\"path\":\"/data/adb/sbx/rules/geoip-cn.srs\"}]},"
            + "\"experimental\":{\"clash_api\":{\"external_controller\":\"127.0.0.1:39090\"}}}";
        write(DATA + "/config.json", cfg);
        write(DATA + "/nodes.json", "[" + String.join(",", nodes) + "]");
        System.out.println("nodes: " + nodes.size());
    }

    static List<Map<String, String>> parseProxies(String yaml) {
        List<Map<String, String>> out = new ArrayList<>();
        String[] lines = yaml.split("\n", -1);
        boolean inP = false;
        Map<String, String> cur = null;
        java.util.ArrayDeque<String[]> keyStack = new java.util.ArrayDeque<>();
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            if (line.isEmpty()) continue;
            if (line.matches("^\\s*#.*")) continue;
            int peek = peekIndent(lines, li + 1);
            if (!inP) {
                if (line.matches("^proxies:\\s*$")) { inP = true; }
                continue;
            }
            if (line.matches("^[A-Za-z0-9_].*")) break;
            int indent = line.length() - line.replaceAll("^\\s+", "").length();
            String t = line.trim();
            if (t.equals("-")) {
                if (cur != null) out.add(cur);
                cur = new LinkedHashMap<>();
                keyStack.clear();
                continue;
            }
            if (t.startsWith("- ")) {
                String rest = t.substring(2).trim();
                if (cur != null) out.add(cur);
                cur = new LinkedHashMap<>();
                keyStack.clear();
                if (rest.startsWith("{")) {
                    cur = parseInlineMap(rest);
                } else {
                    kv(cur, rest, indent, keyStack, peek);
                }
                continue;
            }
            if (cur == null) continue;
            if (t.indexOf(':') > 0) kv(cur, t, indent, keyStack, peek);
        }
        if (cur != null) out.add(cur);
        List<Map<String, String>> filtered = new ArrayList<>();
        for (Map<String, String> m : out) {
            String nm = m.get("name");
            if (nm != null && nm.matches(".*(剩余|重置|到期|套餐|已用).*")) continue;
            filtered.add(m);
        }
        return filtered;
    }

    static int peekIndent(String[] lines, int from) {
        for (int i = from; i < lines.length; i++) {
            String l = lines[i];
            if (l == null || l.trim().isEmpty()) continue;
            if (l.matches("^\\s*#.*")) continue;
            return l.length() - l.replaceAll("^\\s+", "").length();
        }
        return -1;
    }

    static void kv(Map<String, String> m, String t, int indent, java.util.ArrayDeque<String[]> ks, int peek) {
        int c = t.indexOf(':');
        String k = t.substring(0, c).trim();
        String v = t.substring(c + 1).trim();
        if (v.equals("null") || v.equals("~")) v = "";
        if (v.isEmpty() && peek > indent) {
            while (!ks.isEmpty() && Integer.parseInt(ks.peek()[0]) >= indent) ks.pop();
            ks.push(new String[]{String.valueOf(indent), k});
            return;
        }
        if (v.isEmpty()) {
            StringBuilder prefix0 = new StringBuilder();
            java.util.Iterator<String[]> it0 = ks.descendingIterator();
            while (it0.hasNext()) {
                String[] e0 = it0.next();
                if (Integer.parseInt(e0[0]) < indent) prefix0.append(e0[1]).append(".");
            }
            m.put(prefix0 + k, "");
            return;
        }
        StringBuilder prefix = new StringBuilder();
        java.util.Iterator<String[]> it = ks.descendingIterator();
        while (it.hasNext()) {
            String[] e = it.next();
            if (Integer.parseInt(e[0]) < indent) prefix.append(e[1]).append(".");
        }
        if (v.length() > 1 && ((v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') ||
                (v.charAt(0) == '\'' && v.charAt(v.length() - 1) == '\'')))
            v = v.substring(1, v.length() - 1);
        m.put(prefix + k, v);
    }

    static Map<String, String> parseInlineMap(String s) {
        Map<String, String> obj = new LinkedHashMap<>();
        String body = s;
        if (body.startsWith("{") && body.endsWith("}")) body = body.substring(1, body.length() - 1);
        for (String p : splitInline(body)) {
            int k = p.indexOf(':');
            if (k < 0) continue;
            String key = p.substring(0, k).trim();
            String val = p.substring(k + 1).trim();
            if (val.length() > 1 && ((val.charAt(0) == '"' && val.charAt(val.length() - 1) == '"') ||
                    (val.charAt(0) == '\'' && val.charAt(val.length() - 1) == '\'')))
                val = val.substring(1, val.length() - 1);
            obj.put(key, val);
        }
        return obj;
    }

    static List<String> splitInline(String s) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote && (i == 0 || s.charAt(i - 1) != '\\')) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') { quote = c; cur.append(c); continue; }
            if (c == '{' || c == '[') depth++;
            if (c == '}' || c == ']') depth--;
            if (c == ',' && depth == 0) { parts.add(cur.toString()); cur = new StringBuilder(); continue; }
            cur.append(c);
        }
        if (cur.length() > 0) parts.add(cur.toString());
        return parts;
    }

    static String outbound(Map<String, String> p, String tag) {
        String type = p.get("type");
        String base = "\"tag\":\"" + tag + "\",\"server\":" + q(p.get("server")) + ",\"server_port\":" + num(p.get("port"));
        if ("ss".equals(type)) {
            return "{\"type\":\"shadowsocks\"," + base + ",\"method\":" + q(p.get("cipher")) + ",\"password\":" + q(p.get("password")) + "}";
        }
        if ("vmess".equals(type)) {
            StringBuilder b = new StringBuilder("{\"type\":\"vmess\"," + base + ",\"uuid\":" + q(p.get("uuid")) + ",\"alter_id\":" + num(p.getOrDefault("alterId", "0")) + ",\"security\":" + q(p.getOrDefault("cipher", "auto")));
            if ("true".equals(p.get("tls"))) b.append(",\"tls\":{\"enabled\":true,\"insecure\":false}");
            if ("ws".equals(p.get("network"))) b.append(",\"transport\":{\"type\":\"ws\",\"path\":" + q(p.getOrDefault("ws-path", "/")) + "}");
            b.append("}");
            return b.toString();
        }
        if ("vless".equals(type)) {
            StringBuilder b = new StringBuilder("{\"type\":\"vless\"," + base + ",\"uuid\":" + q(p.get("uuid")));
            String flow = p.get("flow");
            if (flow != null && !flow.isEmpty() && !flow.equals("none")) b.append(",\"flow\":").append(q(flow));
            if ("true".equals(p.get("tls"))) {
                String sn = p.get("servername");
                if (sn == null) sn = p.get("server");
                String rpk = p.get("reality-opts.public-key");
                String sid = p.get("reality-opts.short-id");
                b.append(",\"tls\":{\"enabled\":true,\"insecure\":").append("true".equals(p.get("skip-cert-verify"))).append(",\"server_name\":").append(q(sn));
                if (rpk != null && !rpk.isEmpty()) {
                    b.append(",\"reality\":{\"enabled\":true,\"public_key\":").append(q(rpk));
                    if (sid != null && !sid.isEmpty()) b.append(",\"short_id\":").append(q(sid));
                    b.append("},\"utls\":{\"enabled\":true,\"fingerprint\":\"chrome\"}");
                }
                b.append("}");
            }
            String net = p.get("network");
            if ("ws".equals(net)) b.append(",\"transport\":{\"type\":\"ws\",\"path\":").append(q(p.getOrDefault("ws-path", "/"))).append("}");
            else if ("grpc".equals(net)) b.append(",\"transport\":{\"type\":\"grpc\",\"service_name\":").append(q(p.getOrDefault("grpc-service-name", ""))).append("}");
            b.append("}");
            return b.toString();
        }
        if ("hysteria2".equals(type)) {
            StringBuilder b = new StringBuilder("{\"type\":\"hysteria2\"," + base + ",\"password\":" + q(p.get("password")));
            String sn = p.get("sni");
            if (sn == null) sn = p.get("servername");
            if (sn == null) sn = p.get("server");
            b.append(",\"tls\":{\"enabled\":true,\"insecure\":").append("true".equals(p.get("skip-cert-verify"))).append(",\"server_name\":").append(q(sn)).append("}");
            String obfs = p.get("obfs");
            if (obfs != null && !obfs.isEmpty() && !obfs.equals("none")) {
                b.append(",\"obfs\":{\"type\":").append(q(obfs));
                String op = p.get("obfs-password");
                if (op != null && !op.isEmpty()) b.append(",\"password\":").append(q(op));
                b.append("}");
            }
            b.append("}");
            return b.toString();
        }
        if ("trojan".equals(type)) {
            StringBuilder b = new StringBuilder("{\"type\":\"trojan\"," + base + ",\"password\":" + q(p.get("password")));
            if ("true".equals(p.get("tls"))) {
                String sn = p.get("sni");
                if (sn == null) sn = p.get("servername");
                if (sn == null) sn = p.get("server");
                b.append(",\"tls\":{\"enabled\":true,\"insecure\":").append("true".equals(p.get("skip-cert-verify"))).append(",\"server_name\":").append(q(sn)).append("}");
            }
            b.append("}");
            return b.toString();
        }
        return null;
    }

    static String wget(String url, String ua) throws IOException {
        java.io.File bb = new java.io.File("/data/adb/ksu/bin/busybox");
        String bbPath = bb.exists() ? bb.getAbsolutePath() : "busybox";
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(bbPath); cmd.add("wget"); cmd.add("-T"); cmd.add("40"); cmd.add("-qO-");
        if (ua != null) { cmd.add("--header"); cmd.add("User-Agent: " + ua); }
        cmd.add(url);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (InputStream in = proc.getInputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
        }
        try { proc.waitFor(); } catch (InterruptedException ignored) {}
        String body = new String(bo.toByteArray(), StandardCharsets.UTF_8);
        if (body.length() < 200) throw new IOException("wget empty, exit=" + proc.exitValue());
        return body;
    }

    static String fetch(String url) throws IOException {
        String body;
        try {
            body = wget(url, "clash-verge/2.0");
            if (!body.contains("proxies:") && !body.contains("://")) throw new IOException("not config");
            return body;
        } catch (Throwable t1) {
            return wget(url, null);
        }
    }

    static String resolveBody(String raw) {
        String t = raw.trim();
        if (t.contains("proxies:") || t.contains("://")) return t;
        try {
            String clean = t.replaceAll("\\s+", "");
            byte[] d = java.util.Base64.getDecoder().decode(clean);
            return new String(d, StandardCharsets.UTF_8);
        } catch (Throwable e) { return t; }
    }

    static List<Map<String, String>> parseLinks(String text) {
        List<Map<String, String>> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            try {
                Map<String, String> p = null;
                if (l.startsWith("vless://")) p = parseVless(l);
                else if (l.startsWith("hysteria2://")) p = parseHy2(l);
                else if (l.startsWith("vmess://")) p = parseVmess(l);
                else if (l.startsWith("trojan://")) p = parseTrojan(l);
                else if (l.startsWith("ss://")) p = parseSs(l);
                if (p != null && p.get("name") != null) out.add(p);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    static Map<String, String> parseUrl(String s) {
        int i = s.indexOf("://");
        String rest = s.substring(i + 3);
        int hash = rest.indexOf('#');
        String frag = hash >= 0 ? rest.substring(hash + 1) : "";
        if (hash >= 0) rest = rest.substring(0, hash);
        int q = rest.indexOf('?');
        String query = q >= 0 ? rest.substring(q + 1) : "";
        if (q >= 0) rest = rest.substring(0, q);
        int at = rest.lastIndexOf('@');
        String userinfo = at >= 0 ? rest.substring(0, at) : "";
        String hostport = at >= 0 ? rest.substring(at + 1) : rest;
        String host = hostport; int port = 443;
        if (hostport.startsWith("[")) {
            int b = hostport.indexOf(']');
            host = hostport.substring(1, b);
            int c = hostport.indexOf(':', b);
            if (c > 0) port = Integer.parseInt(hostport.substring(c + 1));
        } else {
            int c = hostport.lastIndexOf(':');
            if (c > 0) { host = hostport.substring(0, c); port = Integer.parseInt(hostport.substring(c + 1)); }
        }
        Map<String, String> p = new LinkedHashMap<>();
        p.put("name", dec(frag)); p.put("server", host); p.put("port", String.valueOf(port));
        if (!userinfo.isEmpty()) p.put("_user", dec(userinfo));
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            if (e > 0) p.put(dec(kv.substring(0, e)), dec(kv.substring(e + 1)));
        }
        return p;
    }

    static String dec(String s) {
        try { return java.net.URLDecoder.decode(s, "UTF-8"); } catch (Throwable e) { return s; }
    }

    static Map<String, String> parseVless(String l) {
        Map<String, String> p = parseUrl(l);
        p.put("type", "vless"); p.put("uuid", p.get("_user"));
        if (!"none".equals(p.get("flow")) && p.get("flow") != null) p.put("flow", p.get("flow"));
        String sec = p.get("security");
        if ("tls".equals(sec) || "reality".equals(sec)) {
            p.put("tls", "true");
            p.put("servername", p.get("sni") != null ? p.get("sni") : p.get("server"));
            p.put("skip-cert-verify", "1".equals(p.get("insecure")) ? "true" : "false");
            if ("reality".equals(sec)) {
                p.put("reality-opts.public-key", p.get("pbk"));
                p.put("reality-opts.short-id", p.get("sid"));
            }
        }
        String net = p.get("type"); if (net == null) net = "tcp"; p.put("network", net);
        if ("ws".equals(net)) p.put("ws-path", p.get("path") != null ? p.get("path") : "/");
        return p;
    }

    static Map<String, String> parseHy2(String l) {
        Map<String, String> p = parseUrl(l);
        p.put("type", "hysteria2"); p.put("password", p.get("_user"));
        p.put("sni", p.get("sni") != null ? p.get("sni") : p.get("server"));
        p.put("skip-cert-verify", ("1".equals(p.get("insecure")) || "true".equals(p.get("insecure"))) ? "true" : "false");
        return p;
    }

    static Map<String, String> parseTrojan(String l) {
        Map<String, String> p = parseUrl(l);
        p.put("type", "trojan"); p.put("password", p.get("_user"));
        p.put("tls", "true");
        p.put("sni", p.get("sni") != null ? p.get("sni") : p.get("server"));
        p.put("skip-cert-verify", "1".equals(p.get("insecure")) ? "true" : "false");
        return p;
    }

    static Map<String, String> parseVmess(String l) {
        String body = l.substring(8).trim();
        try {
            byte[] d = java.util.Base64.getDecoder().decode(body);
            String json = new String(d, StandardCharsets.UTF_8);
            Map<String, String> p = new LinkedHashMap<>();
            p.put("name", jget(json, "ps"));
            p.put("type", "vmess");
            p.put("server", jget(json, "add"));
            p.put("port", jget(json, "port"));
            p.put("uuid", jget(json, "id"));
            p.put("alterId", jget(json, "aid"));
            p.put("cipher", jget(json, "scy").isEmpty() ? "auto" : jget(json, "scy"));
            String net = jget(json, "net"); p.put("network", net.isEmpty() ? "tcp" : net);
            String tls = jget(json, "tls");
            if ("tls".equals(tls)) p.put("tls", "true");
            String host = jget(json, "host");
            if (host != null && !host.isEmpty()) p.put("ws-host", host);
            String path = jget(json, "path");
            if (path != null && !path.isEmpty()) p.put("ws-path", path);
            return p;
        } catch (Throwable e) { return null; }
    }

    static String jget(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (m.find()) return m.group(1);
        java.util.regex.Matcher n = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9]+)").matcher(json);
        if (n.find()) return n.group(1);
        return "";
    }

    static Map<String, String> parseSs(String l) {
        String body = l.substring(5).trim();
        int hash = body.indexOf('#');
        String frag = hash >= 0 ? body.substring(hash + 1) : "";
        if (hash >= 0) body = body.substring(0, hash);
        boolean legacy = body.indexOf('@') < 0;
        String userinfo, hostport;
        if (legacy) {
            String dec = new String(java.util.Base64.getDecoder().decode(body.trim()), StandardCharsets.UTF_8);
            int at = dec.indexOf('@');
            userinfo = dec.substring(0, at); hostport = dec.substring(at + 1);
        } else {
            int at = body.indexOf('@');
            userinfo = body.substring(0, at); hostport = body.substring(at + 1);
            if (!userinfo.contains(":")) {
                userinfo = new String(java.util.Base64.getDecoder().decode(userinfo), StandardCharsets.UTF_8);
            }
        }
        String[] hp = hostport.split(":");
        Map<String, String> p = new LinkedHashMap<>();
        p.put("name", dec(frag)); p.put("type", "ss");
        p.put("server", hp[0]); p.put("port", hp[1]);
        String[] mp = userinfo.split(":", 2);
        p.put("cipher", mp[0]); p.put("password", mp.length > 1 ? mp[1] : "");
        return p;
    }

    static String q(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default: b.append(c);
            }
        }
        return b.append("\"").toString();
    }

    static int num(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    static void write(String path, String content) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }

    static List<String> readAll(File f) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) lines.add(l);
        }
        return lines;
    }
}
