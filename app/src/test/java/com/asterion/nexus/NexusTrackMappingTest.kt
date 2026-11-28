package com.asterion.nexus
import org.junit.Test
import org.junit.Assert.*
class NexusTrackMappingTest {
    @Test fun noSilentDefaultForMultipleTracks(){
        assertThrows(IllegalArgumentException::class.java){NexusTrackMapping.resolve(listOf(1,2),-2,false)}
        assertEquals(2,NexusTrackMapping.resolve(listOf(1,2),2,false))
        assertEquals(-1,NexusTrackMapping.resolve(listOf(1,2),-2,true))
        assertEquals(3,NexusTrackMapping.resolve(listOf(3),-2,false))
        assertEquals(-1,NexusTrackMapping.resolve(emptyList(),-2,false))
    }
}
