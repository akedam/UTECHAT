package com.example.utechat.feature.search.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.example.utechat.R;
import com.example.utechat.domain.model.Conversation;
import com.example.utechat.domain.model.Friendship;
import com.example.utechat.domain.model.User;
import com.google.android.material.button.MaterialButton;
import java.util.ArrayList;
import java.util.List;

final class SearchResultsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    interface Listener {
        void openFriend(User user);
        void openGroup(Conversation group);
        void requestFriend(User user);
        void accept(Friendship request);
    }
    static final class Entry {
        String section;
        User user;
        Conversation group;
        boolean friend;
        Friendship request;
        static Entry section(String title){Entry e=new Entry();e.section=title;return e;}
        static Entry friend(User user){Entry e=person(user,null);e.friend=true;return e;}
        static Entry group(Conversation group){Entry e=new Entry();e.group=group;return e;}
        static Entry person(User user,Friendship request){Entry e=new Entry();e.user=user;e.request=request;return e;}
    }
    private final Listener listener;
    private final String viewerUid;
    private final List<Entry> entries=new ArrayList<>();
    private boolean busy;
    SearchResultsAdapter(String uid,Listener listener){this.viewerUid=uid;this.listener=listener;}
    void submit(List<Entry> items){entries.clear();entries.addAll(items);notifyDataSetChanged();}
    void setBusy(boolean busy){if(this.busy!=busy){this.busy=busy;notifyDataSetChanged();}}
    @Override public int getItemCount(){return entries.size();}
    @Override public int getItemViewType(int position){return entries.get(position).section!=null?0:1;}
    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent,int type){
        if(type==0){
            TextView title=new TextView(parent.getContext());
            title.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
            float density=parent.getResources().getDisplayMetrics().density;
            title.setPadding((int)(16*density),(int)(16*density),(int)(16*density),(int)(6*density));
            title.setTextSize(14);title.setTypeface(null,android.graphics.Typeface.BOLD);
            title.setTextColor(androidx.core.content.ContextCompat.getColor(parent.getContext(),R.color.utechat_on_surface_variant));
            ViewCompat.setAccessibilityHeading(title,true);
            return new RecyclerView.ViewHolder(title){};
        }
        return new ResultHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_search_result,parent,false));
    }
    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder,int position){
        Entry entry=entries.get(position);
        if(entry.section!=null){((TextView)holder.itemView).setText(entry.section);return;}
        ResultHolder row=(ResultHolder)holder;
        String name=entry.group!=null?entry.group.getTitle():entry.user.getDisplayName();
        row.name.setText(name);
        row.avatar.setText(entry.group!=null?"#":entry.user.getInitials());
        String photo=entry.group!=null?entry.group.getPhotoUrl():entry.user.getPhotoUrl();
        com.bumptech.glide.Glide.with(row.photo).clear(row.photo);
        boolean hasPhoto=photo!=null&&photo.startsWith("https://");
        row.photo.setVisibility(hasPhoto?View.VISIBLE:View.GONE);
        if(hasPhoto)com.bumptech.glide.Glide.with(row.photo).load(photo).circleCrop().into(row.photo);
        row.action.setOnClickListener(null);row.action.setVisibility(View.GONE);row.action.setEnabled(!busy);
        row.itemView.setOnClickListener(null);row.itemView.setClickable(entry.friend||entry.group!=null);
        row.itemView.setFocusable(entry.friend||entry.group!=null);
        if(entry.group!=null){
            row.subtitle.setText(entry.group.getParticipantIds()==null?row.itemView.getContext().getString(R.string.search_groups)
                    :row.itemView.getContext().getString(R.string.search_group_members,entry.group.getParticipantIds().size()));
            row.itemView.setOnClickListener(v->listener.openGroup(entry.group));
        }else if(entry.friend){
            row.subtitle.setText(R.string.friends_title);row.itemView.setOnClickListener(v->listener.openFriend(entry.user));
        }else{
            row.action.setVisibility(View.VISIBLE);row.subtitle.setText(R.string.search_people);
            if(entry.request==null){row.action.setText(R.string.friends_add_button);row.action.setOnClickListener(v->listener.requestFriend(entry.user));}
            else if(viewerUid.equals(entry.request.getToUid())){
                row.subtitle.setText(R.string.search_request_received);row.action.setText(R.string.friends_accept_button);
                row.action.setOnClickListener(v->listener.accept(entry.request));
            }else{row.action.setText(R.string.search_request_sent);row.action.setEnabled(false);}
        }
    }
    private static final class ResultHolder extends RecyclerView.ViewHolder {
        final TextView name,subtitle,avatar;
        final ImageView photo;
        final MaterialButton action;
        ResultHolder(View view){super(view);name=view.findViewById(R.id.tv_search_name);subtitle=view.findViewById(R.id.tv_search_subtitle);
            avatar=view.findViewById(R.id.tv_search_avatar);photo=view.findViewById(R.id.iv_search_avatar);action=view.findViewById(R.id.btn_search_action);}
    }
}
