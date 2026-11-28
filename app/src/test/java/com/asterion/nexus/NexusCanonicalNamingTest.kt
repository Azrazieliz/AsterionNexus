package com.asterion.nexus
import org.junit.Test
import org.junit.Assert.*
class NexusCanonicalNamingTest{
    @Test fun exactOwnerPatternsAndCounters(){
        val cosplay=NexusCanonicalNaming.Identity("cosplay","id","Character","Series",cosplayer="Cosplayer",album="original album")
        assertEquals("Character - Cosplayer 3.jpg",NexusCanonicalNaming.candidate(cosplay,"source.jpg",listOf("Character - Cosplayer 2.jpg")))
        val model=NexusCanonicalNaming.Identity("character_model","id","Character","Series",transformation="Form")
        assertEquals("Character (Form) 1.gltf.zip",NexusCanonicalNaming.candidate(model,"source.gltf.zip",emptyList()))
        val obj=NexusCanonicalNaming.Identity("object_model",objectName="Object")
        assertEquals("Object 1.stl",NexusCanonicalNaming.candidate(obj,"Object 1.stl",listOf("Object 1.stl")))
    }
    @Test fun missingOrUnsafeIdentityDoesNotInventName(){
        assertTrue(runCatching{NexusCanonicalNaming.prefix(NexusCanonicalNaming.Identity("character_model",character="Unverified"))}.isFailure)
        assertTrue(runCatching{NexusCanonicalNaming.prefix(NexusCanonicalNaming.Identity("object_model",objectName="../Object"))}.isFailure)
    }

    @Test fun destinationNameIsNotMistakenForTheSource(){
        val i=NexusCanonicalNaming.Identity("object_model",objectName="Object")
        assertEquals("Object 2.stl",NexusCanonicalNaming.candidate(i,"Object 1.stl",listOf("Object 1.stl"),false))
    }
}
