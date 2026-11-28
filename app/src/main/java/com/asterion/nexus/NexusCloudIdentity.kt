package com.asterion.nexus

import android.content.Context
import android.net.Uri
import com.openlocal.office.core.FgTeraboxClient
import com.openlocal.office.core.FgTeraboxDocumentsProvider
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest

object NexusCloudIdentity{
    private fun selected(context:Context,uri:Uri):NexusCloudOperations.Remote{
        val file=FgTeraboxDocumentsProvider.scopedFile(uri)
        val remote=NexusCloudOperations.inspect(context,file.scope,file.path)
        check(remote.bytes==file.size){"Selected cloud file changed"}
        check(remote.md5.matches(Regex("[0-9a-f]{32}"))){"Cloud checksum unavailable"}
        return remote
    }
    fun known(context:Context,uri:Uri):String?{
        val remote=selected(context,uri)
        val registry=NexusRegistry(context)
        val binding=registry.remote(remote.account,remote.fsId)?:return null
        if(binding.length!=remote.bytes||binding.md5!=remote.md5||binding.path!=remote.path)return null
        val proof=registry.verifiedCloudLocations(binding.sha256).any{it.account==remote.account&&it.fsId==remote.fsId}
        if(proof)return binding.sha256
        val item=registry.find(binding.sha256)?:return null
        val evicted=registry.evictedLocation(item)
        if(evicted!=null&&evicted.account==remote.account&&evicted.fsId==remote.fsId&&evicted.md5==remote.md5)return item.sha256
        if(!registry.localPresent(item))return null
        // A publication receipt alone is not always a full remote digest.
        // Confirm the exact local SHA and remote MD5 before reusing a binding.
        val md5=MessageDigest.getInstance("MD5");val sha=MessageDigest.getInstance("SHA-256")
        registry.original(item).inputStream().use{input->
            val b=ByteArray(131072)
            while(true){if(Thread.currentThread().isInterrupted)throw InterruptedIOException();val n=input.read(b);if(n<0)break;if(n>0){sha.update(b,0,n);md5.update(b,0,n)}}
        }
        return if(hex(sha.digest())==item.sha256&&hex(md5.digest())==remote.md5&&selected(context,uri)==remote)item.sha256 else null
    }
    private fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it.toInt() and 255)}
    fun verify(context:Context,uri:Uri,title:String,mime:String,stop:()->Boolean,progress:(Long,Long)->Unit):NexusRegistry.Item{
        val remote=selected(context,uri)
        val client=FgTeraboxClient.forScope(context,remote.account)
        client.requireScope(remote.account)
        val sha=MessageDigest.getInstance("SHA-256");val md5=MessageDigest.getInstance("MD5")
        val key="nexus-cloud-identity:"+remote.account+":"+remote.fsId
        val registry=NexusRegistry(context)
        registry.job(key,"QUEUED",0,null)
        var count=0L
        try{
            registry.job(key,"RECEIVING",0,null)
            client.open(remote.path).use{input->
                val buffer=ByteArray(262144);var last=0L
                while(true){
                    if(stop()||Thread.currentThread().isInterrupted)throw InterruptedIOException("Verification stopped")
                    val n=input.read(buffer);if(n<0)break;if(n==0)continue
                    if(n>remote.bytes-count)throw IOException("Cloud stream exceeded selected size")
                    count+=n;sha.update(buffer,0,n);md5.update(buffer,0,n)
                    if(count-last>=4L*1024*1024){client.requireScope(remote.account);progress(count,remote.bytes);last=count}
                }
            }
            check(!stop()&&count==remote.bytes&&hex(md5.digest())==remote.md5){"Complete cloud bytes not confirmed"}
            val current=selected(context,uri)
            check(current==remote){"Remote identity changed during verification"}
            registry.job(key,"BYTES_COMPLETE",count,null)
            val item=registry.registerVerifiedRemote(hex(sha.digest()),remote.account,remote.fsId,remote.path,count,remote.md5,mime,title)
            registry.job(key,"INDEXED",count,null)
            progress(count,count)
            return item
        }catch(e:Exception){registry.job(key,"FAILED",count,e.javaClass.simpleName);throw e}
    }
}
