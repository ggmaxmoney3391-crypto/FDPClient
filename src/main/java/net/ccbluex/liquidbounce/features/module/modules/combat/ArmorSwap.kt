/*
 * FDPClient Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/SkidderMC/FDPClient/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.event.UpdateEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.utils.client.PacketUtils.sendPacket
import net.minecraft.client.gui.inventory.GuiInventory
import net.minecraft.enchantment.Enchantment
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.item.ItemArmor
import net.minecraft.item.ItemStack
import net.minecraft.item.ItemSword
import net.minecraft.network.play.client.C0DPacketCloseWindow
import net.minecraft.network.play.client.C16PacketClientStatus
import org.lwjgl.input.Mouse

/**
 * Troca peças de armadura quando a durabilidade fica abaixo de uma porcentagem,
 * usando trocas por tecla numérica (hotbar) sem precisar pegar o item com o cursor.
 */
object ArmorSwap : Module("ArmorSwap", Category.COMBAT) {

    private val delay by int("Delay", 50, 1..500)
    private val swapAll by boolean("SwapAll", false)
    private val percentage by int("Percentage", 25, 0..100) { swapAll }
    private val helmetPct by int("Helmet", 25, 0..100) { !swapAll }
    private val chestPct by int("Chest", 25, 0..100) { !swapAll }
    private val legsPct by int("Legs", 25, 0..100) { !swapAll }
    private val bootsPct by int("Boots", 25, 0..100) { !swapAll }
    private val openInventory by boolean("OpenInventory", true)
    private val safeSpoof by boolean("SafeSpoof", true)
    private val spoofDelay by int("SpoofDelay", 100, 60..350) { safeSpoof }
    private val multiSwap by boolean("MultiSwap", true)

    // Slots do container do jogador: 5 = capacete, 6 = peitoral, 7 = calça, 8 = botas
    private val armorSlots = intArrayOf(5, 6, 7, 8)

    private const val INVENTORY_FIRST = 9
    private const val INVENTORY_LAST = 35
    private const val HOTBAR_FIRST = 36
    private const val MANUAL_INPUT_GRACE = 180L
    private const val GUI_SWAP_GRACE = 80L

    private val blockedUntil = LongArray(4)
    private val emergencyArmorEquipped = BooleanArray(4)

    private var nextSwap = 0L
    private var nextSearch = 0L
    private var lastManualInput = 0L
    private var lastVirtualClose = 0L
    private var serverInventoryOpened = false
    private var transactionRunning = false
    private var lastPlayer: Any? = null

    val onUpdate = handler<UpdateEvent> {
        val player = mc.thePlayer ?: return@handler

        // Mundo/jogador novo: zera o estado
        if (player !== lastPlayer) {
            lastPlayer = player
            resetState()
        }

        if (!canUseInventory()) return@handler

        val now = System.currentTimeMillis()

        // Usando item/bloqueando: não mexe no inventário
        if (isUseInputActive()) {
            markManualInput(now)
            finishVirtualInventory()
            return@handler
        }

        if (mc.currentScreen is GuiInventory) {
            if (Mouse.isButtonDown(0) || Mouse.isButtonDown(1)) {
                markManualInput(now)
                return@handler
            }

            if (!transactionRunning && player.inventory.itemStack != null) {
                markManualInput(now)
                return@handler
            }
        }

        // Item no cursor: o jogador está mexendo no inventário na mão
        if (player.inventory.itemStack != null) {
            markManualInput(now)
            return@handler
        }

        if (now - lastManualInput < MANUAL_INPUT_GRACE) return@handler
        if (now < nextSwap || now < nextSearch) return@handler

        if (!isSafeToStartSilentSwap()) {
            nextSearch = now + GUI_SWAP_GRACE
            return@handler
        }

        if (!trySwapOne(now)) {
            finishVirtualInventory()
            nextSearch = now + 100L
        }
    }

    override fun onEnable() {
        resetState()
    }

    override fun onDisable() {
        finishVirtualInventory()
        resetState()
    }

    private fun markManualInput(now: Long) {
        lastManualInput = now
    }

