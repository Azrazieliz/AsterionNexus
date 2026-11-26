package com.asterion.nexus;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.asterion.videoflow.NexusDirectAcquisition;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Library-first, source-backed interim Nexus entry: no speculative controls or marketing copy. */
public final class NexusLibraryActivity extends Activity {
    private static final int PICK=822;
    private static final int BG=Color.rgb(12,14,19), PANEL=Color.rgb(24,27,35);
    private static final int TEXT=Color.rgb(237,237,239), MUTED=Color.rgb(159,164,175);
    private static final int ACCENT=Color.rgb(205,171,108);
    private final ExecutorService worker=Executors.newFixedThreadPool(2);
    private LinearLayout list;
    private TextView status;
    private EditText searchField, address;
    private final NexusRegistry[] store=new NexusRegistry[1];
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private GradientDrawable surface(int color,int radius,boolean outlined){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));
        if(outlined)d.setStroke(dp(1),Color.rgb(59,61,71));
        return d;
    }
    private TextView text(String value,int sp,int color,boolean strong){
        TextView t=new TextView(this);t.setText(value);t.setTextColor(color);t.setTextSize(sp);
        if(strong)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private LinearLayout.LayoutParams space(int top){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(top);return lp;
    }
    private TextView action(String title,Runnable callback){
        TextView t=text(title,15,TEXT,true);
        t.setGravity(Gravity.CENTER);t.setMinimumHeight(dp(52));t.setPadding(dp(18),dp(12),dp(18),dp(12));
        t.setBackground(surface(PANEL,14,true));t.setClickable(true);t.setFocusable(true);
        t.setContentDescription(title);t.setOnClickListener(v->callback.run());return t;
    }
    private EditText entry(String hint){
        EditText e=new EditText(this);e.setSingleLine(true);e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);e.setTextSize(16);e.setHint(hint);
        e.setPadding(dp(16),dp(12),dp(16),dp(12));
        e.setBackground(surface(PANEL,14,true));e.setMinimumHeight(dp(52));
        return e;
    }
    private void label(String value){status.setText(value);}
    private void runTask(String operation,CallableImport task){
        label(operation);
        worker.submit(()->{
            try{
                NexusRegistry.Item imported=task.execute();
                runOnUiThread(()->{label("Indexed "+imported.displayName);refresh();});
            }catch(Exception error){
                runOnUiThread(()->label("Import failed: "+error.getClass().getSimpleName()+
                    (error.getMessage()==null?"":": "+error.getMessage())));
            }
        });
    }
    @FunctionalInterface private interface CallableImport{
        NexusRegistry.Item execute()throws Exception;
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        store[0]=new NexusRegistry(this);
        FrameLayout full=new FrameLayout(this);full.setBackgroundColor(BG);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20),dp(28),dp(20),dp(30));
        scroll.addView(column);
        full.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        full.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets top=insets.getInsets(android.view.WindowInsets.Type.statusBars());
            android.graphics.Insets bottom=insets.getInsets(android.view.WindowInsets.Type.navigationBars());
            view.setPadding(0,top.top,0,bottom.bottom);return insets;
        });
        setContentView(full);
        TextView brand=text("ASTERION  /  NEXUS",11,ACCENT,true);brand.setLetterSpacing(.16f);
        column.addView(brand,space(6));
        column.addView(text("Library",31,TEXT,true),space(14));
        LinearLayout workspaces=new LinearLayout(this);
        workspaces.setOrientation(LinearLayout.HORIZONTAL);
        TextView cloudNav=action("Cloud",()->startActivity(
            new Intent(this,NexusCloudActivity.class)));
        LinearLayout.LayoutParams n1=new LinearLayout.LayoutParams(0,dp(52),1);
        n1.rightMargin=dp(8);workspaces.addView(cloudNav,n1);
        workspaces.addView(action("Fusion",()->startActivity(
            new Intent(this,NexusFusionActivity.class))),
            new LinearLayout.LayoutParams(0,dp(52),1));
        column.addView(workspaces,space(18));
        column.addView(action("Search all sources",()->startActivity(
            new Intent(this,NexusSearchActivity.class))),space(10));
        column.addView(action("Connected folders",()->startActivity(
            new Intent(this,NexusSourcesActivity.class))),space(10));
        column.addView(action("Core runtime",()->startActivity(
            new Intent(this,NexusCoreActivity.class))),space(10));
        searchField=entry("Search indexed originals");column.addView(searchField,space(24));
        searchField.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){refresh();}
            @Override public void afterTextChanged(Editable e){}
        });
        LinearLayout tools=new LinearLayout(this);tools.setOrientation(LinearLayout.HORIZONTAL);
        TextView add=action("Add file",this::selectFile);
        LinearLayout.LayoutParams b1=new LinearLayout.LayoutParams(0,dp(52),1);
        b1.rightMargin=dp(8);tools.addView(add,b1);
        TextView update=action("Refresh",this::refresh);
        tools.addView(update,new LinearLayout.LayoutParams(0,dp(52),1));
        column.addView(tools,space(12));
        column.addView(text("Selected original URL",13,MUTED,true),space(24));
        address=entry("https://.../original.mp4");
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|
                             android.text.InputType.TYPE_TEXT_VARIATION_URI);
        column.addView(address,space(9));
        column.addView(action("Acquire original",()->{
            String url=address.getText().toString().trim();
            if(url.isEmpty()){label("Enter a selected original URL");return;}
            runTask("Receiving selected original",()->NexusDirectAcquisition.acquire(this,url));
        }),space(10));
        status=text("Reading local index",13,MUTED,false);column.addView(status,space(20));
        column.addView(text("Files",19,TEXT,true),space(26));
        list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);
        column.addView(list,space(10));
        refresh();
    }
    private void selectFile(){
        Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.setType("*/*");pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|
                      Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(pick,PICK);
    }
    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=PICK||result!=RESULT_OK||data==null||data.getData()==null)return;
        final Uri uri=data.getData();
        try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
        catch(SecurityException ignored){}
        runTask("Importing selected file",()->{
            String name=uri.getLastPathSegment();
            long length=-1;
            try(Cursor c=getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
                if(c!=null&&c.moveToFirst()){
                    int n=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int s=c.getColumnIndex(OpenableColumns.SIZE);
                    if(n>=0&&!c.isNull(n))name=c.getString(n);
                    if(s>=0&&!c.isNull(s))length=c.getLong(s);
                }
            }
            try(InputStream input=getContentResolver().openInputStream(uri)){
                if(input==null)throw new java.io.IOException("Source cannot be opened");
                return store[0].ingest(input,name,getContentResolver().getType(uri),
                    "document","device",uri.toString(),length,null);
            }
        });
    }
    private void refresh(){
        if(list==null)return;
        String query=searchField==null?"":searchField.getText().toString();
        worker.submit(()->{
            try{
                List<NexusRegistry.Item> found=store[0].search(query,100);
                runOnUiThread(()->show(found));
            }catch(Exception error){runOnUiThread(()->label("Index read failed: "+error.getMessage()));}
        });
    }
    private void show(List<NexusRegistry.Item> items){
        list.removeAllViews();
        if(items.isEmpty()){
            list.addView(text("No indexed originals",14,MUTED,false),space(12));
            if(status.getText().toString().startsWith("Reading"))label("0 indexed");
            return;
        }
        if(status.getText().toString().startsWith("Reading"))label(items.size()+" indexed results");
        for(NexusRegistry.Item item:items){
            LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(17),dp(16),dp(17),dp(16));
            card.setBackground(surface(PANEL,14,true));
            card.addView(text(item.displayName,16,TEXT,true));
            String detail=item.sourceKind+"  ·  "+(item.length/1024)+" KiB  ·  "+item.mime;
            card.addView(text(detail,12,MUTED,false),space(7));
            card.setMinimumHeight(dp(72));card.setFocusable(true);card.setClickable(true);
            card.setContentDescription("Open "+item.displayName);
            card.setOnClickListener(v->open(item));
            list.addView(card,space(9));
        }
    }
    private void open(NexusRegistry.Item item){
        try{
            Uri uri=NexusLibraryProvider.uriFor(this,item.sha256);
            if(item.mime.equals("application/pdf")||item.mime.equals("application/epub+zip")||
                item.mime.equals("application/vnd.comicbook+zip")||item.mime.equals("application/zip")||
                item.mime.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")){
                Intent reader=new Intent(this,NexusReaderActivity.class);
                reader.setDataAndType(uri,item.mime);reader.putExtra("title",item.displayName);
                startActivity(reader);
            } else             if(item.mime.startsWith("video/")){
                Intent own=new Intent(this,NexusPlaybackActivity.class);
                own.setDataAndType(uri,item.mime);startActivity(own);
            } else {
                Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,item.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
            }
        }catch(Exception error){label("Cannot open: "+error.getMessage());}
    }
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
