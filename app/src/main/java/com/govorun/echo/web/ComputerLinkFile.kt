package com.govorun.echo.web

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

internal data class ComputerLinkRecord(
    val id: String,
    val pairing: WebExport.Pairing,
    val selection: ComputerSelection,
    val openedAt: Long,
    val deliveredAt: Long = 0,
    val checkpoint: JSONObject? = null,
    val dirty: Boolean = true,
    val revision: Long = 0,
) {
    fun json() = JSONObject().put("id", id).put("room", pairing.room)
        .put("key", Base64.getUrlEncoder().withoutPadding().encodeToString(pairing.computer))
        .put("browser", pairing.browser).put("os", pairing.os)
        .put("selection", selection.json()).put("opened", openedAt).put("delivered", deliveredAt)
        .put("checkpoint", checkpoint ?: JSONObject.NULL).put("dirty", dirty).put("revision", revision)
    companion object {
        fun read(j: JSONObject) = ComputerLinkRecord(j.getString("id"), WebExport.Pairing(j.getString("room"), Base64.getUrlDecoder().decode(j.getString("key")), j.optString("browser"), j.optString("os")), ComputerSelection.read(j.getJSONObject("selection")), j.getLong("opened"), j.optLong("delivered"), j.optJSONObject("checkpoint"), j.optBoolean("dirty", true), j.optLong("revision"))
    }
}

internal data class ComputerLinkData(
    val link: ComputerLinkRecord? = null,
    val closing: List<ComputerLinkRecord> = emptyList(),
    val failures: Int = 0,
    val nextAt: Long = 0,
    val held: Boolean = false,
    val closingFailures: Int = 0,
    val closingNextAt: Long = 0,
    val closingHeld: Boolean = false,
) {
    val pending get() = link?.dirty == true || closing.isNotEmpty()
    fun nextDue(): Long? = listOfNotNull(if (link?.dirty == true && !held) nextAt else null,
        if (closing.isNotEmpty() && !closingHeld) closingNextAt else null).minOrNull()
    fun json() = JSONObject().put("v", 1).put("link", link?.json() ?: JSONObject.NULL)
        .put("closing", JSONArray(closing.map { it.json() })).put("failures", failures).put("next", nextAt).put("held", held).put("closingFailures", closingFailures).put("closingNext", closingNextAt).put("closingHeld", closingHeld)
    companion object {
        fun read(j: JSONObject): ComputerLinkData {
            require(j.getInt("v") == 1)
            val a = j.getJSONArray("closing")
            return ComputerLinkData(j.optJSONObject("link")?.let(ComputerLinkRecord::read), (0 until a.length()).map { ComputerLinkRecord.read(a.getJSONObject(it)) }, j.optInt("failures"), j.optLong("next"), j.optBoolean("held"), j.optInt("closingFailures"), j.optLong("closingNext"), j.optBoolean("closingHeld"))
        }
    }
}

/** Device authorization and derived delivery checkpoint, never part of the portable note store. */
internal class ComputerLinkFile(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "computer-link.json"))
    fun read(): ComputerLinkData = if (!file.baseFile.exists()) ComputerLinkData() else
        runCatching { file.openRead().bufferedReader().use { ComputerLinkData.read(JSONObject(it.readText())) } }
            .getOrElse { ComputerLinkData(held = true) } // Invalid/future authorization fails closed; never broadens to all notes.
    fun clear() = file.delete()
    fun write(data: ComputerLinkData) {
        val stream = file.startWrite()
        try { stream.write(data.json().toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
