package com.asterion.nexus;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single durable book-index queue. Each invocation has its own cancellation
 * token; a stopped job cannot finish or reschedule a newer invocation. */
public final class NexusBookIndexJob extends JobService {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private static final class Run {
        final JobParameters params;
        final AtomicBoolean stop=new AtomicBoolean();
        Future<?> future;
        Run(JobParameters params){this.params=params;}
    }
    private Run active;
    @Override public boolean onStartJob(JobParameters params){
        if(active!=null){active.stop.set(true);if(active.future!=null)active.future.cancel(true);}
        Run run=new Run(params);active=run;
        run.future=worker.submit(()->{
            boolean more=false,failed=false;
            try{
                NexusBookIndex index=new NexusBookIndex(this);
                long until=android.os.SystemClock.elapsedRealtime()+180000L;
                do{
                    if(run.stop.get()||Thread.currentThread().isInterrupted())break;
                    more=index.indexNext(run.stop);
                }while(more&&android.os.SystemClock.elapsedRealtime()<until);
            }catch(Exception e){failed=true;}
            final boolean again=more,error=failed;
            main.post(()->{
                if(active!=run||run.stop.get())return;
                active=null;
                jobFinished(params,error);
                if(again&&!error)new NexusBookIndex(this).schedule(true);
            });
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){
        Run run=active;
        if(run!=null&&run.params==params){
            active=null;run.stop.set(true);
            if(run.future!=null)run.future.cancel(true);
        }
        return true;
    }
    @Override public void onDestroy(){
        Run run=active;active=null;
        if(run!=null){run.stop.set(true);if(run.future!=null)run.future.cancel(true);}
        worker.shutdownNow();super.onDestroy();
    }
}
