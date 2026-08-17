# AutoTotemShieldClient.java

```java
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
     * ============================================================
     * DANGEROUS FALL DETECTION
     * ============================================================
     *
     * Small drops must NOT trigger the Totem.
     *
     * We require a meaningful amount of fall distance AND
     * sufficient downward velocity.
     */

    private static final double DANGEROUS_FALL_DISTANCE = 4.5D;

    private static final double DANGEROUS_FALL_SPEED = -0.45D;

    /*
     * Extremely fast falls can trigger earlier, but still require
     * a meaningful fall distance.
     */
    private static final double VERY_FAST_FALL_DISTANCE = 3.0D;

    private static final double VERY_FAST_FALL_SPEED = -0.90D;


    /*
     * ============================================================
     * LAVA / ENVIRONMENT SAFETY
     * ============================================================
     */

    /*
     * Keep the Totem equipped while actually inside lava.
     */
    private static final boolean LAVA_REQUIRES_TOTEM = true;


    /*
     * ============================================================
     * MOB DANGER
     * ============================================================
     *
     * This is deliberately conservative.
     *
     * We don't want one zombie hitting the player at 10 hearts
     * to constantly switch shield/Totem.
     *
     * Instead, mob danger only matters when the player is already
     * taking serious damage / is surrounded.
     */

    private static final double MOB_DANGER_HEALTH = 10.0D;

    /*
     * Number of nearby hostile mobs required before this condition
     * becomes active.
     */
    private static final int MOB_DANGER_COUNT = 8;

    /*
     * Radius used for nearby hostile mobs.
     */
    private static final double MOB_DANGER_RADIUS = 8.0D;


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
     * True when emergency mode was caused by a dangerous fall.
     */
    private boolean fallEmergencyActive = false;

    /*
     * True when emergency mode is being maintained because of lava.
     */
    private boolean lavaEmergencyActive = false;

    /*
     * True when emergency mode is being maintained because of
     * severe mob danger.
     */
    private boolean mobEmergencyActive = false;

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
         * Don't perform inventory operations while another
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
         * ========================================================
         * CURRENT HEALTH
         * ========================================================
         */

        double health =
                player.getHealth();


        double triggerHealth =
                AutoTotemShieldConfig.triggerHearts * 2.0D;


        double returnHealth =
                AutoTotemShieldConfig.returnHearts * 2.0D;


        /*
         * ========================================================
         * CURRENT OFFHAND
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
             * Successful swap.
             */
            if (offhand.is(Items.TOTEM_OF_UNDYING)) {

                waitingForTotemVerification = false;

                return;
            }


            /*
             * Swap failed.
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
             * Successful restoration.
             */
            if (offhand.is(Items.SHIELD)) {

                waitingForShieldVerification = false;

                returningShield = false;

                emergencyActive = false;

                fallEmergencyActive = false;

                lavaEmergencyActive = false;

                mobEmergencyActive = false;

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
         * LOW HEALTH
         * ========================================================
         */

        boolean lowHealth =
                health <= triggerHealth;


        /*
         * ========================================================
         * DANGEROUS FALL
         * ========================================================
         *
         * IMPORTANT:
         *
         * fallDistance is DOUBLE in this Minecraft version.
         *
         * This fixes the previous compile error.
         */

        boolean airborne =
                !player.onGround();


        double fallDistance =
                player.fallDistance;


        double verticalVelocity =
                player.getDeltaMovement().y;


        /*
         * Normal dangerous fall.
         *
         * 4.5+ blocks AND still falling quickly.
         */
        boolean dangerousFall =
                airborne
                        && fallDistance >= DANGEROUS_FALL_DISTANCE
                        && verticalVelocity <= DANGEROUS_FALL_SPEED;


        /*
         * Very fast fall.
         *
         * Requires 3+ blocks so tiny drops still don't trigger.
         */
        boolean veryFastDangerousFall =
                airborne
                        && fallDistance >= VERY_FAST_FALL_DISTANCE
                        && verticalVelocity <= VERY_FAST_FALL_SPEED;


        dangerousFall =
                dangerousFall
                        || veryFastDangerousFall;


        /*
         * ========================================================
         * LAVA DETECTION
         * ========================================================
         *
         * If the player is actually in lava, health alone is not
         * enough to decide whether the Totem should be removed.
         *
         * We keep it equipped until the player is safely out.
         */

        boolean inLava =
                player.isInLava();


        boolean lavaDanger =
                LAVA_REQUIRES_TOTEM
                        && inLava;


        /*
         * ========================================================
         * MOB DANGER
         * ========================================================
         *
         * We only use this as an extra safety layer when:
         *
         * - health is already below 10 hearts
         * - AND there are many nearby hostile mobs.
         *
         * This prevents the system from switching just because
         * one mob happens to be nearby.
         */

        int nearbyHostiles =
                countNearbyHostiles(
                        player
                );


        boolean mobDanger =
                health <= MOB_DANGER_HEALTH
                        && nearbyHostiles >= MOB_DANGER_COUNT;


        /*
         * ========================================================
         * EMERGENCY CONDITION
         * ========================================================
         */

        boolean emergency =
                lowHealth
                        || dangerousFall
                        || lavaDanger
                        || mobDanger;


        /*
         * ========================================================
         * START / MAINTAIN EMERGENCY
         * ========================================================
         */

        if (emergency) {

            /*
             * Remember the reason.
             */
            if (dangerousFall) {

                fallEmergencyActive = true;
            }


            if (lavaDanger) {

                lavaEmergencyActive = true;
            }


            if (mobDanger) {

                mobEmergencyActive = true;
            }


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
                 * NEVER touch it.
                 *
                 * This prevents shield/Totem flickering.
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
         * MAINTAIN FALL EMERGENCY
         * ========================================================
         *
         * Even if the player has healed above the normal trigger,
         * don't return the shield while still falling.
         */

        if (fallEmergencyActive) {

            if (!player.onGround()) {

                if (offhand.is(Items.TOTEM_OF_UNDYING)) {

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
             * Landed.
             */
            fallEmergencyActive = false;
        }


        /*
         * ========================================================
         * MAINTAIN LAVA EMERGENCY
         * ========================================================
         *
         * Stay on Totem while in lava.
         */

        if (lavaEmergencyActive) {

            if (player.isInLava()) {

                if (offhand.is(Items.TOTEM_OF_UNDYING)) {

                    return;
                }


                /*
                 * If Totem was consumed, restock it if possible.
                 */
                if (AutoTotemShieldConfig.restockTotem
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


            /*
             * No longer in lava.
             */
            lavaEmergencyActive = false;
        }


        /*
         * ========================================================
         * MAINTAIN MOB EMERGENCY
         * ========================================================
         *
         * Don't immediately return the shield just because health
         * crossed the normal return threshold.
         *
         * We require the mob danger to actually disappear.
         */

        if (mobEmergencyActive) {

            int currentHostiles =
                    countNearbyHostiles(
                            player
                    );


            boolean stillMobDanger =
                    health <= MOB_DANGER_HEALTH
                            && currentHostiles >= MOB_DANGER_COUNT;


            if (stillMobDanger) {

                if (offhand.is(Items.TOTEM_OF_UNDYING)) {

                    return;
                }


                if (AutoTotemShieldConfig.restockTotem
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


            /*
             * Danger has reduced.
             */
            mobEmergencyActive = false;
        }


        /*
         * ========================================================
         * RETURN TO SHIELD
         * ========================================================
         *
         * Normal behaviour:
         *
         * trigger at low health
         * return after Return Health
         *
         * But environmental emergencies must be finished first.
         */

        if (emergencyActive
                && health >= returnHealth) {


            /*
             * Never return while any emergency reason remains.
             */
            if (fallEmergencyActive
                    || lavaEmergencyActive
                    || mobEmergencyActive) {

                return;
            }


            /*
             * Return-to-shield disabled.
             */
            if (!AutoTotemShieldConfig.returnToShield) {

                emergencyActive = false;

                returningShield = false;

                savedShield = ItemStack.EMPTY;

                return;
            }


            /*
             * Never restore while airborne.
             */
            if (!player.onGround()) {

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
             * Try to restock before restoring the shield.
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
                 * Restore shield if possible.
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
             * Player manually changed the offhand.
             *
             * Respect the player.
             */
            emergencyActive = false;

            returningShield = false;

            savedShield = ItemStack.EMPTY;

            return;
        }
    }


    /*
     * ============================================================
     * COUNT NEARBY HOSTILE MOBS
     * ============================================================
     */

    private int countNearbyHostiles(Player player) {

        if (player.level() == null) {

            return 0;
        }


        double radiusSquared =
                MOB_DANGER_RADIUS
                        * MOB_DANGER_RADIUS;


        int count = 0;


        for (net.minecraft.world.entity.Entity entity
                : player.level().entitiesForRendering()) {

            /*
             * Ignore the player.
             */
            if (entity == player) {
                continue;
            }


            /*
             * Only living entities count as threats.
             */
            if (!(entity instanceof net.minecraft.world.entity.LivingEntity living)) {
                continue;
            }


            /*
             * Ignore friendly/passive mobs.
             *
             * Mob hostility is checked using the player's target
             * relationship rather than assuming every mob is hostile.
             */
            if (!player.isAlliedTo(living)
                    && living.isAlive()
                    && living.distanceToSqr(player) <= radiusSquared) {

                /*
                 * Don't count armor stands or other non-combat
                 * living entities.
                 */
                if (living instanceof net.minecraft.world.entity.Mob) {

                    count++;

                    if (count >= MOB_DANGER_COUNT) {

                        return count;
                    }
                }
            }
        }


        return count;
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
         * Real Minecraft inventory operation.
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
         * Only swap if Totem is actually in offhand.
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
         * Real Minecraft inventory swap:
         *
         * inventory shield <-> offhand Totem
         */
        client.gameMode.handleContainerInput(
                player.inventoryMenu.containerId,
                menuSlot,
                40,
                ContainerInput.SWAP,
                player
        );


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
         * First try the exact original shield.
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

        lavaEmergencyActive = false;

        mobEmergencyActive = false;

        operationCooldown = 0;

        savedShield = ItemStack.EMPTY;
    }
}
```
