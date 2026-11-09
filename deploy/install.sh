#!/usr/bin/env bash
# =====================================================================
#  Dobby — installation sur le VPS (à côté de 1G-Radar et Okko)
#
#    sudo bash deploy/install.sh                       adresse : dobby.<ip>.sslip.io
#    sudo bash deploy/install.sh dobby.exemple.fr      autre adresse (DNS A vers le serveur)
#
#  Même mécanisme qu'Okko. Relançable sans risque. Le script :
#   - vérifie le point d'entrée partagé de 1G-Radar (réseau edge, Caddy, /srv/caddy-sites) ;
#   - construit et lance le pont Dobby, sans publier aucun port public ;
#   - déclare l'adresse au Caddy de 1G-Radar et le recharge (sans redémarrer 1G-Radar) ;
#   - ne touche ni à 1G-Radar, ni à Okko, ni au pare-feu.
#
#  NOTE : le VPS héberge ainsi l'INTERFACE en HTTPS, mais ne peut pas joindre le drone
#  (192.168.1.1 est sur le Wi-Fi local). Pour PILOTER, lancer le même pont sur une machine
#  branchée au Wi-Fi du drone : docker run --network host  (voir DEPLOY.md).
# =====================================================================
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "Lancez avec sudo : sudo bash deploy/install.sh" >&2; exit 1; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
COMPOSE=(docker compose -f docker-compose.yml -f deploy/docker-compose.prod.yml)
SITE_FILE=/srv/caddy-sites/dobby.caddy

say() { printf '\n\033[1;35m▸ %s\033[0m\n' "$*"; }
die() { echo "ÉCHEC : $*" >&2; exit 1; }
caddy_container() {
  docker ps -q --filter label=com.docker.compose.project=1g-radar --filter label=com.docker.compose.service=caddy | head -1
}

# ------------------------------------------------------------------ prérequis
say "Vérifications"
command -v docker >/dev/null || die "Docker est absent de ce serveur."
docker compose version >/dev/null || die "docker compose est absent."
docker network inspect edge >/dev/null 2>&1 \
  || die "réseau Docker « edge » absent : le point d'entrée partagé de 1G-Radar doit être en place."
CADDY=$(caddy_container)
[ -n "$CADDY" ] || die "le conteneur Caddy de 1G-Radar ne tourne pas."
[ -d /srv/caddy-sites ] || die "dossier /srv/caddy-sites absent (point d'entrée partagé de 1G-Radar)."
echo "Point d'entrée partagé : OK (réseau edge, $CADDY)."

# ------------------------------------------------------------------ adresse
DOMAIN=${1:-}
if [ -z "$DOMAIN" ]; then
  IP=$(curl -4 -fsS --max-time 5 https://api.ipify.org || hostname -I | awk '{print $1}')
  DOMAIN="dobby.${IP//./-}.sslip.io"
fi
[[ "$DOMAIN" =~ ^[a-z0-9.-]+$ ]] || die "adresse invalide : $DOMAIN"
echo "Adresse : https://${DOMAIN}"

# ------------------------------------------------------------------ lancement
say "Construction et démarrage"
"${COMPOSE[@]}" up -d --build --remove-orphans

say "Attente du démarrage"
ready=0
for _ in $(seq 1 24); do
  if curl -fsS --max-time 3 http://127.0.0.1:"${DOBBY_HTTP_PORT:-8080}"/ >/dev/null 2>&1; then ready=1; break; fi
  sleep 2
done
[ "$ready" = 1 ] || echo "  (le pont met un peu à répondre ; vérifiez : ${COMPOSE[*]} logs -f web)"

# ------------------------------------------------------------------ adresse publique (Caddy de 1G-Radar)
say "Adresse publique : ${DOMAIN} (Caddy de 1G-Radar)"
tmp=$(mktemp)
cat > "$tmp" <<SITE
# Dobby — écrit par ${ROOT}/deploy/install.sh (ne pas modifier à la main).
${DOMAIN} {
	encode zstd gzip
	reverse_proxy dobby-web:8080
	header {
		Strict-Transport-Security "max-age=31536000"
		-Server
	}
}
SITE
CADDY=$(caddy_container)
[ -n "$CADDY" ] || die "le conteneur Caddy de 1G-Radar ne tourne pas."
if cmp -s "$tmp" "$SITE_FILE" 2>/dev/null; then
  rm -f "$tmp"; echo "Site déjà déclaré."
else
  previous=""
  [ -f "$SITE_FILE" ] && previous=$(mktemp) && cp "$SITE_FILE" "$previous"
  install -m 644 "$tmp" "$SITE_FILE"; rm -f "$tmp"
  if docker exec "$CADDY" caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1 \
     && docker exec "$CADDY" caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null; then
    echo "Caddy rechargé : https://${DOMAIN} → Dobby (1G-Radar non redémarré)."
    [ -n "$previous" ] && rm -f "$previous"
  else
    if [ -n "$previous" ]; then mv "$previous" "$SITE_FILE"; else rm -f "$SITE_FILE"; fi
    die "configuration Caddy refusée : fichier remis à l'état précédent, 1G-Radar non touché."
  fi
fi

cat <<DONE

==================================================================
  Dobby (interface) est en ligne
    Adresse : https://${DOMAIN}
    Le certificat HTTPS est obtenu au premier accès : comptez 1 minute.

  ⚠ Depuis le VPS, l'interface s'affiche mais ne pilote pas le drone
    (pas de route vers 192.168.1.1). Pour VOLER, lancez le même pont
    sur une machine du Wi-Fi du drone :  docker run --network host …
    (voir DEPLOY.md).

  Journaux : ${COMPOSE[*]} logs -f web
  Mise à jour : git pull puis sudo bash deploy/install.sh
==================================================================
DONE
