package com.example.utechat.data.friends;

import android.content.Context;
import com.example.utechat.R;
import com.example.utechat.domain.model.Conversation;
import com.example.utechat.domain.model.ConversationMember;
import com.example.utechat.domain.model.Friendship;
import com.example.utechat.domain.model.User;
import com.example.utechat.domain.repository.FriendGroupRepository;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;
import java.util.*;

/** Firestore implementation. Email stays private; search uses public display-name profiles only. */
public final class FirebaseFriendGroupRepository implements FriendGroupRepository {
    private static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newCachedThreadPool();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final Context context;
    public FirebaseFriendGroupRepository(Context context) { this.context = context.getApplicationContext(); }

    @Override public void searchUsers(String prefix, Result<List<User>> callback) {
        String value = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        if (value.length() < 2) { callback.success(Collections.emptyList()); return; }
        db.collection("publicProfiles").orderBy("searchName").startAt(value).endAt(value + "\uf8ff")
                .limit(20).get().addOnSuccessListener(s -> {
                    List<User> users = new ArrayList<>();
                    for (DocumentSnapshot d : s.getDocuments()) users.add(readUser(d));
                    callback.success(users);
                }).addOnFailureListener(e -> callback.error(context.getString(R.string.friends_load_error)));
    }

    @Override public void loadUsers(List<String> uids, Result<List<User>> callback) {
        if (uids == null || uids.isEmpty()) { callback.success(Collections.emptyList()); return; }
        List<User> users = Collections.synchronizedList(new ArrayList<>());
        final int[] remaining = {uids.size()};
        for (String uid : uids) db.collection("publicProfiles").document(uid).get().addOnSuccessListener(d -> {
            if (d.exists()) users.add(readUser(d));
            if (--remaining[0] == 0) callback.success(new ArrayList<>(users));
        }).addOnFailureListener(e -> { if (--remaining[0] == 0) callback.error(context.getString(R.string.friends_load_error)); });
    }

    @Override public Subscription observeFriendships(String uid, FriendshipsListener listener) {
        final List<Friendship> outgoing = new ArrayList<>(), incoming = new ArrayList<>();
        final boolean[] ready = {false, false};
        ListenerRegistration a = db.collection("friendships").whereEqualTo("fromUid", uid)
                .addSnapshotListener((s,e) -> { if (e != null) { listener.changed(Collections.emptyList(), context.getString(R.string.friends_load_error)); return; }
                    outgoing.clear(); if (s != null) for (DocumentSnapshot d:s.getDocuments()) outgoing.add(readFriendship(d)); ready[0]=true; publishFriends(listener,outgoing,incoming,ready); });
        ListenerRegistration b = db.collection("friendships").whereEqualTo("toUid", uid)
                .addSnapshotListener((s,e) -> { if (e != null) { listener.changed(Collections.emptyList(), context.getString(R.string.friends_load_error)); return; }
                    incoming.clear(); if (s != null) for (DocumentSnapshot d:s.getDocuments()) incoming.add(readFriendship(d)); ready[1]=true; publishFriends(listener,outgoing,incoming,ready); });
        return () -> { a.remove(); b.remove(); };
    }
    private void publishFriends(FriendshipsListener l,List<Friendship>a,List<Friendship>b,boolean[] ready){ if(ready[0]&&ready[1]){List<Friendship> all=new ArrayList<>(a);all.addAll(b);l.changed(all,null);} }

    @Override public void requestFriend(String from, String to, Result<Void> cb) {
        if (from == null || to == null || from.equals(to)) { cb.error(context.getString(R.string.friends_invalid_request)); return; }
        String id = pairId(from,to); var ref = db.collection("friendships").document(id);
        long now = System.currentTimeMillis();
        Map<String,Object> data = new HashMap<>();
        data.put("fromUid", from); data.put("toUid", to); data.put("status", "pending");
        data.put("createdAt", now); data.put("updatedAt", now);

        // Create directly so Firestore Rules only need to authorize the create.
        // A transaction read of a missing friendship is denied by participant-only get rules.
        ref.set(data).addOnSuccessListener(unused -> cb.success(null))
                .addOnFailureListener(writeError -> ref.get()
                        .addOnSuccessListener(existing -> resolveExistingFriendship(existing, from, to, cb, writeError))
                        .addOnFailureListener(readError -> cb.error(friendRequestError(readError))));
    }

