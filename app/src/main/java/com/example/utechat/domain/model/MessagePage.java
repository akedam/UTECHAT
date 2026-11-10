package com.example.utechat.domain.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MessagePage {
    private final List<Message> items;
    private final boolean hasMore;

    public MessagePage(List<Message> items, boolean hasMore) {
        this.items = items == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(items));
        this.hasMore = hasMore;
    }

    public List<Message> getItems() { return items; }
    public boolean hasMore() { return hasMore; }
}
