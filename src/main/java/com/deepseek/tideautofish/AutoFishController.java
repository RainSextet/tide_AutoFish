package com.deepseek.tideautofish;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;

/**
 * The client-side fishing state machine.
 *
 * <p>Tide replaces vanilla's bobber with its own {@code TideFishingHook} and
 * changes the catch flow: right-clicking a biting hook either retrieves a
 * non-fish catch immediately or starts a minigame (Tide's own overlay, or
 * Stardew Fishing's screen when that mod is installed). This controller drives
 * exactly those actions through the normal client game mode, so the server sees
 * ordinary use-item packets.</p>
 */
public final class AutoFishController {
    private enum State {
        IDLE("idle"),
        CAST_CHARGING("casting"),
        CAST_RELEASING("casting"),
        WAIT_HOOK("casting"),
        WAITING("waiting"),
        REELING("reeling"),
        TIDE_MINIGAME("minigame"),
        STARDEW_MINIGAME("minigame"),
        RECAST_DELAY("cooldown"),
        UNSUPPORTED("idle");

        private final String hudKey;

        State(String hudKey) {
            this.hudKey = hudKey;
        }
    }

    private static final StardewMinigameController STARDEW_CONTROLLER = new StardewMinigameController();

    private static boolean initialised;
    private static boolean enabled;
    private static State state = State.IDLE;
    private static int ticks;
    private static Screen lastStardewScreen;
    private static boolean warnedVanillaHook;
    private static boolean warnedLowDurability;
    private static boolean warnedMissingTide;

    private AutoFishController() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void toggle(Minecraft minecraft) {
        if (!initialised) {
            initialised = true;
            enabled = Config.startEnabled();
        }

        if (!TideBridge.ensureAvailable()) {
            message("message.tideautofish.tide_missing");
            enabled = false;
            reset();
            return;
        }

        enabled = !enabled;
        if (enabled) {
            state = State.IDLE;
            ticks = 0;
            warnedVanillaHook = false;
            warnedLowDurability = false;
            message("message.tideautofish.enabled");
        } else {
            stop(minecraft);
            message("message.tideautofish.disabled");
        }
    }

