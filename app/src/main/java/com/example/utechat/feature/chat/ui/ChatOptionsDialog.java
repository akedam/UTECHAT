package com.example.utechat.feature.chat.ui;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.*;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import com.bumptech.glide.Glide;
import com.example.utechat.R;
import com.example.utechat.app.MediaRepositoryProvider;
import com.example.utechat.domain.model.Message;
import com.example.utechat.domain.repository.MediaRepository;
import com.example.utechat.core.common.Resource;
import com.example.utechat.feature.chat.viewmodel.ChatViewModel;
import com.example.utechat.feature.main.ui.MainActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Full-screen controls for the current conversation. */
final class ChatOptionsDialog extends Dialog {
    private final ChatActivity host;
    private final ChatPreferences prefs;
    private final List<Message> messages;
    private final String originalTitle, photo;
    private final boolean group;
    private final LinearLayout body;
    private final TextView title;
    private final List<MediaRepository.Transfer> downloads = new ArrayList<>();

    ChatOptionsDialog(ChatActivity host, ChatPreferences prefs, String originalTitle,
                      String photo, boolean group, List<Message> messages) {
        super(host, R.style.Theme_UTEChat);
        this.host = host; this.prefs = prefs; this.originalTitle = originalTitle;
        this.photo = photo; this.group = group; this.messages = new ArrayList<>(messages);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = column(); root.setBackgroundResource(R.drawable.bg_header_gradient);
        Toolbar toolbar = new Toolbar(host);
        toolbar.setBackgroundResource(R.drawable.bg_header_gradient);
        toolbar.setTitle(R.string.chat_options); toolbar.setTitleTextColor(color(R.color.white));
        toolbar.setNavigationIcon(R.drawable.ic_chat_back);
        if (toolbar.getNavigationIcon() != null) toolbar.getNavigationIcon().setTint(color(R.color.white));
        toolbar.setNavigationContentDescription(R.string.cd_back);
        toolbar.setNavigationOnClickListener(v -> dismiss());
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(56)));
        ScrollView scroll = new ScrollView(host); scroll.setFillViewport(true);
        scroll.setBackgroundColor(color(R.color.utechat_background));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body = column(); body.setPadding(0, 0, 0, dp(24)); scroll.addView(body);

        LinearLayout identity = column(); identity.setGravity(Gravity.CENTER);
        identity.setBackgroundColor(color(R.color.utechat_surface)); identity.setPadding(dp(16), dp(24), dp(16), dp(20));
        ImageView avatar = new ImageView(host); avatar.setContentDescription(host.getString(R.string.cd_avatar));
        identity.addView(avatar, new LinearLayout.LayoutParams(dp(80), dp(80)));
        avatar.setBackgroundResource(R.drawable.bg_avatar_circle_primary);
        if (photo != null && photo.startsWith("https://")) Glide.with(avatar).load(photo).circleCrop().into(avatar);
        else { avatar.setImageResource(group ? R.drawable.ic_chat_group : R.drawable.ic_nav_profile); avatar.setColorFilter(color(R.color.white)); avatar.setPadding(dp(18),dp(18),dp(18),dp(18)); }
        title = label(prefs.title(originalTitle), 21); title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER); title.setPadding(0, dp(14), 0, dp(24)); identity.addView(title);
        LinearLayout shortcuts = new LinearLayout(host); shortcuts.setGravity(Gravity.TOP);
        shortcut(shortcuts, R.drawable.ic_search_outline, R.string.chat_search_messages, this::searchMessages);
        shortcut(shortcuts, R.drawable.ic_chat_pin, R.string.chat_wallpaper, this::wallpaper);
        final TextView muteLabel = shortcut(shortcuts, R.drawable.ic_bell_outline, R.string.chat_mute, null);
        LinearLayout muteButton = (LinearLayout) muteLabel.getParent();
        muteButton.setSelected(prefs.enabled("muted"));
        muteLabel.setText(prefs.enabled("muted") ? R.string.chat_unmute : R.string.chat_mute);
        muteLabel.setTextColor(color(prefs.enabled("muted") ? R.color.utechat_primary : R.color.utechat_on_surface));
        muteButton.setOnClickListener(v -> {
            boolean muted = !prefs.enabled("muted"); prefs.setEnabled("muted", muted);
            muteButton.setSelected(muted); muteLabel.setTextColor(color(muted ? R.color.utechat_primary : R.color.utechat_on_surface));
            muteLabel.setText(muted ? R.string.chat_unmute : R.string.chat_mute);
            if (muted) androidx.core.app.NotificationManagerCompat.from(host).cancel("utechat_conversation_" + host.conversationId(), 0);
        });
        identity.addView(shortcuts); body.addView(identity);
        gap(); row(R.drawable.ic_edit_outline, R.string.chat_nickname, this::nickname);
        toggle(R.drawable.ic_chat_star, R.string.chat_favorite, "favorite");
        gap(); row(R.drawable.ic_chat_gallery, R.string.chat_media, this::media);
        mediaPreview();
        gap(); row(R.drawable.ic_chat_group, R.string.chat_open_community, () -> {
            host.startActivity(new Intent(host, MainActivity.class).putExtra("open_community", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            dismiss(); host.finish();
        });
        toggle(R.drawable.ic_chat_pin, R.string.chat_pin, "pinned");
        row(R.drawable.ic_shield_outline, R.string.chat_protection, () -> info(R.string.chat_protection, host.getString(R.string.chat_protection_summary)));
        row(R.drawable.ic_chat_storage, R.string.chat_storage, this::storage);
        if (!group && !host.chatViewModel().isMock()) {
            gap();
            row(R.drawable.ic_shield_outline, R.string.user_safety, host::showPeerSafety);
        }
        setContentView(root);
        setOnDismissListener(ignored -> {
            for (MediaRepository.Transfer download : downloads) download.cancel();
            downloads.clear(); host.applyConversationAppearance();
        });
        setOnShowListener(ignored -> {
            getWindow().setLayout(-1, -1);
            ChatActivity.applyChatInsets(getWindow(), root, host);
        });
    }

    private int dp(int value) { return Math.round(value * host.getResources().getDisplayMetrics().density); }
    private int color(int resource) { return ContextCompat.getColor(host, resource); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(host); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private TextView label(String text, int size) { TextView t = new TextView(host); t.setText(text); t.setTextSize(size); t.setTextColor(color(R.color.utechat_on_surface)); return t; }
    private void gap() { View v = new View(host); body.addView(v, new LinearLayout.LayoutParams(-1, dp(8))); }
    private ImageView icon(int drawable) { ImageView i = new ImageView(host); i.setImageResource(drawable); i.setColorFilter(color(R.color.utechat_on_surface_variant)); i.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return i; }
    private LinearLayout row(int drawable, int text, Runnable action) {
        LinearLayout r = new LinearLayout(host); r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(12), dp(16), dp(12)); r.setMinimumHeight(dp(58));
        r.setBackgroundColor(color(R.color.utechat_surface));
        r.addView(icon(drawable), new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView name = label(host.getString(text), 16); name.setPadding(dp(18), 0, dp(8), 0);
        r.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            r.addView(icon(R.drawable.ic_chevron_outline), new LinearLayout.LayoutParams(dp(18), dp(18)));
            r.setOnClickListener(v -> action.run()); r.setFocusable(true);
            android.util.TypedValue value = new android.util.TypedValue(); host.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
            r.setForeground(androidx.appcompat.content.res.AppCompatResources.getDrawable(host, value.resourceId));
        }
        body.addView(r, new LinearLayout.LayoutParams(-1, -2));
        View line = new View(host); line.setBackgroundColor(color(R.color.utechat_divider));
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, dp(1)); lineParams.leftMargin = dp(58); body.addView(line, lineParams);
        return r;
    }
    private void toggle(int icon, int text, String key) {
        LinearLayout r = row(icon, text, null);
        SwitchMaterial control = new SwitchMaterial(host); control.setContentDescription(host.getString(text));
        styleSwitch(control);
        control.setChecked(prefs.enabled(key)); control.setMinHeight(dp(48)); r.addView(control);
        control.setOnCheckedChangeListener((button, checked) -> prefs.setEnabled(key, checked));
        r.setOnClickListener(v -> control.setChecked(!control.isChecked()));
    }
    private void styleSwitch(SwitchMaterial control) {
        control.setUseMaterialThemeColors(false);
        control.setThumbTintList(android.content.res.ColorStateList.valueOf(color(R.color.white)));
        control.setTrackTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},
                new int[]{color(R.color.utechat_primary),0xFFE2E4E7}));
    }
    private TextView shortcut(LinearLayout parent, int drawable, int text, Runnable action) {
        LinearLayout box = column(); box.setGravity(Gravity.CENTER); box.setPadding(dp(4), 0, dp(4), 0);
        ImageView image = icon(drawable); image.setColorFilter(color(R.color.utechat_on_surface)); image.setPadding(dp(12),dp(12),dp(12),dp(12));
        GradientDrawable circle = new GradientDrawable(); circle.setShape(GradientDrawable.OVAL); circle.setColor(color(R.color.utechat_background)); image.setBackground(circle);
        box.addView(image, new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView caption = label(host.getString(text), 12); caption.setGravity(Gravity.CENTER); caption.setPadding(0,dp(8),0,0); box.addView(caption);
        if (action != null) box.setOnClickListener(v -> action.run()); box.setFocusable(true);
        parent.addView(box, new LinearLayout.LayoutParams(0,-2,1)); return caption;
    }
    private void info(int heading, String text) { new MaterialAlertDialogBuilder(host).setTitle(heading).setMessage(text).setPositiveButton(R.string.community_close,null).show(); }
    private void nickname() {
        EditText input = new EditText(host); input.setSingleLine(true); input.setText(prefs.title(originalTitle));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
        new MaterialAlertDialogBuilder(host).setTitle(R.string.chat_nickname).setMessage(R.string.chat_local_setting).setView(input)
                .setNegativeButton(R.string.friends_cancel_button,null).setNeutralButton("Mặc định",(d,w)->{prefs.setTitle("");title.setText(originalTitle);host.applyConversationAppearance();})
                .setPositiveButton(R.string.community_close,(d,w)->{prefs.setTitle(input.getText().toString());title.setText(prefs.title(originalTitle));host.applyConversationAppearance();}).show();
    }
    private void wallpaper() {
        int[] colors = {0xFFE3E8EC,0xFFE8F5FF,0xFFFFF5E6,0xFFFFFFFF};
        String[] labels = {host.getString(R.string.chat_wallpaper_default),host.getString(R.string.chat_wallpaper_blue),host.getString(R.string.chat_wallpaper_cream),host.getString(R.string.chat_wallpaper_white)};
        int selected = 0; for(int i=0;i<colors.length;i++) if(colors[i]==prefs.wallpaper()) selected=i;
        new MaterialAlertDialogBuilder(host).setTitle(R.string.chat_wallpaper).setSingleChoiceItems(labels,selected,(d,w)->{
            prefs.setWallpaper(colors[w]);host.applyConversationAppearance();d.dismiss();
        }).setNegativeButton(R.string.friends_cancel_button,null).show();
    }
    private void searchMessages() {
        LinearLayout layout = column(); layout.setPadding(dp(20),0,dp(20),dp(12));
        EditText input = new EditText(host); input.setSingleLine(true); input.setHint(R.string.chat_search_messages); layout.addView(input);
        TextView scope = label(host.getString(R.string.chat_search_scope), 12);
        scope.setPadding(0, dp(8), 0, dp(8));
        layout.addView(scope);
        ScrollView scroll = new ScrollView(host); LinearLayout results = column();scroll.addView(results);layout.addView(scroll,new LinearLayout.LayoutParams(-1,dp(320)));
        com.google.android.material.button.MaterialButton older = new com.google.android.material.button.MaterialButton(host);
        layout.addView(older);
        older.setOnClickListener(v -> host.loadOlderForSearch());
        TextView status = label("", 12);
        layout.addView(status);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(host)
                .setTitle(R.string.chat_search_messages).setView(layout)
                .setNegativeButton(R.string.community_close, null).create();
        ChatViewModel model = host.chatViewModel();
        Runnable renderPaging = () -> {
            boolean loading = Boolean.TRUE.equals(model.getLoadingOlder().getValue());
            boolean hasMore = Boolean.TRUE.equals(model.getHasMoreHistory().getValue());
            older.setText(loading ? R.string.chat_search_loading : R.string.chat_search_older);
            older.setEnabled(!loading && hasMore);
            older.setVisibility(loading || hasMore ? View.VISIBLE : View.GONE);
            String error = model.getSendError().getValue();
            status.setText(error != null ? error : !loading && !hasMore
                    ? host.getString(R.string.chat_search_all_loaded) : "");
        };
        Runnable refresh = () -> {
            results.removeAllViews();String query=input.getText().toString().trim().toLowerCase(Locale.ROOT);
            Resource<List<Message>> current = model.getMessages().getValue();
            List<Message> loaded = current != null && current.getData() != null ? current.getData() : messages;
            for(Message m:loaded) if(!query.isEmpty() && m.getContent()!=null && m.getContent().toLowerCase(Locale.ROOT).contains(query)) {
                TextView result=label(m.getContent()+"\n"+android.text.format.DateFormat.format("dd/MM HH:mm",m.getSentAt()),14); result.setMaxLines(4);result.setPadding(0,dp(12),0,dp(12));result.setMinHeight(dp(48));
                result.setOnClickListener(v->{dialog.dismiss();dismiss();host.scrollToMessage(m.getId());}); results.addView(result);
            }
            if(results.getChildCount()==0 && !query.isEmpty()) results.addView(label(host.getString(R.string.chat_no_results),14));
        };
        input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){} public void onTextChanged(CharSequence s,int start,int before,int count){refresh.run();} public void afterTextChanged(Editable e){}});
        Observer<Resource<List<Message>>> messageObserver = ignored -> refresh.run();
        Observer<Boolean> pagingObserver = ignored -> renderPaging.run();
        Observer<String> errorObserver = ignored -> renderPaging.run();
        dialog.setOnDismissListener(ignored -> {
            model.getMessages().removeObserver(messageObserver);
            model.getLoadingOlder().removeObserver(pagingObserver);
            model.getHasMoreHistory().removeObserver(pagingObserver);
            model.getSendError().removeObserver(errorObserver);
        });
        dialog.show();
        model.getMessages().observe(host, messageObserver);
        model.getLoadingOlder().observe(host, pagingObserver);
        model.getHasMoreHistory().observe(host, pagingObserver);
        model.getSendError().observe(host, errorObserver);
    }
    private void mediaPreview() {
        HorizontalScrollView scroll = new HorizontalScrollView(host); scroll.setBackgroundColor(color(R.color.utechat_surface));
        LinearLayout strip = new LinearLayout(host); strip.setPadding(dp(58),dp(8),dp(16),dp(12));scroll.addView(strip);
        int count=0;for(int i=messages.size()-1;i>=0&&count<4;i--) if(messages.get(i).getType()==Message.Type.IMAGE){
            Message m=messages.get(i);ImageView image=thumbnail(m);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(64),dp(64));lp.rightMargin=dp(4);strip.addView(image,lp);image.setOnClickListener(v->media());count++;
        }
        if(count==0){TextView none=label(host.getString(R.string.chat_no_media),13);none.setTextColor(color(R.color.utechat_on_surface_variant));strip.addView(none);}
        body.addView(scroll);
    }
    private ImageView thumbnail(Message m) {
        ImageView image=new ImageView(host);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackgroundColor(color(R.color.utechat_background));image.setImageResource(R.drawable.ic_chat_gallery);
        image.setContentDescription(host.getString(R.string.message_image_description,m.getSenderName()));
        MediaRepository media=MediaRepositoryProvider.get(host);String path=m.getMediaUrl();
        if(m.getLocalMediaUri()!=null) Glide.with(image).load(Uri.parse(m.getLocalMediaUri())).centerCrop().into(image);
        else if(path!=null){File cache=media.getImageCacheFile(path);if(cache!=null&&cache.isFile()) Glide.with(image).load(cache).centerCrop().into(image);
            else if(cache!=null) downloads.add(media.downloadImage(path,cache,new MediaRepository.DownloadListener(){
                public void onSuccess(File file){if(isShowing()&&!host.isDestroyed())Glide.with(image).load(file).centerCrop().into(image);}
                public void onError(String message){image.setContentDescription(host.getString(R.string.image_download_error));}
            }));
        }
        return image;
    }
    private void media() {
        LinearLayout content=column();content.setPadding(dp(20),dp(8),dp(20),dp(16));
        content.addView(label(host.getString(R.string.chat_loaded_scope),12));int count=0;
        for(Message m:messages){
            if(m.getType()==Message.Type.IMAGE){ImageView image=thumbnail(m);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(180));lp.topMargin=dp(12);content.addView(image,lp);count++;}
            if(m.getType()==Message.Type.FILE){TextView file=label(m.getFileName()==null?"Tệp đính kèm":m.getFileName(),15);file.setPadding(0,dp(12),0,dp(12));content.addView(file);count++;}
            java.util.regex.Matcher matcher=android.util.Patterns.WEB_URL.matcher(m.getContent()==null?"":m.getContent());
            while(matcher.find()){String url=matcher.group();if(!url.startsWith("https://")&&!url.startsWith("http://"))continue;
                TextView link=label(url,14);link.setTextColor(color(R.color.utechat_primary));link.setPadding(0,dp(12),0,dp(12));link.setMinHeight(dp(48));link.setOnClickListener(v->{try{host.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(android.content.ActivityNotFoundException e){Toast.makeText(host,url,Toast.LENGTH_LONG).show();}});content.addView(link);count++;
            }
        }
        if(count==0)content.addView(label(host.getString(R.string.chat_no_media),14));
        ScrollView scroll=new ScrollView(host);scroll.addView(content);
        new MaterialAlertDialogBuilder(host).setTitle(R.string.chat_media).setView(scroll).setPositiveButton(R.string.community_close,null).show();
    }
    private void storage(){int images=0;long bytes=0;for(Message m:messages){if(m.getType()==Message.Type.IMAGE)images++;bytes+=Math.max(0,m.getFileSize());}
        info(R.string.chat_storage,host.getString(R.string.chat_storage_summary,messages.size(),images,android.text.format.Formatter.formatShortFileSize(host,bytes)));}
}
