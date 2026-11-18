package me.justlime.dummyplayer.service

import com.hypixel.hytale.component.Holder
import com.hypixel.hytale.math.vector.Rotation3f
import com.hypixel.hytale.math.vector.Rotation3fc
import com.hypixel.hytale.math.vector.Transform
import com.hypixel.hytale.protocol.PlayerSkin
import com.hypixel.hytale.protocol.ProtocolSettings
import com.hypixel.hytale.protocol.packets.interface_.AddToServerPlayerList
import com.hypixel.hytale.protocol.packets.interface_.RemoveFromServerPlayerList
import com.hypixel.hytale.protocol.packets.interface_.ServerPlayerListPlayer
import com.hypixel.hytale.server.core.Message
import com.hypixel.hytale.server.core.auth.PlayerAuthentication
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule
import com.hypixel.hytale.server.core.entity.InteractionManager
import com.hypixel.hytale.server.core.entity.UUIDComponent
import com.hypixel.hytale.server.core.entity.entities.Player
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate
import com.hypixel.hytale.server.core.io.ProtocolVersion
import com.hypixel.hytale.server.core.modules.entity.component.*
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource
import com.hypixel.hytale.server.core.universe.PlayerRef
import com.hypixel.hytale.server.core.universe.Universe
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore
import com.hypixel.hytale.component.AddReason
import com.hypixel.hytale.server.npc.NPCPlugin
import com.hypixel.hytale.server.npc.entities.NPCEntity
import com.hypixel.hytale.server.npc.interactions.NPCInteractionSimulationHandler
import com.hypixel.hytale.server.npc.systems.RoleBuilderSystem
import com.hypixel.hytale.server.npc.systems.RoleChangeSystem
import com.hypixel.hytale.protocol.NetworkChannel
import me.justlime.dummyplayer.component.DummyPlayerComponent
import me.justlime.dummyplayer.listener.DummyPlayerEvent
import me.justlime.dummyplayer.model.enums.DummyValidationResult
import me.justlime.dummyplayer.network.DummyChannelConnection
import me.justlime.dummyplayer.network.DummyPacketHandler
import org.joml.Vector3d
import org.joml.Vector3dc
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

object DummyPlayerService {

    //    private val cloneCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val dummies = ConcurrentHashMap<UUID, PlayerRef>()
    private val holders = ConcurrentHashMap<UUID, Holder<EntityStore>>()

    // ===========================================================================================
    // SPAWNING
    // ===========================================================================================

    fun spawnDummy(
        world: World,
        username: String,
        position: Vector3dc,
        rotation: Rotation3fc,
        skin: PlayerSkin
    ): CompletableFuture<DummyValidationResult> {
        val uuid = getDummyUUID(username)

        val existingRef = dummies[uuid]
        if (existingRef != null) {
            if (existingRef.isValid) {
                return CompletableFuture.completedFuture(DummyValidationResult.AlreadyExists(existingRef))
            }
            dummies.remove(uuid)
            holders.remove(uuid)
        }

        // Core Network Objects
        val holder = EntityStore.REGISTRY.newHolder()
        val connection = DummyChannelConnection()
        val protocolVersion = ProtocolVersion(ProtocolSettings.PROTOCOL_VERSION)
        val authentication = PlayerAuthentication(uuid, username)
        val dummyHandler = DummyPacketHandler(connection, protocolVersion, authentication)

        // Populate all NetworkChannel slots so writePacket never hits a null channel
        for (nc in NetworkChannel.VALUES) {
            dummyHandler.setChannel(nc, connection)
        }

        val event = DummyPlayerEvent.setupConnect(dummyHandler, username, uuid, authentication)
        if (event != null && event.isCancelled) {
            val reason = event.reason?.let { it.rawText ?: it.messageId } ?: "Connection rejected."
            return CompletableFuture.completedFuture(DummyValidationResult.ConnectionDenied(reason))
        }

        // Setup Entity
        val chunkTracker = ChunkTracker()
        val spawnRot = Rotation3f(rotation)
        val spawnPos = Vector3d(position.x(), position.y() + 1.0, position.z())
        val spawnTransform = Transform(spawnPos, spawnRot)
        val dummyRef = PlayerRef(holder, uuid, username, "en_US", dummyHandler, chunkTracker)
        setupHolderComponents(holder, dummyRef, chunkTracker, uuid, skin, dummyHandler, username, spawnPos, spawnRot, world)

        // Add to World
        modifyUniversePlayersMap { it[uuid] = dummyRef }
        val result: CompletableFuture<DummyValidationResult> =
            world.addPlayer(dummyRef, spawnTransform)?.thenApply { ref ->
                if (ref != null && ref.isValid) {
                    dummies[uuid] = dummyRef
                    holders[uuid] = holder
                    tabListAddPlayer(world, uuid, username)
                    DummyValidationResult.Success(dummyRef, dummyRef)
                } else DummyValidationResult.Failure("Invalid dummy reference")
            } ?: CompletableFuture.completedFuture(DummyValidationResult.Failure("Failed to add dummy to the world"))
        DummyPlayerEvent.connect(holder, dummyRef, world)
        return result
    }

