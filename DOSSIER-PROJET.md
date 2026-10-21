# 36FileManager — dossier de projet complet

> **Document de reprise.** Il contient tout ce qu'il faut pour continuer le
> développement sur un autre compte Claude, une autre machine ou après plusieurs mois
> d'interruption, sans avoir accès à l'historique de la conversation d'origine.
>
> Dernière mise à jour : **17 septembre 2026** — version livrée **2.5.0 (versionCode 20)**.
>
> ⚠️ **Depuis la 2.5.0, chaque livraison exige une entrée dans `data/Changelog.kt`** : une
> fenêtre présente les nouveautés au premier lancement de chaque version (§ 14).
>
> La 2.5.0 ajoute la **recherche dans l'arborescence** — dans l'application et dans la
> page web, avec un seul moteur (`FileSearch`) pour les deux —, le **déplacement depuis
> la page web**, et une tuile **Téléchargements** à l'accueil.
>
> La 2.4.0 reprend le **dépôt de fichiers depuis le PC** de bout en bout : file d'attente
> à deux envois de front, envoi d'un dossier entier avec son arborescence, doublons
> traités avant l'envoi, bouton « Relancer », et le processeur tenu éveillé le temps
> d'une requête — écran éteint, les transferts tombaient en « Connexion perdue ».
>
> La 2.3.1 corrige la 2.2.0 : le renommage et la suppression étaient **fermés par
> défaut**, donc invisibles dans la page web, et passaient pour absents. Le réglage est
> désormais ouvert (§ 12.8), et les deux actions ont leur place fixe : sur la ligne quand
> un seul élément est coché, dans la barre du haut dès qu'il y en a plusieurs.
>
> La 2.3.0 ouvre la page web sur **tous les volumes** — stockage interne, carte SD, clé
> USB — avec une vue « Stockage » et **l'espace libre affiché en permanence**. Lire
> **§ 12.2 barrière 6** : le confinement des chemins raisonne désormais sur plusieurs
> racines.
>
> La 2.2.0 avait ajouté deux choses : **renommer et supprimer depuis la page web** (sous
> un réglage fermé par défaut) et **l'effacement du journal des transferts**. Elle repose
> sur le durcissement de la 2.1.0 : lire la **section 12** avant de toucher à
> `HttpShareServer`, `ShareRepository` ou `NetworkUtil` — chaque garde-fou y est
> justifié par l'attaque qu'il ferme, et les deux nouvelles routes sont les seules
> **irréversibles** du serveur (§ 12.8).

---

## 1. En un coup d'œil

| | |
|---|---|
| **Nom** | 36FileManager |
| **Quoi** | Gestionnaire de fichiers Android complet, en français |
| **Package** | `com.filemanager36.app` |
| **Langage** | Kotlin 2.0.21 + Jetpack Compose (Material 3) |
| **minSdk / targetSdk / compileSdk** | 26 (Android 8.0) / 35 / 35 |
| **Build** | AGP 8.7.2, Gradle 8.14.3, Java 17 (compilé avec un JDK 21) |
| **Taille** | ~14 700 lignes de Kotlin, 49 fichiers source |
| **APK** | debug ~23,6 Mo · release ~16,7 Mo |
| **État** | Fonctionnel, testé sur appareil réel à chaque version |
| **Livrable** | Sources zippées + APK debug **et** release signés, à chaque itération |

**Toute l'interface et tous les commentaires du code sont en français.** C'est une
exigence du projet, pas un accident — la conserver.

---

## 2. Comment reprendre le projet

### 2.1 Ce dont vous avez besoin
1. L'archive `36FileManager-v2.5.0-sources.zip` (ou toute archive plus récente).
2. Un SDK Android (API 35 + build-tools 35) et un **JDK 17 à 21**.
3. Ce document.

> **Le JDK compte.** Le projet cible Java 17 (`compileOptions`), mais c'est le JDK qui
> exécute Gradle qui doit être compatible avec Gradle 8.14.3 et AGP 8.7.2 : un JDK 17,
> 20 ou **21** convient. Un JDK 25 — celui que livrent les versions récentes d'Android
> Studio dans `jbr/` — **n'est pas supporté par Gradle 8.14.3**. Sous Windows, Android
> Studio range en général un JDK 21 utilisable dans `%USERPROFILE%\.jdks\jbr-21.x`.

### 2.2 Remise en route

```bash
unzip 36FileManager-v2.5.0-sources.zip -d 36FileManager
cd 36FileManager

# local.properties est volontairement EXCLU de l'archive : à recréer.
echo "sdk.dir=/chemin/vers/Android/Sdk" > local.properties

export ANDROID_HOME=/chemin/vers/Android/Sdk
./gradlew assembleDebug assembleRelease
# → app/build/outputs/apk/debug/app-debug.apk
# → app/build/outputs/apk/release/app-release.apk
```

Sous Windows (PowerShell ou Git Bash), en désignant explicitement le JDK :

```bash
printf 'sdk.dir=C:/chemin/vers/Android/Sdk\n' > local.properties
JAVA_HOME="$HOME/.jdks/jbr-21.0.11" ./gradlew assembleDebug assembleRelease
```

> Les chemins de `local.properties` s'écrivent **avec des barres obliques** (`C:/…`) :
> c'est un fichier `.properties`, où l'antislash est un caractère d'échappement.

Dans un environnement Claude cloud, le SDK est en général dans `/root/android-sdk`, et
**`$HOME` vaut `/root` alors que le répertoire de travail est `/home/claude`** — une
confusion qui a déjà fait croire deux fois que le SDK avait disparu. `local.properties`
doit être recréé à chaque nouvelle session.

Une compilation propre prend environ **2 minutes**, une compilation incrémentale
**20 à 50 secondes**. Les tests JVM (`./gradlew testDebugUnitTest`) ajoutent ~1 minute.

### 2.3 Vérifier ce qu'on livre

```bash
APKSIGNER=$ANDROID_HOME/build-tools/35.0.0/apksigner
$APKSIGNER verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
# Attendu : v2 = true, v3 = true, SHA-256 ec15f53f…5813   (voir section 6)

AAPT2=$ANDROID_HOME/build-tools/35.0.0/aapt2
$AAPT2 dump badging app/build/outputs/apk/release/app-release.apk | head -1
# Attendu : versionCode strictement supérieur à celui de la version précédente
```

### 2.4 Message d'amorçage à donner à un nouvel assistant
> Je reprends le développement de 36FileManager, une application Android de gestion de
> fichiers écrite en Kotlin + Jetpack Compose. Les sources sont dans l'archive jointe et
> le dossier de projet complet est dans `DOSSIER-PROJET.md` — lis-le en entier avant
> toute chose, en particulier « Pièges Android », « Signature » et « Sécurité du partage
> réseau ». Toute l'interface et tous les commentaires sont en français, garde cette
> convention. Avant chaque livraison, fais relire le diff par un sous-agent de revue de
> code, et incrémente `versionCode` **et** `versionName`.

---

## 3. Cahier des charges d'origine

Demande initiale de l'utilisateur, reformulée fidèlement :

- Naviguer dans le stockage interne **et** les autres stockages (carte SD, clé USB-C…).
- Sur les **fichiers** : compresser, renommer, déplacer, informations, copier, couper,
  d'un dossier vers un autre.
- Les mêmes opérations sur les **dossiers et tout leur contenu**.
- **Sélection multiple** pour toutes ces actions, et **« Tout sélectionner »** dans le
  dossier courant.
- **Tri** par date d'ajout, taille de fichier, nom.
- **Dossier sécurisé** avec code ou biométrie, accessible depuis un espace titré sur
  l'accueil, avec une option « déplacer vers cet espace » sur fichiers et dossiers.
- **Favoris** (fichiers ou dossiers) dans un espace titré sur l'accueil : de vrais
  raccourcis, ni déplacement ni copie.
- **Afficher ou masquer les fichiers cachés** dans tout le stockage.
- **Partage** via WhatsApp et autres.
- **Mini-aperçus** (icône à côté du nom) pour images, vidéos, documents.
- **Lecteurs réseau LAN** : récupérer des fichiers, en envoyer, et enregistrer le
  lecteur en favori pour les fois suivantes.

Réponses de l'utilisateur aux questions de cadrage : sources **et** APK debug compilé ;
**SMB/CIFS uniquement** ; coffre **chiffré AES-256** ; **minSdk 26** ; accès cloud par
**SAF** (pas d'OAuth).

### Demandes ajoutées après tests sur appareil, dans l'ordre
1. Un espace Favoris dédié regroupant tous les favoris ; les actions de sélection
   déplacées dans le menu « ⋮ » ; accès Google Drive / OneDrive ; formulaire de connexion
   réseau adapté à l'OS du serveur.
2. Supprimer la barre d'outils du bas (doublon + conflit avec la navigation gestuelle) ;
   supprimer l'ancien aperçu des favoris sur l'accueil ; ajouter les catégories Images /
   Vidéos / Documents ; **confirmer que l'application atteint bien tous les fichiers**.
3. Lecteurs réseau : ne saisir que l'IP, puis identifiant et mot de passe Windows, puis
   voir **tous** les dossiers partagés — avec les instructions Windows en parallèle.
4. Réparer l'impossibilité de revenir en arrière depuis un écran d'erreur.
5. Ajouter une catégorie Musique ; deux modes d'affichage dans les catégories (en vrac ou
   par dossier) ; balayage entre fichiers dans la visionneuse.
6. Proposer la retouche d'image via les outils du téléphone ; si un lecteur vidéo/PDF
   intégré est trop complexe, passer la main à une application tierce.
7. Après plantages : **supprimer la lecture multimédia intégrée** au profit
   d'applications tierces (VLC cité en exemple).
8. « Je ne peux pas ouvrir certains fichiers dans l'application. Exemple : .apk. »

---

## 4. Architecture du code

