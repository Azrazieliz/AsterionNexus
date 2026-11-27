package com.asterion.nexus

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ailm.android.runtime.StandaloneRuntime
import com.ailm.android.runtime.LocalDatabase
import com.ailm.android.runtime.FusionDatabaseRepository
import com.azrael.pixivdumpsync.AsterionSearchHub
import com.azrael.pixivdumpsync.UnifiedDownloadsUi
import kotlinx.coroutines.delay
import com.asterion.videoflow.NexusWebAcquisition
import com.openlocal.office.core.FgSearchIndex
import com.openlocal.office.core.FgTeraboxClient
import com.openlocal.office.core.FgTeraboxDocumentsProvider
import com.openlocal.office.core.FgTeraboxSearchIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** One application surface: donors only supply engines, never separate launchers. */
private val C_CARD=NexusVisual.panel
private val C_BORDER=NexusVisual.border
private val C_SILVER=NexusVisual.silver
private val C_TEXT=NexusVisual.text
private val C_MUTED=NexusVisual.muted

data class NexusUnifiedEntry(
    val title:String,
    val detail:String,
    val source:String,
    val uri:Uri?=null,
    val mime:String="application/octet-stream",
    val chapter:Int=-1,
    val web:String?=null,
    val path:String?=null,
    val size:Long=-1,
    val folder:Boolean=false
)
@Composable private fun UnifiedHeading(title:String,subtitle:String){
    Text(title,color=C_TEXT,fontSize=23.sp,fontWeight=FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}
@Composable private fun UnifiedField(value:String,change:(String)->Unit,hint:String,secret:Boolean=false){
    OutlinedTextField(value=value,onValueChange=change,label={Text(hint)},singleLine=true,
        visualTransformation=if(secret)androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),
        colors=OutlinedTextFieldDefaults.colors(
            focusedBorderColor=C_SILVER,unfocusedBorderColor=C_BORDER,
            focusedTextColor=C_TEXT,unfocusedTextColor=C_TEXT,
            focusedLabelColor=C_SILVER,unfocusedLabelColor=C_MUTED))
}
@Composable private fun UnifiedButton(text:String,enabled:Boolean=true,action:()->Unit){
    OutlinedButton(onClick=action,enabled=enabled,
        modifier=Modifier.heightIn(min=50.dp),shape=RoundedCornerShape(13.dp),
        border=BorderStroke(1.dp,C_BORDER)){Text(text)}
}
@Composable private fun UnifiedEntryCard(entry:NexusUnifiedEntry,action:()->Unit){
    Card(modifier=Modifier.fillMaxWidth().clickable(onClick=action),
        colors=CardDefaults.cardColors(containerColor=C_CARD),
        shape=RoundedCornerShape(15.dp),border=BorderStroke(1.dp,C_BORDER)){
        Column(Modifier.fillMaxWidth().padding(15.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){
                Text(entry.title,color=C_TEXT,fontWeight=FontWeight.Medium,
                    fontSize=14.sp,maxLines=2,overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Text(entry.source,color=C_SILVER,fontSize=11.sp)
            }
            if(entry.detail.isNotBlank())Text(entry.detail,color=C_MUTED,
                fontSize=12.sp,maxLines=3,overflow=TextOverflow.Ellipsis,
                modifier=Modifier.padding(top=5.dp))
        }
    }
}

/** All Nexus navigation remains within one Compose Activity. */
@Composable
fun NexusWorkspace(
    section:String,
    onOpen:(Uri,String,String,Int)->Unit,
    onLibraryRefresh:()->Unit
){
    when(section){
        "Search"->NexusUnifiedSearch(onOpen,onLibraryRefresh)
        "Transfers"->NexusUnifiedTransfers(onOpen)
        "Cloud"->NexusUnifiedCloud(onOpen,onLibraryRefresh)
        "Organize","Activity"->NexusUnifiedOrganize("Scanning")
        "Taxonomy"->NexusUnifiedOrganize("Fusion")
        "Review"->NexusUnifiedOrganize("Reviews")
        "Intelligence"->NexusUnifiedIntelligence()
    }
}

@Composable
private fun NexusUnifiedSearch(
    onOpen:(Uri,String,String,Int)->Unit,
    onLibraryRefresh:()->Unit
){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    val main=remember{Handler(Looper.getMainLooper())}
    var term by remember{mutableStateOf("")}
    var online by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf("No query has been sent online.")}
    var loading by remember{mutableStateOf(false)}
    var hits by remember{mutableStateOf<List<NexusUnifiedEntry>>(emptyList())}
    var selected by remember{mutableStateOf<NexusUnifiedEntry?>(null)}
    val generation=remember{AtomicInteger()}
    val cloud=remember(ctx){FgTeraboxClient(ctx.applicationContext)}
    val cloudIndex=remember(ctx){
        FgTeraboxSearchIndex(ctx,cloud,object:FgTeraboxSearchIndex.Listener{
            override fun onSearch(raw:String){main.post{
                runCatching{
                    val response=JSONObject(raw)
                    if(response.optInt("token",-1)!=generation.get())return@runCatching
                    val a=response.optJSONArray("items")?:JSONArray()
                    val rows=mutableListOf<NexusUnifiedEntry>()
                    for(i in 0 until a.length()){
                        val j=a.optJSONObject(i)?:continue
                        val path=j.optString("path")
                        if(!path.startsWith("/"))continue
                        val name=j.optString("name",path)
                        rows.add(NexusUnifiedEntry(name,"Cloud metadata","Cloud",
                            mime=FgTeraboxClient.mime(name),path=path,
                            size=j.optLong("size",-1),folder=j.optBoolean("folder")))
                    }
                    hits=hits+rows
                }
            }}
            override fun onProgress(raw:String){}
        })
    }
    val safIndex=remember(ctx){
        FgSearchIndex(ctx,object:FgSearchIndex.Listener{
            override fun onResult(raw:String){main.post{
                runCatching{
                    val response=JSONObject(raw)
                    if(response.optInt("token",-1)!=generation.get())return@runCatching
                    val a=response.optJSONArray("items")?:JSONArray()
                    val rows=mutableListOf<NexusUnifiedEntry>()
                    for(i in 0 until a.length()){
                        val j=a.optJSONObject(i)?:continue
                        val uri=j.optString("uri")
                        if(!uri.startsWith("content://"))continue
                        rows.add(NexusUnifiedEntry(j.optString("name","File"),
                            j.optString("searchPath","Authorized folder"),
                            "Folders",Uri.parse(uri),j.optString("mime","application/octet-stream"),
                            folder=j.optBoolean("folder")))
                    }
                    hits=hits+rows
                }
            }}
            override fun onProgress(raw:String){}
        })
    }
    DisposableEffect(cloudIndex,safIndex){
        onDispose{generation.incrementAndGet();cloudIndex.close();safIndex.close()}
    }
    fun execute(){
        val query=term.trim()
        if(query.length !in 2..180){status="Enter 2–180 characters";return}
        val token=generation.incrementAndGet()
        loading=true;hits=emptyList();status="Searching Nexus sources"
        scope.launch{
            try{
                val originals=withContext(Dispatchers.IO){
                    val all=mutableListOf<NexusUnifiedEntry>()
                    val registry=NexusRegistry(ctx)
                    registry.search(query,100).forEach{
                        all.add(NexusUnifiedEntry(it.displayName,
                            "Verified original · "+it.mime,"Library",
                            NexusLibraryProvider.uriFor(ctx,it.sha256),it.mime))
                    }
                    NexusBookIndex(ctx).search(query,70).forEach{
                        val original=registry.find(it.sha256)
                        if(original!=null)all.add(NexusUnifiedEntry(
                            original.displayName,"Chapter "+(it.chapter+1)+" · "+it.excerpt,
                            "Book",NexusLibraryProvider.uriFor(ctx,it.sha256),
                            original.mime,it.chapter))
                    }
                    runCatching{
                        StandaloneRuntime.initialize(ctx)
                        StandaloneRuntime.searchByFilename(query,1,80).forEach{item->
                            val uri=(item["uri"]?:item["image_uri"])?.toString().orEmpty()
                            if(uri.startsWith("content://"))
                                all.add(NexusUnifiedEntry(
                                    (item["filename"]?:item["name"])?.toString()?:"Indexed artwork",
                                    "Indexed image","Gallery",Uri.parse(uri),"image/*"))
                        }
                    }
                    all
                }
                if(token!=generation.get())return@launch
                hits=originals
                for(tree in NexusSourceRegistry(ctx).roots()){
                    safIndex.ensure(tree,false)
                    safIndex.search(tree,query,"all",0,token)
                }
                if(cloud.configured()){
                    cloudIndex.ensure(false)
                    cloudIndex.search(query,"all",0,token)
                }
                if(online){
                    val response=withContext(Dispatchers.IO){
                        AsterionSearchHub.searchForNexus(ctx as Activity,query,0,0)
                    }
                    if(token==generation.get()){
                        val incoming=mutableListOf<NexusUnifiedEntry>()
                        val a=response.optJSONArray("items")?:JSONArray()
                        for(i in 0 until a.length()){
                            val j=a.optJSONObject(i)?:continue
                            val url=j.optString("url")
                            if(url.startsWith("https://")||url.startsWith("http://")||
                                url.startsWith("magnet:"))
                                incoming.add(NexusUnifiedEntry(
                                    j.optString("title",url),j.optString("detail",""),
                                    j.optString("source","Web"),web=url))
                        }
                        hits=hits+incoming
                    }
                }
                if(token==generation.get())status="Found "+hits.size+" matches"
            }catch(t:Exception){
                if(token==generation.get())status="Partial search failure: "+
                    t.javaClass.simpleName+": "+t.message.orEmpty().take(100)
            }finally{if(token==generation.get())loading=false}
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp,vertical=12.dp)){
        UnifiedHeading("Discover",
            "Find originals, book passages, gallery images and connected cloud files together")
        UnifiedField(term,{term=it},"Search all indexed sources")
        Row(verticalAlignment=Alignment.CenterVertically){
            Checkbox(checked=online,onCheckedChange={online=it},
                colors=CheckboxDefaults.colors(checkedColor=C_SILVER))
            Text("Search public web too",color=C_TEXT,fontSize=12.sp,
                modifier=Modifier.weight(1f))
            UnifiedButton(if(loading)"Searching…" else "Search",!loading){execute()}
        }
        Text(status,color=C_MUTED,fontSize=12.sp,modifier=Modifier.padding(vertical=11.dp))
        LazyColumn(verticalArrangement=Arrangement.spacedBy(9.dp)){
            items(hits){item->UnifiedEntryCard(item){
                if(item.web!=null){selected=item}
                else if(item.path!=null){
                    if(!item.folder)
                        onOpen(FgTeraboxDocumentsProvider.uriFor(item.path,item.size),
                            item.mime,item.title,item.chapter)
                    else status="Open cloud folders from Library / TeraBox"
                }else if(item.folder)status="Open authorized folders from Library / TeraBox"
                else if(item.uri!=null)onOpen(item.uri,item.mime,item.title,item.chapter)
            }}
        }
    }
    selected?.let{chosen->
        AlertDialog(onDismissRequest={selected=null},
            title={Text(chosen.title)},
            text={Text("Source page is not a verified original. Download only after actual original resolution.")},
            confirmButton={
                TextButton(onClick={
                    selected=null
                    val url=chosen.web?:return@TextButton
                    if(url.startsWith("magnet:")){
                        status="Use torrent transfer for this candidate";return@TextButton
                    }
                    scope.launch{
                        loading=true;status="Resolving chosen original"
                        runCatching{withContext(Dispatchers.IO){
                            NexusWebAcquisition.acquireSelected(ctx,url)
                        }}.onSuccess{
                            status="Verified original: "+it.displayName
                            onLibraryRefresh()
                        }.onFailure{status="Original unavailable: "+it.message.orEmpty().take(120)}
                        loading=false
                    }
                }){Text("Acquire original")}
            },
            dismissButton={
                TextButton(onClick={
                    selected=null
                    runCatching{ctx.startActivity(
                        Intent(Intent.ACTION_VIEW,Uri.parse(chosen.web)))}
                }){Text("Visit source")}
            }
        )
    }
}

