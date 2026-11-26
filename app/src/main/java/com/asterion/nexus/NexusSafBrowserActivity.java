package com.asterion.nexus;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.widget.*;
import com.openlocal.office.core.FgTeraboxDocumentsProvider;
import java.util.*;
import java.util.concurrent.*;

/** Paged, read-only DocumentsProvider browser, including existing Rounder accounts. */
public final class NexusSafBrowserActivity extends Activity {
    private static final int BACK=0xff0c0e13,PANEL=0xff1a1d27,
        WHITE=0xfff0f0f2,GRAY=0xffa4a8b2,GOLD=0xffceb07d, PAGE=96;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ArrayDeque<String> parents=new ArrayDeque<>();
    private Uri tree;
    private String documentId;
    private int page=0,request=0;
    private LinearLayout results;
    private TextView status,location;
    private int dp(int x){return (int)(getResources().getDisplayMetrics().density*x+.5f);}
    private GradientDrawable surface(){
        GradientDrawable d=new GradientDrawable();d.setColor(PANEL);
        d.setStroke(dp(1),0xff434651);d.setCornerRadius(dp(14));return d;
    }
    private TextView text(String s,int size,boolean strong){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);
        t.setTextColor(strong?WHITE:GRAY);
        if(strong)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private LinearLayout.LayoutParams space(int top){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);return p;
    }
    private TextView button(String label,Runnable handler){
        TextView t=text(label,14,true);t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(52));t.setPadding(dp(13),dp(13),dp(13),dp(13));
        t.setBackground(surface());t.setFocusable(true);t.setClickable(true);
        t.setOnClickListener(v->handler.run());return t;
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(BACK);getWindow().setNavigationBarColor(BACK);
        String selected=getIntent().getStringExtra("tree");
        if(selected==null){finish();return;}
        tree=Uri.parse(selected);
        if(!new NexusSourceRegistry(this).roots().contains(tree)){finish();return;}
        try{
            documentId=DocumentsContract.getTreeDocumentId(tree);
            String specified=getIntent().getStringExtra("documentId");
            if(specified!=null&&!specified.equals(documentId)){
                FgSearchIndex verify=new FgSearchIndex(this,new FgSearchIndex.Listener(){
                    public void onResult(String s){}
                    public void onProgress(String s){}
                });
                try{
                    if(!verify.contains(tree,specified)){finish();return;}
                    documentId=specified;
                }finally{verify.close();}
            }
        }catch(Exception ex){finish();return;}
        ScrollView scroller=new ScrollView(this);scroller.setFillViewport(true);
        scroller.setBackgroundColor(BACK);
        LinearLayout root=new LinearLayout(this);root.setPadding(dp(20),dp(26),dp(20),dp(34));
        root.setOrientation(LinearLayout.VERTICAL);scroller.addView(root);setContentView(scroller);
        TextView brand=text("NEXUS / FILES",12,true);brand.setTextColor(GOLD);
        root.addView(brand);root.addView(text("Source browser",27,true),space(12));
        location=text(documentId,13,false);location.setMaxLines(2);root.addView(location,space(10));
        LinearLayout navigation=new LinearLayout(this);navigation.setOrientation(LinearLayout.HORIZONTAL);
        TextView up=button("Up",()->{
            if(!parents.isEmpty()){documentId=parents.pop();page=0;refresh();}
            else finish();
        });
        LinearLayout.LayoutParams l=new LinearLayout.LayoutParams(0,-2,1);l.rightMargin=dp(8);
        navigation.addView(up,l);
        navigation.addView(button("Refresh",this::refresh),
            new LinearLayout.LayoutParams(0,-2,1));
        root.addView(navigation,space(17));
        status=text("Reading folder",13,false);root.addView(status,space(18));
        results=new LinearLayout(this);results.setOrientation(LinearLayout.VERTICAL);
        root.addView(results,space(12));refresh();
    }
    private static final class FileEntry {
        final String documentId,name,mime;final long size;
        FileEntry(String id,String name,String mime,long size){
            documentId=id;this.name=name;this.mime=mime;this.size=size;
        }
    }
    private void refresh(){
        if(results==null)return;
        final int seq=++request;
        final String parent=documentId;
        final int requested=page;
        location.setText(parent);
        status.setText("Reading page "+(page+1));
        io.submit(()->{
            try{
                Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent);
                Bundle opts=new Bundle();
                opts.putInt(FgTeraboxDocumentsProvider.PAGE_OFFSET,requested*PAGE);
                opts.putInt(FgTeraboxDocumentsProvider.PAGE_LIMIT,PAGE);
                String[] columns={
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                };
                ArrayList<FileEntry> entries=new ArrayList<>();
                boolean loading=false;
                try(Cursor cursor=getContentResolver().query(children,columns,opts,null)){
                    if(cursor==null)throw new IllegalStateException("Document provider is unavailable");
                    loading=cursor.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING,false);
                    int id=cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                    int name=cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                    int mime=cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
                    int size=cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
                    while(cursor.moveToNext()&&entries.size()<PAGE){
                        String itemId=cursor.getString(id);
                        String itemName=cursor.getString(name);
                        String itemMime=cursor.getString(mime);
                        long length=size<0||cursor.isNull(size)?-1:cursor.getLong(size);
                        entries.add(new FileEntry(itemId,itemName,itemMime,length));
                    }
                }
                final boolean pending=loading;
                runOnUiThread(()->{
                    if(seq!=request||!parent.equals(documentId)||requested!=page)return;
                    show(entries,pending);
                    status.setText(entries.size()+" items · page "+(requested+1)+
                        (pending?" · cloud listing in progress":""));
                });
            }catch(Exception error){
                runOnUiThread(()->{
                    if(seq==request)status.setText("Folder unavailable: "+
                        error.getClass().getSimpleName()+": "+error.getMessage());
                });
            }
        });
    }
    private void show(List<FileEntry> files,boolean loading){
        results.removeAllViews();
        for(FileEntry file:files){
            boolean folder=DocumentsContract.Document.MIME_TYPE_DIR.equals(file.mime);
            LinearLayout item=new LinearLayout(this);item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(16),dp(16),dp(16),dp(16));item.setBackground(surface());
            item.setMinimumHeight(dp(70));item.setClickable(true);item.setFocusable(true);
            item.addView(text(file.name,16,true));
            item.addView(text(folder?"Folder":file.mime+
                (file.size<0?"":" · "+file.size/1024+" KiB"),12,false),space(7));
            item.setOnClickListener(v->{
                if(folder){
                    parents.push(documentId);documentId=file.documentId;page=0;refresh();
                }else {
                    try{
                        Uri uri=DocumentsContract.buildDocumentUriUsingTree(tree,file.documentId);
                        NexusFileOpen.open(this,uri,file.mime,file.name);
                    }catch(Exception e){status.setText("Open failed: "+e.getMessage());}
                }
            });
            results.addView(item,space(9));
        }
        LinearLayout navigation=new LinearLayout(this);navigation.setOrientation(LinearLayout.HORIZONTAL);
        if(page>0){
            TextView previous=button("Previous",()->{page--;refresh();});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);
            p.rightMargin=dp(8);navigation.addView(previous,p);
        }
        if(files.size()>=PAGE){
            navigation.addView(button("Next",()->{page++;refresh();}),
                new LinearLayout.LayoutParams(0,-2,1));
        }
        if(navigation.getChildCount()>0)results.addView(navigation,space(18));
        if(files.isEmpty())results.addView(text(loading?"Listing not yet available":"Folder empty",14,false),space(10));
    }
    @Override public void onBackPressed(){
        if(parents.isEmpty())super.onBackPressed();
        else{documentId=parents.pop();page=0;refresh();}
    }
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}
