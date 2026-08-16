package com.robert.autototemshield;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class AutoTotemShieldClient implements ClientModInitializer {
    private static final float HEALTH_THRESHOLD = 6.0f; // 3 hearts
    private static final int SWAP_DELAY_TICKS = 0;

    private boolean shieldWasSwapped = false;
    private int tickDelay = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        if (client.player == null || client.level == null) return;
        if (tickDelay > 0) {
            tickDelay--;
            return;
        }

        float health = client.player.getHealth();
        ItemStack offhand = client.player.getItemBySlot(EquipmentSlot.OFFHAND);

        if (health <= HEALTH_THRESHOLD) {
            // At 3 hearts or below: if a shield is equipped, replace it with a totem.
            if (offhand.is(Items.SHIELD) && !shieldWasSwapped) {
                if (swapOffhandWithFirstTotem(client.player)) {
                    shieldWasSwapped = true;
                    tickDelay = SWAP_DELAY_TICKS;
                }
            }

            // If our totem was consumed, replenish it while still dangerous.
            offhand = client.player.getItemBySlot(EquipmentSlot.OFFHAND);
            if (shieldWasSwapped && !offhand.is(Items.TOTEM_OF_UNDYING)) {
                if (putFirstTotemInOffhand(client.player)) {
                    tickDelay = SWAP_DELAY_TICKS;
                }
            }
        } else if (shieldWasSwapped) {
            // Safe again: restore the original shield if a totem is currently in hand.
            offhand = client.player.getItemBySlot(EquipmentSlot.OFFHAND);
            if (offhand.is(Items.TOTEM_OF_UNDYING) || offhand.isEmpty()) {
                if (putShieldBack(client.player)) {
                    shieldWasSwapped = false;
                    tickDelay = SWAP_DELAY_TICKS;
                }
            } else {
                // Something else is in the offhand; don't overwrite the player's choice.
                shieldWasSwapped = false;
            }
        }
    }

    private ItemStack savedShield = ItemStack.EMPTY;

    private boolean swapOffhandWithFirstTotem(net.minecraft.world.entity.player.Player player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(Items.TOTEM_OF_UNDYING)) {
                ItemStack shield = player.getItemBySlot(EquipmentSlot.OFFHAND).copy();
                ItemStack totem = stack.copy();
                totem.setCount(1);

                // Remember the exact shield (including durability) while it is in the offhand.
                savedShield = shield;
                stack.shrink(1);
                player.setItemSlot(EquipmentSlot.OFFHAND, totem);

                return true;
            }
        }
        return false;
    }

    private boolean putFirstTotemInOffhand(net.minecraft.world.entity.player.Player player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(Items.TOTEM_OF_UNDYING)) {
                ItemStack totem = stack.copy();
                totem.setCount(1);
                stack.shrink(1);
                player.setItemSlot(EquipmentSlot.OFFHAND, totem);
                return true;
            }
        }
        return false;
    }

    private boolean putShieldBack(net.minecraft.world.entity.player.Player player) {
        if (savedShield.isEmpty()) return false;

        Inventory inv = player.getInventory();
        ItemStack currentOffhand = player.getItemBySlot(EquipmentSlot.OFFHAND);

        // Put the current Totem away first.
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) {
                inv.setItem(i, currentOffhand);
                player.setItemSlot(EquipmentSlot.OFFHAND, savedShield);
                savedShield = ItemStack.EMPTY;
                return true;
            }
        }

        // If inventory is full, swap with the selected hotbar slot.
        int selected = inv.getSelectedSlot();
        ItemStack main = inv.getItem(selected);
        inv.setItem(selected, currentOffhand);
        player.setItemSlot(EquipmentSlot.OFFHAND, savedShield);
        savedShield = ItemStack.EMPTY;
        return true;
    }
}
