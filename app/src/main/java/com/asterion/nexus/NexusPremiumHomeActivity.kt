package com.asterion.nexus

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.LruCache
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import com.asterion.videoflow.NexusDirectAcquisition
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/** Primary Nexus shell. All media cards are backed by real indexed originals. */
class NexusPremiumHomeActivity : ComponentActivity() {
    private val worker=Executors.newSingleThreadExecutor()
    private val queryHandler=Handler(Looper.getMainLooper())
    private var queryTask:Runnable?=null
    private val records=mutableStateListOf<NexusRegistry.Item>()
    private val generation=AtomicInteger()
    private var query by mutableStateOf("")
    private var error by mutableStateOf("")
    private var loading by mutableStateOf(false)
    private var hasMore by mutableStateOf(false)
    private var directDialog by mutableStateOf(false)
    private var category by mutableStateOf("All")
    private var activeSection by mutableStateOf("Cloud")
    private var previewEnabled by mutableStateOf(false)
    private val batch=80

    private val openDocument=registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ){uri:Uri?->
        if(uri!=null)importDocument(uri)
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        if(intent?.action==com.ailm.android.MainActivity.ACTION_OPEN_AUTOMATION)activeSection="Activity"
        window.statusBarColor=0xff0e1013.toInt()
        window.navigationBarColor=0xff0e1013.toInt()
        WindowCompat.setDecorFitsSystemWindows(window,false)
        setContent{
            NexusSkin {
                NexusHome(
                    records=records,
                    section=activeSection,
                    query=query,
                    onQuery={ value-> onQueryChanged(value) },
                    category=category,
                    onCategory={ selected -> category=selected;loadPage(reset=true) },
                    loading=loading,
                    hasMore=hasMore,
                    status=error,
                    onMore={loadPage(reset=false)},
                    onImport={openDocument.launch(arrayOf("*/*"))},
                    onAcquire={directDialog=true},
                    onOpen={item->
                        runCatching{
                            val uri=NexusLibraryProvider.uriFor(this,item.sha256)
                            NexusFileOpen.open(this,uri,item.mime,item.displayName)
                        }.onFailure{error="Cannot open file: "+it.javaClass.simpleName}
                    },
                    onRoute={activeSection=it},
                    previewEnabled=previewEnabled,
                    onOpenAt={item,position->
                        runCatching{NexusFileOpen.openAt(this,NexusLibraryProvider.uriFor(this,item.sha256),
                            item.mime,item.displayName,position)}.onFailure{error="Cannot open video: "+it.javaClass.simpleName}
                    },
                    onLibraryRefresh={loadPage(reset=true)},
                    onWorkspaceOpen={uri,mime,title,chapter->
                        runCatching{
                            if(chapter>=0){
                                startActivity(Intent(this,NexusReaderActivity::class.java)
                                    .setDataAndType(uri,mime)
                                    .putExtra("title",title).putExtra("chapter",chapter))
                            }else NexusFileOpen.open(this,uri,mime,title)
                        }.onFailure{error="Cannot open selection: "+it.javaClass.simpleName}
                    }
                )
                if(directDialog)NexusAcquireDialog(
                    onCancel={directDialog=false},
                    onConfirm={url->
                        directDialog=false
                        acquireUrl(url)
                    }
                )
            }
        }
        loadPage(reset=true)
        // Reuse Core's once-per-process bootstrap and deferred native model installation.
        // Do not block the first Library frame or automatically start media automation.
        com.ailm.android.startup.AppBootstrap.initializeApplication(applicationContext)
        lifecycleScope.launch {
            com.ailm.android.startup.AppBootstrap.awaitInitialization()
            if(com.ailm.android.startup.AppBootstrap.isRuntimeInitialized()){
                kotlinx.coroutines.delay(8000)
                com.ailm.android.runtime.StandaloneRuntime.beginDeferredBundledModelSetup()
                withContext(Dispatchers.IO){NexusBookIndex(applicationContext).retryBlocked()}
            }
        }
    }

    override fun onNewIntent(intent:Intent){
        super.onNewIntent(intent)
        setIntent(intent)
        if(intent.action==com.ailm.android.MainActivity.ACTION_OPEN_AUTOMATION)activeSection="Activity"
    }

    override fun onResume(){
        super.onResume()
        previewEnabled=true
        if(records.isNotEmpty())loadPage(reset=true)
    }

    override fun onPause(){previewEnabled=false;super.onPause()}

    /** Debounce library index queries: one SQLite lookup after typing, not every keypress. */
    private fun onQueryChanged(value:String){
        query=value
        queryTask?.let(queryHandler::removeCallbacks)
        generation.incrementAndGet() // stale async search cannot overwrite newer text
        loading=true
        val task=Runnable{loadPage(reset=true)}
        queryTask=task
        queryHandler.postDelayed(task,220L)
    }

    private fun loadPage(reset:Boolean){
        val version=generation.incrementAndGet()
        val needle=query
        val chosenCategory=category
        val offset=if(reset)0 else records.size
        loading=true
        worker.execute{
            try{
                val found=NexusRegistry(applicationContext).searchPage(needle,offset,batch,chosenCategory)
                runOnUiThread{
                    if(version!=generation.get()||isFinishing||isDestroyed)return@runOnUiThread
                    if(reset)records.clear()
                    records.addAll(found)
                    hasMore=found.size==batch
                    loading=false
                    error=records.size.toString()+" indexed originals loaded"
                }
            }catch(t:Exception){
                runOnUiThread{
                    if(version==generation.get()){
                        loading=false
                        error="Library index unavailable: "+t.javaClass.simpleName
                    }
                }
            }
        }
    }

    private fun importDocument(uri:Uri){
        error="Importing selected file"
        worker.execute{
            try{
                var name=uri.lastPathSegment?:"Selected original"
                var length=-1L
                val columns=arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
                contentResolver.query(uri,columns,null,null,null)?.use{cursor->
                    if(cursor.moveToFirst()){
                        val n=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s=cursor.getColumnIndex(OpenableColumns.SIZE)
                        if(n>=0&&!cursor.isNull(n))name=cursor.getString(n)
                        if(s>=0&&!cursor.isNull(s))length=cursor.getLong(s)
                    }
                }
                val result=contentResolver.openInputStream(uri)?.use{stream->
                    NexusRegistry(applicationContext).ingest(stream,name,
                        contentResolver.getType(uri),"document","device",
                        uri.toString(),length,null)
                }?:throw java.io.IOException("Selected file cannot be opened")
                runOnUiThread{
                    error="Indexed "+result.displayName
                    loadPage(reset=true)
                }
            }catch(e:Exception){
                runOnUiThread{error="Import failed: "+e.javaClass.simpleName+
                    ": "+e.message.orEmpty().take(120)}
            }
        }
    }

    private fun acquireUrl(url:String){
        error="Acquiring exact selected original"
        worker.execute{
            try{
                val result=NexusDirectAcquisition.acquire(applicationContext,url)
                runOnUiThread{
                    error=NexusCloudPublication.status(applicationContext,result)
                    loadPage(reset=true)
                }
            }catch(e:Exception){
                runOnUiThread{error="Acquisition failed: "+e.javaClass.simpleName+
                    ": "+e.message.orEmpty().take(120)}
            }
        }
    }
    override fun onDestroy(){
        queryTask?.let(queryHandler::removeCallbacks)
        worker.shutdownNow();super.onDestroy()
    }
}

