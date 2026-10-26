# Comprendre le workflow GitHub Actions

Ce guide explique le fichier [`.github/workflows/package.yml`](../.github/workflows/package.yml), étape par étape et ligne par ligne. Les lignes vides dans le fichier YAML servent uniquement à séparer les blocs ; elles n'ont aucun effet sur le workflow.

## À quoi sert ce workflow ?

Il construit l'application sur trois systèmes différents :

- Windows : une archive portable et un installateur MSI ;
- Ubuntu/Linux : une archive portable `.tar.gz` ;
- macOS : une archive portable `.zip`.

Il peut être lancé manuellement depuis GitHub Actions. Il se lance aussi lorsqu'un tag commençant par `v` est poussé. Dans ce dernier cas, après les builds, il publie les paquets dans une release GitHub.

## Nom et déclencheurs

```yaml
name: Build portable apps and Windows MSI
```

Donne son nom au workflow. C'est ce nom qui apparaît dans l'onglet **Actions** de GitHub.

```yaml
on:
  workflow_dispatch:
```

- `on` déclare les événements qui démarrent le workflow.
- `workflow_dispatch` autorise son lancement manuel avec le bouton **Run workflow**.

```yaml
  push:
    tags:
      - "v*"
```

- `push` indique qu'un envoi vers GitHub peut lancer le workflow.
- `tags` limite ce déclencheur aux tags.
- `"v*"` sélectionne les tags qui commencent par `v`, par exemple `v1.0.0`. Un tag `1.0.0` ne le déclenche pas.

## Permissions générales

```yaml
permissions:
  contents: read
```

- `permissions` limite les droits du jeton GitHub fourni au workflow.
- `contents: read` donne aux jobs de compilation un accès en lecture au contenu du dépôt. Le job de publication définit plus bas le droit d'écriture nécessaire pour créer une release.

## Job de compilation multi-plateforme

```yaml
jobs:
  package:
```

- `jobs` commence la liste des tâches du workflow.
- `package` est l'identifiant interne du job qui construit les paquets.

```yaml
    name: Package ${{ matrix.os }}
```

Affiche le système utilisé dans le nom du job, par exemple `Package windows-latest`. `${{ ... }}` est une expression GitHub Actions ; `matrix.os` prend la valeur définie pour chaque entrée de la matrice.

```yaml
    strategy:
      fail-fast: false
      matrix:
        include:
          - os: windows-latest
            platform: windows
          - os: ubuntu-latest
            platform: linux
          - os: macos-latest
            platform: macos
```

- `strategy` configure les différentes exécutions du job.
- `fail-fast: false` laisse les autres systèmes continuer même si l'un des builds échoue.
- `matrix` demande à GitHub de lancer le même job plusieurs fois avec des paramètres différents.
- `include` fournit explicitement ces paramètres.
- Chaque `os` choisit un runner hébergé par GitHub.
- Chaque `platform` donne au script une étiquette simple pour distinguer Windows, Linux et macOS.

```yaml
    runs-on: ${{ matrix.os }}
    timeout-minutes: 45
```

- `runs-on` choisit le runner correspondant à l'entrée courante de la matrice.
- `timeout-minutes` arrête un job s'il dépasse 45 minutes.

## Étapes communes aux trois systèmes

```yaml
    steps:
```

Commence la liste des opérations exécutées dans le job, dans l'ordre où elles sont écrites.

### Récupérer le code

```yaml
      - name: Checkout
        uses: actions/checkout@v4
```

- `-` commence une étape.
- `name` est son libellé visible dans les journaux.
- `uses` exécute l'action officielle qui récupère les fichiers du dépôt sur le runner.

### Installer Java

```yaml
      - name: Set up Java
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
```

- `name` nomme l'étape d'installation de Java.
- `uses` lance l'action officielle de configuration Java.
- `with` contient les paramètres transmis à cette action.
- `distribution: temurin` sélectionne la distribution Eclipse Temurin.
- `java-version: "17"` demande Java 17.

### Lire et vérifier la version

