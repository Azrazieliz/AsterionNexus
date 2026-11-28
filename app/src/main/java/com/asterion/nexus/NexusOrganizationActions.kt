package com.asterion.nexus

import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.openlocal.office.core.FgTeraboxClient
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object NexusOrganizationActions{
    private val worker=Executors.newSingleThreadExecutor()
    private fun active(a:Activity)=!a.isDestroyed&&!a.isFinishing
    private fun <T> run(a:Activity,title:String,action:()->T,done:(T)->Unit){
        val dialog=AlertDialog.Builder(a).setTitle(title).setMessage("Checking the selected account").setCancelable(false).show()
        worker.execute{
            val result=runCatching(action)
            a.runOnUiThread{
                if(!active(a))return@runOnUiThread
                dialog.dismiss()
                result.onSuccess(done).onFailure{
                    AlertDialog.Builder(a).setTitle("Organization unavailable").setMessage(it.message.orEmpty().take(180)).setPositiveButton("Close",null).show()
                }
            }
        }
    }
    fun show(a:Activity){
        val accounts=FgTeraboxClient.accounts(a)
        if(accounts.isEmpty()){
            AlertDialog.Builder(a).setMessage("Connect a TeraBox account in Library first.").setPositiveButton("Close",null).show();return
        }
        AlertDialog.Builder(a).setTitle("Automatic cloud organization")
            .setItems(accounts.map{it.label}.toTypedArray()){_,i->account(a,accounts[i].scope,accounts[i].label)}
            .setNegativeButton("Close",null).show()
    }
    private fun account(a:Activity,scope:String,label:String){
        run(a,"Load organization settings",{NexusAutomaticOrganization.sources(a,scope)}){sources->
            val options=sources.map{it.name+" · "+if(it.enabled)"Enabled" else "Paused"}.toMutableList()
            options.add("Run enabled sources now");options.add("Move history and rollback")
            AlertDialog.Builder(a).setTitle(label)
                .setItems(options.toTypedArray()){_,i->
                    when(i){
                        sources.size->cycle(a,scope,label)
                        sources.size+1->NexusCloudActions.history(a)
                        else->edit(a,scope,label,sources[i])
                    }
                }.setMessage(NexusAutomaticOrganization.lastStatus(a,scope))
                .setNegativeButton("Close",null).show()
        }
    }
    private fun edit(a:Activity,scope:String,label:String,source:NexusAutomaticOrganization.Source){
        val layout=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;val p=(18*resources.displayMetrics.density).toInt();setPadding(p,p,p,p)}
        layout.addView(TextView(a).apply{text="Select a dedicated folder of independent, complete media. Existing destination rules are retained. Packages, sidecars, loose 3D assets and artwork requiring identity review are not automatically moved."})
        val root=EditText(a).apply{hint="Existing cloud ingress folder";setText(source.root);setSingleLine()}
        layout.addView(root)
        val d=AlertDialog.Builder(a).setTitle(source.name).setView(layout)
            .setNegativeButton("Cancel",null)
            .setNeutralButton("Pause"){_,_->run(a,"Pause automatic source",{NexusAutomaticOrganization.configure(a,scope,source.id,source.root,false)}){account(a,scope,label)}}
            .setPositiveButton("Enable",null).create()
        d.setOnShowListener{
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                val path=root.text.toString().trim()
                if(path.isBlank()){root.error="Choose the actual standalone-file folder";return@setOnClickListener}
                d.dismiss()
                AlertDialog.Builder(a).setTitle("Enable this source?")
                    .setMessage(path+"\nOnly this account and direct standalone files. No overwrites or deletions. Verified moves can be undone in history.")
                    .setNegativeButton("Cancel",null)
                    .setPositiveButton("Enable"){_,_->run(a,"Enable automatic source",{NexusAutomaticOrganization.configure(a,scope,source.id,path,true)}){account(a,scope,label)}}.show()
            }
        }
        d.show()
    }
    private fun cycle(a:Activity,scope:String,label:String){
        val stop=AtomicBoolean(false)
        val progress=AlertDialog.Builder(a).setTitle("Organizing "+label).setMessage("Checking approved ingress files and remote identities")
            .setCancelable(false).setNegativeButton("Stop"){_,_->stop.set(true)}.show()
        worker.execute{
            val result=runCatching{NexusAutomaticOrganization.run(a.applicationContext,scope){stop.get()}}
            a.runOnUiThread{
                if(!active(a))return@runOnUiThread
                progress.dismiss()
                AlertDialog.Builder(a).setTitle("Organization cycle")
                    .setMessage(result.getOrNull()?.message?:("Cycle incomplete: "+result.exceptionOrNull()?.javaClass?.simpleName))
                    .setPositiveButton("Close",null).setNeutralButton("Move history"){_,_->NexusCloudActions.history(a)}.show()
            }
        }
    }
}
