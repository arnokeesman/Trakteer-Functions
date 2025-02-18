package dev.keesmand.trakteeractions.mixin;

import com.mojang.authlib.GameProfile;
import dev.keesmand.trakteeractions.TrakteerActionsMod;
import dev.keesmand.trakteeractions.config.UserSettings;
import dev.keesmand.trakteeractions.model.Donation;
import dev.keesmand.trakteeractions.util.Game;
import dev.keesmand.trakteeractions.util.Web;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameRules;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.net.ConnectException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import static dev.keesmand.trakteeractions.TrakteerActionsMod.COMMAND_QUEUE;
import static dev.keesmand.trakteeractions.TrakteerActionsMod.OPERATION_CONFIG;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Unique
    private final Thread.UncaughtExceptionHandler exceptionHandler = (thread, throwable) ->
            TrakteerActionsMod.LOGGER.error("Exception while reading from API", throwable);
    @Unique
    private final Executor threadExecutor = Executors.newCachedThreadPool(runnable -> {
        var thread = new Thread(runnable, "Trakteer API Thread");

        thread.setUncaughtExceptionHandler(exceptionHandler);

        return thread;
    });

    @Unique
    private static @Nullable UserSettings getUserToCheck(MinecraftServer server) {
        int timeBetween = OPERATION_CONFIG.getInterval() * 20;
        List<UserSettings> readyUserSettings = OPERATION_CONFIG.getReadyUserSettings();
        int spread = readyUserSettings.size();
        if (spread > timeBetween / 4) {
            int newInterval = spread / 2;
            OPERATION_CONFIG.setInterval(newInterval);
            server.sendMessage(Text.literal(
                    String.format("[%s]Too many players tracked, increased interval to %d",
                            TrakteerActionsMod.MOD_METADATA.getName(),
                            newInterval)
            ).formatted(Formatting.RED));
            return null;
        }

        int part = server.getTicks() % timeBetween;
        UserSettings userSettings = null;
        for (int i = 0; i < spread; i++) {
            if (part == 0) {
                userSettings = readyUserSettings.getFirst();
                break;
            }

            if (part == timeBetween * i / spread) {
                userSettings = readyUserSettings.get(i);
                break;
            }
        }
        return userSettings;
    }

    @Shadow
    public abstract PlayerManager getPlayerManager();

    @SuppressWarnings("UnreachableCode")
    @Inject(method = "tick", at = @At("TAIL"))
    void onTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        if (TrakteerActionsMod.isObstructed()) return;
        MinecraftServer server = (MinecraftServer) (Object) this;

        executeCommands(server);

        UserSettings userSettings = getUserToCheck(server);
        if (userSettings == null) return;

        assert server.getUserCache() != null;
        Optional<GameProfile> optionalGameProfile = server.getUserCache().getByUuid(userSettings.uuid);

        if (optionalGameProfile.isPresent()) {
            GameProfile gameProfile = optionalGameProfile.get();
            threadExecutor.execute(() -> {
                Donation[] donations = null;
                try {
                    donations = Web.getLatestDonations(userSettings, gameProfile);
                } catch (IOException ioe) {
                    if (ioe instanceof ConnectException) {
                        TrakteerActionsMod.LOGGER.warn("Unable to connect to API: {}", ioe.getMessage());
                        return;
                    }

                    ServerPlayerEntity player = getPlayerManager().getPlayer(gameProfile.getId());
                    if (player != null) {
                        player.sendMessage(Text.literal(TrakteerActionsMod.logPrefix + "API key no longer valid, removing...").formatted(Formatting.RED));
                    }

                    try {
                        OPERATION_CONFIG.setApiKey(gameProfile.getId(), "");
                    } catch (Exception ignored) {
                    }
                }
                if (donations == null) return;

                Arrays.stream(donations)
                        .filter(donation -> TrakteerActionsMod.knownTimestamps.get(userSettings.uuid).add(donation.updated_at))
                        .forEach(donation -> Game.handleDonation(server, donation));
            });
        }
    }

    @Unique
    private static void executeCommands(MinecraftServer server) {
        if (!COMMAND_QUEUE.isEmpty()) {
            GameRules.BooleanRule gameRule = server.getGameRules().get(GameRules.SEND_COMMAND_FEEDBACK);
            boolean sendCommandFeedback = gameRule.get();

            if (sendCommandFeedback) gameRule.set(false, server);
            for (String command : COMMAND_QUEUE) {
                server.getCommandManager().executeWithPrefix(server.getCommandSource(), command);
            }
            if (sendCommandFeedback) gameRule.set(true, server);

            COMMAND_QUEUE.clear();
        }
    }
}
