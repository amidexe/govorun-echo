package com.govorun.echo.backup

import org.json.JSONObject
import java.io.*
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.*

/** One versioned file format. Whole files and complete metadata survive without a per-field export list. */
internal object PortableArchive {
    const val VERSION=1
    private const val PREFIX="echo-v1/"
    private const val INDEX="echo-manifest.json"
    private const val MAX_INDEX_BYTES=8*1024*1024
    private val technical=setOf("snapshot","fair-queue.json","profileInstalled","profileinstaller_profileWrittenFor_lastUpdateTime.dat",EchoBackupAgent.TRANSFER_PREFERENCES)
    data class Staged(val root:File,val preferences:JSONObject?,val problems:List<String>,val complete:Boolean,val expectedUnits:Set<String> = emptySet())
    data class Created(val files:Int,val notes:Int,val bytes:Long,val sha256:String)
    private data class Source(val name:String,val file:File?,val value:ByteArray?,val size:Long,val sha:String,val crc:Long,val stamp:Long)
    fun portable(relative:String)=relative.substringBefore('/') !in technical
    fun safe(relative:String)=com.govorun.echo.PortablePath.safe(relative)
    private fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it)}
    fun sha(file:File)=file.inputStream().use(::sha)
    fun sha(input:InputStream):String {val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)};return hex(digest.digest())}
    private fun source(name:String,file:File?=null,value:ByteArray?=null):Source {
        val digest=MessageDigest.getInstance("SHA-256");val crc=CRC32();var size=0L;val stamp=file?.lastModified() ?: 0L
        (file?.inputStream() ?: value!!.inputStream()).use {input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;size+=n;digest.update(buffer,0,n);crc.update(buffer,0,n)}}
        check(file==null || (file.length()==size && file.lastModified()==stamp)){"source_changed:$name"}
        return Source(name,file,value,size,hex(digest.digest()),crc.value,stamp)
    }
    fun create(root:File,preferences:JSONObject,output:File):Created {
        require(!output.canonicalPath.startsWith(root.canonicalPath+File.separator));output.parentFile?.mkdirs()
        try {val result=output.outputStream().buffered().use{write(root,preferences,it)};RandomAccessFile(output,"rw").use{it.fd.sync()};return result}
        catch(e:Exception){output.delete();throw e}
    }
    /** STORED entries are independently recoverable and sound is already compressed. No spare full-library copy is needed. */
    fun write(root:File,preferences:JSONObject,output:OutputStream):Created {
        val sources=root.walkTopDown().filter{it.isFile && portable(it.relativeTo(root).invariantSeparatorsPath)}.sortedBy{it.relativeTo(root).invariantSeparatorsPath}.map {file->
            val rel=file.relativeTo(root).invariantSeparatorsPath;require(safe(rel) && file.canonicalPath.startsWith(root.canonicalPath+File.separator));source("data/$rel",file)
        }.toMutableList()
        sources+=source("preferences.json",value=preferences.toString().toByteArray())
        val expected=JSONObject();sources.forEach{expected.put(it.name,JSONObject().put("bytes",it.size).put("sha256",it.sha))}
        val header=source(INDEX,value=JSONObject().put("format","govorun-echo-user-data").put("version",VERSION).put("files",expected).toString().toByteArray())
        require(header.size<=MAX_INDEX_BYTES){"manifest_too_large"}
        val digest=MessageDigest.getInstance("SHA-256")
        ZipOutputStream(DigestOutputStream(output,digest)).use {zip->
            (listOf(header)+sources).forEach {item->
                val name=if(item===header) INDEX else PREFIX+item.name
                val entry=ZipEntry(name).apply{method=ZipEntry.STORED;size=item.size;compressedSize=item.size;crc=item.crc}
                zip.putNextEntry(entry);val check=MessageDigest.getInstance("SHA-256")
                (item.file?.inputStream() ?: item.value!!.inputStream()).use {input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;zip.write(buffer,0,n);check.update(buffer,0,n)}}
                zip.closeEntry();check(hex(check.digest())==item.sha && (item.file==null || item.file.lastModified()==item.stamp)){"source_changed:${item.name}"}
            }
        }
        return Created(sources.size-1,sources.count{(it.name.startsWith("data/notes/") || it.name.startsWith("data/trash/")) && it.name.endsWith("/meta.json")},sources.sumOf{it.size},hex(digest.digest()))
    }
    private fun index(input:InputStream):JSONObject {
        val memory=ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(memory.size()+n<=MAX_INDEX_BYTES){"manifest_too_large"};memory.write(buffer,0,n)};val bytes=memory.toByteArray()
        val json=JSONObject(bytes.decodeToString());require(json.optString("format")=="govorun-echo-user-data" && json.optInt("version")==VERSION){"unsupported_format"};val files=json.getJSONObject("files");files.keys().forEach { require(safe(it) && (it.startsWith("data/") || it=="preferences.json")){"unsafe_manifest"} };return files
    }
    private fun expectedUnits(expected:JSONObject?)=expected?.keys()?.asSequence()?.filter{it.startsWith("data/notes/") || it.startsWith("data/trash/")}?.map{it.removePrefix("data/").split('/').take(2).joinToString("/")}?.toSet().orEmpty()
    private fun finish(destination:File,expected:JSONObject?,seen:Set<String>,problems:MutableList<String>):Staged {
        expected?.keys()?.asSequence()?.filter{it !in seen}?.forEach{problems+="missing:$it"}
        val preferences=File(destination,"preferences.json").takeIf{it.isFile}?.let{runCatching{JSONObject(it.readText())}.getOrElse{problems+="damaged:preferences.json";null}}
        return Staged(File(destination,"data"),preferences,problems.toList(),problems.isEmpty(),expectedUnits(expected))
    }
    private fun extract(input:InputStream,entry:ZipEntry,rel:String,destination:File,expected:JSONObject?,reuse:File?) {
        require(safe(rel) && (rel=="preferences.json" || rel.startsWith("data/")))
        val target=File(destination,rel);target.parentFile?.mkdirs();val spec=expected?.optJSONObject(rel)
        if(expected!=null)check(spec!=null){"unlisted:$rel"}
        val reusable=reuse?.let{File(it,rel.removePrefix("data/"))}?.takeIf {rel.startsWith("data/") && !rel.endsWith("/meta.json") && it.isFile && spec!=null && it.length()==spec.getLong("bytes") && sha(it)==spec.getString("sha256")}
        val digest=MessageDigest.getInstance("SHA-256");val crc=CRC32();var length=0L
        val out:OutputStream=if(reusable!=null) object:OutputStream(){override fun write(value:Int){};override fun write(buffer:ByteArray,offset:Int,length:Int){}} else target.outputStream()
        try {
            out.use {val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;length+=n;if(reusable==null)check(destination.usableSpace>1_048_576L){"storage_low"};it.write(buffer,0,n);digest.update(buffer,0,n);crc.update(buffer,0,n)}}
            check((entry.size<0 || entry.size==length) && (entry.crc<0 || entry.crc==crc.value)){"crc:$rel"}
            spec?.let{check(it.getLong("bytes")==length && it.getString("sha256")==hex(digest.digest())){"checksum:$rel"}}
            if(reusable!=null) {
                try {java.nio.file.Files.createLink(target.toPath(),reusable.toPath())}
                catch(e:Exception) {check(destination.usableSpace>reusable.length()+1_048_576L){"storage_low"};reusable.copyTo(target,overwrite=true)}
            }
        } catch(e:Exception){target.delete();throw e}
    }
    /** Random access isolates a damaged ZIP member; the streaming fallback also works with non-seekable providers. */
    fun stage(archive:File,destination:File,reuse:File?=null):Staged {
        require(!destination.exists() || destination.listFiles().isNullOrEmpty());destination.mkdirs()
        val zip=try{ZipFile(archive)}catch(e:Exception){return archive.inputStream().use{stage(it,destination,reuse)}}
        zip.use {
            val problems=mutableListOf<String>();val entry=zip.getEntry(INDEX)
            val expected=if(entry==null){problems+="manifest_missing";null}else try{zip.getInputStream(entry).use(::index)}catch(e:IllegalArgumentException){throw e}catch(e:Exception){problems+="manifest_damaged";null}
            val seen=mutableSetOf<String>()
            zip.entries().asSequence().filter{!it.isDirectory && it.name!=INDEX}.forEach {part->
                val rel=part.name.removePrefix(PREFIX)
                if(!part.name.startsWith(PREFIX) || !safe(rel) || !seen.add(rel)){problems+="unsafe_entry:${part.name.take(100)}";return@forEach}
                try{zip.getInputStream(part).use{input->extract(input,part,rel,destination,expected,reuse)}}catch(e:Exception){File(destination,rel).delete();problems+="damaged:$rel"}
            }
            return finish(destination,expected,seen,problems)
        }
    }
    fun stage(input:InputStream,destination:File,reuse:File?=null):Staged {
        require(!destination.exists() || destination.listFiles().isNullOrEmpty());destination.mkdirs()
        val problems=mutableListOf<String>();var expected:JSONObject?=null;val seen=mutableSetOf<String>()
        ZipInputStream(input.buffered()).use{zip->
            while(true){
                val entry=try{zip.nextEntry}catch(e:Exception){problems+="archive_truncated";break} ?: break
                if(entry.isDirectory)continue
                if(entry.name==INDEX){
                    try{expected=index(zip)}catch(e:IllegalArgumentException){throw e}catch(e:Exception){problems+="manifest_damaged"}
                    continue
                }
                val rel=entry.name.removePrefix(PREFIX)
                if(!entry.name.startsWith(PREFIX) || !safe(rel) || !seen.add(rel)){problems+="unsafe_entry:${entry.name.take(100)}";runCatching{zip.closeEntry()};continue}
                try{extract(zip,entry,rel,destination,expected,reuse)}catch(e:Exception){File(destination,rel).delete();problems+="damaged:$rel";if(runCatching{zip.closeEntry()}.isFailure){problems+="archive_truncated";break}}
            }
        }
        if(expected==null && "manifest_damaged" !in problems)problems+="manifest_missing"
        return finish(destination,expected,seen,problems)
    }
}
