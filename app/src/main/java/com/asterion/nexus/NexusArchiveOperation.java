package com.asterion.nexus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import android.widget.*;
import com.openlocal.office.core.FgTeraboxClient;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Contextual archive action. Uses the existing verified-content registry and
 * job ledger; all paths in the receipt are metadata, never destination paths. */
public final class NexusArchiveOperation {
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor();
    private NexusArchiveOperation(){}
    public static boolean available(Context context,Uri uri){
        return uri!=null&&(context.getPackageName()+".library").equals(uri.getAuthority())
            &&uri.getPathSegments().size()==2&&"original".equals(uri.getPathSegments().get(0));
    }
    private static NexusRegistry.Item source(Context app,Uri uri)throws IOException{
        if(!available(app,uri))throw new IOException("Import a verified offline archive first");
        NexusRegistry.Item item=new NexusRegistry(app).find(uri.getLastPathSegment());
        if(item==null)throw new IOException("Verified archive unavailable");
        return item;
    }
    private static File checked(NexusRegistry registry,NexusRegistry.Item item)throws IOException{
        File file=registry.original(item);
        if(!NexusOriginalStore.matches(file,item.length,item.sha256))
            throw new IOException("Archive source failed SHA-256 verification");
        return file;
    }
    private static void write(AtomicFile file,JSONObject value)throws IOException{
        FileOutputStream out=null;
        try{
            out=file.startWrite();
            out.write(value.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        }catch(IOException|RuntimeException e){
            if(out!=null)file.failWrite(out);
            throw e;
        }
    }
    private static void notify(Activity activity,String title,String message){
        activity.runOnUiThread(()->{
            if(!activity.isFinishing()&&!activity.isDestroyed())
                new AlertDialog.Builder(activity).setTitle(title).setMessage(message)
                    .setPositiveButton("Close",null).show();
        });
    }
    public static void show(Activity activity,Uri uri){
        Context app=activity.getApplicationContext();
        if(!available(app,uri)){notify(activity,"Extract archive","Import a verified offline archive first.");return;}
        Toast.makeText(activity,"Inspecting archive",Toast.LENGTH_SHORT).show();
        WORK.execute(()->{
            try{
                NexusRegistry registry=new NexusRegistry(app);
                NexusRegistry.Item original=source(app,uri);
                NexusZipExtraction.Plan plan=NexusZipExtraction.inspect(checked(registry,original));
                StringBuilder summary=new StringBuilder()
                    .append(plan.files.size()).append(" files · ")
                    .append(android.text.format.Formatter.formatFileSize(app,plan.bytes)).append(" expanded\n\n");
                for(int i=0;i<Math.min(plan.files.size(),30);i++)
                    summary.append(plan.files.get(i).path).append('\n');
                if(plan.files.size()>30)summary.append("… ").append(plan.files.size()-30).append(" more\n");
                summary.append("\nDestination: verified local Library assets. Original archive retained. Nested archives remain unopened.");
                activity.runOnUiThread(()->{
                    if(activity.isFinishing()||activity.isDestroyed())return;
                    new AlertDialog.Builder(activity).setTitle("Extract verified files")
                        .setMessage(summary.toString()).setNegativeButton("Cancel",null)
                        .setPositiveButton("Extract",(dialog,which)->begin(activity,original)).show();
                });
            }catch(Exception e){notify(activity,"Archive unavailable",String.valueOf(e.getMessage()));}
        });
    }
    private static void begin(Activity activity,NexusRegistry.Item original){
        Context app=activity.getApplicationContext();
        AtomicBoolean cancelled=new AtomicBoolean();
        AlertDialog progress=new AlertDialog.Builder(activity).setTitle("Extracting files")
            .setMessage("Verifying all member bytes before registration")
            .setNegativeButton("Cancel",(d,w)->cancelled.set(true)).create();
        progress.setOnCancelListener(d->cancelled.set(true));
        progress.show();
        WORK.execute(()->{
            String job="nexus-zip-extraction-v1:"+original.sha256;
            NexusRegistry registry=new NexusRegistry(app);
            File stage=null;int registered=0;long bytes=0;
            AtomicFile receipt=null;JSONObject journal=new JSONObject();
            try{
                registry.job(job,"QUEUED",0,null);
                File archive=checked(registry,original);
                NexusZipExtraction.Plan plan=NexusZipExtraction.inspect(archive);
                if(cancelled.get())throw new InterruptedIOException("Extraction cancelled");
                File root=new File(app.getFilesDir(),"nexus-extractions");
                if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Receipt directory unavailable");
                receipt=new AtomicFile(new File(root,original.sha256+".json"));
                JSONArray entries=new JSONArray();
                journal.put("version",1).put("archive_sha256",original.sha256)
                    .put("archive_name",original.displayName).put("archive_bytes",original.length)
                    .put("state","VERIFYING").put("member_count",plan.files.size()).put("members",entries);
                write(receipt,journal);
                stage=Files.createTempDirectory(app.getCacheDir().toPath(),"nexus-extract-").toFile();
                registry.job(job,"RECEIVING",0,null);
                List<NexusZipExtraction.Verified> files=NexusZipExtraction.stage(archive,stage,plan,cancelled::get);
                checked(registry,original);
                registry.job(job,"BYTES_COMPLETE",plan.bytes,null);
                registry.job(job,"VERIFIED",plan.bytes,null);
                for(NexusZipExtraction.Verified file:files){
                    entries.put(new JSONObject().put("path",file.entry.path)
                        .put("sha256",file.sha256).put("bytes",file.entry.length));
                }
                journal.put("state","REGISTERING");write(receipt,journal);
                for(NexusZipExtraction.Verified file:files){
                    if(cancelled.get())throw new InterruptedIOException("Extraction cancelled");
                    String name=file.entry.path.substring(file.entry.path.lastIndexOf('/')+1);
                    String type=FgTeraboxClient.mime(name);
                    NexusRegistry.Item result;
                    try(InputStream input=new FileInputStream(file.file)){
                        result=registry.ingest(input,name,type,"nexus-extraction","device",
                            job+":"+file.entry.path,file.entry.length,file.sha256);
                    }
                    if(!NexusOriginalStore.matches(registry.original(result),result.length,result.sha256))
                        throw new IOException("Registered member readback failed");
                    registered++;bytes+=result.length;
                    entries.getJSONObject(registered-1).put("registered",true);
                    // On interruption the existing canonical origin identity makes retry idempotent.
                    journal.put("registered",registered);write(receipt,journal);
                    final int completed=registered;
                    activity.runOnUiThread(()->{
                        if(progress.isShowing())progress.setMessage(completed+" / "+files.size()+" files verified and registered");
                    });
                    file.file.delete();
                }
                journal.put("state","INDEXED");write(receipt,journal);
                registry.job(job,"INDEXED",bytes,null);
                final int count=registered;
                activity.runOnUiThread(()->{if(progress.isShowing())progress.dismiss();});
                notify(activity,"Extraction complete",count+" verified files are available in Library. Original retained.");
            }catch(Exception error){
                try{registry.job(job,"FAILED",bytes,error.getClass().getSimpleName());}catch(Exception ignored){}
                if(receipt!=null)try{
                    journal.put("state","INCOMPLETE").put("registered",registered)
                        .put("error",error.getClass().getSimpleName());write(receipt,journal);
                }catch(Exception ignored){}
                activity.runOnUiThread(()->{if(progress.isShowing())progress.dismiss();});
                notify(activity,"Extraction incomplete",registered+" files registered during this attempt. Retry is safe. "+error.getMessage());
            }finally{
                if(stage!=null){File[] files=stage.listFiles();if(files!=null)for(File f:files)f.delete();stage.delete();}
            }
        });
    }
}
