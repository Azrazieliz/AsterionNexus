package com.asterion.nexus;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.nio.*;
import java.util.concurrent.*;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Native interactive, untextured mesh inspection, not a rendered placeholder. */
public final class NexusMeshActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private GLSurfaceView surface;
    private LinearLayout root;
    private TextView status;
    private MeshRenderer renderer;
    private boolean paused;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        Uri source=getIntent().getData();
        if(source==null){finish();return;}
        String title=getIntent().getStringExtra("title");
        final String name=title==null?"Model":title,mime=getIntent().getType();
        getWindow().setStatusBarColor(0xff101216);getWindow().setNavigationBarColor(0xff101216);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xff101216);
        LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading=new TextView(this);heading.setText(name);heading.setTextColor(Color.WHITE);heading.setTextSize(16);heading.setMaxLines(1);
        toolbar.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        Button details=new Button(this);details.setText("Details");details.setOnClickListener(v->NexusFileDetails.show(this,source,mime,name));toolbar.addView(details);
        root.addView(toolbar,new LinearLayout.LayoutParams(-1,-2));
        status=new TextView(this);status.setPadding(12,10,12,10);status.setTextColor(0xffcbd1db);status.setText("Reading model geometry…");root.addView(status);
        setContentView(root);
        worker.submit(()->{
            try{
                NexusMesh mesh=NexusMesh.open(getContentResolver().openInputStream(source),name,mime);
                runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())showMesh(mesh);});
            }catch(Exception e){
                runOnUiThread(()->{if(!isDestroyed())status.setText("Geometry preview unavailable: "+e.getMessage());});
            }
        });
    }
    private void showMesh(NexusMesh mesh){
        status.setText(mesh.triangles+" triangles · Geometry only"+(mesh.externalMaterials?" · Material files retained, not loaded":""));
        renderer=new MeshRenderer(mesh,problem->runOnUiThread(()->{if(!isDestroyed())status.setText("Rendering unavailable: "+problem);}));
        surface=new GLSurfaceView(this);
        surface.setEGLContextClientVersion(2);
        surface.setEGLConfigChooser(8,8,8,8,16,0);
        surface.setPreserveEGLContextOnPause(true);
        surface.setRenderer(renderer);
        surface.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        surface.setContentDescription("Interactive 3D geometry. Drag to rotate and pinch to zoom.");
        ScaleGestureDetector scale=new ScaleGestureDetector(this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){
                float factor=d.getScaleFactor();
                if(Float.isFinite(factor)&&factor>0){renderer.zoom=Math.max(.35f,Math.min(3.2f,renderer.zoom*factor));surface.requestRender();}
                return true;
            }
        });
        surface.setOnTouchListener(new View.OnTouchListener(){
            float x,y;boolean single;
            @Override public boolean onTouch(View v,android.view.MotionEvent e){
                scale.onTouchEvent(e);
                if(e.getActionMasked()==MotionEvent.ACTION_DOWN){x=e.getX();y=e.getY();single=true;return true;}
                if(e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN||e.getActionMasked()==MotionEvent.ACTION_POINTER_UP){single=false;return true;}
                if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&e.getPointerCount()==1&&!scale.isInProgress()){
                    float nx=e.getX(),ny=e.getY();
                    if(single){renderer.yaw=(renderer.yaw+(nx-x)*.35f)%360f;renderer.pitch=Math.max(-89f,Math.min(89f,renderer.pitch+(ny-y)*.35f));surface.requestRender();}
                    x=nx;y=ny;single=true;
                }
                if(e.getActionMasked()==MotionEvent.ACTION_UP){v.performClick();single=false;}
                return true;
            }
        });
        root.addView(surface,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout controls=new LinearLayout(this);
        String[] labels={"Rotate left","Rotate right","Zoom −","Zoom +","Reset"};
        for(int i=0;i<labels.length;i++){
            final int action=i;Button b=new Button(this);b.setText(labels[i]);b.setTextSize(11);
            b.setOnClickListener(v->{
                if(action==0)renderer.yaw-=20;
                else if(action==1)renderer.yaw+=20;
                else if(action==2)renderer.zoom=Math.max(.35f,renderer.zoom/1.25f);
                else if(action==3)renderer.zoom=Math.min(3.2f,renderer.zoom*1.25f);
                else{renderer.yaw=30;renderer.pitch=15;renderer.zoom=1;}
                surface.requestRender();
            });
            controls.addView(b,new LinearLayout.LayoutParams(0,-2,1));
        }
        root.addView(controls,new LinearLayout.LayoutParams(-1,-2));
        if(paused)surface.onPause();
    }
    @Override protected void onResume(){super.onResume();paused=false;if(surface!=null)surface.onResume();}
    @Override protected void onPause(){paused=true;if(surface!=null)surface.onPause();super.onPause();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
    private interface Problem{void report(String message);}
    private static final class MeshRenderer implements GLSurfaceView.Renderer{
        private final FloatBuffer positions,normals;private final int vertices;
        private final Problem problem;
        volatile float yaw=30,pitch=15,zoom=1;
        private final float[] projection=new float[16],view=new float[16],model=new float[16],mv=new float[16],mvp=new float[16];
        private int program,pos,norm,trans,rotation;private boolean valid;
        MeshRenderer(NexusMesh mesh,Problem problem){
            this.problem=problem;vertices=mesh.positions.length/3;
            positions=buffer(mesh.positions);normals=buffer(mesh.normals);
        }
        private static FloatBuffer buffer(float[] f){FloatBuffer b=ByteBuffer.allocateDirect(f.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();b.put(f).position(0);return b;}
        private static int shader(int type,String source){
            int id=GLES20.glCreateShader(type);GLES20.glShaderSource(id,source);GLES20.glCompileShader(id);
            int[] ok=new int[1];GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,ok,0);
            if(ok[0]!=GLES20.GL_TRUE){GLES20.glDeleteShader(id);throw new IllegalStateException("Shader compilation failed");}return id;
        }
        @Override public void onSurfaceCreated(GL10 unused,EGLConfig config){
            valid=false;
            try{
                GLES20.glClearColor(.055f,.065f,.085f,1f);
                GLES20.glEnable(GLES20.GL_DEPTH_TEST);
                // No backface culling: real scanned meshes may contain mixed winding.
                GLES20.glDisable(GLES20.GL_CULL_FACE);
                int v=shader(GLES20.GL_VERTEX_SHADER,"attribute vec3 p;attribute vec3 n;uniform mat4 mvp;uniform mat4 mv;varying vec3 normal;void main(){normal=mat3(mv)*n;gl_Position=mvp*vec4(p,1.0);}");
                int f=shader(GLES20.GL_FRAGMENT_SHADER,"precision mediump float;varying vec3 normal;void main(){vec3 nn=normalize(normal);float l=abs(dot(nn,normalize(vec3(0.35,0.65,1.0))));vec3 c=vec3(0.70,0.76,0.84)*(0.28+0.72*l);gl_FragColor=vec4(c,1.0);}");
                program=GLES20.glCreateProgram();GLES20.glAttachShader(program,v);GLES20.glAttachShader(program,f);GLES20.glLinkProgram(program);
                int[] ok=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0);
                GLES20.glDeleteShader(v);GLES20.glDeleteShader(f);
                if(ok[0]!=GLES20.GL_TRUE)throw new IllegalStateException("Shader link failed");
                pos=GLES20.glGetAttribLocation(program,"p");norm=GLES20.glGetAttribLocation(program,"n");
                trans=GLES20.glGetUniformLocation(program,"mvp");rotation=GLES20.glGetUniformLocation(program,"mv");
                if(Math.min(Math.min(pos,norm),Math.min(trans,rotation))<0)throw new IllegalStateException("Shader interface unavailable");
                valid=true;
            }catch(RuntimeException e){problem.report(e.getMessage());}
        }
        @Override public void onSurfaceChanged(GL10 unused,int width,int height){
            GLES20.glViewport(0,0,width,height);
            float aspect=Math.max(.01f,width/(float)Math.max(1,height));
            float fov=aspect<1?Math.min(115f,48f/aspect):48f;
            Matrix.perspectiveM(projection,0,fov,aspect,.1f,30f);
        }
        @Override public void onDrawFrame(GL10 unused){
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);
            if(!valid)return;
            Matrix.setLookAtM(view,0,0,0,4.2f/zoom,0,0,0,0,1,0);
            Matrix.setIdentityM(model,0);Matrix.rotateM(model,0,pitch,1,0,0);Matrix.rotateM(model,0,yaw,0,1,0);
            Matrix.multiplyMM(mv,0,view,0,model,0);Matrix.multiplyMM(mvp,0,projection,0,mv,0);
            GLES20.glUseProgram(program);GLES20.glUniformMatrix4fv(trans,1,false,mvp,0);GLES20.glUniformMatrix4fv(rotation,1,false,mv,0);
            positions.position(0);normals.position(0);
            GLES20.glEnableVertexAttribArray(pos);GLES20.glVertexAttribPointer(pos,3,GLES20.GL_FLOAT,false,0,positions);
            GLES20.glEnableVertexAttribArray(norm);GLES20.glVertexAttribPointer(norm,3,GLES20.GL_FLOAT,false,0,normals);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,vertices);
            GLES20.glDisableVertexAttribArray(pos);GLES20.glDisableVertexAttribArray(norm);
            int error=GLES20.glGetError();if(error!=GLES20.GL_NO_ERROR){valid=false;problem.report("OpenGL error "+error);}
        }
    }
}
