package com.asterion.nexus;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class NexusCjkTermsTest {
    private Set<String> terms(String s){return new HashSet<>(Arrays.asList(s.split(" ")));}
    @Test public void japaneseSubstring(){
        assertTrue(terms(NexusCjkTerms.index("私は魔法使いです").text)
            .containsAll(terms(NexusCjkTerms.query("魔法使い"))));
    }
    @Test public void koreanAndSupplementaryHan(){
        String s="한국어\uD840\uDC00";
        assertTrue(NexusCjkTerms.contains(s));
        assertTrue(NexusCjkTerms.index(s).text.contains("c20000"));
        assertTrue(terms(NexusCjkTerms.index(s).text)
            .containsAll(terms(NexusCjkTerms.query("한국"))));
    }
    @Test public void punctuationDoesNotJoinTerms(){
        assertFalse(terms(NexusCjkTerms.index("魔 法").text)
            .containsAll(terms(NexusCjkTerms.query("魔法"))));
    }
    @Test public void ftsSyntaxNeverPassedThrough(){
        String q=NexusCjkTerms.query("漢 OR * \"字\"");
        assertTrue(q.matches("[a-z0-9 ]+"));
        assertFalse(q.contains("OR"));
        assertFalse(NexusCjkTerms.contains("ordinary text"));
    }
    @Test public void emptyInput(){
        assertEquals("",NexusCjkTerms.index(null).text);
        assertTrue(NexusCjkTerms.index("").complete);
    }
}
