package com.example.utechat.feature.chat.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.Menu;
import android.view.View;
import android.net.Uri;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.utechat.R;
import com.example.utechat.app.MediaRepositoryProvider;
import com.example.utechat.app.UteChatApplication;
import com.example.utechat.core.common.AuthGate;
import com.example.utechat.core.common.Constants;
import com.example.utechat.core.common.Resource;
import com.example.utechat.domain.model.Message;
import com.example.utechat.feature.chat.adapter.MessagesAdapter;
import com.example.utechat.feature.chat.viewmodel.ChatViewModel;
import com.example.utechat.feature.calls.ui.CallActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class ChatActivity extends AppCompatActivity {
    private RecyclerView rvMessages;
    private EditText etMessage;
    private ImageButton btnSend;
    private ImageButton btnAttachImage;
    private ChatViewModel viewModel;
    private final ActivityResultLauncher<PickVisualMediaRequest> imagePicker = registerForActivityResult(
            new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri == null || viewModel == null) return;
                forceScrollToBottom = true;
                if (!viewModel.sendImage(uri)) forceScrollToBottom = false;
            });
    private MessagesAdapter adapter;
    private LinearLayoutManager layoutManager;
    private View loadingState;
    private View errorState;
    private View emptyState;
    private TextView errorMessage;
    private TextView chatNotice;
    private View pagingProgress;
    private View sendError;
    private TextView sendErrorMessage;
    private String conversationId;
    private String conversationTitle;
    private String conversationType;
    private String pendingAnchorId;
    private int pendingAnchorOffset;
    private boolean forceScrollToBottom;

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.chat_call_menu, menu);
        boolean direct = "direct".equalsIgnoreCase(conversationType);
        menu.findItem(R.id.action_voice_call).setVisible(direct);
        menu.findItem(R.id.action_video_call).setVisible(direct);
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (AuthGate.redirectIfSignedOut(this)) return;

        conversationId = getIntent().getStringExtra(Constants.EXTRA_CONVERSATION_ID);
        conversationTitle = getIntent().getStringExtra(Constants.EXTRA_CONVERSATION_TITLE);
        conversationType = getIntent().getStringExtra(Constants.EXTRA_CONVERSATION_TYPE);
        setContentView(R.layout.activity_chat);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(conversationTitle == null || conversationTitle.trim().isEmpty()
                    ? getString(R.string.conversation_unknown_title) : conversationTitle);
        }

        bindViews();
        viewModel = new ViewModelProvider(this).get(ChatViewModel.class);
        viewModel.init(conversationId, conversationTitle);
        adapter = new MessagesAdapter(viewModel.getCurrentUserId(), viewModel.getCurrentUserName(),
                message -> viewModel.retryMessage(message.getId()), viewModel::votePoll,
                MediaRepositoryProvider.get(this));
        layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        rvMessages.setLayoutManager(layoutManager);
        rvMessages.setAdapter(adapter);
        chatNotice.setVisibility(viewModel.isMock() ? View.VISIBLE : View.GONE);
        setupListeners();
        observeViewModel();
    }

    private void bindViews() {
        rvMessages = findViewById(R.id.rv_messages);
        etMessage = findViewById(R.id.et_message);
        btnSend = findViewById(R.id.btn_send);
        btnAttachImage = findViewById(R.id.btn_attach_image);
        loadingState = findViewById(R.id.progress_chat);
        errorState = findViewById(R.id.chat_error_state);
        emptyState = findViewById(R.id.empty_chat_state);
        errorMessage = findViewById(R.id.tv_chat_error);
        chatNotice = findViewById(R.id.tv_chat_source);
        pagingProgress = findViewById(R.id.progress_history);
        sendError = findViewById(R.id.tv_chat_send_error);
        sendErrorMessage = findViewById(R.id.tv_chat_send_error_message);
        etMessage.setFilters(new InputFilter[]{new InputFilter.LengthFilter(ChatViewModel.MAX_MESSAGE_LENGTH)});
    }

    private void setupListeners() {
        btnSend.setOnClickListener(v -> sendCurrentText());
        btnAttachImage.setOnClickListener(v -> {
            if (!viewModel.canSendImage()) {
                Toast.makeText(this, R.string.image_storage_setup_error, Toast.LENGTH_LONG).show();
                return;
            }
            imagePicker.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
        });
        etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                btnSend.setEnabled(s != null && s.toString().trim().length() > 0);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        btnSend.setEnabled(false);
        etMessage.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentText();
                return true;
            }
            return false;
        });
        findViewById(R.id.btn_retry_chat).setOnClickListener(v -> viewModel.retryLoading());
        findViewById(R.id.btn_retry_stream).setOnClickListener(v -> {
            viewModel.retryHistory();
            viewModel.retryLoading();
        });
        rvMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (dy < 0 && layoutManager.findFirstVisibleItemPosition() <= 2
                        && Boolean.TRUE.equals(viewModel.getHasMoreHistory().getValue())) {
                    View first = layoutManager.findViewByPosition(layoutManager.findFirstVisibleItemPosition());
                    int position = layoutManager.findFirstVisibleItemPosition();
                    if (first != null && position >= 0 && position < adapter.getItemCount()) {
                        pendingAnchorId = adapter.getMessageAt(position).getId();
                        pendingAnchorOffset = first.getTop();
                    }
                    viewModel.loadOlder();
                }
            }
        });
    }

    private void sendCurrentText() {
        String text = etMessage.getText() == null ? "" : etMessage.getText().toString();
        forceScrollToBottom = true;
        if (viewModel.sendText(text)) etMessage.setText("");
        else forceScrollToBottom = false;
    }

    private void observeViewModel() {
        viewModel.getMessages().observe(this, result -> {
            if (result == null || result.isLoading()) {
                showInitialLoading();
                return;
            }
            if (result.isError()) {
                showInitialError(result.getMessage());
                return;
            }
            List<Message> items = result.getData() == null ? new ArrayList<>() : result.getData();
            boolean wasAtBottom = layoutManager.findLastVisibleItemPosition() >= adapter.getItemCount() - 2;
            adapter.submitList(new ArrayList<>(items), () -> {
                if (pendingAnchorId != null) {
                    int anchorPosition = findMessage(pendingAnchorId);
                    if (anchorPosition >= 0) layoutManager.scrollToPositionWithOffset(anchorPosition, pendingAnchorOffset);
                    pendingAnchorId = null;
                } else if ((wasAtBottom || forceScrollToBottom) && !items.isEmpty()) {
                    rvMessages.scrollToPosition(items.size() - 1);
                }
                forceScrollToBottom = false;
            });
            loadingState.setVisibility(View.GONE);
            errorState.setVisibility(View.GONE);
            emptyState.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            rvMessages.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        });
        viewModel.getLoadingOlder().observe(this, loading ->
                pagingProgress.setVisibility(Boolean.TRUE.equals(loading) ? View.VISIBLE : View.GONE));
        viewModel.getSendError().observe(this, message -> {
            boolean visible = message != null && !message.trim().isEmpty();
            sendError.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible) sendErrorMessage.setText(message);
        });
    }

    private int findMessage(String id) {
        for (int i = 0; i < adapter.getCurrentList().size(); i++) {
            if (id.equals(adapter.getMessageAt(i).getId())) return i;
        }
        return -1;
    }

    private void showCreatePollDialog() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int horizontal = getResources().getDimensionPixelSize(R.dimen.space_lg);
        int vertical = getResources().getDimensionPixelSize(R.dimen.space_xs);
        fields.setPadding(horizontal, vertical, horizontal, vertical);
        EditText question = new EditText(this);
        question.setSingleLine(true);
        question.setHint(R.string.poll_question_hint);
        fields.addView(question, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        List<EditText> optionInputs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            EditText option = new EditText(this);
            option.setSingleLine(true);
            option.setHint(getString(R.string.poll_option_hint, i + 1));
            option.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
            fields.addView(option, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            optionInputs.add(option);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(fields);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.poll_create_title)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.poll_create_button, null);
        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String title = question.getText() == null ? "" : question.getText().toString().trim();
                    List<String> choices = new ArrayList<>();
                    for (EditText input : optionInputs) {
                        String text = input.getText() == null ? "" : input.getText().toString().trim();
                        if (!text.isEmpty()) choices.add(text);
                    }
                    java.util.Set<String> unique = new java.util.HashSet<>(choices);
                    if (title.isEmpty() || title.length() > 200 || choices.size() < 2
                            || unique.size() != choices.size()) {
                        question.setError(title.isEmpty() || title.length() > 200
                                ? getString(R.string.poll_invalid) : null);
                        Toast.makeText(this, R.string.poll_invalid, Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (viewModel.createPoll(title, choices)) {
                        forceScrollToBottom = true;
                        dialog.dismiss();
                    }
                }));
        dialog.show();
    }

    private void showInitialLoading() {
        loadingState.setVisibility(View.VISIBLE);
        errorState.setVisibility(View.GONE);
        emptyState.setVisibility(View.GONE);
        rvMessages.setVisibility(View.GONE);
    }

    private void showInitialError(String message) {
        loadingState.setVisibility(View.GONE);
        emptyState.setVisibility(View.GONE);
        rvMessages.setVisibility(View.GONE);
        errorState.setVisibility(View.VISIBLE);
        errorMessage.setText(message == null || message.trim().isEmpty()
                ? getString(R.string.chat_load_error) : message);
    }

    @Override protected void onResume() {
        super.onResume();
        UteChatApplication.setActiveChat(conversationId);
    }

    @Override protected void onPause() {
        UteChatApplication.setActiveChat(null);
        super.onPause();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_create_poll) {
            showCreatePollDialog();
            return true;
        }
        if (item.getItemId() == R.id.action_voice_call || item.getItemId() == R.id.action_video_call) {
            if (!"direct".equalsIgnoreCase(conversationType)) {
                Toast.makeText(this, R.string.call_invalid_conversation, Toast.LENGTH_SHORT).show();
                return true;
            }
            String mediaType = item.getItemId() == R.id.action_video_call ? "video" : "audio";
            startActivity(CallActivity.outgoingIntent(this, conversationId, conversationTitle, mediaType));
            return true;
        }
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
