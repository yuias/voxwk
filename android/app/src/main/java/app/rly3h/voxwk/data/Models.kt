package app.rly3h.voxwk.data

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@Serializable
data class ModelManifest(
  val models: List<ModelSpec>,
)

@Serializable
data class ModelSpec(
  val id: String,
  val dir: String,
  val checkFile: String,
  val files: List<FileSpec> = emptyList(),
  val archive: ArchiveSpec? = null,
  // Human-readable name for Settings; falls back to id.
  val label: String? = null,
  // Rough download size shown before the model is fetched; null hides the hint.
  val approxMb: Int? = null,
) {
  val displayName: String get() = label ?: id
}

// size/sha256 are verified before the download is renamed into place; null skips that check.
@Serializable data class FileSpec(val name: String, val url: String, val size: Long? = null, val sha256: String? = null)

@Serializable data class ArchiveSpec(val url: String, val format: String, val size: Long? = null, val sha256: String? = null)

/**
 * Returns a human-readable error when a finished download does not match the manifest,
 * or null when it is acceptable. A null expectation is not checked; the sha256 comparison
 * ignores case.
 */
internal fun integrityError(
  name: String,
  expectedSize: Long?,
  actualSize: Long,
  expectedSha256: String?,
  actualSha256: String,
): String? {
  if (expectedSize != null && expectedSize != actualSize) {
    return "$name: size mismatch (expected $expectedSize bytes, got $actualSize)"
  }
  if (expectedSha256 != null && !expectedSha256.equals(actualSha256, ignoreCase = true)) {
    return "$name: sha256 mismatch (expected $expectedSha256, got $actualSha256)"
  }
  return null
}

/** What to do with an existing `.part` given the status of the (possibly ranged) response. */
sealed interface ResumeAction {
  /** 206: keep the `.part` and continue the transfer after it. */
  data object Append : ResumeAction

  /** 200: the server ignored the range (or there was no `.part`); start the file over. */
  data object Restart : ResumeAction

  /** Anything else, including 416; the `.part` is deleted and the download fails. */
  data class Fail(val code: Int) : ResumeAction
}

fun resumeAction(partBytes: Long, responseCode: Int): ResumeAction = when (responseCode) {
  206 -> if (partBytes > 0) ResumeAction.Append else ResumeAction.Restart
  200 -> ResumeAction.Restart
  else -> ResumeAction.Fail(responseCode)
}

/** True when a `.part` already holds the whole file, so a Range request would only get a 416. */
internal fun partIsComplete(partBytes: Long, expectedSize: Long?): Boolean =
  expectedSize != null && partBytes > 0 && partBytes == expectedSize

/** Feeds the bytes already on disk into [digest] so a resumed download hashes the whole file. */
internal fun hashInto(digest: MessageDigest, file: File) {
  file.inputStream().buffered().use { input ->
    val buf = ByteArray(1 shl 16)
    while (true) {
      val n = input.read(buf)
      if (n < 0) break
      digest.update(buf, 0, n)
    }
  }
}

internal fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

/** Thrown by [Models.ensure] when a `cancel` callback returns true mid-transfer. */
class ModelCancelled : Exception("download cancelled")

/**
 * First-run model provisioning: reads assets/models.json and downloads each
 * model into internal storage (filesDir).
 * Internal storage is required because the NDK's raw open() is denied on
 * external Android/data on some OEMs.
 */
object Models {
  const val PHASE_DOWNLOAD = "download"
  const val PHASE_EXTRACT = "extract"

  private val json = Json { ignoreUnknownKeys = true }

  private fun workName(id: String) = "model-$id"