```
app/src/main/java/com/filemanager36/app/
├─ MainActivity.kt              145  hôte Compose, snackbar, dialogues globaux
├─ FmApplication.kt             100  Application : dépôt de partage + rapport de plantage
├─ ShareService.kt              157  service de premier plan du partage réseau
├─ data/
│  ├─ Models.kt                 195  FileItem, volumes, favoris, serveurs, coffre…
│  ├─ Prefs.kt                  166  réglages, favoris, serveurs, crypto, identité
│  ├─ StorageRepo.kt            177  détection des volumes, listage trié, fil d'Ariane
│  ├─ FileOperations.kt         406  copier/déplacer/supprimer/renommer/ZIP/infos
│  ├─ FileSearch.kt             140  recherche par nom, partagée app + page web
│  ├─ Changelog.kt              200  ⚠️ nouveautés par version — une entrée à CHAQUE livraison
│  ├─ VaultCrypto.kt            166  PBKDF2, AES-GCM, AES-CBC, HMAC, clé biométrique
│  ├─ VaultRepository.kt        287  index chiffré, import/export du coffre
│  ├─ SmbRepository.kt          343  SMB/CIFS + énumération des partages
│  ├─ CloudRepository.kt        262  SAF / DocumentsContract
│  ├─ MediaRepository.kt        267  catégories transversales (MediaStore + parcours)
│  ├─ ServerGuides.kt           152  aide de connexion par système d'exploitation
│  ├─ ShareModels.kt            133  réglages, pairs, transferts, propositions
│  ├─ ShareRepository.kt        737  ⭐ orchestration du partage réseau
│  ├─ HttpShareServer.kt       1166  ⭐ serveur HTTP embarqué, écrit à la main
│  └─ MultipartReader.kt        237  lecture multipart en flux (dépôt depuis le PC)
├─ ui/
│  ├─ FmViewModel.kt           1852  ⭐ TOUT l'état de l'application
│  ├─ theme/Theme.kt             56  Material 3 clair/sombre + couleurs dynamiques
│  ├─ components/
│  │  ├─ Common.kt              323  lignes de fichier, vignettes, barre de progression
│  │  ├─ Dialogs.kt             730  saisie, confirmation, détails, sélecteur de dossier,
│  │  │                              formulaire serveur, écran d'accès au stockage
│  │  ├─ OpenDialogs.kt         349  fiche APK, archive ZIP, « Ouvrir en tant que… »
│  │  └─ SelectionActions.kt    159  actions du menu « ⋮ » sur une sélection
│  └─ screens/
│     ├─ HomeScreen.kt          660  accueil : volumes, coffre, favoris, catégories…
│     ├─ BrowserScreen.kt       467  navigateur de fichiers
│     ├─ CategoryScreen.kt      452  Images / Vidéos / Musique / Documents
│     ├─ FavoritesScreen.kt     237
│     ├─ VaultScreen.kt         420
│     ├─ NetworkScreen.kt       450  assistant SMB en deux étapes + navigation
│     ├─ CloudScreen.kt         301
│     ├─ SearchScreen.kt         250  recherche dans les sous-dossiers ou tout le téléphone
│     ├─ ShareScreen.kt         787  partage réseau : QR, code, pairs, transferts
│     └─ ViewerScreen.kt        803  visionneuse à balayage (image, PDF, texte, remise)
├─ util/
│  ├─ FileKind.kt               112  types de fichiers, icônes, couleurs, isText/isZip
│  ├─ ShareUtil.kt              295  types MIME, ouverture, partage, retouche
│  ├─ ApkUtil.kt                159  lecture et installation d'un .apk
│  ├─ Thumbnails.kt             206  miniatures maison + cache LRU 12 Mo
│  ├─ NetworkUtil.kt            111  adresse locale, diffusion, nom d'appareil
│  ├─ QrCode.kt                  46  QR code de l'adresse de partage (zxing core)
│  ├─ SafeName.kt                43  🔒 assainissement des noms venus du réseau
│  ├─ SecretStore.kt             96  🔒 chiffrement Keystore des mots de passe SMB
│  ├─ Drawables.kt               28  conversion d'icône système en bitmap
│  ├─ Formatters.kt              28  tailles et dates
│  ├─ Permissions.kt             43  accès au stockage
│  └─ BiometricHelper.kt         49  BiometricPrompt
└─ assets/share/index.html      707  page web servie au navigateur du PC
                                     (volumes, espace disque, transferts)

app/src/test/java/…/MultipartReaderTest.kt  144  tests JVM du lecteur multipart
app/src/test/java/…/SafeNameTest.kt          92  tests JVM de l'assainissement des noms
app/src/test/java/…/FileSearchTest.kt       130  tests JVM du moteur de recherche
app/src/test/java/…/ChangelogTest.kt        150  échoue si la version construite n'a pas ses nouveautés
```

### Principes structurants
- **Aucune bibliothèque de navigation.** L'écran courant est une simple énumération
  `Screen { HOME, BROWSER, VAULT, NETWORK, FAVORITES, CLOUD, CATEGORY, SHARE, SEARCH }` dans le
  ViewModel ; `MainActivity` fait un `when (vm.screen)`. La visionneuse est une
  **superposition** (`if (vm.viewerOpen) ViewerOverlay(vm)`), ce qui préserve la pile.
- **Un seul ViewModel** (`FmViewModel`, `AndroidViewModel`). Tout l'état y est en
  `mutableStateOf`. C'est volontaire : l'application est très transversale (une action de
  sélection dans le navigateur peut viser le coffre, le réseau ou le cloud).
  - ⚠️ Le fichier fait 1852 lignes et contient **deux blocs `init`**. Le second, qui
    branche le partage réseau, est le **tout dernier bloc de la classe** : Kotlin exécute
    initialiseurs et blocs `init` dans l'ordre du source, et `wireShare()` affecte des
    propriétés déclarées très bas. Le placer en fin de classe rend cette contrainte vraie
    par construction — **ne rien ajouter après lui.**
- **Le partage réseau vit dans l'`Application`, pas dans le ViewModel** : le serveur doit
  survivre à la fermeture de l'écran, et `ShareService` doit désigner la même instance.
  Le ViewModel ne fait qu'en refléter l'état, via cinq rappels qu'il **détache dans
  `onCleared()`** — sans quoi il fuit jusqu'à la création du suivant.
- **Aucune injection de dépendances**, aucune base de données. Les préférences sont dans
  `SharedPreferences`, sérialisées en JSON par `kotlinx.serialization`.
- **Aucune bibliothèque d'images** (pas de Coil) : les miniatures sont produites à la
  main et mises en cache dans un `LruCache` de 12 Mo.
- Les opérations longues passent toutes par `FmViewModel.runOperation()`, qui gère le
  verrou `operationRunning`, la barre de progression limitée à ~20 images/s, le
  `try/catch/finally`, puis `clearSelection()` et `refreshCurrentView()`.

---

## 5. Dépendances

```kotlin
// app/build.gradle.kts
val composeBom = platform("androidx.compose:compose-bom:2024.10.01")

androidx.core:core-ktx:1.13.1
androidx.activity:activity-compose:1.9.3
androidx.fragment:fragment-ktx:1.8.5          // MainActivity étend FragmentActivity
androidx.lifecycle:lifecycle-{runtime-ktx,viewmodel-compose,runtime-compose}:2.8.7
androidx.compose.{ui,material3,material:material-icons-extended}
androidx.documentfile:documentfile:1.0.1
androidx.biometric:biometric:1.1.0            // impose FragmentActivity
org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1

// Réseau LAN (lecteurs SMB)
com.hierynomus:smbj:0.13.0
org.slf4j:slf4j-nop:1.7.36
org.bouncycastle:bcprov-jdk18on:1.78.1
com.rapid7.client:dcerpc:0.12.0 { exclude bcprov-jdk15on }

// QR code du partage réseau. « core » est du Java pur, sans AWT ni Android :
// c'est le module qu'utilise zxing-android-embedded sous le capot.
com.google.zxing:core:3.5.3

coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

// Tests JVM
testImplementation("junit:junit:4.13.2")
```

**Aucune bibliothèque de serveur HTTP.** `HttpShareServer` est écrit à la main sur
`ServerSocket`. Ce n'est pas de l'orgueil : les bibliothèques légères du marché mettent
le corps des requêtes en mémoire tampon, ce qui interdit de recevoir un fichier de 2 Go
depuis le PC. Ici tout est diffusé en flux, dans les deux sens. La contrepartie est que
**la sécurité de ce serveur est entièrement à notre charge** — d'où la section 12.

**Deux exclusions sont indispensables**, sinon `dex` échoue sur des classes dupliquées :

```kotlin
// bcprov-jdk15on contient les mêmes classes que bcprov-jdk18on
implementation("com.rapid7.client:dcerpc:0.12.0") {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
}

// guava 18 (tiré par dcerpc) contient déjà ListenableFuture, que l'artefact
// listenablefuture (tiré par AndroidX) dupliquerait
configurations.all {
    exclude(group = "com.google.guava", module = "listenablefuture")
}
```

`buildFeatures { compose = true; buildConfig = true }` — `buildConfig` sert à afficher le
numéro de version sur l'accueil.

---

## 6. Signature de l'APK ⚠️

**C'est le point le plus important de ce document pour la continuité.**

`app/build.gradle.kts` déclare une `signingConfig` nommée **« projet »**, appliquée aux
compilations *debug* **et** *release* :

```
clé          : keystore/36filemanager-dev.jks       (HORS dépôt, ignorée par git)
identifiants : keystore.properties                  (HORS dépôt, ignoré par git)
sujet        : CN=36FileManager, OU=Dev, O=36FileManager, C=FR
validité     : 10 950 jours
SHA-256      : ec15f53fdc8cb032dff9cb1c1d72e58b26bdc953b101a3116659f00d87345813
schémas      : v2 + v3 (AGP ignore v1 au-delà de minSdk 24)
```

La clé et ses identifiants vivent **hors du dépôt** — ignorés par git — et **hors des
zips de sources**. Avec la publication sur GitHub, c'est elle qui garantit qu'une mise à
jour vient bien du projet : quiconque la détiendrait pourrait publier une fausse mise à
jour qu'Android installerait par-dessus celle des utilisateurs. Sans `keystore.properties`,
la compilation reste possible : *debug* signé par la clé de débogage de la machine,
*release* non signé.

**La sauvegarder hors du poste.** Une clé perdue ne se reconstruit pas, ni depuis un APK
signé ni depuis son mot de passe — 36Note l'a appris — et plus aucune mise à jour ne
s'installerait par-dessus les versions publiées.

### Pourquoi
Par défaut, Gradle signe les compilations *debug* avec `~/.android/debug.keystore`,
propre à la machine qui compile. L'environnement de travail étant éphémère, sa
disparition suffit à produire un APK signé différemment — et Android refuse alors de
l'installer par-dessus le précédent :

> « L'application n'a pas été installée, car le package est en conflit avec un package
> déjà présent. »

C'est exactement l'erreur rencontrée le 28/08/2026 en installant la v1.9.

### Règles
1. **Ne jamais changer cette clé** sans nécessité absolue : tout changement impose à
   l'utilisateur une désinstallation complète.
