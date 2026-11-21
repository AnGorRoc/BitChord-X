package com.music.bitchord.data.feedback

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Feedback in the background, on JobScheduler (the app has no WorkManager):
 *
 * - [SEND_JOB] runs once as soon as there is a network, while something waits in the outbox,
 *   and backs off on its own when the server cannot be reached.
 * - [POLL_JOB] checks for answers every few hours, only while a ticket is open or was closed
 *   recently ([FeedbackCenter.shouldPollInBackground]); it is cancelled once nothing is pending,
 *   so most installs never run it at all. An unchanged inbox costs one 304.
 */
class FeedbackSyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        AndroidFeedback.scope.launch {
            val online = AndroidFeedback.runSync(0)
            // A send that found no server is retried with the job's own backoff.
            jobFinished(params, !online && params.jobId == SEND_JOB)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        // Arbitrary, but unique in the app: "FBK1" / "FBK2".
        const val POLL_JOB = 0x46424b31
        const val SEND_JOB = 0x46424b32
        private val POLL_EVERY_MS = TimeUnit.HOURS.toMillis(6)

        fun schedule(context: Context, center: FeedbackCenter) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            val component = ComponentName(context, FeedbackSyncJob::class.java)

            if (center.shouldPollInBackground()) {
                if (js.getPendingJob(POLL_JOB) == null) {
                    js.schedule(
                        JobInfo.Builder(POLL_JOB, component)
                            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                            .setPeriodic(POLL_EVERY_MS, TimeUnit.HOURS.toMillis(1))
                            .build(),
                    )
                }
            } else {
                js.cancel(POLL_JOB)
            }

            val waiting = center.state.value.outbox.any { it.failure == null }
            if (waiting) {
                if (js.getPendingJob(SEND_JOB) == null) {
                    js.schedule(
                        JobInfo.Builder(SEND_JOB, component)
                            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                            .build(),
                    )
                }
            } else {
                js.cancel(SEND_JOB)
            }
        }
    }
}
