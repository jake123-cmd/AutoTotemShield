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
     * DANGEROUS FALL
     * ============================================================
     *
     * Small drops MUST NOT trigger the Totem.
     *
     * We require an actual dangerous fall.
     */

    private static final float DANGEROUS_FALL_DISTANCE = 6.0F;

    private static final double DANGEROUS_FALL_SPEED = -0.55D;

    /*
     * Very fast falls can trigger earlier, but still require
     * a substantial fall.
     */
    private static final float FAST_FALL_MIN_DISTANCE = 4.5F;

    private static final double VERY_FAST_FALL_SPEED = -1.00D;


    /*
     * ============================================================
     * FALL SAFETY LOCK
     * ============================================================
     *
     * Prevents:
     *
     * Totem -> Shield -> Totem
     *
     * around dangerous falls.
     */

    private static final int FALL_SAFETY_TICKS = 10;

    private int fallSafetyTicks = 0;


    /*
     * ============================================================
     * COMBAT PROTECTION
     * ============================================================
     *
     * Repeated damage can trigger the Totem above the normal
     * 3-heart threshold.
     *
     * Example:
     *
     * 10 hearts + repeatedly being attacked
     * = Totem protection.
     */

    private static final float COMBAT_TRIGGER_HEARTS = 10.0F;

    private static final int COMBAT_REQUIRED_HITS = 3;

    private static final int COMBAT_DAMAGE_WINDOW_TICKS = 40;

    private static final float MIN_DAMAGE_TO_COUNT = 0.5F;

    private int combatDamageWindow = 0;

    private int recentDamageHits = 0;

    private float lastHealth = -1.0F;


    /*
     * ============================================================
     * ENVIRONMENT DANGER
     * ============================================================
     *
     * Lava is treated as a high-priority danger.
     *
     * If the Totem is already equipped because of lava,
     * healing above the normal return threshold does NOT
     * immediately restore the shield.
     *
     * The player must first leave the dangerous environment.
     */

    private boolean lavaEmergencyActive = false;

    /*
     * Additional fire protection.
     *
     * Being on fire is dangerous, but we are more conservative
     * than lava detection.
     */
    private boolean fireEmergencyActive = false;


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
     * Original shield.
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
         * Never manipulate inventory while another screen
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
         * Fall safety timer.
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
         * ========================================================
         * COMBAT DAMAGE TRACKING
         * ========================================================
         */

        if (lastHealth < 0.0F) {

            lastHealth = health;
        }


        float healthDifference =
                lastHealth - health;


        /*
         * Meaningful health loss.
         */
        if (healthDifference >= MIN_DAMAGE_TO_COUNT) {

            combatDamageWindow =
                    COMBAT_DAMAGE_WINDOW_TICKS;

            recentDamageHits++;

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

            recentDamageHits = 0;
        }


        /*
         * ========================================================
         * CONFIGURATION
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
         * ENVIRONMENT DETECTION
         * ========================================================
         */

        boolean inLava =
                player.isInLava();


        boolean onFire =
                player.isOnFire();


        /*
         * ========================================================
         * LAVA STATE
         * ========================================================
         *
         * Once lava danger has activated while using the Totem,
         * we keep the danger state until the player is actually
         * out of lava.
         */

        if (inLava) {

            lavaEmergencyActive = true;
        }


        /*
         * Fire state.
         *
         * We only use fire protection after the player has
         * actually been set on fire.
         */
        if (onFire) {

            fireEmergencyActive = true;
        }


        /*
         * ========================================================
         * CLEAR FIRE STATE
         * ========================================================
         *
         * Fire danger is cleared once the player is no longer
         * burning.
         */
        if (!onFire) {

            fireEmergencyActive = false;
        }


        /*
         * ========================================================
         * VERIFY TOTEM SWAP
         * ========================================================
         */

        if (waitingForTotemVerification) {

            if (offhand.is(Items.TOTEM_OF_UNDYING)) {

                waitingForTotemVerification = false;

                return;
            }


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

            if (offhand.is(Items.SHIELD)) {

                waitingForShieldVerification = false;

                returningShield = false;

                emergencyActive = false;

                fallEmergencyActive = false;

                combatEmergencyActive = false;

                lavaEmergencyActive = false;

                fireEmergencyActive = false;

                savedShield = ItemStack.EMPTY;

                return;
            }


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
         * HEALTH DETECTION
         * ========================================================
         */

        boolean lowHealth =
                health <= triggerHealth;


        /*
         * ========================================================
         * FALL DETECTION
         * ========================================================
         */

        boolean airborne =
                !player.onGround();


        /*
         * Minecraft 26.1.x uses FLOAT for fallDistance.
         */
        float fallDistance =
                player.fallDistance;


        double verticalVelocity =
                player.getDeltaMovement().y;


        /*
         * Genuine dangerous fall.
         */
        boolean dangerousFall =
                airborne
                        && fallDistance >= DANGEROUS_FALL_DISTANCE
                        && verticalVelocity <= DANGEROUS_FALL_SPEED;


        /*
         * Extremely fast dangerous fall.
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
         * COMBAT DANGER
         * ========================================================
         */

        boolean combatDanger =
                health <= COMBAT_TRIGGER_HEARTS * 2.0F
                        && combatDamageWindow > 0
                        && recentDamageHits >= COMBAT_REQUIRED_HITS;


        /*
         * ========================================================
         * ENVIRONMENT DANGER
         * ========================================================
         *
         * Lava is intentionally considered dangerous even when
         * health is currently high.
         *
         * Example:
         *
         * Player enters lava.
         * Totem equips.
         * Player eats/heals to 10 hearts.
         * Totem STAYS.
         */

        boolean environmentDanger =
                inLava
                        || lavaEmergencyActive
                        || fireEmergencyActive;


        /*
         * ========================================================
         * EMERGENCY CONDITION
         * ========================================================
         */

        boolean emergency =
                lowHealth
                        || dangerousFall
                        || combatDanger
                        || environmentDanger;


        /*
         * ========================================================
         * EMERGENCY START
         * ========================================================
         */

        if (emergency) {

            /*
             * Dangerous fall state.
             */
            if (dangerousFall) {

                fallEmergencyActive = true;

                fallSafetyTicks =
                        FALL_SAFETY_TICKS;
            }


            /*
             * Combat state.
             */
            if (combatDanger) {

                combatEmergencyActive = true;
            }


            /*
             * Environment state.
             */
            if (inLava) {

                lavaEmergencyActive = true;
            }


            /*
             * Fire state.
             */
            if (onFire) {

                fireEmergencyActive = true;
            }


            /*
             * Never start returning the shield during danger.
             */
            returningShield = false;


            /*
             * ====================================================
             * SHIELD REQUIRED
             * ====================================================
             */

            if (AutoTotemShieldConfig.shieldRequired) {

                /*
                 * Already have Totem.
                 *
                 * DO NOTHING.
                 *
                 * This is critical for preventing flickering.
                 */
                if (offhand.is(Items.TOTEM_OF_UNDYING)) {

                    emergencyActive = true;

                    return;
                }


                /*
                 * Save shield once.
                 */
                if (!emergencyActive
                        && offhand.is(Items.SHIELD)) {

                    savedShield =
                            offhand.copy();

                    emergencyActive = true;
                }


                /*
                 * Put Totem into offhand.
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
         * LAVA SAFETY LOCK
         * ========================================================
         *
         * This has higher priority than Return Health.
         *
         * If the player is still in lava:
         *
         * NEVER restore shield.
         */

        if (lavaEmergencyActive) {

            /*
             * Still in lava.
             */
            if (inLava) {

                /*
                 * Keep Totem.
                 */
                if (offhand.is(
                        Items.TOTEM_OF_UNDYING)) {

                    return;
                }


                /*
                 * Totem disappeared while still in lava.
                 *
                 * Attempt replacement.
                 */
                if (operationCooldown == 0) {

                    if (putTotemInOffhand(
                            client,
                            player)) {

                        emergencyActive = true;

                        operationCooldown =
                                VERIFY_DELAY_TICKS;
                    }
                }

                return;
            }


            /*
             * No longer in lava.
             *
             * DO NOT immediately clear the emergency.
             *
             * The normal return-health logic below decides
             * when the shield can safely return.
             */
            lavaEmergencyActive = false;
        }


        /*
         * ========================================================
         * FIRE SAFETY
         * ========================================================
         */

        if (fireEmergencyActive) {

            /*
             * Still burning.
             *
             * Keep Totem.
             */
            if (onFire) {

                if (offhand.is(
                        Items.TOTEM_OF_UNDYING)) {

                    return;
                }


                if (operationCooldown == 0) {

                    if (putTotemInOffhand(
                            client,
                            player)) {

                        emergencyActive = true;

                        operationCooldown =
                                VERIFY_DELAY_TICKS;
                    }
                }

                return;
            }


            /*
             * No longer burning.
             *
             * Allow normal return logic.
             */
            fireEmergencyActive = false;
        }


        /*
         * ========================================================
         * FALL SAFETY LOCK
         * ========================================================
         */

        if (fallEmergencyActive) {

            /*
             * Still airborne.
             */
            if (!player.onGround()) {

                if (offhand.is(
                        Items.TOTEM_OF_UNDYING)) {

                    return;
                }


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
             * Landed but safety timer still active.
             */
            if (fallSafetyTicks > 0) {

                return;
            }


            /*
             * Fall is now completely finished.
             */
            fallEmergencyActive = false;
        }


        /*
         * ========================================================
         * COMBAT SAFETY LOCK
         * ========================================================
         */

        if (combatEmergencyActive) {

            /*
             * Still taking repeated damage.
             */
            if (combatDamageWindow > 0
                    && recentDamageHits >= COMBAT_REQUIRED_HITS) {

                return;
            }


            /*
             * Combat has calmed down.
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
             * User disabled shield restoration.
             */
            if (!AutoTotemShieldConfig.returnToShield) {

                emergencyActive = false;

                returningShield = false;

                fallEmergencyActive = false;

                combatEmergencyActive = false;

                lavaEmergencyActive = false;

                fireEmergencyActive = false;

                savedShield = ItemStack.EMPTY;

                return;
            }


            /*
             * Never restore while airborne.
             */
            if (!player.onGround()) {

                return;
            }


            /*
             * Never restore while the fall safety lock exists.
             */
            if (fallSafetyTicks > 0) {

                return;
            }


            /*
             * Never restore while in lava.
             */
            if (player.isInLava()) {

                return;
            }


            /*
             * Never restore while burning.
             */
            if (player.isOnFire()) {

                return;
            }


            offhand =
                    player.getItemBySlot(
                            EquipmentSlot.OFFHAND
                    );


            /*
             * Totem still equipped.
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
             * Try to restock first.
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
                 * Restore shield.
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
             * Respect it.
             */
            emergencyActive = false;

            returningShield = false;

            fallEmergencyActive = false;

            combatEmergencyActive = false;

            lavaEmergencyActive = false;

            fireEmergencyActive = false;

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
         * Find Totem.
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
         * Button 40 = offhand swap.
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
         * Only swap if Totem is actually equipped.
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
         * Exact original shield first.
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

        lavaEmergencyActive = false;

        fireEmergencyActive = false;

        operationCooldown = 0;

        fallSafetyTicks = 0;

        combatDamageWindow = 0;

        recentDamageHits = 0;

        lastHealth = -1.0F;

        savedShield = ItemStack.EMPTY;
    }
}
