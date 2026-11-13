package com.deepseek.tideautofish;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.player.Player;

/**
 * All Tide / Stardew Fishing access is done through reflection.
 *
 * <p>This keeps Tide Autofish buildable without remapping Tide's release jar and,
 * more importantly, keeps it from crashing when Tide (or Stardew Fishing) changes
 * a private field between versions: a failed lookup simply disables the optional
 * integration.</p>
 */
public final class TideBridge {
    private static final String HOOK_ACCESSOR = "com.li64.tide.registries.entities.misc.fishing.HookAccessor";
    private static final String TIDE_HOOK = "com.li64.tide.registries.entities.misc.fishing.TideFishingHook";
    private static final String TIDE_OVERLAY = "com.li64.tide.client.gui.overlays.CatchMinigameOverlay";
    private static final String STARDEW_SCREEN = "com.bonker.stardewfishing.client.FishingScreen";
    private static final String STARDEW_MINIGAME = "com.bonker.stardewfishing.client.FishingMinigame";

    private static boolean initialized;
    private static boolean tideAvailable;
    private static boolean stardewAvailable;

    private static Method getHookMethod;
    private static Method isFishHookedMethod;

    private static Field overlayActiveField;
    private static Field overlayAreaField;
    private static Field overlayDelayField;
    private static Field overlayTimeLeftField;
    private static Method overlayPositionMethod;
    private static Method overlayInteractMethod;

    private static Class<?> stardewScreenClass;
    private static Field stardewMinigameField;
    private static Field stardewStatusField;
    private static Method stardewSetInputDownMethod;
    private static Field mgBobberPosField;
    private static Field mgBobberVelocityField;
    private static Field mgFishPosField;
    private static Field mgFishVelocityField;
    private static Field mgBarSizeField;
    private static Field mgMaxBobberHeightField;

    private static boolean warnedOverlay;
    private static boolean warnedStardew;

    private TideBridge() {
    }

    public static synchronized boolean ensureAvailable() {
        if (initialized) {
            return tideAvailable;
        }
        initialized = true;

        try {
            Class<?> hookAccessor = load(HOOK_ACCESSOR);
            getHookMethod = hookAccessor.getMethod("getHook", Player.class);

            Class<?> tideHook = load(TIDE_HOOK);
            isFishHookedMethod = tideHook.getMethod("isFishHooked");

            Class<?> overlay = load(TIDE_OVERLAY);
            overlayActiveField = accessible(overlay.getDeclaredField("isActive"));
            overlayAreaField = accessible(overlay.getDeclaredField("area"));
            overlayDelayField = accessible(overlay.getDeclaredField("delayTime"));
            overlayTimeLeftField = optionalField(overlay, "timeLeft");
            overlayPositionMethod = accessible(overlay.getDeclaredMethod("getMinigamePosition"));
            overlayInteractMethod = accessible(overlay.getDeclaredMethod("interact"));

            tideAvailable = true;
        } catch (Throwable throwable) {
            tideAvailable = false;
            TideAutoFish.LOGGER.warn("Tide integration is unavailable: {}", throwable.toString());
        }

        try {
            stardewScreenClass = load(STARDEW_SCREEN);
            stardewMinigameField = accessible(stardewScreenClass.getDeclaredField("minigame"));
            stardewStatusField = optionalField(stardewScreenClass, "status");
            stardewSetInputDownMethod = stardewScreenClass.getMethod("setInputDown", boolean.class);

            Class<?> minigame = load(STARDEW_MINIGAME);
            mgBobberPosField = accessible(minigame.getDeclaredField("bobberPos"));
            mgBobberVelocityField = accessible(minigame.getDeclaredField("bobberVelocity"));
            mgFishPosField = accessible(minigame.getDeclaredField("fishPos"));
            mgFishVelocityField = accessible(minigame.getDeclaredField("fishVelocity"));
            mgBarSizeField = accessible(minigame.getDeclaredField("barSize"));
            mgMaxBobberHeightField = accessible(minigame.getDeclaredField("maxBobberHeight"));

            stardewAvailable = true;
        } catch (Throwable ignored) {
            // Stardew Fishing is optional; Tide's own minigame is used without it.
            stardewAvailable = false;
        }

        if (Config.debug()) {
            TideAutoFish.LOGGER.info("Tide integration: tide={}, stardewMinigame={}", tideAvailable, stardewAvailable);
        }
        return tideAvailable;
    }

    public static boolean tideAvailable() {
        return tideAvailable;
    }

