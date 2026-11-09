package com.example.utechat.feature.friends.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.utechat.R;
import com.example.utechat.domain.model.User;

/**
 * Adapter cho danh sách bạn bè.
 */
public class FriendsAdapter extends ListAdapter<User, FriendsAdapter.ViewHolder> {

    public interface OnFriendClickListener {
        void onFriendClick(User user);
    }

    private final OnFriendClickListener listener;

    public FriendsAdapter(OnFriendClickListener listener) {
        super(DIFF_CALLBACK);
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<User> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<User>() {
                @Override
                public boolean areItemsTheSame(@NonNull User a, @NonNull User b) {
                    return a.getUid().equals(b.getUid());
                }
                @Override
                public boolean areContentsTheSame(@NonNull User a, @NonNull User b) {
                    return a.getDisplayName().equals(b.getDisplayName())
                            && a.isOnline() == b.isOnline();
                }
            };

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_friend, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvAvatar, tvName, tvStatus;
        private final View onlineIndicator;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvAvatar = itemView.findViewById(R.id.tv_avatar_placeholder);
            tvName = itemView.findViewById(R.id.tv_friend_name);
            tvStatus = itemView.findViewById(R.id.tv_friend_status);
            onlineIndicator = itemView.findViewById(R.id.view_online_indicator);
        }

        void bind(User user) {
            tvAvatar.setText(user.getInitials());
            tvName.setText(user.getDisplayName());

            boolean online = user.isOnline();
            onlineIndicator.setVisibility(online ? View.VISIBLE : View.INVISIBLE);
            tvStatus.setText(online
                    ? itemView.getContext().getString(R.string.status_online)
                    : itemView.getContext().getString(R.string.status_offline));

            itemView.setOnClickListener(v -> listener.onFriendClick(user));
        }
    }
}
