package com.hr;

import java.io.IOException;

/** Erreur renvoyée par l'API Gmail (ou impossibilité de la joindre). Le message est affichable tel quel. */
public class GmailApiException extends IOException {

    private final int status;
    private final boolean fatal;

    /**
     * @param status code HTTP (0 si le serveur n'a pas répondu)
     * @param fatal  vrai si inutile de continuer (jeton refusé, API désactivée, réseau coupé…)
     */
    public GmailApiException(String message, int status, boolean fatal) {
        super(message);
        this.status = status;
        this.fatal = fatal;
    }

    public int status() {
        return status;
    }

    /** Vrai si l'analyse entière doit s'arrêter, faux si seul l'email concerné peut être ignoré. */
    public boolean isFatal() {
        return fatal;
    }

    /** Jeton d'accès expiré ou révoqué : il faut se reconnecter. */
    public boolean isAuthenticationFailure() {
        return status == 401;
    }
}
