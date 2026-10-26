package com.resumeselector;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hr.CvDocument;
import com.hr.GmailApiException;
import com.hr.GmailCvFetcher;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faux serveur Gmail local : vérifie les appels REST émis, l'analyse des messages et la gestion des erreurs. */
class GmailCvFetcherTest {

    private static final String TOKEN = "tok-123";
    private static final long INTERNAL_DATE = 1792929600000L;

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Serveur de test : enregistre chaque requête (méthode, chemin, requête décodée, en-tête Authorization). */
    private static final class FakeGmail implements AutoCloseable {
        final HttpServer server;
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        final List<String> methods = Collections.synchronizedList(new ArrayList<>());
        final List<String> auth = Collections.synchronizedList(new ArrayList<>());
        final Map<String, String> messages = new ConcurrentHashMap<>();
        final Map<String, String> attachments = new ConcurrentHashMap<>();
        volatile String labelsJson = "{\"labels\":[{\"id\":\"INBOX\",\"name\":\"INBOX\",\"type\":\"system\"},"
                + "{\"id\":\"Label_7\",\"name\":\"CV\",\"type\":\"user\"},"
                + "{\"id\":\"Label_9\",\"name\":\"Clients/Paris\",\"type\":\"user\"}]}";
        volatile Function<String, String[]> listHandler;

        FakeGmail() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/gmail/v1/users/me", this::handle);
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/gmail/v1/users/me";
        }

        private void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath().substring("/gmail/v1/users/me".length());
            String query = ex.getRequestURI().getRawQuery() == null ? ""
                    : URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            methods.add(ex.getRequestMethod());
            auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            requests.add(path + (query.isEmpty() ? "" : "?" + query));

            String[] reply; // {statut, corps}
            if (path.equals("/labels")) {
                reply = new String[]{"200", labelsJson};
            } else if (path.equals("/messages")) {
                reply = listHandler.apply(query);
            } else if (path.contains("/attachments/")) {
                String attachmentId = path.substring(path.lastIndexOf('/') + 1);
                String data = attachments.get(attachmentId);
                reply = data == null ? new String[]{"404", "{\"error\":{\"code\":404,\"message\":\"Not Found\"}}"}
                        : new String[]{"200", "{\"size\":1,\"data\":\"" + data + "\"}"};
            } else if (path.startsWith("/messages/")) {
                String body = messages.get(path.substring("/messages/".length()));
                reply = body == null ? new String[]{"404", "{\"error\":{\"code\":404,\"message\":\"Not Found\"}}"}
                        : new String[]{"200", body};
            } else {
                reply = new String[]{"404", "{}"};
            }
            byte[] bytes = reply[1].getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(Integer.parseInt(reply[0]), bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static String ids(String... ids) {
        StringBuilder sb = new StringBuilder("{\"messages\":[");
        for (int i = 0; i < ids.length; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"id\":\"").append(ids[i]).append("\",\"threadId\":\"t\"}");
        }
        return sb.append("]}").toString();
    }

    private static GmailCvFetcher fetcher(FakeGmail gmail, List<String> log) {
        return new GmailCvFetcher(TOKEN, log::add, gmail.base(), Duration.ZERO);
    }

    /** m1 : pièce jointe téléchargée (attachmentId) ; m2 : pièce jointe en ligne dans un multipart imbriqué + image ignorée. */
    private static void addTwoMessages(FakeGmail gmail) {
        gmail.messages.put("m1", "{\"id\":\"m1\",\"internalDate\":\"" + INTERNAL_DATE + "\",\"payload\":{"
                + "\"mimeType\":\"multipart/mixed\",\"filename\":\"\",\"headers\":["
                + "{\"name\":\"From\",\"value\":\"=?UTF-8?B?SsOpcsO0bWU=?= <jerome@example.com>\"},"
                + "{\"name\":\"Subject\",\"value\":\"=?UTF-8?B?RMOpdmVsb3BwZXVyIEphdmE=?=\"}],"
                + "\"parts\":[{\"mimeType\":\"text/plain\",\"filename\":\"\",\"body\":{\"size\":2,\"data\":\"" + b64("Bonjour") + "\"}},"
                + "{\"mimeType\":\"text/plain\",\"filename\":\"cv-jerome.txt\",\"body\":{\"attachmentId\":\"a1\",\"size\":17}}]}}");
        gmail.attachments.put("a1", b64("Java Spring Kafka"));

        gmail.messages.put("m2", "{\"id\":\"m2\",\"internalDate\":\"" + INTERNAL_DATE + "\",\"payload\":{"
                + "\"mimeType\":\"multipart/mixed\",\"headers\":["
                + "{\"name\":\"from\",\"value\":\"marie@example.com\"},{\"name\":\"SUBJECT\",\"value\":\"Candidature\"}],"
                + "\"parts\":[{\"mimeType\":\"multipart/alternative\",\"filename\":\"\",\"parts\":["
                + "{\"mimeType\":\"text/plain\",\"filename\":\"\",\"body\":{\"data\":\"" + b64("x") + "\"}}]},"
                + "{\"mimeType\":\"text/plain\",\"filename\":\"cv-marie.txt\",\"body\":{\"size\":6,\"data\":\"" + b64("Docker") + "\"}},"
                + "{\"mimeType\":\"image/png\",\"filename\":\"signature.png\",\"body\":{\"attachmentId\":\"img\",\"size\":99}}]}}");
    }

