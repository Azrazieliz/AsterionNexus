package com.asterion.nexus

import android.app.Activity
import android.content.Intent
import android.widget.LinearLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.azrael.pixivdumpsync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Contextual following commands, using the existing donor stores and schedulers. */
@Composable
internal fun NexusFollowingPane(area:String,onBack:()->Unit){
    val a=LocalContext.current as Activity
    val scope=rememberCoroutineScope()
    var refresh by remember{mutableIntStateOf(0)}
    var message by remember{mutableStateOf("")}
    var query by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)}
    data class Subscription(val key:String,val title:String,val detail:String,val live:Boolean,
                            val toggle:(Boolean)->Unit,val backfill:()->Unit)
    var rows by remember{mutableStateOf<List<Subscription>>(emptyList())}
    fun runAction(action:()->Unit){
        if(busy)return
        busy=true
        scope.launch{
            try{withContext(Dispatchers.IO){action()};refresh++;message="Request accepted; check Transfers for the outcome"}
            catch(e:Exception){message=e.message.orEmpty().take(160)}
            finally{busy=false}
        }
    }
    fun pixivSync(mode:SyncMode,id:String?=null){
        check(AppPower.enabled(a)){"Resume downloads first"}
        check(SessionStore.isLoggedIn(a)){"Connect your Pixiv account first"}
        val intent=Intent(a,SyncForegroundService::class.java).setAction(SyncForegroundService.ACTION_START)
            .putExtra(SyncForegroundService.EXTRA_MODE,mode.name)
            .putExtra(SyncForegroundService.EXTRA_SELECTED_ONLY,id==null)
        if(id!=null)intent.putExtra(SyncForegroundService.EXTRA_TARGET_ARTIST_ID,id)
        a.startForegroundService(intent)
    }
    LaunchedEffect(area,refresh){
        if(area !in setOf("ARTWORK","X","COMICS"))return@LaunchedEffect
        runCatching{withContext(Dispatchers.IO){
            when(area){
                "ARTWORK"->AppDb(a).use{it.listArtists()}.map{artist->
                    Subscription(artist.userId,artist.label?:("Artist "+artist.userId),artist.lastError?:artist.archiveStatus,artist.selected&&artist.liveEnabled,
                        {enabled->AppDb(a).use{db->db.setArtistSelected(artist.userId,enabled);db.setArtistLiveEnabled(artist.userId,enabled)};if(enabled)Scheduler.ensure(a)},
                        {pixivSync(SyncMode.BACKFILL,artist.userId)})
                }
                "X"->XPhotoQueue(a).use{it.allSources()}.map{source->
                    Subscription(source.url,"@"+source.handle,source.error?:source.state,source.enabled&&source.liveEnabled,
                        {enabled->XPhotoQueue(a).use{it.setMode(source.url,"live_enabled",enabled)};XPhotoScheduler.ensure(a)},
                        {check(AppPower.enabled(a)){"Resume downloads first"};XPhotoEngine.startSourceBackfill(a,source.url)})
                }
                else->ChapterQueue(a).use{it.list()}.map{job->
                    Subscription(job.id.toString(),job.title,job.error?:job.state,job.syncEnabled,
                        {enabled->ChapterQueue(a).use{it.setSync(job.id,enabled)};if(enabled)ChapterRecovery.ensure(a)},
                        {check(AppPower.enabled(a)){"Resume downloads first"};ChapterQueue(a).use{it.retry(job.id)};ChapterArchives.start(a)})
                }
            }
        }}.onSuccess{rows=it}.onFailure{message="Following unavailable: "+it.message.orEmpty().take(120)}
    }
    BackHandler{onBack()}
    Column(Modifier.fillMaxSize().padding(12.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){
            TextButton(onClick=onBack){Text("Back")}
            Text("Following",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
            TextButton(onClick={refresh++}){Text("Refresh")}
        }
        if(area=="COSPLAY"||area=="CREATORS"){
            // Reuse the specialized creator/album controls in context, without a second launcher or queue.
            key(area,refresh){
                AndroidView(factory={context->LinearLayout(context).apply{
                    orientation=LinearLayout.VERTICAL
                    if(area=="COSPLAY")AsterionUnifiedFollowing.kyora(a,this,{refresh++},true)
                    else CreatorArchiveUi.render(a,this)
                }},modifier=Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()))
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
            if(area=="ARTWORK"){
                TextButton(onClick={a.startActivity(Intent(a,LoginActivity::class.java))}){Text("Account")}
                TextButton(enabled=!busy,onClick={runAction{pixivSync(SyncMode.LIVE)}}){Text("Sync new")}
                TextButton(enabled=!busy,onClick={runAction{pixivSync(SyncMode.BACKFILL)}}){Text("Backfill")}
            }else if(area=="X"){
                TextButton(onClick={a.startActivity(Intent(a,com.azrael.galleryflow.SiteLoginActivity::class.java).putExtra(com.azrael.galleryflow.SiteLoginActivity.EXTRA_URL,"https://x.com/"))}){Text("Account")}
                TextButton(onClick={a.startActivity(Intent(a,XFollowingImportActivity::class.java))}){Text("Import follows")}
                TextButton(enabled=!busy,onClick={runAction{check(AppPower.enabled(a));XPhotoScheduler.ensure(a);XPhotoEngine.start(a)}}){Text("Sync new")}
            }
        }
        OutlinedTextField(query,{query=it},label={Text(when(area){"ARTWORK"->"Artist ID / profile";"X"->"Handle / profile";else->"Complete comic series URL"})},modifier=Modifier.fillMaxWidth(),singleLine=true)
        Button(enabled=!busy&&query.isNotBlank(),onClick={
            val value=query.trim()
            runAction{
                check(AppPower.enabled(a)){"Resume downloads first"}
                check(NexusCloudPublication.canPublish(a)){"Connect TeraBox first"}
                when(area){
                    "ARTWORK"->{val ids=InputParser.parseUserIds(value);require(ids.isNotEmpty()){"Enter a valid artist ID"};AppDb(a).use{db->ids.forEach{db.addArtist(it)}}}
                    "X"->{val url=if(value.startsWith("@"))"https://x.com/"+value.drop(1) else if(value.matches(Regex("[A-Za-z0-9_]{1,15}")))"https://x.com/"+value else value;check(XPhotoQueue(a).use{it.addSource(url)}){"Invalid or already followed account"};XPhotoScheduler.ensure(a)}
                    else->{require(SourceIndexParser.safe(value)&&!ChapterScraper.isSingleChapter(value)){"Select the complete series"};val title=android.net.Uri.parse(value).lastPathSegment.orEmpty().replace('-',' ').ifBlank{"Comic"};check(ChapterQueue(a).use{it.enqueue(value,title)}){"Already followed"};ChapterArchives.start(a)}
                }
            }
        },modifier=Modifier.fillMaxWidth()){Text("Add")}
        if(message.isNotEmpty())Text(message,style=MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){
            items(rows,key={it.key}){row->
                Card(colors=CardDefaults.cardColors(containerColor=NexusVisual.panel)){
                    Column(Modifier.fillMaxWidth().padding(12.dp)){
                        Text(row.title,color=NexusVisual.text)
                        Text(row.detail,color=NexusVisual.muted,style=MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Text("Sync new",modifier=Modifier.weight(1f),color=NexusVisual.text)
                            Switch(checked=row.live,enabled=!busy,onCheckedChange={enabled->runAction{row.toggle(enabled)}})
                            TextButton(enabled=!busy,onClick={runAction{check(NexusCloudPublication.canPublish(a)){"Connect TeraBox first"};row.backfill()}}){Text("Backfill")}
                        }
                    }
                }
            }
            if(rows.isEmpty())item{Text("No followed sources",color=NexusVisual.muted)}
        }
    }
}
