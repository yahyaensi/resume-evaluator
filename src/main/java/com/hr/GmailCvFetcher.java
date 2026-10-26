package com.hr;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Lit Gmail avec l'API REST (scope {@code gmail.readonly}, lecture seule) et extrait les pièces jointes
 * PDF / Word / texte des emails. Aucun email n'est modifié : l'API n'autorise pas l'écriture avec ce scope,
 * et un message lu ici n'est pas marqué « lu ».
 * <p>
 * Appels utilisés : {@code labels.list}, {@code messages.list}, {@code messages.get} (format complet) et
 * {@code messages.attachments.get}.
 */
public class GmailCvFetcher implements AutoCloseable {

    static final String DEFAULT_API = "https://gmail.googleapis.com/gmail/v1/users/me";
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_PAGE_SIZE = 500;

    /**
     * Noms de dossiers IMAP (anglais et français) et identifiants de libellés système → identifiant de libellé
     * de l'API Gmail. Une valeur vide signifie « tous les messages » (aucun filtre de libellé).
     */
    private static final Map<String, String> SYSTEM_LABELS = Map.ofEntries(
            Map.entry("inbox", "INBOX"),
            Map.entry("[gmail]/all mail", ""), Map.entry("[google mail]/all mail", ""),
            Map.entry("[gmail]/tous les messages", ""), Map.entry("[google mail]/tous les messages", ""),
            Map.entry("all mail", ""), Map.entry("tous les messages", ""),
            Map.entry("[gmail]/sent mail", "SENT"), Map.entry("[google mail]/sent mail", "SENT"),
            Map.entry("[gmail]/messages envoyés", "SENT"), Map.entry("[gmail]/messages envoyes", "SENT"),
            Map.entry("sent", "SENT"),
            Map.entry("[gmail]/drafts", "DRAFT"), Map.entry("[gmail]/brouillons", "DRAFT"),
            Map.entry("draft", "DRAFT"), Map.entry("drafts", "DRAFT"),
            Map.entry("[gmail]/spam", "SPAM"), Map.entry("spam", "SPAM"),
            Map.entry("[gmail]/trash", "TRASH"), Map.entry("[gmail]/corbeille", "TRASH"),
            Map.entry("trash", "TRASH"),
            Map.entry("[gmail]/starred", "STARRED"), Map.entry("[gmail]/messages suivis", "STARRED"),
            Map.entry("starred", "STARRED"),
            Map.entry("[gmail]/important", "IMPORTANT"), Map.entry("important", "IMPORTANT"));

    private record LabelSelection(String id, boolean includeSpamTrash) {
    }

    /** Ce qu'on retient d'un email pour chacune de ses pièces jointes. */
    private record MessageInfo(String subject, MimeHeaders.Mailbox from, LocalDateTime receivedAt) {
    }

    private final String accessToken;
    private final String api;
    private final Consumer<String> log;
    private final Duration retryDelay;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private BiConsumer<Integer, Integer> progress = (done, total) -> { };
    private BooleanSupplier cancelled = () -> false;

    /** @param accessToken jeton d'accès OAuth2 Google (scope gmail.readonly), envoyé en « Bearer » */
    public GmailCvFetcher(String accessToken, Consumer<String> log) {
        this(accessToken, log, DEFAULT_API, Duration.ofSeconds(2));
    }

    /** Constructeur de test : adresse de base de l'API et délai entre deux essais. */
    public GmailCvFetcher(String accessToken, Consumer<String> log, String apiBase, Duration retryDelay) {
        this.accessToken = accessToken;
        this.log = log;
        this.api = apiBase.replaceAll("/+$", "");
        this.retryDelay = retryDelay;
    }

    /** Appelé après chaque email traité : (traités, total). */
    public void setProgressListener(BiConsumer<Integer, Integer> progress) {
        this.progress = progress;
    }

    /** Permet d'interrompre proprement l'analyse (ex. bouton « Annuler »). */
    public void setCancelCheck(BooleanSupplier cancelled) {
        this.cancelled = cancelled;
    }

