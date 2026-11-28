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
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.azrael.pixivdumpsync.SourceIndex
import com.azrael.pixivdumpsync.ApprovedCosplayActions
import com.azrael.pixivdumpsync.AuthenticatedItemDownloader
import com.azrael.pixivdumpsync.ResourceDownloads
import com.azrael.pixivdumpsync.SelectedVideoRouter
import com.azrael.pixivdumpsync.SourceIndexParser
import com.azrael.pixivdumpsync.IndexedItem
import com.azrael.pixivdumpsync.IndexedSource
import com.azrael.pixivdumpsync.SiteControls
import com.azrael.pixivdumpsync.AsterionContentSuggestions
import com.azrael.pixivdumpsync.RemoteImageLoader
import com.azrael.pixivdumpsync.CloudOriginalRouting
import com.azrael.pixivdumpsync.TeraBoxRemoteOriginals
import com.azrael.pixivdumpsync.ChapterQueue
import com.azrael.pixivdumpsync.ChapterArchives
import com.azrael.pixivdumpsync.ChapterScraper
import com.azrael.pixivdumpsync.NovelEpubService
import com.azrael.pixivdumpsync.NovelEpubParser
import androidx.compose.ui.viewinterop.AndroidView
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
    val folder:Boolean=false,
    val preview:String?=null,
    val area:String="",
    val kind:String="",
    val origin:String="",
    val filename:String?=null,
    val account:String="",
    val queued:Boolean=false
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
            if(!entry.preview.isNullOrBlank()){
                key(entry.preview,entry.web){
                    AndroidView(factory={context->android.widget.ImageView(context).apply{
                        scaleType=android.widget.ImageView.ScaleType.FIT_CENTER
                        contentDescription=entry.title
                        RemoteImageLoader.load(context,this,entry.preview,entry.web?:entry.origin)
                    }},modifier=Modifier.fillMaxWidth().height(170.dp))
                }
                Spacer(Modifier.height(9.dp))
            }
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
    onLibraryRefresh:()->Unit,
    onCoreRoute:(String)->Unit
){
    when(section){
        "EpisodeAssembly"->NexusEpisodeAssemblyPane(onLibraryRefresh)
        "Modules"->NexusAcquisitionCatalog(false,onLibraryRefresh)
        "Suggestions"->NexusAcquisitionCatalog(true,onLibraryRefresh)
        "Search"->NexusUnifiedSearch(onOpen,onLibraryRefresh)
        "Transfers"->NexusUnifiedTransfers(onOpen)
        "Asterion Bridges"->NexusPartnerPane()
        "Cloud"->NexusUnifiedCloud(onOpen,onLibraryRefresh)
        "Collections"->NexusCollectionsPane(onOpen,onCoreRoute)
        "CoreGallery","Organize","Activity","Taxonomy","Review","Intelligence"->
            com.ailm.android.ui.screens.NexusCorePane(section,onCoreRoute)
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
    var onlineMode by remember{mutableStateOf(0)}
    var status by remember{mutableStateOf("No query has been sent online.")}
    var loading by remember{mutableStateOf(false)}
    var hits by remember{mutableStateOf<List<NexusUnifiedEntry>>(emptyList())}
    var providerErrors by remember{mutableStateOf<List<String>>(emptyList())}
    var providerCount by remember{mutableIntStateOf(0)}
    var searchPage by remember{mutableIntStateOf(0)}
    var completedQuery by remember{mutableStateOf("")}
    var completedMode by remember{mutableIntStateOf(-1)}
    var providerFilter by remember{mutableStateOf("")}
    var moreOnline by remember{mutableStateOf(false)}
    var acquiring by remember{mutableStateOf(false)}
    var localPage by remember{mutableIntStateOf(0)}
    var localMore by remember{mutableStateOf(false)}
    var localQuery by remember{mutableStateOf("")}
    var localBusy by remember{mutableStateOf(false)}
    val generation=remember{AtomicInteger()}
    fun merge(rows:List<NexusUnifiedEntry>){hits=(hits+rows).distinctBy{if(it.web!=null)"web:"+it.web else it.account+":"+(it.path?:it.uri?.toString()?:it.title)+":"+it.chapter}}
    val cloudAccounts=remember(ctx){FgTeraboxClient.accounts(ctx)}
    val cloudIndexes=remember(ctx){cloudAccounts.mapNotNull{account->
        val cloud=runCatching{FgTeraboxClient.forScope(ctx,account.scope)}.getOrNull()?:return@mapNotNull null
        FgTeraboxSearchIndex(ctx,cloud,account.scope,object:FgTeraboxSearchIndex.Listener{
            override fun onSearch(raw:String){main.post{
                runCatching{
                    val response=JSONObject(raw)
                    if(response.optInt("token",-1)!=generation.get()||response.optString("scope")!=account.scope)return@runCatching
                    cloud.requireScope(account.scope)
                    val a=response.optJSONArray("items")?:JSONArray()
                    val rows=mutableListOf<NexusUnifiedEntry>()
                    for(i in 0 until a.length()){
                        val j=a.optJSONObject(i)?:continue
                        val path=j.optString("path")
                        if(!path.startsWith("/"))continue
                        val name=j.optString("name",path)
                        rows.add(NexusUnifiedEntry(name,"Cloud metadata",account.label,
                            mime=FgTeraboxClient.mime(name),path=path,
                            size=j.optLong("size",-1),folder=j.optBoolean("folder"),account=account.scope))
                    }
                    merge(rows)
                    status="Found "+hits.size+" matches"+if(response.optBoolean("building"))" · cloud index building" else ""
                }
            }}
            override fun onProgress(raw:String){}
        })
    }}
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
                    merge(rows)
                }
            }}
            override fun onProgress(raw:String){}
        })
    }
    DisposableEffect(cloudIndexes,safIndex){
        onDispose{generation.incrementAndGet();cloudIndexes.forEach{it.close()};safIndex.close()}
    }
    suspend fun providerPage(query:String,mode:Int,page:Int,token:Int){
        val response=withContext(Dispatchers.IO){AsterionSearchHub.searchForNexus(ctx as Activity,query,mode,page)}
        if(token!=generation.get())return
        providerCount=response.optInt("searched")
        val errors=response.optJSONArray("errors")?:JSONArray()
        providerErrors=(0 until errors.length()).map{errors.optString(it)}.filter{it.isNotBlank()}
        val a=response.optJSONArray("items")?:JSONArray()
        val incoming=(0 until a.length()).mapNotNull{i->
            val j=a.optJSONObject(i)?:return@mapNotNull null
            val url=j.optString("url")
            if(!url.startsWith("https://")&&!url.startsWith("magnet:"))return@mapNotNull null
            NexusUnifiedEntry(j.optString("title",url),j.optString("detail",""),
                j.optString("source","Web"),web=url,preview=j.optString("thumbnail").takeIf{it.isNotBlank()},
                area=when(mode){3->"GAMES";4->"BOOKS";else->"VIDEOS"},
                kind=j.optString("kind").ifBlank{if(mode==0)"video" else ""},
                filename=j.optString("filename").takeIf{it.isNotBlank()})
        }
        val prior=hits.mapNotNull{it.web}.toSet()
        moreOnline=mode==0&&page<1000&&incoming.any{it.web !in prior}
        hits=(hits+incoming).distinctBy{if(it.web!=null)"web:"+it.web else it.account+":"+(it.path?:it.uri?.toString()?:it.title)+":"+it.chapter}
        searchPage=page
        completedQuery=query;completedMode=mode
    }
    fun nextPage(){
        if(loading||!moreOnline||!online||term.trim()!=completedQuery||onlineMode!=completedMode)return
        val token=generation.get()
        loading=true
        scope.launch{
            try{providerPage(completedQuery,completedMode,searchPage+1,token);if(token==generation.get())status="Found "+hits.size+" matches · page "+(searchPage+1)}
            catch(t:Exception){if(token==generation.get())status="Next page unavailable: "+t.message.orEmpty().take(120)}
            finally{if(token==generation.get())loading=false}
        }
    }
    suspend fun localResults(query:String,page:Int,token:Int){
        val (rows,more)=withContext(Dispatchers.IO){
            val all=mutableListOf<NexusUnifiedEntry>()
            val registry=NexusRegistry(ctx)
            val registered=registry.searchPage(query,page*100,100)
            registered.forEach{all.add(NexusUnifiedEntry(it.displayName,"Verified original · "+it.mime,"Library",NexusLibraryProvider.uriFor(ctx,it.sha256),it.mime))}
            if(page==0)NexusBookIndex(ctx).search(query,70).forEach{
                val original=registry.find(it.sha256)
                if(original!=null)all.add(NexusUnifiedEntry(original.displayName,"Chapter "+(it.chapter+1)+" · "+it.excerpt,"Book",NexusLibraryProvider.uriFor(ctx,it.sha256),original.mime,it.chapter))
            }
            var galleryMore=false
            runCatching{
                StandaloneRuntime.initialize(ctx)
                val response=StandaloneRuntime.advancedSearch(mapOf("full_text" to query,"page" to page+1,"page_size" to 100,"sort_by" to "filename","sort_direction" to "asc"))
                val items=(response["items"] as? List<*>).orEmpty().filterIsInstance<Map<*,*>>()
                galleryMore=items.size==100
                items.forEach{item->
                    val uri=(item["uri"]?:item["image_uri"])?.toString().orEmpty()
                    if(uri.startsWith("content://"))all.add(NexusUnifiedEntry((item["filename"]?:item["name"])?.toString()?:"Indexed artwork","Indexed metadata","Gallery",Uri.parse(uri),"image/*"))
                }
            }
            val indexed=SourceIndex.nexusPage(ctx,query,offset=page*100,limit=100,unqueuedOnly=true)
            all.addAll(indexed.map{it.nexusEntry()})
            all to (registered.size==100||galleryMore||indexed.size==100)
        }
        if(token==generation.get()){merge(rows);localPage=page;localMore=more}
    }
    fun nextLocal(){
        if(localBusy||!localMore||term.trim()!=localQuery)return
        val token=generation.get();localBusy=true
        scope.launch{
            try{localResults(localQuery,localPage+1,token)}
            catch(e:Exception){if(token==generation.get())status="Local page unavailable: "+e.message.orEmpty().take(100)}
            finally{if(token==generation.get())localBusy=false}
        }
    }
    fun execute(){
        val query=term.trim()
        if(query.length !in 2..180){status="Enter 2–180 characters";return}
        val token=generation.incrementAndGet()
        loading=true;localBusy=true;hits=emptyList();providerErrors=emptyList();providerCount=0;searchPage=0;moreOnline=false;providerFilter="";completedQuery="";completedMode=-1;localPage=0;localMore=false;localQuery=query;status="Searching Nexus sources"
        val requestedMode=onlineMode
        val requestedOnline=online
        scope.launch{
            val failures=mutableListOf<String>()
            kotlinx.coroutines.supervisorScope{
                val local=launch{
                    try{localResults(query,0,token)}
                    catch(e:Exception){failures.add("Local: "+e.message.orEmpty().take(100))}
                    finally{if(token==generation.get())localBusy=false}
                }
                val remote=launch{
                    try{withContext(Dispatchers.IO){
                        for(tree in NexusSourceRegistry(ctx).roots()){safIndex.ensure(tree,false);safIndex.search(tree,query,"all",0,token)}
                        cloudIndexes.forEach{index->index.ensure(false);index.search(query,"all",0,token)}
                    }}catch(e:Exception){failures.add("Cloud/folders: "+e.message.orEmpty().take(100))}
                }
                val web=launch{
                    if(requestedOnline)try{providerPage(query,requestedMode,0,token)}
                    catch(e:Exception){failures.add("Providers: "+e.message.orEmpty().take(100))}
                }
                local.join();remote.join();web.join()
            }
            if(token==generation.get()){loading=false;status="Found "+hits.size+" matches"+if(failures.isEmpty())"" else " · "+failures.joinToString(" · ")}
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
        if(online)Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            listOf(0 to "Videos",3 to "Games",4 to "Books").forEach{(mode,label)->
                FilterChip(selected=onlineMode==mode,onClick={onlineMode=mode},label={Text(label)})
            }
        }
        Text(status,color=C_MUTED,fontSize=12.sp,modifier=Modifier.padding(vertical=11.dp))
        if(online&&(providerCount>0||providerErrors.isNotEmpty())){
            Text("$providerCount provider indexes responded · ${providerErrors.size} failures",color=C_MUTED,fontSize=12.sp)
            if(providerErrors.isNotEmpty()){
                var expanded by remember{mutableStateOf(false)}
                TextButton(onClick={expanded=!expanded}){Text(if(expanded)"Hide source failures" else "Show source failures")}
                if(expanded)Text(providerErrors.joinToString("\n"),color=C_MUTED,fontSize=11.sp,maxLines=15)
            }
        }
        if(hits.isNotEmpty()){
            val providers=hits.map{it.source}.distinct().sorted()
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                FilterChip(selected=providerFilter.isBlank(),onClick={providerFilter=""},label={Text("All sources")})
                providers.forEach{provider->FilterChip(selected=providerFilter==provider,onClick={providerFilter=provider},label={Text(provider)})}
            }
        }
        LazyColumn(verticalArrangement=Arrangement.spacedBy(9.dp)){
            items(hits.filter{providerFilter.isBlank()||it.source==providerFilter}){item->UnifiedEntryCard(item){
                if(item.web!=null){
                    if(!acquiring){
                        acquiring=true;status="Resolving chosen original"
                        scope.launch{
                            try{status=withContext(Dispatchers.IO){acquireNexusEntry(ctx as Activity,item)};onLibraryRefresh()}
                            catch(e:Exception){status="Original unavailable: "+e.message.orEmpty().take(150)}
                            finally{acquiring=false}
                        }
                    }
                }
                else if(item.path!=null){
                    if(!item.folder&&item.account.isNotBlank()&&item.size>=0)
                        onOpen(FgTeraboxDocumentsProvider.uriForAccount(item.account,item.path,item.size),
                            item.mime,item.title,item.chapter)
                    else status="Open cloud folders from Library / TeraBox"
                }else if(item.folder)status="Open authorized folders from Library / TeraBox"
                else if(item.uri!=null)onOpen(item.uri,item.mime,item.title,item.chapter)
            }}
            if(localMore&&term.trim()==localQuery)item{UnifiedButton("More indexed results",!localBusy){nextLocal()}}
            if(online&&moreOnline&&term.trim()==completedQuery&&onlineMode==completedMode)item{
                UnifiedButton(if(loading)"Loading…" else "Load next provider page",!loading){nextPage()}
            }
        }
    }
}


