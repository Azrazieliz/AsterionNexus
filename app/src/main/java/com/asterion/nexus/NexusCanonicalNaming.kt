package com.asterion.nexus

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.widget.*
import com.ailm.android.runtime.LocalDatabase
import com.ailm.android.runtime.StandaloneRuntime
import com.openlocal.office.core.FgTeraboxClient
import com.openlocal.office.core.FgTeraboxDocumentsProvider
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.Locale

/** Owner-approved naming for verified identities that cannot be inferred
 * from a filename. Reuses the canonical Core character ID and the existing
 * reversible cloud mover. No new folder hierarchy or fabricated identity. */
internal object NexusCanonicalNaming{
    private val worker=Executors.newSingleThreadExecutor()
    data class Identity(val kind:String,val characterId:String="",val character:String="",val series:String="",
                        val cosplayer:String="",val transformation:String="",val objectName:String="",val album:String="")
    private fun segment(raw:String):String{
        val value=raw.trim()
        require(value.length in 1..96&&value!="."&&value!=".."&&value.none{it=='/'||it=='\\'||it.isISOControl()}){"Invalid canonical name"}
        return value
    }
    fun prefix(i:Identity):String=when(i.kind){
        "cosplay"->{require(i.characterId.isNotBlank()&&i.series.isNotBlank()&&i.album.isNotBlank());segment(i.character)+" - "+segment(i.cosplayer)}
        "character_model"->{require(i.characterId.isNotBlank()&&i.series.isNotBlank());segment(i.character)+(if(i.transformation.isBlank())"" else " ("+segment(i.transformation)+")")}
        "object_model"->segment(i.objectName)
        else->error("Unrecognized naming category")
    }
    fun extension(name:String):String{
        val lower=name.lowercase(Locale.ROOT)
        return when{
            lower.endsWith(".gltf.zip")->"gltf.zip"
            lower.endsWith(".glb.zip")->"glb.zip"
            else->name.substringAfterLast('.',"").also{require(it.matches(Regex("[A-Za-z0-9]{1,12}"))){"Original extension unavailable"}}
        }
    }
    fun candidate(i:Identity,name:String,siblings:List<String>):String{
        val stem=prefix(i);val ext=extension(name)
        val matching=Regex("^"+Regex.escape(stem)+" ([1-9][0-9]*)\\."+Regex.escape(ext)+"$",RegexOption.IGNORE_CASE)
        // Do not renumber an already canonical, collision-free filename.
        if(matching.matches(name)&&siblings.count{it.equals(name,true)}==1)return name
        val next=siblings.mapNotNull{matching.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull()}.maxOrNull()?:0
        check(next<Int.MAX_VALUE){"Canonical sequence exhausted"}
        return (stem+" "+(next+1)+"."+ext).also{require(it.toByteArray(Charsets.UTF_8).size<=255){"Filename too long"}}
    }
    private fun prefs(c:Context)=c.getSharedPreferences("nexus_confirmed_media_identity_v1",Context.MODE_PRIVATE)
    private fun record(c:Context,sha:String,i:Identity){
        require(sha.matches(Regex("[a-f0-9]{64}")))
        val json=JSONObject().put("kind",i.kind).put("character_id",i.characterId).put("character",i.character).put("series",i.series)
            .put("cosplayer",i.cosplayer).put("transformation",i.transformation).put("object",i.objectName).put("source_album",i.album)
            .put("evidence","owner_confirmed").put("confirmed_at",System.currentTimeMillis())
        check(prefs(c).edit().putString(sha,json.toString()).commit()){"Identity metadata not saved"}
    }
    private fun identity(c:Context,kind:String,id:String,extra:String,album:String):Identity{
        if(kind=="object_model")return Identity(kind,objectName=segment(extra))
        StandaloneRuntime.initialize(c)
        return LocalDatabase(c).use{db->
            db.readableDatabase.rawQuery("SELECT c.character_id,c.canonical_name,s.canonical_title FROM fusion_characters c JOIN fusion_series s ON s.series_code=c.primary_series_code WHERE c.character_id=?",arrayOf(id.trim())).use{row->
                require(row.moveToFirst()){"Select an existing canonical character ID from Taxonomy"}
                val character=row.getString(1);val series=row.getString(2)
                if(kind=="cosplay"){
                    require(album.trim().length in 1..512&&!album.any{it.isISOControl()}){"Source album identity required"}
                    Identity(kind,row.getString(0),character,series,cosplayer=segment(extra),album=album.trim())
                }else Identity(kind,row.getString(0),character,series,transformation=extra.trim().also{if(it.isNotBlank())segment(it)})
            }
        }
    }
    fun available(mime:String,name:String):Boolean=mime.startsWith("image/")||mime.startsWith("model/")||NexusGltfActivity.supports(mime,name)||name.lowercase(Locale.ROOT).let{it.endsWith(".obj")||it.endsWith(".stl")}
    /** Only reuse explicit canonical identity already bound to these exact bytes. */
    fun known(c:Context,sha:String):Identity?=runCatching{
        val raw=prefs(c).getString(sha,null)?:return null
        val j=JSONObject(raw)
        require(j.getString("evidence")=="owner_confirmed")
        val kind=j.getString("kind")
        identity(c,kind,j.optString("character_id"),when(kind){"cosplay"->j.getString("cosplayer");"character_model"->j.optString("transformation");"object_model"->j.getString("object");else->error("Unsupported identity")},j.optString("source_album"))
    }.getOrNull()
    fun independentlyNamed(c:Context,uri:Uri,name:String,i:Identity){
        if(i.kind=="cosplay")return
        // Model dependencies stay together. Loose projects and OBJ/MTL sets
        // cannot be renamed as if they were independent content.
        val ext=extension(name).lowercase(Locale.ROOT)
        require(ext in setOf("glb","gltf","glb.zip","gltf.zip","stl")){"Dependency-sensitive model: retain its original path"}
        if(ext!="stl"){
            val bytes=c.contentResolver.openInputStream(uri)?.use{input->
                val out=java.io.ByteArrayOutputStream();val b=ByteArray(131072)
                while(true){val n=input.read(b);if(n<0)break;if(n==0)continue;require(n<=NexusMesh.MAX_BYTES-out.size()){"Model exceeds dependency verification budget"};out.write(b,0,n)}
                out.toByteArray()
            }?:error("Model unavailable")
            NexusGltfAdmission.verify(NexusGltfBundle.normalize(bytes))
        }
    }
    private fun organize(c:Context,uri:Uri,title:String,mime:String,i:Identity):String{
        val f=FgTeraboxDocumentsProvider.scopedFile(uri)
        val item=NexusCloudIdentity.verify(c,uri,title,mime,{Thread.currentThread().isInterrupted}){_,_->}
        independentlyNamed(c,uri,title,i)
        record(c,item.sha256,i)
        val remote=NexusCloudOperations.inspect(c,f.scope,f.path)
        check(remote.bytes==item.length&&NexusCloudIdentity.known(c,uri)==item.sha256){"Cloud identity changed"}
        val folder=remote.path.substringBeforeLast('/').ifEmpty{"/"}
        val cloud=FgTeraboxClient.forScope(c,f.scope)
        val names=mutableListOf<String>()
        var complete=false
        for(page in 1..100){
            val rows=cloud.listPage(folder,page)
            for(k in 0 until rows.length()){
                val row=rows.getJSONObject(k)
                names.add(row.getString("path").substringAfterLast('/'))
            }
            if(rows.length()<FgTeraboxClient.pageSize()){complete=true;break}
        }
        check(complete){"Destination listing incomplete"}
        val desired=candidate(i,remote.path.substringAfterLast('/'),names)
        if(desired==remote.path.substringAfterLast('/'))return desired+" · identity saved"
        val receipt=NexusCloudOperations.move(c,remote,folder,desired,true)
        check(receipt.state=="MOVED"){"Name requires reconciliation: "+receipt.state}
        return desired+" · identity saved; reversible move recorded"
    }
    fun show(a:Activity,uri:Uri,title:String,mime:String){
        val kinds=if(mime.startsWith("image/"))listOf("cosplay") else listOf("character_model","object_model")
        fun edit(kind:String){
            val layout=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;setPadding(24,12,24,12)}
            val id=EditText(a).apply{hint="Canonical character ID";setSingleLine()}
            val extra=EditText(a).apply{hint=when(kind){"cosplay"->"Cosplayer name";"character_model"->"Transformation (if known)";else->"Object name"};setSingleLine()}
            val album=EditText(a).apply{hint="Original source album identity";setSingleLine()}
            if(kind!="object_model")layout.addView(id)
            layout.addView(extra);if(kind=="cosplay")layout.addView(album)
            layout.addView(TextView(a).apply{text="Use verified identity metadata only. The original source name is retained; dependencies stay intact. The rename is reversible."})
            val d=AlertDialog.Builder(a).setTitle("Confirm identity and organise").setView(layout).setNegativeButton("Cancel",null).setPositiveButton("Apply",null).create()
            d.setOnShowListener{d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                val cid=id.text.toString();val detail=extra.text.toString();val origin=album.text.toString()
                d.dismiss()
                val progress=AlertDialog.Builder(a).setTitle("Verify and organise").setMessage("Checking the original and canonical identity").setCancelable(false).show()
                worker.execute{
                    val result=runCatching{organize(a.applicationContext,uri,title,mime,identity(a.applicationContext,kind,cid,detail,origin))}
                    a.runOnUiThread{
                        if(!a.isFinishing&&!a.isDestroyed){progress.dismiss();AlertDialog.Builder(a).setTitle("Organisation").setMessage(result.getOrElse{it.message.orEmpty().take(200)}).setPositiveButton("Close",null).show()}
                    }
                }
            }}
            d.show()
        }
        if(kinds.size==1)edit(kinds.single()) else AlertDialog.Builder(a).setTitle("Model identity")
            .setItems(arrayOf("Character model","Object model")){_,i->edit(kinds[i])}.setNegativeButton("Cancel",null).show()
    }
}
