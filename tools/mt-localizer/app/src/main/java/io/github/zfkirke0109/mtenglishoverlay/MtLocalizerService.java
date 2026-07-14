package io.github.zfkirke0109.mtenglishoverlay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.TextRecognizerOptions;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MtLocalizerService extends AccessibilityService {
    public static final String ACTION_REFRESH =
            "io.github.zfkirke0109.mtenglishoverlay.REFRESH";

    private static final String[] MT_PACKAGES = {"bin.mt.plus", "bin.mt.plus.canary"};
    private static final int MAX_CANDIDATES = 90;
    private static final long MIN_CAPTURE_INTERVAL_MS = 650L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = this::refreshVisualTranslation;
    private final LinkedHashMap<String, OverlayItem> translatedItems = new LinkedHashMap<>();

    private WindowManager windowManager;
    private SharedPreferences prefs;
    private BeautifulOverlayView overlayView;
    private WindowManager.LayoutParams overlayParams;
    private TranslationEngine translationEngine;
    private TextRecognizer latinRecognizer;
    private TextRecognizer chineseRecognizer;
    private TextRecognizer japaneseRecognizer;
    private TextRecognizer koreanRecognizer;
    private boolean overlayAdded;
    private boolean receiverRegistered;
    private boolean processing;
    private long lastCaptureAt;
    private long frameGeneration;
    private long lastFingerprint;
    private Bitmap currentBitmap;

    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!isEnabledByUser()) {
                clearVisuals();
            } else {
                scheduleRefresh(80L);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        overlayView = new BeautifulOverlayView(this);
        translationEngine = new TranslationEngine(this, status -> {
            if (overlayView != null) overlayView.setStatus(status);
        });
        latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        chineseRecognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        japaneseRecognizer = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
        koreanRecognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());

        AccessibilityServiceInfo info = getServiceInfo();
        info.packageNames = MT_PACKAGES;
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_SCROLLED
                | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_VISUAL;
        info.notificationTimeout = 350L;
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
        scheduleRefresh(500L);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !isEnabledByUser()) {
            if (!isEnabledByUser()) clearVisuals();
            return;
        }
        CharSequence packageName = event.getPackageName();
        if (packageName != null && !isTargetPackage(packageName.toString())) return;

        long delay;
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_VIEW_SCROLLED:
                delay = 260L;
                break;
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
            case AccessibilityEvent.TYPE_WINDOWS_CHANGED:
                delay = 420L;
                break;
            default:
                delay = 520L;
        }
        scheduleRefresh(delay);
    }

    @Override
    public void onInterrupt() {
        clearVisuals();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        clearVisuals();
        if (translationEngine != null) translationEngine.close();
        if (latinRecognizer != null) latinRecognizer.close();
        if (chineseRecognizer != null) chineseRecognizer.close();
        if (japaneseRecognizer != null) japaneseRecognizer.close();
        if (koreanRecognizer != null) koreanRecognizer.close();
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

    private void refreshVisualTranslation() {
        if (!isEnabledByUser() || windowManager == null || processing) return;
        TargetWindow target = findTargetWindow();
        if (target == null) {
            clearVisuals();
            return;
        }

        long elapsed = SystemClock.elapsedRealtime() - lastCaptureAt;
        if (elapsed < MIN_CAPTURE_INTERVAL_MS) {
            scheduleRefresh(MIN_CAPTURE_INTERVAL_MS - elapsed);
            return;
        }

        ArrayList<Candidate> accessibilityCandidates = collectAccessibilityCandidates(target.root);
        processing = true;
        lastCaptureAt = SystemClock.elapsedRealtime();
        if (overlayView != null) overlayView.setStatus("Reading screen…");

        if (Build.VERSION.SDK_INT >= 34) {
            takeScreenshotOfWindow(target.windowId, getMainExecutor(),
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult screenshot) {
                            handleScreenshot(screenshot, accessibilityCandidates);
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            processing = false;
                            processCandidates(null, accessibilityCandidates);
                            if (overlayView != null) overlayView.setStatus(null);
                        }
                    });
        } else if (Build.VERSION.SDK_INT >= 30) {
            if (overlayView != null) overlayView.setVisibility(View.INVISIBLE);
            handler.postDelayed(() -> takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult screenshot) {
                            if (overlayView != null) overlayView.setVisibility(View.VISIBLE);
                            handleScreenshot(screenshot, accessibilityCandidates);
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            if (overlayView != null) overlayView.setVisibility(View.VISIBLE);
                            processing = false;
                            processCandidates(null, accessibilityCandidates);
                            if (overlayView != null) overlayView.setStatus(null);
                        }
                    }), 55L);
        } else {
            processing = false;
            processCandidates(null, accessibilityCandidates);
        }
    }

    private void handleScreenshot(
            AccessibilityService.ScreenshotResult screenshot,
            ArrayList<Candidate> accessibilityCandidates) {
        Bitmap bitmap = null;
        HardwareBuffer buffer = screenshot.getHardwareBuffer();
        try {
            ColorSpace colorSpace = screenshot.getColorSpace();
            if (colorSpace == null) colorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
            Bitmap hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
            if (hardwareBitmap != null) {
                bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
            }
        } catch (RuntimeException ignored) {
        } finally {
            try {
                buffer.close();
            } catch (Exception ignored) {
            }
        }

        if (bitmap == null) {
            processing = false;
            processCandidates(null, accessibilityCandidates);
            return;
        }

        long fingerprint = fingerprint(bitmap);
        if (fingerprint == lastFingerprint && !translatedItems.isEmpty()) {
            processing = false;
            if (overlayView != null) overlayView.setStatus(null);
            bitmap.recycle();
            return;
        }
        lastFingerprint = fingerprint;
        recognizeScreenshot(bitmap, accessibilityCandidates);
    }

    private void recognizeScreenshot(Bitmap bitmap, ArrayList<Candidate> accessibilityCandidates) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);
        ArrayList<TextRecognizer> order = recognizerOrder(accessibilityCandidates);
        runRecognizer(order, 0, image, bitmap, accessibilityCandidates);
    }

    private void runRecognizer(
            List<TextRecognizer> recognizers,
            int index,
            InputImage image,
            Bitmap bitmap,
            ArrayList<Candidate> accessibilityCandidates) {
        if (index >= recognizers.size()) {
            processing = false;
            processCandidates(bitmap, accessibilityCandidates);
            return;
        }

        recognizers.get(index).process(image)
                .addOnSuccessListener(result -> {
                    ArrayList<Candidate> ocr = candidatesFromText(result);
                    boolean useful = containsStrongForeignScript(ocr)
                            || index == recognizers.size() - 1;
                    if (useful) {
                        mergeCandidates(accessibilityCandidates, ocr);
                        processing = false;
                        processCandidates(bitmap, accessibilityCandidates);
                    } else {
                        runRecognizer(recognizers, index + 1, image, bitmap, accessibilityCandidates);
                    }
                })
                .addOnFailureListener(error ->
                        runRecognizer(recognizers, index + 1, image, bitmap, accessibilityCandidates));
    }

    private ArrayList<TextRecognizer> recognizerOrder(List<Candidate> candidates) {
        ArrayList<TextRecognizer> order = new ArrayList<>();
        String joined = joinSources(candidates);
        if (joined.matches(".*[\\p{IsHangul}].*")) {
            order.add(koreanRecognizer);
            order.add(chineseRecognizer);
            order.add(japaneseRecognizer);
        } else if (joined.matches(".*[\\p{IsHiragana}\\p{IsKatakana}].*")) {
            order.add(japaneseRecognizer);
            order.add(chineseRecognizer);
            order.add(koreanRecognizer);
        } else {
            order.add(chineseRecognizer);
            order.add(japaneseRecognizer);
            order.add(koreanRecognizer);
        }
        order.add(latinRecognizer);
        return order;
    }

    private void processCandidates(Bitmap bitmap, ArrayList<Candidate> candidates) {
        long generation = ++frameGeneration;
        translatedItems.clear();
        replaceCurrentBitmap(bitmap);
        ensureOverlay();
        if (overlayView != null) overlayView.setFrame(currentBitmap, new ArrayList<>());

        if (candidates == null || candidates.isEmpty()) {
            if (overlayView != null) overlayView.setStatus(null);
            return;
        }

        for (Candidate candidate : candidates) {
            if (candidate == null || !isCandidateText(candidate.source)) continue;
            translationEngine.translate(candidate.source, (source, english) -> {
                if (generation != frameGeneration || TextUtils.isEmpty(english)) return;
                OverlayItem item = new OverlayItem(candidate.bounds, source, english);
                translatedItems.put(item.key(), item);
                if (overlayView != null) {
                    overlayView.setItems(new ArrayList<>(translatedItems.values()));
                    overlayView.setStatus(null);
                }
            });
        }
    }

    private ArrayList<Candidate> collectAccessibilityCandidates(AccessibilityNodeInfo root) {
        LinkedHashMap<String, Candidate> output = new LinkedHashMap<>();
        if (root != null) collectNode(root, output, 0);
        return new ArrayList<>(output.values());
    }

    private void collectNode(
            AccessibilityNodeInfo node,
            Map<String, Candidate> output,
            int depth) {
        if (node == null || depth > 60 || output.size() >= MAX_CANDIDATES) return;
        CharSequence packageName = node.getPackageName();
        if (packageName != null && isTargetPackage(packageName.toString()) && node.isVisibleToUser()) {
            String source = nodeText(node);
            if (isCandidateText(source) && !node.isPassword()) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.width() >= dp(16) && bounds.height() >= dp(10)) {
                    Candidate candidate = new Candidate(bounds, source);
                    output.put(candidate.key(), candidate);
                }
            }
        }
        int childCount = Math.min(node.getChildCount(), 220);
        for (int i = 0; i < childCount && output.size() < MAX_CANDIDATES; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectNode(child, output, depth + 1);
        }
    }

    private ArrayList<Candidate> candidatesFromText(Text result) {
        LinkedHashMap<String, Candidate> output = new LinkedHashMap<>();
        if (result == null) return new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect bounds = line.getBoundingBox();
                String source = normalize(line.getText());
                if (bounds == null || !isCandidateText(source)) continue;
                if (bounds.width() < dp(14) || bounds.height() < dp(9)) continue;
                Candidate candidate = new Candidate(bounds, source);
                output.put(candidate.key(), candidate);
                if (output.size() >= MAX_CANDIDATES) break;
            }
            if (output.size() >= MAX_CANDIDATES) break;
        }
        return new ArrayList<>(output.values());
    }

    private void mergeCandidates(ArrayList<Candidate> base, List<Candidate> added) {
        if (added == null) return;
        for (Candidate candidate : added) {
            if (base.size() >= MAX_CANDIDATES) break;
            boolean duplicate = false;
            for (Candidate existing : base) {
                if (existing.source.equals(candidate.source) && overlapRatio(existing.bounds, candidate.bounds) > 0.55f) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) base.add(candidate);
        }
    }

    private boolean containsStrongForeignScript(List<Candidate> candidates) {
        for (Candidate candidate : candidates) {
            if (TranslationMemory.containsForeignScript(candidate.source)) return true;
        }
        return false;
    }

    private String joinSources(List<Candidate> candidates) {
        StringBuilder out = new StringBuilder();
        if (candidates != null) {
            for (Candidate candidate : candidates) out.append(candidate.source).append(' ');
        }
        return out.toString();
    }

    private boolean isCandidateText(String text) {
        if (text == null) return false;
        String trimmed = normalize(text);
        if (trimmed.isEmpty() || trimmed.length() > 1200) return false;
        if (!trimmed.matches(".*[\\p{L}].*")) return false;
        if (trimmed.matches("^[A-Fa-f0-9]{16,}$")) return false;
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return false;
        if (trimmed.matches("^[A-Za-z0-9_.$/:;<>\\[\\](){},=+*#@%-]+$")) return false;
        return true;
    }

    private TargetWindow findTargetWindow() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                CharSequence pkg = root.getPackageName();
                if (pkg != null && isTargetPackage(pkg.toString())) {
                    return new TargetWindow(window.getId(), root);
                }
            }
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null
                && isTargetPackage(root.getPackageName().toString())) {
            return new TargetWindow(root.getWindowId(), root);
        }
        return null;
    }

    private void ensureOverlay() {
        if (overlayAdded || overlayView == null || windowManager == null) return;
        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.setTitle("MT English visual replacement");
        if (Build.VERSION.SDK_INT >= 28) {
            overlayParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            windowManager.addView(overlayView, overlayParams);
            overlayAdded = true;
        } catch (RuntimeException ignored) {
        }
    }

    private void clearVisuals() {
        frameGeneration++;
        translatedItems.clear();
        if (overlayAdded && windowManager != null && overlayView != null) {
            try {
                windowManager.removeViewImmediate(overlayView);
            } catch (RuntimeException ignored) {
            }
        }
        overlayAdded = false;
        recycleCurrentBitmap();
    }

    private void replaceCurrentBitmap(Bitmap bitmap) {
        Bitmap old = currentBitmap;
        currentBitmap = bitmap;
        if (old != null && old != bitmap && !old.isRecycled()) old.recycle();
    }

    private void recycleCurrentBitmap() {
        if (currentBitmap != null && !currentBitmap.isRecycled()) currentBitmap.recycle();
        currentBitmap = null;
    }

    private String nodeText(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        if (text == null || text.toString().trim().isEmpty()) text = node.getContentDescription();
        return text == null ? "" : normalize(text.toString());
    }

    private long fingerprint(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return 0L;
        long hash = 1125899906842597L;
        int cols = 13;
        int rows = 23;
        for (int y = 0; y < rows; y++) {
            int py = Math.min(bitmap.getHeight() - 1, (y * bitmap.getHeight()) / rows);
            for (int x = 0; x < cols; x++) {
                int px = Math.min(bitmap.getWidth() - 1, (x * bitmap.getWidth()) / cols);
                hash = 31L * hash + bitmap.getPixel(px, py);
            }
        }
        return hash;
    }

    private float overlapRatio(Rect a, Rect b) {
        Rect intersection = new Rect();
        if (!intersection.setIntersect(a, b)) return 0f;
        float smaller = Math.max(1f, Math.min(a.width() * a.height(), b.width() * b.height()));
        return (intersection.width() * intersection.height()) / smaller;
    }

    private String normalize(String text) {
        return text == null ? "" : text.replace('\u00A0', ' ')
                .replaceAll("[\\t\\r\\n]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private boolean isEnabledByUser() {
        if (prefs == null) prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        return prefs.getBoolean(MainActivity.PREF_ENABLED, true);
    }

    private boolean isTargetPackage(String packageName) {
        if (packageName == null) return false;
        for (String target : MT_PACKAGES) if (target.equals(packageName)) return true;
        return false;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class TargetWindow {
        final int windowId;
        final AccessibilityNodeInfo root;

        TargetWindow(int windowId, AccessibilityNodeInfo root) {
            this.windowId = windowId;
            this.root = root;
        }
    }

    private static final class Candidate {
        final Rect bounds;
        final String source;

        Candidate(Rect bounds, String source) {
            this.bounds = new Rect(bounds);
            this.source = source;
        }

        String key() {
            return bounds.flattenToString() + "|" + source.toLowerCase(Locale.ROOT);
        }
    }
}
