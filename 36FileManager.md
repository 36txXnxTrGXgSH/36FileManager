# 36FileManager — signature de l'APK et mises à jour

**À lire avant de livrer toute nouvelle version.**

> **Toujours valide au 17 septembre 2026, version 2.5.0 (versionCode 20).** La clé n'a pas
> changé depuis la 1.9.1 et ne doit pas changer. Les APK 2.5.0 debug et release portent
> tous deux le certificat `ec15f53f…5813` : vérifié par `apksigner verify --print-certs`
> contre l'APK de la version précédente déjà installé.
>
> Le dossier complet du projet est dans `36FileManager-DOSSIER-PROJET.md` ; la section 12
> y traite la sécurité du partage réseau.

## Le problème rencontré (28/08/2026, v1.9)
À l'installation de v1.9 par-dessus la version présente sur le téléphone :

> « L'application n'a pas été installée, car le package est en conflit avec un package
> déjà présent. »

C'est `STATUS_FAILURE_CONFLICT` du gestionnaire de paquets. Vérifications faites sur
les deux APK avec `apksigner verify --print-certs` et `aapt2 dump xmltree` :

- v1.8 et v1.9 portaient **le même certificat** (`SHA-256 43fe2aed…`, CN=Android Debug) ;
- les manifestes ne différaient que par `versionCode` 9 → 10 et les ajouts prévus
  (`REQUEST_INSTALL_PACKAGES`, `<queries>`) ;
- aucun `sharedUserId`, aucune permission personnalisée en double, même autorité
  `FileProvider`.

**Donc la mise à jour 1.8 → 1.9 était techniquement valide.** Le conflit vient de ce qui
est réellement installé sur l'appareil, pas de l'APK livré. Causes possibles, dans
l'ordre de fréquence : version installée signée par une autre clé (compilée sur une
autre machine ou dans un conteneur recréé) ; application clonée (Dual Messenger,
Espace sécurisé, App Twin) ; profil professionnel ou second utilisateur ; données
résiduelles d'une désinstallation partielle. **Aucune n'est réparable côté APK :
il faut désinstaller, y compris depuis le profil ou l'espace cloné.**

## La correction durable (v1.9.1)
`app/build.gradle.kts` déclare une `signingConfig` **« projet »** appliquée aux
compilations *debug* **et** *release* :

```
keystore/36filemanager-dev.jks   identifiants dans keystore.properties (tous deux HORS dépôt)
CN=36FileManager, OU=Dev, O=36FileManager, C=FR   validité 10 950 jours
SHA-256 ec15f53fdc8cb032dff9cb1c1d72e58b26bdc953b101a3116659f00d87345813
```

La clé est **hors du dépôt** et doit le rester. Sans elle, Gradle signerait avec
`~/.android/debug.keystore`, propre à la machine qui compile : le conteneur de travail
étant éphémère, sa disparition suffirait à produire un APK non installable par-dessus
le précédent. Ce n'est **pas** une clé jetable : elle garantit aux utilisateurs qu'une mise à jour vient bien du projet. La sauvegarder hors du poste.

Schémas v2 + v3 (AGP ignore la signature v1 au-delà de minSdk 24 ; sans importance ici,
minSdk 26).

## Règles à suivre
1. **Ne jamais changer cette clé** sans nécessité : tout changement impose à
   l'utilisateur une désinstallation complète.
2. **Avant toute désinstallation, prévenir explicitement** : le coffre-fort vit dans
   l'espace privé de l'application et **est détruit** avec elle. Il faut d'abord
   restaurer son contenu vers le stockage normal depuis l'application. Favoris,
   serveurs réseau et réglages sont également perdus (SharedPreferences).
3. **Toujours incrémenter `versionCode`** en plus de `versionName`.
4. Devant un message d'installation refusée, **vérifier avant de conclure** :
   `apksigner verify --print-certs` sur l'ancien et le nouvel APK, puis
   `aapt2 dump xmltree --file AndroidManifest.xml` sur les deux.

## Commandes utiles
```bash
export ANDROID_HOME=/root/android-sdk
echo "sdk.dir=/root/android-sdk" > local.properties   # à recréer à chaque session
./gradlew assembleDebug assembleRelease

APKSIGNER=$ANDROID_HOME/build-tools/35.0.0/apksigner
$APKSIGNER verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

Sous Windows, avec le SDK et le JDK d'Android Studio (Gradle 8.14.3 n'accepte pas le
JDK 25 livré dans `jbr/` — prendre le 21) :

```bash
printf 'sdk.dir=C:/chemin/vers/Android/Sdk\n' > local.properties
JAVA_HOME="$HOME/.jdks/jbr-21.0.11" ./gradlew assembleDebug assembleRelease
```