```yaml
      - name: Read application version
        id: metadata
        shell: pwsh
        run: |
          [xml]$pom = Get-Content -LiteralPath "pom.xml"
          $version = $pom.SelectSingleNode("/*[local-name()='project']/*[local-name()='version']").InnerText
          if ($env:GITHUB_REF_TYPE -eq "tag" -and $env:GITHUB_REF_NAME -ne "v$version") {
            throw "Release tag $env:GITHUB_REF_NAME must match the Maven version v$version."
          }
          "version=$version" >> $env:GITHUB_OUTPUT
```

- `name` est le nom de l'étape.
- `id: metadata` lui donne un identifiant utilisable par les autres étapes.
- `shell: pwsh` exécute les commandes dans PowerShell.
- `run: |` indique que les lignes indentées qui suivent forment un script PowerShell.
- `[xml]$pom = ...` lit `pom.xml` et le traite comme un document XML.
- `$version = ...` extrait la version du projet Maven.
- `if (...) {` vérifie la version uniquement lorsque le workflow a été déclenché par un tag, et ouvre le bloc de vérification.
- La condition compare le nom du tag à la version précédée de `v` ; le tag doit donc correspondre exactement à `v<version Maven>`.
- `throw ...` arrête le workflow avec un message explicite si le tag ne correspond pas.
- `}` termine le bloc de vérification.
- `"version=..." >> $env:GITHUB_OUTPUT` publie la version comme résultat de l'étape `metadata`, pour qu'elle puisse être réutilisée plus loin.

### Préparer le client OAuth

```yaml
      - name: Add OAuth client configuration
        shell: pwsh
        env:
          CLIENT_SECRET_JSON: ${{ secrets.CLIENT_SECRET_JSON }}
        run: |
          if ([string]::IsNullOrWhiteSpace($env:CLIENT_SECRET_JSON)) {
            throw "Configure the CLIENT_SECRET_JSON Actions secret before running this workflow."
          }
          $client = $env:CLIENT_SECRET_JSON | ConvertFrom-Json
          if (-not $client.installed -or -not $client.installed.client_id -or -not $client.installed.client_secret) {
            throw "CLIENT_SECRET_JSON must contain an OAuth desktop client's installed.client_id and installed.client_secret."
          }
          $destination = Join-Path $PWD "src/main/resources/client_secret.json"
          [System.IO.File]::WriteAllText(
            $destination,
            $env:CLIENT_SECRET_JSON,
            [System.Text.UTF8Encoding]::new($false)
          )
```

- `name` nomme l'étape qui prépare le fichier OAuth.
- `shell: pwsh` choisit PowerShell.
- `env` définit les variables d'environnement propres à cette étape.
- `CLIENT_SECRET_JSON: ${{ secrets.CLIENT_SECRET_JSON }}` transmet le secret enregistré dans les paramètres GitHub du dépôt ; sa valeur n'est pas écrite dans le fichier du workflow.
- `run: |` commence le script PowerShell.
- Le premier `if` vérifie que le secret existe et n'est pas vide.
- Le premier `throw` arrête le job si le secret manque.
- `$client = ... | ConvertFrom-Json` analyse le contenu du secret en JSON.
- Le second `if` vérifie que le JSON contient les champs requis pour un client OAuth Google de type application de bureau.
- Le second `throw` indique le format attendu lorsque ces champs manquent.
- `$destination = ...` construit le chemin du fichier OAuth temporaire dans les ressources du projet.
- `WriteAllText(` commence l'écriture du fichier.
- `$destination,` fournit le chemin où écrire.
- `$env:CLIENT_SECRET_JSON,` fournit le contenu du secret.
- `UTF8Encoding...` demande un encodage UTF-8 sans marque BOM.
- `)` termine l'appel d'écriture.

Le fichier créé sert au build et est supprimé dans une étape dédiée à la fin de chaque job de compilation. Attention : comme l'application l'embarque, le client OAuth est extractible des paquets distribués ; le secret GitHub ne rend pas le contenu des paquets confidentiel.

