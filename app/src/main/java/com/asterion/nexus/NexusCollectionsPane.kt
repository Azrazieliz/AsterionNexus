package com.asterion.nexus

import android.app.Activity
import android.app.AlertDialog as NativeAlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.ailm.android.runtime.FusionSmartCollections
import com.ailm.android.runtime.LocalDatabase
import com.ailm.android.runtime.StandaloneRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private data class CollectionMedia(val key:String,val uri:Uri,val title:String,val mime:String,
                                   val sha:String?=null,val imageId:Int?=null)
private fun registryKind(rule:FusionSmartCollections.Rule?):Pair<String,String>?=when(rule?.kind){
    "media_type"->"media_type" to rule.value
    "local_rating"->"local_rating" to rule.minRating.toString()
    "offline_pin","continue_reading","continue_watching","related_output"->rule.kind to ""
    else->null
}
private val builtins=listOf(
    FusionSmartCollections.Collection("virtual-watching","Continue watching","smart",FusionSmartCollections.Rule("continue_watching",""),true),
    FusionSmartCollections.Collection("virtual-reading","Continue reading","smart",FusionSmartCollections.Rule("continue_reading",""),true),
    FusionSmartCollections.Collection("virtual-pins","Offline pins","smart",FusionSmartCollections.Rule("offline_pin",""),true),
    FusionSmartCollections.Collection("virtual-editions","Related editions and outputs","smart",FusionSmartCollections.Rule("related_output",""),true),
    FusionSmartCollections.Collection("virtual-rated","Highly rated artwork","smart",FusionSmartCollections.Rule("manual_rating","",80),true)
)
private fun localMedia(ctx:android.content.Context,item:NexusRegistry.Item)=CollectionMedia("sha:"+item.sha256,NexusLibraryProvider.uriFor(ctx,item.sha256),item.displayName,item.mime,item.sha256)
private fun coreMedia(item:FusionSmartCollections.Entry)=CollectionMedia("core:"+item.imageId,Uri.parse(item.uri),item.filename,item.mime,imageId=item.imageId)
private fun collectionCover(ctx:android.content.Context,item:CollectionMedia):Bitmap?{
    return runCatching{
    if(item.sha!=null){
        val original=NexusRegistry(ctx).find(item.sha)?:return null
        return NexusContentThumbnail.load(ctx,original)
    }
    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
    ctx.contentResolver.openInputStream(item.uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}
    if(bounds.outWidth<=0||bounds.outHeight<=0)return null
    var sample=1
    while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1024)sample*=2
    ctx.contentResolver.openInputStream(item.uri)?.use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample})}
    }.getOrNull()
}