private val CanvasColor=NexusVisual.canvas
private val PaneColor=NexusVisual.panel
private val BorderColor=NexusVisual.border
private val SilverColor=NexusVisual.silver
private val IvoryColor=NexusVisual.text
private val MutedColor=NexusVisual.muted

@Composable
private fun NexusSkin(content:@Composable ()->Unit){
    val palette=darkColorScheme(
        primary=SilverColor,onPrimary=CanvasColor,
        secondary=Color(0xFFC1C8D2),surface=CanvasColor,onSurface=IvoryColor,
        surfaceVariant=PaneColor,onSurfaceVariant=MutedColor,
        outline=BorderColor,background=CanvasColor,onBackground=IvoryColor
    )
    MaterialTheme(colorScheme=palette,typography=Typography(),content=content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NexusHome(
    records:List<NexusRegistry.Item>,section:String,query:String,onQuery:(String)->Unit,
    category:String,onCategory:(String)->Unit,loading:Boolean,hasMore:Boolean,status:String,
    onMore:()->Unit,onImport:()->Unit,onAcquire:()->Unit,
    onOpen:(NexusRegistry.Item)->Unit,onRoute:(String)->Unit,
    previewEnabled:Boolean,onOpenAt:(NexusRegistry.Item,Long)->Unit,
    onLibraryRefresh:()->Unit,
    onWorkspaceOpen:(Uri,String,String,Int)->Unit
){
    val activity=LocalContext.current as Activity
    val destinations=listOf("Library","Organising","Downloading")
    val contexts=mapOf(
        "Library" to listOf("TeraBox","Gallery","Books","Videos","Collections","Assets/Files"),
        "Organising" to listOf("Activity","Taxonomy","Review","Asterion Bridges"),
        "Downloading" to listOf("Modules","Search","Suggestions","Transfers")
    )
    var libraryContext by rememberSaveable { mutableStateOf("TeraBox") }
    var organisingContext by rememberSaveable { mutableStateOf("Activity") }
    var downloadingContext by rememberSaveable { mutableStateOf("Search") }
    var showMore by remember { mutableStateOf(false) }
    var cloudMedia by rememberSaveable { mutableStateOf(true) }
    val destination=when(section){
        "Organize","Intelligence","Activity","Taxonomy","Review","Asterion Bridges"->"Organising"
        "Modules","Search","Suggestions","Transfers"->"Downloading"
        else->"Library"
    }
    val context=when(destination){
        "Organising"->organisingContext
        "Downloading"->downloadingContext
        else->libraryContext
    }
    fun select(target:String,choice:String){
        when(target){
            "Library"->{
                libraryContext=choice
                if(choice=="TeraBox")onRoute("Cloud")
                else if(choice=="Gallery")onRoute("CoreGallery")
                else if(choice=="Collections")onRoute("Collections")
                else{
                    onCategory(when(choice){
                        "Gallery"->"Images"
                        "Assets/Files"->"Assets"
                        else->choice
                    })
                    onRoute("Library")
                }
            }
            "Organising"->{organisingContext=choice;onRoute(choice)}
            "Downloading"->{downloadingContext=choice;onRoute(choice)}
        }
    }
    Column(Modifier.fillMaxSize().background(CanvasColor).systemBarsPadding()){
            Column(Modifier.padding(horizontal=14.dp).padding(top=5.dp)){
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Text(destination,color=IvoryColor,fontSize=20.sp,
                        fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                    if(destination=="Library")TextButton(onClick=onImport){Text("Import")}
                    if(destination=="Library"&&context=="Videos")TextButton(onClick={onRoute("EpisodeAssembly")}){Text("Assemble")}
                    if(destination=="Downloading"&&context=="Search")
                        TextButton(onClick=onAcquire){Text("Direct URL")}
                    Box{
                        TextButton(onClick={showMore=true}){Text("⋮",fontSize=22.sp)}
                        DropdownMenu(expanded=showMore,onDismissRequest={showMore=false}){
                            if(destination=="Organising")DropdownMenuItem(text={Text("Cloud move history")},onClick={showMore=false;NexusCloudActions.history(activity)})
                            if(destination=="Library")DropdownMenuItem(text={Text("Offline pins")},onClick={showMore=false;NexusCloudActions.pins(activity)})
                            DropdownMenuItem(text={Text("Runtime diagnostics")},onClick={
                                showMore=false;onRoute("Intelligence")
                            })
                        }
                    }
                }
                val choices=contexts.getValue(destination)
                Row(Modifier.fillMaxWidth().pointerInput(destination,context){
                    var dx=0f
                    detectHorizontalDragGestures(onDragStart={dx=0f},onDragEnd={
                        val index=choices.indexOf(context)
                        val next=index+if(dx<0)1 else -1
                        if(kotlin.math.abs(dx)>64f&&next in choices.indices)
                            select(destination,choices[next])
                    }){change,amount->change.consume();dx+=amount}
                }.horizontalScroll(rememberScrollState()).padding(vertical=4.dp),
                    horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    choices.forEach{choice->
                        FilterChip(selected=context==choice,onClick={select(destination,choice)},
                            label={Text(choice,fontSize=12.sp)},
                            colors=FilterChipDefaults.filterChipColors(
                                selectedContainerColor=SilverColor,selectedLabelColor=CanvasColor,
                                containerColor=PaneColor,labelColor=MutedColor))
                    }
                }
                if(section=="Library"||section=="CoreGallery"){
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        FilterChip(selected=cloudMedia,onClick={cloudMedia=true},label={Text("TeraBox")})
                        FilterChip(selected=!cloudMedia,onClick={cloudMedia=false},label={Text("On device")})
                    }
                    OutlinedTextField(value=query,onValueChange=onQuery,singleLine=true,
                        placeholder={Text("Search "+context.lowercase(),fontSize=14.sp)},
                        modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(13.dp))
                    if(status.contains("failed",true)||status.contains("unavailable",true))
                        Text(status,color=MutedColor,fontSize=12.sp)
                }
            }
            if(section=="CoreGallery"&&cloudMedia){
                Box(Modifier.weight(1f).fillMaxWidth()){
                    NexusCloudMediaPane("Gallery",query,previewEnabled,onWorkspaceOpen)
                }
            }else if(section=="Library"&&cloudMedia&&category in listOf("Books","Videos","Assets")){
                Box(Modifier.weight(1f).fillMaxWidth()){
                    key(category){NexusCloudMediaPane(category,query,previewEnabled,onWorkspaceOpen)}
                }
            }else if(section=="Library"){
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()){
                val columns=when{
                    maxWidth>=1180.dp->5
                    maxWidth>=920.dp->4
                    maxWidth>=680.dp->3
                    else->2
                }
                val visible=records.filter{categoryMatch(it.mime,category)}
                // First visual original becomes the wide gallery feature; no
                // fake artwork, file duplication or alternate data source.
                val hero=visible.firstOrNull{
                    it.mime.startsWith("image/")||it.mime.startsWith("video/")
                }
                if(category=="Videos"&&visible.isNotEmpty()){
                    NexusVideoFeed(visible,previewEnabled,onOpenAt,hasMore,loading,onMore)
                }else if(category=="Books"&&visible.isNotEmpty()){
                    NexusBookCoverFlow(visible,onOpen,hasMore,loading,onMore)
                }else LazyVerticalGrid(
                    columns=GridCells.Fixed(columns),
                    modifier=Modifier.fillMaxSize(),
                    contentPadding=PaddingValues(start=3.dp,end=3.dp,top=3.dp,bottom=18.dp),
                    horizontalArrangement=Arrangement.spacedBy(4.dp),
                    verticalArrangement=Arrangement.spacedBy(4.dp)
                ){
                    items(visible,key={it.locationId},
                        span={item->
                            if(item.locationId==hero?.locationId&&columns<=3)
                                GridItemSpan(maxLineSpan)
                            else GridItemSpan(1)
                        }){item->
                        NexusContentCard(item,
                            featured=item.locationId==hero?.locationId&&columns<=3){
                            onOpen(item)
                        }
                    }
                    if(visible.isEmpty()){
                        item(span={GridItemSpan(maxLineSpan)}){
                            Column(Modifier.fillMaxWidth().padding(vertical=65.dp),
                                horizontalAlignment=Alignment.CenterHorizontally){
                                Text("No matching indexed files",fontSize=17.sp,color=IvoryColor)
                                Text("Import a file or select an original source",
                                    fontSize=13.sp,color=MutedColor,
                                    modifier=Modifier.padding(top=6.dp))
                            }
                        }
                    }
                    if(hasMore){
                        item(span={GridItemSpan(maxLineSpan)}){
                            OutlinedButton(onClick=onMore,
                                modifier=Modifier.fillMaxWidth().heightIn(min=50.dp),
                                enabled=!loading,
                                shape=RoundedCornerShape(14.dp)){
                                Text(if(loading)"Loading" else "Load more indexed items")
                            }
                        }
                    }
                }
            }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()){
                    NexusWorkspace(section=section,onOpen=onWorkspaceOpen,
                        onLibraryRefresh=onLibraryRefresh,onCoreRoute={target->
                            when(target){
                                "CoreGallery"->select("Library","Gallery")
                                "Collections"->select("Library","Collections")
                                "Cloud"->select("Library","TeraBox")
                                "Activity","Taxonomy","Review"->select("Organising",target)
                                "Intelligence"->onRoute("Intelligence")
                            }
                        })
                }
            }
            Surface(modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=6.dp),
                shape=RoundedCornerShape(24.dp),color=PaneColor,
                border=BorderStroke(1.dp,BorderColor)){
                NavigationBar(containerColor=Color.Transparent,windowInsets=WindowInsets(0,0,0,0)){
                    destinations.forEach{name->
                        NavigationBarItem(selected=destination==name,onClick={
                            select(name,when(name){
                                "Library"->libraryContext
                                "Organising"->organisingContext
                                else->downloadingContext
                            })
                        },icon={Text(navGlyph(name),fontSize=20.sp)},
                            label={Text(name,fontSize=11.sp)},
                            colors=NavigationBarItemDefaults.colors(
                                selectedIconColor=SilverColor,selectedTextColor=SilverColor,
                                unselectedIconColor=MutedColor,unselectedTextColor=MutedColor,
                                indicatorColor=CanvasColor))
                    }
                }
            }
    }
}

