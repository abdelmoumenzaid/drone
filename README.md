# Dobby Control — appli web mobile

Front mobile + pont pour piloter ton ZeroTech Dobby depuis un navigateur, sans
l'appli DoFun ni compte.

```
Téléphone (navigateur, Wi-Fi drone) ──WebSocket──► PONT ──UDP──► Drone 192.168.1.1
```

Fichiers :
- `index.html` — le front mobile (deux joysticks tactiles, télémétrie, boutons). Autonome, aucune ressource externe.
- `bridge.py` — le pont : sert la page + traduit en UDP + boucle RC 20 Hz + heartbeat.

---

## ⚠️ La question clé : drone ET internet en même temps

Le drone est un **point d'accès Wi-Fi isolé, sans internet**. Il faut comprendre
ça avant de déployer :

### Pour piloter, tu n'as PAS besoin d'internet du tout

Tout le pilotage est **local** : le téléphone, le pont et le drone sont sur le
même Wi-Fi (`Dobby-0bdcd5`). La page web est servie par le pont, pas par un
serveur distant. Donc en vol, zéro internet nécessaire. C'est le mode normal.

### Si tu veux quand même internet sur le téléphone en même temps

Les téléphones modernes le gèrent tout seuls :
- **Reste connecté au Wi-Fi du drone** (pour joindre le pont en local).
- **Active les données mobiles (4G/5G)** en parallèle.

Le téléphone utilise le Wi-Fi pour le drone (adresse locale du pont) et la 4G
pour internet. Android bascule automatiquement internet sur la 4G quand le Wi-Fi
n'a pas d'accès. Sur iPhone, c'est pareil. Les deux marchent ensemble.

### Et le VPS, alors ?

Un VPS dans le cloud **ne peut jamais joindre le drone** (pas de route vers
192.168.1.1). Son rôle se limite à :
- héberger le code / tes mises à jour,
- éventuellement te servir la page une première fois pour l'installer.

Il n'est **jamais** dans le chemin de pilotage. L'instance qui vole, c'est le
pont, sur place.

---

## Où faire tourner le pont

Le pont doit être sur une machine **connectée au Wi-Fi du drone**. Au choix :

| Machine | Connexion au drone | Internet (optionnel) |
|---------|-------------------|----------------------|
| Raspberry Pi | Wi-Fi intégré → drone | câble ethernet → ta box |
| Vieux laptop | Wi-Fi → drone | ethernet ou partage 4G |
| 2ᵉ téléphone Android | Wi-Fi → drone (via Termux) | données mobiles |

Pour avoir le pont ET internet sur la même machine : utilise **deux interfaces**
(ex. Pi : Wi-Fi sur le drone, ethernet sur ta box). Le Wi-Fi reste dédié au drone.

---

## Installation & lancement

Sur la machine-pont :

```bash
pip install aiohttp          # le pont
sudo apt install ffmpeg      # pour le retour caméra
python3 bridge.py
```

Trouve son IP locale sur le Wi-Fi du drone :
```bash
ip addr        # Linux/Pi  -> cherche une adresse 192.168.1.x
ipconfig       # Windows
```

Sur le téléphone (connecté au même Wi-Fi `Dobby-0bdcd5`), ouvre le navigateur :
```
http://192.168.1.XX:8080      (XX = l'IP du pont)
```

Tourne le téléphone à l'horizontale. Tu dois voir « Pont OK » puis « Drone »
s'allumer quand la télémétrie arrive.

---

## Utilisation (ordre sûr)

1. **Hélices enlevées** pour les premiers essais.
2. Vérifie que « Pont OK » et « Drone » sont verts.
3. Les joysticks bougent → le pont envoie, mais rien n'atteint les moteurs tant
   que tu n'as pas **Armé** (interrupteur en bas).
4. Joystick **gauche** : haut/bas = monter/descendre, gauche/droite = rotation (lacet).
   Joystick **droit** : haut/bas = avant/arrière, gauche/droite = translation.
   Les deux reviennent au centre tout seuls (= vol stationnaire).
5. **ARRÊT D'URGENCE** : désarme et remet tout au neutre immédiatement.

---

## Retour caméra (FPV)

Tu n'as pas d'écran sur le drone : la vidéo s'affiche dans le front, en plein
écran, joysticks par-dessus. Appuie sur **CAM** pour démarrer.

Comment ça marche (confirmé dans le code décompilé) :
1. Le front demande la vidéo → le pont envoie la commande start-RTP
   (`A0 09 7D 01 … <port>`), qui dit au drone d'envoyer le flux sur le port `7078`.
2. Le drone streame du **H.264 dans du RTP** vers le pont.
3. **ffmpeg** (sur le pont) transcode ce flux en **MJPEG**, que le navigateur
   affiche dans une balise `<img>`. Pas de lib JS, marche hors-ligne.

⚠️ **Partie la plus incertaine du projet.** Le format du flux est confirmé
(H.264 Annex-B), mais je n'ai pas pu le tester sur ton drone. Si CAM reste noir :
- vérifie que `ffmpeg` est installé (`ffmpeg -version`),
- le type de payload RTP est supposé `96` dans `bridge.py` (variable `SDP`) ;
  une capture peut montrer une autre valeur — ajuste la ligne `a=rtpmap:96 …`,
- vérifie qu'aucun pare-feu ne bloque le port UDP `7078` sur le pont,
- en debug, lance ffmpeg à la main pour voir les erreurs :
  `ffmpeg -protocol_whitelist file,udp,rtp -i stream.sdp -f null -`
  (pendant que le pont tourne et que CAM est activé).

Latence attendue : ~0,3–0,5 s en MJPEG sur Wi-Fi local. Suffisant pour cadrer,
pas pour du vol de précision à vue caméra seule.

---

## À finir : décollage / atterrissage

Les boutons existent mais les octets exacts ne sont **pas confirmés** (voir
`dobby_protocole.md`). Dans `bridge.py`, renseigne :

```python
CMD_TAKEOFF = ("module", 0x51, 0x2C)   # à remplacer par ta capture
CMD_LAND    = ("module", 0x46, 0x46)   # à remplacer par ta capture
```

Fais une capture (PCAPdroid) de l'appli DoFun réelle en appuyant une fois sur
chaque bouton, relève le paquet `A0 ..` vers `192.168.1.1:7088`, et mets les
vrais octets ici. Tant que c'est `None`, les boutons n'envoient rien (sécurité).

---

## Déploiement « type VPS » si tu y tiens

Tu peux versionner tout ce dossier et le `git pull` sur la machine-pont (Pi/laptop)
depuis ton VPS ou GitHub. Le VPS sert alors de dépôt de code, pas de serveur de
vol. La commande qui vole reste `python3 bridge.py` sur place.