/** Unified transfer dashboard. All statuses/actions originate from the native
 * Downloader queue aggregator, including videos, EPUB, torrents, X and cloud.
 * No module opens a second app; saved original opens through Nexus routing.
 */
@Composable
private fun NexusUnifiedTransfers(onOpen:(Uri,String,String,Int)->Unit){
    val ctx=LocalContext.current as Activity
    val scope=rememberCoroutineScope()
    var entries by remember{mutableStateOf<List<UnifiedDownloadsUi.NexusQueueEntry>>(emptyList())}
    var filter by remember{mutableStateOf("Unfinished")}
    var status by remember{mutableStateOf("Reading saved transfer receipts")}
    var refreshing by remember{mutableStateOf(false)}
    var pendingRemove by remember{mutableStateOf<UnifiedDownloadsUi.NexusQueueEntry?>(null)}
    fun refresh(){
        scope.launch{
            refreshing=true
            runCatching{withContext(Dispatchers.IO){
                UnifiedDownloadsUi.snapshotForNexus(ctx)
            }}.onSuccess{
                entries=it
                status=it.size.toString()+" native transfer records"
            }.onFailure{
                status="Transfer queue unavailable: "+it.message.orEmpty().take(120)
            }
            refreshing=false
        }
    }
    LaunchedEffect(Unit){
        while(true){
            refresh()
            delay(10000L)
        }
    }
    val visible=remember(entries,filter){
        entries.filter{row->when(filter){
            "Active"->row.state=="ACTIVE"
            "Saved"->row.state=="DONE"
            "Issues"->row.state in setOf("FAILED","HELD")
            "All"->true
            else->row.state!="DONE"
        }}
    }
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp,vertical=12.dp)){
        UnifiedHeading("Transfers",
            "Every native download queue in one place, with retained progress and recovery")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),
            verticalAlignment=Alignment.CenterVertically){
            Text(status,color=C_MUTED,fontSize=12.sp,modifier=Modifier.weight(1f),
                maxLines=2)
            UnifiedButton(if(refreshing)"Reading…" else "Refresh",!refreshing){refresh()}
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(
            rememberScrollState()).padding(vertical=10.dp),
            horizontalArrangement=Arrangement.spacedBy(6.dp)){
            listOf("Unfinished","Active","Issues","Saved","All").forEach{option->
                FilterChip(selected=filter==option,onClick={filter=option},
                    label={Text(option,fontSize=12.sp)})
            }
        }
        LazyColumn(verticalArrangement=Arrangement.spacedBy(9.dp)){
            items(visible,key={it.key}){item->
                Card(colors=CardDefaults.cardColors(containerColor=C_CARD),
                    border=BorderStroke(1.dp,C_BORDER),
                    shape=RoundedCornerShape(15.dp)){
                    Column(Modifier.fillMaxWidth().padding(15.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Text(item.title,color=C_TEXT,fontSize=14.sp,
                                fontWeight=FontWeight.Medium,maxLines=2,
                                overflow=TextOverflow.Ellipsis,
                                modifier=Modifier.weight(1f))
                            Spacer(Modifier.width(10.dp))
                            Text(item.state,color=C_SILVER,fontSize=11.sp)
                        }
                        Text(item.source+" · "+item.detail,
                            color=C_MUTED,fontSize=12.sp,
                            maxLines=3,overflow=TextOverflow.Ellipsis,
                            modifier=Modifier.padding(top=6.dp))
                        item.progress?.let{value->
                            val percent=value.coerceIn(0,100)
                            Text(percent.toString()+"%",color=C_TEXT,fontSize=12.sp,
                                modifier=Modifier.padding(top=7.dp))
                            LinearProgressIndicator(
                                progress={percent/100f},
                                modifier=Modifier.fillMaxWidth().padding(top=5.dp),
                                color=C_SILVER,trackColor=C_BORDER)
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),
                            modifier=Modifier.padding(top=10.dp)){
                            if(item.state=="DONE"&&!item.savedUri.isNullOrBlank()){
                                UnifiedButton("Open"){
                                    runCatching{onOpen(Uri.parse(item.savedUri),
                                        item.mime,item.title,-1)}
                                        .onFailure{status="Cannot open file: "+
                                            it.message.orEmpty().take(110)}
                                }
                            }else if(item.perform!=null&&!item.actionLabel.isNullOrBlank()){
                                UnifiedButton(item.actionLabel){
                                    runCatching{item.perform.invoke()}
                                        .onFailure{status="Action failed: "+
                                            it.message.orEmpty().take(110)}
                                    refresh()
                                }
                            }
                            if(item.remove!=null){
                                UnifiedButton(if(item.state=="DONE")"Hide" else "Remove"){
                                    pendingRemove=item
                                }
                            }
                        }
                    }
                }
            }
            if(visible.isEmpty())item{
                Text("No "+filter.lowercase()+" transfers",color=C_MUTED,fontSize=13.sp,
                    modifier=Modifier.fillMaxWidth().padding(top=22.dp))
            }
        }
    }
    pendingRemove?.let{selected->
        AlertDialog(onDismissRequest={pendingRemove=null},
            title={Text(if(selected.state=="DONE")"Hide saved transfer?" else "Remove queued transfer?")},
            text={Text("Use the existing transfer engine's removal action. "+
                "Original files are not deleted by Nexus navigation.")},
            confirmButton={
                TextButton(onClick={
                    pendingRemove=null
                    runCatching{selected.remove?.invoke()}
                        .onFailure{status="Removal failed: "+it.message.orEmpty().take(110)}
                    refresh()
                }){Text("Continue")}
            },
            dismissButton={TextButton(onClick={pendingRemove=null}){Text("Cancel")}}
        )
    }
}

