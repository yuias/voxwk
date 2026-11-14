package app.rly3h.voxwk.data

import android.content.Context
import android.util.Base64
import app.rly3h.voxwk.lang.OnlineProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * Encrypts the online providers' API keys at rest using Google Tink AEAD.
 *
 * Tink keeps its own AES256-GCM data key in a dedicated SharedPreferences file,
 * wrapped by a master key held in the Android Keystore (hardware-backed where
 * available). Only the base64 ciphertext is persisted, in the secrets DataStore.
 * This replaces the deprecated EncryptedSharedPreferences while keeping the
 * surface tiny.
 */
object SecureKeyStore {
  private const val KEYSET_NAME = "voxwk_tink_keyset"
  private const val PREF_FILE = "voxwk_tink_prefs"
  private const val MASTER_KEY_URI = "android-keystore://voxwk_master_key"

  // Associated data binds a ciphertext to this app and provider (not secret), so one
  // provider's ciphertext cannot be moved into the other's slot.
  private fun aad(provider: OnlineProvider): ByteArray =
    "voxwk.apikey.${provider.name.lowercase()}".toByteArray(Charsets.UTF_8)

  @Volatile private var cached: Aead? = null

  private fun aead(context: Context): Aead {
    cached?.let { return it }
    return synchronized(this) {
      cached ?: run {
        AeadConfig.register()
        val handle = AndroidKeysetManager.Builder()
          .withSharedPref(context.applicationContext, KEYSET_NAME, PREF_FILE)
          .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
          .withMasterKeyUri(MASTER_KEY_URI)
          .build()
          .keysetHandle
        handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java).also { cached = it }
      }
    }
  }

  /** Encrypts [plaintext] for [provider] and returns base64 (NO_WRAP) ciphertext. */
  fun encrypt(context: Context, provider: OnlineProvider, plaintext: String): String {
    val ct = aead(context).encrypt(plaintext.toByteArray(Charsets.UTF_8), aad(provider))
    return Base64.encodeToString(ct, Base64.NO_WRAP)
  }

  /**
   * Decrypts base64 ciphertext. Returns null on any failure (corrupt data, a
   * lost keyset after a device transfer, cleared Keystore) so callers treat it
   * as "no key set" and prompt re-entry rather than crashing.
   */
  fun decrypt(context: Context, provider: OnlineProvider, b64: String): String? = runCatching {
    val ct = Base64.decode(b64, Base64.NO_WRAP)
    String(aead(context).decrypt(ct, aad(provider)), Charsets.UTF_8)
  }.getOrNull()
}
