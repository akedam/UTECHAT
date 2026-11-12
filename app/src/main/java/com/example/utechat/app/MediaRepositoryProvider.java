package com.example.utechat.app;

import android.content.Context;
import com.example.utechat.R;
import com.example.utechat.BuildConfig;
import com.example.utechat.data.media.BackendMediaRepository;
import com.example.utechat.data.media.FirebaseMediaRepository;
import com.example.utechat.data.media.UnavailableMediaRepository;
import com.example.utechat.domain.repository.MediaRepository;
import com.google.firebase.FirebaseApp;

public final class MediaRepositoryProvider {
    private static MediaRepository instance;
    private MediaRepositoryProvider() {}
    public static synchronized MediaRepository get(Context context) {
        if (instance == null) {
            Context app = context.getApplicationContext();
            FirebaseApp firebase = FirebaseApp.getApps(app).isEmpty() ? FirebaseApp.initializeApp(app) : FirebaseApp.getInstance();
            if (firebase != null && BuildConfig.USE_BACKEND_MEDIA) {
                instance = new BackendMediaRepository(app);
            } else if (firebase == null || firebase.getOptions().getStorageBucket() == null) {
                instance = new UnavailableMediaRepository(app.getString(R.string.image_storage_setup_error));
            } else {
                try { instance = new FirebaseMediaRepository(app); }
                catch (RuntimeException error) { instance = new UnavailableMediaRepository(app.getString(R.string.image_storage_setup_error)); }
            }
        }
        return instance;
    }
}