@Composable
private fun NexusUnifiedCloud(
    onOpen:(Uri,String,String,Int)->Unit,
    onRefresh:()->Unit
){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    val cloud=remember(ctx){FgTeraboxClient(ctx)}
    val registry=remember(ctx){NexusSourceRegistry(ctx)}
    var cookie by remember{mutableStateOf("")}
    var connected by remember{mutableStateOf(cloud.configured())}
    var status by remember{mutableStateOf("Source bytes stay in their original location")}
    var folder by remember{mutableStateOf("/")}
    var page by remember{mutableIntStateOf(1)}
    var rows by remember{mutableStateOf<List<NexusUnifiedEntry>>(emptyList())}
    var sourceRoots by remember{mutableStateOf(registry.roots())}
    var selected by remember{mutableStateOf<NexusUnifiedEntry?>(null)}
    var selectedTree by remember{mutableStateOf<Uri?>(null)}
    var currentDocument by remember{mutableStateOf<String?>(null)}
    var sourceOffset by remember{mutableIntStateOf(0)}
    var sourceRows by remember{mutableStateOf<List<NexusUnifiedEntry>>(emptyList())}
    val treeHistory=remember{mutableStateListOf<String>()}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->
        if(uri!=null){
            runCatching{
                ctx.contentResolver.takePersistableUriPermission(
                    uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                registry.add(uri);sourceRoots=registry.roots()
                status="Authorized folder connected"
            }.onFailure{status="Cannot grant folder access: "+it.message.orEmpty().take(100)}
        }
    }
    fun refresh(){
        if(!connected)return
        val path=folder;val current=page
        scope.launch{
            status="Reading "+path
            runCatching{withContext(Dispatchers.IO){
                val raw=cloud.listPageCached(path,current)
                val found=mutableListOf<NexusUnifiedEntry>()
                for(i in 0 until raw.length()){
                    val j=FgTeraboxClient.mapEntry(raw.optJSONObject(i)?:continue)
                    val loc=j.optString("path")
                    if(!loc.startsWith("/"))continue
                    val name=j.optString("name",loc)
                    val isFolder=j.optBoolean("folder",j.optInt("isdir")!=0)
                    found.add(NexusUnifiedEntry(name,
                        if(isFolder)"Folder" else (j.optLong("size",0)/1024).toString()+" KiB",
                        "Cloud",mime=FgTeraboxClient.mime(name),
                        path=loc,size=j.optLong("size",-1),folder=isFolder))
                }
                found
            }}.onSuccess{
                if(path==folder&&current==page){rows=it;status=it.size.toString()+" files"}
            }.onFailure{status="Cloud unavailable: "+it.message.orEmpty().take(120)}
        }
    }
    fun browseTree(tree:Uri,id:String,offset:Int){
        selectedTree=tree
        currentDocument=id
        sourceOffset=offset
        scope.launch{
            status="Browsing authorized folder"
            runCatching{withContext(Dispatchers.IO){
                val child=DocumentsContract.buildChildDocumentsUriUsingTree(tree,id)
                val result=mutableListOf<NexusUnifiedEntry>()
                val cols=arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE)
                val options=Bundle().apply{
                    putInt(ContentResolver.QUERY_ARG_OFFSET,offset)
                    putInt(ContentResolver.QUERY_ARG_LIMIT,80)
                    // The pinned cloud DocumentsProvider consumes these native page hints.
                    // Keep standard SAF hints as well for third-party providers.
                    putInt(FgTeraboxDocumentsProvider.PAGE_OFFSET,offset)
                    putInt(FgTeraboxDocumentsProvider.PAGE_LIMIT,80)
                }
                val cursor=ctx.contentResolver.query(child,cols,options,null)
                    ?:throw java.io.IOException("No provider response")
                cursor.use{
                    val ci=it.getColumnIndexOrThrow(cols[0])
                    val cn=it.getColumnIndexOrThrow(cols[1])
                    val cm=it.getColumnIndexOrThrow(cols[2])
                    val cs=it.getColumnIndex(cols[3])
                    while(it.moveToNext()&&result.size<80){
                        val childId=it.getString(ci)
                        val title=it.getString(cn)?:"File"
                        val mime=it.getString(cm)?:"application/octet-stream"
                        val length=if(cs<0||it.isNull(cs))-1 else it.getLong(cs)
                        result.add(NexusUnifiedEntry(
                            title,if(length>=0)(length/1024).toString()+" KiB" else "Folder / file",
                            "SAF",DocumentsContract.buildDocumentUriUsingTree(tree,childId),
                            mime,size=length,
                            folder=mime==DocumentsContract.Document.MIME_TYPE_DIR))
                    }
                }
                result
            }}.onSuccess{
                if(selectedTree==tree&&currentDocument==id&&sourceOffset==offset){
                    sourceRows=it
                    status=it.size.toString()+" folder entries"
                }
            }.onFailure{status="Folder read failed: "+it.message.orEmpty().take(120)}
        }
    }
    LaunchedEffect(connected,folder,page){if(connected)refresh()}
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp,vertical=12.dp)){
        UnifiedHeading("Cloud & sources",
            "Browse TeraBox and authorized Android folders without duplicating libraries")
        if(!connected){
            UnifiedField(cookie,{cookie=it},"TeraBox session (ndus)",true)
            UnifiedButton("Connect",cookie.trim().length>=12){
                val token=cookie.trim();cookie=""
                if(cloud.setCookie(token)){connected=true;status="TeraBox session encrypted"}
                else status="Connection token rejected"
            }
        }else{
            Row(verticalAlignment=Alignment.CenterVertically){
                Text("Connected · "+folder,color=C_SILVER,fontSize=12.sp,
                    modifier=Modifier.weight(1f),maxLines=1)
                UnifiedButton("Up",folder!="/"){
                    folder=folder.substringBeforeLast('/',"/").ifBlank{"/"};page=1
                }
                Spacer(Modifier.width(7.dp))
                UnifiedButton("Reload"){refresh()}
            }
        }
        Row(Modifier.fillMaxWidth().padding(top=14.dp),
            verticalAlignment=Alignment.CenterVertically){
            Text("Authorized folders",color=C_TEXT,fontSize=15.sp,
                modifier=Modifier.weight(1f))
            UnifiedButton("Add source"){picker.launch(null)}
        }
        Text(status,color=C_MUTED,fontSize=12.sp,modifier=Modifier.padding(vertical=10.dp))
        LazyColumn(verticalArrangement=Arrangement.spacedBy(9.dp)){
            if(selectedTree!=null&&currentDocument!=null){
                item{
                    Row(horizontalArrangement=Arrangement.spacedBy(9.dp)){
                        UnifiedButton("Back"){
                            val tree=selectedTree
                            if(treeHistory.isNotEmpty()&&tree!=null){
                                val parent=treeHistory.removeAt(treeHistory.lastIndex)
                                browseTree(tree,parent,0)
                            }else{
                                selectedTree=null;currentDocument=null
                                sourceRows=emptyList()
                            }
                        }
                        UnifiedButton("Reload"){
                            val tree=selectedTree;val doc=currentDocument
                            if(tree!=null&&doc!=null)browseTree(tree,doc,sourceOffset)
                        }
                    }
                }
                items(sourceRows){entry->
                    UnifiedEntryCard(entry){
                        val tree=selectedTree;val uri=entry.uri
                        if(uri!=null&&tree!=null){
                            if(entry.folder){
                                val doc=runCatching{
                                    DocumentsContract.getDocumentId(uri)}.getOrNull()
                                if(doc!=null){
                                    currentDocument?.let{treeHistory.add(it)}
                                    browseTree(tree,doc,0)
                                }
                            }else onOpen(uri,entry.mime,entry.title,-1)
                        }
                    }
                }
                if(sourceRows.size>=80)item{
                    UnifiedButton("More folder items"){
                        val tree=selectedTree;val doc=currentDocument
                        if(tree!=null&&doc!=null)browseTree(tree,doc,sourceOffset+80)
                    }
                }
            }else{
                items(sourceRoots){tree->
                    UnifiedEntryCard(NexusUnifiedEntry(
                        tree.lastPathSegment?:"Connected source",
                        tree.authority.orEmpty(),"Folder")){
                        val doc=runCatching{
                            DocumentsContract.getTreeDocumentId(tree)}.getOrNull()
                        if(doc!=null){treeHistory.clear();browseTree(tree,doc,0)}
                        else status="Source grant is unavailable"
                    }
                }
                item{Text("Streamed files",color=C_TEXT,fontSize=16.sp,
                    modifier=Modifier.padding(top=15.dp))}
                items(rows){entry->
                    UnifiedEntryCard(entry){
                        when{
                            entry.path!=null&&entry.folder->{folder=entry.path;page=1}
                            entry.path!=null->selected=entry
                            entry.uri!=null&&!entry.folder->onOpen(entry.uri,entry.mime,entry.title,-1)
                            else->status="Select the folder in the authorized source list"
                        }
                    }
                }
                if(connected&&rows.size>=FgTeraboxClient.pageSize())item{
                    UnifiedButton("Next cloud page"){page++}
                }
            }
        }
    }
    selected?.let{entry->
        AlertDialog(onDismissRequest={selected=null},
            title={Text(entry.title)},
            text={Text("Stream without saving locally, or explicitly create a verified offline copy.")},
            confirmButton={
                TextButton(onClick={
                    selected=null
                    val path=entry.path?:return@TextButton
                    onOpen(FgTeraboxDocumentsProvider.uriFor(path,entry.size),
                        entry.mime,entry.title,-1)
                }){Text("Stream")}
            },
            dismissButton={
                TextButton(onClick={
                    selected=null
                    val path=entry.path?:return@TextButton
                    scope.launch {
                        status="Saving verified original"
                        runCatching{withContext(Dispatchers.IO){
                            cloud.open(path).use{input->
                                val account=cloud.metadataScope()
                                NexusRegistry(ctx).ingest(input,entry.title,entry.mime,
                                    "terabox",account,"terabox:"+account+":"+path,
                                    entry.size,null)
                            }
                        }}.onSuccess{status="Verified saved: "+it.displayName;onRefresh()}
                            .onFailure{status="Offline copy failed: "+it.message.orEmpty().take(120)}
                    }
                }){Text("Save offline")}
            }
        )
    }
}

