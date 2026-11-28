package com.asterion.nexus;

import org.json.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

/** Reject malformed or externally dependent GLB before passing bytes to native code. */
final class NexusGltfAdmission {
    static void verify(byte[] bytes)throws IOException{
        try{
            if(bytes.length<28||bytes.length>NexusMesh.MAX_BYTES)throw new IOException("GLB size invalid");
            ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if(b.getInt()!=0x46546c67||b.getInt()!=2||b.getInt()!=bytes.length)throw new IOException("GLB header invalid");
            int n=b.getInt(),type=b.getInt();
            if(type!=0x4e4f534a||n<=0||n%4!=0||n>8*1024*1024||n>b.remaining()-8)throw new IOException("GLB JSON invalid");
            byte[] json=new byte[n];b.get(json);
            JSONObject root=new JSONObject(new String(json,StandardCharsets.UTF_8));
            if(!"2.0".equals(root.getJSONObject("asset").getString("version")))throw new IOException("Unsupported glTF version");
            JSONArray buffers=root.getJSONArray("buffers");
            if(buffers.length()!=1||buffers.getJSONObject(0).has("uri"))throw new IOException("External buffers unsupported");
            int binary=b.getInt(),tag=b.getInt(),declared=buffers.getJSONObject(0).getInt("byteLength");
            if(tag!=0x004e4942||binary<=0||binary%4!=0||binary!=b.remaining()||declared<=0||declared>binary||binary-declared>3)throw new IOException("GLB buffer invalid");
            JSONArray images=root.optJSONArray("images");
            if(images!=null){
                if(images.length()>128)throw new IOException("Texture count exceeds preview budget");
                for(int i=0;i<images.length();i++){
                    JSONObject image=images.getJSONObject(i);
                    if(image.has("uri")||!image.has("bufferView"))throw new IOException("External textures unsupported");
                }
            }
            JSONArray views=root.optJSONArray("bufferViews");
            if(views!=null)for(int i=0;i<views.length();i++){
                JSONObject v=views.getJSONObject(i);
                long start=v.optLong("byteOffset",0),length=v.getLong("byteLength");
                if(v.getInt("buffer")!=0||start<0||length<=0||start>declared||length>declared-start)throw new IOException("GLB buffer view invalid");
            }
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("Invalid GLB",e);}
    }
}
