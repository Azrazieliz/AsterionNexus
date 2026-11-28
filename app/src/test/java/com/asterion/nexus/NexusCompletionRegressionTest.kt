package com.asterion.nexus

import org.junit.Test
import org.junit.Assert.*

class NexusCompletionRegressionTest {
    private val a="00000000-0000-0000-0000-000000000001"
    private val b="00000000-0000-0000-0000-000000000002"
    @Test fun remoteIdentityIsAccountScoped(){
        val one=NexusCloudOperations.Remote(a,1,"/old",42,"abc")
        assertTrue(one.same(one.copy(path="/new")))
        assertFalse(one.same(one.copy(account=b)))
        assertFalse(one.same(one.copy(fsId=2)))
        assertFalse(one.same(one.copy(bytes=43)))
        assertFalse(one.same(one.copy(md5="def")))
    }
    @Test fun moveDestinationMustBeCanonical(){
        assertEquals("/a/file.txt",NexusCloudOperations.target("/a/","file.txt"))
        assertEquals("/file.txt",NexusCloudOperations.target("/","file.txt"))
        for(path in listOf("relative","","//a","/../a","/a//b")){
            try{NexusCloudOperations.target(path,"file.txt");fail("Accepted "+path)}
            catch(_:IllegalArgumentException){}
        }
        try{NexusCloudOperations.target("/a","../file.txt");fail("Accepted traversal")}
        catch(_:IllegalArgumentException){}
    }
    @Test fun cjkRoutingAndMixedLanguage(){
        assertEquals("ja",NexusTranslationQueue.language("日本語の文章"))
        assertEquals("ko",NexusTranslationQueue.language("한국어 문장"))
        assertEquals("zh",NexusTranslationQueue.language("繁體中文"))
        assertNull(NexusTranslationQueue.language("English text"))
        assertNull(NexusTranslationQueue.language("ひらがな 한글"))
        assertTrue(NexusTranslationQueue.hasCjk("English 中文"))
        assertFalse(NexusTranslationQueue.hasCjk("English only"))
    }
}
