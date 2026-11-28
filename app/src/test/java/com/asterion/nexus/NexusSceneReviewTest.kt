package com.asterion.nexus
import org.junit.Test
import org.junit.Assert.*
class NexusSceneReviewTest {
    private fun frame(t:Long,shade:Int)=NexusSceneReview.Frame(t,IntArray(16){shade*0x010101})
    @Test fun findsShiftedVaryingSequenceWithoutClassifyingIt(){
        val series=(0..9).map{frame(it*2000L,if(it%2==0)30 else 180)}
        val shifted=series.map{it.copy(time=it.time+4000)}
        val group=NexusSceneReview.common(listOf(series,shifted,shifted),true)
        assertNotNull(group);assertEquals(4000L,group!!.spans[1].start);assertEquals(10,group.samples)
    }
    @Test fun rejectsStaticSequences(){
        val series=(0..9).map{frame(it*2000L,0)}
        assertNull(NexusSceneReview.common(listOf(series,series,series),true))
    }
    @Test fun keepsEverythingOutsideReviewedCuts(){
        assertEquals(listOf(0L to 20L,50L to 100L),NexusSceneReview.subtract(0,100,listOf(20L to 40L,30L to 50L)))
    }

    @Test fun adjacentRecapSuggestsCutOnlyWithInteriorWitness(){
        val sequence=(0..9).map{frame(it*2000L,if(it%2==0)30 else 180)}
        val before=sequence.map{it.copy(time=it.time+100000)}+listOf(frame(120000,240),frame(122000,240),frame(124000,240),frame(126000,240))
        val after=sequence+listOf(frame(20000,240),frame(22000,240))
        val g=NexusSceneReview.adjacent(listOf(emptyList(),after),listOf(before,emptyList())).single()
        assertEquals(0,g.retainedIndex)
        assertTrue(g.label.contains("recap"))
        assertTrue(NexusSceneReview.protected(listOf(g)).all{it.index==0})
    }
    @Test fun ambiguousCrossEpisodeMatchKeepsBothCopies(){
        val seq=(0..9).map{frame(it*2000L,if(it%2==0)30 else 180)}
        val g=NexusSceneReview.adjacent(listOf(emptyList(),seq),listOf(seq,emptyList())).single()
        assertEquals(-1,g.retainedIndex)
        assertEquals(2,NexusSceneReview.protected(listOf(g)).size)
    }
}
