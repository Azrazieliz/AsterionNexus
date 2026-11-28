package com.asterion.nexus

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Track mapping is explicit; selected compressed packet bytes are checked after staging. */
internal object NexusTrackMapping {
    fun resolve(available:List<Int>,requested:Int,mute:Boolean):Int{
        if(mute||requested==-1)return -1
        if(available.isEmpty())return -1
        if(requested==-2){
            require(available.size==1){"Select an audio track explicitly"}
            return available.first()
        }
        require(requested in available){"Selected audio track unavailable"}
        return requested
    }
    private data class Proof(val count:Long,val digest:ByteArray)
    private fun packets(file:File,track:Int,cancel:AtomicBoolean):Proof{
        val ex=MediaExtractor()
        var count=0L;var data=ByteBuffer.allocateDirect(1024*1024)
        val hash=MessageDigest.getInstance("SHA-256")
        try{
            ex.setDataSource(file.absolutePath);ex.selectTrack(track)
            while(ex.sampleTrackIndex>=0){
                check(!cancel.get()&&!Thread.currentThread().isInterrupted){"Export cancelled"}
                val size=ex.sampleSize
                require(size in 1..(32L*1024*1024)){"Unsupported selected sample size"}
                if(data.capacity()<size)data=ByteBuffer.allocateDirect(size.toInt())
                data.clear();check(ex.readSampleData(data,0)==size.toInt()){"Selected sample truncated"}
                hash.update(ByteBuffer.allocate(4).putInt(size.toInt()).array());data.position(0);data.limit(size.toInt());hash.update(data);count++
                ex.advance()
            }
        }finally{ex.release()}
        return Proof(count,hash.digest())
    }
    fun stage(input:File,output:File,video:Int,audio:Int,cancel:AtomicBoolean){
        require(video>=0&&audio>=0&&video!=audio)
        val ex=MediaExtractor()
        var mux:MediaMuxer?=null
        var started=false
        try{
            ex.setDataSource(input.absolutePath)
            val vf=ex.getTrackFormat(video);val af=ex.getTrackFormat(audio)
            require(vf.getString("mime").orEmpty().startsWith("video/")&&af.getString("mime").orEmpty().startsWith("audio/"))
            val m=MediaMuxer(output.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux=m
            if(vf.containsKey("rotation-degrees"))m.setOrientationHint(vf.getInteger("rotation-degrees"))
            val vi=m.addTrack(vf);val ai=m.addTrack(af)
            ex.selectTrack(video);ex.selectTrack(audio)
            m.start();started=true
            val info=MediaCodec.BufferInfo()
            var data=ByteBuffer.allocateDirect(1024*1024)
            while(ex.sampleTrackIndex>=0){
                check(!cancel.get()&&!Thread.currentThread().isInterrupted){"Export cancelled"}
                val size=ex.sampleSize;val flags=ex.sampleFlags
                require(size in 1..(32L*1024*1024)&&ex.sampleTime>=0){"Unsupported selected sample"}
                require(flags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME)==0){"Encrypted or partial samples cannot be staged"}
                if(data.capacity()<size)data=ByteBuffer.allocateDirect(size.toInt())
                data.clear();check(ex.readSampleData(data,0)==size.toInt()){"Selected sample truncated"}
                info.set(0,size.toInt(),ex.sampleTime,if(flags and MediaExtractor.SAMPLE_FLAG_SYNC!=0)MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                data.position(0);data.limit(size.toInt())
                m.writeSampleData(if(ex.sampleTrackIndex==video)vi else ai,data,info)
                ex.advance()
            }
            m.stop();started=false;m.release();mux=null
            val read=MediaExtractor()
            var v=-1;var a=-1
            try{
                read.setDataSource(output.absolutePath)
                require(read.trackCount==2){"Staged track count differs"}
                for(i in 0 until read.trackCount){
                    val mime=read.getTrackFormat(i).getString("mime")
                    if(mime==vf.getString("mime"))v=i
                    if(mime==af.getString("mime"))a=i
                }
                check(v>=0&&a>=0){"Selected track codec differs"}
            }finally{read.release()}
            for((before,after) in listOf(video to v,audio to a)){
                val expected=packets(input,before,cancel);val actual=packets(output,after,cancel)
                check(expected.count>0&&expected.count==actual.count&&MessageDigest.isEqual(expected.digest,actual.digest)){"Selected-track packet readback differs"}
            }
        }catch(e:Exception){throw IOException("Selected-track staging failed: "+e.message,e)}
        finally{ex.release();mux?.let{if(started)runCatching{it.stop()};runCatching{it.release()}}}
    }
}
