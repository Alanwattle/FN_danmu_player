package dev.ushio.player;

import org.json.JSONObject;

final class Title {
    final String id, name, type, series, poster, art, overview, year, parent;
    final int season, episode;
    final long position, duration;
    final int image;

    Title(JSONObject value) {
        id = first(value, "guid", "item_guid", "id");
        name = first(value, "title", "name", "item_title", "tv_title");
        type = first(value, "type", "item_type", "category");
        parent = first(value, "parent_guid", "season_guid");
        series = first(value, "tv_title", "series_title", "grand_title");
        poster = first(value, "poster", "posters", "still_path");
        art = first(value, "backdrop", "backdrop_path", "fanart", "still_path", "poster", "posters");
        overview = first(value, "overview", "description", "plot");
        String date = first(value, "release_date", "air_date", "year");
        year = date.length() >= 4 ? date.substring(0, 4) : "";
        season = value.optInt("season_number");
        episode = value.optInt("episode_number");
        position = value.optLong("ts", value.optLong("watched_ts")) * 1000;
        duration = value.optLong("duration") * 1000;
        image = 0;
    }

    Title(String id, String name, String type, String series, int season, int episode,
          String overview, String year, int image, long position, long duration) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.series = series;
        this.season = season;
        this.episode = episode;
        this.overview = overview;
        this.year = year;
        this.image = image;
        this.position = position;
        this.duration = duration;
        parent = poster = art = "";
    }

    static String first(JSONObject value, String... keys) {
        if (value == null) return "";
        for (String key : keys) {
            String result = value.isNull(key) ? "" : value.optString(key).trim();
            if (!result.isEmpty()) return result;
        }
        return "";
    }

    boolean is(String value) { return type.equalsIgnoreCase(value); }
    boolean folder() { return is("MediaDB") || is("Directory"); }
    String category() {
        if (is("Movie")) return "电影";
        if (is("TV")) return "剧集";
        if (is("Episode")) return "第 " + episode + " 集";
        if (is("Season")) return "第 " + season + " 季";
        return folder() ? "媒体库" : "视频";
    }
    String meta() { return year.isEmpty() ? category() : category() + "  ·  " + year; }
}