2. **Avant toute désinstallation, prévenir explicitement** : le coffre-fort vit dans
   l'espace privé de l'application et **est détruit** avec elle. Il faut d'abord
   restaurer son contenu vers le stockage normal depuis l'application. Les favoris,
   serveurs réseau et réglages sont aussi perdus (SharedPreferences).
3. **Toujours incrémenter `versionCode` en plus de `versionName`.**
4. Devant un refus d'installation, **vérifier avant de conclure** :
   ```bash
   apksigner verify --verbose --print-certs ancien.apk nouveau.apk
   aapt2 dump xmltree --file AndroidManifest.xml nouveau.apk
   ```
   Sur ce projet, la vérification a montré que 1.8 et 1.9 avaient le **même**
   certificat — le conflit venait donc de l'appareil (application clonée, profil
   professionnel, ou données résiduelles), pas de l'APK.

---

## 7. Modèle de données (`data/Models.kt`)

```kotlin
data class FileItem(path, name, isDirectory, size, lastModified, isHidden, childCount = -1) {
    val file: File get() = File(path)
    val extension: String
    companion object { fun from(f: File): FileItem }
}

enum class SortField(val label: String)      // NAME, SIZE, DATE
enum class ViewMode { LIST, GRID }
enum class ServerOs(val label: String)       // WINDOWS, LINUX, ANDROID, NAS

data class StorageVolumeInfo(...)   // volume détecté : chemin, libellé, type, capacité
data class FavoriteEntry(...)       // raccourci pur : chemin + nom + isDirectory
data class FavStat(...)             // métadonnées relevées en arrière-plan
data class SmbServer(...)           // IP, OS, identifiants, port, dernier partage ouvert
data class SmbShare(...)            // partage publié par la machine
data class RemoteItem(...)          // entrée distante SMB
data class CloudRoot(...)           // dossier cloud rattaché par SAF
data class CloudItem(...)           // entrée dans un espace cloud
data class VaultEntry(...)          // entrée chiffrée du coffre
data class Clipboard(...)           // presse-papiers copier/couper
data class OpProgress(...)          // état de la barre de progression
data class FileDetails(...)         // fiche d'informations d'un fichier
```

`MediaRepository` ajoute :
```kotlin
enum class MediaCategory(val label: String)  // IMAGES, VIDEOS, MUSIQUE, DOCUMENTS
data class CategoryFolder(...)               // dossier regroupant les fichiers d'une catégorie
```

---

## 8. Le ViewModel (`ui/FmViewModel.kt`, 1852 lignes)

Point d'entrée unique de toute l'application. Organisé en sections commentées :

| Section | État principal | Actions notables |
|---|---|---|
| Général | `screen`, `hasStorageAccess`, `volumes`, `favorites`, `servers`, `progress`, `message`, `detailsTarget` | `refreshAccess()`, `goHome()`, `goToScreen()` |
| Réglages | `sortField`, `sortAscending`, `showHidden`, `viewMode`, `foldersFirst`, `showThumbnails` | `setSort()`, `toggleHidden()`, `toggleViewMode()`, `toggleThumbnails()` |
| Navigateur | `currentPath`, `entries`, `selected`, `clipboard`, `searchQuery`, `visibleEntries` | `openPath()`, `reload()`, `navigateUp()`, `toggleSelect()`, `selectAll()` |
| Opérations | — | `copySelection()`, `cutSelection()`, `paste()`, `deleteSelection()`, `rename()`, `createFolder()`, `compressSelection()`, `extractSelection()`, `extractArchive()`, `showDetails()`, `shareSelection()` |
| Ouverture | `apkTarget`, `archiveTarget`, `openAsTarget` | `openFile()`, `openExternally()` |
| Favoris | `favoriteStats` | `toggleFavorite()`, `addSelectionToFavorites()`, `openFavorite()`, `removeBrokenFavorites()` |
| Coffre | `vaultEntries`, `vaultSelected`, `vaultUnlocked`, `vaultConfigured`, `pendingVaultImport` | `setupVault()`, `unlockVault()`, `moveSelectionToVault()`, `restoreVaultSelection()`, `openVaultEntry()` |
| Réseau | `smbServerInUse`, `smbPath`, `smbEntries`, `smbShares`, `smbShareOpen`, `smbError`, `smbLastAttempt` | `connectServer()`, `openSmbShare()`, `backToSmbShares()`, `downloadSmbSelection()`, `uploadToSmb()` |
| Cloud | `cloudRoots`, `cloudRootInUse`, `cloudEntries`, `cloudSelected` | `addCloudRoot()`, `openCloudRoot()`, `downloadCloudSelection()`, `uploadToCloud()`, `importCloudUris()` |
| Catégories | `categoryInUse`, `categoryCache`, `categoryGrouped`, `categoryFolder` | `scanCategories()`, `openCategory()`, `toggleCategoryGrouped()` |
| Visionneuse | `viewerItems`, `viewerIndex`, `viewerOpen` | `openViewer()`, `updateViewerIndex()`, `closeViewer()` |
| Partage réseau | `shareState`, `shareUrl`, `shareCode`, `shareQr`, `shareConfig`, `sharePeers`, `shareTransfers`, `shareIncomingOffer`, `shareWebClients` | `toggleShare()`, `refreshShare()`, `renewShareCode()`, `updateShareConfig()`, `scanPeers()`, `answerShareOffer()`, `sendFilesToPeer()`, `openShareInbox()`, `clearShareTransfers()` |

### Le routage d'ouverture, cœur de la v1.9
```kotlin
fun openFile(context, item, siblings = emptyList()) {
    when {
        item.isDirectory -> openPath(item.path)
        FileKinds.of(item.name, false) == FileKind.APK -> apkTarget = item
        FileKinds.isZip(item.name) -> archiveTarget = item
        FileKinds.prefersExternalApp(item.name) &&
            ShareUtil.hasHandler(context, item.file) -> openExternally(context, item)
        isViewable(item) -> openViewer(if (siblings.isEmpty()) listOf(item) else siblings, item)
        else -> openExternally(context, item)
    }
}
```
`isViewable` = IMAGE, PDF, ou `FileKinds.isText(name)`.
`prefersExternalApp` = html, htm, csv, tsv, md, markdown.

### Le garde-fou des opérations longues
```kotlin
private fun runOperation(title: String, block: suspend ((String, Int, Int) -> Unit) -> Unit) {
    if (operationRunning) { message = "Une opération est déjà en cours"; return }
    operationRunning = true
    viewModelScope.launch {
        progress = OpProgress(title, indeterminate = true)
        try { withContext(Dispatchers.IO) { block { name, done, total -> /* limité à 50 ms */ } } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = "$title : ${e.message ?: "échec"}" }
        finally { operationRunning = false; progress = null; clearSelection(); refreshCurrentView() }
    }
}
```

---

## 9. Fonctionnalités, dans le détail

### 9.1 Navigation et volumes
`StorageRepo.volumes()` combine `StorageManager.storageVolumes` et un balayage de repli
de `/storage`, `/mnt/media_rw`, `/mnt/usb` — indispensable pour les clés USB-C que
`StorageManager` n'expose pas toujours. Une entrée « Racine du système » (`/`) est
ajoutée en lecture seule.

Fil d'Ariane cliquable, affichage liste ou grille, recherche dans le dossier courant,
tri par nom / taille / date avec ordre inversable et dossiers en premier.

### 9.2 Opérations sur fichiers et dossiers
`FileOperations` : copier, déplacer, supprimer, renommer, compresser en ZIP, extraire,
informations détaillées, nouveau dossier.

Protections en place, **à ne jamais retirer** :
- `isSymlink()` et `MAX_DEPTH = 32` sur delete / copy / zip / count / details ;
- `uniqueTarget()` anti-écrasement (`photo.jpg` → `photo (1).jpg`) ;
- protection Zip-Slip à l'extraction (comparaison de chemin canonique **plus** séparateur) ;
- refus de déplacer un dossier dans lui-même.

### 9.3 Coffre-fort
- Code ou mot de passe, plus déverrouillage biométrique facultatif.
- Clé de données aléatoire (DEK) chiffrant le contenu en **AES-256-CBC en flux**.
- DEK emballée par une clé dérivée du code : **PBKDF2-HMAC-SHA256, 120 000 itérations**.
- Option biométrie : DEK également scellée par une clé AndroidKeystore
  `setUserAuthenticationRequired(true)` + `BiometricPrompt.CryptoObject`.
- Index du coffre chiffré en **AES-GCM**. Le code n'est **jamais** stocké.
- Les dossiers sont compressés puis chiffrés.
- Depuis la 2.1.0, le contenu est aussi **authentifié** : chaque fichier chiffré porte un
  `HMAC-SHA256` couvrant l'IV et le texte chiffré, vérifié à la restauration. AES-CBC
  chiffre mais ne signe pas : une altération produisait auparavant un fichier corrompu
  que rien ne distinguait d'un fichier valide.

> **Compatibilité des coffres existants.** `VaultEntry.format` vaut `1` pour les entrées
> écrites jusqu'à la 2.0.2 (`IV || AES-CBC`) et `2` depuis la 2.1.0
> (`IV || AES-CBC || HMAC`). Le champ a une valeur par défaut : une entrée ancienne se
> relit en format 1 sans rien à ressaisir ni à restaurer. **Ne jamais retirer le
> traitement du format 1** — ce serait rendre inaccessibles des coffres déjà remplis.

> Les fichiers du coffre vivent dans l'espace privé de l'application : **désinstaller
> l'application les détruit**.

### 9.4 Favoris
Raccourcis purs, ni copiés ni déplacés. Un seul espace, atteint par la tuile de
l'accueil, séparé en *Dossiers* et *Fichiers*. Les raccourcis cassés sont signalés en
rouge et purgeables. Les métadonnées sont relevées en arrière-plan pour qu'un favori sur
une carte SD retirée ne fige pas l'écran.

### 9.4 bis Recherche (2.5.0)

**Deux recherches à ne pas confondre.** Le filtre de la barre du navigateur
(`searchQuery`) ne regarde que les enfants directs du dossier ouvert, instantanément.
La **recherche** (`SearchScreen`, `FileSearch`) descend dans tous les sous-dossiers,
d'un dossier ou de tout le téléphone. Dès qu'une saisie existe dans le filtre, un
bandeau propose « Chercher « … » dans les sous-dossiers » avec la même saisie.

**Un seul moteur pour l'application et la page web** : `data/FileSearch.kt`, sans
dépendance Android, couvert par `FileSearchTest`. Deux implémentations auraient divergé
au premier accent ou au premier dossier caché.

