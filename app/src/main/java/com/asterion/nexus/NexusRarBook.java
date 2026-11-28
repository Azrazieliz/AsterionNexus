package com.asterion.nexus;

import com.github.junrar.Archive;
import com.github.junrar.ArchiveOptions;
import com.github.junrar.rarfile.FileHeader;
import com.github.junrar.rarfile.HostSystem;
import com.openlocal.office.core.BookArchive;
import java.io.*;
import java.util.*;
import java.util.zip.CRC32;
import java.text.Normalizer;

/** Page-addressable, private RAR4 reading. No archive member name is used as
 * a physical path. Reject multipart, solid, redirected and encrypted sources
 * rather than run unbounded decompression or silently omit pages. */
public final class NexusRarBook implements Closeable {
    private static final long MAX_ARCHIVE=1024L*1024*1024,MAX_EXPANDED=2L*1024*1024*1024;
    private static final int MAX_PAGE=32*1024*1024,MAX_ENTRIES=10000;
    private final Archive archive;
    private final List<Page> pages=new ArrayList<>();
    private boolean closed;
    private static final class Page{
        final FileHeader header;final String name,mime;
        Page(FileHeader header,String name,String mime){this.header=header;this.name=name;this.mime=mime;}
    }
    private static void require(boolean valid,String message)throws IOException{
        if(!valid)throw new IOException(message);
    }
    public static boolean supported(String mime){
        return "application/vnd.comicbook-rar".equalsIgnoreCase(mime)
            ||"application/x-cbr".equalsIgnoreCase(mime);
    }
    static String safeName(String name)throws IOException{
        require(name!=null&&!name.isEmpty()&&name.length()<=1024,"Invalid archive name");
        String path=Normalizer.normalize(name.replace('\\','/'),Normalizer.Form.NFC);
        if(path.endsWith("/"))path=path.substring(0,path.length()-1);
        require(!path.startsWith("/")&&!path.contains(":"),"Unsafe archive path");
        String[] parts=path.split("/",-1);
        require(parts.length<=32,"Archive nesting limit exceeded");
        for(String part:parts){
            require(!part.isEmpty()&&!part.equals(".")&&!part.equals("..")&&part.length()<=255,"Unsafe archive path component");
            for(int i=0;i<part.length();i++)require(!Character.isISOControl(part.charAt(i)),"Invalid archive path character");
        }
        return path;
    }
    static String imageMime(String name){
        for(String segment:name.split("/"))if(segment.startsWith(".")||segment.equalsIgnoreCase("__MACOSX"))return null;
        String n=name.toLowerCase(Locale.ROOT);
        if(n.endsWith(".jpg")||n.endsWith(".jpeg"))return "image/jpeg";
        if(n.endsWith(".png"))return "image/png";
        if(n.endsWith(".webp"))return "image/webp";
        if(n.endsWith(".gif"))return "image/gif";
        if(n.endsWith(".bmp"))return "image/bmp";
        if(n.endsWith(".avif"))return "image/avif";
        return null;
    }
    public NexusRarBook(File source)throws Exception{
        require(source.isFile()&&source.length()>7&&source.length()<=MAX_ARCHIVE,"RAR source unavailable or exceeds 1 GiB");
        // RAR4 has a known CRC32 member contract. RAR5 and SFX are not silently
        // admitted through this adapter until their separate acceptance tests exist.
        try(InputStream input=new FileInputStream(source)){
            byte[] marker={0x52,0x61,0x72,0x21,0x1a,0x07,0x00};
            for(byte b:marker)require(input.read()==(b&255),"Unsupported RAR generation; RAR4 required");
        }
        archive=new Archive(source,ArchiveOptions.builder().maxDictionarySize(32L*1024*1024).build());
        try{
            require(archive.getMainHeader()!=null&&!archive.getMainHeader().isMultiVolume()
                &&!archive.getMainHeader().isSolid()&&!archive.isPasswordProtected(),"Encrypted, multipart or solid RAR unsupported");
            List<FileHeader> headers=archive.getFileHeaders();
            require(!headers.isEmpty()&&headers.size()<=MAX_ENTRIES,"RAR entry count unsupported");
            Set<String> names=new HashSet<>();
            long expanded=0;
            for(FileHeader h:headers){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("RAR opening cancelled");
                String name=safeName(h.getFileName());
                require(names.add(name.toLowerCase(Locale.ROOT)),"Duplicate archive member");
                require(!h.isEncrypted()&&!h.isSplitBefore()&&!h.isSplitAfter()&&!h.isSolid()
                    &&h.getRedirection()==null,"Unsupported archive member");
                if(h.getHostOS()==HostSystem.unix){
                    int kind=h.getFileAttr()&0170000;
                    require(kind==0||kind==0100000||kind==0040000,"Special archive member unsupported");
                }
                if(h.isDirectory())continue;
                long bytes=h.getFullUnpackSize(),packed=h.getFullPackSize();
                require(bytes>=0&&bytes<=MAX_EXPANDED-expanded&&packed>=0,"RAR expanded size unsupported");
                expanded+=bytes;
                String mime=imageMime(name);
                if(mime!=null){
                    require(bytes>0&&bytes<=MAX_PAGE&&bytes<=Math.max(1024L*1024,Math.min(packed,MAX_ARCHIVE)*200),"Comic page exceeds limits");
                    pages.add(new Page(h,name,mime));
                }
            }
            require(!pages.isEmpty(),"RAR contains no supported comic pages");
            pages.sort((a,b)->BookArchive.compareNatural(a.name,b.name));
        }catch(Exception e){try{archive.close();}catch(Exception ignored){}throw e;}
    }
    public int count(){return pages.size();}
    public String mime(int index){return pages.get(index).mime;}
    public String name(int index){return pages.get(index).name;}
    public synchronized byte[] page(int index)throws Exception{
        require(!closed&&index>=0&&index<pages.size(),"Comic page unavailable");
        Page page=pages.get(index);long expected=page.header.getFullUnpackSize();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream((int)Math.min(expected,65536));
        CRC32 crc=new CRC32();
        OutputStream bounded=new OutputStream(){
            @Override public void write(int b)throws IOException{
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Page read cancelled");
                require(bytes.size()<expected,"RAR expanded beyond declared page length");
                bytes.write(b);crc.update(b);
            }
            @Override public void write(byte[] b,int offset,int size)throws IOException{
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Page read cancelled");
                require(size>=0&&size<=expected-bytes.size(),"RAR expanded beyond declared page length");
                bytes.write(b,offset,size);crc.update(b,offset,size);
            }
        };
        archive.extractFile(page.header,bounded);
        require(bytes.size()==expected&&crc.getValue()==Integer.toUnsignedLong(page.header.getFileCRC()),"Comic page CRC or length mismatch");
        return bytes.toByteArray();
    }
    @Override public synchronized void close()throws IOException{
        if(!closed){closed=true;archive.close();}
    }
}
