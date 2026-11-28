package com.asterion.nexus;

import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.zip.*;

/** A bounded, in-memory 3D archive. No extraction to disk or external/network resolution. */
final class NexusGltfBundle {
    private static final int LIMIT=NexusMesh.MAX_BYTES;
    private static String safePath(String name)throws IOException{
        if(name.isEmpty()||name.length()>512||name.startsWith("/")||name.indexOf('\\')>=0||name.indexOf(':')>=0||name.chars().anyMatch(Character::isISOControl))
            throw new IOException("Unsafe model resource path");
        for(String part:name.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IOException("Unsafe model resource path");
        return name;
    }
    static byte[] normalize(byte[] input)throws IOException{
        if(input.length<4||input.length>LIMIT)throw new IOException("Model size invalid");
        if(input[0]!='P'||input[1]!='K'||input[2]!=3||input[3]!=4)return NexusGltfEmbedded.asGlb(input);
        Map<String,byte[]> files=new HashMap<>();
        long total=0;int count=0;String model=null;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(input))){
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;){
                if(++count>512)throw new IOException("Model archive has too many entries");
                String raw=entry.getName();
                if(entry.isDirectory()){safePath(raw.endsWith("/")?raw.substring(0,raw.length()-1):raw);if(zip.read()!=-1)throw new IOException("Nonempty directory entry");continue;}
                String name=safePath(raw);
                if(files.containsKey(name))throw new IOException("Duplicate model archive entry");
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                byte[] buffer=new byte[32768];
                for(int n;(n=zip.read(buffer))!=-1;){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Cancelled");
                    total+=n;
                    if(total>LIMIT)throw new IOException("Expanded model archive exceeds budget");
                    out.write(buffer,0,n);
                }
                files.put(name,out.toByteArray());
                String lower=name.toLowerCase(Locale.ROOT);
                if(lower.endsWith(".gltf")||lower.endsWith(".glb")){
                    if(model!=null)throw new IOException("Package must contain exactly one model");
                    model=name;
                }
            }
        }
        if(model==null)throw new IOException("No glTF model in package");
        final String prefix=model.substring(0,model.lastIndexOf('/')+1);
        return NexusGltfEmbedded.asGlb(files.get(model),uri->{
            try{
                URI reference=new URI(uri);
                if(reference.isAbsolute()||reference.getAuthority()!=null||reference.getQuery()!=null||reference.getFragment()!=null||reference.getPath()==null)
                    throw new IOException("Only relative packaged model resources are allowed");
                String name=safePath(prefix+safePath(reference.getPath()));
                byte[] bytes=files.get(name);
                if(bytes==null)throw new IOException("Missing packaged model resource: "+name);
                return bytes;
            }catch(java.net.URISyntaxException ex){throw new IOException("Invalid packaged resource URI",ex);}
        });
    }
}
