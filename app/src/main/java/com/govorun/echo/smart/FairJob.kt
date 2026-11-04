package com.govorun.echo.smart

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Takes [FairQueue] up in the background: Echo may be closed, and while the job runs Android keeps Echo alive, so Govorun's text
 * reaches the note. One job at a time; it schedules the next one itself, at the earliest due time.
 */
class FairJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        FairQueue.working = true
        work =
            scope.launch {
                try {
                    FairQueue.work(applicationContext)
                } finally {
                    FairQueue.working = false
                    jobFinished(params, false)
                    FairQueue.schedule(applicationContext)
                }
            }
        return true
    }

    /** Android ends the job early: a try under way still finishes and settles its note; the queue is scheduled again. */
    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return false
    }
}
