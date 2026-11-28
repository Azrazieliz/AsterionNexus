package com.asterion.nexus;

import android.content.Context;
import android.media.*;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Selected-track AAC remux. No re-encoding, discarded audio packets or
 * mislabeled codec output; unsupported streams remain in the source. */
public final class NexusAudioExtraction {
    private NexusAudioExtraction(){}
    public static final class Track {
        public final int index;
        public final String description;
        Track(int index,String description){this.index=index;this.description=description;}
    }
    private static void require(boolean value,String reason)throws IOException{
        if(!value)throw new IOException(reason);
    }
    private static MediaExtractor open(File file)throws IOException{
        MediaExtractor ex=new MediaExtractor();
        try{ex.setDataSource(file.getAbsolutePath());return ex;}
        catch(Exception e){ex.release();throw new IOException("Unable to parse media",e);}
    }
    public static List<Track> tracks(Context context,NexusRegistry.Item item)throws IOException{
        NexusRegistry registry=new NexusRegistry(context);
        File source=registry.original(item);
        require(registry.verifyOriginal(item),"Original hash mismatch");
        ArrayList<Track> result=new ArrayList<>();
        MediaExtractor ex=open(source);
        try{
            for(int i=0;i<ex.getTrackCount();i++){
                MediaFormat f=ex.getTrackFormat(i);
                if(!"audio/mp4a-latm".equals(f.getString(MediaFormat.KEY_MIME)))continue;
                String lang=f.getString("language");
                result.add(new Track(i,"Track "+(i+1)+" · AAC"+
                    (lang==null?"":" · "+lang)+
                    (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)?" · "+f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)+" channels":"")+
                    (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)?" · "+f.getInteger(MediaFormat.KEY_SAMPLE_RATE)+" Hz":"")));
            }
        }finally{ex.release();}
        return result;
    }
    private static final class Evidence{
        long count,last=-1,first=-1;
        final MessageDigest hash;
        Evidence()throws Exception{hash=MessageDigest.getInstance("SHA-256");}
        void add(ByteBuffer sample,int size,long time)throws IOException{
            require(time>=0&&time>last,"Nonmonotonic AAC timeline");
            if(first<0)first=time;
            hash.update(ByteBuffer.allocate(12).putInt(size).putLong(time-first).array());
            ByteBuffer data=sample.duplicate();data.position(0);data.limit(size);hash.update(data);
            last=time;count++;
        }
    }
    private static Evidence transfer(MediaExtractor ex,int index,MediaMuxer muxer,int target)throws Exception{
        ex.selectTrack(index);
        Evidence evidence=new Evidence();
        ByteBuffer data=ByteBuffer.allocateDirect(65536);
        MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        while(ex.getSampleTrackIndex()>=0){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Extraction cancelled");
            long size=ex.getSampleSize();
            require(size>0&&size<=4L*1024*1024,"Unsupported AAC sample size");
            int flags=ex.getSampleFlags();
            require((flags&(MediaExtractor.SAMPLE_FLAG_ENCRYPTED|MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME))==0,
                "Encrypted or partial AAC sample");
            if(data.capacity()<size)data=ByteBuffer.allocateDirect((int)size);
            data.clear();int read=ex.readSampleData(data,0);require(read==size,"AAC packet truncated");
            long time=ex.getSampleTime();
            evidence.add(data,read,time);
            if(muxer!=null){
                info.set(0,read,time-evidence.first,0);
                data.position(0);data.limit(read);muxer.writeSampleData(target,data,info);
            }
            if(!ex.advance())break;
        }
        require(evidence.count>0,"Audio track is empty");
        return evidence;
    }
    private static boolean sameCodec(MediaFormat a,MediaFormat b){
        if(!Objects.equals(a.getString(MediaFormat.KEY_MIME),b.getString(MediaFormat.KEY_MIME)))return false;
        for(String k:new String[]{"sample-rate","channel-count"})
            if(!a.containsKey(k)||!b.containsKey(k)||a.getInteger(k)!=b.getInteger(k))return false;
        ByteBuffer left=a.getByteBuffer("csd-0"),right=b.getByteBuffer("csd-0");
        return left!=null&&right!=null&&left.duplicate().equals(right.duplicate());
    }
    private static void write(File file,JSONObject value)throws Exception{
        AtomicFile receipt=new AtomicFile(file);FileOutputStream stream=null;
        try{stream=receipt.startWrite();stream.write(value.toString().getBytes(StandardCharsets.UTF_8));receipt.finishWrite(stream);}
        catch(Exception e){if(stream!=null)receipt.failWrite(stream);throw e;}
    }
    public static synchronized NexusRegistry.Item extract(Context context,NexusRegistry.Item item,int track)throws Exception{
        NexusRegistry registry=new NexusRegistry(context);
        File source=registry.original(item);
        require(registry.verifyOriginal(item),"Original hash mismatch");
        String identity="nexus-aac-extraction-v1:"+item.sha256+":"+track;
        File root=new File(context.getFilesDir(),"nexus-media-receipts");
        require(root.isDirectory()||root.mkdirs()||root.isDirectory(),"Receipt storage unavailable");
        File receipt=new File(root,NexusOriginalStore.sourceKey(identity)+".json");
        if(receipt.isFile()&&receipt.length()<65536){
            JSONObject prior=new JSONObject(new String(java.nio.file.Files.readAllBytes(receipt.toPath()),StandardCharsets.UTF_8));
            if("INDEXED".equals(prior.optString("state"))){
                NexusRegistry.Item previous=registry.find(prior.optString("output_sha256"));
                if(previous!=null&&registry.verifyOriginal(previous))return previous;
            }
        }
        JSONObject journal=new JSONObject().put("version",1).put("source_sha256",item.sha256)
            .put("track",track).put("operation","lossless_aac_remux").put("state","PROCESSING");
        File output=null;
        registry.job(identity,"QUEUED",0,null);
        try{
            write(receipt,journal);
            output=File.createTempFile("nexus-audio-",".m4a",context.getCacheDir());
            registry.job(identity,"RECEIVING",0,null);
            MediaExtractor ex=open(source);
            MediaFormat format;
            Evidence expected;
            try{
                require(track>=0&&track<ex.getTrackCount(),"Selected track missing");
                format=ex.getTrackFormat(track);
                require("audio/mp4a-latm".equals(format.getString(MediaFormat.KEY_MIME)),"Only AAC supports lossless M4A extraction");
                require(format.getByteBuffer("csd-0")!=null,"Missing AAC codec configuration");
                MediaMuxer muxer=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                try{
                    int target=muxer.addTrack(format);muxer.start();
                    expected=transfer(ex,track,muxer,target);muxer.stop();
                }finally{muxer.release();}
            }finally{ex.release();}
            try(RandomAccessFile file=new RandomAccessFile(output,"rw")){file.getFD().sync();}
            registry.job(identity,"BYTES_COMPLETE",output.length(),null);
            MediaExtractor verify=open(output);
            try{
                require(verify.getTrackCount()==1&&sameCodec(format,verify.getTrackFormat(0)),"Output codec configuration changed");
                Evidence actual=transfer(verify,0,null,-1);
                require(expected.count==actual.count&&MessageDigest.isEqual(expected.hash.digest(),actual.hash.digest()),
                    "Audio packet or relative timestamp readback mismatch");
            }finally{verify.release();}
            require(registry.verifyOriginal(item),"Source changed during extraction");
            registry.job(identity,"VERIFIED",output.length(),null);
            NexusRegistry.Item result;
            try(InputStream input=new FileInputStream(output)){
                result=registry.ingest(input,NexusDerivativePolicy.name(item.displayName,"audio track "+(track+1),"m4a"),
                    "audio/mp4","nexus-derivative","device",identity,output.length(),null);
            }
            require(registry.verifyOriginal(result),"Registered output failed readback");
            journal.put("state","INDEXED").put("output_sha256",result.sha256).put("output_bytes",result.length)
                .put("packets",expected.count).put("verification","all AAC packet bytes and relative timestamps");
            write(receipt,journal);registry.job(identity,"INDEXED",result.length,null);
            return result;
        }catch(Exception e){
            try{registry.job(identity,"FAILED",0,e.getClass().getSimpleName());journal.put("state","FAILED").put("error",e.getClass().getSimpleName());write(receipt,journal);}catch(Exception ignored){}
            throw e;
        }finally{if(output!=null)output.delete();}
    }
}
