package com.openlocal.office.core;

import android.app.Activity;
import android.net.Uri;
import android.content.pm.ActivityInfo;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.LinearLayout;
import android.os.StatFs;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;
import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Android media playback for SAF content, including provider streams.
 * Never blocks the UI on a cloud download. ExoPlayer streams when possible;
 * if a provider cannot seek, a single cached copy is tried automatically.
 */
@androidx.media3.common.util.UnstableApi
public final class FgMediaPlayer implements AutoCloseable {
    public interface MediaNavigator {
        void navigate(int direction,boolean random);
    }
    private final MediaNavigator mediaNavigator;
    private final Activity activity;
    private final FrameLayout root;
    private final TextView status;
    private final PlayerView display;
    private final int previousOrientation;
    private final int previousSystemUi;
    private final boolean wasFullscreen;
    private final boolean wasKeepScreenOn;
    private final ExoPlayer player;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private volatile boolean fallbackStarted;
    private volatile int session=1;
    private volatile FgGrowingMediaDataSource.Progress growing;
    private Uri original;
    private String filename;
    private boolean video;
    private volatile File transientMediaFile;
    private final boolean teraboxDirect;
    private final Runnable onExit;
    // Persistent player mode survives switching to the next video.
    // 0 = Play Next, 1 = Replay, 2 = Shuffle without immediate repetition.
    private static volatile int playbackMode=0;
    private boolean endHandled=false;

