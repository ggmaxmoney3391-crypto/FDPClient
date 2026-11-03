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
import net.ccbluex.liquidbounce.file.FileManager.friendsConfig
import net.minecraft.block.Block
import net.minecraft.client.entity.EntityOtherPlayerMP
import net.minecraft.client.gui.GuiChat
import net.minecraft.client.settings.KeyBinding
import net.minecraft.init.Blocks
import net.minecraft.util.BlockPos
import net.minecraft.util.ChatComponentText
import net.minecraft.util.EnumFacing
import net.minecraft.util.MathHelper
import net.minecraft.util.MovingObjectPosition
import net.minecraft.util.Vec3
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Port do AutoBreak do kyo_redblack_v3.jar (lido direto do bytecode).
 *
 * Minera sozinho lapis, redstone, ferro e esmeralda, cavando túneis (padrão Line) ou em
 * espiral (Smart Spiral), desce até 2 blocos quando a camada acaba e mira de forma humanizada.
 * Chamado AutoBreakPlus para não bater com um AutoBreak que o seu FDPClient já tenha.
 */
object AutoBreakPlus : Module("AutoBreakPlus", Category.PLAYER) {

    // ---------------------------------------------------------------- opções

    private val autoWalk by boolean("Walk", true)
    private val useShift by boolean("Sneak", false)
    private val autoSpeedMine by boolean("AutoSpeedMine", true)
    private val autoQuit by boolean("AutoQuit", false)
    private val quitRange by float("QuitRange", 5f, 4f..6f) { autoQuit }

    private val mode by choices("Style", arrayOf("Normal", "Legit"), "Normal")
    private val rotationSpeed by float("RotationSpeed", 10.8f, 1f..60f)
    private val aimSmoothness by int("Smoothness", 70, 0..100)
    private val randomization by float("Randomization", 8f, 0f..20f)
    private val turnSpeedVariation by int("SpeedVariation", 45, 0..100) { randomization > 0f && isLegitMode() }
    private val turnArc by float("TurnArc", 3f, 0f..8f) { randomization > 0f }
    private val turnJitter by int("TurnJitter", 50, 0..100) { randomization > 0f }
    private val turnWalkChance by int("WalkWhileTurning", 45, 0..100) { randomization > 0f }
    private val turnStrafeChance by int("SideStep", 45, 0..100) { randomization > 0f && isLegitMode() }
    private val turnPauseChance by int("PauseChance", 15, 0..100) { randomization > 0f && isLegitMode() }
    private val removeJitter by boolean("NoJitter", true)
    private val stableColumnAim by boolean("StableAim", true)

    private val miningPattern by choices("Pattern", arrayOf("Line", "Smart Spiral"), "Line")
    private val spiralRadius by int("Radius", 6, 3..6) { isSmartSpiralMode() }
    private val downwardBlocks by int("Descent", 2, 1..2)
    private val avoidOpenings by boolean("AvoidOpenings", true)

    // Quais blocos contam como minério (Original = os 4 minérios do módulo original)
    private val targets by choices("Targets", arrayOf("Original", "AllOres", "OresAndBlocks"), "Original")

    // Escreve no chat, a cada 2 segundos, o que o módulo está vendo e por que anda ou não
    private val debug by boolean("Debug", false)

    // ---------------------------------------------------------------- estado

    private var trackedBlock: BlockPos? = null
    private var rotationTarget: BlockPos? = null
    private var nearbyTarget: BlockPos? = null
    private var patternTarget: BlockPos? = null
    private var patternDirection: EnumFacing? = null
    private var downwardTarget: BlockPos? = null

    private var tunnelDirection: EnumFacing? = null
    private var directionLockTicks = 0
    private var preferRight = true
    private var cycleBlocksBroken = 0
    private var speedMineChanged = false
    private var initialSpeedMineState = false

    // descida (recuperação quando a camada acaba)
    private var downwardRecovery = false
    private var waitingForRecoveryLanding = false
    private var recoveryHasFallen = false
    private var downwardRecoveryAvailable = true
    private var downwardCentering = false
    private var downwardBlocksRemaining = 0
    private var recoveryDepthStarted = 0
    private var emptyLayerTicks = 0
    private var recoveryStartY = 0.0
    private var recoveryColumnX = 0
    private var recoveryColumnZ = 0

    // espiral
    private var smartLayerY = Int.MIN_VALUE
    private var smartPlannedRadius = -1
    private var smartCenterX = 0
    private var smartCenterZ = 0
    private var smartRouteIndex = 0
    private var smartRouteForward: EnumFacing? = null

    // mira estável em coluna
    private var columnAimLocked = false
    private var lockedColumnBase: BlockPos? = null
    private var lockedColumnYaw = 0f
    private var lockedColumnPitch = 0f
    private var columnAimMissTicks = 0
    private var lockedAimDirection: EnumFacing? = null
    private var lockedAimLayerY = Int.MIN_VALUE
    private var lockedAimDoubleHeight = false

    // tentativa de mineração
    private var miningAttemptTarget: BlockPos? = null
    private var miningAttemptTicks = 0

    // rotação humanizada
    private var humanYaw = 0f
    private var humanPitch = 0f
    private var yawVelocity = 0f
    private var pitchVelocity = 0f
    private var sideTurnActive = false
    private var turnSpeedScale = 1f
    private var turnResponseScale = 1f
    private var turnYawOffset = 0f
    private var turnPitchOffset = 0f
    private var turnStartError = 0f
    private var turnArcYaw = 0f
    private var turnArcPitch = 0f
    private var turnEaseBias = 1f
    private var turnResumeAngle = 12f
    private var turnKeepsWalking = false
    private var turnMovementDelay = 0
    private var turnStrafeDirection = 0
    private var turnStrafeTicks = 0
    private var humanPauseTicks = 0
    private var blocksUntilHumanPause = 0
    private var jitterPhase = 0.0

    private var previousPauseOnLostFocus = false
    private var pauseOnLostFocusCaptured = false

    // ---------------------------------------------------------------- ciclo de vida

    override fun onEnable() {
        trackedBlock = null
        resetMiningAttempt(false)
        directionLockTicks = 0
        preferRight = true
        cycleBlocksBroken = 0
        speedMineChanged = false
        downwardRecovery = false
        waitingForRecoveryLanding = false
        recoveryHasFallen = false
        downwardRecoveryAvailable = true
        downwardCentering = false
        downwardBlocksRemaining = 0
        recoveryDepthStarted = 0
        emptyLayerTicks = 0
        downwardTarget = null
        nearbyTarget = null
        clearColumnAimLock()
        clearPatternPlan()
        recoveryStartY = 0.0
        yawVelocity = 0f
        pitchVelocity = 0f
        sideTurnActive = false
        turnSpeedScale = 1f
        turnResponseScale = 1f
        turnYawOffset = 0f
        turnPitchOffset = 0f
        turnStartError = 0f
        turnArcYaw = 0f
        turnArcPitch = 0f
        turnEaseBias = 1f
        turnResumeAngle = 12f
        turnKeepsWalking = false
        turnMovementDelay = 0
        turnStrafeDirection = 0
        turnStrafeTicks = 0
        humanPauseTicks = 0
        blocksUntilHumanPause = nextHumanPauseBlockCount()
        jitterPhase = (System.nanoTime() and 0x7FFL).toDouble() * 0.013

        // O jogo não pode pausar quando a janela perde o foco
        val settings = mc.gameSettings
        if (settings != null) {
            previousPauseOnLostFocus = settings.pauseOnLostFocus
            pauseOnLostFocusCaptured = true
            settings.pauseOnLostFocus = false
        }

        val player = mc.thePlayer
        if (player != null) {
            tunnelDirection = horizontalFacing(player.rotationYaw)
            humanYaw = player.rotationYaw
            humanPitch = player.rotationPitch
        } else {
            tunnelDirection = EnumFacing.NORTH
            humanYaw = 0f
            humanPitch = 0f
        }

        initialSpeedMineState = SpeedMine.state
    }

