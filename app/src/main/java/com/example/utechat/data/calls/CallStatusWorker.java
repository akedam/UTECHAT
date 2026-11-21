package com.example.utechat.data.calls;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.example.utechat.app.CallRepositoryProvider;
import com.example.utechat.domain.model.CallSession;
import com.example.utechat.domain.repository.CallRepository;
import com.google.firebase.auth.FirebaseAuth;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Commits a locally closed call and its history when the device reconnects. */
public final class CallStatusWorker extends Worker {
    public CallStatusWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    public static void enqueue(Context context, String callId, String status) {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return;
        var request = new OneTimeWorkRequest.Builder(CallStatusWorker.class)
                .setInputData(new Data.Builder().putString("uid", uid).putString("callId", callId)
                        .putString("status", status).putLong("createdAt", System.currentTimeMillis()).build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork("call-status:" + uid + ":" + callId,
                ExistingWorkPolicy.KEEP, request);
    }

    @NonNull @Override public Result doWork() {
        var data = getInputData();
        String uid = data.getString("uid");
        String id = data.getString("callId");
        String status = data.getString("status");
        if (uid == null || id == null || status == null || !uid.equals(FirebaseAuth.getInstance().getUid())
                || System.currentTimeMillis() - data.getLong("createdAt", 0) > TimeUnit.HOURS.toMillis(24)) {
            return Result.success();
        }
        var finished = new CountDownLatch(1);
        var committed = new AtomicBoolean(false);
        CallRepositoryProvider.get(getApplicationContext()).updateStatus(id, status, new CallRepository.Callback() {
            @Override public void onSuccess(CallSession unused) { committed.set(true); finished.countDown(); }
            @Override public void onError(String error) { finished.countDown(); }
        });
        try {
            if (finished.await(30, TimeUnit.SECONDS) && committed.get()) return Result.success();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return Result.retry();
    }
}
