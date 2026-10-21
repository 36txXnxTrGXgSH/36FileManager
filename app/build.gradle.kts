import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.filemanager36.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.filemanager36.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 21
        versionName = "2.5.1"
        vectorDrawables { useSupportLibrary = true }
    }

    // Clé de signature permanente du projet, gardée HORS du dépôt.
    //
    // Par défaut, Gradle signe les compilations « debug » avec la clé jetable
    // ~/.android/debug.keystore de la machine qui compile. Changer de machine — ou
    // simplement perdre ce fichier — produit un APK signé différemment, qu'Android
    // refuse alors d'installer par-dessus le précédent : « le package est en conflit
    // avec un package déjà présent ». Une clé unique, conservée à part, garde toutes
    // les versions publiées installables les unes par-dessus les autres.
    //
    // Signature du projet. Les identifiants vivent dans keystore.properties, a la
    // racine, **hors du depot** (voir .gitignore) : une cle publiee laisserait
    // n'importe qui signer une mise a jour qu'Android accepterait par-dessus
    // l'application installee.
    //
    // Sans ce fichier la compilation reste possible, mais les APK sortent signes
    // par ~/.android/debug.keystore, propre a la machine : ils ne s'installent
    // pas par-dessus ceux produits ailleurs. Sauvegarder keystore.properties et
    // le .jks a part, une fois pour toutes.
    val identifiantsCle = Properties()
    val fichierIdentifiants = rootProject.file("keystore.properties")
    if (fichierIdentifiants.exists()) {
        fichierIdentifiants.inputStream().use { flux -> identifiantsCle.load(flux) }
    }
    val cheminCle = identifiantsCle.getProperty("storeFile") ?: ""
    val fichierCle = if (cheminCle.isEmpty()) null else rootProject.file(cheminCle)
    val cleDisponible = fichierCle != null && fichierCle.exists()

    signingConfigs {
        if (cleDisponible) {
            create("projet") {
                storeFile = fichierCle
                storePassword = identifiantsCle.getProperty("storePassword")
                keyAlias = identifiantsCle.getProperty("keyAlias")
                keyPassword = identifiantsCle.getProperty("keyPassword")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Sans keystore.properties, on retombe sur la cle de debug d'Android.
            signingConfig = signingConfigs.findByName("projet") ?: signingConfig
        }
        release {
            isMinifyEnabled = false
            // Nul si la cle manque : l'APK sort alors NON SIGNE, ce qui se voit
            // tout de suite (app-release-unsigned.apk) plutot que de passer inapercu.
            signingConfig = signingConfigs.findByName("projet")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf("-Xjvm-default=all")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/*.kotlin_module",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// guava 18 (tiré par dcerpc) contient déjà ListenableFuture : l'artefact séparé
// listenablefuture, tiré par AndroidX, dupliquerait la classe et ferait échouer dex.
configurations.all {
    exclude(group = "com.google.guava", module = "listenablefuture")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.biometric:biometric:1.1.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Accès aux lecteurs réseau LAN (SMB / CIFS)
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("org.slf4j:slf4j-nop:1.7.36")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // Énumération des partages d'un serveur (MS-SRVS / NetrShareEnum sur le tube IPC$).
    // bcprov-jdk15on est exclu : mêmes classes que bcprov-jdk18on, sinon dex refuse.
    implementation("com.rapid7.client:dcerpc:0.12.0") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    }

    // QR code du partage réseau. « core » est du Java pur, sans dépendance AWT ni
    // Android : c'est le module qu'utilise zxing-android-embedded sous le capot.
    implementation("com.google.zxing:core:3.5.3")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Tests JVM du lecteur multipart : la partie du partage réseau où une
    // erreur d'un octet corrompt silencieusement les fichiers reçus.
    testImplementation("junit:junit:4.13.2")
}