    public static boolean stardewAvailable() {
        return stardewAvailable;
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, TideBridge.class.getClassLoader());
    }

    private static Field accessible(Field field) {
        field.setAccessible(true);
        return field;
    }

    private static Method accessible(Method method) {
        method.setAccessible(true);
        return method;
    }

    private static Field optionalField(Class<?> owner, String name) {
        try {
            return accessible(owner.getDeclaredField(name));
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    /** Returns Tide's {@code TideFishingHook} for the player, or null. */
    public static Object getHook(Player player) {
        if (!ensureAvailable() || getHookMethod == null) {
            return null;
        }
        try {
            return getHookMethod.invoke(null, player);
        } catch (Throwable throwable) {
            if (Config.debug()) {
                TideAutoFish.LOGGER.debug("Could not read Tide fishing hook", throwable);
            }
            return null;
        }
    }

    /** Whether a fish is currently biting that hook (synced field on the client). */
    public static boolean isFishHooked(Object hook) {
        if (hook == null || isFishHookedMethod == null) {
            return false;
        }
        try {
            return (boolean) isFishHookedMethod.invoke(hook);
        } catch (Throwable throwable) {
            return false;
        }
    }

    public static boolean isTideMinigameActive() {
        if (!ensureAvailable() || overlayActiveField == null) {
            return false;
        }
        try {
            return overlayActiveField.getBoolean(null);
        } catch (Throwable throwable) {
            return false;
        }
    }

    /**
     * Clicks Tide's minigame when the marker is inside the catch window.
     *
     * <p>Perfect clicks (|position| &lt; 0.1) are preferred. If the marker has not
     * been in the perfect zone by 65% of the minigame's lifetime, any position
     * within the (wider) catch window is accepted so the fish is not lost.</p>
     */
    public static void autoClickTideMinigame() {
        if (!ensureAvailable() || overlayActiveField == null || overlayPositionMethod == null || overlayInteractMethod == null) {
            return;
        }
        try {
            if (!overlayActiveField.getBoolean(null)) {
                return;
            }
            if (overlayDelayField != null && System.currentTimeMillis() < overlayDelayField.getLong(null)) {
                return;
            }

            float position = Math.abs((float) overlayPositionMethod.invoke(null));
            float area = overlayAreaField.getFloat(null);
            float threshold = area * 0.9F;

            if (area > 0.12F) {
                float timeLeft = overlayTimeLeftField == null ? 80.0F : overlayTimeLeftField.getFloat(null);
                boolean late = timeLeft >= 65.0F;
                if (Config.perfectClicks() && !late) {
                    threshold = 0.1F;
                } else {
                    threshold = Math.max(0.1F, threshold);
                }
            }

            if (position < threshold) {
                overlayInteractMethod.invoke(null);
            }
        } catch (Throwable throwable) {
            if (!warnedOverlay) {
                warnedOverlay = true;
                TideAutoFish.LOGGER.warn("Could not automate Tide's minigame", throwable);
            }
        }
    }

    public static boolean isStardewScreen(Screen screen) {
        ensureAvailable();
        return stardewAvailable && screen != null && stardewScreenClass != null && stardewScreenClass.isInstance(screen);
    }

    /** Drives the Stardew minigame for one tick. Returns false if it could not. */
    public static boolean controlStardew(Screen screen, StardewMinigameController controller) {
        if (!isStardewScreen(screen)) {
            return false;
        }
        try {
            // Only drive the minigame while the screen is actually in its
            // MINIGAME phase; the HIT_TEXT / SUCCESS / FAILURE animations must
            // not receive synthetic input.
            if (stardewStatusField != null) {
                Object status = stardewStatusField.get(screen);
                if (status == null || !"MINIGAME".equals(status.toString())) {
                    stardewSetInputDownMethod.invoke(screen, false);
                    return true;
                }
            }

            Object minigame = stardewMinigameField.get(screen);
            if (minigame == null) {
                return false;
            }

            double bobberPos = mgBobberPosField.getDouble(minigame);
            double bobberVelocity = mgBobberVelocityField.getDouble(minigame);
            double fishPos = mgFishPosField.getDouble(minigame);
            double fishVelocity = mgFishVelocityField.getDouble(minigame);
            int barSize = mgBarSizeField.getInt(minigame);
            int maxBobberHeight = mgMaxBobberHeightField.getInt(minigame);

            boolean down = controller.decide(bobberPos, bobberVelocity, fishPos, fishVelocity, barSize, maxBobberHeight);
            stardewSetInputDownMethod.invoke(screen, down);
            return true;
        } catch (Throwable throwable) {
            if (!warnedStardew) {
                warnedStardew = true;
                TideAutoFish.LOGGER.warn("Could not automate the Stardew Fishing minigame", throwable);
            }
            return false;
        }
    }

    public static void releaseStardewInput(Screen screen) {
        if (!isStardewScreen(screen) || stardewSetInputDownMethod == null) {
            return;
        }
        try {
            stardewSetInputDownMethod.invoke(screen, false);
        } catch (Throwable ignored) {
        }
    }
}
