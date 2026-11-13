package com.deepseek.tideautofish;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Client config. All values are read lazily because the spec is only baked
 * after Forge has loaded the config file.
 */
public final class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.BooleanValue START_ENABLED;
    private static final ForgeConfigSpec.BooleanValue SHOW_HUD;
    private static final ForgeConfigSpec.BooleanValue HUD_WHEN_DISABLED;
    private static final ForgeConfigSpec.BooleanValue SHOW_STATE;
    private static final ForgeConfigSpec.BooleanValue ROD_PROTECTION;
    private static final ForgeConfigSpec.IntValue MIN_DURABILITY;
    private static final ForgeConfigSpec.IntValue CAST_HOLD_TICKS;
    private static final ForgeConfigSpec.IntValue RECAST_DELAY_TICKS;
    private static final ForgeConfigSpec.IntValue MAX_WAIT_TICKS;
    private static final ForgeConfigSpec.BooleanValue AUTO_MINIGAME;
    private static final ForgeConfigSpec.BooleanValue PERFECT_CLICKS;
    private static final ForgeConfigSpec.BooleanValue STARDEW_AUTO_PLAY;
    private static final ForgeConfigSpec.BooleanValue DEBUG;

    public static final ForgeConfigSpec SPEC;

    static {
        BUILDER.comment("Tide Autofish client settings").push("general");
        START_ENABLED = BUILDER
                .comment("Start with automatic fishing enabled.")
                .define("startEnabled", false);
        SHOW_HUD = BUILDER
                .comment("Show a small status text in the top-left corner.")
                .define("showHud", true);
        HUD_WHEN_DISABLED = BUILDER
                .comment("Also show the status text while automatic fishing is disabled.")
                .define("showHudWhenDisabled", false);
        SHOW_STATE = BUILDER
                .comment("Include the current state (waiting, bite, minigame, ...) in the HUD text.")
                .define("showStateInHud", true);
        DEBUG = BUILDER
                .comment("Log extra diagnostic messages.")
                .define("debug", false);
        BUILDER.pop();

        BUILDER.comment("Fishing rod handling").push("fishing");
        ROD_PROTECTION = BUILDER
                .comment("Stop automatically before the fishing rod breaks.")
                .define("rodProtection", true);
        MIN_DURABILITY = BUILDER
                .comment("Stop when the held rod has this much durability or less.")
                .defineInRange("minDurability", 10, 0, 1000);
        CAST_HOLD_TICKS = BUILDER
                .comment("How many ticks to hold right-click while casting when Tide's holdToCast is enabled.",
                        "Tide's fully charged cast needs 25 ticks; 30 adds a small safety margin.")
                .defineInRange("castHoldTicks", 30, 5, 100);
        RECAST_DELAY_TICKS = BUILDER
                .comment("Ticks to wait after a catch/failed retrieve before casting again.")
                .defineInRange("recastDelayTicks", 10, 1, 100);
        MAX_WAIT_TICKS = BUILDER
                .comment("Reel in and recast if no bite happens for this many ticks (1200 = 60 seconds).")
                .defineInRange("maxWaitTicks", 1200, 200, 7200);
        BUILDER.pop();

        BUILDER.comment("Minigame automation").push("minigame");
        AUTO_MINIGAME = BUILDER
                .comment("Automatically play Tide's built-in catch minigame.")
                .define("autoMinigame", true);
        PERFECT_CLICKS = BUILDER
                .comment("Try to click in the perfect zone of Tide's minigame, falling back to a normal catch near the timeout.")
                .define("perfectClicks", true);
        STARDEW_AUTO_PLAY = BUILDER
                .comment("Automatically play the Stardew Fishing minigame when Tide delegates to it.")
                .define("stardewAutoPlay", true);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private Config() {
    }

    private static boolean loaded() {
        return SPEC.isLoaded();
    }

    public static boolean startEnabled() {
        return loaded() && START_ENABLED.get();
    }

    public static boolean showHud() {
        return loaded() && SHOW_HUD.get();
    }

    public static boolean hudWhenDisabled() {
        return loaded() && HUD_WHEN_DISABLED.get();
    }

    public static boolean showState() {
        return loaded() && SHOW_STATE.get();
    }

    public static boolean rodProtection() {
        return !loaded() || ROD_PROTECTION.get();
    }

    public static int minDurability() {
        return loaded() ? MIN_DURABILITY.get() : 10;
    }

    public static int castHoldTicks() {
        return loaded() ? CAST_HOLD_TICKS.get() : 30;
    }

    public static int recastDelayTicks() {
        return loaded() ? RECAST_DELAY_TICKS.get() : 10;
    }

    public static int maxWaitTicks() {
        return loaded() ? MAX_WAIT_TICKS.get() : 1200;
    }

    public static boolean autoMinigame() {
        return !loaded() || AUTO_MINIGAME.get();
    }

    public static boolean perfectClicks() {
        return !loaded() || PERFECT_CLICKS.get();
    }

    public static boolean stardewAutoPlay() {
        return !loaded() || STARDEW_AUTO_PLAY.get();
    }

    public static boolean debug() {
        return loaded() && DEBUG.get();
    }
}
