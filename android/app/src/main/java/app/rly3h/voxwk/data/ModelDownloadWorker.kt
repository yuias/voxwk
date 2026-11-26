package app.rly3h.voxwk.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.rly3h.voxwk.R
import app.rly3h.voxwk.VoxwkApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Downloads and extracts one model (input `id`) as a foreground `dataSync` job.
 * Progress is published as `bytes`, `total` (-1 when unknown) and `phase`
 * ([Models.PHASE_DOWNLOAD] or [Models.PHASE_EXTRACT]).
 * An [IOException] retries with WorkManager's backoff and resumes the `.part`; a failed
 * integrity check or HTTP error fails the work with an `error` message.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  private val modelId = inputData.getString(KEY_ID)!!

  override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

  override suspend fun doWork(): Result {
    setForeground(foregroundInfo())
    var lastReport = System.nanoTime()
    return try {
      withContext(Dispatchers.IO) {
        Models.ensure(
          applicationContext,
          modelId,
          cancel = { isStopped },
          onTransfer = { phase, bytes, total ->
            // The database write behind setProgress is too costly to run for every 64 KB buffer.
            val now = System.nanoTime()
            if (now - lastReport >= REPORT_INTERVAL_NS) {
              lastReport = now
              setProgressAsync(workDataOf(KEY_BYTES to bytes, KEY_TOTAL to total, KEY_PHASE to phase))
              applicationContext.getSystemService(NotificationManager::class.java)
                .notify(notificationId(), buildNotification(phase, bytes, total))
            }
          },
        )
      }
      (applicationContext as VoxwkApp).preloader.refresh()
      Result.success()
    } catch (e: ModelCancelled) {
      Result.failure()
    } catch (e: IOException) {
      Result.retry()
    } catch (e: Exception) {
      Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.toString())))
    }
  }

  private fun notificationId() = NOTIFICATION_ID_BASE + modelId.hashCode().and(0xFFFF)

  private fun foregroundInfo(): ForegroundInfo {
    val nm = applicationContext.getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL_ID) == null) {
      nm.createNotificationChannel(
        NotificationChannel(
          CHANNEL_ID,
          applicationContext.getString(R.string.model_download_channel_name),
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = applicationContext.getString(R.string.model_download_channel_description)
          setShowBadge(false)
        }
      )
    }
    val n = buildNotification(Models.PHASE_DOWNLOAD, 0L, -1L)
    return ForegroundInfo(notificationId(), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
  }

  private fun buildNotification(phase: String, bytes: Long, total: Long): Notification {
    val ctx = applicationContext
    val name = Models.spec(ctx, modelId).displayName
    val text = ctx.getString(
      if (phase == Models.PHASE_EXTRACT) R.string.model_download_extracting else R.string.model_download_downloading,
      name,
    )
    val determinate = phase == Models.PHASE_DOWNLOAD && total > 0
    val cancel = WorkManager.getInstance(ctx).createCancelPendingIntent(id)
    return NotificationCompat.Builder(ctx, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle(ctx.getString(R.string.app_name))
      .setContentText(text)
      .setProgress(1000, if (determinate) (bytes * 1000 / total).toInt() else 0, !determinate)
      .addAction(0, ctx.getString(R.string.model_download_cancel), cancel)
      .setOngoing(true)
      .setSilent(true)
      .setOnlyAlertOnce(true)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .build()
  }

  companion object {
    const val KEY_ID = "id"
    const val KEY_BYTES = "bytes"
    const val KEY_TOTAL = "total"
    const val KEY_PHASE = "phase"
    const val KEY_ERROR = "error"
    private const val CHANNEL_ID = "model_download"
    // RecordingService uses id 1; one id per model keeps parallel downloads from replacing each other.
    private const val NOTIFICATION_ID_BASE = 0x10000
    private const val REPORT_INTERVAL_NS = 500_000_000L
  }
}
