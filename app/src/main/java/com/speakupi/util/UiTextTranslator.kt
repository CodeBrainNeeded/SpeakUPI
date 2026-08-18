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

        if (text.contains('"')) {
            dispatch(text, onResult)
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
                    dispatch(translated, onResult)
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

    private fun dispatchList(texts: List<String>, onResult: (List<String>) -> Unit) {
        if (Looper.getMainLooper().thread == Thread.currentThread()) {
            onResult(texts)
            return
        }
        mainHandler.post { onResult(texts) }
    }
}
