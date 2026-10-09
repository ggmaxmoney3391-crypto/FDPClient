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