- **Accents et majuscules ignorés** (normalisation NFD) : « ete » trouve « Été 2025 ».
- **Tous les mots, dans n'importe quel ordre** : « facture 2025 » trouve
  « 2025_Facture_EDF.pdf ».
- **Parcours en largeur** : les résultats proches de la racine arrivent d'abord, au lieu
  du trois-centième fichier d'un cache de miniatures.
- Liens symboliques listés mais **jamais traversés** ; dossiers cachés ignorés sauf si
  l'affichage des fichiers cachés est activé ; racine « / » exclue de « tout le
  téléphone » — elle ferait repasser par `/sdcard`, `/storage` et `/mnt`.
- Application : 1 000 résultats au plus, affichés au fil du parcours par paquets de
  200 ms, relance après 350 ms sans frappe. Page web : 500 résultats et 10 s au plus.

**Retour aux résultats.** Ouvrir un dossier trouvé, ou « ouvrir l'emplacement » d'un
fichier (élément présélectionné), mémorise `searchEntryPath` : un retour arrière depuis
ce dossier ramène aux résultats au lieu de remonter l'arborescence.

### 9.5 Catégories transversales
Images, Vidéos, Musique, Documents. `MediaRepository.scan()` **fusionne par chemin** une
requête `MediaStore.Files` ciblée et un parcours réel des volumes — le parcours rattrape
les fichiers jamais indexés, ce que MediaStore seul ne fait pas.

Deux modes d'affichage mémorisés : *tous les fichiers* (en vrac) ou *par dossier* (avec
nombre d'éléments, poids total, chemin et vignette du plus récent).
Le balayage est annulable et ne repart pas de lui-même après chaque opération.

**Tuile Téléchargements (2.5.0).** Sous les quatre catégories, sur toute la largeur :
ce n'est pas un regroupement par type mais le vrai dossier `Download`, celui où arrive
tout ce qui vient d'un navigateur, d'une messagerie ou du partage réseau. Une cinquième
tuile seule sur sa rangée aurait eu l'air oubliée. Le nombre d'éléments est recompté à
chaque retour à l'accueil (`refreshDownloads`, sur `Dispatchers.IO`).

### 9.6 Visionneuse (`ViewerScreen.kt`)
`HorizontalPager` ; un balayage horizontal passe au fichier suivant du dossier ou de la
catégorie. Barre supérieure escamotable d'un appui.

| Type | Page |
|---|---|
| Image | `ImagePage` — zoom par pincement, double-appui, déplacement borné |
| PDF | `PdfPage` — première page + nombre total, puis ouverture externe |
| Texte | `TextPage` — `LazyColumn` ligne par ligne, chasse fixe |
| Vidéo / audio | `MediaHandoffPage` — vignette + rangée des applications capables de lire |
| APK | `ExternalFallback` + action « Installer l'application » |
| ZIP | `ExternalFallback` + action « Extraire ici » |
| Autre | `UnsupportedPage` |

### 9.7 Ouverture des fichiers (v1.9)
- **APK** : `ApkUtil.read()` produit nom, icône, version, identifiant de paquet, niveau
  d'Android minimal et version déjà installée. `ApkInstallDialog` affiche la fiche puis
  installe. Nécessite `REQUEST_INSTALL_PACKAGES` **et** le réglage utilisateur
  « Installer des applications inconnues », demandé par un bouton **Autoriser** et
  revérifié sur `ON_RESUME`.
- **ZIP** : extraction intégrée dans un dossier créé à côté. Une archive vide, chiffrée
  ou mal nommée est signalée au lieu d'annoncer un faux succès.
- **Texte** : visionneuse intégrée, lecture bornée à 256 Kio, 20 000 lignes maximum,
  lignes découpées à 400 caractères.
- **Type indéterminé** : boîte « Ouvrir en tant que… » (toutes les applications, texte,
  image, vidéo, audio).

### 9.8 Lecteurs réseau LAN (SMB/CIFS)
Connexion en deux étapes, sans jamais avoir à connaître le nom d'un partage :
1. **Adresse IP** + choix du système (Windows, Linux, Android, NAS), qui adapte l'aide,
   le domaine et le port — Android ne peut pas ouvrir le 445, 4455 est proposé.
2. **Identifiant et mot de passe**, ou connexion invité.

L'application interroge alors la machine et **liste tous ses dossiers partagés**, comme
l'Explorateur Windows face à `\\192.168.1.20`. Techniquement : `NetrShareEnum` du service
MS-SRVS sur le tube nommé `IPC$` (bibliothèque rapid7 dcerpc au-dessus de smbj). Les
partages d'impression et `IPC$` sont écartés ; les partages administratifs (`C$`,
`ADMIN$`) sont listés et signalés comme tels.

Ensuite : navigation, création de dossier, renommage, suppression côté serveur,
récupération de fichiers ou de dossiers entiers, envoi d'un dossier local ou de la
sélection courante. Les échecs sont traduits en clair (identifiants refusés, pas de
réponse, partage de fichiers désactivé, compte sans mot de passe…), avec un écran
d'erreur **actionnable** — « Réessayer » et « Accueil » — et l'aide correspondant au
système choisi (`data/ServerGuides.kt`).

Délai de connexion TCP porté à **12 s** via un `ProxySocketFactory`.

### 9.9 Espaces cloud
Storage Access Framework via `DocumentsContract`, aucun compte développeur, aucune clé
d'API. « Connecter » rattache un dossier avec autorisation persistante. Repli
« Importer des fichiers » (fichier par fichier) pour les fournisseurs — Google Drive en
tête — qui refusent le rattachement d'un dossier entier.

### 9.10 Portée réelle de l'accès aux fichiers
Un écran « Accès aux fichiers » dresse le bilan volume par volume.

| Zone | Accès |
|---|---|
| Stockage interne partagé | lecture + écriture |
| Carte SD, clé USB / OTG | lecture + écriture |
| Racine `/` | lecture seule |
| `Android/data`, `Android/obb` | **bloqués par Android 11+**, pour toutes les applications |
| `/data/data` | **impossible sans root** |
| `/system`, `/vendor` | lecture seule |
| Profil professionnel | invisible, cloisonné par Android |

Les trois lignes en gras ne sont pas des limites de l'application : aucune application
non rootée ne peut les franchir depuis Android 11.

### 9.11 Partage réseau (v2.0) — le téléphone devient un serveur

Trois usages sous un seul écran (`ShareScreen`), un seul interrupteur :

**a) Depuis un ordinateur, par le navigateur.** Le téléphone ouvre un serveur HTTP sur
`http://<ip>:8036`. On tape l'adresse dans Edge ou Firefox — ou on scanne le QR code —,
on saisit le **code à six chiffres** affiché sur le téléphone, et on obtient une page
web : navigation, vignettes, téléchargement fichier par fichier ou dossier entier en ZIP
produit à la volée, dépôt par glisser-déposer, création de dossier. Aucun logiciel à
installer côté PC.

**Le dépôt depuis le PC, revu en 2.4.0.** Tout passe par une **file d'attente** : deux
envois avancent de front, les autres attendent leur tour en le disant. La version
précédente lançait toutes les requêtes d'un coup — un navigateur n'ouvre que six
connexions, si bien que le reste restait affiché « 0 % », sans progression, sans erreur
et sans explication. Chaque ligne garde son objet `File` : un envoi qui échoue se
**relance** d'un bouton, individuellement ou tous d'un coup.

Un **dossier entier** s'envoie, arborescence comprise, par le bouton « Envoyer un
dossier » ou par glisser-déposer. Chaque fichier voyage avec son chemin relatif
(`rel=photos/2026/img.jpg`), que le téléphone recrée après l'avoir assaini segment par
segment (`SafeName.relative`, couvert par des tests JVM).

Les **doublons sont réglés avant l'envoi**, par `/api/precheck` : le navigateur annonce
le chemin et la taille, le téléphone répond `absent`, `same`, `different` ou `directory`.
Même nom et même taille : le fichier est ignoré, rien ne repart sur le réseau et rien
n'est écrasé. Tailles différentes : une boîte propose *garder celui du téléphone*,
*garder les deux* ou *remplacer*, avec « appliquer à tous les fichiers suivants » pour ne
pas répondre deux cents fois. Un remplacement écrit d'abord un fichier temporaire et ne
renomme qu'une fois complet : une coupure en cours de route ne détruit pas la version du
téléphone.

**Recherche et déplacement depuis le PC (2.5.0).** Un champ de recherche en tête de page
cherche dans le dossier affiché et tous ses sous-dossiers — ou partout depuis la vue
Stockage — avec le moteur de l'application (`/api/search`). Chaque résultat indique où
il se trouve, et reste manipulable : télécharger, renommer, supprimer, déplacer.
**Déplacer** ouvre un choix de dossier navigable, volumes compris ; « Déplacer ici » se
grise en disant pourquoi (dossier déplacé en lui-même, éléments déjà sur place). Le
travail est confié à `FileOperations.copyOrMove`, celui de l'application : rien n'est
écrasé (« fichier (1).txt »), et entre deux volumes la source n'est supprimée qu'après
une copie réussie.

Depuis la 2.3.0, la page s'ouvre sur une vue **« Stockage »** listant les volumes —
stockage interne, carte SD, clé USB — avec pour chacun l'espace libre, l'espace utilisé
et une barre de remplissage (orange à 85 %, rouge à 95 %). Les mêmes volumes sont
rappelés **en permanence dans l'en-tête**, sur toutes les pages : un appui sur l'un d'eux
y bascule directement, et le fil d'Ariane commence par « Stockage ». Un seul volume — un
dossier a été choisi dans les réglages — et la page entre dedans comme avant : une vue à
une seule carte serait un clic pour rien.

L'espace disque n'est **pas** affiché sur l'écran de code : il est calculé par
`/api/list`, qui exige une session. Un visiteur non authentifié n'apprend rien de la
capacité du téléphone.

Depuis la 2.2.0, la page sait aussi **renommer et supprimer**, tant que le réglage
`ShareConfig.allowManage` — *Autoriser le renommage et la suppression* — est ouvert sur
le téléphone ; il l'est par défaut depuis la 2.3.1. Les deux actions ont une place fixe :
**sur la ligne** quand un seul élément est coché, **dans la barre du haut** dès qu'il y en
a plusieurs, avec le nombre d'éléments visés. Au-delà d'un élément coché, les boutons de
ligne s'effacent — deux cibles concurrentes, l'une agissant sur la ligne et l'autre sur la
sélection, prêtaient à confusion. Renommer plusieurs éléments enchaîne les invites, une
par élément ; « Annuler » arrête la série sans défaire ce qui est déjà renommé. Le pouvoir accordé n'est pas du même ordre que le
dépôt — un dépôt ajoute un fichier et n'en écrase jamais aucun (`uniqueTarget`), une
suppression est définitive et sans corbeille — d'où un réglage distinct et non un
élargissement de `allowUpload`. Détail des barrières en **§ 12.8**.