private fun navGlyph(name:String)=when(name){
    "Library"->"▦"
    "Search"->"⌕"
    "Transfers"->"⇵"
    "Cloud"->"◫"
    "Organising"->"▤"
    "Downloading"->"⇵"
    "More"->"☰"
    else->"◇"
}

private fun categoryMatch(mime:String,category:String):Boolean{
    val m=mime.lowercase()
    val book=m.contains("pdf")||m.contains("epub")||m.contains("comic")||
        m.contains("wordprocessingml")||m=="text/plain"
    return when(category){
        "Images"->m.startsWith("image/")
        "Videos"->m.startsWith("video/")
        "Books"->book
        "Audio"->m.startsWith("audio/")
        "Assets"->!m.startsWith("image/")&&!m.startsWith("video/")&&!book
        "Other"->!m.startsWith("image/")&&!m.startsWith("video/")&&
            !m.startsWith("audio/")&&!book
        else->true
    }
}

@Composable
private fun NexusVideoFeed(
    items:List<NexusRegistry.Item>,enabled:Boolean,onOpen:(NexusRegistry.Item,Long)->Unit,
    hasMore:Boolean,loading:Boolean,onMore:()->Unit
){
    val pager=rememberPagerState(pageCount={items.size})
    LaunchedEffect(pager.settledPage,items.size,hasMore,loading){
        if(hasMore&&!loading&&pager.settledPage>=items.lastIndex-2)onMore()
    }
    VerticalPager(state=pager,modifier=Modifier.fillMaxSize(),pageSpacing=6.dp,
        key={items[it].locationId}){index->
        val item=items[index]
        val context=LocalContext.current
        val accessibility=context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
            as? android.view.accessibility.AccessibilityManager
        val active=enabled&&!pager.isScrollInProgress&&index==pager.settledPage&&
            android.animation.ValueAnimator.areAnimatorsEnabled()&&
            accessibility?.isTouchExplorationEnabled!=true
        val poster by produceState<Bitmap?>(null,item.sha256){
            value=withContext(Dispatchers.IO){NexusContentThumbnail.load(context,item)}
        }
        var preview by remember(item.locationId){mutableStateOf<android.widget.VideoView?>(null)}
        var ready by remember(item.locationId,active){mutableStateOf(false)}
        var failed by remember(item.locationId,active){mutableStateOf(false)}
        val uri=remember(item.sha256){NexusLibraryProvider.uriFor(context,item.sha256)}
        Box(Modifier.fillMaxSize().background(NexusVisual.imageBackdrop)){
            if(poster!=null)Image(poster!!.asImageBitmap(),contentDescription=null,
                modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
            if(active&&!failed){
                AndroidView(factory={ctx->
                    android.widget.VideoView(ctx).apply{
                        preview=this
                        setOnPreparedListener{media->
                            media.setVolume(0f,0f)
                            media.isLooping=true
                            ready=true
                            start()
                        }
                        setOnErrorListener{_,_,_->failed=true;true}
                        setVideoURI(uri)
                    }
                },modifier=Modifier.fillMaxSize(),
                    onRelease={view->view.stopPlayback();if(preview===view)preview=null},
                    onReset=null,update={})
            }
            Box(Modifier.fillMaxSize().clickable{
                val position=runCatching{preview?.currentPosition?.toLong()?:0L}.getOrDefault(0L)
                preview?.pause()
                onOpen(item,position)
            }.semantics{contentDescription="Play "+item.displayName})
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha=0.74f)).padding(16.dp),
                verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){
                    Text(item.displayName,color=Color.White,fontSize=15.sp,maxLines=2,
                        overflow=TextOverflow.Ellipsis)
                    Text(if(ready&&!failed)"Muted preview" else "Open video",
                        color=SilverColor,fontSize=11.sp)
                }
                Text("▶",color=Color.White,fontSize=24.sp)
            }
        }
    }
}

