package com.example.utechat.feature.moments;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import java.io.File;
import java.util.concurrent.Executor;

/** Owns only the camera use cases of the Tin tab. */
final class TinCamera {
    private ProcessCameraProvider provider;
    private Preview preview;
    private ImageCapture capture;
    private Camera activeCamera;
    private boolean boundFront;
    private int revision;
    private boolean front = true;

    boolean ready() { return activeCamera != null; }
    boolean hasFlash() { return activeCamera != null && activeCamera.getCameraInfo().hasFlashUnit(); }
    void setFlash(boolean value) { if (hasFlash()) activeCamera.getCameraControl().enableTorch(value); }
    void flip() { front = !front; }

    void open(Context context, LifecycleOwner owner, PreviewView view, Runnable ready, Runnable error) {
        release();
        int version = revision;
        var future = ProcessCameraProvider.getInstance(context);
        future.addListener(() -> {
            if (revision != version) return;
            try {
                provider = future.get();
                CameraSelector lens = front ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
                if (!provider.hasCamera(lens)) {
                    lens = front ? CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA;
                    if (!provider.hasCamera(lens)) throw new IllegalStateException("Camera unavailable");
                }
                preview = new Preview.Builder().build();
                capture = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build();
                activeCamera = provider.bindToLifecycle(owner, lens, preview, capture);
                boundFront = lens == CameraSelector.DEFAULT_FRONT_CAMERA;
                preview.setSurfaceProvider(view.getSurfaceProvider());
                ready.run();
            } catch (Exception failure) {
                release();
                error.run();
            }
        }, ContextCompat.getMainExecutor(context));
    }

    void take(File target, Executor main, Runnable success, Runnable error) {
        if (capture == null) { error.run(); return; }
        var metadata = new ImageCapture.Metadata();
        metadata.setReversedHorizontal(boundFront);
        capture.takePicture(new ImageCapture.OutputFileOptions.Builder(target).setMetadata(metadata).build(), main,
                new ImageCapture.OnImageSavedCallback() {
                    @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults result) { success.run(); }
                    @Override public void onError(@NonNull ImageCaptureException failure) { error.run(); }
                });
    }

    void release() {
        revision++;
        if (provider != null && preview != null && capture != null) provider.unbind(preview, capture);
        preview = null;
        capture = null;
        activeCamera = null;
    }
}
