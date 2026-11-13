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
    private final VoiceDialogs voiceDialogs = new VoiceDialogs(this);
    private final ActivityResultLauncher<String> microphone = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (isFinishing() || isDestroyed() || this.blockedByMe) return;
                if (granted) voiceDialogs.record();
                else Toast.makeText(this, R.string.voice_permission, Toast.LENGTH_LONG).show();
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
    private ChatPreferences chatPreferences;
    private ChatOptionsDialog optionsDialog;
    private com.example.utechat.data.chat.ConversationReadState readState;
    private boolean reading;
    private Message.Reply replyTo;
    private boolean blockedByMe;
    private com.google.firebase.firestore.ListenerRegistration blockListener;

    private void observePeer() {
        if (!"direct".equalsIgnoreCase(conversationType) || viewModel.isMock() || conversationId == null) return;
        var db = com.google.firebase.firestore.FirebaseFirestore.getInstance();
        db.collection("conversations").document(conversationId).get().addOnSuccessListener(this, snapshot -> {
            Object ids = snapshot.get("participantIds");
            if (!(ids instanceof List)) return;
            for (Object id : (List<?>) ids) {
                if (!(id instanceof String) || id.equals(viewModel.getCurrentUserId())) continue;
                if (blockListener != null) blockListener.remove();
                blockListener = com.example.utechat.feature.safety.UserSafety.blockReference(
                        db, viewModel.getCurrentUserId(), (String) id).addSnapshotListener((block, error) -> {
                    if (block == null || isDestroyed()) return;
                    blockedByMe = block.exists();
                    findViewById(R.id.tv_blocked_chat).setVisibility(blockedByMe ? View.VISIBLE : View.GONE);
                    findViewById(R.id.chat_composer).setVisibility(blockedByMe ? View.GONE : View.VISIBLE);
                    findViewById(R.id.reply_composer).setVisibility(blockedByMe || replyTo == null ? View.GONE : View.VISIBLE);
                    invalidateOptionsMenu();
                });
                break;
            }
        });
    }

    void showPeerSafety() {
        var db = com.google.firebase.firestore.FirebaseFirestore.getInstance();
        db.collection("conversations").document(conversationId).get()
                .addOnSuccessListener(this, snapshot -> {
                    Object ids = snapshot.get("participantIds");
                    if (ids instanceof List) for (Object id : (List<?>) ids) {
                        if (id instanceof String && !id.equals(viewModel.getCurrentUserId())) {
                            com.example.utechat.feature.safety.UserSafety.showActions(
                                    this, (String) id, conversationTitle, conversationId);
                            return;
                        }
                    }
                }).addOnFailureListener(this, error ->
                        Toast.makeText(this, R.string.safety_update_error, Toast.LENGTH_SHORT).show());
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.chat_call_menu, menu);
        boolean direct = "direct".equalsIgnoreCase(conversationType) && !blockedByMe;
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
        applyChatInsets(getWindow(), findViewById(R.id.chat_root), this);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(conversationTitle == null || conversationTitle.trim().isEmpty()
                    ? getString(R.string.conversation_unknown_title) : conversationTitle);
        }
        toolbar.setNavigationIcon(R.drawable.ic_chat_back);
        if (toolbar.getNavigationIcon() != null) toolbar.getNavigationIcon().setTint(getColor(R.color.white));
        chatPreferences = new ChatPreferences(this, conversationId);
        applyConversationAppearance();

        bindViews();
        viewModel = new ViewModelProvider(this).get(ChatViewModel.class);
        viewModel.init(conversationId, conversationTitle);
        if(!viewModel.isMock())readState=new com.example.utechat.data.chat.ConversationReadState(this,viewModel.getCurrentUserId());
        adapter = new MessagesAdapter(viewModel.getCurrentUserId(), viewModel.getCurrentUserName(),
                message -> viewModel.retryMessage(message.getId()), viewModel::votePoll,
                MediaRepositoryProvider.get(this));
        adapter.setGroupConversation("group".equalsIgnoreCase(conversationType));
        adapter.setMessageActions(this::showMessageActions, this::jumpToOriginal, voiceDialogs::play);
        adapter.setPeerPhoto(getIntent().getStringExtra(Constants.EXTRA_CONVERSATION_PHOTO));
        if (!"group".equalsIgnoreCase(conversationType)) adapter.setCallListener(media -> {
            if (!blockedByMe) startActivity(CallActivity.outgoingIntent(this, conversationId, conversationTitle, media));
        });
        layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        rvMessages.setLayoutManager(layoutManager);
        rvMessages.setAdapter(adapter);
        chatNotice.setVisibility(viewModel.isMock() ? View.VISIBLE : View.GONE);
        setupListeners();
        findViewById(R.id.btn_clear_reply).setOnClickListener(v -> setReply(null));
        if (savedInstanceState != null && savedInstanceState.containsKey("reply_id")) {
            setReply(new Message.Reply(savedInstanceState.getString("reply_id"),
                    savedInstanceState.getString("reply_name"), savedInstanceState.getString("reply_text"),
                    savedInstanceState.getString("reply_type")));
        }
        String draft = chatPreferences.draft();
        etMessage.setText(draft);
        etMessage.setSelection(etMessage.length());
        observeViewModel();
        observePeer();
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
        findViewById(R.id.btn_chat_more).setOnClickListener(v -> {
                List<CharSequence> tools = new ArrayList<>();
                tools.add(getString(R.string.cd_attach_image));
                tools.add(getString(R.string.chat_create_poll));
                if (viewModel.canSendVoice()) tools.add(getString(R.string.voice_record));
                new MaterialAlertDialogBuilder(this).setTitle(R.string.chat_more)
                        .setItems(tools.toArray(new CharSequence[0]), (dialog, which) -> {
                            if (which == 0) btnAttachImage.performClick();
                            else if (which == 1) showCreatePollDialog();
                            else requestRecording();
                        }).setNegativeButton(R.string.friends_cancel_button, null).show();
        });
        findViewById(R.id.btn_chat_emoji).setOnClickListener(v -> {
            String[] emojis = {"😀", "😂", "❤️", "👍", "🥰", "🎉", "😢", "🙏", "🔥", "😊", "🤔", "👋"};
            new MaterialAlertDialogBuilder(this).setTitle(R.string.chat_emoji_title).setItems(emojis, (d, which) -> {
                int start = Math.max(0, etMessage.getSelectionStart());
                int end = Math.max(0, etMessage.getSelectionEnd());
                etMessage.getText().replace(Math.min(start, end), Math.max(start, end), emojis[which]);
                etMessage.requestFocus();
            }).show();
        });
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
                btnSend.setVisibility(btnSend.isEnabled() ? View.VISIBLE : View.GONE);
            }
            @Override public void afterTextChanged(Editable s) {
                chatPreferences.setDraft(s.toString());
            }
        });
        btnSend.setEnabled(false);
        btnSend.setVisibility(View.GONE);
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
                markVisibleMessagesRead();
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
        if (blockedByMe) return;
        String text = etMessage.getText() == null ? "" : etMessage.getText().toString();
        forceScrollToBottom = true;
        if (viewModel.sendText(text, replyTo)) {
            etMessage.setText("");
            setReply(null);
        }
        else forceScrollToBottom = false;
    }

    private void requestRecording() {
        if (blockedByMe) return;
        String permission = android.Manifest.permission.RECORD_AUDIO;
        if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) voiceDialogs.record();
        else microphone.launch(permission);
    }

    boolean sendRecordedVoice(java.io.File file, long duration) {
        if (blockedByMe) return false;
        forceScrollToBottom = true;
        if (viewModel.sendVoice(file, duration)) return true;
        forceScrollToBottom = false;
        return false;
    }

    private void showMessageActions(Message message) {
        boolean copy = message.getType() == Message.Type.TEXT || message.getType() == Message.Type.POLL;
        boolean reply = !blockedByMe && message.getDeliveryStatus() == Message.DeliveryStatus.SENT;
        List<CharSequence> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (reply) {
            labels.add(getString(R.string.message_reply));
            actions.add(() -> {
                setReply(new Message.Reply(message.getId(), message.getStoredSenderName(),
                        message.getContent(), message.getType().name().toLowerCase(java.util.Locale.ROOT)));
                etMessage.requestFocus();
                android.view.inputmethod.InputMethodManager keyboard =
                        getSystemService(android.view.inputmethod.InputMethodManager.class);
                if (keyboard != null) keyboard.showSoftInput(etMessage,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            });
        }
        if (copy && message.getContent() != null && !message.getContent().isEmpty()) {
            labels.add(getString(R.string.chat_copy));
            actions.add(() -> {
                android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
                if (clipboard == null) return;
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.app_name), message.getContent()));
                if (android.os.Build.VERSION.SDK_INT < 33)
                    Toast.makeText(this, R.string.chat_copied, Toast.LENGTH_SHORT).show();
            });
        }
        if ("group".equalsIgnoreCase(conversationType) && !viewModel.isMock() && message.getSenderId() != null
                && !java.util.Objects.equals(viewModel.getCurrentUserId(), message.getSenderId())) {
            labels.add(getString(R.string.user_safety));
            actions.add(() -> com.example.utechat.feature.safety.UserSafety.showActions(this, message.getSenderId(),
                    message.getSenderName(), conversationId));
        }
        if (!actions.isEmpty()) new MaterialAlertDialogBuilder(this)
                .setItems(labels.toArray(new CharSequence[0]), (dialog, selected) -> actions.get(selected).run())
                .setNegativeButton(R.string.friends_cancel_button, null).show();
    }

    private void setReply(Message.Reply reply) {
        replyTo = reply;
        findViewById(R.id.reply_composer).setVisibility(reply == null ? View.GONE : View.VISIBLE);
        if (reply != null) {
            String name = reply.name.isEmpty() ? getString(R.string.friends_unknown_user) : reply.name;
            ((TextView) findViewById(R.id.tv_reply_target)).setText(getString(R.string.message_reply_to, name));
            ((TextView) findViewById(R.id.tv_reply_preview)).setText(MessagesAdapter.replyPreview(this, reply));
        }
    }

    private void jumpToOriginal(String id) {
        if (findMessage(id) >= 0) scrollToMessage(id);
        else Toast.makeText(this, R.string.message_reply_missing, Toast.LENGTH_SHORT).show();
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle state) {
        if (replyTo != null) {
            state.putString("reply_id", replyTo.id);
            state.putString("reply_name", replyTo.name);
            state.putString("reply_text", replyTo.text);
            state.putString("reply_type", replyTo.type);
        }
        super.onSaveInstanceState(state);
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
                rvMessages.post(this::markVisibleMessagesRead);
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

    ChatViewModel chatViewModel() {
        return viewModel;
    }

    void loadOlderForSearch() {
        int position = layoutManager.findFirstVisibleItemPosition();
        View first = layoutManager.findViewByPosition(position);
        if (first != null && position >= 0 && position < adapter.getItemCount()) {
            pendingAnchorId = adapter.getMessageAt(position).getId();
            pendingAnchorOffset = first.getTop();
        }
        viewModel.retryHistory();
    }

    private void markVisibleMessagesRead() {
        if(!reading || readState==null || adapter==null || adapter.getItemCount()==0 || rvMessages.getVisibility()!=View.VISIBLE)return;
        int last=adapter.getItemCount()-1;
        if(layoutManager.findLastVisibleItemPosition()>=last)readState.markRead(conversationId,adapter.getMessageAt(last));
    }

    String conversationId() { return conversationId; }

    void scrollToMessage(String id) {
        int position = findMessage(id);
        if (position >= 0) layoutManager.scrollToPositionWithOffset(position, getResources().getDimensionPixelSize(R.dimen.space_md));
    }

    void applyConversationAppearance() {
        if (chatPreferences == null) return;
        String fallback = conversationTitle == null || conversationTitle.trim().isEmpty()
                ? getString(R.string.conversation_unknown_title) : conversationTitle;
        if (getSupportActionBar() != null) getSupportActionBar().setTitle(chatPreferences.title(fallback));
        View messages = findViewById(R.id.rv_messages);
        if (messages != null) ((View) messages.getParent()).setBackgroundColor(chatPreferences.wallpaper());
    }

    static void applyChatInsets(android.view.Window window, View root, android.content.Context context) {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            int bottom = Math.max(bars.bottom, insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom);
            view.setPadding(bars.left, bars.top, bars.right, bottom);
            android.graphics.drawable.LayerDrawable background = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[]{
                            androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.bg_header_gradient),
                            new android.graphics.drawable.ColorDrawable(androidx.core.content.ContextCompat.getColor(context,R.color.utechat_surface))});
            background.setLayerGravity(1, android.view.Gravity.BOTTOM); background.setLayerHeight(1, bottom);
            view.setBackground(background);
            return insets;
        });
        androidx.core.view.WindowCompat.getInsetsController(window, root).setAppearanceLightStatusBars(false);
        androidx.core.view.WindowCompat.getInsetsController(window, root).setAppearanceLightNavigationBars(true);
        androidx.core.view.ViewCompat.requestApplyInsets(root);
    }

    private void showChatOptions() {
        if (optionsDialog != null && optionsDialog.isShowing()) return;
        optionsDialog = new ChatOptionsDialog(this, chatPreferences,
                conversationTitle == null ? getString(R.string.conversation_unknown_title) : conversationTitle,
                getIntent().getStringExtra(Constants.EXTRA_CONVERSATION_PHOTO),
                "group".equalsIgnoreCase(conversationType), adapter.getCurrentList());
        optionsDialog.show();
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
                .setNegativeButton(R.string.friends_cancel_button, null)
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
        reading=true;
        if(rvMessages!=null)rvMessages.post(this::markVisibleMessagesRead);
        UteChatApplication.setActiveChat(conversationId);
    }

    @Override protected void onPause() {
        voiceDialogs.close();
        reading=false;
        UteChatApplication.setActiveChat(null);
        super.onPause();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_chat_options) {
            showChatOptions();
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

    @Override protected void onDestroy() {
        voiceDialogs.close();
        if (blockListener != null) blockListener.remove();
        if (optionsDialog != null) optionsDialog.dismiss();
        if (rvMessages != null) rvMessages.setAdapter(null);
        super.onDestroy();
    }
}
