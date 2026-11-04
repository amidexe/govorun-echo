package com.govorun.echo.backup

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Whole preference stores, including unknown future user keys. Credentials, URIs and update state are device-bound. */
internal object PortablePreferences {
    private val deviceStores=setOf("backup","updates","command_secrets")
    private val deviceKeys=setOf("tile_added","install_info_json")
    fun capture(context:Context):JSONObject {
        val stores=JSONObject()
        File(context.applicationInfo.dataDir,"shared_prefs").listFiles().orEmpty().filter { it.extension=="xml" }.forEach { file ->
            val name=file.nameWithoutExtension
            if(name !in deviceStores && !name.startsWith("secret_") && !name.startsWith("device_")) stores.put(name,encode(context.getSharedPreferences(name,Context.MODE_PRIVATE).all))
        }
        return JSONObject().put("version",1).put("stores",stores)
    }
    fun encode(values:Map<String,*>):JSONObject = JSONObject().apply {
        values.forEach { (key,value) ->
            val type=when(value){is String->"string";is Boolean->"boolean";is Int->"int";is Long->"long";is Float->"float";is Set<*>->"strings";else->error("preference_type:$key")}
            put(key,JSONObject().put("type",type).put("value",if(value is Set<*>) JSONArray(value.sortedBy { it.toString() }) else value))
        }
    }
    fun decode(json:JSONObject):Map<String,Any> = json.keys().asSequence().associateWith { key ->
        val entry=json.getJSONObject(key)
        when(entry.getString("type")) {
            "string"->entry.getString("value");"boolean"->entry.getBoolean("value");"int"->entry.getInt("value");"long"->entry.getLong("value");"float"->entry.getDouble("value").toFloat()
            "strings"->entry.getJSONArray("value").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            else->error("preference_type:$key")
        }
    }
    fun restore(context:Context,json:JSONObject,replace:Boolean):Int {
        require(json.getInt("version")==1);val stores=json.getJSONObject("stores");var count=0
        // Validate all preference payloads before changing any of them.
        val decoded=stores.keys().asSequence().filter { it !in deviceStores && !it.startsWith("secret_") && !it.startsWith("device_") }.associateWith { name ->
            require(name.matches(Regex("[A-Za-z0-9_.-]+")));decode(stores.getJSONObject(name))
        }
        decoded.forEach { (name,values) ->
            val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE);val old=prefs.all;val edit=prefs.edit()
            values.filterKeys { it !in deviceKeys && (replace || it !in old) }.forEach { (key,value) ->
                when(value) {is String->edit.putString(key,value);is Boolean->edit.putBoolean(key,value);is Int->edit.putInt(key,value);is Long->edit.putLong(key,value);is Float->edit.putFloat(key,value);is Set<*>->edit.putStringSet(key,value.filterIsInstance<String>().toSet())}
                count++
            }
            deviceKeys.forEach { edit.remove(it) };check(edit.commit()) { "preferences_write_failed" }
        }
        return count
    }
}
