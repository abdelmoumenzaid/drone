#!/usr/bin/env python3
"""
Pont de contrôle + vidéo du ZeroTech Dobby — à faire tourner sur le Wi-Fi du drone.

Un seul processus :
  - sert la page mobile (index.html) en HTTP,
  - WebSocket /ws : commandes du navigateur <-> UDP vers le drone,
  - boucle RC 20 Hz + heartbeat (indispensables),
  - /video.mjpg : flux caméra FPV transcodé par ffmpeg (RTP/H.264 -> MJPEG),
  - renvoie la télémétrie brute au navigateur.

Schéma :
  Téléphone (navigateur, Wi-Fi drone) --WS/HTTP--> CE PONT --UDP--> Drone 192.168.1.1
  Le pont et le téléphone sont tous deux sur le Wi-Fi "Dobby-0bdcd5".

Dépendances :  pip install aiohttp   +   ffmpeg installé (apt install ffmpeg)
Lancement   :  python3 bridge.py
Téléphone   :  http://<ip-du-pont>:8080
"""

import asyncio
import json
import time
from pathlib import Path

from aiohttp import web, WSMsgType

# ----------------------------------------------------------------------------
DRONE_IP     = "192.168.1.1"
PORT_CONTROL = 7088     # destination : RC + heartbeat + commandes
LOCAL_PORT   = 8000     # port local (réception télémétrie)
RTP_PORT     = 7078     # port local où le drone envoie la vidéo (annoncé au drone)
HTTP_PORT    = 8080     # page web + WebSocket + vidéo

NEUTRAL   = 1500
HEARTBEAT = bytes([0xA0, 0x05, 0xE0, 0xC0, 0x45])      # confirmé

# Décollage / atterrissage : à confirmer par capture (voir README).
CMD_TAKEOFF = None   # ex: (0x51, 0x2C)
CMD_LAND    = None   # ex: (0x46, 0x46)
# ----------------------------------------------------------------------------

HERE = Path(__file__).resolve().parent


def checksum(buf: bytearray) -> int:
    return sum(buf[:-1]) & 0xFF


def rc_packet(roll, pitch, throttle, yaw, counter):
    """A0 18 01 + 10 canaux (16 bits BE) + checksum. 24 octets."""
    ch = [roll, pitch, throttle, yaw, NEUTRAL, NEUTRAL, NEUTRAL,
          counter & 0xFFFF, NEUTRAL, NEUTRAL]
    buf = bytearray(24)
    buf[0] = 0xA0; buf[1] = 24; buf[2] = 0x01
    for i, v in enumerate(ch):
        v = max(1000, min(2000, int(v))) & 0xFFFF
        buf[3 + i * 2] = (v >> 8) & 0xFF
        buf[4 + i * 2] = v & 0xFF
    buf[23] = checksum(buf)
    return bytes(buf)


def module_cmd(module, cmd, seq):
    """A0 07 <module> <cmd> <seq_hi> <seq_lo> <chk>."""
    buf = bytearray(7)
    buf[0] = 0xA0; buf[1] = 7; buf[2] = module & 0xFF; buf[3] = cmd & 0xFF
    buf[4] = (seq >> 8) & 0xFF; buf[5] = seq & 0xFF
    buf[6] = checksum(buf)
    return bytes(buf)


def start_rtp_packet(port, seq):
    """
    Démarre le flux vidéo. Confirmé dans le code : module 0x7D, cmd 0x01,
    paramètre = le port local où envoyer la vidéo (little-endian).
    A0 09 7D 01 <seq_hi> <seq_lo> <port_lo> <port_hi> <chk>.
    """
    buf = bytearray(9)
    buf[0] = 0xA0; buf[1] = 9; buf[2] = 0x7D; buf[3] = 0x01
    buf[4] = (seq >> 8) & 0xFF; buf[5] = seq & 0xFF
    buf[6] = port & 0xFF; buf[7] = (port >> 8) & 0xFF
    buf[8] = checksum(buf)
    return bytes(buf)


class DroneState:
    def __init__(self):
        self.roll = self.pitch = self.throttle = self.yaw = NEUTRAL
        self.armed = False
        self.seq = 0
        self.counter = 0
        self.last_rx = 0.0
        self.pending = []
        self.video = False

    def next_seq(self):
        self.seq = (self.seq + 1) & 0xFFFF
        return self.seq

    def set_neutral(self):
        self.roll = self.pitch = self.throttle = self.yaw = NEUTRAL


state = DroneState()
clients = set()


class UDP(asyncio.DatagramProtocol):
    def __init__(self):
        self.transport = None

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, addr):
        state.last_rx = time.time()
        payload = json.dumps({"t": "tel", "raw": data[:32].hex(" ")})
        for ws in list(clients):
            asyncio.create_task(ws.send_str(payload))


