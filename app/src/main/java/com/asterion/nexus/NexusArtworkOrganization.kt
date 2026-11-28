package com.asterion.nexus

import android.content.Context
import android.net.Uri
import com.ailm.android.runtime.StandaloneRuntime
import com.openlocal.office.core.FgTeraboxClient
import com.openlocal.office.core.FgTeraboxDocumentsProvider
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.Locale

/** Host adapter for the retained Core artwork pipeline, not a second
 * classifier. Only independently verified Core decisions can reach it. */
object NexusArtworkOrganization{
    data class Settings(val source:String,val destination:String,val enabled:Boolean)
    private fun prefs(c:Context)=c.getSharedPreferences("nexus_artwork_organization_v1",Context.MODE_PRIVATE)
    fun settings(c:Context,account:String)=Settings(
        prefs(c).getString("$account:source","").orEmpty(),
        prefs(c).getString("$account:destination","").orEmpty(),
        prefs(c).getBoolean("$account:enabled",false))
    internal fun inside(path:String,root:String)=root.isNotBlank()&&root!="/"&&path.startsWith(root+"/")
    internal fun validateRoots(source:String,destination:String){
        fun checkPath(p:String){
            require(p.startsWith("/")&&p.length in 2..512&&!p.endsWith("/")&&!p.contains('\\')&&p.drop(1).split('/').all{it.isNotBlank()&&it!="."&&it!=".."&&it.none{c->c.isISOControl()}}){"Choose an existing, dedicated cloud folder"}
        }
        checkPath(source);checkPath(destination)
        require(source!=destination&&!inside(destination,source)){"Destination cannot be inside the ingress folder"}
    }
    fun configure(c:Context,account:String,source:String,destination:String,enabled:Boolean){
        val p=prefs(c)
        check(p.edit().putBoolean("$account:enabled",false).commit())
        if(!enabled)return
        validateRoots(source,destination)
        val cloud=FgTeraboxClient.forScope(c,account)
        cloud.requireScope(account)
        cloud.listPage(source,1);cloud.listPage(destination,1)
        cloud.requireScope(account)
        check(p.edit().putString("$account:source",source).putString("$account:destination",destination)
            .putBoolean("$account:enabled",true).commit())
    }
    fun install(c:Context){
        val app=c.applicationContext
        StandaloneRuntime.installNexusCloudArtworkMover{request->route(app,request)}
    }
    internal fun sequence(names:List<String>,prefix:String,extension:String):Int{
        val suffix=if(extension.isEmpty())"" else "\\."+Regex.escape(extension)
        val pattern=Regex("^"+Regex.escape(prefix)+"\\s+(\\d+)"+suffix+"$",RegexOption.IGNORE_CASE)
        val max=names.mapNotNull{pattern.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull()}.maxOrNull()?:0
        check(max<Int.MAX_VALUE){"Artwork filename sequence exhausted"}
        return max+1
    }
    private fun children(cloud:FgTeraboxClient,parent:String,stop:()->Boolean):List<Pair<String,Boolean>>{
        val result=ArrayList<Pair<String,Boolean>>()
        for(page in 1..100){
            if(stop())throw InterruptedIOException("Artwork organization paused")
            val rows=cloud.listPage(parent,page)
            for(i in 0 until rows.length()){
                val row=rows.getJSONObject(i)
                val path=row.getString("path")
                if(path.substringBeforeLast('/').ifEmpty{"/"}==parent)
                    result.add(path.substringAfterLast('/') to (row.optInt("isdir",0)==1))
            }
            if(rows.length()<FgTeraboxClient.pageSize())return result
        }
        throw IOException("Complete destination listing unavailable")
    }
    private fun folder(cloud:FgTeraboxClient,parent:String,segment:String,stop:()->Boolean):String{
        require(segment.isNotBlank()&&segment.length<=96&&!segment.contains('/')&&!segment.contains('\\')&&segment!="."&&segment!="..")
        val matches=children(cloud,parent,stop).filter{it.first.equals(segment,true)}
        if(matches.size==1&&matches[0].second)return parent+"/"+matches[0].first
        check(matches.isEmpty()){"Destination folder conflicts with existing content"}
        if(stop())throw InterruptedIOException("Artwork organization paused")
        val target=NexusCloudOperations.target(parent,segment)
        cloud.createDirectory(target)
        val confirmed=children(cloud,parent,stop).filter{it.first.equals(segment,true)}
        check(confirmed.size==1&&confirmed[0].second){"Created cloud folder could not be verified"}
        return parent+"/"+confirmed[0].first
    }
    private fun shaAndMd5(cloud:FgTeraboxClient,remote:NexusCloudOperations.Remote,stop:()->Boolean):Pair<String,String>{
        val sha=MessageDigest.getInstance("SHA-256");val md5=MessageDigest.getInstance("MD5")
        var count=0L
        cloud.open(remote.path).use{input->
            val b=ByteArray(131072)
            while(true){
                if(stop())throw InterruptedIOException("Artwork organization paused")
                val n=input.read(b);if(n<0)break;if(n==0)continue
                check(n<=remote.bytes-count){"Remote source exceeded expected length"}
                sha.update(b,0,n);md5.update(b,0,n);count+=n
            }
        }
        check(count==remote.bytes){"Remote source incomplete"}
        fun hex(md:MessageDigest)=md.digest().joinToString(""){String.format(Locale.ROOT,"%02x",it.toInt() and 255)}
        return hex(sha) to hex(md5)
    }
    @Synchronized private fun route(c:Context,r:StandaloneRuntime.NexusCloudArtworkRequest):Map<String,Any>{
        fun blocked(reason:String)=mapOf("ok" to false,"status" to "cloud_review_required","message" to reason)
        val selected=runCatching{FgTeraboxDocumentsProvider.scopedFile(Uri.parse(r.sourceUri))}.getOrElse{return blocked("Explicitly scoped artwork source required")}
        val config=settings(c,selected.scope)
        if(!config.enabled||!inside(selected.path,config.source))return blocked("Cloud artwork organization not enabled for this ingress folder")
        fun stop()=Thread.currentThread().isInterrupted||StandaloneRuntime.automationPauseRequested()||settings(c,selected.scope)!=config
        return try{
            validateRoots(config.source,config.destination)
            check(r.bytes==selected.size&&r.bytes in 1..(256L*1024*1024)){"Artwork source size not supported"}
            check(r.extension.isNotBlank()&&selected.path.substringAfterLast('.').equals(r.extension,true)){"Source extension changed"}
            check(r.folders.isNotEmpty()&&r.folders.size<=8&&r.prefix.isNotBlank()){"Incomplete canonical route"}
            val cloud=FgTeraboxClient.forScope(c,selected.scope)
            val remote=NexusCloudOperations.inspect(c,selected.scope,selected.path)
            check(remote.bytes==r.bytes&&remote.md5.isNotEmpty()){"Remote identity or checksum unavailable"}
            check(remote.fsId !in NexusCloudOperations.priorMoves(c,selected.scope)){"Prior move requires review"}
            val (sha,md5)=shaAndMd5(cloud,remote,::stop)
            check(sha==r.sha&&md5==remote.md5){"Cloud bytes no longer match Core analysis"}
            var target=config.destination
            for(segment in r.folders)target=folder(cloud,target,segment,::stop)
            val names=children(cloud,target,::stop).map{it.first}
            val counter=sequence(names,r.prefix,r.extension)
            val filename=r.prefix+" "+counter+"."+r.extension
            check(filename.length<=255&&filename.toByteArray(Charsets.UTF_8).size<=255){"Canonical filename exceeds provider limit"}
            val targetPath=NexusCloudOperations.target(target,filename)
            check(!inside(targetPath,config.source)){"Destination overlaps ingress"}
            check(!stop()){"Artwork organization paused"}
            val binding=NexusCloudOperations.ArtworkBinding(r.imageId,r.sourceUri,r.sha,r.sourceFolder,r.sourceRelative,
                targetPath.removePrefix(config.destination+"/"))
            val receipt=NexusCloudOperations.move(c,remote,target,filename,true,binding)
            if(receipt.state!="MOVED")blocked("Remote move requires reconciliation: "+receipt.state)
            else mapOf("ok" to true,"status" to "organized","uri" to FgTeraboxDocumentsProvider.uriForAccount(selected.scope,targetPath,r.bytes).toString(),
                "folder" to r.folders.joinToString("/"),"filename" to filename,"sequence" to counter,"source_cleanup_pending" to false)
        }catch(e:Exception){blocked(e.message?.take(160)?:e.javaClass.simpleName)}
    }
}
