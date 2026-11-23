package com.example.utechat.data.media;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class AttachmentPolicyTest {
    @Test public void recognizesAllowedExtensionsIndependentOfDeviceLanguage() {
        assertEquals(AttachmentPolicy.PDF, AttachmentPolicy.typeForName("Bài tập.PDF"));
        assertEquals(AttachmentPolicy.WORD, AttachmentPolicy.typeForName("report.final.docx"));
        assertEquals(AttachmentPolicy.TEXT, AttachmentPolicy.typeForName("notes.txt"));
    }

    @Test public void rejectsPathComponentsControlCharactersAndUnsupportedExtensions() {
        assertNull(AttachmentPolicy.typeForName("../notes.txt"));
        assertNull(AttachmentPolicy.typeForName("dir\\notes.txt"));
        assertNull(AttachmentPolicy.typeForName("notes\n.txt"));
        assertNull(AttachmentPolicy.typeForName("notes\u202e.txt"));
        assertNull(AttachmentPolicy.typeForName("notes.exe"));
        assertNull(AttachmentPolicy.typeForName("notes.pdf "));
        assertNull(AttachmentPolicy.typeForName(".pdf"));
        assertNull(AttachmentPolicy.typeForName(null));
    }
}
