package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.event.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.Module
import net.minecraft.client.gui.inventory.GuiInventory
import net.minecraft.enchantment.Enchantment
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.inventory.ContainerPlayer
import net.minecraft.item.ItemArmor
import net.minecraft.item.ItemStack

object ArmorSwap : Module("ArmorSwap", Category.COMBAT) {

    private val openInventory by boolean("OpenInventory", true)
    private val delay by int("Delay", 1, 0..20)
    private val swapAll by boolean("SwapAll", false)

    private val helmet by int("Helmet", 10, 0..100)
    private val chest by int("Chest", 10, 0..100)
    private val legs by int("Legs", 10, 0..100)
    private val boots by int("Boots", 10, 0..100)

    private var ticks = 0
    private var swapping = false
    private var wasInventoryOpen = false
    private var swappedThisOpen = false

    // Minecraft 1.8.9:
    // 5 = capacete, 6 = peitoral, 7 = calça, 8 = botas.
    private val armorSlots = intArrayOf(5, 6, 7, 8)
    private val armorTypes = intArrayOf(0, 1, 2, 3)

    val onTick = handler<GameTickEvent> {
        val player = mc.thePlayer ?: return@handler

        if (mc.theWorld == null) {
            resetState()
            return@handler
        }

        // Item no cursor: o jogador está mexendo no inventário, não interfere
        if (player.inventory.itemStack != null) return@handler

        val inventoryIsOpen = mc.currentScreen is GuiInventory

        // ON: só trabalha com o inventário aberto.
        if (openInventory && !inventoryIsOpen) {
            wasInventoryOpen = false
            swappedThisOpen = false
            ticks = 0
            return@handler
        }

        // Detecta quando o inventário acabou de ser aberto.
        if (openInventory && inventoryIsOpen && !wasInventoryOpen) {
            wasInventoryOpen = true
            swappedThisOpen = false
            ticks = 0
        }

        // Com OpenInventory ON e SwapAll OFF,
        // permite apenas uma troca por abertura.
        if (openInventory && !swapAll && swappedThisOpen) {
            return@handler
        }

        if (swapping) {
            return@handler
        }

        if (ticks < delay) {
            ticks++
            return@handler
        }

        ticks = 0
        swapping = true

        try {
            val swapped = swapArmor()

            if (openInventory && swapped) {
                swappedThisOpen = true
            }
        } finally {
            swapping = false
        }
    }

    /**
     * Procura peças de armadura que precisam ser substituídas.
     * Retorna true se pelo menos uma troca foi realizada.
     */
    private fun swapArmor(): Boolean {
        val player = mc.thePlayer ?: return false
        val container = player.inventoryContainer

        if (container !is ContainerPlayer) {
            return false
        }

        val thresholds = intArrayOf(helmet, chest, legs, boots)
        var swappedAny = false

        for (i in armorTypes.indices) {
            val armorType = armorTypes[i]
            val armorSlot = armorSlots[i]
            val limit = thresholds[i].toDouble()

            val equipped = container.getSlot(armorSlot).stack

            // Ignora peças que ainda têm durabilidade acima do limite.
            if (equipped != null &&
                equipped.item is ItemArmor &&
                getDurabilityPercent(equipped) > limit
            ) {
                continue
            }

            // Procura uma peça do mesmo tipo no inventário principal.
            val result = findBestArmor(armorType, equipped)
            val bestSlot = result.first
            val bestStack = result.second

            if (bestSlot == -1 || bestStack == null) {
                continue
            }

            // Só substitui uma peça equipada se a nova for melhor.
            if (equipped != null && equipped.item is ItemArmor) {
                if (getArmorScore(bestStack) <= getArmorScore(equipped)) {
                    continue
                }
            }

            performArmorSwap(bestSlot, armorSlot)
            swappedAny = true

            // OFF: no máximo uma peça por tentativa.
            if (!swapAll) {
                break
            }
        }

        return swappedAny
    }

    /**
     * Slots 9..35 = inventário principal.
     * Slots 36..44 (hotbar) são ignorados.
     */
    private fun findBestArmor(
        armorType: Int,
        equipped: ItemStack?
    ): Pair<Int, ItemStack?> {
        val player = mc.thePlayer ?: return Pair(-1, null)

        var bestSlot = -1
        var bestStack: ItemStack? = null

        var bestScore = if (equipped != null && equipped.item is ItemArmor) {
            getArmorScore(equipped)
        } else {
            -1.0
        }

        for (slot in 9..35) {
            val stack = player.inventoryContainer.getSlot(slot).stack
                ?: continue

            val armor = stack.item as? ItemArmor ?: continue

            if (armor.armorType != armorType) {
                continue
            }

            val score = getArmorScore(stack)

            if (score > bestScore) {
                bestScore = score
                bestSlot = slot
                bestStack = stack
            }
        }

        return Pair(bestSlot, bestStack)
    }

    /**
     * Pontuação estimada: proteção, encantamentos e durabilidade.
     */
    private fun getArmorScore(stack: ItemStack): Double {
        val armor = stack.item as? ItemArmor ?: return 0.0

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

        if (stack.maxDamage > 0) {
            val durability =
                1.0 - (stack.itemDamage.toDouble() / stack.maxDamage.toDouble())

            score += durability * 0.50
        }

        return score
    }

    /**
     * Percentual de durabilidade restante.
     */
    private fun getDurabilityPercent(stack: ItemStack): Double {
        if (stack.maxDamage <= 0) {
            return 100.0
        }

        val remaining = stack.maxDamage - stack.itemDamage

        return (remaining.toDouble() / stack.maxDamage.toDouble()) * 100.0
    }

    /**
     * Troca a armadura usando os cliques do inventário do Minecraft.
     */
    private fun performArmorSwap(
        inventorySlot: Int,
        armorSlot: Int
    ) {
        val player = mc.thePlayer ?: return
        val container = player.inventoryContainer

        if (container !is ContainerPlayer) {
            return
        }

        val inventoryStack = container.getSlot(inventorySlot).stack
            ?: return

        if (inventoryStack.item !is ItemArmor) {
            return
        }

        val armor = inventoryStack.item as ItemArmor

        if (armor.armorType != armorSlots.indexOf(armorSlot)) {
            return
        }

        // Pega a peça nova.
        mc.playerController.windowClick(
            container.windowId,
            inventorySlot,
            0,
            0,
            player
        )

        // Equipa a peça nova e recolhe a antiga, se houver.
        mc.playerController.windowClick(
            container.windowId,
            armorSlot,
            0,
            0,
            player
        )

        // Devolve a peça antiga ao espaço de origem.
        mc.playerController.windowClick(
            container.windowId,
            inventorySlot,
            0,
            0,
            player
        )
    }

    private fun resetState() {
        ticks = 0
        swapping = false
        wasInventoryOpen = false
        swappedThisOpen = false
    }

    override fun onDisable() {
        resetState()
    }
}
