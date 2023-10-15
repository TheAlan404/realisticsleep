package com.github.steveplays28.realisticsleep.mixin;

import com.github.steveplays28.realisticsleep.api.RealisticSleepApi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import static com.github.steveplays28.realisticsleep.RealisticSleep.config;

@Environment(EnvType.CLIENT)
@Mixin(value = WorldRenderer.class, priority = 500)
public class WorldRendererMixin {
	@Shadow
	@Nullable
	private ClientWorld world;

	@ModifyConstant(method = "renderClouds(Lnet/minecraft/client/util/math/MatrixStack;Lorg/joml/Matrix4f;FDDD)V", constant = @Constant(floatValue = 0.03f))
	private float modifyCloudSpeed(float cloudSpeed) {
		if (world == null || !RealisticSleepApi.isSleeping(world)) {
			return cloudSpeed;
		}

		return cloudSpeed * config.cloudSpeedMultiplier;
	}
}
