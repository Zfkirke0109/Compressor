package io.github.zfkirke0109.mtenglishoverlay;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    static final String PREFS = "mt_english_overlay";
    static final String PREF_ENABLED = "enabled";
    static final String PREF_UNKNOWN = "unknown_text";

    private SharedPreferences prefs;
    private TextView statusView;
    private Button pauseButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(10, 12, 16));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(30), dp(22), dp(42));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView eyebrow = text("VISUAL LOCALIZATION", 12, Color.rgb(108, 174, 255));
        eyebrow.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        eyebrow.setLetterSpacing(0.12f);
        root.addView(eyebrow);

        TextView title = text("MT English Vision", 31, Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setPadding(0, dp(6), 0, 0);
        root.addView(title);

        TextView subtitle = text(
                "A seamless English replacement layer for MT Manager. It combines Android UI text, " +
                        "on-device screen OCR, language detection, and offline translation models instead of drawing separate label bubbles.",
                16,
                Color.rgb(196, 205, 218));
        subtitle.setPadding(0, dp(10), 0, dp(20));
        root.addView(subtitle);

        statusView = text("", 16, Color.WHITE);
        statusView.setPadding(dp(16), dp(15), dp(16), dp(15));
        statusView.setBackground(cardBackground(Color.rgb(24, 29, 37), Color.rgb(55, 67, 83), 18));
        root.addView(statusView, matchWrap());

        root.addView(button("Enable screen translation", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)), true));

        pauseButton = button("", v -> {
            boolean next = !prefs.getBoolean(PREF_ENABLED, true);
            prefs.edit().putBoolean(PREF_ENABLED, next).apply();
            sendBroadcast(new Intent(MtLocalizerService.ACTION_REFRESH).setPackage(getPackageName()));
            refreshStatus();
        }, false);
        root.addView(pauseButton);

        root.addView(button("Open MT Manager", v -> openMtManager(), false));

        TextView modeTitle = sectionTitle("How version 2 works");
        root.addView(modeTitle);
        root.addView(cardText(
                "1. Reads normal Android UI text instantly when available.\n\n" +
                        "2. Captures only the active MT Manager window and runs OCR locally on the phone for text drawn as pixels or hidden from accessibility.\n\n" +
                        "3. Uses the built-in MT technical dictionary first, then downloads free language models once and translates locally afterward.\n\n" +
                        "4. Repaints only the original text regions with colors sampled from the underlying screen, adaptive typography, rounded edges, and subtle transitions."));

        TextView privacyTitle = sectionTitle("Privacy and downloads");
        root.addView(privacyTitle);
        root.addView(cardText(
                "Screen images and recognized text stay on this device. Internet access is used only when a required translation model is downloaded for the first time. " +
                        "A language pair is roughly tens of megabytes and is reused offline afterward."));

        root.addView(button("Clear downloaded translation cache", v -> {
            getSharedPreferences("on_device_translation_cache", MODE_PRIVATE).edit().clear().apply();
            Toast.makeText(this, "Saved translations cleared. Language models remain available on device.", Toast.LENGTH_LONG).show();
        }, false));

        TextView note = text(
                "Best results: leave MT Manager visible for a moment after opening a new screen. The service is restricted to MT Manager and its Canary package.",
                13,
                Color.rgb(132, 145, 162));
        note.setPadding(0, dp(24), 0, 0);
        root.addView(note);

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean service = isAccessibilityServiceEnabled();
        boolean enabled = prefs.getBoolean(PREF_ENABLED, true);
        int cached = getSharedPreferences("on_device_translation_cache", MODE_PRIVATE).getAll().size();

        String state = service ? "Screen translation permission: ON" : "Screen translation permission: OFF";
        state += enabled ? "\nVisual replacement: RUNNING" : "\nVisual replacement: PAUSED";
        state += "\nSaved on-device translations: " + cached;
        statusView.setText(state);
        statusView.setTextColor(service && enabled
                ? Color.rgb(139, 255, 181)
                : Color.rgb(255, 202, 112));
        pauseButton.setText(enabled ? "Pause visual replacement" : "Resume visual replacement");
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String component = getPackageName() + "/" + MtLocalizerService.class.getName();
        return enabled.toLowerCase().contains(component.toLowerCase())
                || enabled.toLowerCase().contains(getPackageName().toLowerCase());
    }

    private void openMtManager() {
        String[] packages = {"bin.mt.plus", "bin.mt.plus.canary"};
        for (String pkg : packages) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                startActivity(launch);
                return;
            }
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://mt2.cn/")));
        } catch (Exception e) {
            Toast.makeText(this, "MT Manager was not found.", Toast.LENGTH_LONG).show();
        }
    }

    private TextView sectionTitle(String value) {
        TextView title = text(value, 20, Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setPadding(0, dp(26), 0, dp(10));
        return title;
    }

    private TextView cardText(String value) {
        TextView view = text(value, 15, Color.rgb(200, 209, 221));
        view.setPadding(dp(16), dp(16), dp(16), dp(16));
        view.setBackground(cardBackground(Color.rgb(20, 24, 31), Color.rgb(42, 51, 64), 18));
        return view;
    }

    private Button button(String label, View.OnClickListener listener, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setTextColor(primary ? Color.rgb(8, 15, 25) : Color.WHITE);
        button.setBackground(cardBackground(
                primary ? Color.rgb(114, 181, 255) : Color.rgb(29, 35, 44),
                primary ? Color.rgb(150, 206, 255) : Color.rgb(56, 66, 81),
                18));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(12);
        button.setLayoutParams(lp);
        button.setPadding(dp(12), dp(4), dp(12), dp(4));
        button.setMinHeight(dp(52));
        return button;
    }

    private GradientDrawable cardBackground(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private TextView text(String value, float sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(0f, 1.12f);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
