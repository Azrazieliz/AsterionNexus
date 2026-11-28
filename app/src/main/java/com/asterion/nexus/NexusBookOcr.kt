package com.asterion.nexus

import android.content.Context
import com.ailm.android.runtime.StandaloneRuntime
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Book-only adapter to the retained native Core OCR pipeline.
 * No cloud translator, external application or speculative text generation. */
object NexusBookOcr {
    class EngineUnavailable(message:String):IOException(message)
    data class Region(val text:String,val x:Double,val y:Double,val width:Double,val height:Double,val confidence:Double)
    data class Result(val text:String,val modelId:String,val modelVersion:String,
                      val boxesDetected:Int,val linesRecognized:Int,val regions:List<Region>,val sourceWidth:Int,val sourceHeight:Int)
    @JvmStatic fun recognize(context:Context,input:InputStream):Result{
        val app=context.applicationContext
        val file=File.createTempFile("nexus-book-ocr-",".page",app.cacheDir)
        try{
            file.outputStream().use{output->
                val buffer=ByteArray(65536)
                var total=0L
                while(true){
                    if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException("Book OCR cancelled")
                    val n=input.read(buffer)
                    if(n<0)break
                    if(n==0)continue
                    total+=n
                    if(total>128L*1024*1024)throw IOException("Page exceeds offline OCR byte limit")
                    output.write(buffer,0,n)
                }
                if(total==0L)throw IOException("Empty page")
            }
            StandaloneRuntime.initialize(app)
            val bundled=StandaloneRuntime.ensureBundledBookOcr()
            val preferred=if(bundled)mapOf<String,Any>("model_id" to "asterion-ppocrv5-cjk","version" to "5.0.1") else emptyMap()
            val answer=StandaloneRuntime.runOcrPipeline(preferred+mapOf(
                "image_path" to file.absolutePath,
                "priority" to 5,
                "max_retries" to 0,
                "timeout_ms" to 120000L
            ))
            if(answer["ok"]!=true||answer["status"]!="succeeded"){
                val state=answer["status"]?.toString().orEmpty()
                if(state in setOf("incompatible","unsupported","invalid"))
                    throw EngineUnavailable("Compatible verified local OCR model unavailable")
                throw IOException("Native OCR did not complete: "+state.take(60))
            }
            val result=answer["result"] as? Map<*,*>?:throw IOException("Native OCR returned no result")
            val text=result["text"] as? String?:throw IOException("Native OCR returned no text field")
            val stage=(answer["stages"] as? List<*>)?.filterIsInstance<Map<*,*>>()
                ?.lastOrNull{it["stage_type"]=="ocr"&&it["status"]=="succeeded"}
                ?:throw IOException("Native OCR execution receipt unavailable")
            val model=stage["model_id"]?.toString().orEmpty()
            val version=stage["model_version"]?.toString().orEmpty()
            if(model.isBlank()||version.isBlank())throw IOException("OCR model identity unavailable")
            val lines=result["lines"] as? List<*>?:throw IOException("OCR region receipt unavailable")
            val boxes=(result["boxes_detected"] as? Number)?.toInt()
                ?:throw IOException("OCR detector receipt unavailable")
            if(boxes<0||lines.size>boxes)throw IOException("Inconsistent OCR region receipt")
            val width=(result["source_width"] as? Number)?.toInt()?:0
            val height=(result["source_height"] as? Number)?.toInt()?:0
            val regions=if(width>0&&height>0)lines.mapNotNull{raw->
                val line=raw as? Map<*,*>?:return@mapNotNull null
                val rect=line["bbox"] as? Map<*,*>?:return@mapNotNull null
                val x=(rect["x"] as? Number)?.toDouble()?:return@mapNotNull null
                val y=(rect["y"] as? Number)?.toDouble()?:return@mapNotNull null
                val w=(rect["w"] as? Number)?.toDouble()?:return@mapNotNull null
                val h=(rect["h"] as? Number)?.toDouble()?:return@mapNotNull null
                val score=(line["confidence"] as? Number)?.toDouble()?:return@mapNotNull null
                val value=line["text"] as? String?:return@mapNotNull null
                if(value.isBlank()||!listOf(x,y,w,h,score).all{it.isFinite()}||x<0||y<0||w<=0||h<=0||x+w>width||y+h>height||score !in 0.0..1.0)return@mapNotNull null
                Region(value,x/width,y/height,w/width,h/height,score)
            }else emptyList()
            return Result(text,model,version,boxes,lines.size,regions,width,height)
        }finally{file.delete()}
    }
}
