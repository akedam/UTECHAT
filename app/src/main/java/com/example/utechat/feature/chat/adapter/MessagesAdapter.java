package com.example.utechat.feature.chat.adapter;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.example.utechat.R;
import com.example.utechat.domain.model.Message;
import com.example.utechat.domain.repository.MediaRepository;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

public class MessagesAdapter extends ListAdapter<Message, RecyclerView.ViewHolder> {
    private static final int VIEW_TYPE_SENT = 1, VIEW_TYPE_RECEIVED = 2,
            VIEW_TYPE_SENT_IMAGE = 3, VIEW_TYPE_RECEIVED_IMAGE = 4, VIEW_TYPE_POLL = 5, VIEW_TYPE_CALL = 6;
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm", Locale.getDefault());
    public interface RetryListener { void onRetry(Message message); }
    public interface VoteListener { void onVote(Message message, int optionIndex); }
    public interface CallListener { void onCallAgain(String mediaType); }
    private CallListener callListener;
    private java.util.function.Consumer<Message> actionListener, voiceListener;
    private java.util.function.Consumer<String> originalListener;

    public void setMessageActions(java.util.function.Consumer<Message> actions,
                                  java.util.function.Consumer<String> original,
                                  java.util.function.Consumer<Message> voice) {
        actionListener = actions;
        originalListener = original;
        voiceListener = voice;
    }

    private boolean showActions(Message message) {
        if (actionListener == null) return false;
        actionListener.accept(message);
        return true;
    }

    public static String replyPreview(android.content.Context context, Message.Reply reply) {
        if ("image".equals(reply.type)) return context.getString(R.string.conversation_preview_image);
        if ("voice".equals(reply.type)) return context.getString(R.string.voice_message);
        return reply.text;
    }
    public void setCallListener(CallListener listener) { callListener = listener; }
    private final String currentUserId, currentUserName;
    private final RetryListener retryListener;
    private final VoteListener voteListener;
    private final MediaRepository media;
    private boolean groupConversation;
    private String peerPhoto;
    public void setGroupConversation(boolean group) { groupConversation = group; }
    public void setPeerPhoto(String url) { peerPhoto = url; }

