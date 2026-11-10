package com.example.utechat.app;

import android.content.Context;
import com.example.utechat.data.calls.FirebaseCallRepository;
import com.example.utechat.domain.repository.CallRepository;

public final class CallRepositoryProvider {
    private static volatile CallRepository instance;
    private CallRepositoryProvider() { }
    public static CallRepository get(Context context) {
        if (instance == null) synchronized (CallRepositoryProvider.class) {
            if (instance == null) instance = new FirebaseCallRepository(context.getApplicationContext());
        }
        return instance;
    }
}
