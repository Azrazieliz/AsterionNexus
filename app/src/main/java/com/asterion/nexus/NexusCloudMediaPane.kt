package com.asterion.nexus

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
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
import androidx.compose.ui.viewinterop.AndroidView
import com.openlocal.office.core.FgTeraboxClient
import com.openlocal.office.core.FgTeraboxDocumentsProvider
import com.openlocal.office.core.FgTeraboxSearchIndex
import com.openlocal.office.core.ReaderCovers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

private data class CloudMedia(
    val scope:String,val name:String,val path:String,val parent:String,
    val size:Long,val modified:Long,val listingPage:Int
){
    val key get()=scope+":"+path
    val mime get()=FgTeraboxClient.mime(name)
    val uri get()=FgTeraboxDocumentsProvider.uriForAccount(scope,path,size)
}

@Composable
internal fun NexusCloudMediaPane(category:String,query:String,previewEnabled:Boolean,onOpen:(android.net.Uri,String,String,Int)->Unit){
    val ctx=LocalContext.current
    val main=remember{Handler(Looper.getMainLooper())}
    val accounts=remember(ctx){FgTeraboxClient.accounts(ctx)}
    var accountScope by remember{mutableStateOf("")}
    val pages=remember{mutableStateMapOf<String,List<CloudMedia>>()}
    val more=remember{mutableStateMapOf<String,Boolean>()}
    val pending=remember{mutableStateMapOf<String,Boolean>()}
    val building=remember{mutableStateMapOf<String,Boolean>()}
    val errors=remember{mutableStateMapOf<String,String>()}
    val token=remember{AtomicInteger()}
    val indexes=remember(ctx,accounts){accounts.mapNotNull{account->
        val client=runCatching{FgTeraboxClient.forScope(ctx,account.scope)}.getOrNull()?:return@mapNotNull null
        account.scope to FgTeraboxSearchIndex(ctx,client,account.scope,object:FgTeraboxSearchIndex.Listener{
            override fun onSearch(raw:String){main.post{
                runCatching{
                    val response=JSONObject(raw)
                    if(response.optInt("token")!=token.get()||response.optString("scope")!=account.scope)return@runCatching
                    client.requireScope(account.scope)
                    val rows=response.optJSONArray("items")
                    val items=buildList{
                        if(rows!=null)for(i in 0 until rows.length()){
                            val row=rows.optJSONObject(i)?:continue
                            val path=row.optString("path")
                            val size=row.optLong("size",-1)
                            if(!path.startsWith("/")||size<0||row.optBoolean("folder"))continue
                            add(CloudMedia(account.scope,row.optString("name",path),path,
                                row.optString("searchPath","/"),size,row.optLong("modified"),row.optInt("listingPage",1)))
                        }
                    }
                    pages[account.scope]=((if(response.optInt("offset")==0)emptyList() else pages[account.scope].orEmpty())+items).distinctBy{it.key}
                    more[account.scope]=response.optBoolean("hasMore")
                    building[account.scope]=response.optBoolean("building")
                    errors[account.scope]=response.optString("error")
                }
                if(runCatching{JSONObject(raw).optInt("token")==token.get()}.getOrDefault(false))pending[account.scope]=false
            }}
            override fun onProgress(raw:String){}
        })
    }.toMap()}
    DisposableEffect(indexes){onDispose{token.incrementAndGet();indexes.values.forEach{it.close()}}}
    val kind=when(category){"Books"->"books";"Videos"->"video";else->"assets"}
    fun load(reset:Boolean){
        val request=if(reset)token.incrementAndGet() else token.get()
        for((scope,index) in indexes){
            if(accountScope.isNotEmpty()&&scope!=accountScope)continue
            if(!reset&&(pending[scope]==true||more[scope]!=true))continue
            pending[scope]=true
            index.search(query,kind,if(reset)0 else pages[scope].orEmpty().size,request)
        }
    }
    LaunchedEffect(query,accountScope,category){
        token.incrementAndGet();pages.clear();pending.clear();more.clear();building.clear();errors.clear()
        delay(300)
        load(true)
        // Refresh the initial empty page while the existing incremental index is building.
        repeat(15){
            delay(2000)
            if(pages.values.any{it.isNotEmpty()}||building.values.none{it}||pending.values.any{it})return@LaunchedEffect
            load(true)
        }
    }
    val rows=accounts.flatMap{if(accountScope.isEmpty()||it.scope==accountScope)pages[it.scope].orEmpty() else emptyList()}
    val hasMore=more.any{(scope,value)->value&&(accountScope.isEmpty()||accountScope==scope)}
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
            var menu by remember{mutableStateOf(false)}
            Box(Modifier.weight(1f)){
                TextButton(onClick={menu=true}){Text(accounts.firstOrNull{it.scope==accountScope}?.label?:"All TeraBox accounts")}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
                    DropdownMenuItem(text={Text("All accounts")},onClick={accountScope="";menu=false})
                    accounts.forEach{a->DropdownMenuItem(text={Text(a.label)},onClick={accountScope=a.scope;menu=false})}
                }
            }
            TextButton(onClick={load(true)}){Text("Refresh")}
            if(hasMore)TextButton(onClick={load(false)},enabled=pending.values.none{it}){Text("More")}
        }
        if(building.values.any{it})Text("Indexing cloud metadata",color=NexusVisual.muted,modifier=Modifier.padding(horizontal=12.dp))
        if(errors.values.any{it.isNotBlank()})Text("One or more cloud accounts could not be indexed",color=NexusVisual.muted,modifier=Modifier.padding(horizontal=12.dp))
        if(rows.isEmpty()){
            Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){
                if(pending.values.any{it}||building.values.any{it})CircularProgressIndicator(color=NexusVisual.silver)
                else Text(if(accounts.isEmpty())"Connect TeraBox in Library" else "No indexed files in this category",color=NexusVisual.muted)
            }
        }else if(category=="Books"){
            val pager=rememberPagerState(pageCount={rows.size})
            LaunchedEffect(pager.settledPage,rows.size,hasMore){if(hasMore&&pager.settledPage>=rows.lastIndex-2)load(false)}
            HorizontalPager(state=pager,contentPadding=PaddingValues(horizontal=18.dp),pageSpacing=8.dp,
                modifier=Modifier.weight(1f).fillMaxWidth(),key={rows[it].key}){index->
                val item=rows[index]
                Column(Modifier.fillMaxSize().clickable{onOpen(item.uri,item.mime,item.name,-1)},horizontalAlignment=Alignment.CenterHorizontally){
                    Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){CloudPoster(item,true)}
                    Text(item.name,color=NexusVisual.text,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(8.dp))
                }
            }
        }else if(category=="Videos"){
            val pager=rememberPagerState(pageCount={rows.size})
            LaunchedEffect(pager.settledPage,rows.size,hasMore){if(hasMore&&pager.settledPage>=rows.lastIndex-2)load(false)}
            VerticalPager(state=pager,modifier=Modifier.weight(1f).fillMaxWidth(),key={rows[it].key}){index->
                val item=rows[index]
                val accessibility=ctx.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
                val active=previewEnabled&&!pager.isScrollInProgress&&index==pager.settledPage&&android.animation.ValueAnimator.areAnimatorsEnabled()&&accessibility?.isTouchExplorationEnabled!=true
                var player by remember(item.key){mutableStateOf<android.widget.VideoView?>(null)}
                var failed by remember(item.key,active){mutableStateOf(false)}
                Box(Modifier.fillMaxSize().background(NexusVisual.imageBackdrop)){
                    CloudPoster(item,false)
                    if(active&&!failed)AndroidView(factory={c->android.widget.VideoView(c).apply{
                        player=this
                        setOnPreparedListener{media->media.setVolume(0f,0f);media.isLooping=true;start()}
                        setOnErrorListener{_,_,_->failed=true;true}
                        setVideoURI(item.uri)
                    }},modifier=Modifier.fillMaxSize(),onReset=null,onRelease={v->v.stopPlayback();if(player===v)player=null},update={})
                    Box(Modifier.fillMaxSize().clickable{
                        val position=runCatching{player?.currentPosition?.toLong()?:0L}.getOrDefault(0L)
                        player?.pause()
                        NexusFileOpen.openAt(ctx as Activity,item.uri,item.mime,item.name,position)
                    })
                    Text(item.name,color=NexusVisual.text,modifier=Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(NexusVisual.panel).padding(16.dp))
                }
            }
        }else{
            LazyVerticalGrid(columns=GridCells.Adaptive(140.dp),modifier=Modifier.weight(1f),
                contentPadding=PaddingValues(8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                items(rows,key={it.key}){item->
                    Column(Modifier.fillMaxWidth().clickable{onOpen(item.uri,item.mime,item.name,-1)}){
                        Box(Modifier.fillMaxWidth().height(116.dp),contentAlignment=Alignment.Center){CloudPoster(item,false)}
                        Text(item.name,maxLines=2,overflow=TextOverflow.Ellipsis,color=NexusVisual.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudPoster(item:CloudMedia,book:Boolean){
    val ctx=LocalContext.current
    val bitmap by produceState<Bitmap?>(null,item.key,item.modified){
        value=withContext(Dispatchers.IO){
            runCatching{
                val client=FgTeraboxClient.forScope(ctx,item.scope)
                client.requireScope(item.scope)
                val result=if(book)ReaderCovers.open(ctx,item.uri,item.name,item.size,item.modified).use{BitmapFactory.decodeStream(it)}
                else if(item.mime.startsWith("image/")||item.mime.startsWith("video/"))client.openThumbnail(item.path,item.parent,item.listingPage).use{BitmapFactory.decodeStream(it)}
                else null
                client.requireScope(item.scope)
                result
            }.getOrNull()
        }
    }
    Box(Modifier.fillMaxSize().background(NexusVisual.imageBackdrop),contentAlignment=Alignment.Center){
        if(bitmap!=null)Image(bitmap!!.asImageBitmap(),item.name,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
        else Text(if(book)"Cover unavailable" else item.name.substringAfterLast('.', "FILE").uppercase(),color=NexusVisual.muted)
    }
}
