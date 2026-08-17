package com.robert.autototemshield;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
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
     * Wait one tick after an inventory operation before deciding
     * that it failed.
     */
    private static final int VERIFY_DELAY_TICKS = 1;

    /*
     * Don't spam inventory packets.
     */
    private static final int RETRY_COOLDOWN_TICKS = 2;

    /*
     * Start preparing the Totem while falling quickly.
     */
    private static final double FALL_SPEED_TRIGGER = -0.35D;

    /*
     * Extremely fast fall protection.
     *
     * This is deliberately lower than the normal trigger because
     * the player may still have full health immediately before
     * receiving a huge amount of fall damage.
     */
    private static final double FAST_FALL_TRIGGER = -0.70D;


    /*
     * ============================================================
     * STATE
     * ============================================================
     */

    private boolean emergencyActive = false;

    private boolean returningShield = false;

    private boolean waitingForTotemVerification = false;

    private boolean waitingForShieldVerification = false;

    private int operationCooldown = 0;

    /*
     * Remember the shield that was originally in the offhand.
     *
     * This preserves durability and components.
     */
    private ItemStack savedShield = ItemStack.EMPTY;


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

        if (client.player == null || client.level == null) {
            resetState();
            return;
        }

        if (!AutoTotemShieldConfig.enabled) {
            return;
        }

        Player player = client.player;


        /*
         * Never manipulate inventory while dead.
         */
        if (!player.isAlive()) {
            resetState();
            return;
        }


        /*
         * Don't operate while a screen/container is open.
         *
         * This prevents the mod from accidentally clicking a
         * chest, crafting table, furnace, etc.
         */
        if (client.screen != null) {
            return;
        }


        if (operationCooldown > 0) {
            operationCooldown--;
        }


        float health = player.getHealth();

        double triggerHealth =
                AutoTotemShieldConfig.triggerHearts * 2.0D;

        double returnHealth =
                AutoTotemShieldConfig.returnHearts * 2.0D;


        ItemStack offhand =
                player.getItemBySlot(
                        EquipmentSlot.OFFHAND
                );


        /*
         * ========================================================
         * VERIFY A PENDING TOTEM SWAP
         * ========================================================
         */

        if (waitingForTotemVerification) {

            if (offhand.is(Items.TOTEM_OF_UNDYING)) {

                /*
                 * SUCCESS.
                 *
                 * Minecraft now sees the Totem in the actual
                 * offhand slot through the inventory system.
                 */
                waitingForTotemVerification = false;

            } else if (operationCooldown == 0) {

                /*
                 * The previous click didn't result in a Totem.
                 *
                 * Try again.
                 */
                waitingForTotemVerification = false;

                putTotemInOffhand(client, player);

                operationCooldown =
                        RETRY_COOLDOWN_TICKS;
            }

            /*
             * Never continue into shield restoration while we
             * haven't confirmed the Totem.
             */
            return;
        }


        /*
         * ========================================================
         * VERIFY A PENDING SHIELD RESTORATION
         * ========================================================
         */

        if (waitingForShieldVerification) {

            if (offhand.is(Items.SHIELD)) {

                /*
                 * Shield successfully restored.
                 */
                waitingForShieldVerification = false;
                returningShield = false;
                emergencyActive = false;
                savedShield = ItemStack.EMPTY;

            } else if (operationCooldown == 0) {

                /*
                 * Shield wasn't restored yet.
                 */
                waitingForShieldVerification = false;

                restoreShield(client, player);

                operationCooldown =
                        RETRY_COOLDOWN_TICKS;
            }

            return;
        }


        /*
         * ========================================================
         * EMERGENCY DETECTION
         * ========================================================
         */

        boolean lowHealth =
                health <= triggerHealth;


        boolean falling =
                !player.onGround()
                        && player.getDeltaMovement().y
                        < FALL_SPEED_TRIGGER;


        boolean extremelyFastFall =
                !player.onGround()
                        && player.getDeltaMovement().y
                        < FAST_FALL_TRIGGER;


        boolean emergency =
                lowHealth
                        || falling
                        || extremelyFastFall;


        /*
         * ========================================================
         * EMERGENCY ACTIVE
         * ========================================================
         */

        if (emergency) {

            returningShield = false;


            /*
             * ----------------------------------------------------
             * SHIELD REQUIRED MODE
             * ----------------------------------------------------
             */

            if (AutoTotemShieldConfig.shieldRequired) {

                /*
                 * Already have the Totem.
                 *
                 * DO NOTHING.
                 *
                 * This is extremely important.
                 *
                 * We must not keep clicking the inventory every
                 * tick while a Totem is already equipped.
                 */
                if (offhand.is(Items.TOTEM_OF_UNDYING)) {

                    emergencyActive = true;

                    return;
                }


                /*
                 * First emergency tick.
                 *
                 * Save the actual shield before swapping it.
                 */
                if (!emergencyActive
                        && offhand.is(Items.SHIELD)) {

                    savedShield =
                            offhand.copy();

                    emergencyActive = true;
                }


                /*
                 * If emergency mode is active and there is no
                 * Totem in the offhand, try to equip one.
                 */
                if (emergencyActive
                        && !offhand.is(Items.TOTEM_OF_UNDYING)
                        && operationCooldown == 0) {

                    /*
                     * IMPORTANT:
                     *
                     * We do NOT use setItemSlot().
                     *
                     * We perform a normal Minecraft inventory
                     * SWAP operation instead.
                     */
                    if (putTotemInOffhand(client, player)) {

                        operationCooldown =
                                VERIFY_DELAY_TICKS;
                    }

                    return;
                }
            }


            /*
             * ----------------------------------------------------
             * SHIELD REQUIRED DISABLED
             * ----------------------------------------------------
             */

            else {

                emergencyActive = true;

                if (!offhand.is(Items.TOTEM_OF_UNDYING)
                        && operationCooldown == 0) {

                    if (putTotemInOffhand(client, player)) {

                        operationCooldown =
                                VERIFY_DELAY_TICKS;
                    }
                }

                return;
            }
        }


        /*
         * ========================================================
         * SAFE AGAIN
         * ========================================================
         *
         * We don't immediately restore the shield.
         *
         * We wait until Return Health.
         */

        if (emergencyActive
                && health >= returnHealth) {


            /*
             * Return-to-shield disabled.
             */
            if (!AutoTotemShieldConfig.returnToShield) {

                emergencyActive = false;
                returningShield = false;
                savedShield = ItemStack.EMPTY;

                return;
            }


            offhand =
                    player.getItemBySlot(
                            EquipmentSlot.OFFHAND
                    );


            /*
             * Only restore the shield if the offhand still
             * contains the Totem we expect.
             *
             * If the player manually changed their offhand,
             * respect their decision.
             */
            if (offhand.is(Items.TOTEM_OF_UNDYING)) {

                if (operationCooldown == 0) {

                    returningShield = true;

                    if (restoreShield(client, player)) {

                        operationCooldown =
                                VERIFY_DELAY_TICKS;
                    }
                }

                return;
            }


            /*
             * Empty offhand can happen if the Totem was consumed.
             *
             * In that case, don't blindly overwrite whatever the
             * player may have manually equipped.
             */
            if (offhand.isEmpty()) {

                emergencyActive = false;
                returningShield = false;
                savedShield = ItemStack.EMPTY;

                return;
            }


            /*
             * Something else is in the offhand.
             *
             * Player probably changed it manually.
             */
            emergencyActive = false;
            returningShield = false;
            savedShield = ItemStack.EMPTY;
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


        for (int inventorySlot = 0;
             inventorySlot < inventory.getContainerSize();
             inventorySlot++) {

            ItemStack stack =
                    inventory.getItem(inventorySlot);

            if (stack.is(Items.TOTEM_OF_UNDYING)) {
                return inventorySlot;
            }
        }

        return -1;
    }


    /*
     * ============================================================
     * INVENTORY SLOT -> SCREEN SLOT
     * ============================================================
     *
     * Minecraft's player inventory menu does not use exactly the
     * same slot numbers as Inventory.
     *
     * Inventory:
     *
     * 0-8   = hotbar
     * 9-35  = main inventory
     *
     * InventoryMenu:
     *
     * 9-35  = main inventory
     * 36-44 = hotbar
     * 45    = offhand
     */

    private int inventorySlotToMenuSlot(int inventorySlot) {

        if (inventorySlot >= 0
                && inventorySlot <= 8) {

            return 36 + inventorySlot;
        }

        if (inventorySlot >= 9
                && inventorySlot <= 35) {

            return inventorySlot;
        }

        return -1;
    }


    /*
     * ============================================================
     * NORMAL SYNCHRONIZED TOTEM SWAP
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


        int inventorySlot =
                findTotemSlot(player);


        /*
         * No Totem available.
         */
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
         * ========================================================
         * THIS IS THE IMPORTANT PART
         * ========================================================
         *
         * Button 40 = offhand.
         *
         * SWAP means:
         *
         * inventory slot <-> selected offhand slot
         *
         * This lets Minecraft perform the actual inventory
         * transaction instead of us directly editing the slot.
         */
        client.gameMode.handleInventoryMouseClick(
                player.inventoryMenu.containerId,
                menuSlot,
                40,
                ContainerInput.SWAP,
                player
        );


        /*
         * We do NOT assume success.
         *
         * The next tick verifies the actual offhand.
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

        if (savedShield.isEmpty()) {
            return false;
        }


        if (client.gameMode == null) {
            return false;
        }


        ItemStack offhand =
                player.getItemBySlot(
                        EquipmentSlot.OFFHAND
                );


        /*
         * If the player no longer has the Totem in the offhand,
         * don't overwrite anything.
         */
        if (!offhand.is(Items.TOTEM_OF_UNDYING)) {

            return false;
        }


        Inventory inventory =
                player.getInventory();


        /*
         * Find the saved shield.
         *
         * We search for an actual shield matching the saved
         * stack's components/durability as closely as possible.
         */
        int shieldInventorySlot =
                findSavedShieldSlot(
                        inventory
                );


        /*
         * If we cannot find the shield, don't destroy the Totem.
         */
        if (shieldInventorySlot == -1) {
            return false;
        }


        int menuSlot =
                inventorySlotToMenuSlot(
                        shieldInventorySlot
                );


        if (menuSlot == -1) {
            return false;
        }


        /*
         * Swap the shield into the offhand using Minecraft's
         * synchronized inventory operation.
         *
         * The Totem automatically goes back into the shield's
         * inventory slot.
         */
        client.gameMode.handleInventoryMouseClick(
                player.inventoryMenu.containerId,
                menuSlot,
                40,
                ContainerInput.SWAP,
                player
        );


        /*
         * Again, don't assume it worked.
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
         * First try to find an exact matching stack.
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
                    savedShield
            )) {

                return slot;
            }
        }


        /*
         * If the exact stack isn't found, find any shield.
         *
         * This is a fallback for cases where Minecraft has changed
         * a stack's metadata/components while the player was using
         * it.
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

        operationCooldown = 0;

        savedShield = ItemStack.EMPTY;
    }
}
