package com.asterion.nexus

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlin.math.abs
import kotlin.math.min

/** Motion/scene-change montage suggestions from decoded frames, never narrative classification. */
internal object NexusHighlightPlanner {
    data class Window(val start:Long,val end:Long,val score:Double)
    fun choose(windows:List<Window>,budgetMs:Long):List<Window>{
        require(budgetMs>0)
        var remaining=budgetMs
        val picked=mutableListOf<Window>()
        for(w in windows.sortedWith(compareByDescending<Window>{it.score}.thenBy{it.start})){
            if(!w.score.isFinite()||w.start<0||w.end<=w.start||remaining<=0)continue
            val end=min(w.end,w.start+remaining)
            if(picked.any{it.start<end&&it.end>w.start})continue
            picked.add(w.copy(end=end));remaining-=end-w.start
        }
        return picked.sortedBy{it.start}
    }
    fun suggest(context:Context,source:NexusVideoExport.Source,budgetMs:Long):List<Window>{
        require(budgetMs in 5000..300000)
        val registry=NexusRegistry(context)
        check(registry.verifyOriginal(source.item)){"Original verification failed"}
        val duration=source.duration
        val step=maxOf(1000L,(duration+179)/180)
        val width=64;val height=36
        val r=MediaMetadataRetriever()
        val windows=mutableListOf<Window>()
        var previous:IntArray?=null
        try{
            r.setDataSource(registry.original(source.item).absolutePath)
            var t=0L
            while(t<duration){
                if(Thread.currentThread().isInterrupted)throw InterruptedException()
                val bitmap=r.getScaledFrameAtTime(t*1000,MediaMetadataRetriever.OPTION_CLOSEST,width,height)
                if(bitmap!=null){
                    try{
                        val pixels=IntArray(bitmap.width*bitmap.height)
                        bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                        val last=previous
                        if(last!=null&&last.size==pixels.size){
                            var delta=0L
                            for(i in pixels.indices){
                                val x=pixels[i];val y=last[i]
                                delta+=abs((x shr 16 and 255)-(y shr 16 and 255))
                                delta+=abs((x shr 8 and 255)-(y shr 8 and 255))
                                delta+=abs((x and 255)-(y and 255))
                            }
                            val start=(t-step/2-2500).coerceAtLeast(0)
                            windows.add(Window(start,min(duration,start+5000),delta.toDouble()/(pixels.size*3)))
                        }
                        previous=pixels
                    }finally{bitmap.recycle()}
                }else previous=null
                t+=step
            }
        }finally{r.release()}
        check(windows.isNotEmpty()){"Insufficient decodable frames for montage suggestions"}
        check(registry.verifyOriginal(source.item)){"Original changed during analysis"}
        return choose(windows,budgetMs)
    }
}
