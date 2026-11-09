package com.dobby.control

/**
 * Alertes du Dobby, d'après DoFun (engine/b.java g(i) + textes anglais d'origine).
 * Le paquet d'état (module 0x48) contient un champ de 32 bits aux octets 25..28
 * (poids faible en premier) : le bit i levé = alerte i active.
 * « (atterrissage) » = alerte qui fait se poser le drone selon DoFun.
 */
object Warnings {
    private val TEXT = mapOf(
        1 to "Boussole anormale (atterrissage)",
        2 to "Batterie faible (atterrissage)",
        3 to "Batterie très faible (atterrissage)",
        4 to "Panne GPS",
        5 to "Zone interdite de vol : décollage impossible",
        6 to "Défaut du flux optique (caméra de positionnement)",
        7 to "Panne des ultrasons",
        8 to "Panne du baromètre",
        9 to "Panne contrôleur moteur (ESC)",
        10 to "Centrale inertielle (IMU) anormale",
        11 to "Panne de la batterie",
        12 to "Panne de la boussole (atterrissage)",
        13 to "Panne de la mémoire interne",
        15 to "Ultrasons anormaux",
        16 to "Ultrasons anormaux",
        17 to "Panne de l'IMU",
        18 to "Capteur IMU anormal",
        19 to "Signal GPS faible (atterrissage)",
        22 to "Surchauffe du drone",
        23 to "Champ magnétique perturbé (atterrissage)",
        24 to "Boussole anormale ou champ magnétique perturbé (atterrissage)",
        25 to "Baromètre anormal"
    )

    fun describe(w: Long): List<String> =
        (0 until 32).filter { ((w ushr it) and 1L) == 1L }.map { TEXT[it] ?: "alerte n°$it" }

    // 1 et 2 viennent du code de DoFun ; 3 et 5 sont déduits des journaux
    // (en vol → 3 ou 5 → au sol à chaque atterrissage).
    fun flyName(s: Int): String = when (s) {
        1 -> "au sol"
        2 -> "en vol"
        3 -> "atterrissage (3)"
        5 -> "atterrissage (5)"
        else -> "état $s"
    }
}
