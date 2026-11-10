package com.example.utechat.data.calls;

import android.content.Context;
import android.view.TextureView;
import android.widget.FrameLayout;

import io.agora.rtc2.ChannelMediaOptions;
import io.agora.rtc2.Constants;
import io.agora.rtc2.IRtcEngineEventHandler;
import io.agora.rtc2.RtcEngine;
import io.agora.rtc2.RtcEngineConfig;
import io.agora.rtc2.video.VideoCanvas;

/** Owns one Agora engine and its render surfaces for the lifetime of one call screen. */
public final class AgoraCallController {
    private static final Object ENGINE_LOCK = new Object();
    private static boolean engineDestroying;
    public interface Events { void onJoined(); void onRemoteJoined(int uid); void onRemoteLeft(); void onReconnecting(); void onTokenRefresh(); void onError(); }
    private final Context viewContext;
    private final Context appContext;
    private final Events events;
    private RtcEngine engine;
    private String channel;
    private boolean video;
    private int localUid;

    public AgoraCallController(Context context, Events events) { this.viewContext = context; this.appContext = context.getApplicationContext(); this.events = events; }

    public void join(String appId, String token, String channelName, int uid, boolean video,
                     boolean speakerEnabled, FrameLayout localContainer, FrameLayout remoteContainer) throws Exception {
        this.channel = channelName; this.video = video; this.localUid = uid;
        RtcEngineConfig config = new RtcEngineConfig(); config.mContext = appContext; config.mAppId = appId;
        config.mEventHandler = new IRtcEngineEventHandler() {
            @Override public void onJoinChannelSuccess(String channel, int uid, int elapsed) { events.onJoined(); }
            @Override public void onUserJoined(int uid, int elapsed) { events.onRemoteJoined(uid); }
            @Override public void onUserOffline(int uid, int reason) { events.onRemoteLeft(); }
            @Override public void onConnectionStateChanged(int state, int reason) {
                if (state == Constants.CONNECTION_STATE_RECONNECTING) events.onReconnecting();
                if (state == Constants.CONNECTION_STATE_FAILED) events.onError();
            }
            @Override public void onError(int errorCode) { events.onError(); }
            @Override public void onTokenPrivilegeWillExpire(String token) { events.onTokenRefresh(); }
            @Override public void onRequestToken() { events.onTokenRefresh(); }
        };
        synchronized (ENGINE_LOCK) {
            while (engineDestroying) {
                try { ENGINE_LOCK.wait(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted while releasing prior call"); }
            }
            engine = RtcEngine.create(config);
        }
        engine.setChannelProfile(Constants.CHANNEL_PROFILE_COMMUNICATION);
        engine.enableAudio();
        engine.setEnableSpeakerphone(speakerEnabled);
        ChannelMediaOptions options = new ChannelMediaOptions();
        options.clientRoleType = Constants.CLIENT_ROLE_BROADCASTER;
        options.publishMicrophoneTrack = true;
        options.autoSubscribeAudio = true;
        options.autoSubscribeVideo = video;
        if (video) {
            engine.enableVideo(); engine.startPreview();
            TextureView local = new TextureView(viewContext);
            localContainer.removeAllViews(); localContainer.addView(local);
            engine.setupLocalVideo(new VideoCanvas(local, VideoCanvas.RENDER_MODE_HIDDEN, uid));
            options.publishCameraTrack = true;
            engine.setDefaultAudioRoutetoSpeakerphone(speakerEnabled);
        }
        engine.joinChannel(token, channelName, uid, options);
    }

    public void attachRemote(int uid, FrameLayout remoteContainer) {
        if (engine == null || !video) return;
        TextureView remote = new TextureView(viewContext);
        remoteContainer.removeAllViews(); remoteContainer.addView(remote);
        engine.setupRemoteVideo(new VideoCanvas(remote, VideoCanvas.RENDER_MODE_HIDDEN, uid));
    }
    public void muteMic(boolean muted) { if (engine != null) engine.muteLocalAudioStream(muted); }
    public void muteCamera(boolean muted) { if (engine != null && video) engine.enableLocalVideo(!muted); }
    public void speaker(boolean enabled) { if (engine != null) engine.setEnableSpeakerphone(enabled); }
    public void switchCamera() { if (engine != null && video) engine.switchCamera(); }
    public void renewToken(String token) { if (engine != null && token != null && !token.isEmpty()) engine.renewToken(token); }
    public void release() {
        final RtcEngine old;
        synchronized (ENGINE_LOCK) {
            old = engine; engine = null;
            if (old != null) engineDestroying = true;
        }
        if (old != null) {
            try { old.leaveChannel(); } catch (RuntimeException ignored) { }
            // Agora requires destroy off the UI thread; serialize it with the next engine create.
            new Thread(() -> {
                try { RtcEngine.destroy(); }
                finally { synchronized (ENGINE_LOCK) { engineDestroying = false; ENGINE_LOCK.notifyAll(); } }
            }, "utechat-agora-release").start();
        }
    }
}