    @Test
    void readsAttachmentsAcrossPagesWithReadOnlyGetRequests() throws Exception {
        try (FakeGmail gmail = new FakeGmail()) {
            addTwoMessages(gmail);
            gmail.messages.put("m3", "{\"id\":\"m3\",\"internalDate\":\"" + INTERNAL_DATE
                    + "\",\"payload\":{\"mimeType\":\"text/plain\",\"filename\":\"\",\"headers\":[]}}");
            gmail.listHandler = query -> query.contains("pageToken=p2")
                    ? new String[]{"200", ids("m3")}
                    : new String[]{"200", ids("m1", "m2").replace("]}", "],\"nextPageToken\":\"p2\"}")};
            List<String> log = new ArrayList<>();
            List<int[]> progress = new ArrayList<>();
            GmailCvFetcher f = fetcher(gmail, log);
            f.setProgressListener((done, total) -> progress.add(new int[]{done, total}));

            List<CvDocument> docs = f.fetch("INBOX", 50, null, null, null);

            assertEquals(2, docs.size(), log.toString());
            CvDocument first = docs.get(0);
            assertEquals("cv-jerome.txt", first.fileName());
            assertEquals("Java Spring Kafka", first.text());
            assertEquals("Jérôme", first.senderName());
            assertEquals("jerome@example.com", first.senderEmail());
            assertEquals("Développeur Java", first.subject());
            assertEquals(LocalDateTime.ofInstant(Instant.ofEpochMilli(INTERNAL_DATE), ZoneId.systemDefault()),
                    first.receivedAt());
            CvDocument second = docs.get(1);
            assertEquals("Docker", second.text());
            assertEquals("marie@example.com", second.senderEmail());
            assertEquals("Candidature", second.subject());

            // 3 emails parcourus, progression (1,3) (2,3) (3,3)
            assertEquals(3, progress.size());
            assertEquals(3, progress.get(2)[1]);

            // lecture seule : uniquement des GET, avec le jeton en « Bearer », et l'image n'est jamais téléchargée
            assertTrue(gmail.methods.stream().allMatch("GET"::equals), gmail.methods.toString());
            assertTrue(gmail.auth.stream().allMatch(("Bearer " + TOKEN)::equals), gmail.auth.toString());
            assertFalse(gmail.requests.stream().anyMatch(r -> r.contains("/attachments/img")), gmail.requests.toString());
            String firstList = gmail.requests.get(0);
            assertTrue(firstList.startsWith("/messages?"), firstList);
            assertTrue(firstList.contains("labelIds=INBOX"), firstList);
            assertTrue(firstList.contains("q=has:attachment"), firstList);
            assertTrue(firstList.contains("maxResults=50"), firstList);
        }
    }

    @Test
    void stopsAtMaxMessages() throws Exception {
        try (FakeGmail gmail = new FakeGmail()) {
            addTwoMessages(gmail);
            gmail.listHandler = query -> new String[]{"200", ids("m1", "m2", "m3", "m4")};

            List<CvDocument> docs = fetcher(gmail, new ArrayList<>()).fetch("INBOX", 2, null, null, null);

            assertEquals(2, docs.size());
            assertTrue(gmail.requests.stream().anyMatch(r -> r.contains("maxResults=2")), gmail.requests.toString());
            assertFalse(gmail.requests.stream().anyMatch(r -> r.startsWith("/messages/m3")), gmail.requests.toString());
        }
    }

    @Test
    void userLabelIsResolvedByNameIgnoringCase() throws Exception {
        try (FakeGmail gmail = new FakeGmail()) {
            gmail.listHandler = query -> new String[]{"200", "{\"resultSizeEstimate\":0}"};

            List<CvDocument> docs = fetcher(gmail, new ArrayList<>()).fetch("cv", 10, null, null, null);

            assertTrue(docs.isEmpty());
            assertEquals("/labels", gmail.requests.get(0));
            assertTrue(gmail.requests.get(1).contains("labelIds=Label_7"), gmail.requests.get(1));
        }
    }

