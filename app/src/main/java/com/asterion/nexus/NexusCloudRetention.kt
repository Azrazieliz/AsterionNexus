package com.asterion.nexus

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.openlocal.office.core.FgTeraboxClient
import com.openlocal.office.core.FgTeraboxDocumentsProvider
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest

/** Only an independently read-back remote SHA-256 can authorize removal
 * of a staged local acquisition. Remote path, account, fsId and checksum
 * are checked again at confirmation and every subsequent remote reopen. */
object NexusCloudRetention{
    data class Proof internal constructor(val sha:String,val remote:NexusRegistry.RemoteLocation,val verifiedAt:Long)
    fun candidates(context:Context,sha:String):List<NexusRegistry.RemoteLocation>{
        val registry=NexusRegistry(context)
        val item=registry.find(sha)?:return emptyList()
        if(!registry.localPresent(item))return emptyList()
        // Book processing still depends on a local verified original; do not
        // discard it merely because cloud publication succeeded.
        if(NexusBookIndex.supported(item.mime))return emptyList()
        val authorized=FgTeraboxClient.accounts(context).map{it.scope}.toSet()
        return registry.cleanupCandidates(sha).filter{it.account in authorized}
    }
    private fun requireRemote(context:Context,remote:NexusRegistry.RemoteLocation):FgTeraboxClient{
        val client=FgTeraboxClient.forScope(context,remote.account)
        client.requireScope(remote.account)
        val current=NexusCloudOperations.inspect(context,remote.account,remote.path)
        check(current.fsId==remote.fsId&&current.bytes==remote.length&&current.md5==remote.md5&&remote.md5.matches(Regex("[0-9a-f]{32}"))){"Cloud object changed or checksum unavailable"}
        client.requireScope(remote.account)
        return client
    }
    fun verify(context:Context,sha:String,account:String,fsId:Long,stop:()->Boolean={false}):Proof{
        val registry=NexusRegistry(context)
        val item=registry.find(sha)?:error("Original not registered")
        val remote=candidates(context,sha).singleOrNull{it.account==account&&it.fsId==fsId}
            ?:error("No authorized cleanup candidate")
        check(registry.verifyOriginal(item)){"Local original changed"}
        val client=requireRemote(context,remote)
        val digest=MessageDigest.getInstance("SHA-256")
        val md5=MessageDigest.getInstance("MD5")
        var count=0L
        client.open(remote.path).use{input->
            val buffer=ByteArray(262144)
            while(true){
                if(stop()||Thread.currentThread().isInterrupted)throw InterruptedIOException("Verification cancelled")
                val n=input.read(buffer);if(n<0)break;if(n==0)continue
                if(n>remote.length-count)throw IOException("Cloud stream exceeded expected length")
                digest.update(buffer,0,n);md5.update(buffer,0,n);count+=n
                client.requireScope(remote.account)
            }
        }
        fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it.toInt() and 255)}
        check(count==item.length&&hex(digest.digest())==item.sha256&&hex(md5.digest())==remote.md5){"Complete remote bytes do not match the local original"}
        requireRemote(context,remote)
        return Proof(sha,remote,System.currentTimeMillis())
    }
    fun evict(context:Context,proof:Proof){
        val registry=NexusRegistry(context)
        val item=registry.find(proof.sha)?:error("Original not registered")
        val candidate=candidates(context,proof.sha).singleOrNull{it.account==proof.remote.account&&it.fsId==proof.remote.fsId}
            ?:error("Cleanup eligibility changed")
        check(candidate.path==proof.remote.path&&candidate.md5==proof.remote.md5&&candidate.length==proof.remote.length){"Remote binding changed"}
        requireRemote(context,candidate)
        registry.evictAfterReadback(item,candidate,proof.verifiedAt)
    }
    @JvmStatic fun openEvicted(context:Context,item:NexusRegistry.Item):ParcelFileDescriptor{
        val remote=NexusRegistry(context).evictedLocation(item)?:throw IOException("No verified cloud-only location")
        requireRemote(context,remote)
        val uri=FgTeraboxDocumentsProvider.uriForAccount(remote.account,remote.path,remote.length)
        return context.contentResolver.openFileDescriptor(uri,"r")?:throw IOException("Verified cloud original is not readable")
    }
    fun restore(context:Context,item:NexusRegistry.Item):NexusRegistry.Item{
        val remote=NexusRegistry(context).evictedLocation(item)?:error("No verified cloud-only location")
        requireRemote(context,remote)
        return NexusCloudOperations.pin(context,remote.account,remote.path,item.displayName,item.mime,item.length).also{check(it.sha256==item.sha256){"Restored identity mismatch"}}
    }
}
