package com.azrael.pixivdumpsync

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.asterion.nexus.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
            var surface:SurfaceView?=null
            var animated=false
            val deadline=android.os.SystemClock.elapsedRealtime()+45000
            while(android.os.SystemClock.elapsedRealtime()<deadline&&!animated){
                instrumentation.runOnMainSync{
                    val views=descendants(activity.window.decorView).toList()
                    surface=views.filterIsInstance<SurfaceView>().firstOrNull()
                    animated=views.filterIsInstance<TextView>().any{it.text.toString().contains("1 animation clips")}
                }
                if(!animated)Thread.sleep(250)
            }
            check(animated&&surface!=null){"Animated model did not reach rendered status"}
            val bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
            val latch=CountDownLatch(1)
            var copied=-1
            instrumentation.runOnMainSync{
                PixelCopy.request(surface!!,bitmap,{result->copied=result;latch.countDown()},Handler(Looper.getMainLooper()))
            }
            try{
                check(latch.await(10,TimeUnit.SECONDS)&&copied==PixelCopy.SUCCESS){"Rendered model pixels unavailable"}
                val pixels=IntArray(128*128);bitmap.getPixels(pixels,0,128,0,0,128,128)
                val colorBins=pixels.map{(it and 0x00f0f0f0)}.distinct().size
                check(colorBins>=3){"Model surface did not contain visible rendered variation"}
            }finally{bitmap.recycle()}
            status("GLTF_RENDER_OK textured model, animation clip and rendered surface pixels")
        }finally{instrumentation.runOnMainSync{activity.finish()}}
    }
}
