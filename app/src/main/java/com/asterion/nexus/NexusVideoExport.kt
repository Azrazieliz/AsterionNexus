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
    data class Clip(val sha:String,val startMs:Long,val endMs:Long,val audioTrack:Int=-2)
    data class Profile(val width:Int,val height:Int,val bitrate:Int,val hevc:Boolean,val mute:Boolean,val omitOtherTracks:Boolean=false)
    data class AudioTrack(val index:Int,val language:String,val mime:String)
    data class Source(val item:NexusRegistry.Item,val file:File,val duration:Long,val videoTrack:Int,val audioTracks:List<AudioTrack>,val otherTracks:Int){val audio:Boolean get()=audioTracks.isNotEmpty()}
    fun source(context:Context,sha:String):Source {
        val registry=NexusRegistry(context)
        val item=registry.find(sha)?:error("Original unavailable")
        require(item.mime.startsWith("video/")){"Video required"}
        val file=NexusVideoInputs.file(context,item)
        val extractor=MediaExtractor()
        val audio=mutableListOf<AudioTrack>();var video=-1;var duration=0L;var other=0
        try{
            extractor.setDataSource(file.absolutePath)
            for(i in 0 until extractor.trackCount){
                val format=extractor.getTrackFormat(i)
                val mime=format.getString("mime").orEmpty()
                when{
                    mime.startsWith("video/")->{require(video<0){"Multiple video tracks require dedicated mapping"};video=i;duration=if(format.containsKey("durationUs"))format.getLong("durationUs")/1000 else 0}
                    mime.startsWith("audio/")->{val track=AudioTrack(i,format.getString("language").orEmpty().ifBlank{"und"},mime);if(format.containsKey("is-default")&&format.getInteger("is-default")==1)audio.add(0,track)else audio.add(track)}
                    else->other++
                }
            }
        }finally{extractor.release()}
        if(duration<=0){
            val retriever=MediaMetadataRetriever()
            try{retriever.setDataSource(file.absolutePath);duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0}
            finally{retriever.release()}
        }
        require(video>=0&&duration>0){"One decodable video track required"}
        return Source(item,file,duration,video,audio,other)
    }
    private fun save(file:File,data:JSONObject){
        val atomic=AtomicFile(file);val stream=atomic.startWrite()
        try{stream.write(data.toString(2).toByteArray(Charsets.UTF_8));atomic.finishWrite(stream)}
        catch(e:Exception){atomic.failWrite(stream);throw e}
    }
    private fun stopped(cancel:AtomicBoolean){if(cancel.get()||Thread.currentThread().isInterrupted)throw IOException("Export cancelled")}
    @Synchronized fun export(context:Context,title:String,clips:List<Clip>,profile:Profile,cancel:AtomicBoolean,reviewEvidence:String="[]",onProgress:(Int)->Unit):NexusRegistry.Item {
        require(title.isNotBlank()&&title.length<=150&&title.none{it=='/'||it=='\\'||it.isISOControl()}){"Invalid output name"}
        require(clips.size in 1..100&&profile.width in 320..3840&&profile.height in 240..2160&&profile.width%2==0&&profile.height%2==0&&profile.bitrate in 500000..50000000)
        val registry=NexusRegistry(context)
        val inputs=clips.map{it.sha}.distinct().associateWith{stopped(cancel);source(context,it)}
        val sources=clips.map{inputs.getValue(it.sha)}
        val duration=clips.zip(sources).fold(0L){total,(clip,source)->
            require(clip.startMs>=0&&clip.startMs<clip.endMs&&clip.endMs<=source.duration){"Trim outside source duration"}
            Math.addExact(total,clip.endMs-clip.startMs)
        }
        require(duration<=12L*60*60*1000){"Timeline exceeds 12 hours"}
        require(profile.omitOtherTracks||sources.none{it.otherTracks>0}){"Explicitly acknowledge subtitles/other tracks retained in originals"}
        val selected=clips.zip(sources).map{(clip,source)->NexusTrackMapping.resolve(source.audioTracks.map{it.index},clip.audioTrack,profile.mute)}
        val hasAudio=selected.any{it>=0}
        val edl=JSONArray()
        clips.forEachIndexed{i,clip->edl.put(JSONObject().put("sha256",clip.sha).put("start_ms",clip.startMs).put("end_ms",clip.endMs).put("audio_track",selected[i]))}
        val spec=JSONObject().put("version",2).put("title",title).put("clips",edl)
            .put("width",profile.width).put("height",profile.height).put("bitrate",profile.bitrate)
            .put("hevc",profile.hevc).put("mute",profile.mute).put("omit_other_tracks",profile.omitOtherTracks).put("duration_ms",duration)
        require(reviewEvidence.length<=128*1024){"Review log too large"}
        val review=JSONArray(reviewEvidence)
        if(review.length()>0)spec.put("review_evidence",review)
        val identity="video-edl-v2:"+NexusOriginalStore.sourceKey(spec.toString())
        val dir=File(context.filesDir,"nexus-video-projects")
        check(dir.isDirectory||dir.mkdirs())
        val receipt=File(dir,identity.substringAfter(':')+".json")
        val prior=if(receipt.isFile&&receipt.length()<1024*1024)runCatching{JSONObject(receipt.readText())}.getOrNull() else null
        if(prior!=null){
            if(prior?.optString("state")=="INDEXED"){
                val item=registry.find(prior.optString("output_sha256"))
                if(item!=null&&registry.verifyOriginal(item))return item
            }
        }
        val estimate=Math.multiplyExact(duration,profile.bitrate.toLong()+512000)/8000
        check(dir.usableSpace>Math.addExact(Math.multiplyExact(estimate,3),64L*1024*1024)){"Insufficient export storage"}
        val output=File(dir,identity.substringAfter(':')+".partial.mp4")
        val mime=if(profile.hevc)MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
        fun publish():NexusRegistry.Item{
            validate(output,duration,profile,mime,hasAudio)
            sources.distinctBy{it.item.sha256}.forEach{stopped(cancel);check(NexusOriginalStore.matches(it.file,it.item.length,it.item.sha256)){"Original changed during export"}}
            save(receipt,spec.put("state","VERIFIED"))
            registry.job(identity,"VERIFIED",output.length(),null)
            val item=output.inputStream().use{registry.ingest(it,title.trim().removeSuffix(".mp4")+".mp4","video/mp4","nexus-video-edit","device",identity,output.length(),null)}
            clips.map{it.sha}.distinct().forEach{registry.linkDerivative(item.sha256,it,"video","timeline-export")}
            save(receipt,spec.put("state","INDEXED").put("output_sha256",item.sha256).put("validation","track, duration and representative decode"))
            registry.job(identity,"INDEXED",item.length,null)
            return item
        }
        if(output.isFile&&prior?.optString("state") in setOf("BYTES_COMPLETE","VERIFIED")){
            stopped(cancel)
            val recovered=runCatching{publish()}
            recovered.onSuccess{output.delete();File(dir,identity.substringAfter(':')+".staging").deleteRecursively();return it}
            stopped(cancel)
        }
        if(output.exists())check(output.delete()){"Old partial export cannot be cleared"}
        val stageDir=File(dir,identity.substringAfter(':')+".staging")
        val worker=HandlerThread("Nexus video codec").apply{start()}
        val handler=Handler(worker.looper)
        val done=CountDownLatch(1)
        val error=AtomicReference<Throwable?>()
        val transformer=AtomicReference<Transformer?>()
        val abort=AtomicBoolean(false)
        try{
            registry.job(identity,"QUEUED",0,null)
            save(receipt,spec.put("state","PROCESSING"))
            registry.job(identity,"RECEIVING",0,null)
            if(stageDir.exists())check(stageDir.deleteRecursively()){"Old staging files cannot be cleared"}
            val stageKeys=clips.indices.filter{selected[it]>=0&&sources[it].audioTracks.size>1}
                .distinctBy{sources[it].item.sha256 to selected[it]}
            val stagingBytes=stageKeys.fold(0L){total,i->Math.addExact(total,sources[i].item.length)}
            check(stagingBytes<=8L*1024*1024*1024){"Selected-track staging exceeds 8 GiB; choose a smaller project"}
            check(dir.usableSpace>Math.addExact(Math.multiplyExact(estimate,3),Math.addExact(stagingBytes,64L*1024*1024))){"Insufficient space for selected track staging"}
            val staged=mutableMapOf<Pair<String,Int>,File>()
            if(stageKeys.isNotEmpty()){
                check(stageDir.mkdirs())
                stageKeys.forEach{i->
                    stopped(cancel)
                    val source=sources[i];val key=source.item.sha256 to selected[i]
                    val target=File(stageDir,source.item.sha256+"-"+selected[i]+".mp4")
                    NexusTrackMapping.stage(source.file,target,source.videoTrack,selected[i],cancel)
                    staged[key]=target
                }
            }
            handler.post {
                try{
                    if(cancel.get()||abort.get())throw IOException("Export cancelled")
                    val edited=clips.mapIndexed{i,clip->
                        val source=sources[i]
                        val input=staged[source.item.sha256 to selected[i]]?:source.file
                        val item=MediaItem.Builder().setUri(android.net.Uri.fromFile(input))
                            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(clip.startMs).setEndPositionMs(clip.endMs).build()).build()
                        EditedMediaItem.Builder(item).setRemoveAudio(selected[i]<0)
                            .setEffects(Effects(emptyList(),listOf(Presentation.createForWidthAndHeight(profile.width,profile.height,Presentation.LAYOUT_SCALE_TO_FIT))))
                            .build()
                    }
                    val sequence=if(hasAudio) EditedMediaItemSequence.withAudioAndVideoFrom(edited) else EditedMediaItemSequence.withVideoFrom(edited)
                    val composition=Composition.Builder(sequence)
                        .setTransmuxAudio(false).setTransmuxVideo(false).build()
                    val encoder=DefaultEncoderFactory.Builder(context)
                        .setEnableFallback(false)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(profile.bitrate).build()).build()
                    val t=Transformer.Builder(context).setLooper(worker.looper).setVideoMimeType(mime)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC).setPortraitEncodingEnabled(true).setEncoderFactory(encoder)
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
            save(receipt,spec.put("state","BYTES_COMPLETE"))
            return publish()
        }catch(e:Exception){
            save(receipt,spec.put("state",if(cancel.get())"CANCELLED" else "FAILED").put("error",e.message.orEmpty().take(500)))
            registry.job(identity,"FAILED",0,e.javaClass.simpleName);throw e
        }finally{
            abort.set(true)
            handler.post{runCatching{transformer.get()?.cancel()};worker.quitSafely()}
            // Never remove a file while codec cancellation may still write to it.
            worker.join(10000)
            if(!worker.isAlive){output.delete();stageDir.deleteRecursively()}else{Thread{worker.join();output.delete();stageDir.deleteRecursively()}.start()}
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
