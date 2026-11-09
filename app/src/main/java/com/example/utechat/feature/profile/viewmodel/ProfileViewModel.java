package com.example.utechat.feature.profile.viewmodel;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.example.utechat.data.auth.AuthRepositoryProvider;
import com.example.utechat.domain.model.User;

/** Loads the current user from the auth/profile repository. */
public class ProfileViewModel extends AndroidViewModel {
    private final MutableLiveData<User> currentUser = new MutableLiveData<>();
    private final MutableLiveData<String> profileError = new MutableLiveData<>();

    public ProfileViewModel(@NonNull Application application) {
        super(application);
        refresh();
    }

    public LiveData<User> getCurrentUser() { return currentUser; }
    public LiveData<String> getProfileError() { return profileError; }

    public void refresh() {
        currentUser.setValue(AuthRepositoryProvider.get(getApplication()).getCurrentUser());
        AuthRepositoryProvider.get(getApplication()).loadCurrentUser(new com.example.utechat.domain.repository.AuthRepository.Callback<User>() {
            @Override public void onSuccess(User user) { currentUser.postValue(user); profileError.postValue(null); }
            @Override public void onError(com.example.utechat.domain.repository.AuthRepository.AuthException error) {
                profileError.postValue(error.getMessage());
            }
        });
    }
}
