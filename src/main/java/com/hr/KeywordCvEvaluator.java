package com.hr;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Évaluation « sans modèle de langage » : les mots-clés sont extraits du descriptif de poste, puis recherchés
 * dans le texte de chaque CV.
 * <ul>
 *   <li>Extraction : termes techniques connus (Java, Kafka, Kubernetes…, avec leurs variantes : « K8s » =
 *       « Kubernetes ») ; si le descriptif en contient peu, complétés par ses mots les plus fréquents
 *       (hors mots vides).</li>
 *   <li>Score : part des mots-clés retrouvés dans le CV, de 0 à 100. Un mot-clé répété dans le descriptif
 *       pèse davantage.</li>
 *   <li>Comparaison insensible à la casse et aux accents, sur des mots entiers (« java » ne correspond pas à
 *       « javascript »).</li>
 * </ul>
 * Comparaison purement lexicale : un synonyme non prévu n'est pas reconnu, et un mot présent hors contexte
 * est compté. Le score est indicatif.
 */
public final class KeywordCvEvaluator implements CvEvaluator {

    /** Libellé repris dans l'interface et dans le rapport Excel. */
    public static final String LABEL = "Mots-clés (sans modèle de langage)";

    /** Nombre maximal de mots-clés retenus dans un descriptif. */
    public static final int MAX_KEYWORDS = 40;
    /** En dessous de ce nombre de termes techniques reconnus, on complète avec les mots fréquents. */
    private static final int MIN_VOCABULARY_HITS = 5;
    private static final int MAX_FALLBACK_WORDS = 25;

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}+#.\\-]*");

    /** Termes techniques reconnus dans le descriptif (minuscules, sans accents). */
    private static final Set<String> VOCABULARY = new HashSet<>(Arrays.asList(
            // langages
            "java", "kotlin", "scala", "groovy", "python", "javascript", "typescript", "php", "ruby", "rust",
            "c++", "c#", "swift", "perl", "bash", "powershell", "sql", "pl/sql", "html", "css", "sass",
            // frameworks et bibliothèques
            "spring", "spring boot", "spring cloud", "spring security", "hibernate", "jpa", "maven", "gradle",
            "junit", "mockito", "react", "angular", "vue", "node.js", "next.js", "django", "flask",
            "fastapi", "symfony", "laravel", "jquery", "bootstrap", "tailwind", ".net", "asp.net", "quarkus",
            "micronaut", "jakarta ee", "thymeleaf", "swing", "javafx",
            // données
            "postgresql", "mysql", "oracle", "mongodb", "redis", "elasticsearch", "cassandra", "sqlite",
            "mariadb", "sql server", "nosql", "kafka", "rabbitmq", "activemq", "spark", "hadoop", "airflow",
            "snowflake", "kibana", "logstash", "grafana", "prometheus",
            // cloud et infrastructure
            "aws", "azure", "gcp", "docker", "kubernetes", "helm", "terraform", "ansible", "linux", "unix",
            "nginx", "openshift", "jenkins", "gitlab", "github", "git", "ci/cd", "sonarqube", "devops", "sre",
            "serverless", "lambda",
            // architecture et méthodes
            "microservices", "rest", "graphql", "grpc", "soap", "api", "oauth", "jwt", "keycloak", "tdd", "bdd",
            "ddd", "agile", "scrum", "kanban", "jira", "confluence", "uml", "orm", "mvc",
            "design patterns", "clean code", "event-driven", "cqrs",
            // data et IA
            "machine learning", "deep learning", "pandas", "numpy", "tensorflow", "pytorch", "power bi",
            "etl", "big data", "data science",
            // tests et qualité
            "selenium", "cypress", "cucumber", "postman", "jmeter", "testng",
            // mobile et divers
            "android", "ios", "flutter", "react native", "salesforce", "sap", "excel", "figma"));

    /** Variantes reconnues dans un CV pour un terme du vocabulaire (minuscules, sans accents). */
    private static final Map<String, List<String>> VARIANTS = Map.ofEntries(
            Map.entry("kubernetes", List.of("k8s")),
            Map.entry("javascript", List.of("js", "ecmascript")),
            Map.entry("typescript", List.of("ts")),
            Map.entry("sql", List.of("postgresql", "postgres", "mysql", "mariadb", "sqlite")),
            Map.entry("postgresql", List.of("postgres")),
            Map.entry("spring", List.of("springboot")),
            Map.entry("spring boot", List.of("springboot")),
            Map.entry("ci/cd", List.of("ci cd", "cicd", "ci-cd")),
            Map.entry("node.js", List.of("nodejs", "node js")),
            Map.entry("react", List.of("reactjs", "react.js")),
            Map.entry("angular", List.of("angularjs")),
            Map.entry("vue", List.of("vue.js", "vuejs")),
            Map.entry("c#", List.of("csharp", "c sharp")),
            Map.entry(".net", List.of("dotnet")),
            Map.entry("aws", List.of("amazon web services")),
            Map.entry("microservices", List.of("microservice", "micro-services", "micro services")),
            Map.entry("agile", List.of("agiles")),
            Map.entry("devops", List.of("dev ops")),
            Map.entry("mongodb", List.of("mongo")),
            Map.entry("elasticsearch", List.of("elastic search")),
            Map.entry("rest", List.of("restful")),
            Map.entry("machine learning", List.of("apprentissage automatique")),
            Map.entry("gcp", List.of("google cloud")),
            Map.entry("sql server", List.of("sqlserver", "mssql")));

    /** Mots vides (français, anglais) et vocabulaire générique des offres d'emploi, sans accents. */
    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "le", "la", "les", "un", "une", "des", "du", "de", "d", "l", "et", "ou", "mais", "donc", "or", "ni",
            "car", "que", "qui", "quoi", "dont", "ou", "au", "aux", "en", "dans", "par", "pour", "sur", "sous",
            "avec", "sans", "chez", "vers", "entre", "depuis", "pendant", "avant", "apres", "contre", "selon",
            "ce", "cet", "cette", "ces", "ca", "cela", "ceci", "celui", "celle", "ceux", "il", "elle", "ils",
            "elles", "on", "nous", "vous", "je", "tu", "me", "te", "se", "lui", "leur", "leurs", "son", "sa",
            "ses", "mon", "ma", "mes", "ton", "ta", "tes", "notre", "nos", "votre", "vos", "y", "ne", "pas",
            "plus", "moins", "tres", "trop", "assez", "aussi", "ainsi", "alors", "comme", "si", "tout", "tous",
            "toute", "toutes", "autre", "autres", "meme", "memes", "chaque", "quelques", "plusieurs", "bien",
            "etre", "avoir", "faire", "est", "sont", "etait", "sera", "serait", "ont", "avait", "aura", "fait",
            "peut", "peuvent", "doit", "doivent", "etes", "avez", "avons", "sommes", "soit", "ete", "etant",
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "at", "for", "with", "without", "from", "by",
            "as", "is", "are", "was", "were", "be", "been", "being", "this", "that", "these", "those", "it",
            "its", "we", "you", "your", "our", "their", "they", "will", "would", "can", "could", "should",
            "must", "have", "has", "had", "not", "but", "if", "than", "then", "also", "about", "into", "over",
            // vocabulaire générique des offres
            "poste", "postes", "recherchons", "recherche", "rechercher", "rejoindre", "rejoignez", "equipe",
            "equipes", "entreprise", "societe", "mission", "missions", "profil", "profils", "candidat",
            "candidats", "candidature", "candidatures", "experience", "experiences", "annees", "annee", "ans",
            "an", "competences", "competence", "attendues", "attendu", "attendue", "souhaitee", "souhaite",
            "connaissance", "connaissances", "maitrise", "bonne", "bon", "bonnes", "bons", "serieux",
            "serieuse", "senior", "junior", "confirme", "h/f", "f/h", "hf", "fh", "cdi", "cdd", "stage",
            "alternance", "temps", "plein", "partiel", "salaire", "remuneration", "avantages", "contrat",
            "type", "lieu", "france", "paris", "renforcer", "developper", "developpement", "participer",
            "participation", "realiser", "assurer", "mettre", "oeuvre", "cadre", "sein", "notamment",
            "ainsi", "afin", "etc", "plus", "autonomie", "autonome", "capacite", "capacites", "qualite",
            "qualites", "projet", "projets", "client", "clients", "besoin", "besoins", "solution", "solutions",
            "job", "role", "team", "company", "skills", "skill", "experience", "years", "year", "required",
            "requirements", "responsibilities", "working", "work", "knowledge", "strong", "good", "ability"));

    /** Un mot-clé du descriptif, avec ses formes reconnues dans un CV. */
    private record Keyword(String label, double weight, List<Pattern> patterns) {
        boolean foundIn(String normalizedCv) {
            for (Pattern p : patterns) {
                if (p.matcher(normalizedCv).find()) {
                    return true;
                }
            }
            return false;
        }
    }

    private final List<Keyword> keywords;

    /** @throws IllegalArgumentException si le descriptif ne contient aucun mot-clé exploitable */
    public KeywordCvEvaluator(String jobText) {
        this.keywords = extractKeywords(jobText == null ? "" : jobText);
        if (keywords.isEmpty()) {
            throw new IllegalArgumentException(
                    "Aucun mot-clé exploitable dans le descriptif de poste : complétez-le (compétences, outils…).");
        }
    }

    /** Mots-clés retenus, du plus au moins important (pour le journal). */
    public List<String> keywords() {
        return keywords.stream().map(Keyword::label).toList();
    }

    @Override
    public EvaluationResult evaluate(CvDocument cv) {
        if (cv.text() == null || cv.text().isBlank()) {
            return new EvaluationResult(cv, 0, "Aucun texte exploitable dans ce CV (PDF scanné ?).",
                    List.of(), List.of(), null);
        }
        String text = normalize(cv.text());
        double total = 0;
        double matched = 0;
        List<String> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Keyword k : keywords) {
            total += k.weight();
            if (k.foundIn(text)) {
                matched += k.weight();
                found.add(k.label());
            } else {
                missing.add(k.label());
            }
        }
        double score = Math.round(100.0 * matched / total * 10) / 10.0;
        String summary = String.format(Locale.FRENCH,
                "%d mot(s)-clé(s) du descriptif sur %d retrouvé(s) dans le CV. "
                        + "Comparaison lexicale : un synonyme non prévu n'est pas détecté.",
                found.size(), keywords.size());
        return new EvaluationResult(cv, score, summary, found, missing, null);
    }

    // ------------------------------------------------------------------
    //  Extraction des mots-clés du descriptif
    // ------------------------------------------------------------------

    private static List<Keyword> extractKeywords(String jobText) {
        String job = normalize(jobText);

        // 1) termes techniques connus
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, List<Pattern>> patterns = new LinkedHashMap<>();
        for (String term : VOCABULARY) {
            List<Pattern> forms = new ArrayList<>();
            forms.add(wholeWord(term));
            for (String variant : VARIANTS.getOrDefault(term, List.of())) {
                forms.add(wholeWord(variant));
            }
            int count = 0;
            for (Pattern form : forms) {
                count += countMatches(form, job);
            }
            if (count > 0) {
                counts.put(term, count);
                patterns.put(term, forms);
            }
        }

        // 2) à défaut, les mots les plus fréquents du descriptif
        if (counts.size() < MIN_VOCABULARY_HITS) {
            addFrequentWords(jobText, counts, patterns);
        }

        return counts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(MAX_KEYWORDS)
                .map(e -> new Keyword(e.getKey(), 1 + Math.log(e.getValue()), patterns.get(e.getKey())))
                .toList();
    }

    private static void addFrequentWords(String jobText, Map<String, Integer> counts,
                                         Map<String, List<Pattern>> patterns) {
        // Les mots sont lus dans le texte d'origine (accents conservés pour l'affichage) ; la clé de
        // comparaison est la forme normalisée.
        Map<String, Integer> frequency = new LinkedHashMap<>();
        Map<String, String> display = new LinkedHashMap<>();
        Matcher m = TOKEN.matcher(jobText.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String token = trimTrailing(m.group());
            String key = normalize(token);
            if (key.length() < 3 || STOPWORDS.contains(key) || key.chars().allMatch(Character::isDigit)
                    || coveredByVocabulary(key, counts, patterns)) {
                continue;
            }
            frequency.merge(key, 1, Integer::sum);
            display.putIfAbsent(key, token);
        }
        frequency.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(Math.min(MAX_FALLBACK_WORDS, Math.max(0, MAX_KEYWORDS - counts.size())))
                .forEach(e -> {
                    String label = display.get(e.getKey());
                    counts.put(label, e.getValue());
                    patterns.put(label, List.of(wholeWord(e.getKey())));
                });
    }

    /**
     * Vrai si ce mot est déjà couvert par un terme technique retenu : le terme lui-même ou l'une de ses variantes
     * (« k8s »), ou un mot qui le compose (« boot » dans « spring boot »).
     */
    private static boolean coveredByVocabulary(String key, Map<String, Integer> counts,
                                               Map<String, List<Pattern>> patterns) {
        for (String term : counts.keySet()) {
            if (wholeWord(key).matcher(term).find()) {
                return true;
            }
            for (Pattern form : patterns.get(term)) {
                if (form.matcher(key).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    //  Utilitaires
    // ------------------------------------------------------------------

    /** Minuscules, sans accents, espaces insécables et apostrophes typographiques normalisés. */
    static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replace('\u00a0', ' ').replace('\u2019', '\'');
    }

    /**
     * Mot entier : ni lettre ni chiffre juste avant ou juste après. Un terme ne se reconnaît pas non plus comme
     * suffixe d'un mot pointé (« js » dans « node.js », « java » dans « Main.java »).
     */
    private static Pattern wholeWord(String term) {
        String notAfterDottedWord = term.startsWith(".") ? "" : "(?<![\\p{L}\\p{N}]\\.)";
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + notAfterDottedWord + Pattern.quote(term)
                + "(?![\\p{L}\\p{N}])");
    }

    private static int countMatches(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }

    private static String trimTrailing(String token) {
        int end = token.length();
        while (end > 0 && ".-".indexOf(token.charAt(end - 1)) >= 0) {
            end--;
        }
        return token.substring(0, end);
    }
}
