package com.picpocket.app.data.store

object MetadataNaming {

    private val pattern = Regex("""metadata\.(\d+)\.(\d+)\.json""")

    fun name(version: Int, passphrase: Int): String = "metadata.$version.$passphrase.json"

    fun parse(name: String): Pair<Int, Int>? =
        pattern.matchEntire(name)?.let { m ->
            (m.groupValues[1].toInt() to m.groupValues[2].toInt())
        }

    fun isMetadata(name: String): Boolean = pattern.matches(name)
}
