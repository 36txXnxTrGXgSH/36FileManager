package com.filemanager36.app.data

/**
 * Marche à suivre, valeurs habituelles et pistes de dépannage pour chaque système hôte.
 * Partagé entre le formulaire de connexion et l'écran d'erreur.
 */
data class ServerGuide(
    val steps: List<String>,
    val note: String,
    /** Points à vérifier quand la machine ne répond pas du tout. */
    val troubleshooting: List<String>,
    val hostLabel: String,
    val hostExample: String,
    val userLabel: String,
    val userExample: String,
    val credentialHint: String,
    val labelExample: String,
    val defaultPort: Int,
    val defaultDomain: String
)

object ServerGuides {

    fun of(os: ServerOs): ServerGuide = when (os) {
        ServerOs.WINDOWS -> ServerGuide(
            steps = listOf(
                "Partagez au moins un dossier : clic droit dessus → Propriétés → onglet " +
                    "Partage → Partager…, ajoutez votre compte, laissez « Lecture/écriture », Partager.",
                "Activez la découverte : Paramètres → Réseau et Internet → Paramètres réseau " +
                    "avancés → Paramètres de partage avancés → activez « Découverte de réseau » " +
                    "et « Partage de fichiers et d'imprimantes » pour le profil Privé.",
                "Passez le réseau Wi-Fi en Privé : Paramètres → Réseau et Internet → Wi-Fi → " +
                    "votre réseau → Type de profil réseau : Privé.",
                "Relevez l'adresse IP : touche Windows + R, tapez cmd, puis ipconfig — " +
                    "notez « Adresse IPv4 » (souvent 192.168.x.x).",
                "Vérifiez que votre compte Windows a un mot de passe : un compte sans mot de " +
                    "passe est refusé sur le réseau."
            ),
            note = "Compte Microsoft : l'identifiant est votre adresse e-mail complète. " +
                "Compte local : le nom d'utilisateur seul.",
            troubleshooting = listOf(
                "Le pare-feu bloque presque toujours : Sécurité Windows → Pare-feu et protection " +
                    "du réseau → Autoriser une application → cochez « Partage de fichiers et " +
                    "d'imprimantes » pour le réseau Privé.",
                "Le profil du réseau Wi-Fi doit être Privé, pas Public : sur Public, Windows " +
                    "refuse toute connexion entrante.",
                "Aucun dossier partagé = aucun service à joindre. Partagez d'abord un dossier " +
                    "(clic droit → Propriétés → Partage → Partager…).",
                "Vérifiez l'adresse : sur le PC, tapez ipconfig dans l'invite de commandes et " +
                    "comparez « Adresse IPv4 » avec ce que vous avez saisi.",
                "Test rapide depuis le PC : ouvrez PowerShell et tapez " +
                    "Test-NetConnection -ComputerName localhost -Port 445 — TcpTestSucceeded " +
                    "doit valoir True.",
                "Le téléphone et le PC doivent être sur le même réseau Wi-Fi : le réseau " +
                    "« invité » d'une box isole les appareils entre eux.",
                "Certains antivirus (Avast, Kaspersky, Bitdefender) ont leur propre pare-feu " +
                    "qui bloque le port 445 indépendamment de Windows.",
                "Si le PC est en veille, il ne répond pas : réveillez-le avant de vous connecter."
            ),
            hostLabel = "Adresse IP du PC",
            hostExample = "192.168.1.20",
            userLabel = "Identifiant Windows",
            userExample = "utilisateur ou prenom.nom@outlook.com",
            credentialHint = "Ce sont vos identifiants de session Windows. Avec un compte " +
                "Microsoft, utilisez l'adresse e-mail complète et le mot de passe du compte.",
            labelExample = "PC du bureau",
            defaultPort = 445,
            defaultDomain = "WORKGROUP"
        )

        ServerOs.LINUX -> ServerGuide(
            steps = listOf(
                "Installez Samba : sudo apt install samba",
                "Déclarez un partage dans /etc/samba/smb.conf :  [Partage]  " +
                    "path = /home/utilisateur/Partage  browseable = yes  read only = no",
                "Créez le mot de passe Samba : sudo smbpasswd -a utilisateur",
                "Redémarrez le service : sudo systemctl restart smbd",
                "Relevez l'adresse IP : hostname -I  (ou  ip a)"
            ),
            note = "L'utilisateur Samba est distinct du mot de passe de session Linux : " +
                "utilisez celui défini avec smbpasswd.",
            troubleshooting = listOf(
                "Le service tourne-t-il ? sudo systemctl status smbd",
                "Pare-feu : sudo ufw allow samba  (ou  sudo ufw allow 445/tcp)",
                "Samba écoute-t-il ? sudo ss -tlnp | grep 445",
                "Vérifiez la configuration : testparm",
                "Vérifiez l'adresse relevée par hostname -I."
            ),
            hostLabel = "Adresse IP de la machine Linux",
            hostExample = "192.168.1.30",
            userLabel = "Utilisateur Samba",
            userExample = "utilisateur",
            credentialHint = "Mot de passe défini par smbpasswd, pas celui de la session Linux.",
            labelExample = "Serveur Linux",
            defaultPort = 445,
            defaultDomain = "WORKGROUP"
        )

        ServerOs.ANDROID -> ServerGuide(
            steps = listOf(
                "Android ne partage pas en SMB nativement : installez sur l'autre téléphone " +
                    "une application serveur SMB (Servers Ultimate, kSMBd…).",
                "Dans cette application, créez un partage et démarrez le serveur.",
                "Relevez l'adresse IP : Paramètres → Wi-Fi → réseau connecté → Détails.",
                "Notez le port affiché par l'application serveur (Options avancées ici).",
                "Cochez « Connexion invité » si le serveur n'exige pas de compte."
            ),
            note = "Le port 445 est réservé au système sur Android : les applications serveur " +
                "utilisent en général 4455, 1445 ou 4450.",
            troubleshooting = listOf(
                "Le serveur est-il démarré ? Beaucoup d'applications s'arrêtent en veille.",
                "Le port doit correspondre exactement à celui affiché par l'application serveur " +
                    "(rarement 445 sur Android).",
                "Désactivez l'économiseur de batterie pour l'application serveur."
            ),
            hostLabel = "Adresse IP du téléphone hôte",
            hostExample = "192.168.1.42",
            userLabel = "Utilisateur",
            userExample = "",
            credentialHint = "Beaucoup de serveurs Android acceptent la connexion invité.",
            labelExample = "Téléphone de Léa",
            defaultPort = 4455,
            defaultDomain = ""
        )

        ServerOs.NAS -> ServerGuide(
            steps = listOf(
                "Ouvrez l'interface web du NAS et activez le service SMB / CIFS.",
                "Créez au moins un dossier partagé.",
                "Créez ou choisissez un utilisateur autorisé sur ce dossier.",
                "Relevez l'adresse IP du NAS dans ses paramètres réseau.",
                "Fixez cette adresse dans votre box (bail DHCP statique)."
            ),
            note = "Synology : Panneau de configuration → Services de fichiers → SMB. " +
                "QNAP : Panneau → Réseau & services de fichiers → Microsoft Networking.",
            troubleshooting = listOf(
                "Le service SMB est-il activé dans l'interface du NAS ?",
                "Le NAS est-il sorti de veille (disques en hibernation) ?",
                "Vérifiez l'adresse IP : elle a pu changer si le bail DHCP n'est pas fixe.",
                "Certains NAS n'acceptent que SMB 2 ou 3 : vérifiez la version minimale exigée."
            ),
            hostLabel = "Adresse IP du NAS",
            hostExample = "192.168.1.10",
            userLabel = "Utilisateur du NAS",
            userExample = "utilisateur",
            credentialHint = "Compte créé dans l'interface d'administration du NAS.",
            labelExample = "NAS du salon",
            defaultPort = 445,
            defaultDomain = "WORKGROUP"
        )
    }
}
