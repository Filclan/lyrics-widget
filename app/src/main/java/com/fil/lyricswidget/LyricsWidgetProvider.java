package com.fil.lyricswidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

/**
 * Renders the lyrics widget. The widget holds two copies of a 3-line block inside a
 * ViewFlipper: each new line is written into the hidden copy and flipped in, which plays
 * the slide-up animation on the home screen.
 */
public class LyricsWidgetProvider extends AppWidgetProvider {
    private static final int[][] IDS = {
            {R.id.prev0, R.id.cur0, R.id.next0},
            {R.id.prev1, R.id.cur1, R.id.next1},
    };
    private static final String[] DEMO = {
            "Questa è un'anteprima del widget",
            "Le righe scorrono a tempo",
            "con la canzone di Spotify",
            "colori e sfondo li scegli tu",
            "e quando non suona niente",
            "il widget sparisce",
    };

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    // Render state, touched only on the main thread.
    private static boolean sVisible;
    private static boolean sHiddenPushed;
    private static String[] sCurrent = {"", "", ""};
    private static String[] sPrevious = {"", "", ""};
    private static int sSlot;
    private static String sKey;
    private static int sIndex = Integer.MIN_VALUE;
    private static boolean sDemo;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        manager.updateAppWidget(ids, build(context));
        // Some OEMs (e.g. Realme/Oppo) kill the listener and the system doesn't bring it back.
        if (LyricsService.instance == null) {
            try {
                NotificationListenerService.requestRebind(new ComponentName(context, LyricsService.class));
            } catch (RuntimeException ignored) {
                // Notification access not granted.
            }
        }
    }

    static void showHidden(Context c) {
        if (sDemo || (!sVisible && sHiddenPushed)) return;
        sVisible = false;
        sKey = null;
        sIndex = Integer.MIN_VALUE;
        sHiddenPushed = true;
        push(c);
    }

    static void showLine(Context c, Lyrics lyrics, int idx, String key) {
        if (sDemo) return;
        if (sVisible && idx == sIndex && key.equals(sKey)) return;
        String[] block = {
                lyrics.text(idx - 1),
                idx < 0 ? "♪" : lyrics.text(idx),
                lyrics.text(idx + 1),
        };
        sIndex = idx;
        sKey = key;
        setBlock(c, block, sVisible);
    }

    /** Plays a few sample lines on the real widget so the look can be checked. */
    static void showDemo(Context context) {
        final Context app = context.getApplicationContext();
        HANDLER.removeCallbacksAndMessages(null);
        sDemo = true;
        final int[] step = {0};
        HANDLER.post(new Runnable() {
            @Override
            public void run() {
                int i = step[0]++;
                if (i >= DEMO.length) {
                    sDemo = false;
                    sVisible = false;
                    sHiddenPushed = false;
                    sKey = null;
                    sIndex = Integer.MIN_VALUE;
                    LyricsService s = LyricsService.instance;
                    if (s != null) s.requestTick();
                    else showHidden(app);
                    return;
                }
                String[] block = {
                        i > 0 ? DEMO[i - 1] : "",
                        DEMO[i],
                        i + 1 < DEMO.length ? DEMO[i + 1] : "",
                };
                setBlock(app, block, sVisible);
                HANDLER.postDelayed(this, 1000);
            }
        });
    }

    private static void setBlock(Context c, String[] block, boolean animate) {
        if (animate) {
            sPrevious = sCurrent;
            sSlot = 1 - sSlot;
        } else {
            sPrevious = block;
        }
        sCurrent = block;
        sVisible = true;
        sHiddenPushed = false;
        push(c);
    }

    /** Pushes the current state (and appearance settings) to every widget instance. */
    static void push(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, LyricsWidgetProvider.class));
        if (ids == null || ids.length == 0) return;
        m.updateAppWidget(ids, build(c));
    }

    static int widgetCount(Context c) {
        int[] ids = AppWidgetManager.getInstance(c)
                .getAppWidgetIds(new ComponentName(c, LyricsWidgetProvider.class));
        return ids == null ? 0 : ids.length;
    }

    private static RemoteViews build(Context c) {
        RemoteViews rv = new RemoteViews(c.getPackageName(),
                Prefs.shadow(c) ? R.layout.widget_lyrics_shadow : R.layout.widget_lyrics);
        rv.setViewVisibility(R.id.root, sVisible ? View.VISIBLE : View.INVISIBLE);

        rv.setInt(R.id.bg, "setColorFilter", Prefs.bgColor(c) | 0xFF000000);
        rv.setInt(R.id.bg, "setImageAlpha", Prefs.bgAlpha(c));

        int color = Prefs.textColor(c);
        int dim = withAlpha(color, 0.55f);
        float size = Prefs.textSize(c);
        boolean threeLines = Prefs.lines(c) != 1;
        fillSlot(rv, IDS[sSlot], sCurrent, color, dim, size, threeLines);
        fillSlot(rv, IDS[1 - sSlot], sPrevious, color, dim, size, threeLines);
        rv.setDisplayedChild(R.id.flipper, sSlot);

        Intent open = null;
        for (String pkg : LyricsService.SPOTIFY_PACKAGES) {
            open = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (open != null) break;
        }
        if (open == null) open = new Intent(c, MainActivity.class);
        rv.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return rv;
    }

    private static void fillSlot(RemoteViews rv, int[] ids, String[] block, int color, int dim,
                                 float size, boolean threeLines) {
        for (int j = 0; j < 3; j++) {
            boolean current = j == 1;
            rv.setTextViewText(ids[j], block[j]);
            rv.setTextColor(ids[j], current ? color : dim);
            rv.setTextViewTextSize(ids[j], TypedValue.COMPLEX_UNIT_SP, current ? size : size * 0.82f);
            if (!current) rv.setViewVisibility(ids[j], threeLines ? View.VISIBLE : View.GONE);
        }
    }

    static int withAlpha(int color, float factor) {
        int a = Math.round(((color >>> 24) & 0xFF) * factor);
        return (a << 24) | (color & 0x00FFFFFF);
    }
}