    private void resolveExistingFriendship(DocumentSnapshot existing, String from, String to,
                                           Result<Void> cb, Exception writeError) {
        if (!existing.exists()) { cb.error(friendRequestError(writeError)); return; }
        String existingFrom = existing.getString("fromUid");
        String existingTo = existing.getString("toUid");
        boolean samePair = (from.equals(existingFrom) && to.equals(existingTo))
                || (from.equals(existingTo) && to.equals(existingFrom));
        if (!samePair) { cb.error(context.getString(R.string.friends_request_error)); return; }
        String status = existing.getString("status");
        if ("accepted".equals(status)
                || ("pending".equals(status) && from.equals(existingFrom) && to.equals(existingTo))) {
            cb.success(null);
            return;
        }
        if ("pending".equals(status) && from.equals(existingTo) && to.equals(existingFrom)) {
            db.collection("friendships").document(existing.getId())
                    .update("status", "accepted", "updatedAt", System.currentTimeMillis())
                    .addOnSuccessListener(unused -> cb.success(null))
                    .addOnFailureListener(error -> cb.error(friendRequestError(error)));
            return;
        }
        cb.error(context.getString(R.string.friends_request_error));
    }

    private String friendRequestError(Exception error) {
        if (error instanceof com.google.firebase.firestore.FirebaseFirestoreException) {
            com.google.firebase.firestore.FirebaseFirestoreException.Code code =
                    ((com.google.firebase.firestore.FirebaseFirestoreException) error).getCode();
            if (code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                return context.getString(R.string.friends_permission_error);
            }
            if (code == com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE
                    || code == com.google.firebase.firestore.FirebaseFirestoreException.Code.DEADLINE_EXCEEDED) {
                return context.getString(R.string.friends_load_error);
            }
        }
        return context.getString(R.string.friends_request_error);
    }
    @Override public void respondFriend(String id, boolean accept, Result<Void> cb) {
        var ref=db.collection("friendships").document(id);
        if (!accept) { ref.delete().addOnSuccessListener(v->cb.success(null)).addOnFailureListener(e->cb.error(context.getString(R.string.friends_request_error))); return; }
        ref.update("status","accepted","updatedAt",System.currentTimeMillis()).addOnSuccessListener(v->cb.success(null))
                .addOnFailureListener(e->cb.error(context.getString(R.string.friends_request_error)));
    }
    @Override public void removeFriend(String id, Result<Void> cb) { respondFriend(id,false,cb); }

    @Override public void openDirect(String from, String to, Result<String> cb) {
        if (from == null || to == null || from.equals(to)) { cb.error(context.getString(R.string.friends_request_error)); return; }
        var request = db.collection("friendships").document(pairId(from,to));
        request.get().addOnSuccessListener(friend -> {
            if (!friend.exists() || !"accepted".equals(friend.getString("status"))) { cb.error(context.getString(R.string.friends_request_error)); return; }
            var ref = db.collection("conversations").document(com.example.utechat.domain.model.ConversationId.direct(from,to));
            // Opening a known chat is a read, not an attempted overwrite of its history/summary.
            ref.get().addOnSuccessListener(existing -> {
                if (!existing.exists()) { createDirect(from,to,ref,cb); return; }
                Object participants=existing.get("participantIds");
                if ("direct".equals(existing.getString("type")) && participants instanceof List
                        && ((List<?>)participants).size()==2 && ((List<?>)participants).contains(from) && ((List<?>)participants).contains(to)) cb.success(ref.getId());
                else cb.error(context.getString(R.string.friends_request_error));
            }).addOnFailureListener(error -> {
                // Participant-only rules deny reading a conversation that has not been created yet.
                if(error instanceof com.google.firebase.firestore.FirebaseFirestoreException
                        && ((com.google.firebase.firestore.FirebaseFirestoreException)error).getCode()==com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED)
                    createDirect(from,to,ref,cb);
                else cb.error(context.getString(R.string.friends_load_error));
            });
        }).addOnFailureListener(e->cb.error(context.getString(R.string.friends_request_error)));
    }

