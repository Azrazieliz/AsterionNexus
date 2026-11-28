package com.asterion.nexus;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import com.openlocal.office.core.BookArchive;
import com.openlocal.office.core.RichDocxPreview;
import org.jsoup.Jsoup;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Private, resumable source-text index. No content or query ever leaves the phone.
 * Source originals are immutable; EPUB/DOCX indexing never rewrites them.
 * PDF, CBZ and image OCR are NOT represented as indexed before an actual OCR model.
 */
public final class NexusBookIndex {
    static final int JOB_ID=28172;
    private static final int MAX_CHAPTERS=30000;
    private static final int MAX_TEXT_CHARS=2_000_000;
    private final Context app;
    public static final class Hit {
        public final String sha256,title,excerpt;
        public final int chapter;
        Hit(String sha,String title,String excerpt,int chapter){
            sha256=sha;this.title=title;this.excerpt=excerpt;this.chapter=chapter;
        }
    }
    private static final class DB extends SQLiteOpenHelper {
        DB(Context c){super(c,"nexus_book_index_v1.sqlite",null,1);setWriteAheadLoggingEnabled(true);}
        @Override public void onCreate(SQLiteDatabase db){
            db.execSQL("CREATE TABLE books(sha TEXT PRIMARY KEY,title TEXT NOT NULL,mime TEXT NOT NULL,chapter_count INTEGER NOT NULL DEFAULT 0,next_chapter INTEGER NOT NULL DEFAULT 0,state TEXT NOT NULL DEFAULT 'queued',error TEXT,updated INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE chapters(rowid INTEGER PRIMARY KEY AUTOINCREMENT,sha TEXT NOT NULL REFERENCES books(sha),chapter INTEGER NOT NULL,title TEXT NOT NULL,characters INTEGER NOT NULL,UNIQUE(sha,chapter))");
            db.execSQL("CREATE VIRTUAL TABLE book_text USING fts4(chapter_id,title,body,tokenize=unicode61)");
            db.execSQL("CREATE INDEX idx_books_pending ON books(state,updated)");
            db.execSQL("CREATE INDEX idx_chapter_book ON chapters(sha,chapter)");
        }
        @Override public void onUpgrade(SQLiteDatabase db,int before,int after){
            throw new IllegalStateException("No destructive book-index migration");
        }
    }
    public NexusBookIndex(Context context){app=context.getApplicationContext();}
    public static boolean supported(String mime){
        return "text/plain".equalsIgnoreCase(mime) || "application/epub+zip".equalsIgnoreCase(mime) ||
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equalsIgnoreCase(mime);
    }
    /** Job creation does not read media or block the initiating acquisition. */
    public void enqueue(NexusRegistry.Item item){
        if(item==null||!supported(item.mime))return;
        DB helper=new DB(app);
        try{
            ContentValues row=new ContentValues();
            row.put("sha",item.sha256);row.put("title",item.displayName);
            row.put("mime",item.mime);row.put("updated",System.currentTimeMillis());
            helper.getWritableDatabase().insertWithOnConflict("books",null,row,SQLiteDatabase.CONFLICT_IGNORE);
        }finally{helper.close();}
        schedule();
    }
    public void schedule(){
        JobScheduler scheduler=(JobScheduler)app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(scheduler==null)return;
        JobInfo job=new JobInfo.Builder(JOB_ID,new ComponentName(app,NexusBookIndexJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            .setPersisted(true)
            .setOverrideDeadline(5*60*1000L)
            .build();
        scheduler.schedule(job);
    }
    static final class Pending {
        final String sha,mime,title;final int next;
        Pending(String sha,String mime,String title,int next){
            this.sha=sha;this.mime=mime;this.title=title;this.next=next;
        }
    }
    Pending nextPending(){
        DB helper=new DB(app);
        try(Cursor c=helper.getReadableDatabase().rawQuery(
            "SELECT sha,mime,title,next_chapter FROM books WHERE state IN ('queued','running') ORDER BY updated ASC LIMIT 1",null)){
            return c.moveToFirst()?new Pending(c.getString(0),c.getString(1),c.getString(2),c.getInt(3)):null;
        }finally{helper.close();}
    }
    public boolean indexNext(AtomicBoolean stop){
        Pending pending=nextPending();
        if(pending==null)return false;
        NexusRegistry.Item source=new NexusRegistry(app).find(pending.sha);
        if(source==null){mark(pending.sha,"failed","Original registry item is unavailable",-1,-1);return hasQueued();}
        if(!supported(source.mime)){
            mark(pending.sha,"unsupported","No verified local-text reader",-1,-1);
            return hasQueued();
        }
        mark(pending.sha,"running","",pending.next,-1);
        try{
            if(source.mime.equals("application/epub+zip"))indexEpub(pending,stop);
            else if("text/plain".equalsIgnoreCase(source.mime))indexPlainText(pending,stop);
            else indexDocx(pending,stop);
        }catch(Exception error){
            if(stop.get()||Thread.currentThread().isInterrupted()){
                mark(pending.sha,"queued","Interrupted safely; will resume",-1,-1);
            }else{
                mark(pending.sha,"failed",error.getClass().getSimpleName(),-1,-1);
            }
        }
        return hasQueued();
    }
    private static boolean interrupted(AtomicBoolean stop){
        return stop.get()||Thread.currentThread().isInterrupted();
    }
    private void indexEpub(Pending p,AtomicBoolean stop)throws Exception {
        Uri uri=NexusLibraryProvider.uriFor(app,p.sha);
        try(BookArchive archive=new BookArchive(app,uri,true)){
            int count=archive.chapterCount();
            if(count<=0||count>MAX_CHAPTERS)throw new IOException("EPUB chapter count invalid");
            for(int i=p.next;i<count;i++){
                if(interrupted(stop)){mark(p.sha,"queued","",i,count);return;}
                JSONObject chapter=archive.chapter(i);
                String title=chapter.optString("title","Chapter "+(i+1));
                String html=chapter.optString("html","");
                String plain=Jsoup.parseBodyFragment(html).text();
                if(plain.length()>MAX_TEXT_CHARS)throw new IOException("Chapter exceeds private index limit");
                record(p.sha,i,title,plain,count);
            }
            mark(p.sha,"complete","",count,count);
        }
    }
    private void indexPlainText(Pending p,AtomicBoolean stop)throws Exception {
        NexusRegistry.Item item=new NexusRegistry(app).find(p.sha);
        if(item==null)throw new IOException("Text original unavailable");
        try(Reader reader=new InputStreamReader(new FileInputStream(new NexusRegistry(app).original(item)),
                java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT))){
            char[] chars=new char[32768];
            int i=0,n;
            while((n=reader.read(chars))!=-1){
                if(interrupted(stop)){mark(p.sha,"queued","",-1,-1);return;}
                if(i>=MAX_CHAPTERS)throw new IOException("Text exceeds chapter budget");
                if(i>=p.next)record(p.sha,i,"Text section "+(i+1),new String(chars,0,n),0);
                i++;
            }
            if(i==0)throw new IOException("Empty text");
            mark(p.sha,"complete","",i,i);
        }
    }
    private static byte[] readDocx(File f,int limit)throws IOException {
        if(f.length()<=0||f.length()>limit)
            throw new IOException("DOCX exceeds bounded offline text-index limit");
        try(FileInputStream in=new FileInputStream(f)){
            ByteArrayOutputStream out=new ByteArrayOutputStream((int)f.length());
            byte[] buf=new byte[32768];int n;
            while((n=in.read(buf))!=-1){
                if(n>limit-out.size())throw new IOException("DOCX source expanded unexpectedly");
                out.write(buf,0,n);
            }
            return out.toByteArray();
        }
    }
    private void indexDocx(Pending p,AtomicBoolean stop)throws Exception {
        NexusRegistry.Item item=new NexusRegistry(app).find(p.sha);
        if(item==null)throw new IOException("DOCX original no longer indexed");
        byte[] source=readDocx(new NexusRegistry(app).original(item),24*1024*1024);
        int blocks=RichDocxPreview.blockCount(source);
        int chunks=(blocks+35)/36;
        if(chunks<=0||chunks>MAX_CHAPTERS)throw new IOException("Document block count invalid");
        for(int i=p.next;i<chunks;i++){
            if(interrupted(stop)){mark(p.sha,"queued","",i,chunks);return;}
            JSONArray fragments=new JSONArray(RichDocxPreview.window(source,i*36,36));
            StringBuilder plain=new StringBuilder();
            for(int j=0;j<fragments.length();j++){
                JSONArray row=fragments.optJSONArray(j);
                if(row!=null)plain.append(Jsoup.parseBodyFragment(row.optString(1)).text()).append('\n');
                if(plain.length()>MAX_TEXT_CHARS)throw new IOException("Document page exceeds index limit");
            }
            record(p.sha,i,"Blocks "+(i*36+1)+"–"+Math.min(blocks,(i+1)*36),plain.toString(),chunks);
        }
        mark(p.sha,"complete","",chunks,chunks);
    }
    private void record(String sha,int chapter,String title,String body,int total){
        DB helper=new DB(app);
        SQLiteDatabase db=helper.getWritableDatabase();
        db.beginTransaction();
        try{
            ContentValues row=new ContentValues();
            row.put("sha",sha);row.put("chapter",chapter);
            row.put("title",title);row.put("characters",body.length());
            db.insertWithOnConflict("chapters",null,row,SQLiteDatabase.CONFLICT_IGNORE);
            long id;
            try(Cursor c=db.rawQuery("SELECT rowid FROM chapters WHERE sha=? AND chapter=?",
                    new String[]{sha,String.valueOf(chapter)})){
                if(!c.moveToFirst())throw new IllegalStateException("Chapter ledger insert failed");
                id=c.getLong(0);
            }
            db.execSQL("INSERT OR REPLACE INTO book_text(docid,chapter_id,title,body) VALUES(?,?,?,?)",
                new Object[]{id,id,title,body});
            db.execSQL("UPDATE books SET next_chapter=?,chapter_count=?,state='running',error=NULL,updated=? WHERE sha=?",
                new Object[]{chapter+1,total,System.currentTimeMillis(),sha});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();helper.close();}
    }
    private void mark(String sha,String state,String error,int chapter,int total){
        DB helper=new DB(app);
        try{
            SQLiteDatabase db=helper.getWritableDatabase();
            if(chapter>=0)db.execSQL("UPDATE books SET state=?,error=?,next_chapter=?,updated=? WHERE sha=?",
                new Object[]{state,error,chapter,System.currentTimeMillis(),sha});
            else db.execSQL("UPDATE books SET state=?,error=?,updated=? WHERE sha=?",
                new Object[]{state,error,System.currentTimeMillis(),sha});
            if(total>=0)db.execSQL("UPDATE books SET chapter_count=? WHERE sha=?",
                new Object[]{total,sha});
        }finally{helper.close();}
    }
    public boolean hasQueued(){
        DB helper=new DB(app);
        try(Cursor c=helper.getReadableDatabase().rawQuery(
            "SELECT 1 FROM books WHERE state IN ('queued','running') LIMIT 1",null)){
            return c.moveToFirst();
        }finally{helper.close();}
    }
    public List<Hit> search(String phrase,int limit){
        if(limit<1||limit>100)throw new IllegalArgumentException("Invalid search limit");
        String sanitized=phrase==null?"":phrase.trim();
        if(sanitized.isEmpty()||sanitized.length()>160)return Collections.emptyList();
        String escaped="\""+sanitized.replace("\"","\"\"")+"\"";
        ArrayList<Hit> found=new ArrayList<>();
        DB helper=new DB(app);
        try(Cursor c=helper.getReadableDatabase().rawQuery(
            "SELECT c.sha,b.title,c.chapter,substr(book_text.body,1,240) FROM book_text JOIN chapters c ON book_text.docid=c.rowid JOIN books b ON c.sha=b.sha WHERE book_text MATCH ? ORDER BY b.updated DESC,c.chapter LIMIT ?",
            new String[]{escaped,Integer.toString(limit)})){
            while(c.moveToNext())found.add(new Hit(c.getString(0),c.getString(1),c.getString(3),c.getInt(2)));
        }finally{helper.close();}
        return found;
    }
}
