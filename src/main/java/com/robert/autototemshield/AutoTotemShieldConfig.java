package com.robert.autototemshield;

public class AutoTotemShieldConfig {

    /*
     * ============================================================
     * DEFAULT SETTINGS
     * ============================================================
     */

    public static final boolean DEFAULT_ENABLED = true;

    /*
     * Switch to Totem at or below this many hearts.
     */
    public static final double DEFAULT_TRIGGER_HEARTS = 3.0;

    /*
     * Do NOT restore the shield until health reaches this
     * many hearts.
     */
    public static final double DEFAULT_RETURN_HEARTS = 6.0;

    /*
     * If true, replace a consumed Totem with another Totem
     * whenever one is available.
     */
    public static final boolean DEFAULT_RESTOCK_TOTEM = true;

    /*
     * If true, restore the original shield after healing.
     */
    public static final boolean DEFAULT_RETURN_TO_SHIELD = true;

    /*
     * If true, the emergency system only activates when a
     * shield is in the offhand.
     */
    public static final boolean DEFAULT_SHIELD_REQUIRED = true;

    /*
     * Kept for compatibility with the existing config/UI.
     *
     * IMPORTANT:
     * This no longer prevents emergency Totem use.
     *
     * If you have 1 Totem, it can be used.
     */
    public static final int DEFAULT_MINIMUM_TOTEMS = 0;

    /*
     * Kept for compatibility with the existing config/UI.
     */
    public static final int DEFAULT_SWAP_DELAY = 0;

    public static final boolean DEFAULT_DEBUG_MESSAGES = false;


    /*
     * ============================================================
     * CURRENT SETTINGS
     * ============================================================
     */

    public static boolean enabled =
            DEFAULT_ENABLED;

    public static double triggerHearts =
            DEFAULT_TRIGGER_HEARTS;

    public static double returnHearts =
            DEFAULT_RETURN_HEARTS;

    public static boolean restockTotem =
            DEFAULT_RESTOCK_TOTEM;

    public static boolean returnToShield =
            DEFAULT_RETURN_TO_SHIELD;

    public static boolean shieldRequired =
            DEFAULT_SHIELD_REQUIRED;

    public static int minimumTotems =
            DEFAULT_MINIMUM_TOTEMS;

    public static int swapDelay =
            DEFAULT_SWAP_DELAY;

    public static boolean debugMessages =
            DEFAULT_DEBUG_MESSAGES;


    /*
     * ============================================================
     * RESET TO DEFAULTS
     * ============================================================
     */
    public static void resetToDefaults() {

        enabled =
                DEFAULT_ENABLED;

        triggerHearts =
                DEFAULT_TRIGGER_HEARTS;

        returnHearts =
                DEFAULT_RETURN_HEARTS;

        restockTotem =
                DEFAULT_RESTOCK_TOTEM;

        returnToShield =
                DEFAULT_RETURN_TO_SHIELD;

        shieldRequired =
                DEFAULT_SHIELD_REQUIRED;

        minimumTotems =
                DEFAULT_MINIMUM_TOTEMS;

        swapDelay =
                DEFAULT_SWAP_DELAY;

        debugMessages =
                DEFAULT_DEBUG_MESSAGES;
    }
}
