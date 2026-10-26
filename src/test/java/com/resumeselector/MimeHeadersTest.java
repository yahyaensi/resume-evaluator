package com.resumeselector;

import org.junit.jupiter.api.Test;

import com.hr.MimeHeaders;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MimeHeadersTest {

    @Test
    void decodesBase64EncodedWord() {
        assertEquals("Jérôme", MimeHeaders.decodeWords("=?UTF-8?B?SsOpcsO0bWU=?="));
    }

    @Test
    void decodesQuotedPrintableEncodedWordInLatin1() {
        assertEquals("Jérôme Dupont", MimeHeaders.decodeWords("=?ISO-8859-1?Q?J=E9r=F4me_Dupont?="));
    }

    @Test
    void whitespaceBetweenAdjacentEncodedWordsIsDropped() {
        assertEquals("Développeur Java",
                MimeHeaders.decodeWords("=?UTF-8?Q?D=C3=A9veloppeur?= =?UTF-8?Q?_Java?="));
    }

    @Test
    void keepsPlainTextAndMixedContent() {
        assertEquals("Candidature - Développeur", MimeHeaders.decodeWords("Candidature - Développeur"));
        assertEquals("Re: café merci", MimeHeaders.decodeWords("Re: =?UTF-8?Q?caf=C3=A9?= merci"));
    }

    @Test
    void leavesUndecodableWordsUntouchedAndNeverReturnsNull() {
        assertEquals("=?X-UNKNOWN-CHARSET?B?abcd?=", MimeHeaders.decodeWords("=?X-UNKNOWN-CHARSET?B?abcd?="));
        assertEquals("", MimeHeaders.decodeWords(null));
        assertEquals("", MimeHeaders.decodeWords(""));
    }

    @Test
    void parsesQuotedNameWithComma() {
        MimeHeaders.Mailbox m = MimeHeaders.parseMailbox("\"Dupont, Jérôme\" <jerome@example.com>");

        assertEquals("Dupont, Jérôme", m.name());
        assertEquals("jerome@example.com", m.email());
    }

    @Test
    void parsesEncodedName() {
        MimeHeaders.Mailbox m = MimeHeaders.parseMailbox("=?UTF-8?B?SsOpcsO0bWU=?= <jerome@example.com>");

        assertEquals("Jérôme", m.name());
        assertEquals("jerome@example.com", m.email());
    }

    @Test
    void parsesQuotedEncodedName() {
        MimeHeaders.Mailbox m = MimeHeaders.parseMailbox("\"=?UTF-8?B?SsOpcsO0bWU=?=\" <jerome@example.com>");

        assertEquals("Jérôme", m.name());
    }

    @Test
    void parsesBareAddressesAndEmptyValues() {
        assertEquals(new MimeHeaders.Mailbox("", "jerome@example.com"), MimeHeaders.parseMailbox("jerome@example.com"));
        assertEquals(new MimeHeaders.Mailbox("", "jerome@example.com"), MimeHeaders.parseMailbox("<jerome@example.com>"));
        assertEquals(new MimeHeaders.Mailbox("Jean", "j@x.fr"), MimeHeaders.parseMailbox("Jean <j@x.fr>"));
        assertEquals(new MimeHeaders.Mailbox("", ""), MimeHeaders.parseMailbox(null));
        assertEquals(new MimeHeaders.Mailbox("", ""), MimeHeaders.parseMailbox("   "));
    }
}
