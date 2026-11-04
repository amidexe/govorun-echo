package com.govorun.echo.ui

import android.app.Application
import com.govorun.echo.NetworkPolicy
import com.govorun.echo.smart.FairQueue
import com.govorun.echo.smart.GovorunSmart
import com.govorun.echo.smart.Smart
import com.govorun.echo.web.ComputerSession
import kotlinx.coroutines.*

class EchoApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        NetworkPolicy.initialize(this)
        Smart.initialize(this)
        ComputerSession.attach(this)
        scope.launch {
            NetworkPolicy.changes.collect {
                ComputerSession.networkChanged()
            }
        }
        scope.launch {
            Smart.changes.collect {
                if (Smart.available) GovorunSmart(this@EchoApplication).syncNetworkPolicy()
                FairQueue.schedule(this@EchoApplication)
            }
        }
    }
}
