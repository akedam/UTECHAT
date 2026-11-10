package com.example.utechat.feature.calls.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.utechat.BuildConfig;
import com.example.utechat.R;
import com.example.utechat.app.CallRepositoryProvider;
import com.example.utechat.app.UteChatApplication;
import com.example.utechat.core.common.AuthGate;
import com.example.utechat.domain.model.CallSession;
import com.example.utechat.domain.repository.CallRepository;
import com.example.utechat.data.calls.AgoraCallController;
import com.example.utechat.data.calls.AgoraTokenClient;
import com.example.utechat.feature.calls.service.CallForegroundService;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/** One-to-one audio/video call UI and lifecycle boundary. */
public final class CallActivity extends AppCompatActivity {
    private static final String EXTRA_MODE = "call_mode";
    private static final String EXTRA_CALL_ID = "call_id";
    private static final String EXTRA_CONVERSATION_ID = "conversation_id";
    private static final String EXTRA_TITLE = "peer_title";
    private static final String EXTRA_MEDIA = "media_type";
    private static final long FINISH_DELAY_MS = 1300L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<String[]> permissionRequest;
    private Runnable timeout;
    private CallRepository repository;
    private CallSession call;
    private AgoraCallController rtc;
    private String mediaType;
    private String peerTitle;
    private String conversationId;
    private String callId;
    private boolean incoming;
    private boolean startingMedia;
    private boolean connected;
    private boolean micMuted;
    private boolean cameraMuted;
    private boolean speakerEnabled;
    private boolean endingRequested;

