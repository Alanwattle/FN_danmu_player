package dev.ushio.player;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class DanmuClient {
    private static final String API = "/api/v2";
    static final class Choice {
        final String id, label;
        Choice(String id, String label) { this.id = id; this.label = label; }
    }
    static final class Comment {
        final long time;
        final int mode, color;
        final String text;
        Comment(long time, int mode, int color, String text) {
            this.time = time;
            this.mode = mode;
            this.color = color;
            this.text = text;
        }
    }

    final String base;
    DanmuClient(String base) { this.base = base; }

    void verify() throws Exception {
        request("/search/anime?keyword=ushio-connection-check", null);
    }

    private JSONObject request(String path, JSONObject body) throws Exception {
        JSONObject result = FnClient.danmu(base, API + path, body);
        if (!result.optBoolean("success", true) || result.optInt("errorCode", 0) != 0) {
            String message = Title.first(result, "errorMessage", "message");
            throw new IOException(message.isEmpty() ? "弹幕服务请求失败" : message);
        }
        return result.optJSONObject("data") == null ? result : result.optJSONObject("data");
    }

    Choice match(String filename) throws Exception {
        JSONObject response = request("/match", FnClient.json("fileName", filename, "matchMode", "hashAndFileName"));
        if (!response.optBoolean("isMatched")) return null;
        List<Choice> choices = choices(response.optJSONArray("matches"), "", true);
        return choices.isEmpty() ? null : choices.get(0);
    }

    List<Choice> anime(String keyword) throws Exception {
        JSONObject response = request("/search/anime?keyword=" + FnClient.segment(keyword), null);
        return choices(response.optJSONArray("animes"), "", false);
    }

    List<Choice> episodes(String id) throws Exception {
        JSONObject result = request("/bangumi/" + FnClient.segment(id), null);
        JSONObject bangumi = result.optJSONObject("bangumi");
        if (bangumi == null) bangumi = result;
        return choices(bangumi.optJSONArray("episodes"), "", true);
    }

    private List<Choice> choices(JSONArray rows, String prefix, boolean episode) {
        List<Choice> results = new ArrayList<>();
        if (rows == null) return results;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            String id = Title.first(row, episode ? "episodeId" : "animeId", "id");
            if (id.isEmpty()) continue;
            String title = Title.first(row, episode ? "episodeTitle" : "animeTitle", "title", "name");
            String anime = episode ? Title.first(row, "animeTitle") : Title.first(row, "typeDescription");
            String label = title.isEmpty() ? id : title;
            if (!anime.isEmpty() && !anime.equals(label)) label = episode ? anime + " · " + label : label + " · " + anime;
            results.add(new Choice(id, prefix + label));
        }
        return results;
    }

    List<Comment> comments(String id) throws Exception {
        JSONObject response = request("/comment/" + FnClient.segment(id) + "?format=json", null);
        JSONArray rows = response.optJSONArray("comments");
        ArrayList<Comment> result = new ArrayList<>();
        if (rows == null) return result;
        Set<String> unique = new HashSet<>();
        for (int i = 0; i < rows.length() && result.size() < 45000; i++) {
            JSONObject comment = rows.optJSONObject(i);
            if (comment == null) continue;
            String[] properties = comment.optString("p").split(",", 5);
            String text = Title.first(comment, "m", "text", "content").replaceAll("[\\r\\n\\t]+", " ");
            if (properties.length < 3 || text.isEmpty()) continue;
            if (text.length() > 160) text = text.substring(0, 157) + "…";
            try {
                double seconds = Double.parseDouble(properties[0].trim());
                int mode = Integer.parseInt(properties[1].trim());
                int color = Integer.parseInt(properties[2].trim());
                if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds < 0 || seconds > 172800) continue;
                if (mode != 1 && mode != 4 && mode != 5) continue;
                long time = Math.round(seconds * 1000);
                if (unique.add(time / 350 + ":" + text)) result.add(new Comment(time, mode, color, text));
            } catch (NumberFormatException ignored) {}
        }
        result.sort(Comparator.comparingLong(comment -> comment.time));
        return result;
    }
}
