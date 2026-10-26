package com.hr;

import java.util.List;

/**
 * Évaluation d'un CV (par le LLM ou par mots-clés).
 *
 * @param score           note de 0 à 100
 * @param summary         synthèse en quelques phrases
 * @param strengths       points forts par rapport au poste
 * @param gaps            points manquants ou à vérifier
 * @param error           message si l'évaluation de ce CV a échoué (null sinon) ; le score vaut alors 0
 */
public record EvaluationResult(CvDocument cv,
                               double score,
                               String summary,
                               List<String> strengths,
                               List<String> gaps,
                               String error) {

    public static EvaluationResult failed(CvDocument cv, String message) {
        return new EvaluationResult(cv, 0, "", List.of(), List.of(), message);
    }

    public boolean hasError() {
        return error != null && !error.isBlank();
    }
}