### Compiler et tester

```yaml
      - name: Build and test
        run: mvn --batch-mode clean package
```

- `name` nomme l'étape Maven.
- `run` exécute Maven en mode non interactif (`--batch-mode`), supprime les anciens fichiers de build (`clean`), puis compile, teste et assemble l'application (`package`).
- Si Maven retourne une erreur, GitHub marque le job comme échoué et n'exécute pas normalement les étapes suivantes de ce job.

## Étapes Windows

### Installer ou localiser WiX

```yaml
      - name: Install WiX Toolset
        if: matrix.platform == 'windows'
        shell: pwsh
        run: |
```

- `name` nomme l'étape.
- `if` limite cette étape à l'entrée Windows de la matrice.
- `shell` choisit PowerShell.
- `run: |` commence le script d'installation et de vérification.

Explication des lignes du script PowerShell :

- `$wixTools = ...` mémorise les noms des trois exécutables nécessaires pour fabriquer le MSI.
- `$missingTools = ...` cherche chaque exécutable avec `Get-Command` et conserve ceux que le runner ne trouve pas.
- `if ($missingTools.Count -gt 0) {` n'installe WiX que si au moins un outil manque.
- `choco install ...` installe WiX avec Chocolatey, sans afficher sa progression.
- `if ($LASTEXITCODE -ne 0) {` teste si l'installation a échoué.
- `throw ...` arrête le job et affiche une erreur si Chocolatey n'a pas réussi.
- Les deux `}` suivantes ferment les blocs des deux conditions précédentes.
- `$wixBin = ...` cherche `candle.exe` dans le `PATH` et récupère le dossier où il se trouve.
- `if (-not $wixBin) {` démarre une recherche de secours si le dossier n'a pas été trouvé.
- `Get-ChildItem ...` liste les dossiers WiX sous `C:\Program Files (x86)`.
- `ForEach-Object ...` transforme chacun de ces dossiers en chemin vers son sous-dossier `bin`.
- `Where-Object ...` ne garde que les dossiers `bin` contenant `candle.exe`.
- `Sort-Object -Descending` trie les chemins pour privilégier la version la plus récente selon leur nom.
- `Select-Object -First 1` sélectionne le premier chemin trouvé.
- `}` termine la recherche de secours.
- `if (-not $wixBin) {` vérifie que WiX a finalement été localisé.
- `throw ...` arrête le job si aucun dossier WiX n'est disponible.
- `}` ferme cette vérification.
- `$env:PATH = ...` rend les exécutables WiX accessibles dans l'étape courante.
- `$wixBin >> $env:GITHUB_PATH` demande à GitHub d'ajouter également ce dossier au `PATH` des étapes suivantes.
- `foreach ($tool in $wixTools) {` vérifie tour à tour les trois outils.
- `if (-not (Get-Command ...)) {` vérifie que l'outil courant peut être trouvé.
- `throw ...` arrête le job et nomme l'outil manquant.
- Les deux dernières accolades ferment les conditions et la boucle.

### Construire l'application portable Windows et le MSI

```yaml
      - name: Build Windows portable app and MSI
        if: matrix.platform == 'windows'
        shell: pwsh
        env:
          APP_VERSION: ${{ steps.metadata.outputs.version }}
          RUNNER_ARCH: ${{ runner.arch }}
        run: |
          .\package-windows.ps1 -SkipBuild
          $dist = Join-Path $PWD "target\dist"
          New-Item -ItemType Directory -Path $dist -Force | Out-Null
          $archive = "resume-evaluator-$env:APP_VERSION-windows-$($env:RUNNER_ARCH.ToLowerInvariant()).zip"
          Compress-Archive `
            -Path "target\windows-installer-build\ResumeEvaluator" `
            -DestinationPath (Join-Path $dist $archive) `
            -CompressionLevel Optimal
          Copy-Item "target\windows-installer\ResumeEvaluator-$env:APP_VERSION.msi" $dist