    override fun onDisable() {
        trackedBlock = null
        resetMiningAttempt(true)
        rotationTarget = null
        downwardRecovery = false
        waitingForRecoveryLanding = false
        recoveryHasFallen = false
        downwardRecoveryAvailable = true
        downwardCentering = false
        downwardBlocksRemaining = 0
        recoveryDepthStarted = 0
        emptyLayerTicks = 0
        downwardTarget = null
        nearbyTarget = null
        clearColumnAimLock()
        clearPatternPlan()
        recoveryStartY = 0.0
        directionLockTicks = 0
        yawVelocity = 0f
        pitchVelocity = 0f
        sideTurnActive = false
        turnResponseScale = 1f
        turnSpeedScale = 1f
        turnPitchOffset = 0f
        turnYawOffset = 0f
        turnArcPitch = 0f
        turnArcYaw = 0f
        turnStartError = 0f
        turnEaseBias = 1f
        turnResumeAngle = 12f
        turnKeepsWalking = false
        turnMovementDelay = 0
        turnStrafeDirection = 0
        turnStrafeTicks = 0
        humanPauseTicks = 0
        blocksUntilHumanPause = 0
        cycleBlocksBroken = 0
        restoreSpeedMineState()

        val settings = mc.gameSettings
        if (pauseOnLostFocusCaptured && settings != null) {
            settings.pauseOnLostFocus = previousPauseOnLostFocus
        }
        pauseOnLostFocusCaptured = false

        releaseAutomatedKeys()
    }

    // ---------------------------------------------------------------- laço principal

    val onUpdate = handler<UpdateEvent> {
        val player = mc.thePlayer ?: return@handler
        val world = mc.theWorld ?: return@handler
        val settings = mc.gameSettings ?: return@handler

        val threat = findAutoQuitThreat()
        if (threat != null) {
            triggerAutoQuit(threat)
            return@handler
        }

        // Qualquer tela aberta (menos o chat) solta as teclas e pausa o módulo
        if (mc.currentScreen != null && mc.currentScreen !is GuiChat) {
            releaseAutomatedKeys()
            return@handler
        }

        if (directionLockTicks > 0) directionLockTicks--
        if (turnMovementDelay > 0) turnMovementDelay--
        if (turnStrafeTicks > 0) turnStrafeTicks--

        synchronizeSpeedMineOption()

        val minY = MathHelper.floor_double(player.posY + 0.001)
        val maxY = MathHelper.floor_double(player.posY + player.height.toDouble() - 0.001)
        updateBrokenBlockCounter(minY, maxY)

        // Pausa humana: só desacelera a mira
        if (humanPauseTicks > 0) {
            humanPauseTicks--
            yawVelocity *= 0.45f
            pitchVelocity *= 0.45f
            applyRotation()
            releaseAutomatedKeys()
            return@handler
        }

        // Esperando pousar depois de uma descida
        if (waitingForRecoveryLanding) {
            if (!player.onGround) recoveryHasFallen = true

            val depthLimit = if (recoveryDepthStarted <= 1) 0.65 else 1.35
            val fellEnough = player.posY <= recoveryStartY - depthLimit

            if (!(recoveryHasFallen && fellEnough && player.onGround)) {
                rotationTarget = null
                applyHumanRotation(humanYaw, 82f, true)
                releaseAutomatedKeys()
                return@handler
            }

            waitingForRecoveryLanding = false
            recoveryHasFallen = false
            recoveryDepthStarted = 0
            directionLockTicks = 0
            tunnelDirection = horizontalFacing(humanYaw)
        }

        if (tunnelDirection == null || tunnelDirection!!.axis == EnumFacing.Axis.Y) {
            tunnelDirection = horizontalFacing(player.rotationYaw)
        }

        // ---- escolha do alvo
        var target: BlockPos?

        if (downwardRecovery) {
            target = resolveDownwardTarget()
        } else {
            target = if (usesPatternPlanner()) {
                findPatternTarget(tunnelDirection, minY)
            } else if (nearbyTarget != null) {
                findNearbyTarget(minY, maxY)
            } else {
                findTarget(tunnelDirection!!, minY, maxY)
            }

            if (target == null && !downwardRecovery && !isSmartSpiralMode()) {
                target = findNearbyTarget(minY, maxY)
                if (target != null) {
                    val direction = horizontalDirectionTo(target)
                    if (direction != tunnelDirection) {
                        tunnelDirection = direction
                        clearPatternPlan()
                    }
                    directionLockTicks = 12
                }
            }

            if (target == null && directionLockTicks <= 0 && isDirectionSettled()) {
                chooseDirection(minY, maxY)

                target = if (usesPatternPlanner()) {
                    findPatternTarget(tunnelDirection, minY)
                } else {
                    findTarget(tunnelDirection!!, minY, maxY)
                }

                if (target == null && !isSmartSpiralMode()) {
                    target = findNearbyTarget(minY, maxY)
                    if (target != null) {
                        val direction = horizontalDirectionTo(target)
                        if (direction != tunnelDirection) {
                            tunnelDirection = direction
                            clearPatternPlan()
                        }
                        directionLockTicks = 12
                    }
                }
            }

            if (target == null && startDownwardRecoveryIfSidesAreEmpty(minY, maxY)) {
                target = resolveDownwardTarget()
            }
        }

        if (!downwardRecovery && target != null) emptyLayerTicks = 0

        downwardCentering = downwardRecovery && !isCenteredOverRecoveryColumn()

        // ---- mira
        val desiredYaw: Float
        val desiredPitch: Float

        if (downwardCentering) {
            clearColumnAimLock()
            desiredYaw = yawToRecoveryColumn()
            desiredPitch = 42f
        } else if (target != null) {
            val rotations = rotationsForTarget(target, minY)
            desiredYaw = rotations[0]
            desiredPitch = rotations[1]
        } else {
            clearColumnAimLock()
            desiredYaw = yawFor(tunnelDirection!!)
            desiredPitch = 2f
        }

        rotationTarget = target
        applyHumanRotation(desiredYaw, desiredPitch, target != null)

        // ---- mineração
        val looked: BlockPos? = when {
            downwardCentering -> null
            downwardRecovery -> getLookedDownwardOre()
            else -> getLookedOre(minY, maxY)
        }
        val lookingOre = looked != null

        if (lookingOre) {
            trackedBlock = looked
            updateMiningAttempt(looked)
        } else {
            resetMiningAttempt(false)
        }

        val attackDown = physicalKeyDown(settings.keyBindAttack.keyCode)

        if (lookingOre && (!attackDown || mc.currentScreen is GuiChat || !mc.inGameHasFocus)) {
            mineLikeVanilla(looked)
        }

        restorePhysicalState(settings.keyBindAttack)

        // ---- movimento
        val recoveryAligned = downwardRecovery && downwardCentering && isRecoveryCenterYawAligned()

        var shouldWalk = !downwardRecovery && target != null && autoWalk && shouldWalkToward(target)

        if (shouldWalk && sideTurnActive) {
            val yawDifference = abs(MathHelper.wrapAngleTo180_float(desiredYaw - humanYaw))

            val resumeAngle = if (randomization > 0f) {
                turnResumeAngle
            } else if (isLegitMode()) {
                12f
            } else {
                20f
            }

            val limit = if (turnKeepsWalking) max(30f, resumeAngle) else resumeAngle

            if (turnMovementDelay > 0 || yawDifference > limit) shouldWalk = false
        }

        debugReport(target, minY, maxY, recoveryAligned || shouldWalk)

        setKeyState(settings.keyBindForward, recoveryAligned || shouldWalk)

        val legitStrafe = !downwardRecovery && isLegitMode() && sideTurnActive &&
                turnStrafeTicks > 0 && randomization > 0f

        setKeyState(settings.keyBindLeft, legitStrafe && turnStrafeDirection < 0)
        setKeyState(settings.keyBindRight, legitStrafe && turnStrafeDirection > 0)

        val crouch = useShift && !downwardRecovery && (target != null || lookingOre || sideTurnActive)

        setKeyState(settings.keyBindSneak, downwardRecovery || crouch)
    }

