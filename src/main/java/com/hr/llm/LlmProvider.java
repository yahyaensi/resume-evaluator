package com.hr.llm;

import java.util.List;
import java.util.Locale;

/** Fournisseurs de LLM proposés dans l'interface, avec leurs valeurs par défaut (toutes modifiables). */
public enum LlmProvider {

    /**
     * Défaut : « openrouter/free » est un routeur qui choisit un modèle gratuit à chaque requête. Les autres
     * modèles gratuits (suffixe « :free ») changent régulièrement : le champ Modèle de l'interface est libre,
     * la liste ci-dessous n'est qu'une suggestion de départ.
     */
    OPENROUTER(
            "OpenRouter (modèles gratuits)",
            Protocol.OPENAI,
            "https://openrouter.ai/api/v1",
            "openrouter/free",
            true,
            "OPENROUTER_API_KEY",
            List.of("openrouter/free",
                    "nvidia/nemotron-3-ultra-550b-a55b:free",
                    "nvidia/nemotron-3.5-lightning:free",
                    "poolside/laguna-s-2.1:free")),
    ANTHROPIC(
            "Anthropic (Claude)",
            Protocol.ANTHROPIC,
            "https://api.anthropic.com",
            "claude-sonnet-5-5",
            true,
            "ANTHROPIC_API_KEY",
            List.of("claude-sonnet-5-5", "claude-haiku-5-5", "claude-opus-5-5")),
    OPENAI(
            "OpenAI",
            Protocol.OPENAI,
            "https://api.openai.com/v1",
            "gpt-4o-mini",
            true,
            "OPENAI_API_KEY",
            List.of("gpt-4o-mini", "gpt-4o")),
    MISTRAL(
            "Mistral AI",
            Protocol.OPENAI,
            "https://api.mistral.ai/v1",
            "mistral-small-latest",
            true,
            "MISTRAL_API_KEY",
            List.of("mistral-small-latest", "mistral-large-latest")),
    OLLAMA(
            "Ollama (local)",
            Protocol.OPENAI,
            "http://localhost:11434/v1",
            "llama3.1",
            false,
            null,
            List.of("llama3.1", "mistral", "qwen2.5:3b")),
    CUSTOM(
            "Autre (API compatible OpenAI)",
            Protocol.OPENAI,
            "",
            "",
            false,
            null,
            List.of());

    /** Format de l'API HTTP. {@code OPENAI} = /chat/completions, utilisé aussi par Ollama, LM Studio, vLLM, OpenRouter… */
    public enum Protocol {
        ANTHROPIC,
        OPENAI
    }

    private final String label;
    private final Protocol protocol;
    private final String defaultBaseUrl;
    private final String defaultModel;
    private final boolean apiKeyRequired;
    private final String envVar;
    private final List<String> suggestedModels;

    LlmProvider(String label, Protocol protocol, String defaultBaseUrl, String defaultModel,
                boolean apiKeyRequired, String envVar, List<String> suggestedModels) {
        this.label = label;
        this.protocol = protocol;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
        this.apiKeyRequired = apiKeyRequired;
        this.envVar = envVar;
        this.suggestedModels = suggestedModels;
    }

    public String label() {
        return label;
    }

    public Protocol protocol() {
        return protocol;
    }

    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    public String defaultModel() {
        return defaultModel;
    }

    public boolean apiKeyRequired() {
        return apiKeyRequired;
    }

    /** Variable d'environnement spécifique au fournisseur (peut être null) ; LLM_API_KEY est toujours acceptée. */
    public String envVar() {
        return envVar;
    }

    public List<String> suggestedModels() {
        return suggestedModels;
    }

    /** Vrai si les CV restent sur le poste (modèle local) : adapte le message de confidentialité de l'interface. */
    public boolean isLocal() {
        return this == OLLAMA;
    }

    /** Identifiant utilisé dans les préférences (ex. « anthropic »). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static LlmProvider fromId(String id) {
        for (LlmProvider p : values()) {
            if (p.id().equalsIgnoreCase(id == null ? "" : id.trim())) {
                return p;
            }
        }
        throw new IllegalArgumentException("Fournisseur LLM inconnu : « " + id + " ». Valeurs possibles : "
                + String.join(", ", java.util.Arrays.stream(values()).map(LlmProvider::id).toList()) + ".");
    }

    /** Affichage dans la liste déroulante. */
    @Override
    public String toString() {
        return label;
    }
}
