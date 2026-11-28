package com.asterion.nexus

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.text.format.Formatter
import com.openlocal.office.core.FgTeraboxClient
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object NexusRetentionActions{
    private val worker=Executors.newSingleThreadExecutor()
    private fun alive(a:Activity)=!a.isDestroyed&&!a.isFinishing
    private fun notice(a:Activity,title:String,message:String){
        if(alive(a))AlertDialog.Builder(a).setTitle(title).setMessage(message).setPositiveButton("Close",null).show()
    }
    fun show(a:Activity,uri:Uri){
        if(!NexusArchiveOperation.available(a,uri))return
        val sha=uri.lastPathSegment?:return
        worker.execute{
            val ctx=a.applicationContext
            val result=runCatching{
                val registry=NexusRegistry(ctx)
                val item=registry.find(sha)?:error("Original unavailable")
                Triple(item,if(registry.localPresent(item))null else registry.evictedLocation(item)?:registry.verifiedCloudLocations(sha).firstOrNull(),NexusCloudRetention.candidates(ctx,sha))
            }
            a.runOnUiThread{
                if(!alive(a))return@runOnUiThread
                result.onFailure{notice(a,"Storage status unavailable",it.javaClass.simpleName)}
                result.onSuccess{(item,evicted,candidates)->
                    if(evicted!=null){
                        AlertDialog.Builder(a).setTitle("Cloud-only original")
                            .setMessage(item.displayName+"\n"+evicted.path+"\nThe verified remote original is retained. Restore an offline copy if needed.")
                            .setPositiveButton("Restore offline"){_,_->
                                worker.execute{
                                    val outcome=runCatching{NexusCloudRetention.restore(ctx,item)}
                                    a.runOnUiThread{notice(a,"Offline restoration",if(outcome.isSuccess)"Verified original restored and pinned." else "Restoration incomplete; remote copy unchanged.")}
                                }
                            }.setNegativeButton("Close",null).show()
                    }else if(candidates.isEmpty())notice(a,"Local copy retained",
                        "No eligible, independently verifiable remote copy. Pinned files, book processing sources and independent local originals are retained.")
                    else{
                        val labels=FgTeraboxClient.accounts(ctx).associate{it.scope to it.label}
                        AlertDialog.Builder(a).setTitle("Choose surviving cloud copy")
                            .setItems(candidates.map{(labels[it.account]?:"TeraBox")+" · "+it.path}.toTypedArray()){_,index->
                                val chosen=candidates[index]
                                AlertDialog.Builder(a).setTitle("Verify cloud copy")
                                    .setMessage("Read back all "+Formatter.formatFileSize(a,item.length)+" from this exact account and compare SHA-256. No local bytes are removed during verification.")
                                    .setPositiveButton("Verify"){_,_->verify(a,item,chosen)}
                                    .setNegativeButton("Cancel",null).show()
                            }.setNegativeButton("Close",null).show()
                    }
                }
            }
        }
    }
    private fun verify(a:Activity,item:NexusRegistry.Item,remote:NexusRegistry.RemoteLocation){
        val stop=AtomicBoolean(false)
        val progress=AlertDialog.Builder(a).setTitle("Verify remote original").setMessage("Reading complete remote file and checking exact bytes.")
            .setNegativeButton("Cancel"){_,_->stop.set(true)}.setCancelable(false).show()
        val future=worker.submit{
            val ctx=a.applicationContext
            val outcome=runCatching{NexusCloudRetention.verify(ctx,item.sha256,remote.account,remote.fsId){stop.get()}}
            a.runOnUiThread{
                if(!alive(a)||stop.get())return@runOnUiThread
                progress.dismiss()
                outcome.onFailure{notice(a,"Cleanup unavailable","Remote verification failed; local original retained.")}
                outcome.onSuccess{proof->
                    AlertDialog.Builder(a).setTitle("Release local staging copy?")
                        .setMessage("SHA-256 and length match the remote original. Free "+Formatter.formatFileSize(a,item.length)+" from Nexus private storage. The cloud original, catalog, ratings and related metadata remain. This is not a cloud deletion.")
                        .setNegativeButton("Keep local copy",null)
                        .setPositiveButton("Release local copy"){_,_->
                            worker.execute{
                                val result=runCatching{NexusCloudRetention.evict(ctx,proof)}
                                a.runOnUiThread{notice(a,"Local storage",if(result.isSuccess)"Local staging released; verified cloud-only original retained." else "Cleanup did not complete; reopen the storage status to reconcile.")}
                            }
                        }.show()
                }
            }
        }
        progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener{stop.set(true);future.cancel(true);progress.dismiss()}
    }
}
