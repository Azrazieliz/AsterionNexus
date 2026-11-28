package com.asterion.nexus;

import java.util.*;

/** Private Unicode-codepoint unigram/bigram terms for languages where
 * unicode61's word tokenization cannot find substrings inside a sentence.
 * Original text is always retained and exact-match checked after ranking. */
public final class NexusCjkTerms {
    private NexusCjkTerms(){}
    private static final int CAP=50000;
    public static final class Terms{
        public final String text;
        public final boolean complete;
        Terms(String text,boolean complete){this.text=text;this.complete=complete;}
    }
    static boolean cjk(int cp){
        Character.UnicodeScript script=Character.UnicodeScript.of(cp);
        return script==Character.UnicodeScript.HAN||script==Character.UnicodeScript.HIRAGANA
            ||script==Character.UnicodeScript.KATAKANA||script==Character.UnicodeScript.HANGUL;
    }
    public static boolean contains(String value){
        return value!=null&&value.codePoints().anyMatch(NexusCjkTerms::cjk);
    }
    public static Terms index(String value){
        if(value==null||value.isEmpty())return new Terms("",true);
        Set<String> tokens=new LinkedHashSet<>();
        int previous=-1;
        for(int i=0;i<value.length();){
            int cp=value.codePointAt(i);i+=Character.charCount(cp);
            if(!cjk(cp)){previous=-1;continue;}
            tokens.add("c"+Integer.toHexString(cp));
            if(previous>=0)tokens.add("g"+Integer.toHexString(previous)+"x"+Integer.toHexString(cp));
            previous=cp;
            if(tokens.size()>CAP)return new Terms(String.join(" ",tokens),false);
        }
        return new Terms(String.join(" ",tokens),true);
    }
    public static String query(String value){
        // Generated terms contain only ASCII letters and digits, never user
        // FTS operators. FTS4 uses implicit AND between these exact tokens.
        return index(value).text;
    }
}
