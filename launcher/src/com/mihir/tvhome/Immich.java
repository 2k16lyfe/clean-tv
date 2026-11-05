package com.mihir.tvhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Small read-only Immich client. It lists albums and people, finds photo ids and downloads preview
 * pictures. It never changes anything on the server. Plain Java so it can be tested off the TV.
 */
final class Immich {

    static final class Failure extends Exception {
        final int code;

        Failure(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    static final class Album {
        String id = "", name = "", cover = "";
        int count;
    }

    static final class Person {
        String id = "", name = "";
    }

    /** What to look for. Filters combine with AND inside one query. */
    static final class Spec {
        boolean favorite;
        final List<String> albums = new ArrayList<>();
        final List<String> people = new ArrayList<>();
    }

    static final class Page {
        final List<String> ids = new ArrayList<>();
        String next; // opaque token for the following page, or null at the end
    }

    static final class Result {
        final List<String> ids = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
    }

    interface Progress {
        void say(String line);
    }

    /** Photo ids are used in URLs and file names, so only plain id characters are accepted. */
    private static final Pattern ID = Pattern.compile("[0-9A-Za-z-]{8,64}");
    private static final int MAX_BODY = 40 * 1024 * 1024;

    private final String base; // like http://host:2283/api
    private final String key;
    private final boolean insecure;
    private int shape = -1; // search format that worked: 0 flat, 1 filter with plain arrays, 2 filter with {any}
    private int[] version;

    Immich(String address, String key, boolean insecure) {
        this.base = normalize(address) + "/api";
        this.key = key == null ? "" : key.trim();
        this.insecure = insecure;
    }

    static String normalize(String address) {
        String a = address == null ? "" : address.trim();
        if (a.isEmpty()) return "";
        if (!a.matches("(?i)^https?://.*")) a = "http://" + a;
        while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        if (a.toLowerCase().endsWith("/api")) a = a.substring(0, a.length() - 4);
        while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        return a;
    }

    static boolean validId(String s) {
        return s != null && ID.matcher(s).matches();
    }

    int shape() {
        return shape;
    }

    void setShape(int s) {
        shape = s;
    }

    // ------------------------------------------------------------------ http

    private static final class Resp {
        int code;
        byte[] body = new byte[0];
        String type = "";
    }

    private Resp call(String method, String path, String json, int readMs, String accept) throws Failure {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + path).openConnection();
            if (insecure && c instanceof HttpsURLConnection) trustAll((HttpsURLConnection) c);
            c.setInstanceFollowRedirects(false); // the key must never follow a redirect to another host
            c.setConnectTimeout(8000);
            c.setReadTimeout(readMs);
            c.setRequestMethod(method);
            c.setRequestProperty("x-api-key", key);
            c.setRequestProperty("Accept", accept);
            if (json != null) {
                byte[] b = json.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(b);
                }
            }
            Resp r = new Resp();
            r.code = c.getResponseCode();
            r.type = c.getContentType() == null ? "" : c.getContentType();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                try (InputStream i = in) {
                    r.body = readAll(i);
                }
            }
            return r;
        } catch (java.net.SocketTimeoutException e) {
            throw new Failure(0, "The server took too long to answer");
        } catch (java.net.UnknownHostException e) {
            throw new Failure(0, "Could not find the server. Check the address");
        } catch (java.net.ConnectException e) {
            throw new Failure(0, "Could not connect. Is the server on and the port right?");
        } catch (IOException e) {
            throw new Failure(0, "Network problem: " + (e.getMessage() == null ? "unknown" : e.getMessage()));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BODY) throw new IOException("Reply too large");
        }
        return out.toByteArray();
    }

    private static void trustAll(HttpsURLConnection c) {
        try {
            TrustManager[] tm = {new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String t) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String t) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tm, new SecureRandom());
            c.setSSLSocketFactory(ctx.getSocketFactory());
            c.setHostnameVerifier((h, s) -> true);
        } catch (Exception ignored) {
        }
    }

    private static String text(Resp r) {
        return new String(r.body, StandardCharsets.UTF_8);
    }

    /** A readable reason from an error reply, without ever echoing the key. */
    private Failure failure(Resp r) {
        String msg = "";
        try {
            String t = text(r).trim();
            if (t.startsWith("{")) {
                Object m = new JSONObject(t).opt("message");
                if (m instanceof JSONArray) {
                    JSONArray a = (JSONArray) m;
                    msg = a.length() > 0 ? String.valueOf(a.opt(0)) : "";
                } else if (m != null) {
                    msg = String.valueOf(m);
                }
            }
        } catch (JSONException ignored) {
        }
        if (r.code == 401) msg = "The API key was rejected";
        else if (r.code == 403) msg = msg.isEmpty() ? "The API key is missing a permission" : msg;
        else if (r.code >= 300 && r.code < 400) msg = "The server redirected the request. Use the address your browser ends up on";
        if (msg.isEmpty()) msg = "Immich answered with code " + r.code;
        return new Failure(r.code, msg.replace(key, "***"));
    }

    private String getText(String path) throws Failure {
        Resp r = call("GET", path, null, 20000, "application/json");
        if (r.code < 200 || r.code >= 300) throw failure(r);
        return text(r);
    }

    // ------------------------------------------------------------------ info

    int[] version() throws Failure {
        if (version != null) return version;
        try {
            JSONObject o = new JSONObject(getText("/server/version"));
            version = new int[]{o.optInt("major"), o.optInt("minor"), o.optInt("patch")};
        } catch (JSONException e) {
            version = new int[]{0, 0, 0};
        }
        return version;
    }

    /** Checks the address and key. Returns the server version like "3.3.3". */
    String test() throws Failure {
        int[] v = version();
        Spec s = new Spec();
        search("/search/metadata", s, 1, null, false);
        return v[0] + "." + v[1] + "." + v[2];
    }

    List<Album> albums() throws Failure {
        List<Album> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(getText("/albums"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || !validId(o.optString("id"))) continue;
                Album al = new Album();
                al.id = o.optString("id");
                al.name = o.optString("albumName", "Album");
                String cv = o.optString("albumThumbnailAssetId", "");
                al.cover = validId(cv) ? cv : "";
                al.count = o.optInt("assetCount", 0);
                if (al.count > 0) out.add(al);
            }
        } catch (JSONException e) {
            throw new Failure(0, "Immich sent an album list this app cannot read");
        }
        return out;
    }

    /** People with a name, most useful first. Unnamed faces are left out. */
    List<Person> people(int max) throws Failure {
        List<Person> out = new ArrayList<>();
        try {
            for (int page = 1; page <= 6 && out.size() < max; page++) {
                String t = getText("/people?size=200&page=" + page).trim();
                JSONArray a;
                boolean more = false;
                if (t.startsWith("[")) {
                    a = new JSONArray(t);
                } else {
                    JSONObject o = new JSONObject(t);
                    a = o.optJSONArray("people");
                    more = o.optBoolean("hasNextPage", false);
                }
                if (a == null) break;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null || !validId(o.optString("id"))) continue;
                    String name = o.optString("name", "").trim();
                    if (name.isEmpty() || o.optBoolean("isHidden", false)) continue;
                    Person p = new Person();
                    p.id = o.optString("id");
                    p.name = name;
                    out.add(p);
                }
                if (!more) break;
            }
        } catch (JSONException e) {
            throw new Failure(0, "Immich sent a people list this app cannot read");
        }
        return out;
    }

    // ------------------------------------------------------------------ search

    private int[] ladder() {
        try {
            int[] v = version();
            if (v[0] > 3 || (v[0] == 3 && v[1] >= 2)) return new int[]{1, 2, 0};
        } catch (Failure ignored) {
        }
        return new int[]{0, 1, 2};
    }

    private static JSONObject obj(String k, Object v) throws JSONException {
        return new JSONObject().put(k, v);
    }

    private String body(int sh, Spec s, int size, String token, boolean random) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("size", size);
        if (sh == 0) {
            o.put("type", "IMAGE");
            o.put("visibility", "timeline");
            if (s.favorite) o.put("isFavorite", true);
            if (!s.albums.isEmpty()) o.put("albumIds", new JSONArray(s.albums));
            if (!s.people.isEmpty()) o.put("personIds", new JSONArray(s.people));
        } else {
            JSONObject f = new JSONObject();
            f.put("type", obj("eq", "IMAGE"));
            f.put("visibility", obj("eq", "timeline"));
            f.put("trashedAt", obj("eq", JSONObject.NULL));
            if (s.favorite) f.put("isFavorite", sh == 1 ? (Object) obj("eq", true) : (Object) Boolean.TRUE);
            if (!s.albums.isEmpty()) {
                f.put("albumIds", sh == 1 ? (Object) new JSONArray(s.albums) : (Object) obj("any", new JSONArray(s.albums)));
            }
            if (!s.people.isEmpty()) f.put("personIds", obj("any", new JSONArray(s.people)));
            o.put("filter", f);
        }
        if (!random && token != null) {
            if (token.startsWith("p:")) o.put("page", Integer.parseInt(token.substring(2)));
            else if (token.startsWith("c:")) o.put("cursor", token.substring(2));
        }
        return o.toString();
    }

    private static String nextToken(JSONObject o, JSONObject assets) {
        String[] names = {"nextCursor", "nextPage"};
        for (JSONObject src : new JSONObject[]{o, assets}) {
            if (src == null) continue;
            for (String n : names) {
                if (!src.has(n) || src.isNull(n)) continue;
                String v = String.valueOf(src.opt(n)).trim();
                if (v.isEmpty() || v.equals("null")) continue;
                return (n.equals("nextCursor") ? "c:" : "p:") + v;
            }
        }
        return null;
    }

    /** One page of search results. Works out which request format this server wants. */
    Page search(String path, Spec s, int size, String token, boolean random) throws Failure {
        int[] order = shape >= 0 ? new int[]{shape} : ladder();
        Failure last = null;
        for (int sh : order) {
            try {
                Resp r = call("POST", path, body(sh, s, size, token, random), 30000, "application/json");
                if (r.code >= 200 && r.code < 300) {
                    Page p = parsePage(r);
                    shape = sh;
                    return p;
                }
                Failure f = failure(r);
                if (r.code == 400 || r.code == 422) {
                    last = f;
                    continue;
                }
                throw f;
            } catch (JSONException | NumberFormatException e) {
                throw new Failure(0, "Immich sent a reply this app cannot read");
            }
        }
        if (order.length == 1) {
            // The format we remembered no longer works (server updated?). Try all of them again.
            shape = -1;
            return search(path, s, size, token, random);
        }
        throw last != null ? last : new Failure(0, "Immich did not accept the search");
    }

    private static Page parsePage(Resp r) throws JSONException {
        String t = text(r).trim();
        Page p = new Page();
        JSONArray items;
        JSONObject o = null, assets = null;
        if (t.startsWith("[")) {
            items = new JSONArray(t);
        } else {
            o = new JSONObject(t);
            assets = o.optJSONObject("assets");
            items = assets != null ? assets.optJSONArray("items") : o.optJSONArray("items");
            if (items == null) items = o.optJSONArray("assets");
        }
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                String id = it.optString("id");
                String type = it.optString("type", "IMAGE");
                if (validId(id) && (type.isEmpty() || type.equalsIgnoreCase("IMAGE"))) p.ids.add(id);
            }
        }
        if (o != null) p.next = nextToken(o, assets);
        return p;
    }

    private List<String> all(Spec s, int cap) throws Failure {
        List<String> out = new ArrayList<>();
        String token = null;
        for (int pages = 0; pages < 60 && out.size() < cap; pages++) {
            Page p = search("/search/metadata", s, Math.min(250, cap), token, false);
            out.addAll(p.ids);
            token = p.next;
            if (token == null || p.ids.isEmpty()) break;
        }
        return out.size() > cap ? new ArrayList<>(out.subList(0, cap)) : out;
    }

    private List<String> albumFallback(String albumId, int cap) throws Failure {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(getText("/albums/" + albumId)).optJSONArray("assets");
            for (int i = 0; a != null && i < a.length() && out.size() < cap; i++) {
                JSONObject it = a.optJSONObject(i);
                if (it != null && validId(it.optString("id")) && "IMAGE".equalsIgnoreCase(it.optString("type", "IMAGE"))) {
                    out.add(it.optString("id"));
                }
            }
        } catch (JSONException e) {
            throw new Failure(0, "Immich sent an album this app cannot read");
        }
        return out;
    }

    /**
     * Builds the list of photos to keep on the TV. Each chosen source gets a fair share, picked photos
     * always come along, and one source failing does not stop the others.
     */
    Result collect(boolean library, boolean favorites, List<String> albums, List<String> people,
                   List<String> picks, int max, Progress progress) throws Failure {
        Result res = new Result();
        Random rnd = new Random();
        List<List<String>> lists = new ArrayList<>();
        int sources = 0, failed = 0;
        Failure firstFailure = null;
        int cap = Math.min(2000, Math.max(max, 50));

        if (library) {
            sources++;
            say(progress, "Picking random photos from your library");
            try {
                Page p = search("/search/random", new Spec(), Math.min(cap, 1000), null, true);
                lists.add(p.ids);
            } catch (Failure f) {
                failed++;
                if (firstFailure == null) firstFailure = f;
                res.warnings.add("Library: " + f.getMessage());
            }
        }
        if (favorites) {
            sources++;
            say(progress, "Finding your favorites");
            try {
                Spec s = new Spec();
                s.favorite = true;
                lists.add(search("/search/random", s, Math.min(cap, 1000), null, true).ids);
            } catch (Failure f) {
                failed++;
                if (firstFailure == null) firstFailure = f;
                res.warnings.add("Favorites: " + f.getMessage());
            }
        }
        for (String id : albums) {
            if (!validId(id)) continue;
            sources++;
            say(progress, "Reading an album");
            try {
                Spec s = new Spec();
                s.albums.add(id);
                List<String> got;
                try {
                    got = all(s, cap);
                } catch (Failure f) {
                    if (f.code != 400 && f.code != 422) throw f;
                    got = albumFallback(id, cap);
                }
                lists.add(got);
            } catch (Failure f) {
                failed++;
                if (firstFailure == null) firstFailure = f;
                res.warnings.add("Album: " + f.getMessage());
            }
        }
        for (String id : people) {
            if (!validId(id)) continue;
            sources++;
            say(progress, "Finding photos of a person");
            try {
                Spec s = new Spec();
                s.people.add(id);
                lists.add(all(s, cap));
            } catch (Failure f) {
                failed++;
                if (firstFailure == null) firstFailure = f;
                res.warnings.add("Person: " + f.getMessage());
            }
        }

        Set<String> out = new LinkedHashSet<>();
        for (String p : picks) if (validId(p)) out.add(p);
        int budget = Math.max(0, max - out.size());
        for (List<String> l : lists) Collections.shuffle(l, rnd);
        boolean progressed = true;
        int[] at = new int[lists.size()];
        while (budget > 0 && progressed) {
            progressed = false;
            for (int i = 0; i < lists.size() && budget > 0; i++) {
                List<String> l = lists.get(i);
                while (at[i] < l.size()) {
                    String id = l.get(at[i]++);
                    if (out.add(id)) {
                        budget--;
                        progressed = true;
                        break;
                    }
                }
            }
        }
        res.ids.addAll(out);
        if (sources > 0 && failed == sources && picks.isEmpty() && firstFailure != null) throw firstFailure;
        return res;
    }

    private static void say(Progress p, String s) {
        if (p != null) p.say(s);
    }

    // ------------------------------------------------------------------ pictures

    private byte[] image(String path, int readMs) throws Failure {
        Resp r = call("GET", path, null, readMs, "image/*,*/*;q=0.5");
        if (r.code < 200 || r.code >= 300) throw failure(r);
        if (!r.type.toLowerCase().startsWith("image/")) throw new Failure(0, "Immich did not send a picture");
        return r.body;
    }

    /** A 1440 px preview, large enough for a 1080p screen. Used for streaming: nothing is written to disk. */
    byte[] preview(String assetId) throws Failure {
        if (!validId(assetId)) throw new Failure(0, "Bad photo id");
        return image("/assets/" + assetId + "/thumbnail?size=preview", 60000);
    }

    /** Same preview, saved only when the whole file arrived. */
    void downloadPreview(String assetId, File out) throws Failure {
        byte[] b = preview(assetId);
        File tmp = new File(out.getPath() + ".part");
        try (FileOutputStream f = new FileOutputStream(tmp)) {
            f.write(b);
        } catch (IOException e) {
            tmp.delete();
            throw new Failure(0, "Could not save a photo on the TV");
        }
        if (!tmp.renameTo(out)) {
            tmp.delete();
            throw new Failure(0, "Could not save a photo on the TV");
        }
    }

    byte[] thumbnail(String assetId) throws Failure {
        if (!validId(assetId)) throw new Failure(0, "Bad photo id");
        return image("/assets/" + assetId + "/thumbnail?size=thumbnail", 30000);
    }

    byte[] personThumbnail(String personId) throws Failure {
        if (!validId(personId)) throw new Failure(0, "Bad person id");
        return image("/people/" + personId + "/thumbnail", 30000);
    }
}
