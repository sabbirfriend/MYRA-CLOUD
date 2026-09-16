package com.myra.assistant.learning

import android.app.job.JobParameters
import android.app.job.JobService
import com.myra.assistant.utils.Constants
import com.myra.assistant.utils.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class LearningJobService : JobService() {
    private var job: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        if (!applicationContext.prefs().getBoolean(Constants.KEY_LEARNING_ENABLED, false)) return false
        job = CoroutineScope(Dispatchers.IO).launch {
            // Background learning only runs when a user-configured topic exists.
            val topic = applicationContext.prefs().getString("learning_topic", "AI, Android, technology and MYRA capabilities").orEmpty()
            LearningHub.learn(this@LearningJobService, topic)
            jobFinished(params, false)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { job?.cancel(); return true }
}