**b) Entre deux téléphones équipés de l'application.** Découverte par diffusion UDP sur
le port 8037, puis proposition explicite : l'expéditeur annonce la liste des fichiers,
le destinataire voit une boîte de dialogue **et une notification**, et accepte ou refuse.
Rien ne part avant l'acceptation.

**c) Réception.** Les fichiers reçus atterrissent dans *Téléchargements / 36FileManager*
(dossier réglable), atteignable d'un appui depuis l'écran de partage.

Le journal des transferts affiché sous l'écran de partage s'efface d'un appui (bouton
« Effacer », 2.2.0). `ShareRepository.clearTransfers()` ne retire que les lignes
**terminées** : `updateTransfer` abandonne silencieusement une progression dont
l'identifiant a disparu de la table, si bien qu'un transfert effacé en pleine course
continuerait sans jamais réapparaître ni signaler sa fin. Le journal reste par ailleurs
borné à 60 entrées, comme avant.

Détails d'implémentation qui comptent :

- `HttpShareServer` est écrit à la main sur `ServerSocket`, **tout en flux** dans les
  deux sens : un film de 2 Go passe sans jamais tenir en mémoire. `MultipartReader` fait
  la même chose en réception — c'est la partie la plus délicate du projet, et la seule
  couverte par des **tests JVM** (`MultipartReaderTest`).
- `ShareService`, service de premier plan, empêche Android de suspendre le processus au
  milieu d'un transfert, et tient un `WifiLock` : sans lui, le Wi-Fi passe en veille
  quelques minutes après l'extinction de l'écran et le téléphone devient injoignable
  alors que le partage s'affiche toujours actif.
- Le serveur se **relie tout seul** quand l'adresse change (bail DHCP, reconnexion
  Wi-Fi), et se suspend proprement quand le réseau disparaît, avec reprise au retour.
- Balayer l'application hors des tâches récentes **coupe le partage** : un serveur ouvert
  sur le réseau à l'insu de l'utilisateur n'est pas acceptable.

> ⚠️ Tout ce qui touche à ce serveur relève de la **section 12**. Ne rien y modifier sans
> l'avoir lue.

---

## 10. Pièges Android rencontrés — à ne pas réintroduire

Ce catalogue est le fruit de neuf itérations sur appareil réel. Chaque ligne a coûté un
bogue.

**Rendu et Compose**
- `PdfRenderer.Page.render()` **n'accepte que `Bitmap.Config.ARGB_8888`** ; toute autre
  valeur lève « Unsupported pixel format ». Une « optimisation » en RGB_565 avait fait
  échouer 100 % des aperçus PDF.
- `Modifier.transformable` **consomme tous les glissements** par défaut : sans
  `canPan = { scale > seuil }`, un pager parent ne reçoit jamais le balayage.
- Une `SurfaceView` ne suit pas les animations d'un pager (image noire) → `texture_view`.
- Avant l'API 28, pdfium n'est pas sérialisé côté système → verrou `PdfLock` partagé.
- `OutOfMemoryError` est une `Error`, pas une `Exception` : `catch (Exception)` ne
  l'attrape pas. Utiliser `catch (Throwable)` autour des rendus.
- **Un seul `Text` contenant un fichier entier fige la mise en page** (ANR) et peut
  dépasser la limite de `Constraints` (2¹⁸−1 px). Rendre ligne par ligne en `LazyColumn`
  **et** découper les lignes très longues (JSON minifié, `.min.js`).
- Dans un pager, utiliser `pagerState.settledPage` pour l'état persisté et
  `pagerState.currentPage` pour les actions de la barre supérieure : sinon les actions
  portent sur le fichier précédent pendant l'animation de calage.

**Intentions et visibilité des paquets**
- `queryIntentActivities` sans `MATCH_DEFAULT_ONLY` renvoie des activités non exportées ;
  les lancer par `setClassName` lève `SecurityException`. Préférer `setPackage`.
- Sur Android 11+, les `<queries>` doivent inclure les variantes **avec schéma**
  (`content`, `file`), sinon les lecteurs dont le filtre est qualifié restent invisibles.
- **`*/*` comme type d'intention correspond à *toutes* les applications** déclarant un
  type quelconque : `queryIntentActivities` ne renvoie jamais vide, donc ce type ne peut
  pas servir de test « une application sait-elle ouvrir ce fichier ? ».
- **Ne pas pré-tester un écran de réglages avec `resolveActivity`** : le filtrage de
  visibilité d'Android 11+ peut le déclarer absent alors qu'il s'ouvre. Lancer et
  rattraper `ActivityNotFoundException`.
- `Settings.ACTION_SECURITY_SETTINGS` **ne contient plus** « sources inconnues » depuis
  Android 8 : y envoyer l'utilisateur le fait tourner en rond.
- `getPackageInfo()` échoue sur Android 11+ pour un paquet masqué par le filtrage de
  visibilité ; un `<queries>` MAIN/LAUNCHER suffit pour les applications lançables.
- Installer un APK exige `REQUEST_INSTALL_PACKAGES` **et** `canRequestPackageInstalls()`.
- **Partager un fichier avec le type `text/plain`** : WhatsApp et Messages le traitent
  comme du texte à coller, lisent `EXTRA_TEXT` (vide) et ignorent la pièce jointe.
  Partager en `*/*`.
- Pour lire nom et icône d'un APK non installé, forcer
  `applicationInfo.sourceDir = publicSourceDir = chemin` avant `loadLabel`/`loadIcon`,
  sinon on n'obtient que le nom du paquet. `loadLabel` **ne lève pas d'exception** : elle
  retombe silencieusement sur le nom de la classe Application puis sur le paquet.

**Réseau et fils d'exécution**
- Toute fermeture SMB (`closeShare`, `disconnect`) doit être sur `Dispatchers.IO` : une
  `NetworkOnMainThreadException` avalée par un `runCatching` laisse un arbre en cache
  mais mort, et le partage devient définitivement inutilisable.
- `ExoPlayer.setWakeMode(WAKE_MODE_LOCAL)` **exige la permission `WAKE_LOCK`**, sinon
  `SecurityException` au démarrage de la lecture. (media3 a depuis été retiré.)

**Navigateur : le dépôt de fichiers (leçons de la 2.4.0)**
- **`dataTransfer.files` est vide quand on dépose un dossier** : il ne contient que le
  dossier lui-même, sans contenu. Il faut passer par `dataTransfer.items[i].webkitGetAsEntry()`
  et parcourir l'arborescence. C'était tout le « rien ne se passe » du dépôt de dossier.
- **`DirectoryReader.readEntries` ne rend qu'une centaine d'entrées par appel** et il faut
  le rappeler jusqu'au lot vide. Un seul appel livrait cent fichiers sur mille, sans la
  moindre erreur.
- **Un navigateur n'ouvre que six connexions par origine.** Lancer cent requêtes d'un coup
  n'en fait pas avancer plus : les autres attendent, et l'interface les montre « 0 % »
  comme si elles étaient en cours. C'est une file d'attente explicite qu'il faut, pas une
  boucle d'envoi.
- `Content-Length` porte sur la requête **entière**, enveloppe multipart comprise : s'en
  servir comme taille de fichier fausse l'affichage et empêche la barre d'atteindre la
  fin. Le navigateur connaît la vraie taille, il la passe en paramètre.
- `querySelector(".top span")` attrape le **premier** span dans l'ordre du document : le
  chemin relatif affiché sous le nom recevait le pourcentage à la place du libellé
  d'état. Viser une classe dédiée, jamais une position.

**Navigateur : navigation asynchrone (leçon de la 2.5.0)**
- **Une réponse lente peut recouvrir une page ouverte depuis.** Une recherche web prend
  jusqu'à dix secondes ; l'utilisateur a le temps d'ouvrir un autre dossier, et la
  réponse arrivait ensuite par-dessus. Chaque chargement porte un numéro (`navSeq`), et
  une réponse périmée est ignorée.

**Compilation**
- Un **KDoc contenant `*/*`** ferme le commentaire et casse la compilation.
- Un `import ... as Alias` sur une **propriété d'extension** (`Icons.Filled.Image`) ne
  produit pas une valeur utilisable seule.
- Une fonction `setXxx()` entre en collision de signature JVM avec le mutateur d'une
  propriété `xxx` du même objet.
- **L'ordre du source compte dans une classe Kotlin.** Initialiseurs de propriétés et
  blocs `init` s'exécutent de haut en bas : appeler depuis un `init` une méthode qui
  affecte une propriété `by mutableStateOf` déclarée plus bas donne une
  `NullPointerException` dans le constructeur. Le ViewModel se fermait instantanément au
  lancement, sans rien afficher. D'où le second bloc `init` en **toute fin** de
  `FmViewModel`.
- **Un paramètre ajouté après une lambda casse les appels existants sans erreur claire.**
  Ajouter `depth: Int = 0` après `onProgress: (String) -> Unit` fait que la lambda finale
  des appelants désigne soudain `depth` : le message du compilateur parle d'un `Int`
  attendu, pas du vrai problème. Placer les nouveaux paramètres **avant** la lambda.
- **Les icônes `Icons.Filled.ArrowBack`, `Send`, `Sort`, `OpenInNew`… sont obsolètes** au
  profit de `Icons.AutoMirrored.Filled.*`, qui se retournent en écriture droite-à-gauche.
  Le manifeste déclare `supportsRtl="true"` : utiliser les versions non miroir est une
  vraie régression, pas seulement un avertissement.

**Réseau et serveur embarqué (leçons de la 2.0)**
- `ThreadPoolExecutor` ne dépasse `corePoolSize` **qu'une fois la file pleine**. Avec un
  noyau de 4 et une file de 16, le cinquième fil n'apparaissait jamais — un navigateur
  n'ouvre que six connexions — et une vidéo en lecture bloquait toute la page. D'où
  `corePoolSize = maximumPoolSize = 12`.
- Une lecture bloquée sur une socket **n'est pas interruptible** : `shutdownNow()` ne
  suffit pas, il faut fermer explicitement chaque socket ouverte, sinon un
  téléchargement continue après l'arrêt annoncé du partage.