```

- `name` nomme l'étape de build Windows.
- `if` fait que le script ne s'exécute que sur Windows.
- `shell` indique que les commandes sont en PowerShell.
- `env` déclare les variables disponibles dans le script.
- `APP_VERSION` reçoit la sortie `version` de l'étape `metadata`.
- `RUNNER_ARCH` reçoit l'architecture du runner GitHub, par exemple `X64`.
- `run: |` commence le script PowerShell.
- `package-windows.ps1 -SkipBuild` fabrique l'image portable Windows et le MSI sans relancer Maven, déjà exécuté dans l'étape commune.
- `$dist = ...` définit le dossier de sortie `target\dist`.
- `New-Item ... -Force` crée le dossier de sortie s'il n'existe pas.
- `| Out-Null` évite d'afficher l'objet de création du dossier dans les journaux.
- `$archive = ...` construit le nom de l'archive avec la version et l'architecture en minuscules.
- `Compress-Archive` commence la création de l'archive ZIP portable.
- `-Path` choisit l'image complète de l'application à compresser, runtime Java inclus.
- `-DestinationPath` choisit le chemin et le nom du ZIP.
- `-CompressionLevel Optimal` demande une compression optimisée.
- `Copy-Item` copie le MSI généré dans le dossier de sortie.

## Étape Linux

```yaml
      - name: Build Linux portable app
        if: matrix.platform == 'linux'
        shell: bash
        env:
          APP_VERSION: ${{ steps.metadata.outputs.version }}
          RUNNER_ARCH: ${{ runner.arch }}
        run: |
```

- `name` nomme l'étape Linux.
- `if` l'exécute uniquement sur Ubuntu.
- `shell` choisit Bash.
- `env` fournit les variables de version et d'architecture.
- `APP_VERSION` récupère la version Maven produite par `metadata`.
- `RUNNER_ARCH` récupère l'architecture de la machine GitHub.
- `run: |` commence le script Bash.

Explication des lignes du script Bash :

- `rm -rf ...` retire les anciens dossiers d'entrée et de sortie pour éviter de réutiliser des fichiers périmés.
- `mkdir -p ...` crée les dossiers d'entrée, de sortie portable et de distribution ; `-p` accepte aussi les dossiers déjà présents.
- `arch=$(...)` crée la variable `arch` à partir de l'architecture du runner.
- `printf ... "$RUNNER_ARCH"` écrit le nom de l'architecture sans ajouter de retour à la ligne.
- `tr '[:upper:]' '[:lower:]'` convertit ce nom en minuscules.
- `cp ...` copie le JAR Maven dans le dossier d'entrée de `jpackage`.
- `jpackage \` commence la commande de création de l'application portable ; `\` indique que la commande continue à la ligne suivante.
- `--type app-image` demande une image d'application portable.
- `--name ResumeEvaluator` choisit le nom de l'application.
- `--app-version "$APP_VERSION"` indique sa version.
- `--vendor ...` indique son éditeur.
- `--input ...` désigne le dossier d'entrée contenant le JAR.
- `--main-jar ...` désigne le JAR à lancer.
- `--main-class ...` désigne la classe Java de démarrage.
- `--icon ...` désigne l'icône PNG de Linux.
- `--dest ...` désigne le dossier où `jpackage` crée l'application.
- `tar -czf ... \` crée une archive tar compressée avec gzip (`-z`) et indique le chemin du fichier à créer (`-f`).
- `-C target/portable ResumeEvaluator` demande à `tar` de se placer dans `target/portable`, puis d'ajouter le dossier de l'application à l'archive.

## Étape macOS

```yaml
      - name: Build macOS portable app
        if: matrix.platform == 'macos'
        shell: bash
        env:
          APP_VERSION: ${{ steps.metadata.outputs.version }}
          RUNNER_ARCH: ${{ runner.arch }}
        run: |
