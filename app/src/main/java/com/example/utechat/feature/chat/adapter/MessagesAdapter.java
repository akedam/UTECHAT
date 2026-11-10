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
            VIEW_TYPE_SENT_IMAGE = 3, VIEW_TYPE_RECEIVED_IMAGE = 4;
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm", Locale.getDefault());
    public interface RetryListener { void onRetry(Message message); }
    private final String currentUserId, currentUserName;
    private final RetryListener retryListener;
    private final MediaRepository media;

    public MessagesAdapter(String currentUserId, String currentUserName, RetryListener retryListener,
                           MediaRepository media) {
        super(DIFF_CALLBACK); this.currentUserId=currentUserId;this.currentUserName=currentUserName;
        this.retryListener=retryListener;this.media=media;
    }
    public Message getMessageAt(int position){return super.getItem(position);}
    private static final DiffUtil.ItemCallback<Message> DIFF_CALLBACK=new DiffUtil.ItemCallback<Message>(){
        @Override public boolean areItemsTheSame(@NonNull Message a,@NonNull Message b){return Objects.equals(a.getId(),b.getId());}
        @Override public boolean areContentsTheSame(@NonNull Message a,@NonNull Message b){return Objects.equals(a.getSenderId(),b.getSenderId())&&Objects.equals(a.getSenderName(),b.getSenderName())&&Objects.equals(a.getContent(),b.getContent())&&Objects.equals(a.getMediaUrl(),b.getMediaUrl())&&Objects.equals(a.getLocalMediaUri(),b.getLocalMediaUri())&&a.getSentAt()==b.getSentAt()&&a.getUploadProgress()==b.getUploadProgress()&&a.getDeliveryStatus()==b.getDeliveryStatus();}
    };
    @Override public int getItemViewType(int position){Message m=getItem(position);boolean mine=currentUserId!=null&&currentUserId.equals(m.getSenderId());if(m.getType()==Message.Type.IMAGE)return mine?VIEW_TYPE_SENT_IMAGE:VIEW_TYPE_RECEIVED_IMAGE;return mine?VIEW_TYPE_SENT:VIEW_TYPE_RECEIVED;}
    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent,int type){LayoutInflater inflater=LayoutInflater.from(parent.getContext());switch(type){case VIEW_TYPE_SENT_IMAGE:return new ImageHolder(inflater.inflate(R.layout.item_message_image_sent,parent,false),true);case VIEW_TYPE_RECEIVED_IMAGE:return new ImageHolder(inflater.inflate(R.layout.item_message_image_received,parent,false),false);case VIEW_TYPE_SENT:return new TextHolder(inflater.inflate(R.layout.item_message_sent,parent,false),true);default:return new TextHolder(inflater.inflate(R.layout.item_message_received,parent,false),false);}}
    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder,int position){Message m=getItem(position);if(holder instanceof ImageHolder)((ImageHolder)holder).bind(m);else ((TextHolder)holder).bind(m);}
    @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder){if(holder instanceof ImageHolder)((ImageHolder)holder).clear();super.onViewRecycled(holder);}
    private String formattedTime(Message m){return m.getSentAt()>0?TIME_FORMAT.format(new Date(m.getSentAt())):"";}

    private final class TextHolder extends RecyclerView.ViewHolder{
        final TextView content,sender,time,status;final boolean sent;
        TextHolder(View view,boolean sent){super(view);this.sent=sent;content=view.findViewById(R.id.tv_message_content);sender=view.findViewById(R.id.tv_sender_name);time=view.findViewById(R.id.tv_message_time);status=sent?view.findViewById(R.id.tv_message_status):null;}
        void bind(Message m){sender.setText(sent?(currentUserName==null?"":currentUserName):(m.getSenderName()==null?"":m.getSenderName()));content.setText(m.getContent()==null?"":m.getContent());time.setText(formattedTime(m));
            if(sent){status.setVisibility(View.VISIBLE);status.setOnClickListener(null);status.setMinHeight(0);if(m.getDeliveryStatus()==Message.DeliveryStatus.FAILED){status.setMinHeight(itemView.getResources().getDimensionPixelSize(R.dimen.touch_target_min));status.setText(R.string.message_send_failed_retry);status.setTextColor(itemView.getContext().getColor(R.color.utechat_error));status.setOnClickListener(v->retryListener.onRetry(m));}else if(m.getDeliveryStatus()==Message.DeliveryStatus.SENDING){status.setText(R.string.message_sending);status.setTextColor(itemView.getContext().getColor(R.color.utechat_on_surface_variant));}else{status.setText(R.string.message_sent);status.setTextColor(itemView.getContext().getColor(R.color.utechat_on_surface_variant));}}
            itemView.setContentDescription(sent?content.getText():itemView.getContext().getString(R.string.message_accessibility_received,sender.getText(),content.getText(),time.getText()));}
    }

    private final class ImageHolder extends RecyclerView.ViewHolder{
        final boolean sent;final ImageView image;final ProgressBar progress;final TextView error,sender,time,status;MediaRepository.Transfer download;String boundKey;
        ImageHolder(View view,boolean sent){super(view);this.sent=sent;image=view.findViewById(R.id.iv_message_image);progress=view.findViewById(R.id.progress_message_image);error=view.findViewById(R.id.tv_image_error);sender=view.findViewById(R.id.tv_sender_name);time=view.findViewById(R.id.tv_message_time);status=sent?view.findViewById(R.id.tv_message_status):null;error.setOnClickListener(v->{Message m=getBoundMessage();if(m!=null)load(m);});}
        private Message getBoundMessage(){int p=getBindingAdapterPosition();return p==RecyclerView.NO_POSITION?null:getItem(p);}
        void bind(Message m){cancelDownload();boundKey=m.getId()+":"+String.valueOf(m.getMediaUrl());image.setTag(boundKey);sender.setText(sent?(currentUserName==null?"":currentUserName):(m.getSenderName()==null?"":m.getSenderName()));time.setText(formattedTime(m));error.setVisibility(View.GONE);progress.setVisibility(View.GONE);
            image.setContentDescription(itemView.getContext().getString(R.string.message_image_description,sender.getText()));
            if(sent){status.setVisibility(View.VISIBLE);status.setOnClickListener(null);status.setMinHeight(0);if(m.getDeliveryStatus()==Message.DeliveryStatus.FAILED){status.setText(R.string.message_send_failed_retry);status.setTextColor(itemView.getContext().getColor(R.color.utechat_error));status.setMinHeight(itemView.getResources().getDimensionPixelSize(R.dimen.touch_target_min));status.setOnClickListener(v->retryListener.onRetry(m));}else if(m.getDeliveryStatus()==Message.DeliveryStatus.SENDING){status.setText(itemView.getContext().getString(R.string.image_upload_progress,m.getUploadProgress()));status.setTextColor(itemView.getContext().getColor(R.color.utechat_on_surface_variant));}else{status.setText(R.string.message_sent);status.setTextColor(itemView.getContext().getColor(R.color.utechat_on_surface_variant));}}
            load(m);itemView.setContentDescription(itemView.getContext().getString(R.string.message_image_description,sender.getText()));}
        void load(Message m){cancelDownload();String local=m.getLocalMediaUri();if(local!=null){progress.setVisibility(m.getDeliveryStatus()==Message.DeliveryStatus.SENDING?View.VISIBLE:View.GONE);Glide.with(image).load(Uri.parse(local)).override(560,560).centerInside().placeholder(R.drawable.bg_bubble_received).error(R.drawable.bg_bubble_received).into(image);return;}
            String path=m.getMediaUrl();if(path==null||path.trim().isEmpty()){image.setImageResource(R.drawable.bg_bubble_received);error.setVisibility(m.getDeliveryStatus()==Message.DeliveryStatus.FAILED?View.VISIBLE:View.GONE);return;}
            File cached=media.getImageCacheFile(path);if(cached!=null&&cached.isFile()&&cached.length()>0){showFile(cached);return;}
            progress.setVisibility(View.VISIBLE);image.setImageResource(R.drawable.bg_bubble_received);
            download=media.downloadImage(path,media.getImageCacheFile(path),new MediaRepository.DownloadListener(){@Override public void onSuccess(File file){if(!isCurrent())return;progress.setVisibility(View.GONE);showFile(file);}@Override public void onError(String message){if(!isCurrent())return;progress.setVisibility(View.GONE);error.setText(R.string.image_download_error);error.setVisibility(View.VISIBLE);}});
        }
        private boolean isCurrent(){return boundKey!=null&&boundKey.equals(image.getTag());}
        private void showFile(File file){if(file==null||!file.isFile()){error.setVisibility(View.VISIBLE);return;}Glide.with(image).load(file).override(560,560).centerInside().placeholder(R.drawable.bg_bubble_received).error(R.drawable.bg_bubble_received).into(image);error.setVisibility(View.GONE);}
        void clear(){cancelDownload();Glide.with(image).clear(image);image.setTag(null);boundKey=null;}
        private void cancelDownload(){if(download!=null){download.cancel();download=null;}}
    }
}
