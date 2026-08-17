package com.robert.autototemshield;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class AutoTotemShieldConfigScreen extends Screen {

    private final Screen parent;

    private Button enabledButton;
    private Button triggerHealthButton;
    private Button returnHealthButton;
    private Button restockButton;
    private Button returnShieldButton;
    private Button shieldRequiredButton;
    private Button minimumTotemsButton;
    private Button swapDelayButton;
    private Button debugButton;

    public AutoTotemShieldConfigScreen(Screen parent) {
        super(Component.literal("Auto Totem Shield Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {

        int centerX = this.width / 2;
        int y = 40;

        /*
         * ========================================================
         * ENABLE MOD
         * ========================================================
         */
        enabledButton = addButton(
                getEnabledText(),
                centerX,
                y,
                button -> {

                    AutoTotemShieldConfig.enabled =
                            !AutoTotemShieldConfig.enabled;

                    button.setMessage(
                            getEnabledText()
                    );
                }
        );

        y += 24;


        /*
         * ========================================================
         * TRIGGER HEALTH
         * ========================================================
         *
         * Default: 3 hearts
         *
         * When health reaches this amount:
         *
         * Shield -> Totem
         */
        triggerHealthButton = addButton(
                getTriggerHealthText(),
                centerX,
                y,
                button -> {

                    AutoTotemShieldConfig.triggerHearts += 0.5;

                    if (
                            AutoTotemShieldConfig.triggerHearts
                                    > 10.0
                    ) {

                        AutoTotemShieldConfig.triggerHearts =
                                0.5;
                    }

                    /*
                     * Make sure Return Health never ends up
                     * below Trigger Health.
                     */
                    if (
                            AutoTotemShieldConfig.returnHearts
                                    < AutoTotemShieldConfig.triggerHearts
                    ) {

                        AutoTotemShieldConfig.returnHearts =
                                AutoTotemShieldConfig.triggerHearts;
                    }

                    updateButtonTexts();
                }
        );

        y += 24;


        /*
         * ========================================================
         * RETURN HEALTH
         * ========================================================
         *
         * Default: 6 hearts
         *
         * The shield will NOT return until health reaches
         * this amount.
         */
        returnHealthButton = addButton(
                getReturnHealthText(),
                centerX,
                y,
                button -> {

                    AutoTotemShieldConfig.returnHearts += 0.5;

                    if (
                            AutoTotemShieldConfig.returnHearts
                                    > 20.0
                    ) {

                        AutoTotemShieldConfig.returnHearts =
                                AutoTotemShieldConfig.triggerHearts;
                    }

                    /*
                     * Return Health can never be below
                     * Trigger Health.
                     */
                    if (
                            AutoTotemShieldConfig.returnHearts
                                    < AutoTotemShieldConfig.triggerHearts
                    ) {

                        AutoTotemShieldConfig.returnHearts =
                                AutoTotemShieldConfig.triggerHearts;
                    }

                    updateButtonTexts();
                }
        );

        y += 24;


        /*
         * ========================================================
         * RESTOCK TOTEM
         * ========================================================
         */
        restockButton = addButton(
                getRestockText(),
                centerX,
                y,
                button -> {

                    AutoTotemShieldConfig.restockTotem =
                            !AutoTotemShieldConfig.restockTotem;

                    button.setMessage(
                            getRestockText()
                    );
                }
        );

        y += 24;


        /*
         * ========================================================
         * RETURN TO SHIELD
         * ========================================================
         */
        returnShieldButton = addButton(
                getReturnShieldText(),
                centerX,
                y,
                button -> {

                    AutoTotemShieldConfig.returnToShield =
                            !AutoTotemShieldConfig.returnToShield;

                    button.setMessage(
                            getReturnShieldText()
                    );
                }
        );

        y += 24;


        /*
         * ========================================================
         * SHIELD REQUIRED
         * ========================================================
         */
        shieldRequiredButton = addButton(
                getShieldRequiredText(),
                centerX,
                y,
                button -> {

                    AutoTotemShieldConfig.shieldRequired =
                            !AutoTotemShieldConfig.shieldRequired;

                    button.setMessage(
                            getShieldRequiredText()
                    );
                }
        );

        y += 24;


        /*
         * ========================================================
         * MINIMUM TOTEMS
         * ========================================================
         *
         * This setting is retained so your existing config/UI
         * stays compatible.
         *
         * IMPORTANT:
         *
         * It NO LONGER prevents emergency Totem use.
         *
         * If you have one Totem, the mod can still use it.
         */
        minimumTotemsButton = addButton(
                getMinimumTotemsText(),
                centerX,
                y,
                button -> {

                    AutoTotemShieldConfig.minimumTotems++;

                    if (
                            AutoTotemShieldConfig.minimumTotems
                                    > 10
                    ) {

                        AutoTotemShieldConfig.minimumTotems =
                                0;
                    }

                    button.setMessage(
                            getMinimumTotemsText()
                    );
                }
        );

        y += 24;


        /*
         * ========================================================
         * SWAP DELAY
         * ========================================================
         */
        swapDelayButton = addButton(
                getSwapDelayText(),
                centerX,
                y,
                button -> {

                    AutoTotemShieldConfig.swapDelay++;

                    if (
                            AutoTotemShieldConfig.swapDelay
                                    > 20
                    ) {

                        AutoTotemShieldConfig.swapDelay =
                                0;
                    }

                    button.setMessage(
                            getSwapDelayText()
                    );
                }
        );

        y += 24;


        /*
         * ========================================================
         * DEBUG
         * ========================================================
         */
        debugButton = addButton(
                getDebugText(),
                centerX,
                y,
                button -> {

                    AutoTotemShieldConfig.debugMessages =
                            !AutoTotemShieldConfig.debugMessages;

                    button.setMessage(
                            getDebugText()
                    );
                }
        );

        y += 32;


        /*
         * ========================================================
         * RESET
         * ========================================================
         */
        this.addRenderableWidget(
                Button.builder(
                        Component.literal("Reset to Defaults"),
                        button -> {

                            AutoTotemShieldConfig
                                    .resetToDefaults();

                            updateButtonTexts();
                        }
                )
                .bounds(
                        centerX - 100,
                        y,
                        200,
                        20
                )
                .build()
        );

        y += 24;


        /*
         * ========================================================
         * DONE
         * ========================================================
         */
        this.addRenderableWidget(
                Button.builder(
                        Component.literal("Done"),
                        button ->
                                this.minecraft.setScreen(parent)
                )
                .bounds(
                        centerX - 100,
                        y,
                        200,
                        20
                )
                .build()
        );
    }


    /*
     * ============================================================
     * CREATE BUTTON
     * ============================================================
     */
    private Button addButton(
            Component text,
            int centerX,
            int y,
            Button.OnPress action
    ) {

        return this.addRenderableWidget(
                Button.builder(
                        text,
                        action
                )
                .bounds(
                        centerX - 100,
                        y,
                        200,
                        20
                )
                .build()
        );
    }


    /*
     * ============================================================
     * UPDATE ALL BUTTON TEXT
     * ============================================================
     */
    private void updateButtonTexts() {

        enabledButton.setMessage(
                getEnabledText()
        );

        triggerHealthButton.setMessage(
                getTriggerHealthText()
        );

        returnHealthButton.setMessage(
                getReturnHealthText()
        );

        restockButton.setMessage(
                getRestockText()
        );

        returnShieldButton.setMessage(
                getReturnShieldText()
        );

        shieldRequiredButton.setMessage(
                getShieldRequiredText()
        );

        minimumTotemsButton.setMessage(
                getMinimumTotemsText()
        );

        swapDelayButton.setMessage(
                getSwapDelayText()
        );

        debugButton.setMessage(
                getDebugText()
        );
    }


    /*
     * ============================================================
     * BUTTON TEXT
     * ============================================================
     */

    private Component getEnabledText() {

        return Component.literal(
                "Enable Mod: "
                        + (
                        AutoTotemShieldConfig.enabled
                                ? "ON"
                                : "OFF"
                )
        );
    }


    private Component getTriggerHealthText() {

        return Component.literal(
                "Trigger Health: "
                        + AutoTotemShieldConfig.triggerHearts
                        + " hearts"
        );
    }


    private Component getReturnHealthText() {

        return Component.literal(
                "Return Health: "
                        + AutoTotemShieldConfig.returnHearts
                        + " hearts"
        );
    }


    private Component getRestockText() {

        return Component.literal(
                "Restock Totem: "
                        + (
                        AutoTotemShieldConfig.restockTotem
                                ? "ON"
                                : "OFF"
                )
        );
    }


    private Component getReturnShieldText() {

        return Component.literal(
                "Return to Shield: "
                        + (
                        AutoTotemShieldConfig.returnToShield
                                ? "ON"
                                : "OFF"
                )
        );
    }


    private Component getShieldRequiredText() {

        return Component.literal(
                "Shield Required: "
                        + (
                        AutoTotemShieldConfig.shieldRequired
                                ? "ON"
                                : "OFF"
                )
        );
    }


    private Component getMinimumTotemsText() {

        return Component.literal(
                "Minimum Totems: "
                        + AutoTotemShieldConfig.minimumTotems
        );
    }


    private Component getSwapDelayText() {

        return Component.literal(
                "Swap Delay: "
                        + AutoTotemShieldConfig.swapDelay
                        + " ticks"
        );
    }


    private Component getDebugText() {

        return Component.literal(
                "Debug Messages: "
                        + (
                        AutoTotemShieldConfig.debugMessages
                                ? "ON"
                                : "OFF"
                )
        );
    }


    /*
     * ============================================================
     * RENDER
     * ============================================================
     */
    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {

        super.extractRenderState(
                graphics,
                mouseX,
                mouseY,
                partialTick
        );

        graphics.text(
                this.font,
                this.title,
                this.width / 2,
                15,
                0xFFFFFF,
                true
        );
    }
}