  /** Queues the download for [id]; a no-op while one for the same model is already queued or running. */
  fun enqueue(context: Context, id: String) {
    val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
      .setInputData(workDataOf(ModelDownloadWorker.KEY_ID to id))
      // Expedited work may start its foreground service when a retry runs in the background (API 31+).
      .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, request)
  }

  /**
   * Stops the download for [id] and discards its partial files. The worker sees `isStopped` and aborts
   * with [ModelCancelled]; a stop that does not come through here (the system stopping the worker) keeps
   * the partial files so the retry resumes. Blocking file work — call from an IO thread.
   */
  fun cancel(context: Context, id: String) {
    WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    val s = spec(context, id)
    File(context.filesDir, s.dir).walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.forEach { it.delete() }
    File(context.cacheDir, "$id-archive").delete()
    File(context.cacheDir, "$id-archive.part").delete()
  }

  /** State and progress (`bytes`, `total`, `phase`) of the download for [id]; null when none was queued. */
  fun workInfo(context: Context, id: String): Flow<WorkInfo?> =
    WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName(id)).map { it.firstOrNull() }

  fun manifest(context: Context): ModelManifest {
    val text = context.assets.open("models.json").bufferedReader().use { it.readText() }
    return json.decodeFromString(text)
  }

  fun spec(context: Context, id: String): ModelSpec =
    manifest(context).models.first { it.id == id }

  fun dir(context: Context, id: String): File =
    File(context.filesDir, spec(context, id).dir)

  fun isPresent(context: Context, id: String): Boolean {
    val s = spec(context, id)
    return File(File(context.filesDir, s.dir), s.checkFile).exists()
  }

  /**
   * Bytes currently on disk under the model's directory, including incomplete
   * files-mode downloads (`*.part`, or some files missing). 0 when the directory
   * does not exist.
   */
  fun sizeOnDisk(context: Context, id: String): Long {
    val dir = dir(context, id)
    if (!dir.exists()) return 0L
    return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
  }

  /**
   * Removes the model and any download leftovers so the next [ensure] fetches it
   * again. Blocking — call from an IO thread. The caller must ensure nothing is
   * downloading or using the model; a model already loaded in native memory keeps
   * working until process restart.
   */
  fun delete(context: Context, id: String) {
    val s = spec(context, id)
    File(context.filesDir, s.dir).deleteRecursively()
    File(context.filesDir, ".staging-$id").deleteRecursively()
    // Archive mode downloads to "$id-archive" and download() writes ".part" beside it.
    File(context.cacheDir, "$id-archive").delete()
    File(context.cacheDir, "$id-archive.part").delete()
  }

  /**
   * Downloads/extracts the model if missing. Blocking — call from an IO thread.
   * `cancel` is polled during transfer/extraction; returning true aborts with
   * [ModelCancelled]; the partial download stays on disk, and the next call resumes
   * it ([cancel] is what discards it). A failed integrity check or HTTP error removes it.
   * `onTransfer` reports (phase, bytes, total); total is -1 when unknown.
   */
  fun ensure(
    context: Context,
    id: String,
    onProgress: (String) -> Unit = {},
    // Fraction in 0..1 for a determinate progress bar; null means "indeterminate"
    // (size unknown — e.g. during bz2 extraction or when the server omits a length).
    onFraction: (Float?) -> Unit = {},
    cancel: () -> Boolean = { false },
    onTransfer: (phase: String, bytes: Long, total: Long) -> Unit = { _, _, _ -> },
  ) {
    val s = spec(context, id)
    val target = File(File(context.filesDir, s.dir), s.checkFile)
    if (target.exists()) {
      onProgress("$id: present")
      return
    }
    val archive = s.archive
    if (archive != null) {
      // tar.bz2 contains a top-level folder == s.dir. Extract into a staging dir
      // first and only swap it into place once complete, so a cancelled/failed
      // extraction never leaves a half-written model that looks "present".
      val tmp = File(context.cacheDir, "$id-archive")
      val staging = File(context.filesDir, ".staging-$id")
      // A leftover archive is a verified download whose extraction was interrupted.
      var keepArchive = false
      if (!tmp.exists()) {
        onProgress("$id: downloading…")
        // Verified before extraction so a corrupt archive never reaches the staging dir.
        download(archive.url, tmp, cancel, id, archive.size, archive.sha256) { written, total ->
          onProgress("$id: ${written / 1_000_000} MB")
          onFraction(if (total > 0) written.toFloat() / total else null)
          onTransfer(PHASE_DOWNLOAD, written, total)
        }
      }
      onProgress("$id: extracting…")
      onFraction(null) // uncompressed size isn't known up front → indeterminate
      try {
        staging.deleteRecursively()
        staging.mkdirs()
        extractTarBz2(tmp, staging, cancel) { b ->
          onProgress("$id: extracting… ${b / 1_000_000} MB")
          onTransfer(PHASE_EXTRACT, b, -1)
        }
        val extracted = File(staging, s.dir)
        check(extracted.isDirectory) { "archive missing top-level dir ${s.dir}" }
        val finalDir = File(context.filesDir, s.dir)
        finalDir.deleteRecursively()
        check(extracted.renameTo(finalDir)) { "failed to move ${s.dir} into place" }
      } catch (e: ModelCancelled) {
        keepArchive = true
        throw e
      } finally {
        staging.deleteRecursively()
        if (!keepArchive) tmp.delete()
      }
    } else {
      val dir = File(context.filesDir, s.dir).apply { mkdirs() }
      for (f in s.files) {
        // Skip files already on disk so adding one new file (e.g. a swapped
        // decoder) doesn't re-download the unchanged ones.
        if (File(dir, f.name).exists()) {
          onProgress("$id: ${f.name} present")
          continue
        }
        onProgress("$id: ${f.name}…")
        download(f.url, File(dir, f.name), cancel, "$id/${f.name}", f.size, f.sha256) { written, total ->
          onProgress("$id: ${f.name} ${written / 1_000_000} MB")
          onFraction(if (total > 0) written.toFloat() / total else null)
          onTransfer(PHASE_DOWNLOAD, written, total)
        }
      }
    }
    onProgress("$id: done")
  }

  // Streaming download that manually follows redirects (HF/GitHub CDN hops).
  // onProgress reports (bytesWritten, contentLength); contentLength is -1 when unknown.
  // The SHA-256 is computed while writing; the .part file is renamed to `dest` only when
  // size/sha256 match, so a file that exists under its final name is always verified.
  // An existing .part is resumed with a Range request (see resumeAction); a transfer that
  // fails with an IOException or is cancelled keeps its .part for the next attempt.
  private fun download(
    urlStr: String,
    dest: File,
    cancel: () -> Boolean,
    name: String,
    expectedSize: Long?,
    expectedSha256: String?,
    onProgress: (Long, Long) -> Unit,
  ) {
    val part = File(dest.parentFile, dest.name + ".part")
    val partBytes = if (part.exists()) part.length() else 0L
    if (partIsComplete(partBytes, expectedSize)) {
      val digest = MessageDigest.getInstance("SHA-256")
      hashInto(digest, part)
      verifyAndRename(part, dest, name, expectedSize, partBytes, expectedSha256, digest)
      return
    }
    var url = urlStr
    var hops = 0
    while (true) {
      val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 30_000
        readTimeout = 60_000
        // Each redirect hop is a new connection, so the range is sent on every one.
        if (partBytes > 0) setRequestProperty("Range", "bytes=$partBytes-")
      }
      val code = conn.responseCode
      if (code in 300..399) {
        val loc = conn.getHeaderField("Location") ?: error("redirect without Location")
        conn.disconnect()
        url = if (loc.startsWith("http")) loc else URL(URL(url), loc).toString()
        if (++hops > 8) error("too many redirects")
        continue
      }
      val action = resumeAction(partBytes, code)
      if (action is ResumeAction.Fail) {
        conn.disconnect()
        part.delete()
        error("HTTP ${action.code} for $url")
      }
      val append = action == ResumeAction.Append
      val contentLength = conn.contentLengthLong // -1 when the server omits it
      val base = if (append) partBytes else 0L
      val totalLength = if (contentLength < 0) -1L else base + contentLength
      var cancelled = false
      var total = 0L
      val digest = MessageDigest.getInstance("SHA-256")
      if (append) hashInto(digest, part)
      conn.inputStream.use { input ->
        FileOutputStream(part, append).use { out ->
          val buf = ByteArray(1 shl 16)
          var written = base
          while (true) {
            if (cancel()) {
              cancelled = true
              break
            }
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            digest.update(buf, 0, n)
            written += n
            onProgress(written, totalLength)
          }
          total = written
        }
      }
      conn.disconnect()
      if (cancelled) throw ModelCancelled()
      verifyAndRename(part, dest, name, expectedSize, total, expectedSha256, digest)
      return
    }
  }

  private fun verifyAndRename(
    part: File,
    dest: File,
    name: String,
    expectedSize: Long?,
    actualSize: Long,
    expectedSha256: String?,
    digest: MessageDigest,
  ) {
    integrityError(name, expectedSize, actualSize, expectedSha256, digest.hex())?.let { msg ->
      part.delete()
      throw IllegalStateException(msg)
    }
    if (dest.exists()) dest.delete()
    check(part.renameTo(dest)) { "rename ${part.name} -> ${dest.name} failed" }
  }

  private fun extractTarBz2(
    archive: File,
    destDir: File,
    cancel: () -> Boolean,
    onProgress: (Long) -> Unit,
  ) {
    val destRoot = destDir.canonicalFile
    var written = 0L
    archive.inputStream().buffered().use { fin ->
      BZip2CompressorInputStream(fin).use { bz ->
        TarArchiveInputStream(bz).use { tar ->
          var entry = tar.nextEntry
          while (entry != null) {
            if (cancel()) throw ModelCancelled()
            val out = File(destDir, entry.name).canonicalFile
            require(out.path.startsWith(destRoot.path)) { "tar entry escapes dest: ${entry.name}" }
            if (entry.isDirectory) {
              out.mkdirs()
            } else {
              out.parentFile?.mkdirs()
              out.outputStream().use { os ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                  if (cancel()) throw ModelCancelled()
                  val n = tar.read(buf)
                  if (n < 0) break
                  os.write(buf, 0, n)
                  written += n
                  onProgress(written)
                }
              }
            }
            entry = tar.nextEntry
          }
        }
      }
    }
  }
}