private fun IndexedItem.nexusEntry()=NexusUnifiedEntry(title,
    if(queued)"Already submitted to native engine" else kind,source,web=url,
    preview=preview,area=area,kind=kind,origin=source,queued=queued)

/** Every candidate either reaches an existing original resolver/queue or reports
 * why it cannot be submitted. No browser launch is used as a download result. */
private suspend fun acquireNexusEntry(activity:Activity,item:NexusUnifiedEntry):String{
    val url=requireNotNull(item.web){"No original source selected"}
    require(url.startsWith("https://")||url.startsWith("magnet:?",true)){"Only HTTPS or supported torrent originals"}
    check(com.azrael.pixivdumpsync.AppPower.enabled(activity)){"Resume downloads first"}
    check(NexusCloudPublication.canPublish(activity)){"Connect the same TeraBox browsing and upload account first"}
    val area=item.area.uppercase(java.util.Locale.ROOT)
    val source=item.origin.takeIf{SourceIndexParser.safe(it)}?:url
    val plan=NexusProviderDispatch.route(url,area,item.kind,item.filename,item.queued)
    when(plan){
        NexusProviderDispatch.Route.ALREADY_TRACKED->return "Already submitted · see Transfers for verified status"
        NexusProviderDispatch.Route.TORRENT->{
            val folder=when(area){"VIDEOS"->"Videos";"BOOKS"->"Books";"ASSETS","KOIKATSU"->"Assets";else->"Games"}
            withContext(Dispatchers.Main){ResourceDownloads.route(activity,url,folder)}
            return "Torrent file selection requested; transfer and security verification pending"
        }
        NexusProviderDispatch.Route.GALLERY->{
            val owner=source.takeIf{ApprovedCosplayActions.isGalleryPage(url)}
                ?:error("Choose the exact publisher gallery, not a preview")
            check(ApprovedCosplayActions.enqueueExactOriginal(activity,owner,url,item.title)){"Gallery original request not accepted"}
            return "Selected gallery queued · original count and verification pending"
        }
        NexusProviderDispatch.Route.BOOK_SERIES->{
            val created=NovelEpubService.enqueue(activity,url,item.title)
            return if(created)"Book archive requested; verification pending" else "Book already tracked"
        }
        NexusProviderDispatch.Route.COMIC_SERIES->{
            require(!ChapterScraper.isSingleChapter(url)){"Select the complete comic series, not one chapter"}
            ChapterQueue(activity).use{it.enqueue(url,item.title)}
            ChapterArchives.start(activity)
            return "Complete comic requested; verification pending"
        }
        NexusProviderDispatch.Route.NATIVE_VIDEO->{
            check(withContext(Dispatchers.Main){SelectedVideoRouter.request(activity,url,item.title,area)}){"Selected video route unavailable"}
            return "Native video selection requested; check Transfers for verified outcome"
        }
        NexusProviderDispatch.Route.WEB_VIDEO->{
            val result=NexusWebAcquisition.acquireSelected(activity,url)
            return NexusCloudPublication.status(activity,result)
        }
        NexusProviderDispatch.Route.PUBLIC_FILE->{
            check(CloudOriginalRouting.enqueue(activity,url,area,item.filename)){"Cloud request not accepted"}
            return "Remote original requested; verification pending"
        }
        NexusProviderDispatch.Route.AUTHORIZED_ITEM->{
            val indexed=IndexedItem(url,item.title,item.preview,source,area,item.kind,System.currentTimeMillis(),false)
            withContext(Dispatchers.Main){AuthenticatedItemDownloader.download(activity,indexed)}
            return "Native original resolution requested; access and verification pending"
        }
    }
}

