package com.asterion.nexus

import org.junit.Assert.*
import org.junit.Test

class NexusAutomaticOrganizationTest {
    @Test fun standaloneFormatsAreConservative(){
        for(name in listOf("Book.epub","Comic.cbz","Music.FLAC","Film.mp4","Scan.pdf"))
            assertTrue(name,NexusAutomaticOrganization.standalone(name))
        for(name in listOf("model.obj","texture.png","scene.blend","asset.zip","film.srt","project.psd","application.apk","archive.rar","file.json","unknown"))
            assertFalse(name,NexusAutomaticOrganization.standalone(name))
    }
}