@Composable
internal fun NexusCollectionsPane(onOpen:(Uri,String,String,Int)->Unit,onCoreRoute:(String)->Unit){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var saved by remember{mutableStateOf<List<FusionSmartCollections.Collection>>(emptyList())}
    var selected by remember{mutableStateOf<FusionSmartCollections.Collection?>(null)}
    var create by remember{mutableStateOf(false)}
    var folders by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)}
    var moreDefs by remember{mutableStateOf(false)}
    var entries by remember{mutableStateOf<List<CollectionMedia>>(emptyList())}
    var coreAfter by remember{mutableStateOf<Int?>(0)}
    var localAfter by remember{mutableStateOf<Long?>(0L)}
    fun refresh(reset:Boolean=true){
        if(busy)return
        busy=true
        scope.launch{
            try{
                val offset=if(reset)0 else saved.size
                val rows=withContext(Dispatchers.IO){LocalDatabase(ctx).use{FusionSmartCollections(it).list(60,offset)}}
                saved=if(reset)rows else saved+rows
                moreDefs=rows.size==60;status=""
            }catch(e:Exception){status="Collections unavailable: "+e.javaClass.simpleName}
            finally{busy=false}
        }
    }
    fun load(reset:Boolean=false){
        val c=selected?:return
        if(busy)return
        val ca=if(reset)0 else coreAfter
        val la=if(reset)0L else localAfter
        busy=true
        scope.launch{
            try{
                val result=withContext(Dispatchers.IO){
                    val all=mutableListOf<CollectionMedia>()
                    var nextCore:Int?=null;var nextLocal:Long?=null
                    if(ca!=null&&(c.kind=="manual"||c.rule?.coreRule==true)){
                        LocalDatabase(ctx).use{db->
                            val store=FusionSmartCollections(db)
                            val page=if(c.id=="virtual-rated")store.previewRule(c.rule!!,ca,60)else store.page(c.id,ca,60)
                            nextCore=page.next;all.addAll(page.entries.map(::coreMedia))
                        }
                    }
                    val registryRule=if(c.kind=="manual")"manual" to c.id else registryKind(c.rule)
                    if(la!=null&&registryRule!=null){
                        val rows=NexusRegistry(ctx).collectionPage(registryRule.first,registryRule.second,la,60)
                        all.addAll(rows.map{localMedia(ctx,it)})
                        nextLocal=if(rows.size==60)rows.last().locationId else null
                    }
                    Triple(all,nextCore,nextLocal)
                }
                if(selected?.id==c.id){
                    entries=(if(reset)result.first else entries+result.first).distinctBy{it.uri.toString()}
                    coreAfter=result.second;localAfter=result.third;status=""
                }
            }catch(e:Exception){status="Collection unavailable: "+e.javaClass.simpleName}
            finally{busy=false}
        }
    }
    LaunchedEffect(Unit){refresh()}
    BackHandler(enabled=selected!=null||folders){
        if(selected!=null){selected=null;entries=emptyList();status=""}else folders=false
    }
    if(folders){
        Column(Modifier.fillMaxSize()){
            TextButton(onClick={folders=false}){Text("Collections")}
            Box(Modifier.weight(1f)){com.ailm.android.ui.screens.NexusCorePane("Collections",onCoreRoute)}
        }
        return
    }
    val chosen=selected
    Column(Modifier.fillMaxSize().padding(horizontal=8.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            if(chosen!=null){
                TextButton(onClick={selected=null;entries=emptyList();status=""},enabled=!busy){Text("Back")}
                Text(chosen.name,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
                TextButton(onClick={load(true)},enabled=!busy){Text("Refresh")}
            }else{
                TextButton(onClick={folders=true},enabled=!busy){Text("Folders")}
                Spacer(Modifier.weight(1f))
                TextButton(onClick={create=true},enabled=!busy){Text("New collection")}
                TextButton(onClick={refresh()},enabled=!busy){Text("Refresh")}
            }
        }
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(status.isNotEmpty())Text(status,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(8.dp))
        if(chosen==null){
            LazyColumn(verticalArrangement=Arrangement.spacedBy(4.dp)){
                items(builtins+saved,key={it.id}){c->
                    CollectionRow(c,enabled=!busy){selected=c;entries=emptyList();coreAfter=0;localAfter=0;status=""}
                }
                if(moreDefs)item{TextButton(onClick={refresh(false)},enabled=!busy){Text("More collections")}}
            }
        }else if(!chosen.supported){
            Text("This imported collection has an unsupported rule format. Existing records are preserved.",modifier=Modifier.padding(12.dp))
        }else{
            LaunchedEffect(chosen.id){load(true)}
            if(entries.isEmpty()&&!busy&&status.isEmpty())Text("No matching indexed items",modifier=Modifier.padding(16.dp))
            if(entries.isNotEmpty()){
                val pager=rememberPagerState(pageCount={entries.size})
                LaunchedEffect(pager.settledPage,entries.size,busy){
                    if(!busy&&pager.settledPage>=entries.lastIndex-3&&(coreAfter!=null||localAfter!=null))load()
                }
                HorizontalPager(state=pager,contentPadding=PaddingValues(horizontal=18.dp),pageSpacing=8.dp,modifier=Modifier.weight(1f).fillMaxWidth(),key={entries[it].key}){index->
                    val media=entries[index]
                    val preview by produceState<Bitmap?>(null,media.key){value=withContext(Dispatchers.IO){collectionCover(ctx,media)}}
                    Box(Modifier.fillMaxSize().background(NexusVisual.imageBackdrop).clickable{onOpen(media.uri,media.mime,media.title,-1)},contentAlignment=Alignment.Center){
                        if(preview!=null)Image(preview!!.asImageBitmap(),media.title,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                        else Text(media.title,modifier=Modifier.padding(12.dp))
                    }
                }
                val current=entries.getOrNull(pager.currentPage)
                Text(current?.title.orEmpty(),maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(6.dp))
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Text((pager.currentPage+1).toString()+" / "+entries.size,modifier=Modifier.weight(1f))
                    if(chosen.kind=="manual"&&current!=null)TextButton(onClick={
                        busy=true
                        scope.launch{
                            try{
                                withContext(Dispatchers.IO){
                                    if(current.sha!=null)NexusRegistry(ctx).removeCollectionMember(chosen.id,current.sha)
                                    else LocalDatabase(ctx).use{FusionSmartCollections(it).remove(chosen.id,current.imageId!!)}
                                }
                                entries=entries.filterNot{it.key==current.key}
                            }catch(e:Exception){status="Membership not removed"}
                            finally{busy=false}
                        }
                    },enabled=!busy){Text("Remove from collection")}
                }
            }
        }
    }
    if(create)CreateCollectionDialog(onClose={create=false},onCreated={create=false;refresh()})
}
@Composable
private fun CollectionRow(c:FusionSmartCollections.Collection,enabled:Boolean,onOpen:()->Unit){
    val ctx=LocalContext.current
    val preview by produceState<Bitmap?>(null,c.id,c.rule){
        value=withContext(Dispatchers.IO){
            runCatching{
                val r=if(c.kind=="manual")"manual" to c.id else registryKind(c.rule)
                val local=r?.let{NexusRegistry(ctx).collectionPage(it.first,it.second,0,1).firstOrNull()}
                val media=if(local!=null)localMedia(ctx,local)else LocalDatabase(ctx).use{db->
                    val store=FusionSmartCollections(db)
                    val page=if(c.id=="virtual-rated")store.previewRule(c.rule!!,0,1) else store.page(c.id,0,1)
                    page.entries.firstOrNull()?.let(::coreMedia)
                }
                media?.let{collectionCover(ctx,it)}
            }.getOrNull()
        }
    }
    Row(Modifier.fillMaxWidth().clickable(enabled=enabled,onClick=onOpen).padding(6.dp),verticalAlignment=Alignment.CenterVertically){
        Box(Modifier.size(62.dp).background(NexusVisual.imageBackdrop),contentAlignment=Alignment.Center){
            preview?.let{Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)}
        }
        Column(Modifier.weight(1f).padding(start=12.dp)){
            Text(c.name,maxLines=2)
            Text(if(!c.supported)"Imported rule" else if(c.kind=="manual")"Manual" else "Smart",style=MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable
private fun CreateCollectionDialog(onClose:()->Unit,onCreated:()->Unit){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var name by remember{mutableStateOf("")}
    var type by remember{mutableStateOf("manual")}
    var query by remember{mutableStateOf("")}
    var choice by remember{mutableStateOf<FusionSmartCollections.Option?>(null)}
    var opts by remember{mutableStateOf<List<FusionSmartCollections.Option>>(emptyList())}
    var rating by remember{mutableStateOf(80f)}
    var media by remember{mutableStateOf("books")}
    var busy by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf("")}
    val canonical=type in setOf("character","series","tag","artist")
    val types=listOf("manual","character","series","tag","artist","manual_rating","local_rating","media_type","offline_pin","continue_reading","continue_watching","related_output")
    LaunchedEffect(type,query){
        opts=emptyList()
        if(canonical){
            kotlinx.coroutines.delay(200)
            runCatching{withContext(Dispatchers.IO){LocalDatabase(ctx).use{FusionSmartCollections(it).options(type,query)}}}
                .onSuccess{opts=it}.onFailure{status="Canonical choices unavailable"}
        }
    }
    AlertDialog(onDismissRequest={if(!busy)onClose()},title={Text("New collection")},text={
        LazyColumn(Modifier.heightIn(max=480.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            item{OutlinedTextField(name,{name=it},label={Text("Name")},singleLine=true)}
            items(types){t->Row(verticalAlignment=Alignment.CenterVertically){
                RadioButton(selected=type==t,onClick={type=t;query="";choice=null},enabled=!busy)
                Text(t.replace('_',' '))
            }}
            if(canonical){
                item{OutlinedTextField(query,{query=it;choice=null},label={Text("Search canonical identity")},singleLine=true)}
                choice?.let{item{Text("Selected: "+it.title+" · "+it.id)}}
                items(opts,key={it.id}){opt->
                    TextButton(onClick={choice=opt}){Text(opt.title+" · "+opt.id)}
                }
            }
            if(type=="manual_rating"||type=="local_rating")item{
                Text("Minimum manual rating: "+rating.toInt());Slider(rating,{rating=it},valueRange=0f..100f)
            }
            if(type=="media_type")items(listOf("image","video","audio","books","assets")){m->Row(verticalAlignment=Alignment.CenterVertically){
                RadioButton(selected=media==m,onClick={media=m});Text(m)
            }}
            if(status.isNotEmpty())item{Text(status,color=MaterialTheme.colorScheme.error)}
        }
    },confirmButton={TextButton(enabled=name.isNotBlank()&&!busy&&(!canonical||choice!=null),onClick={
        val rule=when{
            type=="manual"->null
            canonical->FusionSmartCollections.Rule(type,choice!!.id)
            type=="manual_rating"||type=="local_rating"->FusionSmartCollections.Rule(type,"",rating.toInt())
            type=="media_type"->FusionSmartCollections.Rule(type,media)
            else->FusionSmartCollections.Rule(type,"")
        }
        busy=true;scope.launch{
            runCatching{withContext(Dispatchers.IO){LocalDatabase(ctx).use{FusionSmartCollections(it).create(name,rule)}}}
                .onSuccess{onCreated()}.onFailure{status=it.message?:"Collection not saved"}
            busy=false
        }
    }){Text("Create")}},dismissButton={TextButton(enabled=!busy,onClick=onClose){Text("Cancel")}})
}

object NexusCollectionActions{
    private val worker=Executors.newSingleThreadExecutor()
    fun add(activity:Activity,uri:Uri,mime:String){
        worker.execute{
            val result=runCatching{
                val ctx=activity.applicationContext
                val sha=if(NexusArchiveOperation.available(ctx,uri))uri.lastPathSegment else null
                val core=if(mime.startsWith("image/")){
                    StandaloneRuntime.initialize(ctx);StandaloneRuntime.nexusArtworkId(uri.toString(),sha)
                }else null
                check(core!=null||sha!=null){"Verified item required; pin cloud originals first"}
                val collections=LocalDatabase(ctx).use{FusionSmartCollections(it).list(200).filter{c->c.kind=="manual"}}
                Triple(core,sha,collections)
            }
            activity.runOnUiThread{
                if(activity.isDestroyed||activity.isFinishing)return@runOnUiThread
                result.onFailure{NativeAlertDialog.Builder(activity).setMessage(it.message?:"Collection unavailable").setPositiveButton("Close",null).show()}
                result.onSuccess{(core,sha,rows)->
                    if(rows.isEmpty())NativeAlertDialog.Builder(activity).setMessage("Create a manual collection in Library / Collections first.").setPositiveButton("Close",null).show()
                    else NativeAlertDialog.Builder(activity).setTitle("Add to collection").setItems(rows.map{it.name}.toTypedArray()){_,index->
                        worker.execute{
                            val added=runCatching{
                                if(core!=null)LocalDatabase(activity.applicationContext).use{FusionSmartCollections(it).add(rows[index].id,core)}
                                else NexusRegistry(activity).addCollectionMember(rows[index].id,sha!!)
                            }
                            activity.runOnUiThread{
                                if(!activity.isFinishing&&!activity.isDestroyed)NativeAlertDialog.Builder(activity).setMessage(if(added.isSuccess)"Collection updated." else "Membership not saved.").setPositiveButton("Close",null).show()
                            }
                        }
                    }.setNegativeButton("Cancel",null).show()
                }
            }
        }
    }
}
