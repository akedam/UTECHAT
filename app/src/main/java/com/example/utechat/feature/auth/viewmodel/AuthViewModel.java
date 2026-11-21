package com.example.utechat.feature.auth.viewmodel;

import android.app.Application;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.example.utechat.core.common.Resource;
import com.example.utechat.data.auth.AuthRepositoryProvider;
import com.example.utechat.domain.model.User;
import com.example.utechat.domain.repository.AuthRepository;

/** Owns auth form state; repository implementation is selected in one provider. */
public class AuthViewModel extends AndroidViewModel {
    private final AuthRepository repository;
    private final SharedPreferences loginAttempts;
    private final MutableLiveData<Resource<User>> authState = new MutableLiveData<>();

    public AuthViewModel(@NonNull Application application) {
        super(application);
        repository = AuthRepositoryProvider.get(application);
        loginAttempts = application.getSharedPreferences("login_attempts", Application.MODE_PRIVATE);
    }

    public LiveData<Resource<User>> getAuthState() { return authState; }
    public User getCurrentUser() { return repository.getCurrentUser(); }

    public void signIn(String email, String password) {
        Resource<User> current = authState.getValue();
        if ((current != null && current.isLoading()) || loginWaitMillis() > 0) return;
        authState.setValue(Resource.loading());
        repository.signIn(email, password, new AuthRepository.Callback<User>() {
            @Override public void onSuccess(User user) {
                loginAttempts.edit().clear().apply();
                authState.postValue(Resource.success(user));
            }

            @Override public void onError(AuthRepository.AuthException error) {
                LoginThrottle previous = loginThrottle();
                LoginThrottle updated = previous.afterFailure(error.getReason(), System.currentTimeMillis());
                if (updated != previous) {
                    loginAttempts.edit().putInt("failures", updated.failures)
                            .putLong("last_failure", updated.lastFailure).putLong("try_after", updated.tryAfter)
                            .putBoolean("firebase_limited", updated.firebaseLimited).apply();
                }
                authState.postValue(Resource.error(error.getMessage()));
            }
        });
    }

    public long loginWaitMillis() { return loginThrottle().waitMillis(System.currentTimeMillis()); }

    public boolean isFirebaseLoginLimited() { return loginAttempts.getBoolean("firebase_limited", false); }

    private LoginThrottle loginThrottle() {
        return new LoginThrottle(loginAttempts.getInt("failures", 0), loginAttempts.getLong("last_failure", 0),
                loginAttempts.getLong("try_after", 0), isFirebaseLoginLimited());
    }
    public void register(String name, String email, String password) {
        authState.setValue(Resource.loading());
        repository.register(name, email, password, callback());
    }

    private AuthRepository.Callback<User> callback() {
        return new AuthRepository.Callback<User>() {
            @Override public void onSuccess(User user) { authState.postValue(Resource.success(user)); }
            @Override public void onError(AuthRepository.AuthException error) {
                authState.postValue(Resource.error(error.getMessage()));
            }
        };
    }

    @Override protected void onCleared() {
        super.onCleared();
    }
}
