package com.example.utechat.app;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.utechat.R;
import com.example.utechat.domain.model.CallSession;
import com.example.utechat.domain.repository.CallRepository;
import com.example.utechat.data.notifications.PushTokenRepository;
import com.example.utechat.feature.calls.ui.CallActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.concurrent.atomic.AtomicReference;
import java.util.HashSet;
import java.util.Set;

/** App lifecycle coordination for opt-in presence and notification channels. */
public final class UteChatApplication extends Application
        implements Application.ActivityLifecycleCallbacks, FirebaseAuth.AuthStateListener {
    private static final AtomicReference<String> ACTIVE_CHAT = new AtomicReference<>();
    private static final AtomicReference<String> ACTIVE_CALL = new AtomicReference<>();
    private DevicePresenceManager presenceManager;
    private FirebaseAuth auth;
    private int startedActivities;
    private CallRepository callRepository;
    private final Set<String> deliveredCallIds = new HashSet<>();

    public static UteChatApplication from(Context context) {
        Context app = context.getApplicationContext();
        return app instanceof UteChatApplication ? (UteChatApplication) app : null;
    }

    public static boolean isChatVisible(String conversationId) {
        return conversationId != null && conversationId.equals(ACTIVE_CHAT.get());
    }

    public static void setActiveChat(@Nullable String conversationId) {
        ACTIVE_CHAT.set(conversationId);
    }

    public static boolean hasActiveCall() { return ACTIVE_CALL.get() != null; }
    public static boolean setActiveCall(@Nullable String callId) { return callId != null && ACTIVE_CALL.compareAndSet(null, callId); }
    public static boolean claimActiveCall(@Nullable String callId) {
        return callId != null && (ACTIVE_CALL.compareAndSet(null, callId) || callId.equals(ACTIVE_CALL.get()));
    }
    public static void clearActiveCall(@Nullable String callId) {
        if (callId == null) ACTIVE_CALL.set(null); else ACTIVE_CALL.compareAndSet(callId, null);
    }

    @Override public void onCreate() {
        super.onCreate();
        presenceManager = new DevicePresenceManager(this);
        createNotificationChannel();
        registerActivityLifecycleCallbacks(this);
        try {
            auth = FirebaseAuth.getInstance();
            callRepository = CallRepositoryProvider.get(this);
            auth.addAuthStateListener(this);
        } catch (RuntimeException ignored) {
            auth = null;
        }
    }

    public DevicePresenceManager getPresenceManager() { return presenceManager; }

    @Override public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
        if (startedActivities > 0) syncSignedInSession(firebaseAuth.getCurrentUser());
    }

    private void syncSignedInSession(FirebaseUser user) {
        if (user == null) {
            presenceManager.stop();
            if (callRepository != null) callRepository.stopIncomingListener();
            return;
        }
        presenceManager.start(user.getUid());
        PushTokenRepository.refreshCurrentToken(this);
        if (callRepository != null) callRepository.listenIncoming(user.getUid(), this::onIncomingCall);
    }

    private void onIncomingCall(CallSession call) {
        if (call == null || call.id.equals(ACTIVE_CALL.get())) return;
        synchronized (deliveredCallIds) {
            if (!deliveredCallIds.add(call.id)) return;
        }
        if (hasActiveCall()) {
            callRepository.updateStatus(call.id, "busy", new CallRepository.Callback() {
                @Override public void onSuccess(CallSession ignored) { }
                @Override public void onError(String ignored) { }
            });
            return;
        }
        if (!setActiveCall(call.id)) return;
        android.content.Intent intent = CallActivity.incomingIntent(this, call);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { startActivity(intent); }
        catch (RuntimeException launchError) { clearActiveCall(call.id); }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                getString(R.string.notifications_channel_id),
                getString(R.string.notifications_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(getString(R.string.notifications_channel_description));
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    @Override public void onActivityStarted(@NonNull Activity activity) {
        startedActivities++;
        if (startedActivities == 1 && auth != null) syncSignedInSession(auth.getCurrentUser());
    }

    @Override public void onActivityStopped(@NonNull Activity activity) {
        startedActivities = Math.max(0, startedActivities - 1);
        if (startedActivities == 0) {
            presenceManager.stop();
            if (callRepository != null) callRepository.stopIncomingListener();
        }
    }

    @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
    @Override public void onActivityResumed(@NonNull Activity activity) { }
    @Override public void onActivityPaused(@NonNull Activity activity) { }
    @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
    @Override public void onActivityDestroyed(@NonNull Activity activity) { }
}
