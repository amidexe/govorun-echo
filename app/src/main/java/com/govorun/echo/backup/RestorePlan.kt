package com.govorun.echo.backup

import com.govorun.echo.RestoreTransaction
import com.govorun.echo.UserFiles

import org.json.JSONObject
import java.io.File

/** Stable IDs, not dates or display names, determine identity. Conflicts are reported and never silently duplicated. */
internal object ArchiveRestore {
    enum class Kind { New, Same, Enrich, Conflict, Damaged }
    data class Unit(val relative: String, val incoming: File, val kind: Kind, val fingerprint: String, val record: Boolean = incoming.isDirectory) { val isNote get() = record && (relative.startsWith("notes/") || relative.startsWith("trash/"))
        fun label():String = runCatching {JSONObject(File(incoming,"meta.json").readText()).optString("title")}.getOrDefault("").ifBlank {
            runCatching {File(incoming,"text.txt").bufferedReader().use {reader->val buffer=CharArray(80);val n=reader.read(buffer);if(n>0)String(buffer,0,n).replace('\n',' ').trim() else ""}}.getOrDefault("")
        }.ifBlank{relative.substringAfterLast('/')}
    }
    data class Plan(val units: List<Unit>, val preferences: JSONObject?, val problems: List<String>) {
        fun count(kind: Kind) = units.count { (it.isNote || kind == Kind.Conflict || kind == Kind.Damaged) && it.kind == kind }
    }
    data class Result(val added: Int, val enriched: Int, val unchanged: Int, val conflicts: Int, val damaged: Int, val problems: List<String>)
    private val bookkeeping = setOf("backup","restored","unseen","pinned","archived","tags","changedAtMs","format","deletedAtMs")

    fun plan(staged: PortableArchive.Staged, target: File): Plan {
        val units=mutableListOf<Unit>(); val problems=staged.problems.toMutableList()
        val records=(staged.expectedUnits + listOf("notes","trash","command-history").flatMap { folder -> File(staged.root,folder).listFiles().orEmpty().filter { it.isDirectory }.map { "$folder/${it.name}" } }).sorted()
        val seenIds=mutableSetOf<String>()
        records.forEach { original ->
            val dir=File(staged.root,original);val id=original.substringAfter('/')
            val other=(if(original.startsWith("notes/")) "trash/" else "notes/")+id
            val rel=if(!original.startsWith("command-history/") && !File(target,original).exists() && File(target,other).isDirectory)other else original
            val destination=File(target,rel)
            val problem=staged.problems.any{it.startsWith("damaged:data/$original/") || it.startsWith("missing:data/$original/")}
            val kind=if(problem || !validNote(dir)) Kind.Damaged else if(!seenIds.add(id)) Kind.Conflict else classify(dir,destination,true)
            units+=Unit(rel,dir,kind,fingerprint(destination),record=true)
        }
        // Every other current or future user file is carried without a field list. Existing differing values remain intact.
        staged.root.walkTopDown().filter { file ->
            val relative=file.relativeTo(staged.root).invariantSeparatorsPath
            file.isFile && (relative.substringBefore('/') !in setOf("notes","trash","command-history") || relative.count { it=='/' }<2)
        }.forEach { file ->
            val rel=file.relativeTo(staged.root).invariantSeparatorsPath
            if (PortableArchive.safe(rel) && PortableArchive.portable(rel)) units += Unit(rel,file,classify(file,File(target,rel),false),fingerprint(File(target,rel)))
        }
        return Plan(units,staged.preferences,problems)
    }

    private fun validNote(dir: File): Boolean = runCatching {
        val meta=JSONObject(File(dir,"meta.json").readText())
        val audio=meta.optJSONObject("audio")
        audio?.keys()?.asSequence()?.forEach { name ->
            require(PortableArchive.safe(name) && !name.contains('/'))
            check(File(dir,"chunks/$name").isFile && File(dir,"chunks/$name").length()>0)
        }
        File(dir,"text.txt").isFile || File(dir,"audio.pcm").isFile || (audio?.length() ?: 0)>0
    }.getOrDefault(false)

