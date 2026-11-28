package com.asterion.nexus

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.widget.*
import com.openlocal.office.core.FgTeraboxDocumentsProvider
import java.util.concurrent.Executors

object NexusCloudActions {
    private val worker=Executors.newSingleThreadExecutor()
    private fun active(a:Activity)=!a.isFinishing&&!a.isDestroyed
    private fun error(a:Activity,message:String){
        if(active(a))AlertDialog.Builder(a).setTitle("Cloud operation incomplete").setMessage(message.take(200))
            .setPositiveButton("Close",null).show()
    }
    private fun <T> run(a:Activity,title:String,action:()->T,success:(T)->Unit){
        val progress=AlertDialog.Builder(a).setTitle(title).setMessage("Checking account and file identity")
            .setCancelable(false).show()
        worker.execute{
            val result=runCatching(action)
            a.runOnUiThread{
                if(!active(a))return@runOnUiThread
                progress.dismiss()
                result.onSuccess(success).onFailure{error(a,it.message.orEmpty().ifBlank{it.javaClass.simpleName})}
            }
        }
    }
    fun pin(a:Activity,uri:Uri,title:String,mime:String){
        val selected=runCatching{FgTeraboxDocumentsProvider.scopedFile(uri)}.getOrElse{error(a,"Scoped cloud file required");return}
        run(a,"Pin offline",{NexusCloudOperations.pin(a.applicationContext,selected.scope,selected.path,title,mime,selected.size)}){item->
            AlertDialog.Builder(a).setTitle("Pinned offline").setMessage(item.displayName+"\nVerified local copy retained.")
                .setPositiveButton("Open"){_,_->NexusFileOpen.open(a,NexusLibraryProvider.uriFor(a,item.sha256),item.mime,item.displayName)}
                .setNegativeButton("Close",null).show()
        }
    }
    fun verifyIdentity(a:Activity,uri:Uri,title:String,mime:String,complete:()->Unit){
        val selected=runCatching{FgTeraboxDocumentsProvider.scopedFile(uri)}.getOrElse{error(a,"Scoped cloud file required");return}
        AlertDialog.Builder(a).setTitle("Verify file identity")
            .setMessage("Read all "+android.text.format.Formatter.formatFileSize(a,selected.size)+" to establish the exact SHA-256 identity. No permanent local copy is created.")
            .setNegativeButton("Cancel",null).setPositiveButton("Verify"){_,_->
                val stop=java.util.concurrent.atomic.AtomicBoolean(false)
                val progress=AlertDialog.Builder(a).setTitle("Verifying cloud original")
                    .setMessage("Checking the selected cloud object").setCancelable(false)
                    .setNegativeButton("Stop",null).show()
                val future=worker.submit{
                    val result=runCatching{NexusCloudIdentity.verify(a.applicationContext,uri,title,mime,{stop.get()||a.isDestroyed||a.isFinishing}){read,total->
                        a.runOnUiThread{if(active(a)&&progress.isShowing)progress.setMessage(android.text.format.Formatter.formatFileSize(a,read)+" / "+android.text.format.Formatter.formatFileSize(a,total))}
                    }}
                    a.runOnUiThread{
                        if(!active(a)||stop.get())return@runOnUiThread
                        progress.dismiss()
                        result.onSuccess{complete()}.onFailure{error(a,it.message.orEmpty().take(180))}
                    }
                }
                progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener{stop.set(true);future.cancel(true);progress.dismiss()}
            }.show()
    }
    fun move(a:Activity,uri:Uri){
        val file=runCatching{FgTeraboxDocumentsProvider.scopedFile(uri)}.getOrElse{error(a,"Scoped cloud file required");return}
        run(a,"Inspect selected cloud file",{NexusCloudOperations.inspect(a.applicationContext,file.scope,file.path)}){remote->
            if(remote.bytes!=file.size){error(a,"The selected file changed; reload Library");return@run}
            val layout=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;val p=(20*resources.displayMetrics.density).toInt();setPadding(p,p,p,p)}
            val directory=EditText(a).apply{hint="Existing destination folder";setText(remote.path.substringBeforeLast('/').ifEmpty{"/"});setSingleLine()}
            val name=EditText(a).apply{hint="Filename";setText(remote.path.substringAfterLast('/'));setSingleLine()}
            layout.addView(TextView(a).apply{text="Same account only. The original extension and source identity must be preserved."})
            layout.addView(directory);layout.addView(name)
            val dialog=AlertDialog.Builder(a).setTitle("Move or rename").setView(layout).setNegativeButton("Cancel",null)
                .setPositiveButton("Review",null).create()
            dialog.setOnShowListener{
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                    val folder=directory.text.toString().trim();val label=name.text.toString()
                    val target=runCatching{NexusCloudOperations.target(folder,label)}.getOrElse{name.error="Invalid destination";return@setOnClickListener}
                    val oldExt=remote.path.substringAfterLast('/').substringAfterLast('.', "")
                    val newExt=label.substringAfterLast('.', "")
                    if(!oldExt.equals(newExt,true)){name.error="Keep the original file extension";return@setOnClickListener}
                    if(target==remote.path){name.error="Choose a different destination";return@setOnClickListener}
                    dialog.dismiss()
                    AlertDialog.Builder(a).setTitle("Confirm cloud move")
                        .setMessage(remote.path+"\n→ "+target+"\n\nNo overwrite. Nexus records a rollback receipt.")
                        .setNegativeButton("Cancel",null).setPositiveButton("Move"){_,_->
                            run(a,"Move verified file",{NexusCloudOperations.move(a.applicationContext,remote,folder,label)}){receipt->
                                showMove(a,receipt)
                            }
                        }.show()
                }
            }
            dialog.show()
        }
    }
    private fun showMove(a:Activity,receipt:NexusCloudOperations.Move){
        val d=AlertDialog.Builder(a).setTitle(when(receipt.state){
            "MOVED"->"Move verified"
            "ROLLED_BACK"->"Rollback verified"
            else->"Move outcome unresolved"
        }).setMessage(receipt.original.path+"\n→ "+receipt.target+"\n\n"+receipt.state+
            if(receipt.error.isEmpty())"" else "\n"+receipt.error).setPositiveButton("Close",null)
        if(receipt.state=="MOVED")d.setNeutralButton("Undo"){_,_->
            AlertDialog.Builder(a).setTitle("Restore original location?")
                .setMessage("The exact moved file will be returned without overwriting an existing file.")
                .setNegativeButton("Cancel",null).setPositiveButton("Undo"){_,_->
                    run(a,"Verify rollback",{NexusCloudOperations.undo(a.applicationContext,receipt.id)}){showMove(a,it)}
                }.show()
        }else if(receipt.state in setOf("UNKNOWN","REQUESTED","UNDO_UNKNOWN","UNDO_REQUESTED")){
            d.setNeutralButton("Recheck"){_,_->run(a,"Reconcile remote identity",{NexusCloudOperations.reconcile(a.applicationContext,receipt.id)}){showMove(a,it)}}
        }
        d.show()
    }
    fun history(a:Activity){
        run(a,"Read move history",{NexusCloudOperations.history(a.applicationContext)}){rows->
            if(rows.isEmpty()){AlertDialog.Builder(a).setTitle("Cloud move history").setMessage("No recorded moves.").setPositiveButton("Close",null).show();return@run}
            AlertDialog.Builder(a).setTitle("Cloud move history")
                .setItems(rows.map{it.original.path.substringAfterLast('/')+" · "+it.state}.toTypedArray()){_,which->showMove(a,rows[which])}
                .setPositiveButton("Close",null).show()
        }
    }
    fun pins(a:Activity,offset:Int=0){
        run(a,"Read offline pins",{NexusRegistry(a).pinned(80,offset)}){rows->
            val options=rows.map{it.path.substringAfterLast('/')+" · "+android.text.format.Formatter.formatFileSize(a,it.length)}.toMutableList()
            if(rows.size==80)options.add("Next page")
            if(offset>0)options.add("Previous page")
            if(options.isEmpty()){
                AlertDialog.Builder(a).setTitle("Offline pins").setMessage("No pinned files.").setPositiveButton("Close",null).show();return@run
            }
            AlertDialog.Builder(a).setTitle("Offline pins").setItems(options.toTypedArray()){_,index->
                if(index>=rows.size){
                    pins(a,if(options[index]=="Next page")offset+80 else maxOf(0,offset-80));return@setItems
                }
                val remote=rows[index]
                run(a,"Verify pinned original",{
                    val registry=NexusRegistry(a);val item=registry.find(remote.sha256)?:error("Original missing")
                    check(registry.verifyOriginal(item)){"Pinned bytes missing or changed"};item
                }){item->
                    AlertDialog.Builder(a).setTitle(item.displayName).setMessage(remote.path)
                        .setPositiveButton("Open"){_,_->NexusFileOpen.open(a,NexusLibraryProvider.uriFor(a,item.sha256),item.mime,item.displayName)}
                        .setNeutralButton("Unpin"){_,_->
                            run(a,"Remove pin",{NexusRegistry(a).unpin(remote.account,remote.fsId)}){pins(a,offset)}
                        }.setNegativeButton("Close",null).show()
                }
            }.setPositiveButton("Close",null).show()
        }
    }
}
