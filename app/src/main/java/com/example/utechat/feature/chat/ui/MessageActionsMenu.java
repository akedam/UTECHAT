package com.example.utechat.feature.chat.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.example.utechat.R;
import com.example.utechat.domain.model.Message;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.List;
import java.util.function.Consumer;

final class MessageActionsMenu {
    private static final String[] REACTIONS = {"❤️", "😆", "😮", "😢", "😡", "👍", "😂", "😍", "🔥", "👏", "🎉", "💯", "🙌", "🙏", "👎"};
    private static final int[] REACTION_LABELS = {R.string.reaction_heart, R.string.reaction_laugh, R.string.reaction_wow,
            R.string.reaction_sad, R.string.reaction_angry, R.string.reaction_like};

    static final class Action {
        final int label, icon;
        final Runnable run;
        Action(int label, int icon, Runnable run) { this.label = label; this.icon = icon; this.run = run; }
    }

    static void show(ChatActivity host, View anchor, Message message, boolean mine, List<Action> primary,
                     List<Action> secondary, Consumer<String> reaction, Runnable photo) {
        new MessageActionsMenu(host, anchor, message, mine, primary, secondary, reaction, photo).show();
    }

    private final ChatActivity host;
    private final View chat;
    private final Dialog dialog;
    private final FrameLayout overlay;
    private final LinearLayout panel;
    private final LinearLayout rows;
    private final ScrollView scroll;
    private final List<Action> primary, secondary;
    private final int anchoredY;
    private final int side;
    private final String selected;

