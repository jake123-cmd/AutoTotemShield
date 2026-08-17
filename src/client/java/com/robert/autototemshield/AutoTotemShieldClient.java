package com.robert.autototemshield;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class AutoTotemShieldClient implements ClientModInitializer {

    /*
     * ============================================================
     * GENERAL TIMING
     * ============================================================
     */

    private static final int VERIFY_DELAY_TICKS = 1;

    private static final int RETRY_COOLDOWN_TICKS = 2;


    /*
     * ============================================================
     * DANGEROUS FALL SETTINGS
     * ============================================================
     *
     * The old system could trigger from relatively small drops.
     *
     * This version is intentionally conservative.
     *
     * A fall must:
     *
     * 1. Be large enough to actually be dangerous.
     * 2. Still be descending.
     *
     * Normal stairs, slabs and little ledges should NOT trigger.
     */

    /*
     * Minimum fall distance.
     *
     * 6 blocks means we are looking for a genuinely dangerous
     * Minecraft fall rather than ordinary movement.
     */
    private static final float DANGEROUS_FALL_DISTANCE = 6.0F;

    /*
     * The player must still be falling at a meaningful speed.
     */
    private static final double DANGEROUS_FALL_SPEED = -0.55D;

    /*
     * Extremely fast falling can activate slightly earlier,
     * but still requires a real fall distance.
     */
    private static final float FAST_FALL_MIN_DISTANCE = 4.5F;

    private static final double VERY_FAST_FALL_SPEED = -1.00D;


    /*
     * ============================================================
     * FALL SAFETY LOCK
     * ============================================================
     *
     * Once a dangerous fall has activated the Totem, we don't
     * immediately allow the shield to come back just because
     * the player touched the ground.
     *
     * This prevents:
     *
     *     Totem -> Shield
     *
     * immediately after landing.
     *
     * The Totem stays active until the normal return conditions
     * are satisfied.
     */

    private static final int FALL_SAFETY_TICKS = 10;

    private int fallSafetyTicks = 0;


    /*
     * ============================================================
     * COMBAT PROTECTION
     * ============================================================
     *
     * This is separate from the normal 3-heart trigger.
     *
     * Example:
     *
     * Player has 10 hearts.
     * 20 mobs are attacking.
     * Player keeps losing health.
     *
     * The mod can recognize repeated damage and equip the Totem
     * before the player reaches 3 hearts.
     */

    /*
     * Maximum health where combat protection can activate.
     *
     * 10 hearts = 20 health.
     */
    private static final float COMBAT_TRIGGER_HEARTS = 10.0F;

    /*
     * Number of damaging health drops required within the window.
     */
    private static final int COMBAT_REQUIRED_HITS = 3;

    /*
     * 40 ticks = approximately 2 seconds.
     */
    private static final int COMBAT_DAMAGE_WINDOW_TICKS = 40;

    /*
     * Ignore extremely tiny health changes.
     */
    private static final float MIN_DAMAGE_TO_COUNT = 0.5F;

    /*
     * Number of ticks remaining in the damage window.
     */
    private int combatDamageWindow = 0;

    /*
     * Number of meaningful health drops detected recently.
     */
    private int recentDamageHits = 0;

    /*
     * Last health value seen by the mod.
     */
    private float lastHealth = -1.0F;


    /*
     * ============================================================
     * STATE
     * ============================================================
     */

    private boolean emergencyActive = false;

    private boolean returningShield = false;

    private boolean waitingForTotemVerification = false;

    private boolean waitingForShieldVerification = false;

    private boolean fallEmergencyActive = false;

    private boolean combatEmergencyActive = false;

    private int operationCooldown = 0;


    /*
     * The original shield that was in the offhand.
     */
    private ItemStack savedShield = ItemStack.EMPTY;


    /*
     * ============================================================
     * INITIALIZE
     * ============================================================
     */

    @Override
    public void onInitializeClient() {

        ClientTickEvents.END_CLIENT_TICK.register(
                this::tick
        );
    }


    /*
     * ============================================================
     * MAIN TICK
     * ============================================================
     */

    private void tick(Minecraft client) {

        /*
         * No player/world.
         */
        if (client.player == null || client.level == null) {

            resetState();

            return;
        }


        /*
         * Mod disabled.
         */
        if (!AutoTotemShieldConfig.enabled) {
            return;
        }


        Player player = client.player;


        /*
         * Never manipulate inventory after death.
         */
        if (!player.isAlive()) {

            resetState();

            return;
        }


        /*
         * Don't interact with inventories while another screen
         * is open.
         */
        if (client.screen != null) {
            return;
        }


        /*
         * Cooldown.
         */
        if (operationCooldown > 0) {
            operationCooldown--;
        }


        /*
         * Fall safety countdown.
         */
        if (fallSafetyTicks > 0) {
            fallSafetyTicks--;
        }


        /*
         * Current health.
         */
        float health =
                player.getHealth();


        /*
         * Initialise health tracking.
         */
        if (lastHealth < 0.0F) {

            lastHealth = health;
        }


        /*
         * ========================================================
         * COMBAT DAMAGE TRACKING
         * ========================================================
         *
         * We compare the current health against the previous
         * tick.
         *
         * If health dropped by a meaningful amount, count it as
         * incoming damage.
         */

        float healthDifference =
                lastHealth - health;


        if (healthDifference >= MIN_DAMAGE_TO_COUNT) {

            /*
             * Start/restart the damage window.
             */
            combatDamageWindow =
                    COMBAT_DAMAGE_WINDOW_TICKS;

            recentDamageHits++;

            /*
             * Prevent the counter from becoming unnecessarily huge.
             */
            if (recentDamageHits > 10) {
                recentDamageHits = 10;
            }
        }


        lastHealth = health;


        /*
         * Damage window countdown.
         */
        if (combatDamageWindow > 0) {

            combatDamageWindow--;

        } else {

            /*
             * No recent damage.
             *
             * Reset the hit counter.
             */
            recentDamageHits = 0;
        }


        /*
         * ========================================================
         * CONFIGURED HEALTH
         * ========================================================
         */

        double triggerHealth =
                AutoTotemShieldConfig.triggerHearts * 2.0D;


        double returnHealth =
                AutoTotemShieldConfig.returnHearts * 2.0D;


        /*
         * ========================================================
         * OFFHAND
         * ========================================================
         */

        ItemStack offhand =
                player.getItemBySlot(
                        EquipmentSlot.OFFHAND
                );


        /*
         * ========================================================
         * VERIFY TOTEM SWAP
         * ========================================================
         */

        if (waitingForTotemVerification) {

            /*
             * Success.
             */
            if (offhand.is(Items.TOTEM_OF_UNDYING)) {

                waitingForTotemVerification = false;

                return;
            }


            /*
             * Swap didn't appear to succeed.
             *
             * Retry.
             */
            if (operationCooldown == 0) {

                waitingForTotemVerification = false;

                if (putTotemInOffhand(
                        client,
                        player)) {

                    operationCooldown =
                            RETRY_COOLDOWN_TICKS;
                }
            }

            return;
        }


        /*
         * ========================================================
         * VERIFY SHIELD RESTORATION
         * ========================================================
         */

        if (waitingForShieldVerification) {

            /*
             * Success.
             */
            if (offhand.is(Items.SHIELD)) {

                waitingForShieldVerification = false;

                returningShield = false;

                emergencyActive = false;

                fallEmergencyActive = false;

                combatEmergencyActive = false;

                savedShield = ItemStack.EMPTY;

                return;
            }


            /*
             * Shield wasn't restored.
             *
             * Retry.
             */
            if (operationCooldown == 0) {

                waitingForShieldVerification = false;

                if (restoreShield(
                        client,
                        player)) {

                    operationCooldown =
                            RETRY_COOLDOWN_TICKS;
                }
            }

            return;
        }


        /*
         * ========================================================
         * NORMAL LOW-HEALTH DETECTION
         * ========================================================
         */

        boolean lowHealth =
                health <= triggerHealth;


        /*
         * ========================================================
         * DANGEROUS FALL DETECTION
         * ========================================================
         */

        boolean airborne =
                !player.onGround();


        /*
         * IMPORTANT:
         *
         * fallDistance is a FLOAT in Minecraft 26.1.x.
         */
        float fallDistance =
                player.fallDistance;


        double verticalVelocity =
                player.getDeltaMovement().y;


        /*
         * Normal dangerous fall.
         *
         * Requires BOTH:
         *
         * - at least 6 blocks of fall distance
         * - meaningful downward velocity
         */
        boolean dangerousFall =
                airborne
                        && fallDistance >= DANGEROUS_FALL_DISTANCE
                        && verticalVelocity <= DANGEROUS_FALL_SPEED;


        /*
         * Very fast fall.
         *
         * This can trigger slightly earlier, but still requires
         * at least 4.5 blocks of actual fall distance.
         */
        boolean veryFastDangerousFall =
                airborne
                        && fallDistance >= FAST_FALL_MIN_DISTANCE
                        && verticalVelocity <= VERY_FAST_FALL_SPEED;


        dangerousFall =
                dangerousFall
                        || veryFastDangerousFall;


        /*
         * ========================================================
         * COMBAT DANGER DETECTION
         * ========================================================
         *
         * Combat protection only activates while:
         *
         * - health is at or below 10 hearts
         * - multiple meaningful damage events happened recently
         *
         * This means:
         *
         * 10 hearts + getting repeatedly hit = Totem
         *
         * while:
         *
         * 20 hearts + taking one tiny hit = Shield stays.
         */
        boolean combatDanger =
                health <= COMBAT_TRIGGER_HEARTS * 2.0F
                        && combatDamageWindow > 0
                        && recentDamageHits >= COMBAT_REQUIRED_HITS;


        /*
         * ========================================================
         * EMERGENCY CONDITION
         * ========================================================
         */

        boolean emergency =
                lowHealth
                        || dangerousFall
                        || combatDanger;


        /*
         * ========================================================
         * EMERGENCY START
         * ========================================================
         */

        if (emergency) {

            /*
             * Remember what caused the emergency.
             */
            if (dangerousFall) {

                fallEmergencyActive = true;

                /*
                 * Give the Totem a small safety lock after
                 * landing.
                 */
                fallSafetyTicks =
                        FALL_SAFETY_TICKS;
            }


            if (combatDanger) {

                combatEmergencyActive = true;
            }


            /*
             * Never begin restoring the shield during danger.
             */
            returningShield = false;


            /*
             * ====================================================
             * SHIELD REQUIRED
             * ====================================================
             */

            if (AutoTotemShieldConfig.shieldRequired) {

                /*
                 * Already have a Totem.
                 *
                 * DO ABSOLUTELY NOTHING.
                 *
                 * This prevents phasing.
                 */
                if (offhand.is(Items.TOTEM_OF_UNDYING)) {

                    emergencyActive = true;

                    return;
                }


                /*
                 * Save the shield only once.
                 */
                if (!emergencyActive
                        && offhand.is(Items.SHIELD)) {

                    savedShield =
                            offhand.copy();

                    emergencyActive = true;
                }


                /*
                 * Equip Totem.
                 */
                if (emergencyActive
                        && !offhand.is(Items.TOTEM_OF_UNDYING)
                        && operationCooldown == 0) {

                    if (putTotemInOffhand(
                            client,
                            player)) {

                        operationCooldown =
                                VERIFY_DELAY_TICKS;
                    }

                    return;
                }
            }


            /*
             * ====================================================
             * SHIELD REQUIRED OFF
             * ====================================================
             */

            else {

                emergencyActive = true;


                if (!offhand.is(Items.TOTEM_OF_UNDYING)
                        && operationCooldown == 0) {

                    if (putTotemInOffhand(
                            client,
                            player)) {

                        operationCooldown =
                                VERIFY_DELAY_TICKS;
                    }
                }

                return;
            }
        }


        /*
         * ========================================================
         * FALL EMERGENCY LOCK
         * ========================================================
         *
         * This is the major improvement.
         *
         * Once a dangerous fall activated the Totem:
         *
         *     DO NOT immediately restore shield.
         *
         * We require:
         *
         * - the player has landed
         * - the safety timer has expired
         * - the normal return conditions are satisfied
         *
         * This prevents the 6-block:
         *
         * Totem -> Shield -> Totem
         *
         * problem.
         */

        if (fallEmergencyActive) {

            /*
             * Still airborne.
             *
             * Keep Totem.
             */
            if (!player.onGround()) {

                if (offhand.is(
                        Items.TOTEM_OF_UNDYING)) {

                    return;
                }


                /*
                 * Totem disappeared while falling.
                 *
                 * Try to replace it.
                 */
                if (operationCooldown == 0) {

                    if (putTotemInOffhand(
                            client,
                            player)) {

                        operationCooldown =
                                VERIFY_DELAY_TICKS;
                    }
                }

                return;
            }


            /*
             * Player has landed.
             *
             * Don't immediately clear the fall emergency.
             *
             * Keep the safety lock active.
             */
            if (fallSafetyTicks > 0) {

                return;
            }
        }


        /*
         * ========================================================
         * COMBAT EMERGENCY LOCK
         * ========================================================
         *
         * If we're still taking damage, don't immediately return
         * the shield.
         */

        if (combatEmergencyActive) {

            /*
             * If damage is still happening, keep Totem.
             */
            if (combatDamageWindow > 0
                    && recentDamageHits >= COMBAT_REQUIRED_HITS) {

                return;
            }


            /*
             * Combat danger has calmed down.
             *
             * Normal Return Health logic below can now decide
             * when to restore the shield.
             */
            combatEmergencyActive = false;
        }


        /*
         * ========================================================
         * RETURN TO SHIELD
         * ========================================================
         */

        if (emergencyActive
                && health >= returnHealth) {


            /*
             * Return-to-shield disabled.
             */
            if (!AutoTotemShieldConfig.returnToShield) {

                emergencyActive = false;

                returningShield = false;

                fallEmergencyActive = false;

                combatEmergencyActive = false;

                savedShield = ItemStack.EMPTY;

                return;
            }


            /*
             * NEVER restore while airborne.
             */
            if (!player.onGround()) {

                return;
            }


            /*
             * Fall safety lock still active.
             */
            if (fallSafetyTicks > 0) {

                return;
            }


            offhand =
                    player.getItemBySlot(
                            EquipmentSlot.OFFHAND
                    );


            /*
             * If Totem is still equipped, restore shield.
             */
            if (offhand.is(Items.TOTEM_OF_UNDYING)) {

                if (operationCooldown == 0) {

                    returningShield = true;

                    if (restoreShield(
                            client,
                            player)) {

                        operationCooldown =
                                VERIFY_DELAY_TICKS;
                    }
                }

                return;
            }


            /*
             * Totem was consumed.
             *
             * If restocking is enabled, attempt another Totem
             * first.
             */
            if (offhand.isEmpty()) {

                if (AutoTotemShieldConfig.restockTotem
                        && operationCooldown == 0) {

                    if (putTotemInOffhand(
                            client,
                            player)) {

                        operationCooldown =
                                VERIFY_DELAY_TICKS;

                        return;
                    }
                }


                /*
                 * No Totem available.
                 *
                 * Restore the shield if possible.
                 */
                if (operationCooldown == 0) {

                    if (restoreShield(
                            client,
                            player)) {

                        returningShield = true;

                        operationCooldown =
                                VERIFY_DELAY_TICKS;

                        return;
                    }
                }

                return;
            }


            /*
             * Player manually changed offhand.
             *
             * Respect their action.
             */
            emergencyActive = false;

            returningShield = false;

            fallEmergencyActive = false;

            combatEmergencyActive = false;

            savedShield = ItemStack.EMPTY;

            return;
        }
    }


    /*
     * ============================================================
     * FIND TOTEM
     * ============================================================
     */

    private int findTotemSlot(Player player) {

        Inventory inventory =
                player.getInventory();


        /*
         * Search the complete player inventory.
         */
        for (int slot = 0;
             slot < inventory.getContainerSize();
             slot++) {

            ItemStack stack =
                    inventory.getItem(slot);


            if (stack.is(Items.TOTEM_OF_UNDYING)) {

                return slot;
            }
        }


        return -1;
    }


    /*
     * ============================================================
     * INVENTORY SLOT -> MENU SLOT
     * ============================================================
     *
     * Player inventory:
     *
     * 0-8   = hotbar
     * 9-35  = main inventory
     *
     * Player inventory menu:
     *
     * 9-35  = main inventory
     * 36-44 = hotbar
     * 45    = offhand
     */

    private int inventorySlotToMenuSlot(
            int inventorySlot) {

        /*
         * Hotbar.
         */
        if (inventorySlot >= 0
                && inventorySlot <= 8) {

            return 36 + inventorySlot;
        }


        /*
         * Main inventory.
         */
        if (inventorySlot >= 9
                && inventorySlot <= 35) {

            return inventorySlot;
        }


        return -1;
    }


    /*
     * ============================================================
     * PUT TOTEM INTO OFFHAND
     * ============================================================
     */

    private boolean putTotemInOffhand(
            Minecraft client,
            Player player) {

        if (client.gameMode == null) {

            return false;
        }


        ItemStack offhand =
                player.getItemBySlot(
                        EquipmentSlot.OFFHAND
                );


        /*
         * Already equipped.
         */
        if (offhand.is(Items.TOTEM_OF_UNDYING)) {

            waitingForTotemVerification = false;

            return true;
        }


        /*
         * Find a Totem.
         */
        int inventorySlot =
                findTotemSlot(player);


        if (inventorySlot == -1) {

            return false;
        }


        int menuSlot =
                inventorySlotToMenuSlot(
                        inventorySlot
                );


        if (menuSlot == -1) {

            return false;
        }


        /*
         * REAL Minecraft inventory operation.
         *
         * Button 40 = offhand.
         */
        client.gameMode.handleContainerInput(
                player.inventoryMenu.containerId,
                menuSlot,
                40,
                ContainerInput.SWAP,
                player
        );


        /*
         * Verify on the following tick.
         */
        waitingForTotemVerification = true;


        return true;
    }


    /*
     * ============================================================
     * RESTORE SHIELD
     * ============================================================
     */

    private boolean restoreShield(
            Minecraft client,
            Player player) {

        /*
         * No saved shield.
         */
        if (savedShield.isEmpty()) {

            return false;
        }


        /*
         * No game mode.
         */
        if (client.gameMode == null) {

            return false;
        }


        ItemStack offhand =
                player.getItemBySlot(
                        EquipmentSlot.OFFHAND
                );


        /*
         * Only swap if a Totem is actually in the offhand.
         */
        if (!offhand.is(Items.TOTEM_OF_UNDYING)) {

            return false;
        }


        Inventory inventory =
                player.getInventory();


        /*
         * Find saved shield.
         */
        int shieldSlot =
                findSavedShieldSlot(
                        inventory
                );


        if (shieldSlot == -1) {

            return false;
        }


        int menuSlot =
                inventorySlotToMenuSlot(
                        shieldSlot
                );


        if (menuSlot == -1) {

            return false;
        }


        /*
         * REAL Minecraft inventory operation.
         *
         * Shield <-> Totem.
         */
        client.gameMode.handleContainerInput(
                player.inventoryMenu.containerId,
                menuSlot,
                40,
                ContainerInput.SWAP,
                player
        );


        /*
         * Verify next tick.
         */
        waitingForShieldVerification = true;


        return true;
    }


    /*
     * ============================================================
     * FIND SAVED SHIELD
     * ============================================================
     */

    private int findSavedShieldSlot(
            Inventory inventory) {

        /*
         * First find the exact original shield.
         */
        for (int slot = 0;
             slot < inventory.getContainerSize();
             slot++) {

            ItemStack stack =
                    inventory.getItem(slot);


            if (!stack.is(Items.SHIELD)) {
                continue;
            }


            if (ItemStack.matches(
                    stack,
                    savedShield)) {

                return slot;
            }
        }


        /*
         * Fallback to any shield.
         */
        for (int slot = 0;
             slot < inventory.getContainerSize();
             slot++) {

            ItemStack stack =
                    inventory.getItem(slot);


            if (stack.is(Items.SHIELD)) {

                return slot;
            }
        }


        return -1;
    }


    /*
     * ============================================================
     * RESET
     * ============================================================
     */

    private void resetState() {

        emergencyActive = false;

        returningShield = false;

        waitingForTotemVerification = false;

        waitingForShieldVerification = false;

        fallEmergencyActive = false;

        combatEmergencyActive = false;

        operationCooldown = 0;

        fallSafetyTicks = 0;

        combatDamageWindow = 0;

        recentDamageHits = 0;

        lastHealth = -1.0F;

        savedShield = ItemStack.EMPTY;
    }
}