    // ---------------------------------------------------------------- contador de blocos quebrados

    private fun updateBrokenBlockCounter(minY: Int, maxY: Int) {
        val tracked = trackedBlock ?: return
        val world = mc.theWorld ?: return

        if (world.isAirBlock(tracked)) {
            val broken = tracked
            val recovered = downwardRecovery && downwardTarget != null && downwardTarget == tracked

            trackedBlock = null
            resetMiningAttempt(false)

            if (broken == nearbyTarget) nearbyTarget = null
            if (broken == patternTarget) patternTarget = null

            directionLockTicks = 0

            if (recovered) {
                downwardTarget = null
                downwardBlocksRemaining--

                if (downwardBlocksRemaining <= 0) {
                    downwardBlocksRemaining = 0
                    downwardRecovery = false
                    downwardCentering = false
                    waitingForRecoveryLanding = true
                    recoveryHasFallen = false
                }
            } else {
                downwardRecoveryAvailable = true
                emptyLayerTicks = 0
            }

            onAutoBreakBlockBroken()
            return
        }

        if (downwardRecovery && downwardTarget != null && downwardTarget == tracked) {
            if (!isAllowedTarget(tracked)) {
                trackedBlock = null
                downwardTarget = null
            }
            return
        }

        val y = tracked.y
        val block = world.getBlockState(tracked).block
        val isPatternTarget = tracked == patternTarget

        val outOfRange = !isPatternTarget && (y < minY || y > maxY)

        if (outOfRange || !isAllowedOre(block)) {
            if (tracked == nearbyTarget) nearbyTarget = null
            trackedBlock = null
            resetMiningAttempt(true)
        }
    }

    // ---------------------------------------------------------------- padrão em espiral

    private fun usesPatternPlanner() = isSmartSpiralMode()

    private fun findPatternTarget(direction: EnumFacing?, layerY: Int): BlockPos? {
        if (direction == null || direction.axis == EnumFacing.Axis.Y) return null

        val player = mc.thePlayer ?: return null
        val radius = spiralRadius

        if (!(smartLayerY == layerY && smartPlannedRadius == radius && smartRouteForward != null)) {
            smartCenterX = MathHelper.floor_double(player.posX)
            smartCenterZ = MathHelper.floor_double(player.posZ)
            smartLayerY = layerY
            smartPlannedRadius = radius
            smartRouteIndex = 0
            smartRouteForward = direction
            patternTarget = null
        }

        val forward = smartRouteForward ?: return null
        patternDirection = forward

        if (isSmartCandidate(patternTarget, layerY)) return patternTarget

        patternTarget = null

        val side = radius * 2 + 1
        val total = side * side

        while (smartRouteIndex < total) {
            val offset = smartSpiralOffset(smartRouteIndex)
            val a = (offset shr 32).toInt()
            val b = offset.toInt()

            if (max(abs(a), abs(b)) > radius) {
                smartRouteIndex++
                continue
            }

            val right = rightOf(forward)
            val x = smartCenterX + right.frontOffsetX * a + forward.frontOffsetX * b
            val z = smartCenterZ + right.frontOffsetZ * a + forward.frontOffsetZ * b

            for (y in layerY..layerY + 1) {
                val candidate = BlockPos(x, y, z)

                if (isSmartCandidate(candidate, layerY)) {
                    patternTarget = candidate
                    return candidate
                }
            }

            smartRouteIndex++
        }

        return null
    }

    // Offset (direita, frente) do n-ésimo passo da espiral quadrada, empacotado em um Long
    private fun smartSpiralOffset(step: Int): Long {
        if (step <= 0) return 0L

        var a = 0
        var b = 0
        var count = 0
        var length = 1
        var heading = 0

        while (count < step) {
            var repeat = 0

            while (repeat < 2 && count < step) {
                var i = 0

                while (i < length && count < step) {
                    when (heading) {
                        0 -> b++
                        1 -> a++
                        2 -> b--
                        else -> a--
                    }

                    count++
                    i++
                }

                heading = (heading + 1) and 3
                repeat++
            }

            length++
        }

        return (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)
    }

    private fun isSmartCandidate(pos: BlockPos?, layerY: Int): Boolean {
        if (pos == null || !isAllowedTarget(pos)) return false

        val radius = spiralRadius

        if (abs(pos.x - smartCenterX) > radius || abs(pos.z - smartCenterZ) > radius) return false

        val direction = horizontalDirectionTo(pos)

        return isSafeHorizontalTarget(pos, direction, layerY) && hasDirectView(pos)
    }

    // O raio dos olhos até o centro do bloco precisa bater exatamente nele
    private fun hasDirectView(pos: BlockPos): Boolean {
        val player = mc.thePlayer ?: return false
        val world = mc.theWorld ?: return false

        val from = Vec3(player.posX, player.posY + player.eyeHeight.toDouble(), player.posZ)
        val to = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)

        val hit = world.rayTraceBlocks(from, to, false, true, false)

