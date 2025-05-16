/**
 * Flag war implementation conceptually based on
 * Towny flag war:
 * https://github.com/TownyAdvanced/Towny/tree/master/src/com/palmergames/bukkit/towny/war/flagwar
 * 
 * War handled by placing a "flag" block onto a chunk to start
 * a "conquer" timer. When timer ends, the chunk is claimed
 * by the attacker's town.
 * 
 * When a territory's core is taken, the territory is converted
 * to "occupied" status by the attacking town.
 * 
 * Flag block object:
 *     i       <- torch for light (so players can see it)
 *    [ ]      <- wool beacon block (destroy to cancel)
 *     |       <- initial item placed to start claim
 * 
 * ----
 * i dont even fully understand save architecture anymore after 6 months
 * hope this doesnt break during war time :^)
 * t. xeth
 */

package phonon.nodes.war

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.command.CommandSender
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import phonon.nodes.Config
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.constants.*
import phonon.nodes.event.WarAttackCancelEvent
import phonon.nodes.event.WarAttackFinishEvent
import phonon.nodes.event.WarAttackStartEvent
import phonon.nodes.objects.Coord
import phonon.nodes.objects.Territory
import phonon.nodes.objects.TerritoryChunk
import phonon.nodes.objects.Town
import java.nio.file.Files
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

//import phonon.blockedit.FastBlockEditSession

// beacon color: wool material data values
// corresponding to each 10% progress interval
// 1.12 wool block data
private val WOOL_COLORS: Array<Byte> = arrayOf(
    15,  // black         [0.0, 0.1]
    7,   // gray          [0.1, 0.2]
    8,   // light gray    [0.2, 0.3]
    11,  // blue          [0.3, 0.4]
    10,  // purple        [0.4, 0.5]
    2,   // magenta       [0.5, 0.6]
    6,   // pink          [0.6, 0.7]
    14,  // red           [0.7, 0.8]
    1,   // orange        [0.8, 0.9]
    4    // yellow        [0.9, 1.0]
)

// New Deepslate progression for FLAG_COLORS
private val FLAG_COLORS: Array<Material> = arrayOf(
    Material.COBBLED_DEEPSLATE,      // [0.0, 0.1]
    Material.DEEPSLATE_BRICKS,       // [0.1, 0.2]
    Material.DEEPSLATE_TILES,        // [0.2, 0.3]
    Material.POLISHED_DEEPSLATE,     // [0.3, 0.4]
    Material.CHISELED_DEEPSLATE,     // [0.8, 0.9]
    Material.CRACKED_DEEPSLATE_BRICKS // [0.9, 1.0] - Final state
)

// private val BEACON_COLOR_BLOCK = Material.WOOL     // 1.12 only
// private val BEACON_EDGE_BLOCK = Material.GLOWSTONE // 1.12 use glowstone
private val SKY_BEACON_FRAME_BLOCK = Material.MAGMA_BLOCK // 1.16 use magma

// Updated SKY_BEACON_MATERIALS with Deepslate
private val SKY_BEACON_MATERIALS: EnumSet<Material> = EnumSet.of(
    SKY_BEACON_FRAME_BLOCK, // Still MAGMA_BLOCK by default
    // Add all materials from the new FLAG_COLORS array
    Material.COBBLED_DEEPSLATE,
    Material.DEEPSLATE_BRICKS,
    Material.DEEPSLATE_TILES,
    Material.POLISHED_DEEPSLATE,
    Material.COBBLED_DEEPSLATE_WALL,
    Material.DEEPSLATE_BRICK_WALL,
    Material.DEEPSLATE_TILE_WALL,
    Material.POLISHED_DEEPSLATE_WALL,
    Material.CHISELED_DEEPSLATE,
    Material.CRACKED_DEEPSLATE_BRICKS
)

/**
 * Set flag colored block (Now sets Deepslate variant)
 * 
 * Color based on attack progress
 */
private fun setFlagAttackColorBlock(block: Block, progressColorIndex: Int) {
    val index = max(0, min(FLAG_COLORS.size - 1, progressColorIndex))
    block.setType(FLAG_COLORS[index])
}

public object FlagWar {

    // ============================================
    // war settings
    // ============================================
    // flag that war is turned on
    internal var enabled: Boolean = false

    // allow annexing territories during war (disable for border skirmishes)
    internal var canAnnexTerritories: Boolean = false

    // only allow attacking border territories, cannot go deeper in
    internal var canOnlyAttackBorders: Boolean = false

    // TODO: war permissions, can create/destroy during war
    internal var destructionEnabled: Boolean = false

    // ticks for the save task
    public var saveTaskPeriod: Long = 1
    // ============================================

    // minecraft plugin variable
    internal var plugin: JavaPlugin? = null

    // flag items that can be used to claim during war
    internal val flagMaterials: EnumSet<Material> = EnumSet.noneOf(Material::class.java)

    // flag sky beacon size, must be in [2, 16]
    internal var skyBeaconSize: Int = 6

    // map attacker UUID -> List of Attack instances
    internal val attackers: HashMap<UUID, ArrayList<Attack>> = hashMapOf()

    // map chunk -> Attack instance
    internal val chunkToAttacker: ConcurrentHashMap<Coord, Attack> = ConcurrentHashMap()

    // map flag block -> Attack instance (for cancelling attacks)
    internal val blockToAttacker: HashMap<Block, Attack> = hashMapOf()

    // set of all occupied chunks
    internal val occupiedChunks: MutableSet<Coord> = ConcurrentHashMap.newKeySet() // create concurrent set from ConcurrentHashMap

    // attack/flag update tick interval
    internal val ATTACK_TICK: Long = 20

    // flag that save required
    internal var needsSave: Boolean = false

    // periodic task to check for save
    internal var saveTask: ScheduledTask? = null

    // Configurable distance squared for sending block updates
    private const val BEACON_VIEW_DISTANCE_DEFAULT = 64.0
    private var beaconViewDistanceSquared = BEACON_VIEW_DISTANCE_DEFAULT.pow(2)