    @Test
    void nestedLabelNamesWork() throws Exception {
        try (FakeGmail gmail = new FakeGmail()) {
            gmail.listHandler = query -> new String[]{"200", "{}"};

            fetcher(gmail, new ArrayList<>()).fetch("Clients/Paris", 10, null, null, null);

            assertTrue(gmail.requests.get(1).contains("labelIds=Label_9"), gmail.requests.get(1));
        }
    }

    @Test
    void unknownLabelListsTheAvailableOnes() throws Exception {
        try (FakeGmail gmail = new FakeGmail()) {
            gmail.listHandler = query -> new String[]{"200", "{}"};

            GmailApiException e = assertThrows(GmailApiException.class,
                    () -> fetcher(gmail, new ArrayList<>()).fetch("Inexistant", 10, null, null, null));

            assertTrue(e.getMessage().contains("Inexistant"), e.getMessage());
            assertTrue(e.getMessage().contains("CV"), e.getMessage());
            assertTrue(e.isFatal());
        }
    }

    @Test
    void imapStyleAllMailNamesMeanNoLabelFilter() throws Exception {
        for (String folder : new String[]{"[Gmail]/All Mail", "[Gmail]/Tous les messages"}) {
            try (FakeGmail gmail = new FakeGmail()) {
                gmail.listHandler = query -> new String[]{"200", "{}"};

                fetcher(gmail, new ArrayList<>()).fetch(folder, 10, null, null, null);

                assertEquals(1, gmail.requests.size(), "aucun appel labels.list pour un libellé système");
                assertFalse(gmail.requests.get(0).contains("labelIds"), gmail.requests.get(0));
            }
        }
    }

    @Test
    void spamAndTrashIncludeSpamTrash() throws Exception {
        try (FakeGmail gmail = new FakeGmail()) {
            gmail.listHandler = query -> new String[]{"200", "{}"};

            fetcher(gmail, new ArrayList<>()).fetch("[Gmail]/Spam", 10, null, null, null);

            assertTrue(gmail.requests.get(0).contains("labelIds=SPAM"), gmail.requests.get(0));
            assertTrue(gmail.requests.get(0).contains("includeSpamTrash=true"), gmail.requests.get(0));
        }
    }

    @Test
    void sinceAndSubjectAreSentInTheGmailQuery() throws Exception {
        try (FakeGmail gmail = new FakeGmail()) {
            gmail.listHandler = query -> new String[]{"200", "{}"};
            LocalDate since = LocalDate.of(2026, 9, 1);

            fetcher(gmail, new ArrayList<>()).fetch("INBOX", 10, since, "Candidature", null);

            long expected = since.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
            String list = gmail.requests.get(0);
            assertTrue(list.contains("after:" + expected), list);
            assertTrue(list.contains("subject:\"Candidature\""), list);
        }
    }

    @Test
    void buildQueryUsesUnixSecondsAndStripsQuotes() {
        assertEquals("has:attachment", GmailCvFetcher.buildQuery(null, null, ZoneOffset.UTC));
        assertEquals("has:attachment", GmailCvFetcher.buildQuery(null, "   ", ZoneOffset.UTC));
        assertEquals("has:attachment after:1788220800",
                GmailCvFetcher.buildQuery(LocalDate.of(2026, 9, 1), null, ZoneOffset.UTC));
        assertEquals("has:attachment subject:\"Candidature\"",
                GmailCvFetcher.buildQuery(null, " \"Candidature\" ", ZoneOffset.UTC));
    }

    @Test
    void savesAttachmentsWhenAFolderIsGiven(@TempDir Path dir) throws Exception {
        try (FakeGmail gmail = new FakeGmail()) {
            addTwoMessages(gmail);
            gmail.listHandler = query -> new String[]{"200", ids("m1")};
            Path saveDir = dir.resolve("cvs");

            fetcher(gmail, new ArrayList<>()).fetch("INBOX", 10, null, null, saveDir);

            try (var files = Files.list(saveDir)) {
                List<String> names = files.map(p -> p.getFileName().toString()).toList();
                assertEquals(1, names.size());
                assertTrue(names.get(0).endsWith("_cv-jerome.txt"), names.toString());
                assertEquals("Java Spring Kafka", Files.readString(saveDir.resolve(names.get(0))));
            }
        }
    }

