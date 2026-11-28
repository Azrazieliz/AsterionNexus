
package com.asterion.nexus;

import java.util.*;

/** Fail-closed exact repeated-edge planner. A digest covers both tracks, packet
 * flags, lengths and timestamps relative to a video random-access boundary.
 * It does not infer a recap, OP or ED from visual similarity alone.
 */
final class NexusRepeatedEdges {
    static final long MIN=8_000_000L,MAX=180_000_000L;
    static final class Span {
        final long start,end;
        final String digest;
        Span(long start,long end,String digest){this.start=start;this.end=end;this.digest=digest;}
        long length(){return end-start;}
        boolean same(Span other){return digest!=null&&other.digest!=null&&
            length()>0&&length()==other.length()&&digest.equals(other.digest);}
    }
    static final class Episode {
        final long duration;
        final List<Span> spans;
        Episode(long duration,List<Span> spans){this.duration=duration;this.spans=spans;}
    }
    static final class Cut {
        final long start,end;
        Cut(long start,long end){this.start=start;this.end=end;}
    }
    static List<Cut> plan(List<Episode> episodes){
        List<Cut> result=new ArrayList<>();
        long prefix=0,suffix=0;
        if(episodes.size()>=3){
            long limit=MAX;
            for(Episode ep:episodes)limit=Math.min(limit,ep.duration/4);
            Episode first=episodes.get(0);
            for(int i=0;i<first.spans.size();i++){
                Span candidate=first.spans.get(i);
                if(candidate.start!=prefix||candidate.end>limit||candidate.digest==null)break;
                boolean match=true;
                for(Episode ep:episodes){
                    if(i>=ep.spans.size()||ep.spans.get(i).start!=prefix||
                        !candidate.same(ep.spans.get(i))){match=false;break;}
                }
                if(!match)break;
                prefix=candidate.end;
            }
            if(prefix<MIN)prefix=0;
            for(int i=1;i<=first.spans.size();i++){
                Span candidate=first.spans.get(first.spans.size()-i);
                long remaining=first.duration-candidate.start;
                if(candidate.end!=first.duration-suffix||remaining>limit||candidate.digest==null)break;
                boolean match=true;
                for(Episode ep:episodes){
                    if(i>ep.spans.size()){match=false;break;}
                    Span other=ep.spans.get(ep.spans.size()-i);
                    if(other.end!=ep.duration-suffix||!candidate.same(other)){match=false;break;}
                }
                if(!match)break;
                suffix=remaining;
            }
            if(suffix<MIN)suffix=0;
        }
        for(int i=0;i<episodes.size();i++){
            Episode ep=episodes.get(i);
            long start=i==0?0:prefix,end=i==episodes.size()-1?ep.duration:ep.duration-suffix;
            if(start<0||end>ep.duration||end<=start||end-start<ep.duration/2)
                result.add(new Cut(0,ep.duration));
            else result.add(new Cut(start,end));
        }
        return result;
    }
}
