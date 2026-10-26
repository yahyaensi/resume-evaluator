package com.hr.llm;

/**
 * Échec d'un appel au LLM. Une erreur « fatale » (clé refusée, serveur injoignable…) se reproduirait pour
 * tous les CV : l'analyse s'arrête. Sinon, seul le CV concerné est marqué en échec.
 */
public class LlmException extends Exception {

    private final boolean fatal;

    public LlmException(String message, boolean fatal) {
        super(message);
        this.fatal = fatal;
    }

    public boolean isFatal() {
        return fatal;
    }
}