    public fun initialize(flagMaterials: EnumSet<Material>) {
        FlagWar.flagMaterials.addAll(flagMaterials)
        FlagWar.skyBeaconSize = Math.max(2, Math.min(16, Config.flagBeaconSize))
    }

    /**
     * Print info to sender about current war state
     */
    public fun printInfo(sender: CommandSender, detailed: Boolean = false) {
        val status = if ( Nodes.war.enabled == true ) "enabled" else "${ChatColor.GRAY}disabled"
        Message.print(sender, "${ChatColor.BOLD}Nodes war status: ${status}")
        if ( Nodes.war.enabled ) {
            Message.print(sender, "- Can Annex Territories${ChatColor.WHITE}: ${Nodes.war.canAnnexTerritories}")
            Message.print(sender, "- Can Only Attack Borders${ChatColor.WHITE}: ${Nodes.war.canOnlyAttackBorders}")
            Message.print(sender, "- Destruction Enabled${ChatColor.WHITE}: ${Nodes.war.destructionEnabled}")
            if ( detailed ) {
                Message.print(sender, "- Using Towns Whitelist${ChatColor.WHITE}: ${Config.warUseWhitelist}")
                Message.print(sender, "- Can leave town${ChatColor.WHITE}: ${Config.canLeaveTownDuringWar}")
                Message.print(sender, "- Can create town${ChatColor.WHITE}: ${Config.canCreateTownDuringWar}")
                Message.print(sender, "- Can destroy town${ChatColor.WHITE}: ${Config.canDestroyTownDuringWar}")
                Message.print(sender, "- Annex disabled${ChatColor.WHITE}: ${Config.annexDisabled}")
            }
        }
    }

    /**
     * Async save loop task
     */
    internal object SaveLoop: Runnable {
        override public fun run() {
            if ( FlagWar.needsSave ) {
                FlagWar.needsSave = false
                WarSerializer.save(true)
            }
        }
    }

    /**
     * Load war state from .json file
     */
    internal fun load() {
        // clear all maps
        FlagWar.attackers.clear()
        FlagWar.chunkToAttacker.clear()
        FlagWar.blockToAttacker.clear()
        FlagWar.occupiedChunks.clear()

        if ( Files.exists(Config.pathWar) ) {
            WarDeserializer.fromJson(Config.pathWar)
        }

        if ( FlagWar.enabled ) {
            if ( FlagWar.canOnlyAttackBorders ) {
                Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes border skirmishing enabled")
            }
            else {
                Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes war enabled")
            }
        }
    }

    /**
     * Load an occupied chunk from json
     */
    internal fun loadOccupiedChunk(townName: String, coord: Coord) {
        // get town
        val town = Nodes.towns.get(townName)
        if ( town == null ) {
            return
        }

        // get territory chunk
        val terrChunk = Nodes.getTerritoryChunkFromCoord(coord)
        if ( terrChunk == null ) {
            return
        }

        // mark chunk occupied
        terrChunk.occupier = town
        FlagWar.occupiedChunks.add(terrChunk.coord)
    }

    // load an in-progress attack from json
    // attacker - player UUID
    // coord - chunk coord
    // flagBase - flag fence block
    // progress - current progress in ticks
    internal fun loadAttack(
        attacker: UUID,
        coord: Coord,
        flagBase: Block,
        progress: Long
    ) {
        // get resident and their town
        val attackerResident = Nodes.getResidentFromUUID(attacker)
        if (attackerResident == null) {
            Nodes.logger?.warning("[WarLoad] Could not find resident for attacker UUID: $attacker")
            // Should we try to clean up the invalid flag blocks?
            // flagBase.getRelative(0,1,0).setType(Material.AIR) // flagBlock
            // flagBase.getRelative(0,2,0).setType(Material.AIR) // flagTorch
            // flagBase.setType(Material.AIR)
            return
        }
        val attackingTown = attackerResident.town
        if (attackingTown == null) {
             Nodes.logger?.warning("[WarLoad] Resident ${attackerResident.name} has no town, cannot load attack at $coord.")
            // Clean up flag blocks?
            return
        }

        // get territory chunk
        val chunk = Nodes.getTerritoryChunkFromCoord(coord)
        if (chunk == null) {
            Nodes.logger?.warning("[WarLoad] Could not find territory chunk at $coord, cannot load attack.")
             // Clean up flag blocks?
            return
        }

        // Validate flag blocks exist before creating attack
        val flagBlock = flagBase.getRelative(0,1,0)
        val flagTorch = flagBase.getRelative(0,2,0)
        if (flagBlock.type == Material.AIR || flagTorch.type == Material.AIR) {
             Nodes.logger?.warning("[WarLoad] Flag blocks missing at ${flagBase.location}, cannot load attack.")
             // Ensure base is also cleared if partial flag exists
             flagBase.setType(Material.AIR)
             return
        }

        // Call createAttack - it now handles beacon creation internally
        // No longer pass beacon lists
        try {
            createAttack(
                attacker,
                attackingTown,
                chunk,
                flagBase,
                progress // Pass initial progress
            )
        } catch (e: Exception) {
            Nodes.logger?.severe("[WarLoad] Exception during createAttack for $coord: ${e.message}")
            // Attempt cleanup
             try {
                flagTorch.setType(Material.AIR)
                flagBlock.setType(Material.AIR)
                flagBase.setType(Material.AIR)
             } catch (cleanupEx: Exception) {
                // Ignore cleanup exception
             }
        }
    }

