package com.example.utechat.feature.conversations.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.utechat.R;
import com.example.utechat.core.common.Constants;
import com.example.utechat.core.common.Resource;
import com.example.utechat.domain.model.Conversation;
import com.example.utechat.feature.chat.ui.ChatActivity;
import com.example.utechat.feature.conversations.adapter.ConversationsAdapter;
import com.example.utechat.feature.conversations.viewmodel.ConversationsViewModel;

import java.util.ArrayList;

public class ConversationsFragment extends Fragment {
    private ConversationsViewModel viewModel;
    private ConversationsAdapter adapter;
    private RecyclerView recyclerView;
    private View emptyState;
    private View errorState;
    private View loadMoreState;
    private ProgressBar initialProgress;
    private ProgressBar loadMoreProgress;
    private TextView errorMessage;
    private TextView pagingErrorMessage;

    @Override
    public void onResume() {
        super.onResume();
        if (viewModel != null) viewModel.refresh();
    }

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
        errorState = view.findViewById(R.id.error_state);
        loadMoreState = view.findViewById(R.id.load_more_state);
        initialProgress = view.findViewById(R.id.progress_conversations);
        loadMoreProgress = view.findViewById(R.id.progress_load_more);
        errorMessage = view.findViewById(R.id.tv_conversations_error);
        pagingErrorMessage = view.findViewById(R.id.tv_paging_error);
        TextView mockNotice = view.findViewById(R.id.tv_conversations_source);

        viewModel = new ViewModelProvider(this).get(ConversationsViewModel.class);
        mockNotice.setVisibility(viewModel.isMock() ? View.VISIBLE : View.GONE);

        adapter = new ConversationsAdapter(conversation -> {
            Intent intent = new Intent(requireContext(), ChatActivity.class);
            intent.putExtra(Constants.EXTRA_CONVERSATION_ID, conversation.getId());
            intent.putExtra(Constants.EXTRA_CONVERSATION_TITLE, conversation.getTitle());
            intent.putExtra(Constants.EXTRA_CONVERSATION_TYPE, conversation.isGroup() ? "group" : "direct");
            intent.putExtra(Constants.EXTRA_CONVERSATION_PHOTO, conversation.getPhotoUrl());
            startActivity(intent);
        });
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView list, int dx, int dy) {
                super.onScrolled(list, dx, dy);
                if (dy <= 0) return;
                RecyclerView.LayoutManager manager = list.getLayoutManager();
                if (manager instanceof LinearLayoutManager) {
                    int last = ((LinearLayoutManager) manager).findLastVisibleItemPosition();
                    if (adapter.getItemCount() > 0 && last >= adapter.getItemCount() - 5) {
                        viewModel.loadNextPage();
                    }
                }
            }
        });

        Button retry = view.findViewById(R.id.btn_retry_conversations);
        retry.setOnClickListener(v -> viewModel.retry());
        Button retryMore = view.findViewById(R.id.btn_retry_page);
        retryMore.setOnClickListener(v -> viewModel.retry());
        observeViewModel();
    }

    private void observeViewModel() {
        viewModel.getConversations().observe(getViewLifecycleOwner(), result -> {
            if (result == null || result.isLoading()) {
                showInitialLoading();
                return;
            }
            if (result.isError()) {
                showInitialError(result.getMessage());
                return;
            }
            ArrayList<Conversation> items = new ArrayList<>(result.getData() == null
                    ? java.util.Collections.emptyList() : result.getData());
            adapter.submitList(items);
            boolean isEmpty = items.isEmpty();
            recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
            emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
            errorState.setVisibility(View.GONE);
            initialProgress.setVisibility(View.GONE);
        });

        viewModel.getLoadingMore().observe(getViewLifecycleOwner(), loading -> {
            if (pagingErrorMessage.getVisibility() == View.VISIBLE) return;
            boolean show = Boolean.TRUE.equals(loading);
            loadMoreState.setVisibility(show ? View.VISIBLE : View.GONE);
            loadMoreProgress.setVisibility(show ? View.VISIBLE : View.GONE);
        });
        viewModel.getPagingError().observe(getViewLifecycleOwner(), message -> {
            boolean show = message != null && !message.trim().isEmpty();
            loadMoreState.setVisibility(show ? View.VISIBLE : View.GONE);
            loadMoreProgress.setVisibility(View.GONE);
            pagingErrorMessage.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) pagingErrorMessage.setText(message);
            loadMoreState.findViewById(R.id.btn_retry_page).setVisibility(show ? View.VISIBLE : View.GONE);
        });
    }

    private void showInitialLoading() {
        recyclerView.setVisibility(View.GONE);
        emptyState.setVisibility(View.GONE);
        errorState.setVisibility(View.GONE);
        loadMoreState.setVisibility(View.GONE);
        initialProgress.setVisibility(View.VISIBLE);
    }

    private void showInitialError(String message) {
        recyclerView.setVisibility(View.GONE);
        emptyState.setVisibility(View.GONE);
        initialProgress.setVisibility(View.GONE);
        errorState.setVisibility(View.VISIBLE);
        errorMessage.setText(message == null || message.trim().isEmpty()
                ? getString(R.string.conversations_load_error) : message);
    }
}
