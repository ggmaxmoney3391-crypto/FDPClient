/*
 * FDPClient Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/SkidderMC/FDPClient/
 */
package net.ccbluex.liquidbounce.features.module.modules.player

import net.ccbluex.liquidbounce.event.UpdateEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.Module

/**
 * Port do SpeedMine do kyo_redblack_v3.jar.
 *
 * A cada tick (fora do criativo):
 *  - limita o atraso entre um bloco e o próximo: blockHitDelay = min(blockHitDelay, Delay + 1)
 *  - enquanto você segura o botão num bloco, garante um progresso mínimo de quebra:
 *    curBlockDamageMP >= 0.3 * Speed / 100
 */
object SpeedMine : Module("SpeedMine", Category.PLAYER) {

    // Porcentagem do progresso mínimo de quebra (padrão 15%)
    private val speed by int("Speed", 15, 0..100)

    // Atraso máximo, em ticks, entre quebrar um bloco e começar o próximo (padrão 0)
    private val delay by int("Delay", 0, 0..4)

    val onUpdate = handler<UpdateEvent> {
        val controller = mc.playerController ?: return@handler

        // Não faz nada no criativo
        if (controller.currentGameType?.isCreative == true) return@handler

        controller.blockHitDelay = minOf(controller.blockHitDelay, delay + 1)

        if (controller.isHittingBlock) {
            val current = controller.curBlockDamageMP
            val minimum = 0.3f * (speed.toFloat() / 100f)

            if (current < minimum) {
                controller.curBlockDamageMP = minimum
            }
        }
    }

    override val tag: String
        get() = "$speed%"
}
