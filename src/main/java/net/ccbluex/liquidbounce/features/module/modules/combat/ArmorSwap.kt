package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.event.*
import net.ccbluex.liquidbounce.utils.inventory.ArmorSwapTransaction
import net.ccbluex.liquidbounce.utils.inventory.ArmorSwapBatch
import net.minecraft.network.play.client.C0DPacketCloseWindow
import net.minecraft.network.play.client.C0EPacketClickWindow
import net.minecraft.network.play.server.S2EPacketCloseWindow
import net.minecraft.network.play.server.S32PacketConfirmTransaction
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.Module
import net.minecraft.client.gui.inventory.GuiInventory
import net.minecraft.enchantment.Enchantment
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.inventory.ContainerPlayer
import net.minecraft.item.ItemArmor
import net.minecraft.item.ItemStack
import kotlin.math.roundToInt

object ArmorSwap : Module("ArmorSwap", Category.COMBAT) {

    private val openInventory by boolean("OpenInventory", true).hide()
    private val safeSpoof by boolean("SafeSpoof", true).hide()
    private val spoofDelay by int("SpoofDelay", 70, 60..350).hide()

    private val delay by int("Delay", 50, 1..500)
    private val multiSwap by boolean("MultiSwap", true)
    private val swapAll by boolean("SwapAll", true)

    private val helmet by int("Helmet", 10, 0..100, suffix = "%")
    private val chest by int("Chest", 10, 0..100, suffix = "%")
    private val legs by int("Legs", 10, 0..100, suffix = "%")
    private val boots by int("Boots", 10, 0..100, suffix = "%")

    // Slots de armadura: capacete, peitoral, calca e botas.
    private val armorSlots = intArrayOf(5, 6, 7, 8)
    private val armorTypes = intArrayOf(0, 1, 2, 3)

    private var lastActionTime = 0L
    private var inventoryOpenedAt = 0L
    private var automaticClick = false
    @Volatile private var pendingSwap: ArmorSwapBatch? = null
    private var inventoryScreen: GuiInventory? = null
    private var swappedThisOpening = false

    fun shouldPreventInventoryClose(): Boolean =
        mc.theWorld != null && mc.thePlayer?.let { !it.isDead && it.health > 0f } == true &&
                pendingSwap != null

    // Track actual screen changes, including close/reopen between two game ticks.
    val onScreen = handler<ScreenEvent>(always = true) { event ->
        trackInventory(event.guiScreen as? GuiInventory, System.currentTimeMillis())
    }

    val onWorld = handler<WorldEvent>(always = true) { resetState() }

    // While a dependent exchange is pending, prevent another inventory action from interleaving.
    val onClick = handler<ClickWindowEvent>(always = true, priority = 100) { event ->
        if (pendingSwap?.container?.windowId == event.windowId && !automaticClick) event.cancelEvent()
    }

    val onPacket = handler<PacketEvent>(always = true, priority = 100) { event ->
        when (val packet = event.packet) {
            is C0DPacketCloseWindow -> {
                if (event.eventType == EventState.SEND && pendingSwap != null && mc.currentScreen is GuiInventory &&
                    shouldPreventInventoryClose()) event.cancelEvent()
            }
            is C0EPacketClickWindow -> {
                if (event.eventType == EventState.SEND && !event.isCancelled && automaticClick) {
                    pendingSwap?.takeIf { it.container.windowId == packet.windowId }?.recordAction(
                        packet.slotId, packet.usedButton, packet.mode, packet.actionNumber
                    )
                }
            }
            is S2EPacketCloseWindow -> {
                if (event.eventType != EventState.RECEIVE || event.isCancelled) return@handler
                val transaction = pendingSwap ?: return@handler
                // A server-forced close must not be held back by the local close guard.
                mc.addScheduledTask(Runnable {
                    if (pendingSwap === transaction) {
                        pendingSwap = null
                        lastActionTime = System.currentTimeMillis() + 150L
                    }
                })
            }
            is S32PacketConfirmTransaction -> {
                if (event.eventType != EventState.RECEIVE || event.isCancelled) return@handler
                val transaction = pendingSwap ?: return@handler
                if (transaction.container.windowId != packet.windowId) return@handler
                val action = packet.actionNumber
                val accepted = packet.func_148888_e()
                mc.addScheduledTask(Runnable {
                    if (pendingSwap === transaction) {
                        val now = System.currentTimeMillis()
                        transaction.confirm(action, accepted, now)
                        // Release the GUI as soon as the final acknowledgment arrives.
                        if (transaction.hasAllConfirmations &&
                            transaction.advance(now) { slot, button -> numberKeySwap(transaction.container, slot, button, mc.thePlayer ?: return@advance false) } == ArmorSwapTransaction.Result.SUCCESS) {
                            swappedThisOpening = true
                            lastActionTime = now
                            pendingSwap = null
                        }
                    }
                })
            }
        }
    }

