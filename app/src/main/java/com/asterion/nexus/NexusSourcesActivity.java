package com.asterion.nexus;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.widget.*;
import com.openlocal.office.core.FgSearchIndex;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;

/** Owner-authorized SAF roots: existing Rounder TeraBox or local/provider folders. */
public final class NexusSourcesActivity extends Activity {
    private static final int PICK=7201, BG=0xff0c0e13,PANEL=0xff1a1d27,
        TEXT=0xfff0f0f2,MUTED=0xffa4a8b2,GOLD=0xffcfb079;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private NexusSourceRegistry sourceRegistry;
    private FgSearchIndex index;
    private LinearLayout list;
    private TextView status;
    private int dp(int x){return (int)(getResources().getDisplayMetrics().density*x+.5f);}
    private GradientDrawable surface(){
        GradientDrawable d=new GradientDrawable();d.setColor(PANEL);
        d.setCornerRadius(dp(14));d.setStroke(dp(1),0xff454652);return d;
    }
    private TextView text(String value,int sp,boolean strong){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);
        t.setTextColor(strong?TEXT:MUTED);
        if(strong)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private LinearLayout.LayoutParams space(int top){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);return p;
    }
    private TextView button(String title,Runnable run){
        TextView t=text(title,14,true);t.setGravity(Gravity.CENTER);
        t.setPadding(dp(13),dp(13),dp(13),dp(13));t.setMinHeight(dp(52));
        t.setBackground(surface());t.setFocusable(true);t.setClickable(true);
        t.setContentDescription(title);t.setOnClickListener(v->run.run());return t;
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        sourceRegistry=new NexusSourceRegistry(this);
        index=new FgSearchIndex(this,new FgSearchIndex.Listener(){
            @Override public void onResult(String json){}
            @Override public void onProgress(String json){
                runOnUiThread(()->{
                    try{
                        JSONObject p=new JSONObject(json);
                        status.setText((p.optBoolean("building")?"Indexing":"Indexed")+
                            " · "+p.optLong("indexed")+" items");
                    }catch(Exception ignored){}
                });
            }
        });
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20),dp(26),dp(20),dp(32));scroll.addView(content);
        setContentView(scroll);
        TextView brand=text("NEXUS / SOURCES",12,true);brand.setTextColor(GOLD);
        brand.setLetterSpacing(.15f);content.addView(brand);
        content.addView(text("Connected folders",29,true),space(12));
        content.addView(text("Use folders granted through Android’s document picker, including the existing Rounder cloud provider. The original source remains in place.",13,false),space(12));
        content.addView(button("Add folder",()->{
            Intent choose=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            choose.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(choose,PICK);
        }),space(20));
        status=text("Inspecting access grants",13,false);content.addView(status,space(18));
        list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list,space(14));refresh();
    }
    @SuppressWarnings("deprecation") @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=PICK||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        try{
            getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            sourceRegistry.add(uri);
            index.ensure(uri,false);
            status.setText("Connected read-only source");
            refresh();
        }catch(Exception error){status.setText("Cannot connect: "+error.getMessage());}
    }
    private void refresh(){
        list.removeAllViews();
        List<Uri> roots=sourceRegistry.roots();
        if(roots.isEmpty()){list.addView(text("No document trees connected",14,false),space(9));return;}
        for(Uri uri:roots){
            String name=uri.getAuthority()+" · "+uri.getLastPathSegment();
            LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(15),dp(16),dp(15),dp(16));panel.setBackground(surface());
            TextView title=text(name,15,true);title.setMaxLines(2);panel.addView(title);
            TextView browse=button("Browse",()->{
                Intent intent=new Intent(this,NexusSafBrowserActivity.class);
                intent.putExtra("tree",uri.toString());startActivity(intent);
            });panel.addView(browse,space(12));
            TextView update=button("Reindex metadata",()->{
                index.ensure(uri,true);status.setText("Source metadata reindex requested");
            });panel.addView(update,space(9));
            TextView remove=button("Remove connection",()->{
                new AlertDialog.Builder(this).setTitle("Remove source access?")
                    .setMessage("Disconnect this granted folder. No files are deleted.")
                    .setNegativeButton("Cancel",null)
                    .setPositiveButton("Remove",(d,w)->{
                        sourceRegistry.remove(uri);status.setText("Source access removed");refresh();
                    }).show();
            });panel.addView(remove,space(9));
            list.addView(panel,space(11));
        }
    }
    @Override protected void onDestroy(){worker.shutdownNow();index.close();super.onDestroy();}
}