async def rc_loop(udp: UDP):
    """RC 20 Hz + heartbeat 1 Hz + réémission start-RTP tant que la vidéo est active."""
    last_hb = 0.0
    last_rtp = 0.0
    while True:
        now = time.time()
        state.counter = (state.counter + 1) & 0xFFFF
        if state.armed:
            pkt = rc_packet(state.roll, state.pitch, state.throttle, state.yaw, state.counter)
        else:
            pkt = rc_packet(NEUTRAL, NEUTRAL, NEUTRAL, NEUTRAL, state.counter)
        udp.transport.sendto(pkt, (DRONE_IP, PORT_CONTROL))

        if now - last_hb >= 1.0:
            udp.transport.sendto(HEARTBEAT, (DRONE_IP, PORT_CONTROL))
            last_hb = now

        # relance régulière du flux vidéo (le drone l'arrête s'il ne reçoit plus la demande)
        if state.video and now - last_rtp >= 0.5:
            udp.transport.sendto(start_rtp_packet(RTP_PORT, state.next_seq()),
                                 (DRONE_IP, PORT_CONTROL))
            last_rtp = now

        while state.pending:
            udp.transport.sendto(state.pending.pop(0), (DRONE_IP, PORT_CONTROL))

        await asyncio.sleep(0.05)   # 20 Hz


# ---------------------------------------------------------------- WebSocket
async def ws_handler(request):
    ws = web.WebSocketResponse(heartbeat=5)
    await ws.prepare(request)
    clients.add(ws)
    try:
        async for msg in ws:
            if msg.type != WSMsgType.TEXT:
                continue
            try:
                m = json.loads(msg.data)
            except Exception:
                continue
            if m.get("t") == "rc":
                state.roll     = m.get("r", NEUTRAL)
                state.pitch    = m.get("p", NEUTRAL)
                state.throttle = m.get("th", NEUTRAL)
                state.yaw      = m.get("y", NEUTRAL)
                state.armed    = bool(m.get("a", 0))
            elif m.get("t") == "cmd":
                handle_cmd(m.get("c"))
            await ws.send_str(json.dumps({
                "t": "status", "drone": (time.time() - state.last_rx) < 2.0}))
    finally:
        clients.discard(ws)
    return ws


def handle_cmd(c):
    if c == "arm":
        state.armed = True
    elif c in ("disarm", "estop"):
        state.armed = False
        state.set_neutral()
    elif c == "video_on":
        state.video = True
    elif c == "video_off":
        state.video = False
    elif c == "takeoff" and CMD_TAKEOFF:
        state.pending.append(module_cmd(*CMD_TAKEOFF, state.next_seq()))
    elif c == "land" and CMD_LAND:
        state.pending.append(module_cmd(*CMD_LAND, state.next_seq()))


# ---------------------------------------------------------------- Vidéo FPV
SDP = f"""v=0
o=- 0 0 IN IP4 0.0.0.0
s=Dobby
c=IN IP4 0.0.0.0
t=0 0
m=video {RTP_PORT} RTP/AVP 96
a=rtpmap:96 H264/90000
"""

async def video_handler(request):
    """
    Transcode le flux RTP/H.264 du drone en MJPEG (multipart) pour le <img> du front.
    Nécessite ffmpeg. Le flux est confirmé H.264 Annex-B ; le type de payload RTP (96)
    peut devoir être ajusté selon une capture réelle.
    """
    sdp_path = HERE / "stream.sdp"
    sdp_path.write_text(SDP)

    proc = await asyncio.create_subprocess_exec(
        "ffmpeg", "-hide_banner", "-loglevel", "error",
        "-protocol_whitelist", "file,udp,rtp",
        "-fflags", "nobuffer", "-flags", "low_delay",
        "-i", str(sdp_path),
        "-f", "mpjpeg", "-q:v", "7", "-r", "20", "pipe:1",
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)

    resp = web.StreamResponse()
    resp.headers["Content-Type"] = "multipart/x-mixed-replace; boundary=ffmpeg"
    resp.headers["Cache-Control"] = "no-cache"
    await resp.prepare(request)
    try:
        while True:
            chunk = await proc.stdout.read(32768)
            if not chunk:
                break
            await resp.write(chunk)
    except (ConnectionResetError, asyncio.CancelledError):
        pass
    finally:
        try:
            proc.kill()
        except Exception:
            pass
    return resp


# ---------------------------------------------------------------- HTTP
async def index(request):
    return web.FileResponse(HERE / "index.html")


async def on_startup(app):
    loop = asyncio.get_event_loop()
    transport, proto = await loop.create_datagram_endpoint(
        UDP, local_addr=("0.0.0.0", LOCAL_PORT))
    app["udp"] = transport
    app["loop_task"] = asyncio.create_task(rc_loop(proto))
    print(f"[pont] UDP local :{LOCAL_PORT} -> drone {DRONE_IP}:{PORT_CONTROL}  | vidéo RTP :{RTP_PORT}")


async def on_cleanup(app):
    app["loop_task"].cancel()
    app["udp"].close()


def main():
    app = web.Application()
    app.router.add_get("/", index)
    app.router.add_get("/ws", ws_handler)
    app.router.add_get("/video.mjpg", video_handler)
    app.router.add_static("/", HERE)
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)
    print(f"[pont] Ouvre http://<ip-de-cette-machine>:{HTTP_PORT} sur le tel (même Wi-Fi)")
    web.run_app(app, host="0.0.0.0", port=HTTP_PORT)


if __name__ == "__main__":
    main()
