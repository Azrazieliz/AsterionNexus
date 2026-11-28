package com.azrael.pixivdumpsync

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.SeekBar
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.asterion.nexus.*
import java.util.concurrent.atomic.AtomicBoolean

/** Exercises the actual platform codec and Filament renderer using original synthetic fixtures. */
internal object NexusMediaSimulation{
    fun run(instrumentation:Instrumentation,status:(String)->Unit){
        val context=instrumentation.targetContext
        val registry=NexusRegistry(context)
        fun ingest(name:String,mime:String):NexusRegistry.Item =
            instrumentation.context.assets.open("nexus-fixtures/"+name).use{input->
                registry.ingest(input,name,mime,"ci-media-fixture","emulator","fixture:"+name,-1,null)
            }
        val a=ingest("clip-a.mp4","video/mp4")
        val b=ingest("clip-b.mp4","video/mp4")
        NexusVideoInputs.begin(context)
        try{
            val clips=listOf(NexusVideoExport.Clip(a.sha256,250,1750),NexusVideoExport.Clip(b.sha256,0,1500))
            val profile=NexusVideoExport.Profile(640,360,1_000_000,false,false,true)
            val item=NexusVideoExport.export(context,"CI media montage",clips,profile,AtomicBoolean(false)){ }
            check(registry.verifyOriginal(item)&&registry.verifyOriginal(a)&&registry.verifyOriginal(b))
            check(registry.search("CI media montage",10).any{it.sha256==item.sha256})
            check(registry.related(item.sha256,10).map{it.sha256}.toSet()==setOf(a.sha256,b.sha256)){"Export provenance missing"}
            val again=NexusVideoExport.export(context,"CI media montage",clips,profile,AtomicBoolean(false)){ }
            check(again.sha256==item.sha256){"Repeated export changed the output identity"}
            status("MEDIA_EXPORT_OK "+item.sha256+"; two real decoded and transcoded sources")
        }finally{NexusVideoInputs.end(context)}
        val model=ingest("textured-animation.glb","model/gltf-binary")
        val uri=NexusLibraryProvider.uriFor(context,model.sha256)
        val activity=instrumentation.startActivitySync(Intent(context,NexusGltfActivity::class.java)
            .setDataAndType(uri,"model/gltf-binary").putExtra("title",model.displayName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try{
            fun descendants(v:View):Sequence<View> = sequence{
                yield(v)
                if(v is ViewGroup)for(i in 0 until v.childCount)yieldAll(descendants(v.getChildAt(i)))
            }
            var surface:TextureView?=null
            var animated=false
            val deadline=android.os.SystemClock.elapsedRealtime()+45000
            while(android.os.SystemClock.elapsedRealtime()<deadline&&!animated){
                instrumentation.runOnMainSync{
                    val views=descendants(activity.window.decorView).toList()
                    surface=views.filterIsInstance<TextureView>().firstOrNull()
                    animated=views.filterIsInstance<TextView>().any{it.text.toString().contains("1 animation clips")}
                }
                if(!animated)Thread.sleep(250)
            }
            check(animated&&surface!=null){"Animated model did not reach rendered status"}
            // The composited texture is the actual user-visible rendering, not
            // the enclosing activity background or the model metadata.
            var verified=false
            var lastBins=0
            var lastChannels=listOf(0,0,0)
            val renderDeadline=android.os.SystemClock.elapsedRealtime()+90000
            while(!verified&&android.os.SystemClock.elapsedRealtime()<renderDeadline){
                Thread.sleep(1000)
                var bitmap:Bitmap?=null
                instrumentation.runOnMainSync{
                    val view=surface!!
                    if(view.isAvailable&&view.width>0&&view.height>0)
                        bitmap=view.getBitmap(128,128)
                }
                bitmap?.let{frame->
                    try{
                        val pixels=IntArray(128*128);frame.getPixels(pixels,0,128,0,0,128,128)
                        lastBins=pixels.map{it and 0x00f0f0f0}.distinct().size
                        // The original fixture deliberately contains red, green,
                        // blue and yellow texels. Require all three primary color
                        // families so a uniform sky or loading layer cannot pass.
                        val counts=IntArray(3)
                        for(pixel in pixels){
                            val r=pixel ushr 16 and 255
                            val g=pixel ushr 8 and 255
                            val b=pixel and 255
                            if(r>g+18&&r>b+18)counts[0]++
                            if(g>r+18&&g>b+18)counts[1]++
                            if(b>r+18&&b>g+18)counts[2]++
                        }
                        lastChannels=counts.toList()
                        verified=lastBins>=3&&counts.all{it>=12}
                    }finally{frame.recycle()}
                }
            }
            check(verified){"Rendered texture missing: bins="+lastBins+" primary pixels="+lastChannels}
            fun seekFrame(position:Int){
                instrumentation.runOnMainSync{
                    val views=descendants(activity.window.decorView).toList()
                    val pause=views.filterIsInstance<Button>().firstOrNull{it.text.toString()=="Pause"}
                    pause?.performClick()
                    val slider=views.filterIsInstance<SeekBar>().single{it.contentDescription=="Animation position"}
                    if(slider.progress!=position){
                        check(slider.performAccessibilityAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                            Bundle().apply{putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,position.toFloat())}
                        )){"Animation seek did not accept the requested position"}
                    }
                }
            }
            fun framePixels():IntArray?{
                var result:IntArray?=null
                instrumentation.runOnMainSync{
                    val view=surface!!
                    if(view.isAvailable)view.getBitmap(128,128)?.let{frame->
                        try{result=IntArray(128*128).also{frame.getPixels(it,0,128,0,0,128,128)}}
                        finally{frame.recycle()}
                    }
                }
                return result
            }
            seekFrame(100)
            Thread.sleep(2500)
            val first=framePixels()?:error("First paused animation frame unavailable")
            seekFrame(500)
            val motionDeadline=android.os.SystemClock.elapsedRealtime()+30000
            var moved=false
            while(!moved&&android.os.SystemClock.elapsedRealtime()<motionDeadline){
                Thread.sleep(1000)
                val second=framePixels()?:continue
                val changed=first.indices.count{idx->
                    val a=first[idx];val b=second[idx]
                    val ar=a ushr 16 and 255;val ag=a ushr 8 and 255;val ab=a and 255
                    val br=b ushr 16 and 255;val bg=b ushr 8 and 255;val bb=b and 255
                    kotlin.math.abs(ar-br)+kotlin.math.abs(ag-bg)+kotlin.math.abs(ab-bb)>55
                }
                moved=changed>=40&&second.map{it and 0x00f0f0f0}.distinct().size>=3
            }
            check(moved){"Seeking animation did not change actual rendered pixels"}
            status("GLTF_RENDER_OK textured model, verified RGB texture pixels and real animation seek motion")
        }finally{instrumentation.runOnMainSync{activity.finish()}}
    }
}
