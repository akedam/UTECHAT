package com.example.utechat.feature.safety;

import android.content.Context;
import android.widget.Toast;

import com.example.utechat.R;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Map;

/** Shared dialogs for account protections. Firestore Rules enforce all writes. */
public final class UserSafety {
    private UserSafety() { }

    public static DocumentReference blockReference(FirebaseFirestore db, String owner, String peer) {
        return db.collection("users").document(owner).collection("blocked").document(peer);
    }

    public static void showActions(androidx.fragment.app.FragmentActivity host, String peer, String name,
                                   String conversationId) {
        String me = FirebaseAuth.getInstance().getUid();
        if (peer == null || me == null || me.equals(peer)) return;
        var db = FirebaseFirestore.getInstance();
        blockReference(db, me, peer).get(Source.SERVER).addOnCompleteListener(host, task -> {
            if (!task.isSuccessful()) {
                toast(host, R.string.safety_update_error);
                return;
            }
            boolean blocked = task.getResult().exists();
            CharSequence[] options = {
                host.getString(blocked ? R.string.unblock_person : R.string.block_person),
                host.getString(R.string.report_person),
            };
            String label = name == null || name.trim().isEmpty() ? host.getString(R.string.friends_unknown_user) : name;
            new MaterialAlertDialogBuilder(host).setTitle(label).setItems(options, (dialog, choice) -> {
                if (choice == 0) confirmBlock(host, peer, label, blocked, null);
                else report(host, db, me, peer, label, conversationId);
            }).setNegativeButton(R.string.friends_cancel_button, null).show();
        });
    }

    public static void confirmBlock(androidx.fragment.app.FragmentActivity host, String peer, String name,
                                    boolean blocked, Runnable onChanged) {
        String me = FirebaseAuth.getInstance().getUid();
        if (me == null || me.equals(peer)) return;
        int action = blocked ? R.string.unblock_person : R.string.block_person;
        new MaterialAlertDialogBuilder(host).setTitle(action)
                .setMessage(host.getString(blocked ? R.string.unblock_confirm : R.string.block_confirm, name))
                .setNegativeButton(R.string.friends_cancel_button, null)
                .setPositiveButton(action, (dialog, which) -> {
                    if (!me.equals(FirebaseAuth.getInstance().getUid())) return;
                    var db = FirebaseFirestore.getInstance();
                    DocumentReference ref = blockReference(db, me, peer);
                    Task<Void> change = blocked ? ref.delete() : db.runTransaction(tx -> {
                        if (!tx.get(ref).exists()) tx.set(ref, Map.of("createdAt", FieldValue.serverTimestamp()));
                        return null;
                    });
                    change.addOnCompleteListener(host, task -> {
                        toast(host, task.isSuccessful() ? R.string.blocked_saved : R.string.safety_update_error);
                        if (task.isSuccessful() && onChanged != null) onChanged.run();
                    });
                }).show();
    }

    private static void report(androidx.fragment.app.FragmentActivity host, FirebaseFirestore db, String me,
                               String peer, String name, String conversationId) {
        String[] reasons = {"spam", "abuse", "other"};
        CharSequence[] labels = {
            host.getString(R.string.report_spam), host.getString(R.string.report_abuse),
            host.getString(R.string.report_other),
        };
        int[] selection = {0};
        new MaterialAlertDialogBuilder(host).setTitle(host.getString(R.string.report_title, name))
                .setSingleChoiceItems(labels, 0, (dialog, index) -> selection[0] = index)
                .setNegativeButton(R.string.friends_cancel_button, null)
                .setPositiveButton(R.string.report_submit, (dialog, which) -> {
                    if (!me.equals(FirebaseAuth.getInstance().getUid())) return;
                    var ref = db.collection("userReports").document(me).collection("targets").document(peer);
                    db.runTransaction(tx -> {
                        if (tx.get(ref).exists()) return false;
                        tx.set(ref, Map.of("conversationId", conversationId,
                                "reason", reasons[selection[0]], "createdAt", FieldValue.serverTimestamp()));
                        return true;
                    }).addOnCompleteListener(host, task -> {
                        int message = !task.isSuccessful() ? R.string.safety_update_error
                                : task.getResult() ? R.string.report_sent : R.string.report_already_sent;
                        toast(host, message);
                    });
                }).show();
    }

    public static void showBlockedAccounts(androidx.fragment.app.FragmentActivity host) {
        String me = FirebaseAuth.getInstance().getUid();
        if (me == null) return;
        var db = FirebaseFirestore.getInstance();
        db.collection("users").document(me).collection("blocked").get(Source.SERVER)
                .addOnCompleteListener(host, task -> {
                    if (!task.isSuccessful()) {
                        toast(host, R.string.safety_update_error);
                        return;
                    }
                    var docs = task.getResult().getDocuments();
                    if (docs.isEmpty()) {
                        new MaterialAlertDialogBuilder(host).setTitle(R.string.blocked_people)
                                .setMessage(R.string.no_blocked_people)
                                .setPositiveButton(R.string.community_close, null).show();
                        return;
                    }
                    var names = new java.util.ArrayList<Task<com.google.firebase.firestore.DocumentSnapshot>>();
                    for (var doc : docs) names.add(db.collection("publicProfiles").document(doc.getId()).get());
                    com.google.android.gms.tasks.Tasks.whenAllComplete(names).addOnCompleteListener(host, unused -> {
                        if (!me.equals(FirebaseAuth.getInstance().getUid())) return;
                        String[] labels = new String[docs.size()];
                        for (int i = 0; i < labels.length; i++) {
                            String label = names.get(i).isSuccessful()
                                    ? names.get(i).getResult().getString("displayName") : null;
                            labels[i] = label == null || label.isEmpty() ? docs.get(i).getId() : label;
                        }
                        new MaterialAlertDialogBuilder(host).setTitle(R.string.blocked_people)
                                .setItems(labels, (dialog, which) -> confirmBlock(host, docs.get(which).getId(),
                                        labels[which], true, () -> showBlockedAccounts(host)))
                                .setNegativeButton(R.string.community_close, null).show();
                    });
                });
    }

    private static void toast(Context context, int message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }
}
