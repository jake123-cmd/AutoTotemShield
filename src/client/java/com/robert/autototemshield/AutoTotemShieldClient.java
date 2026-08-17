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
     * SAFETY / TIMING
     * ============================================================
     */

    /*
     * Verify inventory swaps on the following tick.
     */
    private static final int VERIFY_DELAY_TICKS = 1;

    /*
     * Small delay between failed/repeated inventory operations.
     */
    private static final int RETRY_COOLDOWN_TICKS = 2;

    /*
     * ============================================================
     * DANGEROUS FALL DETECTION
     * ============================================================
     *
     * IMPORTANT:
     *
     * We DO NOT trigger simply because the player is moving
     * downward.
     *
     * That caused the old:
     *
     * Shield -> Totem -> Shield -> Totem
     *
     * problem when walking down stairs or small ledges.
     *
     * The player must have already fallen a meaningful distance
     * AND still be descending.
     */

    /*
     * Minimum fall distance before we consider the fall dangerous.
     *
     * 4 blocks is intentionally used so normal steps, stairs,
     * slabs and tiny ledges don't trigger the system.
     */
    private static final double DANGEROUS_FALL_DISTANCE = 4.0D;

    /*
     * Player must still be moving downward.
     */
    private static final double DANGEROUS_FALL_SPEED = -0.45D;

    /*
     * Very fast falling can trigger slightly earlier, but still
     * requires some actual fall distance.
     */
    private static final double VERY_FAST_FALL_SPEED = -0.90D;

    /*
     * ============================================================
     * STATE
     * ============================================================
     */

    /*
     * True while the emergency system is active.
     */
    private boolean emergencyActive = false;

    /*
     * True when we're currently trying to put the shield back.
     */
    private boolean returningShield = false;

    /*
     * True while waiting to verify a Totem swap.
     */
    private boolean waitingForTotemVerification = false;

    /*
     * True while waiting to verify a shield swap.
     */
    private boolean waitingForShieldVerification = false;

    /*
     * Inventory operation cooldown.
     */
    private int operationCooldown = 0;

    /*
     * True if emergency mode was caused by a dangerous fall.
     *
     * This is important because we must NOT restore the shield
     * while the player is still airborne.
     */
    private boolean fallEmergencyActive = false;

    /*
     * The original shield.
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
         * Never perform inventory operations while another
         * container/screen is open.
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
         * Current health.
         */
        float health =
                player.getHealth();


        /*
         * Configured health thresholds.
         */
        double triggerHealth =
                AutoTotemShieldConfig.triggerHearts * 2.0D;

        double returnHealth =
                AutoTotemShieldConfig.returnHearts * 2.0D;


        /*
         * Actual offhand.
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
             * SUCCESS.
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
             * SUCCESS.
             */
            if (offhand.is(Items.SHIELD)) {

                waitingForShieldVerification = false;

                returningShield = false;

                emergencyActive = false;

                fallEmergencyActive = false;

                savedShield = ItemStack.EMPTY;

                return;
            }


            /*
             * Shield wasn't restored yet.
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
         * HEALTH DETECTION
         * ========================================================
         */

        boolean lowHealth =
                health <= triggerHealth;


        /*
         * ========================================================
         * DANGEROUS FALL DETECTION
         * ========================================================
         *
         * THIS IS THE IMPORTANT FIX.
         *
         * We don't care about merely moving downward.
         *
         * Walking down:
         *
         *   stairs
         *   slabs
         *   small ledges
         *   terrain
         *
         * should NOT activate the Totem.
         *
         * We require:
         *
         * 1. Player is airborne.
         * 2. Player has fallen at least ~4 blocks.
         * 3. Player is still descending.
         *
         * A very fast fall can trigger using a slightly more
         * aggressive path, but it STILL requires fall distance.
         */

        boolean airborne =
                !player.onGround();


        /*
         * FIX:
         *
         * Minecraft 26.1.2 exposes fallDistance as a double.
         */
        double fallDistance =
                player.fallDistance;


        double verticalVelocity =
                player.getDeltaMovement().y;


        boolean dangerousFall =
                airborne
                        && fallDistance >= DANGEROUS_FALL_DISTANCE
                        && verticalVelocity <= DANGEROUS_FALL_SPEED;


        /*
         * Very fast fall:
         *
         * We still require at least 2 blocks of fall distance.
         *
         * This catches extremely fast drops without triggering
         * from normal walking movement.
         */
        boolean veryFastDangerousFall =
                airborne
                        && fallDistance >= 2.0D
                        && verticalVelocity <= VERY_FAST_FALL_SPEED;


        dangerousFall =
                dangerousFall
                        || veryFastDangerousFall;


        /*
         * ========================================================
         * EMERGENCY START
         * ========================================================
         */

        if (lowHealth || dangerousFall) {

            /*
             * A fall emergency is different from ordinary
             * low-health emergency.
             */
            if (dangerousFall) {

                fallEmergencyActive = true;
            }


            /*
             * Never start returning the shield while emergency
             * conditions are active.
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
                 * DO NOTHING.
                 *
                 * This is extremely important.
                 */
                if (offhand.is(Items.TOTEM_OF_UNDYING)) {

                    emergencyActive = true;

                    return;
                }


                /*
                 * Save the original shield only once.
                 */
                if (!emergencyActive
                        && offhand.is(Items.SHIELD)) {

                    savedShield =
                            offhand.copy();

                    emergencyActive = true;
                }


                /*
                 * Emergency is active and Totem isn't equipped.
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
         * FALL EMERGENCY PROTECTION
         * ========================================================
         *
         * If the Totem was equipped because of a dangerous fall,
         * NEVER restore the shield while still airborne.
         *
         * This prevents:
         *
         * Totem -> Shield -> Totem
         *
         * during a fall.
         */

        if (fallEmergencyActive) {

            /*
             * Still airborne.
             *
             * Keep Totem equipped.
             */
            if (!player.onGround()) {

                /*
                 * If the Totem is already equipped, leave it alone.
                 */
                if (offhand.is(Items.TOTEM_OF_UNDYING)) {

                    return;
                }


                /*
                 * If it somehow disappeared while still falling,
                 * try to equip another one.
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
             * We are now on the ground.
             *
             * The dangerous fall is over.
             */
            fallEmergencyActive = false;
        }


        /*
         * ========================================================
         * RETURN TO SHIELD
         * ========================================================
         *
         * The shield is restored ONLY after Return Health.
         *
         * For fall emergencies, the player must ALSO be safely
         * on the ground.
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

                savedShield = ItemStack.EMPTY;

                return;
            }


            /*
             * Absolutely do not restore while airborne.
             */
            if (!player.onGround()) {

                return;
            }


            offhand =
                    player.getItemBySlot(
                            EquipmentSlot.OFFHAND
                    );


            /*
             * Only restore if the Totem is actually occupying
             * the offhand.
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
             * Empty offhand:
             *
             * The Totem was probably consumed.
             *
             * If restocking is enabled, keep emergency mode alive
             * and try to put another Totem there rather than
             * instantly restoring the shield.
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
                 * No replacement Totem available.
                 *
                 * At this point we can safely restore the shield
                 * if one was saved.
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


                /*
                 * Nothing else we can safely do.
                 */
                return;
            }


            /*
             * Player deliberately put another item in the
             * offhand.
             *
             * Respect the player.
             */
            emergencyActive = false;

            returningShield = false;

            fallEmergencyActive = false;

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
     *
     * Player inventory:
     *
     * 0-8   = hotbar
     * 9-35  = main inventory
     *
     * Player menu:
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
         * Do not assume success.
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
         * Only swap if Totem is actually in offhand.
         */
        if (!offhand.is(Items.TOTEM_OF_UNDYING)) {

            return false;
        }


        Inventory inventory =
                player.getInventory();


        /*
         * Find our shield.
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
         * Real Minecraft inventory swap:
         *
         * shield <-> offhand Totem.
         */
        client.gameMode.handleContainerInput(
                player.inventoryMenu.containerId,
                menuSlot,
                40,
                ContainerInput.SWAP,
                player
        );


        /*
         * Verify on next tick.
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
         * First try to find the exact original shield.
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
         * Fallback:
         *
         * Any shield is better than getting permanently stuck.
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
     * RESET STATE
     * ============================================================
     */

    private void resetState() {

        emergencyActive = false;

        returningShield = false;

        waitingForTotemVerification = false;

        waitingForShieldVerification = false;

        fallEmergencyActive = false;

        operationCooldown = 0;

        savedShield = ItemStack.EMPTY;
    }
}
