package com.govorun.echo.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.govorun.echo.R
import com.govorun.echo.backup.ArchiveRestore
import com.govorun.echo.backup.UserTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class TransferState(val busy:Boolean=false,val message:String="",val pending:UserTransfer.Pending?=null,val settings:Boolean=true,val restored:Boolean=false)
internal class TransferViewModel(application:Application):AndroidViewModel(application) {
    private val mutable=MutableStateFlow(TransferState())
    val state=mutable.asStateFlow()
    @Volatile private var closed=false
    private val app get()=getApplication<Application>()
    fun settings(value:Boolean){mutable.update{it.copy(settings=value)}}
    fun discard(){val pending=mutable.value.pending;mutable.update{it.copy(pending=null)};if(pending!=null)Thread{UserTransfer.discard(pending)}.start()}
    fun save(uri:Uri)=work {
        val value=UserTransfer.create(app,uri);mutable.update{it.copy(message=app.getString(R.string.transfer_saved,value.notes))}
    }
    fun open(uri:Uri)=work {
        val value=UserTransfer.prepare(app,uri)
        mutable.update{it.copy(pending=value,settings=value.plan.units.none{unit->unit.isNote && unit.fingerprint!="missing"})}
        if(closed)discard()
    }
    fun restore() {
        val pending=mutable.value.pending ?: return
        val settings=mutable.value.settings
        mutable.update{it.copy(pending=null)}
        work {
            try {
                val value=UserTransfer.apply(app,pending,settings)
                val message=app.getString(R.string.transfer_restored,value.added,value.enriched,value.unchanged,value.conflicts,value.damaged)+(if(value.problems.isNotEmpty()) "\n"+app.getString(R.string.transfer_partial) else "")
                mutable.update{it.copy(restored=true,message=message)}
            } finally{UserTransfer.discard(pending)}
        }
    }
    private fun work(body:()->Unit) {
        if(mutable.value.busy)return
        mutable.update{it.copy(busy=true,message="")}
        viewModelScope.launch {
            try{withContext(Dispatchers.IO){body()}}
            catch(e:Exception){mutable.update{it.copy(message=app.getString(when(e.message){"recording_or_processing"->R.string.transfer_wait;"unsupported_format"->R.string.transfer_newer;else->R.string.transfer_failed}))}}
            finally{mutable.update{it.copy(busy=false)}}
        }
    }
    override fun onCleared(){closed=true;val pending=mutable.value.pending;if(pending!=null)Thread{UserTransfer.discard(pending)}.start();super.onCleared()}
}
