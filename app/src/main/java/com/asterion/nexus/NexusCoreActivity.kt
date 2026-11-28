package com.asterion.nexus

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import com.ailm.android.runtime.StandaloneRuntime
import java.util.concurrent.Executors

/** Real native Core runtime in the unified Nexus package, initialized off the UI thread. */
class NexusCoreActivity : Activity() {
    private val worker=Executors.newSingleThreadExecutor()
    private lateinit var status:TextView
    private lateinit var content:LinearLayout
    private var ready=false
    private var page=0
    private val ink=0xfff4f5f7.toInt()
    private val muted=0xffa8b0bb.toInt()
    private val gold=0xffd4d9e1.toInt()
    private val background=0xff0e1013.toInt()
    private val panel=0xff1b1e23.toInt()
    private val pages=listOf("Library","Scan","Review","Tags","AI models","Health")
    private fun dp(value:Int)=(value*resources.displayMetrics.density+0.5f).toInt()
    private fun shape()=GradientDrawable().apply {
        setColor(panel);cornerRadius=dp(14).toFloat();setStroke(dp(1),0xff454a53.toInt())
    }
    private fun text(value:String,sp:Float=14f,title:Boolean=false)=TextView(this).apply {
        text=value;textSize=sp;setTextColor(if(title)ink else muted)
        if(title)typeface=Typeface.DEFAULT_BOLD
    }
    private fun line(top:Int=10)=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(top)}
    private fun button(label:String, action:()->Unit)=text(label,15f,true).apply {
        gravity=Gravity.CENTER;minHeight=dp(52);setPadding(dp(13),dp(13),dp(13),dp(13))
        background=shape();isFocusable=true;isClickable=true;contentDescription=label
        setOnClickListener{action()}
    }
    private fun execute(label:String,op:()->String){
        status.text=label
        worker.execute {
            try{
                if(!ready){StandaloneRuntime.initialize(applicationContext);ready=true}
                val output=op()
                runOnUiThread {
                    if(!isFinishing && !isDestroyed){
                        status.text=output
                        render()
                    }
                }
            }catch(t:Throwable){
                runOnUiThread {
                    if(!isFinishing && !isDestroyed)status.text=
                        "Core operation failed: "+t.javaClass.simpleName+": "+t.message.orEmpty().take(180)
                }
            }
        }
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.statusBarColor=background;window.navigationBarColor=background
        val scroll=ScrollView(this).apply{isFillViewport=true;setBackgroundColor(this@NexusCoreActivity.background)}
        val parent=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(20),dp(27),dp(20),dp(28))
        }
        scroll.addView(parent);setContentView(scroll)
        parent.addView(text("NEXUS / CORE",12f,true).apply{setTextColor(gold);letterSpacing=.15f})
        parent.addView(text("Native runtime",29f,true),line(12))
        val select=Spinner(this).apply {
            adapter=ArrayAdapter(this@NexusCoreActivity,
                android.R.layout.simple_spinner_dropdown_item,pages)
        }
        parent.addView(select,line(19))
        select.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,v:android.view.View?,pos:Int,id:Long){
                page=pos;render()
            }
        }
        status=text("Initializing native Core runtime",13f)
        parent.addView(status,line(19))
        parent.addView(button("Refresh",{render()}),line(12))
        content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        parent.addView(content,line(14))
        execute("Opening Fusion and Core",{
            val health=StandaloneRuntime.healthStatus()
            "Core initialized · "+health.keys.take(3).joinToString(", ")
        })
    }
    private fun render(){
        if(!::content.isInitialized)return
        content.removeAllViews()
        if(!ready){content.addView(text("Core is initializing",13f),line(8));return}
        when(page){
            0->showLibrary()
            1->showScan()
            2->showReviews()
            3->showTags()
            4->showModels()
            5->showHealth()
        }
    }
    private fun heading(value:String){content.addView(text(value,19f,true),line(18))}
    private fun row(name:String,details:String,action:(()->Unit)?=null){
        val card=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(15),dp(15),dp(15),dp(15))
            background=shape();minimumHeight=dp(67)
        }
        card.addView(text(name,15f,true))
        if(details.isNotBlank())card.addView(text(details,12f),line(6))
        if(action!=null){card.isClickable=true;card.isFocusable=true;card.setOnClickListener{action()}}
        content.addView(card,line(9))
    }
    private fun showLibrary(){
        heading("Indexed Core images")
        executeList("Reading indexed Core images"){
            StandaloneRuntime.getLibraryImages(null,1,100).map{entry->
                Triple(entry["filename"]?.toString()?:entry["name"]?.toString()?:"Indexed image",
                    entry.filterKeys{it in setOf("image_id","uri","tags","rating")}
                        .toString().take(180),entry["uri"]?.toString())
            }
        }
    }
    private fun showScan(){
        heading("Local image scanning")
        content.addView(button("Scan connected folder",{
            val roots=NexusSourceRegistry(this).roots()
            if(roots.isEmpty())status.text="Connect a source folder in Nexus first"
            else{
                val names=roots.map{(it.authority?:"Files")+" · "+it.lastPathSegment}.toTypedArray()
                AlertDialog.Builder(this).setTitle("Select image scan source")
                    .setItems(names){_,which->
                        execute("Starting Core scan"){StandaloneRuntime.startScan(roots[which].toString()).toString()}
                    }.show()
            }
        }),line(9))
        content.addView(button("Pause scan",{execute("Pausing"){StandaloneRuntime.pauseScan().toString()}}),line(9))
        content.addView(button("Resume scan",{execute("Resuming"){StandaloneRuntime.resumeScan().toString()}}),line(9))
        content.addView(button("Cancel scan",{AlertDialog.Builder(this@NexusCoreActivity)
            .setTitle("Cancel active scan?")
            .setMessage("Stop indexing without deleting originals.")
            .setNegativeButton("Keep scanning",null)
            .setPositiveButton("Cancel scan"){_,_->
                execute("Cancelling scan"){StandaloneRuntime.cancelScan().toString()}
            }.show()}),line(9))
        content.addView(button("Scan status",{
            execute("Reading scanner status"){StandaloneRuntime.scanStatus().toString()}
        }),line(9))
    }
    private fun showReviews(){
        heading("Core review queue")
        executeList("Reading review queue"){
            StandaloneRuntime.getReviewQueue().take(100).map{entry->
                Triple(entry["review_type"]?.toString()?:entry["type"]?.toString()?:"Review",
                    entry.filterKeys{key->key in setOf("image_id","reason","status","image_uri")}
                        .toString().take(210),entry["image_uri"]?.toString())
            }
        }
    }
    private fun showTags(){
        heading("Fusion tags")
        content.addView(button("Create owner tag",{
            val box=LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL
                setPadding(dp(16),dp(12),dp(16),dp(8))
            }
            val name=EditText(this).apply{hint="Tag label";isSingleLine=true}
            val family=EditText(this).apply{hint="Two-letter family ID";isSingleLine=true}
            box.addView(name);box.addView(family)
            AlertDialog.Builder(this).setTitle("Add Fusion tag").setView(box)
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Create"){_,_->
                    val label=name.text.toString().trim()
                    val code=family.text.toString().trim()
                    if(label.isNotBlank()&&code.length==2){
                        execute("Adding tag"){StandaloneRuntime.createNativeTag(label,code).toString()}
                    }else status.text="Tag name and family required"
                }.show()
        }),line(9))
        executeList("Reading native Fusion tags"){
            StandaloneRuntime.getTags().take(100).map{Triple(it,"","")}
        }
    }
    private fun showModels(){
        heading("Local AI models")
        content.addView(button("Pause local AI queue",{
            execute("Pausing AI queue"){StandaloneRuntime.pauseAiQueue().toString()}
        }),line(9))
        content.addView(button("Resume local AI queue",{
            execute("Resuming AI queue"){StandaloneRuntime.resumeAiQueue().toString()}
        }),line(9))
        executeList("Reading installed model status"){
            StandaloneRuntime.listInstalledAiModels().take(50).map{entry->
                Triple(entry["name"]?.toString()?:"Installed model",
                    entry.filterKeys{it in setOf("model_id","status","backend","version")}
                        .toString().take(200),"")
            }
        }
    }
    private fun showHealth(){
        heading("Core health and resources")
        content.addView(button("Read Core health",{
            execute("Checking resources"){StandaloneRuntime.healthStatus().toString().take(2500)}
        }),line(9))
        content.addView(button("Read AI hardware",{
            execute("Reading hardware"){StandaloneRuntime.detectAiHardwareProfile().toString().take(2500)}
        }),line(9))
    }
    private fun executeList(label:String, source:()->List<Triple<String,String,String?>>){
        content.addView(text("Loading",13f),line(10))
        worker.execute{
            try{
                val records=source()
                runOnUiThread{
                    if(isFinishing||isDestroyed||!::content.isInitialized)return@runOnUiThread
                    content.removeAllViews();heading(pages[page])
                    if(records.isEmpty())content.addView(text("No records",14f),line(10))
                    for((name,detail,uri) in records){
                        row(name,detail,if(uri!=null&&!uri.isBlank())({
                            runCatching{
                                NexusFileOpen.open(this,android.net.Uri.parse(uri),"image/*",name)
                            }.onFailure{status.text="Cannot open record: "+it.message}
                        }) else null)
                    }
                    status.text=records.size.toString()+" native records"
                }
            }catch(e:Exception){runOnUiThread{status.text=label+" failed: "+e.message}}
        }
    }
    override fun onDestroy(){worker.shutdownNow();super.onDestroy()}
}
