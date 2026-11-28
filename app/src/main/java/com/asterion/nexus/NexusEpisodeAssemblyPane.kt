package com.asterion.nexus

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

/** Contextual video operation; the three permanent destinations remain unchanged. */
@Composable
fun NexusEpisodeAssemblyPane(onRefresh:()->Unit){
    val context=LocalContext.current
    val app=context.applicationContext
    val scope=rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var videos by remember { mutableStateOf(emptyList<NexusRegistry.Item>()) }
    var page by remember { mutableIntStateOf(0) }
    val chosen=remember { mutableStateListOf<NexusRegistry.Item>() }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(query,page){
        videos=withContext(Dispatchers.IO){NexusRegistry(app).searchPage(query,page*100,100,"Videos").distinctBy{it.sha256}}
    }
    Column(Modifier.fillMaxSize().padding(12.dp)){
        Text("Assemble episodes",style=MaterialTheme.typography.titleLarge)
        Text("Select verified local episodes in playback order. Identical audio/video opening and ending segments shared by at least three episodes may be removed; the first opening, final ending and all uncertain footage are retained.")
        OutlinedTextField(title,{title=it},label={Text("Series title")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
        OutlinedTextField(query,{query=it;page=0},label={Text("Find local episodes")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
        Text("Selected: "+chosen.size+" · order of selection",modifier=Modifier.padding(vertical=4.dp))
        LazyColumn(Modifier.weight(1f)){
            items(chosen.toList(),key={"selected:"+it.sha256}){item->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    Text((chosen.indexOf(item)+1).toString()+". "+item.displayName,modifier=Modifier.weight(1f))
                    TextButton(enabled=!busy,onClick={
                        val i=chosen.indexOf(item);if(i>0){chosen.removeAt(i);chosen.add(i-1,item)}
                    }){Text("↑")}
                    TextButton(enabled=!busy,onClick={chosen.remove(item)}){Text("Remove")}
                }
            }
            items(videos.filter{v->chosen.none{it.sha256==v.sha256}},key={it.sha256}){item->
                TextButton(enabled=!busy&&chosen.size<100,onClick={chosen.add(item)}){Text("+ "+item.displayName)}
            }
            item{
                Row{
                    TextButton(enabled=!busy&&page>0,onClick={page--}){Text("Previous")}
                    TextButton(enabled=!busy&&videos.isNotEmpty(),onClick={page++}){Text("Next page")}
                }
            }
        }
        TextButton(enabled=!busy&&chosen.isNotEmpty(),onClick={
            NexusVideoEditActivity.open(context,chosen.map{it.sha256},title)
        },modifier=Modifier.fillMaxWidth()){Text("Edit timeline / transcode")}
        TextButton(enabled=!busy&&chosen.size>=2,onClick={
            NexusVideoEditActivity.open(context,chosen.map{it.sha256},title,true)
        },modifier=Modifier.fillMaxWidth()){Text("Prepare automatic assembly / transcode")}
        if(status.isNotBlank())Text(status)
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(enabled=!busy&&title.isNotBlank()&&chosen.size>=2,onClick={
            val ids=chosen.map{it.sha256};val name=title
            busy=true;status="Verifying and assembling originals…"
            scope.launch{
                try{
                    status=withContext(Dispatchers.IO){
                        val item=NexusEpisodeAssembly.assemble(app,name,ids)
                        NexusCloudPublication.enqueueIfConnected(app,item)
                        NexusCloudPublication.status(app,item)
                    }
                    onRefresh()
                }catch(e:Exception){status="Assembly not completed: "+(e.message?:e.javaClass.simpleName)}
                finally{busy=false}
            }
        },modifier=Modifier.fillMaxWidth()){Text("Assemble MP4")}
    }
}
