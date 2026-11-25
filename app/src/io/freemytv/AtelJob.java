package io.freemytv;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * atel (this fork): fetches the agenda every 15 minutes in the background, so an appointment added while another app
 * is in front still gets its reminder (AtelReminder). The home screen fetches on its own while it shows; this job only
 * covers the rest of the time. Read only, like the home screen: GET /device/v1/agenda.
 */
public class AtelJob extends JobService {
    private static final int ID = 4318;
    private static final long EVERY_MS = 15 * 60_000L;

    /** Schedules the job once (it survives a restart); nothing when no atel server is built in. */
    static void ensure(Context c) {
        if (!Atel.configured()) return;
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (scheduler == null || scheduler.getPendingJob(ID) != null) return;
        JobInfo job = new JobInfo.Builder(ID, new ComponentName(c, AtelJob.class))
                .setPeriodic(EVERY_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        try {
            scheduler.schedule(job);
        } catch (RuntimeException e) {
            Log.w("FreeMyTV", "atel: background job not scheduled (" + e.getMessage() + ")");
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        final Context app = getApplicationContext();
        new Thread(() -> {
            try {
                Atel.fetchNow(app);
            } catch (Throwable t) {
                Log.w("FreeMyTV", "atel: background fetch failed: " + t);
            }
            jobFinished(params, false);
        }, "atel-agenda").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return false;
    }
}