@Composable
private fun NexusUnifiedOrganize(initialPage:String){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var page by remember(initialPage){mutableStateOf(initialPage)}
    var status by remember(initialPage){mutableStateOf("Loading…")}
    var rows by remember{mutableStateOf<List<String>>(emptyList())}
    var tag by remember{mutableStateOf("")}
    var family by remember{mutableStateOf("")}
    val importPicker=rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)scope.launch{
            status="Importing selected Fusion JSON"
            runCatching{withContext(Dispatchers.IO){
                val buffer=java.io.ByteArrayOutputStream()
                ctx.contentResolver.openInputStream(uri)?.use{input->
                    val block=ByteArray(32768)
                    while(true){
                        val n=input.read(block)
                        if(n<0)break
                        if(buffer.size()+n>32*1024*1024)
                            throw java.io.IOException("Fusion import exceeds 32 MiB")
                        buffer.write(block,0,n)
                    }
                }?:throw java.io.IOException("Input is not readable")
                val db=LocalDatabase(ctx)
                try{
                    FusionDatabaseRepository(db)
                        .importJson(buffer.toString("UTF-8"),false).toMap().toString()
                }finally{db.close()}
            }}.onSuccess{status=it}.onFailure{
                status="Fusion import failed: "+it.message.orEmpty().take(140)
            }
        }
    }
    val exportPicker=rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")){uri->
        if(uri!=null)scope.launch{
            status="Exporting Fusion records"
            runCatching{withContext(Dispatchers.IO){
                val db=LocalDatabase(ctx)
                val json=try{FusionDatabaseRepository(db).exportJson(true)}
                    finally{db.close()}
                if(json.length>96*1024*1024)
                    throw java.io.IOException("Export exceeds safe in-memory JSON size")
                ctx.contentResolver.openOutputStream(uri,"wt")?.use{
                    it.write(json.toByteArray(Charsets.UTF_8));it.flush()
                }?:throw java.io.IOException("Export target unavailable")
                "Fusion JSON exported to selected destination"
            }}.onSuccess{status=it}.onFailure{
                status="Fusion export failed: "+it.message.orEmpty().take(140)
            }
        }
    }
    fun run(label:String,task:()->List<String>){
        scope.launch{
            status=label
            rows=emptyList()
            runCatching{withContext(Dispatchers.IO){task()}}
                .onSuccess{rows=it;status=it.size.toString()+" entries"}
                .onFailure{status="Unavailable: "+it.message.orEmpty().take(140)}
        }
    }
    LaunchedEffect(page){
        when(page){
            "Fusion"->run("Reading Fusion",{
                val db=LocalDatabase(ctx)
                try{FusionDatabaseRepository(db).logicalContentCounts().map{
                    it.key+": "+it.value
                }}finally{db.close()}
            })
            "Tags"->run("Reading Fusion tags",{
                StandaloneRuntime.initialize(ctx)
                StandaloneRuntime.getTags().take(250)
            })
            "Reviews"->run("Reading native reviews",{
                StandaloneRuntime.initialize(ctx)
                StandaloneRuntime.getReviewQueue().take(120).map{it.toString().take(300)}
            })
            "Scanning"->run("Reading scan status",{
                StandaloneRuntime.initialize(ctx)
                StandaloneRuntime.scanStatus().map{it.key+": "+it.value}
            })
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp,vertical=12.dp)){
        if(initialPage=="Fusion")Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf("Fusion","Tags").forEach{label->
                FilterChip(selected=page==label,onClick={page=label},
                    label={Text(label,fontSize=12.sp)})
            }
        }
        if(page=="Fusion"){
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                UnifiedButton("Validate"){
                    run("Validating Fusion",{
                        val db=LocalDatabase(ctx)
                        try{listOf(FusionDatabaseRepository(db).validateDatabase(false)
                            .toMap().toString().take(2500))}
                        finally{db.close()}
                    })
                }
                UnifiedButton("Import JSON"){importPicker.launch(arrayOf("application/json"))}
                UnifiedButton("Export JSON"){exportPicker.launch("nexus-fusion.json")}
            }
        }
        if(page=="Tags"){
            UnifiedField(tag,{tag=it},"Canonical tag")
            UnifiedField(family,{family=it},"Two-letter family")
            UnifiedButton("Create tag",tag.isNotBlank()&&family.trim().length==2){
                val chosen=tag.trim();val id=family.trim().uppercase()
                run("Creating tag",{
                    StandaloneRuntime.createNativeTag(chosen,id)
                    StandaloneRuntime.getTags().take(250)
                })
                tag=""
            }
        }
        if(page=="Scanning"){
            UnifiedButton("Scan authorized source"){
                val options=NexusSourceRegistry(ctx).roots()
                if(options.isEmpty())status="Connect a source folder in Library / TeraBox first"
                else android.app.AlertDialog.Builder(ctx)
                    .setTitle("Choose granted image source")
                    .setItems(options.map{it.lastPathSegment}.toTypedArray()){_,index->
                        val source=options[index].toString()
                        run("Starting Core scan",{
                            StandaloneRuntime.initialize(ctx)
                            listOf(StandaloneRuntime.startScan(source).toString())
                        })
                    }.show()
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                UnifiedButton("Pause"){
                    run("Pausing scan",{listOf(StandaloneRuntime.pauseScan().toString())})
                }
                UnifiedButton("Resume"){
                    run("Resuming scan",{listOf(StandaloneRuntime.resumeScan().toString())})
                }
                UnifiedButton("Stop"){
                    android.app.AlertDialog.Builder(ctx)
                        .setTitle("Cancel active scan?")
                        .setMessage("Stop indexing without deleting original files.")
                        .setNegativeButton("Keep scanning",null)
                        .setPositiveButton("Stop scan"){_,_->
                            run("Cancelling scan",{
                                listOf(StandaloneRuntime.cancelScan().toString())
                            })
                        }.show()
                }
            }
        }
        Text(status,color=C_MUTED,fontSize=12.sp,
            modifier=Modifier.padding(vertical=12.dp))
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){
            items(rows){entry->
                UnifiedEntryCard(NexusUnifiedEntry(entry,"","Fusion")){}
            }
        }
    }
}