    private fun trySwapOne(now: Long): Boolean {
        for (i in armorSlots.indices) {
            if (now < blockedUntil[i]) continue

            val equipped = stackAt(armorSlots[i])

            if (equipped == null) emergencyArmorEquipped[i] = false

            // Peça equipada ainda está acima do limite: nada a fazer
            if (equipped != null && durabilityPercent(equipped) > threshold(i)) {
                emergencyArmorEquipped[i] = false
                continue
            }

            val allowEmergency = equipped == null || !emergencyArmorEquipped[i]
            val source = findBestReplacement(i, allowEmergency)

            if (source !in INVENTORY_FIRST..INVENTORY_LAST) continue

            if (!prepareInventory(now)) {
                nextSearch = now + 60L
                return true
            }

            val emergency = durabilityPercent(stackAt(source)) <= threshold(i)
            val swapped = numberKeySwap(source, armorSlots[i], i)

            var configuredDelay = delay.toLong()
            if (mc.currentScreen is GuiInventory) {
                configuredDelay = maxOf(configuredDelay, GUI_SWAP_GRACE)
            }
            nextSwap = now + configuredDelay

            if (swapped) {
                blockedUntil[i] = 0L
                emergencyArmorEquipped[i] = emergency

                if (!multiSwap) {
                    finishVirtualInventory()
                }
                nextSearch = nextSwap
            } else {
                blockedUntil[i] = now + 500L
                finishVirtualInventory()
                nextSearch = now + MANUAL_INPUT_GRACE
            }

            return true
        }

        return false
    }

    /**
     * Troca [source] (inventário) com [armorSlot] usando 3 trocas por tecla numérica
     * em um slot "ponte" da hotbar: a peça nova vai para a armadura e a velha volta
     * para o lugar de onde a nova saiu.
     */
    private fun numberKeySwap(source: Int, armorSlot: Int, armorType: Int): Boolean {
        val player = mc.thePlayer ?: return false

        if (transactionRunning) return false
        if (mc.netHandler == null) return false
        if (player.inventory.itemStack != null) return false
        if (source !in INVENTORY_FIRST..INVENTORY_LAST) return false
        if (armorSlot !in 5..8) return false

        val sourceStack = copy(stackAt(source))
        val armorStack = copy(stackAt(armorSlot))

        if (!isCorrectArmor(sourceStack, armorType)) return false

        val bridge = chooseBridgeHotbarButton()
        val bridgeSlot = HOTBAR_FIRST + bridge
        val bridgeStack = copy(stackAt(bridgeSlot))

        transactionRunning = true

        try {
            directNumberSwap(source, bridge)
            directNumberSwap(armorSlot, bridge)
            directNumberSwap(source, bridge)

            // Confere se tudo terminou exatamente como esperado
            return sameStack(stackAt(armorSlot), sourceStack)
                    && sameStack(stackAt(source), armorStack)
                    && sameStack(stackAt(bridgeSlot), bridgeStack)
                    && player.inventory.itemStack == null
        } finally {
            transactionRunning = false
        }
    }

    // Escolhe um slot da hotbar (que não seja o selecionado), preferindo um vazio
    private fun chooseBridgeHotbarButton(): Int {
        val current = mc.thePlayer?.inventory?.currentItem ?: 0

        for (i in 0 until 9) {
            if (i != current && stackAt(HOTBAR_FIRST + i) == null) return i
        }

        for (i in 0 until 9) {
            if (i != current) return i
        }

        return if (current == 0) 1 else 0
    }

    private fun directNumberSwap(slot: Int, button: Int) {
        val player = mc.thePlayer ?: return
        mc.playerController.windowClick(player.inventoryContainer.windowId, slot, button, 2, player)
    }

    private fun canUseInventory(): Boolean {
        if (mc.thePlayer == null || mc.theWorld == null || mc.playerController == null) return false
        if (mc.thePlayer.inventoryContainer == null) return false
        if (mc.playerController.currentGameType?.isSurvivalOrAdventure != true) return false

        val screen = mc.currentScreen
        if (screen != null && screen !is GuiInventory) return false

        return openInventory || screen is GuiInventory
    }

    private fun isUseInputActive(): Boolean {
        val player = mc.thePlayer ?: return false

        val active = player.isUsingItem || mc.gameSettings.keyBindUseItem.isKeyDown
        if (!active) return false

        // Bloquear com espada não conta como "usando item"
        val held = player.heldItem
        return held == null || held.item !is ItemSword
    }

    private fun isSafeToStartSilentSwap(): Boolean {
        if (mc.currentScreen is GuiInventory) return true
        if (!openInventory || isUseInputActive()) return false

        return !safeSpoof || !mc.playerController.isHittingBlock
    }