    // cleanup when Nodes plugin disabled
    internal fun cleanup() {
        // stop save task
        FlagWar.saveTask?.cancel()
        FlagWar.saveTask = null

        // remove all progress bars from players
        for ( attack in FlagWar.chunkToAttacker.values ) {
            attack.progressBar.removeAll()
        }

        // save current war state
        if ( Nodes.initialized && FlagWar.enabled ) {
            WarSerializer.save(false)
        }

        // disable war
        FlagWar.enabled = false

        // iterate chunks and stop current attacks
        for ( (coord, attack) in FlagWar.chunkToAttacker ) {
            val chunk = Nodes.getTerritoryChunkFromCoord(coord)
            if ( chunk !== null ) {
                chunk.attacker = null
                chunk.occupier = null
            }
            attack.thread.cancel()
            FlagWar.cancelAttack(attack)
        }

        // clear occupied chunks
        for ( coord in FlagWar.occupiedChunks ) {
            val chunk = Nodes.getTerritoryChunkFromCoord(coord)
            if ( chunk !== null ) {
                chunk.attacker = null
                chunk.occupier = null
            }
        }

        // clear all maps
        FlagWar.attackers.clear()
        FlagWar.chunkToAttacker.clear()
        FlagWar.blockToAttacker.clear()
        FlagWar.occupiedChunks.clear()
    }

    /**
     * Enable war, set war state flags
     */
    internal fun enable(canAnnexTerritories: Boolean, canOnlyAttackBorders: Boolean, destructionEnabled: Boolean) {
        FlagWar.enabled = true
        FlagWar.canAnnexTerritories = canAnnexTerritories
        FlagWar.canOnlyAttackBorders = canOnlyAttackBorders
        FlagWar.destructionEnabled = destructionEnabled

        // create task
        FlagWar.saveTask?.cancel()
        FlagWar.saveTask = Bukkit.getAsyncScheduler().runAtFixedRate(Nodes.plugin!!, {SaveLoop.run()}, FlagWar.saveTaskPeriod, FlagWar.saveTaskPeriod, TimeUnit.SECONDS)
    }

    /**
     * Disable war, cleanup war state
     */
    internal fun disable() {
        FlagWar.enabled = false
        FlagWar.canAnnexTerritories = false
        
        // kill save task
        FlagWar.saveTask?.cancel()
        FlagWar.saveTask = null
        
        // iterate chunks and stop current attacks
        for ( (coord, attack) in FlagWar.chunkToAttacker ) {
            val chunk = Nodes.getTerritoryChunkFromCoord(coord)
            if ( chunk !== null ) {
                chunk.attacker = null
                chunk.occupier = null
            }
            attack.thread.cancel()
            FlagWar.cancelAttack(attack)
        }

        // clear occupied chunks
        for ( coord in FlagWar.occupiedChunks ) {
            val chunk = Nodes.getTerritoryChunkFromCoord(coord)
            if ( chunk !== null ) {
                chunk.attacker = null
                chunk.occupier = null
            }
        }

        // clear all maps
        FlagWar.attackers.clear()
        FlagWar.chunkToAttacker.clear()
        FlagWar.blockToAttacker.clear()
        FlagWar.occupiedChunks.clear()

        // save war.json (empty)
        WarSerializer.save(true)
    }

    // initiate attack on a territory chunk:
    // 1. check chunk is valid target, flag placement valid,
    //    and player can attack
    // 2. create and run attack timer thread
    internal fun beginAttack(attacker: UUID, attackingTown: Town, chunk: TerritoryChunk, flagBase: Block): Result<Attack> {
        val world = flagBase.world
        val flagBaseX = flagBase.x
        val flagBaseY = flagBase.y
        val flagBaseZ = flagBase.z
        val territory = chunk.territory
        val territoryTown = territory.town

        // run checks that chunk attack is valid
        
        // check chunk has a town
        if ( territoryTown === null ) {
            return Result.failure(ErrorNotEnemy)
        }

        // check if town blacklisted
        if ( Config.warUseBlacklist && Config.warBlacklist.contains(territoryTown.uuid) ) {
            return Result.failure(ErrorTownBlacklisted)
        }

        // check if town not whitelisted
        if ( Config.warUseWhitelist ) {
            if ( !Config.warWhitelist.contains(territoryTown.uuid) || (Config.onlyWhitelistCanClaim && !Config.warWhitelist.contains(attackingTown.uuid)) ) {
                return Result.failure(ErrorTownNotWhitelisted)
            }
        }

        // check chunk not currently under attack
        if ( chunk.attacker !== null ) {
            return Result.failure(ErrorAlreadyUnderAttack)
        }
        
        // check chunk not already captured by town or allies
        if ( chunkAlreadyCaptured(chunk, territory, attackingTown) ) {
            return Result.failure(ErrorAlreadyCaptured)
        }

        // check chunk either:
        // 1. belongs to enemy
        // 2. town chunk occupied by enemy
        // 3. allied chunk occupied by enemy
        if ( chunkIsEnemy(chunk, territory, attackingTown) ) {

            // check for only attacking border territories
            if ( FlagWar.canOnlyAttackBorders && !FlagWar.isBorderTerritory(territory) ) {
                return Result.failure(ErrorNotBorderTerritory)
            }

            // check that chunk valid, either:
            // 1. next to wilderness
            // 2. next to occupied chunk (by town or allies)
            if ( FlagWar.chunkIsAtEdge(chunk, attackingTown) == false ) {
                return Result.failure(ErrorChunkNotEdge)
            }

            // check that there is room to create flag
            if ( flagBaseY >= world.maxHeight - 2 ) { // need room for wool + torch
                return Result.failure(ErrorFlagTooHigh)
            }

            // check flag has vision to sky
            for ( y in flagBaseY+1 until world.maxHeight ) {
                if ( !world.getBlockAt(flagBaseX, y, flagBaseZ).type.isAir ) {
                    return Result.failure(ErrorSkyBlocked)
                }
            }

            // attacker's current attacks (if any exist)
            var currentAttacks = FlagWar.attackers.get(attacker)
            if ( currentAttacks == null ) {
                currentAttacks = ArrayList(Config.maxPlayerChunkAttacks) // set initial capacity = max attacks
                FlagWar.attackers.put(attacker, currentAttacks)
            }
            else if ( currentAttacks.size >= Config.maxPlayerChunkAttacks ) {
                return Result.failure(ErrorTooManyAttacks)
            }

            // send attack event, allow other plugins to custom cancel flag attack
            val event = WarAttackStartEvent(
                attacker,
                attackingTown,
                territory,
                flagBase
            )
            Bukkit.getPluginManager().callEvent(event);
            if ( event.isCancelled() ) {
                return Result.failure(ErrorAttackCustomCancel)
            }

            val attack = createAttack(
                attacker,
                attackingTown,
                chunk,
                flagBase,
                0L
            )
            
            // mark that save required
            FlagWar.needsSave = true

            return Result.success(attack)
        }
        else {
            return Result.failure(ErrorNotEnemy)
        }
    }
    
