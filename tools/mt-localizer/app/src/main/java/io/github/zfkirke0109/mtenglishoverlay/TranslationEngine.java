package io.github.zfkirke0109.mtenglishoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class TranslationEngine {
    interface ResultCallback {
        void onResult(String source, @Nullable String english);
    }

    interface StatusCallback {
        void onStatus(@Nullable String status);
    }

    private static final String CACHE_PREFS = "on_device_translation_cache";

    private final SharedPreferences cache;
    private final LanguageIdentifier languageIdentifier;
    private final Map<String, Translator> translators = new HashMap<>();
    private final Set<String> pending = new HashSet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final StatusCallback statusCallback;

    TranslationEngine(Context context, StatusCallback statusCallback) {
        cache = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
        this.statusCallback = statusCallback;
        languageIdentifier = LanguageIdentification.getClient(
                new LanguageIdentificationOptions.Builder()
                        .setConfidenceThreshold(0.34f)
                        .build());
    }

    void translate(String source, ResultCallback callback) {
        if (source == null) {
            callback.onResult("", null);
            return;
        }
        String normalized = normalize(source);
        if (normalized.length() < 1 || normalized.length() > 1200) {
            callback.onResult(source, null);
            return;
        }

        String memory = TranslationMemory.translate(normalized);
        if (memory != null && !memory.equals(normalized) && !memory.startsWith("Partial: ")) {
            callback.onResult(source, memory);
            return;
        }

        String cached = cache.getString(cacheKey(normalized), null);
        if (!TextUtils.isEmpty(cached)) {
            callback.onResult(source, cached);
            return;
        }

        String pendingKey = cacheKey(normalized);
        synchronized (pending) {
            if (!pending.add(pendingKey)) return;
        }

        languageIdentifier.identifyLanguage(normalized)
                .addOnSuccessListener(languageTag -> {
                    String sourceLanguage = mapLanguage(languageTag, normalized);
                    if (sourceLanguage == null || TranslateLanguage.ENGLISH.equals(sourceLanguage)) {
                        finishPending(pendingKey);
                        callback.onResult(source, memory);
                        return;
                    }
                    translateWithModel(source, normalized, sourceLanguage, memory, pendingKey, callback);
                })
                .addOnFailureListener(error -> {
                    String sourceLanguage = mapLanguage("und", normalized);
                    if (sourceLanguage == null) {
                        finishPending(pendingKey);
                        callback.onResult(source, memory);
                    } else {
                        translateWithModel(source, normalized, sourceLanguage, memory, pendingKey, callback);
                    }
                });
    }

    private void translateWithModel(
            String originalSource,
            String normalized,
            String sourceLanguage,
            @Nullable String fallback,
            String pendingKey,
            ResultCallback callback) {
        Translator translator = translatorFor(sourceLanguage);
        if (translator == null) {
            finishPending(pendingKey);
            callback.onResult(originalSource, fallback);
            return;
        }

        postStatus("Preparing " + displayLanguage(sourceLanguage) + " → English…");
        DownloadConditions conditions = new DownloadConditions.Builder().build();
        translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener(unused -> {
                    postStatus("Translating on device…");
                    translator.translate(normalized)
                            .addOnSuccessListener(translated -> {
                                String cleaned = cleanTranslation(translated, normalized);
                                if (cleaned != null) {
                                    cache.edit().putString(cacheKey(normalized), cleaned).apply();
                                }
                                finishPending(pendingKey);
                                postStatus(null);
                                callback.onResult(originalSource, cleaned != null ? cleaned : fallback);
                            })
                            .addOnFailureListener(error -> {
                                finishPending(pendingKey);
                                postStatus(null);
                                callback.onResult(originalSource, fallback);
                            });
                })
                .addOnFailureListener(error -> {
                    finishPending(pendingKey);
                    postStatus("Translation model download unavailable");
                    mainHandler.postDelayed(() -> postStatus(null), 2400L);
                    callback.onResult(originalSource, fallback);
                });
    }

    @Nullable
    private Translator translatorFor(String sourceLanguage) {
        if (sourceLanguage == null || TranslateLanguage.ENGLISH.equals(sourceLanguage)) return null;
        synchronized (translators) {
            Translator existing = translators.get(sourceLanguage);
            if (existing != null) return existing;
            TranslatorOptions options = new TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLanguage)
                    .setTargetLanguage(TranslateLanguage.ENGLISH)
                    .build();
            Translator created = Translation.getClient(options);
            translators.put(sourceLanguage, created);
            return created;
        }
    }

    @Nullable
    private String mapLanguage(@Nullable String languageTag, String text) {
        if (languageTag != null && !"und".equals(languageTag)) {
            String mapped = TranslateLanguage.fromLanguageTag(languageTag);
            if (mapped != null) return mapped;
            int separator = languageTag.indexOf('-');
            if (separator > 0) {
                mapped = TranslateLanguage.fromLanguageTag(languageTag.substring(0, separator));
                if (mapped != null) return mapped;
            }
        }
        if (text.matches(".*[\\p{IsHan}].*")) return TranslateLanguage.CHINESE;
        if (text.matches(".*[\\p{IsHiragana}\\p{IsKatakana}].*")) return TranslateLanguage.JAPANESE;
        if (text.matches(".*[\\p{IsHangul}].*")) return TranslateLanguage.KOREAN;
        if (text.matches(".*[\\p{IsCyrillic}].*")) return TranslateLanguage.RUSSIAN;
        return null;
    }

    @Nullable
    private String cleanTranslation(@Nullable String translated, String source) {
        if (translated == null) return null;
        String cleaned = normalize(translated);
        if (cleaned.isEmpty() || cleaned.equalsIgnoreCase(source)) return null;
        return cleaned;
    }

    private void finishPending(String key) {
        synchronized (pending) {
            pending.remove(key);
        }
    }

    private void postStatus(@Nullable String status) {
        if (statusCallback == null) return;
        mainHandler.post(() -> statusCallback.onStatus(status));
    }

    int cachedTranslationCount() {
        return cache.getAll().size();
    }

    void clearCache() {
        cache.edit().clear().apply();
    }

    void close() {
        languageIdentifier.close();
        synchronized (translators) {
            for (Translator translator : translators.values()) translator.close();
            translators.clear();
        }
    }

    private String displayLanguage(String language) {
        Locale locale = Locale.forLanguageTag(language);
        String display = locale.getDisplayLanguage(Locale.ENGLISH);
        return TextUtils.isEmpty(display) ? language : display;
    }

    private String normalize(String text) {
        return text.replace('\u00A0', ' ')
                .replaceAll("[\\t\\r\\n]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private String cacheKey(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("tr_");
            for (byte value : bytes) out.append(String.format(Locale.US, "%02x", value));
            return out.toString();
        } catch (Exception ignored) {
            return "tr_" + Integer.toHexString(text.hashCode());
        }
    }
}
