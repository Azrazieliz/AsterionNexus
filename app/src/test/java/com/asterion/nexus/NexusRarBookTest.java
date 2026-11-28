package com.asterion.nexus;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.util.zip.CRC32;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public final class NexusRarBookTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static void u16(OutputStream out,int n)throws IOException{out.write(n&255);out.write((n>>>8)&255);}
    private static void u32(OutputStream out,long n)throws IOException{u16(out,(int)n);u16(out,(int)(n>>>16));}
    private static long crc(byte[] bytes){CRC32 c=new CRC32();c.update(bytes);return c.getValue();}
    private static void header(OutputStream out,int type,int flags,byte[] fields)throws IOException{
        ByteArrayOutputStream body=new ByteArrayOutputStream();
        body.write(type);u16(body,flags);u16(body,7+fields.length);body.write(fields);
        byte[] raw=body.toByteArray();u16(out,(int)crc(raw));out.write(raw);
    }
    private File rar(int mainFlags,boolean badCrc,String... names)throws Exception{
        File file=temp.newFile();
        try(OutputStream out=new FileOutputStream(file)){
            out.write(new byte[]{0x52,0x61,0x72,0x21,0x1a,0x07,0});
            header(out,0x73,mainFlags,new byte[6]);
            for(String name:names){
                byte[] raw=name.getBytes(StandardCharsets.US_ASCII);
                byte[] data=("page:"+name).getBytes(StandardCharsets.US_ASCII);
                ByteArrayOutputStream fields=new ByteArrayOutputStream();
                u32(fields,data.length);u32(fields,data.length);fields.write(2);
                u32(fields,crc(data)^(badCrc?1:0));u32(fields,0x50210000L);
                fields.write(20);fields.write(0x30);u16(fields,raw.length);u32(fields,0x20);
                fields.write(raw);header(out,0x74,0x8000,fields.toByteArray());out.write(data);
            }
            header(out,0x7b,0,new byte[0]);
        }
        return file;
    }
    @Test public void actualPagesAreReadInNaturalOrder()throws Exception{
        File file=rar(0,false,"chapter/page10.png","chapter/page2.png","notes.txt");
        try(NexusRarBook book=new NexusRarBook(file)){
            assertEquals(2,book.count());assertEquals("chapter/page2.png",book.name(0));
            assertEquals("image/png",book.mime(0));
            assertArrayEquals("page:chapter/page2.png".getBytes(StandardCharsets.US_ASCII),book.page(0));
            assertArrayEquals("page:chapter/page10.png".getBytes(StandardCharsets.US_ASCII),book.page(1));
        }
    }
    @Test public void wrongCrcFails()throws Exception{
        try(NexusRarBook book=new NexusRarBook(rar(0,true,"page.png"))){
            try{book.page(0);fail("Bad CRC accepted");}catch(Exception expected){}
        }
    }
    @Test public void traversalFails()throws Exception{
        try(NexusRarBook ignored=new NexusRarBook(rar(0,false,"../page.png"))){fail("Traversal accepted");}
        catch(IOException expected){}
    }
    @Test public void solidArchiveRejected()throws Exception{
        try{NexusRarBook.preflight(rar(8,false,"page.png"));fail("Solid RAR accepted");}
        catch(IOException expected){}
    }
    @Test public void truncatedArchiveRejected()throws Exception{
        File file=rar(0,false,"page.png");
        try(RandomAccessFile r=new RandomAccessFile(file,"rw")){r.setLength(r.length()-1);}
        try{NexusRarBook.preflight(file);fail("Truncated RAR accepted");}
        catch(IOException expected){}
    }
    @Test public void legacyMimeNormalization(){
        assertEquals("application/vnd.comicbook-rar",NexusRegistry.canonicalComicMime("Series.CBR","application/vnd.rar"));
        assertEquals("application/vnd.comicbook+zip",NexusRegistry.canonicalComicMime("Series.cbz","application/zip"));
        assertEquals("application/vnd.rar",NexusRegistry.canonicalComicMime("files.rar","application/vnd.rar"));
    }
}