    // actually creates attack instance
    // shared between beginAttack() and loadAttack()
    internal fun createAttack(
        attacker: UUID,
        attackingTown: Town,
        chunk: TerritoryChunk,
        flagBase: Block,
        progress: Long
    ): Attack {
        val world = flagBase.world
        val flagBaseX = flagBase.x
        val flagBaseY = flagBase.y
        val flagBaseZ = flagBase.z
        val territory = chunk.territory

        val flagBlock = world.getBlockAt(flagBaseX, flagBaseY + 1, flagBaseZ)
        val flagTorch = world.getBlockAt(flagBaseX, flagBaseY + 2, flagBaseZ)
        val progressBar = Bukkit.getServer().createBossBar(
            "Attacking ${territory.town?.name ?: "territory"} at ($flagBaseX, $flagBaseZ)",
             BarColor.YELLOW, 
             BarStyle.SOLID
        )
        var attackTime = Config.chunkAttackTime.toDouble()
        if ( territory.bordersWilderness ) {
            attackTime *= Config.chunkAttackFromWastelandMultiplier
        }
        if ( territory.id == territory.town?.home ) {
            attackTime *= Config.chunkAttackHomeMultiplier
        }

        // Create and configure flag nametag
        val flagNametagLocation = flagTorch.location.clone().add(0.5, 0.5, 0.5) // Adjusted Y: Torch_Y + 0.5 = Base_Y + 2.5
        val flagNametag = world.spawn(flagNametagLocation, ArmorStand::class.java).apply {
            isVisible = false
            isSmall = true
            isMarker = true
            isCustomNameVisible = true
            
            // Initial Nametag Format Setup
            val attackerPlayer = Nodes.getResidentFromUUID(attacker)
            val attackerName = attackerPlayer?.name ?: "Unknown"
            val nationName = attackingTown.nation?.name
            val townName = attackingTown.name
            val timeRemaining = formatTimeRemaining(max(0, attackTime.toLong() - progress))
            
            val prefix = if (nationName != null) {
                "${ChatColor.GRAY}[${ChatColor.DARK_PURPLE}${nationName}${ChatColor.GRAY}|${ChatColor.GREEN}${townName}${ChatColor.GRAY}]"
            } else {
                "${ChatColor.GRAY}[${ChatColor.GREEN}${townName}${ChatColor.GRAY}]"
            }
            customName = "${prefix} ${ChatColor.GOLD}${attackerName} ${ChatColor.GRAY}| ${ChatColor.YELLOW}${timeRemaining}"
            
            setGravity(false)
        }

        // Initialize physical flag blocks (using the deepslate setter now)
        if (flagBlock.type.isAir) {
             setFlagAttackColorBlock(flagBlock, 0) // Set initial deepslate variant
        }
        if (flagTorch.type.isAir) {
             flagTorch.setType(Material.TORCH)
        }
        if (!Config.flagMaterials.contains(flagBase.type)) {
            flagBase.setType(Config.flagMaterialDefault)
        }

        // create new attack instance
        val attack = Attack(
            attacker,
            attackingTown,
            chunk.coord,
            flagBase,
            flagBlock,
            flagTorch,
            progressBar,
            attackTime.toLong(),
            progress,
            flagNametag
        )

        // mark territory chunk under attack
        chunk.attacker = attackingTown

        // enable boss bar for player
        val player = Bukkit.getPlayer(attacker)
        if ( player != null ) {
            attack.progressBar.addPlayer(player)
        }

        // add attack to list of attacks by attacker
        var currentAttacks = FlagWar.attackers.get(attacker)
        if ( currentAttacks == null ) {
            currentAttacks = ArrayList(Config.maxPlayerChunkAttacks) // set initial capacity = max attacks
            FlagWar.attackers.put(attacker, currentAttacks)
        }
        currentAttacks.add(attack)

        // map chunk to the attack
        FlagWar.chunkToAttacker.put(chunk.coord, attack)

        // map flag block to attack (for breaking)
        FlagWar.blockToAttacker.put(flagBlock, attack)

        return attack
    }
    
    // check if territory is a border territory of a town, requirements:
    // any adjacent territory is not of the same town
    internal fun isBorderTerritory(territory: Territory): Boolean {
        // do not allow attacking home territory
        val territoryTown = territory.town
        if ( territoryTown !== null && territoryTown.home == territory.id ) {
            return false
        }

        // territory borders wilderness (no territories)
        if ( territory.bordersWilderness ) {
            return true
        }

        // otherwise, check if any neighbor territory is not owned by the town
        for ( neighborTerritoryId in territory.neighbors ) {
            val neighborTerritory = Nodes.territories[neighborTerritoryId]
            if ( neighborTerritory !== null && neighborTerritory.town !== territoryTown ) {
                return true
            }
        }

        return false
    }

    // check if chunk was already captured
    // 1. territory occupied by town or allies and chunk not occupied
    // 2. chunk occupied by town or allies
    internal fun chunkAlreadyCaptured(chunk: TerritoryChunk, territory: Territory, attackingTown: Town): Boolean {
        val territoryOccupier = territory.occupier
        val chunkOccupier = chunk.occupier

        if ( territoryOccupier === attackingTown || attackingTown.allies.contains(territoryOccupier) ) {
            if ( !attackingTown.enemies.contains(chunkOccupier) ) {
                return true
            }
        }
        
        if ( chunkOccupier !== null ) {
            if ( chunkOccupier === attackingTown || attackingTown.allies.contains(chunkOccupier) ) {
                return true
            }
        }

        return false
    }

