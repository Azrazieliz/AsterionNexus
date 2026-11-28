package com.azrael.pixivdumpsync

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/** Shared direct upload worker for foreground app use and scheduled retries. */
object NduUploadEngine {
    private val inProgress=AtomicBoolean(false)
    data class Result(val uploaded:Int,val attempted:Int,val pending:Int,
                      val connectionError:String?=null)

    fun drain(context:Context, maximum:Int, durationMs:Long,
              stop:()->Boolean={false}, report:(String)->Unit={},
              manual:Boolean=false,nexusOnly:Boolean=false):Result {
        if(!manual && !AppPower.enabled(context)) return Result(0,0,0,"Downloader paused")
        if(!NduSettings.configured(context)) return Result(0,0,0,"NDUS not configured")
        if(!inProgress.compareAndSet(false,true))
            return Result(0,0,0,"Another direct upload is active")
        try {
            if(nexusOnly)return drainScopedNexus(context,maximum,durationMs,stop,report)
            val begin=SystemClock.elapsedRealtime()
            val session=NduSettings.session(context)
                ?: return Result(0,0,0,"NDUS unavailable")
            val cookie=session.token
            val accountScope=session.scope
            fun cancelled()=stop()||!NduSettings.enabled(context)||NduSettings.currentAccountScope(context)!=accountScope
            NduCloudQueue(context).use { queue ->
                var uploaded=0
                var reverified=0
                var attempted=0
                val client=NduTeraboxClient(cookie,NduSettings.domain(context))
                try {
                    report("Connecting to TeraBox")
                    client.connect()
                } catch(e:Exception) {
                    return Result(0,0,queue.status().pending,
                        e.message ?: "NDUS session invalid")
                }
                while (attempted<maximum && !cancelled() &&
                    SystemClock.elapsedRealtime()-begin<durationMs) {
                    val jobs=queue.nextBatch(minOf(30,maximum-attempted),nexusOnly)
                    if(jobs.isEmpty()) break
                    for(job in jobs) {
                        if(cancelled() || job.accountScope!=accountScope || SystemClock.elapsedRealtime()-begin>=durationMs) break
                        attempted++
                        try {
                            report("Uploading "+job.category+" / "+job.name)
                            val uri=Uri.parse(job.uri)
                            val path=NduSettings.destination(context,job.category)
                            val nexus=uri.authority==context.packageName+".library"
                            val proof=if(nexus){
                                com.asterion.nexus.NexusCloudPublication.publish(context,client,uri,job.name,path,::cancelled){done,total->
                                    report("TeraBox "+(if(total>0)done*100/total else 0)+"% / "+job.name)
                                }
                            }else if(job.state=="VERIFY_PENDING"){
                                report("Checking existing TeraBox copy / "+job.name)
                                client.verifyExisting(context,uri,job.name,path,::cancelled)
                            }else{
                                client.upload(context,uri,job.name,path,cancelled=::cancelled){
                                    done,total ->
                                    val percent=if(total>0)done*100/total else 0
                                    report("TeraBox "+percent+"% / "+job.name)
                                }
                            }
                            if(proof==null){
                                queue.missingRemote(job)
                                report("Remote file missing; queued a fresh transfer")
                                continue
                            }
                            // Persist the exact remote identity BEFORE Rounder reconciliation.
                            queue.completed(job,proof)
                            if(!nexus)RounderOutbox(context).use{it.ack(uri,proof)}
                            if(job.state=="VERIFY_PENDING")reverified++ else uploaded++
                        } catch(t:Throwable) {
                            if(cancelled() || t is InterruptedException) break
                            queue.failed(job,t.message ?: t.javaClass.simpleName)
                        }
                    }
                }
                val pending=queue.status().pending
                report(uploaded.toString()+" uploaded · "+reverified+
                    " historical copies reverified · "+pending+" awaiting proof")
                return Result(uploaded,attempted,pending)
            }
        } finally { inProgress.set(false) }
    }
    /** One scheduler/outbox, with independent authorized account leases.
     * Browsing or selecting a new destination cannot strand previous jobs.
     * Revoked or ambiguous accounts keep their existing receipts and pending rows. */
    private fun drainScopedNexus(context:Context,maximum:Int,durationMs:Long,
                                 stop:()->Boolean,report:(String)->Unit):Result{
        val begin=SystemClock.elapsedRealtime()
        val accounts=NduSettings.uploadAccounts(context)
        var uploaded=0
        var attempted=0
        var error:String?=null
        fun cancelled()=stop()||!NduSettings.enabled(context)||!NduSettings.cloudOnly(context)||
            SystemClock.elapsedRealtime()-begin>=durationMs
        NduCloudQueue(context).use{queue->
            val clients=mutableMapOf<String,NduTeraboxClient>()
            val failedAccounts=mutableSetOf<String>()
            while(attempted<maximum&&!cancelled()){
                var advanced=false
                for(account in accounts){
                    if(attempted>=maximum||cancelled())break
                    if(account.scope in failedAccounts||!NduSettings.stillAuthorized(context,account))continue
                    val job=queue.nextBatchScoped(account.scope,1,true).firstOrNull()?:continue
                    advanced=true;attempted++
                    fun stopped()=cancelled()||!NduSettings.stillAuthorized(context,account)
                    try{
                        val client=clients[account.scope]?:NduTeraboxClient(account.token,account.host).also{
                            com.openlocal.office.core.FgTeraboxClient.forScope(context,account.browserScope)
                                .requireScope(account.browserScope)
                            it.connect();clients[account.scope]=it
                        }
                        if(stopped())break
                        val path=NduSettings.destination(context,job.category)
                        val proof=com.asterion.nexus.NexusCloudPublication.publishScoped(
                            context,client,Uri.parse(job.uri),job.name,path,account,::stopped){done,total->
                            report("TeraBox "+(if(total>0)done*100/total else 0)+"% / "+job.name)
                        }
                        if(stopped())break
                        queue.completed(job,proof);uploaded++
                    }catch(t:Exception){
                        if(stopped()||t is InterruptedException)continue
                        error=t.javaClass.simpleName
                        queue.failed(job,error?:"Publication failed")
                        if(!clients.containsKey(account.scope))failedAccounts.add(account.scope)
                    }
                }
                if(!advanced)break
            }
            val pending=accounts.sumOf{queue.pendingNexus(it.scope)}
            return Result(uploaded,attempted,pending,error)
        }
    }

}
