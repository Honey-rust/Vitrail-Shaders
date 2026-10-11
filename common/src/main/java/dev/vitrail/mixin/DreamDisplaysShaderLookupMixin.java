package dev.vitrail.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cache missing optional backends, never the live shader-enabled state. */
@Pseudo
@Mixin(targets = "com.dreamdisplays.platform.client.render.ShaderPackCompat", remap = false)
public abstract class DreamDisplaysShaderLookupMixin {
    @Unique private volatile int vitrail$irisPresence;
    @Unique private volatile int vitrail$optifinePresence;
    @Unique private volatile int vitrail$canvasPresence;

    @Unique
    private int vitrail$presence(String name) {
        try {
            Class.forName(name, false, this.getClass().getClassLoader());
            return 1;
        } catch (ClassNotFoundException absent) {
            return -1;
        } catch (LinkageError unavailable) {
            // Let the original compatibility code handle present but broken backends.
            return 1;
        }
    }

    @Inject(method = "irisShaderPackActive", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void vitrail$skipMissingIris(CallbackInfoReturnable<Boolean> result) {
        int presence = this.vitrail$irisPresence;
        if (presence == 0) this.vitrail$irisPresence = presence =
                vitrail$presence("net.irisshaders.iris.api.v0.IrisApi");
        if (presence < 0) result.setReturnValue(false);
    }

    @Inject(method = "optifineShaderPackActive", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void vitrail$skipMissingOptifine(CallbackInfoReturnable<Boolean> result) {
        int presence = this.vitrail$optifinePresence;
        if (presence == 0) this.vitrail$optifinePresence = presence =
                vitrail$presence("net.optifine.Config");
        if (presence < 0) result.setReturnValue(false);
    }

    @Inject(method = "canvasRendererActive", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void vitrail$skipMissingCanvas(CallbackInfoReturnable<Boolean> result) {
        int presence = this.vitrail$canvasPresence;
        if (presence == 0) {
            presence = vitrail$presence("grondag.canvas.CanvasMod") > 0
                    || vitrail$presence("io.vram.canvas.CanvasFabricMod") > 0 ? 1 : -1;
            this.vitrail$canvasPresence = presence;
        }
        if (presence < 0) result.setReturnValue(false);
    }
}
