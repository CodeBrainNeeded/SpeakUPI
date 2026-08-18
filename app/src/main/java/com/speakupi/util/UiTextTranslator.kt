package com.speakupi.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object UiTextTranslator {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val translationCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var translator: Translator? = null

    @Volatile
    private var translatorTargetLanguage: String? = null

    @Volatile
    private var isModelReady: Boolean = false

    private val lock = Any()

    fun translate(context: Context, text: String, onResult: (String) -> Unit) {
        if (text.isBlank()) {
            dispatch(text, onResult)
            return
        }

        if (QUOTED_SEGMENT_REGEX.containsMatchIn(text)) {
            translateWithQuotedSegments(context, text, onResult)
            return
        }

        val targetLanguage = resolveTargetLanguage(context)
        if (targetLanguage == null || targetLanguage == TranslateLanguage.ENGLISH) {
            dispatch(text, onResult)
            return
        }

        translationCache[text]?.let { cached ->
            dispatch(cached, onResult)
            return
        }

        val activeTranslator = getOrCreateTranslator(targetLanguage) ?: run {
            dispatch(text, onResult)
            return
        }

        ensureModel(activeTranslator) { modelReady ->
            if (!modelReady) {
                dispatch(text, onResult)
                return@ensureModel
            }

                activeTranslator.translate(text)
                .addOnSuccessListener { translated ->
                    translationCache[text] = translated
                    dispatch(restoreQuoteDelimiters(text, translated), onResult)
                }
                .addOnFailureListener {
                    dispatch(text, onResult)
                }
        }
    }

    fun translateList(context: Context, texts: List<String>, onResult: (List<String>) -> Unit) {
        if (texts.isEmpty()) {
            dispatchList(emptyList(), onResult)
            return
        }

        val translated = MutableList(texts.size) { "" }

        fun translateAt(index: Int) {
            if (index >= texts.size) {
                dispatchList(translated, onResult)
                return
            }

            translate(context, texts[index]) { value ->
                translated[index] = value
                translateAt(index + 1)
            }
        }

        translateAt(0)
    }

    private fun ensureModel(activeTranslator: Translator, onReady: (Boolean) -> Unit) {
        if (isModelReady) {
            onReady(true)
            return
        }

        val conditions = DownloadConditions.Builder().build()
        activeTranslator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                isModelReady = true
                onReady(true)
            }
            .addOnFailureListener {
                onReady(false)
            }
    }

    private fun resolveTargetLanguage(context: Context): String? {
        val locale = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale ?: Locale.getDefault()
        }

        return TranslateLanguage.fromLanguageTag(locale.toLanguageTag())
            ?: TranslateLanguage.fromLanguageTag(locale.language)
    }

    private fun getOrCreateTranslator(targetLanguage: String): Translator? {
        synchronized(lock) {
            if (translator != null && translatorTargetLanguage == targetLanguage) {
                return translator
            }

            translator?.close()
            translationCache.clear()
            isModelReady = false

            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(targetLanguage)
                .build()

            translator = Translation.getClient(options)
            translatorTargetLanguage = targetLanguage
            return translator
        }
    }

    private fun dispatch(text: String, onResult: (String) -> Unit) {
        if (Looper.getMainLooper().thread == Thread.currentThread()) {
            onResult(text)
            return
        }
        mainHandler.post { onResult(text) }
    }

    private fun restoreQuoteDelimiters(source: String, translated: String): String {
        val quotedValues = QUOTED_SEGMENT_REGEX.findAll(source)
            .map { it.value.removePrefix("\"").removeSuffix("\"") }
            .toList()

        if (quotedValues.isEmpty() || translated.contains('"')) {
            return translated
        }

        var restored = translated
        quotedValues.forEach { value ->
            val start = restored.indexOf(value)
            if (start >= 0) {
                restored = buildString {
                    append(restored.substring(0, start))
                    append('"')
                    append(value)
                    append('"')
                    append(restored.substring(start + value.length))
                }
            }
        }
        return restored
    }

    private fun translateWithQuotedSegments(
        context: Context,
        text: String,
        onResult: (String) -> Unit
    ) {
        val segments = mutableListOf<TranslationSegment>()
        var cursor = 0

        QUOTED_SEGMENT_REGEX.findAll(text).forEach { match ->
            if (match.range.first > cursor) {
                segments += TranslationSegment(text.substring(cursor, match.range.first), true)
            }
            segments += TranslationSegment(match.value, false)
            cursor = match.range.last + 1
        }

        if (cursor < text.length) {
            segments += TranslationSegment(text.substring(cursor), true)
        }

        val translatedSegments = MutableList(segments.size) { "" }
        fun translateAt(index: Int) {
            if (index >= segments.size) {
                dispatch(restoreQuoteDelimiters(text, translatedSegments.joinToString(separator = "")), onResult)
                return
            }

            val segment = segments[index]
            if (!segment.shouldTranslate) {
                translatedSegments[index] = segment.text
                translateAt(index + 1)
                return
            }

            translate(context, segment.text) { translated ->
                translatedSegments[index] = translated
                translateAt(index + 1)
            }
        }

        translateAt(0)
    }

    private fun dispatchList(texts: List<String>, onResult: (List<String>) -> Unit) {
        if (Looper.getMainLooper().thread == Thread.currentThread()) {
            onResult(texts)
            return
        }
        mainHandler.post { onResult(texts) }
    }

    private data class TranslationSegment(
        val text: String,
        val shouldTranslate: Boolean
    )

    private val QUOTED_SEGMENT_REGEX = Regex("\"[^\"]*\"")
}
