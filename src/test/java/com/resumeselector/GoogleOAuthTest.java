package com.resumeselector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hr.auth.GoogleOAuth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoogleOAuthTest {

    @Test
    void bundledClientSecretIsPresentAndValid(@TempDir Path dir) {
        // Garde-fou : src/main/resources/client_secret.json doit exister (client « Application de bureau »)
        assertTrue(new GoogleOAuth(dir.resolve("cfg")).hasClientSecret());
    }

    @Test
    void acceptsDesktopClientSecretFromClasspath(@TempDir Path dir) {
        assertTrue(new GoogleOAuth(dir.resolve("cfg"), "/oauth/desktop-client.json").hasClientSecret());
    }

    @Test
    void rejectsWebClientSecret(@TempDir Path dir) {
        assertFalse(new GoogleOAuth(dir.resolve("cfg"), "/oauth/web-client.json").hasClientSecret());
    }

    @Test
    void rejectsInvalidClientSecret(@TempDir Path dir) {
        assertFalse(new GoogleOAuth(dir.resolve("cfg"), "/oauth/invalid.json").hasClientSecret());
    }

    @Test
    void missingResourceMeansNoClientSecret(@TempDir Path dir) {
        assertFalse(new GoogleOAuth(dir.resolve("cfg"), "/oauth/n-existe-pas.json").hasClientSecret());
    }

    @Test
    void clientSecretIsNeverReadFromConfigDir(@TempDir Path dir) throws Exception {
        // Même si un client_secret.json valide traîne dans le dossier de configuration, il est ignoré.
        Path cfg = dir.resolve("cfg");
        java.nio.file.Files.createDirectories(cfg);
        java.nio.file.Files.writeString(cfg.resolve("client_secret.json"),
                "{\"installed\":{\"client_id\":\"abc\",\"client_secret\":\"s\"}}");

        assertFalse(new GoogleOAuth(cfg, "/oauth/n-existe-pas.json").hasClientSecret());
    }

    @Test
    void noAccountStoredInFreshConfigDir(@TempDir Path dir) {
        assertTrue(new GoogleOAuth(dir.resolve("cfg")).storedEmail().isEmpty());
    }

    @Test
    void onlyTheGmailReadonlyScopeIsRequested() {
        assertEquals("https://www.googleapis.com/auth/gmail.readonly", GoogleOAuth.SCOPES);
        assertFalse(GoogleOAuth.SCOPES.contains("mail.google.com"), "plus d'accès complet à Gmail");
        assertFalse(GoogleOAuth.SCOPES.contains(" "), "un seul scope demandé");
    }

    @Test
    void requiredScopeDetection() {
        assertTrue(GoogleOAuth.hasRequiredScope("https://www.googleapis.com/auth/gmail.readonly"));
        assertTrue(GoogleOAuth.hasRequiredScope("openid https://www.googleapis.com/auth/gmail.readonly"));
        assertFalse(GoogleOAuth.hasRequiredScope("https://mail.google.com/ email"));
        assertFalse(GoogleOAuth.hasRequiredScope("https://www.googleapis.com/auth/gmail.readonly.extra"));
        assertFalse(GoogleOAuth.hasRequiredScope(""));
        assertFalse(GoogleOAuth.hasRequiredScope(null));
    }

    @Test
    void tokenFromAnOlderVersionIsNotTreatedAsConnected(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("cfg");
        Files.createDirectories(cfg);
        Path tokens = cfg.resolve("tokens.json");

        // ancienne version : pas de scope enregistré (accès complet IMAP)
        Files.writeString(tokens, "{\"email\":\"a@b.c\",\"refresh_token\":\"r\"}");
        assertTrue(new GoogleOAuth(cfg).storedEmail().isEmpty());

        Files.writeString(tokens, "{\"email\":\"a@b.c\",\"refresh_token\":\"r\","
                + "\"scope\":\"https://www.googleapis.com/auth/gmail.readonly\"}");
        assertEquals(Optional.of("a@b.c"), new GoogleOAuth(cfg).storedEmail());
    }
}
