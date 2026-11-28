package com.asterion.nexus

import android.content.Context
import android.net.Uri
import com.azrael.pixivdumpsync.NduCloudQueue
import com.azrael.pixivdumpsync.NduCloudScheduler
import com.azrael.pixivdumpsync.NduSettings
import com.azrael.pixivdumpsync.NduTeraboxClient
import com.azrael.pixivdumpsync.NduRemoteReceipt
import com.openlocal.office.core.FgTeraboxClient

/** Reuse the donor's durable upload ledger and verified remote receipts.
 * Local originals are retained until a separate, owner-approved cleanup policy.
 */
object NexusCloudPublication {
    private const val PREF="nexus_cloud_binding"
    fun canPublish(context:Context):Boolean{
        if(!NduSettings.enabled(context)||!NduSettings.cloudOnly(context))return false
        val scope=NduSettings.currentAccountScope(context)
        val binding=context.getSharedPreferences(PREF,0)
        val browser=binding.getString("browser",null).orEmpty()
        return scope.isNotBlank()&&browser.isNotBlank()&&binding.getString("upload",null)==scope&&
            runCatching{FgTeraboxClient.forScope(context,browser).requireScope(browser)}.isSuccess
    }
    fun publicationScope(context:Context):String=if(canPublish(context))context.getSharedPreferences(PREF,0).getString("browser","").orEmpty() else ""
    fun hasUploadAccount(context:Context,scope:String):Boolean=NduSettings.hasAccount(context,scope)
    @Synchronized fun addAccount(context:Context,label:String,input:String):FgTeraboxClient.Account{
        val token=NduSettings.parseNdus(input)
        NduTeraboxClient(token,NduSettings.domain(context)).connect()
        val account=FgTeraboxClient.addAccount(context,label,token)
        NduSettings.saveAccount(context,account.scope,token)
        return account
    }
    @Synchronized fun selectAccount(context:Context,browserScope:String){
        FgTeraboxClient.forScope(context,browserScope).requireScope(browserScope)
        val binding=context.getSharedPreferences(PREF,0)
        binding.edit().clear().commit()
        val uploadScope=NduSettings.activateAccount(context,browserScope)
        check(binding.edit().putString("upload",uploadScope).putString("browser",browserScope).commit()){"Account binding failed"}
        NduSettings.setCloudOnly(context,true);NduSettings.setEnabled(context,true)
        recoverUnassigned(context)
        NduCloudScheduler.requestImmediate(context)
    }
    @Synchronized fun connect(context:Context,cloud:FgTeraboxClient,input:String){
        val token=NduSettings.parseNdus(input)
        val uploader=NduTeraboxClient(token,NduSettings.domain(context))
        uploader.connect()
        // The same authorized account powers browsing and publication.
        val binding=context.getSharedPreferences(PREF,0)
        binding.edit().clear().commit()
        check(cloud.setCookie(token)){"Unable to persist browsing session"}
        cloud.listPage("/",1)
        NduSettings.save(context,token)
        NduSettings.saveAccount(context,cloud.metadataScope(),token,NduSettings.accountScope(context))
        check(binding.edit().putString("upload",NduSettings.accountScope(context))
            .putString("browser",cloud.metadataScope()).commit()){"Account binding not saved"}
        NduSettings.setCloudOnly(context,true)
        NduSettings.setEnabled(context,true)
        recoverUnassigned(context)
        NduCloudScheduler.requestImmediate(context)
    }
    private fun recoverUnassigned(context:Context){
        val uploadScope=NduSettings.currentAccountScope(context)
        if(!canPublish(context)||uploadScope.isBlank())return
        // Recover only Nexus-selected originals never previously assigned to
        // an account. Old donor rows and other-account jobs remain untouched.
        val registry=NexusRegistry(context)
        NduCloudQueue(context).use{queue->
            var offset=0
            while(true){
                val page=registry.acquiredPage(offset,200)
                for(item in page){
                    val uri=NexusLibraryProvider.uriFor(context,item.sha256)
                    if(registry.localPresent(item)&&!queue.hasAnyPublication(uri.toString()))queue.enqueueScoped(category(item),item.displayName,uri,uploadScope)
                }
                if(page.size<200)break
                offset+=page.size
            }
        }
    }
    private fun category(item:NexusRegistry.Item):String=when{
        item.mime.startsWith("image/")->"images"
        item.mime.startsWith("video/")->"videos"
        item.mime.contains("comic")->"comics"
        item.mime.contains("epub")||item.mime.contains("pdf")->"books"
        else->"originals"
    }
    @Synchronized fun enqueueIfConnected(context:Context,item:NexusRegistry.Item):Boolean{
        if(!canPublish(context))return false
        val uri=NexusLibraryProvider.uriFor(context,item.sha256)
        return runCatching{
            val scope=NduSettings.currentAccountScope(context)
            check(scope.isNotBlank())
            NduCloudQueue(context).use{it.enqueueScoped(category(item),item.displayName,uri,scope)}
            NduCloudScheduler.requestImmediate(context)
            true
        }.getOrDefault(false)
    }
    fun status(context:Context,item:NexusRegistry.Item):String{
        val uri=NexusLibraryProvider.uriFor(context,item.sha256).toString()
        return runCatching{
            NduCloudQueue(context).use{
                val proof=it.receiptFor(uri)
                if(proof!=null&&canPublish(context))"TeraBox verified: "+item.displayName
                else if(it.stateFor(category(item),uri)!=null)"Local bytes verified; TeraBox publication pending: "+item.displayName
                else "Local bytes verified; TeraBox publication not queued: "+item.displayName
            }
        }.getOrDefault("Local bytes verified; TeraBox receipt unavailable")
    }
    /** Stage in a content-identified remote folder and use the accepted
     * Rounder fail-on-conflict move. A collision never overwrites an original.
     */
    fun publish(context:Context,client:NduTeraboxClient,uri:Uri,name:String,folder:String,
                cancelled:()->Boolean,progress:(Long,Long)->Unit):NduRemoteReceipt{
        val browserScope=publicationScope(context)
        val account=NduSettings.uploadAccount(context,browserScope)?:error("TeraBox publication account unavailable")
        check(account.scope==NduSettings.currentAccountScope(context)){"TeraBox publication account not aligned"}
        return publishScoped(context,client,uri,name,folder,account,cancelled,progress)
    }
    internal fun publishScoped(context:Context,client:NduTeraboxClient,uri:Uri,name:String,folder:String,
                account:NduSettings.UploadAccount,cancelled:()->Boolean,progress:(Long,Long)->Unit):NduRemoteReceipt{
        fun stop()=cancelled()||!NduSettings.enabled(context)||!NduSettings.cloudOnly(context)||!NduSettings.stillAuthorized(context,account)
        check(!stop()){"TeraBox publication account not aligned"}
        val sha=uri.lastPathSegment?:throw IllegalArgumentException("Original identity missing")
        val item=NexusRegistry(context).find(sha)?:throw IllegalArgumentException("Original unavailable")
        check(NexusRegistry(context).verifyOriginal(item)){"Original bytes no longer match their verified identity"}
        val cloud=FgTeraboxClient.forScope(context,account.browserScope)
        cloud.requireScope(account.browserScope)
        val safeName=NduTeraboxClient.safeFileName(name)
        // On retry, credit a pre-existing final path only if its actual
        // remote whole-file hash matches the original.
        val existing=runCatching{client.verifyExisting(context,uri,safeName,folder,::stop)}.getOrNull()
        if(existing?.hashVerified==true){
            recordLocation(context,item,account.browserScope,existing)
            return existing
        }
        check(!stop()){"Publication cancelled"}
        val browserScope=account.browserScope
        check(!stop()){"Cloud account authorization changed"}
        val stageFolder=NduSettings.validateDirectory(folder+"/Nexus publishing")
        val stageName=sha+"-"+safeName.takeLast(120)
        val staged=runCatching{client.verifyExisting(context,uri,stageName,stageFolder,::stop)}
            .getOrNull()?.takeIf{it.hashVerified}
            ?:client.upload(context,uri,stageName,stageFolder,cancelled=::stop,progress=progress)
        check(staged.size==item.length&&staged.fsId>0){"Remote staging identity/length mismatch"}
        check(!stop()){"Publication cancelled"}
        cloud.requireScope(browserScope)
        cloud.moveFileChecked(staged.path,folder,safeName,item.length)
        cloud.requireScope(browserScope)
        check(!stop()){"Publication account changed before receipt"}
        val final=client.verifyExisting(context,uri,safeName,folder,::stop)
            ?:throw IllegalStateException("Remote final file not verified")
        check(final.hashVerified||final.fsId==staged.fsId){"Remote final identity cannot be linked to staged bytes"}
        recordLocation(context,item,account.browserScope,final)
        return final
    }
    private fun recordLocation(context:Context,item:NexusRegistry.Item,scope:String,receipt:NduRemoteReceipt){
        val md5=receipt.remoteMd5.orEmpty().lowercase(java.util.Locale.ROOT)
            .takeIf{it.matches(Regex("[0-9a-f]{32}"))}.orEmpty()
        NexusRegistry(context).bindRemote(item,scope,receipt.fsId,receipt.path,receipt.size,md5,false)
    }
}