@Composable
private fun NexusUnifiedIntelligence(){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var page by remember{mutableStateOf("Models")}
    var status by remember{mutableStateOf("Actual native Core runtime")}
    var rows by remember{mutableStateOf<List<String>>(emptyList())}
    fun run(){
        scope.launch{
            status="Loading "+page.lowercase()
            runCatching{withContext(Dispatchers.IO){
                StandaloneRuntime.initialize(ctx)
                when(page){
                    "Models"->StandaloneRuntime.listInstalledAiModels().map{
                        it.toString().take(250)}
                    "Queue"->StandaloneRuntime.listAiTasks(50).map{
                        it.toString().take(250)}
                    "Hardware"->StandaloneRuntime.detectAiHardwareProfile().map{
                        it.key+": "+it.value}
                    else->StandaloneRuntime.healthStatus().map{it.key+": "+it.value}
                }
            }}.onSuccess{rows=it;status=it.size.toString()+" native entries"}
                .onFailure{status="Native runtime unavailable: "+it.message.orEmpty().take(120)}
        }
    }
    LaunchedEffect(page){run()}
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp,vertical=12.dp)){
        UnifiedHeading("Intelligence","Models, tasks, hardware and runtime health")
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
            listOf("Models","Queue","Hardware","Health").forEach{choice->
                FilterChip(selected=page==choice,onClick={page=choice},
                    label={Text(choice,fontSize=12.sp)})
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            UnifiedButton("Refresh"){run()}
            if(page=="Queue"){
                UnifiedButton("Pause"){scope.launch {
                    status=withContext(Dispatchers.IO){
                        StandaloneRuntime.pauseAiQueue().toString()
                    };run()
                }}
                UnifiedButton("Resume"){scope.launch {
                    status=withContext(Dispatchers.IO){
                        StandaloneRuntime.resumeAiQueue().toString()
                    };run()
                }}
            }
        }
        Text(status,color=C_MUTED,fontSize=12.sp,
            modifier=Modifier.padding(vertical=12.dp))
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){
            items(rows){entry->
                UnifiedEntryCard(NexusUnifiedEntry(entry,"","Core")){}
            }
        }
    }
}
