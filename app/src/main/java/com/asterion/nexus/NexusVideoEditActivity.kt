package com.asterion.nexus

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Native timeline editor. Millisecond boundaries, reordered clips, export profile and cancellation. */
class NexusVideoEditActivity:Activity(){
    companion object{
        fun open(context:Context,ids:List<String>,title:String,automatic:Boolean=false){
            context.startActivity(Intent(context,NexusVideoEditActivity::class.java)
                .putStringArrayListExtra("sources",ArrayList(ids)).putExtra("title",title).putExtra("automatic",automatic))
        }
    }
    private val worker=Executors.newSingleThreadExecutor()
    private val cancel=AtomicBoolean(false)
    private lateinit var timeline:LinearLayout
    private lateinit var status:TextView
    private lateinit var titleField:EditText
    private lateinit var exportButton:Button
    private lateinit var cancelButton:Button
    private lateinit var resolution:Spinner
    private lateinit var bitrate:Spinner
    private lateinit var codec:Spinner
    private lateinit var mute:CheckBox
    private val rows=mutableListOf<Row>()
    private data class Row(val source:NexusVideoExport.Source,var start:String="0",var end:String=source.duration.toString())
    private var running=false
    private var ready=false
    private var draftKey=""
    private var initialRows=emptyList<Row>()
    private fun saveDraft(){
        if(!ready||draftKey.isEmpty())return
        sync()
        val clips=org.json.JSONArray()
        rows.forEach{clips.put(org.json.JSONObject().put("sha",it.source.item.sha256).put("start",it.start).put("end",it.end))}
        val draft=org.json.JSONObject().put("clips",clips).put("title",titleField.text.toString())
            .put("resolution",resolution.selectedItemPosition).put("bitrate",bitrate.selectedItemPosition)
            .put("codec",codec.selectedItemPosition).put("mute",mute.isChecked)
        getSharedPreferences("nexus-video-drafts",MODE_PRIVATE).edit().putString(draftKey,draft.toString()).apply()
    }
    private fun restoreDraft(items:List<Row>):List<Row>{
        val raw=getSharedPreferences("nexus-video-drafts",MODE_PRIVATE).getString(draftKey,null)?:return items
        return runCatching{
            val d=org.json.JSONObject(raw)
            val a=d.getJSONArray("clips")
            require(a.length() in 1..100)
            val sources=items.associateBy{it.source.item.sha256}
            val restored=(0 until a.length()).map{i->
                val j=a.getJSONObject(i)
                val src=sources.getValue(j.getString("sha")).source
                Row(src,j.getString("start"),j.getString("end"))
            }
            titleField.setText(d.optString("title",titleField.text.toString()))
            resolution.setSelection(d.optInt("resolution").coerceIn(0,4))
            bitrate.setSelection(d.optInt("bitrate").coerceIn(0,3))
            codec.setSelection(d.optInt("codec").coerceIn(0,1))
            mute.isChecked=d.optBoolean("mute")
            restored
        }.getOrElse{items}
    }
    private lateinit var highlightButton:Button
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val ids=intent.getStringArrayListExtra("sources").orEmpty()
        if(ids.isEmpty()||ids.size>100){finish();return}
        draftKey=NexusOriginalStore.sourceKey(ids.joinToString(":")+":"+intent.getBooleanExtra("automatic",false))
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,16,16,16);setBackgroundColor(0xff101216.toInt())}
        fun heading(s:String)=TextView(this).apply{text=s;setTextColor(-1);textSize=16f}
        fun choices(values:List<String>)=Spinner(this).apply{adapter=ArrayAdapter(this@NexusVideoEditActivity,android.R.layout.simple_spinner_dropdown_item,values)}
        layout.addView(heading("Video timeline"))
        titleField=EditText(this).apply{hint="Output title";setSingleLine(true);setText(intent.getStringExtra("title").orEmpty().ifBlank{"Edited video"});setTextColor(-1)}
        layout.addView(titleField)
        layout.addView(Button(this).apply{text="Reset timeline draft";setOnClickListener{if(ready&&!running){android.app.AlertDialog.Builder(this@NexusVideoEditActivity).setMessage("Reset edits to the initial timeline?").setPositiveButton("Reset"){_,_->rows.clear();rows.addAll(initialRows.map{it.copy()});renderRows();saveDraft()}.setNegativeButton("Cancel",null).show()}}})
        timeline=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        layout.addView(ScrollView(this).apply{addView(timeline)},LinearLayout.LayoutParams(-1,0,1f))
        layout.addView(heading("Resolution · preserve aspect with padding"))
        resolution=choices(listOf("1280 × 720","1920 × 1080","1080 × 1920","3840 × 2160","854 × 480"));layout.addView(resolution)
        layout.addView(heading("Video bitrate"))
        bitrate=choices(listOf("4 Mbps","8 Mbps","16 Mbps","30 Mbps"));layout.addView(bitrate)
        codec=choices(listOf("H.264 / AAC MP4","HEVC / AAC MP4"));layout.addView(codec)
        mute=CheckBox(this).apply{text="Remove audio";setTextColor(-1)};layout.addView(mute)
        status=heading("Verifying sources…");layout.addView(status)
        exportButton=Button(this).apply{text="Export independent MP4";isEnabled=false;setOnClickListener{startExport()}}
        layout.addView(exportButton)
        highlightButton=Button(this).apply{text="Suggest motion montage";isEnabled=false;setOnClickListener{suggestHighlights()}}
        layout.addView(highlightButton)
        cancelButton=Button(this).apply{text="Cancel export";isEnabled=false;setOnClickListener{cancel.set(true);isEnabled=false}}
        layout.addView(cancelButton)
        setContentView(layout)
        worker.execute{
            val result=runCatching{
                val sources=ids.map{NexusVideoExport.source(applicationContext,it)}
                if(intent.getBooleanExtra("automatic",false)){
                    val plan=NexusEpisodeAssembly.transcodePlan(applicationContext,ids,sources.map{it.duration})
                    sources.zip(plan).map{(source,range)->Row(source,range[0].toString(),range[1].toString())}
                }else sources.map{Row(it)}
            }
            runOnUiThread{
                if(isDestroyed||isFinishing)return@runOnUiThread
                result.onSuccess{items->initialRows=items.map{it.copy()};rows.addAll(restoreDraft(items));renderRows();status.text=if(intent.getBooleanExtra("automatic",false))"Review conservative repeated-edge suggestions; uncertain footage retained" else "Trim and reorder clips";exportButton.isEnabled=true;ready=true;highlightButton.isEnabled=true}
                    .onFailure{status.text="Cannot edit: "+it.message}
            }
        }
    }
    private val fields=mutableListOf<Pair<EditText,EditText>>()
    private fun sync(){rows.zip(fields).forEach{(row,pair)->row.start=pair.first.text.toString();row.end=pair.second.text.toString()}}
    private fun renderRows(){
        fields.clear();timeline.removeAllViews()
        rows.forEachIndexed{i,row->
            val entry=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
            entry.addView(TextView(this).apply{text="${i+1}. ${row.source.item.displayName} · ${row.source.duration} ms";setTextColor(-1)})
            val times=LinearLayout(this)
            fun time(h:String,v:String)=EditText(this).apply{hint=h;setText(v);inputType=InputType.TYPE_CLASS_NUMBER;setTextColor(-1);isEnabled=!running}
            val a=time("Start ms",row.start);val b=time("End ms",row.end)
            fields.add(a to b);times.addView(a,LinearLayout.LayoutParams(0,-2,1f));times.addView(b,LinearLayout.LayoutParams(0,-2,1f));entry.addView(times)
            val controls=LinearLayout(this)
            fun action(label:String,enabled:Boolean,fn:()->Unit){controls.addView(Button(this).apply{text=label;isEnabled=enabled&&!running;setOnClickListener{sync();fn();renderRows()}},LinearLayout.LayoutParams(0,-2,1f))}
            action("↑",i>0){rows.add(i-1,rows.removeAt(i))}
            action("↓",i<rows.lastIndex){rows.add(i+1,rows.removeAt(i))}
            action("Duplicate",rows.size<100){rows.add(i+1,row.copy())}
            action("Remove",rows.size>1){rows.removeAt(i)}
            entry.addView(controls)
            entry.addView(Button(this).apply{text="Preview / mark clip";isEnabled=!running;setOnClickListener{sync();preview(row)}})
            timeline.addView(entry)
        }
    }
    private fun preview(row:Row){
        val source=row.source
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val video=VideoView(this)
        panel.addView(video,LinearLayout.LayoutParams(-1,(240*resources.displayMetrics.density).toInt()))
        val position=TextView(this)
        panel.addView(position)
        val seek=SeekBar(this).apply{max=1000;isEnabled=false}
        panel.addView(seek)
        val controls=LinearLayout(this)
        var start=row.start.toLongOrNull()?.coerceIn(0,source.duration-1)?:0L
        var end=row.end.toLongOrNull()?.coerceIn(start+1,source.duration)?:source.duration
        var prepared=false
        var dragging=false
        val handler=android.os.Handler(mainLooper)
        fun display(){position.text="Position: ${video.currentPosition} ms · clip: $start–$end ms"}
        val ticker=object:Runnable{
            override fun run(){
                if(prepared){
                    if(video.isPlaying&&video.currentPosition.toLong()>=end){video.pause();video.seekTo(start.toInt())}
                    if(!dragging)seek.progress=(video.currentPosition.toLong()*1000/source.duration).toInt().coerceIn(0,1000)
                    display()
                }
                handler.postDelayed(this,150)
            }
        }
        fun button(label:String,action:()->Unit){controls.addView(Button(this).apply{text=label;setOnClickListener{if(prepared)action()}},LinearLayout.LayoutParams(0,-2,1f))}
        button("Play / pause"){if(video.isPlaying)video.pause()else{if(video.currentPosition.toLong() !in start until end)video.seekTo(start.toInt());video.start()}}
        button("Mark in"){start=video.currentPosition.toLong().coerceIn(0,end-1);display()}
        button("Mark out"){end=video.currentPosition.toLong().coerceIn(start+1,source.duration);display()}
        panel.addView(controls)
        seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onStartTrackingTouch(bar:SeekBar){dragging=true;video.pause()}
            override fun onStopTrackingTouch(bar:SeekBar){dragging=false}
            override fun onProgressChanged(bar:SeekBar,value:Int,fromUser:Boolean){if(fromUser&&prepared)video.seekTo((source.duration*value/1000).toInt())}
        })
        val dialog=android.app.AlertDialog.Builder(this).setTitle(source.item.displayName).setView(panel)
            .setPositiveButton("Apply marks"){_,_->row.start=start.toString();row.end=end.toString();renderRows();saveDraft()}
            .setNegativeButton("Cancel",null).create()
        dialog.setOnDismissListener{handler.removeCallbacks(ticker);video.stopPlayback()}
        video.setOnPreparedListener{prepared=true;seek.isEnabled=true;video.seekTo(start.toInt());display()}
        video.setOnErrorListener{_,_,_->prepared=false;seek.isEnabled=false;position.text="Preview decoder unavailable; numerical trim remains available";true}
        dialog.show()
        video.setVideoURI(android.net.Uri.fromFile(NexusRegistry(this).original(source.item)))
        handler.post(ticker)
    }
    override fun onPause(){saveDraft();super.onPause()}
    private fun suggestHighlights(){
        if(running||!ready||rows.isEmpty())return
        sync()
        val snapshot=rows.map{it.copy()}
        val ranges=runCatching{snapshot.map{
            val start=it.start.toLong();val end=it.end.toLong()
            require(start>=0&&end>start&&end<=it.source.duration)
            NexusHighlightPlanner.Range(it.source,start,end)
        }}.getOrElse{status.text="Correct clip boundaries before suggesting a montage";return}
        val settings=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,12,24,12)}
        val length=EditText(this).apply{hint="Total seconds (5–300)";inputType=InputType.TYPE_CLASS_NUMBER;setText("60")}
        val pace=Spinner(this).apply{adapter=ArrayAdapter(this@NexusVideoEditActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Fast · 2 seconds","Balanced · 5 seconds","Slow · 10 seconds","Extended · 15 seconds"));setSelection(1)}
        settings.addView(length);settings.addView(pace)
        val dialog=android.app.AlertDialog.Builder(this).setTitle("Motion montage").setView(settings).setMessage("Analyze selected ranges and replace the timeline with suggested clips. Review before export.")
            .setPositiveButton("Analyze",null).setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener{
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                val seconds=length.text.toString().toLongOrNull()
                if(seconds==null||seconds !in 5L..300L){length.error="Enter 5–300 seconds";return@setOnClickListener}
                val clip=listOf(2000L,5000L,10000L,15000L)[pace.selectedItemPosition]
                dialog.dismiss();running=true;renderRows();exportButton.isEnabled=false;highlightButton.isEnabled=false
                status.text="Analyzing decoded frames across ${ranges.size} selected clips…"
                worker.execute{
                    val result=runCatching{NexusHighlightPlanner.suggestMontage(applicationContext,ranges,seconds*1000,clip)}
                    runOnUiThread{
                        if(isDestroyed||isFinishing)return@runOnUiThread
                        running=false
                        result.onSuccess{windows->
                            rows.clear();rows.addAll(windows.map{Row(snapshot[it.sourceIndex].source,it.window.start.toString(),it.window.end.toString())})
                            status.text="Motion-based montage suggested; review clip boundaries before export"
                        }.onFailure{status.text="Montage unavailable: "+it.message}
                        renderRows();saveDraft();exportButton.isEnabled=true;highlightButton.isEnabled=rows.isNotEmpty()
                    }
                }
            }
        }
        dialog.show()
    }
    private fun startExport(){
        sync()
        val clips=runCatching{rows.map{NexusVideoExport.Clip(it.source.item.sha256,it.start.toLong(),it.end.toLong())}}.getOrElse{status.text="Enter valid millisecond boundaries";return}
        val dims=listOf(1280 to 720,1920 to 1080,1080 to 1920,3840 to 2160,854 to 480)[resolution.selectedItemPosition]
        val profile=NexusVideoExport.Profile(dims.first,dims.second,listOf(4000000,8000000,16000000,30000000)[bitrate.selectedItemPosition],codec.selectedItemPosition==1,mute.isChecked)
        val name=titleField.text.toString()
        saveDraft()
        running=true;cancel.set(false);renderRows();exportButton.isEnabled=false;highlightButton.isEnabled=false;cancelButton.isEnabled=true
        status.text="Preparing export…"
        worker.execute{
            val result=runCatching{
                val item=NexusVideoExport.export(applicationContext,name,clips,profile,cancel){percent->runOnUiThread{if(!isDestroyed)status.text="Exporting · $percent%"}}
                NexusCloudPublication.enqueueIfConnected(applicationContext,item)
                item
            }
            runOnUiThread{
                if(isDestroyed||isFinishing)return@runOnUiThread
                running=false;renderRows();exportButton.isEnabled=true;highlightButton.isEnabled=rows.isNotEmpty();cancelButton.isEnabled=false
                result.onSuccess{item->
                    status.text=NexusCloudPublication.status(applicationContext,item)
                    android.app.AlertDialog.Builder(this).setTitle("Video output").setMessage(item.displayName+"\nOriginals retained.")
                        .setPositiveButton("Open"){_,_->NexusFileOpen.open(this,NexusLibraryProvider.uriFor(this,item.sha256),item.mime,item.displayName)}
                        .setNegativeButton("Close",null).show()
                }.onFailure{status.text="Export incomplete: "+it.message}
            }
        }
    }
    override fun onDestroy(){cancel.set(true);worker.shutdown();super.onDestroy()}
}
