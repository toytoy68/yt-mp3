# YT MP3 — appli Android

Colle le lien d'une vidéo (ou partage-la depuis l'appli YouTube), choisis le nom du fichier, et récupère la piste en MP3 dans **Musique/YtMp3**.

- Titre récupéré automatiquement dès que le lien est collé, nom modifiable avant la création
- Vidéo analysée une seule fois : l'extraction démarre tout de suite
- Qualité 128 / 192 / 320 kbps
- Barres d'avancement du téléchargement et de la conversion MP3
- Continue en arrière-plan (notification avec progression et bouton Annuler)
- « Partager → YT MP3 » depuis l'appli YouTube
- Mise à jour du moteur yt-dlp par bouton (pas de mise à jour automatique)
- « Écouter » propose de choisir l'appli de lecture
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

## Play Protect
Play Protect signale l'appli comme dangereuse, comme tous les téléchargeurs YouTube installés hors Play Store. Le code complet est dans ce dépôt.

## Si un téléchargement échoue
Appuie sur **Mettre à jour le moteur** : YouTube change régulièrement et yt-dlp suit rapidement.

À réserver aux contenus dont tu as les droits ou sous licence libre.
