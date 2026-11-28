package com.asterion.nexus;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded, persisted private translation work. Heavy native inference is never
 * started on the UI thread, and cancelled jobs cannot finish newer invocations. */
public final class NexusTranslationJob extends JobService {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private static final class Run {
        final JobParameters params;
        final AtomicBoolean stop=new AtomicBoolean();
        Future<?> future;
        Run(JobParameters p){params=p;}
    }
    private Run active;
    @Override public boolean onStartJob(JobParameters params){
        if(active!=null){active.stop.set(true);if(active.future!=null)active.future.cancel(true);}
        Run run=new Run(params);active=run;
        run.future=worker.submit(()->{
            boolean more=false,failed=false;
            try{
                NexusTranslationQueue queue=new NexusTranslationQueue(this);
                more=queue.next(run.stop);
            }catch(Exception e){failed=true;}
            final boolean again=more,error=failed;
            main.post(()->{
                if(active!=run||run.stop.get())return;
                active=null;
                jobFinished(params,error);
                if(again&&!error)new NexusTranslationQueue(this).schedule(true);
            });
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){
        Run run=active;
        if(run!=null&&run.params.getJobId()==params.getJobId()){
            active=null;run.stop.set(true);if(run.future!=null)run.future.cancel(true);
        }
        return true;
    }
    @Override public void onDestroy(){
        Run run=active;active=null;
        if(run!=null){run.stop.set(true);if(run.future!=null)run.future.cancel(true);}
        worker.shutdownNow();super.onDestroy();
    }
}
