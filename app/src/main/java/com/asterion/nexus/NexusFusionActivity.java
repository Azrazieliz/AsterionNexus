package com.asterion.nexus;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import com.ailm.android.runtime.LocalDatabase;
import com.ailm.android.runtime.FusionDatabaseRepository;
import com.ailm.android.runtime.FusionValidationReport;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Retains actual Core Fusion SQLite, validation, import and user-owned exports. */
public final class NexusFusionActivity extends Activity {
    private static final int IMPORT=521,EXPORT=522;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private LocalDatabase database;
    private FusionDatabaseRepository fusion;
    private TextView state,overview;
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String content,int size,boolean header){
        TextView t=new TextView(this);t.setText(content);t.setTextSize(size);
        t.setTextColor(header?0xfff4f5f7:0xffa8b0bb);
        if(header)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private GradientDrawable panel(){
        GradientDrawable d=new GradientDrawable();d.setColor(0xff1b1e23);
        d.setCornerRadius(dp(14));d.setStroke(dp(1),0xff454a53);return d;
    }
    private LinearLayout.LayoutParams space(int top){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.topMargin=dp(top);return p;
    }
    private TextView button(String value,Runnable run){
        TextView v=text(value,15,true);v.setGravity(Gravity.CENTER);
        v.setBackground(panel());v.setMinHeight(dp(52));
        v.setPadding(dp(15),dp(12),dp(15),dp(12));
        v.setFocusable(true);v.setClickable(true);
        v.setOnClickListener(x->run.run());return v;
    }
    private void setState(String value){runOnUiThread(()->state.setText(value));}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().setStatusBarColor(0xff0e1013);
        getWindow().setNavigationBarColor(0xff0e1013);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xff0e1013);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22),dp(35),dp(22),dp(30));scroll.addView(root);
        setContentView(scroll);
        root.addView(text("NEXUS / CORE",12,false));
        root.addView(text("Fusion",31,true),space(12));
        root.addView(text("Native database",16,true),space(28));
        overview=text("Reading Fusion database",14,false);root.addView(overview,space(8));
        state=text("Local, private data",13,false);root.addView(state,space(20));
        root.addView(button("Validate Fusion records",this::refresh),space(14));
        root.addView(button("Import Fusion JSON",this::selectImport),space(10));
        root.addView(button("Export Fusion JSON",this::selectExport),space(10));
        worker.submit(()->{
            try{
                database=new LocalDatabase(this);
                fusion=new FusionDatabaseRepository(database);
                refresh();
            }catch(Exception e){setState("Fusion unavailable: "+e.getMessage());}
        });
    }
    private void refresh(){
        worker.submit(()->{
            if(fusion==null){setState("Initializing Fusion");return;}
            try{
                Map<String,Integer> counts=fusion.logicalContentCounts();
                FusionValidationReport report=fusion.validateDatabase(false);
                StringBuilder info=new StringBuilder();
                for(Map.Entry<String,Integer> e:counts.entrySet())
                    info.append(e.getKey()).append("   ").append(e.getValue()).append("\n");
                final String text=info.toString();
                runOnUiThread(()->overview.setText(text.isBlank()?"No indexed Fusion records":text));
                setState(report.getValid()?"Fusion schema verified":
                    "Fusion needs review: "+report.getIssues().size()+" validation issues");
            }catch(Exception e){setState("Fusion validation failed: "+e.getMessage());}
        });
    }
    private void selectImport(){
        Intent choose=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        choose.addCategory(Intent.CATEGORY_OPENABLE);
        choose.setType("application/json");
        startActivityForResult(choose,IMPORT);
    }
    private void selectExport(){
        Intent choose=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        choose.addCategory(Intent.CATEGORY_OPENABLE);
        choose.setType("application/json");
        choose.putExtra(Intent.EXTRA_TITLE,"nexus-fusion-export.json");
        startActivityForResult(choose,EXPORT);
    }
    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        if(request==EXPORT){
            worker.submit(()->{
                try{
                    String content=fusion.exportJson(true);
                    try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
                        if(out==null)throw new IOException("Export destination not writable");
                        out.write(content.getBytes(StandardCharsets.UTF_8));out.flush();
                    }
                    setState("Fusion export written");
                }catch(Exception e){setState("Export failed: "+e.getMessage());}
            });return;
        }
        if(request==IMPORT)new AlertDialog.Builder(this)
            .setTitle("Import Fusion records?")
            .setMessage("Import selected JSON without replacing existing Nexus Fusion data. Existing source-app data stays untouched.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Import",(d,w)->worker.submit(()->{
                try{
                    ByteArrayOutputStream payload=new ByteArrayOutputStream();
                    try(InputStream input=getContentResolver().openInputStream(uri)){
                        if(input==null)throw new IOException("Input unavailable");
                        byte[] bytes=new byte[32768];int n;
                        while((n=input.read(bytes))!=-1){
                            if(payload.size()+n>32*1024*1024)throw new IOException("Import exceeds 32 MiB");
                            payload.write(bytes,0,n);
                        }
                    }
                    boolean ok=fusion.importJson(payload.toString(StandardCharsets.UTF_8),false).getOk();
                    setState(ok?"Fusion records imported":"Fusion import validation failed");
                    refresh();
                }catch(Exception e){setState("Import failed: "+e.getMessage());}
            })).show();
    }
    @Override protected void onDestroy(){
        worker.shutdownNow();if(database!=null)database.close();super.onDestroy();
    }
}
