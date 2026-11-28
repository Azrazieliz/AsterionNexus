package com.asterion.nexus;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public final class NexusZipExtractionTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private File zip(String... names)throws Exception{
        File file=temp.newFile();
        try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(file))){
            for(String name:names){
                ZipEntry entry=new ZipEntry(name);
                out.putNextEntry(entry);
                if(!name.endsWith("/"))out.write(("payload:"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }
    private void rejected(File file)throws Exception{
        try{NexusZipExtraction.inspect(file);fail("Unsafe ZIP accepted");}
        catch(IOException expected){}
    }
    @Test public void extractsRealBytesWithoutMaterializingArchivePaths()throws Exception{
        File source=zip("book/","book/page1.txt","book/page2.txt","cover.txt");
        NexusZipExtraction.Plan plan=NexusZipExtraction.inspect(source);
        assertEquals(3,plan.files.size());
        File stage=temp.newFolder();
        List<NexusZipExtraction.Verified> files=NexusZipExtraction.stage(source,stage,plan);
        assertEquals(3,files.size());
        assertEquals("book/page1.txt",files.get(0).entry.path);
        assertEquals("payload:book/page1.txt",new String(java.nio.file.Files.readAllBytes(files.get(0).file.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        for(NexusZipExtraction.Verified file:files){
            assertEquals(stage,file.file.getParentFile());
            assertTrue(NexusOriginalStore.matches(file.file,file.entry.length,file.sha256));
        }
        assertFalse(new File(stage,"book").exists());
    }
    @Test public void rejectsTraversalAndCollisions()throws Exception{
        rejected(zip("../escape.txt"));
        rejected(zip("C:/escape.txt"));
        rejected(zip("a\\escape.txt"));
        rejected(zip("a.txt","A.TXT"));
        rejected(zip("a","a/b.txt"));
        rejected(zip("a/b.txt","a"));
    }
    @Test public void rejectsTruncatedDirectory()throws Exception{
        File source=zip("page.txt");
        try(RandomAccessFile r=new RandomAccessFile(source,"rw")){r.setLength(r.length()-10);}
        rejected(source);
    }
    @Test public void rejectsUnixSymlink()throws Exception{
        File source=zip("link");
        try(RandomAccessFile r=new RandomAccessFile(source,"rw")){
            r.seek(r.length()-22+16);
            long central=0;for(int i=0;i<4;i++)central|=(long)r.readUnsignedByte()<<(8*i);
            r.seek(central+5);r.write(3);
            r.seek(central+38);r.write(new byte[]{0,0,(byte)0xff,(byte)0xa1});
        }
        rejected(source);
    }
    @Test public void crcFailureDoesNotLeavePartialOutput()throws Exception{
        File source=temp.newFile();byte[] payload="actual bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        CRC32 crc=new CRC32();crc.update(payload);
        try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(source))){
            ZipEntry entry=new ZipEntry("page.txt");entry.setMethod(ZipEntry.STORED);
            entry.setSize(payload.length);entry.setCompressedSize(payload.length);entry.setCrc(crc.getValue());
            z.putNextEntry(entry);z.write(payload);z.closeEntry();
        }
        NexusZipExtraction.Plan plan=NexusZipExtraction.inspect(source);
        try(RandomAccessFile r=new RandomAccessFile(source,"rw")){
            r.seek(26);int name=r.readUnsignedByte()|(r.readUnsignedByte()<<8);
            int extra=r.readUnsignedByte()|(r.readUnsignedByte()<<8);
            r.seek(30+name+extra);r.write('X');
        }
        File stage=temp.newFolder();
        try{NexusZipExtraction.stage(source,stage,plan);fail("Corrupt bytes accepted");}
        catch(IOException expected){assertEquals(0,stage.list().length);}
    }
    @Test public void cancellationLeavesNoStagingBytes()throws Exception{
        File source=zip("page.txt");File stage=temp.newFolder();
        try{NexusZipExtraction.stage(source,stage,NexusZipExtraction.inspect(source),()->true);fail("Cancelled work accepted");}
        catch(InterruptedIOException expected){assertEquals(0,stage.list().length);}
    }
    @Test public void rejectsExcessiveExpansion()throws Exception{
        File source=temp.newFile();
        try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(source))){
            z.putNextEntry(new ZipEntry("large.txt"));
            byte[] zero=new byte[65536];for(int i=0;i<64;i++)z.write(zero);z.closeEntry();
        }
        rejected(source);
    }
}
