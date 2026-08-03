package com.github.augustistaken.avismod.mixin;

import com.github.augustistaken.avismod.CanePathMapper;
import net.minecraft.util.MovementInput;
import net.minecraft.util.MovementInputFromOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MovementInputFromOptions.class)
public class MixinMovementInputFromOptions {

    @Inject(method = "updatePlayerMoveState", at = @At("RETURN"))
    private void avismod$applyCanePathInput(CallbackInfo callbackInfo) {
        CanePathMapper.applyMovementOverride((MovementInput) (Object) this);
    }
}
