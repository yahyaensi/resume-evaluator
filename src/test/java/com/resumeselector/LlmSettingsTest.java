package com.resumeselector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hr.llm.LlmProvider;
import com.hr.llm.LlmSettings;
import com.hr.llm.LlmSettingsStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmSettingsTest {

    @Test
    void cloudProviderRequiresApiKey() {
        LlmSettings s = new LlmSettings(LlmProvider.ANTHROPIC, "https://api.anthropic.com", "claude-sonnet-5-5", "");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, s::validate);

        assertTrue(e.getMessage().contains("ANTHROPIC_API_KEY"), e.getMessage());
    }

    @Test
    void localProviderDoesNotRequireApiKey() {
        new LlmSettings(LlmProvider.OLLAMA, "http://localhost:11434/v1", "llama3.1", "").validate();
    }

    @Test
    void rejectsBadUrlAndEmptyModel() {
        assertThrows(IllegalArgumentException.class,
                () -> new LlmSettings(LlmProvider.OLLAMA, "localhost:11434", "m", "").validate());
        assertThrows(IllegalArgumentException.class,
                () -> new LlmSettings(LlmProvider.OLLAMA, "http://localhost:11434/v1", "  ", "").validate());
    }

    @Test
    void toStringNeverLeaksTheKey() {
        LlmSettings s = new LlmSettings(LlmProvider.OPENAI, "https://api.openai.com/v1", "m", "sk-très-secret");

        assertFalse(s.toString().contains("sk-très-secret"));
        assertFalse(s.label().contains("sk-très-secret"));
    }

    @Test
    void openRouterIsTheDefaultProviderWithItsFreeRouterModel() {
        LlmProvider first = LlmProvider.values()[0];

        assertEquals(LlmProvider.OPENROUTER, first);
        assertEquals("https://openrouter.ai/api/v1", first.defaultBaseUrl());
        assertEquals("openrouter/free", first.defaultModel());
        assertEquals("openrouter/free", first.suggestedModels().get(0));
        assertTrue(first.suggestedModels().size() > 1, "d'autres modèles gratuits doivent être proposés");
        assertEquals(LlmProvider.Protocol.OPENAI, first.protocol());
        assertEquals(LlmProvider.OPENROUTER, LlmProvider.fromId("openrouter"));
    }

    @Test
    void openRouterRequiresApiKey() {
        LlmSettings s = new LlmSettings(LlmProvider.OPENROUTER, "https://openrouter.ai/api/v1", "openrouter/free", "");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, s::validate);

        assertTrue(e.getMessage().contains("OPENROUTER_API_KEY"), e.getMessage());
    }

    @Test
    void providerLookupById() {
        assertEquals(LlmProvider.MISTRAL, LlmProvider.fromId("Mistral"));
        assertThrows(IllegalArgumentException.class, () -> LlmProvider.fromId("gemini-pro-max"));
    }

    @Test
    void storedKeyRoundTripPerProvider(@TempDir Path dir) throws Exception {
        LlmSettingsStore store = new LlmSettingsStore(dir.resolve("cfg"));
        assertEquals("", store.storedKey(LlmProvider.OPENAI));

        store.saveKey(LlmProvider.OPENAI, "  sk-openai ");
        store.saveKey(LlmProvider.MISTRAL, "mistral-key");

        assertEquals("sk-openai", store.storedKey(LlmProvider.OPENAI));
        assertEquals("mistral-key", store.storedKey(LlmProvider.MISTRAL));

        store.deleteKey(LlmProvider.OPENAI);
        assertEquals("", store.storedKey(LlmProvider.OPENAI));
        assertEquals("mistral-key", store.storedKey(LlmProvider.MISTRAL));

        store.deleteKey(LlmProvider.MISTRAL);
        assertFalse(Files.exists(dir.resolve("cfg").resolve("llm_api_keys.json")));
    }

    @Test
    void typedKeyTakesPrecedence(@TempDir Path dir) throws Exception {
        LlmSettingsStore store = new LlmSettingsStore(dir.resolve("cfg"));
        store.saveKey(LlmProvider.OPENAI, "stored");

        assertEquals("typed", store.resolveKey(LlmProvider.OPENAI, " typed "));
    }

    @Test
    void keyFileIsReadableOnlyByOwner(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("cfg");
        new LlmSettingsStore(cfg).saveKey(LlmProvider.OPENAI, "k");
        Path file = cfg.resolve("llm_api_keys.json");

        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
    }
}
