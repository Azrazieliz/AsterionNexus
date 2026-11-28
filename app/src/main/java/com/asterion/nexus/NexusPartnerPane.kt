package com.asterion.nexus

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

@Composable
internal fun NexusPartnerPane(){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var partners by remember{mutableStateOf<List<NexusPartnerBridge.Partner>>(emptyList())}
    var history by remember{mutableStateOf<List<NexusPartnerBridge.Operation>>(emptyList())}
    var capabilities by remember{mutableStateOf<Map<String,List<NexusPartnerBridge.Action>>>(emptyMap())}
    var message by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)}
    var approval by remember{mutableStateOf<NexusPartnerBridge.Partner?>(null)}
    fun refresh(){scope.launch{
        busy=true
        runCatching{withContext(Dispatchers.IO){
            NexusPartnerBridge.expire(ctx)
            NexusPartnerBridge.discover(ctx) to NexusPartnerBridge.history(ctx)
        }}.onSuccess{partners=it.first;history=it.second}.onFailure{message=it.message?:"Discovery failed"}
        busy=false
    }}
    fun execute(block:()->Unit){scope.launch{
        busy=true
        runCatching{withContext(Dispatchers.IO){block()}}.onFailure{message=it.message?:"Operation failed"}
        busy=false;refresh()
    }}
    LaunchedEffect(Unit){refresh()}
    Column(Modifier.fillMaxSize().padding(12.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Text("Asterion Bridges",style=MaterialTheme.typography.titleLarge)
            TextButton(onClick={refresh()},enabled=!busy){Text("Refresh")}
        }
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(message.isNotEmpty())Text(message,color=MaterialTheme.colorScheme.error)
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){
            if(partners.isEmpty())item{Text("No compatible installed specialist providers discovered.")}
            items(partners,key={it.pkg}){p->
                Card(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(12.dp)){
                        Text(p.label,style=MaterialTheme.typography.titleMedium)
                        Text(p.pkg,style=MaterialTheme.typography.bodySmall)
                        Text(if(p.approved)"Locally approved certificate" else "Approval required")
                        Row{
                            TextButton(onClick={approval=p},enabled=!busy){Text(if(p.approved)"Revoke" else "Review approval")}
                            if(p.approved)TextButton(onClick={scope.launch{
                                busy=true
                                runCatching{withContext(Dispatchers.IO){NexusPartnerBridge.capabilities(ctx,p)}}
                                    .onSuccess{capabilities=capabilities+(p.pkg to it)}
                                    .onFailure{message=it.message?:"Capability query failed"}
                                busy=false
                            }},enabled=!busy){Text("Capabilities")}
                        }
                        capabilities[p.pkg]?.takeIf{p.approved}?.let{actions->
                            if(actions.isEmpty())Text("No supported capabilities advertised.")
                            actions.forEach{Text(it.name+" · "+if(it.assetContract)"Scoped asset action" else it.intent)}
                        }
                    }
                }
            }
            item{Text("Operation history",style=MaterialTheme.typography.titleMedium)}
            items(history,key={it.id}){op->
                Card(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(12.dp)){
                        Text(op.action+" · "+op.status)
                        Text(op.pkg,style=MaterialTheme.typography.bodySmall)
                        Text(op.message)
                        if(op.status in setOf("SUBMITTED","RUNNING","UNKNOWN","ACCEPTED")){
                            Row{
                                TextButton(onClick={execute{NexusPartnerBridge.poll(ctx,op.id)}},enabled=!busy){Text("Reconcile")}
                                TextButton(onClick={execute{NexusPartnerBridge.poll(ctx,op.id,true)}},enabled=!busy){Text("Request cancellation")}
                            }
                        }
                    }
                }
            }
        }
    }
    approval?.let{p->
        AlertDialog(onDismissRequest={approval=null},title={Text(if(p.approved)"Revoke specialist access" else "Approve specialist certificate")},
            text={Text(p.label+"\n"+p.pkg+"\nSHA-256: "+p.signer+"\n\n"+if(p.approved)"New access will be denied. Already-open file descriptors cannot be recalled." else "This enables capability discovery. Each asset transfer still requires your separate confirmation.")},
            confirmButton={TextButton(onClick={approval=null;execute{NexusPartnerBridge.approve(ctx,p,!p.approved)}}){Text(if(p.approved)"Revoke" else "Approve")}},
            dismissButton={TextButton(onClick={approval=null}){Text("Cancel")}})
    }
}

object NexusPartnerActions{
    private val worker=Executors.newSingleThreadExecutor()
    fun show(activity:Activity,uri:Uri){
        val sha=uri.lastPathSegment?:return
        worker.execute{
            val result=runCatching{
                val item=NexusRegistry(activity).find(sha)?:error("Verified local item required")
                val choices=NexusPartnerBridge.discover(activity).filter{it.approved}.flatMap{p->
                    runCatching{NexusPartnerBridge.capabilities(activity,p)}.getOrDefault(emptyList())
                        .filter{NexusPartnerBridge.supports(it,item)}.map{p to it}
                }
                item to choices
            }
            activity.runOnUiThread{
                if(activity.isFinishing||activity.isDestroyed)return@runOnUiThread
                result.onFailure{AlertDialog.Builder(activity).setMessage(it.message?:"Discovery failed").setPositiveButton("OK",null).show()}
                result.onSuccess{(item,choices)->
                    if(choices.isEmpty()){
                        AlertDialog.Builder(activity).setMessage("No approved specialist advertises a compatible asset action. Review Organising / Asterion Bridges.").setPositiveButton("OK",null).show()
                    }else AlertDialog.Builder(activity).setTitle("Choose specialist action")
                        .setItems(choices.map{it.first.label+" · "+it.second.name}.toTypedArray()){_,index->
                            val (p,a)=choices[index]
                            AlertDialog.Builder(activity).setTitle("Confirm asset access")
                                .setMessage(item.displayName+"\n"+p.label+" · "+a.name+"\nRead-only access for up to 10 minutes. A separate verified output is retained only when supplied.")
                                .setNegativeButton("Cancel",null).setPositiveButton("Send"){_,_->
                                    worker.execute{
                                        val outcome=runCatching{NexusPartnerBridge.send(activity.applicationContext,p,a,item)}
                                        activity.runOnUiThread{
                                            if(!activity.isFinishing&&!activity.isDestroyed)AlertDialog.Builder(activity)
                                                .setMessage(outcome.fold({it.status+": "+it.message},{it.message?:"Transfer failed"}))
                                                .setPositiveButton("OK",null).show()
                                        }
                                    }
                                }.show()
                        }.setNegativeButton("Cancel",null).show()
                }
            }
        }
    }
}
