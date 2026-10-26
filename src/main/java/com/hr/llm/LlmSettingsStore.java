package com.hr.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.prefs.Preferences;

/**
 * Mémorise la configuration du LLM utilisée par l'interface graphique.
 * <ul>
 *   <li>URL et modèle (non secrets) : préférences utilisateur, par fournisseur ;</li>
 *   <li>clé API : jamais dans les préférences. Variable d'environnement, ou fichier
 *       {@code llm_api_keys.json} du dossier de configuration (droits {@code rw-------}),
 *       écrit uniquement si l'utilisateur coche « Mémoriser la clé ».</li>
 * </ul>
 */
public final class LlmSettingsStore {

    private static final String GENERIC_ENV = "LLM_API_KEY";

    private final Preferences prefs = Preferences.userNodeForPackage(LlmSettingsStore.class);
    private final Path keyFile;
    private final Gson gson = new Gson();

    public LlmSettingsStore(Path configDir) {
        this.keyFile = configDir.resolve("llm_api_keys.json");
    }

    // ------------------------------------------------------------------
    //  URL / modèle
    // ------------------------------------------------------------------

    public LlmProvider lastProvider() {
        try {
            return LlmProvider.fromId(prefs.get("provider", LlmProvider.OPENROUTER.id()));
        } catch (IllegalArgumentException e) {
            return LlmProvider.OPENROUTER;
        }
    }

    /** Réglages mémorisés pour ce fournisseur (valeurs par défaut sinon) ; la clé est celle du fichier, si elle y est. */
    public LlmSettings load(LlmProvider provider) {
        return new LlmSettings(provider,
                prefs.get("baseUrl." + provider.id(), provider.defaultBaseUrl()),
                prefs.get("model." + provider.id(), provider.defaultModel()),
                storedKey(provider));
    }

    /** Enregistre fournisseur, URL et modèle (pas la clé). */
    public void save(LlmSettings settings) {
        prefs.put("provider", settings.provider().id());
        prefs.put("baseUrl." + settings.provider().id(), settings.baseUrl());
        prefs.put("model." + settings.provider().id(), settings.model());
    }

    // ------------------------------------------------------------------
    //  Clé API
    // ------------------------------------------------------------------

    /** Ordre de priorité : clé saisie, variable d'environnement du fournisseur, LLM_API_KEY, fichier mémorisé. */
    public String resolveKey(LlmProvider provider, String typed) {
        if (typed != null && !typed.isBlank()) {
            return typed.trim();
        }
        for (String name : new String[]{provider.envVar(), GENERIC_ENV}) {
            String value = name == null ? null : System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return storedKey(provider);
    }

    /** Clé mémorisée dans le fichier (chaîne vide s'il n'y en a pas). */
    public String storedKey(LlmProvider provider) {
        JsonObject keys = readKeys();
        return keys.has(provider.id()) ? keys.get(provider.id()).getAsString() : "";
    }

    public void saveKey(LlmProvider provider, String key) throws IOException {
        JsonObject keys = readKeys();
        keys.addProperty(provider.id(), key.trim());
        writeKeys(keys);
    }

    public void deleteKey(LlmProvider provider) throws IOException {
        JsonObject keys = readKeys();
        if (!keys.has(provider.id())) {
            return;
        }
        keys.remove(provider.id());
        if (keys.size() == 0) {
            Files.deleteIfExists(keyFile);
        } else {
            writeKeys(keys);
        }
    }

    private JsonObject readKeys() {
        try {
            if (Files.isRegularFile(keyFile)) {
                return JsonParser.parseString(Files.readString(keyFile, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (IOException | RuntimeException ignored) {
            // fichier absent ou corrompu : on repart de zéro
        }
        return new JsonObject();
    }

    private void writeKeys(JsonObject keys) throws IOException {
        Files.createDirectories(keyFile.getParent());
        if (!Files.exists(keyFile)) {
            try {
                // Lisible uniquement par l'utilisateur (systèmes POSIX)
                Files.createFile(keyFile,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException ignored) {
                // Windows : les droits du dossier utilisateur s'appliquent
            }
        }
        Files.writeString(keyFile, gson.toJson(keys), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }
}