    public MessagesAdapter(String currentUserId, String currentUserName, RetryListener retryListener,
                           VoteListener voteListener, MediaRepository media) {
        super(DIFF_CALLBACK); this.currentUserId=currentUserId;this.currentUserName=currentUserName;
        this.retryListener=retryListener;this.voteListener=voteListener;this.media=media;
    }
    public Message getMessageAt(int position){return super.getItem(position);}
    private static final DiffUtil.ItemCallback<Message> DIFF_CALLBACK=new DiffUtil.ItemCallback<Message>(){
        @Override public boolean areItemsTheSame(@NonNull Message a,@NonNull Message b){return Objects.equals(a.getId(),b.getId());}
        @Override public boolean areContentsTheSame(@NonNull Message a,@NonNull Message b){return Objects.equals(a.getSenderId(),b.getSenderId())&&Objects.equals(a.getSenderName(),b.getSenderName())&&Objects.equals(a.getType(),b.getType())&&Objects.equals(a.getContent(),b.getContent())&&Objects.equals(a.getMediaUrl(),b.getMediaUrl())&&Objects.equals(a.getLocalMediaUri(),b.getLocalMediaUri())&&Objects.equals(a.getPollOptions(),b.getPollOptions())&&Objects.equals(a.getPollVotes(),b.getPollVotes())&&Objects.equals(a.getReply(),b.getReply())&&a.getDurationMs()==b.getDurationMs()&&a.getSentAt()==b.getSentAt()&&a.getUploadProgress()==b.getUploadProgress()&&a.getDeliveryStatus()==b.getDeliveryStatus();}
    };
    @Override public int getItemViewType(int position){Message m=getItem(position);boolean mine=currentUserId!=null&&currentUserId.equals(m.getSenderId());if(m.getType()==Message.Type.TEXT&&com.example.utechat.data.calls.CallHistory.isHistoryMessage(m.getId(),m.getContent()))return VIEW_TYPE_CALL;if(m.getType()==Message.Type.POLL)return VIEW_TYPE_POLL;if(m.getType()==Message.Type.IMAGE)return mine?VIEW_TYPE_SENT_IMAGE:VIEW_TYPE_RECEIVED_IMAGE;return mine?VIEW_TYPE_SENT:VIEW_TYPE_RECEIVED;}
    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent,int type){LayoutInflater inflater=LayoutInflater.from(parent.getContext());switch(type){case VIEW_TYPE_CALL:return new CallHolder(inflater.inflate(R.layout.item_message_call,parent,false));case VIEW_TYPE_POLL:return new PollHolder(inflater.inflate(R.layout.item_message_poll,parent,false));case VIEW_TYPE_SENT_IMAGE:return new ImageHolder(inflater.inflate(R.layout.item_message_image_sent,parent,false),true);case VIEW_TYPE_RECEIVED_IMAGE:return new ImageHolder(inflater.inflate(R.layout.item_message_image_received,parent,false),false);case VIEW_TYPE_SENT:return new TextHolder(inflater.inflate(R.layout.item_message_sent,parent,false),true);default:return new TextHolder(inflater.inflate(R.layout.item_message_received,parent,false),false);}}
    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder,int position){Message m=getItem(position);if(holder instanceof CallHolder)((CallHolder)holder).bind(m);else if(holder instanceof ImageHolder)((ImageHolder)holder).bind(m);else if(holder instanceof PollHolder)((PollHolder)holder).bind(m);else ((TextHolder)holder).bind(m);}
    @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder){if(holder instanceof ImageHolder)((ImageHolder)holder).clear();super.onViewRecycled(holder);}
    private String formattedTime(Message m){return m.getSentAt()>0?TIME_FORMAT.format(new Date(m.getSentAt())):"";}

    private final class CallHolder extends RecyclerView.ViewHolder {
        final TextView title, time, again;
        final ImageView icon;
        final View card;
        CallHolder(View view) {
            super(view); title=view.findViewById(R.id.tv_call_history_title);
            time=view.findViewById(R.id.tv_call_history_time); again=view.findViewById(R.id.btn_call_again);
            icon=view.findViewById(R.id.iv_call_history_type);
            // Reserve the same 26dp avatar + 8dp gap as an incoming text bubble.
            int available=view.getResources().getDisplayMetrics().widthPixels - Math.round(58*view.getResources().getDisplayMetrics().density);
            card=((android.widget.LinearLayout)view).getChildAt(0);
            card.getLayoutParams().width=Math.min(card.getLayoutParams().width,available);
        }
        void bind(Message m) {
            String media=com.example.utechat.data.calls.CallHistory.mediaType(m.getContent());
            String caption=com.example.utechat.data.calls.CallHistory.cardTitle(m.getContent());
            title.setText(caption.startsWith("Đã nhỡ") ? caption.replace("gọi ", "gọi\n") : caption);
            time.setText(formattedTime(m));
            icon.setImageResource("video".equals(media)?R.drawable.ic_call_missed_video:R.drawable.ic_call_missed_voice);
            boolean mine=currentUserId!=null&&currentUserId.equals(m.getSenderId());
            float density=itemView.getResources().getDisplayMetrics().density;
            itemView.setPaddingRelative(Math.round((mine?12:46)*density),Math.round(3*density),
                    Math.round(12*density),Math.round(3*density));
            card.setBackgroundResource(mine?R.drawable.bg_call_history_sent:R.drawable.bg_call_history);
            ((android.widget.LinearLayout)itemView).setGravity(mine?android.view.Gravity.END:android.view.Gravity.START);
            again.setEnabled(callListener!=null);
            again.setOnClickListener(v->{if(callListener!=null)callListener.onCallAgain(media);});
            again.setContentDescription(itemView.getContext().getString(R.string.call_card_accessibility,caption,time.getText()));
        }
    }

    private final class TextHolder extends RecyclerView.ViewHolder{
        final TextView content,sender,time,status,quote;final boolean sent;
        TextHolder(View view,boolean sent){super(view);this.sent=sent;content=view.findViewById(R.id.tv_message_content);sender=view.findViewById(R.id.tv_sender_name);time=view.findViewById(R.id.tv_message_time);status=sent?view.findViewById(R.id.tv_message_status):null;
            float density=view.getResources().getDisplayMetrics().density;
            content.setMaxWidth((int)Math.min(280*density,view.getResources().getDisplayMetrics().widthPixels-88*density));
            quote = new TextView(view.getContext());
            quote.setTextColor(view.getContext().getColor(R.color.utechat_primary));
            quote.setTextSize(13);
            quote.setPadding(0, (int) (6 * density), 0, (int) (6 * density));
            quote.setMaxWidth(content.getMaxWidth());
            quote.setMaxLines(3);
            quote.setEllipsize(android.text.TextUtils.TruncateAt.END);
            android.widget.LinearLayout bubble = (android.widget.LinearLayout) content.getParent();
            bubble.addView(quote, bubble.indexOfChild(content));
        }
        void bind(Message m){sender.setText(sent?(currentUserName==null?"":currentUserName):(m.getSenderName()==null?"":m.getSenderName()));content.setText(m.getContent()==null?"":m.getContent());time.setText(formattedTime(m));
            content.setOnLongClickListener(view -> showActions(m));
            boolean voice = m.getType() == Message.Type.VOICE;
            if (voice) {
                content.setText(itemView.getContext().getString(R.string.voice_row,
                        com.example.utechat.feature.chat.ui.VoiceDialogs.formatDuration(m.getDurationMs())));
                content.setTextColor(itemView.getContext().getColor(R.color.utechat_primary));
            } else {
                content.setTextColor(itemView.getContext().getColor(R.color.utechat_on_surface));
            }
            content.setOnClickListener(voice && m.getMediaUrl() != null && voiceListener != null
                    ? view -> voiceListener.accept(m) : null);
            content.setClickable(voice && m.getMediaUrl() != null && voiceListener != null);
            Message.Reply reply = m.getReply();
            quote.setVisibility(reply == null ? View.GONE : View.VISIBLE);
            if (reply != null) {
                String name = reply.name.isEmpty() ? itemView.getContext().getString(R.string.friends_unknown_user) : reply.name;
                quote.setText(itemView.getContext().getString(R.string.message_reply_excerpt, name,
                        replyPreview(itemView.getContext(), reply)));
                quote.setOnClickListener(view -> {
                    if (originalListener != null) originalListener.accept(reply.id);
                });
                quote.setOnLongClickListener(view -> showActions(m));
            }
            sender.setVisibility(!sent && groupConversation ? View.VISIBLE : View.GONE);
            if(!sent){
                TextView avatar=itemView.findViewById(R.id.tv_message_avatar);String name=m.getSenderName();avatar.setText(name==null||name.trim().isEmpty()?"?":name.trim().substring(0,1).toUpperCase(Locale.ROOT));
                ImageView photo=itemView.findViewById(R.id.iv_message_avatar);
                boolean showPhoto=!groupConversation && peerPhoto!=null && peerPhoto.startsWith("https://");
                photo.setVisibility(showPhoto?View.VISIBLE:View.GONE);avatar.setVisibility(showPhoto?View.GONE:View.VISIBLE);
                Glide.with(photo).clear(photo);
                if(showPhoto) Glide.with(photo).load(peerPhoto).circleCrop().error(R.drawable.bg_avatar_circle_accent).into(photo);
            }
            if(sent){status.setVisibility(View.VISIBLE);status.setOnClickListener(null);status.setMinHeight(0);if(m.getDeliveryStatus()==Message.DeliveryStatus.FAILED){status.setMinHeight(itemView.getResources().getDimensionPixelSize(R.dimen.touch_target_min));status.setText(R.string.message_send_failed_retry);status.setTextColor(itemView.getContext().getColor(R.color.utechat_error));status.setOnClickListener(v->retryListener.onRetry(m));}else if(m.getDeliveryStatus()==Message.DeliveryStatus.SENDING){status.setText(R.string.message_sending);status.setTextColor(itemView.getContext().getColor(R.color.utechat_on_surface_variant));}else{status.setText(R.string.message_sent);status.setTextColor(itemView.getContext().getColor(R.color.utechat_on_surface_variant));}}
            itemView.setContentDescription(sent?content.getText():itemView.getContext().getString(R.string.message_accessibility_received,sender.getText(),content.getText(),time.getText()));}
    }

    private final class PollHolder extends RecyclerView.ViewHolder {
        final TextView sender, question, time, status;
        final android.widget.LinearLayout options;
        PollHolder(View view) {
            super(view); sender=view.findViewById(R.id.tv_poll_sender); question=view.findViewById(R.id.tv_poll_question);
            time=view.findViewById(R.id.tv_poll_time); status=view.findViewById(R.id.tv_poll_status);
            options=view.findViewById(R.id.layout_poll_options);
        }
        void bind(Message message) {
            question.setOnLongClickListener(view -> showActions(message));
            sender.setText(currentUserId != null && currentUserId.equals(message.getSenderId())
                    ? (currentUserName == null ? "" : currentUserName)
                    : (message.getSenderName() == null ? "" : message.getSenderName()));
            question.setText(message.getContent() == null ? "" : message.getContent());
            time.setText(formattedTime(message)); options.removeAllViews();
            java.util.List<String> choices = message.getPollOptions();
            java.util.Map<String, Integer> votes = message.getPollVotes();
            int[] counts = new int[choices == null ? 0 : choices.size()];
            int selected = -1;
            if (votes != null) for (java.util.Map.Entry<String, Integer> vote : votes.entrySet()) {
                Integer index = vote.getValue();
                if (index != null && index >= 0 && index < counts.length) counts[index]++;
                if (currentUserId != null && currentUserId.equals(vote.getKey()) && index != null) selected=index;
            }
            if (choices != null) for (int i=0; i<choices.size(); i++) {
                final int choiceIndex=i;
                com.google.android.material.button.MaterialButton button =
                        new com.google.android.material.button.MaterialButton(itemView.getContext());
                button.setText(itemView.getContext().getString(R.string.poll_option_vote_count,
                        choices.get(i), counts[i]));
                button.setMinHeight(itemView.getResources().getDimensionPixelSize(R.dimen.touch_target_min));
                button.setInsetTop(0); button.setInsetBottom(0); button.setSelected(i == selected);
                button.setContentDescription(itemView.getContext().getString(
                        i == selected ? R.string.poll_option_accessibility_selected
                                : R.string.poll_option_accessibility, choices.get(i), counts[i]));
                button.setEnabled(message.getDeliveryStatus() != Message.DeliveryStatus.SENDING);
                button.setOnClickListener(v -> voteListener.onVote(message, choiceIndex));
                options.addView(button);
            }
            boolean sent=currentUserId != null && currentUserId.equals(message.getSenderId());
            status.setVisibility(sent ? View.VISIBLE : View.GONE);
            status.setOnClickListener(null); status.setMinHeight(0);
            if (sent) {
                status.setText(message.getDeliveryStatus()==Message.DeliveryStatus.FAILED
                        ? R.string.message_send_failed_retry : message.getDeliveryStatus()==Message.DeliveryStatus.SENDING
                        ? R.string.message_sending : R.string.message_sent);
                if (message.getDeliveryStatus() == Message.DeliveryStatus.FAILED) {
                    status.setMinHeight(itemView.getResources().getDimensionPixelSize(R.dimen.touch_target_min));
                    status.setTextColor(itemView.getContext().getColor(R.color.utechat_error));
                    status.setOnClickListener(v -> retryListener.onRetry(message));
                } else status.setTextColor(itemView.getContext().getColor(R.color.utechat_on_surface_variant));
            }
            itemView.setContentDescription(itemView.getContext().getString(R.string.poll_accessibility,
                    sender.getText(), question.getText(), counts.length));
        }
    }

    private final class ImageHolder extends RecyclerView.ViewHolder{
        final boolean sent;final ImageView image;final ProgressBar progress;final TextView error,sender,time,status;MediaRepository.Transfer download;String boundKey;
        ImageHolder(View view,boolean sent){super(view);this.sent=sent;image=view.findViewById(R.id.iv_message_image);progress=view.findViewById(R.id.progress_message_image);error=view.findViewById(R.id.tv_image_error);sender=view.findViewById(R.id.tv_sender_name);time=view.findViewById(R.id.tv_message_time);status=sent?view.findViewById(R.id.tv_message_status):null;error.setOnClickListener(v->{Message m=getBoundMessage();if(m!=null)load(m);});}
        private Message getBoundMessage(){int p=getBindingAdapterPosition();return p==RecyclerView.NO_POSITION?null:getItem(p);}
        void bind(Message m){cancelDownload();boundKey=m.getId()+":"+String.valueOf(m.getMediaUrl());image.setTag(boundKey);sender.setText(sent?(currentUserName==null?"":currentUserName):(m.getSenderName()==null?"":m.getSenderName()));time.setText(formattedTime(m));error.setVisibility(View.GONE);progress.setVisibility(View.GONE);
            image.setOnLongClickListener(view -> showActions(m));
            image.setContentDescription(itemView.getContext().getString(R.string.message_image_description,sender.getText()));
            if(sent){status.setVisibility(View.VISIBLE);status.setOnClickListener(null);status.setMinHeight(0);if(m.getDeliveryStatus()==Message.DeliveryStatus.FAILED){status.setText(R.string.message_send_failed_retry);status.setTextColor(itemView.getContext().getColor(R.color.utechat_error));status.setMinHeight(itemView.getResources().getDimensionPixelSize(R.dimen.touch_target_min));status.setOnClickListener(v->retryListener.onRetry(m));}else if(m.getDeliveryStatus()==Message.DeliveryStatus.SENDING){status.setText(itemView.getContext().getString(R.string.image_upload_progress,m.getUploadProgress()));status.setTextColor(itemView.getContext().getColor(R.color.utechat_on_surface_variant));}else{status.setText(R.string.message_sent);status.setTextColor(itemView.getContext().getColor(R.color.utechat_on_surface_variant));}}
            load(m);itemView.setContentDescription(itemView.getContext().getString(R.string.message_image_description,sender.getText()));}
        void load(Message m){cancelDownload();String local=m.getLocalMediaUri();if(local!=null){progress.setVisibility(m.getDeliveryStatus()==Message.DeliveryStatus.SENDING?View.VISIBLE:View.GONE);Glide.with(image).load(Uri.parse(local)).override(560,560).centerInside().placeholder(R.drawable.bg_bubble_received).error(R.drawable.bg_bubble_received).into(image);return;}
            String path=m.getMediaUrl();if(path==null||path.trim().isEmpty()){image.setImageResource(R.drawable.bg_bubble_received);error.setVisibility(m.getDeliveryStatus()==Message.DeliveryStatus.FAILED?View.VISIBLE:View.GONE);return;}
            File cached=media.getImageCacheFile(path);if(cached!=null&&cached.isFile()&&cached.length()>0){showFile(cached);return;}
            progress.setVisibility(View.VISIBLE);image.setImageResource(R.drawable.bg_bubble_received);
            String requestKey = boundKey;
            download = media.downloadImage(path, cached, new MediaRepository.DownloadListener() {
                @Override public void onSuccess(File file) {
                    if (!isCurrent(requestKey)) return;
                    progress.setVisibility(View.GONE);
                    showFile(file);
                }
                @Override public void onError(String message) {
                    if (!isCurrent(requestKey)) return;
                    progress.setVisibility(View.GONE);
                    error.setText(R.string.image_download_error);
                    error.setVisibility(View.VISIBLE);
                }
            });
        }
        private boolean isCurrent(String requestKey) {
            if (requestKey == null || !requestKey.equals(boundKey)) return false;
            android.content.Context context = image.getContext();
            while (context instanceof android.content.ContextWrapper) {
                if (context instanceof android.app.Activity) {
                    android.app.Activity activity = (android.app.Activity) context;
                    return !activity.isFinishing() && !activity.isDestroyed();
                }
                android.content.Context base = ((android.content.ContextWrapper) context).getBaseContext();
                if (base == context) break;
                context = base;
            }
            return true;
        }
        private void showFile(File file){if(file==null||!file.isFile()){error.setVisibility(View.VISIBLE);return;}Glide.with(image).load(file).override(560,560).centerInside().placeholder(R.drawable.bg_bubble_received).error(R.drawable.bg_bubble_received).into(image);error.setVisibility(View.GONE);}
        void clear(){cancelDownload();Glide.with(image.getContext().getApplicationContext()).clear(image);image.setTag(null);boundKey=null;}
        private void cancelDownload(){if(download!=null){download.cancel();download=null;}}
    }
}
