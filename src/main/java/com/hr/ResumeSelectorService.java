package com.hr;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.hr.auth.GoogleOAuth;
import com.hr.llm.HttpLlmClient;
import com.hr.llm.LlmCvEvaluator;
import com.hr.llm.LlmException;
import com.hr.llm.LlmSettings;

/**
 * Orchestration : OAuth2 → API Gmail → extraction des CV → évaluation (par LLM, ou par mots-clés si
 * {@link Request#keywordMode()}). Utilisé par l'interface Swing.
 */
public final class ResumeSelectorService {

    /** Nombre d'échecs consécutifs (hors erreurs fatales) au-delà duquel l'analyse est interrompue. */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private ResumeSelectorService() {
    }

    public record Request(GoogleOAuth oauth,
                          String jobText,
                          String jobLabel,
                          String folder,
                          int max,
                          LocalDate since,
                          String subjectFilter,
                          Path saveDir,
                          boolean keywordMode,
                          LlmSettings llm) {
    }

    /** @param modelLabel moteur d'évaluation utilisé (modèle LLM, ou {@link KeywordCvEvaluator#LABEL}) */
    public record Outcome(List<EvaluationResult> results, String jobLabel, String modelLabel, boolean keywordMode) {
    }

    public static Outcome analyze(Request request,
                                  Consumer<String> log,
                                  BiConsumer<Integer, Integer> progress,
                                  BooleanSupplier cancelled) throws Exception {
        if (request.jobText() == null || request.jobText().isBlank()) {
            throw new IllegalArgumentException("Le descriptif de poste est vide.");
        }
        CvEvaluator evaluator;
        String modelLabel;
        if (request.keywordMode()) {
            KeywordCvEvaluator keywordEvaluator = new KeywordCvEvaluator(request.jobText());
            evaluator = keywordEvaluator;
            modelLabel = KeywordCvEvaluator.LABEL;
            log.accept(String.format("Descriptif « %s » — évaluation par mots-clés.", request.jobLabel()));
            log.accept(String.format("Mots-clés retenus (%d) : %s", keywordEvaluator.keywords().size(),
                    String.join(", ", keywordEvaluator.keywords())));
        } else {
            if (request.llm() == null) {
                throw new IllegalArgumentException("Aucun modèle de langage configuré.");
            }
            request.llm().validate();
            evaluator = new LlmCvEvaluator(new HttpLlmClient(request.llm()), request.jobText());
            modelLabel = request.llm().label();
            log.accept(String.format("Descriptif « %s » — évaluation par %s.", request.jobLabel(), modelLabel));
        }

        log.accept("Authentification Google (OAuth2)…");
        GoogleOAuth.Credentials credentials = request.oauth().authorize(log, cancelled);
        if (cancelled.getAsBoolean()) {
            return new Outcome(List.of(), request.jobLabel(), modelLabel, request.keywordMode());
        }

        log.accept("Lecture de Gmail (" + credentials.email() + ", lecture seule)…");
        List<CvDocument> cvs;
        try (GmailCvFetcher fetcher = new GmailCvFetcher(credentials.accessToken(), log)) {
            fetcher.setProgressListener(progress);
            fetcher.setCancelCheck(cancelled);
            cvs = fetcher.fetch(request.folder(), request.max(), request.since(),
                    request.subjectFilter(), request.saveDir());
        }

        List<EvaluationResult> results = evaluate(cvs, evaluator, log, progress, cancelled);
        results.sort(Comparator.comparingDouble(EvaluationResult::score).reversed());
        log.accept(results.size() + " CV évalué(s).");
        return new Outcome(List.copyOf(results), request.jobLabel(), modelLabel, request.keywordMode());
    }

    /**
     * Évalue les CV un par un. Une erreur « fatale » (clé refusée…) interrompt tout ; sinon le CV concerné
     * est marqué en échec et on continue, jusqu'à {@value #MAX_CONSECUTIVE_FAILURES} échecs consécutifs.
     */
    public static List<EvaluationResult> evaluate(List<CvDocument> cvs,
                                           CvEvaluator evaluator,
                                           Consumer<String> log,
                                           BiConsumer<Integer, Integer> progress,
                                           BooleanSupplier cancelled) throws Exception {
        List<EvaluationResult> results = new ArrayList<>();
        int consecutiveFailures = 0;
        for (int i = 0; i < cvs.size() && !cancelled.getAsBoolean(); i++) {
            CvDocument cv = cvs.get(i);
            log.accept(String.format("Évaluation %d/%d : %s (%s)…", i + 1, cvs.size(), cv.candidateLabel(), cv.fileName()));
            try {
                results.add(evaluator.evaluate(cv));
                consecutiveFailures = 0;
            } catch (LlmException e) {
                if (e.isFatal()) {
                    throw e;
                }
                log.accept("  Échec : " + e.getMessage());
                results.add(EvaluationResult.failed(cv, e.getMessage()));
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    throw new LlmException(MAX_CONSECUTIVE_FAILURES
                            + " évaluations consécutives en échec, arrêt de l'analyse. Dernière erreur : "
                            + e.getMessage(), true);
                }
            }
            progress.accept(i + 1, cvs.size());
        }
        return results;
    }
}
