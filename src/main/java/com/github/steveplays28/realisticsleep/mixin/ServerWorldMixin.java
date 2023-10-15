package com.github.steveplays28.realisticsleep.mixin;

import com.github.steveplays28.realisticsleep.api.RealisticSleepApi;
import com.github.steveplays28.realisticsleep.extension.ServerWorldExtension;
import com.github.steveplays28.realisticsleep.util.SleepMathUtil;
import net.minecraft.block.*;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.fluid.Fluid;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.SleepManager;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.village.raid.RaidManager;
import net.minecraft.world.*;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.level.ServerWorldProperties;
import net.minecraft.world.tick.WorldTickScheduler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static com.github.steveplays28.realisticsleep.RealisticSleep.*;
import static com.github.steveplays28.realisticsleep.util.SleepMathUtil.*;

@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin extends World implements ServerWorldExtension {
	@Unique
	public double timeStepPerTick = 2;
	@Unique
	public int timeStepPerTickRounded = 1;
	@Unique
	public long tickDelay;
	@Unique
	public MutableText sleepMessage;
	@Unique
	public Boolean shouldSkipWeather = false;
	@Unique
	public int consecutiveSleepTicks = 0;

	@Shadow
	@Final
	protected RaidManager raidManager;
	@Shadow
	@Final
	List<ServerPlayerEntity> players;
	@Shadow
	@Final
	private ServerWorldProperties worldProperties;
	@Shadow
	@Final
	private MinecraftServer server;
	@Shadow
	@Final
	private SleepManager sleepManager;
	@Shadow
	@Final
	private ServerChunkManager chunkManager;
	@Shadow
	@Final
	private boolean shouldTickTime;

	protected ServerWorldMixin(MutableWorldProperties properties, RegistryKey<World> registryRef, DynamicRegistryManager registryManager, RegistryEntry<DimensionType> dimensionEntry, Supplier<Profiler> profiler, boolean isClient, boolean debugWorld, long biomeAccess, int maxChainedNeighborUpdates) {
		super(properties, registryRef, registryManager, dimensionEntry, profiler, isClient, debugWorld, biomeAccess,
				maxChainedNeighborUpdates
		);
	}

	@Shadow
	public abstract List<ServerPlayerEntity> getPlayers();

	@Shadow
	protected abstract void wakeSleepingPlayers();

	@Shadow
	protected abstract BlockPos getLightningPos(BlockPos pos);

	@Shadow
	public abstract ServerWorld toServerWorld();

	@Shadow
	protected abstract void tickFluid(BlockPos pos, Fluid fluid);

	@Shadow
	@Final
	private WorldTickScheduler<Fluid> fluidTickScheduler;

	@Shadow
	@Final
	private static int MAX_TICKS;

	@Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/GameRules;getInt(Lnet/minecraft/world/GameRules$Key;)I"))
	public void tickInject(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
		// Calculate seconds until awake
		int sleepingPlayerCount = sleepManager.getSleeping();
		int playerCount = getPlayers().size();
		double sleepingRatio = (double) sleepingPlayerCount / playerCount;
		timeStepPerTick = SleepMathUtil.calculateTimeStepPerTick(sleepingRatio, config.sleepSpeedMultiplier, timeStepPerTick);
		int timeOfDay = RealisticSleepApi.getTimeOfDay(this);
		// TODO: Don't assume the TPS is 20
		int secondsUntilAwake = Math.abs(SleepMathUtil.calculateSecondsUntilAwake(timeOfDay, timeStepPerTick, 20));

		// Check if the night has (almost) ended and the weather should be skipped
		if (secondsUntilAwake <= 2 && shouldSkipWeather) {
			clearWeather();
			shouldSkipWeather = false;
		}

		// Check if anyone is sleeping
		if (sleepingPlayerCount <= 0) {
			// Reset consecutive sleep ticks
			consecutiveSleepTicks = 0;

			return;
		}

		// Fetch values and construct night, day, or thunderstorm text
		var isNight = SleepMathUtil.isNightTime(timeOfDay);
		var nightDayOrThunderstormText = Text.translatable(
				String.format("%s.text.%s", MOD_ID, worldProperties.isThundering() ? "thunderstorm" : isNight ? "night" : "day"));

		// Check if the required percentage of players are sleeping
		if (!RealisticSleepApi.isSleeping(this)) {
			if (!config.sendNotEnoughPlayersSleepingMessage) {
				return;
			}

			double playersRequiredToSleepRatio = server.getGameRules().getInt(GameRules.PLAYERS_SLEEPING_PERCENTAGE) / 100d;
			int playersRequiredToSleep = (int) Math.ceil(playersRequiredToSleepRatio * playerCount);

			for (ServerPlayerEntity player : players) {
				player.sendMessage(
						Text.translatable(String.format("%s.text.not_enough_players_sleeping_message", MOD_ID), sleepingPlayerCount,
								playerCount, playersRequiredToSleep, playerCount, nightDayOrThunderstormText
						), true);
			}

			return;
		}

		// Fetch config values and do calculations
		timeStepPerTickRounded = (int) Math.round(timeStepPerTick);
		int ticksUntilAwake = SleepMathUtil.calculateTicksUntilAwake(timeOfDay);
		boolean doDayLightCycle = server.getGameRules().getBoolean(GameRules.DO_DAYLIGHT_CYCLE);

		int blockEntityTickSpeedMultiplier = (int) Math.round(config.blockEntityTickSpeedMultiplier);
		int chunkTickSpeedMultiplier = (int) Math.round(config.chunkTickSpeedMultiplier);
		int raidTickSpeedMultiplier = (int) Math.round(config.raidTickSpeedMultiplier);
		int fluidScheduledTickSpeedMultiplier = (int) Math.round(config.fluidScheduledTickSpeedMultiplier);

		// Advance time
		if (doDayLightCycle) {
			worldProperties.setTimeOfDay(worldProperties.getTimeOfDay() + timeStepPerTickRounded);
		}

		// Tick block entities
		for (int i = blockEntityTickSpeedMultiplier; i > 1; i--) {
			tickBlockEntities();
		}

		// Tick chunks
		for (int i = chunkTickSpeedMultiplier; i > 1; i--) {
			chunkManager.tick(shouldKeepTicking, true);
		}

		// Tick raid timers
		for (int i = raidTickSpeedMultiplier; i > 1; i--) {
			raidManager.tick();
		}

		// Tick fluids
		for (int i = fluidScheduledTickSpeedMultiplier; i > 1; i--) {
			fluidTickScheduler.tick(worldProperties.getTimeOfDay(), MAX_TICKS, this::tickFluid);
		}

		// Send new time to all players in the overworld
		server.getPlayerManager().sendToDimension(
				new WorldTimeUpdateS2CPacket(worldProperties.getTime(), worldProperties.getTimeOfDay(), doDayLightCycle), getRegistryKey());

		// Check if players are still supposed to be sleeping, and send a HUD message if so
		if (ticksUntilAwake > WAKE_UP_GRACE_PERIOD_TICKS) {
			if (consecutiveSleepTicks >= MINIMUM_SLEEP_TICKS_TO_CLEAR_WEATHER) {
				shouldSkipWeather = true;
			}

			if (config.sendSleepingMessage) {
				sleepMessage = Text.translatable(String.format("%s.text.sleep_message", MOD_ID), sleepingPlayerCount, playerCount).append(
						nightDayOrThunderstormText);

				if (isNight) {
					if (config.showTimeUntilDawn) {
						sleepMessage.append(Text.translatable(String.format("%s.text.time_until_dawn", MOD_ID), secondsUntilAwake));
					}
				} else if (config.showTimeUntilDusk) {
					sleepMessage.append(Text.translatable(String.format("%s.text.time_until_dusk", MOD_ID), secondsUntilAwake));
				}
			}

			for (ServerPlayerEntity player : players) {
				player.sendMessage(sleepMessage, true);
			}

			consecutiveSleepTicks += timeStepPerTickRounded;
		}

		if (ticksUntilAwake <= WAKE_UP_GRACE_PERIOD_TICKS) {
			// Wake up sleeping players
			this.wakeSleepingPlayers();

			// Reset time step per tick, to reset the exponential sleep speed curve calculation
			timeStepPerTick = 2;
		}
	}

	@Inject(method = "tickTime", at = @At(value = "HEAD"), cancellable = true)
	public void tickTimeInject(CallbackInfo ci) {
		this.worldProperties.getScheduledEvents().processEvents(this.server, this.properties.getTime());

		if (!this.shouldTickTime) {
			ci.cancel();
			return;
		}

		long l = this.properties.getTime() + 1L;
		if (sleepManager.getSleeping() <= 0) {
			this.worldProperties.setTime(l);
		}

		if (tickDelay > 0L) {
			tickDelay -= 1L;
			server.getPlayerManager().sendToDimension(
					new WorldTimeUpdateS2CPacket(worldProperties.getTime(), worldProperties.getTimeOfDay(),
							this.properties.getGameRules().getBoolean(GameRules.DO_DAYLIGHT_CYCLE)
					), getRegistryKey());

			ci.cancel();
			return;
		}

		if (sleepManager.getSleeping() > 0) {
			return;
		}

		if (this.properties.getGameRules().getBoolean(GameRules.DO_DAYLIGHT_CYCLE)) {
			this.worldProperties.setTimeOfDay(this.properties.getTimeOfDay() + 1L);
		}

		tickDelay = config.tickDelay;

		ci.cancel();
	}

	/**
	 * Cancels {@link ServerWorld#sendSleepingStatus sendSleepingStatus}.
	 *
	 * @author Steveplays28
	 * @reason Method's HUD messages conflict with Realistic Sleep's custom HUD messages.
	 */
	@SuppressWarnings("JavadocReference")
	@Inject(method = "sendSleepingStatus", at = @At(value = "HEAD"), cancellable = true)
	private void sendSleepingStatusInject(CallbackInfo ci) {
		ci.cancel();
	}

	@Inject(method = "tickChunk", at = @At(value = "HEAD"))
	private void tickChunkInject(WorldChunk chunk, int randomTickSpeed, CallbackInfo ci) {
		if (!RealisticSleepApi.isSleeping(this)) {
			return;
		}

		var thunderTickSpeedMultiplier = (int) Math.round(config.thunderTickSpeedMultiplier);
		var iceAndSnowTickSpeedMultiplier = (int) Math.round(config.iceAndSnowTickSpeedMultiplier);
		var profiler = this.getProfiler();
		var chunkPos = chunk.getPos();
		var chunkStartPosX = chunkPos.getStartX();
		var chunkStartPosZ = chunkPos.getStartZ();
		BlockPos blockPos;

		// Thunder tick speed multiplier
		profiler.push(String.format("Thunder (%s)", MOD_NAME));
		for (int i = 0; i < thunderTickSpeedMultiplier; i++) {
			if (this.isRaining() && this.isThundering() && this.random.nextInt(100000) == 0) {
				blockPos = this.getLightningPos(this.getRandomPosInChunk(chunkStartPosX, 0, chunkStartPosZ, 15));

				if (this.hasRain(blockPos)) {
					LightningEntity lightningEntity = EntityType.LIGHTNING_BOLT.create(this);

					if (lightningEntity != null) {
						lightningEntity.refreshPositionAfterTeleport(Vec3d.ofBottomCenter(blockPos));
						lightningEntity.setCosmetic(true);
						this.spawnEntity(lightningEntity);
					}
				}
			}
		}

		if (randomTickSpeed <= 0) {
			profiler.pop();
			return;
		}

		// Ice and snow formation tick speed multiplier
		profiler.swap(String.format("Form ice and snow (%s)", MOD_NAME));
		for (int i = 0; i < iceAndSnowTickSpeedMultiplier; i++) {
			if (this.random.nextInt(16) == 0) {
				blockPos = this.getTopPosition(
						Heightmap.Type.MOTION_BLOCKING, this.getRandomPosInChunk(chunkStartPosX, 0, chunkStartPosZ, 15));
				BlockPos blockPosDown = blockPos.down();
				Biome biome = this.getBiome(blockPos).value();

				if (biome.canSetIce(this, blockPosDown)) {
					this.setBlockState(blockPosDown, Blocks.ICE.getDefaultState());
				}

				if (this.isRaining() && biome.canSetSnow(this, blockPos)) {
					this.setBlockState(blockPos, Blocks.SNOW.getDefaultState());
				}
			}
		}

		profiler.swap(String.format("Tick blocks (%s)", MOD_NAME));
		for (int l = 0; l < chunk.getSectionArray().length; l++) {
			var chunkSection = chunk.getSectionArray()[l];

			if (!chunkSection.hasRandomTicks()) {
				continue;
			}

			var cropGrowthTickSpeedMultiplier = (int) Math.round(config.cropGrowthTickSpeedMultiplier);
			var precipitationTickSpeedMultiplier = (int) Math.round(config.precipitationTickSpeedMultiplier);
			var blockRandomTickSpeedMultiplier = (int) Math.round(config.blockRandomTickSpeedMultiplier);
			var fluidRandomTickSpeedMultiplier = (int) Math.round(config.fluidRandomTickSpeedMultiplier);

			// Crop growth speed multiplier
			for (int i = 0; i < cropGrowthTickSpeedMultiplier; i++) {
				int chunkSectionYOffset = ChunkSectionPos.getBlockCoord(chunk.sectionIndexToCoord(l));
				var randomPosInChunk = this.getRandomPosInChunk(chunkStartPosX, chunkSectionYOffset, chunkStartPosZ, 15);
				var randomBlockStateInChunk = chunkSection.getBlockState(randomPosInChunk.getX() - chunkStartPosX,
						randomPosInChunk.getY() - chunkSectionYOffset, randomPosInChunk.getZ() - chunkStartPosZ
				);
				var randomBlockInChunk = randomBlockStateInChunk.getBlock();

				if (getLightLevel(randomPosInChunk, 0) >= 9) {
					if (randomBlockInChunk instanceof CropBlock cropBlock) {
						cropBlock.grow(this.toServerWorld(), random, randomPosInChunk, randomBlockStateInChunk);
					} else if (randomBlockInChunk instanceof StemBlock stemBlock) {
						stemBlock.grow(this.toServerWorld(), random, randomPosInChunk, randomBlockStateInChunk);
					}
				}
			}

			// Precipitation tick speed multiplier
			for (int i = 0; i < precipitationTickSpeedMultiplier; i++) {
				int chunkSectionYOffset = ChunkSectionPos.getBlockCoord(chunk.sectionIndexToCoord(l));
				var randomPosInChunk = this.getRandomPosInChunk(chunkStartPosX, chunkSectionYOffset, chunkStartPosZ, 15);
				var randomBlockStateInChunk = chunkSection.getBlockState(randomPosInChunk.getX() - chunkStartPosX,
						randomPosInChunk.getY() - chunkSectionYOffset, randomPosInChunk.getZ() - chunkStartPosZ
				);
				var randomBlockInChunk = randomBlockStateInChunk.getBlock();
				var biome = this.getBiome(randomPosInChunk).value();
				var precipitation = biome.getPrecipitation(randomPosInChunk);

				if (precipitation == Biome.Precipitation.RAIN && biome.isCold(randomPosInChunk)) {
					precipitation = Biome.Precipitation.SNOW;
				}

				if (randomBlockInChunk instanceof CauldronBlock cauldronBlock) {
					cauldronBlock.precipitationTick(randomBlockStateInChunk, this, randomPosInChunk, precipitation);
				} else if (randomBlockInChunk instanceof LeveledCauldronBlock leveledCauldronBlock) {
					leveledCauldronBlock.precipitationTick(randomBlockStateInChunk, this, randomPosInChunk, precipitation);
				}
			}

			// Random tick speed multiplier
			for (int j = 0; j < randomTickSpeed; j++) {
				int chunkSectionYOffset = ChunkSectionPos.getBlockCoord(chunk.sectionIndexToCoord(l));
				var randomPosInChunk = this.getRandomPosInChunk(chunkStartPosX, chunkSectionYOffset, chunkStartPosZ, 15);
				var randomBlockStateInChunk = chunkSection.getBlockState(randomPosInChunk.getX() - chunkStartPosX,
						randomPosInChunk.getY() - chunkSectionYOffset, randomPosInChunk.getZ() - chunkStartPosZ
				);
				var fluidState = randomBlockStateInChunk.getFluidState();

				for (int k = 0; k < blockRandomTickSpeedMultiplier; k++) {
					if (randomBlockStateInChunk.hasRandomTicks()) {
						randomBlockStateInChunk.randomTick(this.toServerWorld(), randomPosInChunk, this.random);
					}
				}

				for (int k = 0; k < fluidRandomTickSpeedMultiplier; k++) {
					if (fluidState.hasRandomTicks()) {
						fluidState.onRandomTick(this, randomPosInChunk, this.random);
					}
				}
			}

			profiler.pop();
		}
	}

	@Unique
	private void clearWeather() {
		boolean doWeatherCycle = server.getGameRules().getBoolean(GameRules.DO_WEATHER_CYCLE);

		if (doWeatherCycle && (worldProperties.isRaining() || worldProperties.isThundering())) {
			// Reset weather clock and clear weather
			var nextRainTime = (int) (DAY_LENGTH * SleepMathUtil.getRandomNumberInRange(0.5, 7.5));
			worldProperties.setRainTime(nextRainTime);
			worldProperties.setThunderTime(nextRainTime + (Math.random() > 0 ? 1 : -1));

			worldProperties.setThundering(false);
			worldProperties.setRaining(false);
		}
	}
}
