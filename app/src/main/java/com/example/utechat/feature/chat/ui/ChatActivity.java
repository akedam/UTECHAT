package com.example.utechat.feature.chat.ui;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.utechat.R;
import com.example.utechat.core.common.AuthGate;
import com.example.utechat.core.common.Constants;
import com.example.utechat.feature.chat.adapter.MessagesAdapter;
import com.example.utechat.feature.chat.viewmodel.ChatViewModel;

/**
 * Activity màn hình chat (1-1 và nhóm).
 * [MOCK] Hiển thị tin nhắn mẫu và cho phép gõ text — không gửi đi đâu.
 * TODO (G3): Kết nối ChatViewModel → Firestore real-time messages.
 */
public class ChatActivity extends AppCompatActivity {

    private RecyclerView rvMessages;
    private EditText etMessage;
    private ImageButton btnSend, btnAttach;
    private ChatViewModel viewModel;
    private MessagesAdapter adapter;

    private String conversationId;
    private String conversationTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (AuthGate.redirectIfSignedOut(this)) return;
        setContentView(R.layout.activity_chat);

        conversationId = getIntent().getStringExtra(Constants.EXTRA_CONVERSATION_ID);
        conversationTitle = getIntent().getStringExtra(Constants.EXTRA_CONVERSATION_TITLE);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(conversationTitle != null ? conversationTitle : "");
        }

        bindViews();
        viewModel = new ViewModelProvider(this).get(ChatViewModel.class);
        viewModel.init(conversationId);

        setupRecyclerView();
        observeViewModel();
        setupListeners();
    }

    private void bindViews() {
        rvMessages = findViewById(R.id.rv_messages);
        etMessage = findViewById(R.id.et_message);
        btnSend = findViewById(R.id.btn_send);
        btnAttach = findViewById(R.id.btn_attach);
    }

    private void setupRecyclerView() {
        // "mock_uid_me" là currentUserId giả — G3 lấy từ FirebaseAuth
        adapter = new MessagesAdapter("mock_uid_me");
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true); // Tin mới nhất ở dưới
        rvMessages.setLayoutManager(layoutManager);
        rvMessages.setAdapter(adapter);
    }

    private void observeViewModel() {
        viewModel.getMessages().observe(this, messages -> {
            adapter.submitList(messages);
            if (messages != null && !messages.isEmpty()) {
                rvMessages.scrollToPosition(messages.size() - 1);
            }
        });
    }

    private void setupListeners() {
        btnSend.setOnClickListener(v -> {
            String text = etMessage.getText().toString().trim();
            if (text.isEmpty()) return;
            // TODO (G3): viewModel.sendMessage(conversationId, text)
            viewModel.mockSendMessage(text);
            etMessage.setText("");
        });

        btnAttach.setOnClickListener(v -> {
            // TODO (G5): Mở file/image picker
            Toast.makeText(this, R.string.feature_coming_soon, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
