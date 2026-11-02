package com.fil.lyricswidget;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Looks up lyrics on LRCLIB (https://lrclib.net). Blocking: call off the main thread. */
final class LyricsFetcher {
    private static final String API = "https://lrclib.net/api/";
    private static final long DURATION_TOLERANCE_MS = 8000;

    /** title+artist -> {syncedLyrics, plainLyrics}; either may be null. */
    private static final Map<String, String[]> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, String[]>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String[]> e) {
                    return size() > 80;
                }
            });

    private LyricsFetcher() {}

    /** Returns lyrics to show, or null when none were found. Network failures throw. */
    static Lyrics fetch(String title, String artist, String album, long durationMs, boolean allowPlain)
            throws IOException {
        String key = title + "\u0000" + artist;
        String[] found = CACHE.get(key);
        if (found == null) {
            try {
                found = lookup(title, artist, album, durationMs);
            } catch (JSONException e) {
                found = new String[]{null, null};
            }
            CACHE.put(key, found);
        }
        if (found[0] != null) {
            Lyrics l = Lyrics.fromLrc(found[0]);
            if (!l.isEmpty()) return l;
        }
        if (allowPlain && found[1] != null) return Lyrics.fromPlain(found[1], durationMs);
        return null;
    }

    private static String[] lookup(String title, String artist, String album, long durationMs)
            throws IOException, JSONException {
        String synced = null, plain = null;

        if (durationMs > 0 && artist != null) {
            String url = API + "get?track_name=" + enc(title) + "&artist_name=" + enc(artist)
                    + (album != null ? "&album_name=" + enc(album) : "")
                    + "&duration=" + Math.round(durationMs / 1000.0);
            String body = httpGet(url);
            if (body != null) {
                JSONObject o = new JSONObject(body);
                if (o.optBoolean("instrumental")) return new String[]{null, null};
                synced = str(o, "syncedLyrics");
                plain = str(o, "plainLyrics");
            }
        }
        if (synced != null) return new String[]{synced, plain};

        String clean = cleanTitle(title);
        String mainArtist = artist == null ? null : primaryArtist(artist);
        Set<String> searches = new LinkedHashSet<>();
        searches.add("track_name=" + enc(title) + (artist != null ? "&artist_name=" + enc(artist) : ""));
        searches.add("track_name=" + enc(clean) + (mainArtist != null ? "&artist_name=" + enc(mainArtist) : ""));
        searches.add("q=" + enc(clean + (mainArtist != null ? " " + mainArtist : "")));

        for (String query : searches) {
            String body = httpGet(API + "search?" + query);
            if (body == null) continue;
            JSONObject best = pick(new JSONArray(body), durationMs);
            if (best == null) continue;
            String s = str(best, "syncedLyrics");
            if (plain == null) plain = str(best, "plainLyrics");
            if (s != null) { synced = s; break; }
        }
        return new String[]{synced, plain};
    }

    /** Best match: synced lyrics with the closest duration; otherwise something with plain lyrics. */
    private static JSONObject pick(JSONArray arr, long durationMs) {
        JSONObject bestSynced = null, bestPlain = null;
        long bestSyncedDiff = Long.MAX_VALUE, bestPlainDiff = Long.MAX_VALUE;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || o.optBoolean("instrumental")) continue;
            long diff = durationMs > 0 ? Math.abs((long) (o.optDouble("duration", 0) * 1000) - durationMs) : 0;
            if (durationMs > 0 && diff > DURATION_TOLERANCE_MS) continue;
            if (str(o, "syncedLyrics") != null && diff < bestSyncedDiff) { bestSynced = o; bestSyncedDiff = diff; }
            if (str(o, "plainLyrics") != null && diff < bestPlainDiff) { bestPlain = o; bestPlainDiff = diff; }
        }
        return bestSynced != null ? bestSynced : bestPlain;
    }

    /** "Song - Remastered 2011" / "Song (feat. X)" -> "Song". */
    static String cleanTitle(String title) {
        String t = title.replaceAll("(?i)\\s*[(\\[][^)\\]]*(feat|ft\\.|with |remaster|version|edit|mono|stereo|live)[^)\\]]*[)\\]]", "");
        int dash = t.indexOf(" - ");
        if (dash > 0) t = t.substring(0, dash);
        t = t.trim();
        return t.isEmpty() ? title : t;
    }

    /** "A, B & C" -> "A". */
    static String primaryArtist(String artist) {
        String a = artist.split("(?i)\\s*(,|&|\\bfeat\\.?|\\bft\\.?)\\s*")[0].trim();
        return a.isEmpty() ? artist : a;
    }

    private static String str(JSONObject o, String key) {
        if (o.isNull(key)) return null;
        String s = o.optString(key, "").trim();
        return s.isEmpty() ? null : s;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    /** Body on 200, null on 404, IOException otherwise. */
    private static String httpGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "LyricsWidget/1.0 (Android)");
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
