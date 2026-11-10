package com.example.utechat.domain.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Một lô hội thoại; cursor là document ID cuối cùng, null khi hết dữ liệu. */
public class ConversationPage {
    private final List<Conversation> items;
    private final String nextCursor;

    public ConversationPage(List<Conversation> items, String nextCursor) {
        this.items = items == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(items));
        this.nextCursor = nextCursor;
    }

    public List<Conversation> getItems() { return items; }
    public String getNextCursor() { return nextCursor; }
    public boolean hasMore() { return nextCursor != null && !nextCursor.isEmpty(); }
}