    // check chunk belongs to an enemy and can be attacked:
    // 1. belongs to enemy town
    // 2. town chunk occupied by enemy
    // 3. allied chunk occupied by enemy
    // 4. town's occupied territory, chunk occupied by enemy
    // 5. ally's occupied territory, chunk occupied by enemy
    internal fun chunkIsEnemy(chunk: TerritoryChunk, territory: Territory, attackingTown: Town): Boolean {
        if ( attackingTown.enemies.contains(territory.town) ) {
            return true
        }

        val attackingNation = attackingTown.nation
        val territoryNation = territory.town?.nation

        // your town, nation, or ally town chunk occupied by enemy
        if ( ( territory.town === attackingTown ) ||
             ( attackingNation !== null && attackingNation === territoryNation) ||
             ( attackingTown.allies.contains(territory.town) )
        ) {
            if ( attackingTown.enemies.contains(territory.occupier) ) {
                return true
            }
            if ( attackingTown.enemies.contains(chunk.occupier) ) {
                return true
            }
        }

        // your occupied territory or ally's occupied territory
        // chunk occupied by enemy
        val occupier = territory.occupier
        val occupierNation = occupier?.nation
        if ( occupier === attackingTown ||
             (attackingNation !== null && attackingNation === occupierNation ) ||
             attackingTown.allies.contains(occupier)
        ) {
            if ( attackingTown.enemies.contains(chunk.occupier) ) {
                return true
            }
        }

        return false
    }

    // check that chunk valid, either:
    // 1. next to wilderness
    // 2. next to occupied chunk (by town or allies)
    internal fun chunkIsAtEdge(chunk: TerritoryChunk, attackingTown: Town): Boolean {
        val coord = chunk.coord

        val chunkNorth = Nodes.getTerritoryChunkFromCoord(Coord(coord.x, coord.z - 1))
        val chunkSouth = Nodes.getTerritoryChunkFromCoord(Coord(coord.x, coord.z + 1))
        val chunkWest = Nodes.getTerritoryChunkFromCoord(Coord(coord.x - 1, coord.z))
        val chunkEast = Nodes.getTerritoryChunkFromCoord(Coord(coord.x + 1, coord.z))

        if ( canAttackFromNeighborChunk(chunkNorth, attackingTown) ||
             canAttackFromNeighborChunk(chunkSouth, attackingTown) ||
             canAttackFromNeighborChunk(chunkWest, attackingTown) ||
             canAttackFromNeighborChunk(chunkEast, attackingTown) ) {
            return true
        }

        return false
    }

    /**
     * conditions for attacking a chunk relative to a neighbor chunk
     */
    internal fun canAttackFromNeighborChunk(neighborChunk: TerritoryChunk?, attacker: Town): Boolean {
        
        // no territory here
        if ( neighborChunk === null ) {
            return true
        }

        val attackerNation = attacker.nation

        val neighborTerritory = neighborChunk.territory
        val neighborTown = neighborTerritory.town
        val neighborTerritoryOccupier = neighborTerritory.occupier
        val neighborChunkOccupier = neighborChunk.occupier

        // territory is unoccupied
        if ( neighborTown === null ) {
            return true
        }

        // neighbor is your town and occupier is friendly
        if ( neighborTown === attacker ) {
            if ( neighborTerritoryOccupier === null ) {
                return true
            }
            else if ( attacker.allies.contains(neighborTerritoryOccupier) ) {
                return true
            }
        }
        
        // you are neighbor territory occupier or an ally is the occupier
        if ( neighborTerritoryOccupier === attacker || attacker.allies.contains(neighborTerritoryOccupier) ) {
            return true
        }

        // you or an ally is occupying the neighboring chunk
        if ( neighborChunkOccupier === attacker || attacker.allies.contains(neighborChunkOccupier) == true ) {
            return true
        }

        if ( attackerNation !== null ) {
            val neighborNation = neighborTown.nation
            val neighborTerritoryOccupierNation = neighborTerritoryOccupier?.nation
            val neighborChunkOccupierNation = neighborChunk.occupier?.nation

            // additional neighbor town check, when occupier is in same nation (somehow)
            if ( neighborTown === attacker && neighborNation === neighborTerritoryOccupierNation ) {
                return true
            }

            // neighboring chunk belongs to nation and occupied by friendly
            if ( attackerNation === neighborNation ) {
                if ( neighborTerritoryOccupier === null ) {
                    return true
                }
                else if ( attacker.allies.contains(neighborTerritoryOccupier) ) {
                    return true
                }
            }

            if ( attackerNation === neighborTerritoryOccupierNation ) {
                return true
            }

            if ( attackerNation === neighborChunkOccupierNation ) {
                return true
            }
        }

        return false
    }

