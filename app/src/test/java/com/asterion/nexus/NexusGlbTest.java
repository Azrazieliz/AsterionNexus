package com.asterion.nexus;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

public class NexusGlbTest {
    private static String scene(){
        return "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0,1]}],"+
            "\"nodes\":[{\"mesh\":0},{\"mesh\":0,\"translation\":[3,0,0]}],"+
            "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0},\"indices\":1}]}],"+
            "\"buffers\":[{\"byteLength\":42}],"+
            "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36},{\"buffer\":0,\"byteOffset\":36,\"byteLength\":6}],"+
            "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},{\"bufferView\":1,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}]}";
    }
    private static byte[] glb(String json,int lastIndex){
        byte[] raw=json.getBytes(StandardCharsets.UTF_8);int padded=(raw.length+3)&~3;
        ByteBuffer b=ByteBuffer.allocate(12+8+padded+8+44).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46546c67).putInt(2).putInt(b.capacity()).putInt(padded).putInt(0x4e4f534a).put(raw);
        for(int i=raw.length;i<padded;i++)b.put((byte)' ');
        b.putInt(44).putInt(0x004e4942);
        for(float x:new float[]{0,0,0,1,0,0,0,1,0})b.putFloat(x);
        b.putShort((short)0).putShort((short)1).putShort((short)lastIndex).putShort((short)0);
        return b.array();
    }
    @Test public void actualEmbeddedTrianglesAndNodeTransform()throws Exception{
        NexusMesh mesh=NexusGlb.open(glb(scene(),2));
        assertEquals(2,mesh.triangles);
        assertEquals(-1f,mesh.positions[0],1e-6);
        assertEquals(.5f,mesh.positions[9],1e-6);
        assertEquals(1f,mesh.positions[12],1e-6);
        assertEquals(1f,mesh.normals[2],1e-6);
    }
    @Test public void invalidIndexAndExternalBufferRejected()throws Exception{
        for(byte[] b:new byte[][]{glb(scene(),3),glb(scene().replace("\"byteLength\":42","\"uri\":\"external.bin\",\"byteLength\":42"),2),
            glb(scene().replace("\"translation\":[3,0,0]","\"children\":[1]"),2),glb(scene().replace("\"count\":3,\"type\":\"VEC3\"","\"count\":99,\"type\":\"VEC3\""),2)}){
            try{NexusGlb.open(b);fail("Invalid geometry admitted");}catch(IOException expected){}
        }
    }
}
