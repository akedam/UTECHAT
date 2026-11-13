package com.example.utechat.data.calls;

import android.content.Context;
import android.view.TextureView;
import android.widget.FrameLayout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.agora.rtc2.ChannelMediaOptions;
import io.agora.rtc2.Constants;
import io.agora.rtc2.IRtcEngineEventHandler;
import io.agora.rtc2.RtcEngine;
import io.agora.rtc2.RtcEngineConfig;
import io.agora.rtc2.video.VideoCanvas;

/** Keeps blocking SDK calls off the UI thread and serializes access to Agora's singleton. */
public final class AgoraCallController {
    private static final ExecutorService RTC = Executors.newSingleThreadExecutor();
    private static AgoraCallController engineOwner;

    public interface Events {
        void onJoined();
        void onRemoteJoined(int uid);
        void onRemoteLeft();
        void onReconnecting();
        void onTokenRefresh();
        void onError();
        default void onRemoteVideoAvailable(boolean available) { }
    }

    private final Context appContext;
    private final Events events;
    private volatile boolean released;
    private RtcEngine engine;
    private boolean previewStarted;
    private boolean localVideoAttached;
    private boolean joined;
    private TextureView localView;

    public AgoraCallController(Context context, Events events) {
        appContext = context.getApplicationContext();
        this.events = events;
    }

    public void join(String appId, String token, String channelName, int uid, boolean video,
                     boolean speakerEnabled, boolean micMuted, boolean cameraMuted,
                     FrameLayout localContainer) {
        TextureView local = video ? localSurface(localContainer) : null;
        run(() -> {
            ensureEngine(appId);
            engine.enableAudio();
            engine.muteLocalAudioStream(micMuted);
            engine.setEnableSpeakerphone(speakerEnabled);
            if (video) {
                prepareVideo(local);
                engine.enableLocalVideo(!cameraMuted);
                engine.muteLocalVideoStream(cameraMuted);
            }

            ChannelMediaOptions options = new ChannelMediaOptions();
            options.clientRoleType = Constants.CLIENT_ROLE_BROADCASTER;
            options.publishMicrophoneTrack = true;
            options.autoSubscribeAudio = true;
            options.autoSubscribeVideo = video;
            options.publishCameraTrack = video;
            check(engine.joinChannel(token, channelName, uid, options), "joinChannel");
            joined = true;
        });
    }

    /** Starts local preview at most once, and never restarts it while joining the call. */
    public void previewLocal(String appId, FrameLayout container) {
        TextureView local = localSurface(container);
        run(() -> {
            ensureEngine(appId);
            prepareVideo(local);
            engine.enableLocalVideo(true);
            if (!joined && !previewStarted) {
                check(engine.startPreview(), "startPreview");
                previewStarted = true;
            }
        });
    }

    private TextureView localSurface(FrameLayout container) {
        if (localView == null) {
            localView = new TextureView(container.getContext());
            container.removeAllViews();
            container.addView(localView);
        }
        return localView;
    }

    private void prepareVideo(TextureView local) {
        if (localVideoAttached) return;
        check(engine.enableVideo(), "enableVideo");
        check(engine.setupLocalVideo(new VideoCanvas(local, VideoCanvas.RENDER_MODE_HIDDEN, 0)),
                "setupLocalVideo");
        localVideoAttached = true;
    }

    private void ensureEngine(String appId) throws Exception {
        if (engine != null) return;
        if (engineOwner != null) throw new IllegalStateException("An Agora call is already active");
        RtcEngineConfig config = new RtcEngineConfig();
        config.mContext = appContext;
        config.mAppId = appId;
        config.mEventHandler = new IRtcEngineEventHandler() {
            @Override public void onJoinChannelSuccess(String channel, int uid, int elapsed) {
                if (!released) events.onJoined();
            }
            @Override public void onRejoinChannelSuccess(String channel, int uid, int elapsed) {
                if (!released) events.onJoined();
            }
            @Override public void onUserJoined(int uid, int elapsed) {
                if (!released) events.onRemoteJoined(uid);
            }
            @Override public void onUserOffline(int uid, int reason) {
                if (!released) events.onRemoteLeft();
            }
            @Override public void onRemoteVideoStateChanged(int uid, int state, int reason, int elapsed) {
                if (released) return;
                if (state == Constants.REMOTE_VIDEO_STATE_DECODING) events.onRemoteVideoAvailable(true);
                else if (state == Constants.REMOTE_VIDEO_STATE_STOPPED || state == Constants.REMOTE_VIDEO_STATE_FAILED)
                    events.onRemoteVideoAvailable(false);
            }
            @Override public void onConnectionStateChanged(int state, int reason) {
                if (released) return;
                if (state == Constants.CONNECTION_STATE_RECONNECTING) events.onReconnecting();
                if (state == Constants.CONNECTION_STATE_FAILED) events.onError();
            }
            @Override public void onError(int code) {
                if (com.example.utechat.BuildConfig.DEBUG) android.util.Log.w("UTEChatRTC", "RTC error=" + code);
                if (!released) events.onError();
            }
            @Override public void onTokenPrivilegeWillExpire(String token) {
                if (!released) events.onTokenRefresh();
            }
            @Override public void onRequestToken() {
                if (!released) events.onTokenRefresh();
            }
        };
        engine = RtcEngine.create(config);
        engineOwner = this;
        check(engine.setChannelProfile(Constants.CHANNEL_PROFILE_COMMUNICATION), "setChannelProfile");
    }

    public void attachRemote(int uid, FrameLayout container) {
        TextureView remote = new TextureView(container.getContext());
        container.removeAllViews();
        container.addView(remote);
        run(() -> {
            if (engine != null) check(engine.setupRemoteVideo(
                    new VideoCanvas(remote, VideoCanvas.RENDER_MODE_HIDDEN, uid)), "setupRemoteVideo");
        });
    }

    public void muteMic(boolean muted) {
        run(() -> { if (engine != null) engine.muteLocalAudioStream(muted); });
    }

    public void muteCamera(boolean muted) {
        run(() -> {
            if (engine != null) {
                engine.enableLocalVideo(!muted);
                engine.muteLocalVideoStream(muted);
            }
        });
    }

    public void speaker(boolean enabled) {
        run(() -> { if (engine != null) engine.setEnableSpeakerphone(enabled); });
    }

    public void switchCamera() {
        run(() -> { if (engine != null) engine.switchCamera(); });
    }

    public void renewToken(String token) {
        if (token == null || token.isEmpty()) return;
        run(() -> { if (engine != null) engine.renewToken(token); });
    }

    public void release() {
        if (released) return;
        released = true;
        localView = null;
        RTC.execute(this::closeEngine);
    }

    private void closeEngine() {
        if (engine == null) return;
        try {
            if (joined) engine.leaveChannel();
        } finally {
            try {
                if (previewStarted) engine.stopPreview();
            } finally {
                engine = null;
                engineOwner = null;
                RtcEngine.destroy();
            }
        }
    }

    private void run(RtcAction action) {
        RTC.execute(() -> {
            if (released) return;
            try {
                action.run();
            } catch (Exception error) {
                if (com.example.utechat.BuildConfig.DEBUG)
                    android.util.Log.w("UTEChatRTC", "Agora operation failed", error);
                if (!released) events.onError();
            }
        });
    }

    private static void check(int result, String operation) {
        if (result < 0) throw new IllegalStateException(operation + " failed: " + result);
    }

    private interface RtcAction {
        void run() throws Exception;
    }
}
