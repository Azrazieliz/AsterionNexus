package com.asterion.nexus;

import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Static glTF 2.0 geometry preview. Reject external buffers, required
 * extensions, skinning, morphing and nontriangle topology rather than
 * guessing the shape. Embedded materials are retained but not rendered. */
final class NexusGlb {
    private final JSONObject root;
    private final ByteBuffer data;
    private final int bufferLength;
    private final NexusMesh.Builder builder=new NexusMesh.Builder();
    private final HashSet<Integer> visiting=new HashSet<>(),visited=new HashSet<>();
    private int nodes;
    private NexusGlb(JSONObject root,ByteBuffer data)throws Exception{
        this.root=root;this.data=data;
        require("2.0".equals(root.getJSONObject("asset").getString("version")),"Unsupported glTF version");
        require(root.optJSONArray("extensionsRequired")==null||root.getJSONArray("extensionsRequired").length()==0,"Required glTF extension unsupported");
        require(root.optJSONArray("animations")==null||root.getJSONArray("animations").length()==0,"Animated glTF requires animation renderer");
        JSONArray buffers=root.getJSONArray("buffers");
        require(buffers.length()==1,"GLB requires one embedded buffer");
        JSONObject buffer=buffers.getJSONObject(0);
        require(!buffer.has("uri"),"External glTF buffers unavailable");
        bufferLength=buffer.getInt("byteLength");
        require(bufferLength>0&&bufferLength<=data.limit()&&data.limit()-bufferLength<=3,"Invalid GLB buffer length");
        builder.materials=root.optJSONArray("materials")!=null||root.optJSONArray("textures")!=null;
    }
    private static void require(boolean valid,String message)throws IOException{if(!valid)throw new IOException(message);}
    static NexusMesh open(byte[] bytes)throws IOException{
        try{
            require(bytes.length>=28&&bytes.length<=NexusMesh.MAX_BYTES,"GLB size invalid");
            ByteBuffer input=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            require(input.getInt()==0x46546c67&&input.getInt()==2&&input.getInt()==bytes.length,"Invalid GLB header");
            JSONObject json=null;ByteBuffer bin=null;
            while(input.remaining()>0){
                require(input.remaining()>=8,"Incomplete GLB chunk");
                int length=input.getInt(),type=input.getInt();
                require(length>=0&&length%4==0&&length<=input.remaining(),"Invalid GLB chunk size");
                if(type==0x4e4f534a){
                    require(json==null&&bin==null&&length<=8*1024*1024,"Invalid GLB JSON chunk");
                    byte[] raw=new byte[length];input.get(raw);
                    String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
                    json=new JSONObject(text);
                }else if(type==0x004e4942){
                    require(json!=null&&bin==null,"Invalid GLB binary chunk");
                    bin=input.slice().order(ByteOrder.LITTLE_ENDIAN);bin.limit(length);
                    input.position(input.position()+length);
                }else throw new IOException("Unknown GLB chunk");
            }
            require(json!=null&&bin!=null,"Missing GLB geometry");
            return new NexusGlb(json,bin).scene();
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("Invalid GLB: "+e.getClass().getSimpleName(),e);}
    }
    private JSONObject get(String array,int id)throws Exception{
        JSONArray list=root.getJSONArray(array);
        require(id>=0&&id<list.length(),"GLB index outside "+array);
        return list.getJSONObject(id);
    }
    private NexusMesh scene()throws Exception{
        JSONObject scene=get("scenes",root.optInt("scene",0));
        JSONArray list=scene.getJSONArray("nodes");
        for(int i=0;i<list.length();i++)node(list.getInt(i),identity(),0);
        return builder.finish();
    }
    private static double[] identity(){double[] a=new double[16];a[0]=a[5]=a[10]=a[15]=1;return a;}
    private static double[] mul(double[] a,double[] b){
        double[] c=new double[16];
        for(int col=0;col<4;col++)for(int row=0;row<4;row++)for(int k=0;k<4;k++)c[col*4+row]+=a[k*4+row]*b[col*4+k];
        return c;
    }
    private static double[] vector(JSONObject object,String key,double[] defaults)throws Exception{
        if(!object.has(key))return defaults;
        JSONArray list=object.getJSONArray(key);
        require(list.length()==defaults.length,"Invalid node transform");
        double[] out=new double[defaults.length];
        for(int i=0;i<out.length;i++){out[i]=list.getDouble(i);require(Double.isFinite(out[i])&&Math.abs(out[i])<=1e20,"Invalid node transform value");}
        return out;
    }
    private static double[] transform(JSONObject n)throws Exception{
        if(n.has("matrix")){
            require(!n.has("translation")&&!n.has("rotation")&&!n.has("scale"),"Conflicting node transforms");
            double[] m=vector(n,"matrix",identity());
            require(m[3]==0&&m[7]==0&&m[11]==0&&m[15]==1,"Non-affine node matrix");
            return m;
        }
        double[] t=vector(n,"translation",new double[]{0,0,0}),s=vector(n,"scale",new double[]{1,1,1}),
            q=vector(n,"rotation",new double[]{0,0,0,1});
        double len=Math.sqrt(q[0]*q[0]+q[1]*q[1]+q[2]*q[2]+q[3]*q[3]);
        require(Math.abs(len-1)<.001,"Non-unit node quaternion");
        double x=q[0]/len,y=q[1]/len,z=q[2]/len,w=q[3]/len;
        double[] m=identity();
        m[0]=(1-2*y*y-2*z*z)*s[0];m[1]=(2*x*y+2*z*w)*s[0];m[2]=(2*x*z-2*y*w)*s[0];
        m[4]=(2*x*y-2*z*w)*s[1];m[5]=(1-2*x*x-2*z*z)*s[1];m[6]=(2*y*z+2*x*w)*s[1];
        m[8]=(2*x*z+2*y*w)*s[2];m[9]=(2*y*z-2*x*w)*s[2];m[10]=(1-2*x*x-2*y*y)*s[2];
        m[12]=t[0];m[13]=t[1];m[14]=t[2];return m;
    }
    private void node(int id,double[] parent,int depth)throws Exception{
        require(depth<=64&&++nodes<=10000&&visiting.add(id)&&visited.add(id),"Cyclic or repeated scene node");
        JSONObject n=get("nodes",id);
        require(!n.has("skin")&&!n.has("weights")&&!n.has("extensions"),"Unsupported node deformation or extension");
        double[] world=mul(parent,transform(n));
        if(n.has("mesh")){
            JSONObject mesh=get("meshes",n.getInt("mesh"));
            require(!mesh.has("weights"),"Morph mesh unsupported");
            JSONArray primitives=mesh.getJSONArray("primitives");
            require(primitives.length()<=10000,"Primitive limit");
            for(int i=0;i<primitives.length();i++)primitive(primitives.getJSONObject(i),world);
        }
        JSONArray children=n.optJSONArray("children");
        if(children!=null)for(int i=0;i<children.length();i++)node(children.getInt(i),world,depth+1);
        visiting.remove(id);
    }
    private final class Accessor{
        final int count,start,stride,type;
        Accessor(int id,boolean position)throws Exception{
            JSONObject a=get("accessors",id);
            require(!a.has("sparse")&&!a.optBoolean("normalized",false),"Sparse/normalized accessor unsupported");
            type=a.getInt("componentType");count=a.getInt("count");
            int width;
            if(position){
                require(type==5126&&"VEC3".equals(a.getString("type")),"Position accessor must be float VEC3");
                require(count<=500000,"Vertex limit");width=12;
            }else{
                require("SCALAR".equals(a.getString("type"))&&(type==5121||type==5123||type==5125),"Index format unsupported");
                require(count<=900000,"Index limit");width=type==5121?1:type==5123?2:4;
            }
            JSONObject view=get("bufferViews",a.getInt("bufferView"));
            require(view.getInt("buffer")==0&&!view.has("extensions"),"External/compressed buffer unsupported");
            int offset=view.optInt("byteOffset",0),length=view.getInt("byteLength"),relative=a.optInt("byteOffset",0);
            stride=view.optInt("byteStride",width);
            int component=position?4:width;
            require(count>0&&offset>=0&&length>0&&relative>=0&&stride>=width&&stride<=252&&stride%component==0&&
                (offset+(long)relative)%component==0&&offset+(long)length<=bufferLength&&
                relative+(long)(count-1)*stride+width<=length,"Accessor exceeds buffer bounds");
            start=offset+relative;
        }
        int index(int i)throws IOException{
            require(i>=0&&i<count,"Index outside accessor");
            int at=start+i*stride;
            long v=type==5121?data.get(at)&255:type==5123?data.getShort(at)&65535:Integer.toUnsignedLong(data.getInt(at));
            require(v<=Integer.MAX_VALUE,"Index overflow");return (int)v;
        }
        float[] position(int i,double[] m)throws IOException{
            require(i>=0&&i<count,"Vertex index outside accessor");
            int at=start+i*stride;double x=data.getFloat(at),y=data.getFloat(at+4),z=data.getFloat(at+8);
            float[] out=new float[3];
            for(int k=0;k<3;k++){
                double v=m[k]*x+m[k+4]*y+m[k+8]*z+m[k+12];
                require(Double.isFinite(v)&&Math.abs(v)<=1e20,"Invalid transformed position");out[k]=(float)v;
            }
            return out;
        }
    }
    private void primitive(JSONObject p,double[] world)throws Exception{
        require(p.optInt("mode",4)==4&&!p.has("targets")&&!p.has("extensions"),"Only static triangle primitives supported");
        JSONObject attrs=p.getJSONObject("attributes");
        require(!attrs.has("JOINTS_0")&&!attrs.has("WEIGHTS_0"),"Skin attributes unsupported");
        Accessor positions=new Accessor(attrs.getInt("POSITION"),true);
        Accessor indices=p.has("indices")?new Accessor(p.getInt("indices"),false):null;
        int count=indices==null?positions.count:indices.count;
        require(count%3==0&&count/3<=300000,"Triangle count invalid");
        for(int i=0;i<count;i+=3){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("GLB cancelled");
            float[] a=positions.position(indices==null?i:indices.index(i),world);
            float[] b=positions.position(indices==null?i+1:indices.index(i+1),world);
            float[] c=positions.position(indices==null?i+2:indices.index(i+2),world);
            builder.triangle(a[0],a[1],a[2],b[0],b[1],b[2],c[0],c[1],c[2]);
        }
    }
}
