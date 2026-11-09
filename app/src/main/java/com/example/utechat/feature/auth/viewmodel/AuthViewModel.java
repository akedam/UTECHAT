package com.example.utechat.feature.auth.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.example.utechat.core.common.Resource;
import com.example.utechat.domain.model.User;

/**
 * ViewModel cho màn hình xác thực (Login, Register).
 *
 * Hiện tại cung cấp mock methods để test UI mà không cần Firebase.
 * TODO (G3): Inject AuthRepository và gọi Firebase Auth thật.
 */
public class AuthViewModel extends ViewModel {

    private final MutableLiveData<Resource<User>> authState = new MutableLiveData<>();

    public LiveData<Resource<User>> getAuthState() {
        return authState;
    }

    /**
     * [MOCK] Giả lập đăng nhập thành công sau 800ms.
     * Thay bằng repository.login(email, password) ở G3.
     */
    public void mockLogin(String email, String password) {
        authState.setValue(Resource.loading());

        // Giả lập delay network
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            User mockUser = new User(
                    "mock_uid_001",
                    "Nguyễn Văn A",
                    email,
                    null,
                    "Sinh viên UTE",
                    true,
                    System.currentTimeMillis()
            );
            authState.setValue(Resource.success(mockUser));
        }, 800);
    }

    /**
     * [MOCK] Giả lập đăng ký thành công sau 1000ms.
     * Thay bằng repository.register(name, email, password) ở G3.
     */
    public void mockRegister(String name, String email, String password) {
        authState.setValue(Resource.loading());

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            User mockUser = new User(
                    "mock_uid_new",
                    name,
                    email,
                    null,
                    "",
                    true,
                    System.currentTimeMillis()
            );
            authState.setValue(Resource.success(mockUser));
        }, 1000);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        // TODO (G3): Hủy Firestore listeners nếu có
    }
}
