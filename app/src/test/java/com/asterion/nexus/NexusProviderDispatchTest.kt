package com.asterion.nexus

import org.junit.Assert.*
import org.junit.Test

class NexusProviderDispatchTest {
    @Test fun exactMagnetAndDescriptorUseNativeTorrentSelection(){
        assertEquals(NexusProviderDispatch.Route.TORRENT,NexusProviderDispatch.route("magnet:?xt=urn:btih:0123456789012345678901234567890123456789","GAMES","",null,false))
        assertEquals(NexusProviderDispatch.Route.TORRENT,NexusProviderDispatch.route("https://example.org/archive.torrent","GAMES","",null,false))
    }
    @Test fun existingQueueIsNotResubmitted(){
        assertEquals(NexusProviderDispatch.Route.ALREADY_TRACKED,NexusProviderDispatch.route("https://example.org/book.epub","BOOKS","",null,true))
    }
    @Test fun galleryDoesNotBecomeIsolatedImage(){
        assertEquals(NexusProviderDispatch.Route.GALLERY,NexusProviderDispatch.route("https://example.org/gallery/1","COSPLAY","image",null,false))
    }
    @Test fun nonHttpsIsRejected(){
        assertThrows(IllegalArgumentException::class.java){NexusProviderDispatch.route("http://example.org/file.zip","ASSETS","",null,false)}
    }
}
