package com.robert.autototemshield;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class AutoTotemShieldClient implements ClientModInitializer {

    /*
     * ============================================================
     * AUTO TOTEM SHIELD - FAILSAFE EDITION
     * Minecraft 26.1.2
     * ============================================================
     *
     * Core logic:
     *
     *   Shield normally equipped
     *          ↓
     *   Health <= Trigger Health
     *          ↓
     *   Emergency Totem mode
     *          ↓
     *   Keep Totem equipped while unsafe
     *          ↓
     *   Totem consumed?
     *          ↓
     *   Immediately find another
     *          ↓
     *   Health >= Return Health
     *          ↓
     *   Restore original shield
     *
     * The damage source does NOT matter.
     *
     * Fall damage       -> health check
     * Mob damage        -> health check
     * Player damage     -> health check
     * Lava              -> health check
     * Fire              -> health check
     * Drowning          -> health check
     * Explosion         -> health check
     * Projectile        -> health check
     * Poison            -> health check
     * Wither            -> health check
     * Suffocation       -> health check
     * etc.
     *
     * Everything ultimately goes through the same emergency
     * health system.
     */


    /*
     * ============================================================
     * TIMING
     * ============================================================
     *
     * We intentionally do NOT wait before the first emergency
     * attempt.
     *
     * This is only used after a successful inventory operation
     * so Minecraft gets a tick to settle the change.
     */
    private static final int POST_OPERATION_DELAY = 1;


    /*
     * ============================================================
     * EMERGENCY STATE
     * ============================================================
     */

    private boolean emergencyMode = false;

    /*
     * True only when we actually saved a shield.
     */
    private boolean shieldSaved = false;

    /*
     * Exact shield, including durability/components.
     */
    private ItemStack savedShield = ItemStack.EMPTY;

    /*
     * Prevents us from repeatedly trying to restore the shield
     * when the inventory is completely full.
     */
    private int operationDelay = 0;


    /*
     * ============================================================
     * INITIALIZATION
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
         * No world/player.
         */
        if (client.player == null || client.level == null) {
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
         * Never manipulate inventory after health has reached zero.
         */
        if (player.getHealth() <= 0.0F) {
            emergencyMode = false;
            return;
        }

        /*
         * ========================================================
         * HEALTH VALUES
         * ========================================================
         *
         * Config uses hearts.
         *
         * Minecraft health uses half-hearts.
         *
         * 3 hearts = 6 health
         * 6 hearts = 12 health
         */

        float triggerHealth =
                (float) (
                        AutoTotemShieldConfig.triggerHearts * 2.0
                );

        float returnHealth =
                (float) (
                        AutoTotemShieldConfig.returnHearts * 2.0
                );


        /*
         * Safety:
         *
         * Return Health can NEVER be below Trigger Health.
         */
        if (returnHealth < triggerHealth) {
            returnHealth = triggerHealth;
        }


        float health = player.getHealth();


        /*
         * ========================================================
         * EMERGENCY TRIGGER
         * ========================================================
         *
         * This check happens BEFORE the normal operation delay.
         *
         * If we are dying, emergency protection gets priority.
         */

        if (health <= triggerHealth) {

            emergencyMode = true;

            emergencyTick(player);

            return;
        }


        /*
         * ========================================================
         * EMERGENCY RECOVERY
         * ========================================================
         */

        if (emergencyMode) {

            /*
             * We haven't reached safe health yet.
             *
             * Keep Totem protection active.
             */
            if (health < returnHealth) {

                emergencyTick(player);

                return;
            }


            /*
             * We are finally healthy enough to restore
             * the shield.
             */
            if (health >= returnHealth) {

                if (operationDelay > 0) {

                    operationDelay--;

                    return;
                }

                restoreShield(player);

                return;
            }
        }


        /*
         * ========================================================
         * NORMAL OPERATION DELAY
         * ========================================================
         */

        if (operationDelay > 0) {
            operationDelay--;
        }
    }


    /*
     * ============================================================
     * EMERGENCY TICK
     * ============================================================
     */

    private void emergencyTick(Player player) {

        ItemStack offhand =
                player.getItemBySlot(
                        EquipmentSlot.OFFHAND
                );


        /*
         * ========================================================
         * ALREADY HAVE TOTEM
         * ========================================================
         *
         * Perfect.
         *
         * Do nothing.
         */
        if (offhand.is(Items.TOTEM_OF_UNDYING)) {
            return;
        }


        /*
         * ========================================================
         * SHIELD REQUIRED
         * ========================================================
         */

        if (AutoTotemShieldConfig.shieldRequired) {

            /*
             * If the player manually removed the shield before
             * emergency mode started, respect that.
             */
            if (!offhand.is(Items.SHIELD)) {

                /*
                 * If we previously saved a shield, we're already
                 * in an emergency sequence, so don't cancel it.
                 */
                if (!shieldSaved) {
                    return;
                }
            }
        }


        /*
         * ========================================================
         * SAVE SHIELD
         * ========================================================
         *
         * Save exactly once.
         */

        if (!shieldSaved && offhand.is(Items.SHIELD)) {

            savedShield = offhand.copy();

            shieldSaved = true;
        }


        /*
         * ========================================================
         * GET TOTEM
         * ========================================================
         *
         * IMPORTANT:
         *
         * minimumTotems is deliberately NOT checked.
         *
         * If you have:
         *
         * 1 Totem -> use it
         * 2 Totems -> use one
         * 20 Totems -> use one
         */

        if (putTotemInOffhand(player)) {

            operationDelay =
                    POST_OPERATION_DELAY;

            return;
        }


        /*
         * No Totem found.
         *
         * Stay in emergency mode.
         *
         * The method will search again on the next tick.
         */
    }


    /*
     * ============================================================
     * FIND + EQUIP TOTEM
     * ============================================================
     *
     * IMPORTANT:
     *
     * We verify the result.
     *
     * We also remember the source slot so that if something
     * unexpected happens and the offhand doesn't become a Totem,
     * the removed Totem is put back.
     */

    private boolean putTotemInOffhand(Player player) {

        Inventory inventory =
                player.getInventory();


        /*
         * Never replace a Totem that is already there.
         */
        ItemStack currentOffhand =
                player.getItemBySlot(
                        EquipmentSlot.OFFHAND
                );

        if (currentOffhand.is(Items.TOTEM_OF_UNDYING)) {
            return true;
        }


        /*
         * Search the complete inventory.
         */
        for (
                int slot = 0;
                slot < inventory.getContainerSize();
                slot++
        ) {

            ItemStack stack =
                    inventory.getItem(slot);


            /*
             * Not a Totem.
             */
            if (!stack.is(Items.TOTEM_OF_UNDYING)) {
                continue;
            }


            /*
             * Empty for safety.
             */
            if (stack.isEmpty()) {
                continue;
            }


            /*
             * Take exactly ONE.
             */
            ItemStack totem =
                    stack.split(1);


            /*
             * Put it into the offhand.
             */
            player.setItemSlot(
                    EquipmentSlot.OFFHAND,
                    totem
            );


            /*
             * ====================================================
             * VERIFY
             * ====================================================
             */

            ItemStack resultingOffhand =
                    player.getItemBySlot(
                            EquipmentSlot.OFFHAND
                    );


            if (resultingOffhand.is(
                    Items.TOTEM_OF_UNDYING
            )) {

                return true;
            }


            /*
             * ====================================================
             * FAILED OPERATION
             * ====================================================
             *
             * The Totem was removed from the source stack but
             * didn't appear in the offhand.
             *
             * Put it back immediately.
             */

            ItemStack source =
                    inventory.getItem(slot);

            if (
                    source.isEmpty()
                            ||
                    source.is(Items.TOTEM_OF_UNDYING)
            ) {

                if (source.isEmpty()) {

                    inventory.setItem(
                            slot,
                            totem
                    );

                } else {

                    /*
                     * Try to merge it back.
                     */
                    source.grow(
                            totem.getCount()
                    );
                }

                return false;
            }


            /*
             * If the source slot unexpectedly changed,
             * search for an empty slot rather than destroying
             * the Totem.
             */
            for (
                    int backup = 0;
                    backup < inventory.getContainerSize();
                    backup++
            ) {

                if (inventory.getItem(backup).isEmpty()) {

                    inventory.setItem(
                            backup,
                            totem
                    );

                    return false;
                }
            }


            /*
             * No safe destination.
             *
             * Keep the emergency state active and retry.
             */
            return false;
        }


        /*
         * No Totem anywhere in inventory.
         */
        return false;
    }


    /*
     * ============================================================
     * RESTORE SHIELD
     * ============================================================
     */

    private void restoreShield(Player player) {

        /*
         * Return-to-shield disabled.
         */
        if (!AutoTotemShieldConfig.returnToShield) {

            clearEmergencyState();

            return;
        }


        /*
         * Nothing saved.
         */
        if (!shieldSaved || savedShield.isEmpty()) {

            clearEmergencyState();

            return;
        }


        ItemStack offhand =
                player.getItemBySlot(
                        EquipmentSlot.OFFHAND
                );


        /*
         * ========================================================
         * PLAYER MANUALLY CHANGED OFFHAND
         * ========================================================
         *
         * Never overwrite a deliberate player choice.
         */
        if (
                !offhand.isEmpty()
                        &&
                !offhand.is(Items.TOTEM_OF_UNDYING)
        ) {

            clearEmergencyState();

            return;
        }


        /*
         * ========================================================
         * OFFHAND IS EMPTY
         * ========================================================
         */

        if (offhand.isEmpty()) {

            player.setItemSlot(
                    EquipmentSlot.OFFHAND,
                    savedShield
            );


            /*
             * Verify.
             */
            ItemStack verify =
                    player.getItemBySlot(
                            EquipmentSlot.OFFHAND
                    );


            if (verify.is(Items.SHIELD)) {

                finishRestore();
            }

            return;
        }


        /*
         * ========================================================
         * OFFHAND HAS TOTEM
         * ========================================================
         */

        if (offhand.is(Items.TOTEM_OF_UNDYING)) {

            Inventory inventory =
                    player.getInventory();


            /*
             * Find an empty slot for the Totem.
             */
            for (
                    int slot = 0;
                    slot < inventory.getContainerSize();
                    slot++
            ) {

                if (inventory.getItem(slot).isEmpty()) {

                    /*
                     * Move Totem into the empty slot.
                     */
                    inventory.setItem(
                            slot,
                            offhand
                    );


                    /*
                     * Restore shield.
                     */
                    player.setItemSlot(
                            EquipmentSlot.OFFHAND,
                            savedShield
                    );


                    /*
                     * Verify shield actually returned.
                     */
                    ItemStack verify =
                            player.getItemBySlot(
                                    EquipmentSlot.OFFHAND
                            );


                    if (verify.is(Items.SHIELD)) {

                        finishRestore();
                    }

                    return;
                }
            }


            /*
             * ====================================================
             * INVENTORY FULL
             * ====================================================
             *
             * IMPORTANT:
             *
             * We DO NOT overwrite the selected hotbar slot.
             *
             * The previous version could destroy/replace the
             * player's selected item here.
             *
             * Instead:
             *
             *   Totem stays safely in offhand.
             *   Shield stays saved.
             *   We wait for an inventory slot to become available.
             *
             * Next tick we'll try again.
             */
            return;
        }
    }


    /*
     * ============================================================
     * FINISH RESTORE
     * ============================================================
     */

    private void finishRestore() {

        emergencyMode = false;

        shieldSaved = false;

        savedShield = ItemStack.EMPTY;

        operationDelay =
                POST_OPERATION_DELAY;
    }


    /*
     * ============================================================
     * CLEAR STATE
     * ============================================================
     */

    private void clearEmergencyState() {

        emergencyMode = false;

        shieldSaved = false;

        savedShield = ItemStack.EMPTY;

        operationDelay = 0;
    }
}
