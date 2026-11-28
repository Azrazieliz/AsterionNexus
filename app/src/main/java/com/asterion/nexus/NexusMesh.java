package com.asterion.nexus;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Actual mesh geometry for native inspection. No external material, texture,
 * executable, or linked file is fetched. Original assets remain unchanged. */
public final class NexusMesh {
    public static final int MAX_BYTES=48*1024*1024;
    private static final int MAX_VERTICES=500000,MAX_TRIANGLES=300000;
    public final float[] positions,normals;
    public final int triangles;
    public final boolean externalMaterials;
    private NexusMesh(float[] p,float[] n,boolean external){positions=p;normals=n;triangles=p.length/9;externalMaterials=external;}
    public static boolean supported(String mime,String name){
        String n=name==null?"":name.toLowerCase(Locale.ROOT);
        return n.endsWith(".obj")||n.endsWith(".stl")||"model/obj".equals(mime)||"model/stl".equals(mime);
    }
    private static final class Builder{
        float[] data=new float[9*1024];int length;boolean materials;
        void triangle(float ax,float ay,float az,float bx,float by,float bz,float cx,float cy,float cz)throws IOException{
            if(length/9>=MAX_TRIANGLES)throw new IOException("Mesh triangle limit");
            float[] values={ax,ay,az,bx,by,bz,cx,cy,cz};
            for(float f:values)if(!Float.isFinite(f)||Math.abs(f)>1e20f)throw new IOException("Invalid mesh coordinate");
            if(length+9>data.length)data=Arrays.copyOf(data,Math.min(MAX_TRIANGLES*9,data.length*2));
            System.arraycopy(values,0,data,length,9);length+=9;
        }
        NexusMesh finish()throws IOException{
            if(length==0)throw new IOException("Mesh has no triangles");
            double[] lo={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY};
            double[] hi={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
            for(int i=0;i<length;i++){int a=i%3;lo[a]=Math.min(lo[a],data[i]);hi[a]=Math.max(hi[a],data[i]);}
            double span=Math.max(hi[0]-lo[0],Math.max(hi[1]-lo[1],hi[2]-lo[2]));
            if(!Double.isFinite(span)||span<=0)throw new IOException("Degenerate mesh bounds");
            float[] p=Arrays.copyOf(data,length),n=new float[length];
            for(int i=0;i<length;i++){int a=i%3;p[i]=(float)((p[i]-(lo[a]+hi[a])/2.0)*2.0/span);}
            int real=0;
            for(int i=0;i<length;i+=9){
                double ax=p[i+3]-p[i],ay=p[i+4]-p[i+1],az=p[i+5]-p[i+2];
                double bx=p[i+6]-p[i],by=p[i+7]-p[i+1],bz=p[i+8]-p[i+2];
                double x=ay*bz-az*by,y=az*bx-ax*bz,z=ax*by-ay*bx;
                double len=Math.sqrt(x*x+y*y+z*z);
                if(len>1e-14){x/=len;y/=len;z/=len;real++;}else{x=0;y=0;z=1;}
                for(int v=0;v<3;v++){n[i+v*3]=(float)x;n[i+v*3+1]=(float)y;n[i+v*3+2]=(float)z;}
            }
            if(real==0)throw new IOException("Mesh contains no nondegenerate faces");
            return new NexusMesh(p,n,materials);
        }
    }
    public static NexusMesh open(InputStream input,String name,String mime)throws IOException{
        if(input==null)throw new IOException("Mesh source unavailable");
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buffer=new byte[65536];
        try(InputStream in=input){
            for(int count;(count=in.read(buffer))!=-1;){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Mesh decode cancelled");
                if(count>MAX_BYTES-out.size())throw new IOException("Mesh exceeds 48 MiB preview limit");
                out.write(buffer,0,count);
            }
        }
        String lower=name==null?"":name.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".stl")||"model/stl".equals(mime))return stl(out.toByteArray());
        if(lower.endsWith(".obj")||"model/obj".equals(mime))return obj(out.toByteArray());
        throw new IOException("Unsupported mesh format");
    }
    private static BufferedReader text(byte[] bytes)throws IOException{
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes),
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)));
    }
    private static float scalar(String s)throws IOException{
        try{float v=Float.parseFloat(s);if(!Float.isFinite(v)||Math.abs(v)>1e20f)throw new NumberFormatException();return v;}
        catch(NumberFormatException e){throw new IOException("Invalid mesh number",e);}
    }
    private static int vertex(String token,int count)throws IOException{
        String raw=token.split("/",-1)[0];
        try{
            int id=Integer.parseInt(raw);
            if(id<0)id=count+id+1;
            if(id<1||id>count)throw new NumberFormatException();
            return id-1;
        }catch(NumberFormatException e){throw new IOException("Invalid OBJ vertex index");}
    }
    private static void add(Builder b,List<float[]> v,int a,int c,int d)throws IOException{
        float[] x=v.get(a),y=v.get(c),z=v.get(d);
        b.triangle(x[0],x[1],x[2],y[0],y[1],y[2],z[0],z[1],z[2]);
    }
    /** Ear clipping on the dominant planar projection supports concave
     * simple polygons without silently filling their missing regions. */
    private static void polygon(Builder b,List<float[]> vertices,int[] ids)throws IOException{
        if(ids.length==3){add(b,vertices,ids[0],ids[1],ids[2]);return;}
        if(ids.length>128)throw new IOException("OBJ face exceeds 128 vertices");
        Set<Integer> unique=new HashSet<>();for(int id:ids)if(!unique.add(id))throw new IOException("Repeated polygon vertex");
        double[] norm=new double[3];
        for(int i=0;i<ids.length;i++){
            float[] a=vertices.get(ids[i]),c=vertices.get(ids[(i+1)%ids.length]);
            norm[0]+=(double)(a[1]-c[1])*(a[2]+c[2]);
            norm[1]+=(double)(a[2]-c[2])*(a[0]+c[0]);
            norm[2]+=(double)(a[0]-c[0])*(a[1]+c[1]);
        }
        int drop=Math.abs(norm[0])>Math.abs(norm[1])?0:1;
        if(Math.abs(norm[2])>Math.abs(norm[drop]))drop=2;
        if(norm[drop]==0)throw new IOException("Degenerate polygon");
        double[] x=new double[ids.length],y=new double[ids.length];
        int u=(drop+1)%3,v=(drop+2)%3;
        float[] origin=vertices.get(ids[0]);double span=0;
        for(int i=0;i<ids.length;i++){
            float[] p=vertices.get(ids[i]);x[i]=(double)p[u]-origin[u];y[i]=(double)p[v]-origin[v];
            span=Math.max(span,Math.max(Math.abs(x[i]),Math.abs(y[i])));
        }
        if(span==0)throw new IOException("Degenerate polygon");
        // Project after scale normalization, then reject markedly nonplanar
        // polygons rather than inventing a surface.
        double len=Math.sqrt(norm[0]*norm[0]+norm[1]*norm[1]+norm[2]*norm[2]);
        if(!Double.isFinite(len)||len==0)throw new IOException("Invalid polygon normal");
        for(int i=0;i<ids.length;i++){
            float[] p=vertices.get(ids[i]);
            double distance=Math.abs(norm[0]*((double)p[0]-origin[0])+norm[1]*((double)p[1]-origin[1])+norm[2]*((double)p[2]-origin[2]))/len;
            if(distance>span*1e-4)throw new IOException("Nonplanar OBJ polygon");
            x[i]/=span;y[i]/=span;
        }
        double area=0;for(int i=0;i<ids.length;i++){int j=(i+1)%ids.length;area+=x[i]*y[j]-x[j]*y[i];}
        if(Math.abs(area)<1e-12)throw new IOException("Degenerate polygon");
        double sign=Math.signum(area);
        for(int i=0;i<ids.length;i++)for(int j=i+1;j<ids.length;j++){
            int in=(i+1)%ids.length,jn=(j+1)%ids.length;
            if(i==j||in==j||jn==i)continue;
            double a=cross(x,y,i,in,j),c=cross(x,y,i,in,jn),d=cross(x,y,j,jn,i),e=cross(x,y,j,jn,in);
            if(a*c<0&&d*e<0)throw new IOException("Self-intersecting OBJ polygon");
        }
        List<Integer> left=new ArrayList<>();for(int i=0;i<ids.length;i++)left.add(i);
        while(left.size()>3){
            boolean clipped=false;
            for(int k=0;k<left.size();k++){
                int a=left.get((k+left.size()-1)%left.size()),c=left.get(k),d=left.get((k+1)%left.size());
                if(sign*cross(x,y,a,c,d)<=1e-12)continue;
                boolean inside=false;
                for(int p:left)if(p!=a&&p!=c&&p!=d&&sign*cross(x,y,a,c,p)>=-1e-12&&sign*cross(x,y,c,d,p)>=-1e-12&&sign*cross(x,y,d,a,p)>=-1e-12){inside=true;break;}
                if(inside)continue;
                add(b,vertices,ids[a],ids[c],ids[d]);left.remove(k);clipped=true;break;
            }
            if(!clipped)throw new IOException("OBJ polygon triangulation failed");
        }
        add(b,vertices,ids[left.get(0)],ids[left.get(1)],ids[left.get(2)]);
    }
    private static double cross(double[] x,double[] y,int a,int b,int c){
        return (x[b]-x[a])*(y[c]-y[a])-(y[b]-y[a])*(x[c]-x[a]);
    }
    public static NexusMesh obj(byte[] bytes)throws IOException{
        if(bytes.length>MAX_BYTES)throw new IOException("Mesh size limit");
        ArrayList<float[]> vertices=new ArrayList<>();Builder b=new Builder();
        try(BufferedReader in=text(bytes)){
            for(String line;(line=in.readLine())!=null;){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Mesh cancelled");
                if(line.length()>65536)throw new IOException("OBJ line limit");
                int comment=line.indexOf('#');if(comment>=0)line=line.substring(0,comment);
                line=line.trim();if(line.isEmpty())continue;
                String[] fields=line.split("\\s+");
                if("v".equals(fields[0])){
                    if(fields.length<4||vertices.size()>=MAX_VERTICES)throw new IOException("Invalid OBJ vertex");
                    float x=scalar(fields[1]),y=scalar(fields[2]),z=scalar(fields[3]);
                    if(fields.length==5){float w=scalar(fields[4]);if(w==0)throw new IOException("Invalid homogeneous vertex");x/=w;y/=w;z/=w;}
                    if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))throw new IOException("Nonfinite vertex");
                    vertices.add(new float[]{x,y,z});
                }else if("f".equals(fields[0])){
                    if(fields.length<4||fields.length>129)throw new IOException("Invalid OBJ face");
                    int[] ids=new int[fields.length-1];for(int i=1;i<fields.length;i++)ids[i-1]=vertex(fields[i],vertices.size());
                    polygon(b,vertices,ids);
                }else if("mtllib".equals(fields[0])||"usemtl".equals(fields[0]))b.materials=true;
                else if(Set.of("curv","curv2","surf","p","l").contains(fields[0]))throw new IOException("OBJ contains unsupported non-surface geometry");
            }
        }
        return b.finish();
    }
    public static NexusMesh stl(byte[] bytes)throws IOException{
        if(bytes.length>MAX_BYTES)throw new IOException("Mesh size limit");
        if(bytes.length>=84){
            ByteBuffer input=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            long count=Integer.toUnsignedLong(input.getInt(80));
            if(count>0&&84L+count*50L==bytes.length){
                if(count>MAX_TRIANGLES)throw new IOException("STL triangle limit");
                Builder b=new Builder();input.position(84);
                for(long i=0;i<count;i++){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Mesh cancelled");
                    input.position(input.position()+12);float[] p=new float[9];
                    for(int j=0;j<9;j++)p[j]=input.getFloat();
                    input.getShort();b.triangle(p[0],p[1],p[2],p[3],p[4],p[5],p[6],p[7],p[8]);
                }
                return b.finish();
            }
        }
        Builder b=new Builder();float[] p=new float[9];int count=0;boolean facet=false,solid=false,ended=false;
        try(BufferedReader in=text(bytes)){
            for(String line;(line=in.readLine())!=null;){
                if(line.length()>65536)throw new IOException("STL line limit");
                line=line.trim();if(line.isEmpty())continue;
                String[] f=line.split("\\s+");
                switch(f[0].toLowerCase(Locale.ROOT)){
                    case "solid":if(solid||facet)throw new IOException("Invalid STL solid");solid=true;break;
                    case "facet":if(!solid||facet||ended)throw new IOException("Invalid STL facet");facet=true;count=0;break;
                    case "outer":case "endloop":if(!facet)throw new IOException("Invalid STL loop");break;
                    case "vertex":
                        if(!facet||count>=9||f.length!=4)throw new IOException("Invalid STL vertex");
                        for(int i=1;i<4;i++)p[count++]=scalar(f[i]);break;
                    case "endfacet":
                        if(!facet||count!=9)throw new IOException("Incomplete STL facet");
                        b.triangle(p[0],p[1],p[2],p[3],p[4],p[5],p[6],p[7],p[8]);facet=false;break;
                    case "endsolid":if(!solid||facet)throw new IOException("Invalid STL ending");ended=true;break;
                    default:throw new IOException("Unrecognized STL syntax");
                }
            }
        }
        if(!solid||!ended||facet)throw new IOException("Incomplete STL");
        return b.finish();
    }
}
