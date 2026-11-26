package com.asterion.nexus;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.Random;

public class NexusOriginalStoreTest {
    @Test public void verifiedCopySurvivesRepeatAndRejectsTruncatedOrWrongBytes() throws Exception {
        File root=Files.createTempDirectory("nexus-original-test").toFile();
        byte[] source=new byte[131101];new Random(12).nextBytes(source);
        NexusOriginalStore.VerifiedFile one=NexusOriginalStore.ingest(
            new ByteArrayInputStream(source),root,source.length,null);
        assertTrue(NexusOriginalStore.matches(one.file,one.length,one.sha256));
        NexusOriginalStore.VerifiedFile two=NexusOriginalStore.ingest(
            new ByteArrayInputStream(source),root,source.length,one.sha256);
        assertEquals(one.file,two.file);
        try{
            NexusOriginalStore.ingest(new ByteArrayInputStream(source),root,source.length+1,null);
            fail("Truncated original was accepted");
        }catch(IOException expected){}
        try{
            NexusOriginalStore.ingest(new ByteArrayInputStream(source),root,source.length,"a".repeat(64));
            fail("Wrong selected original accepted");
        }catch(IOException expected){}
        assertEquals(0,new File(root,"staging").list().length);
    }
    @Test public void distinctContentNeverSharesIdentity() throws Exception {
        File root=Files.createTempDirectory("nexus-identity-test").toFile();
        NexusOriginalStore.VerifiedFile first=NexusOriginalStore.ingest(
            new ByteArrayInputStream(new byte[]{1,2,3}),root,3,null);
        NexusOriginalStore.VerifiedFile second=NexusOriginalStore.ingest(
            new ByteArrayInputStream(new byte[]{1,2,4}),root,3,null);
        assertNotEquals(first.sha256,second.sha256);
    }
}
