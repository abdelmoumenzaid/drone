#!/usr/bin/env python3
"""
Force le Wi-Fi du ZeroTech Dobby en 2,4 GHz (pour qu'un téléphone qui ne capte
que le 2,4 GHz, comme le Samsung J1, puisse le voir).

À lancer SUR LE PC, pendant qu'il est connecté au Wi-Fi du drone (Dobby-0bdcd5),
même si ce Wi-Fi est en 5 GHz.

    python3 switch_24ghz.py

Commande confirmée (décompilation DoFun) : module 0xE0, cmd 0xC8, param 0x00 = 2,4 GHz.
Après l'envoi, le Wi-Fi du drone se coupe puis revient en 2,4 GHz (le PC perd la
connexion, c'est normal). Le J1 doit alors voir "Dobby-0bdcd5".
"""
import socket
import time

DRONE_IP = "192.168.1.1"
PORT = 7088
HEARTBEAT = bytes([0xA0, 0x05, 0xE0, 0xC0, 0x45])

def checksum(buf):
    return sum(buf[:-1]) & 0xFF

def band_cmd(param, seq):
    # A0 08 E0 C8 <seq_hi> <seq_lo> <param> <chk>
    b = bytearray(8)
    b[0] = 0xA0; b[1] = 8; b[2] = 0xE0; b[3] = 0xC8
    b[4] = (seq >> 8) & 0xFF; b[5] = seq & 0xFF
    b[6] = param & 0xFF
    b[7] = checksum(b)
    return bytes(b)

def main():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(0.2)
    print("Établissement de la liaison avec le drone…")
    # quelques heartbeats pour réveiller la liaison
    for _ in range(20):
        s.sendto(HEARTBEAT, (DRONE_IP, PORT))
        time.sleep(0.05)

    print("Envoi de la commande : passage en 2,4 GHz…")
    seq = 1
    pkt = band_cmd(0x00, seq)          # 0x00 = 2,4 GHz
    print("  paquet :", pkt.hex(" "))
    for _ in range(5):                 # envoyé plusieurs fois par sécurité
        s.sendto(pkt, (DRONE_IP, PORT))
        time.sleep(0.2)

    print()
    print("Fait. Le Wi-Fi du drone va se couper puis revenir en 2,4 GHz.")
    print("Attends ~30 s, puis cherche 'Dobby-0bdcd5' dans le Wi-Fi du Samsung J1")
    print("(mot de passe : zerotech).")
    s.close()

if __name__ == "__main__":
    main()
