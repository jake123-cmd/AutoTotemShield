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

    private static final int VERIFY_DELAY_TICKS = 1;

    private static final int RETRY_COOLDOWN_TICKS = 2;

    /*
     * Start preparing the Totem while falling.
     */
    private static final double FALL_SPEED_TRIGGER = -0.35D;

    /*
     * Faster fall = even more urgent.
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
     * The shield that was originally in the offhand.
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
         * Don't perform inventory clicks while a chest,
         * crafting table, furnace, etc. is open.
         *
         * This prevents the mod from accidentally clicking
         * another container.
         */
        if (client.screen != null) {
            return;
        }


        /*
         * Cooldown between inventory operations.
         */
        if (operationCooldown > 0) {
            operationCooldown--;
        }


        float health =
                player.getHealth();


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
         * VERIFY TOTEM SWAP
         * ========================================================
         *
         * We NEVER assume the swap succeeded.
         *
         * We check the actual offhand slot.
         */

        if (waitingForTotemVerification) {

            if (offhand.is(Items.TOTEM_OF_UNDYING)) {

                /*
                 * SUCCESS.
                 */
                waitingForTotemVerification = false;

                return;
            }


            /*
             * Swap did not appear in the offhand.
             *
             * Retry after cooldown.
             */
            if (operationCooldown == 0) {

                waitingForTotemVerification = false;

                if (putTotemInOffhand(client, player)) {

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

                /*
                 * SUCCESS.
                 */
                waitingForShieldVerification = false;

                returningShield = false;

                emergencyActive = false;

                savedShield = ItemStack.EMPTY;

                return;
            }


            /*
             * Shield wasn't restored.
             */
            if (operationCooldown == 0) {

                waitingForShieldVerification = false;

                if (restoreShield(client, player)) {

                    operationCooldown =
                            RETRY_COOLDOWN_TICKS;
                }
            }

            return;
        }


        /*
         * ========================================================
         * HEALTH / FALL DETECTION
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


        /*
         * Emergency can therefore be triggered by:
         *
         * - low health
         * - falling
         * - very fast falling
         *
         * This doesn't care what caused previous damage.
         */
        boolean emergency =
                lowHealth
                        || falling
                        || extremelyFastFall;


        /*
         * ========================================================
         * EMERGENCY
         * ========================================================
         */

        if (emergency) {

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
                 * DO NOT TOUCH IT.
                 *
                 * This prevents the shield/Totem from repeatedly
                 * phasing over each other.
                 */
                if (offhand.is(Items.TOTEM_OF_UNDYING)) {

                    emergencyActive = true;

                    return;
                }


                /*
                 * First emergency detection.
                 *
                 * Save the actual shield.
                 */
                if (!emergencyActive
                        && offhand.is(Items.SHIELD)) {

                    savedShield =
                            offhand.copy();

                    emergencyActive = true;
                }


                /*
                 * Emergency is active but the Totem isn't equipped.
                 *
                 * Use a real Minecraft inventory swap.
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
         * RETURN TO SHIELD
         * ========================================================
         *
         * We only restore after Return Health.
         */

        if (emergencyActive
                && health >= returnHealth) {


            /*
             * User disabled shield restoration.
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
             * Only swap if the Totem is actually there.
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
             * If the Totem was consumed and the offhand is now
             * empty, don't blindly overwrite it.
             *
             * The player may have manually changed something.
             */
            if (offhand.isEmpty()) {

                emergencyActive = false;

                returningShield = false;

                savedShield = ItemStack.EMPTY;

                return;
            }


            /*
             * Player put something else in the offhand.
             *
             * Respect the player's action.
             */
            emergencyActive = false;

            returningShield = false;

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
         * Search the entire player inventory.
         *
         * 1 Totem = usable.
         * 3 Totems = usable.
         * 20 Totems = usable.
         *
         * minimumTotems is NOT used as an emergency blocker.
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
     * INVENTORY SLOT -> PLAYER MENU SLOT
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

        /*
         * No game mode = don't touch inventory.
         */
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
         * Find an available Totem.
         */
        int inventorySlot =
                findTotemSlot(player);


        if (inventorySlot == -1) {

            /*
             * No Totem available.
             */
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
         * REAL MINECRAFT INVENTORY OPERATION
         * ========================================================
         *
         * 40 = offhand swap button.
         *
         * ContainerInput.SWAP tells Minecraft to perform the
         * actual inventory swap instead of us directly changing
         * the player's slot.
         *
         * Minecraft 26.1.x exposes this through
         * MultiPlayerGameMode.handleContainerInput().
         */
        client.gameMode.handleContainerInput(
                player.inventoryMenu.containerId,
                menuSlot,
                40,
                ContainerInput.SWAP,
                player
        );


        /*
         * We DON'T claim it worked.
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

        /*
         * Nothing saved.
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
         * We only move the shield back if the Totem is actually
         * occupying the offhand.
         */
        if (!offhand.is(Items.TOTEM_OF_UNDYING)) {

            return false;
        }


        Inventory inventory =
                player.getInventory();


        /*
         * Find the saved shield in the inventory.
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
         * Swap:
         *
         * inventory shield <-> offhand Totem
         *
         * Minecraft handles the actual transaction.
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
         * First look for the exact saved stack.
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
         * If Minecraft changed the stack's data/components,
         * find any shield rather than getting stuck forever.
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

        operationCooldown = 0;

        savedShield = ItemStack.EMPTY;
    }
}
