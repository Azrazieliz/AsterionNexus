package com.asterion.nexus;

import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Normalize a self-contained glTF 2.0 JSON model to GLB without fetching external resources.
 * Source bytes are never changed. All binary buffers and embedded images are bounded. */
final class NexusGltfEmbedded {
    private static final int LIMIT=NexusMesh.MAX_BYTES;
    private static final int JSON_LIMIT=8*1024*1024;
    private static byte[] data(String uri,String prefix)throws IOException{
        if(!uri.startsWith(prefix))throw new IOException("External or unsupported resource; import an embedded GLB");
        try{return Base64.getDecoder().decode(uri.substring(prefix.length()));}
        catch(IllegalArgumentException ex){throw new IOException("Malformed embedded resource",ex);}
    }
    private static void pad(ByteArrayOutputStream out)throws IOException{
        while((out.size()&3)!=0){if(out.size()>=LIMIT)throw new IOException("Model budget exceeded");out.write(0);}
    }
    private static void append(ByteArrayOutputStream out,byte[] bytes)throws IOException{
        if(bytes.length>LIMIT-out.size())throw new IOException("Model budget exceeded");
        out.write(bytes);pad(out);
    }
    static byte[] asGlb(byte[] input)throws IOException{
        if(input.length<4||input.length>LIMIT)throw new IOException("Model size invalid");
        if(ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).getInt()==0x46546c67)return input;
        try{
            JSONObject root=new JSONObject(new String(input,StandardCharsets.UTF_8));
            if(!"2.0".equals(root.getJSONObject("asset").getString("version")))throw new IOException("Unsupported glTF version");
            JSONArray buffers=root.getJSONArray("buffers"),views=root.optJSONArray("bufferViews");
            if(buffers.length()<1||buffers.length()>128)throw new IOException("Buffer count invalid");
            if(views==null){views=new JSONArray();root.put("bufferViews",views);}
            ByteArrayOutputStream bin=new ByteArrayOutputStream();
            int[] offsets=new int[buffers.length()],lengths=new int[buffers.length()];
            for(int i=0;i<buffers.length();i++){
                JSONObject b=buffers.getJSONObject(i);
                String uri=b.getString("uri");
                String prefix=uri.startsWith("data:application/gltf-buffer;base64,")?"data:application/gltf-buffer;base64,":"data:application/octet-stream;base64,";
                byte[] bytes=data(uri,prefix);
                if(bytes.length<=0||bytes.length!=b.getInt("byteLength"))throw new IOException("Embedded buffer length mismatch");
                offsets[i]=bin.size();lengths[i]=bytes.length;append(bin,bytes);
            }
            for(int i=0;i<views.length();i++){
                JSONObject view=views.getJSONObject(i);
                int old=view.getInt("buffer");long offset=view.optLong("byteOffset",0),length=view.getLong("byteLength");
                if(old<0||old>=lengths.length||offset<0||length<=0||offset>lengths[old]||length>lengths[old]-offset)throw new IOException("Embedded buffer view invalid");
                // Extension-specific alternate buffer locations cannot safely be rewritten.
                if(view.has("extensions"))throw new IOException("Extended buffer views require native GLB");
                view.put("buffer",0).put("byteOffset",offsets[old]+offset);
            }
            JSONArray images=root.optJSONArray("images");
            if(images!=null){
                if(images.length()>128)throw new IOException("Texture count exceeds budget");
                for(int i=0;i<images.length();i++){
                    JSONObject image=images.getJSONObject(i);
                    if(!image.has("uri"))continue;
                    if(image.has("bufferView"))throw new IOException("Ambiguous image resource");
                    String uri=image.getString("uri");
                    String mime=uri.startsWith("data:image/png;base64,")?"image/png":uri.startsWith("data:image/jpeg;base64,")?"image/jpeg":null;
                    if(mime==null)throw new IOException("External or unsupported image resource");
                    byte[] bytes=data(uri,"data:"+mime+";base64,");
                    if(bytes.length==0)throw new IOException("Empty texture");
                    int offset=bin.size();append(bin,bytes);
                    int view=views.length();
                    views.put(new JSONObject().put("buffer",0).put("byteOffset",offset).put("byteLength",bytes.length));
                    image.remove("uri");image.put("bufferView",view).put("mimeType",mime);
                }
            }
            root.put("buffers",new JSONArray().put(new JSONObject().put("byteLength",bin.size())));
            byte[] json=root.toString().getBytes(StandardCharsets.UTF_8);
            int aligned=(json.length+3)&~3;
            if(aligned>JSON_LIMIT||12L+8+aligned+8+bin.size()>LIMIT)throw new IOException("Normalized GLB exceeds budget");
            ByteBuffer result=ByteBuffer.allocate(12+8+aligned+8+bin.size()).order(ByteOrder.LITTLE_ENDIAN);
            result.putInt(0x46546c67).putInt(2).putInt(result.capacity());
            result.putInt(aligned).putInt(0x4e4f534a).put(json);
            for(int i=json.length;i<aligned;i++)result.put((byte)32);
            result.putInt(bin.size()).putInt(0x004e4942).put(bin.toByteArray());
            return result.array();
        }catch(IOException ex){throw ex;}catch(Exception ex){throw new IOException("Invalid embedded glTF",ex);}
    }
}
