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
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val ids=intent.getStringArrayListExtra("sources").orEmpty()
        if(ids.isEmpty()||ids.size>100){finish();return}
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,16,16,16);setBackgroundColor(0xff101216.toInt())}
        fun heading(s:String)=TextView(this).apply{text=s;setTextColor(-1);textSize=16f}
        fun choices(values:List<String>)=Spinner(this).apply{adapter=ArrayAdapter(this@NexusVideoEditActivity,android.R.layout.simple_spinner_dropdown_item,values)}
        layout.addView(heading("Video timeline"))
        titleField=EditText(this).apply{hint="Output title";setSingleLine(true);setText(intent.getStringExtra("title").orEmpty().ifBlank{"Edited video"});setTextColor(-1)}
        layout.addView(titleField)
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
                result.onSuccess{items->rows.addAll(items);renderRows();status.text=if(intent.getBooleanExtra("automatic",false))"Review conservative repeated-edge suggestions; uncertain footage retained" else "Trim and reorder clips";exportButton.isEnabled=true}
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
            entry.addView(controls);timeline.addView(entry)
        }
    }
    private fun startExport(){
        sync()
        val clips=runCatching{rows.map{NexusVideoExport.Clip(it.source.item.sha256,it.start.toLong(),it.end.toLong())}}.getOrElse{status.text="Enter valid millisecond boundaries";return}
        val dims=listOf(1280 to 720,1920 to 1080,1080 to 1920,3840 to 2160,854 to 480)[resolution.selectedItemPosition]
        val profile=NexusVideoExport.Profile(dims.first,dims.second,listOf(4000000,8000000,16000000,30000000)[bitrate.selectedItemPosition],codec.selectedItemPosition==1,mute.isChecked)
        val name=titleField.text.toString()
        running=true;cancel.set(false);renderRows();exportButton.isEnabled=false;cancelButton.isEnabled=true
        status.text="Preparing export…"
        worker.execute{
            val result=runCatching{
                val item=NexusVideoExport.export(applicationContext,name,clips,profile,cancel){percent->runOnUiThread{if(!isDestroyed)status.text="Exporting · $percent%"}}
                NexusCloudPublication.enqueueIfConnected(applicationContext,item)
                item
            }
            runOnUiThread{
                if(isDestroyed||isFinishing)return@runOnUiThread
                running=false;renderRows();exportButton.isEnabled=true;cancelButton.isEnabled=false
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
