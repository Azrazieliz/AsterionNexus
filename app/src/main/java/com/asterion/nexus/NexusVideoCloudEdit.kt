package com.asterion.nexus

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import com.openlocal.office.core.FgTeraboxDocumentsProvider
import java.util.concurrent.atomic.AtomicBoolean

object NexusVideoCloudEdit{
    fun open(a:Activity,uri:Uri,title:String,mime:String){
        val selected=runCatching{FgTeraboxDocumentsProvider.scopedFile(uri)}.getOrElse{
            AlertDialog.Builder(a).setMessage("Scoped cloud video required").setPositiveButton("Close",null).show();return
        }
        if(selected.size !in 1..NexusVideoInputs.LIMIT){
            AlertDialog.Builder(a).setMessage("Cloud video exceeds the 8 GiB temporary editing limit").setPositiveButton("Close",null).show();return
        }
        AlertDialog.Builder(a).setTitle("Edit cloud original").setMessage("Verify the exact source and stage it temporarily for editing. No permanent offline pin is created; the original is unchanged.")
            .setNegativeButton("Cancel",null).setPositiveButton("Continue"){_,_->
                val stop=AtomicBoolean(false)
                val dialog=AlertDialog.Builder(a).setTitle("Verifying video").setMessage("Checking cloud identity…").setCancelable(false).setNegativeButton("Stop",null).show()
                val worker=Thread{
                    val result=runCatching{
                        NexusCloudIdentity.known(a.applicationContext,uri)?:NexusCloudIdentity.verify(a.applicationContext,uri,title,mime,{stop.get()||a.isDestroyed||a.isFinishing}){read,total->
                            a.runOnUiThread{if(dialog.isShowing)dialog.setMessage("${read/1048576} / ${total/1048576} MiB")}
                        }.sha256
                    }
                    a.runOnUiThread{
                        if(stop.get()||a.isDestroyed||a.isFinishing)return@runOnUiThread
                        dialog.dismiss()
                        result.onSuccess{NexusVideoEditActivity.open(a,listOf(it),title)}.onFailure{AlertDialog.Builder(a).setMessage(it.message?:"Cloud verification failed").setPositiveButton("Close",null).show()}
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener{stop.set(true);worker.interrupt();dialog.dismiss()}
                worker.start()
            }.show()
    }
}