    private fun classify(source: File, target: File, note: Boolean): Kind {
        if (!target.exists()) return Kind.New
        if (source.isDirectory != target.isDirectory) return Kind.Conflict
        if (!source.isDirectory) return if (PortableArchive.sha(source)==PortableArchive.sha(target)) Kind.Same else Kind.Conflict
        if (note && !validNote(target)) return Kind.Conflict
        var addition=false
        source.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel=file.relativeTo(source).invariantSeparatorsPath; val existing=File(target,rel)
            if (!existing.exists()) addition=true
            else if (!existing.isFile) return Kind.Conflict
            else if (rel=="meta.json") {
                val a=JSONObject(file.readText()); val b=JSONObject(existing.readText())
                if (!compatible(a,b)) return Kind.Conflict
                if (hasMissing(a,b)) addition=true
            } else if (PortableArchive.sha(file)!=PortableArchive.sha(existing)) return Kind.Conflict
        }
        return if (addition) Kind.Enrich else Kind.Same
    }

    private fun compatible(source: JSONObject,target: JSONObject,top: Boolean = true): Boolean = source.keys().asSequence().all { key ->
        if ((top && key in bookkeeping) || !target.has(key)) true
        else {
            val a=source.get(key); val b=target.get(key)
            if(a is JSONObject && b is JSONObject) compatible(a,b,false) else equal(a,b)
        }
    }
    private fun equal(a: Any,b: Any): Boolean = when {
        a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet()==b.keys().asSequence().toSet() && a.keys().asSequence().all { equal(a.get(it),b.get(it)) }
        a is org.json.JSONArray && b is org.json.JSONArray -> a.length()==b.length() && (0 until a.length()).all { equal(a.get(it),b.get(it)) }
        else -> a==b
    }
    private fun hasMissing(a:JSONObject,b:JSONObject,top:Boolean=true):Boolean = a.keys().asSequence().any {
        !(top && it in bookkeeping) && (!b.has(it) || (a.opt(it) is JSONObject && b.opt(it) is JSONObject && hasMissing(a.getJSONObject(it),b.getJSONObject(it),false)))
    }
    private fun merge(a:JSONObject,b:JSONObject,top:Boolean=true):JSONObject = JSONObject(b.toString()).apply {
        a.keys().forEach { key ->
            if(top && key in bookkeeping)return@forEach
            if(!has(key)) put(key,a.get(key))
            else if(a.opt(key) is JSONObject && opt(key) is JSONObject) put(key,merge(a.getJSONObject(key),getJSONObject(key),false))
        }
    }

    private fun contentHash(file:File):String = if(file.name=="meta.json") runCatching {JSONObject(file.readText()).apply{remove("backup")}.toString()}.getOrDefault("damaged") else PortableArchive.sha(file)
    private fun fingerprint(file: File): String = if (!file.exists()) "missing" else if (file.isFile) contentHash(file) else file.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(file).path }.joinToString("\n") { it.relativeTo(file).invariantSeparatorsPath+":"+contentHash(it) }
    private fun fastStamp(file:File):String = if(!file.exists()) "missing" else if(file.isFile) "${file.length()}:${file.lastModified()}" else file.walkTopDown().filter{it.isFile}.sortedBy{it.relativeTo(file).path}.joinToString(";"){it.relativeTo(file).path+":"+if(it.name=="meta.json") contentHash(it) else "${it.length()}:${it.lastModified()}"}


    fun apply(plan: Plan,target: File,work: File,replaceGlobals:Boolean=false,atomic:((()->kotlin.Unit)->kotlin.Unit)={it()}): Result {
        var added=0;var enriched=0;var same=0;var conflicts=0;var damaged=0;val problems=plan.problems.toMutableList()
        plan.units.forEach { unit ->
            when(unit.kind) {
                Kind.Same -> if(unit.isNote) same++
                Kind.Conflict -> if(unit.record || !replaceGlobals) conflicts++ else {
                    try {check(fingerprint(File(target,unit.relative))==unit.fingerprint);RestoreTransaction.prepare(unit.incoming);atomic{RestoreTransaction.install(target,unit.relative,unit.incoming,work)}}
                    catch(e:Exception){RestoreTransaction.recover(target,work);problems+="restore_failed:${unit.relative}"}
                }
                Kind.Damaged -> damaged++
                Kind.New,Kind.Enrich -> try {
                    val stamp=fastStamp(File(target,unit.relative))
                    check(fingerprint(File(target,unit.relative))==unit.fingerprint) { "target_changed" }
                    if (unit.kind==Kind.Enrich) {
                        val current=File(target,unit.relative)
                        current.walkTopDown().filter{it.isFile}.forEach { existing ->
                            val rel=existing.relativeTo(current).invariantSeparatorsPath;val incoming=File(unit.incoming,rel)
                            if(rel=="meta.json" && incoming.isFile) incoming.writeText(merge(JSONObject(incoming.readText()),JSONObject(existing.readText())).apply{if(unit.relative.startsWith("notes/"))remove("deletedAtMs")}.toString())
                            else if(!incoming.exists()) {incoming.parentFile?.mkdirs();existing.copyTo(incoming)}
                        }
                    }
                    RestoreTransaction.prepare(unit.incoming)
                    atomic {check(fastStamp(File(target,unit.relative))==stamp){"target_changed"};RestoreTransaction.install(target,unit.relative,unit.incoming,work)}
                    if(unit.isNote) {if(unit.kind==Kind.New) added++ else enriched++}
                } catch(e:Exception) {
                    RestoreTransaction.recover(target,work); problems+="restore_failed:${unit.relative}"; damaged++
                }
            }
        }
        return Result(added,enriched,same,conflicts,damaged,problems)
    }
}
