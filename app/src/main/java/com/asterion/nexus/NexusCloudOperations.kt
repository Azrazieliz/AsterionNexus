package com.asterion.nexus

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.azrael.pixivdumpsync.NduCloudQueue
import com.azrael.pixivdumpsync.NduSettings
import com.openlocal.office.core.FgTeraboxClient
import com.openlocal.office.core.FgTeraboxSearchIndex
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** A move is a durable, explicitly selected action on a concrete cloud object.
 * The existing Rounder client owns the actual server operation. Unknown
 * transport outcomes are reconciled by remote identity, not replayed. */
object NexusCloudOperations {
    data class Remote(val account:String,val fsId:Long,val path:String,val bytes:Long,val md5:String){
        init{require(account.matches(Regex("[0-9a-f-]{36}"))&&fsId>0&&bytes>0)}
        fun same(other:Remote)=account==other.account&&fsId==other.fsId&&bytes==other.bytes&&
            (md5.isEmpty()||other.md5.isEmpty()||md5==other.md5)
    }
    data class Move(val id:String,val original:Remote,val target:String,val state:String,val updated:Long,val error:String)
    private fun path(input:String):String{
        require(input.length in 2..4096&&input.startsWith("/")&&!input.endsWith("/")&&!input.contains('\\'))
        require(input.drop(1).split('/').all{it.isNotBlank()&&it!="."&&it!=".."&&it.none{c->c.isISOControl()}})
        return input
    }
    fun target(directory:String,name:String):String{
        require(name.isNotBlank()&&name.length<=255&&!name.contains('/')&&!name.contains('\\')&&name!="."&&name!=".."&&name.none{it.isISOControl()})
        require(directory.startsWith("/")&&!directory.startsWith("//")){"Absolute cloud directory required"}
        val folder=directory.trimEnd('/')
        return path(folder+"/"+name)
    }
    private fun inspect(client:FgTeraboxClient,account:String,location:String):Remote{
        client.requireScope(account)
        val data=client.fileDetails(path(location))
        require(data.optString("path")==location&&data.optInt("isdir",0)==0){"Not the selected remote file"}
        val md5=data.optString("md5").lowercase(Locale.ROOT).takeIf{it.matches(Regex("[0-9a-f]{32}"))}.orEmpty()
        val remote=Remote(account,data.optLong("fs_id",-1),location,data.optLong("size",-1),md5)
        client.requireScope(account)
        return remote
    }
    fun inspect(context:Context,account:String,location:String):Remote =
        inspect(FgTeraboxClient.forScope(context,account),account,location)
    private fun root(context:Context):File=File(context.filesDir,"nexus-cloud-moves").apply{
        check(isDirectory||mkdirs()||isDirectory){"Cloud journal unavailable"}
    }
    private fun journal(context:Context,id:String):AtomicFile{
        require(id.matches(Regex("[0-9a-f-]{36}")))
        return AtomicFile(File(root(context),id+".json"))
    }
    private fun persist(context:Context,move:Move):Move{
        val value=JSONObject().put("version",1).put("id",move.id).put("account",move.original.account)
            .put("fs_id",move.original.fsId).put("source",move.original.path).put("bytes",move.original.bytes)
            .put("md5",move.original.md5).put("target",move.target).put("state",move.state)
            .put("updated",move.updated).put("error",move.error.take(100))
        val file=journal(context,move.id);val out=file.startWrite()
        try{out.write(value.toString().toByteArray(Charsets.UTF_8));file.finishWrite(out)}
        catch(e:Exception){file.failWrite(out);throw e}
        return move
    }
    private fun load(context:Context,id:String):Move{
        val f=journal(context,id)
        val bytes=f.readFully();require(bytes.size<=32768){"Invalid move journal"}
        val j=JSONObject(String(bytes,Charsets.UTF_8))
        check(j.getInt("version")==1&&j.getString("id")==id)
        return Move(id,Remote(j.getString("account"),j.getLong("fs_id"),path(j.getString("source")),
            j.getLong("bytes"),j.getString("md5")),path(j.getString("target")),j.getString("state"),
            j.getLong("updated"),j.optString("error"))
    }
    fun history(context:Context,limit:Int=60):List<Move>{
        require(limit in 1..200)
        return root(context).listFiles().orEmpty().filter{it.name.matches(Regex("[0-9a-f-]{36}\\.json"))}
            .sortedByDescending{it.lastModified()}.take(limit)
            .mapNotNull{runCatching{load(context,it.nameWithoutExtension)}.getOrNull()}
    }
    private fun state(context:Context,move:Move,value:String,error:String=""):Move =
        persist(context,move.copy(state=value,updated=System.currentTimeMillis(),error=error))
    private fun note(context:Context,client:FgTeraboxClient,move:Move,from:String,to:String){
        val remote=move.original
        NexusRegistry(context).noteRemoteMove(remote.account,remote.fsId,from,to,remote.bytes,remote.md5)
        NduSettings.uploadAccount(context,remote.account)?.let{account->
            NduCloudQueue(context).use{it.relocateVerified(account.scope,remote.fsId,from,to,remote.bytes)}
        }
        FgTeraboxSearchIndex(context,client,remote.account,null).use{it.noteMoved(remote.account,from,to)}
    }
    /** An automatic cycle never repeats a prior operation, including an
     * unresolved request or owner rollback. Read every journal, not just the UI page. */
    @Synchronized fun priorMoves(context:Context,account:String):Set<Long>{
        val ids=HashSet<Long>()
        for(file in root(context).listFiles()?:throw IOException("Move history unavailable")){
            if(!file.name.matches(Regex("[0-9a-f-]{36}\\.json")))continue
            val entry=load(context,file.nameWithoutExtension)
            if(entry.original.account==account)ids.add(entry.original.fsId)
        }
        return ids
    }
    /** The source identity shown in the confirmation is re-read before mutation. */
    @Synchronized fun move(context:Context,expected:Remote,directory:String,name:String,automatic:Boolean=false):Move{
        val destination=target(directory,name)
        require(destination!=expected.path){"Source and destination are identical"}
        // Root is a valid same-account destination; the server must fail on conflicts.
        val client=FgTeraboxClient.forScope(context,expected.account)
        val current=inspect(client,expected.account,expected.path)
        check(expected.same(current)){"Selected source changed"}
        if(automatic){
            check(expected.md5.isNotEmpty()&&current.md5==expected.md5){"Automatic routing requires matching remote checksum"}
            check(current.fsId !in priorMoves(context,current.account)){"Prior move requires explicit owner review"}
        }
        var entry=persist(context,Move(UUID.randomUUID().toString(),current,destination,"PREPARED",System.currentTimeMillis(),""))
        val job="nexus-cloud-move:"+entry.id
        val registry=NexusRegistry(context)
        registry.job(job,"QUEUED",0,null)
        try{
            entry=state(context,entry,"REQUESTED")
            registry.job(job,"RECEIVING",0,null)
            client.moveFileChecked(current.path,destination.substringBeforeLast('/').ifEmpty{"/"},destination.substringAfterLast('/'),current.bytes)
            return reconcileInternal(context,entry,false)
        }catch(e:Exception){
            registry.job(job,"FAILED",0,e.javaClass.simpleName)
            return state(context,entry,"UNKNOWN",e.javaClass.simpleName)
        }
    }
    private fun reconcileInternal(context:Context,move:Move,undo:Boolean):Move{
        val client=FgTeraboxClient.forScope(context,move.original.account)
        val wanted=if(undo)move.original.path else move.target
        val former=if(undo)move.target else move.original.path
        val actual=runCatching{inspect(client,move.original.account,wanted)}.getOrNull()
        if(actual==null||!move.original.same(actual))
            return state(context,move,if(undo)"UNDO_UNKNOWN" else "UNKNOWN","Destination identity not confirmed")
        val duplicate=runCatching{inspect(client,move.original.account,former)}.getOrNull()
        if(duplicate!=null&&move.original.same(duplicate))
            return state(context,move,if(undo)"UNDO_UNKNOWN" else "UNKNOWN","Identity reported at both paths")
        note(context,client,move,former,wanted)
        NexusRegistry(context).job("nexus-cloud-move:"+move.id,"INDEXED",move.original.bytes,null)
        return state(context,move,if(undo)"ROLLED_BACK" else "MOVED")
    }
    @Synchronized fun reconcile(context:Context,id:String):Move{
        val entry=load(context,id)
        return when(entry.state){
            "REQUESTED","UNKNOWN"->reconcileInternal(context,entry,false)
            "UNDO_REQUESTED","UNDO_UNKNOWN"->reconcileInternal(context,entry,true)
            else->entry
        }
    }
    @Synchronized fun undo(context:Context,id:String):Move{
        var entry=load(context,id)
        check(entry.state=="MOVED"){"Only a verified move can be undone"}
        val client=FgTeraboxClient.forScope(context,entry.original.account)
        val current=inspect(client,entry.original.account,entry.target)
        check(entry.original.same(current)){"Moved object has changed"}
        val previous=entry.original.path

        entry=state(context,entry,"UNDO_REQUESTED")
        try{
            client.moveFileChecked(current.path,previous.substringBeforeLast('/').ifEmpty{"/"},previous.substringAfterLast('/'),current.bytes)
            return reconcileInternal(context,entry,true)
        }catch(e:Exception){return state(context,entry,"UNDO_UNKNOWN",e.javaClass.simpleName)}
    }
    /** Only the explicitly selected object is downloaded. Bytes are checked
     * against remote MD5 when supplied, then independently SHA-256 registered. */
    @Synchronized fun pin(context:Context,account:String,location:String,title:String,mime:String,expectedSize:Long):NexusRegistry.Item{
        val client=FgTeraboxClient.forScope(context,account)
        val remote=inspect(client,account,location)
        check(expectedSize<0||remote.bytes==expectedSize){"Cloud file changed"}
        val registry=NexusRegistry(context)
        val existing=registry.remote(account,remote.fsId)
        if(existing!=null){
            check(existing.length==remote.bytes&&(existing.md5.isEmpty()||remote.md5.isEmpty()||existing.md5==remote.md5)){"Stored remote identity changed"}
            val local=registry.find(existing.sha256)
            if(local!=null&&registry.verifyOriginal(local)&&remote.md5.isNotEmpty()){
                val digest=MessageDigest.getInstance("MD5")
                registry.original(local).inputStream().use{input->
                    val buf=ByteArray(131072)
                    while(true){val n=input.read(buf);if(n<0)break;if(n>0)digest.update(buf,0,n)}
                }
                if(digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}==remote.md5){
                    registry.bindRemote(local,account,remote.fsId,location,remote.bytes,remote.md5,true)
                    return local
                }
            }
        }
        check(remote.bytes<=128L*1024*1024*1024){"File exceeds offline pin limit"}
        val usable=context.cacheDir.usableSpace
        check(remote.bytes<=maxOf(0L,(usable-64L*1024*1024)/2)){"Insufficient space for verified offline staging"}
        val staged=File.createTempFile("nexus-pin-",".part",context.cacheDir)
        val job="nexus-pin-v1:"+account+":"+remote.fsId
        registry.job(job,"QUEUED",0,null)
        try{
            registry.job(job,"RECEIVING",0,null)
            val md5=MessageDigest.getInstance("MD5")
            val sha=MessageDigest.getInstance("SHA-256")
            var count=0L
            client.open(location).use{input->FileOutputStream(staged).use{out->
                val buffer=ByteArray(131072)
                while(true){
                    if(Thread.currentThread().isInterrupted)throw InterruptedIOException("Pin cancelled")
                    val n=input.read(buffer);if(n<0)break;if(n==0)continue
                    check(n<=remote.bytes-count){"Remote byte count exceeded"}
                    out.write(buffer,0,n);md5.update(buffer,0,n);sha.update(buffer,0,n);count+=n
                }
                out.flush();out.fd.sync()
            }}
            check(count==remote.bytes){"Remote stream incomplete"}
            registry.job(job,"BYTES_COMPLETE",count,null)
            val downloadedMd5=md5.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
            check(remote.md5.isEmpty()||downloadedMd5==remote.md5){"Remote checksum mismatch"}
            val after=inspect(client,account,location)
            check(remote.same(after)&&(after.md5.isEmpty()||downloadedMd5==after.md5)){"Remote changed during pin"}
            val digest=sha.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
            check(existing==null||existing.sha256==digest){"Previously verified content identity changed"}
            registry.job(job,"VERIFIED",count,null)
            val item=staged.inputStream().use{registry.ingest(it,title,mime,"terabox",account,
                "terabox:"+account+":"+remote.fsId,count,digest)}
            registry.bindRemote(item,account,remote.fsId,location,count,after.md5,true)
            registry.job(job,"INDEXED",count,null)
            return item
        }catch(e:Exception){registry.job(job,"FAILED",0,e.javaClass.simpleName);throw e}
        finally{staged.delete()}
    }
}