@Composable
private fun NexusBookCoverFlow(
    items:List<NexusRegistry.Item>,onOpen:(NexusRegistry.Item)->Unit,
    hasMore:Boolean,loading:Boolean,onMore:()->Unit
){
    val pager=rememberPagerState(pageCount={items.size})
    LaunchedEffect(pager.settledPage,items.size,hasMore,loading){
        if(hasMore&&!loading&&pager.settledPage>=items.lastIndex-2)onMore()
    }
    Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally){
        HorizontalPager(state=pager,contentPadding=PaddingValues(horizontal=18.dp),
            pageSpacing=8.dp,modifier=Modifier.weight(1f).fillMaxWidth(),
            key={items[it].locationId}){index->
            val item=items[index]
            val ctx=LocalContext.current
            val preview by produceState<Bitmap?>(null,item.sha256){
                value=withContext(Dispatchers.IO){NexusContentThumbnail.load(ctx,item)}
            }
            Box(Modifier.fillMaxSize().padding(vertical=6.dp)
                .clip(RoundedCornerShape(8.dp)).background(NexusVisual.imageBackdrop)
                .clickable{onOpen(item)}.semantics{contentDescription="Open "+item.displayName},
                contentAlignment=Alignment.Center){
                if(preview!=null)Image(preview!!.asImageBitmap(),
                    contentDescription=item.displayName,modifier=Modifier.fillMaxSize(),
                    contentScale=ContentScale.Fit)
                else Column(horizontalAlignment=Alignment.CenterHorizontally,
                    modifier=Modifier.padding(24.dp)){
                    Text(item.displayName,color=IvoryColor,fontSize=20.sp)
                    Text("Cover unavailable",color=MutedColor,fontSize=12.sp)
                }
            }
        }
        Text(items.getOrNull(pager.currentPage)?.displayName.orEmpty(),color=IvoryColor,
            fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis,
            modifier=Modifier.padding(horizontal=16.dp,vertical=5.dp))
        Text((pager.currentPage+1).toString()+" / "+items.size,color=MutedColor,fontSize=11.sp)
    }
}

