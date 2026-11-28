package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class FusionSmartCollectionsTest{
    @Test fun canonicalRuleRetainsExactIdentity(){
        val rule=FusionSmartCollections.Rule("character","CH00013",75)
        assertEquals(rule,FusionSmartCollections.Rule.parse(rule.json()))
        assertTrue(rule.coreRule)
    }
    @Test fun unknownLegacyRulesAreNotWildcardMatches(){
        assertNull(FusionSmartCollections.Rule.parse("{}"))
        assertNull(FusionSmartCollections.Rule.parse("{\"version\":\"nexus.smart.v0\",\"kind\":\"character\",\"value\":\"x\"}"))
    }
    @Test fun explicitZeroIsValidRating(){
        val zero=FusionSmartCollections.Rule("manual_rating","",0)
        assertEquals(zero,FusionSmartCollections.Rule.parse(zero.json()))
        assertTrue(zero.coreRule)
    }
    @Test fun localRulesAreNotCoreQueries(){
        val rule=FusionSmartCollections.Rule("media_type","books")
        assertFalse(rule.coreRule)
        assertEquals(rule,FusionSmartCollections.Rule.parse(rule.json()))
    }
    @Test fun malformedRulesAreRejected(){
        for(block in listOf<()->Any>(
            {FusionSmartCollections.Rule("tag","")},
            {FusionSmartCollections.Rule("manual_rating","",101)},
            {FusionSmartCollections.Rule("media_type","unknown")},
            {FusionSmartCollections.Rule("offline_pin","unexpected")}
        )){
            try{block();fail("Accepted malformed rule")}catch(_:IllegalArgumentException){}
        }
    }
}