    /**
     * Populates the visual beacon block data for an attack.
     * Does NOT send any packets or change server blocks.
     */
    internal fun createAttackBeacon(
        attack: Attack, // Pass the Attack instance
        world: World,
        coord: Coord,
        flagBaseY: Int,
        progressColorIndex: Int, // Use index directly
        createFrame: Boolean,
        createColor: Boolean,
        updateLighting: Boolean // Parameter kept for signature consistency but not used
    ) {
        attack.beaconVisualBlocks.clear()
        attack.originalBlocks.clear()

        // Ensure color index is valid
        val validProgressColorIndex = max(0, min(FLAG_COLORS.size - 1, progressColorIndex))

        val frameData = SKY_BEACON_FRAME_BLOCK.createBlockData()
        val colorData = FLAG_COLORS[validProgressColorIndex].createBlockData()

        // get starting corner
        val size = FlagWar.skyBeaconSize
        val startPositionInChunk: Int = (16 - size) / 2
        val x0: Int = coord.x * 16 + startPositionInChunk
        val z0: Int = coord.z * 16 + startPositionInChunk
        val y0: Int = max(flagBaseY + Config.flagBeaconSkyLevel, Config.flagBeaconMinSkyLevel)
        val xEnd: Int = x0 + size - 1
        val zEnd: Int = z0 + size - 1
        val yEnd: Int = min(world.maxHeight - 1, y0 + size - 1) // Use world height, ensure valid range

        for (y in y0..yEnd) {
            for (x in x0..xEnd) {
                for (z in z0..zEnd) {
                    val loc = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
                    val currentBlockData: BlockData
                    try {
                        // Getting block data can sometimes fail near world borders or unloaded chunks
                         currentBlockData = world.getBlockData(x, y, z)
                    } catch (e: Exception) {
                        Nodes.logger?.warning("[WarBeacon] Failed to get block data at $x, $y, $z: ${e.message}")
                        continue // Skip this block
                    }
                    
                    // Store original block data *before* deciding beacon type
                    attack.originalBlocks[loc] = currentBlockData

                    // Only proceed if the block is replaceable (air or existing beacon part)
                    if (currentBlockData.material == Material.AIR || SKY_BEACON_MATERIALS.contains(currentBlockData.material)) {
                        if (((y == y0 || y == yEnd) && (x == x0 || x == xEnd || z == z0 || z == zEnd)) || // end caps edges
                            ((x == x0 || x == xEnd) && (z == z0 || z == zEnd))) { // middle section corners
                            if (createFrame) {
                                attack.beaconVisualBlocks[loc] = frameData
                            }
                        } else { // color block
                            if (createColor) {
                                attack.beaconVisualBlocks[loc] = colorData
                            }
                        }
                    } else {
                        // If the block is not replaceable, remove it from originalBlocks
                        // so we don't try to revert it later
                        attack.originalBlocks.remove(loc)
                    }
                }
            }
        }
    }

    /**
     * Sends the current state of the beacon visuals to nearby players.
     */
    internal fun sendBeaconUpdateToNearbyPlayers(attack: Attack) {
        sendBlockChangesToNearbyPlayers(attack.flagBase.location, attack.beaconVisualBlocks)
    }

    /**
     * Sends the original block states to nearby players to remove the beacon visual.
     */
    internal fun removeBeaconVisualsForNearbyPlayers(attack: Attack) {
        // Send the original blocks back to the clients
        sendBlockChangesToNearbyPlayers(attack.flagBase.location, attack.originalBlocks)
        // Clear the maps after sending the removal update
        attack.beaconVisualBlocks.clear()
        attack.originalBlocks.clear()
    }
    
    /**
     * Helper function to send a map of block changes to nearby players.
     */
    private fun sendBlockChangesToNearbyPlayers(center: Location, changes: Map<Location, BlockData>) {
        if (changes.isEmpty()) return // Nothing to send

        val world = center.world ?: return // Safety check
        
        // Use a view distance check appropriate for squared distance
        val nearbyPlayers = world.players.filter { 
            it.world == world && it.location.distanceSquared(center) < beaconViewDistanceSquared 
        }
        
        // Ensure the map type matches the API (Paper 1.16.5+ wants Map<Location, BlockData>)
        val blockDataMap: Map<Location, BlockData> = changes

        nearbyPlayers.forEach { player ->
            try {
                // Explicitly pass the map with the correct type
                player.sendMultiBlockChange(blockDataMap, false) // false = do not queue (send immediately)
            } catch (e: Exception) {
                 Nodes.logger?.warning("[FlagWar] Failed to send multi-block change packet for player ${player.name}: ${e.message}")
            }
        }
    }

    /**
     * Update flag colors blocks based on progress color
     * Now updates the visual map and sends packets.
     */
    internal fun updateAttackFlag(
        attack: Attack,
        progressColorIndex: Int
    ) {
        // Ensure color index is valid
        val validProgressColorIndex = max(0, min(FLAG_COLORS.size - 1, progressColorIndex))
        val newColorData = FLAG_COLORS[validProgressColorIndex].createBlockData() // Uses new Deepslate FLAG_COLORS
        val flagLoc = attack.flagBlock.location // Location of the main flag block

        // Update the central flag block visual ONLY if it's part of the visual map
        if (attack.beaconVisualBlocks.containsKey(flagLoc)) { 
             val currentVisualData = attack.beaconVisualBlocks[flagLoc]
             // Check if current block is one of the Deepslate variants in FLAG_COLORS
             if (currentVisualData != null && FLAG_COLORS.any { it == currentVisualData.material }) {
                  attack.beaconVisualBlocks[flagLoc] = newColorData
             } 
             // Optional: Force update even if it wasn't a color block?
             // else { attack.beaconVisualBlocks[flagLoc] = newColorData }
        } 
        // Optional: Add if not present?
        // else { attack.beaconVisualBlocks[flagLoc] = newColorData } 
       
        // Update beacon color blocks in the map (excluding the central flag block)
        val locationsToUpdate = attack.beaconVisualBlocks.filter { (loc, data) -> 
             loc != flagLoc && 
             FLAG_COLORS.any { it == data.material } // Update only blocks that are currently a Deepslate color variant
        }.keys

        locationsToUpdate.forEach { loc ->
            attack.beaconVisualBlocks[loc] = newColorData
        }

        // Send the updated visuals
        sendBeaconUpdateToNearbyPlayers(attack)
    }

    internal fun cancelAttack(attack: Attack) {
        // remove status from territory chunk
        val chunk = Nodes.getTerritoryChunkFromCoord(attack.coord)
        chunk?.attacker = null

        // remove progress bar from player
        attack.progressBar.removeAll()

        // Remove physical flag blocks (these were actually placed)
        attack.flagTorch.setType(Material.AIR)
        attack.flagBlock.setType(Material.AIR)
        attack.flagBase.setType(Material.AIR)

        // Remove flag nametag
        attack.flagNametag?.remove()

        // Remove sky beacon VISUALS using packets
        removeBeaconVisualsForNearbyPlayers(attack)

        // remove attack instance references
        FlagWar.attackers.get(attack.attacker)?.remove(attack)
        FlagWar.chunkToAttacker.remove(attack.coord)
        FlagWar.blockToAttacker.remove(attack.flagBlock)

        // mark save needed
        FlagWar.needsSave = true

        // run cancel attack event
        if (chunk !== null) {
            val event = WarAttackCancelEvent(
                attack.attacker,
                attack.town,
                chunk.territory,
                attack.flagBase
            )
            Bukkit.getPluginManager().callEvent(event)
        }
    }

