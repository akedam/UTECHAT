package com.example.utechat.feature.auth.viewmodel;

import android.app.Application;
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
    private final MutableLiveData<Resource<User>> authState = new MutableLiveData<>();

    public AuthViewModel(@NonNull Application application) {
        super(application);
        repository = AuthRepositoryProvider.get(application);
    }

    public LiveData<Resource<User>> getAuthState() { return authState; }
    public User getCurrentUser() { return repository.getCurrentUser(); }

    public void signIn(String email, String password) {
        authState.setValue(Resource.loading());
        repository.signIn(email, password, callback());
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
