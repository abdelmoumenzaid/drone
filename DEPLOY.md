# Déploiement

⚠️ **Rappel réseau** : le pont doit pouvoir joindre le drone (`192.168.1.1`).
Un VPS dans le cloud n'y arrive pas. Déploie sur le VPS pour **héberger la page /
tester l'interface** ; pour **voler**, lance exactement le même repo sur une
machine branchée au Wi-Fi du drone (Raspberry Pi, laptop). La commande est
identique, seul l'endroit change.

---

## Option 0 — Sur ton VPS, comme Okko (Caddy 1G-Radar + sslip.io)

Même mécanisme qu'Okko : le Caddy de 1G-Radar sert l'interface en HTTPS sur le
réseau `edge`, sans toucher à 1G-Radar ni à Okko.

```bash
scp -r dobby-web/ UTILISATEUR@51.38.243.181:~/
ssh UTILISATEUR@51.38.243.181
cd ~/dobby-web
sudo bash deploy/install.sh
```

Adresse obtenue : **https://dobby.51-38-243-181.sslip.io** (certificat au 1er accès).
Mise à jour : `git pull && sudo bash deploy/install.sh`.

⚠️ Sur le VPS, l'interface s'affiche mais **ne pilote pas** le drone (pas de route
vers 192.168.1.1). C'est une vitrine de l'UI. Le pilotage se fait avec le même
repo lancé sur le Wi-Fi du drone (options ci-dessous, `--network host`).

---

## Option 1 — Docker (recommandé, tout inclus : ffmpeg)

Sur ta machine locale, envoie le dossier sur le VPS :

```bash
scp -r dobby-web/ UTILISATEUR@IP_DU_VPS:~/
```

Puis sur le VPS :

```bash
cd ~/dobby-web
docker build -t dobby .
docker run -d --name dobby --restart unless-stopped --network host dobby
```

- `--network host` est **obligatoire** : le pont utilise de l'UDP (ports 8000 /
  7078) et doit partager la pile réseau de l'hôte.
- Page accessible sur `http://IP_DU_VPS:8080`.

Logs / arrêt :
```bash
docker logs -f dobby
docker stop dobby && docker rm dobby
```

---

## Option 2 — Sans Docker (Python + systemd)

Sur le VPS :

```bash
sudo apt update && sudo apt install -y python3-pip ffmpeg
sudo useradd -r -s /usr/sbin/nologin dobby 2>/dev/null || true
sudo mkdir -p /opt/dobby-web
sudo cp -r ~/dobby-web/* /opt/dobby-web/
sudo pip install -r /opt/dobby-web/requirements.txt

sudo cp /opt/dobby-web/dobby-bridge.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now dobby-bridge
```

État / logs :
```bash
systemctl status dobby-bridge
journalctl -u dobby-bridge -f
```

---

## Option 3 — Lancer vite, sans service (test)

```bash
cd ~/dobby-web
pip install -r requirements.txt
sudo apt install -y ffmpeg
python3 bridge.py
```

Ouvre ensuite `http://IP_DU_VPS:8080`.

---

## Via Git (si tu préfères `git pull`)

Le dossier est déjà un dépôt Git. Pousse-le sur GitHub/GitLab depuis ta machine :

```bash
cd dobby-web
git remote add origin git@github.com:TON_COMPTE/dobby-web.git
git push -u origin main
```

Puis sur le VPS (ou le Raspberry Pi qui vole) :

```bash
git clone https://github.com/TON_COMPTE/dobby-web.git
cd dobby-web
pip install -r requirements.txt && sudo apt install -y ffmpeg
python3 bridge.py
```

---

## Pare-feu VPS

Ouvre le port **8080/tcp** (page + WebSocket + vidéo) :

```bash
sudo ufw allow 8080/tcp
```

Les ports UDP 7088/8000/7078 ne concernent que la liaison locale avec le drone ;
inutile de les ouvrir côté internet.