    internal fun finishAttack(attack: Attack) {
        // remove progress bar from player
        attack.progressBar.removeAll()

        // Remove physical flag blocks
        attack.flagTorch.setType(Material.AIR)
        attack.flagBlock.setType(Material.AIR)
        attack.flagBase.setType(Material.AIR)

        // Remove flag nametag
        attack.flagNametag?.remove()

        // Remove sky beacon VISUALS using packets
        removeBeaconVisualsForNearbyPlayers(attack)

        // remove attack instance references
        FlagWar.attackers.get(attack.attacker)?.remove(attack)
        FlagWar.chunkToAttacker.remove(attack.coord)
        FlagWar.blockToAttacker.remove(attack.flagBlock)

        // mark that save required
        FlagWar.needsSave = true

        val chunk = Nodes.getTerritoryChunkFromCoord(attack.coord)
        if ( chunk == null ) {
            Nodes.logger?.severe("finishAttack(): TerritoryChunk at ${attack.coord} is null")
            return
        }

        // check if attack finish is cancelled
        val event = WarAttackFinishEvent(
            attack.attacker,
            attack.town,
            chunk.territory,
            attack.flagBase
        )
        Bukkit.getPluginManager().callEvent(event);
        if ( event.isCancelled() ) {
            chunk.attacker = null
            return
        }

        // handle occupation state of chunk
        // if chunk is core chunk of territory, attacking town occupies territory
        if ( chunk.coord == chunk.territory.core ) {
            val territory = chunk.territory
            val territoryTown = territory.town
            val attacker = Nodes.getResidentFromUUID(attack.attacker)
            val attackerTown = attack.town
            val attackerNation = attackerTown.nation

            // cleanup territory chunks
            for ( coord in territory.chunks ) {
                val territoryChunk = Nodes.getTerritoryChunkFromCoord(coord)
                if ( territoryChunk != null ) {
                    // cancel any concurrent attacks in this territory
                    FlagWar.chunkToAttacker.get(territoryChunk.coord)?.cancel()

                    // clear occupy/attack status from chunks
                    territoryChunk.attacker = null
                    territoryChunk.occupier = null
                    
                    // remove from internal list of occupied chunks
                    FlagWar.occupiedChunks.remove(territoryChunk.coord)
                }
            }

            // handle re-capturing your own territory, nation territory, or ally territory from enemy
            if ( territoryTown === attackerTown ||
                 ( attackerNation !== null && attackerNation === territoryTown?.nation ) ||
                 attackerTown.allies.contains(territoryTown)
            ) {
                val occupier = territory.occupier
                Nodes.releaseTerritory(territory)
                Message.broadcast("${ChatColor.DARK_RED}[War] ${attacker?.name} liberated territory (id=${territory.id}) from ${occupier?.name}!")
            }
            // captured enemy territory
            else {
                Nodes.captureTerritory(attackerTown, territory)
                Message.broadcast("${ChatColor.DARK_RED}[War] ${attacker?.name} captured territory (id=${territory.id}) from ${territory.town?.name}!")
            }
            
        }
        // else, attacking normal chunk cases:
        // 1. your town, chunk captured by enemy -> liberating, remove flag
        // 2. your town (occupied) -> liberating, put flag
        // 3. territory occupied by your town, captured -> liberating, remove flag
        // 4. enemy town, empty chunk -> attacking, put flag
        else {
            val town = chunk.territory.town
            val occupier = chunk.territory.occupier
            val attacker = Nodes.getResidentFromUUID(attack.attacker)

            chunk.attacker = null

            if ( town === attack.town ) {                
                // re-capturing territory from occupier
                if ( occupier !== null ) {
                    chunk.occupier = town
                    FlagWar.occupiedChunks.add(chunk.coord)

                    Message.broadcast("${ChatColor.DARK_RED}[War] ${attacker?.name} liberated chunk (${chunk.coord.x}, ${chunk.coord.z}) from ${occupier.name}!")
                }
                // must be defending captured chunk
                else {
                    val chunkOccupier = chunk.occupier

                    chunk.occupier = null
                    FlagWar.occupiedChunks.remove(chunk.coord)

                    if ( chunkOccupier !== null ) {
                        Message.broadcast("${ChatColor.DARK_RED}[War] ${attacker?.name} defended chunk (${chunk.coord.x}, ${chunk.coord.z}) against ${chunkOccupier.name}!")
                    }
                }
            }
            else if ( occupier === attack.town && chunk.occupier !== null ) {
                chunk.occupier = null
                FlagWar.occupiedChunks.remove(chunk.coord)

                Message.broadcast("${ChatColor.DARK_RED}[War] ${attacker?.name} defended chunk (${chunk.coord.x}, ${chunk.coord.z}) against ${chunk.occupier!!.name}!")
            }
            else {
                chunk.occupier = attack.town
                FlagWar.occupiedChunks.add(chunk.coord)

                Message.broadcast("${ChatColor.DARK_RED}[War] ${attacker?.name} captured chunk (${chunk.coord.x}, ${chunk.coord.z}) from ${chunk.territory.town?.name}!")
            }
            
            // update minimaps
            Nodes.renderMinimaps()
        }
    }

