package com.hr.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Client HTTP pour l'API Anthropic ({@code /v1/messages}) et pour les API « compatibles OpenAI »
 * ({@code /chat/completions} : OpenAI, Mistral, Ollama, LM Studio, vLLM, OpenRouter…).
 * Réessaie automatiquement en cas de limite de débit (429), d'erreur serveur (5xx) ou d'erreur réseau.
 */
public final class HttpLlmClient implements LlmClient {

    private static final Gson GSON = new Gson();
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int ANTHROPIC_MAX_TOKENS = 1500;

    private final LlmSettings settings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final Duration requestTimeout;
    private final int maxAttempts;
    private final Duration retryDelay;

    public HttpLlmClient(LlmSettings settings) {
        // Délai généreux : un modèle local sur CPU peut mettre plus d'une minute.
        this(settings, Duration.ofMinutes(3), 3, Duration.ofSeconds(2));
    }

    public HttpLlmClient(LlmSettings settings, Duration requestTimeout, int maxAttempts, Duration retryDelay) {
        this.settings = settings;
        this.requestTimeout = requestTimeout;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelay = retryDelay;
    }

    @Override
    public String complete(String system, String user) throws LlmException, InterruptedException {
        HttpRequest request = buildRequest(system, user);
        LlmException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status / 100 == 2) {
                    return extractText(response.body());
                }
                LlmException error = httpError(status, response.body());
                if (!(status == 429 || status >= 500)) {
                    throw error;
                }
                last = error;
            } catch (ConnectException | HttpConnectTimeoutException e) {
                throw new LlmException("Impossible de joindre " + settings.baseUrl()
                        + " : le serveur est-il démarré et l'URL correcte ?", true);
            } catch (IOException e) {
                last = new LlmException("Erreur réseau : " + e.getMessage(), false);
            }
            if (attempt < maxAttempts) {
                Thread.sleep(retryDelay.toMillis() * attempt);
            }
        }
        throw last;
    }

    // ------------------------------------------------------------------
    //  Requête
    // ------------------------------------------------------------------

    private HttpRequest buildRequest(String system, String user) throws LlmException {
        String base = settings.baseUrl().replaceAll("/+$", "");
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.model());

        String url;
        boolean anthropic = settings.provider().protocol() == LlmProvider.Protocol.ANTHROPIC;
        if (anthropic) {
            url = base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
            body.addProperty("max_tokens", ANTHROPIC_MAX_TOKENS);
            body.addProperty("system", system);
            body.add("messages", messages(null, user));
        } else {
            url = base + "/chat/completions";
            body.add("messages", messages(system, user));
        }

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json");
            if (anthropic) {
                builder.header("x-api-key", settings.apiKey()).header("anthropic-version", ANTHROPIC_VERSION);
            } else if (!settings.apiKey().isEmpty()) {
                builder.header("Authorization", "Bearer " + settings.apiKey());
            }
            return builder.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8)).build();
        } catch (IllegalArgumentException e) {
            throw new LlmException("URL de l'API invalide : " + url, true);
        }
    }

    private static JsonArray messages(String system, String user) {
        JsonArray array = new JsonArray();
        if (system != null) {
            array.add(message("system", system));
        }
        array.add(message("user", user));
        return array;
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    // ------------------------------------------------------------------
    //  Réponse
    // ------------------------------------------------------------------

    private String extractText(String body) throws LlmException {
        String text;
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            text = settings.provider().protocol() == LlmProvider.Protocol.ANTHROPIC
                    ? joinTextParts(root.getAsJsonArray("content"))
                    : openAiContent(root.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content"));
        } catch (RuntimeException e) {
            throw new LlmException("Réponse inattendue du serveur : " + abbreviate(body, 300), false);
        }
        if (text == null || text.isBlank()) {
            throw new LlmException("Le modèle a renvoyé une réponse vide.", false);
        }
        return text;
    }

    /** Anthropic : tableau de blocs {"type":"text","text":"…"} ; on concatène les blocs texte. */
    private static String joinTextParts(JsonArray parts) {
        StringBuilder sb = new StringBuilder();
        for (JsonElement part : parts) {
            JsonObject o = part.getAsJsonObject();
            if (o.has("text") && (!o.has("type") || "text".equals(o.get("type").getAsString()))) {
                sb.append(o.get("text").getAsString());
            }
        }
        return sb.toString();
    }

    /** OpenAI : « content » est une chaîne (ou, chez certains serveurs, un tableau de parties). */
    private static String openAiContent(JsonElement content) {
        if (content == null || content.isJsonNull()) {
            return null;
        }
        return content.isJsonArray() ? joinTextParts(content.getAsJsonArray()) : content.getAsString();
    }

    private LlmException httpError(int status, String body) {
        String detail = errorMessage(body);
        if (detail == null) {
            detail = abbreviate(body, 300);
        }
        return switch (status) {
            case 401, 403 -> new LlmException("Clé API refusée (HTTP " + status + ") : " + detail, true);
            case 404 -> new LlmException("Modèle ou URL introuvable (HTTP 404) : " + detail, true);
            case 429 -> new LlmException("Limite de débit ou quota atteint (HTTP 429) : " + detail, false);
            default -> new LlmException("Erreur du serveur LLM (HTTP " + status + ") : " + detail, false);
        };
    }

    /** Extrait « error.message » (format commun à Anthropic et OpenAI), ou null. */
    private static String errorMessage(String body) {
        try {
            JsonElement error = JsonParser.parseString(body).getAsJsonObject().get("error");
            if (error == null) {
                return null;
            }
            if (error.isJsonObject() && error.getAsJsonObject().has("message")) {
                return error.getAsJsonObject().get("message").getAsString();
            }
            return error.isJsonPrimitive() ? error.getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
