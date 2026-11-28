package com.asterion.nexus

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

@Composable
internal fun NexusRounderMigrationDialog(onClose:()->Unit){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    val migration=remember{NexusRounderMigration(ctx)}
    var staged by remember{mutableStateOf<NexusRounderMigration.Staged?>(null)}
    var rows by remember{mutableStateOf<List<NexusRounderMigration.Bookmark>>(emptyList())}
    var selected by remember{mutableStateOf<NexusRounderMigration.Bookmark?>(null)}
    var query by remember{mutableStateOf("")}
    var candidates by remember{mutableStateOf<List<NexusRegistry.Item>>(emptyList())}
    var bind by remember{mutableStateOf<NexusRegistry.Item?>(null)}
    var busy by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf("Select a Rounder reader export. Filename-based records are retained but must be linked to the correct original.")}

    suspend fun refresh(){rows=withContext(Dispatchers.IO){migration.list()}}
    LaunchedEffect(Unit){runCatching{refresh()}.onFailure{status="Existing migration records unavailable"}}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri:Uri?->
        if(uri!=null){
            busy=true;staged=null
            scope.launch{
                try{
                    staged=withContext(Dispatchers.IO){migration.stage(uri)}
                    val p=staged!!
                    status="Verified export: "+p.records+" records, "+p.bookmarks+" bookmarks, "+p.recent+" recent items. Nothing applied."
                }catch(e:Exception){status="Export rejected: "+(e.message?:e.javaClass.simpleName).take(160)}
                finally{busy=false}
            }
        }
    }
    fun search(){
        if(busy)return
        busy=true
        scope.launch{
            try{candidates=withContext(Dispatchers.IO){NexusRegistry(ctx).search(query,100).filter{it.mime=="application/epub+zip"}.distinctBy{it.sha256}}}
            catch(e:Exception){status="Book search unavailable"}
            finally{busy=false}
        }
    }
    AlertDialog(onDismissRequest={if(!busy)onClose()},title={Text("Rounder reader migration")},text={
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(status)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(selected==null){
                Row{
                    TextButton(enabled=!busy,onClick={picker.launch(arrayOf("application/json","text/plain","application/octet-stream"))}){Text("Select export")}
                    TextButton(enabled=!busy&&staged!=null,onClick={
                        val p=staged?:return@TextButton
                        busy=true;scope.launch{
                            try{withContext(Dispatchers.IO){migration.apply(p)};refresh();status="Export retained. Bind bookmarks to verified books.";staged=null}
                            catch(e:Exception){status="Import incomplete: "+(e.message?:e.javaClass.simpleName).take(120)}
                            finally{busy=false}
                        }
                    }){Text("Apply")}
                }
                LazyColumn(Modifier.heightIn(max=280.dp)){
                    items(rows,key={it.exportSha+":"+it.key}){row->
                        TextButton(enabled=!busy&&row.boundSha==null,onClick={
                            selected=row;query=row.name.substringBeforeLast('.');candidates=emptyList()
                        }){Text(row.name+" · chapter "+(row.chapter+1)+if(row.boundSha==null)" · unbound" else " · bound")}
                    }
                }
                Text("Recent history and appearance settings remain in the private export; account permissions are not transferred.")
            }else{
                Text(selected!!.name+" · chapter "+(selected!!.chapter+1))
                OutlinedTextField(query,{query=it},label={Text("Find the original EPUB")},singleLine=true,enabled=!busy)
                Row{
                    TextButton(enabled=!busy,onClick={selected=null;candidates=emptyList()}){Text("Back")}
                    TextButton(enabled=!busy&&query.isNotBlank(),onClick={search()}){Text("Search")}
                }
                LazyColumn(Modifier.heightIn(max=260.dp)){
                    items(candidates,key={it.sha256}){item->
                        TextButton(enabled=!busy,onClick={bind=item}){Text(item.displayName+" · "+item.sha256.take(12))}
                    }
                }
            }
        }
    },confirmButton={TextButton(enabled=!busy,onClick=onClose){Text("Close")}})
    val item=bind
    if(item!=null&&selected!=null){
        val row=selected!!
        AlertDialog(onDismissRequest={bind=null},title={Text("Bind legacy bookmark?")},
            text={Text(row.name+" → "+item.displayName+"\nSHA-256 "+item.sha256+"\nExisting Nexus bookmarks cannot be overwritten by this import.")},
            confirmButton={TextButton(onClick={
                bind=null;busy=true
                scope.launch{
                    try{withContext(Dispatchers.IO){migration.bind(row,item.sha256)};refresh();selected=null;status="Bookmark linked to the verified original."}
                    catch(e:Exception){status="Binding refused: "+(e.message?:e.javaClass.simpleName).take(150)}
                    finally{busy=false}
                }
            }){Text("Bind")}},dismissButton={TextButton(onClick={bind=null}){Text("Cancel")}})
    }
}
