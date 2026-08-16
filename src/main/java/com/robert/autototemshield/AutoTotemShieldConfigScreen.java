package com.robert.autototemshield;

public class AutoTotemShieldConfig {

    public static final boolean DEFAULT_ENABLED = true;
    public static final double DEFAULT_TRIGGER_HEARTS = 3.0;
    public static final boolean DEFAULT_RESTOCK_TOTEM = true;
    public static final boolean DEFAULT_RETURN_TO_SHIELD = true;
    public static final boolean DEFAULT_SHIELD_REQUIRED = true;
    public static final int DEFAULT_MINIMUM_TOTEMS = 0;
    public static final int DEFAULT_SWAP_DELAY = 0;
    public static final boolean DEFAULT_DEBUG_MESSAGES = false;

    public static boolean enabled = DEFAULT_ENABLED;
    public static double triggerHearts = DEFAULT_TRIGGER_HEARTS;
    public static boolean restockTotem = DEFAULT_RESTOCK_TOTEM;
    public static boolean returnToShield = DEFAULT_RETURN_TO_SHIELD;
    public static boolean shieldRequired = DEFAULT_SHIELD_REQUIRED;
    public static int minimumTotems = DEFAULT_MINIMUM_TOTEMS;
    public static int swapDelay = DEFAULT_SWAP_DELAY;
    public static boolean debugMessages = DEFAULT_DEBUG_MESSAGES;

    public static void resetToDefaults() {
        enabled = DEFAULT_ENABLED;
        triggerHearts = DEFAULT_TRIGGER_HEARTS;
        restockTotem = DEFAULT_RESTOCK_TOTEM;
        returnToShield = DEFAULT_RETURN_TO_SHIELD;
        shieldRequired = DEFAULT_SHIELD_REQUIRED;
        minimumTotems = DEFAULT_MINIMUM_TOTEMS;
        swapDelay = DEFAULT_SWAP_DELAY;
        debugMessages = DEFAULT_DEBUG_MESSAGES;
    }
}
