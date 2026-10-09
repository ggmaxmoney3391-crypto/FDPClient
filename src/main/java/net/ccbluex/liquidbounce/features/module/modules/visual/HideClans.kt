/*
 * FDPClient Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/SkidderMC/FDPClient/
 */
package net.ccbluex.liquidbounce.features.module.modules.visual

import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.file.FileManager.friendsConfig
import net.minecraft.entity.Entity
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.world.World
import kotlin.math.abs

/**
 * Esconde da tela os jogadores que estão na lista de amigos (clã) e as
 * partículas que nascem em cima deles.
 *
 * Os mixins chamam [shouldHideEntity] e [shouldHideParticle].
 */
object HideClans : Module("HideClans", Category.VISUAL, subjective = true, gameDetecting = false) {

    private val hideParticles by boolean("HideParticles", true)

    // Cache atualizado uma vez por tick do mundo, para não varrer a lista a cada render
    private val hiddenPlayers = ArrayList<EntityPlayer>(16)
    private var cachedWorld: World? = null
    private var cachedWorldTick = -1L
    private var cachedPlayerCount = -1

    private fun isClanMember(player: EntityPlayer): Boolean =
        friendsConfig.friends.any { it.playerName.equals(player.name, ignoreCase = true) }

    private fun refreshCache() {
        val world = mc.theWorld ?: return
        val tick = world.totalWorldTime
        val count = world.playerEntities.size

        if (world === cachedWorld && tick == cachedWorldTick && count == cachedPlayerCount) return

        hiddenPlayers.clear()
        for (player in world.playerEntities) {
            if (player != mc.thePlayer && isClanMember(player)) {
                hiddenPlayers += player
            }
        }

        cachedWorld = world
        cachedWorldTick = tick
        cachedPlayerCount = count
    }

    private fun clearCache() {
        hiddenPlayers.clear()
        cachedWorld = null
        cachedWorldTick = -1L
        cachedPlayerCount = -1
    }

    override fun onDisable() = clearCache()

    /**
     * Usado pelo mixin do RenderManager: true = não renderizar a entidade.
     */
    @JvmStatic
    fun shouldHideEntity(entity: Entity?): Boolean {
        if (!state) return false
        if (entity !is EntityPlayer || entity == mc.thePlayer) return false

        refreshCache()
        return entity in hiddenPlayers
    }

    /**
     * Usado pelo mixin do EffectRenderer: true = não criar a partícula nessa posição.
     */
    @JvmStatic
    fun shouldHideParticle(x: Double, y: Double, z: Double): Boolean {
        if (!state || !hideParticles) return false

        refreshCache()
        if (hiddenPlayers.isEmpty()) return false

        for (player in hiddenPlayers) {
            // Margens estimadas: ajuste se partículas "vazarem" ao redor do jogador
            val horizontal = player.width + 0.5
            if (abs(x - player.posX) <= horizontal &&
                abs(z - player.posZ) <= horizontal &&
                y >= player.posY - 0.5 &&
                y <= player.posY + player.height + 0.5
            ) {
                return true
            }
        }
        return false
    }
}
