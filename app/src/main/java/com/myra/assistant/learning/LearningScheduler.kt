package com.myra.assistant.learning

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.myra.assistant.utils.Constants
import com.myra.assistant.utils.prefs

object LearningScheduler {
    private const val JOB_ID = 7101
    fun apply(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        scheduler.cancel(JOB_ID)
        if (!context.prefs().getBoolean(Constants.KEY_LEARNING_ENABLED, false)) return
        val hours = context.prefs().getLong(Constants.KEY_LEARNING_INTERVAL_HOURS, 6L).coerceIn(1L, 24L)
        val info = JobInfo.Builder(JOB_ID, ComponentName(context, LearningJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(hours * 60L * 60L * 1000L)
            .setBackoffCriteria(15 * 60L * 1000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        scheduler.schedule(info)
    }
}
