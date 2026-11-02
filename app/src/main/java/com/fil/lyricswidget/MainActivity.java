package com.fil.lyricswidget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The only screen: grants access, pins the widget and configures its look. */
public class MainActivity extends Activity {
    private static final int[] BG_PRESETS = {
            0xFF000000, 0xFF1E1E1E, 0xFF0D1B2A, 0xFF1DB954, 0xFF4A148C, 0xFF8E0000, 0xFF3E2723, 0xFFFFFFFF,
    };
    private static final int[] TEXT_PRESETS = {
            0xFFFFFFFF, 0xFF000000, 0xFF1DB954, 0xFFFFEB3B, 0xFF4DD0E1, 0xFFFF80AB, 0xFFFFAB40, 0xFFB388FF,
    };
    private static final int MIN_TEXT_SIZE = 12;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<View> bgSwatchViews = new ArrayList<>();
    private final List<View> textSwatchViews = new ArrayList<>();

    private TextView statusText, alphaLabel, sizeLabel;
    private Button accessButton;
    private EditText bgHex, textHex;
    private SeekBar alphaSeek, sizeSeek;
    private View previewCard;
    private TextView previewPrev, previewCur, previewNext;
    private boolean updatingUi;

    private final Runnable statusUpdater = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status);
        accessButton = findViewById(R.id.btn_access);
        alphaLabel = findViewById(R.id.alpha_label);
        sizeLabel = findViewById(R.id.size_label);
        bgHex = findViewById(R.id.bg_hex);
        textHex = findViewById(R.id.text_hex);
        alphaSeek = findViewById(R.id.alpha_seek);
        sizeSeek = findViewById(R.id.size_seek);
        previewCard = findViewById(R.id.preview_card);
        previewPrev = findViewById(R.id.preview_prev);
        previewCur = findViewById(R.id.preview_cur);
        previewNext = findViewById(R.id.preview_next);

        accessButton.setOnClickListener(v -> openAccessSettings());
        findViewById(R.id.btn_app_info).setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        findViewById(R.id.btn_pin).setOnClickListener(v -> pinWidget());
        findViewById(R.id.btn_demo).setOnClickListener(v -> {
            if (LyricsWidgetProvider.widgetCount(this) == 0) {
                toast("Prima aggiungi il widget alla schermata Home");
                return;
            }
            LyricsWidgetProvider.showDemo(this);
            toast("Torna alla Home per vedere il widget");
        });

        buildSwatches(findViewById(R.id.bg_swatches), BG_PRESETS, bgSwatchViews, color -> {
            Prefs.putInt(this, Prefs.BG_COLOR, color);
            if (Prefs.bgAlpha(this) == 0) Prefs.putInt(this, Prefs.BG_ALPHA, 255);
        });
        buildSwatches(findViewById(R.id.text_swatches), TEXT_PRESETS, textSwatchViews,
                color -> Prefs.putInt(this, Prefs.TEXT_COLOR, color));

        bgHex.addTextChangedListener(new HexWatcher(bgHex) {
            @Override
            void onColor(int color, boolean hasAlpha) {
                Prefs.putInt(MainActivity.this, Prefs.BG_COLOR, color | 0xFF000000);
                if (hasAlpha) Prefs.putInt(MainActivity.this, Prefs.BG_ALPHA, color >>> 24);
            }
        });
        textHex.addTextChangedListener(new HexWatcher(textHex) {
            @Override
            void onColor(int color, boolean hasAlpha) {
                Prefs.putInt(MainActivity.this, Prefs.TEXT_COLOR, hasAlpha ? color : color | 0xFF000000);
            }
        });

        findViewById(R.id.btn_alpha_0).setOnClickListener(v -> setBgAlpha(0));
        findViewById(R.id.btn_alpha_half).setOnClickListener(v -> setBgAlpha(150));
        findViewById(R.id.btn_alpha_full).setOnClickListener(v -> setBgAlpha(255));
        alphaSeek.setOnSeekBarChangeListener(new SeekListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) setBgAlpha(progress);
            }
        });
        sizeSeek.setOnSeekBarChangeListener(new SeekListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                Prefs.putInt(MainActivity.this, Prefs.TEXT_SIZE, MIN_TEXT_SIZE + progress);
                applyChanges();
            }
        });

        RadioGroup lines = findViewById(R.id.lines_group);
        lines.check(Prefs.lines(this) == 1 ? R.id.lines_1 : R.id.lines_3);
        lines.setOnCheckedChangeListener((g, id) -> {
            Prefs.putInt(this, Prefs.LINES, id == R.id.lines_1 ? 1 : 3);
            applyChanges();
        });

        Switch shadow = findViewById(R.id.switch_shadow);
        shadow.setChecked(Prefs.shadow(this));
        shadow.setOnCheckedChangeListener((b, checked) -> {
            Prefs.putBool(this, Prefs.SHADOW, checked);
            applyChanges();
        });

        Switch plain = findViewById(R.id.switch_plain);
        plain.setChecked(Prefs.plainLyrics(this));
        plain.setOnCheckedChangeListener((b, checked) -> {
            Prefs.putBool(this, Prefs.PLAIN, checked);
            LyricsService s = LyricsService.instance;
            if (s != null) s.reload();
        });

        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isAccessGranted() && LyricsService.instance == null) {
            NotificationListenerService.requestRebind(new ComponentName(this, LyricsService.class));
        }
        handler.post(statusUpdater);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statusUpdater);
    }

    private void updateStatus() {
        boolean granted = isAccessGranted();
        accessButton.setText(granted ? "Accesso alle notifiche: attivo ✓" : "Concedi accesso alle notifiche");
        String s = granted
                ? LyricsService.describe()
                : "Per sapere cosa suona Spotify, l'app ha bisogno dell'accesso alle notifiche "
                + "(non legge né salva le tue notifiche).";
        int widgets = LyricsWidgetProvider.widgetCount(this);
        s += "\n" + (widgets == 0 ? "Nessun widget sulla Home." : "Widget sulla Home: " + widgets);
        statusText.setText(s);
    }

    private boolean isAccessGranted() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        ComponentName cn = new ComponentName(this, LyricsService.class);
        return flat != null && (flat.contains(cn.flattenToString()) || flat.contains(cn.flattenToShortString()));
    }

    private void openAccessSettings() {
        Intent i;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
            i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    new ComponentName(this, LyricsService.class).flattenToString());
        } else {
            i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        }
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }

    private void pinWidget() {
        AppWidgetManager m = AppWidgetManager.getInstance(this);
        if (m.isRequestPinAppWidgetSupported()) {
            m.requestPinAppWidget(new ComponentName(this, LyricsWidgetProvider.class), null, null);
        } else {
            toast("Il launcher non lo permette: tieni premuto sulla Home → Widget → Lyrics Widget");
        }
    }

    private void setBgAlpha(int alpha) {
        Prefs.putInt(this, Prefs.BG_ALPHA, alpha);
        applyChanges();
    }

    private void applyChanges() {
        refreshUi();
        LyricsWidgetProvider.push(this);
    }

    private void refreshUi() {
        updatingUi = true;
        int bg = Prefs.bgColor(this), alpha = Prefs.bgAlpha(this);
        int text = Prefs.textColor(this), size = Prefs.textSize(this);
        if (!bgHex.hasFocus()) bgHex.setText(hex(bg, false));
        if (!textHex.hasFocus()) textHex.setText(hex(text, (text >>> 24) != 0xFF));
        alphaSeek.setProgress(alpha);
        alphaLabel.setText(alpha == 0 ? "Opacità sfondo: trasparente"
                : "Opacità sfondo: " + Math.round(alpha * 100 / 255f) + "%");
        sizeSeek.setProgress(size - MIN_TEXT_SIZE);
        sizeLabel.setText("Dimensione testo: " + size);
        markSelected(bgSwatchViews, bg);
        markSelected(textSwatchViews, text);
        updatingUi = false;

        GradientDrawable card = new GradientDrawable();
        card.setCornerRadius(dp(20));
        card.setColor((alpha << 24) | (bg & 0x00FFFFFF));
        previewCard.setBackground(card);
        int dim = LyricsWidgetProvider.withAlpha(text, 0.55f);
        boolean shadow = Prefs.shadow(this), three = Prefs.lines(this) != 1;
        for (TextView tv : new TextView[]{previewPrev, previewCur, previewNext}) {
            boolean cur = tv == previewCur;
            tv.setTextColor(cur ? text : dim);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, cur ? size : size * 0.82f);
            tv.setShadowLayer(shadow ? 6 : 0, 0, shadow ? 1 : 0, shadow ? 0xCC000000 : 0);
            if (!cur) tv.setVisibility(three ? View.VISIBLE : View.GONE);
        }
    }

    private interface ColorPick {
        void onPick(int color);
    }

    private void buildSwatches(LinearLayout row, int[] colors, List<View> out, ColorPick pick) {
        int size = dp(40), margin = dp(5);
        for (int color : colors) {
            View v = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(margin, margin, margin, margin);
            v.setLayoutParams(lp);
            v.setTag(color);
            v.setOnClickListener(x -> {
                bgHex.clearFocus();
                textHex.clearFocus();
                pick.onPick(color);
                applyChanges();
            });
            row.addView(v);
            out.add(v);
        }
    }

    private void markSelected(List<View> swatches, int selected) {
        for (View v : swatches) {
            int color = (Integer) v.getTag();
            boolean on = (color & 0xFFFFFF) == (selected & 0xFFFFFF);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(color);
            d.setStroke(dp(on ? 4 : 1), on ? 0xFF1DB954 : 0xFF888888);
            v.setBackground(d);
        }
    }

    private abstract class HexWatcher implements TextWatcher {
        private final EditText field;

        HexWatcher(EditText field) { this.field = field; }

        abstract void onColor(int color, boolean hasAlpha);

        @Override
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

        @Override
        public void onTextChanged(CharSequence s, int a, int b, int c) {}

        @Override
        public void afterTextChanged(Editable e) {
            if (updatingUi || !field.hasFocus()) return;
            String s = e.toString().trim();
            if (s.startsWith("#")) s = s.substring(1);
            if (!s.matches("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")) return;
            int color = (int) Long.parseLong(s, 16);
            onColor(color, s.length() == 8);
            applyChanges();
        }
    }

    private abstract static class SeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar s) {}

        @Override
        public void onStopTrackingTouch(SeekBar s) {}
    }

    private static String hex(int color, boolean withAlpha) {
        return withAlpha ? String.format(Locale.ROOT, "#%08X", color)
                : String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
