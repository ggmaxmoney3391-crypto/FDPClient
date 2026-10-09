/*
 * FDPClient Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/SkidderMC/FDPClient/
 */
package net.ccbluex.liquidbounce.injection.forge.mixins.render;

import net.ccbluex.liquidbounce.features.module.modules.visual.HideClans;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.client.particle.EntityParticleEmitter;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;

@Mixin(EffectRenderer.class)
@SideOnly(Side.CLIENT)
public abstract class MixinEffectRenderer {

    @Shadow
    protected abstract void updateEffectLayer(int layer);

    @Shadow
    private List<EntityParticleEmitter> particleEmitters;

    /**
     * @author Mojang
     * @author Marco
     */
    @Overwrite
    public void updateEffects() {
        try {
            for (int i = 0; i < 4; ++i)
                updateEffectLayer(i);

            for (final Iterator<EntityParticleEmitter> it = particleEmitters.iterator(); it.hasNext(); ) {
                final EntityParticleEmitter entityParticleEmitter = it.next();

                entityParticleEmitter.onUpdate();

                if (entityParticleEmitter.isDead)
                    it.remove();
            }
        } catch(final ConcurrentModificationException ignored) {
        }
    }

    // HideClans: não cria a partícula se ela nasce em cima de um amigo escondido
    @Inject(method = "spawnEffectParticle", at = @At("HEAD"), cancellable = true)
    private void hideClanParticle(int particleId, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed, int[] parameters, CallbackInfoReturnable<EntityFX> cir) {
        if (HideClans.INSTANCE.shouldHideParticle(x, y, z)) {
            cir.setReturnValue(null);
        }
    }

    // HideClans: pega partículas adicionadas direto por outros caminhos
    @Inject(method = "addEffect", at = @At("HEAD"), cancellable = true)
    private void hideClanEffect(EntityFX effect, CallbackInfo ci) {
        if (effect != null && HideClans.INSTANCE.shouldHideParticle(effect.posX, effect.posY, effect.posZ)) {
            ci.cancel();
        }
    }
}
