package com.asterion.nexus
import org.junit.Test
import org.junit.Assert.*
class NexusMontagePlannerTest {
    @Test fun respectsGlobalBudgetAndSourceOrdering(){
        fun c(i:Int,s:Long,e:Long,score:Double)=NexusHighlightPlanner.Candidate(i,NexusHighlightPlanner.Window(s,e,score))
        val picked=NexusHighlightPlanner.chooseMontage(listOf(c(1,0,5000,8.0),c(0,0,5000,6.0),c(1,2000,6000,7.0)),8000)
        assertEquals(listOf(0,1),picked.map{it.sourceIndex})
        assertEquals(8000L,picked.sumOf{it.window.end-it.window.start})
        assertEquals(3000L,picked.first().window.end)
    }
}
