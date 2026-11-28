package com.asterion.nexus

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

/** Visual repeat candidates for owner review only. Never classifies narrative or removes footage. */
internal object NexusSceneReview {
    const val STEP=2000L
    private const val WIDTH=16
    private const val HEIGHT=9
    data class Frame(val time:Long,val rgb:IntArray)
    data class Span(val index:Int,val start:Long,val end:Long)
    data class Group(val early:Boolean,val spans:List<Span>,val samples:Int)

    private fun distance(a:Frame,b:Frame):Double{
        if(a.rgb.size!=b.rgb.size||a.rgb.isEmpty())return Double.POSITIVE_INFINITY
        var sum=0L
        for(i in a.rgb.indices){
            val x=a.rgb[i];val y=b.rgb[i]
            sum+=abs((x shr 16 and 255)-(y shr 16 and 255))
            sum+=abs((x shr 8 and 255)-(y shr 8 and 255))
            sum+=abs((x and 255)-(y and 255))
        }
        return sum.toDouble()/(a.rgb.size*3)
    }
    private fun matches(a:Frame,b:Frame):Boolean=distance(a,b)<=12.0
    /** Exact sample cadence and visually varying runs; avoid matches from black or static footage. */
    fun common(episodes:List<List<Frame>>,early:Boolean):Group?{
        if(episodes.size<3||episodes.any{it.size<8})return null
        val first=episodes.first()
        val alignments=episodes.drop(1).map{other->
            val best=Array(first.size){0 to -1}
            val dp=Array(first.size+1){IntArray(other.size+1)}
            for(i in first.lastIndex downTo 0)for(j in other.lastIndex downTo 0){
                if(matches(first[i],other[j])){
                    val continuous=i<first.lastIndex&&j<other.lastIndex&&first[i+1].time-first[i].time==STEP&&other[j+1].time-other[j].time==STEP
                    dp[i][j]=1+if(continuous)dp[i+1][j+1]else 0
                    if(dp[i][j]>best[i].first)best[i]=dp[i][j] to j
                }
            }
            best
        }
        var best:Group?=null
        for(i in first.indices){
            val count=alignments.minOf{it[i].first}
            if(count<8||count<=(best?.samples?:0)||i+count>first.size)continue
            val varied=(i+1 until i+count).count{distance(first[it-1],first[it])>10}
            if(varied<max(3,count/4))continue
            // Exclude the unsampled boundary at each end. Owner sees and explicitly approves every cut.
            val spans=mutableListOf(Span(0,first[i].time,first[i+count-1].time))
            alignments.forEachIndexed{k,alignment->
                val j=alignment[i].second
                val other=episodes[k+1]
                spans.add(Span(k+1,other[j].time,other[j+count-1].time))
            }
            best=Group(early,spans,count)
        }
        return best
    }
    fun subtract(start:Long,end:Long,cuts:List<Pair<Long,Long>>):List<Pair<Long,Long>>{
        require(start>=0&&end>start)
        val out=mutableListOf<Pair<Long,Long>>()
        var cursor=start
        for((a,b) in cuts.filter{it.second>it.first}.sortedBy{it.first}){
            val l=max(start,a);val r=min(end,b)
            if(r<=cursor||l>=end)continue
            if(l>cursor)out.add(cursor to l)
            cursor=max(cursor,r)
        }
        if(cursor<end)out.add(cursor to end)
        return out
    }
    fun scan(context:Context,sources:List<NexusVideoExport.Source>):List<Group>{
        require(sources.size in 3..20&&sources.map{it.item.sha256}.distinct().size==sources.size)
        val registry=NexusRegistry(context)
        val early=mutableListOf<List<Frame>>()
        val late=mutableListOf<List<Frame>>()
        for(source in sources){
            check(NexusOriginalStore.matches(source.file,source.item.length,source.item.sha256)){"Original verification failed"}
            val duration=source.duration
            val window=min(180000L,duration/4)
            val retriever=MediaMetadataRetriever()
            try{
                retriever.setDataSource(source.file.absolutePath)
                fun read(start:Long,end:Long):List<Frame>{
                    val frames=mutableListOf<Frame>()
                    var time=start
                    while(time<end){
                        if(Thread.currentThread().isInterrupted)throw InterruptedException()
                        val bitmap=retriever.getScaledFrameAtTime(time*1000,MediaMetadataRetriever.OPTION_CLOSEST,WIDTH,HEIGHT)
                        if(bitmap!=null){
                            try{
                                val normalized=android.graphics.Bitmap.createScaledBitmap(bitmap,WIDTH,HEIGHT,true)
                                try{
                                    val pixels=IntArray(WIDTH*HEIGHT)
                                    normalized.getPixels(pixels,0,WIDTH,0,0,WIDTH,HEIGHT)
                                    frames.add(Frame(time,pixels))
                                }finally{if(normalized!==bitmap)normalized.recycle()}
                            }finally{bitmap.recycle()}
                        }
                        time+=STEP
                    }
                    return frames
                }
                early.add(read(0,window))
                late.add(read(duration-window,duration))
            }finally{retriever.release()}
            check(NexusOriginalStore.matches(source.file,source.item.length,source.item.sha256)){"Original changed during scene review"}
        }
        return listOfNotNull(common(early,true),common(late,false))
    }
}
