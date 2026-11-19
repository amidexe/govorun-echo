package com.govorun.echo.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.govorun.echo.NetworkGate
import com.govorun.echo.R
import com.govorun.echo.backup.FolderExports
import com.govorun.echo.export.ReadableNotes
import com.govorun.echo.notes.NoteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class FileExportModel(app: Application) : AndroidViewModel(app) {
    private val context = getApplication<Application>()
    private val configured = FolderExports.config(context)
    var tree by mutableStateOf(FolderExports.lastTree(context))
    var tags by mutableStateOf<Set<String>?>(configured?.let { it.tags } ?: emptySet())
    var options by mutableStateOf(configured?.options ?: ReadableNotes.Options())
    var notes by mutableStateOf<List<NoteStore.Entry>>(emptyList())
        private set
    var selected by mutableStateOf<Set<String>>(emptySet())
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    var cleanup by mutableStateOf<FolderExports.Cleanup?>(null)
    private var pendingZip: Pair<List<String>, ReadableNotes.Options>? = null
    init { if (configured != null) tags = configured.tags }

    private var loaded = false

    fun load(mode: FileExportMode) {
        if (loaded) return
        loaded = true
        if (mode == FileExportMode.Manage) return
        if (mode != FileExportMode.Automatic) options = ReadableNotes.Options(markdown = mode == FileExportMode.Markdown)
        viewModelScope.launch {
            notes = withContext(Dispatchers.IO) { NoteStore.list(context).filter { it.state == NoteStore.STATE_DONE && !it.appending && !it.archiving } }
        }
    }

    fun chooseZip(): Boolean {
        val ids = notes.filter { it.id in selected }.map { it.id }
        if (ids.isEmpty()) return false
        pendingZip = ids to options
        return true
    }

    fun save(uri: Uri?) {
        val request = pendingZip
        pendingZip = null
        if (uri == null) return
        if (request == null) { message = context.getString(R.string.folder_retry); return }
        work {
            val resolver = context.contentResolver
            val initiallyEmpty = resolver.openInputStream(uri)?.use { it.read() == -1 } == true
            try {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val out = resolver.openOutputStream(uri, "wt") ?: error("destination_unavailable")
                java.security.DigestOutputStream(out, digest).use { ReadableNotes.zip(NoteStore.root(context), request.first, request.second, it) }
                val actual = resolver.openInputStream(uri)?.use(com.govorun.echo.backup.PortableArchive::sha)
                check(actual == com.govorun.echo.backup.CopyReceipt.fingerprint(digest.digest())) { "copy_readback_failed" }
            } catch (error: Exception) {
                if (initiallyEmpty) runCatching { android.provider.DocumentsContract.deleteDocument(resolver, uri) }
                throw error
            }
            context.getString(R.string.folder_saved)
        }
    }

    fun enable() {
        val destination = tree ?: return
        val scope = tags?.toSet()
        if (scope != null && scope.isEmpty()) return
        val selection = options
        work {
            val previous = FolderExports.lastTree(context)
            context.contentResolver.takePersistableUriPermission(destination, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            FolderExports.enable(context, destination, scope, selection)
            if (previous != null && previous != destination) FolderExports.release(context, previous)
            context.getString(R.string.folder_enabled)
        }
    }

    fun stop() = work {
        FolderExports.stop(context)
        FolderExports.lastTree(context)?.let { FolderExports.release(context, it) }
        context.getString(R.string.folder_stopped)
    }

    fun forget() = work {
        FolderExports.forgetLocation(context)
        withContext(Dispatchers.Main) { tree = null; tags = emptySet() }
        context.getString(R.string.folder_stopped)
    }

    fun inspect(uri: Uri) = work {
        val plan = FolderExports.preview(context, uri)
        withContext(Dispatchers.Main) { cleanup = plan }
        ""
    }

    fun remove(includeIndex: Boolean) {
        val plan = cleanup ?: return
        cleanup = null
        work {
            val result = FolderExports.remove(context, plan, includeIndex)
            context.getString(R.string.folder_cleanup_done, result.removed, result.kept)
        }
    }

    private fun work(operation: suspend () -> String) {
        if (busy) return
        busy = true
        message = ""
        viewModelScope.launch {
            try { message = withContext(Dispatchers.IO) { operation() } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (refused: NetworkGate.Refused) { message = smartStateText(refused.reason).let { context.getString(it.line) } }
            catch (failure: Exception) {
                android.util.Log.w("FileExport", "event=operation_failed type=${failure.javaClass.simpleName}")
                message = context.getString(R.string.folder_retry)
            } finally { busy = false }
        }
    }
}