    private void createDirect(String from,String to,com.google.firebase.firestore.DocumentReference ref,Result<String> cb) {
        db.collection("publicProfiles").document(from).get().addOnSuccessListener(me ->
                db.collection("publicProfiles").document(to).get().addOnSuccessListener(peer -> {
                    if (!me.exists() || !peer.exists()) { cb.error(context.getString(R.string.friends_load_error)); return; }
                    Map<String,Object> summaries = new HashMap<>(); summaries.put(from, summary(readUser(me))); summaries.put(to, summary(readUser(peer)));
                    long now=System.currentTimeMillis(); Map<String,Object> data=new HashMap<>();data.put("type","direct");data.put("participantIds",Arrays.asList(from,to));data.put("participantSummaries",summaries);data.put("lastMessage","");data.put("lastMessageType","text");data.put("lastMessageAt",now);data.put("createdBy",from);data.put("createdAt",now);data.put("updatedAt",now);
                    ref.set(data).addOnSuccessListener(unused -> cb.success(ref.getId()))
                            .addOnFailureListener(writeError -> ref.get()
                                    .addOnSuccessListener(existing -> {
                                        Object participants = existing.get("participantIds");
                                        if (existing.exists() && "direct".equals(existing.getString("type"))
                                                && participants instanceof List
                                                && ((List<?>) participants).contains(from)
                                                && ((List<?>) participants).contains(to)) {
                                            cb.success(ref.getId());
                                        } else {
                                            cb.error(context.getString(R.string.friends_request_error));
                                        }
                                    })
                                    .addOnFailureListener(readError -> cb.error(context.getString(R.string.friends_request_error))));
                }).addOnFailureListener(e->cb.error(context.getString(R.string.friends_load_error))))
                .addOnFailureListener(e->cb.error(context.getString(R.string.friends_load_error)));
    }

    @Override public void createGroup(String ownerUid,String name,List<User> initial,Result<String> cb) {
        String title=name==null?"":name.trim();
        if(title.isEmpty()||title.length()>80){cb.error(context.getString(R.string.groups_name_invalid));return;}
        String id=UUID.randomUUID().toString(); var root=db.collection("conversations").document(id); long now=System.currentTimeMillis();
        LinkedHashMap<String,User> unique=new LinkedHashMap<>();
        if(initial!=null) for(User u:initial) if(u!=null&&u.getUid()!=null&&!u.getUid().equals(ownerUid)) unique.put(u.getUid(),u);
        if(unique.size()>4){cb.error(context.getString(R.string.groups_initial_member_limit));return;}
        if(unique.size()<2){cb.error(context.getString(R.string.groups_select_one_member));return;}
        ArrayList<String> ids=new ArrayList<>();ids.add(ownerUid);ids.addAll(unique.keySet());
        Map<String,Object> data=new HashMap<>();data.put("type","group");data.put("groupName",title);data.put("participantIds",ids);data.put("participantSummaries",summaries(ownerUid,unique));data.put("createdBy",ownerUid);data.put("createdAt",now);data.put("updatedAt",now);data.put("lastMessageAt",now);data.put("lastMessage","");data.put("lastMessageType","text");
        data.put("isCommunity",false);data.put("ownerUid",ownerUid);
        WriteBatch batch=db.batch();batch.set(root,data);
        Map<String,Object> owner=memberData(ownerUid,"owner",now);batch.set(root.collection("members").document(ownerUid),owner);
        for(User u:unique.values()) batch.set(root.collection("members").document(u.getUid()),memberData(u.getUid(),"member",now));
        batch.commit().addOnSuccessListener(v->cb.success(id)).addOnFailureListener(e->cb.error(context.getString(R.string.groups_create_error)));
    }
    private Map<String,Object> summaries(String ownerUid,Map<String,User> users){Map<String,Object> m=new HashMap<>();for(User u:users.values())m.put(u.getUid(),summary(u));return m;}
    private Map<String,Object> summary(User u){Map<String,Object> m=new HashMap<>();m.put("displayName",u.getDisplayName()==null?"":u.getDisplayName());m.put("photoUrl",u.getPhotoUrl());return m;}
    private Map<String,Object> memberData(String uid,String role,long now){Map<String,Object> m=new HashMap<>();m.put("userId",uid);m.put("role",role);m.put("joinedAt",now);m.put("lastReadAt",now);m.put("unreadCount",0);return m;}

