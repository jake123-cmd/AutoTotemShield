package com.robert.autototemshield;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Monster;
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
     * We intentionally require a substantial fall.
     *
     * This means:
     *
     * 1 block drop  -> NO
     * 2 block drop  -> NO
     * 3 block drop  -> NO
     * 4 block drop  -> NO
     * 5+ block fall -> YES, if still falling fast enough
     *
     * This is designed for genuinely dangerous falls rather
     * than stairs, slabs and tiny ledges.
     */

    private static final double DANGEROUS_FALL_DISTANCE = 5.0D;

    private static final double DANGEROUS_FALL_SPEED = -0.45D;


    /*
     * ============================================================
     * LAVA SAFETY
     * ============================================================
     *
     * Once the player is actually in lava, keep the Totem out.
     *
     * The Totem remains equipped even if the player heals above
     * the normal health return threshold.
     */

    private static final boolean LAVA_REQUIRES_TOTEM = true;


    /*
     * ============================================================
     * HOSTILE MOB SAFETY
     * ============================================================
     *
     * ONLY hostile Monster entities count here.
     *
     * Zombies       YES
     * Skeletons     YES
     * Creepers      YES
     * Spiders       YES
     *
     * Cows          NO
     * Sheep         NO
     * Pigs          NO
     * Villagers     NO
     *
     * The extra safety mode activates when:
     *
     * - Player is at 10 hearts or less
     * - At least 8 hostile mobs are within 8 blocks
     *
     * Once activated, the Totem stays equipped until the hostile
     * mob danger has actually gone away.
     */

    private static final double MOB_DANGER_HEALTH = 10.0D;

    private static final int MOB_DANGER_COUNT = 8;

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
     * Dangerous fall emergency.
     */
    private boolean fallEmergencyActive = false;


    /*
     * Lava emergency.
     */
    private boolean lavaEmergencyActive = false;


    /*
     * Hostile mob emergency.
     */
    private boolean mobEmergencyActive = false;


    /*
     * Original shield that was in the offhand.
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
         * No player or world.
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
         * Never manipulate inventory while a container/screen
         * is open.
         */
        if (client.screen != null) {

            return;
        }


        /*
         * ========================================================
         * COOLDOWN
         * ========================================================
         */

        if (operationCooldown > 0) {

            operationCooldown--;
        }


        /*
         * ========================================================
         * HEALTH
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
             * Swap succeeded.
             */
            if (offhand.is(Items.TOTEM_OF_UNDYING)) {

                waitingForTotemVerification = false;

                return;
            }


            /*
             * Swap failed.
             *
             * Retry after cooldown.
             */
            if (operationCooldown == 0) {

                waitingForTotemVerification = false;

                if (putTotemInOffhand(
                        client,
                        player
                )) {

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
             * Shield successfully restored.
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
             * Shield swap failed.
             */
            if (operationCooldown == 0) {

                waitingForShieldVerification = false;

                if (restoreShield(
                        client,
                        player
                )) {

                    operationCooldown =
                            RETRY_COOLDOWN_TICKS;
                }
            }

            return;
        }


        /*
         * ========================================================
         * NORMAL LOW HEALTH DETECTION
         * ========================================================
         */

        boolean lowHealth =
                health <= triggerHealth;


        /*
         * ========================================================
         * DANGEROUS FALL DETECTION
         * ========================================================
         *
         * IMPORTANT:
         *
         * fallDistance is DOUBLE in Minecraft 26.1.2.
         */

        boolean airborne =
                !player.onGround();


        double fallDistance =
                player.fallDistance;


        double verticalVelocity =
                player.getDeltaMovement().y;


        /*
         * A genuinely dangerous fall requires:
         *
         * - Player is airborne
         * - At least 5 blocks of fall distance
         * - Player is still moving downward
         */

        boolean dangerousFall =
                airborne
                        && fallDistance >= DANGEROUS_FALL_DISTANCE
                        && verticalVelocity <= DANGEROUS_FALL_SPEED;


        /*
         * ========================================================
         * LAVA DETECTION
         * ========================================================
         */

        boolean inLava =
                player.isInLava();


        boolean lavaDanger =
                LAVA_REQUIRES_TOTEM
                        && inLava;


        /*
         * ========================================================
         * HOSTILE MOB DETECTION
         * ========================================================
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
         * COMBINED EMERGENCY
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
             * Remember dangerous fall.
             */
            if (dangerousFall) {

                fallEmergencyActive = true;
            }


            /*
             * Remember lava.
             */
            if (lavaDanger) {

                lavaEmergencyActive = true;
            }


            /*
             * Remember hostile mob danger.
             */
            if (mobDanger) {

                mobEmergencyActive = true;
            }


            /*
             * Never begin restoring the shield while an emergency
             * condition is active.
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
                 * DO ABSOLUTELY NOTHING.
                 *
                 * This prevents:
                 *
                 * Totem -> Shield -> Totem
                 *
                 * flickering.
                 */
                if (offhand.is(Items.TOTEM_OF_UNDYING)) {

                    emergencyActive = true;

                    return;
                }


                /*
                 * Save original shield once.
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
                            player
                    )) {

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
                            player
                    )) {

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
         * IMPORTANT:
         *
         * Even if the player somehow heals above the configured
         * return health while falling, we do NOT restore the shield
         * until the player has landed.
         */

        if (fallEmergencyActive) {

            /*
             * Still airborne.
             */
            if (!player.onGround()) {

                /*
                 * Totem already equipped.
                 */
                if (offhand.is(Items.TOTEM_OF_UNDYING)) {

                    return;
                }


                /*
                 * Totem was consumed during the fall.
                 *
                 * Try to equip another one.
                 */
                if (operationCooldown == 0) {

                    if (putTotemInOffhand(
                            client,
                            player
                    )) {

                        operationCooldown =
                                VERIFY_DELAY_TICKS;
                    }
                }

                return;
            }


            /*
             * Player landed.
             */
            fallEmergencyActive = false;
        }


        /*
         * ========================================================
         * MAINTAIN LAVA EMERGENCY
         * ========================================================
         *
         * Healing does NOT remove the Totem while still in lava.
         */

        if (lavaEmergencyActive) {

            /*
             * Still in lava.
             */
            if (player.isInLava()) {

                /*
                 * Totem already equipped.
                 */
                if (offhand.is(Items.TOTEM_OF_UNDYING)) {

                    return;
                }


                /*
                 * Totem was consumed.
                 *
                 * Restock if enabled.
                 */
                if (AutoTotemShieldConfig.restockTotem
                        && operationCooldown == 0) {

                    if (putTotemInOffhand(
                            client,
                            player
                    )) {

                        operationCooldown =
                                VERIFY_DELAY_TICKS;
                    }
                }

                return;
            }


            /*
             * Player is out of lava.
             */
            lavaEmergencyActive = false;
        }


        /*
         * ========================================================
         * MAINTAIN HOSTILE MOB EMERGENCY
         * ========================================================
         *
         * This is the important behaviour for:
         *
         * "I'm surrounded by loads of mobs and healed above
         * 3 hearts, but I still want my Totem."
         *
         * The Totem stays equipped while:
         *
         * - Health <= 10 hearts
         * - At least 8 hostile Monsters are nearby
         *
         * Once the mob danger disappears, normal return logic
         * takes over.
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

                /*
                 * Totem already equipped.
                 */
                if (offhand.is(Items.TOTEM_OF_UNDYING)) {

                    return;
                }


                /*
                 * Totem was consumed.
                 *
                 * Restock if enabled.
                 */
                if (AutoTotemShieldConfig.restockTotem
                        && operationCooldown == 0) {

                    if (putTotemInOffhand(
                            client,
                            player
                    )) {

                        operationCooldown =
                                VERIFY_DELAY_TICKS;
                    }
                }

                return;
            }


            /*
             * Mob danger has reduced enough to leave emergency
             * mode.
             */
            mobEmergencyActive = false;
        }


        /*
         * ========================================================
         * RETURN TO SHIELD
         * ========================================================
         */

        if (emergencyActive
                && health >= returnHealth) {

            /*
             * Never restore the shield if an environmental or
             * hostile-mob emergency is still active.
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


            /*
             * Refresh offhand.
             */
            offhand =
                    player.getItemBySlot(
                            EquipmentSlot.OFFHAND
                    );


            /*
             * ====================================================
             * TOTEM STILL EQUIPPED
             * ====================================================
             */

            if (offhand.is(Items.TOTEM_OF_UNDYING)) {

                if (operationCooldown == 0) {

                    returningShield = true;

                    if (restoreShield(
                            client,
                            player
                    )) {

                        operationCooldown =
                                VERIFY_DELAY_TICKS;
                    }
                }

                return;
            }


            /*
             * ====================================================
             * TOTEM WAS CONSUMED
             * ====================================================
             *
             * If the offhand is empty, try to restock first.
             */

            if (offhand.isEmpty()) {

                if (AutoTotemShieldConfig.restockTotem
                        && operationCooldown == 0) {

                    if (putTotemInOffhand(
                            client,
                            player
                    )) {

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
                            player
                    )) {

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
             * Respect their choice.
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
     *
     * ONLY Monster entities count.
     *
     * This intentionally excludes passive/friendly entities.
     */

    private int countNearbyHostiles(Player player) {

        double radiusSquared =
                MOB_DANGER_RADIUS
                        * MOB_DANGER_RADIUS;


        int count = 0;


        /*
         * Search the area around the player for actual Monster
         * entities.
         *
         * getEntitiesOfClass is used instead of
         * entitiesForRendering(), which does not exist on the
         * 26.1.2 Level API.
         */
        for (Monster monster
                : player.level().getEntitiesOfClass(
                        Monster.class,
                        player.getBoundingBox().inflate(
                                MOB_DANGER_RADIUS
                        )
                )) {

            /*
             * Ignore dead mobs.
             */
            if (!monster.isAlive()) {

                continue;
            }


            /*
             * Extra distance check.
             */
            if (monster.distanceToSqr(player)
                    > radiusSquared) {

                continue;
            }


            /*
             * Count hostile Monster.
             */
            count++;


            /*
             * We only need to know if the threshold has been
             * reached.
             */
            if (count >= MOB_DANGER_COUNT) {

                return count;
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

        /*
         * Need a game mode.
         */
        if (client.gameMode == null) {

            return false;
        }


        /*
         * Current offhand.
         */
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


        /*
         * Convert player inventory slot to menu slot.
         */
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
         * Never assume the operation succeeded.
         *
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
         * Need a game mode.
         */
        if (client.gameMode == null) {

            return false;
        }


        /*
         * Current offhand.
         */
        ItemStack offhand =
                player.getItemBySlot(
                        EquipmentSlot.OFFHAND
                );


        /*
         * Only swap if the Totem is actually in the offhand.
         */
        if (!offhand.is(Items.TOTEM_OF_UNDYING)) {

            return false;
        }


        Inventory inventory =
                player.getInventory();


        /*
         * Find the original shield.
         */
        int shieldSlot =
                findSavedShieldSlot(
                        inventory
                );


        if (shieldSlot == -1) {

            return false;
        }


        /*
         * Convert inventory slot to menu slot.
         */
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
         * Shield <-> offhand Totem.
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


            /*
             * Exact item stack match.
             */
            if (ItemStack.matches(
                    stack,
                    savedShield
            )) {

                return slot;
            }
        }


        /*
         * Fallback:
         *
         * If the exact shield cannot be found, use any shield.
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

        lavaEmergencyActive = false;

        mobEmergencyActive = false;

        operationCooldown = 0;

        savedShield = ItemStack.EMPTY;
    }
}
