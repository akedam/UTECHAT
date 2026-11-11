package com.example.utechat.data.chat;
import org.junit.Test;
import static org.junit.Assert.*;
public class UnreadStateTest {
    @Test public void newIncomingPreviewIsUnreadUntilObserved(){
        assertTrue(UnreadState.isUnread("me","peer","new",200,"old",100));
        assertFalse(UnreadState.isUnread("me","peer","new",200,"new",200));
    }
    @Test public void ownMessagesAndOlderMessagesAreNotUnread(){
        assertFalse(UnreadState.isUnread("me","me","own",300,"old",100));
        assertFalse(UnreadState.isUnread("me","peer","old",100,"new",200));
    }
    @Test public void equalTimestampsStillDistinguishNewMessages(){
        assertTrue(UnreadState.isUnread("me","peer","second",200,"first",200));
        assertFalse(UnreadState.isUnread("me","peer","second",200,"second",200));
    }
    @Test public void missingLegacySummaryDoesNotInventUnread(){
        assertFalse(UnreadState.isUnread("me",null,null,0,null,0));
    }
}
