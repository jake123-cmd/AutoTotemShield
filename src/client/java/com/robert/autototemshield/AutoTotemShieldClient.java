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

    private boolean shieldWasSwapped = false;
    private int tickDelay = 0;

    private ItemStack savedShield = ItemStack.EMPTY;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {

        if (client.player == null || client.level == null) {
            return;
        }

        if (!AutoTotemShieldConfig.enabled) {
            return;
        }

        if (tickDelay > 0) {
            tickDelay--;
            return;
        }

        Player player = client.player;

        float health = player.getHealth();

        // Config uses hearts.
        // Minecraft health uses half-hearts.
        float healthThreshold =
                (float) (AutoTotemShieldConfig.triggerHearts * 2.0);

        ItemStack offhand =
                player.getItemBySlot(EquipmentSlot.OFFHAND);

        /*
         * LOW HEALTH
         */
        if (health <= healthThreshold) {

            /*
             * Only start the emergency swap if the player
             * currently has a shield in the offhand.
             */
            if (AutoTotemShieldConfig.shieldRequired) {

                if (offhand.is(Items.SHIELD) && !shieldWasSwapped) {

                    if (swapOffhandWithFirstTotem(player)) {

                        shieldWasSwapped = true;
                        tickDelay = AutoTotemShieldConfig.swapDelay;
                    }
                }

            } else {

                /*
                 * If Shield Required is disabled, the mod can
                 * place a Totem into an empty/non-totem offhand.
                 */
                if (!offhand.is(Items.TOTEM_OF_UNDYING)
                        && !shieldWasSwapped) {

                    if (putFirstTotemInOffhand(player)) {

                        shieldWasSwapped = true;
                        tickDelay = AutoTotemShieldConfig.swapDelay;
                    }
                }
            }

            /*
             * Restock the Totem if it gets consumed.
             */
            if (shieldWasSwapped
                    && AutoTotemShieldConfig.restockTotem) {

                offhand =
                        player.getItemBySlot(EquipmentSlot.OFFHAND);

                if (!offhand.is(Items.TOTEM_OF_UNDYING)) {

                    if (putFirstTotemInOffhand(player)) {
                        tickDelay = AutoTotemShieldConfig.swapDelay;
                    }
                }
            }

        }

        /*
         * SAFE AGAIN
         */
        else if (shieldWasSwapped) {

            offhand =
                    player.getItemBySlot(EquipmentSlot.OFFHAND);

            if (AutoTotemShieldConfig.returnToShield) {

                if (offhand.is(Items.TOTEM_OF_UNDYING)
                        || offhand.isEmpty()) {

                    if (putShieldBack(player)) {

                        shieldWasSwapped = false;
                        tickDelay = AutoTotemShieldConfig.swapDelay;
                    }

                } else {

                    /*
                     * Something else is in the offhand.
                     * Don't overwrite it.
                     */
                    shieldWasSwapped = false;
                }

            } else {

                /*
                 * Return-to-shield disabled.
                 */
                shieldWasSwapped = false;
            }
        }
    }

    private boolean swapOffhandWithFirstTotem(Player player) {

        Inventory inv = player.getInventory();

        for (int i = 0; i < inv.getContainerSize(); i++) {

            ItemStack stack = inv.getItem(i);

            if (stack.is(Items.TOTEM_OF_UNDYING)) {

                ItemStack shield =
                        player.getItemBySlot(
                                EquipmentSlot.OFFHAND
                        ).copy();

                ItemStack totem = stack.copy();
                totem.setCount(1);

                savedShield = shield;

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

    private boolean putFirstTotemInOffhand(Player player) {

        Inventory inv = player.getInventory();

        int totalTotems = 0;

        for (int i = 0; i < inv.getContainerSize(); i++) {

            ItemStack stack = inv.getItem(i);

            if (stack.is(Items.TOTEM_OF_UNDYING)) {
                totalTotems += stack.getCount();
            }
        }

        /*
         * Minimum Totems setting.
         *
         * Example:
         * Minimum Totems = 2
         *
         * The mod will leave at least 2 Totems in the
         * inventory before taking another one.
         */
        if (totalTotems <= AutoTotemShieldConfig.minimumTotems) {
            return false;
        }

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
         * Find an empty inventory slot for the Totem.
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
         * Inventory completely full.
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
