package dev.ushio.player;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class FnClient {
    private static final String V1 = "/v/api/v1";
    private static final String PASSWORD_LOGIN = "/v/api/v2/user/loginByPassword";
    private static final String CONNECT_API_KEY = "zIGtkc3dqZnJpd29qZXJqa2w7c";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    // Protocol identifiers published by the reference desktop client.
    private static final String LEGACY_KEY = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh";
    private static final String LEGACY_SECRET = "16CCEB3D-AB42-077D-36A1-F355324E4237";
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(28, TimeUnit.SECONDS).followRedirects(false).build();

    static final class Session {
        final String base, username, token;
        final boolean signed, legacy, relay;
        final String relayId;

        Session(String base, String username, String token, boolean signed, boolean legacy) {
            this(base, username, token, signed, legacy, false);
        }

        Session(String base, String username, String token, boolean signed, boolean legacy, boolean relay) {
            this(base, username, token, signed, legacy, relay, "");
        }

        Session(String base, String username, String token, boolean signed, boolean legacy,
                boolean relay, String relayId) {
            this.base = base;
            this.username = username;
            this.token = token;
            this.signed = signed;
            this.legacy = legacy;
            this.relay = relay;
            this.relayId = relayId;
        }
    }

    static final class ApiError extends IOException {
        final int code;
        ApiError(String message, int code) { super(message); this.code = code; }
    }

    static final class Page {
        final List<Title> titles;
        final boolean more;
        Page(List<Title> titles, boolean more) { this.titles = titles; this.more = more; }
    }

    static final class Source {
        final String name, url;
        final boolean hls;
        final Map<String, String> headers;
        Source(String name, String url, boolean hls, Map<String, String> headers) {
            this.name = name;
            this.url = url;
            this.hls = hls;
            this.headers = headers;
        }
    }

    static final class Video {
        final JSONObject info;
        final List<Source> sources;
        final List<JSONObject> subtitles;
        final String item, media, filename;
        final long position, opening;
        Video(JSONObject info, List<Source> sources, List<JSONObject> subtitles, String filename) {
            this.info = info;
            this.sources = sources;
            this.subtitles = subtitles;
            this.filename = filename;
            item = info.optString("guid");
            media = info.optString("media_guid");
            position = Math.max(0, info.optLong("ts")) * 1000;
            JSONObject config = info.optJSONObject("play_config");
            opening = config == null ? 0 : Math.max(0, config.optLong("skip_opening")) * 1000;
        }
    }

    final Session session;

    FnClient(Session session) { this.session = session; }

    static JSONObject json(Object... pairs) {
        JSONObject value = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
        } catch (org.json.JSONException malformed) {
            throw new IllegalArgumentException(malformed);
        }
        return value;
    }

    static String hash(String kind, String text) {
        try {
            byte[] bytes = MessageDigest.getInstance(kind).digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b & 255) >>> 4, 16)).append(Character.forDigit(b & 15, 16));
            }
            return hex.toString();
        } catch (Exception unsupported) {
            throw new IllegalStateException(unsupported);
        }
    }

    static String base(String value, boolean nas) throws IOException {
        String input = value.trim();
        if (input.isEmpty()) throw new IOException(nas ? "请输入飞牛影视地址" : "请输入弹幕服务地址");
        if (!input.contains("://")) input = "http://" + input;
        HttpUrl parsed = HttpUrl.parse(input);
        if (parsed == null || !parsed.username().isEmpty() || !parsed.password().isEmpty()
                || parsed.query() != null || parsed.fragment() != null) {
            throw new IOException("地址格式无效，请输入 HTTP 或 HTTPS 服务地址");
        }
        String result = parsed.toString().replaceFirst("/+$", "");
        if (nas) result = result.replaceFirst("/v(?:/api/v[12])?$", "");
        else result = result.replaceFirst("/api/v2$", "");
        return result;
    }

    static boolean looksLikeFnId(String value) {
        String id = value == null ? "" : value.trim();
        return id.matches("[A-Za-z0-9_-]{6,30}") && !id.contains(".");
    }

    static String segment(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String nonce() {
        return Integer.toString(ThreadLocalRandom.current().nextInt(100000, 1000000));
    }

    private static String resolveFnId(String fnId) throws Exception {
        String body = json("fnId", fnId).toString();
        Exception last = null;
        for (String origin : new String[]{"https://fnos.net", "https://5ddd.com"}) {
            try {
                String path = "/api/v1/fn/con";
                String random = nonce();
                String timestamp = Long.toString(System.currentTimeMillis());
                String authx = "nonce=" + random + "&timestamp=" + timestamp + "&sign=" +
                        hash("MD5", String.join("_", LEGACY_KEY, path, random, timestamp,
                                hash("MD5", body), CONNECT_API_KEY));
                Request request = new Request.Builder().url(origin + path)
                        .post(RequestBody.create(body, JSON))
                        .header("Authx", authx)
                        .header("fn-sign", hash("SHA-256", "trim_connect" + ((char)96) + fnId + ((char)96) + timestamp + ((char)96) + "anna"))
                        .header("Content-Type", "application/json")
                        .header("Origin", "https://fnos.net")
                        .header("Referer", "https://fnos.net/" + fnId)
                        .build();
                JSONObject envelope = read(HTTP, request);
                if (envelope.optInt("code", -1) != 0) throw new ApiError(
                        Title.first(envelope, "msg", "message"), envelope.optInt("code", -1));
                List<String> candidates = new ArrayList<>();
                collectAddresses(envelope.opt("data"), candidates);
                for (String candidate : candidates) {
                    String normalized = normalizeCandidate(candidate);
                    if (!normalized.isEmpty() && activateRelay(normalized, fnId)) return normalized;
                }
                throw new IOException("FN ID 没有返回可用的连接地址");
            } catch (Exception error) {
                last = error;
            }
        }
        throw last == null ? new IOException("FN ID 解析失败") : last;
    }

    private static void collectAddresses(Object value, List<String> result) {
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (!text.isEmpty() && (text.contains(".") || text.contains(":"))) result.add(text);
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray values = (JSONArray) value;
            for (int i = 0; i < values.length(); i++) collectAddresses(values.opt(i), result);
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (String key : new String[]{"fn", "ddns", "publicIpv4", "publicIpv6", "ipv4", "ipv6", "host", "hostname", "url"}) {
                collectAddresses(object.opt(key), result);
            }
        }
    }

    private static String normalizeCandidate(String candidate) {
        String value = candidate.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            HttpUrl parsed = HttpUrl.parse(value);
            return parsed == null ? "" : parsed.scheme() + "://" + parsed.host() +
                    (parsed.port() == 443 || parsed.port() == 80 ? "" : ":" + parsed.port());
        }
        return "https://" + value;
    }

    private static boolean activateRelay(String base, String fnId) {
        try {
            Request request = new Request.Builder().url(base + "/v")
                    .header("Cookie", "mode=relay")
                    .header("Origin", "https://fnos.net")
                    .header("Referer", "https://fnos.net/" + fnId)
                    .build();
            try (Response response = HTTP.newBuilder().followRedirects(true).build().newCall(request).execute()) {
                return response.isSuccessful() && response.body() != null;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private String auth(String path, String body) {
        String random = nonce();
        String time = Long.toString(System.currentTimeMillis());
        String input = session.signed
                ? String.join("_", LEGACY_KEY, path, random, time, hash("MD5", body), LEGACY_SECRET)
                : random + time + session.token;
        return "nonce=" + random + "&timestamp=" + time + "&sign=" + hash("MD5", input);
    }

    private Request.Builder authorize(Request.Builder builder, String path, String body) {
        builder.header("Authx", auth(path, body))
                .header("Cookie", "mode=relay")
                .header("Authorization", session.token)
                .header("X-Trim-Client", "web")
                .header("X-Trim-Client-Version", "631");
        if (session.relay) {
            builder.header("Origin", "https://fnos.net")
                    .header("Referer", "https://fnos.net/" + session.relayId);
        }
        return builder;
    }

    private Object request(String path, JSONObject payload) throws Exception {
        String body = payload == null ? "" : payload.toString();
        if (payload != null && session.legacy) {
            payload.put("nonce", nonce());
            body = payload.toString();
        }
        int queryStart = path.indexOf('?');
        String route = queryStart < 0 ? path : path.substring(0, queryStart);
        String signedData = queryStart < 0 || payload != null ? body
                : URLDecoder.decode(path.substring(queryStart + 1), "UTF-8");
        Request.Builder builder = authorize(new Request.Builder().url(session.base + path), route, signedData);
        if (payload != null) builder.post(RequestBody.create(body, JSON));
        JSONObject response = read(HTTP, builder.build());
        int code = response.optInt("code", -1);
        if (code != 0) {
            String message = Title.first(response, "msg", "message");
            throw new ApiError(message.isEmpty() ? "飞牛影视请求失败 (" + code + ")" : message, code);
        }
        return response.opt("data");
    }

    static Session login(String address, String user, String password) throws Exception {
        String rawAddress = address == null ? "" : address.trim();
        boolean relay = looksLikeFnId(rawAddress);
        String relayId = relay ? rawAddress : "";
        String base = relay ? resolveFnId(relayId) : base(rawAddress, true);
        if (user.trim().isEmpty() || password.isEmpty()) throw new IOException("请输入账号和密码");
        String username = user.trim();
        JSONObject payload = json("username", username, "password", hash("SHA-256", password), "app_name", "trimemedia-web");
        Session web = new Session(base, username, "", true, false, relay, relayId);
        Session authenticated;
        try {
            authenticated = withToken(web, new FnClient(web).request(PASSWORD_LOGIN, payload));
        } catch (ApiError error) {
            if (missing(error)) return legacyLogin(base, username, password);
            if (!signatureError(error)) throw error;
            Session token = new Session(base, username, "", false, false, relay, relayId);
            try {
                authenticated = withToken(token, new FnClient(token).request(PASSWORD_LOGIN, payload));
            } catch (ApiError retry) {
                if (missing(retry) || signatureError(retry)) return legacyLogin(base, username, password);
                throw retry;
            }
        }
        return verifySession(authenticated);
    }

    private static Session verifySession(Session session) throws Exception {
        try {
            new FnClient(session).libraries();
            return session;
        } catch (ApiError error) {
            if (!signatureError(error)) throw error;
            Session alternate = new Session(session.base, session.username, session.token, !session.signed,
                    session.legacy, session.relay, session.relayId);
            new FnClient(alternate).libraries();
            return alternate;
        }
    }

    private static Session legacyLogin(String base, String username, String password) throws Exception {
        Session legacy = new Session(base, username, "", true, true, false);
        JSONObject legacyPayload = json("app_name", "trimemedia-web", "username", username, "password", password);
        return verifySession(withToken(legacy, new FnClient(legacy).request(V1 + "/login", legacyPayload)));
    }

    private static boolean missing(ApiError error) {
        return error.code == 404 || error.code == 405 || error.code == 501
                || error.getMessage().toLowerCase(Locale.ROOT).contains("not found");
    }

    private static boolean signatureError(ApiError error) {
        String message = error.getMessage().toLowerCase(Locale.ROOT);
        return error.code == 5000 || message.contains("invalid sign")
                || message.contains("签名") || message.contains("authx");
    }

    private static Session withToken(Session session, Object result) throws IOException {
        JSONObject object = object(result);
        String token = Title.first(object, "token", "access_token");
        if (token.isEmpty()) throw new IOException("飞牛影视没有返回登录令牌");
        return new Session(session.base, session.username, token, session.signed, session.legacy,
                session.relay, session.relayId);
    }

    static JSONObject read(OkHttpClient client, Request request) throws Exception {
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new ApiError("服务返回 HTTP " + response.code(), response.code());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("服务返回空响应");
            String raw = body.string();
            if (!raw.trim().startsWith("{")) throw new IOException("地址未返回 API 数据，请检查服务端口");
            return new JSONObject(raw);
        }
    }

    static JSONObject danmu(String base, String path, JSONObject data) throws Exception {
        Request.Builder builder = new Request.Builder().url(base + path);
        if (data != null) builder.post(RequestBody.create(data.toString(), JSON));
        return read(HTTP, builder.build());
    }

    static JSONObject object(Object value) { return value instanceof JSONObject ? (JSONObject) value : new JSONObject(); }
    static JSONArray array(Object value, String... keys) {
        if (value instanceof JSONArray) return (JSONArray) value;
        JSONObject obj = object(value);
        for (String key : keys) {
            JSONArray result = obj.optJSONArray(key);
            if (result != null) return result;
        }
        return new JSONArray();
    }

    static List<Title> titles(Object value, String... keys) {
        JSONArray array = array(value, keys);
        List<Title> results = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            JSONObject nested = item.optJSONObject("item");
            if (nested != null) {
                java.util.Iterator<String> fields = item.keys();
                while (fields.hasNext()) {
                    String field = fields.next();
                    if (!field.equals("item") && (field.equals("ts") || nested.isNull(field))) {
                        try { nested.put(field, item.opt(field)); } catch (org.json.JSONException ignored) {}
                    }
                }
                item = nested;
            }
            Title title = new Title(item);
            if (!title.id.isEmpty()) results.add(title);
        }
        return results;
    }

    List<Title> libraries() throws Exception {
        return titles(request(V1 + "/mediadb/list", null), "list", "mediadbs", "items");
    }

    List<Title> resume() throws Exception {
        return titles(request(V1 + "/play/list", null), "list", "items");
    }

    Title details(String id) throws Exception {
        return new Title(object(request(V1 + "/item/" + segment(id), null)));
    }

    Page page(String library, String directory, int page) throws Exception {
        JSONObject payload = json("tags", json("type", new JSONArray(java.util.Arrays.asList("Movie", "TV", "Directory", "Video"))),
                "exclude_grouped_video", 1, "exclude_folder", 0,
                "sort_type", "DESC", "sort_column", "release_date", "page", page, "page_size", 30);
        if (!library.isEmpty()) payload.put("ancestor_guid", library);
        if (!directory.isEmpty()) payload.put("parent_guid", directory);
        Object result = request(V1 + "/item/list", payload);
        List<Title> items = titles(result, "list", "items");
        int total = object(result).optInt("total", -1);
        return new Page(items, !items.isEmpty() && (total < 0 ? items.size() >= 30 : page * 30 < total));
    }

    List<Title> search(String keyword) throws Exception {
        try {
            return titles(request(V1 + "/search/list?q=" + segment(keyword), null), "list", "items");
        } catch (ApiError error) {
            if (error.code == 404 || error.code == 405 || error.code == 501) return null;
            throw error;
        }
    }
    List<Title> seasons(String series) throws Exception {
        return titles(request(V1 + "/season/list/" + segment(series), null), "list", "items", "seasons");
    }

    List<Title> episodes(String season) throws Exception {
        List<Title> results = titles(request(V1 + "/episode/list/" + segment(season), null), "list", "items", "episodes");
        results.sort((a, b) -> Integer.compare(a.episode, b.episode));
        return results;
    }

    private HttpUrl resolve(String value) {
        HttpUrl base = HttpUrl.get(session.base + "/");
        return base.resolve(value);
    }

    static boolean sameOrigin(HttpUrl one, HttpUrl other) {
        return one != null && other != null && one.host().equals(other.host())
                && one.port() == other.port() && one.scheme().equals(other.scheme());
    }

    Bitmap image(String path, int target) throws Exception {
        if (path.isEmpty()) return null;
        String relative = path.startsWith("/v/") || path.startsWith("http") ? path
                : V1 + "/sys/img/" + path.replaceFirst("^/+", "");
        HttpUrl url = resolve(relative);
        if (!sameOrigin(HttpUrl.get(session.base), url)) return null;
        Request request = authorize(new Request.Builder().url(url), url.encodedPath(), "").build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            byte[] bytes = response.body().bytes();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / (options.inSampleSize * 2) >= target) {
                options.inSampleSize *= 2;
            }
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        }
    }

    Video video(String id) throws Exception {
        JSONObject info = object(request(V1 + "/play/info", json("item_guid", id)));
        if (Title.first(info, "guid").isEmpty()) info.put("guid", id);
        JSONObject list = new JSONObject();
        try { list = object(request(V1 + "/stream/list/" + segment(id), null)); } catch (ApiError ignored) {}
        String media = Title.first(info, "media_guid");
        JSONArray files = array(list, "video_streams");
        if (media.isEmpty() && files.length() > 0) media = Title.first(files.optJSONObject(0), "media_guid");
        if (media.isEmpty()) throw new IOException("飞牛影视没有返回可播放的文件");
        info.put("media_guid", media);

        JSONObject stream = new JSONObject();
        try {
            stream = object(request(V1 + "/stream", json("header", json("User-Agent", new JSONArray().put("trim_player")),
                    "level", 1, "media_guid", media, "ip", hash("MD5", session.username))));
        } catch (ApiError ignored) {}

        ArrayList<Source> sources = new ArrayList<>();
        boolean cloud = object(stream.opt("cloud_storage_info")).optBoolean("valid");
        if (cloud) addSources(sources, array(stream, "direct_link_qualities"), object(stream.opt("header")));
        sources.add(new Source("原画 · NAS 直连", session.base + V1 + "/media/range/" + segment(media), false, Collections.emptyMap()));
        addSources(sources, array(stream, "qualities"), new JSONObject());
        if (!cloud) addSources(sources, array(stream, "direct_link_qualities"), object(stream.opt("header")));

        List<JSONObject> subtitles = new ArrayList<>();
        JSONArray tracks = array(list, "subtitle_streams");
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject track = tracks.optJSONObject(i);
            if (track != null && (track.optBoolean("is_external") || track.optInt("is_external") == 1)
                    && !Title.first(track, "guid").isEmpty()) subtitles.add(track);
        }
        String filename = Title.first(object(stream.opt("file_stream")), "file_name", "filename");
        return new Video(info, sources, subtitles, filename);
    }

    private void addSources(List<Source> sources, JSONArray rows, JSONObject headers) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : new String[]{"Cookie", "User-Agent", "Referer"}) {
            JSONArray multiple = headers.optJSONArray(key);
            if (multiple != null) {
                List<String> strings = new ArrayList<>();
                for (int i = 0; i < multiple.length(); i++) strings.add(multiple.optString(i));
                values.put(key, String.join(key.equals("Cookie") ? "; " : ", ", strings));
            } else if (!Title.first(headers, key).isEmpty()) {
                values.put(key, headers.optString(key));
            }
        }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject entry = rows.optJSONObject(i);
            String address = Title.first(entry, "url", "play_url", "play_link", "link");
            if (address.isEmpty()) continue;
            HttpUrl url = resolve(address);
            if (url == null) continue;
            String label = Title.first(entry, "resolution", "name", "quality");
            int bitrate = entry.optInt("bitrate");
            if (label.isEmpty()) label = "备用源 " + (i + 1);
            if (bitrate > 0) label += String.format(Locale.ROOT, " · %.1f Mbps", bitrate / 1000000.0);
            sources.add(new Source(label, url.toString(), entry.optBoolean("is_m3u8")
                    || url.encodedPath().endsWith(".m3u8"), values));
        }
    }

    OkHttpClient streaming(Source source) {
        HttpUrl nas = HttpUrl.get(session.base);
        HttpUrl media = HttpUrl.parse(source.url);
        return HTTP.newBuilder().followRedirects(true).followSslRedirects(false)
                .callTimeout(0, TimeUnit.SECONDS)
                .addNetworkInterceptor(chain -> {
                    Request req = chain.request();
                    Request.Builder builder = req.newBuilder().removeHeader("Authorization").removeHeader("Authx")
                            .removeHeader("Cookie").removeHeader("Referer");
                    if (sameOrigin(nas, req.url())) authorize(builder, req.url().encodedPath(), "");
                    if (sameOrigin(media, req.url())) {
                        for (Map.Entry<String, String> header : source.headers.entrySet()) {
                            builder.header(header.getKey(), header.getValue());
                        }
                    }
                    return chain.proceed(builder.build());
                }).build();
    }

    String subtitle(String id) { return session.base + V1 + "/subtitle/dl/" + segment(id); }

    void record(Video video, long position, long duration) throws Exception {
        JSONObject payload = json("item_guid", video.item, "media_guid", video.media,
                "video_guid", video.info.optString("video_guid"), "audio_guid", video.info.optString("audio_guid"),
                "subtitle_guid", video.info.optString("subtitle_guid"), "play_link", "",
                "ts", position / 1000, "duration", duration / 1000);
        request(V1 + "/play/record", payload);
    }
}

