package phonon.nodes.war

import kotlinx.serialization.Serializable

@Serializable
data class DynmapWarFlag(
    val id: String, // A unique ID for the flag, e.g., coord.toString()
    val x: Int,
    val y: Int, // Flag base Y
    val z: Int,
    val world: String,
    val attackerTownName: String,
    val attackerNationName: String?,
    val progressNormalized: Double, // Normalized 0.0 to 1.0
    val timeRemainingFormatted: String // Formatted time like "1m 30s"
)