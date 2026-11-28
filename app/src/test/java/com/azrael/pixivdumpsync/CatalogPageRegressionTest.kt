package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class CatalogPageRegressionTest {
    @Test fun catalogPagesAreDisjointAndRetainEvidence(){
        val hits=(1..65).map{CatalogHit("Fixture $it","fixture","https://example.org/$it.epub",CatalogKind.EPUB)}
        val outcome=CatalogOutcome(hits,listOf("One source unavailable"),4,3)
        val pages=(0..3).map{CatalogDiscovery.pageSlice(outcome,it)}
        assertEquals(listOf(30,30,5,0),pages.map{it.hits.size})
        assertEquals(hits,pages.flatMap{it.hits})
        assertTrue(pages.all{it.failures==outcome.failures&&it.successful==3&&it.searched==4})
    }
    @Test fun emptyOrUnavailableCatalogDoesNotCreateResults(){
        val outcome=CatalogOutcome(emptyList(),listOf("Provider unavailable"),1,0)
        assertEquals(outcome,CatalogDiscovery.pageSlice(outcome,0))
        assertEquals(outcome,CatalogDiscovery.pageSlice(outcome,1))
    }
}