@Composable
private fun NexusAcquisitionCatalog(suggestions:Boolean,onLibraryRefresh:()->Unit){
    val ctx=LocalContext.current as Activity
    val scope=rememberCoroutineScope()
    val modules=listOf("ARTWORK" to "Artwork","VIDEOS" to "Videos","BOOKS" to "Books",
        "COMICS" to "Comics","GAMES" to "Games","ASSETS" to "Assets",
        "CREATORS" to "Creators","COSPLAY" to "Gallery sources","X" to "Social sources",
        "KOIKATSU" to "Character assets")
    var area by remember{mutableStateOf(if(suggestions)"ALL" else "ARTWORK")}
    var rows by remember{mutableStateOf<List<NexusUnifiedEntry>>(emptyList())}
    var providers by remember{mutableStateOf<List<IndexedSource>>(emptyList())}
    var message by remember{mutableStateOf("Loading native index")}
    var busy by remember{mutableStateOf(false)}
    var acquiring by remember{mutableStateOf(false)}
    var refresh by remember{mutableIntStateOf(0)}
    var catalogQuery by remember{mutableStateOf("")}
    var catalogPage by remember{mutableIntStateOf(0)}
    var sourceFilter by remember{mutableStateOf<String?>(null)}
    var enrollment by remember{mutableStateOf(false)}
    var directImport by remember{mutableStateOf(false)}
    var following by remember{mutableStateOf(false)}
    val torrentFolder=when(area){"BOOKS"->"Books";"VIDEOS"->"Videos";"ASSETS","KOIKATSU"->"Assets";else->"Games"}
    val torrentPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){
            if(!NexusCloudPublication.canPublish(ctx))message="Connect TeraBox first"
            else if(!com.azrael.pixivdumpsync.AppPower.enabled(ctx))message="Resume downloads first"
            else ResourceDownloads.onTorrentPicked(ctx,uri,torrentFolder)
        }
    }
    LaunchedEffect(area,refresh,catalogQuery,catalogPage,sourceFilter){
        busy=true
        runCatching{withContext(Dispatchers.IO){
            if(suggestions){
                AsterionContentSuggestions.picks(ctx,area).map{
                    NexusUnifiedEntry(it.title,it.reason,it.source,web=it.url,
                        preview=it.preview,area=it.area,kind=it.kind,origin=it.source)
                } to emptyList<IndexedSource>()
            }else{
                SourceIndex.nexusPage(ctx,catalogQuery,area,sourceFilter,catalogPage*100,100).map{it.nexusEntry()} to SourceIndex.status(ctx,area)
            }
        }}.onSuccess{(entries,sources)->
            rows=entries;providers=sources;message=entries.size.toString()+" indexed candidates"
        }.onFailure{message="Index unavailable: "+it.javaClass.simpleName}
        busy=false
    }
    if(following){
        NexusFollowingPane(area,onBack={following=false;refresh++})
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal=16.dp,vertical=10.dp)){
        UnifiedHeading(if(suggestions)"Suggestions" else "Modules","Native source engines")
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            (if(suggestions)listOf("ALL" to "All")+modules else modules).forEach{(id,label)->
                FilterChip(selected=area==id,onClick={area=id;catalogPage=0;sourceFilter=null},label={Text(label)})
            }
        }
        Row(verticalAlignment=Alignment.CenterVertically){
            Text(message,color=C_MUTED,fontSize=12.sp,modifier=Modifier.weight(1f))
            UnifiedButton("Refresh",!busy){refresh++}
        }
        if(!suggestions){
            UnifiedField(catalogQuery,{catalogQuery=it;catalogPage=0},"Find indexed source media")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                UnifiedButton("Add source",!busy){enrollment=true}
                UnifiedButton("Manage sources",!busy){com.azrael.pixivdumpsync.SourceIndexPanel.showStatus(ctx,listOf(area),modules.firstOrNull{it.first==area}?.second?:area)}
            }
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                UnifiedButton("Add link",!acquiring){directImport=true}
                if(area in setOf("GAMES","BOOKS","VIDEOS","ASSETS","KOIKATSU"))UnifiedButton("Import .torrent",!acquiring){
                    torrentPicker.launch(arrayOf("application/x-bittorrent","application/octet-stream"))
                }
                if(area in setOf("ARTWORK","X","COMICS","COSPLAY","CREATORS"))UnifiedButton("Following"){following=true}
            }
            if(sourceFilter!=null)TextButton(onClick={sourceFilter=null;catalogPage=0}){Text("Clear selected source filter")}
        }
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){
            if(!suggestions&&catalogPage==0&&sourceFilter==null&&catalogQuery.isBlank())items(providers,key={it.url}){provider->
                var live by remember(provider.url,refresh){mutableStateOf(SiteControls.sync(ctx,provider.url))}
                Card(colors=CardDefaults.cardColors(containerColor=C_CARD)){
                    Column(Modifier.fillMaxWidth().padding(12.dp)){
                        Text(provider.name,color=C_TEXT,fontWeight=FontWeight.Medium)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            TextButton(onClick={sourceFilter=provider.url;catalogPage=0}){Text("Browse items")}
                            TextButton(onClick={ctx.startActivity(Intent(ctx,com.azrael.galleryflow.SiteLoginActivity::class.java).putExtra(com.azrael.galleryflow.SiteLoginActivity.EXTRA_URL,provider.url))}){Text("Account")}
                        }
                        Text(provider.discovered.toString()+" indexed · "+(provider.error?:
                            if(provider.historyComplete)"History complete" else "History not complete"),color=C_MUTED,fontSize=12.sp)
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Text("Live sync",color=C_TEXT,modifier=Modifier.weight(1f))
                            Switch(checked=live,onCheckedChange={enabled->
                                scope.launch{
                                    runCatching{withContext(Dispatchers.IO){
                                        SiteControls.setSync(ctx,provider.url,enabled)
                                        SiteControls.applyToEngines(ctx,provider.url)
                                    }}.onSuccess{live=enabled}
                                        .onFailure{message="Sync change failed"}
                                }
                            })
                            Spacer(Modifier.width(8.dp))
                            UnifiedButton("Backfill"){
                                scope.launch{
                                    runCatching{withContext(Dispatchers.IO){
                                        if(area=="COSPLAY")ApprovedCosplayActions.backfill(ctx,provider.url)
                                        else{SourceIndex.scanSourceNow(ctx,provider.url,true);"Backfill requested; refresh for actual status"}
                                    }}.onSuccess{message=it}
                                        .onFailure{message="Backfill request failed"}
                                }
                            }
                        }
                    }
                }
            }
            items(rows){entry->UnifiedEntryCard(entry){
                if(!acquiring){
                    acquiring=true;message="Resolving selected original"
                    scope.launch{
                        try{message=withContext(Dispatchers.IO){acquireNexusEntry(ctx,entry)};onLibraryRefresh()}
                        catch(e:Exception){message="Not acquired: "+e.message.orEmpty().take(150)}
                        finally{acquiring=false}
                    }
                }
            }}
            if(rows.isEmpty()&&!busy)item{Text("No indexed candidates",color=C_MUTED)}
            if(!suggestions)item{
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    UnifiedButton("Previous",!busy&&catalogPage>0){catalogPage--}
                    Text("Page "+(catalogPage+1),color=C_MUTED,modifier=Modifier.align(Alignment.CenterVertically))
                    UnifiedButton("Next",!busy&&rows.isNotEmpty()){catalogPage++}
                }
            }
        }
    }
    if(directImport){
        var address by remember{mutableStateOf("")}
        var name by remember{mutableStateOf("")}
        var error by remember{mutableStateOf("")}
        AlertDialog(onDismissRequest={directImport=false},title={Text("Acquire selected link")},
            text={Column{
                UnifiedField(address,{address=it},"HTTPS address or magnet")
                UnifiedField(name,{name=it},"Title (optional)")
                if(error.isNotBlank())Text(error,color=C_MUTED)
            }},
            confirmButton={TextButton(enabled=!acquiring,onClick={
                val url=address.trim()
                val isMagnet=url.startsWith("magnet:?",true)
                if(!isMagnet&&!SourceIndexParser.safe(url)){error="Enter a valid HTTPS address or magnet";return@TextButton}
                val kind=if(isMagnet)"torrent" else SourceIndexParser.kind(url,area)
                val label=name.trim().ifBlank{if(isMagnet)"Selected torrent" else Uri.parse(url).lastPathSegment.orEmpty().ifBlank{"Selected original"}}
                val entry=NexusUnifiedEntry(label,"Selected address","Direct",web=url,area=area,kind=kind,origin=url)
                directImport=false;acquiring=true;message="Resolving selected original"
                scope.launch{
                    try{message=withContext(Dispatchers.IO){acquireNexusEntry(ctx,entry)};onLibraryRefresh()}
                    catch(e:Exception){message="Original unavailable: "+e.message.orEmpty().take(150)}
                    finally{acquiring=false}
                }
            }){Text("Acquire")}},
            dismissButton={TextButton(onClick={directImport=false}){Text("Cancel")}})
    }
    if(enrollment){
        var address by remember{mutableStateOf("")}
        var name by remember{mutableStateOf("")}
        var error by remember{mutableStateOf("")}
        AlertDialog(onDismissRequest={enrollment=false},title={Text("Register source")},
            text={Column{
                UnifiedField(address,{address=it},"HTTPS source URL")
                UnifiedField(name,{name=it},"Source name")
                Text("Registering a source does not authorize bulk downloads.",color=C_MUTED,fontSize=12.sp)
                if(error.isNotBlank())Text(error,color=C_MUTED)
            }},
            confirmButton={TextButton(onClick={
                val url=address.trim()
                val label=name.trim()
                if(!SourceIndexParser.safe(url)||label.isBlank()||label.length>120){error="Enter a valid HTTPS address and name";return@TextButton}
                scope.launch{
                    runCatching{withContext(Dispatchers.IO){
                        SourceIndex.enroll(ctx,url,area,label)
                        check(SourceIndex.status(ctx,area).any{it.url==url}){"Source rejected or unavailable"}
                    }}.onSuccess{enrollment=false;refresh++;message="Source registered · configure sync separately"}
                        .onFailure{error=it.message.orEmpty().take(140)}
                }
            }){Text("Register")}},
            dismissButton={TextButton(onClick={enrollment=false}){Text("Cancel")}})
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
    var powered by remember{mutableStateOf(com.azrael.pixivdumpsync.AppPower.enabled(ctx))}
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
        Row(verticalAlignment=Alignment.CenterVertically){
            Text(if(powered)"Downloads enabled" else "Downloads paused",color=C_TEXT,modifier=Modifier.weight(1f))
            Switch(checked=powered,onCheckedChange={enabled->
                powered=enabled;scope.launch{runCatching{withContext(Dispatchers.IO){com.azrael.pixivdumpsync.AppPower.set(ctx,enabled)}}.onFailure{powered=com.azrael.pixivdumpsync.AppPower.enabled(ctx);status="Download setting unavailable"}}
            })
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

private val cloudPreviewSlots=java.util.concurrent.Semaphore(3,true)

private fun decodeCloudPreview(input:java.io.InputStream):Bitmap?{
    val data=java.io.ByteArrayOutputStream()
    val buffer=ByteArray(16384)
    while(true){
        val count=input.read(buffer)
        if(count<0)break
        if(count==0)continue
        if(data.size()+count>4*1024*1024)return null
        data.write(buffer,0,count)
    }
    val bytes=data.toByteArray()
    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    if(bounds.outWidth<=0||bounds.outHeight<=0)return null
    var sample=1
    while(bounds.outWidth/sample>800||bounds.outHeight/sample>1000)sample*=2
    return BitmapFactory.decodeByteArray(bytes,0,bytes.size,
        BitmapFactory.Options().apply{inSampleSize=sample})
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NexusCloudContentTile(
    entry:NexusUnifiedEntry,cloud:FgTeraboxClient?,accountScope:String,folder:String,page:Int,
    onOpen:()->Unit,onDetails:()->Unit
){
    val ctx=LocalContext.current
    val mime=entry.mime
    val visual=mime.startsWith("image/")||mime.startsWith("video/")||
        mime.startsWith("audio/")||mime.contains("pdf")||mime.contains("epub")||mime.contains("comic")
    val preview by produceState<Bitmap?>(null,accountScope,entry.path,entry.uri,entry.size){
        value=null
        if(!entry.folder&&visual)value=withContext(Dispatchers.IO){
            cloudPreviewSlots.acquire()
            try{
                runCatching{
                    if(entry.path!=null&&cloud!=null){
                        if(accountScope!=cloud.metadataScope())null
                        else cloud.openThumbnail(entry.path,folder,page).use{input->
                            val bitmap=decodeCloudPreview(input)
                            if(accountScope==cloud.metadataScope())bitmap else null
                        }
                    }else if(entry.uri!=null){
                        ctx.contentResolver.loadThumbnail(entry.uri,android.util.Size(640,840),null)
                    }else null
                }.getOrNull()
            }finally{cloudPreviewSlots.release()}
        }
    }
    val ratio=if(entry.folder)1.9f else preview?.let{
        (it.width.toFloat()/it.height.coerceAtLeast(1)).coerceIn(.68f,1.8f)
    }?:if(visual).85f else 2f
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
        .background(if(entry.folder)C_CARD else NexusVisual.imageBackdrop)
        .combinedClickable(onClick=onOpen,onLongClick=if(entry.folder)null else onDetails)){
        Box(Modifier.fillMaxWidth().aspectRatio(ratio),contentAlignment=Alignment.Center){
            if(preview!=null)Image(preview!!.asImageBitmap(),entry.title,
                Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
            else Text(when{
                entry.folder->"▱"
                mime.startsWith("video/")->"▶"
                mime.startsWith("audio/")->"♫"
                mime.startsWith("image/")->"Image"
                mime.contains("pdf")->"PDF"
                mime.contains("epub")->"EPUB"
                mime.contains("comic")->"CBZ"
                else->"File"
            },fontSize=if(entry.folder)30.sp else 15.sp,color=C_SILVER)
            if(preview!=null&&mime.startsWith("video/"))Text("▶",color=Color.White,
                fontSize=24.sp,modifier=Modifier.align(Alignment.BottomEnd)
                    .padding(8.dp).background(Color.Black.copy(alpha=.6f),RoundedCornerShape(7.dp))
                    .padding(horizontal=10.dp,vertical=4.dp))
        }
        Text(entry.title,color=C_TEXT,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis,
            modifier=Modifier.padding(horizontal=8.dp,vertical=6.dp))
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
    var accounts by remember{mutableStateOf(FgTeraboxClient.accounts(ctx))}
    var accountScope by remember{mutableStateOf("")}
    var accountLabel by remember{mutableStateOf("")}
    var publicationScope by remember{mutableStateOf(NexusCloudPublication.publicationScope(ctx))}
    var addingAccount by remember{mutableStateOf(false)}
    var connecting by remember{mutableStateOf(false)}
    var mediaFilter by remember{mutableStateOf("All")}
    var showSources by remember{mutableStateOf(!cloud.configured()||!NexusCloudPublication.canPublish(ctx))}
    var status by remember{mutableStateOf("Source bytes stay in their original location")}
    var folder by remember{mutableStateOf("/")}
    var page by remember{mutableIntStateOf(1)}
    var hasNextCloudPage by remember{mutableStateOf(false)}
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
        val path=folder;val current=page;val requestedScope=accountScope
        val chosen=accounts.filter{requestedScope.isEmpty()||it.scope==requestedScope}
        if(chosen.isEmpty()){rows=emptyList();return}
        scope.launch{
            status="Reading "+path
            runCatching{withContext(Dispatchers.IO){
                val found=mutableListOf<NexusUnifiedEntry>()
                val errors=mutableListOf<String>()
                var more=false
                for(account in chosen){
                    runCatching{
                        val client=FgTeraboxClient.forScope(ctx,account.scope)
                        val raw=client.listPageCached(path,current)
                        client.requireScope(account.scope)
                        if(raw.length()>=FgTeraboxClient.pageSize())more=true
                        for(i in 0 until raw.length()){
                            val j=FgTeraboxClient.mapEntry(raw.optJSONObject(i)?:continue)
                            val loc=j.optString("path")
                            if(!loc.startsWith("/"))continue
                            val name=j.optString("name",loc)
                            val isFolder=j.optBoolean("folder",j.optInt("isdir")!=0)
                            found.add(NexusUnifiedEntry(name,
                                if(isFolder)"Folder" else (j.optLong("size",0)/1024).toString()+" KiB",
                                account.label,mime=FgTeraboxClient.mime(name),
                                path=loc,size=j.optLong("size",-1),folder=isFolder,account=account.scope))
                        }
                    }.onFailure{errors.add(account.label+": "+it.javaClass.simpleName)}
                }
                Triple(found,errors,more)
            }}.onSuccess{(items,errors,more)->
                if(path==folder&&current==page&&requestedScope==accountScope){
                    rows=items;hasNextCloudPage=more
                    status=items.size.toString()+" files"+if(errors.isEmpty())"" else " · "+errors.joinToString(", ")
                }
            }.onFailure{status="Cloud unavailable: "+it.javaClass.simpleName}
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
                val nativeCloud=tree.authority==FgTeraboxDocumentsProvider.AUTHORITY
                val limit=if(nativeCloud)100 else 80
                val options=Bundle().apply{
                    putInt(ContentResolver.QUERY_ARG_OFFSET,offset)
                    putInt(ContentResolver.QUERY_ARG_LIMIT,limit)
                    // The pinned cloud DocumentsProvider consumes these native page hints.
                    // Keep standard SAF hints as well for third-party providers.
                    putInt(FgTeraboxDocumentsProvider.PAGE_OFFSET,offset)
                    putInt(FgTeraboxDocumentsProvider.PAGE_LIMIT,limit)
                }
                val cursor=ctx.contentResolver.query(child,cols,options,null)
                    ?:throw java.io.IOException("No provider response")
                cursor.use{
                    val ci=it.getColumnIndexOrThrow(cols[0])
                    val cn=it.getColumnIndexOrThrow(cols[1])
                    val cm=it.getColumnIndexOrThrow(cols[2])
                    val cs=it.getColumnIndex(cols[3])
                    while(it.moveToNext()&&result.size<limit){
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
    LaunchedEffect(accounts,accountScope,folder,page){refresh()}
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp,vertical=12.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Text(if(selectedTree==null)"TeraBox" else "Device folders",color=C_TEXT,
                fontSize=16.sp,modifier=Modifier.weight(1f))
            TextButton(onClick={showSources=!showSources}){Text("Sources")}
        }
        if(showSources&&accounts.isNotEmpty()){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                UnifiedButton("Add account",!connecting){addingAccount=!addingAccount}
            }
        }
        if(addingAccount){
            UnifiedField(accountLabel,{accountLabel=it},"Account label")
            UnifiedField(cookie,{cookie=it},"TeraBox session (ndus)",true)
            UnifiedButton("Connect account",!connecting&&accountLabel.isNotBlank()&&cookie.trim().length>=12){
                val token=cookie.trim();val label=accountLabel.trim();cookie="";connecting=true
                scope.launch{
                    runCatching{withContext(Dispatchers.IO){NexusCloudPublication.addAccount(ctx,label,token)}}
                        .onSuccess{accounts=FgTeraboxClient.accounts(ctx);accountScope="";folder="/";page=1;rows=emptyList();selected=null;addingAccount=false;accountLabel="";status="Account connected"}
                        .onFailure{status="Account connection failed: "+it.javaClass.simpleName}
                    connecting=false
                }
            }
        }
        if(!addingAccount&&(!connected||(showSources&&!NexusCloudPublication.canPublish(ctx)))){
            UnifiedField(cookie,{cookie=it},"TeraBox session (ndus)",true)
            UnifiedButton("Connect",cookie.trim().length>=12){
                val token=cookie.trim();cookie=""
                scope.launch{
                    status="Connecting to TeraBox"
                    runCatching{withContext(Dispatchers.IO){NexusCloudPublication.connect(ctx,cloud,token)}}
                        .onSuccess{
                            rows=emptyList();selected=null;folder="/";page=1
                            accounts=FgTeraboxClient.accounts(ctx);accountScope="";connected=true;showSources=false
                            publicationScope=NexusCloudPublication.publicationScope(ctx)
                            status="TeraBox connected"
                            refresh()
                        }.onFailure{status="TeraBox connection failed: "+it.javaClass.simpleName}
                }
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
        if(showSources)Row(Modifier.fillMaxWidth().padding(top=6.dp),
            verticalAlignment=Alignment.CenterVertically){
            Text("Authorized folders",color=C_TEXT,fontSize=15.sp,
                modifier=Modifier.weight(1f))
            UnifiedButton("Add source"){picker.launch(null)}
            UnifiedButton("Offline pins"){NexusCloudActions.pins(ctx as Activity)}
        }
        if(showSources&&accounts.isNotEmpty()){
            Text("Download destination",color=C_TEXT,fontSize=13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)){
                accounts.forEach{account->FilterChip(selected=publicationScope==account.scope,
                    enabled=!connecting&&NexusCloudPublication.hasUploadAccount(ctx,account.scope),
                    onClick={connecting=true;scope.launch{
                        runCatching{withContext(Dispatchers.IO){NexusCloudPublication.selectAccount(ctx,account.scope)}}
                            .onSuccess{publicationScope=account.scope;status="New acquisitions use "+account.label+"; previous account jobs retained"}
                            .onFailure{status="Destination not changed: "+it.javaClass.simpleName}
                        connecting=false
                    }},label={Text(account.label)})}
            }
        }
        if(selectedTree==null&&accounts.isNotEmpty())Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            FilterChip(selected=accountScope.isEmpty(),onClick={accountScope="";folder="/";page=1;rows=emptyList();selected=null},label={Text("All accounts")})
            accounts.forEach{account->FilterChip(selected=accountScope==account.scope,onClick={
                accountScope=account.scope;folder="/";page=1;rows=emptyList();selected=null
            },label={Text(account.label)})}
        }
        Text(status,color=C_MUTED,fontSize=12.sp,modifier=Modifier.padding(vertical=10.dp))
        if(selectedTree==null)Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement=Arrangement.spacedBy(5.dp)){
            listOf("All","Gallery","Videos","Books","Audio","Files").forEach{kind->
                FilterChip(selected=mediaFilter==kind,onClick={mediaFilter=kind},label={Text(kind,fontSize=11.sp)})
            }
        }
        LazyVerticalGrid(columns=GridCells.Adaptive(146.dp),
            modifier=Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement=Arrangement.spacedBy(5.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
            if(selectedTree!=null&&currentDocument!=null){
                item(span={GridItemSpan(maxLineSpan)}){
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
                    NexusCloudContentTile(entry,null,"","",0,onDetails={
                        entry.uri?.let{NexusFileDetails.show(ctx as Activity,it,entry.mime,entry.title)}
                    },onOpen={
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
                    })
                }
                if(sourceRows.size>=if(selectedTree?.authority==FgTeraboxDocumentsProvider.AUTHORITY)100 else 80)item(span={GridItemSpan(maxLineSpan)}){
                    UnifiedButton("More folder items"){
                        val tree=selectedTree;val doc=currentDocument
                        if(tree!=null&&doc!=null)browseTree(tree,doc,sourceOffset+if(tree.authority==FgTeraboxDocumentsProvider.AUTHORITY)100 else 80)
                    }
                }
            }else{
                if(showSources)items(sourceRoots){tree->
                    UnifiedEntryCard(NexusUnifiedEntry(
                        tree.lastPathSegment?:"Connected source",
                        tree.authority.orEmpty(),"Folder")){
                        val doc=runCatching{
                            DocumentsContract.getTreeDocumentId(tree)}.getOrNull()
                        if(doc!=null){treeHistory.clear();browseTree(tree,doc,0)}
                        else status="Source grant is unavailable"
                    }
                }
                val visibleRows=rows.filter{entry->
                    val m=entry.mime
                    val book=m.contains("pdf")||m.contains("epub")||m.contains("comic")||m.contains("wordprocessingml")
                    entry.folder||when(mediaFilter){
                        "Gallery"->m.startsWith("image/")
                        "Videos"->m.startsWith("video/")
                        "Books"->book
                        "Audio"->m.startsWith("audio/")
                        "Files"->!book&&!m.startsWith("image/")&&!m.startsWith("video/")&&!m.startsWith("audio/")
                        else->true
                    }
                }
                items(visibleRows,key={it.account+":"+it.path}){entry->
                    val entryClient=remember(entry.account){runCatching{FgTeraboxClient.forScope(ctx,entry.account)}.getOrNull()}
                    NexusCloudContentTile(entry,entryClient,entry.account,folder,page,onDetails={selected=entry},onOpen={
                        when{
                            entry.path!=null&&entry.folder->{accountScope=entry.account;folder=entry.path;page=1;rows=emptyList()}
                            entry.path!=null&&entryClient!=null&&entry.size>=0->
                                onOpen(FgTeraboxDocumentsProvider.uriForAccount(entry.account,entry.path,entry.size),entry.mime,entry.title,-1)
                            entry.uri!=null&&!entry.folder->onOpen(entry.uri,entry.mime,entry.title,-1)
                            else->status="Select the folder in the authorized source list"
                        }
                    })
                }
                if(hasNextCloudPage)item(span={GridItemSpan(maxLineSpan)}){
                    UnifiedButton("Next cloud page"){page++}
                }
            }
        }
    }
    selected?.let{entry->
        AlertDialog(onDismissRequest={selected=null},
            title={Text(entry.title)},
            text={Text("Open, view metadata or create a verified offline copy.")},
            confirmButton={
                TextButton(onClick={
                    selected=null
                    val path=entry.path?:return@TextButton
                    if(entry.size>=0)onOpen(FgTeraboxDocumentsProvider.uriForAccount(entry.account,path,entry.size),
                        entry.mime,entry.title,-1)
                }){Text("Open")}
            },
            dismissButton={
                TextButton(onClick={
                    selected=null
                    val path=entry.path?:return@TextButton
                    if(entry.size>=0)NexusFileDetails.show(ctx as Activity,
                        FgTeraboxDocumentsProvider.uriForAccount(entry.account,path,entry.size),entry.mime,entry.title)
                }){Text("Details")}
                TextButton(onClick={
                    selected=null
                    val path=entry.path?:return@TextButton
                    scope.launch{
                        status="Verifying selected offline pin"
                        runCatching{withContext(Dispatchers.IO){
                            NexusCloudOperations.pin(ctx,entry.account,path,entry.title,entry.mime,entry.size)
                        }}.onSuccess{status="Pinned: "+it.displayName;onRefresh()}
                            .onFailure{status="Offline pin incomplete: "+it.javaClass.simpleName}
                    }
                }){Text("Pin offline")}
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