    // ===========================================================================================
    // UPDATING (Visuals)
    // ===========================================================================================

    /**
     * Updates the skin and model of an existing dummy.
     * @return true if successful, false if dummy not found or invalid
     */
    fun updateSkin(dummyRef: PlayerRef, newSkin: PlayerSkin): Boolean {
        val entity = dummyRef.reference ?: return false
        if (!entity.isValid) return false
        val store = entity.store

        val skinComponent = PlayerSkinComponent(newSkin)
        store.putComponent(entity, PlayerSkinComponent.getComponentType(), skinComponent)

        val newModel = CosmeticsModule.get().createModel(newSkin)
        if (newModel != null) {
            val modelComponent = ModelComponent(newModel)
            store.putComponent(entity, ModelComponent.getComponentType(), modelComponent)
        }
        return true
    }

    // ===========================================================================================
    // UTILITIES
    // ===========================================================================================

    fun deleteDummy(username: String): Boolean {
        val uuid = getDummyUUID(username)
        val dummyRef = dummies.remove(uuid) ?: return false
        holders.remove(uuid)
        val entity = dummyRef.reference
        if (entity != null && entity.isValid) {
            val world = entity.store.externalData.world
            tabListRemovePlayer(world, uuid)
        }
        try {
            dummyRef.packetHandler.channel.closeConnection()
        } catch (_: Exception) {
            // Ignore if already closed
        }
        Universe.get().removePlayer(dummyRef)
        return true
    }

    fun getDummy(name: String): PlayerRef? = dummies[getDummyUUID(name)]

    fun getDummy(uuid: UUID): PlayerRef? = dummies[uuid]

    fun getDummyUUID(username: String): UUID {
        return UUID.nameUUIDFromBytes("Dummy:$username".toByteArray(Charsets.UTF_8))
    }

    fun getDummyNames(): List<String> {
        return dummies.values.map { it.username }
    }

    fun setRole(dummyName: String, roleName: String) {
        val ref = getDummy(dummyName)?.reference ?: return
        val store = ref.store
        val npc = store.getComponent(ref, NPCEntity.getComponentType()!!) ?: return
        val roleIndex = NPCPlugin.get().getIndex(roleName)
        RoleChangeSystem.requestRoleChange(ref, npc.role!!, roleIndex, true, store)
    }

    /**
     * Attaches an [NPCEntity] with the dummy role to [holder] and builds the role.
     *
     * Since 0.7.7 the motion controllers read the NPCEntity from the holder while the role is
     * built, so the component must be attached first. The role is built by the server's own
     * [RoleBuilderSystem] (role, support components and `Role.spawned`). Players enter the world
     * with AddReason.LOAD, which skips the spawn step, so it runs here with SPAWN; when the
     * world later adds the holder, the system finds the role already built and does nothing.
     */
    internal fun attachNPCEntity(
        holder: Holder<EntityStore>,
        world: World,
        position: Vector3dc? = null,
        rotation: Rotation3fc? = null
    ): NPCEntity {
        val npcEntity = NPCEntity()
        val plugin = NPCPlugin.get()
        val roleName = "Dummy_Player"

        // Validate that the role template is registered before using it
        if (!plugin.hasRoleName(roleName)) {
            val available = plugin.getRoleTemplateNames(false)
            println("[DummyPlayer] Role '$roleName' not found! Available roles: $available")
            throw IllegalStateException(
                "NPC role template '$roleName' is not registered. " +
                "Ensure the asset pack is loaded and the template Type is not 'Abstract'."
            )
        }

        npcEntity.roleName = roleName
        npcEntity.roleIndex = plugin.getIndex(roleName)

        val store = world.entityStore.store
        val worldTime = store.getResource(WorldTimeResource.getResourceType())
        npcEntity.spawnInstant = worldTime.gameTime

        if (position != null && rotation != null) {
            npcEntity.saveLeashInformation(position, rotation)
        }

        holder.addComponent(NPCEntity.getComponentType()!!, npcEntity)
        roleBuilderSystem.onEntityAdd(holder, AddReason.SPAWN, store)

        // On failure RoleBuilderSystem strips the holder and marks it FailedSpawnComponent
        checkNotNull(npcEntity.role) {
            "Failed to build NPC role '$roleName' for the dummy. Check the server log for the cause."
        }
        return npcEntity
    }

