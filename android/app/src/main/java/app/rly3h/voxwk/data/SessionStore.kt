package app.rly3h.voxwk.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** One transcribed + translated turn within a session. */
@Serializable
data class LoggedUtterance(
  val transcript: String,
  val srcLang: String?,
  val tgtLang: String,
  val detected: String,
  val translation: String,
  val speaker: String?, // "ME" | "PARTNER" in Conversation mode; null for Listen
  val at: Long,
)

/** A saved session: a time-ordered list of turns. */
@Serializable
data class SessionLog(
  val id: String,
  val startedAt: Long,
  val mode: String, // "LISTEN" | "CONVERSATION"
  val input: String, // "PHONE" | "GLASSES"
  val utterances: List<LoggedUtterance>,
)

/**
 * File-backed session persistence: one JSON document per session under
 * `filesDir/sessions`. Chosen over a database since sessions are small and
 * few — directory listing is enough, with no schema or extra dependency.
 */
object SessionStore {
  private val json = Json { ignoreUnknownKeys = true }

  fun encode(session: SessionLog): String = json.encodeToString(session)

  /** Null when the text is not a complete session document. */
  fun decode(text: String): SessionLog? =
    runCatching { json.decodeFromString<SessionLog>(text) }.getOrNull()

  private fun dir(context: Context): File =
    File(context.filesDir, "sessions").apply { mkdirs() }

  fun newId(startedAt: Long): String = startedAt.toString()

  /** Writes the session, overwriting any prior copy (called as it grows). */
  fun save(context: Context, session: SessionLog) {
    File(dir(context), "${session.id}.json").writeText(encode(session))
  }

  /** All saved sessions, newest first. Corrupt files are skipped, not fatal. */
  fun list(context: Context): List<SessionLog> =
    dir(context).listFiles { f -> f.extension == "json" }
      ?.mapNotNull { decode(it.readText()) }
      ?.sortedByDescending { it.startedAt }
      ?: emptyList()

  fun load(context: Context, id: String): SessionLog? {
    val f = File(dir(context), "$id.json")
    return if (f.exists()) decode(f.readText()) else null
  }

  fun delete(context: Context, id: String) {
    File(dir(context), "$id.json").delete()
  }
}
