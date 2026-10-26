package com.hr;

import java.time.LocalDateTime;

/** Un CV récupéré en pièce jointe d'un email. */
public record CvDocument(String fileName,
                         String senderName,
                         String senderEmail,
                         String subject,
                         LocalDateTime receivedAt,
                         String text) {

    /** Nom à afficher pour le candidat : nom de l'expéditeur, sinon son email. */
    public String candidateLabel() {
        return (senderName == null || senderName.isBlank()) ? senderEmail : senderName;
    }
}
