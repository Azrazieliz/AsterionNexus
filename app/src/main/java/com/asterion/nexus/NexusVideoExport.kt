package com.asterion.nexus

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.HandlerThread
import android.util.AtomicFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Explicit non-destructive EDL, MediaCodec/Media3 export and independently probed output. */
@androidx.annotation.OptIn(UnstableApi::class)
object NexusVideoExport {
    data class Clip(val sha:String,val startMs:Long,val endMs:Long)
    data class Profile(val width:Int,val height:Int,val bitrate:Int,val hevc:Boolean,val mute:Boolean)
    data class Source(val item:NexusRegistry.Item,val duration:Long,val audio:Boolean)
    fun source(context:Context,sha:String):Source {
        val registry=NexusRegistry(context)
        val item=registry.find(sha)?:error("Original unavailable")
        require(item.mime.startsWith("video/")){"Video required"}
        val file=registry.original(item)
        check(registry.verifyOriginal(item)){"Source hash mismatch"}
        val extractor=MediaExtractor()
        var audio=0;var video=0;var duration=0L
        try{
            extractor.setDataSource(file.absolutePath)
            for(i in 0 until extractor.trackCount){
                val format=extractor.getTrackFormat(i)
                val mime=format.getString("mime").orEmpty()
                when{
                    mime.startsWith("video/")->{video++;duration=format.getLong("durationUs")/1000}
                    mime.startsWith("audio/")->audio++
                    else->error("Additional tracks need explicit mapping; source preserved")
                }
            }
        }finally{extractor.release()}
        require(video==1&&audio<=1&&duration>0){"Export requires one video and at most one audio track"}
        return Source(item,duration,audio==1)
    }
    private fun save(file:File,data:JSONObject){
        val atomic=AtomicFile(file);val stream=atomic.startWrite()
        try{stream.write(data.toString(2).toByteArray(Charsets.UTF_8));atomic.finishWrite(stream)}
        catch(e:Exception){atomic.failWrite(stream);throw e}
    }
    private fun stopped(cancel:AtomicBoolean){if(cancel.get()||Thread.currentThread().isInterrupted)throw IOException("Export cancelled")}
    @Synchronized fun export(context:Context,title:String,clips:List<Clip>,profile:Profile,cancel:AtomicBoolean,onProgress:(Int)->Unit):NexusRegistry.Item {
        require(title.isNotBlank()&&title.length<=150&&title.none{it=='/'||it=='\\'||it.isISOControl()}){"Invalid output name"}
        require(clips.size in 1..100&&profile.width in 320..3840&&profile.height in 240..2160&&profile.width%2==0&&profile.height%2==0&&profile.bitrate in 500000..50000000)
        val registry=NexusRegistry(context)
        val sources=clips.map{stopped(cancel);source(context,it.sha)}
        val duration=clips.zip(sources).fold(0L){total,(clip,source)->
            require(clip.startMs>=0&&clip.startMs<clip.endMs&&clip.endMs<=source.duration){"Trim outside source duration"}
            Math.addExact(total,clip.endMs-clip.startMs)
        }
        require(duration<=12L*60*60*1000){"Timeline exceeds 12 hours"}
        require(profile.mute||sources.map{it.audio}.distinct().size==1){"Mixed silent and audio clips require explicit mute or audio mapping"}
        val edl=JSONArray()
        clips.forEach{edl.put(JSONObject().put("sha256",it.sha).put("start_ms",it.startMs).put("end_ms",it.endMs))}
        val spec=JSONObject().put("version",1).put("title",title).put("clips",edl)
            .put("width",profile.width).put("height",profile.height).put("bitrate",profile.bitrate)
            .put("hevc",profile.hevc).put("mute",profile.mute).put("duration_ms",duration)
        val identity="video-edl-v1:"+NexusOriginalStore.sourceKey(spec.toString())
        val dir=File(context.filesDir,"nexus-video-projects")
        check(dir.isDirectory||dir.mkdirs())
        val receipt=File(dir,identity.substringAfter(':')+".json")
        if(receipt.isFile&&receipt.length()<1024*1024){
            val prior=runCatching{JSONObject(receipt.readText())}.getOrNull()
            if(prior?.optString("state")=="INDEXED"){
                val item=registry.find(prior.optString("output_sha256"))
                if(item!=null&&registry.verifyOriginal(item))return item
            }
        }
        val estimate=Math.multiplyExact(duration,profile.bitrate.toLong()+512000)/8000
        check(dir.usableSpace>Math.addExact(Math.multiplyExact(estimate,3),64L*1024*1024)){"Insufficient export storage"}
        val output=File(dir,identity.substringAfter(':')+".partial.mp4")
        if(output.exists())check(output.delete()){"Old partial export cannot be cleared"}
        val worker=HandlerThread("Nexus video codec").apply{start()}
        val handler=Handler(worker.looper)
        val done=CountDownLatch(1)
        val error=AtomicReference<Throwable?>()
        val transformer=AtomicReference<Transformer?>()
        val abort=AtomicBoolean(false)
        val mime=if(profile.hevc)MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
        try{
            registry.job(identity,"QUEUED",0,null)
            save(receipt,spec.put("state","PROCESSING"))
            registry.job(identity,"RECEIVING",0,null)
            handler.post {
                try{
                    if(cancel.get()||abort.get())throw IOException("Export cancelled")
                    val edited=clips.zip(sources).map{(clip,source)->
                        val item=MediaItem.Builder().setUri(android.net.Uri.fromFile(registry.original(source.item)))
                            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(clip.startMs).setEndPositionMs(clip.endMs).build()).build()
                        EditedMediaItem.Builder(item).setRemoveAudio(profile.mute)
                            .setEffects(Effects(emptyList(),listOf(Presentation.createForWidthAndHeight(profile.width,profile.height,Presentation.LAYOUT_SCALE_TO_FIT))))
                            .build()
                    }
                    val composition=Composition.Builder(EditedMediaItemSequence.Builder(edited).build())
                        .setTransmuxAudio(false).setTransmuxVideo(false).build()
                    val encoder=DefaultEncoderFactory.Builder(context)
                        .setEnableFallback(false)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(profile.bitrate).build()).build()
                    val t=Transformer.Builder(context).setLooper(worker.looper).setVideoMimeType(mime)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC).setEncoderFactory(encoder)
                        .addListener(object:Transformer.Listener{
                            override fun onCompleted(composition:Composition,exportResult:ExportResult){done.countDown()}
                            override fun onError(composition:Composition,exportResult:ExportResult,exportException:ExportException){error.set(exportException);done.countDown()}
                        }).build()
                    transformer.set(t)
                    t.start(composition,output.absolutePath)
                }catch(e:Throwable){error.set(e);done.countDown()}
            }
            val progress=ProgressHolder()
            while(!done.await(500,TimeUnit.MILLISECONDS)){
                stopped(cancel)
                handler.post{
                    runCatching{if(transformer.get()?.getProgress(progress)==Transformer.PROGRESS_STATE_AVAILABLE)onProgress(progress.progress)}
                }
            }
            stopped(cancel);error.get()?.let{throw IOException("Codec export failed: "+it.message,it)}
            check(output.isFile&&output.length()>0){"Empty export"}
            registry.job(identity,"BYTES_COMPLETE",output.length(),null)
            validate(output,duration,profile,mime,!profile.mute&&sources.first().audio)
            sources.forEach{stopped(cancel);check(registry.verifyOriginal(it.item)){"Original changed during export"}}
            registry.job(identity,"VERIFIED",output.length(),null)
            val item=output.inputStream().use{registry.ingest(it,title.trim().removeSuffix(".mp4")+".mp4","video/mp4","nexus-video-edit","device",identity,output.length(),null)}
            clips.map{it.sha}.distinct().forEach{registry.linkDerivative(item.sha256,it,"video","timeline-export")}
            save(receipt,spec.put("state","INDEXED").put("output_sha256",item.sha256).put("validation","track, duration and representative decode"))
            registry.job(identity,"INDEXED",item.length,null)
            return item
        }catch(e:Exception){
            save(receipt,spec.put("state",if(cancel.get())"CANCELLED" else "FAILED").put("error",e.message.orEmpty().take(500)))
            registry.job(identity,"FAILED",0,e.javaClass.simpleName);throw e
        }finally{
            abort.set(true)
            handler.post{runCatching{transformer.get()?.cancel()};worker.quitSafely()}
            // Never remove a file while codec cancellation may still write to it.
            worker.join(10000)
            if(!worker.isAlive)output.delete()
        }
    }
    private fun validate(file:File,duration:Long,profile:Profile,mime:String,audio:Boolean){
        val ex=MediaExtractor()
        var v=0;var a=0
        try{
            ex.setDataSource(file.absolutePath)
            for(i in 0 until ex.trackCount){
                val f=ex.getTrackFormat(i)
                val type=f.getString("mime").orEmpty()
                if(type.startsWith("video/")){
                    v++;check(type==mime){"Unexpected output codec"}
                    val w=f.getInteger("width");val h=f.getInteger("height")
                    val rotation=if(f.containsKey("rotation-degrees"))f.getInteger("rotation-degrees") else 0
                    check(if(rotation%180==0)w==profile.width&&h==profile.height else h==profile.width&&w==profile.height){"Output dimensions differ"}
                    check(abs(f.getLong("durationUs")/1000-duration)<=maxOf(500L,duration/10000)){"Output duration mismatch"}
                }else if(type.startsWith("audio/")){a++;check(type==MimeTypes.AUDIO_AAC){"Unexpected audio format"}}
            }
            check(v==1&&a==(if(audio)1 else 0)){"Output track mismatch"}
        }finally{ex.release()}
        val retriever=MediaMetadataRetriever()
        try{
            retriever.setDataSource(file.absolutePath)
            for(ms in listOf(0L,duration/2,(duration-250).coerceAtLeast(0))){
                val frame=retriever.getScaledFrameAtTime(ms*1000,MediaMetadataRetriever.OPTION_CLOSEST,320,180)
                    ?:error("Output frame could not be decoded")
                frame.recycle()
            }
        }finally{retriever.release()}
    }
}