    public static void tick(Minecraft minecraft) {
        if (!initialised) {
            initialised = true;
            enabled = Config.startEnabled();
        }

        if (minecraft.player == null || minecraft.level == null) {
            reset();
            return;
        }
        if (!enabled || minecraft.gameMode == null) {
            return;
        }

        if (!TideBridge.ensureAvailable()) {
            if (!warnedMissingTide) {
                warnedMissingTide = true;
                message("message.tideautofish.tide_missing");
            }
            enabled = false;
            reset();
            return;
        }

        LocalPlayer player = minecraft.player;

        // Stardew Fishing's minigame is a Screen. Drive it before the generic
        // "a GUI is open, pause" logic below.
        Screen screen = minecraft.screen;
        if (TideBridge.isStardewScreen(screen)) {
            handleStardewScreen(screen);
            return;
        }

        if (lastStardewScreen != null) {
            TideBridge.releaseStardewInput(lastStardewScreen);
            lastStardewScreen = null;
            STARDEW_CONTROLLER.reset();
            if (state == State.STARDEW_MINIGAME) {
                state = State.RECAST_DELAY;
                ticks = 0;
            }
        }

        if (screen != null) {
            // Inventory, chat, pause menu, ...: freeze the state machine. If we
            // were charging a cast, release the item so the player does not get
            // stuck holding right-click.
            if (state == State.CAST_CHARGING && player.isUsingItem() && minecraft.gameMode != null) {
                minecraft.gameMode.releaseUsingItem(player);
            }
            if (state == State.CAST_CHARGING || state == State.CAST_RELEASING) {
                state = State.IDLE;
                ticks = 0;
            }
            return;
        }

        if (player.isDeadOrDying() || player.isSpectator() || player.isSleeping()) {
            state = State.IDLE;
            ticks = 0;
            return;
        }

        Object hook = TideBridge.getHook(player);
        boolean tideMinigameActive = TideBridge.isTideMinigameActive();

        // A vanilla FishingHook means Tide's "overrideVanillaRod" option is off or
        // the player is using a rod that does not create Tide hooks. Do not fight
        // the vanilla hook; just wait until it is gone.
        if (hook == null && player.fishing != null && !tideMinigameActive) {
            if (!warnedVanillaHook) {
                warnedVanillaHook = true;
                message("message.tideautofish.vanilla_hook");
            }
            state = State.UNSUPPORTED;
            ticks = 0;
            return;
        }

        if (tideMinigameActive) {
            if (state != State.TIDE_MINIGAME) {
                state = State.TIDE_MINIGAME;
                ticks = 0;
            }
            return;
        }

        switch (state) {
            case UNSUPPORTED -> {
                if (hook != null) {
                    state = State.WAITING;
                    ticks = 0;
                } else if (player.fishing == null) {
                    state = State.IDLE;
                    ticks = 0;
                    warnedVanillaHook = false;
                }
            }
            case IDLE -> {
                if (hook != null) {
                    state = State.WAITING;
                    ticks = 0;
                } else if (canStartCast(player)) {
                    startCast(minecraft, player);
                }
            }
            case CAST_CHARGING -> {
                ticks++;
                if (player.isUsingItem()) {
                    if (ticks >= Config.castHoldTicks()) {
                        minecraft.gameMode.releaseUsingItem(player);
                        state = State.CAST_RELEASING;
                        ticks = 0;
                    }
                } else {
                    // holdToCast is disabled: useItem cast the bobber immediately.
                    state = State.WAIT_HOOK;
                    ticks = 0;
                }
                if (ticks > 120) {
                    state = State.RECAST_DELAY;
                    ticks = 0;
                }
            }
            case CAST_RELEASING -> {
                ticks++;
                if (ticks >= 2) {
                    state = State.WAIT_HOOK;
                    ticks = 0;
                }
            }
            case WAIT_HOOK -> {
                ticks++;
                if (hook != null) {
                    state = State.WAITING;
                    ticks = 0;
                } else if (ticks > 60) {
                    state = State.RECAST_DELAY;
                    ticks = 0;
                }
            }
            case WAITING -> {
                ticks++;
                if (hook == null) {
                    state = State.RECAST_DELAY;
                    ticks = 0;
                } else if (TideBridge.isFishHooked(hook)) {
                    state = reel(minecraft, player) ? State.REELING : State.RECAST_DELAY;
                    ticks = 0;
                } else if (ticks > Config.maxWaitTicks()) {
                    // No bite for a long time (bad cast, land, ...). Reel in and retry.
                    state = reel(minecraft, player) ? State.REELING : State.RECAST_DELAY;
                    ticks = 0;
                }
            }
            case REELING -> {
                ticks++;
                if (hook == null) {
                    state = State.RECAST_DELAY;
                    ticks = 0;
                } else if (ticks > 40) {
                    // The use-item packet probably did not start anything (lag or
                    // the hook was invalidated). Do not keep clicking.
                    state = State.RECAST_DELAY;
                    ticks = 0;
                }
            }
            case TIDE_MINIGAME -> {
                ticks++;
                if (!TideBridge.isTideMinigameActive()) {
                    if (hook == null) {
                        state = State.RECAST_DELAY;
                    } else {
                        // Timed out/failed but the hook restarted its sequence.
                        state = State.WAITING;
                    }
                    ticks = 0;
                } else if (ticks > 400) {
                    state = State.WAITING;
                    ticks = 0;
                }
            }
            case STARDEW_MINIGAME -> {
                // The screen was closed between ticks (normally handled above).
                state = hook == null ? State.RECAST_DELAY : State.WAITING;
                ticks = 0;
            }
            case RECAST_DELAY -> {
                ticks++;
                if (ticks >= Config.recastDelayTicks()) {
                    state = State.IDLE;
                    ticks = 0;
                }
            }
        }
    }