- Une réponse de taille inconnue (archive ZIP produite à la volée) exige
  `Transfer-Encoding: chunked`. En cas d'échec en cours de route, **ne pas clore le flux
  proprement** : une archive tronquée mais bien terminée est indiscernable d'une archive
  complète.
- `Content-Length` se lit en `Long` : un corps de 2 Gio ou plus déborde un `Int`, donne
  `null`, et la requête passe pour vide au lieu d'être refusée.
- Le microprogramme Wi-Fi filtre les trames de diffusion écran éteint : sans
  `MulticastLock`, la découverte des autres téléphones paraît capricieuse. Et sans
  `WifiLock`, le Wi-Fi passe en veille quelques minutes après l'extinction de l'écran —
  le téléphone devient injoignable alors que le partage s'affiche actif.
- **Le service de premier plan n'empêche pas la mise en veille du processeur.** Écran
  éteint, Android suspend le processeur entre deux paquets : les fils du serveur cessent
  de tourner, les lectures expirent et le PC affiche « Connexion perdue » sur un transfert
  qui se portait bien. Il faut un `PARTIAL_WAKE_LOCK` — tenu seulement pendant qu'une
  requête est servie, jamais en permanence — **en plus** du `WifiLock`.
- **Un `soTimeout` unique ne convient pas.** Vingt secondes sont justes pour des en-têtes,
  ridicules pour un corps de deux gigaoctets sur un Wi-Fi en économie d'énergie. Le délai
  est relevé après la lecture des en-têtes, pas avant : c'est l'attente sur une connexion
  muette qu'il faut borner, pas celle sur un transfert qui avance.
- `startForeground()` doit être appelé **avant toute autre décision** dans
  `onStartCommand`, y compris avant un `stopSelf()` : le service est lancé par
  `startForegroundService()`, et Android tue le processus avec
  `ForegroundServiceDidNotStartInTimeException` s'il n'est jamais passé au premier plan.
- Depuis une classe interne, un appel non qualifié cherche d'abord un membre de la classe
  interne. Une méthode nommée `available()` dans `MultipartReader` était masquée par
  `InputStream.available()` (qui renvoie 0) : le lecteur croyait le tampon éternellement
  vide et **tout fichier de plus de 128 Kio figeait le transfert**. Renommée `pending()`,
  et appelée en `this@MultipartReader.pending()`.

---

## 11. Historique des versions

| Version | Contenu |
|---|---|
| **1.0** | Première livraison : navigation, opérations, coffre, favoris, partage, miniatures, SMB |
| **1.1** | Espace Favoris dédié, toutes les actions dans le menu « ⋮ », cloud SAF, formulaire réseau par OS |
| **1.2** | Suppression de la barre du bas, catégories Images/Vidéos/Documents, volume « / », écran d'accès au stockage |
| **1.3** | Énumération des partages SMB (`NetrShareEnum`), assistant de connexion en 2 étapes |
| **1.4** | Diagnostic réseau, écran d'erreur actionnable |
| **1.5** | Numéro de version affiché sur l'accueil |
| **1.6** | Catégorie Musique, deux modes d'affichage, visionneuse à balayage |
| **1.7** | Balayage image réparé (`canPan`), bouton Modifier (`ACTION_EDIT`), aperçu PDF rapide |
| **1.8** | Suppression du lecteur multimédia intégré, remise aux applications tierces avec rangée d'applications en un appui |
| **1.9** | Installation des APK, extraction ZIP au toucher, visionneuse texte, « Ouvrir en tant que… », types MIME corrigés |
| **1.9.1** | Clé de signature permanente du projet (sortie du dépôt depuis : `keystore.properties`) |
| **2.0** | **Partage réseau** : serveur HTTP embarqué + page web, QR code, code d'accès, transferts entre appareils Android, service de premier plan |
| **2.0.1** | Corrections du partage : reliaison au changement d'adresse, quotas de réception, plafonds de connexions |
| **2.0.2** | Corrections du partage : requêtes de plage HTTP, lecteur multipart en UTF-8, propositions acceptées non expirées en cours de transfert |
| **2.1.0** | **Durcissement.** Revue de sécurité complète du partage réseau (section 12), authentification du contenu du coffre, chiffrement des mots de passe SMB, correction de la découverte entre appareils identiques, ménage. Aucune fonctionnalité nouvelle. |
| **2.2.0** | **Renommage et suppression depuis la page web**, sous un réglage fermé par défaut (§ 12.8) ; effacement du journal des transferts dans l'application ; changer une autorisation ne redémarre plus le serveur — seuls le port, la racine et l'exigence de code le font, les autres réglages étant relus à chaque requête |
| **2.3.0** | **Vue « Stockage » dans la page web** : tous les volumes (interne, carte SD, clé USB) au lieu du seul stockage interne, et **espace libre affiché en permanence** sur toutes les pages. Le réglage « Dossier visible depuis le PC » redevient réinitialisable |
| **2.3.1** | **Correction de la 2.2.0** : le renommage et la suppression, fermés par défaut, n'apparaissaient nulle part dans la page web. Réglage ouvert par défaut avec migration des installations existantes ; boutons sur la ligne à un élément coché, dans la barre du haut au-delà ; renommage en série sur une sélection ; autorisations relues à chaque listage |
| **2.4.0** | **Dépôt depuis le PC repris de bout en bout** : file d'attente à deux envois de front (les suivants restaient à « 0 % »), envoi d'un dossier entier avec son arborescence, doublons réglés avant l'envoi (ignoré si identique, choix si différent), bouton « Relancer », processeur tenu éveillé pendant chaque requête et délai d'attente séparé pour les corps — écran éteint, les transferts tombaient en « Connexion perdue » |
| **2.5.0** | **Recherche** dans l'arborescence, dans l'application (écran dédié, portée dossier ou téléphone, retour aux résultats) et dans la page web, avec un moteur commun testé ; **déplacement** depuis la page web ; tuile **Téléchargements** à l'accueil ; fenêtre des **nouveautés** au premier lancement de chaque version |

