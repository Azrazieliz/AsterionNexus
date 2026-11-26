package com.asterion.nexus;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.*;
import android.widget.*;
import com.openlocal.office.core.BookArchive;
import com.openlocal.office.core.UniversalReader;
import com.openlocal.office.core.RichDocxPreview;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.concurrent.*;
import java.security.*;

/** Actual Rounder seekable book/comic reader and native PDF page rendering.
 * Content remains in its source; only chapter/page bytes are requested.
 */
public final class NexusReaderActivity extends Activity {
    private static final int PANEL=0xff171a22,ACCENT=0xffcdad76,FG=0xfff0f0f2;
    private static final String ASSET_HOST="fg-assets.invalid";
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private WebView web;
    private ImageView image;
    private LinearLayout view;
    private TextView heading,position,prev,next;
    private volatile BookArchive book;
    private volatile int count=0,current=0,mode=0;
    private Uri source;
    private String sourceKey;
    private byte[] document;
    private int dp(int size){return (int)(getResources().getDisplayMetrics().density*size+.5f);}
    private static String safeHtml(String s){
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }
    private TextView text(String value,int sp){
        TextView t=new TextView(this);t.setText(value);t.setTextColor(FG);t.setTextSize(sp);
        return t;
    }
    private GradientDrawable buttonBg(){
        GradientDrawable d=new GradientDrawable();d.setColor(PANEL);d.setCornerRadius(dp(12));
        d.setStroke(dp(1),0xff414550);return d;
    }
    private TextView button(String title,Runnable run){
        TextView t=text(title,15);t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(52));t.setPadding(dp(13),dp(10),dp(13),dp(10));
        t.setBackground(buttonBg());t.setOnClickListener(v->run.run());t.setFocusable(true);
        return t;
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        source=getIntent().getData();
        if(source==null){finish();return;}
        sourceKey=NexusOriginalStore.sourceKey(source.toString());
        String type=getIntent().getType();
        String path=source.toString().toLowerCase(java.util.Locale.ROOT);
        if("application/pdf".equals(type)||path.endsWith(".pdf"))mode=1;
        else if("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(type)
                ||path.endsWith(".docx"))mode=3;
        else if(path.endsWith(".cbz")||path.endsWith(".zip")||
            "application/zip".equals(type)||"application/vnd.comicbook+zip".equals(type))mode=2;
        else mode=0;
        getWindow().setStatusBarColor(0xff0c0e13);
        getWindow().setNavigationBarColor(0xff0c0e13);
        view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);
        view.setBackgroundColor(0xff0c0e13);
        view.setPadding(dp(12),dp(8),dp(12),dp(8));
        setContentView(view);
        heading=text(getIntent().getStringExtra("title")==null?
            "Reader":getIntent().getStringExtra("title"),16);
        heading.setSingleLine(true);heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.addView(heading,new LinearLayout.LayoutParams(-1,dp(45)));
        position=text("Opening",12);position.setTextColor(ACCENT);
        view.addView(position,new LinearLayout.LayoutParams(-1,dp(28)));
        web=new WebView(this);
        web.setBackgroundColor(Color.rgb(244,242,237));
        web.getSettings().setJavaScriptEnabled(false);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setBlockNetworkLoads(true);
        web.getSettings().setDomStorageEnabled(false);
        web.getSettings().setSupportZoom(true);
        web.getSettings().setBuiltInZoomControls(true);
        web.getSettings().setDisplayZoomControls(false);
        web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest request){
                Uri uri=request.getUrl();
                if(!"https".equals(uri.getScheme())||!ASSET_HOST.equals(uri.getHost()))
                    return denied();
                try{
                    BookArchive currentBook=book;
                    if(currentBook==null)return denied();
                    WebResourceResponse asset=currentBook.response(uri.getEncodedPath());
                    return asset==null?denied():asset;
                }catch(Exception e){return denied();}
            }
            @Override public boolean shouldOverrideUrlLoading(WebView w,WebResourceRequest request){
                return true; // Internal page links never navigate to the public network.
            }
            private WebResourceResponse denied(){
                return new WebResourceResponse("text/plain","utf-8",
                    new ByteArrayInputStream(new byte[0]));
            }
        });
        image=new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setBackgroundColor(0xff11141a);
        view.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        view.addView(image,new LinearLayout.LayoutParams(-1,0,1));
        image.setVisibility(View.GONE);
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        prev=button("Previous",()->display(current-1));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.rightMargin=dp(8);
        row.addView(prev,lp);
        next=button("Next",()->display(current+1));
        row.addView(next,new LinearLayout.LayoutParams(0,-2,1));
        view.addView(row,new LinearLayout.LayoutParams(-1,-2));
        work.submit(()->initialize());
    }
    private void initialize(){
        try{
            if(mode==1){
                count=UniversalReader.pdfPages(this,source);
            } else if(mode==3){
                document=readBounded(source,24*1024*1024);
                count=(RichDocxPreview.blockCount(document)+35)/36;
            } else {
                book=new BookArchive(this,source,mode==0);
                if(mode==2)count=book.imageCount();
                else count=book.chapterCount();
            }
            if(count<=0)throw new IOException("No readable pages in this source");
            int saved=getPreferences(Context.MODE_PRIVATE).getInt(sourceKey,0);
            int requested=getIntent().getIntExtra("chapter",-1);
            final int initial=Math.max(0,Math.min(count-1,requested>=0?requested:saved));
            runOnUiThread(()->display(initial));
        }catch(Exception e){
            runOnUiThread(()->position.setText("Reader failed: "+e.getClass().getSimpleName()+
                (e.getMessage()==null?"":": "+e.getMessage())));
        }
    }
    private byte[] readBounded(Uri uri,int limit)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(InputStream input=getContentResolver().openInputStream(uri)){
            if(input==null)throw new IOException("Cannot open source");
            byte[] buf=new byte[32768];int n;
            while((n=input.read(buf))!=-1){
                if(n>limit-out.size())throw new IOException("DOCX exceeds 24 MiB safety limit");
                out.write(buf,0,n);
            }
        }
        return out.toByteArray();
    }
    private void display(int requested){
        if(count==0||requested<0||requested>=count)return;
        current=requested;
        position.setText((requested+1)+" / "+count);
        prev.setEnabled(requested>0);next.setEnabled(requested+1<count);
        prev.setAlpha(requested>0?1f:.3f);next.setAlpha(requested+1<count?1f:.3f);
        getPreferences(Context.MODE_PRIVATE).edit().putInt(sourceKey,requested).apply();
        final int index=requested;
        work.submit(()->{
            try{
                if(mode==1){
                    String encoded=UniversalReader.pdfPage(this,source,index,
                        Math.max(720,Math.min(1900,getResources().getDisplayMetrics().widthPixels)));
                    byte[] pixels=Base64.decode(encoded,Base64.DEFAULT);
                    Bitmap bmp=BitmapFactory.decodeByteArray(pixels,0,pixels.length);
                    if(bmp==null)throw new IOException("PDF page decode failed");
                    runOnUiThread(()->{
                        if(current!=index){bmp.recycle();return;}
                        web.setVisibility(View.GONE);image.setVisibility(View.VISIBLE);
                        image.setImageBitmap(bmp);
                    });
                } else if(mode==3){
                    JSONArray chunks=new JSONArray(RichDocxPreview.window(document,index*36,36));
                    StringBuilder html=new StringBuilder("<html><meta name='viewport' content='width=device-width, initial-scale=1'><style>body{background:#f4f2ed;color:#151721;font:17px/1.6 sans-serif;padding:18px}img{max-width:100%}table{max-width:100%;overflow:auto}</style>");
                    for(int i=0;i<chunks.length();i++){
                        JSONArray block=chunks.optJSONArray(i);
                        if(block!=null)html.append(block.optString(1));
                    }
                    html.append("</html>");
                    showHtml(html.toString(),"https://fg-assets.invalid/",index);
                } else if(mode==2){
                    String url=book.archiveEntry(index);
                    String html="<html><meta name='viewport' content='width=device-width, initial-scale=1'>"+
                        "<style>html,body{margin:0;background:#11141a}img{width:100%;height:auto;display:block}</style>"+
                        "<img src='"+safeHtml(url)+"' alt='Comic page'></html>";
                    showHtml(html,url,index);
                } else {
                    JSONObject chapter=book.chapter(index);
                    String html=chapter.optString("html","");
                    String base=chapter.optString("base","https://fg-assets.invalid/");
                    showHtml(html,base,index);
                }
            }catch(Exception e){
                runOnUiThread(()->{
                    if(current==index)position.setText("Page unavailable: "+e.getMessage());
                });
            }
        });
    }
    private void showHtml(String html,String base,int index){
        runOnUiThread(()->{
            if(current!=index)return;
            image.setVisibility(View.GONE);web.setVisibility(View.VISIBLE);
            web.loadDataWithBaseURL(base,html,"text/html","UTF-8",null);
        });
    }
    @Override protected void onDestroy(){
        work.shutdownNow();
        if(web!=null){web.loadUrl("about:blank");web.destroy();}
        if(book!=null)try{book.close();}catch(Exception ignored){}
        super.onDestroy();
    }
}
