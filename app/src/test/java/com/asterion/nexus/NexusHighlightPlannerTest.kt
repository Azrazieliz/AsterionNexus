package com.asterion.nexus

import org.junit.Assert.*
import org.junit.Test

class NexusHighlightPlannerTest {
    @Test fun ranksWithoutOverlapAndKeepsChronologicalOrder(){
        val windows=listOf(NexusHighlightPlanner.Window(0,5000,3.0),
            NexusHighlightPlanner.Window(3000,8000,4.0),
            NexusHighlightPlanner.Window(10000,15000,7.0))
        val picked=NexusHighlightPlanner.choose(windows,10000)
        assertEquals(listOf(3000L,10000L),picked.map{it.start})
        assertEquals(10000L,picked.sumOf{it.end-it.start})
    }
    @Test fun respectsRemainingBudget(){
        val picked=NexusHighlightPlanner.choose(listOf(NexusHighlightPlanner.Window(0,5000,2.0)),3000)
        assertEquals(3000L,picked.single().end)
    }
}
