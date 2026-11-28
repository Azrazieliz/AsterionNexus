package com.asterion.nexus;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import static org.junit.Assert.*;

public final class NexusExactRecoveryTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private final byte[] original="original verified bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private final byte[] damaged="corrupt".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private NexusOriginalStore.VerifiedFile original(File root)throws IOException{
        return NexusOriginalStore.ingest(new ByteArrayInputStream(original),root,original.length,null);
    }
    private NexusOriginalStore.Recovery recover(File root,NexusOriginalStore.VerifiedFile file,byte[] input)throws IOException{
        return NexusOriginalStore.recoverExact(new ByteArrayInputStream(input),root,file.length,file.sha256);
    }
    @Test public void exactCopyRestoresAndPreservesDamagedBytes()throws Exception{
        File root=temp.newFolder();
        NexusOriginalStore.VerifiedFile item=original(root);
        Files.write(item.file.toPath(),damaged);
        NexusOriginalStore.Recovery result=recover(root,item,original);
        assertFalse(result.alreadyVerified);
        assertArrayEquals(original,Files.readAllBytes(result.restored.toPath()));
        assertArrayEquals(damaged,Files.readAllBytes(result.retainedDamage.toPath()));
        Properties receipt=new Properties();
        try(InputStream in=new FileInputStream(result.receipt)){receipt.load(in);}
        assertEquals("VERIFIED",receipt.getProperty("state"));
        assertEquals(item.sha256,receipt.getProperty("expected_sha256"));
        assertEquals(Long.toString(damaged.length),receipt.getProperty("retained_bytes"));
    }
    @Test public void wrongCandidateNeverModifiesDamagedOriginal()throws Exception{
        File root=temp.newFolder();
        NexusOriginalStore.VerifiedFile item=original(root);
        Files.write(item.file.toPath(),damaged);
        byte[] wrong=original.clone();wrong[0]^=1;
        try{recover(root,item,wrong);fail("Wrong copy accepted");}
        catch(IOException expected){assertArrayEquals(damaged,Files.readAllBytes(item.file.toPath()));}
        assertFalse(new File(root,"recovery").exists());
    }
    @Test public void shortCandidateNeverModifiesDamagedOriginal()throws Exception{
        File root=temp.newFolder();
        NexusOriginalStore.VerifiedFile item=original(root);
        Files.write(item.file.toPath(),damaged);
        try{recover(root,item,new byte[]{1,2});fail("Short copy accepted");}
        catch(IOException expected){assertArrayEquals(damaged,Files.readAllBytes(item.file.toPath()));}
    }
    @Test public void missingOriginalCanBeRecovered()throws Exception{
        File root=temp.newFolder();
        NexusOriginalStore.VerifiedFile item=original(root);
        assertTrue(item.file.delete());
        NexusOriginalStore.Recovery result=recover(root,item,original);
        assertNull(result.retainedDamage);
        assertTrue(NexusOriginalStore.matches(result.restored,item.length,item.sha256));
    }
    @Test public void emptyDamagedOriginalIsPreserved()throws Exception{
        File root=temp.newFolder();
        NexusOriginalStore.VerifiedFile item=original(root);
        Files.write(item.file.toPath(),new byte[0]);
        NexusOriginalStore.Recovery result=recover(root,item,original);
        assertTrue(result.retainedDamage.isFile());assertEquals(0,result.retainedDamage.length());
    }
    @Test public void validOriginalIsUnchanged()throws Exception{
        File root=temp.newFolder();NexusOriginalStore.VerifiedFile item=original(root);
        NexusOriginalStore.Recovery result=recover(root,item,original);
        assertTrue(result.alreadyVerified);assertNull(result.receipt);
        assertFalse(new File(root,"recovery").exists());
    }
    @Test public void corruptExistingObjectCannotBeOverwrittenByOrdinaryIngest()throws Exception{
        File root=temp.newFolder();NexusOriginalStore.VerifiedFile item=original(root);
        Files.write(item.file.toPath(),damaged);
        try{original(root);fail("Corrupt original overwritten");}
        catch(IOException expected){assertArrayEquals(damaged,Files.readAllBytes(item.file.toPath()));}
    }
}
