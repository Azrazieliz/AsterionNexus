package com.asterion.nexus

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.azrael.galleryflow.LegacyCatalogMigration
import com.ailm.android.runtime.StandaloneRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Explicit export selection; never read another app's private sandbox. */
@Composable
internal fun NexusMigrationDialog(onClose:()->Unit){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    val migration=remember{LegacyCatalogMigration(ctx.applicationContext)}
    val donorSqlite=remember{NexusDonorSqliteMigration(ctx.applicationContext)}
    var donorReceipts by remember{mutableStateOf<List<NexusDonorSqliteMigration.Receipt>>(emptyList())}
    var donorRollback by remember{mutableStateOf<NexusDonorSqliteMigration.Receipt?>(null)}
    var kind by remember{mutableStateOf("Downloader")}
    var rounder by remember{mutableStateOf(false)}
    if(rounder){NexusRounderMigrationDialog{rounder=false};return}
    var staged by remember{mutableStateOf<LegacyCatalogMigration.Staged?>(null)}
    var coreRequest by remember{mutableStateOf<Map<String,Any>?>(null)}
    var status by remember{mutableStateOf("Select an authorized donor export. Original apps and files remain unchanged.")}
    var busy by remember{mutableStateOf(false)}
    var ready by remember{mutableStateOf(false)}
    var rollback by remember{mutableStateOf<String?>(null)}
    var receipts by remember{mutableStateOf<List<Pair<String,String>>>(emptyList())}
    suspend fun refreshReceipts(){val all=withContext(Dispatchers.IO){migration.receipts() to donorSqlite.receipts()};receipts=all.first;donorReceipts=all.second}
    LaunchedEffect(Unit){runCatching{refreshReceipts()}}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri:Uri?->
        if(uri!=null){
            val domain=kind
            busy=true;ready=false;staged=null;coreRequest=null
            status="Verifying selected export and calculating dry run…"
            scope.launch{
                try{
                    val result=withContext(Dispatchers.IO){
                        val source=migration.stage(uri)
                        if(domain=="Downloader"){
                            val report=migration.preview(source)
                            Triple(source,null,report.description())
                        }else if(domain=="Downloader SQLite"){
                            Triple(source,null,donorSqlite.process(source,false).detail())
                        }else{
                            StandaloneRuntime.initialize(ctx.applicationContext)
                            val request=mapOf<String,Any>(
                                "import_id" to UUID.randomUUID().toString(),
                                "source" to source.file.absolutePath,
                                "source_type" to if(domain=="Core SQLite")"sqlite" else "json",
                                "conflict_strategy" to "skip","replace_existing" to false,
                                "verified_image_bindings_only" to true
                            )
                            val preview=StandaloneRuntime.previewImport(request)
                            val validation=StandaloneRuntime.validateImport(request)
                            val details=preview["preview"] as? Map<*,*>
                            val summary=details?.get("summary_statistics") as? Map<*,*>
                            val message=buildString{
                                append("Fusion dry run\n")
                                summary?.forEach{(key,value)->append(key).append(": ").append(value).append("\n")}
                                (details?.get("warnings") as? List<*>)?.filter { it.toString().startsWith("Verified artwork migration:") }?.forEach { append(it).append("\n") }
                                append(if(validation["ok"]==true)"Validation passed." else "Validation failed. No changes applied.")
                                append("\nExisting keys are preserved. Private donor files and permissions are not assumed portable.")
                            }
                            Triple(source,request.takeIf{validation["ok"]==true},message)
                        }
                    }
                    staged=result.first;coreRequest=result.second;status=result.third
                    ready=domain.startsWith("Downloader")||result.second!=null
                }catch(e:Exception){status="Export preflight failed: "+e.javaClass.simpleName}
                finally{busy=false}
            }
        }
    }
    AlertDialog(
        onDismissRequest={if(!busy)onClose()},
        title={Text("Donor data migration")},
        text={
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                listOf("Downloader","Downloader SQLite","Core JSON","Core SQLite").forEach{label->
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        RadioButton(selected=kind==label,enabled=!busy,onClick={
                            kind=label;staged=null;ready=false;coreRequest=null
                            status="Select an authorized "+label+" export."
                        })
                        Text(label,modifier=Modifier.padding(top=12.dp))
                    }
                }
                OutlinedButton(enabled=!busy,onClick={picker.launch(arrayOf("*/*"))}){Text("Select export")}
                if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(status)
                TextButton(enabled=!busy,onClick={rounder=true}){Text("Rounder reader migration")}
                Text("Reconnect each TeraBox account and authorized SAF root. No foreign credentials or permissions are copied.")
                donorReceipts.forEach{receipt->
                    Text(receipt.kind+" · "+receipt.digest.take(12))
                    TextButton(enabled=!busy,onClick={donorRollback=receipt}){Text("Review rollback")}
                }
                if(receipts.isNotEmpty()){
                    Text("Downloader migration receipts",style=MaterialTheme.typography.titleSmall)
                    receipts.take(10).forEach{(digest,receipt)->
                        Text(digest.take(12)+" · "+receipt.take(180))
                        TextButton(enabled=!busy,onClick={rollback=digest}){Text("Review rollback")}
                    }
                }
            }
        },
        confirmButton={
            TextButton(enabled=ready&&!busy,onClick={
                val source=staged?:return@TextButton
                val request=coreRequest
                busy=true;ready=false;status="Applying validated migration…"
                scope.launch{
                    try{
                        status=withContext(Dispatchers.IO){
                            if(kind=="Downloader SQLite")donorSqlite.process(source,true).detail()
                            else if(request==null)migration.apply(source).description()
                            else{
                                val result=StandaloneRuntime.importDatabase(request)
                                if(result["ok"]==true)"Fusion import committed. Receipt: "+result["import_id"]
                                else "Fusion import not completed: "+result["status"]
                            }
                        }
                        refreshReceipts()
                    }catch(e:Exception){status="Migration not completed: "+e.javaClass.simpleName}
                    finally{busy=false}
                }
            }){Text("Apply migration")}
        },
        dismissButton={TextButton(enabled=!busy,onClick=onClose){Text("Close")}}
    )
    donorRollback?.let{receipt->
        AlertDialog(onDismissRequest={donorRollback=null},title={Text("Rollback "+receipt.kind+" import?")},text={Text("Only unchanged imported records are removed. Original files and existing catalog entries are retained.")},confirmButton={TextButton(onClick={
            donorRollback=null;busy=true;ready=false;scope.launch{try{val count=withContext(Dispatchers.IO){donorSqlite.rollback(receipt.kind,receipt.digest)};status="Rolled back "+count+" unchanged records";refreshReceipts()}catch(e:Exception){status="Rollback refused: "+e.message.orEmpty().take(140)}finally{busy=false}}
        }){Text("Rollback")}},dismissButton={TextButton(onClick={donorRollback=null}){Text("Cancel")}})
    }
    rollback?.let{digest->
        AlertDialog(onDismissRequest={rollback=null},title={Text("Rollback this catalog import?")},
            text={Text("Only unchanged rows inserted by this import may be removed. Existing catalog entries and media files are not deleted. Any later change or reference prevents rollback.")},
            confirmButton={TextButton(onClick={
                rollback=null;busy=true;ready=false
                scope.launch{
                    try{
                        val removed=withContext(Dispatchers.IO){migration.rollback(digest)}
                        status="Rolled back "+removed+" unchanged imported rows."
                        refreshReceipts()
                    }catch(e:Exception){status="Rollback refused: later changes or dependencies may exist."}
                    finally{busy=false}
                }
            }){Text("Rollback")}},dismissButton={TextButton(onClick={rollback=null}){Text("Cancel")}})
    }
}
