package com.asterion.nexus;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaCodec;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;

/** Lossless, conservative native MP4 assembly. Never removes unreviewed content. */
public final class NexusEpisodeAssembly {
    private static final long MAX_SAMPLE=32L*1024*1024;
    private static final String[] KEYS={"mime","width","height","sample-rate","channel-count",
        "profile","level","rotation-degrees","color-standard","color-range","color-transfer",
        "pixel-aspect-ratio-width","pixel-aspect-ratio-height","aac-profile"};
    private NexusEpisodeAssembly(){}
    private static void require(boolean value,String reason)throws IOException{
        if(!value)throw new IOException(reason);
    }
    private static MediaExtractor open(File file)throws IOException{
        MediaExtractor extractor=new MediaExtractor();
        try{extractor.setDataSource(file.getAbsolutePath());return extractor;}
        catch(Exception e){extractor.release();throw new IOException("Cannot inspect media",e);}
    }
    private static int kind(MediaFormat format)throws IOException{
        String mime=format.getString(MediaFormat.KEY_MIME);
        if("video/avc".equals(mime))return 0;
        if("audio/mp4a-latm".equals(mime))return 1;
        throw new IOException("Unsupported track "+mime+"; no source tracks will be discarded");
    }
    private static String signature(MediaFormat f){
        StringBuilder b=new StringBuilder();
        for(String key:KEYS)if(f.containsKey(key)){
            b.append(key).append('=');
            if("mime".equals(key))b.append(f.getString(key));else b.append(f.getInteger(key));
            b.append(';');
        }
        for(int i=0;i<8;i++){
            ByteBuffer data=f.getByteBuffer("csd-"+i);
            if(data!=null){
                ByteBuffer copy=data.duplicate();byte[] bytes=new byte[copy.remaining()];copy.get(bytes);
                b.append(i).append(':').append(android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)).append(';');
            }
        }
        return b.toString();
    }
    private static final class Layout {
        final MediaFormat[] formats=new MediaFormat[2];
        final int[] tracks=new int[]{-1,-1};
        int count;
    }
    private static Layout layout(MediaExtractor extractor)throws IOException{
        Layout l=new Layout();
        for(int i=0;i<extractor.getTrackCount();i++){
            MediaFormat f=extractor.getTrackFormat(i);int k=kind(f);
            require(l.tracks[k]<0,"Multiple tracks of the same kind require explicit remapping");
            l.tracks[k]=i;l.formats[k]=f;l.count++;
        }
        require(l.tracks[0]>=0,"Video track missing");return l;
    }
    private static void compatible(Layout expected,Layout actual)throws IOException{
        require(expected.count==actual.count,"Track counts differ");
        for(int k=0;k<2;k++){
            require((expected.tracks[k]>=0)==(actual.tracks[k]>=0),"Track types differ");
            if(expected.tracks[k]>=0)require(signature(expected.formats[k]).equals(signature(actual.formats[k])),
                "Codec configuration differs; transcoding is required");
        }
    }
    private static final class Evidence {
        final MessageDigest[] hashes;
        final long[] counts={0,0};
        Evidence()throws Exception{hashes=new MessageDigest[]{MessageDigest.getInstance("SHA-256"),MessageDigest.getInstance("SHA-256")};}
        void add(int k,ByteBuffer sample,int size){
            hashes[k].update(ByteBuffer.allocate(4).putInt(size).array());
            ByteBuffer copy=sample.duplicate();copy.position(0);copy.limit(size);
            hashes[k].update(copy);counts[k]++;
        }
        void verify(Evidence actual)throws IOException{
            for(int k=0;k<2;k++)require(counts[k]==actual.counts[k]&&
                MessageDigest.isEqual(hashes[k].digest(),actual.hashes[k].digest()),"Output sample readback differs from sources");
        }
    }
    private static long transfer(MediaExtractor extractor,Layout l,MediaMuxer muxer,int[] output,long offset,
                                 Evidence evidence)throws Exception{
        return transfer(extractor,l,muxer,output,offset,evidence,0,Long.MAX_VALUE);
    }
    private static long transfer(MediaExtractor extractor,Layout l,MediaMuxer muxer,int[] output,long offset,
                                 Evidence evidence,long keepFrom,long keepUntil)throws Exception{
        for(int k=0;k<2;k++)if(l.tracks[k]>=0)extractor.selectTrack(l.tracks[k]);
        long start=extractor.getSampleTime();require(start>=0,"Empty media");
        long[] last={-1,-1},delta={0,0},first={-1,-1},keptFirst={-1,-1},keptLast={-1,-1},keptDelta={0,0};
        ByteBuffer data=ByteBuffer.allocateDirect(1024*1024);
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        while(extractor.getSampleTrackIndex()>=0){
            int index=extractor.getSampleTrackIndex(),k=index==l.tracks[0]?0:1;
            long time=extractor.getSampleTime()-start;
            long size=extractor.getSampleSize();
            require(size>0&&size<=MAX_SAMPLE,"Unsupported sample size");
            int flags=extractor.getSampleFlags();
            require((flags&(MediaExtractor.SAMPLE_FLAG_ENCRYPTED|MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME))==0,
                "Encrypted or partial samples require a different assembly engine");
            require(time>=0&&time>last[k],"Non-monotonic timestamps require transcoding");
            if(first[k]<0){
                first[k]=time;
                if(k==0)require((flags&MediaExtractor.SAMPLE_FLAG_SYNC)!=0,"Episode must start on a video keyframe");
            }
            if(last[k]>=0)delta[k]=time-last[k];
            last[k]=time;
            if(time>=keepFrom&&time<keepUntil){
                if(keptFirst[k]<0){
                    keptFirst[k]=time;
                    if(k==0)require((flags&MediaExtractor.SAMPLE_FLAG_SYNC)!=0,"Cut is not a video random-access point");
                }
                if(keptLast[k]>=0)keptDelta[k]=time-keptLast[k];
                keptLast[k]=time;
                if(data.capacity()<size)data=ByteBuffer.allocateDirect((int)size);
                data.clear();int read=extractor.readSampleData(data,0);require(read==size,"Sample truncated");
                evidence.add(k,data,read);
                if(muxer!=null){
                    info.set(0,read,Math.addExact(offset,time-keepFrom),(flags&MediaExtractor.SAMPLE_FLAG_SYNC)!=0?MediaCodec.BUFFER_FLAG_KEY_FRAME:0);
                    data.position(0);data.limit(read);muxer.writeSampleData(output[k],data,info);
                }
            }
            extractor.advance();
        }
        long end=0;
        for(int k=0;k<2;k++)if(l.tracks[k]>=0){
            require(first[k]>=0&&delta[k]>0,"Insufficient track samples");
            long measured=Math.addExact(last[k],delta[k]);
            long declared=l.formats[k].containsKey(MediaFormat.KEY_DURATION)?l.formats[k].getLong(MediaFormat.KEY_DURATION):measured;
            require(Math.abs(declared-measured)<=500000,"Unusual edit list or duration requires transcoding");
            end=Math.max(end,Math.max(declared,measured));
        }
        if(l.tracks[1]>=0)require(Math.abs((last[0]+delta[0])-(last[1]+delta[1]))<=500000,
            "Track durations differ excessively");
        if(keepFrom==0&&keepUntil==Long.MAX_VALUE)return end;
        long retainedEnd=0;
        for(int k=0;k<2;k++)if(l.tracks[k]>=0){
            require(keptFirst[k]>=0&&keptDelta[k]>0,"No usable samples after repeated edge cut");
            require(keptFirst[k]-keepFrom<=100000,"Repeated edge cut would desynchronize the tracks");
            long measured=keptLast[k]+keptDelta[k];
            retainedEnd=Math.max(retainedEnd,measured);
            if(keepUntil!=Long.MAX_VALUE)require(Math.abs(measured-keepUntil)<=100000,"Repeated edge boundary is not aligned");
        }
        if(l.tracks[1]>=0)require(Math.abs((keptLast[0]+keptDelta[0])-(keptLast[1]+keptDelta[1]))<=100000,
            "Repeated edge cut would change A/V synchronization");
        return Math.max(retainedEnd,Math.min(end,keepUntil))-keepFrom;
    }

    private static NexusRepeatedEdges.Episode scanEdges(File file,Layout l)throws Exception{
        long duration=0;
        for(MediaFormat format:l.formats)if(format!=null&&format.containsKey(MediaFormat.KEY_DURATION))
            duration=Math.max(duration,format.getLong(MediaFormat.KEY_DURATION));
        // No audio means exact audio+video evidence cannot be established.
        if(duration<=0||l.tracks[1]<0)return new NexusRepeatedEdges.Episode(Math.max(1,duration),Collections.emptyList());
        long origin;
        {
            MediaExtractor both=open(file);
            try{for(int track:l.tracks)if(track>=0)both.selectTrack(track);origin=both.getSampleTime();}
            finally{both.release();}
        }
        if(origin<0)return new NexusRepeatedEdges.Episode(duration,Collections.emptyList());
        ArrayList<Long> boundaries=new ArrayList<>();
        MediaExtractor video=open(file);
        try{
            video.selectTrack(l.tracks[0]);
            while(video.getSampleTrackIndex()>=0){
                long t=video.getSampleTime()-origin;
                if(t>=0&&t<duration&&(video.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_SYNC)!=0){
                    if(boundaries.isEmpty()||t>boundaries.get(boundaries.size()-1))boundaries.add(t);
                    if(boundaries.size()>20000)return new NexusRepeatedEdges.Episode(duration,Collections.emptyList());
                }
                video.advance();
            }
        }finally{video.release();}
        if(boundaries.isEmpty())return new NexusRepeatedEdges.Episode(duration,Collections.emptyList());
        boundaries.add(duration);
        int n=boundaries.size()-1;
        MessageDigest[][] hash=new MessageDigest[2][n];
        long[][] count=new long[2][n];
        for(int i=0;i<n;i++)if(boundaries.get(i+1)<=NexusRepeatedEdges.MAX||
                boundaries.get(i)>=duration-NexusRepeatedEdges.MAX){
            for(int k=0;k<2;k++)hash[k][i]=MessageDigest.getInstance("SHA-256");
        }
        ByteBuffer sample=ByteBuffer.allocateDirect(1024*1024);
        ByteBuffer meta=ByteBuffer.allocate(16);
        for(int k=0;k<2;k++){
            MediaExtractor ex=open(file);
            try{
                ex.selectTrack(l.tracks[k]);
                int i=0;long last=-1;
                while(ex.getSampleTrackIndex()>=0){
                    long time=ex.getSampleTime()-origin;
                    if(time<0||time<=last)return new NexusRepeatedEdges.Episode(duration,Collections.emptyList());
                    last=time;
                    while(i+1<n&&time>=boundaries.get(i+1))i++;
                    MessageDigest digest=hash[k][i];
                    if(digest!=null&&time>=boundaries.get(i)&&time<boundaries.get(i+1)){
                        long size=ex.getSampleSize();
                        int flags=ex.getSampleFlags();
                        if(size<=0||size>MAX_SAMPLE||(flags&(MediaExtractor.SAMPLE_FLAG_ENCRYPTED|MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME))!=0)
                            return new NexusRepeatedEdges.Episode(duration,Collections.emptyList());
                        if(sample.capacity()<size)sample=ByteBuffer.allocateDirect((int)size);
                        sample.clear();
                        int read=ex.readSampleData(sample,0);
                        if(read!=size)return new NexusRepeatedEdges.Episode(duration,Collections.emptyList());
                        meta.clear();meta.putLong(time-boundaries.get(i)).putInt(read).putInt(flags);
                        digest.update(meta.array());sample.position(0);sample.limit(read);digest.update(sample);count[k][i]++;
                    }
                    ex.advance();
                }
            }finally{ex.release();}
        }
        ArrayList<NexusRepeatedEdges.Span> spans=new ArrayList<>();
        for(int i=0;i<n;i++){
            String fingerprint=null;
            if(hash[0][i]!=null&&hash[1][i]!=null&&count[0][i]>0&&count[1][i]>0){
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                digest.update(hash[0][i].digest());digest.update(hash[1][i].digest());
                fingerprint=android.util.Base64.encodeToString(digest.digest(),android.util.Base64.NO_WRAP);
            }
            spans.add(new NexusRepeatedEdges.Span(boundaries.get(i),boundaries.get(i+1),fingerprint));
        }
        return new NexusRepeatedEdges.Episode(duration,spans);
    }
    /** Conservative repeated-edge suggestions for the transcoding editor.
     * Unsupported tracks, uncertain fingerprints and questionable cuts retain the complete episode.
     */
    public static List<long[]> transcodePlan(Context context,List<String> ids,List<Long> durations)throws Exception{
        require(ids.size()==durations.size()&&ids.size()>=2&&ids.size()<=100,"Invalid episode selection");
        NexusRegistry registry=new NexusRegistry(context);
        ArrayList<NexusRepeatedEdges.Episode> edges=new ArrayList<>();
        ArrayList<File> files=new ArrayList<>();
        for(int i=0;i<ids.size();i++){
            NexusRegistry.Item item=registry.find(ids.get(i));
            require(item!=null,"Episode unavailable");
            File file=NexusVideoInputs.file(context,item);files.add(file);
            NexusRepeatedEdges.Episode ep=new NexusRepeatedEdges.Episode(durations.get(i)*1000,Collections.emptyList());
            try{
                MediaExtractor ex=open(file);Layout l;
                try{l=layout(ex);}finally{ex.release();}
                ep=scanEdges(file,l);
            }catch(Exception uncertain){/* preserve complete footage */}
            edges.add(ep);
        }
        List<NexusRepeatedEdges.Cut> cuts=NexusRepeatedEdges.plan(edges);
        ArrayList<long[]> result=new ArrayList<>();
        for(int i=0;i<ids.size();i++){
            long duration=durations.get(i),start=0,end=duration;
            NexusRepeatedEdges.Cut cut=cuts.get(i);
            long until=cut.end==edges.get(i).duration?Long.MAX_VALUE:cut.end;
            if(cut.start!=0||until!=Long.MAX_VALUE){
                try{
                    MediaExtractor check=open(files.get(i));
                    try{transfer(check,layout(check),null,null,0,new Evidence(),cut.start,until);}
                    finally{check.release();}
                    long proposedStart=cut.start/1000;
                    long proposedEnd=until==Long.MAX_VALUE?duration:Math.min(duration,(until+999)/1000);
                    if(proposedStart>=0&&proposedStart<proposedEnd){start=proposedStart;end=proposedEnd;}
                }catch(Exception uncertain){/* preserve complete footage */}
            }
            result.add(new long[]{start,end});
        }
        return result;
    }
    private static void journal(File file,JSONObject project)throws Exception{
        AtomicFile atomic=new AtomicFile(file);FileOutputStream stream=atomic.startWrite();
        try{stream.write(project.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));atomic.finishWrite(stream);}
        catch(Exception e){atomic.failWrite(stream);throw e;}
    }
    public static synchronized NexusRegistry.Item assemble(Context context,String title,List<String> ids)throws Exception{
        String name=title.trim();
        require(!name.isEmpty()&&name.length()<=150&&!name.contains("/")&&!name.contains("\\")&&!name.matches(".*[\\p{Cntrl}].*"),"Invalid series title");
        require(ids.size()>=2&&ids.size()<=100&&new HashSet<>(ids).size()==ids.size(),"Select 2–100 distinct episodes in order");
        Context app=context.getApplicationContext();NexusRegistry registry=new NexusRegistry(app);
        File projects=new File(app.getFilesDir(),"nexus-assemblies");
        require(projects.isDirectory()||projects.mkdirs(),"Assembly storage unavailable");
        String identity="assembly-v2:"+name+":"+String.join(",",ids);
        String projectId=NexusOriginalStore.sourceKey(identity);
        File manifest=new File(projects,projectId+".json");
        if(manifest.isFile()){
            JSONObject previous=new JSONObject(new String(java.nio.file.Files.readAllBytes(manifest.toPath()),java.nio.charset.StandardCharsets.UTF_8));
            if("INDEXED".equals(previous.optString("state"))){
                NexusRegistry.Item existing=registry.find(previous.optString("output_sha256"));
                if(existing!=null&&NexusOriginalStore.matches(registry.original(existing),existing.length,existing.sha256))return existing;
            }
        }
        JSONArray sources=new JSONArray();ArrayList<File> inputs=new ArrayList<>();
        long bytes=0;
        JSONObject project=new JSONObject().put("project_id",projectId).put("title",name)
            .put("policy","exact_repeated_edges_only").put("state","QUEUED").put("sources",sources);
        journal(manifest,project);registry.job(identity,"QUEUED",0,null);
        File output=null;MediaMuxer muxer=null;boolean started=false;
        try{
            Layout expected=null;
            ArrayList<NexusRepeatedEdges.Episode> episodeEdges=new ArrayList<>();
            for(String sha:ids){
                NexusRegistry.Item item=registry.find(sha);
                require(item!=null&&item.mime.startsWith("video/"),"Verified local episode unavailable");
                File original=registry.original(item);
                require(NexusOriginalStore.matches(original,item.length,item.sha256),"Source integrity verification failed");
                bytes=Math.addExact(bytes,item.length);inputs.add(original);
                MediaExtractor extractor=open(original);
                Layout sourceLayout;
                try{sourceLayout=layout(extractor);if(expected==null)expected=sourceLayout;else compatible(expected,sourceLayout);}
                finally{extractor.release();}
                try{episodeEdges.add(scanEdges(original,sourceLayout));}
                catch(Exception uncertain){episodeEdges.add(new NexusRepeatedEdges.Episode(1,Collections.emptyList()));}
                sources.put(new JSONObject().put("sha256",sha).put("name",item.displayName).put("bytes",item.length));
            }
            List<NexusRepeatedEdges.Cut> cuts=NexusRepeatedEdges.plan(episodeEdges);
            // Reject the entire automatic cut plan if any boundary does not
            // preserve source A/V synchronization. Full assembly remains available.
            boolean cutsVerified=true;
            for(int i=0;i<inputs.size();i++){
                NexusRepeatedEdges.Cut cut=cuts.get(i);
                long until=cut.end==episodeEdges.get(i).duration?Long.MAX_VALUE:cut.end;
                if(cut.start==0&&until==Long.MAX_VALUE)continue;
                MediaExtractor check=open(inputs.get(i));
                try{transfer(check,layout(check),null,null,0,new Evidence(),cut.start,until);}
                catch(Exception uncertain){cutsVerified=false;break;}
                finally{check.release();}
            }
            if(!cutsVerified){
                cuts=new ArrayList<>();
                for(NexusRepeatedEdges.Episode ep:episodeEdges)cuts.add(new NexusRepeatedEdges.Cut(0,ep.duration));
                project.put("cut_plan_status","unproven_boundaries_preserved");
            }else project.put("cut_plan_status","validated_sample_boundaries");
            require(projects.getUsableSpace()>Math.addExact(Math.multiplyExact(bytes,2),64L*1024*1024),"Insufficient free space for verified output");
            output=File.createTempFile("assembly-",".mp4",projects);
            muxer=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int[] tracks={-1,-1};
            for(int k=0;k<2;k++)if(expected.tracks[k]>=0)tracks[k]=muxer.addTrack(expected.formats[k]);
            if(expected.formats[0].containsKey(MediaFormat.KEY_ROTATION))
                muxer.setOrientationHint(expected.formats[0].getInteger(MediaFormat.KEY_ROTATION));
            muxer.start();started=true;
            Evidence evidence=new Evidence();long offset=0;
            registry.job(identity,"RECEIVING",0,null);
            project.put("state","ASSEMBLING");journal(manifest,project);
            for(int i=0;i<inputs.size();i++){
                MediaExtractor extractor=open(inputs.get(i));
                try{
                    Layout l=layout(extractor);compatible(expected,l);
                    sources.getJSONObject(i).put("start_us",offset);
                    NexusRepeatedEdges.Cut cut=cuts.get(i);
                    long from=cut.start,until=cut.end==episodeEdges.get(i).duration?Long.MAX_VALUE:cut.end;
                    sources.getJSONObject(i).put("source_keep_from_us",from)
                        .put("source_keep_until_us",until==Long.MAX_VALUE?JSONObject.NULL:until)
                        .put("cut_evidence",from>0||until!=Long.MAX_VALUE?"exact_audio_video_packet_match_all_episodes":"no_proven_repeated_edge");
                    offset=Math.addExact(offset,transfer(extractor,l,muxer,tracks,offset,evidence,from,until));
                    sources.getJSONObject(i).put("end_us",offset);
                }finally{extractor.release();}
            }
            muxer.stop();started=false;muxer.release();muxer=null;
            registry.job(identity,"BYTES_COMPLETE",output.length(),null);
            MediaExtractor check=open(output);
            try{
                Layout l=layout(check);compatible(expected,l);
                Evidence actual=new Evidence();long duration=transfer(check,l,null,null,0,actual);
                evidence.verify(actual);require(Math.abs(duration-offset)<=1000000,"Output duration verification failed");
            }finally{check.release();}
            registry.job(identity,"VERIFIED",output.length(),null);
            NexusRegistry.Item result;
            try(InputStream in=new FileInputStream(output)){
                result=registry.ingest(in,name.replaceFirst("(?i)\\.mp4$","")+".mp4","video/mp4","nexus-assembly","device",identity,output.length(),null);
            }
            project.put("state","INDEXED").put("output_sha256",result.sha256).put("duration_us",offset);
            journal(manifest,project);registry.job(identity,"INDEXED",result.length,null);
            return result;
        }catch(Exception e){
            project.put("state","FAILED").put("error",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());
            journal(manifest,project);registry.job(identity,"FAILED",0,e.getClass().getSimpleName());throw e;
        }finally{
            if(muxer!=null){if(started)try{muxer.stop();}catch(Exception ignored){}try{muxer.release();}catch(Exception ignored){}}
            if(output!=null)output.delete();
        }
    }
}
