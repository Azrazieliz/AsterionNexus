package com.asterion.nexus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import java.io.*;
import java.util.concurrent.*;

/** Owner-selected exact replacement only; no automatic deletion or remote
 * provider access. Damaged bytes are retained by the original store. */
public final class NexusRecoveryActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private NexusRegistry.Item item;
    private NexusRegistry registry;
    private TextView status;
    private Button pick;
    private boolean running;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        registry=new NexusRegistry(this);
        item=registry.find(getIntent().getStringExtra("sha256"));
        if(item==null){finish();return;}
        LinearLayout layout=new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(20*getResources().getDisplayMetrics().density);
        layout.setPadding(pad,pad,pad,pad);
        TextView title=new TextView(this);title.setTextSize(20);title.setText(item.displayName);
        TextView identity=new TextView(this);identity.setText("Expected SHA-256\n"+item.sha256+"\n"+item.length+" bytes");
        status=new TextView(this);status.setText("Checking original bytes…");
        pick=new Button(this);pick.setText("Select exact replacement copy");pick.setEnabled(false);
        pick.setOnClickListener(v->{
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try{startActivityForResult(intent,47);}
            catch(android.content.ActivityNotFoundException e){status.setText("No document picker available");}
        });
        layout.addView(title);layout.addView(identity);layout.addView(status);layout.addView(pick);
        setContentView(layout);check();
    }
    private void check(){
        worker.execute(()->{
            try{
                boolean valid=registry.verifyOriginal(item);
                runOnUiThread(()->{
                    if(isDestroyed())return;
                    status.setText(valid?"Exact recorded bytes verified. Format/decoder integrity not assessed.":"Recorded bytes missing or changed. Select a byte-identical verified copy.");
                    pick.setEnabled(!valid&&!running);
                });
            }catch(Exception e){runOnUiThread(()->{if(!isDestroyed()){status.setText("Verification unavailable: "+e.getMessage());pick.setEnabled(false);}});}
        });
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=47||result!=RESULT_OK||data==null||data.getData()==null||running)return;
        Uri replacement=data.getData();
        new AlertDialog.Builder(this).setTitle("Restore recorded bytes?")
            .setMessage("The selected copy must match the exact recorded length and SHA-256. If the original is damaged, its current bytes will be retained in the private recovery journal before replacement.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Verify and restore",(dialog,which)->restore(replacement)).show();
    }
    private void restore(Uri uri){
        running=true;pick.setEnabled(false);status.setText("Verifying replacement copy…");
        worker.execute(()->{
            String job="nexus-recovery-v1:"+item.sha256;
            try{
                registry.job(job,"QUEUED",0,null);registry.job(job,"RECEIVING",0,null);
                NexusOriginalStore.Recovery result;
                try(InputStream input=getContentResolver().openInputStream(uri)){
                    if(input==null)throw new IOException("Selected copy unavailable");
                    result=registry.recoverOriginal(item,input);
                }
                registry.job(job,"VERIFIED",item.length,null);registry.job(job,"INDEXED",item.length,null);
                String message=result.alreadyVerified?"Original already matches recorded bytes."
                    :"Exact original restored and readback verified. "+(result.retainedDamage!=null?"Damaged bytes retained.":"Original had been missing.");
                runOnUiThread(()->{
                    if(isDestroyed())return;running=false;status.setText(message);pick.setEnabled(false);
                });
            }catch(Exception e){
                try{registry.job(job,"FAILED",0,e.getClass().getSimpleName());}catch(Exception ignored){}
                runOnUiThread(()->{if(!isDestroyed()){running=false;status.setText("Recovery not certified: "+e.getMessage());pick.setEnabled(true);}});
            }
        });
    }
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