    // Avisa o servidor que o inventário foi aberto (sem abrir a tela de verdade)
    private fun prepareInventory(now: Long): Boolean {
        if (mc.currentScreen is GuiInventory) return true
        if (!openInventory || mc.netHandler == null) return false

        if (safeSpoof && now - lastVirtualClose < spoofDelay) return false

        if (!serverInventoryOpened) {
            sendPacket(C16PacketClientStatus(C16PacketClientStatus.EnumState.OPEN_INVENTORY_ACHIEVEMENT))
            serverInventoryOpened = true
        }

        return true
    }

    private fun finishVirtualInventory() {
        if (!serverInventoryOpened) return

        val player = mc.thePlayer
        if (player == null || mc.netHandler == null) {
            serverInventoryOpened = false
            return
        }

        if (player.inventory.itemStack != null) return

        if (mc.currentScreen !is GuiInventory) {
            sendPacket(C0DPacketCloseWindow(player.inventoryContainer.windowId))
            lastVirtualClose = System.currentTimeMillis()
        }

        serverInventoryOpened = false
    }

    /**
     * Procura no inventário (slots 9..35) a melhor peça para o tipo de armadura [armorType].
     * Prefere a melhor peça acima do limite de durabilidade; se não houver e [allowEmergency],
     * usa a melhor entre as que também estão abaixo do limite.
     */
    private fun findBestReplacement(armorType: Int, allowEmergency: Boolean): Int {
        val container = mc.thePlayer?.inventoryContainer ?: return -1

        var best = -1
        var bestScore = Long.MIN_VALUE
        var emergency = -1
        var emergencyScore = Long.MIN_VALUE

        for (slot in INVENTORY_FIRST..INVENTORY_LAST) {
            if (slot >= container.inventorySlots.size) break

            val stack = stackAt(slot)
            if (!isCorrectArmor(stack, armorType)) continue

            val durability = durabilityPercent(stack)
            if (durability <= 0) continue

            val score = armorScore(stack)

            if (durability > threshold(armorType) && score > bestScore) {
                bestScore = score
                best = slot
            } else if (durability <= threshold(armorType) && score > emergencyScore) {
                emergencyScore = score
                emergency = slot
            }
        }

        if (best >= INVENTORY_FIRST) return best
        return if (allowEmergency) emergency else -1
    }

    private fun armorScore(stack: ItemStack?): Long {
        val armor = stack?.item as? ItemArmor ?: return Long.MIN_VALUE

        var score = armor.damageReduceAmount.toLong() * 100000L
        score += enchantment(stack, Enchantment.protection.effectId) * 15000L
        score += enchantment(stack, Enchantment.blastProtection.effectId) * 2500L
        score += enchantment(stack, Enchantment.projectileProtection.effectId) * 2500L
        score += enchantment(stack, Enchantment.fireProtection.effectId) * 2500L
        score += durabilityPercent(stack).toLong()

        return score
    }

    private fun enchantment(stack: ItemStack, id: Int): Int =
        EnchantmentHelper.getEnchantmentLevel(id, stack)

    private fun durabilityPercent(stack: ItemStack?): Int {
        if (stack == null || stack.item !is ItemArmor) return 0

        val max = stack.maxDamage
        if (max <= 0) return 100

        return maxOf(0, minOf(max, max - stack.itemDamage)) * 100 / max
    }

    private fun threshold(armorType: Int): Int {
        if (swapAll) return clamp(percentage)

        return clamp(
            when (armorType) {
                0 -> helmetPct
                1 -> chestPct
                2 -> legsPct
                else -> bootsPct
            }
        )
    }

    private fun clamp(value: Int) = maxOf(0, minOf(100, value))

    private fun stackAt(slot: Int): ItemStack? {
        val container = mc.thePlayer?.inventoryContainer ?: return null
        return container.inventorySlots.getOrNull(slot)?.stack
    }

    private fun copy(stack: ItemStack?): ItemStack? = stack?.copy()

    private fun isCorrectArmor(stack: ItemStack?, armorType: Int): Boolean {
        val armor = stack?.item as? ItemArmor ?: return false
        return armor.armorType == armorType
    }

    private fun sameStack(a: ItemStack?, b: ItemStack?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return ItemStack.areItemStacksEqual(a, b)
    }

    private fun resetState() {
        transactionRunning = false
        serverInventoryOpened = false
        nextSwap = 0L
        nextSearch = 0L
        lastManualInput = 0L
        lastVirtualClose = 0L
        blockedUntil.fill(0L)
        emergencyArmorEquipped.fill(false)
    }
}
