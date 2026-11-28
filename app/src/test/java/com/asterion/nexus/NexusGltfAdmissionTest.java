package com.asterion.nexus;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

public class NexusGltfAdmissionTest {
    private static byte[] glb(String json){
        byte[] raw=json.getBytes(StandardCharsets.UTF_8);
        int n=(raw.length+3)&~3;
        ByteBuffer b=ByteBuffer.allocate(12+8+n+8+4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46546c67).putInt(2).putInt(b.capacity());
        b.putInt(n).putInt(0x4e4f534a).put(raw);
        for(int i=raw.length;i<n;i++)b.put((byte)32);
        b.putInt(4).putInt(0x004e4942).putInt(0);
        return b.array();
    }
    private static final String BASE="{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":4}],\"bufferViews\":[{\"buffer\":0,\"byteLength\":4}]";
    @Test public void acceptsEmbeddedResourcesAndAnimationMetadata()throws Exception{
        NexusGltfAdmission.verify(glb(BASE+",\"images\":[{\"bufferView\":0,\"mimeType\":\"image/png\"}],\"animations\":[]}"));
    }
    @Test public void rejectsExternalImage(){
        assertThrows(java.io.IOException.class,()->NexusGltfAdmission.verify(glb(BASE+",\"images\":[{\"uri\":\"https://example.com/image.png\"}]}")));
    }
    @Test public void rejectsOutOfBoundsView(){
        assertThrows(java.io.IOException.class,()->NexusGltfAdmission.verify(glb(BASE.replace("\"buffer\":0,\"byteLength\":4","\"buffer\":0,\"byteOffset\":3,\"byteLength\":4")+"}")));
    }
    @Test public void rejectsTruncatedContainer(){
        byte[] valid=glb(BASE+"}");
        assertThrows(java.io.IOException.class,()->NexusGltfAdmission.verify(java.util.Arrays.copyOf(valid,valid.length-1)));
    }
}
