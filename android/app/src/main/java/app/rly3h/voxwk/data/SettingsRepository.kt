package app.rly3h.voxwk.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.rly3h.voxwk.lang.OnlineProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// The two stores are declared once, at top level: that makes one DataStore per file per
// process however many repositories are created. Never build one with PreferenceDataStoreFactory.
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("settings")
private val Context.secretsStore: DataStore<Preferences> by preferencesDataStore("secrets")

/** Settings and API keys. Create with the application context. */
class SettingsRepository(context: Context) {
  private val context = context.applicationContext

  val settings: Flow<AppSettings> = this.context.settingsStore.data.map { prefs ->
    parseSettings(prefs.asMap().mapKeys { it.key.name })
  }

  suspend fun update(transform: (AppSettings) -> AppSettings) {
    context.settingsStore.edit { prefs ->
      val current = parseSettings(prefs.asMap().mapKeys { it.key.name })
      write(prefs, transform(current))
    }
  }

  // Keys are stored only as Tink ciphertext; the plaintext never touches prefs.
  // Each provider has its own slot (the OpenAI key and the Google key are distinct).
  private fun cipherKey(provider: OnlineProvider) = stringPreferencesKey(
    when (provider) {
      OnlineProvider.OPENAI -> "openaiKeyCipher"
      OnlineProvider.GEMINI -> "geminiKeyCipher"
    },
  )

  /** Decrypted key for [provider], or null if unset/undecryptable (treated as "no key"). */
  suspend fun apiKey(provider: OnlineProvider): String? =
    context.secretsStore.data.first()[cipherKey(provider)]?.let { SecureKeyStore.decrypt(context, provider, it) }

  /** Stores [plaintext] encrypted; null or blank clears the slot. */
  suspend fun setApiKey(provider: OnlineProvider, plaintext: String?) {
    val key = cipherKey(provider)
    val cipher = plaintext?.takeIf { it.isNotBlank() }?.let { SecureKeyStore.encrypt(context, provider, it) }
    context.secretsStore.edit { if (cipher == null) it.remove(key) else it[key] = cipher }
  }

  fun hasApiKey(provider: OnlineProvider): Flow<Boolean> =
    context.secretsStore.data.map { !it[cipherKey(provider)].isNullOrBlank() }

  private fun write(prefs: MutablePreferences, s: AppSettings) {
    prefs[stringPreferencesKey(SettingsKeys.MY_LANGUAGE)] = s.myLanguage.code
    val source = stringPreferencesKey(SettingsKeys.LISTEN_SOURCE)
    if (s.listenSource == null) prefs.remove(source) else prefs[source] = s.listenSource.code
    prefs[stringPreferencesKey(SettingsKeys.CONVERSATION_PARTNER)] = s.conversationPartner.code
    prefs[stringPreferencesKey(SettingsKeys.SESSION_MODE)] = s.sessionMode.name
    prefs[stringPreferencesKey(SettingsKeys.JA_RECOGNIZER)] = s.jaRecognizer.name
    prefs[stringPreferencesKey(SettingsKeys.MT_MODEL)] = s.mtModel.name
    prefs[booleanPreferencesKey(SettingsKeys.AUTO_SPEAK)] = s.autoSpeak
    prefs[floatPreferencesKey(SettingsKeys.SPEECH_RATE)] = s.speechRate
    prefs[booleanPreferencesKey(SettingsKeys.ONLINE_ENABLED)] = s.onlineEnabled
    prefs[stringPreferencesKey(SettingsKeys.ONLINE_PROVIDER)] = s.onlineProvider.name
    prefs[intPreferencesKey(SettingsKeys.ONLINE_IDLE_GAP)] = s.onlineIdleGapMs
    prefs[floatPreferencesKey(SettingsKeys.VAD_THRESHOLD)] = s.vad.threshold
    prefs[intPreferencesKey(SettingsKeys.VAD_SILENCE)] = s.vad.minSilenceMs
    prefs[intPreferencesKey(SettingsKeys.VAD_MIN_SPEECH)] = s.vad.minSpeechMs
    prefs[intPreferencesKey(SettingsKeys.VAD_MAX_SPEECH)] = s.vad.maxSpeechMs
    prefs[floatPreferencesKey(SettingsKeys.EP_RULE1)] = s.endpoint.rule1
    prefs[floatPreferencesKey(SettingsKeys.EP_RULE2)] = s.endpoint.rule2
    prefs[floatPreferencesKey(SettingsKeys.EP_RULE3)] = s.endpoint.rule3
  }
}
