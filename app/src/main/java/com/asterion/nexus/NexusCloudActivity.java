package com.asterion.nexus;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.*;
import android.view.*;
import android.widget.*;
import com.openlocal.office.core.FgTeraboxClient;
import com.openlocal.office.core.FgTeraboxDocumentsProvider;
import com.openlocal.office.core.FgTeraboxSearchIndex;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;

/** Native cloud browser, metadata-only indexed search and explicit offline publication. */
public final class NexusCloudActivity extends Activity {
    private static final int BACKGROUND=0xff0c0e13,PANEL=0xff191c25,WHITE=0xfff0f0f2,
        SECONDARY=0xffa4a9b5,GOLD=0xffceac71,EDGE=0xff383c48;
    private final ExecutorService queue=Executors.newFixedThreadPool(3);
    private final FgTeraboxClient[] cloud=new FgTeraboxClient[1];
    private FgTeraboxSearchIndex index;
    private TextView status,pathTitle;
    private EditText searchInput,tokenInput;
    private LinearLayout results,accountPanel,actions;
    private String currentPath="/";
    private int page=1,request=0;
    private volatile int generation=0;
    private final ArrayList<NexusRegistry.Item> localFound=new ArrayList<>();
    private boolean indexBuilding;
    private String indexError="";
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable box(int color){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(14));
        d.setStroke(dp(1),EDGE);return d;
    }
    private TextView label(String t,int size,int color,boolean bold){
        TextView v=new TextView(this);v.setText(t);v.setTextSize(size);v.setTextColor(color);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;
    }
    private LinearLayout.LayoutParams spacing(int top){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);return p;
    }
    private EditText field(String hint){
        EditText e=new EditText(this);e.setSingleLine(true);e.setTextColor(WHITE);
        e.setHintTextColor(SECONDARY);e.setHint(hint);e.setTextSize(15);
        e.setBackground(box(PANEL));e.setPadding(dp(16),dp(13),dp(16),dp(13));
        e.setMinHeight(dp(52));return e;
    }
    private TextView button(String title,Runnable onClick){
        TextView x=label(title,14,WHITE,true);x.setGravity(Gravity.CENTER);
        x.setPadding(dp(13),dp(14),dp(13),dp(14));x.setMinHeight(dp(52));
        x.setBackground(box(PANEL));x.setFocusable(true);x.setClickable(true);
        x.setContentDescription(title);x.setOnClickListener(v->onClick.run());return x;
    }
    private void state(String value){status.setText(value);}
    private void work(String busy,Worker task){
        state(busy);
        queue.submit(()->{try{task.run();}catch(Exception e){
            runOnUiThread(()->state("Cloud operation failed: "+
                e.getClass().getSimpleName()+(e.getMessage()==null?"":": "+e.getMessage())));
        }});
    }
    interface Worker {void run()throws Exception;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        cloud[0]=new FgTeraboxClient(this);
        index=new FgTeraboxSearchIndex(this,cloud[0],new FgTeraboxSearchIndex.Listener(){
            @Override public void onSearch(String json){runOnUiThread(()->onCloudSearch(json));}
            @Override public void onProgress(String json){runOnUiThread(()->{
                try{
                    JSONObject progress=new JSONObject(json);
                    indexBuilding=progress.optBoolean("building");
                    indexError=progress.optString("error","");
                    if(searchInput!=null&&!searchInput.getText().toString().isBlank())
                        state(indexBuilding?"Cloud index: "+progress.optLong("indexed")+" entries indexed":
                            indexError.isBlank()?"Cloud index ready":indexError);
                }catch(Exception ignored){}
            });}
        });
        FrameLayout screen=new FrameLayout(this);screen.setBackgroundColor(BACKGROUND);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20),dp(25),dp(20),dp(36));scroll.addView(content);
        screen.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        screen.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets a=insets.getInsets(android.view.WindowInsets.Type.statusBars());
            android.graphics.Insets b=insets.getInsets(android.view.WindowInsets.Type.navigationBars());
            v.setPadding(0,a.top,0,b.bottom);return insets;
        });
        setContentView(screen);
        TextView brand=label("NEXUS / TERABox",12,GOLD,true);
        brand.setLetterSpacing(.15f);content.addView(brand);
        content.addView(label("Cloud",31,WHITE,true),spacing(10));
        accountPanel=new LinearLayout(this);accountPanel.setOrientation(LinearLayout.VERTICAL);
        content.addView(accountPanel,spacing(22));
        searchInput=field("Search local and indexed cloud files");
        content.addView(searchInput,spacing(21));
        searchInput.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){
                String q=s.toString().trim();
                if(q.isEmpty())browse();else search(q);
            }
            @Override public void afterTextChanged(Editable e){}
        });
        actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        pathTitle=label("/",14,WHITE,true);
        content.addView(pathTitle,spacing(23));
        TextView up=button("Up",()->{
            if(!"/".equals(currentPath)){
                int i=currentPath.lastIndexOf('/');
                currentPath=i<=0?"/":currentPath.substring(0,i);page=1;browse();
            }
        });
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1);left.rightMargin=dp(8);
        actions.addView(up,left);
        actions.addView(button("Refresh",()->{
            if(!cloud[0].configured()){state("Connect TeraBox first");return;}
            if(searchInput.getText().length()>0)search(searchInput.getText().toString());
            else browse();
        }),new LinearLayout.LayoutParams(0,-2,1));
        content.addView(actions,spacing(10));
        status=label("Local data remains on this device",13,SECONDARY,false);
        content.addView(status,spacing(17));
        results=new LinearLayout(this);results.setOrientation(LinearLayout.VERTICAL);
        content.addView(results,spacing(15));
        configure();
        if(cloud[0].configured()){index.ensure(false);browse();}
        else search("");
    }
    private void configure(){
        accountPanel.removeAllViews();
        if(cloud[0].configured()){
            TextView ready=label("TeraBox account connected",15,WHITE,true);
            accountPanel.addView(ready);
            accountPanel.addView(button("Disconnect account",()->new AlertDialog.Builder(this)
                .setTitle("Disconnect TeraBox?")
                .setMessage("Remove this Nexus login. Existing originals and indexed local media remain unchanged.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Disconnect",(d,w)->{
                    cloud[0].disconnect();currentPath="/";page=1;configure();results.removeAllViews();
                    state("TeraBox disconnected");searchInput.setText("");
                }).show()),spacing(10));
            return;
        }
        tokenInput=field("TeraBox ndus session cookie");
        tokenInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT|
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        accountPanel.addView(tokenInput);
        accountPanel.addView(button("Connect TeraBox",()->{
            String token=tokenInput.getText().toString().trim();
            tokenInput.setText("");
            if(!cloud[0].setCookie(token)){
                state("TeraBox session was rejected locally");return;
            }
            configure();index.ensure(false);browse();
        }),spacing(10));
    }
    private void browse(){
        if(results==null)return;
        if(!cloud[0].configured()){results.removeAllViews();state("Connect a TeraBox account to browse");return;}
        final String path=currentPath;final int requested=page;final int seq=++generation;
        pathTitle.setText(path+"  ·  page "+requested);
        work("Listing "+path,()->{
            JSONArray raw=cloud[0].listPageCached(path,requested);
            ArrayList<JSONObject> rows=new ArrayList<>();
            for(int i=0;i<raw.length();i++){
                JSONObject node=raw.optJSONObject(i);
                if(node!=null)rows.add(FgTeraboxClient.mapEntry(node));
            }
            runOnUiThread(()->{
                if(seq!=generation||!path.equals(currentPath)||requested!=page)return;
                showFiles(rows,false);
                state(rows.size()+" items · "+path);
            });
        });
    }
    private void search(String query){
        if(results==null)return;
        final int requestId=++request;
        ++generation;
        work("Searching local library",()->{
            List<NexusRegistry.Item> local=new NexusRegistry(this).search(query,100);
            runOnUiThread(()->{
                if(requestId!=request)return;
                localFound.clear();localFound.addAll(local);
                if(!cloud[0].configured()){
                    showFiles(Collections.emptyList(),true);
                    state(local.size()+" local results · cloud disconnected");
                }
            });
        });
        if(cloud[0].configured()){
            index.ensure(false);
            index.search(query,"all",0,requestId);
        }
    }
    private void onCloudSearch(String value){
        try{
            JSONObject response=new JSONObject(value);
            if(response.optInt("token",-1)!=request||searchInput.getText().length()==0)return;
            JSONArray matches=response.optJSONArray("items");
            ArrayList<JSONObject> cloudMatches=new ArrayList<>();
            if(matches!=null)for(int i=0;i<matches.length();i++){
                JSONObject node=matches.optJSONObject(i);
                if(node!=null)cloudMatches.add(node);
            }
            showFiles(cloudMatches,true);
            String error=response.optString("error","");
            state(localFound.size()+" local · "+cloudMatches.size()+" cloud"+
                (response.optBoolean("building")?" · cloud index building":"")+
                (error.isBlank()?"":" · "+error));
        }catch(Exception error){state("Cloud search response invalid");}
    }
    private void showFiles(List<JSONObject> files,boolean searching){
        results.removeAllViews();
        if(searching){
            for(NexusRegistry.Item item:localFound){
                LinearLayout line=card(item.displayName,"LOCAL · "+item.mime+" · "+
                    (item.length/1024)+" KiB");
                line.setOnClickListener(v->{
                    Uri uri=NexusLibraryProvider.uriFor(this,item.sha256);
                    openUri(uri,item.mime,item.displayName);
                });
                results.addView(line,spacing(9));
            }
        }
        for(JSONObject node:files){
            final String path=node.optString("path",node.optString("id",""));
            if(!path.startsWith("/"))continue;
            boolean folder=node.optBoolean("folder",node.optInt("isdir",0)==1);
            String name=node.optString("name",node.optString("server_filename",path));
            final long size=node.optLong("size",-1);
            String mime=FgTeraboxClient.mime(name);
            LinearLayout row=card(name,(folder?"FOLDER":"TERABOX · "+mime)+
                (size>=0?" · "+(size/1024)+" KiB":""));
            row.setOnClickListener(v->{
                if(folder){
                    searchInput.setText("");currentPath=path;page=1;browse();
                } else openUri(FgTeraboxDocumentsProvider.uriFor(path,size),mime,name);
            });
            if(!folder){
                TextView keep=button("Save offline",()->new AlertDialog.Builder(this)
                    .setTitle("Save verified original offline?")
                    .setMessage(name+"\nThis creates a private verified copy; the cloud source remains unchanged.")
                    .setNegativeButton("Cancel",null)
                    .setPositiveButton("Save",(d,which)->saveOffline(path,name,mime,size)).show());
                row.addView(keep,spacing(10));
            }
            results.addView(row,spacing(9));
        }
        if(!searching){
            LinearLayout pagination=new LinearLayout(this);
            pagination.setOrientation(LinearLayout.HORIZONTAL);
            if(page>1){
                TextView prev=button("Previous page",()->{page--;browse();});
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
                lp.rightMargin=dp(8);pagination.addView(prev,lp);
            }
            if(files.size()>=FgTeraboxClient.pageSize()){
                pagination.addView(button("Next page",()->{page++;browse();}),
                    new LinearLayout.LayoutParams(0,-2,1));
            }
            if(pagination.getChildCount()>0)results.addView(pagination,spacing(16));
        }
        if(results.getChildCount()==0)results.addView(
            label(searching?"No matching indexed items":"Folder empty",14,SECONDARY,false),spacing(12));
    }
    private LinearLayout card(String title,String detail){
        LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(17),dp(17),dp(17),dp(15));
        v.setBackground(box(PANEL));v.setClickable(true);v.setFocusable(true);
        v.setMinHeight(dp(70));v.setContentDescription("Open "+title);
        v.addView(label(title,16,WHITE,true));v.addView(label(detail,12,SECONDARY,false),spacing(7));
        return v;
    }
    private void saveOffline(String path,String name,String mime,long size){
        String account=cloud[0].metadataScope();
        work("Saving verified original offline",()->{
            try(InputStream input=cloud[0].open(path)){
                NexusRegistry.Item item=new NexusRegistry(this).ingest(input,name,mime,"terabox",
                    account,"terabox:"+account+":"+path,size,null);
                runOnUiThread(()->state("Indexed offline original: "+item.displayName));
            }
        });
    }
    private void openUri(Uri uri,String mime,String title){
        try{
            if(mime.startsWith("video/")){
                Intent video=new Intent(this,NexusRounderPlaybackActivity.class);
                video.setDataAndType(uri,mime);video.putExtra("title",title);startActivity(video);
            } else {
                Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(view);
            }
        }catch(Exception error){state("Cannot open selected file: "+error.getClass().getSimpleName());}
    }
    @Override public void onDestroy(){queue.shutdownNow();index.close();super.onDestroy();}
}
