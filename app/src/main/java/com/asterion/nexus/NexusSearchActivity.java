package com.asterion.nexus;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import com.azrael.pixivdumpsync.AsterionSearchHub;
import com.asterion.videoflow.NexusWebAcquisition;
import com.openlocal.office.core.FgTeraboxClient;
import com.openlocal.office.core.FgTeraboxDocumentsProvider;
import com.openlocal.office.core.FgTeraboxSearchIndex;
import com.openlocal.office.core.FgSearchIndex;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Federated private/local and opt-in public discovery. Results always retain source.
 * Never sends search text to an online service without user selection.
 */
public final class NexusSearchActivity extends Activity {
    private static final int BACK=0xff0c0e13,PANEL=0xff1a1d27,EDGE=0xff454652,
        PRIMARY=0xfff0f0f2,SECONDARY=0xffa4a8b2,ACCENT=0xffcbab73;
    private static final String[] MODES={"All videos","Adult videos","Hentai videos","Games","EPUB"};
    private final ExecutorService worker=Executors.newFixedThreadPool(3);
    private final AtomicInteger request=new AtomicInteger();
    private final Map<String,List<Result>> groups=new LinkedHashMap<>();
    private EditText term;
    private Spinner mode;
    private CheckBox online;
    private TextView status;
    private LinearLayout results;
    private FgTeraboxClient cloud;
    private FgTeraboxSearchIndex index;
    private FgSearchIndex authorizedIndex;
    private final Map<String,List<Result>> safMatches=new LinkedHashMap<>();
    private String currentTerm="";
    private static final class Result {
        final String type,title,detail,source,id,mime;
        final long size;
        Result(String type,String title,String detail,String source,String id,String mime,long size){
            this.type=type;this.title=title;this.detail=detail;this.source=source;
            this.id=id;this.mime=mime;this.size=size;
        }
    }
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable panel(){
        GradientDrawable d=new GradientDrawable();d.setColor(PANEL);
        d.setCornerRadius(dp(14));d.setStroke(dp(1),EDGE);return d;
    }
    private TextView text(String value,int sp,boolean strong){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);
        t.setTextColor(strong?PRIMARY:SECONDARY);
        if(strong)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private LinearLayout.LayoutParams spaced(int top){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);return p;
    }
    private TextView button(String title,Runnable run){
        TextView t=text(title,15,true);t.setBackground(panel());t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(52));t.setPadding(dp(13),dp(13),dp(13),dp(13));
        t.setFocusable(true);t.setClickable(true);t.setContentDescription(title);
        t.setOnClickListener(v->run.run());return t;
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().setStatusBarColor(BACK);getWindow().setNavigationBarColor(BACK);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACK);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(27),dp(20),dp(32));scroll.addView(root);
        setContentView(scroll);
        TextView overline=text("NEXUS / DISCOVERY",12,true);overline.setTextColor(ACCENT);
        overline.setLetterSpacing(.15f);root.addView(overline);
        root.addView(text("Search",30,true),spaced(12));
        term=new EditText(this);term.setSingleLine(true);term.setTextColor(PRIMARY);
        term.setHintTextColor(SECONDARY);term.setHint("Title, artist or original source");
        term.setBackground(panel());term.setMinHeight(dp(54));
        term.setPadding(dp(16),dp(12),dp(16),dp(12));root.addView(term,spaced(22));
        online=new CheckBox(this);
        online.setText("Include public online provider search");
        online.setTextColor(PRIMARY);online.setButtonTintList(
            android.content.res.ColorStateList.valueOf(ACCENT));
        online.setChecked(false);root.addView(online,spaced(15));
        root.addView(text("When enabled, the entered query is sent to online provider indexes.",
            12,false),spaced(5));
        mode=new Spinner(this);
        mode.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,MODES));
        root.addView(mode,spaced(12));
        root.addView(button("Search sources",this::execute),spaced(14));
        status=text("Local and authorized cloud indexing available",13,false);
        root.addView(status,spaced(19));
        results=new LinearLayout(this);results.setOrientation(LinearLayout.VERTICAL);
        root.addView(results,spaced(14));
        cloud=new FgTeraboxClient(this);
        index=new FgTeraboxSearchIndex(this,cloud,new FgTeraboxSearchIndex.Listener(){
            @Override public void onSearch(String response){
                runOnUiThread(()->receiveCloud(response));
            }
            @Override public void onProgress(String response){
                runOnUiThread(()->{
                    try{
                        JSONObject p=new JSONObject(response);
                        if(p.optBoolean("building"))
                            status.setText("Cloud indexing "+p.optLong("indexed")+
                                " entries · existing matches remain searchable");
                    }catch(Exception ignored){}
                });
            }
        });
    }
        authorizedIndex=new FgSearchIndex(this,new FgSearchIndex.Listener(){
            @Override public void onResult(String response){
                runOnUiThread(()->receiveSaf(response));
            }
            @Override public void onProgress(String response){
                runOnUiThread(()->{
                    try{
                        JSONObject progress=new JSONObject(response);
                        if(progress.optBoolean("building"))
                            status.setText("Connected folders indexing · "+
                                progress.optLong("indexed")+" metadata entries");
                    }catch(Exception ignored){}
                });
            }
        });
    private void execute(){
        String query=term.getText().toString().trim();
        if(query.length()<2||query.length()>180){status.setText("Enter 2–180 characters");return;}
        currentTerm=query;
        final int requestId=request.incrementAndGet();
        groups.clear();safMatches.clear();results.removeAllViews();
        List<Uri> authorizedTrees=new NexusSourceRegistry(this).roots();
        groups.put("Folders",Collections.emptyList());
        for(Uri selected:authorizedTrees){
            authorizedIndex.ensure(selected,false);
            authorizedIndex.search(selected,query,"all",0,requestId);
        }
        status.setText("Searching local and cloud metadata");
        worker.submit(()->{
            try{
                List<NexusRegistry.Item> rows=new NexusRegistry(this).search(query,100);
                ArrayList<Result> found=new ArrayList<>();
                for(NexusRegistry.Item item:rows)found.add(new Result("local",item.displayName,
                    item.mime+" · "+item.length/1024+" KiB","Local",item.sha256,
                    item.mime,item.length));
                runOnUiThread(()->accept(requestId,"Local",found,null));
            }catch(Exception e){runOnUiThread(()->accept(requestId,"Local",
                Collections.emptyList(),e.getClass().getSimpleName()));}
        });
        if(cloud.configured()){
            index.ensure(false);
            index.search(query,"all",0,requestId);
        } else {
            groups.put("Cloud",Collections.emptyList());render();
        }
        if(online.isChecked()){
            final int selected=mode.getSelectedItemPosition();
            worker.submit(()->{
                try{
                    JSONObject response=AsterionSearchHub.searchForNexus(this,query,selected,0);
                    JSONArray items=response.optJSONArray("items");
                    ArrayList<Result> found=new ArrayList<>();
                    if(items!=null)for(int i=0;i<items.length();i++){
                        JSONObject item=items.optJSONObject(i);
                        if(item==null)continue;
                        String url=item.optString("url","");
                        if(!url.startsWith("https://")&&!url.startsWith("http://")&&
                            !url.startsWith("magnet:"))continue;
                        found.add(new Result("online",item.optString("title",url),
                            item.optString("detail",""),item.optString("source","Online"),url,
                            "",-1));
                    }
                    String errors=response.optJSONArray("errors")==null?"":response.optJSONArray("errors").toString();
                    runOnUiThread(()->accept(requestId,"Online",found,
                        response.optJSONArray("errors")!=null&&response.optJSONArray("errors").length()>0?errors:null));
                }catch(Exception e){runOnUiThread(()->accept(requestId,"Online",
                    Collections.emptyList(),e.getClass().getSimpleName()));}
            });
        }
    }
    private void receiveSaf(String raw){
        try{
            JSONObject response=new JSONObject(raw);
            if(response.optInt("token",-1)!=request.get())return;
            String root=response.optString("root","");
            if(root.isBlank())return;
            JSONArray items=response.optJSONArray("items");
            ArrayList<Result> found=new ArrayList<>();
            if(items!=null)for(int i=0;i<items.length();i++){
                JSONObject object=items.optJSONObject(i);
                if(object==null)continue;
                String uri=object.optString("uri","");
                if(!uri.startsWith("content://"))continue;
                String mime=object.optString("mime","application/octet-stream");
                found.add(new Result(object.optBoolean("folder")?"saf-folder":"saf",
                    object.optString("name",uri),
                    object.optString("searchPath",""),Uri.parse(root).getAuthority(),
                    uri,mime,object.optLong("size",-1)));
            }
            safMatches.put(root,found);
            ArrayList<Result> all=new ArrayList<>();
            for(List<Result> rows:safMatches.values())
                for(Result result:rows)if(all.size()<200)all.add(result);
            groups.put("Folders",all);
            render();
        }catch(Exception ignored){}
    }

    private void receiveCloud(String raw){
        try{
            JSONObject response=new JSONObject(raw);
            int token=response.optInt("token",-1);
            if(token!=request.get())return;
            JSONArray items=response.optJSONArray("items");
            ArrayList<Result> found=new ArrayList<>();
            if(items!=null)for(int i=0;i<items.length();i++){
                JSONObject item=items.optJSONObject(i);
                if(item==null)continue;
                String name=item.optString("name","");
                String path=item.optString("path","");
                if(path.isBlank()||!path.startsWith("/"))continue;
                found.add(new Result(item.optBoolean("folder")?"folder":"cloud",
                    name,item.optBoolean("folder")?"Folder":"TeraBox",
                    "Cloud",path,FgTeraboxClient.mime(name),item.optLong("size",-1)));
            }
            accept(token,"Cloud",found,response.optString("error","").isBlank()?null:response.optString("error"));
        }catch(Exception e){status.setText("Cloud returned an invalid index response");}
    }
    private void accept(int token,String source,List<Result> items,String error){
        if(token!=request.get())return;
        groups.put(source,items);
        render();
        String state="Local "+groups.getOrDefault("Local",Collections.emptyList()).size()+
            " · Cloud "+groups.getOrDefault("Cloud",Collections.emptyList()).size()+
            (online.isChecked()?" · Online "+groups.getOrDefault("Online",Collections.emptyList()).size():"");
        status.setText(error==null?state:state+" · "+source+" partial failure");
    }
    private void render(){
        results.removeAllViews();
        for(String source:new String[]{"Local","Folders","Cloud","Online"}){
            if(!groups.containsKey(source))continue;
            List<Result> items=groups.get(source);
            results.addView(text(source+" · "+items.size(),18,true),spaced(18));
            if(items.isEmpty())results.addView(text("No matching items",13,false),spaced(6));
            for(Result r:items){
                LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(16),dp(16),dp(16),dp(14));card.setBackground(panel());
                card.setFocusable(true);card.setClickable(true);
                card.addView(text(r.title,16,true));
                card.addView(text(r.source+" · "+r.detail,12,false),spaced(7));
                card.setOnClickListener(v->open(r));
                results.addView(card,spaced(9));
            }
        }
    }
    private void open(Result r){
        try{
            if("saf".equals(r.type)){
                NexusFileOpen.open(this,Uri.parse(r.id),r.mime,r.title);
                return;
            }
            if("saf-folder".equals(r.type)){
                Uri child=Uri.parse(r.id);
                for(Uri tree:new NexusSourceRegistry(this).roots()){
                    if(tree.getAuthority().equals(child.getAuthority())){
                        Intent intent=new Intent(this,NexusSafBrowserActivity.class);
                        intent.putExtra("tree",tree.toString());
                        intent.putExtra("documentId",android.provider.DocumentsContract.getDocumentId(child));
                        startActivity(intent);return;
                    }
                }
                status.setText("Folder permission no longer granted");return;
            }
            if("folder".equals(r.type)){
                Intent open=new Intent(this,NexusCloudActivity.class);
                open.putExtra("folder",r.id);startActivity(open);return;
            }
            if("online".equals(r.type)){openOnline(r);return;}
            Uri uri="local".equals(r.type)?
                NexusLibraryProvider.uriFor(this,r.id):
                FgTeraboxDocumentsProvider.uriFor(r.id,r.size);
            NexusFileOpen.open(this,uri,r.mime,r.title);
        }catch(Exception e){status.setText("Cannot open selected source: "+e.getMessage());}
    }
    private void openOnline(Result r){
        if(r.id.startsWith("magnet:")){
            status.setText("Magnet metadata selected; use the torrent transfer engine in Downloads");
            return;
        }
        if(!r.id.startsWith("https://")&&!r.id.startsWith("http://")){
            status.setText("Source URL rejected");return;
        }
        new AlertDialog.Builder(this).setTitle(r.title)
            .setItems(new String[]{"Acquire verified selected original","Open provider page"},
                (dialog,which)->{
                    if(which==1){
                        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(r.id)));}
                        catch(Exception e){status.setText("No browser available");}
                    } else {
                        status.setText("Resolving selected media original");
                        worker.submit(()->{
                            try{
                                NexusRegistry.Item item=NexusWebAcquisition.acquireSelected(this,r.id);
                                runOnUiThread(()->{
                                    status.setText("Verified original indexed: "+item.displayName);
                                    new AlertDialog.Builder(this)
                                        .setTitle("Original indexed")
                                        .setItems(new String[]{"Open in library","Stay in search"},
                                            (d,w)->{
                                                if(w==0)NexusFileOpen.open(this,
                                                    NexusLibraryProvider.uriFor(this,item.sha256),
                                                    item.mime,item.displayName);
                                            }).show();
                                });
                            }catch(Exception e){runOnUiThread(()->status.setText(
                                "Original not acquired: "+e.getMessage()));}
                        });
                    }
                }).show();
    }
    @Override protected void onDestroy(){
        worker.shutdownNow();index.close();authorizedIndex.close();super.onDestroy();
    }
}