    private val roleBuilderSystem by lazy { RoleBuilderSystem() }

    private fun setupHolderComponents(
        holder: Holder<EntityStore>,
        dummyRef: PlayerRef,
        chunkTracker: ChunkTracker,
        uuid: UUID,
        skin: PlayerSkin,
        dummyHandler: DummyPacketHandler,
        username: String,
        spawnPos: Vector3d,
        spawnRot: Rotation3f,
        world: World
    ) {
        // Core Reference & Tracking
        chunkTracker.setDefaultMaxSectionsPerSecond(dummyRef)
        holder.putComponent(PlayerRef.getComponentType(), dummyRef)
        holder.putComponent(ChunkTracker.getComponentType(), chunkTracker)
        holder.putComponent(UUIDComponent.getComponentType(), UUIDComponent(uuid))

        //  Position & Movement
        holder.addComponent(TransformComponent.getComponentType(), TransformComponent(spawnPos, spawnRot))
        holder.ensureComponent(PositionDataComponent.getComponentType())
        holder.ensureComponent(MovementAudioComponent.getComponentType())

        // Player Identity
        val playerComponent = holder.ensureAndGetComponent(Player.getComponentType())
        playerComponent.init(uuid, dummyRef)
        playerComponent.playerConfigData.cleanup(Universe.get()) // Clean up config like the server does

        // Set View Radius
        val viewRadiusChunks = 4
        playerComponent.clientViewRadius = viewRadiusChunks * 32

        // Visuals (Skin, Model, Name)
        holder.putComponent(PlayerSkinComponent.getComponentType(), PlayerSkinComponent(skin))
        holder.putComponent(ModelComponent.getComponentType(), ModelComponent(CosmeticsModule.get().createModel(skin)))
        holder.putComponent(Nameplate.getComponentType(), Nameplate(username))
        holder.putComponent(DisplayNameComponent.getComponentType(), DisplayNameComponent(Message.raw(username)))

        // NPC Specifics
        holder.addComponent(DummyPlayerComponent.getComponentType(), DummyPlayerComponent())
        attachNPCEntity(holder, world, spawnPos, spawnRot)
        holder.addComponent(
            InteractionModule.get().interactionManagerComponent,
            InteractionManager(null, NPCInteractionSimulationHandler())
        )

        // Network / Viewer
        dummyHandler.setPlayerRef(dummyRef)
        val entityViewer = EntityTrackerSystems.EntityViewer(playerComponent.viewRadius * 32, dummyHandler)
        holder.addComponent(EntityTrackerSystems.EntityViewer.getComponentType(), entityViewer)
    }

    /**
     * Reflectively add dummy to Universe.playersByUuid.
     * Since 0.7.7 `players` is only a read-only view of that map.
     */
    private fun modifyUniversePlayersMap(action: (MutableMap<UUID, PlayerRef>) -> Unit) {
        try {
            val universe = Universe.get()
            val playersField = Universe::class.java.getDeclaredField("playersByUuid")
            playersField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val playersMap = playersField.get(universe) as MutableMap<UUID, PlayerRef>
            action(playersMap)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Sends packet to update Tab List
    private fun tabListAddPlayer(world: World, uuid: UUID, name: String) {
        val listPlayer = ServerPlayerListPlayer(uuid, name, world.worldConfig.uuid, 0, false, null)
        val addPacket = AddToServerPlayerList(arrayOf(listPlayer))
        world.playerRefs.forEach { p ->
            p.packetHandler.writeNoCache(addPacket)
        }
    }

    private fun tabListRemovePlayer(world: World, uuid: UUID) {
        val removePacket = RemoveFromServerPlayerList(arrayOf(uuid))
        world.playerRefs.forEach { p ->
            if (p.packetHandler !is DummyPacketHandler) {
                p.packetHandler.writeNoCache(removePacket)
            }
        }
    }

}