### Décision structurante : pas de lecteur multimédia intégré
Un lecteur ExoPlayer (media3) avait été ajouté en v1.6 puis retiré en v1.8 après deux
rapports de plantage. **Cause racine** : `setWakeMode(C.WAKE_MODE_LOCAL)` sans la
permission `WAKE_LOCK`. La permission aurait suffi, mais l'utilisateur avait explicitement
proposé le repli vers une application tierce. Choix retenu : **supprimer media3** (−2 Mo
d'APK, toute une classe de risques éliminée) et confier la lecture à VLC / MX Player / au
lecteur du constructeur.

---

## 12. Sécurité du partage réseau ⚠️

**À lire en entier avant de modifier `HttpShareServer`, `ShareRepository`, `NetworkUtil`
ou `assets/share/index.html`.**

Le partage réseau de la 2.0 ouvre un serveur HTTP sur le téléphone. C'est, de loin, la
plus grande surface d'attaque du projet : tout le reste de l'application n'agit que sur
demande de l'utilisateur, ce serveur répond à qui frappe à la porte. La 2.1.0 est née
d'une revue complète de ce code. Chaque garde-fou ci-dessous ferme une attaque précise —
**aucun n'est décoratif, aucun ne doit être retiré « pour simplifier ».**

### 12.1 Le modèle de menace, en une phrase

Un attaquant est soit **sur le même réseau local** (Wi-Fi public, invité, colocation),
soit **une page web** que l'utilisateur consulte pendant que le partage tourne. Il n'a
pas l'appareil en main. Ce qu'il ne doit jamais pouvoir faire : lire le stockage, y
écrire, ou faire exécuter du code dans l'origine du serveur.

### 12.2 Les huit barrières, dans l'ordre où une requête les rencontre

**1. Liaison sur une interface locale uniquement** — `NetworkUtil.localAddress()`,
`ShareRepository.start()`.
Le serveur ne s'ouvre que sur Wi-Fi, point d'accès ou Ethernet USB, et seulement sur une
adresse privée. Les interfaces de données mobiles (`rmnet`, `ccmni`, `pdp`, `clat`) et de
tunnel (`tun`, `wg`, `ipsec`) sont **exclues**, et `onLocalNetwork()` exige le transport
Wi-Fi ou Ethernet. Jusqu'à la 2.0.2 elles étaient seulement reléguées en dernier choix :
Wi-Fi coupé et données mobiles actives, le serveur se liait à l'interface de l'opérateur.
Chez ceux qui attribuent une adresse routable, le téléphone devenait joignable **depuis
Internet**.

**2. Refus des pairs non locaux** — `isLocalPeer()`.
Une connexion venant d'une adresse publique n'obtient rien, même si l'appareil se
retrouve joignable autrement. Deuxième clé du même verrou que la barrière 1.

**3. `Host` doit être une adresse IP littérale** — `route()`, `isIpLiteral()`.
Ferme la redécouverte DNS (*DNS rebinding*) : un site fait pointer son domaine vers
l'adresse locale du téléphone et obtient ainsi une origine identique. Un navigateur à qui
l'on a tapé `http://192.168.x.y:8036` envoie toujours une IP littérale.

**4. Contrôle d'origine** — `originAllowed()`.
Ferme la falsification de requête entre sites (CSRF). La barrière 3 ne suffit pas : une
page hostile peut viser l'IP directement, et le navigateur enverra alors le bon `Host`.
On vérifie `Sec-Fetch-Site`, puis `Origin`, puis `Referer`. Une requête **sans aucun de
ces en-têtes** est acceptée : c'est un client non navigateur — l'application elle-même,
un gestionnaire de téléchargement — qu'une page web ne peut pas piloter, ce qui est
précisément la menace visée. Sans ce contrôle, n'importe quel site consulté pendant que
le partage tournait pouvait déposer des fichiers sur le téléphone.

**5. Code d'accès et session** — `/api/login`, `tokenValid()`.
Six chiffres tirés d'un `SecureRandom` (et non de `kotlin.random.Random`, prédictible),
comparés à **temps constant**, six essais avant blocage d'une minute par adresse, une
demi-seconde de pause à chaque échec. Le jeton de session **expire au bout de 24 h côté
serveur** — le cookie l'annonçait déjà, le serveur ne le tenait pas — et il est posé en
`SameSite=Strict; HttpOnly`.

**6. Confinement des chemins** — `resolve()`, `rootDirs()`, `under()`.
`?path=../../data/data` : le chemin demandé est résolu en forme canonique et doit rester
sous **l'une des racines partagées**, séparateur compris — sinon `/sdcard/Photos2`
passerait pour un enfant de `/sdcard/Photos`. Depuis la 2.3.0 il y a plusieurs racines
quand aucun dossier n'est choisi dans les réglages : un volume par entrée de
`StorageRepo.volumes()` — stockage interne, carte SD, clé USB. **La racine « / » du
système en est exclue**, et elle seule : elle donnerait tout le téléphone, et elle expose
`/sdcard` en lien symbolique — le chemin par lequel, en 1.2, une suppression aurait
effacé tout le stockage interne. Choisir un dossier dans les réglages ramène à une racine
unique, exactement comme avant : c'est la façon de restreindre — et ce dossier reste la
racine **même s'il n'existe plus** (carte SD retirée), sans quoi le partage repasserait
tout seul sur l'ensemble des volumes.

**7. En-têtes de réponse et `inline` restreint** — `sendHeaders()`, `isSafeToDisplay()`,
`sendAsset()`.
`X-Content-Type-Options: nosniff` sur **toutes** les réponses ; « ouvrir dans le
navigateur » n'est accordé qu'aux images, vidéos, audio, PDF et texte brut ;
`image/svg+xml` en est **exclu** malgré son préfixe, un SVG étant un document XML qui
peut contenir du JavaScript ; la page elle-même est servie sous une
`Content-Security-Policy` fermée. Sans cela, un `.html` déposé sur le téléphone puis
ouvert depuis la page s'exécutait **dans l'origine du serveur**, lisait le cookie de
session et pouvait parcourir puis exfiltrer tout le stockage partagé.

**8. Bornes de ressources** — un peu partout.
12 fils, 24 connexions simultanées, 8 par adresse ; en-têtes plafonnés à 32 Kio et
80 lignes ; frontière multipart ≤ 200 caractères (RFC 2046 en autorise 70) ; quota de
réception **réservé de façon atomique** avant la première écriture.

Les délais d'attente sont **deux** depuis la 2.4.0 : 20 s sur les en-têtes, 180 s sur un
corps ou une réponse. C'est la première qui compte contre une connexion ouverte pour ne
rien dire (« Slowloris ») ; la seconde n'est atteinte que par un transfert déjà engagé,
et vingt secondes n'y suffisaient pas écran éteint, quand le Wi-Fi passe en économie
d'énergie et fait des pauses de plusieurs dizaines de secondes.

### 12.3 Les transferts entre appareils Android

`/api/push/offer`, `/api/push/state` et `/api/push/send` ne passent **pas** par le code
d'accès : leur sécurité vient de l'acceptation explicite de l'utilisateur qui reçoit.
Quatre vérifications rendent cette acceptation contraignante, et non un blanc-seing :

1. la proposition doit exister et être **acceptée** ;
2. l'envoi doit venir de **la même adresse** que la proposition ;
3. le nom du fichier doit figurer dans **la liste montrée à l'utilisateur** ;
4. le volume est **réservé atomiquement** (`ShareOffer.reserve`) avant toute écriture — un
   simple « vérifier puis incrémenter » laissait passer autant de requêtes qu'il y a de
   fils, et une seule acceptation autorisait douze fois le volume annoncé.

Une proposition dont tous les fichiers sont arrivés est **fermée** : elle ne doit pas
rester un droit d'écriture dormant.

> `/api/hello` a été **supprimé en 2.1.0** : point d'entrée sans authentification, qui
> annonçait le modèle du téléphone à quiconque sondait le port, et qu'aucun appelant
> n'utilisait — la découverte passe par la diffusion UDP.

### 12.4 Assainissement des noms venus de l'extérieur

`SafeName.of()` — et **rien d'autre** — transforme un nom reçu du réseau en nom de
fichier. Il retire les deux séparateurs (un serveur Windows renvoie des antislashs),
refuse `.` et `..`, supprime les caractères de contrôle et tronque à 200 **octets** : la
limite des systèmes de fichiers porte sur les octets, et « é » en compte deux.

Il est appelé depuis quatre endroits :

- le dépôt depuis le navigateur (`HttpShareServer.upload`) ;
- la réception entre appareils (`pushSend`) ;
- le **téléchargement SMB récursif** (`SmbRepository.downloadRecursive`) ;
- la **copie depuis le cloud** (`CloudRepository.downloadRecursive`, `importUri`).

Les deux derniers ne l'avaient pas jusqu'à la 2.1.0 : un serveur SMB hostile, ou un
fournisseur SAF renvoyant `..`, faisait écrire hors du dossier choisi par l'utilisateur.
Ces deux chemins sont aussi bornés en profondeur, contre un partage qui se contient
lui-même.

### 12.5 Ce qui n'est délibérément **pas** protégé

- **Pas de HTTPS.** Le trafic circule en clair sur le réseau local. Un certificat
  auto-signé imposerait un avertissement de sécurité à chaque connexion, ce qui
  apprendrait à l'utilisateur à passer outre les avertissements — un remède pire que le
  mal. `usesCleartextTraffic="true"` est là pour cette raison, assumée.
- **Un pair du réseau local peut faire apparaître une proposition** sur l'écran. C'est le
  principe même de la fonction ; la file est bornée à quatre propositions en attente pour
  que ce ne soit pas une nuisance.

### 12.6 Autres points de sécurité, hors partage

- **Mots de passe SMB** (`SecretStore`) : chiffrés en AES-GCM par une clé de
  l'AndroidKeystore, plus jamais en clair dans les préférences. Les entrées héritées sont
  relues telles quelles puis ré-écrites chiffrées au premier enregistrement, sans rien à
  ressaisir. Le repli en clair si le Keystore est indisponible est assumé : sur quelques
  surcouches il refuse de générer une clé, et perdre le mot de passe du lecteur réseau
  serait une régression bien réelle pour un gain hypothétique.
- **Coffre-fort** : voir 9.3 — contenu désormais authentifié par HMAC-SHA256, avec
  compatibilité des coffres existants par `VaultEntry.format`.
- **Extraction ZIP** (`FileOperations.unzip`) : Zip-Slip, **plus** un plafond de volume
  produit (16 Go), un plafond d'entrées (100 000) et un contrôle de l'espace libre.
  42 Kio de zéros compressés donnent 4 Go : sans ces bornes, une archive minuscule
  remplissait le stockage jusqu'à l'échec du système.
- **Liens symboliques** : `isSymlink()` et `MAX_DEPTH = 32` sur delete / copy / zip /
  count / details. C'est ce qui empêche d'effacer tout le stockage interne en croyant
  supprimer `/sdcard`. **Ne jamais retirer.**

### 12.7 Ce qui a été vérifié et n'a rien donné

La revue de la 2.1.0 a aussi cherché ce qui n'existait pas, et il faut le noter pour ne
pas refaire le travail : **aucun code malveillant, aucune obfuscation, aucune télémétrie,
aucune adresse en dur vers un serveur tiers, aucun chargement de code dynamique.** Les
onze dépendances viennent toutes de Maven Central ou du dépôt Google et sont
identifiables une à une. Les seuls flux sortants vont vers l'adresse IP que
l'utilisateur saisit lui-même (serveur SMB) ou vers un pair qu'il a choisi.

### 12.8 Renommer et supprimer depuis la page web (2.2.0)

`/api/rename`, `/api/delete` et, depuis la 2.5.0, `/api/move` sont les **seules routes
qui modifient ce qui existe déjà** sur le téléphone.
Elles franchissent toutes les barrières de 12.2 — dont la session et le contrôle
d'origine — puis deux de plus, propres à elles :

**a) Un interrupteur, visible sur l'écran de partage.** `ShareConfig.allowManage` était
fermé par défaut en 2.2.0. **Il est ouvert depuis la 2.3.1**, et une migration unique
(`Prefs.migrateShareManage`) l'ouvre sur les installations qui avaient déjà enregistré un
réglage — sans quoi `encodeDefaults = true` aurait fait gagner l'ancien `false` sur le
nouveau défaut. La raison de ce revirement est dans le § 13 : fermée par défaut, la
fonction n'apparaissait nulle part et passait pour absente. Un réglage qu'il faut deviner
pour que la fonction existe n'est pas un garde-fou.

Ce qui reste, et qui compte : l'interrupteur est en clair sur l'écran de partage avec un
avertissement en rouge, le navigateur demande confirmation avant chaque suppression, et
c'est **le serveur qui décide** — la requête est refusée quoi qu'affiche la page. Les
autorisations voyagent avec chaque listage, et non plus seulement dans `/api/session` :
refermer l'interrupteur fait disparaître les boutons au listage suivant, sans recharger
la page.

**b) Un en-tête que seule notre page peut poser.** `X-Fm36: 1` n'appartient pas à la
liste « sûre » du CORS : pour l'envoyer vers une autre origine, un navigateur doit
d'abord demander la permission au serveur (requête préalable `OPTIONS`), à laquelle nous
ne répondons jamais. Une page hostile ne peut donc pas fabriquer ces requêtes, même sur
un navigateur ancien qui n'enverrait ni `Sec-Fetch-Site` ni `Origin` — le cas que le
contrôle d'origine laisse passer, puisqu'il accepte les clients non navigateurs. Cette
exigence est limitée à ces deux routes : l'imposer au téléchargement casserait les
gestionnaires de téléchargement, qui sont des clients légitimes.

Le reste tient en quatre règles :

- **Confinement** : chaque chemin repasse par `resolve()`, et la racine d'un volume
  partagé ne peut être ni renommée ni supprimée (`isSharedRoot`).
- **Pas de suivi de lien symbolique** : la suppression réutilise
  `FileOperations.deleteRecursive`, qui efface le lien et jamais sa cible. C'est
  exactement le défaut qui, en 1.2, aurait effacé tout le stockage interne.
- **Aucun corps tronqué n'est interprété.** `readBody` s'arrête au budget donné sans le
  dire : « /sdcard/Photos/vacances2024.jpg » amputé donne « /sdcard/Photos/vacances »,
  qui peut exister et désigner autre chose. Un `Content-Length` trop grand est donc
  refusé (413), jamais deviné — et la page envoie ses suppressions par paquets de cent
  pour rester loin de la limite. Le serveur borne à 500 chemins par requête.
- **Un renommage ne peut pas faire disparaître une entrée.** Un nom commençant par un
  point est refusé et non assaini : le listage masque les fichiers cachés, si bien qu'un
  renommage accepté aurait effacé la ligne de la page — indiscernable d'une suppression
  accidentelle. Le nom passe par `SafeName` puis par `FileOperations.rename`, qui refuse
  à nouveau le séparateur et la cible existante : la même règle pour le navigateur et
  pour l'application.

