package com.asterion.nexus;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
public class NexusGltfEmbeddedTest {
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    @Test public void normalizesEmbeddedBuffersAndImages()throws Exception{
        byte[] glb=NexusGltfEmbedded.asGlb(bytes("{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":4,\"uri\":\"data:application/octet-stream;base64,AAAAAA==\"}],\"bufferViews\":[{\"buffer\":0,\"byteLength\":4}],\"images\":[{\"uri\":\"data:image/png;base64,AAAAAA==\"}]}"));
        NexusGltfAdmission.verify(glb);
        assertSame(glb,NexusGltfEmbedded.asGlb(glb));
    }
    @Test public void rejectsExternalBuffer(){
        assertThrows(java.io.IOException.class,()->NexusGltfEmbedded.asGlb(bytes("{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":4,\"uri\":\"mesh.bin\"}]}")));
    }
    @Test public void rejectsMismatchedLength(){
        assertThrows(java.io.IOException.class,()->NexusGltfEmbedded.asGlb(bytes("{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":5,\"uri\":\"data:application/octet-stream;base64,AAAAAA==\"}]}")));
    }
}
