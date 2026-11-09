package com.example.utechat.feature.friends.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.example.utechat.domain.model.User;

import java.util.ArrayList;
import java.util.List;

/**
 * ViewModel cho FriendsFragment.
 * [MOCK] Trả về danh sách bạn bè mẫu.
 * TODO (G4): Inject FriendRepository và observe Firestore.
 */
public class FriendsViewModel extends ViewModel {

    private final MutableLiveData<List<User>> friends = new MutableLiveData<>();

    public FriendsViewModel() {
        loadMockFriends();
    }

    public LiveData<List<User>> getFriends() {
        return friends;
    }

    /** [MOCK] Dữ liệu mẫu — sẽ được xóa ở G4. */
    private void loadMockFriends() {
        List<User> list = new ArrayList<>();

        list.add(new User("mock_uid_b", "[Mock] Trần Thị B",
                "b@ute.edu.vn", null, "Sinh viên K22", true, System.currentTimeMillis()));
        list.add(new User("mock_uid_c", "[Mock] Phạm Văn C",
                "c@ute.edu.vn", null, "CNTT K21", false, System.currentTimeMillis() - 3600000));
        list.add(new User("mock_uid_d", "[Mock] Lê Thị D",
                "d@ute.edu.vn", null, "Điện tử K22", true, System.currentTimeMillis()));
        list.add(new User("mock_uid_e", "[Mock] Hoàng Văn E",
                "e@ute.edu.vn", null, "", false, System.currentTimeMillis() - 86400000));

        friends.setValue(list);
    }
}
