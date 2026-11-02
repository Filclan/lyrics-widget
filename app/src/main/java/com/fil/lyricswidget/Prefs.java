package com.fil.lyricswidget;

import android.content.Context;
import android.content.SharedPreferences;

/** Widget appearance settings chosen in the app. */
final class Prefs {
    static final String BG_COLOR = "bg_color";
    static final String BG_ALPHA = "bg_alpha";
    static final String TEXT_COLOR = "text_color";
    static final String TEXT_SIZE = "text_size";
    static final String LINES = "lines";
    static final String SHADOW = "shadow";
    static final String PLAIN = "plain";

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** Background RGB (alpha is stored separately). */
    static int bgColor(Context c) { return get(c).getInt(BG_COLOR, 0xFF000000); }

    /** Background opacity 0..255; 0 = fully transparent. */
    static int bgAlpha(Context c) { return get(c).getInt(BG_ALPHA, 0); }

    static int textColor(Context c) { return get(c).getInt(TEXT_COLOR, 0xFFFFFFFF); }

    static int textSize(Context c) { return get(c).getInt(TEXT_SIZE, 18); }

    static int lines(Context c) { return get(c).getInt(LINES, 3); }

    static boolean shadow(Context c) { return get(c).getBoolean(SHADOW, true); }

    static boolean plainLyrics(Context c) { return get(c).getBoolean(PLAIN, false); }

    static void putInt(Context c, String key, int value) {
        get(c).edit().putInt(key, value).apply();
    }

    static void putBool(Context c, String key, boolean value) {
        get(c).edit().putBoolean(key, value).apply();
    }
}