    // update tick for attack instance
    // NOTE: this runs in separate thread
    internal fun attackTick(attack: Attack) {
        val progress = attack.progress + FlagWar.ATTACK_TICK

        if (progress >= attack.attackTime) {
            // Finish attack logic...
            attack.thread.cancel()
            Bukkit.getGlobalRegionScheduler().run(Nodes.plugin!!) {
                FlagWar.finishAttack(attack)
            }
        } else { // Update progress
            attack.progress = progress

            // Update boss bar progress
            val progressNormalized: Double = progress.toDouble() / attack.attackTime.toDouble()
            attack.progressBar.setProgress(max(0.0, min(1.0, progressNormalized)))
            
            // Check if color needs update (less frequent)
            val progressColorIndex = getProgressColor(progressNormalized)
            if (progressColorIndex != attack.progressColor) {
                attack.progressColor = progressColorIndex
                // Schedule color update task
                Bukkit.getRegionScheduler().run(Nodes.plugin!!, attack.flagBlock.location) {
                    FlagWar.updateAttackFlag(
                        attack,
                        progressColorIndex
                    )
                }
            }

            // Update the nametag every tick (every second)
            // Schedule this on the region scheduler as well to ensure thread safety when accessing Bukkit API
            Bukkit.getRegionScheduler().run(Nodes.plugin!!, attack.flagBlock.location) { 
                try {
                    val timeRemaining = formatTimeRemaining(max(0L, attack.attackTime - progress))
                    val attackerPlayer = Nodes.getResidentFromUUID(attack.attacker)
                    val attackerName = attackerPlayer?.name ?: "Unknown"
                    // Need the town and nation again for the prefix
                    val attackingTown = attack.town // Already available in Attack object
                    val nationName = attackingTown.nation?.name
                    val townName = attackingTown.name
                    
                    val prefix = if (nationName != null) {
                        "${ChatColor.GRAY}[${ChatColor.DARK_PURPLE}${nationName}${ChatColor.GRAY}|${ChatColor.GREEN}${townName}${ChatColor.GRAY}]"
                    } else {
                        "${ChatColor.GRAY}[${ChatColor.GREEN}${townName}${ChatColor.GRAY}]"
                    }
                    attack.flagNametag?.customName = "${prefix} ${ChatColor.GOLD}${attackerName} ${ChatColor.GRAY}| ${ChatColor.YELLOW}${timeRemaining}"
                
                } catch (e: Exception) {
                        Nodes.logger?.warning("[WarTick] Failed to update nametag for attack at ${attack.coord}: ${e.message}")
                }
            }
        }
    }

    // intended to run on PlayerJoin event
    // if war enabled and player has attacks,
    // send progress bars to player
    public fun sendWarProgressBarToPlayer(player: Player) {
        val uuid = player.getUniqueId()
        
        // add attack to list of attacks by attacker
        var currentAttacks = FlagWar.attackers.get(uuid)
        if ( currentAttacks != null ) {
            for ( attack in currentAttacks ) {
                attack.progressBar.addPlayer(player)
            }
        }
    }

    /**
     * Return progress color from normalized progress in [0.0, 1.0]
     */
    internal fun getProgressColor(progressNormalized: Double): Int {
        if ( progressNormalized < 0.0 ) {
            return 0
        } else if ( progressNormalized > 1.0 ) {
            return FLAG_COLORS.size - 1
        } else {
            return (progressNormalized * FLAG_COLORS.size.toDouble()).toInt()
        }
    }

    /**
     * Format time remaining in seconds to a readable format
     */
    internal fun formatTimeRemaining(timeInTicks: Long): String {
        val seconds = timeInTicks / 20 // Convert ticks to seconds
        
        if (seconds < 60) {
            return "${seconds}s"
        }
        
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return "${minutes}m ${remainingSeconds}s"
    }

    // ADDED FUNCTION FOR DYNMAP INTEGRATION
    fun getActiveWarFlagsForDynmap(): List<DynmapWarFlag> {
        val activeFlags = mutableListOf<DynmapWarFlag>()

        chunkToAttacker.values.forEach { attack -> // References now internal to FlagWar object
            try {
                val attackingTown = attack.town
                val worldName = attack.flagBase.world.name // Make sure world is loaded

                val timeRemaining = max(0L, attack.attackTime - attack.progress)
                val progressNormalized = (attack.progress.toDouble() / attack.attackTime.toDouble()).coerceIn(0.0, 1.0)
                val timeRemainingStr = formatTimeRemaining(timeRemaining) // Uses internal formatTimeRemaining

                activeFlags.add(
                    DynmapWarFlag(
                        id = attack.coord.toString().replace(" ", "_"), // Ensure ID is simple string for JSON/JS
                        x = attack.flagBase.x,
                        y = attack.flagBase.y,
                        z = attack.flagBase.z,
                        world = worldName,
                        attackerTownName = attackingTown.name,
                        attackerNationName = attackingTown.nation?.name,
                        progressNormalized = progressNormalized,
                        timeRemainingFormatted = timeRemainingStr
                    )
                )
            } catch (e: Exception) {
                Nodes.logger?.warning("[DynmapWarFlags] Error processing attack at ${attack.coord}: ${e.message}")
                // Optionally log stack trace e.printStackTrace()
            }
        }
        return activeFlags
    }

    // Called on plugin enable/reload
    internal fun loadConfig() {
        // Load other war config...
        try {
             // Check if the field exists in Config - replace with your actual config access
             if (Config::class.java.getDeclaredField("flagBeaconViewDistance") != null) {
                 beaconViewDistanceSquared = Config.flagBeaconViewDistance.toDouble().pow(2)
             } else {
                  Nodes.logger?.warning("[WarConfig] Config.flagBeaconViewDistance not found, using default: ${BEACON_VIEW_DISTANCE_DEFAULT}")
                  beaconViewDistanceSquared = BEACON_VIEW_DISTANCE_DEFAULT.pow(2)
             }
        } catch (e: NoSuchFieldException) {
             Nodes.logger?.warning("[WarConfig] Config.flagBeaconViewDistance not found, using default: ${BEACON_VIEW_DISTANCE_DEFAULT}")
             beaconViewDistanceSquared = BEACON_VIEW_DISTANCE_DEFAULT.pow(2)
        } catch (e: Exception) {
            Nodes.logger?.warning("[WarConfig] Error loading flagBeaconViewDistance, using default: ${BEACON_VIEW_DISTANCE_DEFAULT}. Error: ${e.message}")
            beaconViewDistanceSquared = BEACON_VIEW_DISTANCE_DEFAULT.pow(2)
        }
        skyBeaconSize = max(2, min(16, Config.flagBeaconSize)) // Ensure size is valid
    }
}