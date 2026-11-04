package com.govorun.echo.web

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.*

/** One bounded outgoing operation. Android owns its network constraints and process lifetime. */
class ComputerJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch { try { ComputerSession.deliver(applicationContext) } finally { jobFinished(params, false); ComputerSession.schedule() } }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
