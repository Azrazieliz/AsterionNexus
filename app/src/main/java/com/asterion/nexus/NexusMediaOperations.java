package com.asterion.nexus;

import android.content.Context;
import android.graphics.*;
import android.media.*;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Original-preserving media operations. Metadata, packet probes and decoded
 * representative frames are deliberately distinct from full-file validation. */
public final class NexusMediaOperations {
    private NexusMediaOperations(){}
    private static final long MAX_SAMPLE=32L*1024*1024;
    private static void require(boolean value,String reason)throws IOException{
        if(!value)throw new IOException(reason);
    }
    private static File checked(NexusRegistry registry,NexusRegistry.Item item)throws IOException{
        File file=registry.original(item);
        require(registry.verifyOriginal(item),"Recorded original SHA-256 mismatch");
        return file;
    }
    private static void checkStop()throws InterruptedIOException{
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Operation cancelled");
    }
    private static MediaExtractor extractor(File file)throws IOException{
        MediaExtractor ex=new MediaExtractor();
        try{ex.setDataSource(file.getAbsolutePath());return ex;}
        catch(Exception e){ex.release();throw new IOException("Media parser unavailable",e);}
    }
    private static String hash(File file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)){
            byte[] buf=new byte[131072];
            for(int n;(n=in.read(buf))!=-1;){checkStop();if(n>0)digest.update(buf,0,n);}
        }
        StringBuilder s=new StringBuilder(64);
        for(byte b:digest.digest())s.append(String.format(Locale.ROOT,"%02x",b&255));
        return s.toString();
    }
    private static void write(File destination,JSONObject data)throws IOException{
        AtomicFile receipt=new AtomicFile(destination);
        FileOutputStream out=null;
        try{out=receipt.startWrite();out.write(data.toString(2).getBytes(StandardCharsets.UTF_8));receipt.finishWrite(out);}
        catch(Exception e){if(out!=null)receipt.failWrite(out);throw new IOException("Receipt write failed",e);}
    }
    private static File receipts(Context context)throws IOException{
        File dir=new File(context.getFilesDir(),"nexus-media-receipts");
        require(dir.isDirectory()||dir.mkdirs()||dir.isDirectory(),"Media receipt storage unavailable");
        return dir;
    }
    private static JSONObject fields(MediaFormat f)throws JSONException{
        JSONObject row=new JSONObject();
        String[] integer={"width","height","rotation-degrees","sample-rate","channel-count","bitrate","profile","level","color-standard","color-range","color-transfer","pcm-encoding","max-input-size","aac-profile"};
        for(String key:integer)if(f.containsKey(key))try{row.put(key,f.getInteger(key));}catch(Exception ignored){}
        for(String key:new String[]{"mime","language"})if(f.containsKey(key))try{row.put(key,f.getString(key));}catch(Exception ignored){}
        if(f.containsKey("durationUs"))try{row.put("duration_us",f.getLong("durationUs"));}catch(Exception ignored){}
        if(f.containsKey("frame-rate"))try{row.put("frame_rate",f.getNumber("frame-rate"));}catch(Exception ignored){}
        return row;
    }
    public static JSONObject inspect(Context context,NexusRegistry.Item item)throws Exception{
        NexusRegistry registry=new NexusRegistry(context);
        File file=checked(registry,item);
        JSONObject report=new JSONObject().put("version",1).put("source_sha256",item.sha256)
            .put("source_bytes",item.length).put("mime",item.mime)
            .put("parser","Android native ImageDecoder/MediaExtractor/MediaMetadataRetriever")
            .put("android_api",android.os.Build.VERSION.SDK_INT).put("full_source_hash_verified",true);
        if(item.mime.startsWith("image/")){
            final JSONObject image=new JSONObject();
            Bitmap bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(file),(decoder,info,src)->{
                try{
                    int w=info.getSize().getWidth(),h=info.getSize().getHeight();
                    image.put("width",w).put("height",h).put("animated",info.isAnimated());
                    if(info.getColorSpace()!=null)image.put("color_space",info.getColorSpace().getName());
                    NexusDerivativePolicy.Size size=NexusDerivativePolicy.fit(w,h,1024);
                    decoder.setTargetSize(size.width,size.height);
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                }catch(JSONException e){throw new IllegalStateException(e);}
            });
            try{image.put("representative_decode",true).put("decoded_width",bitmap.getWidth())
                .put("decoded_height",bitmap.getHeight()).put("alpha",bitmap.hasAlpha());}
            finally{bitmap.recycle();}
            report.put("image",image);
        }else if(item.mime.startsWith("video/")||item.mime.startsWith("audio/")){
            JSONArray tracks=new JSONArray();
            MediaExtractor ex=extractor(file);
            try{
                require(ex.getTrackCount()>0&&ex.getTrackCount()<=128,"Unsupported track count");
                for(int i=0;i<ex.getTrackCount();i++){
                    checkStop();
                    MediaFormat format=ex.getTrackFormat(i);
                    JSONObject row=fields(format).put("track",i);
                    ex.selectTrack(i);
                    try{
                        long duration=row.optLong("duration_us",-1);
                        JSONArray probes=new JSONArray();
                        ByteBuffer packet=ByteBuffer.allocateDirect(65536);
                        for(long time:new long[]{0,Math.max(0,duration/2),Math.max(0,duration-1000000)}){
                            checkStop();ex.seekTo(time,MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                            long size=ex.getSampleSize();
                            JSONObject probe=new JSONObject().put("requested_us",time);
                            if(size>0&&size<=MAX_SAMPLE){
                                if(packet.capacity()<size)packet=ByteBuffer.allocateDirect((int)size);
                                packet.clear();
                                int read=ex.readSampleData(packet,0);
                                probe.put("packet_read",read==size).put("sample_bytes",size)
                                    .put("sample_time_us",ex.getSampleTime())
                                    .put("sync",(ex.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_SYNC)!=0)
                                    .put("encrypted",(ex.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0);
                            }else probe.put("packet_read",false).put("reason","No bounded packet");
                            probes.put(probe);
                        }
                        row.put("packet_probes",probes);
                    }finally{ex.unselectTrack(i);}
                    tracks.put(row);
                }
            }finally{ex.release();}
            report.put("tracks",tracks);
            if(item.mime.startsWith("video/")){
                MediaMetadataRetriever r=new MediaMetadataRetriever();
                try{
                    r.setDataSource(file.getAbsolutePath());
                    long duration=parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                    int w=(int)parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                    int h=(int)parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                    JSONArray probes=new JSONArray();
                    if(w>0&&h>0&&duration>0&&duration<=Long.MAX_VALUE/1000){
                        NexusDerivativePolicy.Size s=NexusDerivativePolicy.fit(w,h,640);
                        for(long t:new long[]{0,duration*500,Math.max(0,duration-1000)*1000}){
                            checkStop();
                            JSONObject probe=new JSONObject().put("requested_us",t);
                            Bitmap frame=r.getScaledFrameAtTime(t,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,s.width,s.height);
                            if(frame!=null){try{probe.put("decoded",true).put("width",frame.getWidth()).put("height",frame.getHeight());}finally{frame.recycle();}}
                            else probe.put("decoded",false);
                            probes.put(probe);
                        }
                    }
                    report.put("representative_video_frames",probes);
                }finally{r.release();}
            }
        }else throw new IOException("No supported native media parser");
        require(registry.verifyOriginal(item),"Source changed during inspection");
        report.put("coverage","Representative decode/packet probes only; not full decoder validation");
        write(new File(receipts(context),item.sha256+"-inspection-v1.json"),report);
        return report;
    }
    private static long parse(String value){
        try{return Long.parseLong(value);}catch(Exception e){return -1;}
    }
    private static String mime(String extension){
        if("jpg".equals(extension))return "image/jpeg";
        if("png".equals(extension))return "image/png";
        if("webp".equals(extension))return "image/webp";
        throw new IllegalArgumentException("Unsupported image output");
    }
    public static synchronized NexusRegistry.Item derive(Context context,NexusRegistry.Item item,
            String operation,int maxSide,String format,int quality,long timestampUs)throws Exception{
        require("resize".equals(operation)||"poster".equals(operation),"Unsupported operation");
        String outputMime=mime(format);
        String identity=NexusDerivativePolicy.identity(item.sha256,operation,maxSide,format,quality,timestampUs);
        NexusRegistry registry=new NexusRegistry(context);
        File source=checked(registry,item);
        File receipt=new File(receipts(context),NexusOriginalStore.sourceKey(identity)+".json");
        if(receipt.isFile()&&receipt.length()<65536){
            JSONObject prior=new JSONObject(new String(java.nio.file.Files.readAllBytes(receipt.toPath()),StandardCharsets.UTF_8));
            if("INDEXED".equals(prior.optString("state"))){
                NexusRegistry.Item old=registry.find(prior.optString("output_sha256"));
                if(old!=null&&registry.verifyOriginal(old))return old;
            }
        }
        registry.job(identity,"QUEUED",0,null);
        File output=null;Bitmap bitmap=null;
        JSONObject journal=new JSONObject().put("version",1).put("source_sha256",item.sha256)
            .put("operation",operation).put("max_side",maxSide).put("format",format)
            .put("quality",quality).put("requested_us",timestampUs).put("state","PROCESSING");
        try{
            write(receipt,journal);
            registry.job(identity,"RECEIVING",0,null);
            checkStop();
            if("resize".equals(operation)){
                require(item.mime.startsWith("image/"),"Image required");
                bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(source),(decoder,info,src)->{
                    if(info.isAnimated())throw new IllegalArgumentException("Animated image conversion would discard frames");
                    NexusDerivativePolicy.Size s=NexusDerivativePolicy.fit(info.getSize().getWidth(),info.getSize().getHeight(),maxSide);
                    decoder.setTargetSize(s.width,s.height);decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
                });
            }else{
                require(item.mime.startsWith("video/"),"Video required");
                MediaMetadataRetriever r=new MediaMetadataRetriever();
                try{
                    r.setDataSource(source.getAbsolutePath());
                    long duration=parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                    require(duration>0&&duration<=Long.MAX_VALUE/1000&&timestampUs<duration*1000,"Timestamp outside video");
                    int w=(int)parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                    int h=(int)parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                    NexusDerivativePolicy.Size s=NexusDerivativePolicy.fit(w,h,maxSide);
                    bitmap=r.getScaledFrameAtTime(timestampUs,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,s.width,s.height);
                    require(bitmap!=null,"No decoded frame");
                    journal.put("frame_selection","closest sync frame; not exact timestamp");
                }finally{r.release();}
            }
            require(bitmap!=null&&bitmap.getWidth()>0&&bitmap.getHeight()>0,"Decoded bitmap unavailable");
            require((long)bitmap.getWidth()*bitmap.getHeight()<=NexusDerivativePolicy.MAX_PIXELS
                &&Math.max(bitmap.getWidth(),bitmap.getHeight())<=maxSide,"Decoded image exceeds output bounds");
            require(!"jpg".equals(format)||!bitmap.hasAlpha(),"JPEG could lose transparency; select PNG or WebP");
            Bitmap.CompressFormat encoder="png".equals(format)?Bitmap.CompressFormat.PNG:
                "webp".equals(format)?Bitmap.CompressFormat.WEBP_LOSSLESS:Bitmap.CompressFormat.JPEG;
            output=File.createTempFile("nexus-derivative-","."+format,context.getCacheDir());
            try(FileOutputStream out=new FileOutputStream(output)){
                require(bitmap.compress(encoder,quality,out),"Image encoder failed");out.flush();out.getFD().sync();
            }
            int width=bitmap.getWidth(),height=bitmap.getHeight();
            bitmap.recycle();bitmap=null;
            require(output.length()>0&&output.length()<=128L*1024*1024,"Unexpected encoded size");
            registry.job(identity,"BYTES_COMPLETE",output.length(),null);
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(output.getAbsolutePath(),bounds);
            require(bounds.outWidth==width&&bounds.outHeight==height&&outputMime.equals(bounds.outMimeType),"Encoded image format/geometry mismatch");
            Bitmap readback=BitmapFactory.decodeFile(output.getAbsolutePath());
            require(readback!=null,"Encoded image cannot be decoded");
            try{require(readback.getWidth()==width&&readback.getHeight()==height,"Readback geometry mismatch");}
            finally{readback.recycle();}
            String sha=hash(output);
            require(registry.verifyOriginal(item),"Source changed during generation");
            registry.job(identity,"VERIFIED",output.length(),null);
            NexusRegistry.Item result;
            try(InputStream in=new FileInputStream(output)){
                result=registry.ingest(in,NexusDerivativePolicy.name(item.displayName,operation,format),
                    outputMime,"nexus-derivative","device",identity,output.length(),sha);
            }
            require(registry.verifyOriginal(result),"Registered derivative integrity mismatch");
            journal.put("state","INDEXED").put("output_sha256",result.sha256).put("output_bytes",result.length)
                .put("width",width).put("height",height).put("metadata_policy","Original metadata retained in source; derived image does not copy EXIF");
            write(receipt,journal);registry.job(identity,"INDEXED",result.length,null);
            return result;
        }catch(Exception e){
            try{registry.job(identity,"FAILED",0,e.getClass().getSimpleName());journal.put("state","FAILED").put("error",e.getClass().getSimpleName());write(receipt,journal);}catch(Exception ignored){}
            throw e;
        }finally{if(bitmap!=null)bitmap.recycle();if(output!=null)output.delete();}
    }
}
