package com.example.utechat.feature.friends.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.utechat.R;
import com.example.utechat.feature.friends.adapter.FriendsAdapter;
import com.example.utechat.feature.friends.viewmodel.FriendsViewModel;

/**
 * Fragment hiển thị danh sách bạn bè và yêu cầu kết bạn.
 * [MOCK] Dữ liệu mẫu — kết nối Firestore ở G4.
 */
public class FriendsFragment extends Fragment {

    private FriendsViewModel viewModel;
    private FriendsAdapter adapter;
    private RecyclerView recyclerView;
    private View emptyState;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_friends, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerView = view.findViewById(R.id.rv_friends);
        emptyState = view.findViewById(R.id.empty_state);

        viewModel = new ViewModelProvider(this).get(FriendsViewModel.class);

        adapter = new FriendsAdapter(friend -> {
            // TODO (G4): Mở chat với bạn bè hoặc xem profile
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        observeViewModel();
    }

    private void observeViewModel() {
        viewModel.getFriends().observe(getViewLifecycleOwner(), friends -> {
            if (friends == null || friends.isEmpty()) {
                recyclerView.setVisibility(View.GONE);
                emptyState.setVisibility(View.VISIBLE);
            } else {
                recyclerView.setVisibility(View.VISIBLE);
                emptyState.setVisibility(View.GONE);
                adapter.submitList(friends);
            }
        });
    }
}
