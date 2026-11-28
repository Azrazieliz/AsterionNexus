
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
        // Adjacent duplicate footage is removable only when exact A/V packet
        // evidence proves the same continuous material survives in another episode.
        // Never consume a witness used to justify another cut.
        List<List<Cut>> protectedSpans=new ArrayList<>();
        for(Episode ep:episodes)protectedSpans.add(new ArrayList<>());
        for(int i=1;i<episodes.size();i++)
            extend(episodes,result,protectedSpans,i,i-1,true);
        for(int i=episodes.size()-2;i>=0;i--)
            extend(episodes,result,protectedSpans,i,i+1,false);
        return result;
    }
    private static boolean overlaps(Cut a,Cut b){return a.start<b.end&&b.start<a.end;}
    private static void extend(List<Episode> episodes,List<Cut> kept,List<List<Cut>> protectedSpans,
                               int targetIndex,int witnessIndex,boolean early){
        Episode target=episodes.get(targetIndex), witness=episodes.get(witnessIndex);
        Cut current=kept.get(targetIndex), surviving=kept.get(witnessIndex);
        long limit=Math.min(MAX,target.duration/4);
        if(limit<MIN||target.spans.isEmpty()||witness.spans.isEmpty())return;
        // Bound comparisons to edge spans only (full-length un-hashed spans cannot match).
        ArrayList<Span> edge=new ArrayList<>();
        for(Span s:target.spans){
            if(s.start>=current.start&&s.end<=current.end&&s.digest!=null&&
                (early?s.end<=limit:s.start>=target.duration-limit))edge.add(s);
        }
        if(!early)Collections.reverse(edge);
        if(edge.isEmpty()||(early?edge.get(0).start!=current.start:edge.get(0).end!=current.end))return;
        ArrayList<Span> proof=new ArrayList<>();
        for(Span s:witness.spans)
            if(s.digest!=null&&s.start>=surviving.start&&s.end<=surviving.end)proof.add(s);
        if(!early)Collections.reverse(proof);
        Cut best=null, bestProof=null;
        long bestLength=0;
        for(int j=0;j<proof.size();j++){
            long length=0;
            for(int k=0;k<edge.size()&&j+k<proof.size();k++){
                Span a=edge.get(k),q=proof.get(j+k);
                if(!a.same(q))break;
                if(k>0){
                    Span pa=edge.get(k-1),pq=proof.get(j+k-1);
                    if(early?(pa.end!=a.start||pq.end!=q.start):(a.end!=pa.start||q.end!=pq.start))break;
                }
                length+=a.length();
                if(length<MIN||length<=bestLength)continue;
                Cut candidate=early?new Cut(current.start,a.end):new Cut(a.start,current.end);
                Cut keptAfter=early?new Cut(candidate.end,current.end):new Cut(current.start,candidate.start);
                if(keptAfter.end-keptAfter.start<target.duration/2)break;
                if(protectedSpans.get(targetIndex).stream().anyMatch(x->overlaps(x,candidate)))break;
                Span first=proof.get(j);
                best=candidate;
                bestProof=new Cut(Math.min(first.start,q.start),Math.max(first.end,q.end));
                bestLength=length;
            }
        }
        if(best!=null){
            kept.set(targetIndex,early?new Cut(best.end,current.end):new Cut(current.start,best.start));
            protectedSpans.get(witnessIndex).add(bestProof);
        }
    }
}
