package com.example.handshakecontactcapture.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only ciphertext is persisted. Never include this key in logs, saved UI state or WorkManager data. */
class AiSettings(context: Context, private val storageName: String = "openai_private") {
    private val preferences = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    val extractionModel: String get() = preferences.getString("extraction_model", "gpt-4.1-mini")!!
    val researchModel: String get() = preferences.getString("research_model", "gpt-4.1")!!
    val summaryModel: String get() = preferences.getString("summary_model", "gpt-4.1-mini")!!
    val transcriptionModel: String get() = preferences.getString("transcription_model", "gpt-4o-mini-transcribe")!!
    val hasKey: Boolean get() = preferences.contains("ciphertext")

    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("handshake.$storageName", null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("handshake.$storageName",
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
    }

    fun readKey(): String {
        val encrypted = preferences.getString("ciphertext", null) ?: return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128,
                Base64.decode(preferences.getString("iv", ""), Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            throw AiFailure("The saved key could not be unlocked. Remove it and enter it again in AI settings.")
        }
    }

    fun save(key: String, extraction: String, research: String, summary: String = summaryModel, transcription: String = transcriptionModel) {
        require(transcription.matches(Regex("[a-zA-Z0-9._-]{1,100}"))) { "Enter a valid transcription model ID." }
        require(extraction.matches(Regex("[a-zA-Z0-9._-]{1,100}")) &&
            research.matches(Regex("[a-zA-Z0-9._-]{1,100}")) && summary.matches(Regex("[a-zA-Z0-9._-]{1,100}"))) { "Enter valid model IDs." }
        val edit = preferences.edit().putString("extraction_model", extraction).putString("research_model", research).putString("summary_model", summary)
            .putString("transcription_model", transcription)
        if (key.isNotBlank()) {
            require(key.trim().startsWith("sk-") && key.trim().none { it.isWhitespace() }) { "Enter an OpenAI API key beginning with sk-." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            edit.putString("ciphertext", Base64.encodeToString(cipher.doFinal(key.trim().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
        check(edit.commit()) { "Could not save AI settings." }
    }

    fun remove() {
        check(preferences.edit().remove("ciphertext").remove("iv").commit())
    }
}
