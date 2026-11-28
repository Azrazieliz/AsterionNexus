package com.asterion.nexus;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import com.openlocal.office.core.FgTeraboxClient;

/** One content-oriented viewer router, regardless of original source. */
public final class NexusFileOpen {
    private NexusFileOpen(){}
    public static void open(Activity activity,Uri uri,String mime,String title){
        openAt(activity,uri,mime,title,0L);
    }
    public static void openAt(Activity activity,Uri uri,String mime,String title,long positionMs){
        if(uri==null)throw new IllegalArgumentException("File location missing");
        String resolved=mime==null||mime.isBlank()?
            FgTeraboxClient.mime(title==null?"":title):mime;
        resolved=NexusRegistry.canonicalComicMime(title,resolved);
        Intent intent;
        if(resolved.startsWith("image/")){
            intent=new Intent(activity,NexusImageViewerActivity.class);
        }else if(resolved.startsWith("video/")||resolved.startsWith("audio/")){
            intent=new Intent(activity,NexusRounderPlaybackActivity.class);
        }else if(resolved.equals("application/pdf")||resolved.equals("application/epub+zip")||
            resolved.equals("application/zip")||resolved.equals("text/plain")||
            resolved.equals("application/vnd.comicbook+zip")||NexusRarBook.supported(resolved)||
            resolved.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")||NexusSpreadsheet.supported(resolved,title)){
            intent=new Intent(activity,NexusReaderActivity.class);
        }else{
            final String externalType=resolved;
            final Intent external=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,resolved);
            external.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            NexusFileDetails.showWithOpen(activity,uri,resolved,title,()->{
                try{activity.startActivity(external);}
                catch(android.content.ActivityNotFoundException error){
                    new android.app.AlertDialog.Builder(activity).setMessage("No compatible viewer installed for "+externalType).setPositiveButton("Close",null).show();
                }
            });
            return;
        }
        intent.setDataAndType(uri,resolved);
        intent.putExtra("title",title);
        if(positionMs>0)intent.putExtra("nexus_position_ms",positionMs);
        activity.startActivity(intent);
    }
}
