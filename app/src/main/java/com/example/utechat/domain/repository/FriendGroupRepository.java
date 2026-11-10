package com.example.utechat.domain.repository;

import com.example.utechat.domain.model.Conversation;
import com.example.utechat.domain.model.ConversationMember;
import com.example.utechat.domain.model.Friendship;
import com.example.utechat.domain.model.User;
import java.util.List;

/** Data operations for friend requests and member-managed group conversations. */
public interface FriendGroupRepository {
    interface Result<T> { void success(T value); void error(String message); }
    interface Subscription { void remove(); }
    interface UsersListener { void changed(List<User> users, String error); }
    interface FriendshipsListener { void changed(List<Friendship> items, String error); }
    interface GroupsListener { void changed(List<Conversation> items, String error); }
    interface MembersListener { void changed(List<ConversationMember> items, String error); }

    void searchUsers(String prefix, Result<List<User>> callback);
    void loadUsers(List<String> uids, Result<List<User>> callback);
    Subscription observeFriendships(String uid, FriendshipsListener listener);
    void requestFriend(String fromUid, String toUid, Result<Void> callback);
    void respondFriend(String friendshipId, boolean accept, Result<Void> callback);
    void removeFriend(String friendshipId, Result<Void> callback);
    void openDirect(String fromUid, String toUid, Result<String> callback);
    void createGroup(String ownerUid, String name, List<User> initialMembers, Result<String> callback);
    Subscription observeGroups(String uid, GroupsListener listener);
    Subscription observeMembers(String conversationId, MembersListener listener);
    void addMember(String conversationId, User member, Result<Void> callback);
    void removeMember(String conversationId, String memberUid, Result<Void> callback);
    void transferOwnership(String conversationId, String targetUid, Result<Void> callback);
    void leaveGroup(String conversationId, String uid, Result<Void> callback);
    boolean isMock();
}
