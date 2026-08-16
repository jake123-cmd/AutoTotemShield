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
     * 1 Minecraft tick = 50ms.
     *
     * This gives Minecraft one tick to finish an inventory
     * change without adding a noticeable delay.
     */
    private static final int SAFETY_DELAY_TICKS = 1;

    private boolean shieldWasSwapped = false;
    private int tickDelay = 0;

    /*
     * The exact shield that was in the offhand before
     * the emergency Totem swap.
     */
    private ItemStack savedShield = ItemStack.EMPTY;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {

        if (client.player == null || client.level == null) {
            return;
        }

        /*
         * Mod disabled.
         */
        if (!AutoTotemShieldConfig.enabled) {
            return;
        }

        /*
         * Small safety delay after an inventory operation.
         */
        if (tickDelay > 0) {
            tickDelay--;
            return;
        }

        Player player = client.player;

        float health = player.getHealth();

        /*
         * Config uses hearts.
         *
         * Minecraft uses half-hearts.
         *
         * 3 hearts = 6 health.
         */
        float healthThreshold =
                (float) (AutoTotemShieldConfig.triggerHearts * 2.0);

        ItemStack offhand =
                player.getItemBySlot(EquipmentSlot.OFFHAND);

        /*
         * =========================================================
         * LOW HEALTH
         * =========================================================
         */
        if (health <= healthThreshold) {

            /*
             * We have not started the emergency Totem state yet.
             */
            if (!shieldWasSwapped) {

                /*
                 * Shield Required ON:
                 *
                 * Only activate when a shield is in the offhand.
                 */
                if (AutoTotemShieldConfig.shieldRequired) {

                    if (offhand.is(Items.SHIELD)) {

                        if (swapShieldForTotem(player)) {

                            shieldWasSwapped = true;
                            tickDelay = SAFETY_DELAY_TICKS;
                        }
                    }
                }

                /*
                 * Shield Required OFF:
                 *
                 * Put a Totem into the offhand even if there
                 * isn't currently a shield there.
                 */
                else {

                    if (!offhand.is(Items.TOTEM_OF_UNDYING)) {

                        if (putFirstTotemInOffhand(player)) {

                            shieldWasSwapped = true;
                            tickDelay = SAFETY_DELAY_TICKS;
                        }
                    }
                }
            }

            /*
             * =====================================================
             * RESTOCK
             * =====================================================
             *
             * If our Totem was consumed while we're still below
             * the health threshold, immediately search for another.
             */
            if (shieldWasSwapped
                    && AutoTotemShieldConfig.restockTotem) {

                offhand =
                        player.getItemBySlot(EquipmentSlot.OFFHAND);

                /*
                 * The Totem has disappeared.
                 *
                 * Only restock when the offhand is EMPTY.
                 *
                 * This prevents the mod from overwriting something
                 * the player deliberately put there.
                 */
                if (offhand.isEmpty()) {

                    if (putFirstTotemInOffhand(player)) {
                        tickDelay = SAFETY_DELAY_TICKS;
                    }
                }
            }

            return;
        }

        /*
         * =========================================================
         * HEALTH SAFE AGAIN
         * =========================================================
         */
        if (shieldWasSwapped) {

            offhand =
                    player.getItemBySlot(EquipmentSlot.OFFHAND);

            /*
             * Return the original shield if enabled.
             */
            if (AutoTotemShieldConfig.returnToShield) {

                /*
                 * Only restore if the offhand is still occupied
                 * by our Totem or is empty.
                 */
                if (offhand.is(Items.TOTEM_OF_UNDYING)
                        || offhand.isEmpty()) {

                    if (putShieldBack(player)) {

                        shieldWasSwapped = false;
                        tickDelay = SAFETY_DELAY_TICKS;
                    }
                }

                else {

                    /*
                     * Player manually changed the offhand.
                     * Respect their choice.
                     */
                    shieldWasSwapped = false;
                    savedShield = ItemStack.EMPTY;
                }
            }

            /*
             * Return-to-shield disabled.
             */
            else {
                shieldWasSwapped = false;
            }
        }
    }

    /*
     * =============================================================
     * SHIELD -> TOTEM
     * =============================================================
     */
    private boolean swapShieldForTotem(Player player) {

        Inventory inv = player.getInventory();

        for (int i = 0; i < inv.getContainerSize(); i++) {

            ItemStack stack = inv.getItem(i);

            if (stack.is(Items.TOTEM_OF_UNDYING)) {

                /*
                 * Save the exact shield, including durability.
                 */
                savedShield =
                        player.getItemBySlot(
                                EquipmentSlot.OFFHAND
                        ).copy();

                /*
                 * Take exactly one Totem.
                 */
                ItemStack totem = stack.copy();
                totem.setCount(1);

                stack.shrink(1);

                player.setItemSlot(
                        EquipmentSlot.OFFHAND,
                        totem
                );

                return true;
            }
        }

        return false;
    }

    /*
     * =============================================================
     * FIND TOTEM
     * =============================================================
     */
    private boolean putFirstTotemInOffhand(Player player) {

        Inventory inv = player.getInventory();

        int totalTotems = 0;

        /*
         * Count all Totems in the inventory.
         */
        for (int i = 0; i < inv.getContainerSize(); i++) {

            ItemStack stack = inv.getItem(i);

            if (stack.is(Items.TOTEM_OF_UNDYING)) {
                totalTotems += stack.getCount();
            }
        }

        /*
         * Respect Minimum Totems.
         *
         * Minimum Totems = 0:
         * Take a Totem whenever one is available.
         *
         * Minimum Totems = 2:
         * Leave at least 2 Totems in the inventory.
         */
        if (totalTotems <= AutoTotemShieldConfig.minimumTotems) {
            return false;
        }

        /*
         * Find the first Totem.
         */
        for (int i = 0; i < inv.getContainerSize(); i++) {

            ItemStack stack = inv.getItem(i);

            if (stack.is(Items.TOTEM_OF_UNDYING)) {

                ItemStack totem = stack.copy();
                totem.setCount(1);

                stack.shrink(1);

                player.setItemSlot(
                        EquipmentSlot.OFFHAND,
                        totem
                );

                return true;
            }
        }

        return false;
    }

    /*
     * =============================================================
     * TOTEM -> SHIELD
     * =============================================================
     */
    private boolean putShieldBack(Player player) {

        if (savedShield.isEmpty()) {
            return false;
        }

        Inventory inv = player.getInventory();

        ItemStack currentOffhand =
                player.getItemBySlot(
                        EquipmentSlot.OFFHAND
                );

        /*
         * First try to put the current Totem into an empty slot.
         */
        for (int i = 0; i < inv.getContainerSize(); i++) {

            if (inv.getItem(i).isEmpty()) {

                inv.setItem(i, currentOffhand);

                player.setItemSlot(
                        EquipmentSlot.OFFHAND,
                        savedShield
                );

                savedShield = ItemStack.EMPTY;

                return true;
            }
        }

        /*
         * Inventory is completely full.
         *
         * Swap the Totem with the selected hotbar slot.
         */
        int selected = inv.getSelectedSlot();

        ItemStack main = inv.getItem(selected);

        inv.setItem(selected, currentOffhand);

        player.setItemSlot(
                EquipmentSlot.OFFHAND,
                savedShield
        );

        savedShield = ItemStack.EMPTY;

        return true;
    }
}
