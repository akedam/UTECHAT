package com.example.utechat.feature.profile.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.example.utechat.domain.model.User;

/**
 * ViewModel cho ProfileFragment.
 * [MOCK] Trả về người dùng mẫu.
 * TODO (G4): Inject UserRepository và lấy currentUser từ Firebase Auth.
 */
public class ProfileViewModel extends ViewModel {

    private final MutableLiveData<User> currentUser = new MutableLiveData<>();

    public ProfileViewModel() {
        loadMockUser();
    }

    public LiveData<User> getCurrentUser() {
        return currentUser;
    }

    /** [MOCK] Dữ liệu mẫu — sẽ được xóa ở G4. */
    private void loadMockUser() {
        currentUser.setValue(new User(
                "mock_uid_me",
                "[Mock] Nguyễn Văn A",
                "a@ute.edu.vn",
                null,
                "Sinh viên Đại học Sư phạm Kỹ thuật TP.HCM",
                true,
                System.currentTimeMillis()
        ));
    }
}