    public FgMediaPlayer(Activity activity,Uri original,String filename,
                         boolean video,Runnable onExit,MediaNavigator mediaNavigator){
        this.activity=activity;this.original=original;this.filename=filename;
        this.video=video;this.onExit=onExit;
        this.mediaNavigator=mediaNavigator;
        teraboxDirect=FgTeraboxDocumentsProvider.AUTHORITY.equals(original.getAuthority());
        previousOrientation=activity.getRequestedOrientation();
        previousSystemUi=activity.getWindow().getDecorView().getSystemUiVisibility();
        int flags=activity.getWindow().getAttributes().flags;
        wasFullscreen=(flags&android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)!=0;
        wasKeepScreenOn=(flags&android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)!=0;
        root=new FrameLayout(activity);
        root.setBackgroundColor(0xff000000);
        display=new PlayerView(activity);
        display.setUseController(true);
        if(video){
            display.setUseController(false);
            display.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
            display.setControllerShowTimeoutMs(2600);
            // Media3's built-in skip/seek neighbours collide visually with
            // Rounder's own previous/next-folder-video controls.
            // Keep exactly one playlist pair, plus Media3's central play/pause.
            display.setShowPreviousButton(false);
            display.setShowNextButton(false);
            display.setShowRewindButton(false);
            display.setShowFastForwardButton(false);
        }
        FrameLayout.LayoutParams videoLayout=new FrameLayout.LayoutParams(-1,-1);
        videoLayout.gravity=Gravity.CENTER;
        root.addView(display,videoLayout);
        player=new ExoPlayer.Builder(activity).build();
        display.setPlayer(player);
        if(video){
            // Some Media3 Controller layouts retain the legacy seek buttons
            // even when PlayerView flags are false. Hide only the competing
            // symbols, not the seek bar or playback/pause control.
            for(int id:new int[]{
                androidx.media3.ui.R.id.exo_prev,
                androidx.media3.ui.R.id.exo_next,
                androidx.media3.ui.R.id.exo_rew,
                androidx.media3.ui.R.id.exo_ffwd
            }){
                View duplicate=display.findViewById(id);
                if(duplicate!=null)duplicate.setVisibility(View.GONE);
            }
        }
        TextView title=new TextView(activity);
        title.setText((video?"Video Â· ":"Audio Â· ")+filename);
        title.setTextColor(0xffffffff);
        title.setBackgroundColor(0xcc152039);
        title.setPadding(20,16,150,16);
        title.setTextSize(14);
        if(!video)root.addView(title,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        Button back=new Button(activity);
        back.setText("Back");
        back.setOnClickListener(v->onExit.run());
        if(!video)root.addView(back,new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.RIGHT));
        else {
            // Aloha-style fullscreen video chrome: one narrow title/action row,
            // central transport, native seek timeline and compact mode bar.
            // No duplicate Media3 transport or oversized PREV/NEXT panels.
            final float dp=activity.getResources().getDisplayMetrics().density;
            final android.graphics.drawable.GradientDrawable pill=
                new android.graphics.drawable.GradientDrawable();
            pill.setColor(0xc51b1e26);pill.setCornerRadius(999*dp);
            final int[] selectedRotation={0}; // 0: video aspect auto, 1: portrait, 2: landscape
            final boolean[] rotationLocked={false},verticalSource={false};
            final Runnable applyRotation=()->{
                int requested=rotationLocked[0]?
                    ActivityInfo.SCREEN_ORIENTATION_LOCKED:
                    selectedRotation[0]==1?ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:
                    selectedRotation[0]==2?ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:
                    verticalSource[0]?ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
                if(activity.getRequestedOrientation()!=requested)
                    activity.setRequestedOrientation(requested);
                activity.getWindow().getDecorView().postDelayed(()->{
                    if(!closed)forceImmersiveBars();
                },220);
            };
            final LinearLayout heading=new LinearLayout(activity);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            heading.setPadding((int)(7*dp),0,(int)(8*dp),0);
            heading.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xb5000000,0x42000000,0x00000000}));
            final Button close=new Button(activity);
            close.setText("×");close.setTextColor(-1);close.setTextSize(22);
            close.setAllCaps(false);close.setBackgroundColor(0x00000000);
            close.setContentDescription("Close video and return to library");
            close.setOnClickListener(v->onExit.run());
            heading.addView(close,new LinearLayout.LayoutParams((int)(52*dp),(int)(52*dp)));
            final TextView videoTitle=new TextView(activity);
            videoTitle.setText(filename);videoTitle.setTextSize(15);
            videoTitle.setTextColor(-1);videoTitle.setSingleLine(true);
            videoTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            heading.addView(videoTitle,new LinearLayout.LayoutParams(0,-2,1f));
            // Native vector phone / padlock icons, never diamond or share.
            final View orientation=new View(activity){
                final android.graphics.Paint p=new android.graphics.Paint(3);
                @Override protected void onDraw(android.graphics.Canvas c){
                    super.onDraw(c);
                    float u=activity.getResources().getDisplayMetrics().density;
                    float cx=getWidth()/2f,cy=getHeight()/2f;
                    boolean horizontal=(selectedRotation[0]==2||
                        (selectedRotation[0]==0&&!verticalSource[0]));
                    c.save();
                    if(horizontal)c.rotate(90,cx,cy);
                    p.setColor(0xfff0f4ff);p.setStrokeWidth(1.9f*u);
                    p.setStyle(android.graphics.Paint.Style.STROKE);
                    c.drawRoundRect(cx-8*u,cy-12*u,cx+8*u,cy+12*u,3*u,3*u,p);
                    c.drawLine(cx-4*u,cy-8.5f*u,cx+4*u,cy-8.5f*u,p);
                    p.setStyle(android.graphics.Paint.Style.FILL);
                    c.drawCircle(cx,cy+9*u,1.2f*u,p);
                    c.restore();
                }
            };
            orientation.setClickable(true);orientation.setFocusable(true);
            orientation.setContentDescription("Phone orientation. Tap for portrait or landscape.");
            final View lock=new View(activity){
                final android.graphics.Paint p=new android.graphics.Paint(3);
                @Override protected void onDraw(android.graphics.Canvas c){
                    super.onDraw(c);
                    float u=activity.getResources().getDisplayMetrics().density;
                    float cx=getWidth()/2f,cy=getHeight()/2f;
                    p.setColor(0xffeff5ff);p.setStrokeWidth(2*u);
                    p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                    p.setStyle(android.graphics.Paint.Style.STROKE);
                    c.drawArc(cx-6*u,cy-10*u,cx+6*u,cy+4*u,185,170,false,p);
                    p.setStyle(android.graphics.Paint.Style.FILL);
                    c.drawRoundRect(cx-10*u,cy-2*u,cx+10*u,cy+12*u,2.5f*u,2.5f*u,p);
                    p.setColor(0xff101924);
                    c.drawCircle(cx,cy+3*u,1.9f*u,p);
                    c.drawRect(cx-.8f*u,cy+4*u,cx+.8f*u,cy+8*u,p);
                    if(rotationLocked[0]){
                        p.setColor(0xff87c6d1);
                        p.setStyle(android.graphics.Paint.Style.STROKE);
                        p.setStrokeWidth(1.6f*u);
                        c.drawCircle(cx,cy+4*u,14*u,p);
                    }
                }
            };
            lock.setClickable(true);lock.setFocusable(true);
            lock.setContentDescription("Lock video orientation");
            orientation.setOnClickListener(v->{
                selectedRotation[0]= selectedRotation[0]==2?1:2;
                rotationLocked[0]=false;
                applyRotation.run();
                orientation.invalidate();lock.invalidate();
                orientation.setContentDescription(selectedRotation[0]==1?
                    "Phone portrait orientation":"Phone landscape orientation");
            });
            lock.setOnClickListener(v->{
                rotationLocked[0]=!rotationLocked[0];
                applyRotation.run();
                lock.invalidate();
                lock.setContentDescription(rotationLocked[0]?
                    "Unlock orientation":"Lock orientation");
            });
            heading.addView(orientation,new LinearLayout.LayoutParams((int)(44*dp),(int)(48*dp)));
            heading.addView(lock,new LinearLayout.LayoutParams((int)(44*dp),(int)(48*dp)));
            FrameLayout.LayoutParams headPosition=new FrameLayout.LayoutParams(-1,(int)(58*dp),Gravity.TOP);
            headPosition.rightMargin=(int)(12*dp);
            root.addView(heading,headPosition);
            final long[] lastChromeInteraction={android.os.SystemClock.uptimeMillis()};
            final LinearLayout transport=new LinearLayout(activity);
            transport.setGravity(Gravity.CENTER);
            transport.setOrientation(LinearLayout.HORIZONTAL);
            final VideoTransport previous=new VideoTransport(activity,0,dp);
            final VideoTransport toggle=new VideoTransport(activity,1,dp);
            final VideoTransport next=new VideoTransport(activity,2,dp);
            for(VideoTransport button:new VideoTransport[]{previous,toggle,next}){
                button.setContentDescription(button==previous?"Previous video":
                    button==next?"Next video":"Play or pause");
                int size=button==toggle?60:48;
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(
                    (int)(size*dp),(int)(size*dp));
                lp.setMargins((int)(5*dp),0,(int)(5*dp),0);
                transport.addView(button,lp);
            }
            previous.setOnClickListener(v->{
                lastChromeInteraction[0]=android.os.SystemClock.uptimeMillis();
                if(mediaNavigator!=null)mediaNavigator.navigate(-1,false);
            });
            next.setOnClickListener(v->{
                lastChromeInteraction[0]=android.os.SystemClock.uptimeMillis();
                if(mediaNavigator!=null)mediaNavigator.navigate(1,false);
            });
            toggle.setOnClickListener(v->{
                lastChromeInteraction[0]=android.os.SystemClock.uptimeMillis();
                if(player.isPlaying())player.pause();else player.play();
                toggle.invalidate();
            });
            root.addView(transport,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
            // Seek is separate from playlist navigation and never occluded.
            final LinearLayout footer=new LinearLayout(activity);
            footer.setOrientation(LinearLayout.VERTICAL);
            footer.setPadding((int)(8*dp),0,(int)(8*dp),(int)(5*dp));
            footer.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xb8000000,0x00000000}));
            final LinearLayout timeline=new LinearLayout(activity);
            timeline.setGravity(Gravity.CENTER_VERTICAL);
            final TextView elapsed=new TextView(activity),duration=new TextView(activity);
            for(TextView time:new TextView[]{elapsed,duration}){
                time.setText("00:00");time.setTextColor(-1);time.setTextSize(13);
                time.setGravity(Gravity.CENTER);
            }
            final android.widget.SeekBar scrub=new android.widget.SeekBar(activity);
            scrub.setMax(1000);
            scrub.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff7ec2cc));
            scrub.setThumbTintList(android.content.res.ColorStateList.valueOf(0xff7ec2cc));
            timeline.addView(elapsed,new LinearLayout.LayoutParams((int)(54*dp),-2));
            timeline.addView(scrub,new LinearLayout.LayoutParams(0,-2,1f));
            timeline.addView(duration,new LinearLayout.LayoutParams((int)(54*dp),-2));
            footer.addView(timeline,new LinearLayout.LayoutParams(-1,(int)(45*dp)));
            // One 42dp playback-type symbol at the far right of the timeline.
            // Tap cycles Play Next -> Replay -> Shuffle; no oversized options row.
            final View modeSymbol=new View(activity){
                final android.graphics.Paint paint=new android.graphics.Paint(3);
                @Override protected void onDraw(android.graphics.Canvas canvas){
                    super.onDraw(canvas);
                    float scale= getResources().getDisplayMetrics().density;
                    float cx=getWidth()/2f,cy=getHeight()/2f;
                    paint.setColor(0xfff1f5ff);paint.setStrokeWidth(2.3f*scale);
                    paint.setStyle(android.graphics.Paint.Style.STROKE);
                    paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                    paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                    float r=10*scale;
                    if(playbackMode==0){
                        paint.setStyle(android.graphics.Paint.Style.FILL);
                        android.graphics.Path arrow=new android.graphics.Path();
                        arrow.moveTo(cx-r*.65f,cy-r*.9f);
                        arrow.lineTo(cx+r*.45f,cy);
                        arrow.lineTo(cx-r*.65f,cy+r*.9f);arrow.close();
                        canvas.drawPath(arrow,paint);
                        paint.setStyle(android.graphics.Paint.Style.STROKE);
                        canvas.drawLine(cx+r*.8f,cy-r,cx+r*.8f,cy+r,paint);
                    }else if(playbackMode==1){
                        canvas.drawArc(cx-r,cy-r,cx+r,cy+r,38,300,false,paint);
                        paint.setStyle(android.graphics.Paint.Style.FILL);
                        android.graphics.Path arrow=new android.graphics.Path();
                        arrow.moveTo(cx+r*.95f,cy-r*.9f);
                        arrow.lineTo(cx+r*.95f,cy-r*.05f);
                        arrow.lineTo(cx+r*.05f,cy-r*.5f);arrow.close();
                        canvas.drawPath(arrow,paint);
                        paint.setStyle(android.graphics.Paint.Style.STROKE);
                        canvas.drawText("1",cx-r*.22f,cy+r*.33f,paint);
                    }else{
                        canvas.drawLine(cx-r,cy-r*.75f,cx+r,cy+r*.75f,paint);
                        canvas.drawLine(cx-r,cy+r*.75f,cx+r,cy-r*.75f,paint);
                        paint.setStyle(android.graphics.Paint.Style.FILL);
                        android.graphics.Path a=new android.graphics.Path();
                        a.moveTo(cx+r*.95f,cy+r*.75f);
                        a.lineTo(cx+r*.30f,cy+r*.75f);
                        a.lineTo(cx+r*.85f,cy+r*.15f);a.close();canvas.drawPath(a,paint);
                        android.graphics.Path b=new android.graphics.Path();
                        b.moveTo(cx+r*.95f,cy-r*.75f);
                        b.lineTo(cx+r*.30f,cy-r*.75f);
                        b.lineTo(cx+r*.85f,cy-r*.15f);b.close();canvas.drawPath(b,paint);
                    }
                }
            };
            modeSymbol.setClickable(true);
            modeSymbol.setFocusable(true);
            modeSymbol.setContentDescription("Playback mode: "+playbackModeName()+". Tap to change.");
            timeline.addView(modeSymbol,new LinearLayout.LayoutParams((int)(46*dp),(int)(43*dp)));
            modeSymbol.setOnClickListener(v->{
                lastChromeInteraction[0]=android.os.SystemClock.uptimeMillis();
                playbackMode=(playbackMode+1)%3;
                modeSymbol.invalidate();
                modeSymbol.setContentDescription("Playback mode: "+playbackModeName()+". Tap to change.");
                android.widget.Toast.makeText(activity,playbackModeName(),
                    android.widget.Toast.LENGTH_SHORT).show();
            });
            FrameLayout.LayoutParams footerPosition=
                new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
            // In landscape the right-edge system gesture/navigation area
            // remains reserved even when Android temporarily shows its taskbar.
            footerPosition.rightMargin=(int)(20*dp);
            footerPosition.leftMargin=(int)(8*dp);
            footerPosition.bottomMargin=(int)(12*dp);
            root.addView(footer,footerPosition);
            scrub.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
                boolean dragging=false;
                @Override public void onStartTrackingTouch(android.widget.SeekBar b){dragging=true;}
                @Override public void onProgressChanged(android.widget.SeekBar b,int value,boolean fromUser){}
                @Override public void onStopTrackingTouch(android.widget.SeekBar b){
                    dragging=false;long end=player.getDuration();
                    if(end>0&&player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
                        player.seekTo(end*b.getProgress()/1000L);
                }
            });
            final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
            final Runnable[] clock={null};
            clock[0]=()->{
                if(closed)return;
                long ms=Math.max(0,player.getCurrentPosition()),end=player.getDuration();
                elapsed.setText(clockText(ms));duration.setText(clockText(Math.max(0,end)));
                toggle.setPlaying(player.isPlaying());
                if(player.isPlaying() && transport.getVisibility()==View.VISIBLE &&
                   android.os.SystemClock.uptimeMillis()-lastChromeInteraction[0]>3400){
                    transport.setVisibility(View.GONE);
                    heading.setVisibility(View.GONE);
                    footer.setVisibility(View.GONE);
                }
                if(end>0&&!scrub.isPressed())
                    scrub.setProgress((int)Math.min(1000,ms*1000L/end));
                handler.postDelayed(clock[0],350);
            };
            handler.post(clock[0]);
            // 1x means edge-to-edge cropped video, not fitted pillarboxes.
            // Pinch up to 4x and pan when magnified; double tap center resets.
            final float[] videoScale={1f},videoX={0f},videoY={0f};
            final Runnable applyZoom=()->{
                float scale=videoScale[0];
                float maxX=Math.max(0,(root.getWidth()*(scale-1))/2f);
                float maxY=Math.max(0,(root.getHeight()*(scale-1))/2f);
                videoX[0]=Math.max(-maxX,Math.min(maxX,videoX[0]));
                videoY[0]=Math.max(-maxY,Math.min(maxY,videoY[0]));
                display.setPivotX(display.getWidth()/2f);
                display.setPivotY(display.getHeight()/2f);
                display.setScaleX(scale);display.setScaleY(scale);
                display.setTranslationX(videoX[0]);display.setTranslationY(videoY[0]);
            };
            final android.view.ScaleGestureDetector scaler=
                new android.view.ScaleGestureDetector(activity,
                    new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener(){
                        @Override public boolean onScale(android.view.ScaleGestureDetector detector){
                            videoScale[0]=Math.max(1f,Math.min(4f,
                                videoScale[0]*detector.getScaleFactor()));
                            if(videoScale[0]<1.02f){
                                videoScale[0]=1f;videoX[0]=0;videoY[0]=0;
                            }
                            applyZoom.run();return true;
                        }
                    });
            final float[] lastPan={0f,0f};
            final View tapLayer=new View(activity);
            final GestureDetector detector=new GestureDetector(activity,
                new GestureDetector.SimpleOnGestureListener(){
                    @Override public boolean onDown(MotionEvent e){return true;}
                    @Override public boolean onSingleTapConfirmed(MotionEvent e){
                        lastChromeInteraction[0]=android.os.SystemClock.uptimeMillis();
                        int newVisibility=transport.getVisibility()==View.VISIBLE?
                            View.GONE:View.VISIBLE;
                        transport.setVisibility(newVisibility);
                        heading.setVisibility(newVisibility);
                        footer.setVisibility(newVisibility);
                        return true;
                    }
                    @Override public boolean onDoubleTap(MotionEvent e){
                        lastChromeInteraction[0]=android.os.SystemClock.uptimeMillis();
                        if(videoScale[0]>1.02f ||
                           (e.getX()>=root.getWidth()*.33f&&
                            e.getX()<=root.getWidth()*.67f)){
                            videoScale[0]=1f;videoX[0]=0f;videoY[0]=0f;
                            applyZoom.run();
                            return true;
                        }
                        if(!player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))return true;
                        long amount=e.getX()<root.getWidth()/2?-10000:10000;
                        long position=Math.max(0,player.getCurrentPosition()+amount);
                        if(player.getDuration()>0)position=Math.min(position,player.getDuration());
                        player.seekTo(position);return true;
                    }
                });
            tapLayer.setOnTouchListener((v,e)->{
                scaler.onTouchEvent(e);
                int action=e.getActionMasked();
                if(action==MotionEvent.ACTION_DOWN){
                    lastPan[0]=e.getX();lastPan[1]=e.getY();
                }else if(action==MotionEvent.ACTION_MOVE&&e.getPointerCount()==1&&
                         !scaler.isInProgress()&&videoScale[0]>1.02f){
                    videoX[0]+=e.getX()-lastPan[0];
                    videoY[0]+=e.getY()-lastPan[1];
                    applyZoom.run();
                }
                if(e.getPointerCount()==1){
                    lastPan[0]=e.getX();lastPan[1]=e.getY();
                }
                return scaler.isInProgress() || detector.onTouchEvent(e) ||
                       videoScale[0]>1.02f;
            });
            // Gesture layer is behind all buttons, so playback actions stay clickable.
            root.addView(tapLayer,1,new FrameLayout.LayoutParams(-1,-1));
            player.addListener(new Player.Listener(){
                @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize size){
                    if(size.width>0&&size.height>0){
                        verticalSource[0]=size.height>size.width;
                        if(selectedRotation[0]==0&&!rotationLocked[0])applyRotation.run();
                    }
                }
            });
            enterVideoFullscreen();
        }
        status=new TextView(activity);
        status.setTextColor(0xffcbd9ed);
        status.setTextSize(13);
        status.setBackgroundColor(0xbb101827);
        status.setPadding(14,12,14,12);
        root.addView(status,new FrameLayout.LayoutParams(
            video?-2:-1,-2,video?(Gravity.TOP|Gravity.CENTER_HORIZONTAL):Gravity.BOTTOM));
        if(video){
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)status.getLayoutParams();
            lp.topMargin=(int)(65*activity.getResources().getDisplayMetrics().density);
            status.setLayoutParams(lp);
        }
        final int[] bufferingSequence={0};
        status.setOnClickListener(v->{
            if(closed||player.getPlaybackState()!=Player.STATE_BUFFERING)return;
            status.setClickable(false);
            showMessage("Retrying video stream…");
            try{
                if(teraboxDirect)playTeraboxRanges();
                else play(original);
            }catch(Exception retryError){
                showMessage("Video stream retry failed. Reopen the file.");
            }
        });
        status.setClickable(false);
        player.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int state){
                if(closed)return;
                bufferingSequence[0]++;
                if(state==Player.STATE_READY){
                    status.setClickable(false);
                    endHandled=false;showMessage("");
                    Log.i("FG_MEDIA","MEDIA_READY "+filename+
                        " duration_ms="+player.getDuration());
                }else if(state==Player.STATE_BUFFERING){
                    showMessage(teraboxDirect?"Connecting to TeraBox video…":"Loading video…");
                    final int sequence=bufferingSequence[0];
                    root.postDelayed(()->{
                        if(closed||bufferingSequence[0]!=sequence||
                           player.getPlaybackState()!=Player.STATE_BUFFERING)return;
                        showMessage(teraboxDirect?
                            "Video data has stalled. Tap here to retry TeraBox streaming.":
                            "Video data has stalled. Tap here to retry.");
                        status.setClickable(true);
                    },14000L);
                }
                else if(state==Player.STATE_ENDED){
                    if(endHandled)return;
                    endHandled=true;
                    if(playbackMode==1){
                        player.seekTo(0);player.play();
                        showMessage("");
                    }else if(mediaNavigator!=null){
                        mediaNavigator.navigate(playbackMode==2?0:1,playbackMode==2);
                    }else showMessage("Playback finished");
                }
            }
            @Override public void onRenderedFirstFrame(){
                Log.i("FG_MEDIA","VIDEO_FIRST_FRAME "+filename);
            }
            @Override public void onPlayerError(PlaybackException error){
                bufferingSequence[0]++;
                status.setClickable(false);
                Log.e("FG_MEDIA","PLAYBACK_ERROR code="+error.getErrorCodeName(),error);
                if(teraboxDirect){
                    // A cloud file is a seekable data source. Falling back to
                    // copyThenPlay() would silently download the entire video.
                    showMessage("TeraBox playback unavailable: "+error.getErrorCodeName()+
                        " · tap Back to retry");
                }else if(!fallbackStarted){
                    fallbackStarted=true;copyThenPlay();
                }else showMessage("Unable to decode or play this file: "+error.getMessage());
            }
        });
        // TeraBox supports seekable native ranges. Other selected Android
        // documents use their granted file URI; progressive fallback is
        // activated only if the media decoder reports an actual failure.
        if(teraboxDirect)playTeraboxRanges();
        else play(original);
    }
    private void playTeraboxRanges(){
        try{
            Log.i("FG_MEDIA","TERABOX_DIRECT_RANGE_PLAY");
            androidx.media3.datasource.DataSource.Factory factory=
                ()->new FgTeraboxMediaDataSource(activity,original);
            player.setMediaSource(new ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(original)));
            player.prepare();player.play();
        }catch(Exception error){
            Log.e("FG_MEDIA","TERABOX_RANGE_PREPARE_FAILED",error);
            showMessage("Unable to start cloud video: "+error.getMessage());
        }
    }
    private static String clockText(long ms){
        long seconds=Math.max(0,ms/1000L);
        return String.format(java.util.Locale.ROOT,"%02d:%02d",
            seconds/60L,seconds%60L);
    }
    private static String playbackModeName(){
        return playbackMode==0?"Play Next":playbackMode==1?"Replay":"Shuffle";
    }
    private static String playbackSymbol(){
        // Drawable Unicode monochrome glyphs, not a word-sized button row.
        return playbackMode==0?"⇥":playbackMode==1?"↻":"⤨";
    }
    private static void refreshPlaybackMode(Button[] buttons){
        for(int i=0;i<buttons.length;i++){
            boolean selected=i==playbackMode;
            buttons[i].setTextColor(android.graphics.Color.WHITE);
            buttons[i].setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                    selected?0xff456ae8:0xff273449));
            buttons[i].setAlpha(selected?1f:0.78f);
            buttons[i].setSelected(selected);
        }
    }
    public View getView(){return root;}
    /** Restore the selected Nexus preview position in the existing player. */
    public void seekToPosition(long positionMs){
        if(!closed&&positionMs>0)player.seekTo(positionMs);
    }
    private static final class VideoTransport extends View{
        private final int role;
        private final float dp;
        private boolean playing;
        private final android.graphics.Paint paint=new android.graphics.Paint(3);
        VideoTransport(Activity context,int role,float dp){
            super(context);this.role=role;this.dp=dp;
            setClickable(true);setFocusable(true);
        }
        void setPlaying(boolean value){if(playing!=value){playing=value;invalidate();}}
        @Override protected void onDraw(android.graphics.Canvas c){
            super.onDraw(c);
            float cx=getWidth()/2f,cy=getHeight()/2f;
            float radius=Math.min(getWidth(),getHeight())*.48f;
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(role==1?0xe01d2d43:0xd21b2a3f);
            c.drawCircle(cx,cy,radius,paint);
            paint.setColor(0xfff0d3aa);
            paint.setStrokeWidth(2.25f*dp);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            if(role==1){
                paint.setStyle(android.graphics.Paint.Style.FILL);
                if(playing){
                    c.drawRoundRect(cx-6*dp,cy-9*dp,cx-2*dp,cy+9*dp,1.3f*dp,1.3f*dp,paint);
                    c.drawRoundRect(cx+2*dp,cy-9*dp,cx+6*dp,cy+9*dp,1.3f*dp,1.3f*dp,paint);
                }else{
                    android.graphics.Path play=new android.graphics.Path();
                    play.moveTo(cx-5*dp,cy-9*dp);
                    play.lineTo(cx+9*dp,cy);
                    play.lineTo(cx-5*dp,cy+9*dp);
                    play.close();c.drawPath(play,paint);
                }
            }else{
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                final float direction=role==0?-1f:1f;
                android.graphics.Path skip=new android.graphics.Path();
                skip.moveTo(cx-4*direction*dp,cy-8*dp);
                skip.lineTo(cx+4*direction*dp,cy);
                skip.lineTo(cx-4*direction*dp,cy+8*dp);
                c.drawPath(skip,paint);
                c.drawLine(cx+9*direction*dp,cy-9*dp,
                    cx+9*direction*dp,cy+9*dp,paint);
            }
        }
    }
    private TextView smallChromeIcon(String glyph,String description,float density){
        TextView view=new TextView(activity);
        view.setText(glyph);
        view.setTextColor(0xffffffff);
        view.setTextSize(23);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        view.setBackgroundColor(0x00000000);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }
    private void forceImmersiveBars(){
        activity.getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN|
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE|
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        if(android.os.Build.VERSION.SDK_INT>=30){
            android.view.WindowInsetsController controller=
                activity.getWindow().getInsetsController();
            if(controller!=null){
                controller.hide(android.view.WindowInsets.Type.statusBars()|
                    android.view.WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }
    private void enterVideoFullscreen(){
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        activity.getWindow().addFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN |
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        forceImmersiveBars();
    }
    private void leaveVideoFullscreen(){
        activity.getWindow().getDecorView().setSystemUiVisibility(previousSystemUi);
        if(!wasFullscreen)activity.getWindow().clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if(!wasKeepScreenOn)activity.getWindow().clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        activity.setRequestedOrientation(previousOrientation);
    }
    private void showMessage(String message){
        activity.runOnUiThread(()->{
            if(closed)return;
            // Download progress must not cover the video seek bar or remain
            // visible throughout normal progressive playback.
            String shown=video && player.getPlaybackState()==Player.STATE_READY &&
                message.startsWith("Streaming ")?"":message;
            status.setText(shown);
            status.setVisibility(shown.isEmpty()?View.GONE:View.VISIBLE);
        });
    }
    private void play(Uri uri){
        if(closed)return;
        try{
            Log.i("FG_MEDIA","PLAY_START "+uri.getScheme()+" "+uri.getAuthority());
            player.setMediaItem(MediaItem.fromUri(uri));
            player.prepare();
            player.play();
        }catch(Exception e){
            Log.e("FG_MEDIA","MEDIA_PREPARE_FAILED",e);
            if(!fallbackStarted){fallbackStarted=true;copyThenPlay();}
            else showMessage("Playback failed: "+e.getMessage());
        }
    }
    private static String digest(Uri uri){
        try{
            byte[] bytes=MessageDigest.getInstance("SHA-256")
                .digest(uri.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder text=new StringBuilder();
            for(int i=0;i<16;i++)text.append(String.format(Locale.ROOT,"%02x",bytes[i]&255));
            return text.toString();
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    /** MP4s with a fast-start index can begin once the first bytes arrive.
     * Do not wait for an entire multi-GB remote movie. An MP4 with its
     * moov index at the end still requires seekable provider or later bytes.
     */
    private void copyThenPlay(){
        final int version=session;
        showMessage("Connecting to video streamâ€¦");
        io.execute(()->{
            FgGrowingMediaDataSource.Progress progress=null;
            try{
                File cacheDir=new File(activity.getExternalCacheDir()!=null?
                    activity.getExternalCacheDir():activity.getCacheDir(),"fg-media-session-v3");
                if(!cacheDir.exists()&&!cacheDir.mkdirs())
                    throw new IOException("Media cache directory is not available");
                String low=filename.toLowerCase(Locale.ROOT);
                String extension=".mp4";
                for(String suffix:new String[]{".webm",".mkv",".mov",".m4a",
                    ".mp3",".flac",".wav",".ogg",".opus"}){
                    if(low.endsWith(suffix)){extension=suffix;break;}
                }
                // Player scratch is session-only. Never reuse a completed
                // remote movie as a cache in subsequent sessions.
                File ready=new File(cacheDir,"session-"+
                    java.util.UUID.randomUUID()+extension);
                transientMediaFile=ready;
                if(!ready.createNewFile())throw new IOException("Cannot create session media stream");
                progress=new FgGrowingMediaDataSource.Progress(ready);
                growing=progress;
                final FgGrowingMediaDataSource.Progress state=progress;
                // Start ExoPlayer immediately. Its decoder will block only
                // when it asks for bytes not yet received from the provider.
                activity.runOnUiThread(()->{
                    if(closed||version!=session)return;
                    try{
                        androidx.media3.datasource.DataSource.Factory factory=
                            ()->new FgGrowingMediaDataSource(state);
                        player.setMediaSource(new ProgressiveMediaSource.Factory(factory)
                            .createMediaSource(MediaItem.fromUri(Uri.fromFile(ready))));
                        player.prepare();player.play();
                        Log.i("FG_MEDIA","PROGRESSIVE_PLAYBACK_START "+filename);
                    }catch(Exception e){
                        Log.e("FG_MEDIA","PROGRESSIVE_PREPARE_FAILED",e);
                        showMessage("Video cannot start: "+e.getMessage());
                    }
                });
                long written=0,reported=0;
                try(InputStream in=activity.getContentResolver().openInputStream(original);
                    FileOutputStream out=new FileOutputStream(ready)){
                    if(in==null)throw new IOException("Media provider returned no stream");
                    byte[] buffer=new byte[128*1024];
                    int n;
                    while((n=in.read(buffer))!=-1){
                        if(closed||version!=session)
                            throw new IOException("Playback cancelled");
                        out.write(buffer,0,n);
                        // Flush each chunk before publishing its length to
                        // readers that may be waiting at that offset.
                        out.flush();
                        written+=n;
                        state.available=written;
                        state.wake();
                        if(written-reported>=2L*1024*1024){
                            reported=written;
                            final long copied=written;
                            Log.i("FG_MEDIA","STREAM_BYTES_READY "+copied);
                            showMessage("Streaming "+copied/1048576+" MB receivedâ€¦");
                            StatFs stat=new StatFs(cacheDir.getAbsolutePath());
                            if(stat.getAvailableBytes()<64L*1024*1024)
                                throw new IOException("Not enough space to cache video");
                        }
                    }
                }
                if(written==0)throw new IOException("Video file is empty");
                state.finished=true;state.wake();
                Log.i("FG_MEDIA","STREAM_DOWNLOAD_COMPLETE bytes="+written);
            }catch(Exception e){
                Log.e("FG_MEDIA","VIDEO_STREAM_FAILED",e);
                if(progress!=null){
                    progress.failure=e instanceof IOException?(IOException)e:new IOException(e);
                    progress.finished=true;progress.wake();
                }
                if(!closed&&version==session)
                    showMessage("Video stream failed: "+e.getMessage());
            }
        });
    }
    @Override public void close(){
        closed=true;session++;
        if(growing!=null){growing.cancelled=true;growing.wake();}
        player.stop();player.release();
        io.shutdownNow();
        File scratch=transientMediaFile;
        transientMediaFile=null;
        if(scratch!=null && scratch.exists())scratch.delete();
        if(video)leaveVideoFullscreen();
    }
}