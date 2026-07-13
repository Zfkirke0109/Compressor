package io.github.zfkirke0109.mtenglishoverlay;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

public final class MainActivity extends Activity {
    static final String PREFS = "mt_english_overlay";
    static final String PREF_ENABLED = "enabled";
    static final String PREF_UNKNOWN = "unknown_text";

    private SharedPreferences prefs;
    private TextView statusView;
    private TextView unknownView;
    private Button pauseButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(16, 18, 20));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(36));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("MT English Overlay", 27, Color.WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView subtitle = text(
                "A deterministic, no-cloud-AI English overlay for MT Manager. " +
                "It reads accessibility text from MT Manager and places English labels over non-English UI text.",
                16,
                Color.rgb(205, 214, 224));
        subtitle.setPadding(0, dp(10), 0, dp(18));
        root.addView(subtitle);

        statusView = text("", 16, Color.WHITE);
        statusView.setPadding(dp(14), dp(12), dp(14), dp(12));
        statusView.setBackgroundColor(Color.rgb(35, 40, 46));
        root.addView(statusView, matchWrap());

        root.addView(button("Enable accessibility service", v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }));

        pauseButton = button("", v -> {
            boolean next = !prefs.getBoolean(PREF_ENABLED, true);
            prefs.edit().putBoolean(PREF_ENABLED, next).apply();
            sendBroadcast(new Intent(MtLocalizerService.ACTION_REFRESH).setPackage(getPackageName()));
            refreshStatus();
        });
        root.addView(pauseButton);

        root.addView(button("Open MT Manager", v -> openMtManager()));

        TextView coverageTitle = text("What it can translate", 20, Color.WHITE);
        coverageTitle.setTypeface(coverageTitle.getTypeface(), android.graphics.Typeface.BOLD);
        coverageTitle.setPadding(0, dp(22), 0, dp(8));
        root.addView(coverageTitle);

        TextView coverage = text(
                "• MT Manager menus, dialogs, buttons, labels, plugin listings, and descriptions when Android exposes them as accessibility text.\n" +
                "• Simplified and Traditional Chinese technical vocabulary, plus common Japanese, Korean, Russian, Spanish, French, German, Portuguese, and Italian UI terms.\n" +
                "• Exact MT Manager phrases first, then deterministic phrase and term replacement.\n\n" +
                "It cannot read text drawn only as pixels, inside protected surfaces, or hidden from Android accessibility. " +
                "Those screens would require OCR, which is intentionally not included in this no-AI build.",
                15,
                Color.rgb(205, 214, 224));
        root.addView(coverage);

        TextView unknownTitle = text("Unknown text captured", 20, Color.WHITE);
        unknownTitle.setTypeface(unknownTitle.getTypeface(), android.graphics.Typeface.BOLD);
        unknownTitle.setPadding(0, dp(22), 0, dp(8));
        root.addView(unknownTitle);

        unknownView = text("", 14, Color.rgb(190, 200, 210));
        unknownView.setTextIsSelectable(true);
        unknownView.setPadding(dp(12), dp(10), dp(12), dp(10));
        unknownView.setBackgroundColor(Color.rgb(28, 32, 37));
        root.addView(unknownView, matchWrap());

        root.addView(button("Copy unknown text", v -> copyUnknown()));
        root.addView(button("Clear unknown text", v -> {
            prefs.edit().remove(PREF_UNKNOWN).apply();
            refreshStatus();
        }));

        TextView privacy = text(
                "Privacy: the app is restricted to MT Manager package names. It does not use the internet, does not use an AI model, " +
                "and stores only unknown visible phrases locally so the dictionary can be improved.",
                13,
                Color.rgb(145, 158, 171));
        privacy.setPadding(0, dp(24), 0, 0);
        root.addView(privacy);

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
        int unknown = prefs.getStringSet(PREF_UNKNOWN, Collections.emptySet()).size();

        String state = service ? "Accessibility service: ON" : "Accessibility service: OFF";
        state += enabled ? "\nOverlay translation: RUNNING" : "\nOverlay translation: PAUSED";
        statusView.setText(state);
        statusView.setTextColor(service && enabled
                ? Color.rgb(132, 255, 170)
                : Color.rgb(255, 199, 106));
        pauseButton.setText(enabled ? "Pause English overlay" : "Resume English overlay");

        Set<String> set = prefs.getStringSet(PREF_UNKNOWN, Collections.emptySet());
        ArrayList<String> list = new ArrayList<>(set);
        Collections.sort(list);
        StringBuilder preview = new StringBuilder();
        preview.append(unknown).append(" unique phrase").append(unknown == 1 ? "" : "s");
        int limit = Math.min(25, list.size());
        for (int i = 0; i < limit; i++) {
            preview.append("\n• ").append(list.get(i));
        }
        if (list.size() > limit) {
            preview.append("\n… and ").append(list.size() - limit).append(" more");
        }
        unknownView.setText(preview.toString());
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
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://mt2.cn/")));
        } catch (Exception e) {
            Toast.makeText(this, "MT Manager was not found.", Toast.LENGTH_LONG).show();
        }
    }

    private void copyUnknown() {
        Set<String> set = prefs.getStringSet(PREF_UNKNOWN, Collections.emptySet());
        ArrayList<String> list = new ArrayList<>(set);
        Collections.sort(list);
        String joined = android.text.TextUtils.join("\n", list);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("MT Manager unknown text", joined));
        Toast.makeText(this, "Unknown text copied.", Toast.LENGTH_SHORT).show();
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(12);
        button.setLayoutParams(lp);
        return button;
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
