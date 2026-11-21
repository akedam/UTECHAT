package com.example.utechat.feature.moments;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import com.bumptech.glide.Glide;
import com.example.utechat.R;
import com.example.utechat.app.FriendGroupRepositoryProvider;
import com.example.utechat.core.common.Constants;
import com.example.utechat.feature.chat.ui.ChatActivity;
import com.example.utechat.feature.main.ui.MainActivity;
import com.example.utechat.domain.model.User;
import com.example.utechat.domain.repository.FriendGroupRepository;
import com.example.utechat.feature.safety.UserSafety;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MomentsFragment extends Fragment {
    private enum Page { FEED, PREVIEW, PICKER, DETAIL, FULL }
    public static final String EXTRA_ID = "momentId";
    private static final String[] EMOJI = {"❤️", "😂", "😮", "😢", "👍"};
    private final ExecutorService requests = Executors.newSingleThreadExecutor();
    private final ExecutorService photos = Executors.newFixedThreadPool(2);
    private final ExecutorService effects = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TinFaces faces = new TinFaces();
    private static final int CONTROL_COLOR = 0xFF234F7E;
    private static final int CONTROL_SURFACE = 0xFFFBFDFF;
    private final TinCamera liveCamera = new TinCamera();
    private final java.util.ArrayList<File> shots = new java.util.ArrayList<>();
    private final Map<String, User> friends = new LinkedHashMap<>();
    private final Set<String> blocked = new LinkedHashSet<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private FriendGroupRepository.Subscription friendshipSubscription;
    private com.google.firebase.firestore.ListenerRegistration blockedSubscription;
    private Page page = Page.FEED;
    private volatile int screen;
    private int friendshipRevision;
    private String uid, detailId, nonce, caption = "", view = "received", cursor = "";
    private String authorFilter = "", captionStyle = "";
    private String ownPhoto;
    private String nonceAudience = "";
    private JSONObject detail;
    private File captureFile, draftFile;
    private boolean loading, busy, friendsLoaded, friendsFailed;
    private LinearLayout body, pickerRows;
    private LinearLayout feedRows, feedFooter, cameraPanel, tileRow, shotStrip;
    private ViewPager2 storyPager;
    private StoryAdapter storyAdapter;
    private TextView streamStatus, cameraHint;
    private final java.util.ArrayList<JSONObject> feedCards = new java.util.ArrayList<>();
    private final Set<String> loadedStories = new LinkedHashSet<>();
    private int storyIndex = -1;
    private boolean personalHistory;
    private ScrollView scroll;
    private TextView title, selectedCount, feedback;
    private ImageButton postButton;
    private LinearLayout previewAudience;
    private ImageButton backButton, toolbarAction;
    private PreviewView cameraView;
    private ImageView cameraEffect;
    private TextView effectStatus;
    private ImageButton shutterButton, filtersButton, albumButton, flashButton;
    private boolean flash;
    private TextView captureStatus;
    private TinFilters filterCatalog;
    private TinFilters.Filter frame = TinFilters.NATURAL;
    private TinPhotos.Tone tone = TinPhotos.Tone.NORMAL;
    private boolean renderingEffect;
    private java.util.List<File> previewPaths = java.util.List.of();
    private java.util.List<Bitmap> previewPhotos = java.util.List.of();
    private final Runnable effectTick = this::renderEffect;
    private boolean grid, allViewers = true, capturing, cameraUnavailable, cameraOpening, feedRequested;
    private int tileCount;
    private long lastFeedLoaded;
    private byte[] exportBytes;
    private String friendQuery = "";
    private String sendError = "";
    private final ActivityResultLauncher<String> album = registerForActivityResult(
            new ActivityResultContracts.GetContent(), this::albumResult);
    private final ActivityResultLauncher<String> saveDocument = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("image/jpeg"), uri -> {
                byte[] bytes = exportBytes;
                exportBytes = null;
                if (uri == null || bytes == null || body == null) return;
                var resolver = requireContext().getContentResolver();
                run(() -> {
                    try (var output = resolver.openOutputStream(uri)) {
                        if (output == null) throw new IOException();
                        output.write(bytes);
                    }
                    return true;
                }, ignored -> toast("Đã lưu ảnh."), failure -> toast("Chưa lưu được ảnh."));
            });
    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), allowed -> {
                if (body == null || page != Page.FEED || grid || isHidden()) return;
                if (allowed) resumeCamera(); else cameraHelp();
            });

    public static Intent intent(Context context, String id) {
        Intent open = new Intent(context, MainActivity.class).putExtra(EXTRA_ID, id == null ? "" : id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (MomentApi.validId(id)) {
            open.setData(Uri.parse("utechat://moment/" + id)).putExtra(EXTRA_ID, id);
        }
        return open;
    }

    public MomentsFragment() { super(R.layout.activity_moments); }

    @Override public void onViewCreated(@androidx.annotation.NonNull View root, Bundle state) {
        super.onViewCreated(root, state);
        uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return;
        body = root.findViewById(R.id.moments_body);
        scroll = root.findViewById(R.id.moments_scroll);
        storyPager = root.findViewById(R.id.moments_pager);
        streamStatus = root.findViewById(R.id.moments_stream_status);
        storyPager.registerOnPageChangeCallback(storyPageChanges);
        filterCatalog = new TinFilters(requireContext());
        title = root.findViewById(R.id.moments_title);
        backButton = root.findViewById(R.id.moments_back);
        toolbarAction = root.findViewById(R.id.moments_action);
        styleTool(backButton, CONTROL_SURFACE, CONTROL_COLOR, 15, 12);
        styleTool(toolbarAction, CONTROL_SURFACE, CONTROL_COLOR, 15, 12);
        title.setTextColor(0xFF102139);
        backButton.setOnClickListener(v -> back());
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> updateCameraVisibility());
        watchFriends();
        if (state != null) {
            draftFile = ownedFile(state.getString("draft"));
            caption = state.getString("caption", "");
            captionStyle = state.getString("captionStyle", "");
            allViewers = state.getBoolean("allViewers", true);
            grid = state.getBoolean("grid");
            personalHistory = state.getBoolean("personalHistory");
            authorFilter = state.getString("author", "");
            frame = filterCatalog.find(state.getString("frame", "natural"));
            tone = TinPhotos.Tone.valueOf(state.getString("tone", "NORMAL"));
            var restoredShots = state.getStringArrayList("shots");
            if (restoredShots != null) for (String name : restoredShots) {
                File file = ownedFile(name);
                if (file != null && file.isFile()) shots.add(file);
            }
            nonce = state.getString("nonce");
            nonceAudience = state.getString("nonceAudience", "");
            sendError = state.getString("sendError", "");
            view = state.getString("view", "received");
            var restored = state.getStringArrayList("selected");
            if (restored != null) selected.addAll(restored);
            if (draftFile != null && draftFile.exists()) {
                if ("PICKER".equals(state.getString("page"))) showPicker(); else showPreview();
                return;
            }
        }
        String initial = state == null ? null : state.getString("detail");
        if (MomentApi.validId(initial)) open(initial); else showFeed(view);
    }

    @Override public void onSaveInstanceState(@androidx.annotation.NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("draft", draftFile == null ? null : draftFile.getName());
        state.putString("caption", caption);
        state.putString("captionStyle", captionStyle);
        state.putBoolean("allViewers", allViewers);
        state.putBoolean("grid", grid);
        state.putBoolean("personalHistory", personalHistory);
        state.putString("author", authorFilter);
        state.putString("frame", frame.id);
        state.putString("tone", tone.name());
        var shotNames = new java.util.ArrayList<String>();
        for (File file : shots) shotNames.add(file.getName());
        state.putStringArrayList("shots", shotNames);
        state.putString("nonce", nonce);
        state.putString("nonceAudience", nonceAudience);
        state.putString("page", page.name());
        state.putString("detail", page == Page.DETAIL || page == Page.FULL ? detailId : null);
        state.putString("view", view);
        state.putString("sendError", sendError);
        state.putStringArrayList("selected", new java.util.ArrayList<>(selected));
    }

    @Override public void onDestroyView() {
        if (friendshipSubscription != null) friendshipSubscription.remove();
        if (blockedSubscription != null) blockedSubscription.remove();
        screen++;
        if (storyPager != null) {
            storyPager.unregisterOnPageChangeCallback(storyPageChanges);
            storyPager.setAdapter(null);
        }
        main.removeCallbacks(effectTick);
        liveCamera.release();
        cameraOpening = false;
        if (captureFile != null) captureFile.delete();
        captureFile = null;
        cameraView = null;
        body = null;
        super.onDestroyView();
    }

    @Override public void onDestroy() {
        requests.shutdownNow();
        photos.shutdownNow();
        effects.shutdownNow();
        faces.close();
        if (getActivity() != null && getActivity().isFinishing()) {
            if (captureFile != null) captureFile.delete();
            clearDraft();
            clearShots();
        }
        super.onDestroy();
    }

    @Override public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (body == null) return;
        if (hidden) {
            liveCamera.release();
            main.removeCallbacks(effectTick);
            cameraOpening = false;
        }
        else if (page == Page.FEED && (!feedRequested || storyIndex < 0 && !capturing && shots.isEmpty()
                && android.os.SystemClock.elapsedRealtime() - lastFeedLoaded > 10_000)) showFeed(view);
        else updateCameraVisibility();
    }

    @Override public void onResume() {
        super.onResume();
        if (body != null) updateCameraVisibility();
    }

    @Override public void onPause() {
        liveCamera.release();
        main.removeCallbacks(effectTick);
        cameraOpening = false;
        super.onPause();
    }

    public void openTarget(String id) {
        if (body == null || !sameUser()) return;
        if (MomentApi.validId(id)) {
            personalHistory = false;
            grid = false;
            authorFilter = "";
            view = "received";
            open(id);
        }
        else if (page != Page.FEED || !feedRequested) showFeed(view);
    }

    public void openHistory() {
        if (body == null || !sameUser()) return;
        personalHistory = true;
        grid = true;
        authorFilter = "";
        showFeed("mine");
    }

    public boolean handleBack() {
        if (body == null) return false;
        if (page == Page.FEED && !grid && storyIndex < 0) return false;
        back();
        return true;
    }

    private void watchFriends() {
        FriendGroupRepository repository = FriendGroupRepositoryProvider.get(requireContext());
        if (repository == null) { friendsFailed = true; return; }
        repository.findUser(uid, new FriendGroupRepository.Result<User>() {
            @Override public void success(User user) {
                if (body == null || !sameUser() || user == null) return;
                ownPhoto = user.getPhotoUrl();
                if (page == Page.FEED || page == Page.DETAIL || page == Page.FULL) setProfileTool();
            }
            @Override public void error(String message) { }
        });
        friendshipSubscription = repository.observeFriendships(uid, (items, error) -> {
            if (body == null || !sameUser()) return;
            int revision = ++friendshipRevision;
            friendsFailed = error != null;
            if (friendsFailed || items == null) {
                if (page == Page.PICKER) updatePicker();
                return;
            }
            var peers = new java.util.ArrayList<String>();
            for (var item : items) {
                if (item.isAccepted()) peers.add(uid.equals(item.getFromUid()) ? item.getToUid() : item.getFromUid());
            }
            repository.loadUsers(peers, new FriendGroupRepository.Result<java.util.List<User>>() {
                @Override public void success(java.util.List<User> users) {
                    if (revision != friendshipRevision || body == null || !sameUser()) return;
                    friendsLoaded = true;
                    friends.clear();
                    users.stream().sorted(Comparator.comparing(user -> name(user).toLowerCase(Locale.ROOT)))
                            .forEach(user -> friends.put(user.getUid(), user));
                    if (page == Page.PICKER) updatePicker();
                    if (page == Page.PREVIEW) updatePreviewAudience();
                    if (page == Page.DETAIL && detail != null && detail.optBoolean("own") && !busy) showDetail();
                }
                @Override public void error(String message) {
                    if (revision != friendshipRevision || body == null || !sameUser()) return;
                    friendsFailed = true;
                    if (page == Page.PICKER) updatePicker();
                }
            });
        });
        blockedSubscription = FirebaseFirestore.getInstance().collection("users").document(uid)
                .collection("blocked").addSnapshotListener((snapshot, error) -> {
                    if (snapshot == null || !sameUser()) return;
                    Set<String> previous = new LinkedHashSet<>(blocked);
                    blocked.clear();
                    for (var entry : snapshot) blocked.add(entry.getId());
                    if (page == Page.PICKER && pickerRows != null) updatePicker();
                    if (page == Page.PREVIEW) updatePreviewAudience();
                    if (!previous.equals(blocked)) MomentWidget.invalidate(requireContext());
                    if (detail != null && blocked.contains(detail.optString("owner"))
                            && (page == Page.DETAIL || page == Page.FULL)) showFeed(view);
                });
    }

    private void startPage(Page next, String label) {
        liveCamera.release();
        main.removeCallbacks(effectTick);
        cameraOpening = cameraUnavailable = false;
        cameraView = null;
        storyAdapter = null;
        storyPager.setAdapter(null);
        storyPager.setVisibility(View.GONE);
        streamStatus.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);
        screen++;
        busy = false;
        page = next;
        title.setText(label);
        title.setOnClickListener(null);
        title.setClickable(false);
        title.setBackground(null);
        body.setPadding(dp(12), dp(next == Page.PREVIEW || next == Page.DETAIL || next == Page.FEED && !grid ? 28 : 10), dp(12), dp(18));
        backButton.setVisibility(next == Page.PREVIEW ? View.INVISIBLE : View.VISIBLE);
        backButton.setImageResource(R.drawable.ic_chat_back);
        backButton.setContentDescription("Quay lại Tin");
        backButton.setOnClickListener(v -> back());
        toolbarAction.setVisibility(View.INVISIBLE);
        body.removeAllViews();
        scroll.scrollTo(0, 0);
        body.setBackgroundColor(Color.TRANSPARENT);
        body.setGravity(next == Page.FULL ? Gravity.CENTER : Gravity.TOP);
    }

    private void showFeed(String which) {
        view = which;
        boolean history = "mine".equals(view);
        startPage(Page.FEED, history ? getString(R.string.tin_personal_history) : filterName());
        if (!history) setTitleTool(filterName(), this::chooseAuthor);
        setProfileTool();
        cursor = "";
        loading = false;
        storyIndex = -1;
        feedCards.clear();
        loadedStories.clear();
        if (grid) {
            feedRows = new LinearLayout(requireContext());
            feedRows.setOrientation(LinearLayout.VERTICAL);
            body.addView(feedRows);
            feedFooter = new LinearLayout(requireContext());
            feedFooter.setOrientation(LinearLayout.VERTICAL);
            body.addView(feedFooter);
            tileCount = 0;
        } else {
            scroll.setVisibility(View.GONE);
            storyPager.setVisibility(View.VISIBLE);
            showCamera();
            storyAdapter = new StoryAdapter();
            storyPager.setAdapter(storyAdapter);
            storyPager.setUserInputEnabled(!capturing);
        }
        if (history) addWidgetSetting();
        updateFeedToolbar();
        feedRequested = !isHidden();
        if (feedRequested) fetchFeed();
    }

    private String filterName() {
        return "mine".equals(view) ? "Bạn" : authorFilter.isEmpty() ? "Mọi người" : name(friends.get(authorFilter));
    }

    private void showAllStories() {
        personalHistory = false;
        grid = true;
        authorFilter = "";
        showFeed("received");
    }

    private void showCameraFeed() {
        personalHistory = false;
        grid = false;
        authorFilter = "";
        showFeed("received");
    }

    private void chooseAuthor() {
        var keys = new java.util.ArrayList<>(java.util.List.of("", "mine"));
        var labels = new java.util.ArrayList<>(java.util.List.of("Mọi người", "Bạn"));
        for (User user : friends.values()) {
            if (blocked.contains(user.getUid())) continue;
            keys.add(user.getUid()); labels.add(name(user));
        }
        new MaterialAlertDialogBuilder(requireContext()).setTitle("Lọc người đăng")
                .setItems(labels.toArray(new String[0]), (dialog, position) -> {
                    String key = keys.get(position);
                    authorFilter = "mine".equals(key) ? "" : key;
                    personalHistory = false;
                    grid = true;
                    showFeed("mine".equals(key) ? "mine" : "received");
                }).show();
    }

    private void addWidgetSetting() {
        var widget = new MaterialCheckBox(requireContext());
        widget.setText(R.string.moments_widget_opt_in);
        widget.setMinHeight(dp(48));
        widget.setChecked(MomentWidget.enabled(requireContext(), uid));
        widget.setOnCheckedChangeListener((v, enabled) -> {
            if (enabled) new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.moments_widget_label)
                    .setMessage("Ảnh mới từ bạn bè có thể hiện trên màn hình chính. Thêm widget Khoảnh khắc UTEChat từ danh sách widget của thiết bị.")
                    .setPositiveButton("Bật", (dialog, choice) -> MomentWidget.setEnabled(requireContext(), uid, true))
                    .setNegativeButton("Hủy", (dialog, choice) -> widget.setChecked(false))
                    .setOnCancelListener(dialog -> widget.setChecked(false)).show();
            else MomentWidget.setEnabled(requireContext(), uid, false);
        });
        body.addView(widget);
    }

    private void fetchFeed() {
        if (loading) return;
        loading = true;
        String requestedView = "mine".equals(view) ? "mine" : authorFilter.isEmpty() ? "all" : "received";
        String before = cursor;
        updateStreamStatus(false);
        if (grid) {
            feedFooter.removeAllViews();
            feedFooter.addView(text("Đang tải…", 14, false));
        }
        run(() -> MomentApi.list(requestedView, before), result -> {
            lastFeedLoaded = android.os.SystemClock.elapsedRealtime();
            loading = false;
            if (grid) feedFooter.removeAllViews();
            cursor = result.isNull("next") ? "" : result.optString("next");
            JSONArray cards = result.optJSONArray("items");
            int first = feedCards.size();
            for (int index = 0; index < cards.length(); index++) {
                JSONObject card = cards.optJSONObject(index);
                if (card == null || !MomentApi.validId(card.optString("id")) ||
                        !authorFilter.isEmpty() && !authorFilter.equals(card.optString("owner"))) continue;
                feedCards.add(card);
                if (grid) addCard(card);
            }
            if (grid) {
                if (feedCards.isEmpty() && cursor.isEmpty())
                    feedFooter.addView(text("mine".equals(view) ? "Bạn chưa đăng tin nào." : "Chưa có Tin.", 15, true));
                if (!cursor.isEmpty()) feedFooter.addView(button("Xem thêm", false, this::fetchFeed));
            } else {
                if (first < feedCards.size()) storyAdapter.notifyItemRangeInserted(first + 1, feedCards.size() - first);
                updateStreamStatus(false);
                maybeLoadMore();
            }
        }, failure -> {
            loading = false;
            if (grid) {
                feedFooter.removeAllViews();
                feedFooter.addView(text("Chưa tải được tin.", 14, false));
                feedFooter.addView(button("Thử lại", false, this::fetchFeed));
            } else updateStreamStatus(true);
        });
    }

    private void addCard(JSONObject card) {
        String id = card.optString("id");
        int column = tileCount++ % 3;
        if (column == 0) {
            tileRow = row();
            for (int index = 0; index < 3; index++) {
                var cell = new FrameLayout(requireContext());
                var layout = new LinearLayout.LayoutParams(0, -2, 1);
                layout.setMargins(dp(2), dp(2), dp(2), dp(2));
                tileRow.addView(cell, layout);
            }
            feedRows.addView(tileRow);
        }
        ((FrameLayout) tileRow.getChildAt(column)).addView(photo(id, false, true, () -> open(id), () -> {}));
    }


    private final ViewPager2.OnPageChangeCallback storyPageChanges = new ViewPager2.OnPageChangeCallback() {
        @Override public void onPageSelected(int position) {
            if (storyAdapter == null || position > feedCards.size()) return;
            storyIndex = position - 1;
            if (storyIndex >= 0) {
                liveCamera.release();
                cameraOpening = false;
                main.removeCallbacks(effectTick);
                JSONObject card = feedCards.get(storyIndex);
                if (loadedStories.contains(card.optString("id"))) markSeen(card);
            } else storyPager.post(MomentsFragment.this::updateCameraVisibility);
            updateFeedToolbar();
            storyPager.post(MomentsFragment.this::maybeLoadMore);
        }

        @Override public void onPageScrollStateChanged(int state) {
            if (storyAdapter == null) return;
            if (state == ViewPager2.SCROLL_STATE_DRAGGING) hideKeyboard();
            if (state == ViewPager2.SCROLL_STATE_IDLE) updateCameraVisibility();
        }
    };

    private void maybeLoadMore() {
        if (storyAdapter != null && !loading && !cursor.isEmpty() && storyIndex >= feedCards.size() - 2) fetchFeed();
    }

    private void updateFeedToolbar() {
        boolean toProfile = grid && personalHistory;
        backButton.setVisibility(!grid && storyIndex >= 0 ? View.INVISIBLE : View.VISIBLE);
        backButton.setImageResource(toProfile ? R.drawable.ic_chat_back
                : grid ? R.drawable.ic_message_camera : R.drawable.ic_tin_refresh);
        backButton.setContentDescription(toProfile ? "Về hồ sơ" : grid ? "Về máy ảnh" : "Làm mới tin");
        backButton.setOnClickListener(v -> {
            if (toProfile) back();
            else if (grid) showCameraFeed();
            else if (!busy && !capturing) showFeed(view);
        });
    }

    private void updateStreamStatus(boolean failed) {
        if (storyAdapter == null) return;
        cameraHint.setText(!feedCards.isEmpty() || !cursor.isEmpty() ? "↓  Vuốt lên để xem Tin"
                : loading ? "Đang tải Tin…" : failed ? "" : "Chưa có Tin");
        streamStatus.setVisibility(failed ? View.VISIBLE : View.GONE);
        streamStatus.setText(failed ? "Chưa tải được Tin · Thử lại" : "");
        streamStatus.setOnClickListener(failed ? v -> fetchFeed() : null);
    }

    private void limitPhotoToPage(LinearLayout content, FrameLayout photo, int reserved) {
        var photoLayout = new LinearLayout.LayoutParams(-1, -2);
        photoLayout.gravity = Gravity.CENTER_HORIZONTAL;
        photo.setLayoutParams(photoLayout);
        content.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int available = right - left - v.getPaddingLeft() - v.getPaddingRight();
            int side = Math.max(1, Math.min(available, bottom - top - dp(reserved)));
            if (photoLayout.width != side) {
                photoLayout.width = side;
                photo.setLayoutParams(photoLayout);
            }
        });
    }

    private final class StoryAdapter extends RecyclerView.Adapter<StoryHolder> {
        @Override public int getItemCount() { return 1 + feedCards.size(); }
        @Override public int getItemViewType(int position) { return position == 0 ? 0 : 1; }

        @androidx.annotation.NonNull
        @Override public StoryHolder onCreateViewHolder(@androidx.annotation.NonNull ViewGroup parent, int type) {
            FrameLayout slot = new FrameLayout(parent.getContext());
            slot.setLayoutParams(new RecyclerView.LayoutParams(-1, -1));
            return new StoryHolder(slot);
        }

        @Override public void onBindViewHolder(@androidx.annotation.NonNull StoryHolder holder, int position) {
            FrameLayout slot = (FrameLayout) holder.itemView;
            slot.removeAllViews();
            if (position == 0) {
                if (cameraPanel.getParent() instanceof ViewGroup) ((ViewGroup) cameraPanel.getParent()).removeView(cameraPanel);
                slot.addView(cameraPanel, new FrameLayout.LayoutParams(-1, -1));
                slot.post(MomentsFragment.this::updateCameraVisibility);
                return;
            }
            JSONObject card = feedCards.get(position - 1);
            LinearLayout content = new LinearLayout(slot.getContext());
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(12), dp(12), dp(12), 0);
            FrameLayout picture = photoWithCaption(card, false, null, () -> {
                loadedStories.add(card.optString("id"));
                if (storyPager.getCurrentItem() == position) markSeen(card);
            });
            content.addView(picture);
            limitPhotoToPage(content, picture, 200);
            content.addView(author(card));
            if (!card.optBoolean("own")) content.addView(replyInput(card));
            content.addView(storyTools(card));
            slot.addView(content, new FrameLayout.LayoutParams(-1, -1));
        }
    }

    private static final class StoryHolder extends RecyclerView.ViewHolder {
        StoryHolder(View item) { super(item); }
    }

    private void open(String id) {
        detailId = id;
        detail = null;
        startPage(Page.DETAIL, "Chi tiết ảnh");
        body.addView(text("Đang tải ảnh…", 14, false));
        run(() -> MomentApi.json("GET", MomentApi.route(id), null), item -> {
            detail = item;
            showDetail();
        }, failure -> {
            body.removeAllViews();
            boolean unavailable = failure instanceof MomentApi.Failure && ((MomentApi.Failure) failure).status == 404;
            body.addView(text(unavailable ? "Ảnh đã bị xóa hoặc không còn khả dụng." : "Chưa tải được ảnh.", 16, true));
            if (!unavailable) body.addView(button("Thử lại", false, () -> open(id)));
        });
    }

    private void showDetail() {
        startPage(Page.DETAIL, "Xem tin");
        JSONObject item = detail;
        boolean own = item.optBoolean("own");
        setTitleTool(filterName(), this::chooseAuthor);
        setProfileTool();
        body.addView(photoWithCaption(item, false, this::fullPhoto, () -> markSeen(item)));
        body.addView(author(item));
        if (own) {
            body.addView(text("Người nhận", 16, true));
            JSONArray recipients = item.optJSONArray("recipients");
            for (int index = 0; index < recipients.length(); index++) {
                JSONObject peer = recipients.optJSONObject(index);
                String state = peer.optBoolean("seen") ? "Đã xem" : "Chưa xem";
                String reaction = peer.isNull("reaction") ? "" : peer.optString("reaction");
                body.addView(text(name(friends.get(peer.optString("id"))) + " · " + state + " " + reaction, 14, false));
            }
        } else {
            body.addView(replyInput(item));
        }
        body.addView(storyTools(item));
    }

    private View storyTools(JSONObject item) {
        LinearLayout tools = row();
        tools.setPadding(0, dp(8), 0, dp(5));
        tools.addView(icon(R.drawable.ic_tin_grid, "Xem tất cả tin", this::showAllStories), new LinearLayout.LayoutParams(dp(46), dp(46)));
        tools.addView(new View(requireContext()), new LinearLayout.LayoutParams(0, 1, 1));
        tools.addView(icon(R.drawable.ic_tin_share, "Chia sẻ tin", () -> showOptions(item)), new LinearLayout.LayoutParams(dp(46), dp(46)));
        return tools;
    }

    private void fullPhoto() {
        String id = detailId;
        startPage(Page.FULL, "Ảnh Khoảnh khắc");
        body.addView(photo(id, true, false, null, () -> {
            if (!detail.optBoolean("own") && !detail.optBoolean("seen"))
                run(() -> MomentApi.respond(id, "seen", JSONObject.NULL), seen -> detail = seen, ignored -> {});
        }));
    }

    private void markSeen(JSONObject item) {
        if (item.optBoolean("own") || item.optBoolean("seen")) return;
        String id = item.optString("id");
        try { item.put("seen", true); } catch (org.json.JSONException ignored) { return; }
        run(() -> MomentApi.respond(id, "seen", JSONObject.NULL), seen -> {
            if (detail != null && id.equals(detail.optString("id"))) detail = seen;
        }, ignored -> { try { item.put("seen", false); } catch (org.json.JSONException unavailable) { } });
    }

    private View replyInput(JSONObject item) {
        LinearLayout line = row();
        line.setBackground(controlBackground(CONTROL_SURFACE, 17));
        line.setPadding(dp(12), 0, dp(4), 0);
        var layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(9);
        line.setLayoutParams(layout);
        EditText input = new EditText(requireContext());
        input.setHint("Trả lời tin…");
        input.setTextSize(15);
        input.setTextColor(0xFF102139);
        input.setHintTextColor(0xFF445C77);
        input.setBackground(null);
        input.setMinHeight(dp(46));
        input.setPadding(0, 0, 0, 0);
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});
        line.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout quick = quickReactions(item);
        line.addView(quick);
        ImageButton send = icon(R.drawable.ic_chat_send, "Gửi trả lời", () -> {});
        send.setBackground(null);
        line.addView(send, new LinearLayout.LayoutParams(dp(44), dp(44)));
        String[] retryKey = {null};
        send.setEnabled(false);
        send.setVisibility(View.GONE);
        onText(input, value -> {
            retryKey[0] = null;
            boolean typing = !value.isBlank();
            send.setEnabled(typing);
            send.setVisibility(typing ? View.VISIBLE : View.GONE);
            quick.setVisibility(typing ? View.GONE : View.VISIBLE);
        });
        send.setOnClickListener(v -> {
            String message = input.getText().toString().trim();
            if (message.isEmpty() || busy || !sameUser()) return;
            String owner = item.optString("owner"), id = item.optString("id");
            if (blocked.contains(owner)) return;
            FriendGroupRepository repository = FriendGroupRepositoryProvider.get(requireContext());
            if (repository == null) return;
            hideKeyboard();
            busy = true;
            if (storyAdapter != null) storyPager.setUserInputEnabled(false);
            input.setEnabled(false);
            send.setEnabled(false);
            if (retryKey[0] == null) retryKey[0] = UUID.randomUUID().toString();
            String key = retryKey[0];
            int generation = screen;
            Runnable failed = () -> {
                busy = false;
                if (storyAdapter != null) storyPager.setUserInputEnabled(true);
                input.setEnabled(true); send.setEnabled(true);
                toast("Chưa gửi được trả lời. Hãy thử lại.");
            };
            repository.openDirect(uid, owner, new FriendGroupRepository.Result<String>() {
                @Override public void success(String conversationId) {
                    if (!active(generation)) return;
                    run(() -> MomentApi.json("POST", "/api/moment", new JSONObject().put("id", id)
                                    .put("action", "reply").put("conversationId", conversationId)
                                    .put("value", message).put("nonce", key)),
                            result -> {
                                input.setText(""); input.setEnabled(true);
                                if (storyAdapter != null) storyPager.setUserInputEnabled(true);
                                startActivity(new Intent(requireContext(), ChatActivity.class)
                                        .putExtra(Constants.EXTRA_CONVERSATION_ID, conversationId)
                                        .putExtra(Constants.EXTRA_CONVERSATION_TITLE, item.optString("name"))
                                        .putExtra(Constants.EXTRA_CONVERSATION_TYPE, "direct"));
                            }, failure -> failed.run());
                }
                @Override public void error(String reason) { if (active(generation)) failed.run(); }
            });
        });
        return line;
    }

    private LinearLayout quickReactions(JSONObject item) {
        LinearLayout controls = row();
        String[] quick = {EMOJI[0], EMOJI[1], EMOJI[4]};
        TextView[] buttons = new TextView[quick.length];
        java.util.function.Consumer<String> react = emoji -> {
            if (busy || !sameUser()) return;
            String id = item.optString("id");
            busy = true;
            Object value = emoji.equals(item.optString("reaction")) ? JSONObject.NULL : emoji;
            run(() -> MomentApi.respond(id, "react", value), changed -> {
                if (detail != null && id.equals(detail.optString("id"))) detail = changed;
                try { item.put("reaction", changed.get("reaction")); } catch (org.json.JSONException ignored) { }
                for (int index = 0; index < buttons.length; index++) {
                    boolean active = quick[index].equals(changed.optString("reaction"));
                    buttons[index].setSelected(active);
                    buttons[index].setBackground(active ? rounded(0xFFDFEDFF, 11) : null);
                }
            }, failure -> toast("Chưa gửi được cảm xúc."));
        };
        for (int index = 0; index < quick.length; index++) {
            String emoji = quick[index];
            TextView option = text(emoji, 17, false);
            option.setGravity(Gravity.CENTER);
            option.setSelected(emoji.equals(item.optString("reaction")));
            if (option.isSelected()) option.setBackground(rounded(0xFFDFEDFF, 11));
            option.setOnClickListener(v -> react.accept(emoji));
            buttons[index] = option;
            controls.addView(option, new LinearLayout.LayoutParams(dp(35), dp(44)));
        }
        ImageButton more = icon(R.drawable.ic_chat_smile, "Thêm cảm xúc", () ->
                new MaterialAlertDialogBuilder(requireContext()).setTitle("Cảm xúc về tin").setItems(EMOJI,
                        (dialog, index) -> react.accept(EMOJI[index])).show());
        more.setBackground(null);
        controls.addView(more, new LinearLayout.LayoutParams(dp(41), dp(44)));
        return controls;
    }

    private void showOptions(JSONObject item) {
        if (busy) return;
        String id = item.optString("id");
        boolean own = item.optBoolean("own");
        var sheet = new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
        LinearLayout menu = new LinearLayout(requireContext());
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackground(rounded(CONTROL_SURFACE, 22));
        menu.setPadding(dp(16), dp(12), dp(16), dp(16));
        TextView heading = text("Chia sẻ tin", 19, true);
        heading.setGravity(Gravity.CENTER);
        menu.addView(heading);
        FrameLayout thumb = photoWithCaption(item, false, null, () -> {});
        LinearLayout.LayoutParams thumbnail = new LinearLayout.LayoutParams(dp(170), -2);
        thumbnail.gravity = Gravity.CENTER_HORIZONTAL;
        thumbnail.bottomMargin = dp(14);
        menu.addView(thumb, thumbnail);
        LinearLayout actions = row();
        shareChoice(actions, R.drawable.ic_tin_download, "Lưu", false, () -> { sheet.dismiss(); saveImage(() -> MomentApi.bytes(id)); });
        shareChoice(actions, R.drawable.ic_tin_share, "Chia sẻ", false, () -> {
            sheet.dismiss();
            String link = "https://utechat-ab9d8.web.app/?moment=" + id;
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), "Chia sẻ tin"));
        });
        if (!own) {
            shareChoice(actions, R.drawable.ic_chat_warning, "Báo cáo", false, () -> { sheet.dismiss(); report(id); });
            shareChoice(actions, R.drawable.ic_chat_block, "Chặn", false, () -> {
                sheet.dismiss();
                UserSafety.confirmBlock(requireActivity(), item.optString("owner"), item.optString("name"), false, () -> showFeed(view));
            });
        }
        shareChoice(actions, R.drawable.ic_chat_trash, "Xóa", true, () -> {
            sheet.dismiss();
            new MaterialAlertDialogBuilder(requireContext()).setTitle("Xóa tin?")
                    .setMessage(own ? "Ảnh sẽ biến mất khỏi Tin của bạn và của người nhận." : "Tin sẽ được ẩn khỏi bảng Tin của riêng bạn.")
                    .setNegativeButton("Hủy", null).setPositiveButton("Xóa tin", (dialog, choice) -> {
                        busy = true;
                        run(() -> own ? MomentApi.json("DELETE", MomentApi.route(id), null)
                                        : MomentApi.respond(id, "hide", JSONObject.NULL),
                                result -> {
                                    if (!own) MomentWidget.invalidate(requireContext());
                                    toast(own ? "Đã xóa tin." : "Đã ẩn tin.");
                                    showFeed(own ? "mine" : view);
                                }, failure -> toast("Chưa xóa được ảnh. Hãy thử lại."));
                    }).show();
        });
        menu.addView(actions);
        sheet.setContentView(menu);
        sheet.show();
    }

    private void shareChoice(LinearLayout actions, int drawable, String label, boolean danger, Runnable click) {
        LinearLayout choice = new LinearLayout(requireContext());
        choice.setOrientation(LinearLayout.VERTICAL);
        choice.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageButton symbol = icon(drawable, label, click);
        styleTool(symbol, danger ? 0xFFFFE6EB : 0xFFE6EFFA, danger ? 0xFFA42536 : CONTROL_COLOR, 15, 11);
        choice.addView(symbol, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView text = text(label, 12, true);
        text.setTextColor(danger ? 0xFFA42536 : CONTROL_COLOR);
        text.setGravity(Gravity.CENTER);
        text.setOnClickListener(v -> click.run());
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        choice.addView(text, new LinearLayout.LayoutParams(-1, -2));
        actions.addView(choice, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void saveImage(Callable<byte[]> load) {
        var resolver = requireContext().getContentResolver();
        run(() -> {
            byte[] bytes = load.call();
            if (Build.VERSION.SDK_INT < 29) return bytes;
            var values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "UTEChat-Tin-" + System.currentTimeMillis() + ".jpg");
            values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UTEChat");
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
            Uri target = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (target == null) throw new IOException("Cannot create local photo");
            try {
                try (var output = resolver.openOutputStream(target)) {
                    if (output == null) throw new IOException("Cannot open local photo");
                    output.write(bytes);
                }
                values.clear();
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(target, values, null, null);
            } catch (Exception error) {
                resolver.delete(target, null, null);
                throw error;
            }
            return null;
        }, bytes -> {
            if (bytes == null) toast("Đã lưu ảnh vào album.");
            else {
                exportBytes = bytes;
                saveDocument.launch("UTEChat-Tin.jpg");
            }
        }, failure -> toast("Chưa lưu được ảnh."));
    }

    private void report(String id) {
        int[] chosen = {0};
        String[] reasons = {"spam", "abuse", "other"};
        new MaterialAlertDialogBuilder(requireContext()).setTitle("Báo cáo ảnh")
                .setSingleChoiceItems(new String[]{"Tin rác", "Nội dung không phù hợp", "Lý do khác"},
                        0, (dialog, which) -> chosen[0] = which)
                .setNegativeButton("Hủy", null).setPositiveButton("Gửi báo cáo", (dialog, choice) -> {
                    busy = true;
                    run(() -> MomentApi.respond(id, "report", reasons[chosen[0]]),
                            result -> toast("Đã gửi báo cáo."), failure -> toast("Chưa gửi được báo cáo."));
                }).show();
    }

    private void setToolbar(int icon, String description, Runnable click) {
        Glide.with(this).clear(toolbarAction);
        styleTool(toolbarAction, CONTROL_SURFACE, CONTROL_COLOR, 15, 12);
        toolbarAction.setImageResource(icon);
        toolbarAction.setContentDescription(description);
        toolbarAction.setVisibility(View.VISIBLE);
        toolbarAction.setOnClickListener(v -> click.run());
    }

    private void setTitleTool(String label, Runnable click) {
        title.setText(label + "  ▾");
        title.setBackground(controlBackground(CONTROL_SURFACE, 17));
        title.setOnClickListener(v -> { if (!busy) click.run(); });
    }

    private void setProfileTool() {
        setToolbar(R.drawable.ic_nav_profile, "Mở hồ sơ của bạn", () -> ((MainActivity) requireActivity()).openProfile());
        if (ownPhoto != null && !ownPhoto.isBlank()) {
            toolbarAction.setPadding(dp(4), dp(4), dp(4), dp(4));
            toolbarAction.setImageTintList(null);
            Glide.with(this).load(ownPhoto).circleCrop().error(R.drawable.ic_nav_profile).into(toolbarAction);
        }
    }

    private void showCamera() {
        cameraPanel = new LinearLayout(requireContext());
        cameraPanel.setOrientation(LinearLayout.VERTICAL);
        cameraPanel.setPadding(dp(12), dp(12), dp(12), 0);
        FrameLayout cameraFrame = squareFrame();
        cameraFrame.setBackground(rounded(0xFFD4DEEA, 28));
        cameraView = new PreviewView(requireContext());
        cameraView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        cameraView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        cameraView.setContentDescription("Xem trước camera");
        cameraFrame.addView(cameraView, new FrameLayout.LayoutParams(-1, -1));
        cameraFrame.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (v == cameraViewParent()) positionCamera();
        });
        cameraEffect = imageView();
        cameraEffect.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cameraEffect.setVisibility(View.GONE);
        cameraFrame.addView(cameraEffect, new FrameLayout.LayoutParams(-1, -1));
        captureStatus = text("", 15, true);
        captureStatus.setGravity(Gravity.CENTER);
        captureStatus.setTextColor(CONTROL_COLOR);
        captureStatus.setBackground(controlBackground(0xF5FBFDFF, 13));
        FrameLayout.LayoutParams statusLayout = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        captureStatus.setPadding(dp(14), dp(7), dp(14), dp(7));
        cameraFrame.addView(captureStatus, statusLayout);
        effectStatus = text("", 13, true);
        effectStatus.setPadding(dp(10), dp(5), dp(10), dp(5));
        effectStatus.setBackground(controlBackground(CONTROL_SURFACE, 12));
        effectStatus.setVisibility(View.GONE);
        FrameLayout.LayoutParams hintLayout = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintLayout.bottomMargin = dp(10);
        cameraFrame.addView(effectStatus, hintLayout);
        filtersButton = icon(R.drawable.ic_tin_filter, "Bộ lọc và khung ảnh", this::chooseTone);
        FrameLayout.LayoutParams filterLayout = new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP | Gravity.END);
        filterLayout.setMargins(dp(12), dp(12), dp(12), 0);
        cameraFrame.addView(filtersButton, filterLayout);
        flashButton = icon(R.drawable.ic_tin_flash, "Bật đèn", () -> {
            flash = !flash;
            liveCamera.setFlash(flash);
            flashButton.setContentDescription(flash ? "Tắt đèn" : "Bật đèn");
            flashButton.setSelected(flash);
        });
        FrameLayout.LayoutParams flashLayout = new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP | Gravity.START);
        flashLayout.setMargins(dp(12), dp(12), dp(12), 0);
        cameraFrame.addView(flashButton, flashLayout);
        cameraPanel.addView(cameraFrame);
        limitPhotoToPage(cameraPanel, cameraFrame, 195);
        shotStrip = row();
        cameraPanel.addView(shotStrip);
        LinearLayout controls = row();
        controls.setPadding(0, dp(16), 0, dp(10));
        albumButton = icon(R.drawable.ic_chat_gallery, "Chọn ảnh từ album", () -> album.launch("image/*"));
        setCaptureSide(controls, albumButton);
        shutterButton = icon(0, "Chụp ảnh", this::capturePhoto);
        GradientDrawable outer = rounded(requireContext().getColor(R.color.chat_canvas), 38);
        outer.setStroke(dp(3), 0xFF087AF0);
        GradientDrawable center = rounded(CONTROL_SURFACE, 31);
        var shutter = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{outer, center});
        shutter.setLayerInset(1, dp(7), dp(7), dp(7), dp(7));
        shutterButton.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x3F087AF0), shutter, null));
        controls.addView(shutterButton, new LinearLayout.LayoutParams(dp(72), dp(72)));
        setCaptureSide(controls, icon(R.drawable.ic_call_switch_camera, "Đổi camera trước/sau", () -> {
            if (capturing) return;
            liveCamera.flip();
            liveCamera.release();
            cameraOpening = false;
            resumeCamera();
        }));
        cameraPanel.addView(controls);
        LinearLayout tools = row();
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.addView(new View(requireContext()), new LinearLayout.LayoutParams(dp(46), 1));
        cameraHint = text("", 14, true);
        cameraHint.setGravity(Gravity.CENTER);
        tools.addView(cameraHint, new LinearLayout.LayoutParams(0, -2, 1));
        tools.addView(icon(R.drawable.ic_tin_grid, "Xem tất cả tin", this::showAllStories),
                new LinearLayout.LayoutParams(dp(46), dp(46)));
        cameraPanel.addView(tools);
        refreshCapture();
    }

    private void setCaptureSide(LinearLayout row, ImageButton button) {
        FrameLayout slot = new FrameLayout(requireContext());
        slot.addView(button, new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER));
        row.addView(slot, new LinearLayout.LayoutParams(0, dp(56), 1));
    }

    private void chooseTone() {
        var sheet = new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
        LinearLayout panel = new LinearLayout(requireContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(18));
        panel.setBackground(rounded(CONTROL_SURFACE, 22));
        TextView heading = text("Bộ lọc và khung ảnh", 19, true);
        heading.setGravity(Gravity.CENTER);
        panel.addView(heading);

        String[] groups = {"booth", "trend", "funny"};
        String[] labels = {"Khung & Photobooth", "Khung viền & Xu hướng", "Hài hước"};
        for (int group = 0; group < groups.length; group++) {
            LinearLayout ribbon = filterRibbon(panel, labels[group]);
            for (TinFilters.Filter option : filterCatalog.entries) {
                if (!groups[group].equals(option.group)) continue;
                filterChoice(ribbon, filterThumb(option), option.label, option.id.equals(frame.id), () -> {
                    frame = option;
                    cameraEffect.setVisibility(View.GONE);
                    effectStatus.setVisibility(View.GONE);
                    refreshCapture();
                    sheet.dismiss();
                });
            }
        }

        int[] swatches = {0xFFB9CCE7, 0xFFFDAAB0, 0xFF91CEF3, 0xFF949FAC};
        LinearLayout colors = filterRibbon(panel, "Màu ảnh");
        for (TinPhotos.Tone color : TinPhotos.Tone.values()) {
            View swatch = new View(requireContext());
            swatch.setBackground(rounded(swatches[color.ordinal()], 11));
            filterChoice(colors, swatch, color.label, color == tone, () -> {
                tone = color;
                refreshCapture();
                sheet.dismiss();
            });
        }
        ScrollView scroller = new ScrollView(requireContext());
        scroller.addView(panel);
        sheet.setContentView(scroller);
        sheet.show();
    }

    private View filterThumb(TinFilters.Filter option) {
        if (option.art != null) {
            ImageView swatch = imageView();
            swatch.setScaleType(ImageView.ScaleType.FIT_CENTER);
            swatch.setBackground(rounded(option.anchor == null ? 0xFF2E415A : 0xFFEDF2F9, 9));
            swatch.setClipToOutline(true);
            if (option.anchor != null) swatch.setPadding(dp(2), dp(4), dp(2), dp(4));
            Glide.with(this).load(filterCatalog.previewUri(option)).override(dp(64)).into(swatch);
            return swatch;
        }
        return new View(requireContext()) {
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                canvas.save();
                canvas.scale(getWidth() / (float) TinPhotos.SIZE, getHeight() / (float) TinPhotos.SIZE);
                TinPhotos.drawFrame(canvas, java.util.List.of(), -1, option, TinPhotos.Tone.NORMAL, null);
                canvas.restore();
            }
        };
    }

    private LinearLayout filterRibbon(LinearLayout panel, String label) {
        panel.addView(text(label, 15, true));
        var scroll = new android.widget.HorizontalScrollView(requireContext());
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout options = row();
        scroll.addView(options);
        panel.addView(scroll);
        return options;
    }

    private void filterChoice(LinearLayout ribbon, View preview, String label, boolean selected, Runnable choose) {
        LinearLayout tile = new LinearLayout(requireContext());
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(4), dp(5), dp(4), 0);
        if (selected) tile.setBackground(rounded(0xFFDFEDFF, 12));
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(preview, new LinearLayout.LayoutParams(dp(51), dp(51)));
        TextView text = text(label, 12, selected);
        text.setGravity(Gravity.CENTER);
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(text, new LinearLayout.LayoutParams(-1, -2));
        tile.setContentDescription(label);
        tile.setSelected(selected);
        tile.setOnClickListener(v -> choose.run());
        ribbon.addView(tile, new LinearLayout.LayoutParams(dp(64), -2));
    }

    private void refreshCapture() {
        if (cameraView == null) return;
        if (storyAdapter != null) storyPager.setUserInputEnabled(!capturing && !busy);
        boolean permitted = requireContext().checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        captureStatus.setVisibility(liveCamera.ready() ? View.GONE : View.VISIBLE);
        captureStatus.setText(cameraUnavailable ? "Chưa mở được camera" : cameraOpening ? "Đang mở camera…"
                : permitted ? "Máy ảnh đang tạm dừng" : "Nhấn chụp để mở camera");
        shutterButton.setEnabled(!capturing);
        albumButton.setEnabled(!capturing);
        filtersButton.setEnabled(!capturing && shots.isEmpty());
        flashButton.setVisibility(liveCamera.hasFlash() ? View.VISIBLE : View.GONE);
        android.graphics.Paint color = new android.graphics.Paint();
        color.setColorFilter(tone.filter());
        cameraView.setLayerType(tone == TinPhotos.Tone.NORMAL ? View.LAYER_TYPE_NONE : View.LAYER_TYPE_HARDWARE,
                tone == TinPhotos.Tone.NORMAL ? null : color);
        positionCamera();
        main.removeCallbacks(effectTick);
        if (effectActive()) main.post(effectTick);
        else {
            cameraEffect.setVisibility(View.GONE);
            cameraEffect.setImageDrawable(null);
            effectStatus.setVisibility(View.GONE);
        }
        shotStrip.removeAllViews();
        if (frame.cells > 1) {
            TextView progress = text(shots.size() + "/" + frame.cells + " ảnh", 14, true);
            progress.setTextColor(CONTROL_COLOR);
            shotStrip.addView(progress, new LinearLayout.LayoutParams(0, -2, 1));
            for (File shot : shots) {
                ImageView image = imageView();
                Glide.with(this).load(shot).diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                        .skipMemoryCache(true).centerCrop().into(image);
                var lp = new LinearLayout.LayoutParams(dp(28), dp(28));
                lp.setMargins(dp(2), 0, dp(2), 0);
                shotStrip.addView(image, lp);
            }
            if (!shots.isEmpty() && !capturing) shotStrip.addView(button("Bỏ bộ ảnh", false, () -> {
                clearShots();
                refreshCapture();
            }));
        }
    }

    private View cameraViewParent() {
        return cameraView == null ? null : (View) cameraView.getParent();
    }

    private void positionCamera() {
        View parent = cameraViewParent();
        if (parent == null || parent.getWidth() == 0 || parent.getHeight() == 0) return;
        var cell = frame.cell(Math.min(shots.size(), frame.cells - 1));
        float x = parent.getWidth() / (float) TinPhotos.SIZE, y = parent.getHeight() / (float) TinPhotos.SIZE;
        int left = Math.round(cell.left * x), top = Math.round(cell.top * y);
        int width = Math.round(cell.right * x) - left, height = Math.round(cell.bottom * y) - top;
        var layout = (FrameLayout.LayoutParams) cameraView.getLayoutParams();
        if (layout.leftMargin == left && layout.topMargin == top && layout.width == width && layout.height == height) return;
        var next = new FrameLayout.LayoutParams(width, height, Gravity.TOP | Gravity.LEFT);
        next.leftMargin = left;
        next.topMargin = top;
        cameraView.setLayoutParams(next);
    }

    private boolean effectActive() {
        return cameraView != null && liveCamera.ready() && !isHidden() && isResumed() && !capturing &&
                !isNaturalFrame();
    }

    private boolean isNaturalFrame() {
        return "natural".equals(frame.id);
    }

    private void renderEffect() {
        if (!effectActive() || renderingEffect) return;
        TinFilters.Filter chosen = frame;
        Bitmap raw = chosen.anchor == null ? null : cameraView.getBitmap();
        if (chosen.anchor != null && raw == null) {
            main.postDelayed(effectTick, 150);
            return;
        }
        int generation = screen;
        int capturedCount = shots.size();
        var paths = new java.util.ArrayList<>(shots);
        TinPhotos.Tone color = tone;
        renderingEffect = true;
        TinFilters catalog = filterCatalog;
        effects.execute(() -> {
            Bitmap preview = null;
            String hint = "";
            try {
                int size = raw == null ? 480 : 320;
                preview = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                var paint = new android.graphics.Canvas(preview);
                Bitmap art = catalog.artwork(chosen);
                if (raw == null) {
                    paint.scale(.5f, .5f);
                    TinPhotos.drawFrame(paint, previewShots(paths), capturedCount, chosen, color, art);
                } else {
                    Bitmap sample = Bitmap.createScaledBitmap(raw, size, size, true);
                    java.util.List<com.google.mlkit.vision.face.Face> detected;
                    try { detected = faces.detect(sample); }
                    finally { if (sample != raw) sample.recycle(); }
                    if (detected.isEmpty()) hint = "Đưa khuôn mặt vào giữa khung";
                    else TinFaces.place(paint, art, chosen, detected);
                }
            } catch (Exception failure) {
                if (preview != null) preview.recycle();
                preview = null;
                hint = "Chưa mở được hiệu ứng";
            } finally { if (raw != null) raw.recycle(); }
            Bitmap result = preview;
            String status = hint;
            main.post(() -> {
                renderingEffect = false;
                boolean current = active(generation) && effectActive() && chosen == frame &&
                        color == tone && capturedCount == shots.size();
                if (current) {
                    if (result != null) {
                        cameraEffect.setImageBitmap(result);
                        cameraEffect.setVisibility(View.VISIBLE);
                    }
                    effectStatus.setText(status);
                    effectStatus.setVisibility(status.isBlank() ? View.GONE : View.VISIBLE);
                } else if (result != null) result.recycle();
                if (effectActive() && (!current || frame.anchor != null)) {
                    main.removeCallbacks(effectTick);
                    main.postDelayed(effectTick, frame.anchor == null ? 0 : 175);
                }
            });
        });
    }

    private java.util.List<Bitmap> previewShots(java.util.List<File> paths) throws IOException {
        if (previewPaths.equals(paths)) return previewPhotos;
        var next = new java.util.ArrayList<Bitmap>(paths.size());
        try {
            for (File file : paths) {
                Bitmap source = TinPhotos.decode(file);
                int largest = Math.max(source.getWidth(), source.getHeight());
                if (largest > 480) {
                    Bitmap resized = Bitmap.createScaledBitmap(source, Math.max(1, source.getWidth() * 480 / largest),
                            Math.max(1, source.getHeight() * 480 / largest), true);
                    if (resized != source) source.recycle();
                    source = resized;
                }
                next.add(source);
            }
        } catch (Exception badFile) {
            for (Bitmap image : next) image.recycle();
            throw badFile;
        }
        for (Bitmap image : previewPhotos) image.recycle();
        previewPaths = paths;
        previewPhotos = next;
        return next;
    }

    private void updateCameraVisibility() {
        View parent = cameraViewParent();
        if (parent == null || storyIndex >= 0 || isHidden() || !isResumed()) return;
        var visible = new android.graphics.Rect();
        if (parent.getGlobalVisibleRect(visible) && visible.height() >= dp(75)) {
            if (!cameraUnavailable) resumeCamera();
        } else if (liveCamera.ready() || cameraOpening) {
            liveCamera.release();
            cameraOpening = false;
            refreshCapture();
        }
    }

    private void resumeCamera() {
        if (cameraView == null || storyIndex >= 0 || isHidden() || !isResumed() || liveCamera.ready() || cameraOpening
                || requireContext().checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        cameraOpening = true;
        cameraUnavailable = false;
        refreshCapture();
        liveCamera.open(requireContext(), getViewLifecycleOwner(), cameraView, () -> {
            cameraOpening = false;
            flash = false;
            flashButton.setContentDescription("Bật đèn");
            flashButton.setSelected(false);
            refreshCapture();
        }, () -> {
            cameraOpening = false;
            cameraUnavailable = true;
            refreshCapture();
        });
    }

    private void cameraHelp() {
        new MaterialAlertDialogBuilder(requireContext()).setTitle("Cho phép sử dụng camera")
                .setMessage("Bạn có thể bật quyền camera trong Cài đặt hoặc chọn ảnh từ Album.")
                .setNegativeButton("Để sau", null).setPositiveButton("Mở cài đặt", (dialog, which) ->
                        startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", requireContext().getPackageName(), null)))).show();
    }

    private File temporaryPhoto(String prefix) throws IOException {
        File directory = captureDirectory();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create photo directory");
        return File.createTempFile(prefix, ".jpg", directory);
    }

    private void capturePhoto() {
        if (capturing) return;
        if (requireContext().checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA);
            return;
        }
        if (!liveCamera.ready()) { resumeCamera(); return; }
        try {
            File source = temporaryPhoto("capture-");
            captureFile = source;
            capturing = true;
            refreshCapture();
            int generation = screen;
            liveCamera.take(source, ContextCompat.getMainExecutor(requireContext()), () -> {
                captureFile = null;
                if (active(generation)) acceptPhoto(source); else { capturing = false; source.delete(); }
            }, () -> {
                captureFile = null;
                capturing = false;
                source.delete();
                if (active(generation)) { refreshCapture(); toast("Chưa chụp được ảnh."); }
            });
        } catch (IOException failure) { toast("Chưa lưu được ảnh vừa chụp."); }
    }

    private void albumResult(Uri uri) {
        if (body == null || page != Page.FEED || grid || uri == null || capturing) return;
        try {
            File target = temporaryPhoto("capture-");
            var resolver = requireContext().getContentResolver();
            int generation = screen;
            capturing = true;
            refreshCapture();
            photos.execute(() -> {
                try (var input = resolver.openInputStream(uri); var output = Files.newOutputStream(target.toPath())) {
                    if (input == null) throw new IOException("Cannot read album photo");
                    byte[] buffer = new byte[32768];
                    int total = 0;
                    for (int count; (count = input.read(buffer)) != -1;) {
                        total += count;
                        if (total > 32 * 1024 * 1024) throw new IOException("Album photo too large");
                        output.write(buffer, 0, count);
                    }
                    main.post(() -> {
                        if (active(generation)) acceptPhoto(target);
                        else { capturing = false; target.delete(); }
                    });
                } catch (Exception failure) {
                    target.delete();
                    main.post(() -> {
                        capturing = false;
                        if (active(generation)) { refreshCapture(); toast("Không mở được ảnh trong album."); }
                    });
                }
            });
        } catch (IOException error) { toast("Không mở được ảnh trong album."); }
    }

    private void acceptPhoto(File source) {
        int generation = screen;
        File directory = captureDirectory();
        var images = new java.util.ArrayList<>(shots);
        images.add(source);
        TinFilters.Filter chosen = frame;
        TinFilters catalog = filterCatalog;
        TinPhotos.Tone color = tone;
        photos.execute(() -> {
            File output = null;
            try {
                if (images.size() == chosen.cells) {
                    output = File.createTempFile("draft-", ".jpg", directory);
                    Files.write(output.toPath(), TinPhotos.compose(images, chosen, color, catalog.artwork(chosen), faces));
                } else TinPhotos.decode(source).recycle();
                File completed = output;
                main.post(() -> {
                    capturing = false;
                    if (!active(generation)) {
                        source.delete();
                        if (completed != null) completed.delete();
                        return;
                    }
                    if (completed == null) {
                        shots.add(source);
                        refreshCapture();
                        return;
                    }
                    source.delete();
                    clearShots();
                    clearDraft();
                    allViewers = true;
                    draftFile = completed;
                    showPreview();
                });
            } catch (Exception failure) {
                source.delete();
                if (output != null) output.delete();
                main.post(() -> {
                    capturing = false;
                    if (active(generation)) {
                        refreshCapture();
                        toast(failure instanceof TinFaces.NoFace
                                ? "Chưa thấy khuôn mặt. Hãy đưa mặt vào giữa khung hoặc chọn ảnh chân dung."
                                : "Không xử lý được ảnh. Hãy thử lại.");
                    }
                });
            }
        });
    }

    private void clearShots() {
        for (File file : shots) file.delete();
        shots.clear();
    }

    private void showPreview() {
        startPage(Page.PREVIEW, "Gửi đến");
        setTitleTool("Gửi đến", this::showPicker);
        File saved = draftFile;
        setToolbar(R.drawable.ic_tin_download, "Lưu ảnh về máy",
                () -> { if (!busy) saveImage(() -> Files.readAllBytes(saved.toPath())); });
        FrameLayout frame = squareFrame();
        ImageView image = imageView();
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        Glide.with(this).load(draftFile).diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                .skipMemoryCache(true).centerCrop().into(image);
        TextView label = captionLabel(caption.isBlank() ? "Thêm chú thích" : caption, captionStyle);
        label.setOnClickListener(v -> {
            if (!busy) editCaption("Chú thích", "Thêm chú thích", caption, captionStyle.isBlank() ? "accent" : captionStyle);
        });
        placeCaption(frame, label);
        body.addView(frame);
        LinearLayout controls = row();
        controls.setPadding(0, dp(16), 0, dp(12));
        setCaptureSide(controls, icon(R.drawable.ic_tin_close, "Bỏ ảnh", this::back));
        postButton = icon(R.drawable.ic_chat_send, "Đăng", this::send);
        styleTool(postButton, 0xFF087AF0, Color.WHITE, 24, 21);
        controls.addView(postButton, new LinearLayout.LayoutParams(dp(70), dp(67)));
        setCaptureSide(controls, icon(R.drawable.ic_tin_caption, "Chọn chú thích", () -> { if (!busy) showCaptions(); }));
        body.addView(controls);
        var audienceScroll = new android.widget.HorizontalScrollView(requireContext());
        audienceScroll.setHorizontalScrollBarEnabled(false);
        audienceScroll.setFillViewport(true);
        previewAudience = row();
        previewAudience.setGravity(Gravity.CENTER);
        previewAudience.setPadding(dp(3), dp(2), dp(3), dp(3));
        audienceScroll.addView(previewAudience);
        body.addView(audienceScroll);
        feedback = text(sendError, 14, false);
        feedback.setTextColor(0xFF9C2031);
        body.addView(feedback);
        updatePreviewAudience();
    }

    private void showCaptions() {
        String[] styles = {"accent", "rose", "mint", "rating", "music", "place", "weather", "time", "rose", "mint", "accent"};
        String[] labels = {"Văn bản", "Màu hồng", "Màu xanh", "Đánh giá", "Đang nghe", "Vị trí", "Thời tiết",
                "Giờ hiện tại", "🤳 Hôm nay", "🌿 Một chút bình yên", "☀️ Chào ngày mới"};
        String[] prefixes = {"", "", "", "⭐ ", "🎵 ", "📍 ", "☀️ "};
        String[] hints = {"Nhập chú thích", "Nhập chú thích", "Nhập chú thích", "Ví dụ 5/5", "Tên bài hát",
                "Tên địa điểm", "Ví dụ Trời nắng"};
        var sheet = new com.google.android.material.bottomsheet.BottomSheetDialog(requireContext());
        LinearLayout panel = new LinearLayout(requireContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(16));
        panel.addView(text("Chú thích", 19, true));
        for (int first = 0; first < labels.length; first += 2) {
            LinearLayout choices = row();
            for (int index = first; index < Math.min(first + 2, labels.length); index++) {
                int option = index;
                chip(choices, labels[option], styles[option].equals(captionStyle), () -> {
                    sheet.dismiss();
                    if (option == 7) chooseCaption(android.text.format.DateFormat.getTimeFormat(requireContext())
                            .format(new java.util.Date()), styles[option]);
                    else if (option > 7) chooseCaption(labels[option], styles[option]);
                    else editCaption(labels[option], hints[option],
                            captionStyle.equals(styles[option]) ? caption : prefixes[option], styles[option]);
                });
            }
            panel.addView(choices);
        }
        panel.addView(button("Xóa chú thích", false, () -> { sheet.dismiss(); chooseCaption("", ""); }));
        ScrollView content = new ScrollView(requireContext());
        content.setBackground(rounded(CONTROL_SURFACE, 22));
        content.addView(panel);
        sheet.setContentView(content);
        sheet.show();
    }

    private void editCaption(String title, String hint, String value, String style) {
        EditText field = new EditText(requireContext());
        field.setHint(hint);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES |
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(150)});
        field.setText(value);
        field.setSelection(field.length());
        var dialog = new MaterialAlertDialogBuilder(requireContext()).setTitle(title).setView(field)
                .setNegativeButton("Hủy", null).setPositiveButton("Xong", (window, which) ->
                        chooseCaption(field.getText().toString().trim(), style)).create();
        dialog.setOnShowListener(window -> {
            field.requestFocus();
            if (dialog.getWindow() != null)
                dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });
        dialog.show();
    }

    private void chooseCaption(String value, String style) {
        caption = value;
        captionStyle = value.isBlank() ? "" : style;
        nonce = null;
        showPreview();
    }

    private java.util.List<String> audience() {
        var result = new java.util.ArrayList<String>();
        for (String friend : friends.keySet()) {
            if (!blocked.contains(friend) && (allViewers || selected.contains(friend))) result.add(friend);
        }
        return result;
    }

    private void updatePreviewAudience() {
        if (previewAudience == null || page != Page.PREVIEW) return;
        int count = audience().size();
        previewAudience.removeAllViews();
        addAudienceChoice("Tất cả", null, allViewers, () -> {
            allViewers = true;
            selected.clear();
            nonce = null;
            updatePreviewAudience();
        });
        for (User friend : friends.values()) {
            String id = friend.getUid();
            if (blocked.contains(id)) continue;
            addAudienceChoice(name(friend), friend, !allViewers && selected.contains(id), () -> {
                if (allViewers) {
                    allViewers = false;
                    selected.clear();
                    selected.add(id);
                } else if (!selected.remove(id)) selected.add(id);
                nonce = null;
                updatePreviewAudience();
            });
        }
        postButton.setEnabled(!busy && count > 0 && count <= 100);
        postButton.setAlpha(postButton.isEnabled() ? 1f : .53f);
        postButton.setContentDescription(busy ? "Đang đăng…" : sendError.isEmpty() ? "Đăng" : "Đăng lại");
        String message = !sendError.isEmpty() ? sendError : !friendsLoaded && !friendsFailed ? "Đang tải bạn bè…"
                : count == 0 ? "Chọn ít nhất một bạn để đăng tin." : count > 100 ? "Chọn tối đa 100 bạn cho một tin." : "";
        feedback.setText(message);
        feedback.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void addAudienceChoice(String label, User friend, boolean active, Runnable choose) {
        LinearLayout choice = new LinearLayout(requireContext());
        choice.setOrientation(LinearLayout.VERTICAL);
        choice.setGravity(Gravity.CENTER_HORIZONTAL);
        choice.setEnabled(!busy);
        choice.setSelected(active);
        choice.setContentDescription(label);
        choice.setOnClickListener(v -> { if (!busy) choose.run(); });

        var circle = new FrameLayout(requireContext());
        GradientDrawable border = rounded(CONTROL_SURFACE, 26);
        border.setStroke(dp(2), active ? 0xFF087AF0 : 0xFFCDD6E1);
        circle.setBackground(border);
        ImageView image = avatarImage(friend == null ? null : friend.getPhotoUrl(),
                friend == null ? R.drawable.ic_chat_group : R.drawable.ic_nav_profile);
        circle.addView(image, new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER));
        choice.addView(circle, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView name = text(label, 12, active);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        choice.addView(name, new LinearLayout.LayoutParams(-1, -2));
        previewAudience.addView(choice, new LinearLayout.LayoutParams(dp(64), -2));
    }

    private void showPicker() {
        startPage(Page.PICKER, "Chọn bạn");
        EditText search = new EditText(requireContext());
        search.setSingleLine(true);
        search.setHint("Tìm theo tên");
        search.setText(friendQuery);
        body.addView(search);
        selectedCount = text("", 14, true);
        body.addView(selectedCount);
        pickerRows = new LinearLayout(requireContext());
        pickerRows.setOrientation(LinearLayout.VERTICAL);
        body.addView(pickerRows);
        feedback = text(sendError, 14, false);
        feedback.setTextColor(0xFF9C2031);
        body.addView(feedback);
        body.addView(button("Xong", true, () -> { hideKeyboard(); showPreview(); }));
        onText(search, query -> { friendQuery = query; updatePicker(); });
        updatePicker();
    }

    private void updatePicker() {
        pickerRows.removeAllViews();
        MaterialCheckBox all = new MaterialCheckBox(requireContext());
        all.setText("Tất cả bạn bè");
        all.setMinHeight(dp(48));
        all.setChecked(allViewers);
        all.setOnCheckedChangeListener((v, checked) -> {
            allViewers = checked;
            selected.clear();
            nonce = null;
            updatePicker();
        });
        pickerRows.addView(all);
        if (!friendsLoaded && !friendsFailed) pickerRows.addView(text("Đang tải bạn bè…", 14, false));
        String query = friendQuery.trim().toLowerCase(Locale.ROOT);
        int matching = 0;
        for (User user : friends.values()) {
            String id = user.getUid();
            if (blocked.contains(id) || !name(user).toLowerCase(Locale.ROOT).contains(query)) continue;
            matching++;
            MaterialCheckBox box = new MaterialCheckBox(requireContext());
            box.setMinHeight(dp(48));
            box.setText(name(user));
            box.setChecked(allViewers || selected.contains(id));
            box.setOnCheckedChangeListener((v, checked) -> {
                if (allViewers) {
                    selected.addAll(audience());
                    allViewers = false;
                }
                if (checked) selected.add(id); else selected.remove(id);
                nonce = null;
                updatePicker();
                View replacement = pickerRows.findViewWithTag(id);
                if (replacement != null) replacement.requestFocus();
            });
            box.setTag(id);
            pickerRows.addView(box);
        }
        if (matching == 0 && (friendsLoaded || friendsFailed)) {
            pickerRows.addView(text(friendsFailed ? "Chưa tải được bạn bè."
                    : query.isEmpty() ? "Bạn chưa có bạn bè nhận ảnh." : "Không tìm thấy bạn phù hợp.", 14, false));
            if (friendsFailed) pickerRows.addView(button("Thử lại", false, () -> {
                if (friendshipSubscription != null) friendshipSubscription.remove();
                if (blockedSubscription != null) blockedSubscription.remove();
                friendsFailed = false;
                watchFriends();
                updatePicker();
            }));
            else if (query.isEmpty()) pickerRows.addView(button("Tìm bạn", false, () ->
                    startActivity(new Intent(requireContext(), com.example.utechat.feature.search.ui.SearchActivity.class))));
        }
        selectedCount.setText("Đã chọn " + audience().size() + " bạn");
        feedback.setText(sendError);
        feedback.setVisibility(sendError.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void send() {
        var people = audience();
        if (busy || people.isEmpty() || people.size() > 100 || draftFile == null) return;
        hideKeyboard();
        people.sort(String::compareTo);
        String selection = String.join(",", people);
        if (nonce == null || !nonceAudience.equals(selection)) {
            nonce = UUID.randomUUID().toString();
            nonceAudience = selection;
        }
        String key = nonce, note = caption, style = captionStyle;
        File draft = draftFile;
        JSONArray recipients = new JSONArray(people);
        busy = true;
        updatePreviewAudience();
        run(() -> MomentApi.json("PUT", "/api/moments", new JSONObject().put("nonce", key)
                        .put("caption", note).put("captionStyle", style.isBlank() ? JSONObject.NULL : style).put("recipients", recipients)
                        .put("image", Base64.encodeToString(Files.readAllBytes(draft.toPath()), Base64.NO_WRAP))),
                result -> {
                    clearDraft();
                    toast("Đã đăng tin.");
                    grid = false;
                    authorFilter = "";
                    showFeed("received");
                }, failure -> {
                    sendError = MomentApi.publishError(failure);
                    updatePreviewAudience();
                });
    }

    private void back() {
        if (busy) return;
        switch (page) {
            case FEED:
                if (grid && personalHistory) {
                    personalHistory = false;
                    ((MainActivity) requireActivity()).openProfile();
                } else if (grid) showCameraFeed();
                else if (storyAdapter != null && storyIndex >= 0) storyPager.setCurrentItem(storyIndex, true);
                break;
            case FULL: showDetail(); break;
            case PICKER: showPreview(); break;
            case DETAIL: showFeed(view); break;
            case PREVIEW:
                new MaterialAlertDialogBuilder(requireContext()).setTitle("Bỏ ảnh này?")
                        .setNegativeButton("Tiếp tục chỉnh sửa", null)
                        .setPositiveButton("Bỏ ảnh", (dialog, choice) -> {
                            clearDraft();
                            grid = false; authorFilter = ""; showFeed("received");
                        }).show();
                break;
        }
    }

    private void clearDraft() {
        if (draftFile != null) draftFile.delete();
        draftFile = null;
        nonce = null;
        captionStyle = "";
        allViewers = true;
        caption = sendError = friendQuery = "";
        selected.clear();
    }

    private LinearLayout author(JSONObject item) {
        LinearLayout author = row();
        author.setGravity(Gravity.CENTER);
        author.setMinimumHeight(dp(57));
        boolean own = item.optBoolean("own");
        String name = own ? "Bạn" : item.optString("name");
        User peer = friends.get(item.optString("owner"));
        ImageView avatar = avatarImage(own ? ownPhoto : peer == null ? null : peer.getPhotoUrl(), R.drawable.ic_nav_profile);
        author.addView(avatar, new LinearLayout.LayoutParams(dp(38), dp(38)));
        long date = item.optLong("createdAt");
        CharSequence time = DateUtils.getRelativeTimeSpanString(date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        TextView label = text(name + "  ·  " + time + (item.optBoolean("seen") ? "" : "  ·  Chưa xem"), 14, true);
        label.setPadding(dp(8), dp(9), 0, dp(9));
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        author.addView(label, new LinearLayout.LayoutParams(-2, -2));
        return author;
    }

    private ImageView avatarImage(String url, int fallback) {
        ImageView image = new ImageView(requireContext());
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        image.setBackground(rounded(0xFFDFEDFF, 22));
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (url != null && !url.isBlank()) Glide.with(this).load(url).circleCrop().error(fallback).into(image);
        else {
            image.setImageResource(fallback);
            image.setColorFilter(CONTROL_COLOR);
            image.setPadding(dp(8), dp(8), dp(8), dp(8));
        }
        return image;
    }

    private FrameLayout photoWithCaption(JSONObject item, boolean lazy, Runnable open, Runnable loaded) {
        FrameLayout wrapper = squareFrame();
        wrapper.addView(photo(item.optString("id"), false, lazy, open, loaded), new FrameLayout.LayoutParams(-1, -1));
        String note = item.optString("caption");
        if (!note.isBlank()) placeCaption(wrapper, captionLabel(note, item.optString("captionStyle")));
        return wrapper;
    }

    private void placeCaption(FrameLayout frame, TextView label) {
        var position = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        position.setMargins(dp(10), 0, dp(10), dp(10));
        frame.addView(label, position);
    }

    private View photo(String id, boolean fullscreen, boolean lazy, Runnable open, Runnable loaded) {
        FrameLayout frame = squareFrame();
        TextView status = text("Đang tải ảnh…", 14, false);
        if (fullscreen) {
            frame.setBackground(rounded(requireContext().getColor(R.color.chat_canvas), 18));
        }
        status.setGravity(Gravity.CENTER);
        frame.addView(status, new FrameLayout.LayoutParams(-1, -1));
        int generation = screen;
        Runnable load = new Runnable() {
            @Override public void run() {
                status.setOnClickListener(null);
                status.setText("Đang tải ảnh…");
                photos.execute(() -> {
                    if (screen != generation || !sameUser()) return;
                    try {
                        Bitmap bitmap = MomentApi.photo(id, lazy);
                        main.post(() -> {
                            if (!active(generation)) { bitmap.recycle(); return; }
                            ImageView image = imageView();
                            if (fullscreen) image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                            image.setImageBitmap(bitmap);
                            if (open != null) {
                                image.setContentDescription("Mở ảnh Khoảnh khắc");
                                image.setOnClickListener(v -> open.run());
                            }
                            frame.removeAllViews();
                            frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
                            loaded.run();
                        });
                    } catch (Exception failure) {
                        main.post(() -> {
                            if (!active(generation)) return;
                            boolean gone = failure instanceof MomentApi.Failure && ((MomentApi.Failure) failure).status == 404;
                            status.setText(gone ? "Ảnh không còn khả dụng" : "Chạm để thử tải ảnh lại");
                            if (!gone) status.setOnClickListener(v -> run());
                        });
                    }
                });
            }
        };
        if (!lazy) load.run();
        else frame.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            private final android.view.ViewTreeObserver.OnPreDrawListener visible = () -> {
                if (frame.getGlobalVisibleRect(new android.graphics.Rect())) {
                    frame.getViewTreeObserver().removeOnPreDrawListener(this.visible);
                    frame.removeOnAttachStateChangeListener(this);
                    load.run();
                }
                return true;
            };
            @Override public void onViewAttachedToWindow(View view) { view.getViewTreeObserver().addOnPreDrawListener(visible); }
            @Override public void onViewDetachedFromWindow(View view) { view.getViewTreeObserver().removeOnPreDrawListener(visible); }
        });
        return frame;
    }

    private FrameLayout squareFrame() {
        FrameLayout frame = new FrameLayout(requireContext()) {
            @Override protected void onMeasure(int width, int height) {
                int side = View.MeasureSpec.getSize(width);
                super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY));
            }
        };
        frame.setBackground(rounded(0xFFD4DEEA, grid && page == Page.FEED ? 14 : 28));
        frame.setClipToOutline(true);
        return frame;
    }

    private ImageView imageView() {
        ImageView image = new ImageView(requireContext());
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setContentDescription("Ảnh Khoảnh khắc");
        return image;
    }

    private TextView captionLabel(String note, String style) {
        TextView label = text(note, 15, true);
        label.setPadding(dp(12), dp(5), dp(12), dp(5));
        label.setGravity(Gravity.CENTER);
        int background = CONTROL_SURFACE, ink = CONTROL_COLOR;
        switch (style) {
            case "rose": background = 0xFFFFDAE6; ink = 0xFF711738; break;
            case "mint": background = 0xFFCDF6E8; ink = 0xFF124A42; break;
            case "weather": background = 0xFFFFE6A8; ink = 0xFF583406; break;
            case "music": background = 0xFFE4DCFF; ink = 0xFF392B67; break;
        }
        label.setTextColor(ink);
        label.setBackground(rounded(background, 12));
        return label;
    }

    private MaterialButton button(String label, boolean primary, Runnable click) {
        MaterialButton view = new MaterialButton(requireContext());
        view.setText(label);
        view.setAllCaps(false);
        view.setMinHeight(dp(48));
        if (!primary) {
            view.setBackgroundTintList(ColorStateList.valueOf(0xFFE3F0FD));
            view.setTextColor(0xFF075BA9);
        }
        view.setOnClickListener(v -> click.run());
        return view;
    }

    private ImageButton icon(int drawable, String label, Runnable click) {
        ImageButton button = new ImageButton(requireContext());
        styleTool(button, CONTROL_SURFACE, CONTROL_COLOR, 15, 11);
        if (drawable != 0) button.setImageResource(drawable);
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setOnClickListener(v -> click.run());
        return button;
    }

    private void styleTool(ImageButton button, int surface, int color, int radius, int inset) {
        button.setImageTintList(ColorStateList.valueOf(color));
        button.setPadding(dp(inset), dp(inset), dp(inset), dp(inset));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x30087AF0), controlBackground(surface, radius), null));
    }

    private void chip(LinearLayout parent, String label, boolean active, Runnable click) {
        MaterialButton button = button(label, active, click);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        var layout = new LinearLayout.LayoutParams(0, -2, 1);
        layout.setMargins(dp(2), 0, dp(2), 0);
        parent.addView(button, layout);
    }

    private TextView text(String value, int size, boolean bold) {
        TextView text = new TextView(requireContext());
        text.setText(value);
        text.setTextColor(0xFF102139);
        text.setTextSize(size);
        text.setPadding(0, dp(8), 0, dp(8));
        if (bold) text.setTypeface(null, Typeface.BOLD);
        return text;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private GradientDrawable controlBackground(int color, int radius) {
        GradientDrawable border = rounded(color, radius);
        border.setStroke(dp(1), 0xFFCDD6E1);
        return border;
    }

    private void onText(EditText edit, Consumer<String> update) {
        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { update.accept(value.toString()); }
            @Override public void afterTextChanged(Editable editable) {}
        });
    }

    private <T> void run(Callable<T> job, Consumer<T> success, Consumer<Exception> error) {
        int generation = screen;
        boolean locked = busy;
        requests.execute(() -> {
            try {
                if (!sameUser()) return;
                T result = job.call();
                main.post(() -> { if (active(generation)) { if (locked) busy = false; success.accept(result); } });
            } catch (Exception failure) {
                main.post(() -> { if (active(generation)) { if (locked) busy = false; error.accept(failure); } });
            }
        });
    }

    private boolean active(int generation) { return generation == screen && body != null && isAdded() && sameUser(); }
    private boolean sameUser() { return uid != null && uid.equals(FirebaseAuth.getInstance().getUid()); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { if (isAdded()) Toast.makeText(requireContext(), value, Toast.LENGTH_SHORT).show(); }
    private File captureDirectory() { return new File(requireContext().getCacheDir(), "moments-camera"); }
    private File ownedFile(String name) {
        return name != null && name.matches("(capture|draft)-[0-9]+\\.jpg") ? new File(captureDirectory(), name) : null;
    }
    private String name(User user) {
        return user == null || user.getDisplayName() == null || user.getDisplayName().isBlank()
                ? getString(R.string.friends_unknown_user) : user.getDisplayName();
    }
    private void hideKeyboard() {
        WindowCompat.getInsetsController(requireActivity().getWindow(), body).hide(WindowInsetsCompat.Type.ime());
    }
}
