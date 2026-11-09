package com.example.utechat.feature.conversations.ui;

import android.content.Intent;
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
import com.example.utechat.feature.chat.ui.ChatActivity;
import com.example.utechat.feature.conversations.adapter.ConversationsAdapter;
import com.example.utechat.feature.conversations.viewmodel.ConversationsViewModel;

/**
 * Fragment hiển thị danh sách hội thoại (1-1 và nhóm).
 * Dữ liệu hiện tại là mock — sẽ kết nối Firestore ở G3.
 */
public class ConversationsFragment extends Fragment {

    private ConversationsViewModel viewModel;
    private ConversationsAdapter adapter;
    private RecyclerView recyclerView;
    private View emptyState;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_conversations, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerView = view.findViewById(R.id.rv_conversations);
        emptyState = view.findViewById(R.id.empty_state);

        viewModel = new ViewModelProvider(this).get(ConversationsViewModel.class);

        adapter = new ConversationsAdapter(conversation -> {
            Intent intent = new Intent(requireContext(), ChatActivity.class);
            intent.putExtra(com.example.utechat.core.common.Constants.EXTRA_CONVERSATION_ID,
                    conversation.getId());
            intent.putExtra(com.example.utechat.core.common.Constants.EXTRA_CONVERSATION_TITLE,
                    conversation.getTitle());
            intent.putExtra(com.example.utechat.core.common.Constants.EXTRA_CONVERSATION_TYPE,
                    conversation.isGroup() ? "group" : "direct");
            startActivity(intent);
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        observeViewModel();
    }

    private void observeViewModel() {
        viewModel.getConversations().observe(getViewLifecycleOwner(), conversations -> {
            if (conversations == null || conversations.isEmpty()) {
                recyclerView.setVisibility(View.GONE);
                emptyState.setVisibility(View.VISIBLE);
            } else {
                recyclerView.setVisibility(View.VISIBLE);
                emptyState.setVisibility(View.GONE);
                adapter.submitList(conversations);
            }
        });
    }
}
