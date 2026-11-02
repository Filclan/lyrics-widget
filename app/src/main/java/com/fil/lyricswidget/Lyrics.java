package com.fil.lyricswidget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Time-stamped lyric lines, sorted by start time (ms). */
final class Lyrics {
    private static final Pattern TAG = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\]");

    final long[] times;
    final String[] lines;
    final boolean synced;

    private Lyrics(long[] times, String[] lines, boolean synced) {
        this.times = times;
        this.lines = lines;
        this.synced = synced;
    }

    boolean isEmpty() { return lines.length == 0; }

    /** Index of the line playing at time t, or -1 before the first line. */
    int indexAt(long t) {
        int lo = 0, hi = times.length - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= t) { ans = mid; lo = mid + 1; } else { hi = mid - 1; }
        }
        return ans;
    }

    /** Start time of the line after idx, or -1 if idx is the last one. */
    long nextTime(int idx) {
        return idx + 1 < times.length ? times[idx + 1] : -1;
    }

    /** Text of line i; "" when out of range, a note for instrumental gaps. */
    String text(int i) {
        if (i < 0 || i >= lines.length) return "";
        return lines[i].isEmpty() ? "♪" : lines[i];
    }

    static Lyrics fromLrc(String lrc) {
        List<long[]> stamps = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (String raw : lrc.split("\\r?\\n")) {
            Matcher m = TAG.matcher(raw);
            int end = 0;
            List<Long> lineTimes = new ArrayList<>();
            while (m.find() && m.start() == end) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                long ms = 0;
                String frac = m.group(3);
                if (frac != null) {
                    if (frac.length() > 3) frac = frac.substring(0, 3);
                    ms = Long.parseLong(frac);
                    for (int k = frac.length(); k < 3; k++) ms *= 10;
                }
                lineTimes.add(min * 60_000 + sec * 1000 + ms);
                end = m.end();
            }
            if (lineTimes.isEmpty()) continue;
            String text = raw.substring(end).trim();
            for (long t : lineTimes) {
                stamps.add(new long[]{t, texts.size()});
                texts.add(text);
            }
        }
        Collections.sort(stamps, (a, b) -> Long.compare(a[0], b[0]));
        long[] times = new long[stamps.size()];
        String[] lines = new String[stamps.size()];
        for (int i = 0; i < stamps.size(); i++) {
            times[i] = stamps.get(i)[0];
            lines[i] = texts.get((int) stamps.get(i)[1]);
        }
        return new Lyrics(times, lines, true);
    }

    /** Unsynced lyrics: lines spread evenly over the song, as a rough estimate. */
    static Lyrics fromPlain(String plain, long durationMs) {
        if (durationMs <= 0) return null;
        List<String> list = new ArrayList<>();
        for (String raw : plain.split("\\r?\\n")) {
            String s = raw.trim();
            if (!s.isEmpty()) list.add(s);
        }
        if (list.isEmpty()) return null;
        long start = (long) (durationMs * 0.08), end = (long) (durationMs * 0.92);
        long[] times = new long[list.size()];
        for (int i = 0; i < times.length; i++) {
            times[i] = start + (end - start) * i / times.length;
        }
        return new Lyrics(times, list.toArray(new String[0]), false);
    }
}
