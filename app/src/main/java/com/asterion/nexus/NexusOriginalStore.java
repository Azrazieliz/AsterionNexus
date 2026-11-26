package com.asterion.nexus;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Crash-safe, content-addressed, independently readback-verified originals. */
public final class NexusOriginalStore {
    private NexusOriginalStore() {}
    public static final class VerifiedFile {
        public final String sha256;
        public final long length;
        public final File file;
        VerifiedFile(String sha256,long length,File file){
            this.sha256=sha256;this.length=length;this.file=file;
        }
    }
    public static VerifiedFile ingest(InputStream input,File privateRoot,long expectedLength,String expectedSha256)
            throws IOException {
        if(input==null||privateRoot==null)throw new IllegalArgumentException("Missing original input");
        if(expectedLength < -1L)throw new IllegalArgumentException("Invalid expected size");
        if(expectedSha256!=null&&!expectedSha256.matches("[a-fA-F0-9]{64}"))
            throw new IllegalArgumentException("Invalid SHA-256 expectation");
        File staging=new File(privateRoot,"staging"), originals=new File(privateRoot,"originals");
        if((!staging.isDirectory()&&!staging.mkdirs())||(!originals.isDirectory()&&!originals.mkdirs()))
            throw new IOException("Private library unavailable");
        File part=File.createTempFile("original-",".part",staging);
        try {
            MessageDigest sha=digest();
            long bytes=0;
            try(FileOutputStream out=new FileOutputStream(part)){
                byte[] buffer=new byte[256*1024];
                for(int n;(n=input.read(buffer))>=0;){
                    if(n==0)continue;
                    if(bytes>Long.MAX_VALUE-n)throw new IOException("Size overflow");
                    bytes+=n;
                    if(expectedLength>=0&&bytes>expectedLength)
                        throw new IOException("Original exceeds declared length");
                    out.write(buffer,0,n);
                    sha.update(buffer,0,n);
                }
                out.flush();out.getFD().sync();
            }
            if(bytes==0||expectedLength>=0&&bytes!=expectedLength)
                throw new IOException("Incomplete or empty original");
            String contentId=hex(sha.digest());
            if(expectedSha256!=null&&!contentId.equalsIgnoreCase(expectedSha256))
                throw new IOException("Original digest mismatch");
            File published=new File(originals,contentId);
            if(published.exists()){
                if(!matches(published,bytes,contentId))
                    throw new IOException("Existing original failed integrity verification");
                return new VerifiedFile(contentId,bytes,published);
            }
            try { Files.move(part.toPath(),published.toPath(),StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException e){throw new IOException("Atomic original publication unavailable",e);}
            if(!matches(published,bytes,contentId))
                throw new IOException("Published original failed readback verification");
            return new VerifiedFile(contentId,bytes,published);
        } finally {Files.deleteIfExists(part.toPath());}
    }
    public static boolean matches(File file,long length,String sha) throws IOException {
        if(!file.isFile()||length<=0||file.length()!=length)return false;
        MessageDigest md=digest();
        try(FileInputStream in=new FileInputStream(file)){
            byte[] buffer=new byte[256*1024];
            for(int n;(n=in.read(buffer))>=0;){if(n>0)md.update(buffer,0,n);}
        }
        return hex(md.digest()).equalsIgnoreCase(sha);
    }
    public static String sourceKey(String value){
        if(value==null)throw new IllegalArgumentException("Origin required");
        return hex(digest().digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static MessageDigest digest(){
        try{return MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private static String hex(byte[] d){
        char[] tab="0123456789abcdef".toCharArray(),out=new char[d.length*2];
        for(int i=0;i<d.length;i++){out[i*2]=tab[(d[i]&255)>>>4];out[i*2+1]=tab[d[i]&15];}
        return new String(out);
    }
}
