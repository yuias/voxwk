package app.rly3h.voxwk.translate

import android.content.Context
import app.rly3h.voxwk.data.Models
import app.rly3h.voxwk.lang.AsrEngine
import java.io.File
import uniffi.translatecore.asrStreamUnload
import uniffi.translatecore.asrUnload
import uniffi.translatecore.mtUnload
import uniffi.translatecore.vadUnload

/** Absolute path of a model's checkFile (for MT: the .gguf passed to mtLoad). */
internal fun modelFile(context: Context, id: String): File =
  File(Models.dir(context, id), Models.spec(context, id).checkFile)

/**
 * Frees the ASR engines the session does not use, so a change of recognizer gives its
 * memory back instead of keeping both resident. Blocking; call on IO.
 */
internal fun releaseUnusedEngines(engines: Set<AsrEngine>) {
  if (AsrEngine.NEMOTRON !in engines) asrStreamUnload()
  if (AsrEngine.SENSEVOICE !in engines) {
    asrUnload()
    vadUnload()
  }
}

/**
 * Unloads whatever engine serves model [id], before its files are deleted (the
 * GGUF is memory-mapped, and the ONNX engines keep their files open). Blocking.
 */
internal fun unloadEngineFor(id: String) {
  when {
    id == "vad" -> vadUnload()
    id == "asr" -> asrUnload()
    id == "asr_stream" -> asrStreamUnload()
    id.startsWith("mt_") -> mtUnload()
  }
}
