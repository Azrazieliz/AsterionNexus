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
    data class Range(val source:NexusVideoExport.Source,val start:Long,val end:Long)
    data class Candidate(val sourceIndex:Int,val window:Window)
    fun chooseMontage(candidates:List<Candidate>,budgetMs:Long):List<Candidate>{
        require(budgetMs>0)
        var remaining=budgetMs
        val picked=mutableListOf<Candidate>()
        for(c in candidates.sortedWith(compareByDescending<Candidate>{it.window.score}.thenBy{it.sourceIndex}.thenBy{it.window.start})){
            val w=c.window
            if(!w.score.isFinite()||w.start<0||w.end<=w.start||remaining<=0)continue
            val end=min(w.end,w.start+remaining)
            if(picked.any{it.sourceIndex==c.sourceIndex&&it.window.start<end&&it.window.end>w.start})continue
            picked.add(c.copy(window=w.copy(end=end)));remaining-=end-w.start
        }
        return picked.sortedWith(compareBy<Candidate>{it.sourceIndex}.thenBy{it.window.start})
    }
    fun suggestMontage(context:Context,ranges:List<Range>,budgetMs:Long,clipMs:Long):List<Candidate>{
        require(ranges.size in 1..100&&budgetMs in 5000..300000&&clipMs in 2000..15000)
        val samples=maxOf(3,360/ranges.size).coerceAtMost(180)
        val candidates=ranges.flatMapIndexed{index,range->analyze(context,range,samples,clipMs).map{Candidate(index,it)}}
        check(candidates.isNotEmpty()){"Insufficient decodable frames for montage suggestions"}
        return chooseMontage(candidates,budgetMs)
    }
    fun suggest(context:Context,source:NexusVideoExport.Source,budgetMs:Long):List<Window>{
        return suggestMontage(context,listOf(Range(source,0,source.duration)),budgetMs,5000).map{it.window}
    }
    private fun analyze(context:Context,range:Range,samples:Int,clipMs:Long):List<Window>{
        val source=range.source
        require(range.start>=0&&range.end>range.start&&range.end<=source.duration){"Invalid montage range"}
        val registry=NexusRegistry(context)
        check(registry.verifyOriginal(source.item)){"Original verification failed"}
        val duration=range.end-range.start
        val step=maxOf(250L,(duration+samples-1)/samples)
        val width=64;val height=36
        val r=MediaMetadataRetriever()
        val windows=mutableListOf<Window>()
        var previous:IntArray?=null
        try{
            r.setDataSource(registry.original(source.item).absolutePath)
            var t=range.start
            while(t<range.end){
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
                            val start=(t-step/2-clipMs/2).coerceAtLeast(range.start)
                            windows.add(Window(start,min(range.end,start+clipMs),delta.toDouble()/(pixels.size*3)))
                        }
                        previous=pixels
                    }finally{bitmap.recycle()}
                }else previous=null
                t+=step
            }
        }finally{r.release()}
        check(registry.verifyOriginal(source.item)){"Original changed during analysis"}
        return windows
    }
}