---

## 13. Défauts critiques trouvés en revue de code

Chaque version a été relue par un sous-agent de revue avant livraison. Ce qui a été
rattrapé **avant** d'atteindre l'appareil :

| Version | Défaut |
|---|---|
| **1.2** | ⚠️ **Perte de données** : supprimer `/sdcard` depuis le volume « / » suivait le lien symbolique et aurait effacé tout le stockage interne |
| **1.3** | ⚠️ Partage SMB définitivement mort : `closeShare()` sur le fil principal |
| **1.6** | ⚠️ Plantage mémoire : rendu de 30 pages PDF d'un coup (~180 Mo), `OutOfMemoryError` non rattrapée |
| **1.7** | ⚠️ Aucun PDF ne s'affichait (RGB_565 refusé) ; barre supérieure agissant sur le fichier précédent ; déplacement d'image non borné |
| **1.8** | Lancement direct d'une application échouant et retombant sur le sélecteur ; page de remise non défilable en paysage |
| **1.9** | ⚠️ ANR/plantage : la visionneuse texte rendait une ligne unique de 260 000 caractères ; ⚠️ impasse introduite puis corrigée — retirer le repli `*/*` avait aussi condamné le bouton « Ouvrir avec une autre application », le coffre et le cloud, qui n'ont aucune boîte de dialogue derrière eux (d'où le paramètre `allowGenericFallback`) |
| **2.0.1** | ⚠️ Un quota vérifié puis incrémenté sans atomicité : une seule acceptation autorisait douze fois le volume annoncé |
| **2.0.2** | ⚠️ **Corruption silencieuse** : `bytes=100-abc` laissait `start` à 100 sans passer en réponse partielle, et le fichier repartait tronqué de 100 octets sous un « 200 OK » |
| **2.1.0** | Revue de sécurité complète : voir la section 12. Les deux plus graves — ⚠️ le serveur pouvait se lier à l'interface **des données mobiles** (téléphone joignable depuis Internet chez certains opérateurs), et ⚠️ un `.html` déposé sur le téléphone puis ouvert depuis la page web s'exécutait **dans l'origine du serveur** et pouvait exfiltrer tout le stockage partagé |

| **2.2.0** | ⚠️ **Écriture hors cible** : sur une route destructrice, un corps de requête tronqué par le budget de lecture était interprété tel quel — un chemin amputé de sa fin peut désigner un autre fichier existant, qui aurait alors été renommé ou supprimé à la place. Un `Content-Length` trop grand est désormais refusé au lieu d'être deviné. ⚠️ Et une erreur de syntaxe dans `index.html` empêchait **tout** le script de la page de s'exécuter : rien ne s'affichait au-delà de l'écran de code. Les deux ont été trouvés avant l'appareil, le second en rejouant les routes du téléphone sur un serveur factice et en pilotant la page dans un vrai navigateur — méthode à reprendre pour toute modification de `index.html`, qu'aucun compilateur ne relit |

| **2.3.0** | ⚠️ **Élargissement silencieux du partage** : un dossier partagé devenu introuvable — carte SD retirée — faisait retomber le serveur sur *tous* les volumes. Le dossier configuré est maintenant retenu tel quel, et la page répond « dossier introuvable ». ⚠️ La barre d'espace disque était collée à 52 px du haut : sur un écran étroit, l'en-tête s'enroule, dépasse cette hauteur et l'aurait recouverte. Elle est devenue la dernière ligne de l'en-tête, qui est collant d'un seul bloc |

| **2.3.1** | ⚠️ **Une fonction livrée mais invisible.** Le renommage et la suppression, fermés par défaut « par prudence », n'apparaissaient nulle part : ni bouton, ni mention, ni explication — l'utilisateur les a signalés comme absents. Une capacité qu'il faut deviner n'est pas protégée, elle est perdue. Corollaire trouvé en même temps : les autorisations n'étaient lues qu'au chargement de la page, si bien que basculer l'interrupteur pendant qu'un navigateur était ouvert ne changeait rien, même en actualisant |

| **2.4.0** | ⚠️ **File d'attente en boucle infinie** : un fichier dont le conflit venait d'être tranché repassait par la vérification, qui le redéclarait en conflit ; avec « appliquer à tous », la boucle tournait sans fin et grignotait un créneau d'envoi à chaque tour. Un état « prêt », distinct de « en attente », sépare désormais ce qui doit être vérifié de ce qui est déjà décidé |

| **2.5.0** | `ChangelogTest`, repris de 36Note, échouait sans défaut de logique : ses exemples utilisaient les numéros de version de 36Note (5 à 8), absents de l'historique de 36FileManager (15 à 20). Collision de signature JVM `setSearchScope` / mutateur de `searchScope` — le piège était déjà consigné en § 10 et a été retrouvé à la compilation. Dans la page web, une recherche lente recouvrait le dossier ouvert entre-temps : jeton de navigation |

| **2.5.1** | ⚠️ **La sélection de la page web ne marchait plus depuis la 2.5.0** : un correctif avait inséré le compteur de résultats de recherche dans `toggle()` au lieu de `render()` — le motif visé figurait d'abord à la fin de `toggle()`. Chaque case cochée levait `ReferenceError: searching is not defined` : ligne surlignée, mais barre d'outils jamais activée. Trouvé en préparant les captures de la page de présentation ; la 2.5.0 n'avait pas été éprouvée dans un navigateur. Leçon : **toute modification de `index.html` passe par un test navigateur, sans exception**, et un patch par motif textuel doit viser un ancrage unique |

**La revue avant livraison n'est pas une formalité : elle a évité une perte totale du
stockage de l'utilisateur, une corruption silencieuse de fichiers, et l'exposition du
téléphone sur Internet.** La conserver dans le cycle de livraison.

---

## 14. Méthode de travail

1. **Toujours demander le numéro de version affiché sur l'accueil avant d'analyser un
   rapport de bogue.** Une session de débogage complète a été perdue sur des symptômes
   impossibles dans la version livrée — l'utilisateur exécutait encore la v1.2. C'est la
   raison d'être de l'affichage de `BuildConfig.VERSION_NAME` sur l'accueil.
2. Cycle par itération : comprendre le symptôme → chercher la cause racine dans le code
   (pas la rustine) → corriger → compiler → **faire relire par un sous-agent de revue** →
   corriger les défauts trouvés → recompiler → livrer APK + sources.
3. Incrémenter `versionCode` **et** `versionName` à chaque livraison, **et ajouter l'entrée
   correspondante dans `data/Changelog.kt`** (demande de l'utilisateur, 17/09/2026, reprise de
   36Note). Une fenêtre présente ces nouveautés au premier lancement de chaque version —
   jamais à la première installation, une seule fois, toutes les versions sautées d'un coup —
   et reste consultable en touchant le titre de l'accueil. Écrire ce que l'utilisateur
   constate, pas ce que le code fait. L'oubli ne casse aucune compilation : **`ChangelogTest`
   est le seul garde-fou**, il échoue si la version construite n'a pas son entrée.
4. Vérifier les faits avant de conclure. Exemple : devant l'erreur « package en conflit »,
   la comparaison des certificats a montré que l'hypothèse évidente (signatures
   différentes) était fausse.
5. Écrire les commentaires du code en français, et **expliquer le pourquoi** — les
   commentaires de ce projet documentent les pièges Android, ce sont eux qui empêchent la
   réintroduction des bogues.

---

## 15. Reste à faire / pistes

**Décidé mais pas fait — et pourquoi**

- **Activer R8 en release** (`isMinifyEnabled = true`). Gagnerait ~30 % de taille et
  masquerait les noms. **Volontairement laissé à `false`** : smbj, dcerpc et
  kotlinx-serialization travaillent par réflexion, et un APK minifié qui plante au
  premier accès SMB ne se distingue d'un APK sain qu'à l'usage. À tenter dans une
  itération dédiée, avec les `-keep` nécessaires **et un test sur appareil réel**, jamais
  en même temps que d'autres changements.
- **Découper `FmViewModel`** (1852 lignes) en états délégués par domaine. Le ViewModel
  unique est un choix assumé (section 4), mais le fichier est devenu long. C'est le
  changement le plus risqué envisageable sur ce projet : il touche tout l'état de
  l'application d'un coup, et rend impossible d'attribuer une régression. À faire seul,
  dans une itération sans rien d'autre.

**Fonctionnalités**

- Signature *release* pour le Play Store. Attention : `REQUEST_INSTALL_PACKAGES` y est
  une autorisation **restreinte** exigeant un formulaire de déclaration.
- OAuth Google Drive / Microsoft Graph, en remplacement ou en complément du SAF.
- Découverte automatique des machines du réseau (mDNS / NetBIOS) — pour les partages SMB ;
  la découverte entre appareils 36FileManager existe déjà (diffusion UDP).
- Corbeille. **Plus utile depuis la 2.2.0** : la suppression depuis la page web est
  définitive, et c'est le seul endroit où l'on efface sans avoir l'appareil en main.
- FTP / SFTP / WebDAV.
- Extraction d'archives autres que ZIP (RAR, 7z) — nécessiterait une bibliothèque.
- Annulation des opérations longues (`ProgressOverlay` n'a pas de bouton Annuler).
- Tests JVM au-delà de `MultipartReader`, `SafeName` et `FileSearch` : les bornes de
  `unzip` s'y prêteraient bien.

*Fait en 2.1.0, précédemment sur cette liste : vérification d'espace disque avant
extraction d'une grosse archive. Fait en 2.4.0 : tests JVM de `SafeName` (9 cas, dont
toutes les formes de remontée).*

---

## 16. Fichiers du projet

| Fichier | Contenu |
|---|---|
| `36FileManager-vX.Y.Z-sources.zip` | Sources complètes **sans la clé** : `keystore/`, `keystore.properties`, `local.properties` et `build/` exclus |
| `36FileManager-v2.5.0-release.apk` | **APK à installer**, signé avec la clé du projet (~16,7 Mo) |
| `36FileManager-v2.5.0-debug.apk` | Même application, compilation debug (~23,6 Mo) |
| `README.md` (dans l'archive) | Documentation utilisateur et fonctionnelle |
| `DOSSIER-PROJET.md` | Ce document — également livré à côté de l'archive |

Les deux APK portent **le même certificat** (`ec15f53f…5813`) et le même `versionCode` :
ils s'installent l'un par-dessus l'autre et par-dessus toute version antérieure ≥ 1.9.1.
Sauf besoin de débogage, livrer la **release** : même code, deux fois moins lourde.
