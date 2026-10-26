package com.resumeselector;

import org.junit.jupiter.api.Test;

import com.hr.CvDocument;
import com.hr.EvaluationResult;
import com.hr.KeywordCvEvaluator;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordCvEvaluatorTest {

    private static final String JOB = """
            Développeur Java senior (H/F)
            Compétences attendues : Java 17, Spring Boot, microservices, Kafka, SQL, Docker, Kubernetes,
            CI/CD (Jenkins), AWS. Méthodes agiles (Scrum), tests unitaires (JUnit, Mockito).""";

    private static CvDocument cv(String text) {
        return new CvDocument("cv.pdf", "Jean Dupont", "jean@example.com", "Candidature", LocalDateTime.now(), text);
    }

    @Test
    void extractsTechnicalTermsFromTheJobDescription() {
        KeywordCvEvaluator evaluator = new KeywordCvEvaluator(JOB);

        assertTrue(evaluator.keywords().containsAll(
                java.util.List.of("java", "spring boot", "kafka", "kubernetes", "docker", "aws", "jenkins", "ci/cd")),
                evaluator.keywords().toString());
        // les mots génériques d'une offre ne sont pas des mots-clés
        assertFalse(evaluator.keywords().contains("recherchons"));
        assertFalse(evaluator.keywords().contains("h/f"));
    }

    @Test
    void cvCoveringEveryKeywordScoresOneHundred() {
        KeywordCvEvaluator evaluator = new KeywordCvEvaluator("Java, Kafka, Docker");

        EvaluationResult r = evaluator.evaluate(cv("Ingénieur : JAVA 21, Apache Kafka, Docker Compose."));

        assertEquals(100.0, r.score());
        assertEquals(3, r.strengths().size());
        assertTrue(r.gaps().isEmpty());
        assertFalse(r.hasError());
    }

    @Test
    void scoreReflectsTheShareOfKeywordsFound() {
        KeywordCvEvaluator evaluator = new KeywordCvEvaluator("Java, Kafka");

        EvaluationResult r = evaluator.evaluate(cv("Développeur Java."));

        assertEquals(50.0, r.score());
        assertEquals(java.util.List.of("java"), r.strengths());
        assertEquals(java.util.List.of("kafka"), r.gaps());
    }

    @Test
    void matchingIgnoresCaseAndAccentsAndUnderstandsSynonyms() {
        KeywordCvEvaluator evaluator = new KeywordCvEvaluator("Kubernetes, JavaScript, Spring Boot, agile");

        EvaluationResult r = evaluator.evaluate(cv("K8S, JS, SpringBoot, méthodes AGILES"));

        assertEquals(100.0, r.score());
    }

    @Test
    void wholeWordsOnly() {
        KeywordCvEvaluator evaluator = new KeywordCvEvaluator("Java");

        assertEquals(0.0, evaluator.evaluate(cv("Expert JavaScript et nosql")).score());
        assertEquals(100.0, evaluator.evaluate(cv("Java/Spring, (java).")).score());
    }

    @Test
    void sqlIsNotFoundInsideNosql() {
        KeywordCvEvaluator evaluator = new KeywordCvEvaluator("SQL");

        assertEquals(0.0, evaluator.evaluate(cv("MongoDB, NoSQL")).score());
        assertEquals(100.0, evaluator.evaluate(cv("PostgreSQL et SQL Server")).score());
        assertEquals(100.0, evaluator.evaluate(cv("Bases de données : PostgreSQL")).score());
    }

    @Test
    void symbolsInTermsAreSupported() {
        KeywordCvEvaluator evaluator = new KeywordCvEvaluator("C++, C#, CI/CD, Node.js");

        assertEquals(100.0, evaluator.evaluate(cv("C++ et C# ; pipelines CI/CD ; NodeJS")).score());
    }

    @Test
    void nodeJsDoesNotImplyJavaScript() {
        KeywordCvEvaluator evaluator = new KeywordCvEvaluator("Node.js");

        assertEquals(java.util.List.of("node.js"), evaluator.keywords());
        assertEquals(0.0, evaluator.evaluate(cv("Expert JavaScript")).score());
        assertEquals(100.0, evaluator.evaluate(cv("Backend Node.js")).score());
    }

    @Test
    void repeatedKeywordWeighsMore() {
        KeywordCvEvaluator evaluator = new KeywordCvEvaluator("Java Java Java Java Kafka");

        double withJava = evaluator.evaluate(cv("Java")).score();
        double withKafka = evaluator.evaluate(cv("Kafka")).score();

        assertTrue(withJava > withKafka, withJava + " > " + withKafka);
        assertEquals(100.0, withJava + withKafka, 0.11);
    }

    @Test
    void fallsBackToFrequentWordsWhenJobHasFewTechnicalTerms() {
        KeywordCvEvaluator evaluator = new KeywordCvEvaluator(
                "Comptable expérimenté : comptabilité générale, bilan, comptabilité fournisseurs, bilan.");

        assertTrue(evaluator.keywords().contains("comptabilité"), evaluator.keywords().toString());
        assertTrue(evaluator.keywords().contains("bilan"), evaluator.keywords().toString());
        assertTrue(evaluator.evaluate(cv("Comptabilite generale, bilan, fournisseurs, comptable")).score() > 0);
    }

    @Test
    void emptyCvIsScoredZero() {
        EvaluationResult r = new KeywordCvEvaluator("Java").evaluate(cv("   "));

        assertEquals(0.0, r.score());
        assertTrue(r.summary().contains("Aucun texte"));
    }

    @Test
    void jobWithoutUsableKeywordIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new KeywordCvEvaluator(""));
        assertThrows(IllegalArgumentException.class, () -> new KeywordCvEvaluator("le la les de du et ou"));
    }

    @Test
    void keywordListIsCapped() {
        StringBuilder job = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            job.append("motcle").append((char) ('a' + i % 26)).append((char) ('a' + i / 26)).append(' ');
        }

        assertTrue(new KeywordCvEvaluator(job.toString()).keywords().size() <= KeywordCvEvaluator.MAX_KEYWORDS);
    }
}
