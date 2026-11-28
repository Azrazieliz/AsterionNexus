package com.asterion.nexus;

import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.Properties;
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
        directory(staging);directory(originals);
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
            final long verifiedLength=bytes;
            return locked(privateRoot,contentId,()->{
                File published=new File(originals,contentId);
                if(Files.exists(published.toPath(),LinkOption.NOFOLLOW_LINKS)){
                    if(!matches(published,verifiedLength,contentId))
                        throw new IOException("Existing original failed integrity verification");
                    return new VerifiedFile(contentId,verifiedLength,published);
                }
                try { Files.move(part.toPath(),published.toPath(),StandardCopyOption.ATOMIC_MOVE); }
                catch(AtomicMoveNotSupportedException e){throw new IOException("Atomic original publication unavailable",e);}
                if(!matches(published,verifiedLength,contentId))
                    throw new IOException("Published original failed readback verification");
                return new VerifiedFile(contentId,verifiedLength,published);
            });
        } finally {Files.deleteIfExists(part.toPath());}
    }
    public static boolean matches(File file,long length,String sha) throws IOException {
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||length<=0||file.length()!=length)return false;
        MessageDigest md=digest();
        try(FileInputStream in=new FileInputStream(file)){
            byte[] buffer=new byte[256*1024];
            for(int n;(n=in.read(buffer))>=0;){if(n>0)md.update(buffer,0,n);}
        }
        return hex(md.digest()).equalsIgnoreCase(sha);
    }

    private interface IO<T>{T run()throws IOException;}
    private static void directory(File dir)throws IOException{
        if(!dir.isDirectory()&&!dir.mkdirs()&&!dir.isDirectory())throw new IOException("Private library directory unavailable");
    }
    /** Both ingestion and repair serialize publication across the app's
     * independent Android processes. Lock files contain no owner data. */
    private static synchronized <T> T locked(File root,String sha,IO<T> action)throws IOException{
        File locks=new File(root,"locks");directory(locks);
        try(FileChannel channel=FileChannel.open(new File(locks,sha+".lock").toPath(),
                StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock ignored=channel.lock()){
            return action.run();
        }
    }
    /** Caller must have independently verified and durably recorded a
     * surviving remote copy. Never remove modified or non-regular bytes. */
    static boolean evictVerified(File root,long length,String sha)throws IOException{
        if(root==null||length<=0||sha==null||!sha.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid eviction identity");
        return locked(root,sha,()->{
            File target=new File(new File(root,"originals"),sha);
            if(!Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS))return false;
            if(!matches(target,length,sha))throw new IOException("Local original changed; cleanup refused");
            Files.delete(target.toPath());
            return true;
        });
    }
    public static final class Recovery {
        public final File restored,retainedDamage,receipt;
        public final boolean alreadyVerified;
        Recovery(File restored,File damage,File receipt,boolean unchanged){
            this.restored=restored;retainedDamage=damage;this.receipt=receipt;alreadyVerified=unchanged;
        }
    }
    /** Exact-copy recovery, never a heuristic repair. The expected hash and
     * length must come from an existing verified registry record. A damaged
     * predecessor is copied, synced and independently hashed before any
     * atomic replacement. A wrong candidate never changes the original. */
    public static Recovery recoverExact(InputStream input,File root,long length,String expectedSha)throws IOException{
        if(input==null||root==null||length<=0||expectedSha==null||!expectedSha.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Verified recovery identity required");
        File staging=new File(root,"staging"), originals=new File(root,"originals");
        directory(staging);directory(originals);
        File candidate=File.createTempFile("recovery-",".part",staging);
        try{
            copy(input,candidate,length);
            if(!matches(candidate,length,expectedSha))throw new IOException("Recovery copy does not match expected SHA-256");
            return locked(root,expectedSha,()->{
                File target=new File(originals,expectedSha);
                if(matches(target,length,expectedSha))return new Recovery(target,null,null,true);
                File recovery=new File(root,"recovery");directory(recovery);
                File incident=Files.createTempDirectory(recovery.toPath(),expectedSha+"-").toFile();
                File damaged=null;
                Properties journal=new Properties();
                journal.setProperty("expected_sha256",expectedSha);
                journal.setProperty("expected_bytes",Long.toString(length));
                if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS)){
                    if(!Files.isRegularFile(target.toPath(),LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("Original path is not a regular file");
                    long oldLength=target.length();
                    String oldHash=hash(target);
                    damaged=new File(incident,"damaged-original.bin");
                    try(InputStream old=new FileInputStream(target)){copy(old,damaged,oldLength);}
                    if(damaged.length()!=oldLength||!hash(damaged).equals(oldHash)
                        ||target.length()!=oldLength||!hash(target).equals(oldHash))
                        throw new IOException("Damaged predecessor could not be preserved");
                    journal.setProperty("retained_file",damaged.getName());
                    journal.setProperty("retained_sha256",oldHash);
                    journal.setProperty("retained_bytes",Long.toString(oldLength));
                }else journal.setProperty("predecessor","MISSING");
                File receipt=new File(incident,"receipt.properties");
                journal.setProperty("state","PREPARED");writeJournal(receipt,journal);
                try{Files.move(candidate.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(AtomicMoveNotSupportedException e){throw new IOException("Atomic repair unavailable; original retained",e);}
                if(!matches(target,length,expectedSha))throw new IOException("Restored original failed readback; predecessor retained");
                journal.setProperty("state","VERIFIED");writeJournal(receipt,journal);
                return new Recovery(target,damaged,receipt,false);
            });
        }finally{Files.deleteIfExists(candidate.toPath());}
    }
    private static void copy(InputStream input,File target,long exact)throws IOException{
        long written=0;
        try(FileOutputStream out=new FileOutputStream(target)){
            byte[] buf=new byte[262144];
            for(int n;(n=input.read(buf))!=-1;){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Recovery cancelled");
                if(n==0)continue;
                if(n>exact-written)throw new IOException("Recovery copy exceeds expected length");
                out.write(buf,0,n);written+=n;
            }
            if(written!=exact)throw new IOException("Recovery copy is incomplete");
            out.flush();out.getFD().sync();
        }
    }
    private static String hash(File file)throws IOException{
        MessageDigest md=digest();
        try(InputStream in=new FileInputStream(file)){
            byte[] buf=new byte[262144];
            for(int n;(n=in.read(buf))!=-1;)if(n>0)md.update(buf,0,n);
        }
        return hex(md.digest());
    }
    private static void writeJournal(File receipt,Properties properties)throws IOException{
        File part=File.createTempFile("receipt-",".part",receipt.getParentFile());
        try{
            try(FileOutputStream out=new FileOutputStream(part)){
                properties.store(out,"Nexus exact original recovery");
                out.flush();out.getFD().sync();
            }
            Files.move(part.toPath(),receipt.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(part.toPath());}
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
