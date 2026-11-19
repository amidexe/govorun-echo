package com.govorun.echo.backup

import android.content.ContentResolver
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken

/** Read only the names from a recognized old Echo index. Note bodies may be very large. */
internal object FolderDetails {
    const val NAME = "echo-notes.json"

    fun bases(resolver: ContentResolver, uri: Uri): Set<String>? = runCatching {
        val input = resolver.openInputStream(uri) ?: return null
        JsonReader(input.bufferedReader()).use { reader ->
            var supported = false
            var found: MutableSet<String>? = null
            reader.beginObject()
            while (reader.hasNext()) when (reader.nextName()) {
                "version" -> supported = reader.nextInt() == 1
                "notes" -> {
                    val bases = mutableSetOf<String>()
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val base = reader.nextName()
                        require(BackupFolder.parseName("$base.txt") != null)
                        reader.beginObject()
                        var hasId = false
                        var dated = false
                        while (reader.hasNext()) when (reader.nextName()) {
                            "id" -> hasId = reader.nextString().isNotBlank()
                            "startedAtMs" -> { reader.nextLong(); dated = true }
                            else -> reader.skipValue()
                        }
                        reader.endObject()
                        require(hasId && dated && bases.add(base))
                    }
                    reader.endObject()
                    found = bases
                }
                else -> reader.skipValue()
            }
            reader.endObject()
            require(supported && reader.peek() == JsonToken.END_DOCUMENT)
            found
        }
    }.getOrNull()
}
