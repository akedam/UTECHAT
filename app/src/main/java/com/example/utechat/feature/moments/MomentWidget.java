package com.example.utechat.feature.moments;

import com.example.utechat.data.auth.PhoneAccountClient;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.RemoteViews;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.example.utechat.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/** Launcher opt-in is per account. Only the widget's host holds a rendered copy. */
public final class MomentWidget extends AppWidgetProvider {
    private static final String WORK = "utechat-moment-widget";
    private static final String PREFS = "moment-widget";
    private static long generation;

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        refresh(context);
    }

    @Override public void onDisabled(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK);
    }

    static boolean enabled(Context context, String uid) {
        return uid != null && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("show." + uid, false);
    }

    static synchronized void setEnabled(Context context, String uid, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("show." + uid, enabled).apply();
        if (!enabled) publish(context, null, null);
        refresh(context);
    }

    public static synchronized void invalidate(Context context) {
        publish(context, null, null);
        refresh(context);
    }

    public static synchronized void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager == null || manager.getAppWidgetIds(component(context)).length == 0) return;
        long version = ++generation;
        String uid = currentUid();
        WorkManager work = WorkManager.getInstance(context);
        if (!enabled(context, uid)) {
            publish(context, null, null);
            work.cancelUniqueWork(WORK);
            return;
        }
        work.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, new OneTimeWorkRequest.Builder(Refresh.class)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(new Data.Builder().putString("uid", uid).putLong("version", version).build())
                .build());
    }

    private static String currentUid() {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            return PhoneAccountClient.hasVerifiedContact(user) ? user.getUid() : null;
        } catch (IllegalStateException missing) { return null; }
    }

    private static ComponentName component(Context context) { return new ComponentName(context, MomentWidget.class); }

    private static void publish(Context context, String id, Bitmap picture) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager == null || manager.getAppWidgetIds(component(context)).length == 0) return;
        RemoteViews layout = new RemoteViews(context.getPackageName(), R.layout.widget_moment);
        layout.setViewVisibility(R.id.widget_moment_photo, picture == null ? View.GONE : View.VISIBLE);
        layout.setViewVisibility(R.id.widget_moment_empty, picture == null ? View.VISIBLE : View.GONE);
        if (picture != null) layout.setImageViewBitmap(R.id.widget_moment_photo, picture);
        layout.setOnClickPendingIntent(R.id.widget_moment_root, PendingIntent.getActivity(context, 779,
                MomentsFragment.intent(context, id), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        manager.updateAppWidget(component(context), layout);
    }

    public static final class Refresh extends Worker {
        public Refresh(@NonNull Context context, @NonNull WorkerParameters parameters) { super(context, parameters); }

        @NonNull @Override public Result doWork() {
            Context context = getApplicationContext();
            String uid = getInputData().getString("uid");
            if (!enabled(context, uid) || !uid.equals(currentUid())) return Result.success();
            Bitmap picture = null;
            String id = null;
            try {
                var feed = MomentApi.list("received", "").getJSONArray("items");
                if (feed.length() > 0) {
                    id = feed.getJSONObject(0).getString("id");
                    picture = MomentApi.photo(id, true);
                }
            } catch (Exception failure) {
                if (isStopped()) return Result.success();
                return getRunAttemptCount() < 2 ? Result.retry() : Result.failure();
            }
            synchronized (MomentWidget.class) {
                if (!isStopped() && (generation == 0 || generation == getInputData().getLong("version", -1))
                        && enabled(context, uid) && uid.equals(currentUid())) publish(context, id, picture);
            }
            return Result.success();
        }
    }
}
