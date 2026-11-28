package com.asterion.nexus;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

public class NexusMeshTest {
    private static byte[] utf(String s){return s.getBytes(StandardCharsets.UTF_8);}
    @Test public void actualConcaveObjUsesEarClipping()throws Exception{
        NexusMesh mesh=NexusMesh.obj(utf("mtllib external.mtl\nv 0 0 0\nv 2 0 0\nv 2 2 0\nv 1 1 0\nv 0 2 0\nf -5 -4 -3 -2 -1\n"));
        assertEquals(3,mesh.triangles);
        assertTrue(mesh.externalMaterials);
        assertEquals(27,mesh.positions.length);
        double area=0;
        for(int i=0;i<mesh.positions.length;i+=9){
            float[] p=mesh.positions;
            area+=Math.abs((p[i+3]-p[i])*(p[i+7]-p[i+1])-(p[i+4]-p[i+1])*(p[i+6]-p[i]))/2;
        }
        assertEquals(3.0,area,1e-5);
    }
    @Test public void binaryStlActualTriangle()throws Exception{
        ByteBuffer b=ByteBuffer.allocate(134).order(ByteOrder.LITTLE_ENDIAN);
        b.position(80);b.putInt(1);
        for(float f:new float[]{0,0,1,0,0,0,1,0,0,0,1,0})b.putFloat(f);
        b.putShort((short)0);
        NexusMesh mesh=NexusMesh.stl(b.array());
        assertEquals(1,mesh.triangles);assertEquals(1f,mesh.normals[2],1e-6);
    }
    @Test public void asciiStlActualTriangle()throws Exception{
        NexusMesh mesh=NexusMesh.stl(utf("solid model\nfacet normal 0 0 1\nouter loop\nvertex 0 0 0\nvertex 1 0 0\nvertex 0 1 0\nendloop\nendfacet\nendsolid model\n"));
        assertEquals(1,mesh.triangles);
    }
    @Test public void invalidMeshesFailClosed()throws Exception{
        for(String obj:new String[]{"v 0 0 0\nf 1 2 3\n","v NaN 0 0\n","v 0 0 0\nv 1 1 0\nv 0 1 0\nv 1 0 0\nf 1 2 3 4\n"}){
            try{NexusMesh.obj(utf(obj));fail("Malformed mesh admitted");}catch(IOException expected){}
        }
    }
}
