package com.asterion.nexus;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.security.*;
import java.text.Normalizer;

/** Bounded ZIP/CBZ preflight and byte-verified extraction. Archive names are
 * metadata only: no untrusted entry path is ever passed to the filesystem.
 * ZIP64, encryption and unsupported member types fail closed. */
public final class NexusZipExtraction {
    private NexusZipExtraction(){}
    public static final long MAX_ARCHIVE=1024L*1024*1024;
    public static final long MAX_TOTAL=1024L*1024*1024;
    public static final long MAX_ENTRY=256L*1024*1024;
    private static final int MAX_ENTRIES=10000;
    public static final class Entry {
        public final String path;
        public final long length;
        public final long crc;
        private final ZipEntry zip;
        Entry(String path,ZipEntry zip){this.path=path;this.zip=zip;length=zip.getSize();crc=zip.getCrc();}
    }
    public static final class Plan {
        public final List<Entry> files;
        public final long bytes;
        Plan(List<Entry> files,long bytes){this.files=Collections.unmodifiableList(files);this.bytes=bytes;}
    }
    public static final class Verified {
        public final Entry entry;
        public final File file;
        public final String sha256;
        Verified(Entry entry,File file,String sha){this.entry=entry;this.file=file;this.sha256=sha;}
    }
    private static void require(boolean valid,String message)throws IOException{
        if(!valid)throw new IOException(message);
    }
    private static void interrupt()throws InterruptedIOException{
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Extraction cancelled");
    }
    private static int u16(RandomAccessFile r)throws IOException{
        return r.readUnsignedByte()|(r.readUnsignedByte()<<8);
    }
    private static long u32(RandomAccessFile r)throws IOException{
        return (long)u16(r)|((long)u16(r)<<16);
    }
    /** Check central-directory flags and Unix file mode, which java.util.zip
     * does not expose. Require a single-disk ordinary ZIP with exact bounds. */
    private static int checkDirectory(File archive)throws IOException{
        require(archive.isFile()&&archive.length()>=22&&archive.length()<=MAX_ARCHIVE,
            "ZIP source exceeds supported size or is missing");
        try(RandomAccessFile r=new RandomAccessFile(archive,"r")){
            long end=-1,length=r.length();
            for(long p=length-22;p>=Math.max(0,length-65557);p--){
                r.seek(p);
                if(u32(r)==0x06054b50L){
                    r.seek(p+20);
                    if(p+22+u16(r)==length){end=p;break;}
                }
            }
            require(end>=0,"ZIP central directory missing");
            r.seek(end+4);
            require(u16(r)==0&&u16(r)==0,"Multi-disk ZIP unsupported");
            int diskCount=u16(r),count=u16(r);
            require(count==diskCount&&count>0&&count<=MAX_ENTRIES,"ZIP entry count unsupported");
            long size=u32(r),offset=u32(r);
            require(offset+size==end&&offset>=0&&size>=46L*count,"ZIP central directory bounds invalid");
            r.seek(offset);
            for(int i=0;i<count;i++){
                interrupt();
                require(r.getFilePointer()+46<=end&&u32(r)==0x02014b50L,"Invalid central directory header");
                int creator=u16(r);u16(r);
                int flags=u16(r),method=u16(r);
                require((flags&0x2041)==0,"Encrypted ZIP unsupported");
                require(method==ZipEntry.STORED||method==ZipEntry.DEFLATED,"Unsupported ZIP compression");
                r.skipBytes(8);
                long compressed=u32(r),expanded=u32(r);
                require(compressed!=0xffffffffL&&expanded!=0xffffffffL,"ZIP64 unsupported");
                int name=u16(r),extra=u16(r),comment=u16(r);
                require(u16(r)==0,"Multi-disk member unsupported");
                u16(r);
                long attributes=u32(r),localOffset=u32(r);
                require(localOffset<offset,"Invalid ZIP member location");
                int kind=(int)((attributes>>>16)&0170000);
                if((creator>>>8)==3||(creator>>>8)==19)
                    require(kind==0||kind==0100000||kind==0040000,"ZIP contains a symbolic link or special file");
                require(name>0&&r.getFilePointer()+name+extra+comment<=end,"Invalid ZIP member metadata");
                r.seek(r.getFilePointer()+name+extra+comment);
            }
            require(r.getFilePointer()==end,"Unexpected central directory data");
            return count;
        }
    }
    private static String safePath(String raw,boolean directory)throws IOException{
        require(raw!=null&&!raw.isEmpty()&&raw.length()<=1024,"Invalid ZIP member name");
        String path=Normalizer.normalize(raw,Normalizer.Form.NFC);
        if(directory&&path.endsWith("/"))path=path.substring(0,path.length()-1);
        require(!path.startsWith("/")&&!path.contains("\\")&&!path.contains(":"),"Unsafe ZIP member path");
        String[] parts=path.split("/",-1);
        require(parts.length<=32,"ZIP nesting depth exceeded");
        for(String p:parts){
            require(!p.isEmpty()&&!p.equals(".")&&!p.equals("..")&&p.length()<=255
                &&!p.endsWith(".")&&!p.endsWith(" "),"Unsafe ZIP path component");
            for(int i=0;i<p.length();i++)require(!Character.isISOControl(p.charAt(i)),"Invalid ZIP member character");
        }
        return path;
    }
    public static Plan inspect(File source)throws IOException{
        int expected=checkDirectory(source);
        List<Entry> files=new ArrayList<>();
        Set<String> explicit=new HashSet<>(),dirs=new HashSet<>(),leaves=new HashSet<>();
        long total=0;int seen=0;
        try(ZipFile zip=new ZipFile(source)){
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()){
                interrupt();
                ZipEntry member=entries.nextElement();seen++;
                require(seen<=expected,"ZIP entry count mismatch");
                String path=safePath(member.getName(),member.isDirectory());
                String key=path.toLowerCase(Locale.ROOT);
                require(explicit.add(key),"Duplicate or case-colliding ZIP member");
                for(int slash=key.indexOf('/');slash>=0;slash=key.indexOf('/',slash+1)){
                    String parent=key.substring(0,slash);
                    require(!leaves.contains(parent),"ZIP file/directory collision");dirs.add(parent);
                }
                if(member.isDirectory()){
                    require(!leaves.contains(key)&&member.getSize()==0,"Invalid ZIP directory member");
                    dirs.add(key);continue;
                }
                require(!dirs.contains(key),"ZIP file/directory collision");leaves.add(key);
                long n=member.getSize(),packed=member.getCompressedSize();
                require(n>0&&n<=MAX_ENTRY&&packed>=0&&member.getCrc()>=0,"Empty or oversized ZIP member unsupported");
                require(n<=Math.max(1024L*1024,packed*200L),"ZIP expansion ratio exceeded");
                require(n<=MAX_TOTAL-total,"ZIP expanded size exceeded");
                total+=n;files.add(new Entry(path,member));
            }
        }
        require(seen==expected&&!files.isEmpty(),"ZIP has no supported extractable files");
        return new Plan(files,total);
    }
    /** Validate every member into a new private staging directory before any
     * output is handed to the registry. Caller owns cleanup of that directory. */
    public static List<Verified> stage(File source,File staging,Plan plan)throws IOException{
        return stage(source,staging,plan,()->false);
    }
    public static List<Verified> stage(File source,File staging,Plan plan,java.util.function.BooleanSupplier cancelled)throws IOException{
        require(staging.isDirectory()&&staging.list()!=null&&staging.list().length==0,"Staging must be empty");
        require(staging.getUsableSpace()>plan.bytes+32L*1024*1024,"Insufficient extraction space");
        List<Verified> result=new ArrayList<>();
        try(ZipFile zip=new ZipFile(source)){
            for(Entry entry:plan.files){
                interrupt();if(cancelled.getAsBoolean())throw new InterruptedIOException("Extraction cancelled");
                ZipEntry current=zip.getEntry(entry.zip.getName());
                require(current!=null&&current.getSize()==entry.length&&current.getCrc()==entry.crc,"ZIP changed after preview");
                File output=File.createTempFile("entry-",".part",staging);
                CRC32 crc=new CRC32();
                MessageDigest hash;
                try{hash=MessageDigest.getInstance("SHA-256");}
                catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
                long total=0;
                try(InputStream input=zip.getInputStream(current);FileOutputStream out=new FileOutputStream(output)){
                    byte[] buf=new byte[65536];
                    for(int n;(n=input.read(buf))!=-1;){
                        interrupt();if(cancelled.getAsBoolean())throw new InterruptedIOException("Extraction cancelled");if(n==0)continue;
                        require(n<=entry.length-total,"ZIP member exceeds declared length");
                        out.write(buf,0,n);crc.update(buf,0,n);hash.update(buf,0,n);total+=n;
                    }
                    out.flush();out.getFD().sync();
                }
                require(total==entry.length&&crc.getValue()==entry.crc,"ZIP member CRC or length mismatch");
                StringBuilder sha=new StringBuilder(64);
                for(byte b:hash.digest())sha.append(String.format(Locale.ROOT,"%02x",b&255));
                require(NexusOriginalStore.matches(output,total,sha.toString()),"Staged member readback failed");
                result.add(new Verified(entry,output,sha.toString()));
            }
            return Collections.unmodifiableList(result);
        }catch(IOException|RuntimeException e){
            File[] files=staging.listFiles();
            if(files!=null)for(File f:files)if(f.isFile())f.delete();
            throw e;
        }
    }
}