@Composable
private fun NexusContentCard(
    item:NexusRegistry.Item,featured:Boolean=false,onClick:()->Unit
){
    val context=LocalContext.current
    val thumbnail by produceState<Bitmap?>(null,item.sha256){
        value=withContext(Dispatchers.IO){
            NexusContentThumbnail.load(context,item)
        }
    }
    val image=item.mime.startsWith("image/")
    val video=item.mime.startsWith("video/")
    val visual=image||video
    val ratio=thumbnail?.let{
        val native=it.width.toFloat()/it.height.coerceAtLeast(1)
        native.coerceIn(if(featured)0.85f else 0.70f,
                        if(featured)2.2f else 1.25f)
    } ?: if(featured)1.62f else 0.82f
    Card(
        modifier=Modifier.fillMaxWidth().clickable(onClick=onClick)
            .semantics{contentDescription="Open "+item.displayName},
        shape=RoundedCornerShape(7.dp),
        colors=CardDefaults.cardColors(containerColor=PaneColor),
        border=BorderStroke(0.5.dp,BorderColor)
    ){
        Column{
            Box(
                Modifier.fillMaxWidth().aspectRatio(ratio)
                    .background(NexusVisual.imageBackdrop),
                contentAlignment=Alignment.Center
            ){
                if(thumbnail!=null){
                    Image(bitmap=thumbnail!!.asImageBitmap(),
                        contentDescription="Original thumbnail: "+item.displayName,
                        modifier=Modifier.fillMaxSize(),
                        contentScale=ContentScale.Fit)
                }else{
                    Text(
                        when {
                            video->"VIDEO"
                            image->"IMAGE"
                            item.mime.contains("pdf")->"PDF"
                            item.mime.contains("epub")->"EPUB"
                            item.mime.startsWith("audio/")->"AUDIO"
                            else->item.mime.substringBefore('/').uppercase()
                        },
                        color=SilverColor,fontSize=if(featured)16.sp else 12.sp,
                        fontWeight=FontWeight.Medium,letterSpacing=1.5.sp)
                }
                if(video){
                    Box(Modifier.align(Alignment.Center)
                        .background(Color.Black.copy(alpha=0.46f),
                            RoundedCornerShape(50))
                        .padding(horizontal=13.dp,vertical=7.dp)){
                        Text("▶",color=Color.White,fontSize=23.sp)
                    }
                    Text("VIDEO",color=Color.White,fontSize=10.sp,
                        modifier=Modifier.align(Alignment.BottomEnd).padding(7.dp)
                            .background(Color.Black.copy(alpha=0.70f),
                                RoundedCornerShape(5.dp))
                            .padding(horizontal=7.dp,vertical=4.dp))
                }
            }
            // File labels stay readable but never overwhelm the visual gallery.
            Column(Modifier.padding(horizontal=8.dp,vertical=7.dp)){
                Text(item.displayName,color=IvoryColor,fontSize=12.sp,
                    fontWeight=FontWeight.Medium,maxLines=1,
                    overflow=TextOverflow.Ellipsis)
                if(!visual){
                    Text(item.sourceKind+" · "+formatSize(item.length),
                        color=MutedColor,fontSize=11.sp,
                        modifier=Modifier.padding(top=3.dp),
                        maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun formatSize(bytes:Long):String=when{
    bytes>=1024L*1024L*1024L->"%.1f GiB".format(bytes/1073741824.0)
    bytes>=1024L*1024L->"%.1f MiB".format(bytes/1048576.0)
    else->(bytes/1024L).toString()+" KiB"
}

@Composable
private fun NexusAcquireDialog(onCancel:()->Unit,onConfirm:(String)->Unit){
    var url by remember{mutableStateOf("")}
    AlertDialog(
        onDismissRequest=onCancel,
        title={Text("Acquire selected original")},
        text={
            Column{
                Text("Direct HTTPS video file; complete bytes must be verified before library publication.",
                    color=MutedColor,fontSize=13.sp)
                OutlinedTextField(
                    value=url,onValueChange={url=it},
                    singleLine=true,label={Text("Original URL")},
                    modifier=Modifier.fillMaxWidth().padding(top=14.dp),
                    shape=RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton={
            TextButton(
                enabled=url.startsWith("https://",true)&&url.length<4096,
                onClick={onConfirm(url.trim())}
            ){Text("Acquire")}
        },
        dismissButton={TextButton(onClick=onCancel){Text("Cancel")}}
    )
}

private object NexusContentThumbnail {
    private val cache=object:LruCache<String,Bitmap>(20*1024*1024){
        override fun sizeOf(key:String,value:Bitmap)=value.byteCount
    }
    @Synchronized fun load(context:android.content.Context,item:NexusRegistry.Item):Bitmap?{
        if(!item.mime.startsWith("image/")&&!item.mime.startsWith("video/")&&!item.mime.startsWith("audio/")&&
            !item.mime.contains("pdf")&&!item.mime.contains("comic")&&!item.mime.contains("epub"))return null
        cache.get(item.sha256)?.let{return it}
        return runCatching{
            val file=NexusRegistry(context).original(item)
            val image=if(item.mime.startsWith("image/")){
                val opts=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                BitmapFactory.decodeFile(file.path,opts)
                val down=max(1,max(opts.outWidth/640,opts.outHeight/840))
                BitmapFactory.decodeFile(file.path,
                    BitmapFactory.Options().apply{inSampleSize=down})
            }else if(item.mime.startsWith("video/")){
                MediaMetadataRetriever().use{retriever->
                    retriever.setDataSource(file.path)
                    retriever.getScaledFrameAtTime(900000L,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,640,840)
                }
            }else if(item.mime.startsWith("audio/")){
                MediaMetadataRetriever().use{retriever->
                    retriever.setDataSource(file.path)
                    retriever.embeddedPicture?.takeIf{it.size<=16*1024*1024}?.let{data->
                        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                        BitmapFactory.decodeByteArray(data,0,data.size,bounds)
                        var sample=1
                        while(bounds.outWidth/sample>640||bounds.outHeight/sample>840)sample*=2
                        BitmapFactory.decodeByteArray(data,0,data.size,BitmapFactory.Options().apply{inSampleSize=sample})
                    }
                }
            }else if(item.mime.contains("epub")){
                com.openlocal.office.core.ReaderCovers.open(context,
                    NexusLibraryProvider.uriFor(context,item.sha256),item.displayName,item.length,0L).use{
                    BitmapFactory.decodeStream(it)
                }
            }else if(item.mime.contains("pdf")){
                val fd=android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                android.graphics.pdf.PdfRenderer(fd).use{renderer->
                    if(renderer.pageCount==0)null else renderer.openPage(0).use{page->
                        val scale=minOf(640f/page.width,840f/page.height)
                        Bitmap.createBitmap(max(1,(page.width*scale).toInt()),
                            max(1,(page.height*scale).toInt()),Bitmap.Config.ARGB_8888).also{bitmap->
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }else if(item.mime.contains("comic")){
                java.util.zip.ZipFile(file).use{zip->
                    val entry=zip.entries().asSequence().filter{!it.isDirectory&&it.size in 1..16777216L&&
                        it.name.substringAfterLast('.').lowercase() in setOf("jpg","jpeg","png","webp")}
                        .minWithOrNull(compareBy<java.util.zip.ZipEntry>{it.name.replace(Regex("\\d+")){m->m.value.padStart(16,'0')}})
                    entry?.let{chosen->
                        val opts=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                        zip.getInputStream(chosen).use{BitmapFactory.decodeStream(it,null,opts)}
                        val sample=max(1,max(opts.outWidth/640,opts.outHeight/840))
                        zip.getInputStream(chosen).use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample})}
                    }
                }
            }else null
            if(image!=null){
                val result=if(image.width>640||image.height>840){
                    val ratio=minOf(640f/image.width,840f/image.height)
                    Bitmap.createScaledBitmap(image,
                        max(1,(image.width*ratio).toInt()),
                        max(1,(image.height*ratio).toInt()),true).also{
                            if(it!==image)image.recycle()
                        }
                }else image
                cache.put(item.sha256,result);result
            }else null
        }.getOrNull()
    }
}