    val onTick = handler<GameTickEvent>(always = true) {
        val player = mc.thePlayer
        if (player == null || mc.theWorld == null || player.isDead || player.health <= 0f) {
            resetState()
            return@handler
        }

        val now = System.currentTimeMillis()
        val screen = mc.currentScreen as? GuiInventory
        trackInventory(screen, now)
        val inventoryIsOpen = screen != null
        val transaction = pendingSwap
        if (transaction != null) {
            if (player.openContainer !== transaction.container || !inventoryIsOpen) {
                pendingSwap = null
                lastActionTime = now
                return@handler
            }
            val result = transaction.advance(now) { slot, button ->
                numberKeySwap(transaction.container, slot, button, player)
            }
            when (result) {
                ArmorSwapTransaction.Result.SUCCESS -> {
                    swappedThisOpening = true
                    lastActionTime = now
                    pendingSwap = null
                }
                ArmorSwapTransaction.Result.FAILED -> {
                    transaction.rollback { slot, button -> numberKeySwap(transaction.container, slot, button, player) }
                    pendingSwap = null
                    lastActionTime = now + 150L
                }
                ArmorSwapTransaction.Result.WAITING -> Unit
            }
            return@handler
        }
        if (!handleEvents()) return@handler

        // Never send player-inventory clicks while another container or screen is open.
        if (mc.currentScreen != null && !inventoryIsOpen) {
            inventoryOpenedAt = 0L
            swappedThisOpening = false
            return@handler
        }

        // So funciona enquanto a tela do inventario estiver aberta.
        if (openInventory && !inventoryIsOpen) {
            inventoryOpenedAt = 0L
            swappedThisOpening = false
            return@handler
        }

        // Do not start an automatic swap while the user holds an item on the cursor.
        if (player.inventory.itemStack != null) {
            return@handler
        }

        // Sem MultiSwap, permite apenas uma troca por abertura.
        if (!multiSwap && swappedThisOpening) {
            return@handler
        }

        if (now - lastActionTime < delay.toLong()) {
            return@handler
        }

        // Aguarda apos abrir o inventario.
        if (openInventory && safeSpoof &&
            now - inventoryOpenedAt < spoofDelay.toLong()
        ) {
            return@handler
        }

        val container = player.openContainer as? ContainerPlayer
            ?: return@handler

        if (container !== player.inventoryContainer) {
            return@handler
        }

        // SwapAll must still respect the individual durability limit for each piece.
        val thresholds = intArrayOf(helmet, chest, legs, boots)

        for (i in armorSlots.indices) {
            val armorSlot = armorSlots[i]
            val armorType = armorTypes[i]
            val equipped = container.getSlot(armorSlot).stack

            // O percentual configurado para cada slot é o limite de troca:
            // mantém a peça equipada até a durabilidade chegar ao limite.
            if (equipped != null &&
                equipped.item is ItemArmor &&
                getDurabilityPercent(equipped).roundToInt() > thresholds[i]
            ) {
                continue
            }

            val replacement = findBestArmor(container, armorType)
                ?: continue

            val replacementSlot = replacement.first
            val replacementStack = replacement.second

            // Nao usa itens da hotbar como origem.
            if (replacementSlot !in 9..35) {
                continue
            }

            // Mesmo ao atingir o limite de desgaste, não substitui por uma peça inferior.
            if (equipped != null &&
                equipped.item is ItemArmor &&
                getArmorScore(replacementStack) <= getArmorScore(equipped)
            ) {
                continue
            }

            val started = beginArmorSwap(
                container, replacementSlot, armorSlot, armorType, replacementStack
            )
            if (started) {
                // Finish the local exchange in this update; subsequent ticks verify all server confirmations.
                val batch = pendingSwap ?: break
                if (batch.advance(now) { slot, button -> numberKeySwap(container, slot, button, player) } == ArmorSwapTransaction.Result.FAILED) {
                    batch.rollback { slot, button -> numberKeySwap(container, slot, button, player) }
                    pendingSwap = null
                    lastActionTime = now + 150L
                    break
                }
                if (!multiSwap || !swapAll) break
            } else {
                lastActionTime = now
                break
            }
        }
    }

    private fun trackInventory(screen: GuiInventory?, now: Long) {
        if (screen === inventoryScreen) return
        inventoryScreen = screen
        inventoryOpenedAt = if (screen != null) now else 0L
        swappedThisOpening = false
    }

    private fun findBestArmor(
        container: ContainerPlayer,
        armorType: Int
    ): Pair<Int, ItemStack>? {
        var bestSlot = -1
        var bestStack: ItemStack? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (slot in 9..35) {
            val stack = container.getSlot(slot).stack ?: continue
            val armor = stack.item as? ItemArmor ?: continue

            if (armor.armorType != armorType) {
                continue
            }

            // A fully broken piece cannot be equipped usefully.
            if (getDurabilityPercent(stack) <= 0.0) {
                continue
            }

            val score = getArmorScore(stack)

            if (score > bestScore) {
                bestScore = score
                bestSlot = slot
                bestStack = stack
            }
        }

        val result = bestStack ?: return null
        return Pair(bestSlot, result)
    }

