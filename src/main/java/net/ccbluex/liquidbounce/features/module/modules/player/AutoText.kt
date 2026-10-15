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

            val message = entry.message.trim()
            if (message.isEmpty()) continue

            val down = try {
                Keyboard.isKeyDown(entry.key)
            } catch (e: Throwable) {
                false
            }

            val wasDown = keyStates[entry.key] == true

            // Só dispara na hora em que a tecla é apertada
            if (down && !wasDown) sendMacro(message)

            keyStates[entry.key] = down
        }
    }

    private fun sendMacro(message: String) {
        val player = mc.thePlayer ?: return
        val text = message.trim()

        if (text.isEmpty()) return

        player.sendChatMessage(text)
    }

    // ----- Interface (opções) -----

    private fun addMacroFromOptions() {
        val keyText = macroKey.get().trim()
        val message = macroText.get().trim()

        if (keyText.isEmpty() || message.isEmpty()) return

        val key = parseKey(keyText)
        if (key <= 0) return

        val entries = loadEntries().filter { it.key != key }.toMutableList()
        entries.add(MacroEntry(key, message))
        saveEntries(entries)

        macroKey.set("")
        macroText.set("")

        reloadEntries()
        keyStates[key] = false
    }

    private fun removeMacroFromOptions() {
        val keyText = macroKey.get().trim()
        if (keyText.isEmpty()) return

        val key = parseKey(keyText)
        if (key <= 0) return

        saveEntries(loadEntries().filter { it.key != key })
        macroKey.set("")

        reloadEntries()
        keyStates.remove(key)
    }

    // ----- Estado das teclas e recarga do arquivo -----

    private fun syncKeyStates() {
        for (entry in cachedEntries) {
            if (entry.key <= 0) continue

            keyStates[entry.key] = try {
                Keyboard.isKeyDown(entry.key)
            } catch (e: Throwable) {
                false
            }
        }
    }

    private fun checkReload() {
        val file = macroFile()
        val modified = if (file.exists()) file.lastModified() else -1L

        if (modified != lastModified) reloadEntries()
    }

    private fun reloadEntries() {
        cachedEntries = loadEntries()

        val file = macroFile()
        lastModified = if (file.exists()) file.lastModified() else -1L

        // Mantém o estado das teclas que continuam existindo
        val newStates = HashMap<Int, Boolean>()

        for (entry in cachedEntries) {
            if (entry.key <= 0) continue
            newStates[entry.key] = keyStates[entry.key] == true
        }

        keyStates.clear()
        keyStates.putAll(newStates)
    }

    // ----- Arquivo autotext.txt -----

    private fun ensureFile() {
        val file = macroFile()

        file.parentFile?.mkdirs()

        if (!file.exists()) saveEntries(emptyList())
    }

    private fun loadEntries(): List<MacroEntry> {
        val file = macroFile()
        if (!file.exists()) return emptyList()

        val entries = ArrayList<MacroEntry>()

        try {
            for (rawLine in file.readLines()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue

                val separator = findSeparator(line)
                if (separator <= 0) continue

                val key = parseKey(line.substring(0, separator))
                val message = line.substring(separator + 1).trim()

                if (key <= 0 || message.isEmpty()) continue

                // Se a mesma tecla aparecer mais de uma vez, vale a última
                entries.removeAll { it.key == key }
                entries.add(MacroEntry(key, message))
            }
        } catch (e: Throwable) {
            return emptyList()
        }

        return entries
    }

    private fun saveEntries(entries: List<MacroEntry>) {
        val file = macroFile()
        file.parentFile?.mkdirs()

        try {
            file.printWriter().use { writer ->
                writer.println("# FDPClient AutoText")
                writer.println("# Formato: TECLA mensagem")
                writer.println("# Exemplo: B /repairall")
                writer.println()

                for (entry in entries) {
                    if (entry.key <= 0) continue

                    val message = entry.message.trim()
                    if (message.isEmpty()) continue

                    var keyName = Keyboard.getKeyName(entry.key)
                    if (keyName == null || keyName.trim().isEmpty()) keyName = entry.key.toString()

                    writer.println("$keyName $message")
                }
            }
        } catch (e: Throwable) {
        }
    }

    // Primeiro espaço em branco da linha (separa a tecla da mensagem)
    private fun findSeparator(line: String): Int {
        for (i in line.indices) {
            if (line[i].isWhitespace()) return i
        }

        return -1
    }

    /**
     * Aceita o nome da tecla (B, F5, NUMPAD1...), "KEY_B" ou o código numérico (1..255).
     * Devolve 0 se não reconhecer.
     */
    private fun parseKey(text: String?): Int {
        if (text == null) return 0

        var name = text.trim().uppercase(Locale.ROOT)
        if (name.isEmpty()) return 0

        if (name.startsWith("KEY_")) name = name.substring(4)

        val numeric = name.toIntOrNull()
        if (numeric != null && numeric in 1..255) return numeric

        val index = Keyboard.getKeyIndex(name)
        if (index > 0) return index

        if (name.length == 1) {
            val c = name[0]

            if (c in 'A'..'Z' || c in '0'..'9') return Keyboard.getKeyIndex(c.toString())
        }

        return 0
    }
}
