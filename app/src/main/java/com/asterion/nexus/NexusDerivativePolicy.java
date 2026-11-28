package com.asterion.nexus;

import java.util.Locale;

/** Shared pure policy for image resize and representative video frame output. */
public final class NexusDerivativePolicy {
    private NexusDerivativePolicy(){}
    public static final int MAX_SIDE=4096;
    public static final long MAX_PIXELS=12000000;
    public static final class Size{
        public final int width,height;
        Size(int w,int h){width=w;height=h;}
    }
    public static Size fit(int width,int height,int maxSide){
        if(width<=0||height<=0||maxSide<128||maxSide>MAX_SIDE)
            throw new IllegalArgumentException("Invalid media dimensions");
        double scale=Math.min(1.0,Math.min(maxSide/(double)Math.max(width,height),
            Math.sqrt(MAX_PIXELS/((double)width*height))));
        return new Size(Math.max(1,(int)Math.floor(width*scale)),
            Math.max(1,(int)Math.floor(height*scale)));
    }
    public static String identity(String sha,String operation,int maxSide,String format,int quality,long timestampUs){
        if(sha==null||!sha.matches("[0-9a-f]{64}")||!java.util.Set.of("resize","poster","audio").contains(operation)
            ||!java.util.Set.of("png","jpg","webp","m4a").contains(format)
            ||maxSide<128||maxSide>MAX_SIDE||quality<1||quality>100||timestampUs<0)
            throw new IllegalArgumentException("Invalid derivative specification");
        return "nexus-derivative-v1:"+sha+":"+operation+":"+maxSide+":"+format+":"+quality+":"+timestampUs;
    }
    public static String name(String original,String operation,String format){
        String base=original==null?"Media":original.replaceAll("[\\p{Cntrl}/\\\\]+"," ").trim();
        int dot=base.lastIndexOf('.');
        if(dot>0)base=base.substring(0,dot);
        if(base.isBlank())base="Media";
        return base.substring(0,Math.min(180,base.length()))+" - "+operation.toLowerCase(Locale.ROOT)+"."+format;
    }
}