    private MessageActionsMenu(ChatActivity host, View anchor, Message message, boolean mine, List<Action> primary,
                               List<Action> secondary, Consumer<String> reaction, Runnable photo) {
        this.host = host;
        this.chat = host.findViewById(R.id.chat_root);
        this.primary = primary;
        this.secondary = secondary;
        String uid = host.chatViewModel().getCurrentUserId();
        selected = uid == null ? null : message.getReactions().get(uid);
        int[] anchorPosition = new int[2], rootPosition = new int[2];
        anchor.getLocationOnScreen(anchorPosition);
        chat.getLocationOnScreen(rootPosition);
        anchoredY = anchorPosition[1] - rootPosition[1];
        side = mine ? Gravity.END : Gravity.START;

        dialog = new Dialog(host, R.style.Theme_UTEChat_MessageActions);
        overlay = new FrameLayout(host);
        overlay.setOnClickListener(v -> dialog.dismiss());
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(overlay, (view, insets) -> {
            var bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        panel = new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(side);
        panel.setClickable(true);
        scroll = new ScrollView(host);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(panel, new ScrollView.LayoutParams(-1, -2));
        var frame = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        frame.setMarginStart(dp(host, 12));
        frame.setMarginEnd(dp(host, 12));
        overlay.addView(scroll, frame);

        if (reaction != null) addReactionBar(reaction, photo);
        TextView bubble = new TextView(host);
        bubble.setText(preview(host, message));
        bubble.setTextColor(host.getColor(R.color.utechat_on_surface));
        bubble.setTextSize(17);
        bubble.setMaxWidth(Math.min(dp(host, 284), Math.max(dp(host, 170), chat.getWidth() - dp(host, 38))));
        bubble.setMaxLines(6);
        bubble.setEllipsize(TextUtils.TruncateAt.END);
        bubble.setPaddingRelative(dp(host, 12), dp(host, 9), dp(host, 12), dp(host, 9));
        bubble.setBackgroundResource(mine ? R.drawable.bg_bubble_sent : R.drawable.bg_bubble_received);
        panel.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
        rows = new LinearLayout(host);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setBackground(round(host.getColor(R.color.utechat_surface), 17));
        rows.setClipToOutline(true);
        var menu = new LinearLayout.LayoutParams(Math.min(dp(host, 248), chat.getWidth() - dp(host, 24)), -2);
        menu.topMargin = dp(host, 8);
        panel.addView(rows, menu);
        render(false);
    }

    private void show() {
        var window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.20f);
        dialog.setContentView(overlay);
        dialog.setOnDismissListener(d -> {
            if (Build.VERSION.SDK_INT >= 31) chat.setRenderEffect(null);
        });
        dialog.show();
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setLayout(-1, -1);
        if (Build.VERSION.SDK_INT >= 31) {
            int radius = dp(host, 16);
            chat.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }
        overlay.post(this::positionPanel);
    }

    private void render(boolean more) {
        rows.removeAllViews();
        List<Action> actions = more || primary.isEmpty() ? secondary : primary;
        for (Action action : actions) addRow(action.label, action.icon, () -> {
            dialog.dismiss();
            action.run.run();
        });
        if (!primary.isEmpty() && !secondary.isEmpty()) addRow(R.string.message_more, R.drawable.ic_chat_more, () -> render(!more));
        if (overlay.getHeight() != 0) overlay.post(this::positionPanel);
    }

    private void positionPanel() {
        int available = overlay.getHeight() - overlay.getPaddingTop() - overlay.getPaddingBottom() - dp(host, 16);
        if (available <= 0) return;
        int width = scroll.getWidth();
        if (width == 0) return;
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = Math.min(available, panel.getMeasuredHeight());
        int bubbleOffset = panel.getChildCount() == 3 ? dp(host, 58) : 0;
        int top = Math.min(Math.max(dp(host, 8), anchoredY - bubbleOffset), available - height + dp(host, 8));
        var params = (FrameLayout.LayoutParams) scroll.getLayoutParams();
        if (params.height != height || params.topMargin != top) {
            params.height = height;
            params.topMargin = top;
            scroll.setLayoutParams(params);
        }
        scroll.scrollTo(0, 0);
    }

    private void addReactionBar(Consumer<String> reaction, Runnable photo) {
        var bar = new HorizontalScrollView(host);
        bar.setHorizontalScrollBarEnabled(false);
        bar.setBackground(round(host.getColor(R.color.utechat_surface), 30));
        var line = new LinearLayout(host);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(host, 4), 0, dp(host, 4), 0);
        for (int i = 0; i < REACTION_LABELS.length; i++) {
            String emoji = REACTIONS[i];
            var button = new TextView(host);
            button.setGravity(Gravity.CENTER);
            button.setTextSize(24);
            button.setText(emoji);
            button.setContentDescription(host.getString(REACTION_LABELS[i]));
            int fill = emoji.equals(selected) ? host.getColor(R.color.utechat_surface_variant) : Color.TRANSPARENT;
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x240989ea), round(fill, 30), null));
            button.setOnClickListener(v -> chooseReaction(reaction, emoji));
            line.addView(button, new LinearLayout.LayoutParams(dp(host, 42), dp(host, 48)));
        }
        if (photo != null) addIcon(line, R.drawable.ic_message_camera, R.string.message_photo_reply, () -> {
            dialog.dismiss();
            photo.run();
        });
        addIcon(line, R.drawable.ic_add_outline, R.string.reaction_more, () -> {
            dialog.dismiss();
            new MaterialAlertDialogBuilder(host).setTitle(R.string.reaction_more)
                    .setItems(REACTIONS, (d, index) -> reaction.accept(REACTIONS[index]))
                    .setNegativeButton(R.string.friends_cancel_button, null).show();
        });
        bar.addView(line);
        var params = new LinearLayout.LayoutParams(-2, dp(host, 50));
        params.bottomMargin = dp(host, 8);
        panel.addView(bar, params);
    }

    private void chooseReaction(Consumer<String> listener, String emoji) {
        dialog.dismiss();
        listener.accept(emoji);
    }

    private void addIcon(LinearLayout line, int icon, int label, Runnable action) {
        var button = new ImageButton(host);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x240989ea),
                round(host.getColor(R.color.utechat_surface_variant), 24), null));
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(host.getColor(R.color.utechat_primary)));
        button.setContentDescription(host.getString(label));
        button.setOnClickListener(v -> action.run());
        var params = new LinearLayout.LayoutParams(dp(host, 42), dp(host, 44));
        params.setMarginStart(dp(host, 2));
        line.addView(button, params);
    }

    private void addRow(int title, int icon, Runnable action) {
        if (rows.getChildCount() > 0) {
            View separator = new View(host);
            separator.setBackgroundColor(host.getColor(R.color.utechat_surface_variant));
            rows.addView(separator, new LinearLayout.LayoutParams(-1, dp(host, 1)));
        }
        TextView row = new TextView(host);
        row.setText(title);
        row.setTextSize(16);
        int color = host.getColor(title == R.string.message_delete ? R.color.utechat_error : R.color.utechat_on_surface);
        row.setTextColor(color);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(dp(host, 14), 0, dp(host, 14), 0);
        var drawable = androidx.appcompat.content.res.AppCompatResources.getDrawable(host, icon);
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setTint(color);
            drawable.setBounds(0, 0, dp(host, 20), dp(host, 20));
            row.setCompoundDrawablesRelative(null, null, drawable, null);
        }
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x240989ea), null, new ColorDrawable(Color.WHITE)));
        row.setOnClickListener(v -> action.run());
        rows.addView(row, new LinearLayout.LayoutParams(-1, dp(host, 46)));
    }

    private GradientDrawable round(int color, int radius) {
        var background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(host, radius));
        return background;
    }

    static String preview(Context context, Message message) {
        switch (message.getType()) {
            case IMAGE: return context.getString(R.string.conversation_preview_image);
            case FILE: return context.getString(R.string.conversation_preview_file);
            case VOICE: return context.getString(R.string.voice_message);
            default: return message.getContent() == null ? "" : message.getContent();
        }
    }

    static int dp(Context context, int size) { return Math.round(size * context.getResources().getDisplayMetrics().density); }
}
