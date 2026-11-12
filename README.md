# YT MP3 — appli Android

Colle le lien d'une vidéo (ou partage-la depuis l'appli YouTube), choisis le nom du fichier, et récupère la piste en MP3 dans **Musique/YtMp3**.

- Nom du fichier modifiable avant la création (vide = titre de la vidéo, bouton « Utiliser le titre de la vidéo » pour le pré-remplir)
- Qualité 128 / 192 / 320 kbps
- Barre de progression et bouton Annuler
- « Partager → YT MP3 » depuis l'appli YouTube
- Mise à jour automatique du moteur yt-dlp (1×/jour) + bouton manuel
- Android 10 ou plus récent

Basée sur [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) (yt-dlp + ffmpeg embarqués).

## Obtenir l'APK

### Option A — sans rien installer (GitHub)
1. Crée un nouveau dépôt sur github.com (privé si tu veux).
2. Envoie-y tout le contenu de ce dossier (« Add file → Upload files », en gardant le dossier `.github`).
3. Onglet **Actions** → le workflow « Build APK » se lance tout seul (~5 min).
4. Ouvre l'exécution terminée → section **Artifacts** → télécharge `YtMp3-apk` (zip contenant l'APK).

> Le dossier `.github` est caché sur certains systèmes : vérifie qu'il est bien envoyé, sinon crée le fichier `.github/workflows/build.yml` à la main sur GitHub.

### Option B — Android Studio
1. Installe [Android Studio](https://developer.android.com/studio).
2. *File → Open* → choisis ce dossier, attends la synchronisation Gradle.
3. *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.
   L'APK est dans `app/build/outputs/apk/debug/app-debug.apk`.
   (Ou branche ton téléphone en mode développeur et clique sur ▶.)

## Installer sur le téléphone
Copie l'APK sur le téléphone, ouvre-le, et autorise « Installer des applis inconnues » pour l'appli qui l'ouvre (Fichiers, Chrome…).

## Si un téléchargement échoue
Appuie sur **Mettre à jour le moteur** : YouTube change régulièrement et yt-dlp suit rapidement.

À réserver aux contenus dont tu as les droits ou sous licence libre.
