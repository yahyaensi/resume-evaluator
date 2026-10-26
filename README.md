# Évaluateur de CV

Application Java d’évaluation de CV assistée par IA, permettant d’analyser les CV reçus via Gmail et d’évaluer leur adéquation avec un descriptif de poste selon deux approches : l’utilisation d’un modèle de langage (LLM) ou une analyse basée sur des mots-clés, sans recours à l’IA générative.

## Ce que fait l'application

- se connecte à Gmail avec OAuth2 (scope `gmail.readonly` uniquement) ;
- récupère les pièces jointes PDF, Word (`.doc`, `.docx`) et `.txt` ;
- évalue chaque CV selon deux modes :
  - LLM (OpenRouter, Anthropic, OpenAI, Mistral, Ollama ou API compatible OpenAI) ;
  - mots-clés (sans LLM) ;
- génère un rapport Excel avec la note, le résumé, les points forts et les points à vérifier.

> Si vous utilisez un fournisseur externe, le texte des CV est envoyé à ce fournisseur. Avec Ollama ou le mode mots-clés, il reste local.

## Prérequis

- Java 17+
- Maven 3.9+ si vous construisez le projet
- un client OAuth Google de type "Application de bureau"
- un accès LLM ou un serveur local (Ollama)

## Démarrage

### Construire le jar

```bash
mvn clean package
```

### Créer l'installateur Windows (MSI)

Sur Windows, avec un JDK 17+, Maven et WiX Toolset 3.0+ installés (les exécutables `heat.exe`, `candle.exe` et `light.exe` doivent être dans le `PATH`) :

```powershell
powershell -ExecutionPolicy Bypass -File .\package-windows.ps1
```

L'installateur `ResumeEvaluator-<version>.msi` est créé dans `target\windows-installer`. Son assistant d'installation affiche une confirmation de réussite à la fin. Il installe l'application avec son runtime Java, son icône et des raccourcis dans le menu Démarrer et sur le bureau.

### Générer les versions Windows, Ubuntu et macOS avec GitHub Actions

Le workflow `.github/workflows/package.yml` crée une version portable avec runtime Java pour Windows, Ubuntu et macOS, ainsi qu'un installateur MSI pour Windows. Chaque paquet correspond au système et à l'architecture indiqués dans son nom. L'application macOS n'est pas signée ni notarisée ; Gatekeeper peut afficher un avertissement au premier lancement.

Avant de lancer le workflow, pousse le code et le fichier `.github/workflows/package.yml` sur GitHub. Ajoute ensuite le JSON du client OAuth comme secret du dépôt :

1. ouvre **Settings → Secrets and variables → Actions** ;
2. clique sur **New repository secret** ;
3. nomme le secret `CLIENT_SECRET_JSON` et colle le contenu complet de `src/main/resources/client_secret.json`.

Tu peux aussi créer le secret avec GitHub CLI depuis PowerShell, sans afficher son contenu :

```powershell
Get-Content -Raw .\src\main\resources\client_secret.json | gh secret set CLIENT_SECRET_JSON
```

Pour lancer les builds manuellement, ouvre **Actions → Build portable apps and Windows MSI**, clique sur **Run workflow**, sélectionne la branche contenant le workflow, puis confirme avec **Run workflow**. À la fin, ouvre l'exécution et télécharge les paquets dans la section **Artifacts** ; ils sont conservés 30 jours.

Pour publier les paquets dans une release GitHub, crée un tag qui commence par `v` et dont le numéro correspond exactement à la version de `pom.xml`. Par exemple, pour la version `1.0.0`, le tag attendu est `v1.0.0` : un tag `1.0.0` sans le `v` ne déclenche pas le workflow. Depuis PowerShell :

```powershell
git tag v1.0.0
git push origin v1.0.0
```

Le workflow construit alors les paquets et crée ou met à jour la release avec les fichiers à distribuer. Le runner recrée temporairement `src/main/resources/client_secret.json` pour construire les paquets, puis supprime le fichier. Comme le client OAuth est intégré à l'application, il sera extractible des paquets distribués ; le secret GitHub protège le dépôt et le processus de build, pas les binaires remis aux utilisateurs.

### Lancer l'interface graphique

```bash
java -jar target/resume-evaluator-1.0.0.jar
```

## Configuration Google OAuth

Le fichier `src/main/resources/client_secret.json` est embarqué dans le jar et utilisé comme source unique.

Pour le configurer :

1. créer un projet Google Cloud ;
2. activer l'API Gmail ;
3. créer un ID client OAuth de type "Application de bureau" ;
4. télécharger le JSON et le placer dans `src/main/resources/client_secret.json`.

La première connexion ouvre le navigateur et demande l'autorisation. Le refresh token est stocké localement dans `~/.resume-selector/tokens.json`.

## Notes

- Le mode mots-clés ne fait aucun appel réseau pour l'évaluation.
- Les CV scannés sans texte détectable sont notés 0 et signalés dans le rapport.
- Le score est indicatif : il doit aider à trier, pas remplacer l'examen humain des CV.