        return hit != null &&
                hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK &&
                pos == hit.blockPos
    }

    private fun offsetOnPlane(base: BlockPos, forward: EnumFacing, right: EnumFacing, f: Int, r: Int, up: Int) =
        BlockPos(
            base.x + forward.frontOffsetX * f + right.frontOffsetX * r,
            base.y + up,
            base.z + forward.frontOffsetZ * f + right.frontOffsetZ * r
        )

    private fun shouldWalkToward(target: BlockPos?): Boolean {
        if (!usesPatternPlanner() || target == null) return true

        val player = mc.thePlayer ?: return true

        var direction = patternDirection ?: tunnelDirection
        if (direction == null || direction.axis == EnumFacing.Axis.Y) {
            direction = horizontalDirectionTo(target)
        }

        val dx = target.x + 0.5 - player.posX
        val dz = target.z + 0.5 - player.posZ

        if (isSmartSpiralMode()) return dx * dx + dz * dz > 3.24

        val forward = dx * direction.frontOffsetX + dz * direction.frontOffsetZ
        return forward > 1.8
    }

    private fun clearPatternPlan() {
        patternTarget = null
        patternDirection = null
        smartLayerY = Int.MIN_VALUE
        smartPlannedRadius = -1
        smartRouteIndex = 0
        smartRouteForward = null
    }

    // ---------------------------------------------------------------- busca de alvos (túnel)

    private fun findTarget(direction: EnumFacing, minY: Int, maxY: Int): BlockPos? {
        val origin = origin(minY)

        for (distance in 1..6) {
            val x = origin.x + direction.frontOffsetX * distance
            val z = origin.z + direction.frontOffsetZ * distance

            for (y in minY..maxY) {
                val pos = BlockPos(x, y, z)

                if (isAllowedTarget(pos) && isSafeHorizontalTarget(pos, direction, minY)) return pos
            }
        }

        return null
    }

    private fun findNearbyTarget(minY: Int, maxY: Int): BlockPos? {
        val player = mc.thePlayer ?: return null

        val px = MathHelper.floor_double(player.posX)
        val pz = MathHelper.floor_double(player.posZ)
        val radiusSquared = 36

        val previous = nearbyTarget

        if (previous != null) {
            val dx = previous.x - px
            val dz = previous.z - pz

            if (dx * dx + dz * dz <= radiusSquared &&
                previous.y in minY..maxY &&
                isAllowedTarget(previous) &&
                isSafeHorizontalTarget(previous, horizontalDirectionTo(previous), minY)
            ) {
                return previous
            }

            nearbyTarget = null
        }

        var best: BlockPos? = null
        var bestScore = Double.MAX_VALUE
        val tunnel = tunnelDirection

        for (dx in -6..6) {
            for (dz in -6..6) {
                val distanceSquared = dx * dx + dz * dz
                if (distanceSquared > radiusSquared) continue

                for (y in minY..maxY) {
                    val pos = BlockPos(px + dx, y, pz + dz)

                    if (!isAllowedTarget(pos)) continue

                    val direction = horizontalDirectionTo(pos)
                    if (!isSafeHorizontalTarget(pos, direction, minY)) continue

                    val angle = Math.toDegrees(atan2(dz.toDouble(), dx.toDouble())) - 90.0
                    val yawDifference = abs(MathHelper.wrapAngleTo180_float(angle.toFloat() - humanYaw)).toDouble()

                    val sideFace = tunnel != null &&
                            (direction == leftOf(tunnel) || direction == rightOf(tunnel)) &&
                            hasFourBlockFace(direction, minY)

                    val score = distanceSquared * 100.0 +
                            yawDifference * 0.35 +
                            abs(y - minY) * 4.0 -
                            (if (sideFace) 100000.0 else 0.0)

                    if (score < bestScore) {
                        bestScore = score
                        best = pos
                    }
                }
            }
        }

        nearbyTarget = best
        return best
    }

    private fun horizontalDirectionTo(pos: BlockPos): EnumFacing {
        val player = mc.thePlayer ?: return EnumFacing.NORTH

        val dx = pos.x - MathHelper.floor_double(player.posX)
        val dz = pos.z - MathHelper.floor_double(player.posZ)

        return if (abs(dx) >= abs(dz)) {
            if (dx >= 0) EnumFacing.EAST else EnumFacing.WEST
        } else {
            if (dz >= 0) EnumFacing.SOUTH else EnumFacing.NORTH
        }
    }

    private fun chooseDirection(minY: Int, maxY: Int) {
        val tunnel = tunnelDirection ?: return

        val left = leftOf(tunnel)
        val right = rightOf(tunnel)

        val leftScore = score(left, minY, maxY)
        val rightScore = score(right, minY, maxY)
        val leftFace = hasFourBlockFace(left, minY)
        val rightFace = hasFourBlockFace(right, minY)

        if (leftScore <= 0 && rightScore <= 0) {
            directionLockTicks = 12
            return
        }

        val previous = tunnelDirection

        if (leftFace && !rightFace && leftScore > 0) {
            tunnelDirection = left
        } else if (rightFace && !leftFace && rightScore > 0) {
            tunnelDirection = right
        } else if (leftScore > rightScore) {
            tunnelDirection = left
        } else if (rightScore > leftScore) {
            tunnelDirection = right
        } else if (leftScore > 0) {
            tunnelDirection = if (preferRight) right else left
            preferRight = !preferRight
        }

        if (previous != tunnelDirection) {
            nearbyTarget = null
            clearPatternPlan()
        }

        directionLockTicks = 12
    }

    private fun score(direction: EnumFacing, minY: Int, maxY: Int): Int {
        val origin = origin(minY)
        var total = 0

        for (distance in 1..6) {
            val x = origin.x + direction.frontOffsetX * distance
            val z = origin.z + direction.frontOffsetZ * distance
            val weight = 7 - distance

            for (y in minY..maxY) {
                val pos = BlockPos(x, y, z)

                if (isAllowedTarget(pos) && isSafeHorizontalTarget(pos, direction, minY)) {
                    total += weight * 4 + (maxY - y)
                }
            }
        }

        return total
    }

    // ---------------------------------------------------------------- descida (recuperação)

    private fun startDownwardRecoveryIfSidesAreEmpty(minY: Int, maxY: Int): Boolean {
        if (downwardRecovery || !downwardRecoveryAvailable) return false

        val tunnel = tunnelDirection ?: return false

        if (!isSmartSpiralMode() &&
            (score(leftOf(tunnel), minY, maxY) > 0 || score(rightOf(tunnel), minY, maxY) > 0)
        ) {
            emptyLayerTicks = 0
            return false
        }

        val found = if (isSmartSpiralMode()) {
            findPatternTarget(tunnel, minY)
        } else {
            findNearbyTarget(minY, maxY)
        }

        if (found != null) {
            emptyLayerTicks = 0
            return false
        }

        emptyLayerTicks++
        if (emptyLayerTicks < 10) return false

        val down = findDownwardTarget() ?: return false

        emptyLayerTicks = 0
        downwardRecovery = true
        clearPatternPlan()
        downwardRecoveryAvailable = false
        downwardCentering = true
        recoveryDepthStarted = chooseRecoveryDepth()
        downwardBlocksRemaining = recoveryDepthStarted
        downwardTarget = down
        recoveryStartY = mc.thePlayer?.posY ?: 0.0
        recoveryColumnX = down.x
        recoveryColumnZ = down.z
        directionLockTicks = 0
        yawVelocity *= 0.35f

        return true
    }

    private fun chooseRecoveryDepth() = max(1, min(2, downwardBlocks))

    private fun findDownwardTarget(): BlockPos? {
        val player = mc.thePlayer ?: return null

        val px = MathHelper.floor_double(player.posX)
        val pz = MathHelper.floor_double(player.posZ)
        val baseY = MathHelper.floor_double(player.posY - 0.01)

        for (i in 0..1) {
            val pos = BlockPos(px, baseY - i, pz)

            if (isAllowedTarget(pos)) return pos
        }

        return null
    }

    private fun isCenteredOverRecoveryColumn(): Boolean {
        val player = mc.thePlayer ?: return true

        val dx = recoveryColumnX + 0.5 - player.posX
        val dz = recoveryColumnZ + 0.5 - player.posZ

        return dx * dx + dz * dz <= 0.0625
    }

    private fun yawToRecoveryColumn(): Float {
        val player = mc.thePlayer ?: return humanYaw

        val dx = recoveryColumnX + 0.5 - player.posX
        val dz = recoveryColumnZ + 0.5 - player.posZ

        if (dx * dx + dz * dz <= 0.0004) return humanYaw

        return (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
    }

    private fun isRecoveryCenterYawAligned() =
        abs(MathHelper.wrapAngleTo180_float(yawToRecoveryColumn() - humanYaw)) <= 12f

    private fun resolveDownwardTarget(): BlockPos? {
        if (!downwardRecovery || downwardBlocksRemaining <= 0) return null

        val current = downwardTarget
        if (current != null && isAllowedTarget(current)) return current

        downwardTarget = findDownwardTarget()

        if (downwardTarget == null) {
            downwardRecovery = false
            downwardCentering = false
            downwardBlocksRemaining = 0
            recoveryDepthStarted = 0
            directionLockTicks = 12

            if (mc.thePlayer?.onGround == false) {
                waitingForRecoveryLanding = true
                recoveryHasFallen = true
            }
        }

        return downwardTarget
    }

    // ---------------------------------------------------------------- blocos permitidos e seguros

    private fun origin(y: Int): BlockPos {
        val player = mc.thePlayer!!

        return BlockPos(MathHelper.floor_double(player.posX), y, MathHelper.floor_double(player.posZ))
    }

    private fun isAllowedTarget(pos: BlockPos?): Boolean {
        if (pos == null) return false

        val world = mc.theWorld ?: return false

        return !world.isAirBlock(pos) && isAllowedOre(world.getBlockState(pos).block)
    }

    // Evita abrir buracos: o bloco logo atrás do alvo precisa ser sólido
    private fun isSafeHorizontalTarget(pos: BlockPos?, direction: EnumFacing?, minY: Int): Boolean {
        if (!avoidOpenings || pos == null || direction == null || direction.axis == EnumFacing.Axis.Y) return true

        val player = mc.thePlayer ?: return true
        val world = mc.theWorld ?: return true

        val dx = pos.x - MathHelper.floor_double(player.posX)
        val dz = pos.z - MathHelper.floor_double(player.posZ)
        val along = dx * direction.frontOffsetX + dz * direction.frontOffsetZ

        if (along <= 0) return true

        val behind = pos.offset(direction)

        if (!world.isBlockLoaded(behind, false)) return false

        return world.getBlockState(behind).block.material.blocksMovement()
    }

    // Uma face de 2x2 blocos de minério na direção dada
    private fun hasFourBlockFace(direction: EnumFacing?, minY: Int): Boolean {
        if (direction == null || direction.axis == EnumFacing.Axis.Y) return false

        val origin = origin(minY)
        val right = rightOf(direction)

        for (start in -1..0) {
            var complete = true

            var column = start
            while (column <= start + 1 && complete) {
                for (height in 0..1) {
                    val pos = offsetOnPlane(origin, direction, right, 1, column, height)

                    if (!(isAllowedTarget(pos) && isSafeHorizontalTarget(pos, direction, minY))) {
                        complete = false
                        break
                    }
                }

                column++
            }

            if (complete) return true
        }

        return false
    }

    private fun isAllowedOre(block: Block): Boolean {
        // Minérios do módulo original
        if (block === Blocks.lapis_ore ||
            block === Blocks.redstone_ore ||
            block === Blocks.lit_redstone_ore ||
            block === Blocks.iron_ore ||
            block === Blocks.emerald_ore
        ) {
            return true
        }

        if (targets == "Original") return false

        // AllOres: todos os minérios comuns
        if (block === Blocks.coal_ore ||
            block === Blocks.gold_ore ||
            block === Blocks.diamond_ore ||
            block === Blocks.quartz_ore
        ) {
            return true
        }

        if (targets != "OresAndBlocks") return false

        // OresAndBlocks: também os blocos de mineral (minas de servidor costumam usar estes)
        return block === Blocks.lapis_block ||
                block === Blocks.redstone_block ||
                block === Blocks.iron_block ||
                block === Blocks.gold_block ||
                block === Blocks.diamond_block ||
                block === Blocks.emerald_block ||
                block === Blocks.coal_block
    }

    private var debugTicks = 0

    private fun debugReport(target: BlockPos?, minY: Int, maxY: Int, walking: Boolean) {
        if (!debug) return

        debugTicks++
        if (debugTicks < 40) return
        debugTicks = 0

        val player = mc.thePlayer ?: return
        val world = mc.theWorld ?: return
        val direction = tunnelDirection ?: return

        val px = MathHelper.floor_double(player.posX)
        val pz = MathHelper.floor_double(player.posZ)

        // Primeiro bloco que não é ar à frente, em cada camada (pés e cabeça)
        val ahead = StringBuilder()

        for (y in minY..maxY) {
            var found = "ar"

            for (distance in 1..6) {
                val pos = BlockPos(
                    px + direction.frontOffsetX * distance,
                    y,
                    pz + direction.frontOffsetZ * distance
                )

                if (!world.isAirBlock(pos)) {
                    found = Block.blockRegistry.getNameForObject(world.getBlockState(pos).block).toString() +
                            " (a $distance)"
                    break
                }
            }

            ahead.append(" y=").append(y).append(": ").append(found)
        }

        val hit = mc.objectMouseOver
        val looking = if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            Block.blockRegistry.getNameForObject(world.getBlockState(hit.blockPos).block).toString()
        } else {
            "nada"
        }

        val targetText = if (target != null) "sim" else "NAO"

        val keyForward = mc.gameSettings?.keyBindForward?.isKeyDown == true
        val targetPos = if (target != null) "${target.x},${target.y},${target.z}" else "-"

        player.addChatMessage(
            ChatComponentText(
                "§7[ABP] §fdir=$direction alvo=$targetText($targetPos) andando=$walking tecla=$keyForward " +
                        "pos=${"%.2f".format(player.posX)},${"%.2f".format(player.posY)},${"%.2f".format(player.posZ)} " +
                        "chao=${player.onGround} yaw=${"%.0f".format(player.rotationYaw)} " +
                        "pitch=${"%.0f".format(player.rotationPitch)} pausa=$humanPauseTicks " +
                        "curva=$sideTurnActive espera=$turnMovementDelay desc=$downwardRecovery " +
                        "olhando=$looking |$ahead"
            )
        )
    }

    // ---------------------------------------------------------------- rotação humanizada

    private fun applyHumanRotation(targetYaw: Float, targetPitch: Float, hasTarget: Boolean) {
        val legit = isLegitMode()

        val yawError = MathHelper.wrapAngleTo180_float(targetYaw - humanYaw)
        val pitchError = targetPitch - humanPitch

        // Já está olhando para o alvo: congela a mira (sem tremor)
        val settled = removeJitter && hasTarget && isLookingAtRotationTarget() &&
                abs(yawError) <= 4f && abs(pitchError) <= 4f

        if (settled) {
            yawVelocity = 0f
            pitchVelocity = 0f
            sideTurnActive = false
            applyRotation()
            return
        }

        val startThreshold = if (legit) 4.8f else 6.5f
        val continueThreshold = if (legit) 2f else 2.8f

        val turning = if (sideTurnActive) abs(yawError) > continueThreshold else abs(yawError) > startThreshold

        if (turning && !sideTurnActive) {
            beginTurnProfile(legit, yawError)
        } else if (!turning && sideTurnActive) {
            turnYawOffset = 0f
            turnPitchOffset = 0f
            turnArcYaw = 0f
            turnArcPitch = 0f
            turnStartError = 0f
            turnEaseBias = 1f
            turnSpeedScale = 1f
            turnResponseScale = 1f
        }

        sideTurnActive = turning

        jitterPhase += if (turning) 0.19 else if (hasTarget) 0.12 else 0.15

        val errorFactor = MathHelper.clamp_float(abs(yawError) / 42f, 0f, 1f)
        val random = MathHelper.clamp_float(randomization / 20f, 0f, 1f)

        val jitterEnabled = random > 0f && !columnAimLocked && (!removeJitter || turning)

        var jitterAmount = if (jitterEnabled) {
            (if (turning) 0.22f + errorFactor * 0.78f else 0.55f) * (0.35f + random * 0.65f)
        } else {
            0f
        }

        if (turning) jitterAmount *= turnJitter.toFloat() / 50f

        val jitterScale = if (legit) 0.72f else 1f

        val jitterYaw = ((sin(jitterPhase) * 0.38 + sin(jitterPhase * 0.47) * 0.19) *
                jitterAmount.toDouble() * jitterScale.toDouble()).toFloat()
        val jitterPitch = ((sin(jitterPhase * 0.73) * 0.24 + cos(jitterPhase * 0.39) * 0.12) *
                jitterAmount.toDouble() * jitterScale.toDouble()).toFloat()

        val progress = if (turning && turnStartError > 0.01f) {
            MathHelper.clamp_float(1f - abs(yawError) / turnStartError, 0f, 1f)
        } else {
            1f
        }

        val arcFactor = if (turning) sin(Math.PI * progress.toDouble()).toFloat() else 0f
        val offsetFactor = if (turning) (1f - progress) * errorFactor else 0f

        val aimYaw = targetYaw + jitterYaw + turnYawOffset * offsetFactor + turnArcYaw * arcFactor
        val aimPitch = MathHelper.clamp_float(
            targetPitch + jitterPitch + turnPitchOffset * offsetFactor + turnArcPitch * arcFactor,
            -89f, 89f
        )

        val yawDifference = MathHelper.wrapAngleTo180_float(aimYaw - humanYaw)
        val pitchDifference = aimPitch - humanPitch

        val speed = rotationSpeed
        val speedFactor = MathHelper.clamp_float((speed - 1f) / 59f, 0f, 1f)
        val smooth = aimSmoothness.toFloat() / 100f

        val speedScale = if (random > 0f) turnSpeedScale else 1f

        val maxYawStep = speed * (if (hasTarget) 0.9f else 1f) * speedScale *
                (if (legit) 0.92f else 1f) * (1f - smooth * 0.08f)
        val maxPitchStep = speed * (if (hasTarget) 0.76f else 0.86f) * speedScale *
                (if (legit) 0.9f else 1f) * (1f - smooth * 0.1f)

        val responseScale = if (random > 0f) turnResponseScale else 1f

        val easing = if (turning) {
            0.7f + 0.46f * sin(Math.PI * progress.toDouble()).toFloat()
        } else {
            1f
        }

        val yawGain = ((if (legit) 0.27f else 0.35f) + speedFactor * 0.31f) *
                responseScale * easing * (1f - smooth * 0.1f)
        val pitchGain = ((if (legit) 0.24f else 0.32f) + speedFactor * 0.25f) *
                responseScale * (0.9f + 0.1f * easing) * (1f - smooth * 0.12f)

        val yawTarget = MathHelper.clamp_float(yawDifference * yawGain, -maxYawStep, maxYawStep)
        val pitchTarget = MathHelper.clamp_float(pitchDifference * pitchGain, -maxPitchStep, maxPitchStep)

        val yawAcceleration = ((if (legit) 0.28f else 0.36f) + speedFactor * 0.34f) *
                (if (turning) 0.76f + 0.28f * arcFactor else 1f) * (1f - smooth * 0.18f)
        val pitchAcceleration = ((if (legit) 0.25f else 0.33f) + speedFactor * 0.29f) *
                (if (turning) 0.8f + 0.22f * arcFactor else 1f) * (1f - smooth * 0.2f)

        yawVelocity += (yawTarget - yawVelocity) * yawAcceleration
        pitchVelocity += (pitchTarget - pitchVelocity) * pitchAcceleration

        // Perto do alvo: freia
        if (abs(yawDifference) < max(if (legit) 2.2f else 1.4f, abs(yawVelocity) * 1.25f)) {
            yawVelocity *= if (legit) 0.55f else 0.64f
        }

        if (abs(pitchDifference) < max(if (legit) 1.8f else 1.1f, abs(pitchVelocity) * 1.2f)) {
            pitchVelocity *= if (legit) 0.53f else 0.62f
        }

        val yawStep = MathHelper.clamp_float(yawVelocity, -abs(yawDifference), abs(yawDifference))
        val pitchStep = MathHelper.clamp_float(pitchVelocity, -abs(pitchDifference), abs(pitchDifference))

        humanYaw += yawStep
        humanPitch = MathHelper.clamp_float(humanPitch + pitchStep, -89f, 89f)

        // Legit: arredonda para a granularidade de sensibilidade do mouse
        if (legit) {
            humanYaw -= humanYaw % 0.0096f
            humanPitch -= humanPitch % 0.0096f
        }

        applyRotation()
    }

    private fun beginTurnProfile(legit: Boolean, yawError: Float) {
        val random = MathHelper.clamp_float(randomization / 20f, 0f, 1f)

        if (random <= 0f) {
            turnSpeedScale = 1f
            turnResponseScale = 1f
            turnYawOffset = 0f
            turnPitchOffset = 0f
            turnStartError = max(1f, abs(yawError))
            turnArcYaw = 0f
            turnArcPitch = 0f
            turnEaseBias = 1f
            turnResumeAngle = if (legit) 12f else 20f
            turnKeepsWalking = false
            turnMovementDelay = if (legit) 1 else 0
            turnStrafeDirection = 0
            turnStrafeTicks = 0
            return
        }

        val rnd = ThreadLocalRandom.current()

        turnStartError = max(1f, abs(yawError))

        val variation = turnSpeedVariation.toFloat() / 100f

        turnSpeedScale = 1f + (rnd.nextFloat() * 2f - 1f) * 0.6f * variation * random
        turnResponseScale = 1f + (rnd.nextFloat() * 2f - 1f) * 0.47f * variation * random

        val arc = turnArc * random

        turnYawOffset = (rnd.nextFloat() * 2f - 1f) * arc * 0.58f
        turnPitchOffset = (rnd.nextFloat() * 2f - 1f) * arc * 0.28f

        val sign = if (yawError >= 0f) 1f else -1f
        val arcSign = if (rnd.nextFloat() < 0.78f) sign else -sign

        turnArcYaw = arcSign * arc * (0.2f + rnd.nextFloat() * 0.8f)
        turnArcPitch = (rnd.nextFloat() * 2f - 1f) * arc * 0.38f
        turnEaseBias = 0.65f + rnd.nextFloat() * 1.15f

        val chanceScale = 0.4f + 0.6f * random

        turnKeepsWalking = rnd.nextFloat() < (turnWalkChance.toFloat() / 100f) * chanceScale

        val resumeBase = if (legit) 8f else 13f
        val resumeSpread = if (legit) 8f else 10f

        turnResumeAngle = resumeBase + rnd.nextFloat() * resumeSpread

        val delayMax = if (legit) 1 + (3f * random).roundToInt() else (2f * random).roundToInt()

        turnMovementDelay = if (turnKeepsWalking) 0 else rnd.nextInt(delayMax + 1)

        val strafe = legit && rnd.nextFloat() < (turnStrafeChance.toFloat() / 100f) * chanceScale

        if (strafe) {
            turnStrafeDirection = if (yawError >= 0f) 1 else -1

            if (rnd.nextFloat() < 0.18f * random) turnStrafeDirection = -turnStrafeDirection

            turnStrafeTicks = 2 + rnd.nextInt(1 + max(1, (5f * random).roundToInt()))
        } else {
            turnStrafeDirection = 0
            turnStrafeTicks = 0
        }

        if (legit && humanPauseTicks <= 0 &&
            rnd.nextFloat() < (turnPauseChance.toFloat() / 100f) * chanceScale
        ) {
            humanPauseTicks = if (rnd.nextFloat() < 0.28f) rnd.nextInt(32, 41) else rnd.nextInt(4, 13)
        }
    }

    private fun isLookingAtRotationTarget(): Boolean {
        val target = rotationTarget ?: return false
        val over = mc.objectMouseOver ?: return false

        if (over.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return false

        val pos = over.blockPos
        if (pos == null || !isAllowedTarget(pos)) return false

        if (pos == target) return true

        val base = lockedColumnBase

        return columnAimLocked && base != null &&
                pos.x == base.x && pos.z == base.z &&
                (pos.y == base.y || (lockedAimDoubleHeight && pos.y == base.y + 1))
    }

    private fun isLegitMode() = mode == "Legit"

    private fun isLineMode() = miningPattern == "Line"

    private fun isSmartSpiralMode() = miningPattern == "Smart Spiral"

    // Aplica a rotação interna na câmera do jogador
    private fun applyRotation() {
        val player = mc.thePlayer ?: return

        player.rotationYaw = humanYaw
        player.rotationPitch = humanPitch
    }

    private fun rotationsTo(pos: BlockPos) =
        rotationsToPoint(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)

    private fun rotationsForTarget(target: BlockPos, layerBase: Int): FloatArray {
        val player = mc.thePlayer ?: return rotationsTo(target)

        if (downwardRecovery) {
            clearColumnAimLock()
            return rotationsToPoint(target.x + 0.5, target.y + 0.88, target.z + 0.5)
        }

        if (!stableColumnAim) {
            clearColumnAimLock()
            return rotationsTo(target)
        }

        val patternDir = patternDirection
        val direction = if (patternDir != null && patternDir.axis != EnumFacing.Axis.Y) {
            patternDir
        } else {
            horizontalDirectionTo(target)
        }

        var base = findTwoBlockColumnBase(target, layerBase)
        var doubleHeight = base != null

        // Mantém a coluna dupla travada enquanto o alvo continua nela
        if (!doubleHeight && columnAimLocked && lockedAimDoubleHeight &&
            lockedAimDirection == direction &&
            (target.y == lockedAimLayerY || target.y == lockedAimLayerY + 1)
        ) {
            doubleHeight = true
            base = BlockPos(target.x, lockedAimLayerY, target.z)
        }

        val column = if (doubleHeight) base!! else target
        val layer = column.y

        val stillLocked = columnAimLocked &&
                lockedAimDirection == direction &&
                lockedAimLayerY == layer &&
                lockedAimDoubleHeight == doubleHeight

        if (stillLocked) {
            lockedColumnBase = column

            if (isLookingAtRotationTarget()) columnAimMissTicks = 0 else columnAimMissTicks++

            val missLimit = if (isLineMode()) 12 else 8

            if (columnAimMissTicks <= missLimit) {
                return floatArrayOf(lockedColumnYaw, lockedColumnPitch)
            }

            clearColumnAimLock()
        } else if (columnAimLocked) {
            clearColumnAimLock()
        }

        val distance = if (doubleHeight) 2.15 else 1.9
        val pointY = if (doubleHeight) column.y + 1.0 - 0.001 else column.y + 0.54

        val rotations = rotationsToPoint(
            player.posX + direction.frontOffsetX * distance,
            pointY,
            player.posZ + direction.frontOffsetZ * distance
        )

        val dx = target.x + 0.5 - player.posX
        val dz = target.z + 0.5 - player.posZ

        val lockNow = isLineMode() || dx * dx + dz * dz <= 4.0

        if (lockNow) {
            lockedColumnBase = column
            lockedColumnYaw = rotations[0]
            lockedColumnPitch = rotations[1]
            columnAimLocked = true
            columnAimMissTicks = 0
            lockedAimDirection = direction
            lockedAimLayerY = layer
            lockedAimDoubleHeight = doubleHeight
        }

        return rotations
    }

    private fun findTwoBlockColumnBase(target: BlockPos?, layerBase: Int): BlockPos? {
        if (target == null) return null

        if (isAllowedTarget(target) && isAllowedTarget(target.up())) return target

        val below = target.down()
        if (isAllowedTarget(below) && isAllowedTarget(target)) return below

        val base = BlockPos(target.x, layerBase, target.z)
        if (isAllowedTarget(base) && isAllowedTarget(base.up())) return base

        return null
    }

    private fun rotationsToPoint(x: Double, y: Double, z: Double): FloatArray {
        val player = mc.thePlayer ?: return floatArrayOf(humanYaw, humanPitch)

        val dx = x - player.posX
        val dy = y - (player.posY + player.eyeHeight.toDouble())
        val dz = z - player.posZ
        val horizontal = sqrt(dx * dx + dz * dz)

        val yaw = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
        val pitch = (-Math.toDegrees(atan2(dy, horizontal))).toFloat()

        return floatArrayOf(yaw, MathHelper.clamp_float(pitch, -89f, 89f))
    }

    private fun clearColumnAimLock() {
        lockedColumnBase = null
        lockedColumnYaw = 0f
        lockedColumnPitch = 0f
        columnAimLocked = false
        columnAimMissTicks = 0
        lockedAimDirection = null
        lockedAimLayerY = Int.MIN_VALUE
        lockedAimDoubleHeight = false
    }

    // ---------------------------------------------------------------- mineração

    private fun getLookedOre(minY: Int, maxY: Int): BlockPos? {
        val over = mc.objectMouseOver ?: return null

        if (over.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return null

        val pos = over.blockPos ?: return null
        val base = lockedColumnBase

        val locked = pos == rotationTarget || pos == patternTarget ||
                (columnAimLocked && base != null &&
                        pos.x == base.x && pos.z == base.z &&
                        (pos.y == base.y || (lockedAimDoubleHeight && pos.y == base.y + 1)))

        if (!locked && (pos.y < minY || pos.y > maxY)) return null
        if (!isAllowedTarget(pos)) return null
        if (!locked && !isSafeHorizontalTarget(pos, horizontalDirectionTo(pos), minY)) return null

        return pos
    }

    private fun getLookedDownwardOre(): BlockPos? {
        val target = downwardTarget ?: return null
        val over = mc.objectMouseOver ?: return null

        if (over.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return null

        val pos = over.blockPos

        return if (target == pos && isAllowedTarget(pos)) pos else null
    }

    // Quebra o bloco do mesmo jeito que o jogo faz quando você segura o botão
    private fun mineLikeVanilla(pos: BlockPos?) {
        if (pos == null) return

        val controller = mc.playerController ?: return
        val over = mc.objectMouseOver ?: return
        val player = mc.thePlayer ?: return

        val side = over.sideHit ?: EnumFacing.UP

        if (controller.onPlayerDamageBlock(pos, side)) {
            player.swingItem()
        }
    }

    private fun updateMiningAttempt(pos: BlockPos?) {
        val controller = mc.playerController

        if (pos == null || controller == null) {
            resetMiningAttempt(false)
            return
        }

        if (pos != miningAttemptTarget) {
            miningAttemptTarget = pos
            miningAttemptTicks = 1
            return
        }

        miningAttemptTicks++
        if (miningAttemptTicks < 45) return

        // Travou no mesmo bloco por 45 ticks: recomeça a quebra
        controller.resetBlockRemoving()
        miningAttemptTicks = 0
    }

    private fun resetMiningAttempt(abort: Boolean) {
        miningAttemptTarget = null
        miningAttemptTicks = 0

        if (abort) mc.playerController?.resetBlockRemoving()
    }

    private fun isDirectionSettled(): Boolean {
        val tunnel = tunnelDirection ?: return true
        val difference = abs(MathHelper.wrapAngleTo180_float(yawFor(tunnel) - humanYaw))

        return difference <= 7f && abs(yawVelocity) <= 2.5f
    }

    // ---------------------------------------------------------------- SpeedMine automático e pausas

    private fun onAutoBreakBlockBroken() {
        scheduleHumanPauseAfterProgress()

        if (!autoSpeedMine) {
            cycleBlocksBroken = 0
            return
        }

        cycleBlocksBroken++
        if (cycleBlocksBroken < 2) return

        cycleBlocksBroken = 0

        // Alterna o SpeedMine a cada 2 blocos quebrados
        SpeedMine.state = !SpeedMine.state
        speedMineChanged = true
    }

    private fun synchronizeSpeedMineOption() {
        if (!autoSpeedMine && speedMineChanged) restoreSpeedMineState()
    }

    private fun restoreSpeedMineState() {
        if (speedMineChanged && SpeedMine.state != initialSpeedMineState) {
            SpeedMine.state = initialSpeedMineState
        }

        speedMineChanged = false
        cycleBlocksBroken = 0
    }

    private fun scheduleHumanPauseAfterProgress() {
        val random = MathHelper.clamp_float(randomization / 20f, 0f, 1f)

        if (!isLegitMode() || random <= 0f) {
            blocksUntilHumanPause = nextHumanPauseBlockCount()
            return
        }

        blocksUntilHumanPause--

        if (blocksUntilHumanPause > 0 || humanPauseTicks > 0) return

        val rnd = ThreadLocalRandom.current()

        humanPauseTicks = if (rnd.nextFloat() < 0.12f + 0.18f * random) {
            rnd.nextInt(32, 41)
        } else {
            rnd.nextInt(5, 19)
        }

        blocksUntilHumanPause = nextHumanPauseBlockCount()
    }

    private fun nextHumanPauseBlockCount(): Int {
        val random = MathHelper.clamp_float(randomization / 20f, 0f, 1f)

        val low = max(18, (34f - 16f * random).roundToInt())
        val high = max(20, (42f - 10f * random).roundToInt())

        return low + ThreadLocalRandom.current().nextInt(high + 1)
    }

    // ---------------------------------------------------------------- AutoQuit

    private fun findAutoQuitThreat(): EntityOtherPlayerMP? {
        if (!autoQuit) return null

        val player = mc.thePlayer ?: return null
        val world = mc.theWorld ?: return null

        val range = quitRange.toDouble()
        val rangeSquared = range * range

        var best: EntityOtherPlayerMP? = null
        var bestDistance = Double.MAX_VALUE

        for (entity in world.playerEntities) {
            if (entity !is EntityOtherPlayerMP) continue
            if (entity.entityId == player.entityId) continue
            if (entity.isDead) continue
            if (isFriend(entity.name)) continue

            val distance = player.getDistanceSqToEntity(entity)

            if (distance <= rangeSquared && distance < bestDistance) {
                best = entity
                bestDistance = distance
            }
        }

        return best
    }

    private fun isFriend(name: String) =
        friendsConfig.friends.any { it.playerName.equals(name, ignoreCase = true) }

    private fun triggerAutoQuit(threat: EntityOtherPlayerMP?) {
        val name = threat?.name ?: "unknown"

        state = false

        mc.netHandler?.networkManager?.closeChannel(
            ChatComponentText("Auto Quit: $name entered the protected range.")
        )
    }

    // ---------------------------------------------------------------- direções e teclas

    private fun horizontalFacing(yaw: Float): EnumFacing = EnumFacing.fromAngle(yaw.toDouble())

    private fun yawFor(direction: EnumFacing): Float =
        (Math.toDegrees(atan2(direction.frontOffsetZ.toDouble(), direction.frontOffsetX.toDouble())) - 90.0).toFloat()

    private fun rightOf(direction: EnumFacing): EnumFacing = when (direction) {
        EnumFacing.NORTH -> EnumFacing.EAST
        EnumFacing.EAST -> EnumFacing.SOUTH
        EnumFacing.SOUTH -> EnumFacing.WEST
        else -> EnumFacing.NORTH
    }

    private fun leftOf(direction: EnumFacing): EnumFacing = when (direction) {
        EnumFacing.NORTH -> EnumFacing.WEST
        EnumFacing.WEST -> EnumFacing.SOUTH
        EnumFacing.SOUTH -> EnumFacing.EAST
        else -> EnumFacing.NORTH
    }

    // Estado físico da tecla (teclado ou mouse), ignorando o que o módulo forçou
    private fun physicalKeyDown(code: Int): Boolean = try {
        if (code < 0) Mouse.isButtonDown(code + 100) else Keyboard.isKeyDown(code)
    } catch (e: Throwable) {
        false
    }

    private fun setKeyState(key: KeyBinding, pressed: Boolean) {
        val code = key.keyCode

        KeyBinding.setKeyBindState(code, pressed || physicalKeyDown(code))
    }

    private fun restorePhysicalState(key: KeyBinding) {
        val code = key.keyCode

        KeyBinding.setKeyBindState(code, physicalKeyDown(code))
    }

    private fun releaseAutomatedKeys() {
        val settings = mc.gameSettings ?: return

        restorePhysicalState(settings.keyBindAttack)
        restorePhysicalState(settings.keyBindForward)
        restorePhysicalState(settings.keyBindLeft)
        restorePhysicalState(settings.keyBindRight)
        restorePhysicalState(settings.keyBindSneak)
    }

    override val tag: String
        get() = mode
}
