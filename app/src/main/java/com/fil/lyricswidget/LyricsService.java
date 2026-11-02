package com.fil.lyricswidget;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kept alive by the system while notification access is granted. Watches Spotify's media
 * session, fetches lyrics for the current track and drives the widget line by line.
 */
public class LyricsService extends NotificationListenerService {
    static final String SPOTIFY = "com.spotify.music";
    /** Show each line slightly early so the slide animation ends as it is sung. */
    private static final long LOOKAHEAD_MS = 250;

    static volatile LyricsService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private MediaSessionManager sessionManager;
    private MediaController controller;

    private String trackKey;
    private String title, artist;
    private Lyrics lyrics;
    private String lyricsStatus = "";
    private int fetchGeneration;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = this::onSessionsChanged;
    private final Runnable tickRunnable = this::tick;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            handleMetadata(metadata);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            tick();
        }

        @Override
        public void onSessionDestroyed() {
            detachController();
            tick();
        }
    };

    @Override
    public void onListenerConnected() {
        instance = this;
        sessionManager = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, LyricsService.class);
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, me, handler);
            onSessionsChanged(sessionManager.getActiveSessions(me));
        } catch (SecurityException e) {
            lyricsStatus = "Accesso alle notifiche mancante";
        }
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        if (sessionManager != null) sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
        detachController();
        handler.removeCallbacks(tickRunnable);
        LyricsWidgetProvider.showHidden(this);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        executor.shutdownNow();
        super.onDestroy();
    }

    /** Re-reads the current track from scratch, e.g. after a lyrics setting changed. */
    void reload() {
        trackKey = null;
        if (controller != null) handleMetadata(controller.getMetadata());
        else tick();
    }

    /** Re-renders the widget for the current position (after the demo ends). */
    void requestTick() {
        handler.post(tickRunnable);
    }

    private void onSessionsChanged(List<MediaController> controllers) {
        MediaController spotify = null;
        if (controllers != null) {
            for (MediaController c : controllers) {
                if (SPOTIFY.equals(c.getPackageName())) { spotify = c; break; }
            }
        }
        if (spotify != null && controller != null
                && spotify.getSessionToken().equals(controller.getSessionToken())) {
            return;
        }
        detachController();
        if (spotify != null) {
            controller = spotify;
            controller.registerCallback(callback, handler);
            handleMetadata(controller.getMetadata());
        }
        tick();
    }

    private void detachController() {
        if (controller != null) {
            controller.unregisterCallback(callback);
            controller = null;
        }
        clearTrack();
    }

    private void clearTrack() {
        trackKey = null;
        title = artist = null;
        lyrics = null;
        lyricsStatus = "";
        fetchGeneration++;
    }

    private void handleMetadata(MediaMetadata md) {
        final String t = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (t == null || t.trim().isEmpty()) {
            clearTrack();
            tick();
            return;
        }
        final String a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        final String album = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
        final long duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        String key = t + "\u0000" + (a == null ? "" : a);
        if (key.equals(trackKey)) return;

        trackKey = key;
        title = t;
        artist = a;
        lyrics = null;
        lyricsStatus = "Cerco il testo…";
        final int gen = ++fetchGeneration;
        final boolean allowPlain = Prefs.plainLyrics(this);
        tick();

        executor.execute(() -> {
            Lyrics result = LyricsFetcher.fetch(t, a, album, duration, allowPlain);
            handler.post(() -> {
                if (gen != fetchGeneration) return;
                lyrics = result;
                if (result == null) lyricsStatus = "Testo non trovato";
                else if (result.synced) lyricsStatus = "Testo sincronizzato trovato";
                else lyricsStatus = "Testo non sincronizzato (tempi stimati)";
                tick();
            });
        });
    }

    private boolean isPlaying(PlaybackState st) {
        return st != null && (st.getState() == PlaybackState.STATE_PLAYING
                || st.getState() == PlaybackState.STATE_BUFFERING);
    }

    private void tick() {
        handler.removeCallbacks(tickRunnable);
        PlaybackState st = controller == null ? null : controller.getPlaybackState();
        if (!isPlaying(st) || lyrics == null || lyrics.isEmpty() || trackKey == null) {
            LyricsWidgetProvider.showHidden(this);
            return;
        }
        long pos = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
            float speed = st.getPlaybackSpeed() > 0 ? st.getPlaybackSpeed() : 1f;
            pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * speed);
        }
        long t = pos + LOOKAHEAD_MS;
        int idx = lyrics.indexAt(t);
        LyricsWidgetProvider.showLine(this, lyrics, idx, trackKey);

        long next = lyrics.nextTime(idx);
        long delay = next < 0 ? 1000 : Math.max(50, Math.min(1000, next - t + 10));
        handler.postDelayed(tickRunnable, delay);
    }

    /** Human-readable state for the settings screen. Main thread only. */
    static String describe() {
        LyricsService s = instance;
        if (s == null) {
            return "Servizio in avvio… Se resta così, disattiva e riattiva l'accesso alle notifiche.";
        }
        if (s.controller == null || s.title == null) {
            return "Spotify non sta riproducendo nulla → widget invisibile.";
        }
        boolean playing = s.isPlaying(s.controller.getPlaybackState());
        String track = "«" + s.title + "»" + (s.artist != null ? " – " + s.artist : "");
        String line = (playing ? "In riproduzione: " : "In pausa: ") + track + "\n" + s.lyricsStatus;
        if (!playing || s.lyrics == null) line += " → widget invisibile.";
        else line += " → widget visibile.";
        return line;
    }
}