    private fun getArmorScore(stack: ItemStack, includeDurability: Boolean = true): Double {
        val armor = stack.item as? ItemArmor ?: return -1.0

        var score = armor.damageReduceAmount.toDouble()

        score += EnchantmentHelper.getEnchantmentLevel(
            Enchantment.protection.effectId, stack
        ) * 1.25

        score += EnchantmentHelper.getEnchantmentLevel(
            Enchantment.fireProtection.effectId, stack
        ) * 0.20

        score += EnchantmentHelper.getEnchantmentLevel(
            Enchantment.blastProtection.effectId, stack
        ) * 0.20

        score += EnchantmentHelper.getEnchantmentLevel(
            Enchantment.projectileProtection.effectId, stack
        ) * 0.20

        score += EnchantmentHelper.getEnchantmentLevel(
            Enchantment.unbreaking.effectId, stack
        ) * 0.10

        val maxDamage = stack.maxDamage

        if (includeDurability && maxDamage > 0) {
            val durability =
                (maxDamage - stack.itemDamage).toDouble() / maxDamage.toDouble()

            score += durability * 0.50
        }

        return score
    }

    private fun getDurabilityPercent(stack: ItemStack): Double {
        val maxDamage = stack.maxDamage

        if (maxDamage <= 0) {
            return 100.0
        }

        val remaining = maxDamage - stack.itemDamage

        return (remaining.toDouble() / maxDamage.toDouble() * 100.0)
            .coerceIn(0.0, 100.0)
    }

    private fun beginArmorSwap(
        container: ContainerPlayer,
        inventorySlot: Int,
        armorSlot: Int,
        armorType: Int,
        replacement: ItemStack
    ): Boolean {
        val player = mc.thePlayer ?: return false
        if (player.inventory.itemStack != null || player.openContainer !== container ||
            mc.currentScreen !is GuiInventory || inventorySlot !in 9..35) return false
        val source = container.getSlot(inventorySlot).stack ?: return false
        val armor = source.item as? ItemArmor ?: return false
        if (armorSlots.indexOf(armorSlot) == -1 || armor.armorType != armorType || !sameStack(source, replacement)) return false
        val button = chooseBridgeHotbarButton(container, player)
        if (button !in 0..8) return false
        val ping = player.sendQueue.getPlayerInfo(player.uniqueID)?.responseTime ?: 0
        val transaction = ArmorSwapTransaction(
            container, inventorySlot, armorSlot, button, source.copy(),
            container.getSlot(armorSlot).stack?.copy(), container.getSlot(36 + button).stack?.copy(),
            (750L + ping.coerceAtLeast(0) * 3L).coerceAtMost(3000L)
        )
        val batch = pendingSwap ?: ArmorSwapBatch(container).also { pendingSwap = it }
        batch.add(transaction)
        return true
    }

    private fun chooseBridgeHotbarButton(
        container: ContainerPlayer,
        player: net.minecraft.client.entity.EntityPlayerSP
    ): Int {
        for (button in 0..8) {
            if (button != player.inventory.currentItem &&
                container.getSlot(36 + button).stack == null
            ) {
                return button
            }
        }
        for (button in 0..8) {
            if (button != player.inventory.currentItem) {
                return button
            }
        }
        return -1
    }

    private fun numberKeySwap(
        container: ContainerPlayer,
        slot: Int,
        hotbarButton: Int,
        player: net.minecraft.client.entity.EntityPlayerSP
    ): Boolean {
        if ((slot !in 5..8 && slot !in 9..35) || hotbarButton !in 0..8 ||
            player.openContainer !== container || mc.currentScreen !is GuiInventory ||
            player.inventory.itemStack != null
        ) {
            return false
        }

        automaticClick = true
        try {
            mc.playerController.windowClick(container.windowId, slot, hotbarButton, 2, player)
        } finally {
            automaticClick = false
        }

        // Do not pick up, clear, or relocate anything on the user's cursor.
        return player.inventory.itemStack == null
    }

    private fun sameStack(first: ItemStack?, second: ItemStack?): Boolean {
        if (first == null || second == null) {
            return first == null && second == null
        }

        return first.item == second.item &&
                first.itemDamage == second.itemDamage &&
                first.stackSize == second.stackSize &&
                ItemStack.areItemStackTagsEqual(first, second)
    }

    private fun resetState() {
        lastActionTime = 0L
        inventoryOpenedAt = 0L
        automaticClick = false
        pendingSwap = null
        inventoryScreen = null
        swappedThisOpening = false
    }

    override fun onDisable() {
        val player = mc.thePlayer
        val transaction = pendingSwap
        if (player != null && transaction != null && player.openContainer === transaction.container && mc.currentScreen is GuiInventory) {
            transaction.rollback { slot, button -> numberKeySwap(transaction.container, slot, button, player) }
        }
        resetState()
    }
}
