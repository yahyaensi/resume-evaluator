package com.hr.llm;

/** Paramètres de connexion au LLM. La clé API n'apparaît jamais dans {@link #toString()}. */
public record LlmSettings(LlmProvider provider, String baseUrl, String model, String apiKey) {

    public LlmSettings {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        model = model == null ? "" : model.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
    }

    /** @throws IllegalArgumentException avec un message directement affichable à l'utilisateur */
    public void validate() {
        if (!(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            throw new IllegalArgumentException(
                    "L'URL de l'API du modèle est invalide : elle doit commencer par http:// ou https://.");
        }
        if (model.isEmpty()) {
            throw new IllegalArgumentException("Indiquez le nom du modèle à utiliser.");
        }
        if (provider.apiKeyRequired() && apiKey.isEmpty()) {
            String env = provider.envVar() == null ? "LLM_API_KEY" : provider.envVar() + " (ou LLM_API_KEY)";
            throw new IllegalArgumentException("Clé API manquante pour " + provider.label()
                    + " : saisissez-la dans la section « Modèle d'évaluation » ou définissez la variable d'environnement "
                    + env + ".");
        }
    }

    /** Ex. « Anthropic (Claude) · claude-sonnet-5-5 », repris dans le rapport Excel. */
    public String label() {
        return provider.label() + " · " + model;
    }

    @Override
    public String toString() {
        return "LlmSettings[provider=" + provider + ", baseUrl=" + baseUrl + ", model=" + model
                + ", apiKey=" + (apiKey.isEmpty() ? "(vide)" : "***") + "]";
    }
}