    @Override public Subscription observeGroups(String uid,GroupsListener listener){
        ListenerRegistration reg=db.collection("conversations").whereArrayContains("participantIds",uid).addSnapshotListener((s,e)->{
            if(e!=null){listener.changed(Collections.emptyList(),context.getString(R.string.groups_load_error));return;}
            List<Conversation> out=new ArrayList<>();if(s!=null)for(DocumentSnapshot d:s.getDocuments())if("group".equals(d.getString("type"))&&(!"deleting".equals(d.getString("lifecycle"))||uid.equals(d.getString("deletingBy"))))try{out.add(com.example.utechat.data.chat.FirestoreConversationMapper.fromSnapshot(d,uid));}catch(RuntimeException ignored){}
            out.sort((a,b)->Long.compare(b.getUpdatedAt(),a.getUpdatedAt()));listener.changed(out,null);
        });return reg::remove;
    }
    @Override public Subscription observeMembers(String id,MembersListener listener){ListenerRegistration reg=db.collection("conversations").document(id).collection("members").addSnapshotListener((s,e)->{
        if(e!=null){listener.changed(Collections.emptyList(),context.getString(R.string.groups_load_error));return;}List<ConversationMember> out=new ArrayList<>();if(s!=null)for(DocumentSnapshot d:s.getDocuments()){ConversationMember m=new ConversationMember();m.setUserId(d.getId());m.setRole(ConversationMember.Role.fromSchemaValue(d.getString("role")));m.setJoinedAt(number(d.get("joinedAt")));m.setUnreadCount((int)number(d.get("unreadCount")));out.add(m);}listener.changed(out,null);
    });return reg::remove;}

