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
import net.ccbluex.liquidbounce.file.FileManager
import org.lwjgl.input.Keyboard
import java.io.File
import java.util.Locale

/**
 * Macros de tecla: aperta uma tecla e o módulo manda uma mensagem (ou comando) no chat.
 *
 * As macros ficam no arquivo "autotext.txt" dentro da pasta do FDPClient, no formato:
 *     TECLA mensagem
 * Exemplo:
 *     B /repairall
 *
 * Para adicionar pelo jogo: escreva a tecla em MacroKey, a mensagem em MacroText e ligue AddMacro.
 * Para remover: escreva a tecla em MacroKey e ligue RemoveMacro. ClearMacros apaga tudo.
 */
object AutoText : Module("AutoText", Category.PLAYER) {

    private val macroKey = text("MacroKey", "")
    private val macroText = text("MacroText", "")
    private val addMacro = boolean("AddMacro", false)
    private val removeMacro = boolean("RemoveMacro", false)
    private val clearMacros = boolean("ClearMacros", false)

    private class MacroEntry(val key: Int, val message: String)

    private val keyStates = HashMap<Int, Boolean>()
    private var cachedEntries: List<MacroEntry> = emptyList()
    private var lastModified = Long.MIN_VALUE

    private fun macroFile() = File(FileManager.dir, "autotext.txt")

    override fun onEnable() {
        ensureFile()
        reloadEntries()
    }

    override fun onDisable() {
        keyStates.clear()
    }

    val onUpdate = handler<UpdateEvent> {
        if (mc.thePlayer == null || mc.theWorld == null) return@handler

        // "Botões" da interface: ligue e eles executam uma vez e voltam a desligar
        if (addMacro.get()) {
            addMacro.set(false)
            addMacroFromOptions()
        }

        if (removeMacro.get()) {
            removeMacro.set(false)
            removeMacroFromOptions()
        }

        if (clearMacros.get()) {
            clearMacros.set(false)
            saveEntries(emptyList())
            reloadEntries()
        }

        checkReload()

        // Com qualquer tela aberta (chat, inventário...) só atualiza o estado das teclas
        if (mc.currentScreen != null) {
            syncKeyStates()
            return@handler
        }

        for (entry in cachedEntries) {
            if (entry.key <= 0) continue
