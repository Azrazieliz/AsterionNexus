package com.asterion.nexus;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.net.Uri;
import com.openlocal.office.core.FgMediaPlayer;

/** Playback position is bound to a verified original, not a mutable filename. */
public final class NexusRounderPlaybackActivity extends Activity {
    private FgMediaPlayer player;
    private String sha;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable checkpoint=new Runnable(){public void run(){saveProgress();handler.postDelayed(this,15000L);}};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        Uri uri=getIntent().getData();
        if(uri==null){finish();return;}
        if((getPackageName()+".library").equals(uri.getAuthority())&&uri.getPathSegments().size()==2&&"original".equals(uri.getPathSegments().get(0))){
            String candidate=uri.getLastPathSegment();
            if(candidate!=null&&candidate.matches("[0-9a-f]{64}"))sha=candidate;
        }
        String label=getIntent().getStringExtra("title");
        boolean video=getIntent().getType()==null||!getIntent().getType().startsWith("audio/");
        player=new FgMediaPlayer(this,uri,label==null?"Media":label,
            video,()->finish(),null,()->NexusFileDetails.show(this,uri,getIntent().getType(),label==null?"Media":label));
        long position=getIntent().getLongExtra("nexus_position_ms",0L);
        if(saved!=null)position=saved.getLong("playback_position",position);
        else if(position<=0&&sha!=null)position=getPreferences(MODE_PRIVATE).getLong("position:"+sha,0L);
        if(position>0)player.seekToPosition(position);
        setContentView(player.getView());
    }
    private void saveProgress(){
        if(player==null||sha==null)return;
        long duration=player.durationMs(),position=player.positionMs();
        if(duration<=0||position<0||position>duration)return;
        long remaining=duration-position;
        getPreferences(MODE_PRIVATE).edit().putLong("position:"+sha,remaining<=Math.min(3000L,duration/20L)?0L:position).apply();
    }
    @Override public void onResume(){super.onResume();handler.removeCallbacks(checkpoint);handler.postDelayed(checkpoint,15000L);}
    @Override public void onPause(){handler.removeCallbacks(checkpoint);saveProgress();if(player!=null)player.pausePlayback();super.onPause();}
    @Override public void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);if(player!=null)state.putLong("playback_position",player.positionMs());}
    @Override public void onDestroy(){handler.removeCallbacks(checkpoint);if(player!=null)player.close();super.onDestroy();}
}
