package com.asterion.nexus

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.Choreographer
import android.view.SurfaceView
import android.widget.*
import com.google.android.filament.IndirectLight
import com.google.android.filament.Skybox
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors

/** Self-contained GLB preview; no network resource resolution or source mutation. */
class NexusGltfActivity : Activity() {
    companion object {
        @JvmStatic fun supports(mime:String?,name:String?):Boolean =
            mime=="model/gltf-binary" || name.orEmpty().lowercase(Locale.ROOT).endsWith(".glb")
    }
    private val worker=Executors.newSingleThreadExecutor()
    private lateinit var root:LinearLayout
    private lateinit var status:TextView
    private var viewer:ModelViewer?=null
    private var sky:Skybox?=null
    private var ambient:IndirectLight?=null
    private var active=false
    private var playing=true
    private var animation=0
    private var elapsed=0f
    private var previous=0L
    private var seek:SeekBar?=null
    private var updatingSeek=false
    private val callback=object:Choreographer.FrameCallback {
        override fun doFrame(now:Long) {
            if(!active)return
            val v=viewer?:return
            try {
                val animator=v.animator
                if(animator!=null && animator.animationCount>animation) {
                    val duration=animator.getAnimationDuration(animation)
                    if(playing && previous>0 && duration>0) elapsed=(elapsed+((now-previous)/1e9).toFloat().coerceIn(0f,.1f))%duration
                    animator.applyAnimation(animation,elapsed)
                    animator.updateBoneMatrices()
                    if(duration>0) {
                        updatingSeek=true
                        seek?.progress=(elapsed/duration*1000).toInt().coerceIn(0,1000)
                        updatingSeek=false
                    }
                }
                previous=now
                v.render(now)
                val label=if(v.progress<1f) "Loading model resources…" else if((animator?.animationCount?:0)>0) "Model · ${animator!!.animationCount} animation clips" else "Model"
                if(status.text.toString()!=label)status.text=label
                Choreographer.getInstance().postFrameCallback(this)
            } catch(e:Exception) {
                status.text="Rendering stopped: "+(e.message?:e.javaClass.simpleName)
                active=false
            }
        }
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val uri=intent.data?:run{finish();return}
        val name=intent.getStringExtra("title")?:"Model"
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xff101216.toInt())}
        val header=LinearLayout(this)
        header.addView(TextView(this).apply{text=name;setTextColor(-1);textSize=16f;maxLines=1},LinearLayout.LayoutParams(0,-2,1f))
        header.addView(Button(this).apply{text="Details";setOnClickListener{NexusFileDetails.show(this@NexusGltfActivity,uri,intent.type,name)}})
        root.addView(header)
        status=TextView(this).apply{text="Reading model…";setTextColor(0xffcbd1db.toInt());setPadding(16,12,16,12)}
        root.addView(status)
        setContentView(root)
        worker.execute {
            val result=runCatching {
                val bytes=contentResolver.openInputStream(uri)?.use { input ->
                    val out=ByteArrayOutputStream()
                    val buffer=ByteArray(131072)
                    while(true) {
                        check(!Thread.currentThread().isInterrupted){"Cancelled"}
                        val n=input.read(buffer);if(n<0)break
                        check(n<=NexusMesh.MAX_BYTES-out.size()){"Model exceeds bounded 48 MiB preview"}
                        out.write(buffer,0,n)
                    }
                    out.toByteArray()
                }?:error("Model unavailable")
                NexusGltfAdmission.verify(bytes)
                ByteBuffer.allocateDirect(bytes.size).apply{put(bytes);flip()}
            }
            runOnUiThread {
                if(isDestroyed||isFinishing)return@runOnUiThread
                result.onSuccess{buffer->runCatching{showModel(buffer)}.onFailure{status.text="Model unavailable: "+it.message}}
                    .onFailure{status.text="Model unavailable: "+it.message}
            }
        }
    }
    private fun showModel(buffer:ByteBuffer) {
        Utils.init()
        val surface=SurfaceView(this)
        val v=ModelViewer(surface)
        viewer=v
        root.addView(surface,LinearLayout.LayoutParams(-1,0,1f))
        surface.setOnTouchListener(v)
        surface.contentDescription="Interactive model. Drag to orbit and pinch to zoom."
        sky=Skybox.Builder().color(0.055f,0.065f,0.085f,1f).build(v.engine)
        v.scene.skybox=sky
        ambient=IndirectLight.Builder().irradiance(1,floatArrayOf(1f,1f,1f)).intensity(30000f).build(v.engine)
        v.scene.indirectLight=ambient
        v.loadModelGlb(buffer)
        check(v.asset!=null){"GLB decoder rejected model"}
        val extent=v.asset!!.boundingBox.halfExtent
        check(extent.all{it.isFinite()&&it>=0f}&&extent.maxOrNull()!!>0f){"Invalid model bounds"}
        v.transformToUnitCube()
        val animator=v.animator
        val count=animator?.animationCount?:0
        if(count>0 && animator!=null) {
            val names=(0 until count).map { i -> animator.getAnimationName(i).takeIf{!it.isNullOrBlank()}?:"Animation ${i+1}" }
            val selector=Spinner(this)
            selector.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,names)
            selector.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent:android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:android.view.View?,position:Int,id:Long) {
                    animation=position;elapsed=0f;previous=0
                }
            }
            root.addView(selector)
            seek=SeekBar(this).apply {
                max=1000;contentDescription="Animation position"
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(bar:SeekBar) {}
                    override fun onStopTrackingTouch(bar:SeekBar) {}
                    override fun onProgressChanged(bar:SeekBar,value:Int,fromUser:Boolean) {
                        if(fromUser&&!updatingSeek){elapsed=animator.getAnimationDuration(this@NexusGltfActivity.animation)*value/1000f;previous=0}
                    }
                })
            }
            root.addView(seek)
            root.addView(Button(this).apply{text="Pause";setOnClickListener{playing=!playing;previous=0;text=if(playing)"Pause" else "Play"}})
        }
        if(lifecycleActive)startFrames()
    }
    private var lifecycleActive=false
    private fun startFrames(){if(viewer!=null&&!active){active=true;previous=0;Choreographer.getInstance().postFrameCallback(callback)}}
    override fun onResume(){super.onResume();lifecycleActive=true;startFrames()}
    override fun onPause(){lifecycleActive=false;active=false;Choreographer.getInstance().removeFrameCallback(callback);super.onPause()}
    override fun onDestroy(){
        active=false;Choreographer.getInstance().removeFrameCallback(callback);worker.shutdownNow()
        viewer?.let{v->runCatching{
            v.scene.skybox=null;sky?.let{v.engine.destroySkybox(it)}
            v.scene.indirectLight=null;ambient?.let{v.engine.destroyIndirectLight(it)}
        }}
        // ModelViewer owns the engine and destroys it on SurfaceView detach.
        viewer=null;super.onDestroy()
    }
}
