package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class FusionVisualSimilarityTest {
    private fun vector(a:Float,b:Float)=FloatArray(FusionVisualLinearMath.FEATURE_WIDTH).apply{
        this[0]=a;this[1]=b
    }

    @Test fun matchingDirectionRanksHighest(){
        val query=FusionVisualSimilarity.quantize(vector(2f,1f))!!
        val same=FusionVisualSimilarity.quantize(vector(10f,5f))!!
        val other=FusionVisualSimilarity.quantize(vector(0f,1f))!!
        val opposite=FusionVisualSimilarity.quantize(vector(-2f,-1f))!!
        assertEquals(1.0,FusionVisualSimilarity.score(query,same)!!,0.00001)
        assertTrue(FusionVisualSimilarity.score(query,other)!! < 0.6)
        assertEquals(-1.0,FusionVisualSimilarity.score(query,opposite)!!,0.00001)
    }

    @Test fun invalidVectorsAreNotMatches(){
        assertNull(FusionVisualSimilarity.quantize(vector(0f,0f)))
        assertNull(FusionVisualSimilarity.quantize(vector(Float.NaN,1f)))
        assertNull(FusionVisualSimilarity.quantize(FloatArray(10){1f}))
        val valid=FusionVisualSimilarity.quantize(vector(1f,0f))!!
        assertNull(FusionVisualSimilarity.score(valid,ByteArray(valid.size)))
        assertNull(FusionVisualSimilarity.score(valid,ByteArray(2)))
    }
}
