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
private val C_CARD=Color(0xFF151B25)
private val C_BORDER=Color(0xFF303946)
private val C_GOLD=Color(0xFFD4B47D)
private val C_TEXT=Color(0xFFF2F2F4)
private val C_MUTED=Color(0xFF9CA5B4)

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
    Text(subtitle,color=C_MUTED,fontSize=12.sp,
        modifier=Modifier.padding(top=6.dp,bottom=15.dp))
}
@Composable private fun UnifiedField(value:String,change:(String)->Unit,hint:String){
    OutlinedTextField(value=value,onValueChange=change,label={Text(hint)},singleLine=true,
        modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),
        colors=OutlinedTextFieldDefaults.colors(
            focusedBorderColor=C_GOLD,unfocusedBorderColor=C_BORDER,
            focusedTextColor=C_TEXT,unfocusedTextColor=C_TEXT,
            focusedLabelColor=C_GOLD,unfocusedLabelColor=C_MUTED))
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
                Text(entry.source,color=C_GOLD,fontSize=11.sp)
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
        "Cloud"->NexusUnifiedCloud(onOpen,onLibraryRefresh)
        "Organize"->NexusUnifiedOrganize()
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
                                    "Core indexed image","Gallery",Uri.parse(uri),"image/*"))
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
                colors=CheckboxDefaults.colors(checkedColor=C_GOLD))
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
                    else status="Open cloud folders from the Cloud tab"
                }else if(item.folder)status="Open authorized folders from Cloud"
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
    LaunchedEffect(connected,folder,page){if(connected)refresh()}
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp,vertical=12.dp)){
        UnifiedHeading("Cloud & sources",
            "Browse TeraBox and authorized Android folders without duplicating libraries")
        if(!connected){
            UnifiedField(cookie,{cookie=it},"TeraBox session (ndus)")
            UnifiedButton("Connect",cookie.trim().length>=12){
                val token=cookie.trim();cookie=""
                if(cloud.setCookie(token)){connected=true;status="TeraBox session encrypted"}
                else status="Connection token rejected"
            }
        }else{
            Row(verticalAlignment=Alignment.CenterVertically){
                Text("Connected · "+folder,color=C_GOLD,fontSize=12.sp,
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
            items(sourceRoots){tree->
                UnifiedEntryCard(NexusUnifiedEntry(
                    tree.lastPathSegment?:"Connected source",
                    tree.authority.orEmpty(),"Folder")){
                    val path=runCatching{DocumentsContract.getTreeDocumentId(tree)}.getOrNull()
                    if(path==null){status="Source is unavailable"}
                    else{
                        scope.launch{
                            runCatching{withContext(Dispatchers.IO){
                                val child=DocumentsContract.buildChildDocumentsUriUsingTree(tree,path)
                                val result=mutableListOf<NexusUnifiedEntry>()
                                val cols=arrayOf(
                                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                    DocumentsContract.Document.COLUMN_MIME_TYPE)
                                ctx.contentResolver.query(child,cols,null,null,null)?.use{cursor->
                                    val id=cursor.getColumnIndexOrThrow(cols[0])
                                    val name=cursor.getColumnIndexOrThrow(cols[1])
                                    val mime=cursor.getColumnIndexOrThrow(cols[2])
                                    while(cursor.moveToNext()&&result.size<100){
                                        val mt=cursor.getString(mime)?:"application/octet-stream"
                                        result.add(NexusUnifiedEntry(
                                            cursor.getString(name)?:"File","Authorized folder",
                                            "SAF",
                                            DocumentsContract.buildDocumentUriUsingTree(tree,
                                                cursor.getString(id)),
                                            mt,folder=mt==DocumentsContract.Document.MIME_TYPE_DIR))
                                    }
                                }
                                result
                            }}.onSuccess{rows=it;status="Browsing granted folder"}
                                .onFailure{status="Source unavailable: "+it.message.orEmpty().take(100)}
                        }
                    }
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
                        else->status="Open this folder through the authorized source picker"
                    }
                }
            }
            if(connected&&rows.size>=FgTeraboxClient.pageSize())item{
                UnifiedButton("Next page"){page++}
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
private fun NexusUnifiedOrganize(){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var page by remember{mutableStateOf("Fusion")}
    var status by remember{mutableStateOf("Owner-controlled organization")}
    var rows by remember{mutableStateOf<List<String>>(emptyList())}
    var tag by remember{mutableStateOf("")}
    var family by remember{mutableStateOf("")}
    fun run(label:String,task:()->List<String>){
        scope.launch{
            status=label
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
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp,vertical=12.dp)){
        UnifiedHeading("Organize","Shared Fusion knowledge, tags and Core review queue")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf("Fusion","Tags","Reviews").forEach{label->
                FilterChip(selected=page==label,onClick={page=label},
                    label={Text(label,fontSize=12.sp)})
            }
        }
        if(page=="Fusion")UnifiedButton("Validate Fusion"){
            run("Validating",{
                val db=LocalDatabase(ctx)
                try{listOf(FusionDatabaseRepository(db).validateDatabase(false)
                    .toMap().toString().take(2500))}
                finally{db.close()}
            })
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
        UnifiedHeading("Intelligence","AI model management, queued jobs, hardware and runtime health")
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
