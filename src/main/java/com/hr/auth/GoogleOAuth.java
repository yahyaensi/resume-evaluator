package com.hr.auth;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

/**
 * Autorisation OAuth2 Google pour application de bureau (flux « loopback » + PKCE).
 * <ol>
 * <li>Premier lancement : le navigateur s'ouvre, l'utilisateur autorise l'accès à Gmail ;
 * l'application reçoit le code sur http://127.0.0.1:port et l'échange contre des jetons.</li>
 * <li>Lancements suivants : le refresh token stocké dans {@code ~/.resume-selector/tokens.json}
 * sert à obtenir un access token, sans aucune interaction ni mot de passe.</li>
 * </ol>
 * L'access token est ensuite envoyé en « Bearer » à l'API REST Gmail. Seul le scope {@code gmail.readonly}
 * (lecture seule) est demandé : l'application ne peut ni modifier, ni supprimer, ni envoyer d'email.
 */
public final class GoogleOAuth {

    /** Lecture seule de la boîte Gmail : l'unique autorisation demandée (l'adresse du compte vient de users.getProfile). */
    public static final String GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly";
    public static final String SCOPES = GMAIL_READONLY;

    private static final String AUTH_URI = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String REVOKE_URI = "https://oauth2.googleapis.com/revoke";
    private static final String PROFILE_URI = "https://gmail.googleapis.com/gmail/v1/users/me/profile";
    private static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(3);
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String PAGE_OK = """
            <!doctype html><html lang="fr"><head><meta charset="utf-8"><title>Évaluateur de CV</title></head>
            <body style="font-family:sans-serif;text-align:center;margin-top:18vh;color:#1f2937">
            <h2>Autorisation réussie ✔</h2>
            <p>Vous pouvez fermer cet onglet et retourner dans Évaluateur de CV.</p></body></html>
            """;
    private static final String PAGE_ERR = """
            <!doctype html><html lang="fr"><head><meta charset="utf-8"><title>Évaluateur de CV</title></head>
            <body style="font-family:sans-serif;text-align:center;margin-top:18vh;color:#1f2937">
            <h2>Autorisation refusée ou échouée</h2>
            <p>Retournez dans Évaluateur de CV pour voir le détail.</p></body></html>
            """;

    /** Adresse du compte + jeton d'accès valable environ une heure. */
    public record Credentials(String email, String accessToken) {
        @Override
        public String toString() {
            return "Credentials[email=" + email + "]";
        }
    }

    /** Erreur renvoyée par Google (ex. invalid_grant, access_denied). */
    public static class OAuthException extends IOException {
        private final String error;

        public OAuthException(String error, String description) {
            super(description == null || description.isBlank() ? error : error + " : " + description);
            this.error = error;
        }

        public String error() {
            return error;
        }
    }

    /** Emplacement du client OAuth dans le classpath (donc dans le jar) : src/main/resources/client_secret.json. */
    private static final String BUNDLED_CLIENT_SECRET = "/client_secret.json";

    private record ClientSecret(String clientId, String clientSecret) {}

    private final Path configDir;
    private final String clientSecretResource;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final Gson gson = new Gson();

    /** Client OAuth lu dans le jar ; {@code configDir} ne contient que le jeton (tokens.json). */
    public GoogleOAuth(Path configDir) {
        this(configDir, BUNDLED_CLIENT_SECRET);
    }

    /**
     * @param configDir            dossier où est stocké tokens.json
     * @param clientSecretResource chemin de la ressource classpath contenant le client_secret.json
     *                             (surchargeable pour les tests)
     */
    public GoogleOAuth(Path configDir, String clientSecretResource) {
        this.configDir = configDir;
        this.clientSecretResource = clientSecretResource;
    }

    public static Path defaultConfigDir() {
        return Path.of(System.getProperty("user.home"), ".resume-selector");
    }

    // ------------------------------------------------------------------
    // Client OAuth (client_secret.json téléchargé depuis Google Cloud)
    // ------------------------------------------------------------------

