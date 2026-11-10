package com.example.utechat.data.presence;

import androidx.annotation.NonNull;

import com.example.utechat.domain.repository.PresenceRepository;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

/** Reads a user's opt-in Realtime Database presence node. */
public final class FirebasePresenceRepository implements PresenceRepository {
    private final FirebaseDatabase database;

    public FirebasePresenceRepository(FirebaseDatabase database) {
        this.database = database;
    }

    @Override public Subscription observe(String uid, Listener listener) {
        DatabaseReference reference = database.getReference("presence").child(uid);
        ValueEventListener valueListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                DataSnapshot connections = snapshot.child("connections");
                boolean online = false;
                for (DataSnapshot connection : connections.getChildren()) {
                    if (Boolean.TRUE.equals(connection.getValue(Boolean.class))) {
                        online = true;
                        break;
                    }
                }
                Object rawLastSeen = snapshot.child("lastSeen").getValue();
                long lastSeen = rawLastSeen instanceof Number ? ((Number) rawLastSeen).longValue() : 0L;
                listener.onChanged(online, lastSeen);
            }

            @Override public void onCancelled(@NonNull DatabaseError error) {
                listener.onChanged(false, 0L);
            }
        };
        reference.addValueEventListener(valueListener);
        return () -> reference.removeEventListener(valueListener);
    }
}
