package com.asterion.nexus

import org.junit.Test
import org.junit.Assert.*

class NexusArtworkOrganizationTest {
    @Test fun exactIngressBoundary(){
        assertTrue(NexusArtworkOrganization.inside("/Art/Incoming/Example.jpg","/Art/Incoming"))
        assertFalse(NexusArtworkOrganization.inside("/Art/Incoming-old/Example.jpg","/Art/Incoming"))
        assertFalse(NexusArtworkOrganization.inside("/Art/Incoming","/Art/Incoming"))
        assertFalse(NexusArtworkOrganization.inside("/Art/Incoming/Example.jpg","/"))
        NexusArtworkOrganization.validateRoots("/Art/Incoming","/Art")
    }
    @Test fun rejectNestedDestinationAndTraversal(){
        for((a,b) in listOf("/Art/Incoming" to "/Art/Incoming/Sorted","/Art" to "/Art","/Art/../Incoming" to "/Art","/" to "/Art")){
            try{NexusArtworkOrganization.validateRoots(a,b);fail("Invalid root admitted")}catch(expected:IllegalArgumentException){}
        }
    }
    @Test fun exactCanonicalNumbering(){
        assertEquals(10,NexusArtworkOrganization.sequence(listOf("Name - Series 2.jpg","NAME - SERIES 9.JPG","Name - Series 20.png","Name - Series extra 100.jpg"),"Name - Series","jpg"))
        assertEquals(1,NexusArtworkOrganization.sequence(listOf("Other 8.jpg"),"Name - Series","jpg"))
    }
}
