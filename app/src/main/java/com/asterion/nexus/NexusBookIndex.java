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
        DB(Context c){super(c,"nexus_book_index_v1.sqlite",null,3);setWriteAheadLoggingEnabled(true);}
        @Override public void onCreate(SQLiteDatabase db){
            db.execSQL("CREATE TABLE books(sha TEXT PRIMARY KEY,title TEXT NOT NULL,mime TEXT NOT NULL,chapter_count INTEGER NOT NULL DEFAULT 0,next_chapter INTEGER NOT NULL DEFAULT 0,state TEXT NOT NULL DEFAULT 'queued',error TEXT,updated INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE chapters(rowid INTEGER PRIMARY KEY AUTOINCREMENT,sha TEXT NOT NULL REFERENCES books(sha),chapter INTEGER NOT NULL,title TEXT NOT NULL,characters INTEGER NOT NULL,UNIQUE(sha,chapter))");
            db.execSQL("CREATE VIRTUAL TABLE book_text USING fts4(chapter_id,title,body,tokenize=unicode61)");
            db.execSQL("CREATE INDEX idx_books_pending ON books(state,updated)");
            db.execSQL("CREATE INDEX idx_chapter_book ON chapters(sha,chapter)");
            addV2(db);
            addV3(db);
        }
        private void addV2(SQLiteDatabase db){
            db.execSQL("ALTER TABLE chapters ADD COLUMN source_method TEXT NOT NULL DEFAULT 'legacy_text'");
            db.execSQL("ALTER TABLE chapters ADD COLUMN model_id TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE chapters ADD COLUMN model_version TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE chapters ADD COLUMN coverage TEXT NOT NULL DEFAULT 'legacy_unverified'");
            db.execSQL("ALTER TABLE chapters ADD COLUMN cjk_ready INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE chapters ADD COLUMN cjk_full INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE VIRTUAL TABLE book_cjk USING fts4(terms,tokenize=unicode61)");
            db.execSQL("CREATE INDEX idx_chapters_cjk ON chapters(cjk_ready,rowid)");
        }
        private void addV3(SQLiteDatabase db){
            db.execSQL("ALTER TABLE books ADD COLUMN translation_checked INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE INDEX idx_translation_seed ON books(translation_checked,state)");
        }
        @Override public void onUpgrade(SQLiteDatabase db,int before,int after){
            if(before==1&&after>=2){
                addV2(db);
                // Old partial/unsupported PDF rows must be revisited for OCR.
                // Keep all searchable text and original identities.
                db.execSQL("UPDATE books SET state='queued',next_chapter=0 WHERE mime='application/pdf' AND state IN ('partial','unsupported')");
                before=2;
            }
            if(before==2&&after==3){addV3(db);return;}
            if(before==after)return;
            throw new IllegalStateException("No destructive book-index migration");
        }
    }
    public NexusBookIndex(Context context){app=context.getApplicationContext();}
    public static boolean supported(String mime){
        return "application/pdf".equalsIgnoreCase(mime) || "text/plain".equalsIgnoreCase(mime) || "application/epub+zip".equalsIgnoreCase(mime) ||
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equalsIgnoreCase(mime)||comic(mime)||NexusRarBook.supported(mime);
    }
    private static boolean comic(String mime){
        return "application/vnd.comicbook+zip".equalsIgnoreCase(mime)||"application/x-cbz".equalsIgnoreCase(mime);
    }
    private boolean seedExisting(){
        android.content.SharedPreferences prefs=app.getSharedPreferences("nexus_book_seed_v3",0);
        NexusRegistry.BookSeedPage page=new NexusRegistry(app).bookCandidatesAfter(prefs.getLong("cursor",0),100);
        for(NexusRegistry.Item item:page.items)enqueueRecord(item);
        if(!prefs.edit().putLong("cursor",page.cursor).commit())throw new IllegalStateException("Book seed cursor not saved");
        return page.more;
    }
    /** Job creation does not read media or block the initiating acquisition. */
    public void enqueue(NexusRegistry.Item item){
        if(item==null||!supported(item.mime))return;
        enqueueRecord(item);schedule();
    }
    private void enqueueRecord(NexusRegistry.Item item){
        if(item==null||!supported(item.mime))return;
        DB helper=new DB(app);
        try{
            ContentValues row=new ContentValues();
            row.put("sha",item.sha256);row.put("title",item.displayName);
            row.put("mime",item.mime);row.put("updated",System.currentTimeMillis());
            helper.getWritableDatabase().insertWithOnConflict("books",null,row,SQLiteDatabase.CONFLICT_IGNORE);
        }finally{helper.close();}
    }
    public void schedule(){schedule(false);}
    void schedule(boolean continuation){
        JobScheduler scheduler=(JobScheduler)app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(scheduler==null||(!continuation&&scheduler.getPendingJob(JOB_ID)!=null))return;
        JobInfo job=new JobInfo.Builder(JOB_ID,new ComponentName(app,NexusBookIndexJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            .setPersisted(true)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .setMinimumLatency(1000L)
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
        if(interrupted(stop))return true;
        boolean seedMore=seedExisting();
        boolean cjkMore=backfillCjk();
        boolean translationMore=seedTranslations();
        Pending pending=nextPending();
        if(pending==null)return seedMore||cjkMore||translationMore;
        NexusRegistry.Item source=new NexusRegistry(app).find(pending.sha);
        if(source==null){mark(pending.sha,"failed","Original registry item is unavailable",-1,-1);return seedMore||cjkMore||translationMore||hasQueued();}
        if(!supported(source.mime)){
            mark(pending.sha,"unsupported","No verified local-text reader",-1,-1);
            return seedMore||cjkMore||translationMore||hasQueued();
        }
        mark(pending.sha,"running","",pending.next,-1);
        try{
            // Ingestion already verified the full source; do not rehash a
            // multi-GiB book on every two-page OCR continuation. Check the
            // immutable registered file exists, and hash at start/end.
            NexusRegistry registry=new NexusRegistry(app);
            registry.original(source);
            if(pending.next==0&&!registry.verifyOriginal(source))throw new IOException("Recorded source bytes failed verification");
            if(source.mime.equals("application/epub+zip"))indexEpub(pending,stop);
            else if("text/plain".equalsIgnoreCase(source.mime))indexPlainText(pending,stop);
            else if("application/pdf".equalsIgnoreCase(source.mime))indexPdf(pending,stop);
            else if(NexusRarBook.supported(source.mime))indexRar(pending,stop);
            else if(comic(source.mime))indexComic(pending,stop);
            else indexDocx(pending,stop);
        }catch(Exception error){
            if(stop.get()||Thread.currentThread().isInterrupted()){
                mark(pending.sha,"queued","Interrupted safely; will resume",-1,-1);
            }else if(error instanceof NexusBookOcr.EngineUnavailable){
                mark(pending.sha,"blocked_ocr","Compatible native offline OCR model unavailable",-1,-1);
            }else{
                mark(pending.sha,"failed",error.getClass().getSimpleName(),-1,-1);
            }
        }
        return seedMore||cjkMore||translationMore||seedTranslations()||hasQueued();
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
            finishCoverage(p.sha,count);
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
            while(true){
                n=0;
                while(n<chars.length){
                    if(interrupted(stop)){mark(p.sha,"queued","",-1,-1);return;}
                    int read=reader.read(chars,n,chars.length-n);
                    if(read<0)break;
                    n+=read;
                }
                if(n==0)break;
                if(interrupted(stop)){mark(p.sha,"queued","",-1,-1);return;}
                if(i>=MAX_CHAPTERS)throw new IOException("Text exceeds chapter budget");
                if(i>=p.next)record(p.sha,i,"Text section "+(i+1),new String(chars,0,n),0);
                i++;
            }
            if(i==0)throw new IOException("Empty text");
            finishCoverage(p.sha,i);
        }
    }
    private void indexPdf(Pending p,AtomicBoolean stop)throws Exception {
        Uri uri=NexusLibraryProvider.uriFor(app,p.sha);
        android.os.ParcelFileDescriptor fd=app.getContentResolver().openFileDescriptor(uri,"r");
        if(fd==null)throw new IOException("PDF original unavailable");
        android.graphics.pdf.PdfRenderer renderer;
        try{renderer=new android.graphics.pdf.PdfRenderer(fd);}
        catch(Exception e){fd.close();throw e;}
        try(android.graphics.pdf.PdfRenderer pdf=renderer){
            int count=pdf.getPageCount();
            if(count<=0||count>MAX_CHAPTERS)throw new IOException("PDF page count invalid");
            int ocrPages=0;
            for(int i=p.next;i<count;i++){
                if(interrupted(stop)){mark(p.sha,"queued","",i,count);return;}
                StringBuilder body=new StringBuilder();
                try(android.graphics.pdf.PdfRenderer.Page page=pdf.openPage(i)){
                    if(android.os.Build.VERSION.SDK_INT>=35){
                        for(android.graphics.pdf.content.PdfPageTextContent part:page.getTextContents()){
                            body.append(part.getText()).append('\n');
                            if(body.length()>MAX_TEXT_CHARS)throw new IOException("PDF page text budget exceeded");
                        }
                    }
                    String text=body.toString().trim();
                    if(!text.isEmpty())record(p.sha,i,"Page "+(i+1),text,count);
                    else{
                        byte[] image=renderPdfPage(page);
                        try(InputStream input=new ByteArrayInputStream(image)){
                            recordOcr(p.sha,i,"Page "+(i+1),count,NexusBookOcr.recognize(app,input));
                        }
                        ocrPages++;
                    }
                }
                // Yield after heavy OCR so reader/playback and other jobs can run.
                if(ocrPages>=2&&i+1<count){mark(p.sha,"queued","",i+1,count);return;}
            }
            finishCoverage(p.sha,count);
        }
    }
    private static byte[] renderPdfPage(android.graphics.pdf.PdfRenderer.Page page)throws IOException{
        double scale=Math.min(2000.0/Math.max(1,page.getWidth()),2400.0/Math.max(1,page.getHeight()));
        int width=Math.max(1,(int)Math.round(page.getWidth()*scale));
        int height=Math.max(1,(int)Math.round(page.getHeight()*scale));
        android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);
        try{
            image.eraseColor(android.graphics.Color.WHITE);
            page.render(image,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            if(!image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,bytes))throw new IOException("PDF OCR render failed");
            return bytes.toByteArray();
        }finally{image.recycle();}
    }
    private void indexComic(Pending p,AtomicBoolean stop)throws Exception{
        try(BookArchive archive=new BookArchive(app,NexusLibraryProvider.uriFor(app,p.sha),false)){
            int count=archive.imageCount();
            if(count<=0||count>MAX_CHAPTERS)throw new IOException("Comic has no supported pages");
            int processed=0;
            for(int i=p.next;i<count;i++){
                if(interrupted(stop)){mark(p.sha,"queued","",i,count);return;}
                String path=Uri.parse(archive.archiveEntry(i)).getEncodedPath();
                android.webkit.WebResourceResponse response=archive.response(path);
                if(response==null||response.getData()==null)throw new IOException("Comic page unavailable");
                try(InputStream page=response.getData()){
                    recordOcr(p.sha,i,"Page "+(i+1),count,NexusBookOcr.recognize(app,page));
                }
                if(++processed>=2&&i+1<count){mark(p.sha,"queued","",i+1,count);return;}
            }
            finishCoverage(p.sha,count);
        }
    }
    private void indexRar(Pending p,AtomicBoolean stop)throws Exception{
        NexusRegistry registry=new NexusRegistry(app);
        NexusRegistry.Item source=registry.find(p.sha);
        if(source==null)throw new IOException("CBR original unavailable");
        try(NexusRarBook book=new NexusRarBook(registry.original(source))){
            int count=book.count();
            if(count<=0||count>MAX_CHAPTERS)throw new IOException("Comic page count unsupported");
            int processed=0;
            for(int i=p.next;i<count;i++){
                if(interrupted(stop)){mark(p.sha,"queued","",i,count);return;}
                try(InputStream page=new ByteArrayInputStream(book.page(i))){
                    recordOcr(p.sha,i,"Page "+(i+1),count,NexusBookOcr.recognize(app,page));
                }
                if(++processed>=2&&i+1<count){mark(p.sha,"queued","",i+1,count);return;}
            }
            finishCoverage(p.sha,count);
        }
    }
    private void recordOcr(String sha,int page,String title,int count,NexusBookOcr.Result result){
        String coverage=result.getText().isBlank()?"ocr_no_text":
            result.getLinesRecognized()<result.getBoxesDetected()?"ocr_partial_regions":"ocr_unvalidated";
        if(result.getText().length()>MAX_TEXT_CHARS)throw new IllegalArgumentException("OCR text budget exceeded");
        record(sha,page,title,result.getText(),count,"native_ocr",result.getModelId(),result.getModelVersion(),coverage);
    }
    private void finishCoverage(String sha,int count)throws IOException{
        NexusRegistry registry=new NexusRegistry(app);
        if(!registry.verifyOriginal(registry.find(sha)))throw new IOException("Source failed final integrity verification");
        DB helper=new DB(app);
        int uncertain;
        try(Cursor c=helper.getReadableDatabase().rawQuery(
            "SELECT count(*),sum(CASE WHEN coverage='native_text' AND characters>0 THEN 1 ELSE 0 END) FROM chapters WHERE sha=?",new String[]{sha})){
            uncertain=c.moveToFirst()?Math.max(0,count-c.getInt(1)):count;
        }finally{helper.close();}
        mark(sha,uncertain==0?"complete":"partial",uncertain==0?"":uncertain+" pages/sections have unvalidated OCR or incomplete text coverage",count,count);
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
        finishCoverage(p.sha,chunks);
    }
    private void record(String sha,int chapter,String title,String body,int total){
        record(sha,chapter,title,body,total,"native_text","","",body.isBlank()?"empty":"native_text");
    }
    private void record(String sha,int chapter,String title,String body,int total,String method,String model,String version,String coverage){
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
            db.execSQL("UPDATE chapters SET title=?,characters=?,source_method=?,model_id=?,model_version=?,coverage=? WHERE rowid=?",
                new Object[]{title,body.length(),method,model,version,coverage,id});
            writeCjk(db,id,title+"\n"+body);
            db.execSQL("UPDATE books SET next_chapter=?,chapter_count=?,state='running',error=NULL,updated=? WHERE sha=?",
                new Object[]{chapter+1,total,System.currentTimeMillis(),sha});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();helper.close();}
    }
    private static void writeCjk(SQLiteDatabase db,long id,String text){
        NexusCjkTerms.Terms terms=NexusCjkTerms.index(text);
        db.execSQL("INSERT OR REPLACE INTO book_cjk(docid,terms) VALUES(?,?)",new Object[]{id,terms.text});
        db.execSQL("UPDATE chapters SET cjk_ready=1,cjk_full=? WHERE rowid=?",new Object[]{terms.complete?1:0,id});
    }
    private boolean seedTranslations(){
        DB helper=new DB(app);
        java.util.ArrayList<String[]> rows=new java.util.ArrayList<>();
        try{
            SQLiteDatabase db=helper.getWritableDatabase();
            try(Cursor c=db.rawQuery("SELECT sha,mime FROM books WHERE translation_checked=0 AND state IN ('complete','partial') AND NOT EXISTS(SELECT 1 FROM chapters WHERE chapters.sha=books.sha AND cjk_ready=0) LIMIT 8",null)){
                while(c.moveToNext())rows.add(new String[]{c.getString(0),c.getString(1)});
            }
            NexusTranslationQueue queue=new NexusTranslationQueue(app);
            NexusRegistry registry=new NexusRegistry(app);
            for(String[] row:rows){
                NexusRegistry.Item item=registry.find(row[0]);
                if(item!=null&&!"nexus-translation".equals(item.sourceKind)){
                    StringBuilder sample=new StringBuilder();
                    Set<Integer> unique=new HashSet<>();
                    try(Cursor c=db.rawQuery("SELECT book_cjk.terms FROM chapters JOIN book_cjk ON book_cjk.docid=chapters.rowid WHERE chapters.sha=?",new String[]{row[0]})){
                        while(c.moveToNext()){
                            StringTokenizer tokens=new StringTokenizer(c.getString(0));
                            while(tokens.hasMoreTokens()){
                                String token=tokens.nextToken();
                                if(token.startsWith("c")){
                                    int cp=Integer.parseInt(token.substring(1),16);
                                    if(Character.isValidCodePoint(cp)&&unique.add(cp))sample.appendCodePoint(cp);
                                }
                            }
                        }
                    }
                    queue.enqueue(row[0],row[1],sample.toString());
                }
                db.execSQL("UPDATE books SET translation_checked=1 WHERE sha=?",new Object[]{row[0]});
            }
        }finally{helper.close();}
        return rows.size()==8;
    }
    private boolean backfillCjk(){
        DB helper=new DB(app);
        SQLiteDatabase db=helper.getWritableDatabase();
        int n=0;
        db.beginTransaction();
        try(Cursor c=db.rawQuery("SELECT c.rowid,c.title,book_text.body FROM chapters c JOIN book_text ON book_text.docid=c.rowid WHERE c.cjk_ready=0 ORDER BY c.rowid LIMIT 8",null)){
            while(c.moveToNext()){writeCjk(db,c.getLong(0),c.getString(1)+"\n"+c.getString(2));n++;}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();helper.close();}
        return n==8;
    }
    public void retryBlocked(){
        DB helper=new DB(app);
        try{helper.getWritableDatabase().execSQL("UPDATE books SET state='queued',error=NULL WHERE state='blocked_ocr'");}
        finally{helper.close();}
        schedule();
    }
    public void retry(String sha){
        if(sha==null||!sha.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid book identity");
        DB helper=new DB(app);
        try{helper.getWritableDatabase().execSQL("UPDATE books SET state='queued',error=NULL,translation_checked=0,next_chapter=CASE WHEN state='partial' THEN 0 ELSE next_chapter END WHERE sha=? AND state IN ('blocked_ocr','failed','partial','unsupported')",new Object[]{sha});}
        finally{helper.close();}
        schedule();
    }
    public String status(String sha){
        DB helper=new DB(app);
        try(Cursor c=helper.getReadableDatabase().rawQuery("SELECT state,next_chapter,chapter_count,error FROM books WHERE sha=?",new String[]{sha})){
            if(!c.moveToFirst())return "Not queued";
            String error=c.getString(3);
            return c.getString(0)+" · "+c.getInt(1)+" / "+c.getInt(2)+(error==null||error.isEmpty()?"":" · "+error);
        }finally{helper.close();}
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
        boolean cjk=NexusCjkTerms.contains(sanitized);
        String sql=cjk?
            "SELECT c.sha,b.title,c.chapter,substr(book_text.body,max(1,instr(book_text.body,?)-60),240) FROM book_text JOIN chapters c ON book_text.docid=c.rowid JOIN books b ON c.sha=b.sha WHERE (c.rowid IN (SELECT docid FROM book_cjk WHERE book_cjk MATCH ?) OR c.cjk_ready=0 OR c.cjk_full=0) AND (instr(book_text.body,?)>0 OR instr(c.title,?)>0) ORDER BY b.updated DESC,c.chapter LIMIT ?":
            "SELECT c.sha,b.title,c.chapter,snippet(book_text,'','','…',2,36) FROM book_text JOIN chapters c ON book_text.docid=c.rowid JOIN books b ON c.sha=b.sha WHERE book_text MATCH ? ORDER BY b.updated DESC,c.chapter LIMIT ?";
        String[] args=cjk?new String[]{sanitized,NexusCjkTerms.query(sanitized),sanitized,sanitized,Integer.toString(limit)}:
            new String[]{escaped,Integer.toString(limit)};
        try(Cursor c=helper.getReadableDatabase().rawQuery(sql,args)){
            while(c.moveToNext())found.add(new Hit(c.getString(0),c.getString(1),c.getString(3),c.getInt(2)));
        }finally{helper.close();}
        return found;
    }
}