    private static void handleStardewScreen(Screen screen) {
        if (state != State.STARDEW_MINIGAME || lastStardewScreen != screen) {
            state = State.STARDEW_MINIGAME;
            ticks = 0;
            lastStardewScreen = screen;
            STARDEW_CONTROLLER.reset();
        }

        if (Config.stardewAutoPlay()) {
            TideBridge.controlStardew(screen, STARDEW_CONTROLLER);
        } else {
            TideBridge.releaseStardewInput(screen);
        }
    }

    private static boolean canStartCast(LocalPlayer player) {
        if (player.isUsingItem()) {
            return false;
        }
        return rodHand(player) != null;
    }

    private static void startCast(Minecraft minecraft, LocalPlayer player) {
        InteractionHand hand = rodHand(player);
        if (hand == null) {
            return;
        }

        if (Config.rodProtection()) {
            ItemStack rod = player.getItemInHand(hand);
            if (rod.isDamageableItem() && rod.getMaxDamage() - rod.getDamageValue() <= Config.minDurability()) {
                if (!warnedLowDurability) {
                    warnedLowDurability = true;
                    message("message.tideautofish.low_durability");
                }
                enabled = false;
                state = State.IDLE;
                ticks = 0;
                return;
            }
        }

        minecraft.gameMode.useItem(player, hand);
        state = State.CAST_CHARGING;
        ticks = 0;
    }

    private static boolean reel(Minecraft minecraft, LocalPlayer player) {
        InteractionHand hand = rodHand(player);
        if (hand == null) {
            // Switched away from the rod: the server removes the hook on its own.
            return false;
        }
        minecraft.gameMode.useItem(player, hand);
        return true;
    }

    private static InteractionHand rodHand(LocalPlayer player) {
        if (player.getMainHandItem().getItem() instanceof FishingRodItem) {
            return InteractionHand.MAIN_HAND;
        }
        if (player.getOffhandItem().getItem() instanceof FishingRodItem) {
            return InteractionHand.OFF_HAND;
        }
        return null;
    }

    private static void stop(Minecraft minecraft) {
        if (minecraft.player != null && minecraft.gameMode != null
                && (state == State.CAST_CHARGING || state == State.CAST_RELEASING)
                && minecraft.player.isUsingItem()) {
            minecraft.gameMode.releaseUsingItem(minecraft.player);
        }
        if (lastStardewScreen != null) {
            TideBridge.releaseStardewInput(lastStardewScreen);
            lastStardewScreen = null;
        }
        STARDEW_CONTROLLER.reset();
        state = State.IDLE;
        ticks = 0;
    }

    private static void reset() {
        if (lastStardewScreen != null) {
            TideBridge.releaseStardewInput(lastStardewScreen);
            lastStardewScreen = null;
        }
        STARDEW_CONTROLLER.reset();
        state = State.IDLE;
        ticks = 0;
    }

    public static void render(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        // Minigame automation must keep working with the HUD hidden (F1).
        if (enabled && minecraft.screen == null && Config.autoMinigame()) {
            TideBridge.autoClickTideMinigame();
        }

        if (minecraft.options.hideGui || !Config.showHud() || (!enabled && !Config.hudWhenDisabled())) {
            return;
        }

        String text = I18n.get(enabled ? "hud.tideautofish.on" : "hud.tideautofish.off");
        if (enabled && Config.showState()) {
            text += " (" + I18n.get("hud.tideautofish.state." + state.hudKey) + ")";
        }
        int color = enabled ? 0xFF55FF55 : 0xFFAAAAAA;
        graphics.drawString(minecraft.font, text, 6, 6, color, true);
    }

    private static void message(String key) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(key), true);
        }
    }
}

