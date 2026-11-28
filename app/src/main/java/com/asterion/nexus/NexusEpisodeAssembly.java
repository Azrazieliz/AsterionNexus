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
        for(int k=0;k<2;k++)if(l.tracks[k]>=0)extractor.selectTrack(l.tracks[k]);
        long start=extractor.getSampleTime();require(start>=0,"Empty media");
        long[] last={-1,-1},delta={0,0},first={-1,-1};
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
            if(data.capacity()<size)data=ByteBuffer.allocateDirect((int)size);
            data.clear();int read=extractor.readSampleData(data,0);require(read==size,"Sample truncated");
            evidence.add(k,data,read);
            if(muxer!=null){
                info.set(0,read,Math.addExact(offset,time),(flags&MediaExtractor.SAMPLE_FLAG_SYNC)!=0?MediaCodec.BUFFER_FLAG_KEY_FRAME:0);
                data.position(0);data.limit(read);muxer.writeSampleData(output[k],data,info);
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
        return end;
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
        String identity="assembly-v1:"+name+":"+String.join(",",ids);
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
            .put("policy","preserve_every_sample").put("state","QUEUED").put("sources",sources);
        journal(manifest,project);registry.job(identity,"QUEUED",0,null);
        File output=null;MediaMuxer muxer=null;boolean started=false;
        try{
            Layout expected=null;
            for(String sha:ids){
                NexusRegistry.Item item=registry.find(sha);
                require(item!=null&&item.mime.startsWith("video/"),"Verified local episode unavailable");
                File original=registry.original(item);
                require(NexusOriginalStore.matches(original,item.length,item.sha256),"Source integrity verification failed");
                bytes=Math.addExact(bytes,item.length);inputs.add(original);
                MediaExtractor extractor=open(original);
                try{Layout l=layout(extractor);if(expected==null)expected=l;else compatible(expected,l);}
                finally{extractor.release();}
                sources.put(new JSONObject().put("sha256",sha).put("name",item.displayName).put("bytes",item.length));
            }
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
                    offset=Math.addExact(offset,transfer(extractor,l,muxer,tracks,offset,evidence));
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
