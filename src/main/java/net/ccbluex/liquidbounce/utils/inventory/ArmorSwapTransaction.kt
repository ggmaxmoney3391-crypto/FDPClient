/*
 * FDPClient Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/SkidderMC/FDPClient/
 */
package net.ccbluex.liquidbounce.utils.inventory

import net.minecraft.inventory.ContainerPlayer
import net.minecraft.item.ItemStack

/**
 * Uma troca de armadura feita com 3 trocas por tecla numérica usando um slot "ponte" da hotbar:
 *
 *   1) inventário <-> ponte   (a peça nova vai para a ponte)
 *   2) armadura   <-> ponte   (a peça nova é equipada; a velha vai para a ponte)
 *   3) inventário <-> ponte   (a peça velha volta para o slot de origem; a ponte volta ao normal)
 *
 * Depois de enviar os cliques, a troca espera o servidor confirmar cada um deles.
 */
class ArmorSwapTransaction(
    val container: ContainerPlayer,
    val inventorySlot: Int,
    val armorSlot: Int,
    val hotbarButton: Int,
    private val newArmor: ItemStack,
    private val oldArmor: ItemStack?,
    private val bridgeStack: ItemStack?,
    private val timeoutMs: Long
) {

    enum class Result { SUCCESS, FAILED, WAITING }

    // Slot de cada troca (o botão é sempre o da hotbar escolhida)
    private val steps = intArrayOf(inventorySlot, armorSlot, inventorySlot)

    private var performed = 0
    private var startedAt = 0L
    private var rejected = false

    // número da ação do clique -> confirmado pelo servidor?
    private val recorded = LinkedHashMap<Short, Boolean>()

    var completed = false
        private set

    val hasAllConfirmations: Boolean
        get() = performed == steps.size &&
                recorded.size >= performed &&
                recorded.values.all { it }

    // Chamado quando um clique desta troca é enviado ao servidor
    fun recordAction(slotId: Int, button: Int, mode: Int, actionNumber: Short) {
        // Só as trocas por tecla numérica (modo 2) fazem parte desta transação
        if (mode != 2) return

        recorded[actionNumber] = false
    }

    // Chamado quando o servidor responde a um clique
    fun confirm(action: Short, accepted: Boolean, now: Long) {
        if (!recorded.containsKey(action)) return

        if (accepted) {
            recorded[action] = true
        } else {
            rejected = true
        }
    }

    /**
     * Executa as trocas que faltam (de uma vez, no mesmo tick) e depois só confere as confirmações.
     * [swap] faz uma troca por tecla numérica e devolve se deu certo.
     */
    fun advance(now: Long, swap: (slot: Int, button: Int) -> Boolean): Result {
        if (completed) return Result.SUCCESS

        if (startedAt == 0L) startedAt = now

        if (rejected) return Result.FAILED
        if (now - startedAt > timeoutMs) return Result.FAILED

        if (performed < steps.size) {
            while (performed < steps.size) {
                if (!swap(steps[performed], hotbarButton)) return Result.FAILED
                performed++
            }

            // Confere o resultado local logo depois das trocas (antes de qualquer desgaste da armadura)
            if (!matchesExpectedState()) return Result.FAILED
        }

        if (hasAllConfirmations) {
            completed = true
            return Result.SUCCESS
        }

        return Result.WAITING
    }

    /**
     * Desfaz o que já foi feito, repetindo as trocas na ordem inversa
     * (cada troca por tecla numérica desfaz a si mesma quando repetida).
     */
    fun rollback(swap: (slot: Int, button: Int) -> Boolean) {
        if (completed) return

        for (i in performed - 1 downTo 0) {
            swap(steps[i], hotbarButton)
        }

        performed = 0
        recorded.clear()
    }

    private fun matchesExpectedState(): Boolean =
        sameStack(container.getSlot(armorSlot).stack, newArmor) &&
                sameStack(container.getSlot(inventorySlot).stack, oldArmor) &&
                sameStack(container.getSlot(36 + hotbarButton).stack, bridgeStack)

    private fun sameStack(first: ItemStack?, second: ItemStack?): Boolean {
        if (first == null || second == null) return first == null && second == null

        return first.item == second.item &&
                first.itemDamage == second.itemDamage &&
                first.stackSize == second.stackSize &&
                ItemStack.areItemStackTagsEqual(first, second)
    }
}

/**
 * Um grupo de trocas de armadura (uma por peça) feitas no mesmo inventário.
 * As trocas são executadas uma depois da outra, e o grupo só termina quando todas terminam.
 */
class ArmorSwapBatch(val container: ContainerPlayer) {

    private val transactions = ArrayList<ArmorSwapTransaction>()

    // Troca que está enviando cliques neste momento (os cliques são registrados nela)
    private var current: ArmorSwapTransaction? = null

    val hasAllConfirmations: Boolean
        get() = transactions.isNotEmpty() && transactions.all { it.completed || it.hasAllConfirmations }

    fun add(transaction: ArmorSwapTransaction) {
        transactions.add(transaction)
    }

    fun recordAction(slotId: Int, button: Int, mode: Int, actionNumber: Short) {
        current?.recordAction(slotId, button, mode, actionNumber)
    }

    fun confirm(action: Short, accepted: Boolean, now: Long) {
        for (transaction in transactions) {
            transaction.confirm(action, accepted, now)
        }
    }

    fun advance(now: Long, swap: (slot: Int, button: Int) -> Boolean): ArmorSwapTransaction.Result {
        var waiting = false

        for (transaction in transactions) {
            if (transaction.completed) continue

            current = transaction

            val result = try {
                transaction.advance(now, swap)
            } finally {
                current = null
            }

            when (result) {
                ArmorSwapTransaction.Result.FAILED -> return ArmorSwapTransaction.Result.FAILED
                ArmorSwapTransaction.Result.WAITING -> waiting = true
                ArmorSwapTransaction.Result.SUCCESS -> Unit
            }
        }

        return if (waiting) ArmorSwapTransaction.Result.WAITING else ArmorSwapTransaction.Result.SUCCESS
    }

    // Desfaz as trocas ainda não concluídas, da última para a primeira
    fun rollback(swap: (slot: Int, button: Int) -> Boolean) {
        for (transaction in transactions.asReversed()) {
            transaction.rollback(swap)
        }
    }
}
