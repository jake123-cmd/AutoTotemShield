package com.robert.autototemshield;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class AutoTotemShieldConfigScreen extends Screen {
    private final Screen parent;
    private Button enabledButton, triggerHealthButton, restockButton, returnShieldButton;
    private Button shieldRequiredButton, minimumTotemsButton, swapDelayButton, debugButton;

    public AutoTotemShieldConfigScreen(Screen parent) {
        super(Component.literal("Auto Totem Shield Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = 40;
        enabledButton = addButton(getEnabledText(), centerX, y, button -> {
            AutoTotemShieldConfig.enabled = !AutoTotemShieldConfig.enabled;
            button.setMessage(getEnabledText());
        }); y += 24;
        triggerHealthButton = addButton(getTriggerHealthText(), centerX, y, button -> {
            AutoTotemShieldConfig.triggerHearts += 0.5;
            if (AutoTotemShieldConfig.triggerHearts > 10.0) AutoTotemShieldConfig.triggerHearts = 0.5;
            button.setMessage(getTriggerHealthText());
        }); y += 24;
        restockButton = addButton(getRestockText(), centerX, y, button -> {
            AutoTotemShieldConfig.restockTotem = !AutoTotemShieldConfig.restockTotem;
            button.setMessage(getRestockText());
        }); y += 24;
        returnShieldButton = addButton(getReturnShieldText(), centerX, y, button -> {
            AutoTotemShieldConfig.returnToShield = !AutoTotemShieldConfig.returnToShield;
            button.setMessage(getReturnShieldText());
        }); y += 24;
        shieldRequiredButton = addButton(getShieldRequiredText(), centerX, y, button -> {
            AutoTotemShieldConfig.shieldRequired = !AutoTotemShieldConfig.shieldRequired;
            button.setMessage(getShieldRequiredText());
        }); y += 24;
        minimumTotemsButton = addButton(getMinimumTotemsText(), centerX, y, button -> {
            AutoTotemShieldConfig.minimumTotems++;
            if (AutoTotemShieldConfig.minimumTotems > 10) AutoTotemShieldConfig.minimumTotems = 0;
            button.setMessage(getMinimumTotemsText());
        }); y += 24;
        swapDelayButton = addButton(getSwapDelayText(), centerX, y, button -> {
            AutoTotemShieldConfig.swapDelay++;
            if (AutoTotemShieldConfig.swapDelay > 20) AutoTotemShieldConfig.swapDelay = 0;
            button.setMessage(getSwapDelayText());
        }); y += 24;
        debugButton = addButton(getDebugText(), centerX, y, button -> {
            AutoTotemShieldConfig.debugMessages = !AutoTotemShieldConfig.debugMessages;
            button.setMessage(getDebugText());
        }); y += 32;
        this.addRenderableWidget(Button.builder(Component.literal("Reset to Defaults"), button -> {
            AutoTotemShieldConfig.resetToDefaults();
            updateButtonTexts();
        }).bounds(centerX - 100, y, 200, 20).build()); y += 24;
        this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> this.minecraft.setScreen(parent))
                .bounds(centerX - 100, y, 200, 20).build());
    }

    private Button addButton(Component text, int centerX, int y, Button.OnPress action) {
        return this.addRenderableWidget(Button.builder(text, action).bounds(centerX - 100, y, 200, 20).build());
    }

    private void updateButtonTexts() {
        enabledButton.setMessage(getEnabledText());
        triggerHealthButton.setMessage(getTriggerHealthText());
        restockButton.setMessage(getRestockText());
        returnShieldButton.setMessage(getReturnShieldText());
        shieldRequiredButton.setMessage(getShieldRequiredText());
        minimumTotemsButton.setMessage(getMinimumTotemsText());
        swapDelayButton.setMessage(getSwapDelayText());
        debugButton.setMessage(getDebugText());
    }
    private Component getEnabledText() { return Component.literal("Enable Mod: " + (AutoTotemShieldConfig.enabled ? "ON" : "OFF")); }
    private Component getTriggerHealthText() { return Component.literal("Trigger Health: " + AutoTotemShieldConfig.triggerHearts + " hearts"); }
    private Component getRestockText() { return Component.literal("Restock Totem: " + (AutoTotemShieldConfig.restockTotem ? "ON" : "OFF")); }
    private Component getReturnShieldText() { return Component.literal("Return to Shield: " + (AutoTotemShieldConfig.returnToShield ? "ON" : "OFF")); }
    private Component getShieldRequiredText() { return Component.literal("Shield Required: " + (AutoTotemShieldConfig.shieldRequired ? "ON" : "OFF")); }
    private Component getMinimumTotemsText() { return Component.literal("Minimum Totems: " + AutoTotemShieldConfig.minimumTotems); }
    private Component getSwapDelayText() { return Component.literal("Swap Delay: " + AutoTotemShieldConfig.swapDelay + " ticks"); }
    private Component getDebugText() { return Component.literal("Debug Messages: " + (AutoTotemShieldConfig.debugMessages ? "ON" : "OFF")); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.text(this.font, this.title, this.width / 2, 15, 0xFFFFFF, true);
    }
}
