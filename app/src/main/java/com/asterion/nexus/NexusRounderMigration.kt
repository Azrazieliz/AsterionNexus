package com.asterion.nexus

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.*
import java.io.*
import java.security.MessageDigest

/** Actual Rounder WebView localStorage export. Legacy names are not canonical
 * content identities; binding requires an explicit selected Nexus original.
 * No cloud credential, old SAF permission or foreign URI is activated. */
class NexusRounderMigration(context:Context){
    private val app=context.applicationContext
    private val root=File(app.noBackupFilesDir,"rounder-migrations").apply{check(isDirectory||mkdirs()||isDirectory)}
    data class Staged(val sha:String,val file:File,val records:Int,val bookmarks:Int,val recent:Int)
    data class Bookmark(val exportSha:String,val key:String,val name:String,val chapter:Int,val fraction:Double,val boundSha:String?)
    companion object{
        const val SCHEMA="asterion.rounder.reader-export.v1"
        const val PREFIX="fg.book.preferences.v2."
        private const val MAX=4*1024*1024
        private val controlKeys=setOf("fg.controls.bg","fg.controls.ink","fg.controls.size","fg.controls.font",
            "fg.controls.mode:epub","fg.controls.mode:pdf","fg.controls.mode:comic","fg.controls.mode:text")
        internal fun validate(raw:String):JSONObject{
            val doc=JSONObject(raw)
            require(doc.getString("schema")==SCHEMA){"Not a Rounder reader export"}
            val records=doc.getJSONObject("records")
            require(records.length()<=10000){"Too many reader records"}
            val names=records.keys()
            while(names.hasNext()){
                val key=names.next()
                require(key.length in 1..1024&&!key.any{it.isISOControl()}){"Invalid reader key"}
                require(key.startsWith(PREFIX)||key in controlKeys||key=="asterion-rounder-recent-v1"){"Unexpected export record"}
                val value=records.getString(key)
                require(value.length<=128*1024){"Reader record exceeds budget"}
                when{
                    key.startsWith(PREFIX)&&key.endsWith(".position")->{
                        val pos=JSONObject(value)
                        val chapter=pos.getInt("chapter");val percent=pos.getDouble("percent")
                        require(chapter in 0..1000000&&percent.isFinite()&&percent in 0.0..1.0){"Invalid legacy bookmark"}
                    }
                    key.startsWith(PREFIX)->JSONObject(value) // appearance record, retained without applying unchecked values
                    key=="asterion-rounder-recent-v1"->{
                        val rows=JSONArray(value);require(rows.length()<=24)
                        for(i in 0 until rows.length()){
                            val row=rows.getJSONObject(i)
                            require(row.getString("name").length in 1..1024)
                            // URI or path is inert historical data, not an authorization.
                            require(row.optString("path").length<=4096&&row.optString("uri").length<=4096)
                        }
                    }
                }
            }
            return doc
        }
    }
    private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun bounded(uri:Uri):ByteArray{
        require(uri.scheme=="content")
        return (app.contentResolver.openInputStream(uri)?:error("Selected export unavailable")).use{input->
            val out=ByteArrayOutputStream();val buffer=ByteArray(32768)
            while(true){val n=input.read(buffer);if(n<0)break;if(n==0)continue;require(n<=MAX-out.size()){"Rounder export exceeds 4 MiB"};out.write(buffer,0,n)}
            out.toByteArray()
        }
    }
    private fun decode(bytes:ByteArray)=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    private fun atomic(file:File,data:ByteArray){
        val f=AtomicFile(file);val out=f.startWrite()
        try{out.write(data);f.finishWrite(out)}catch(e:Exception){f.failWrite(out);throw e}
    }
    private fun read(sha:String):JSONObject{
        require(sha.matches(Regex("[0-9a-f]{64}")))
        val file=File(root,sha+".json")
        require(file.isFile&&file.length() in 1..MAX.toLong()){"Rounder export unavailable"}
        val bytes=file.readBytes()
        require(digest(bytes)==sha){"Rounder export hash mismatch"}
        return validate(decode(bytes))
    }
    private fun report(sha:String,doc:JSONObject):Staged{
        val records=doc.getJSONObject("records")
        val n=records.keys().asSequence().count{it.startsWith(PREFIX)&&it.endsWith(".position")}
        val recent=records.optString("asterion-rounder-recent-v1","[]").let{JSONArray(it).length()}
        return Staged(sha,File(root,sha+".json"),records.length(),n,recent)
    }
    @Synchronized fun stage(uri:Uri):Staged{
        val bytes=bounded(uri);val doc=validate(decode(bytes));val sha=digest(bytes)
        val staged=report(sha,doc)
        if(staged.file.exists())read(sha) else atomic(staged.file,bytes)
        return staged
    }
    @Synchronized fun apply(staged:Staged):Staged{
        val doc=read(staged.sha)
        val actual=report(staged.sha,doc)
        require(actual.file.canonicalFile==staged.file.canonicalFile)
        val marker=File(root,staged.sha+".accepted")
        if(!marker.exists())atomic(marker,JSONObject().put("schema",SCHEMA).put("sha256",staged.sha)
            .put("records",actual.records).put("bookmarks",actual.bookmarks).put("recent",actual.recent).toString().toByteArray(Charsets.UTF_8))
        return actual
    }
    private fun imports():List<String> = root.listFiles().orEmpty().filter{it.name.matches(Regex("[0-9a-f]{64}\\.accepted"))}
        .map{it.nameWithoutExtension}.sorted()
    fun list():List<Bookmark>{
        val registry=NexusRegistry(app)
        val rows=mutableListOf<Bookmark>()
        for(sha in imports()){
            val records=read(sha).getJSONObject("records")
            for(key in records.keys().asSequence().filter{it.startsWith(PREFIX)&&it.endsWith(".position")}.sorted()){
                val pos=JSONObject(records.getString(key))
                rows.add(Bookmark(sha,key,key.removePrefix(PREFIX).removeSuffix(".position"),
                    pos.getInt("chapter"),pos.getDouble("percent"),registry.rounderBookmarkBinding(sha,key)))
                require(rows.size<=20000){"Bookmark display limit"}
            }
        }
        return rows
    }
    @Synchronized fun bind(bookmark:Bookmark,original:String){
        require(File(root,bookmark.exportSha+".accepted").isFile){"Apply the export first"}
        val records=read(bookmark.exportSha).getJSONObject("records")
        require(bookmark.key.startsWith(PREFIX)&&bookmark.key.endsWith(".position"))
        val actual=JSONObject(records.getString(bookmark.key))
        val registry=NexusRegistry(app)
        val item=registry.find(original)?:error("Original is not registered")
        require(item.mime=="application/epub+zip"&&registry.verifyOriginal(item)){"A verified offline EPUB is required"}
        registry.bindRounderBookmark(bookmark.exportSha,bookmark.key,item.sha256,actual.getInt("chapter"),actual.getDouble("percent"))
    }
    @Synchronized fun rollback(sha:String){
        require(File(root,sha+".accepted").isFile){"Import not applied"}
        // Bound bookmarks may already have been used; never erase them silently.
        require(!NexusRegistry(app).hasRounderBindings(sha)){"Remove explicit bookmark bindings before rollback"}
        check(File(root,sha+".accepted").delete()){"Migration receipt could not be revoked"}
    }
}
