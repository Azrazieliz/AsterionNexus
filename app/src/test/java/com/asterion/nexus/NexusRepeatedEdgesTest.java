
package com.asterion.nexus;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class NexusRepeatedEdgesTest {
    private static NexusRepeatedEdges.Episode episode(String story,String tail){
        return new NexusRepeatedEdges.Episode(100_000_000L,Arrays.asList(
            new NexusRepeatedEdges.Span(0,10_000_000L,"opening"),
            new NexusRepeatedEdges.Span(10_000_000L,90_000_000L,story),
            new NexusRepeatedEdges.Span(90_000_000L,100_000_000L,tail)));
    }
    @Test public void keepFirstOpeningAndLastEnding(){
        List<NexusRepeatedEdges.Cut> cuts=NexusRepeatedEdges.plan(Arrays.asList(episode("a","ending"),episode("b","ending"),episode("c","ending")));
        assertEquals(0,cuts.get(0).start);
        assertEquals(90_000_000L,cuts.get(0).end);
        assertEquals(10_000_000L,cuts.get(1).start);
        assertEquals(90_000_000L,cuts.get(1).end);
        assertEquals(10_000_000L,cuts.get(2).start);
        assertEquals(100_000_000L,cuts.get(2).end);
    }
    @Test public void uncertainEndingAndTwoEpisodesNotCut(){
        List<NexusRepeatedEdges.Cut> cuts=NexusRepeatedEdges.plan(Arrays.asList(episode("a","ending"),episode("b","different"),episode("c","ending")));
        for(NexusRepeatedEdges.Cut cut:cuts)assertEquals(100_000_000L,cut.end);
        cuts=NexusRepeatedEdges.plan(Arrays.asList(episode("a","ending"),episode("b","ending")));
        for(NexusRepeatedEdges.Cut cut:cuts){assertEquals(0,cut.start);assertEquals(100_000_000L,cut.end);}
    }
    @Test public void failedFingerprintCannotAuthorizeCuts(){
        List<NexusRepeatedEdges.Cut> cuts=NexusRepeatedEdges.plan(Arrays.asList(episode("a","ending"),new NexusRepeatedEdges.Episode(100_000_000L,Collections.emptyList()),episode("c","ending")));
        for(NexusRepeatedEdges.Cut cut:cuts){assertEquals(0,cut.start);assertEquals(100_000_000L,cut.end);}
    }
}
