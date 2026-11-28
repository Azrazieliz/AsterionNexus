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
                if(id in setOf("downloader","sound","converter"))rows.add(Source(id,j.getString("name"),j.optString("root"),prefs(c).getBoolean(key(account,id)+":enabled",false)))
            }
        }
        return rows
    }
    fun configure(c:Context,account:String,id:String,root:String,enable:Boolean){
        require(id in setOf("downloader","sound","converter"))
        // Pause before waiting on a running cycle. Never change its source root mid-cycle.
        check(prefs(c).edit().putBoolean(key(account,id)+":enabled",false).commit())
        if(!enable){NexusOrganizationJob.schedule(c);return}
        synchronized(this){scoped(c,account){client,sources->
            if(enable){
                require(root.isNotBlank()){"Choose the actual standalone-file ingress folder"}
                client.listPage(root,1)
                sources.configure(id,root,false) // legacy automatic worker remains disabled
            }
            check(prefs(c).edit().putBoolean(key(account,id)+":enabled",enable)
                .remove(key(account,id)+":page").commit())
        }}
        NexusOrganizationJob.schedule(c)
    }
    fun anyEnabled(c:Context)=prefs(c).all.any{it.key.endsWith(":enabled")&&it.value==true}
    fun lastStatus(c:Context,account:String):String=prefs(c).getString(account+":status","No automatic cycle recorded").orEmpty()
    internal fun standalone(name:String):Boolean{
        val ext=name.substringAfterLast('.',"").lowercase(Locale.ROOT)
        // No loose 3D meshes, packages, project files, installers, artwork
        // needing identity classification, or dependent sidecar formats.
        return ext in setOf("epub","cbz","cbr","pdf","mp3","m4a","flac","wav","ogg","opus","aac","mid","midi","mp4","mkv","webm","mov","m4v","txt","docx","xlsx","pptx","odt","ods","csv","md","rtf")
    }
    @Synchronized fun run(c:Context,account:String,stop:()->Boolean):Cycle{
        var scanned=0;var moved=0;var review=0;var deferred=0
        val failures=mutableSetOf<String>()
        val settings=prefs(c)
        val blocked=NexusCloudOperations.priorMoves(c,account).toMutableSet()
        scoped(c,account){client,sources->
            for(id in listOf("downloader","sound","converter")){
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
                    // A partial page cannot prove that a companion subtitle,
                    // cue or manifest does not exist on another page. Inspect
                    // all direct siblings within a bounded snapshot before
                    // routing sidecar-sensitive media; otherwise leave them.
                    val names=mutableListOf<String>()
                    var completeSiblings=false
                    for(p in 1..10){
                        if(stop()){completed=false;break}
                        val list=if(p==page)rows else client.listPage(root,p)
                        for(k in 0 until list.length()){
                            val candidate=list.optJSONObject(k)?.optString("path")?:continue
                            if(candidate.substringBeforeLast('/')==root)names.add(candidate.substringAfterLast('/'))
                        }
                        if(list.length()<FgTeraboxClient.pageSize()){completeSiblings=true;break}
                    }
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
                        val ext=name.substringAfterLast('.',"").lowercase(Locale.ROOT)
                        val kind=FgRounderSources.classify(name)
                        val identityAsset=kind=="image"||kind=="model"
                        if((!standalone(name)&&!identityAsset)||(!completeSiblings&&ext !in setOf("epub","cbz","cbr","pdf"))||
                            name.substringBeforeLast('.').lowercase(Locale.ROOT) in sidecarStems){review++;continue}
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
                            var outputName=name
                            if(identityAsset){
                                val uri=com.openlocal.office.core.FgTeraboxDocumentsProvider.uriForAccount(account,path,current.bytes)
                                val sha=NexusCloudIdentity.known(c,uri)
                                val identity=sha?.let{NexusCanonicalNaming.known(c,it)}
                                if(identity==null||(kind=="image"&&identity.kind!="cosplay")||
                                    (kind=="model"&&identity.kind !in setOf("character_model","object_model"))){review++;continue}
                                NexusCanonicalNaming.independentlyNamed(c,uri,name,identity)
                                val siblings=mutableListOf<String>()
                                var complete=false
                                for(p in 1..100){
                                    if(stop())break
                                    val entries=client.listPage(route.destination,p)
                                    for(k in 0 until entries.length())siblings.add(entries.getJSONObject(k).getString("path").substringAfterLast('/'))
                                    if(entries.length()<FgTeraboxClient.pageSize()){complete=true;break}
                                }
                                if(!complete){review++;continue}
                                outputName=NexusCanonicalNaming.candidate(identity,name,siblings,false)
                            }
                            if(stop()||!settings.getBoolean(base+":enabled",false)){completed=false;break}
                            val receipt=NexusCloudOperations.move(c,current,route.destination,outputName,true)
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
