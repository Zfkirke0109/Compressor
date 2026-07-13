package io.github.zfkirke0109.mtenglishoverlay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MtLocalizerService extends AccessibilityService {
    public static final String ACTION_REFRESH =
            "io.github.zfkirke0109.mtenglishoverlay.REFRESH";

    private static final String[] MT_PACKAGES = {"bin.mt.plus", "bin.mt.plus.canary"};
    private static final int MAX_OVERLAYS = 70;
    private static final int MAX_UNKNOWN = 500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<View> overlayViews = new ArrayList<>();
    private final Runnable refreshRunnable = this::refreshOverlays;

    private WindowManager windowManager;
    private SharedPreferences prefs;
    private boolean receiverRegistered;

    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!isEnabledByUser()) {
                clearOverlays();
            } else {
                scheduleRefresh(50L);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);

        AccessibilityServiceInfo info = getServiceInfo();
        info.packageNames = MT_PACKAGES;
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_SCROLLED
                | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_VISUAL;
        info.notificationTimeout = 250L;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        setServiceInfo(info);

        IntentFilter filter = new IntentFilter(ACTION_REFRESH);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(refreshReceiver, filter);
        }
        receiverRegistered = true;
        scheduleRefresh(300L);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !isEnabledByUser()) {
            if (!isEnabledByUser()) clearOverlays();
            return;
        }
        CharSequence packageName = event.getPackageName();
        if (packageName != null && !isTargetPackage(packageName.toString())) return;

        long delay = event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED
                ? 120L
                : 320L;
        scheduleRefresh(delay);
    }

    @Override
    public void onInterrupt() {
        clearOverlays();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        clearOverlays();
        if (receiverRegistered) {
            try {
                unregisterReceiver(refreshReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    private void scheduleRefresh(long delayMs) {
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, delayMs);
    }

    private void refreshOverlays() {
        clearOverlays();
        if (!isEnabledByUser() || windowManager == null) return;

        LinkedHashMap<String, Candidate> unique = new LinkedHashMap<>();
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null && !windows.isEmpty()) {
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) collectCandidates(root, unique, 0);
                if (unique.size() >= MAX_OVERLAYS) break;
            }
        } else {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) collectCandidates(root, unique, 0);
        }

        for (Candidate candidate : unique.values()) {
            if (overlayViews.size() >= MAX_OVERLAYS) break;
            addOverlay(candidate);
        }
    }

    private void collectCandidates(
            AccessibilityNodeInfo node,
            Map<String, Candidate> output,
            int depth) {
        if (node == null || depth > 60 || output.size() >= MAX_OVERLAYS) return;

        CharSequence packageName = node.getPackageName();
        if (packageName != null && isTargetPackage(packageName.toString()) && node.isVisibleToUser()) {
            String source = nodeText(node);
            if (isCandidateText(source) && !node.isPassword()) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.width() >= dp(18) && bounds.height() >= dp(12)) {
                    String english = TranslationMemory.translate(source);
                    if (english != null && !english.equals(source)) {
                        String key = bounds.flattenToString() + "|" + source;
                        output.put(key, new Candidate(bounds, source, english));
                    } else {
                        rememberUnknown(source);
                    }
                }
            }
        }

        int childCount = Math.min(node.getChildCount(), 200);
        for (int i = 0; i < childCount && output.size() < MAX_OVERLAYS; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectCandidates(child, output, depth + 1);
        }
    }

    private String nodeText(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        if (text == null || text.toString().trim().isEmpty()) {
            text = node.getContentDescription();
        }
        return text == null ? "" : text.toString().trim();
    }

    private boolean isCandidateText(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.length() > 1600) return false;
        if (!TranslationMemory.containsForeignScript(trimmed)) return false;

        // Preserve code, hashes, file paths, URLs, and package/class identifiers.
        if (trimmed.matches("^[A-Fa-f0-9]{16,}$")) return false;
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return false;
        if (trimmed.matches("^[A-Za-z0-9_.$/:;<>\\[\\]()-]+$")) return false;
        return true;
    }

    private void addOverlay(Candidate candidate) {
        Rect bounds = clampToScreen(candidate.bounds);
        if (bounds.isEmpty()) return;

        TextView view = new TextView(this);
        view.setText(candidate.english);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, chooseTextSize(bounds));
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        view.setPadding(dp(5), dp(2), dp(5), dp(2));
        view.setIncludeFontPadding(false);
        view.setMaxLines(Math.max(1, Math.min(12, bounds.height() / Math.max(dp(14), 1))));
        view.setEllipsize(TextUtils.TruncateAt.END);

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(238, 19, 23, 28));
        background.setCornerRadius(dp(4));
        background.setStroke(dp(1), Color.argb(220, 73, 146, 255));
        view.setBackground(background);
        view.setContentDescription("English translation: " + candidate.english);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                Math.max(bounds.width(), dp(64)),
                Math.max(bounds.height(), dp(24)),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = bounds.left;
        params.y = bounds.top;
        params.setTitle("MT English: " + candidate.source);
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        try {
            windowManager.addView(view, params);
            overlayViews.add(view);
        } catch (RuntimeException ignored) {
            // Window may have disappeared between accessibility traversal and overlay creation.
        }
    }

    private Rect clampToScreen(Rect original) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        Rect out = new Rect(original);
        out.left = Math.max(0, Math.min(out.left, metrics.widthPixels - 1));
        out.top = Math.max(0, Math.min(out.top, metrics.heightPixels - 1));
        out.right = Math.max(out.left + 1, Math.min(out.right, metrics.widthPixels));
        out.bottom = Math.max(out.top + 1, Math.min(out.bottom, metrics.heightPixels));
        return out;
    }

    private float chooseTextSize(Rect bounds) {
        int heightDp = Math.round(bounds.height() / getResources().getDisplayMetrics().density);
        if (heightDp <= 24) return 10f;
        if (heightDp <= 36) return 12f;
        if (heightDp <= 55) return 14f;
        return 15f;
    }

    private void clearOverlays() {
        if (windowManager == null) {
            overlayViews.clear();
            return;
        }
        for (View view : overlayViews) {
            try {
                windowManager.removeViewImmediate(view);
            } catch (RuntimeException ignored) {
            }
        }
        overlayViews.clear();
    }

    private void rememberUnknown(String source) {
        if (prefs == null || source == null) return;
        String normalized = source.replaceAll("[\\t\\r\\n]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        if (normalized.length() < 2 || normalized.length() > 500) return;

        Set<String> existing = prefs.getStringSet(MainActivity.PREF_UNKNOWN, Collections.emptySet());
        HashSet<String> copy = new HashSet<>(existing);
        if (copy.size() >= MAX_UNKNOWN || !copy.add(normalized)) return;
        prefs.edit().putStringSet(MainActivity.PREF_UNKNOWN, copy).apply();
    }

    private boolean isEnabledByUser() {
        if (prefs == null) {
            prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        }
        return prefs.getBoolean(MainActivity.PREF_ENABLED, true);
    }

    private boolean isTargetPackage(String packageName) {
        if (packageName == null) return false;
        for (String target : MT_PACKAGES) {
            if (target.equals(packageName)) return true;
        }
        return false;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class Candidate {
        final Rect bounds;
        final String source;
        final String english;

        Candidate(Rect bounds, String source, String english) {
            this.bounds = new Rect(bounds);
            this.source = source;
            this.english = english;
        }
    }
}
