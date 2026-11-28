package com.asterion.nexus

import android.content.Context
import com.openlocal.office.core.FgRounderSources
import com.openlocal.office.core.FgTeraboxClient
import com.openlocal.office.core.FgTeraboxSearchIndex
import java.util.Locale

/** Account-specific, opt-in routing of standalone files from actual owner
 * registered ingress folders. Reuse Rounder's route authority and Nexus's
 * reversible move path; never use the legacy unjournaled organizer. */
object NexusAutomaticOrganization {
    data class Source(val id:String,val name:String,val root:String,val enabled:Boolean)
    data class Cycle(val scanned:Int,val moved:Int,val review:Int,val deferred:Int,val message:String)
    private fun prefs(c:Context)=c.getSharedPreferences("nexus_automatic_organization_v1",Context.MODE_PRIVATE)
    private fun key(account:String,id:String)=account+":"+id
    private fun scoped(c:Context,account:String,block:(FgTeraboxClient,FgRounderSources)->Unit){
        val client=FgTeraboxClient.forScope(c,account)
        client.requireScope(account)
        FgTeraboxSearchIndex(c,client,account,null).use{index->
            block(client,FgRounderSources(c,index))
        }
    }
    fun sources(c:Context,account:String):List<Source>{
        val rows=mutableListOf<Source>()
        scoped(c,account){_,sources->
            val profiles=sources.profiles()
            for(i in 0 until profiles.length()){
                val j=profiles.getJSONObject(i)
                val id=j.getString("id")
                if(id in setOf("downloader","sound"))rows.add(Source(id,j.getString("name"),j.optString("root"),prefs(c).getBoolean(key(account,id)+":enabled",false)))
            }
        }
        return rows
    }
    @Synchronized fun configure(c:Context,account:String,id:String,root:String,enable:Boolean){
        require(id in setOf("downloader","sound"))
        scoped(c,account){client,sources->
            if(enable){
                require(root.isNotBlank()){"Choose the actual standalone-file ingress folder"}
                client.listPage(root,1)
                sources.configure(id,root,false) // legacy automatic worker remains disabled
            }
            check(prefs(c).edit().putBoolean(key(account,id)+":enabled",enable)
                .remove(key(account,id)+":page").commit())
        }
        NexusOrganizationJob.schedule(c)
    }
    fun anyEnabled(c:Context)=prefs(c).all.any{it.key.endsWith(":enabled")&&it.value==true}
    fun lastStatus(c:Context,account:String):String=prefs(c).getString(account+":status","No automatic cycle recorded").orEmpty()
    internal fun standalone(name:String):Boolean{
        val ext=name.substringAfterLast('.',"").lowercase(Locale.ROOT)
        // No loose 3D meshes, packages, project files, installers, artwork
        // needing identity classification, or dependent sidecar formats.
        return ext in setOf("epub","cbz","cbr","pdf","mp3","m4a","flac","wav","ogg","opus","aac","mid","midi","mp4","mkv","webm","mov","m4v")
    }
    @Synchronized fun run(c:Context,account:String,stop:()->Boolean):Cycle{
        var scanned=0;var moved=0;var review=0;var deferred=0
        val failures=mutableSetOf<String>()
        val settings=prefs(c)
        val blocked=NexusCloudOperations.priorMoves(c,account).toMutableSet()
        scoped(c,account){client,sources->
            for(id in listOf("downloader","sound")){
                val base=key(account,id)
                if(stop()||!settings.getBoolean(base+":enabled",false))continue
                val root=sources.root(id)
                if(root.isBlank())continue
                var page=settings.getInt(base+":page",1).coerceIn(1,100000)
                try{
                    // One bounded listing per configured source per cycle.
                    val rows=client.listPage(root,page)
                    var changed=false
                    var completed=true
                    val names=(0 until rows.length()).mapNotNull{rows.optJSONObject(it)?.optString("path")}
                        .filter{it.startsWith(root+"/")}.map{it.substringAfterLast('/')}
                    val sidecarStems=names.filter{it.substringAfterLast('.',"").lowercase(Locale.ROOT) in setOf("srt","ass","ssa","vtt","sub","idx","nfo","cue","m3u","m3u8","lrc","xmp","json")}
                        .map{it.substringBeforeLast('.').lowercase(Locale.ROOT)}.toSet()
                    for(i in 0 until rows.length()){
                        if(stop()||!settings.getBoolean(base+":enabled",false)||moved>=12){completed=false;break}
                        val item=rows.optJSONObject(i)?:continue
                        if(item.optInt("isdir",0)!=0)continue
                        val path=item.optString("path")
                        if(path.substringBeforeLast('/')!=root)continue
                        scanned++
                        val name=path.substringAfterLast('/')
                        if(!standalone(name)||name.substringBeforeLast('.').lowercase(Locale.ROOT) in sidecarStems){review++;continue}
                        val route=sources.route(path)
                        if(route==null||route.source!=id||path.startsWith(route.destination+"/")){deferred++;continue}
                        try{
                            val current=NexusCloudOperations.inspect(c,account,path)
                            if(current.fsId in blocked){deferred++;continue}
                            if(current.md5.isEmpty()){review++;continue}
                            // Only use the existing accepted destination. Missing
                            // paths are reported, not silently guessed or created.
                            client.listPage(route.destination,1)
                            client.requireScope(account)
                            if(stop()||!settings.getBoolean(base+":enabled",false)){completed=false;break}
                            val receipt=NexusCloudOperations.move(c,current,route.destination,name,true)
                            blocked.add(current.fsId)
                            if(receipt.state=="MOVED"){moved++;changed=true}else{review++;failures.add(receipt.state)}
                        }catch(e:Exception){review++;failures.add(e.javaClass.simpleName)}
                    }
                    // A move changes listing offsets. Restart the first page
                    // after successful moves; otherwise advance bounded pages.
                    page=when{changed->1;!completed->page;rows.length()<FgTeraboxClient.pageSize()->1;else->if(page==100000)1 else page+1}
                    settings.edit().putInt(base+":page",page).commit()
                }catch(e:Exception){failures.add(e.javaClass.simpleName);deferred++}
            }
        }
        val message="Scanned $scanned · moved $moved · review $review · deferred $deferred"+
            if(failures.isEmpty())"" else " · "+failures.joinToString(", ")
        settings.edit().putString(account+":status",message).putLong(account+":updated",System.currentTimeMillis()).commit()
        return Cycle(scanned,moved,review,deferred,message)
    }
}
