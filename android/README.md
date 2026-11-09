# Dobby Control — APK Android native

Appli native qui pilote le drone **sans pont ni VPS** : le téléphone se connecte
au Wi-Fi du drone et parle l'UDP directement à `192.168.1.1`.

## Télécharger l'APK (sans rien compiler)

À chaque push, GitHub Actions compile l'APK et la publie ici :
**Releases → `apk-latest` → `app-debug.apk`**
(ou onglet Actions → dernier run → artefact `dobby-control-apk`).

Sur le téléphone : télécharge `app-debug.apk`, autorise « installer des applis
inconnues » pour ton navigateur, et installe.

## Utiliser

1. Connecte le téléphone au Wi-Fi **Dobby-0bdcd5** (clé `zerotech`).
2. Ouvre l'appli. Elle route automatiquement l'UDP par le Wi-Fi du drone
   (même sans internet ; garde la 4G active si tu veux internet en parallèle).
3. **Hélices enlevées** pour les premiers essais. Interrupteur **Armer**, puis
   joysticks. **CAM** démarre le retour vidéo.

Joystick gauche : monter/descendre + lacet. Droit : avant/arrière + translation.

## Compiler soi-même (Android Studio)

Ouvre le dossier `android/` dans Android Studio et lance « Build APK »,
ou en ligne de commande : `gradle assembleDebug` (APK dans
`app/build/outputs/apk/debug/`).

## À confirmer

- **Décollage / atterrissage** : octets à confirmer par capture. Dans
  `DroneLink.kt`, renseigne `cmdTakeoff` / `cmdLand` (ex. `Pair(0x51, 0x2C)`).
  Tant qu'ils sont `null`, les boutons n'envoient rien (sécurité).
- **Vidéo** : dépacketisation RTP H.264 standard (NAL simple + FU-A + STAP-A) ;
  non testée sur ton drone, peut demander un ajustement.