    /** Vrai si un client_secret.json valide (type « Application de bureau ») est embarqué dans le jar. */
    public boolean hasClientSecret() {
        try {
            loadClientSecret();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Le client OAuth est lu exclusivement dans le jar (classpath), jamais depuis le disque ou l'environnement. */
    private ClientSecret loadClientSecret() {
        try (java.io.InputStream in = GoogleOAuth.class.getResourceAsStream(clientSecretResource)) {
            if (in == null) {
                throw new IllegalStateException("client_secret.json introuvable dans le jar. Placez-le dans "
                        + "src/main/resources/ puis reconstruisez le jar (mvn clean package). Voir le README.");
            }
            return parseClientSecret(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Lecture du client_secret.json embarqué impossible : " + e.getMessage());
        }
    }

    private static ClientSecret parseClientSecret(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("web")) {
                throw new IllegalArgumentException("Ce fichier correspond à un client « Application Web ». "
                        + "Créez un ID client OAuth de type « Application de bureau ».");
            }
            JsonObject o = root.has("installed") ? root.getAsJsonObject("installed") : root;
            return new ClientSecret(o.get("client_id").getAsString(), o.get("client_secret").getAsString());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Fichier client_secret.json invalide (client_id / client_secret absents).");
        }
    }

    // ------------------------------------------------------------------
    // Autorisation
    // ------------------------------------------------------------------

    public Optional<String> storedEmail() {
        JsonObject stored = readToken();
        if (stored == null || !stored.has("email") || !hasRequiredScope(str(stored, "scope", ""))) {
            return Optional.empty();
        }
        return Optional.of(stored.get("email").getAsString());
    }

    /** Vrai si la liste de scopes accordés (séparés par des espaces) contient l'accès en lecture seule à Gmail. */
    public static boolean hasRequiredScope(String grantedScopes) {
        if (grantedScopes == null) {
            return false;
        }
        for (String scope : grantedScopes.trim().split("\\s+")) {
            if (GMAIL_READONLY.equals(scope)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Retourne un access token valide : rafraîchit le jeton stocké si possible,
     * sinon ouvre le navigateur pour demander l'autorisation à l'utilisateur.
     */
    public Credentials authorize(Consumer<String> log, BooleanSupplier cancelled) throws Exception {
        ClientSecret client = loadClientSecret();

        JsonObject stored = readToken();
        if (stored != null && stored.has("refresh_token") && stored.has("email")) {
            if (!hasRequiredScope(str(stored, "scope", ""))) {
                // Jeton d'une version antérieure (accès complet à Gmail via IMAP) : on le révoque chez Google
                // pour que seul l'accès en lecture seule subsiste, puis on redemande l'autorisation.
                log.accept("L'autorisation enregistrée donne un accès plus large que nécessaire : elle est révoquée "
                        + "et une autorisation en lecture seule (gmail.readonly) va être demandée.");
                logout();
            } else {
                try {
                    String access = refreshAccessToken(client, stored.get("refresh_token").getAsString());
                    return new Credentials(stored.get("email").getAsString(), access);
                } catch (OAuthException e) {
                    if (!"invalid_grant".equals(e.error())) {
                        throw e;
                    }
                    log.accept("Autorisation expirée ou révoquée : une nouvelle connexion est nécessaire.");
                    deleteToken();
                }
            }
        }
        return interactiveLogin(client, log, cancelled);
    }

    /** Révoque l'autorisation chez Google (au mieux) et supprime le jeton local. */
    public void logout() {
        JsonObject stored = readToken();
        if (stored != null && stored.has("refresh_token")) {
            try {
                postForm(REVOKE_URI, Map.of("token", stored.get("refresh_token").getAsString()));
            } catch (Exception ignored) {
                // la révocation est facultative : le jeton local est supprimé dans tous les cas
            }
        }
        deleteToken();
    }

    private Credentials interactiveLogin(ClientSecret client, Consumer<String> log, BooleanSupplier cancelled)
            throws Exception {
        String verifier = randomUrlSafe(48);
        String challenge = Base64.getUrlEncoder()
                                 .withoutPadding()
                                 .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String state = randomUrlSafe(24);

        CompletableFuture<Map<String, String>> result = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            boolean relevant = query.containsKey("code") || query.containsKey("error");
            if (!relevant) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] body = (query.containsKey("code") ? PAGE_OK : PAGE_ERR).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            result.complete(query);
        });
        server.start();

        try {
            String redirectUri = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            Map<String, String> authParams = new LinkedHashMap<>();
            authParams.put("client_id", client.clientId());
            authParams.put("redirect_uri", redirectUri);
            authParams.put("response_type", "code");
            authParams.put("scope", SCOPES);
            authParams.put("code_challenge", challenge);
            authParams.put("code_challenge_method", "S256");
            authParams.put("state", state);
            authParams.put("access_type", "offline");
            authParams.put("prompt", "consent");
            String url = AUTH_URI + "?" + encode(authParams);

            log.accept("Ouverture du navigateur pour autoriser l'accès à Gmail…");
            log.accept("Si rien ne s'ouvre, copiez ce lien dans votre navigateur : " + url);
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI.create(url));
                }
            } catch (Exception ignored) {
                // le lien est déjà affiché dans le journal
            }

            Map<String, String> params;
            long deadline = System.nanoTime() + LOGIN_TIMEOUT.toNanos();
            while (true) {
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("Connexion annulée.");
                }
                if (System.nanoTime() > deadline) {
                    throw new IOException("Délai dépassé : aucune autorisation reçue depuis le navigateur.");
                }
                try {
                    params = result.get(500, TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException ignored) {
                    // on boucle pour tester l'annulation
                }
            }

