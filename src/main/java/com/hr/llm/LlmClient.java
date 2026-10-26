package com.hr.llm;

/** Envoie une conversation (consigne système + message utilisateur) à un LLM et retourne sa réponse texte. */
@FunctionalInterface
public interface LlmClient {

    String complete(String system, String user) throws LlmException, InterruptedException;
}
