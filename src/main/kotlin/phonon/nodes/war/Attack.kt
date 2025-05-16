/**
 * Instance for attacking a chunk
 * - holds state data of attack
 * - functions as runnable thread for attack tick
 */

package phonon.nodes.war

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.util.UUID
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.Location
import org.bukkit.boss.*
import org.bukkit.entity.ArmorStand
import phonon.nodes.Nodes
import phonon.nodes.Config
import phonon.nodes.objects.Coord
import phonon.nodes.objects.TerritoryChunk
import phonon.nodes.objects.Town
import java.util.concurrent.TimeUnit

public class Attack(
    val attacker: UUID,        // attacker's UUID
    val town: Town,            // attacker's town
    val coord: Coord,          // chunk coord under attack
    val flagBase: Block,       // fence base of flag (used for location)
    val flagBlock: Block,      // wool block for flag (used as key in FlagWar.blockToAttacker)
    val flagTorch: Block,      // torch block of flag (used for removal)
    val progressBar: BossBar,  // progress bar
    val attackTime: Long,      // 
    var progress: Long,        // initial progress, current tick count
    val flagNametag: ArmorStand? = null  // nametag displaying info about the flag
): Runnable {
    // Map to store locations and intended BlockData for the visual beacon
    val beaconVisualBlocks: MutableMap<Location, BlockData> = mutableMapOf()
    // Map to store the original blocks replaced by the beacon visual
    val originalBlocks: MutableMap<Location, BlockData> = mutableMapOf()

    // no build region
    val noBuildXMin: Int
    val noBuildXMax: Int
    val noBuildZMin: Int
    val noBuildZMax: Int
    val noBuildYMin: Int
    val noBuildYMax: Int = 255 // temporarily set to height

    var progressColor: Int // index in progress color array
    var thread: ScheduledTask = Bukkit.getAsyncScheduler().runAtFixedRate(Nodes.plugin!!, { this.run() }, FlagWar.ATTACK_TICK * 50, FlagWar.ATTACK_TICK * 50, TimeUnit.MILLISECONDS)

    // re-used json serialization StringBuilders
    val jsonStringBase: StringBuilder
    val jsonString: StringBuilder

    init {
        val flagX = flagBase.x
        val flagY = flagBase.y
        val flagZ = flagBase.z

        // set no build ranges
        this.noBuildXMin = flagX - Config.flagNoBuildDistance
        this.noBuildXMax = flagX + Config.flagNoBuildDistance
        this.noBuildZMin = flagZ - Config.flagNoBuildDistance
        this.noBuildZMax = flagZ + Config.flagNoBuildDistance
        this.noBuildYMin = flagY + Config.flagNoBuildYOffset
        
        // set boss bar progress
        val progressNormalized: Double = this.progress.toDouble() / this.attackTime.toDouble()
        this.progressBar.setProgress(progressNormalized)
        this.progressColor = FlagWar.getProgressColor(progressNormalized)

        // pre-generate main part of the JSON serialization string
        this.jsonStringBase = generateFixedJsonBase(
            this.attacker,
            this.coord,
            this.flagBase
        )
        
        // Initialize the beacon visuals (populate beaconVisualBlocks and originalBlocks)
        // This needs to happen *after* flagBase is available
        FlagWar.createAttackBeacon(
            this, // Pass the Attack instance itself
            flagBase.world,
            coord,
            flagBase.y,
            FlagWar.getProgressColor(progressNormalized),
            true, // createFrame
            true, // createColor
            false // updateLighting (not relevant for packet-based visuals)
        )
        
        // full json StringBuilder, initialize capacity to be
        // base capacity + room for progress ticks length
        val jsonStringBufferSize = this.jsonStringBase.capacity() + 20
        this.jsonString = StringBuilder(jsonStringBufferSize)

        // Initial send of beacon visual to nearby players
        FlagWar.sendBeaconUpdateToNearbyPlayers(this)
    }

    override public fun run() {
        FlagWar.attackTick(this)
    }
    
    public fun cancel() {
        this.thread.cancel()
        
        val attack = this
        Bukkit.getGlobalRegionScheduler().run(Nodes.plugin!!) {
            FlagWar.cancelAttack(attack)
        }
    }

    // returns json format string as a StringBuilder
    // only used with WarSerializer objects
    public fun toJson(): StringBuilder {
        // reset json StringBuilder
        this.jsonString.setLength(0)

        // add base
        this.jsonString.append(this.jsonStringBase)

        // add progress in ticks
        this.jsonString.append("\"p\":${this.progress.toString()}") 
        this.jsonString.append("}")

        return this.jsonString
    }
}

// pre-generate main part of the JSON serialization string
// for the attack which does not change
// (only part that changes is progress)
// parts required for serialization:
// - attacker: player uuid
// - coord: chunk coord
// - block: flag base block (fence)
// - skyBeaconColorBlocks: track blocks in sky beacon -> No longer serialized directly, visual only
// - skyBeaconWireframeBlocks: track blocks in sky beacon -> No longer serialized directly, visual only
private fun generateFixedJsonBase(
    attacker: UUID,
    coord: Coord,
    block: Block
): StringBuilder {
    val s = StringBuilder()

    s.append("{")

    // attacker uuid
    s.append("\"id\":\"${attacker.toString()}\",")

    // chunk coord [c.x, c.z]
    s.append("\"c\":[${coord.x},${coord.z}],")

    // flag base block [b.x, b.y, b.z]
    s.append("\"b\":[${block.x},${block.y},${block.z}],")

    return s
}