    /**
     * @param folderName    libellé Gmail (INBOX, CV, "[Gmail]/All Mail"...)
     * @param max           nombre maximum d'emails analysés (les plus récents)
     * @param since         ne garde que les emails reçus à partir de cette date (optionnel)
     * @param subjectFilter ne garde que les emails dont l'objet contient ce texte, au sens de la recherche
     *                      Gmail : mots entiers (optionnel)
     * @param saveDir       si renseigné, copie les pièces jointes dans ce dossier (optionnel)
     */
    public List<CvDocument> fetch(String folderName, int max, LocalDate since,
                                  String subjectFilter, Path saveDir) throws Exception {
        LabelSelection label = resolveLabel(folderName);
        String query = buildQuery(since, subjectFilter, ZoneId.systemDefault());
        List<String> ids = listMessageIds(label, query, max);

        if (saveDir != null) {
            Files.createDirectories(saveDir);
        }

        log.accept(String.format("%d email(s) avec pièce jointe à analyser dans « %s »...", ids.size(), folderName));
        List<CvDocument> result = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            if (cancelled.getAsBoolean()) {
                log.accept("Analyse annulée.");
                break;
            }
            String id = ids.get(i);
            try {
                collectFromMessage(id, saveDir, result);
            } catch (GmailApiException e) {
                if (e.isFatal()) {
                    throw e;
                }
                log.accept("Email ignoré (" + id + ") : " + e.getMessage());
            } catch (IOException | RuntimeException e) {
                log.accept("Email ignoré (" + id + ") : " + e.getMessage());
            }
            progress.accept(i + 1, ids.size());
        }
        return result;
    }

    /**
     * Requête de recherche Gmail : seulement les emails avec pièce jointe ; la date de début est exprimée en
     * secondes Unix (début de journée dans le fuseau donné), ce qui évite toute ambiguïté de fuseau.
     */
    public static String buildQuery(LocalDate since, String subjectFilter, ZoneId zone) {
        StringBuilder q = new StringBuilder("has:attachment");
        if (since != null) {
            q.append(" after:").append(since.atStartOfDay(zone).toEpochSecond());
        }
        if (subjectFilter != null) {
            String cleaned = subjectFilter.replace('"', ' ').strip();
            if (!cleaned.isEmpty()) {
                q.append(" subject:\"").append(cleaned).append('"');
            }
        }
        return q.toString();
    }

    // ------------------------------------------------------------------
    //  Libellés et liste des messages
    // ------------------------------------------------------------------

    private LabelSelection resolveLabel(String folderName) throws IOException, InterruptedException {
        String name = folderName == null || folderName.isBlank() ? "INBOX" : folderName.strip();
        String system = SYSTEM_LABELS.get(name.toLowerCase(Locale.ROOT));
        if (system != null) {
            if (system.isEmpty()) {
                return new LabelSelection(null, false);
            }
            return new LabelSelection(system, system.equals("SPAM") || system.equals("TRASH"));
        }

        JsonObject response = getJson(api + "/labels");
        List<String> available = new ArrayList<>();
        for (JsonElement element : array(response, "labels")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject label = element.getAsJsonObject();
            String labelName = string(label, "name");
            String labelId = string(label, "id");
            if (labelId == null) {
                continue;
            }
            if (name.equalsIgnoreCase(labelName) || name.equals(labelId)) {
                return new LabelSelection(labelId, false);
            }
            if (labelName != null && "user".equals(string(label, "type"))) {
                available.add(labelName);
            }
        }
        throw new GmailApiException("Libellé Gmail introuvable : « " + name + " »."
                + (available.isEmpty() ? "" : " Libellés disponibles : " + String.join(", ", available) + "."),
                404, true);
    }

    private List<String> listMessageIds(LabelSelection label, String query, int max)
            throws IOException, InterruptedException {
        List<String> ids = new ArrayList<>();
        String pageToken = null;
        while (ids.size() < max && !cancelled.getAsBoolean()) {
            StringBuilder url = new StringBuilder(api).append("/messages?maxResults=")
                    .append(Math.min(MAX_PAGE_SIZE, max - ids.size()));
            if (label.id() != null) {
                url.append("&labelIds=").append(enc(label.id()));
            }
            if (label.includeSpamTrash()) {
                url.append("&includeSpamTrash=true");
            }
            url.append("&q=").append(enc(query));
            if (pageToken != null) {
                url.append("&pageToken=").append(enc(pageToken));
            }
            JsonObject page = getJson(url.toString());
            for (JsonElement element : array(page, "messages")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                String id = string(element.getAsJsonObject(), "id");
                if (id != null && ids.size() < max) {
                    ids.add(id);
                }
            }
            pageToken = string(page, "nextPageToken");
            if (pageToken == null || pageToken.isEmpty()) {
                break;
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------
    //  Lecture d'un email
    // ------------------------------------------------------------------

    private void collectFromMessage(String id, Path saveDir, List<CvDocument> out)
            throws IOException, InterruptedException {
        JsonObject message = getJson(api + "/messages/" + enc(id) + "?format=full");
        JsonElement payloadElement = message.get("payload");
        if (payloadElement == null || !payloadElement.isJsonObject()) {
            return;
        }
        JsonObject payload = payloadElement.getAsJsonObject();
        MessageInfo info = new MessageInfo(
                MimeHeaders.decodeWords(header(payload, "Subject")),
                MimeHeaders.parseMailbox(header(payload, "From")),
                receivedAt(message, header(payload, "Date")));
        collectParts(id, payload, info, saveDir, out);
    }

    private void collectParts(String messageId, JsonObject part, MessageInfo info, Path saveDir,
                              List<CvDocument> out) throws IOException, InterruptedException {
        String rawName = string(part, "filename");
        if (rawName != null && !rawName.isBlank()) {
            String fileName = MimeHeaders.decodeWords(rawName);
            if (TextExtractor.supports(fileName)) {
                byte[] data = attachmentBytes(messageId, part);
                if (data != null) {
                    addDocument(fileName, data, info, saveDir, out);
                }
            }
        }
        for (JsonElement child : array(part, "parts")) {
            if (child.isJsonObject()) {
                collectParts(messageId, child.getAsJsonObject(), info, saveDir, out);
            }
        }
    }

    private void addDocument(String fileName, byte[] data, MessageInfo info, Path saveDir, List<CvDocument> out)
            throws IOException {
        String text;
        try {
            text = TextExtractor.extract(fileName, data);
        } catch (Exception e) {
            log.accept("Lecture impossible de « " + fileName + " » : " + e.getMessage());
            text = "";
        }
        if (saveDir != null) {
            String prefix = info.receivedAt() == null ? "" : FILE_DATE.format(info.receivedAt()) + "_";
            Files.write(saveDir.resolve(prefix + sanitize(fileName)), data);
        }
        out.add(new CvDocument(fileName, info.from().name(), info.from().email(), info.subject(),
                info.receivedAt(), text));
    }

    /** Contenu d'une pièce jointe : téléchargé via attachments.get, ou directement dans le message si elle est petite. */
    private byte[] attachmentBytes(String messageId, JsonObject part) throws IOException, InterruptedException {
        JsonElement bodyElement = part.get("body");
        if (bodyElement == null || !bodyElement.isJsonObject()) {
            return null;
        }
        JsonObject body = bodyElement.getAsJsonObject();
        String attachmentId = string(body, "attachmentId");
        String data;
        if (attachmentId != null) {
            JsonObject attachment = getJson(api + "/messages/" + enc(messageId) + "/attachments/" + enc(attachmentId));
            data = string(attachment, "data");
        } else {
            data = string(body, "data");
        }
        return data == null ? null : decodeBase64(data);
    }

    /** Gmail encode en base64 « URL-safe » ; on tolère aussi l'alphabet standard. */
    static byte[] decodeBase64(String data) {
        boolean standard = data.indexOf('+') >= 0 || data.indexOf('/') >= 0;
        return (standard ? Base64.getDecoder() : Base64.getUrlDecoder()).decode(data.strip());
    }

    private static String header(JsonObject payload, String name) {
        for (JsonElement element : array(payload, "headers")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject h = element.getAsJsonObject();
            if (name.equalsIgnoreCase(string(h, "name"))) {
                String value = string(h, "value");
                return value == null ? "" : value;
            }
        }
        return "";
    }

    /** Date de réception de Gmail (internalDate, en ms), sinon l'en-tête Date. */
    private static LocalDateTime receivedAt(JsonObject message, String dateHeader) {
        String internal = string(message, "internalDate");
        if (internal != null) {
            try {
                return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(internal.strip())),
                        ZoneId.systemDefault());
            } catch (NumberFormatException ignored) {
                // on essaie l'en-tête
            }
        }
        if (dateHeader != null && !dateHeader.isBlank()) {
            try {
                return ZonedDateTime.parse(dateHeader.strip(), DateTimeFormatter.RFC_1123_DATE_TIME)
                        .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
            } catch (DateTimeParseException ignored) {
                // date illisible : le CV est conservé sans date
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    //  HTTP
    // ------------------------------------------------------------------

    /** GET + JSON, avec nouvel essai en cas de limite de débit, d'erreur serveur ou d'erreur réseau. */
    private JsonObject getJson(String url) throws IOException, InterruptedException {
        GmailApiException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpResponse<String> response;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(60))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Accept", "application/json")
                        .GET().build();
                response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (ConnectException | HttpConnectTimeoutException e) {
                throw new GmailApiException("Impossible de joindre l'API Gmail : vérifiez votre connexion.", 0, true);
            } catch (IOException e) {
                last = new GmailApiException("Erreur réseau : " + e.getMessage(), 0, false);
                if (attempt < MAX_ATTEMPTS) {
                    Thread.sleep(retryDelay.toMillis() * attempt);
                }
                continue;
            }

            int status = response.statusCode();
            if (status / 100 == 2) {
                return parseObject(response.body());
            }
            String reason = errorReason(response.body());
            GmailApiException error = toException(status, reason, errorMessage(response.body()));
            boolean retryable = status == 429 || status >= 500
                    || (status == 403 && reason != null && reason.toLowerCase(Locale.ROOT).contains("ratelimit"));
            if (!retryable) {
                throw error;
            }
            last = error;
            if (attempt < MAX_ATTEMPTS) {
                Thread.sleep(retryDelay.toMillis() * attempt);
            }
        }
        throw last;
    }

    private static GmailApiException toException(int status, String reason, String detail) {
        String d = detail == null ? "" : " Détail Google : " + detail;
        if (status == 401) {
            return new GmailApiException("Accès à Gmail refusé : l'autorisation a expiré ou été révoquée."
                    + " Déconnectez-vous puis reconnectez-vous." + d, 401, true);
        }
        if (status == 403) {
            String r = reason == null ? "" : reason;
            if (r.equals("accessNotConfigured") || (detail != null && detail.contains("has not been used in project"))
                    || (detail != null && detail.contains("is disabled"))) {
                return new GmailApiException("L'API Gmail n'est pas activée pour le projet Google Cloud de ce client "
                        + "OAuth : activez « Gmail API » (API et services → Bibliothèque), patientez une minute "
                        + "puis réessayez." + d, 403, true);
            }
            if (r.equals("insufficientPermissions") || (detail != null && detail.contains("insufficient"))) {
                return new GmailApiException("Autorisation insuffisante : l'accès en lecture seule à Gmail n'a pas "
                        + "été accordé. Déconnectez-vous puis reconnectez-vous en cochant l'autorisation." + d,
                        403, true);
            }
            if (r.toLowerCase(Locale.ROOT).contains("ratelimit")) {
                return new GmailApiException("Limite de débit de l'API Gmail atteinte (HTTP 403)." + d, 403, false);
            }
            return new GmailApiException("Accès refusé par Gmail (HTTP 403)." + d, 403, true);
        }
        if (status == 404) {
            return new GmailApiException("Élément introuvable dans Gmail (HTTP 404)." + d, 404, false);
        }
        if (status == 429) {
            return new GmailApiException("Limite de débit de l'API Gmail atteinte (HTTP 429)." + d, 429, false);
        }
        return new GmailApiException("Erreur de l'API Gmail (HTTP " + status + ")." + d, status, false);
    }

    private static String errorMessage(String body) {
        JsonObject error = errorObject(body);
        return error == null ? null : string(error, "message");
    }

    /** « reason » du premier détail d'erreur (ex. accessNotConfigured, rateLimitExceeded), sinon « status ». */
    private static String errorReason(String body) {
        JsonObject error = errorObject(body);
        if (error == null) {
            return null;
        }
        JsonArray details = array(error, "errors");
        if (details.size() > 0 && details.get(0).isJsonObject()) {
            String reason = string(details.get(0).getAsJsonObject(), "reason");
            if (reason != null) {
                return reason;
            }
        }
        return string(error, "status");
    }

    private static JsonObject errorObject(String body) {
        try {
            JsonElement error = JsonParser.parseString(body).getAsJsonObject().get("error");
            return error != null && error.isJsonObject() ? error.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonObject parseObject(String body) throws GmailApiException {
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new GmailApiException("Réponse inattendue de l'API Gmail.", 0, false);
        }
    }

    // ------------------------------------------------------------------
    //  Utilitaires JSON / texte
    // ------------------------------------------------------------------

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static JsonArray array(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^\\w.\\-]+", "_");
    }

    @Override
    public void close() {
        // rien à fermer : chaque appel de l'API est une requête HTTP indépendante
    }
}
