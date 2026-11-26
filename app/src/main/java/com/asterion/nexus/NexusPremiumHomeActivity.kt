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
    private var activeSection by mutableStateOf("Library")
    private val batch=80

    private val openDocument=registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ){uri:Uri?->
        if(uri!=null)importDocument(uri)
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
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
                    onCategory={category=it},
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
    }

    override fun onResume(){
        super.onResume()
        if(records.isNotEmpty())loadPage(reset=true)
    }

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
        val offset=if(reset)0 else records.size
        loading=true
        worker.execute{
            try{
                val found=NexusRegistry(applicationContext).searchPage(needle,offset,batch)
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
                    error="Verified and indexed "+result.displayName
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

@Composable
private fun NexusHome(
    records:List<NexusRegistry.Item>,section:String,query:String,onQuery:(String)->Unit,
    category:String,onCategory:(String)->Unit,loading:Boolean,hasMore:Boolean,status:String,
    onMore:()->Unit,onImport:()->Unit,onAcquire:()->Unit,
    onOpen:(NexusRegistry.Item)->Unit,onRoute:(String)->Unit,
    onLibraryRefresh:()->Unit,
    onWorkspaceOpen:(Uri,String,String,Int)->Unit
){
    val configuration=androidx.compose.ui.platform.LocalConfiguration.current
    val landscape=configuration.screenWidthDp>=720
    val sections=listOf("Library","Search","Transfers","Cloud","Organize","Intelligence")
    Row(Modifier.fillMaxSize().background(CanvasColor).systemBarsPadding()){
        if(landscape)NavigationRail(containerColor=CanvasColor){
            Spacer(Modifier.height(20.dp))
            sections.forEachIndexed{index,name->
                NavigationRailItem(
                    selected=section==name,onClick={onRoute(name)},
                    icon={Text(navGlyph(name),fontSize=22.sp)},
                    label={Text(name,fontSize=11.sp)}
                )
            }
        }
        Column(Modifier.fillMaxSize()){
            Column(Modifier.padding(horizontal=20.dp).padding(top=18.dp)){
                Text("ASTERION",color=SilverColor,fontSize=11.sp,
                    fontWeight=FontWeight.Bold,letterSpacing=3.sp)
                Row(verticalAlignment=Alignment.CenterVertically,
                    modifier=Modifier.fillMaxWidth().padding(top=3.dp)){
                    Text("Nexus",color=IvoryColor,fontSize=33.sp,
                        fontWeight=FontWeight.SemiBold,letterSpacing=(-0.9f).sp,
                        modifier=Modifier.weight(1f))
                    TextButton(onClick=onImport){Text("Import",color=SilverColor)}
                    Spacer(Modifier.width(6.dp))
                    Button(onClick=onAcquire,colors=ButtonDefaults.buttonColors(containerColor=SilverColor),
                        shape=RoundedCornerShape(12.dp),
                        modifier=Modifier.heightIn(min=48.dp)){Text("Acquire",color=CanvasColor)}
                }
                if(section=="Library"){
                OutlinedTextField(
                    value=query,onValueChange=onQuery,singleLine=true,
                    label={Text("Search indexed files")},
                    modifier=Modifier.fillMaxWidth().padding(top=14.dp),
                    shape=RoundedCornerShape(16.dp),
                    colors=OutlinedTextFieldDefaults.colors(
                        focusedBorderColor=SilverColor,unfocusedBorderColor=BorderColor,
                        focusedTextColor=IvoryColor,unfocusedTextColor=IvoryColor,
                        focusedLabelColor=SilverColor,unfocusedLabelColor=MutedColor,
                        cursorColor=SilverColor
                    )
                )
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(top=15.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    listOf("All","Images","Videos","Books","Other").forEach{kind->
                        FilterChip(
                            selected=category==kind,onClick={onCategory(kind)},
                            label={Text(kind,fontSize=12.sp)},
                            colors=FilterChipDefaults.filterChipColors(
                                selectedContainerColor=SilverColor,
                                selectedLabelColor=CanvasColor,
                                containerColor=PaneColor,labelColor=MutedColor),
                            border=FilterChipDefaults.filterChipBorder(
                                enabled=true,selected=category==kind,
                                borderColor=BorderColor,selectedBorderColor=SilverColor)
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top=14.dp),
                    verticalAlignment=Alignment.CenterVertically){
                    Text("Library",fontWeight=FontWeight.SemiBold,fontSize=18.sp,
                        color=IvoryColor,modifier=Modifier.weight(1f))
                    Text(records.size.toString()+" loaded",fontSize=12.sp,color=MutedColor)
                }
                if(status.isNotBlank())Text(status,color=MutedColor,fontSize=12.sp,
                    modifier=Modifier.padding(top=6.dp).semantics{
                        contentDescription=status
                    })
                }
            }
            if(section=="Library"){
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()){
                val columns=when{
                    maxWidth>=1180.dp->5
                    maxWidth>=920.dp->4
                    maxWidth>=680.dp->3
                    else->2
                }
                val visible=records.filter{categoryMatch(it.mime,category)}
                LazyVerticalGrid(
                    columns=GridCells.Fixed(columns),
                    modifier=Modifier.fillMaxSize(),
                    contentPadding=PaddingValues(start=16.dp,end=16.dp,top=12.dp,bottom=20.dp),
                    horizontalArrangement=Arrangement.spacedBy(13.dp),
                    verticalArrangement=Arrangement.spacedBy(15.dp)
                ){
                    items(visible,key={it.locationId}){item->
                        NexusContentCard(item){onOpen(item)}
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
                        onLibraryRefresh=onLibraryRefresh)
                }
            }
            if(!landscape)NavigationBar(containerColor=PaneColor){
                sections.forEachIndexed{index,name->
                    NavigationBarItem(
                        selected=section==name,onClick={onRoute(name)},
                        icon={Text(navGlyph(name),fontSize=19.sp)},
                        label={Text(name,fontSize=10.sp)},
                        colors=NavigationBarItemDefaults.colors(
                            selectedIconColor=SilverColor,selectedTextColor=SilverColor,
                            unselectedIconColor=MutedColor,unselectedTextColor=MutedColor,
                            indicatorColor=CanvasColor)
                    )
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
    "Organize"->"▤"
    else->"◇"
}

private fun categoryMatch(mime:String,category:String):Boolean{
    val m=mime.lowercase()
    val book=m.contains("pdf")||m.contains("epub")||m.contains("comic")||
        m.contains("wordprocessingml")||m.endsWith("/zip")
    return when(category){
        "Images"->m.startsWith("image/")
        "Videos"->m.startsWith("video/")
        "Books"->book
        "Other"->!m.startsWith("image/")&&!m.startsWith("video/")&&!book
        else->true
    }
}

@Composable
private fun NexusContentCard(item:NexusRegistry.Item,onClick:()->Unit){
    val context=LocalContext.current
    val thumbnail by produceState<Bitmap?>(null,item.sha256){
        value=withContext(Dispatchers.IO){
            NexusContentThumbnail.load(context,item)
        }
    }
    Card(
        modifier=Modifier.fillMaxWidth().clickable(onClick=onClick)
            .semantics{contentDescription="Open "+item.displayName},
        shape=RoundedCornerShape(16.dp),
        colors=CardDefaults.cardColors(containerColor=PaneColor),
        border=BorderStroke(1.dp,BorderColor)
    ){
        Column{
            Box(
                Modifier.fillMaxWidth().aspectRatio(0.82f)
                    .background(NexusVisual.imageBackdrop),
                contentAlignment=Alignment.Center
            ){
                if(thumbnail!=null){
                    Image(bitmap=thumbnail!!.asImageBitmap(),
                        contentDescription="Original thumbnail: "+item.displayName,
                        modifier=Modifier.fillMaxSize(),
                        contentScale=ContentScale.Fit)
                }else{
                    Text(item.mime.substringBefore('/').uppercase(),
                        color=SilverColor,fontSize=12.sp,
                        fontWeight=FontWeight.Medium,letterSpacing=1.5.sp)
                }
            }
            Column(Modifier.padding(horizontal=12.dp, vertical=12.dp)){
                Text(item.displayName,color=IvoryColor,fontSize=13.sp,
                    fontWeight=FontWeight.Medium,maxLines=2,
                    overflow=TextOverflow.Ellipsis)
                Text(item.sourceKind+" · "+formatSize(item.length),
                    color=MutedColor,fontSize=11.sp,
                    modifier=Modifier.padding(top=6.dp),
                    maxLines=1,overflow=TextOverflow.Ellipsis)
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
        if(!item.mime.startsWith("image/")&&!item.mime.startsWith("video/"))return null
        cache.get(item.sha256)?.let{return it}
        return runCatching{
            val file=NexusRegistry(context).original(item)
            val image=if(item.mime.startsWith("image/")){
                val opts=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                BitmapFactory.decodeFile(file.path,opts)
                val down=max(1,max(opts.outWidth/640,opts.outHeight/840))
                BitmapFactory.decodeFile(file.path,
                    BitmapFactory.Options().apply{inSampleSize=down})
            }else{
                MediaMetadataRetriever().use{retriever->
                    retriever.setDataSource(file.path)
                    retriever.getFrameAtTime(900000L,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            }
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