    @Override public void addMember(String id,User user,Result<Void> cb){if(user==null||user.getUid()==null){cb.error(context.getString(R.string.groups_member_error));return;}
        var root=db.collection("conversations").document(id);var mem=root.collection("members").document(user.getUid());
        db.runTransaction(t->{DocumentSnapshot r=t.get(root);if(!activeGroup(r))throw new IllegalStateException();
            List<String> ids=(List<String>)r.get("participantIds");ArrayList<String> updated=ids==null?new ArrayList<>():new ArrayList<>(ids);long now=System.currentTimeMillis();
            if(updated.contains(user.getUid())){DocumentSnapshot existingMember=t.get(mem);if(existingMember.exists())return null;throw new IllegalStateException("member_record_missing");}
            boolean community=Boolean.TRUE.equals(r.getBoolean("isCommunity"));
            if(updated.size()>=(community?10:5))throw new IllegalStateException(community?"community_full":"group_full");
            updated.add(user.getUid());t.set(mem,memberData(user.getUid(),"member",now));
            t.update(root,"participantIds",updated,"participantSummaries."+user.getUid(),summary(user),"updatedAt",now,
                    "membershipChangeAction","add","membershipChangeUid",user.getUid());return null;
        }).addOnSuccessListener(v->cb.success(null)).addOnFailureListener(e->cb.error(context.getString(
                hasError(e,"group_full")?R.string.groups_full:
                hasError(e,"community_full")?R.string.groups_community_full:R.string.groups_member_error)));
    }
    @Override public void removeMember(String id,String memberUid,Result<Void> cb){updateMembership(id,memberUid,cb);}
    @Override public void leaveGroup(String id,String uid,Result<Void> cb){updateMembership(id,uid,cb);}
    @Override public void transferOwnership(String id,String targetUid,Result<Void> cb){
        String actor=com.google.firebase.auth.FirebaseAuth.getInstance().getUid();
        if(actor==null||actor.equals(targetUid)){cb.error(context.getString(R.string.groups_member_error));return;}
        var root=db.collection("conversations").document(id);var owner=root.collection("members").document(actor);var target=root.collection("members").document(targetUid);
        db.runTransaction(t->{DocumentSnapshot r=t.get(root),a=t.get(owner),b=t.get(target);
            if(!activeGroup(r)||!listed(r,actor)||!listed(r,targetUid)||!a.exists()||!b.exists()||!"owner".equals(a.getString("role"))||!Arrays.asList("admin","member").contains(b.getString("role")))throw new IllegalStateException();
            t.update(root,"ownerUid",targetUid,"updatedAt",System.currentTimeMillis());t.update(owner,"role","admin");t.update(target,"role","owner");return null;
        }).addOnSuccessListener(v->cb.success(null)).addOnFailureListener(e->cb.error(context.getString(R.string.groups_member_error)));
    }
    @Override public void setDeputy(String id,String uid,boolean deputy,Result<Void> cb){
        String actor=com.google.firebase.auth.FirebaseAuth.getInstance().getUid();
        if(actor==null||actor.equals(uid)){cb.error(context.getString(R.string.groups_member_error));return;}
        var root=db.collection("conversations").document(id);var target=root.collection("members").document(uid);
        db.runTransaction(t->{DocumentSnapshot r=t.get(root),a=t.get(root.collection("members").document(actor)),b=t.get(target);
            if(!activeGroup(r)||!listed(r,uid)||!listed(r,actor)||!a.exists()||!"owner".equals(a.getString("role"))||!b.exists()||!Arrays.asList("admin","member").contains(b.getString("role")))throw new IllegalStateException();
            t.update(target,"role",deputy?"admin":"member");return null;
        }).addOnSuccessListener(v->cb.success(null)).addOnFailureListener(e->cb.error(context.getString(R.string.groups_member_error)));
    }
    @Override public void upgradeCommunity(String id,Result<Void> cb){
        String actor=com.google.firebase.auth.FirebaseAuth.getInstance().getUid();if(actor==null){cb.error(context.getString(R.string.groups_member_error));return;}
        var root=db.collection("conversations").document(id);
        db.runTransaction(t->{DocumentSnapshot r=t.get(root),a=t.get(root.collection("members").document(actor));
            if(!activeGroup(r)||!a.exists()||!"owner".equals(a.getString("role"))||!listed(r,actor))throw new IllegalStateException();
            List<?> ids=(List<?>)r.get("participantIds");if(ids==null||ids.size()<5||ids.size()>10)throw new IllegalStateException("invalid_upgrade_size");
            if(!Boolean.TRUE.equals(r.getBoolean("isCommunity")))t.update(root,"isCommunity",true,"updatedAt",System.currentTimeMillis());return null;
        }).addOnSuccessListener(v->cb.success(null)).addOnFailureListener(e->cb.error(context.getString(R.string.groups_upgrade_error)));
    }
    @Override public void deleteGroup(String id,Result<Void> cb){
        IO.execute(()->{try{
            byte[] body=new org.json.JSONObject().put("conversationId",id).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            com.example.utechat.data.backend.BackendClient.Response response=com.example.utechat.data.backend.BackendClient.request("POST","/api/group-delete",body,"application/json",true);
            if(!response.ok())throw new IllegalStateException();main.post(()->cb.success(null));
        }catch(Exception e){main.post(()->cb.error(context.getString(R.string.groups_delete_error)));}});
    }
    private boolean activeGroup(DocumentSnapshot r){return r.exists()&&"group".equals(r.getString("type"))&&!"deleting".equals(r.getString("lifecycle"));}
    private boolean hasError(Throwable error,String code){for(int i=0;error!=null&&i<10;i++,error=error.getCause())if(code.equals(error.getMessage()))return true;return false;}
    private boolean listed(DocumentSnapshot r,String uid){Object ids=r.get("participantIds");return ids instanceof List&&((List<?>)ids).contains(uid);}
    private void updateMembership(String id,String uid,Result<Void> cb){var root=db.collection("conversations").document(id);var mem=root.collection("members").document(uid);
        db.runTransaction(t->{DocumentSnapshot r=t.get(root);if(!activeGroup(r))throw new IllegalStateException("group_missing");List<String> ids=(List<String>)r.get("participantIds");ArrayList<String> next=ids==null?new ArrayList<>():new ArrayList<>(ids);if(!next.contains(uid))return null;DocumentSnapshot m=t.get(mem);if(!m.exists())throw new IllegalStateException("member_record_missing");if("owner".equals(m.getString("role")))throw new IllegalStateException("transfer_owner_first");next.remove(uid);long now=System.currentTimeMillis();t.delete(mem);t.update(root,"participantIds",next,"participantSummaries."+uid,FieldValue.delete(),"updatedAt",now,"membershipChangeAction","remove","membershipChangeUid",uid);return null;
        }).addOnSuccessListener(v->cb.success(null)).addOnFailureListener(e->cb.error(context.getString(R.string.groups_member_error)));
    }
    private String pairId(String a,String b){String left=a.compareTo(b)<0?a:b,right=a.compareTo(b)<0?b:a;return left+"_"+right;}
    private Friendship readFriendship(DocumentSnapshot d){Friendship f=new Friendship();f.setId(d.getId());f.setFromUid(d.getString("fromUid"));f.setToUid(d.getString("toUid"));String s=d.getString("status");try{f.setStatus(Friendship.Status.valueOf(s.toUpperCase(Locale.ROOT)));}catch(Exception e){f.setStatus(Friendship.Status.PENDING);}f.setCreatedAt(number(d.get("createdAt")));f.setUpdatedAt(number(d.get("updatedAt")));return f;}
    private User readUser(DocumentSnapshot d){User u=new User();u.setUid(d.getId());u.setDisplayName(d.getString("displayName"));u.setPhotoUrl(d.getString("photoUrl"));return u;}
    private long number(Object o){return o instanceof Number?((Number)o).longValue():0;}
    @Override public boolean isMock(){return false;}
}