    public static Intent outgoingIntent(Context context, String conversationId, String title, String mediaType) {
        return new Intent(context, CallActivity.class).putExtra(EXTRA_MODE, "outgoing")
                .putExtra(EXTRA_CONVERSATION_ID, conversationId).putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_MEDIA, mediaType);
    }
    public static Intent incomingIntent(Context context, CallSession call) {
        return new Intent(context, CallActivity.class).putExtra(EXTRA_MODE, "incoming")
                .putExtra(EXTRA_CALL_ID, call.id).putExtra(EXTRA_CONVERSATION_ID, call.conversationId)
                .putExtra(EXTRA_TITLE, context.getString(R.string.call_generic_peer)).putExtra(EXTRA_MEDIA, call.mediaType);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (AuthGate.redirectIfSignedOut(this)) return;
        permissionRequest = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            if (isFinishing() || isDestroyed()) return;
            boolean granted = hasPermission(Manifest.permission.RECORD_AUDIO)
                    && (!"video".equals(mediaType) || hasPermission(Manifest.permission.CAMERA));
            if (!granted) {
                showError(R.string.call_permission_denied);
                setStatus(R.string.call_state_failed);
                if (incoming) findViewById(R.id.incoming_actions).setVisibility(View.VISIBLE);
                else finish();
                return;
            }
            if (incoming) acceptCall(); else createOutgoing();
        });
        timeout = this::handleTimeout;
        setContentView(R.layout.activity_call);
        repository = CallRepositoryProvider.get(this);
        incoming = "incoming".equals(getIntent().getStringExtra(EXTRA_MODE));
        callId = getIntent().getStringExtra(EXTRA_CALL_ID);
        mediaType = getIntent().getStringExtra(EXTRA_MEDIA);
        if (!"video".equals(mediaType)) mediaType = "audio";
        peerTitle = getIntent().getStringExtra(EXTRA_TITLE);
        conversationId = getIntent().getStringExtra(EXTRA_CONVERSATION_ID);
        if (!UteChatApplication.claimActiveCall(callId == null ? "pending-outgoing" : callId)) {
            Toast.makeText(this, R.string.call_state_busy, Toast.LENGTH_LONG).show(); finish(); return;
        }
        ((TextView) findViewById(R.id.tv_call_title)).setText(getString(
                "video".equals(mediaType) ? R.string.call_video_title : R.string.call_audio_title));
        ((TextView) findViewById(R.id.tv_call_peer)).setText(peerTitle == null || peerTitle.trim().isEmpty()
                ? getString(R.string.call_generic_peer) : peerTitle);
        findViewById(R.id.btn_accept_call).setOnClickListener(v -> requestMediaPermissions());
        findViewById(R.id.btn_reject_call).setOnClickListener(v -> rejectCall());
        findViewById(R.id.btn_end_call).setOnClickListener(v -> endCall());
        findViewById(R.id.btn_toggle_mic).setOnClickListener(v -> toggleMic());
        findViewById(R.id.btn_toggle_camera).setOnClickListener(v -> toggleCamera());
        findViewById(R.id.btn_toggle_speaker).setOnClickListener(v -> toggleSpeaker());
        findViewById(R.id.btn_switch_camera).setOnClickListener(v -> { if (rtc != null) rtc.switchCamera(); });
        findViewById(R.id.btn_toggle_camera).setVisibility("video".equals(mediaType) ? View.VISIBLE : View.GONE);
        findViewById(R.id.btn_switch_camera).setVisibility("video".equals(mediaType) ? View.VISIBLE : View.GONE);
        findViewById(R.id.tv_call_title).setContentDescription(getString(
                incoming ? ("video".equals(mediaType) ? R.string.call_incoming_video : R.string.call_incoming_audio)
                        : ("video".equals(mediaType) ? R.string.call_video_title : R.string.call_audio_title)));
        if (incoming) {
            if (callId == null) { showError(R.string.call_network_error); finish(); return; }
            findViewById(R.id.incoming_actions).setVisibility(View.VISIBLE);
            setStatus(R.string.call_state_incoming);
            observeCall(callId);
        } else {
            if (conversationId == null || conversationId.trim().isEmpty()) { showError(R.string.call_invalid_conversation); finish(); return; }
            requestMediaPermissions();
        }
    }

    private void handleTimeout() {
        if (call != null && "ringing".equals(call.status)) {
            setStatus(R.string.call_state_missed);
            repository.updateStatus(call.id, incoming ? "missed" : "ended", silentCallback());
            finishSoon();
        }
    }

    private void requestMediaPermissions() {
        if (hasPermission(Manifest.permission.RECORD_AUDIO)
                && (!"video".equals(mediaType) || hasPermission(Manifest.permission.CAMERA))) {
            if (incoming) acceptCall(); else createOutgoing();
            return;
        }
        findViewById(R.id.incoming_actions).setVisibility(View.GONE);
        String[] needed = "video".equals(mediaType)
                ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}
                : new String[]{Manifest.permission.RECORD_AUDIO};
        boolean explain = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                || ("video".equals(mediaType) && shouldShowRequestPermissionRationale(Manifest.permission.CAMERA));
        if (!explain) { permissionRequest.launch(needed); return; }
        int title = "video".equals(mediaType) ? R.string.call_permission_camera_title : R.string.call_permission_mic_title;
        int message = "video".equals(mediaType) ? R.string.call_permission_video_message : R.string.call_permission_mic_message;
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    if (incoming) findViewById(R.id.incoming_actions).setVisibility(View.VISIBLE); else finish();
                })
                .setPositiveButton(R.string.call_accept, (dialog, which) -> permissionRequest.launch(needed)).show();
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void createOutgoing() {
        setStatus(R.string.call_state_preparing); showProgress(true);
        repository.createOutgoing(conversationId, mediaType, new CallRepository.Callback() {
            @Override public void onSuccess(CallSession created) {
                if (isFinishing() || isDestroyed()) {
                    repository.updateStatus(created.id, "ended", silentCallback());
                    return;
                }
                showProgress(false); call = created; callId = created.id;
                UteChatApplication.clearActiveCall("pending-outgoing");
                UteChatApplication.setActiveCall(callId);
                observeCall(callId); setStatus(R.string.call_state_ringing);
                findViewById(R.id.btn_end_call).setVisibility(View.VISIBLE);
                handler.postDelayed(timeout, Math.max(1, created.expiresAt - System.currentTimeMillis()));
            }
            @Override public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                showProgress(false); showError(R.string.call_network_error); setStatus(R.string.call_state_failed); finishSoon();
            }
        });
    }

    private void observeCall(String id) {
        repository.listen(id, (updated, error) -> {
            if (isFinishing() || isDestroyed()) return;
            if (error != null) { showError(R.string.call_network_error); return; }
            if (updated == null) { showError(R.string.call_network_error); return; }
            call = updated; mediaType = updated.mediaType;
            if ("ringing".equals(updated.status)) {
                if (incoming) { findViewById(R.id.incoming_actions).setVisibility(View.VISIBLE); setStatus(R.string.call_state_incoming); }
                else { findViewById(R.id.btn_end_call).setVisibility(View.VISIBLE); setStatus(R.string.call_state_ringing); }
                long remaining = updated.expiresAt - System.currentTimeMillis();
                if (remaining <= 0) timeout.run(); else { handler.removeCallbacks(timeout); handler.postDelayed(timeout, remaining); }
            } else if ("accepted".equals(updated.status)) {
                handler.removeCallbacks(timeout); findViewById(R.id.incoming_actions).setVisibility(View.GONE);
                findViewById(R.id.in_call_actions).setVisibility(View.VISIBLE);
                findViewById(R.id.btn_end_call).setVisibility(View.VISIBLE);
                setStatus(connected ? R.string.call_state_connected : R.string.call_state_connecting);
                startMediaOnce();
            } else if ("rejected".equals(updated.status)) { setStatus(R.string.call_state_rejected); finishSoon(); }
            else if ("busy".equals(updated.status)) { setStatus(R.string.call_state_busy); finishSoon(); }
            else if ("missed".equals(updated.status)) { setStatus(R.string.call_state_missed); finishSoon(); }
            else if ("ended".equals(updated.status) || "failed".equals(updated.status)) {
                setStatus("ended".equals(updated.status) ? R.string.call_state_ended : R.string.call_state_failed); finishSoon();
            }
        });
    }

    private void acceptCall() {
        if (callId == null) return;
        findViewById(R.id.incoming_actions).setVisibility(View.GONE); showProgress(true);
        repository.updateStatus(callId, "accepted", new CallRepository.Callback() {
            @Override public void onSuccess(CallSession ignored) { if (!isFinishing() && !isDestroyed()) showProgress(false); }
            @Override public void onError(String error) {
                if (isFinishing() || isDestroyed()) return;
                showProgress(false); showError(R.string.call_network_error); findViewById(R.id.incoming_actions).setVisibility(View.VISIBLE);
            }
        });
    }
    private void rejectCall() {
        if (endingRequested) return;
        if (callId == null) { finish(); return; }
        endingRequested = true;
        findViewById(R.id.btn_reject_call).setEnabled(false);
        repository.updateStatus(callId, "rejected", new CallRepository.Callback() {
            @Override public void onSuccess(CallSession ignored) { if (!isFinishing() && !isDestroyed()) { setStatus(R.string.call_state_rejected); finishSoon(); } }
            @Override public void onError(String error) {
                endingRequested = false;
                if (!isFinishing() && !isDestroyed()) {
                    findViewById(R.id.btn_reject_call).setEnabled(true);
                    showError(R.string.call_network_error);
                }
            }
        });
    }
    private void endCall() {
        if (endingRequested) return;
        if (call == null) { finish(); return; }
        endingRequested = true;
        findViewById(R.id.btn_end_call).setEnabled(false);
        repository.updateStatus(call.id, "ended", new CallRepository.Callback() {
            @Override public void onSuccess(CallSession ignored) { if (!isFinishing() && !isDestroyed()) { setStatus(R.string.call_state_ended); finishSoon(); } }
            @Override public void onError(String error) {
                endingRequested = false;
                if (!isFinishing() && !isDestroyed()) {
                    findViewById(R.id.btn_end_call).setEnabled(true);
                    showError(R.string.call_network_error);
                }
            }
        });
    }

    private void startMediaOnce() {
        if (startingMedia || connected || endingRequested || call == null) return;
        if (!hasPermission(Manifest.permission.RECORD_AUDIO) || ("video".equals(call.mediaType) && !hasPermission(Manifest.permission.CAMERA))) {
            showError(R.string.call_permission_denied); endCall(); return;
        }
        if (BuildConfig.AGORA_APP_ID.trim().isEmpty() || BuildConfig.AGORA_TOKEN_SERVER_URL.trim().isEmpty()) {
            showError(R.string.call_service_unconfigured); repository.updateStatus(call.id, "failed", silentCallback()); return;
        }
        startingMedia = true; showProgress(true); setStatus(R.string.call_state_connecting);
        speakerEnabled = "video".equals(call.mediaType);
        try { androidx.core.content.ContextCompat.startForegroundService(this,
                CallForegroundService.startIntent(this, "video".equals(call.mediaType))); }
        catch (RuntimeException serviceError) { startingMedia = false; showProgress(false); showError(R.string.call_network_error); repository.updateStatus(call.id, "failed", silentCallback()); return; }
        CallSession callSnapshot = call;
        new AgoraTokenClient().fetch(callSnapshot, new AgoraTokenClient.Callback() {
            @Override public void onToken(String appId, String token, String channel, int uid) {
                if (isFinishing() || isDestroyed() || endingRequested || call == null || !call.id.equals(callSnapshot.id)) return;
                try {
                    FrameLayout local = findViewById(R.id.local_video_container);
                    FrameLayout remote = findViewById(R.id.remote_video_container);
                    rtc = new AgoraCallController(CallActivity.this, new AgoraCallController.Events() {
                        @Override public void onJoined() { runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed() || endingRequested) return;
                            showProgress(false); setStatus(R.string.call_state_connected);
                        }); }
                        @Override public void onRemoteJoined(int remoteUid) { runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed() || endingRequested) return;
                            connected = true;
                            if ("video".equals(callSnapshot.mediaType)) {
                                findViewById(R.id.audio_peer_state).setVisibility(View.GONE);
                                remote.setVisibility(View.VISIBLE); local.setVisibility(View.VISIBLE);
                                rtc.attachRemote(remoteUid, remote);
                            }
                        }); }
                        @Override public void onRemoteLeft() { runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            findViewById(R.id.audio_peer_state).setVisibility(View.VISIBLE);
                            ((TextView) findViewById(R.id.tv_call_status)).setText(R.string.call_state_connecting);
                        }); }
                        @Override public void onReconnecting() { runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed()) setStatus(R.string.call_state_connecting);
                        }); }
                        @Override public void onTokenRefresh() { runOnUiThread(() -> refreshRtcToken(callSnapshot)); }
                        @Override public void onError() { runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed()) { showProgress(false); showError(R.string.call_network_error); }
                        }); }
                    });
                    rtc.join(appId, token, channel, uid, "video".equals(callSnapshot.mediaType), speakerEnabled, local, remote);
                } catch (Exception error) {
                    startingMedia = false; showProgress(false); showError(R.string.call_network_error);
                    repository.updateStatus(callSnapshot.id, "failed", silentCallback());
                }
            }
            @Override public void onError() { startingMedia = false; showProgress(false); showError(R.string.call_service_unconfigured); repository.updateStatus(callSnapshot.id, "failed", silentCallback()); }
        });
    }

    private void refreshRtcToken(CallSession callSnapshot) {
        if (rtc == null || call == null || endingRequested || isFinishing() || isDestroyed()
                || !call.id.equals(callSnapshot.id)) return;
        new AgoraTokenClient().fetch(callSnapshot, new AgoraTokenClient.Callback() {
            @Override public void onToken(String appId, String token, String channel, int uid) {
                if (rtc != null && call != null && call.id.equals(callSnapshot.id)
                        && callSnapshot.channelName.equals(channel)) rtc.renewToken(token);
            }
            @Override public void onError() { if (!isFinishing() && !isDestroyed()) showError(R.string.call_network_error); }
        });
    }

    private void toggleMic() {
        micMuted = !micMuted; if (rtc != null) rtc.muteMic(micMuted);
        int label = micMuted ? R.string.call_mic_off : R.string.call_mic_on;
        findViewById(R.id.btn_toggle_mic).setContentDescription(getString(label));
        findViewById(R.id.btn_toggle_mic).setSelected(micMuted);
    }
    private void toggleCamera() {
        cameraMuted = !cameraMuted; if (rtc != null) rtc.muteCamera(cameraMuted);
        int label = cameraMuted ? R.string.call_camera_off : R.string.call_camera_on;
        findViewById(R.id.btn_toggle_camera).setContentDescription(getString(label));
        findViewById(R.id.btn_toggle_camera).setSelected(cameraMuted);
    }
    private void toggleSpeaker() {
        speakerEnabled = !speakerEnabled; if (rtc != null) rtc.speaker(speakerEnabled);
        int label = speakerEnabled ? R.string.call_speaker_on : R.string.call_speaker_off;
        findViewById(R.id.btn_toggle_speaker).setContentDescription(getString(label));
        findViewById(R.id.btn_toggle_speaker).setSelected(speakerEnabled);
    }
    private void showError(int textId) { TextView error = findViewById(R.id.tv_call_error); error.setText(textId); error.setVisibility(View.VISIBLE); }
    private void setStatus(int textId) { ((TextView) findViewById(R.id.tv_call_status)).setText(textId); }
    private void showProgress(boolean visible) { findViewById(R.id.progress_call).setVisibility(visible ? View.VISIBLE : View.GONE); }
    private void finishSoon() { handler.postDelayed(this::finish, FINISH_DELAY_MS); }
    private CallRepository.Callback silentCallback() { return new CallRepository.Callback() {
        @Override public void onSuccess(CallSession ignored) { }
        @Override public void onError(String ignored) { }
    }; }

    @Override public void onBackPressed() {
        if (incoming && call != null && "ringing".equals(call.status)) rejectCall(); else endCall();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (isFinishing() && call != null && !endingRequested
                && ("ringing".equals(call.status) || "accepted".equals(call.status))) {
            repository.updateStatus(call.id, "accepted".equals(call.status) ? "ended" : (incoming ? "missed" : "ended"), silentCallback());
        }
        if (repository != null && callId != null) repository.removeListener(callId);
        if (rtc != null) { rtc.release(); rtc = null; }
        try { stopService(new Intent(this, CallForegroundService.class)); }
        catch (RuntimeException ignored) { }
        UteChatApplication.clearActiveCall(callId == null ? "pending-outgoing" : callId);
        super.onDestroy();
    }
}
