package com.asterion.nexus;

import java.io.*;
import java.nio.file.*;
import java.util.Random;

/** No mocks: real stream-to-fsync-to-atomic-publish-to-readback acceptance. */
public final class NexusOriginalStoreSmoke {
    public static void main(String[] args) throws Exception {
        File root=Files.createTempDirectory("nexus-native-original-").toFile();
        byte[] bytes=new byte[196673];
        new Random(20261126L).nextBytes(bytes);
        NexusOriginalStore.VerifiedFile first=NexusOriginalStore.ingest(
            new ByteArrayInputStream(bytes),root,bytes.length,null);
        if(!NexusOriginalStore.matches(first.file,bytes.length,first.sha256))
            throw new AssertionError("Integrity not verified");
        NexusOriginalStore.VerifiedFile again=NexusOriginalStore.ingest(
            new ByteArrayInputStream(bytes),root,bytes.length,first.sha256);
        if(!first.file.equals(again.file))throw new AssertionError("Duplicate original not deduplicated");
        try {
            NexusOriginalStore.ingest(new ByteArrayInputStream(bytes),root,bytes.length+1,null);
            throw new AssertionError("Truncated original accepted");
        } catch(IOException expected) {}
        try {
            NexusOriginalStore.ingest(new ByteArrayInputStream(bytes),root,bytes.length,"a".repeat(64));
            throw new AssertionError("Incorrect digest accepted");
        } catch(IOException expected) {}
        if(new File(root,"staging").list().length!=0)
            throw new AssertionError("Interrupted or rejected originals leaked staging");
        try(RandomAccessFile damaged=new RandomAccessFile(first.file,"rw")){
            damaged.seek(0);damaged.writeByte(bytes[0]^1);
        }
        try {
            NexusOriginalStore.ingest(new ByteArrayInputStream(bytes),root,bytes.length,first.sha256);
            throw new AssertionError("A corrupted indexed original was silently reused");
        } catch(IOException expected) {}
        System.out.println("PASS: complete verified original, restart-safe reuse, truncation, SHA mismatch, cleanup, corruption refusal");
    }
}
