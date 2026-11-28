package com.asterion.nexus;
import org.junit.Test;
import static org.junit.Assert.*;
public class NexusDerivativePolicyTest {
    @Test public void fitsLandscapeWithoutCropping(){
        NexusDerivativePolicy.Size s=NexusDerivativePolicy.fit(6000,4000,2048);
        assertEquals(2048,s.width);assertEquals(1365,s.height);
    }
    @Test public void preservesSmallOriginalDimensions(){
        NexusDerivativePolicy.Size s=NexusDerivativePolicy.fit(640,480,4096);
        assertEquals(640,s.width);assertEquals(480,s.height);
    }
    @Test public void boundsSquareAndExtremeAspect(){
        NexusDerivativePolicy.Size s=NexusDerivativePolicy.fit(40000,40000,4096);
        assertTrue((long)s.width*s.height<=NexusDerivativePolicy.MAX_PIXELS);
        s=NexusDerivativePolicy.fit(1,100000,2048);
        assertEquals(1,s.width);assertEquals(2048,s.height);
    }
    @Test public void identitySeparatesOutputs(){
        String sha="a".repeat(64);
        assertNotEquals(NexusDerivativePolicy.identity(sha,"poster",2048,"png",100,0),
            NexusDerivativePolicy.identity(sha,"poster",2048,"png",100,1000000));
        assertNotEquals(NexusDerivativePolicy.identity(sha,"resize",2048,"png",100,0),
            NexusDerivativePolicy.identity(sha,"resize",1024,"png",100,0));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsInvalidOutput(){
        NexusDerivativePolicy.identity("a".repeat(64),"resize",4097,"png",100,0);
    }
    @Test public void namesDoNotCarryPaths(){
        assertFalse(NexusDerivativePolicy.name("folder/a.jpg","resize","png").contains("/"));
        assertTrue(NexusDerivativePolicy.name("photo.jpg","resize","png").endsWith(".png"));
    }
}
