package com.asterion.nexus;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
public class NexusGltfBundleTest {
    private static byte[] zip(String name,String json)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(out)){
            zip.putNextEntry(new ZipEntry(name));zip.write(json.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("scene/mesh.bin"));zip.write(new byte[4]);zip.closeEntry();
            zip.putNextEntry(new ZipEntry("scene/texture.png"));zip.write(new byte[4]);zip.closeEntry();
        }
        return out.toByteArray();
    }
    private static final String MODEL="{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":4,\"uri\":\"mesh.bin\"}],\"bufferViews\":[{\"buffer\":0,\"byteLength\":4}],\"images\":[{\"uri\":\"texture.png\"}]}";
    @Test public void loadsPackagedRelativeResources()throws Exception{
        NexusGltfAdmission.verify(NexusGltfBundle.normalize(zip("scene/model.gltf",MODEL)));
    }
    @Test public void refusesTraversal()throws Exception{
        assertThrows(IOException.class,()->NexusGltfBundle.normalize(zip("scene/model.gltf",MODEL.replace("mesh.bin","../mesh.bin"))));
    }
    @Test public void refusesNetworkResource()throws Exception{
        assertThrows(IOException.class,()->NexusGltfBundle.normalize(zip("scene/model.gltf",MODEL.replace("mesh.bin","https://example.com/mesh.bin"))));
    }
}
