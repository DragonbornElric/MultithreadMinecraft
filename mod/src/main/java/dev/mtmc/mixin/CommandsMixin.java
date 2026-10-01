package dev.mtmc.mixin;

import com.mojang.brigadier.CommandDispatcher;
import dev.mtmc.MtmcCommand;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Registers /mtmc without Fabric API. */
@Mixin(Commands.class)
abstract class CommandsMixin {
    @Shadow
    @Final
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void mtmc$register(Commands.CommandSelection selection, CommandBuildContext context, CallbackInfo ci) {
        MtmcCommand.register(dispatcher);
    }
}
