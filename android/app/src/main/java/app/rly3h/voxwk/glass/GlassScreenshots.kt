package app.rly3h.voxwk.glass

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import app.rly3h.voxwk.glass.gm.GmMessage
import app.rly3h.voxwk.glass.gm.captureScreenshot
import app.rly3h.voxwk.glass.gm.toPng
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue

private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

/**
 * Captures what the Glass shows and saves it as a PNG in Pictures/voxwk. Blocks; run on IO. Throws IOException when
 * the capture fails, the link is closed, or the file cannot be written.
 */
fun captureGlassScreenshot(context: Context, link: GlassLink): Uri {
  val inbox = LinkedBlockingQueue<GmMessage>()
  link.onGmMessage = inbox::add
  val image = try {
    captureScreenshot(link.gm, inbox)
  } finally {
    link.onGmMessage = null
  }
  val png = image.toPng()

  // No permission is needed to insert into the shared media collections since API 29.
  val resolver = context.contentResolver
  val values = ContentValues().apply {
    put(MediaStore.Images.Media.DISPLAY_NAME, screenshotFileName(System.currentTimeMillis()))
    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/voxwk")
    put(MediaStore.Images.Media.IS_PENDING, 1)
  }
  val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
    ?: throw IOException("could not create the image file")
  try {
    (resolver.openOutputStream(uri) ?: throw IOException("could not open the image file")).use { it.write(png) }
    // The gallery shows the file only once it is no longer pending.
    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
  } catch (e: IOException) {
    resolver.delete(uri, null, null)
    throw e
  }
  return uri
}

internal fun screenshotFileName(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
  "voxwk-glass-${FILE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone))}.png"