```

- `name` nomme l'étape macOS.
- `if` l'exécute uniquement sur macOS.
- `shell` choisit Bash.
- `env` fournit les variables de version et d'architecture.
- `APP_VERSION` récupère la version Maven.
- `RUNNER_ARCH` récupère l'architecture du runner.
- `run: |` commence le script Bash.

Explication des lignes du script Bash :

- `rm -rf ...` supprime les anciens dossiers d'entrée et de sortie.
- `mkdir -p ...` crée les dossiers d'entrée, de sortie et de distribution.
- `arch=$(...)`, `printf ...` et `tr ...` calculent le nom de l'architecture en minuscules.
- `cp ...` copie le JAR Maven dans le dossier d'entrée.
- `mkdir -p target/ResumeEvaluator.iconset` crée le dossier temporaire où macOS attend les différentes tailles de l'icône.
- `for size in ...; do` démarre une boucle sur les tailles d'icône indiquées.
- `sips -z "$size" "$size" ...` redimensionne l'image PNG à la taille courante.
- `--out ...icon_${size}x${size}.png` définit le chemin de l'image standard produite.
- `double_size=$((size * 2))` calcule le double de la taille courante.
- Le second `sips -z ...` crée l'image haute résolution deux fois plus grande.
- `--out ...icon_${size}x${size}@2x.png` donne à cette image le nom conventionnel `@2x`.
- `done` termine la boucle de création des icônes.
- `iconutil -c icns ...` convertit le dossier `.iconset` au format `.icns`.
- `-o target/ResumeEvaluator.icns` définit le nom du fichier icône macOS créé.
- `jpackage \` démarre la création de l'application portable macOS.
- Les options `--type`, `--name`, `--app-version`, `--vendor`, `--input`, `--main-jar`, `--main-class` et `--dest` ont le même rôle que pour Linux.
- `--icon target/ResumeEvaluator.icns` sélectionne l'icône macOS générée juste avant.
- `ditto -c -k ... \` démarre la création d'une archive ZIP tout en préservant les métadonnées macOS.
- `target/portable/ResumeEvaluator.app` est l'application à archiver.
- La dernière ligne donne le nom du ZIP, avec sa version et son architecture.

Les `\` à la fin de certaines lignes Bash indiquent que la commande se poursuit à la ligne suivante. `>/dev/null` masque la sortie normale d'une commande.

## Enregistrer les artefacts temporaires

```yaml
      - name: Upload packages
        uses: actions/upload-artifact@v4
        with:
          name: resume-evaluator-${{ steps.metadata.outputs.version }}-${{ matrix.platform }}-${{ runner.arch }}
          path: target/dist/*
          if-no-files-found: error
          retention-days: 30
```

- `name` nomme l'étape d'envoi.
- `uses` lance l'action officielle d'enregistrement d'artefacts.
- `with` contient les paramètres de l'action.
- `name` sous `with` choisit le nom de l'artefact, avec version, système et architecture.
- `path` sélectionne tous les paquets dans `target/dist`.
- `if-no-files-found: error` fait échouer le job si aucun paquet n'a été produit.
- `retention-days: 30` demande à GitHub de conserver l'artefact pendant 30 jours.

Les artefacts sont téléchargeables depuis la page de l'exécution du workflow. Ils ne sont pas encore la release GitHub : le job `publish` les réutilise pour la créer lorsque le workflow est lancé par un tag.

## Supprimer le fichier OAuth temporaire

```yaml
      - name: Remove OAuth client configuration
        if: always()
        shell: pwsh
        run: |
          $clientSecret = Join-Path $PWD "src/main/resources/client_secret.json"
          if (Test-Path -LiteralPath $clientSecret) {
            Remove-Item -LiteralPath $clientSecret -Force
          }
```

- `name` nomme l'étape de nettoyage.
- `if: always()` demande de l'exécuter même si une étape précédente du job a échoué.
- `shell` choisit PowerShell.
- `run: |` commence le script.
- `$clientSecret = ...` calcule le chemin du fichier OAuth temporaire.
- `Test-Path` vérifie si le fichier existe.
- `Remove-Item ... -Force` supprime le fichier s'il existe.
- Les accolades délimitent le bloc exécuté lorsque le fichier existe.

## Job de publication d'une release

```yaml
  publish:
    name: Publish release assets
    if: startsWith(github.ref, 'refs/tags/')
    needs: package
    runs-on: ubuntu-latest
    permissions:
      contents: write
```

- `publish` est l'identifiant du job de publication.
- `name` est son nom visible dans Actions.
- `if` limite ce job aux exécutions provenant d'un tag ; un lancement manuel ne publie donc pas de release.
- `needs: package` attend que tous les builds Windows, Linux et macOS soient terminés avec succès.
- `runs-on` choisit un runner Ubuntu pour publier.
- `permissions` définit les droits particuliers de ce job.
- `contents: write` autorise la création d'une release et l'envoi de ses fichiers.

### Télécharger les paquets produits par la matrice

```yaml
    steps:
      - name: Download all platform packages
        uses: actions/download-artifact@v4
        with:
          pattern: resume-evaluator-*
          path: release-assets
          merge-multiple: true
```

- `steps` commence la liste des étapes du job de publication.
- `name` nomme l'étape de téléchargement.
- `uses` lance l'action officielle de téléchargement des artefacts.
- `with` commence ses paramètres.
- `pattern` sélectionne tous les artefacts dont le nom commence par `resume-evaluator-`.
- `path` place les fichiers dans `release-assets`.
- `merge-multiple: true` met les fichiers de tous les systèmes dans un même dossier.

### Créer la release ou mettre à jour ses fichiers

```yaml
      - name: Create or update GitHub release
        env:
          GH_TOKEN: ${{ github.token }}
          GH_REPO: ${{ github.repository }}
          TAG_NAME: ${{ github.ref_name }}
        run: |
          if gh release view "$TAG_NAME" >/dev/null 2>&1; then
            gh release upload "$TAG_NAME" release-assets/* --clobber
          else
            gh release create "$TAG_NAME" release-assets/* \
              --title "Resume Evaluator $TAG_NAME" \
              --generate-notes
          fi
```

- `name` nomme l'étape de publication.
- `env` définit les variables utilisées par GitHub CLI.
- `GH_TOKEN` transmet le jeton de l'exécution GitHub, autorisé à écrire par `contents: write`.
- `GH_REPO` indique explicitement le dépôt cible au format `propriétaire/dépôt`.
- `TAG_NAME` récupère le nom du tag qui a déclenché le workflow.
- `run: |` commence les commandes Bash.
- `if gh release view ...` vérifie si une release existe déjà pour ce tag ; `>/dev/null 2>&1` masque la sortie de vérification.
- `gh release upload ... --clobber` ajoute ou remplace les fichiers de la release existante.
- `else` indique quoi faire si aucune release n'existe.
- `gh release create ...` crée une release et lui attache tous les fichiers téléchargés.
- `--title` définit le titre visible de la release.
- `--generate-notes` demande à GitHub de générer les notes de version.
- `fi` termine le bloc conditionnel.

## Comment le lancer

### Lancement manuel

1. Vérifie que le workflow et les changements voulus sont poussés sur GitHub.
2. Vérifie que le secret de dépôt `CLIENT_SECRET_JSON` est configuré.
3. Ouvre l'onglet **Actions**, puis **Build portable apps and Windows MSI**.
4. Clique sur **Run workflow**, sélectionne la branche, puis confirme.
5. Une fois les jobs terminés, ouvre l'exécution et télécharge les fichiers sous **Artifacts**.

Le lancement manuel ne crée pas de release, car il ne provient pas d'un tag.

### Publication d'une release

Si la version dans `pom.xml` est `1.0.0`, le tag doit être exactement `v1.0.0`. Dans PowerShell :

```powershell
git tag v1.0.0
git push origin v1.0.0
```

Le workflow vérifie la correspondance du tag avec la version Maven, construit les trois paquets, puis crée ou met à jour la release GitHub. Consulte **Actions** pour suivre les étapes ; les fichiers publiés se trouvent ensuite dans **Releases**, dans la section **Assets** de la release.