            if (params.containsKey("error")) {
                throw new OAuthException(params.get("error"), "autorisation refusée dans le navigateur");
            }
            if (!state.equals(params.get("state"))) {
                throw new IOException("Réponse OAuth invalide (state différent) : connexion abandonnée.");
            }

            log.accept("Autorisation reçue, échange du code contre un jeton…");
            Map<String, String> form = new LinkedHashMap<>();
            form.put("code", params.get("code"));
            form.put("client_id", client.clientId());
            form.put("client_secret", client.clientSecret());
            form.put("redirect_uri", redirectUri);
            form.put("grant_type", "authorization_code");
            form.put("code_verifier", verifier);
            JsonObject token = postForm(TOKEN_URI, form);

            String scope = str(token, "scope", "");
            if (!hasRequiredScope(scope)) {
                throw new IOException("L'accès en lecture seule à Gmail n'a pas été accordé : cochez bien "
                        + "l'autorisation demandée sur l'écran Google.");
            }
            String access = str(token, "access_token", null);
            String refresh = str(token, "refresh_token", null);
            if (access == null || refresh == null) {
                throw new IOException("Google n'a pas renvoyé de refresh token. Supprimez l'accès de l'application "
                        + "sur https://myaccount.google.com/permissions puis recommencez.");
            }
            String email = fetchEmail(access);
            saveToken(email, refresh, GMAIL_READONLY);
            log.accept("Connecté en tant que " + email + ".");
            return new Credentials(email, access);
        } finally {
            server.stop(1);
        }
    }

    private String refreshAccessToken(ClientSecret client, String refreshToken) throws IOException, InterruptedException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", client.clientId());
        form.put("client_secret", client.clientSecret());
        form.put("refresh_token", refreshToken);
        form.put("grant_type", "refresh_token");
        String access = str(postForm(TOKEN_URI, form), "access_token", null);
        if (access == null) {
            throw new IOException("Réponse inattendue de Google : pas d'access token.");
        }
        return access;
    }

    /** Adresse du compte : users.getProfile de l'API Gmail (aucun scope supplémentaire nécessaire). */
    private String fetchEmail(String accessToken) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(PROFILE_URI))
                                         .timeout(Duration.ofSeconds(20))
                                         .header("Authorization", "Bearer " + accessToken)
                                         .GET()
                                         .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject json = parseJson(response.body());
        String email = str(json, "emailAddress", null);
        if (response.statusCode() / 100 != 2 || email == null) {
            String detail = apiErrorMessage(json);
            throw new IOException("Impossible de lire le profil Gmail (HTTP " + response.statusCode() + "). "
                    + (response.statusCode() == 403
                    ? "L'API Gmail est peut-être désactivée pour le projet Google Cloud : activez « Gmail API » "
                    + "(API et services → Bibliothèque), patientez une minute puis réessayez."
                    : "")
                    + (detail == null ? "" : " Détail Google : " + detail));
        }
        return email;
    }

    private static String apiErrorMessage(JsonObject json) {
        try {
            JsonObject error = json.getAsJsonObject("error");
            return error != null && error.has("message") ? error.get("message").getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Utilitaires HTTP / JSON / fichiers
    // ------------------------------------------------------------------

    private JsonObject postForm(String uri, Map<String, String> form) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                                         .timeout(Duration.ofSeconds(20))
                                         .header("Content-Type", "application/x-www-form-urlencoded")
                                         .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
                                         .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject json = parseJson(response.body());
        if (response.statusCode() / 100 != 2) {
            throw new OAuthException(str(json, "error", "http_" + response.statusCode()),
                                     str(json, "error_description", null));
        }
        return json;
    }

    private static JsonObject parseJson(String body) {
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }

    private static String encode(Map<String, String> params) {
        StringJoiner joiner = new StringJoiner("&");
        params.forEach((k, v) -> joiner.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return joiner.toString();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            map.put(k, v);
        }
        return map;
    }

    private static String randomUrlSafe(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private Path tokenFile() {
        return configDir.resolve("tokens.json");
    }

    private JsonObject readToken() {
        try {
            Path file = tokenFile();
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void saveToken(String email, String refreshToken, String scope) throws IOException {
        Files.createDirectories(configDir);
        Path file = tokenFile();
        if (!Files.exists(file)) {
            try {
                // Lisible uniquement par l'utilisateur (systèmes POSIX)
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException ignored) {
                // Windows : les droits du dossier utilisateur s'appliquent
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("email", email);
        o.addProperty("refresh_token", refreshToken);
        o.addProperty("scope", scope);
        Files.writeString(file, gson.toJson(o), StandardCharsets.UTF_8,
                          StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private void deleteToken() {
        try {
            Files.deleteIfExists(tokenFile());
        } catch (IOException ignored) {
            // rien d'autre à faire
        }
    }
}