    @Test
    void unauthorizedIsAFatalAuthenticationFailure() throws Exception {
        try (FakeGmail gmail = new FakeGmail()) {
            gmail.listHandler = query -> new String[]{"401",
                    "{\"error\":{\"code\":401,\"message\":\"Invalid Credentials\",\"status\":\"UNAUTHENTICATED\"}}"};

            GmailApiException e = assertThrows(GmailApiException.class,
                    () -> fetcher(gmail, new ArrayList<>()).fetch("INBOX", 10, null, null, null));

            assertTrue(e.isAuthenticationFailure());
            assertTrue(e.isFatal());
            assertEquals(1, gmail.requests.size(), "pas de nouvel essai sur un 401");
        }
    }

    @Test
    void disabledGmailApiGivesAnActionableMessage() throws Exception {
        try (FakeGmail gmail = new FakeGmail()) {
            gmail.listHandler = query -> new String[]{"403",
                    "{\"error\":{\"code\":403,\"message\":\"Gmail API has not been used in project 123 before or it is disabled.\","
                            + "\"errors\":[{\"reason\":\"accessNotConfigured\"}],\"status\":\"PERMISSION_DENIED\"}}"};

            GmailApiException e = assertThrows(GmailApiException.class,
                    () -> fetcher(gmail, new ArrayList<>()).fetch("INBOX", 10, null, null, null));

            assertTrue(e.getMessage().contains("Gmail API"), e.getMessage());
            assertTrue(e.getMessage().contains("activez"), e.getMessage());
            assertTrue(e.isFatal());
            assertFalse(e.isAuthenticationFailure());
        }
    }

    @Test
    void retriesOnRateLimitThenSucceeds() throws Exception {
        try (FakeGmail gmail = new FakeGmail()) {
            addTwoMessages(gmail);
            AtomicInteger calls = new AtomicInteger();
            gmail.listHandler = query -> calls.incrementAndGet() == 1
                    ? new String[]{"429", "{\"error\":{\"code\":429,\"message\":\"Too many requests\"}}"}
                    : new String[]{"200", ids("m1")};

            List<CvDocument> docs = fetcher(gmail, new ArrayList<>()).fetch("INBOX", 10, null, null, null);

            assertEquals(2, calls.get());
            assertEquals(1, docs.size());
        }
    }

    @Test
    void persistentRateLimitFailsAfterThreeAttemptsWithoutBeingFatal() throws Exception {
        try (FakeGmail gmail = new FakeGmail()) {
            AtomicInteger calls = new AtomicInteger();
            gmail.listHandler = query -> {
                calls.incrementAndGet();
                return new String[]{"429", "{\"error\":{\"code\":429,\"message\":\"Too many requests\"}}"};
            };

            GmailApiException e = assertThrows(GmailApiException.class,
                    () -> fetcher(gmail, new ArrayList<>()).fetch("INBOX", 10, null, null, null));

            assertEquals(3, calls.get());
            assertEquals(429, e.status());
        }
    }

    @Test
    void aMissingMessageIsSkippedAndTheOthersAreKept() throws Exception {
        try (FakeGmail gmail = new FakeGmail()) {
            addTwoMessages(gmail);
            gmail.listHandler = query -> new String[]{"200", ids("deleted", "m1")};
            List<String> log = new ArrayList<>();

            List<CvDocument> docs = fetcher(gmail, log).fetch("INBOX", 10, null, null, null);

            assertEquals(1, docs.size());
            assertTrue(log.stream().anyMatch(l -> l.contains("Email ignoré (deleted)")), log.toString());
        }
    }

    @Test
    void cancellationStopsBeforeTheNextMessage() throws Exception {
        try (FakeGmail gmail = new FakeGmail()) {
            addTwoMessages(gmail);
            gmail.listHandler = query -> new String[]{"200", ids("m1", "m2")};
            GmailCvFetcher f = fetcher(gmail, new ArrayList<>());
            AtomicInteger processed = new AtomicInteger();
            f.setProgressListener((done, total) -> processed.set(done));
            f.setCancelCheck(() -> processed.get() >= 1);

            List<CvDocument> docs = f.fetch("INBOX", 10, null, null, null);

            assertEquals(1, docs.size());
        }
    }

    @Test
    void decodesUrlSafeAndStandardBase64() throws Exception {
        java.lang.reflect.Method m = GmailCvFetcher.class.getDeclaredMethod("decodeBase64", String.class);
        m.setAccessible(true);

        assertEquals(java.util.Arrays.toString(new byte[]{(byte) 0xfb, (byte) 0xff}),
                java.util.Arrays.toString((byte[]) m.invoke(null, "-_8=")));
        assertEquals(java.util.Arrays.toString(new byte[]{(byte) 0xfb, (byte) 0xff}),
                java.util.Arrays.toString((byte[]) m.invoke(null, "+/8=")));
    }
}
