package com.ayoshiko.productivebeesgenesis.resource.experience;

import com.ayoshiko.productivebeesgenesis.resource.ResourceContent;
import es.degrassi.experiencelib.api.capability.ExperienceLibCapabilities;
import es.degrassi.experiencelib.api.capability.IExperienceHandler;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** 仅由 ExperienceLib 加载守卫后的注册入口调用。 */
public final class ResourceExperienceIntegration {
	private ResourceExperienceIntegration() {}

	public static boolean canReceive(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos,
			net.minecraft.core.Direction side) {
		var receiver = level.getCapability(ExperienceLibCapabilities.EXPERIENCE.block(), pos, side);
		if (receiver == null) return false;
		for (int tank = 0, tanks = Math.min(16, receiver.getTanks()); tank < tanks; tank++) {
			if (receiver.receiveExperience(tank, 1, true) == 1) return true;
		}
		return false;
	}
    public static com.ayoshiko.productivebeesgenesis.resource.ResourceNativeOutput.Receiver receiver(
            net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos, net.minecraft.core.Direction side) {
        var cap = ExperienceLibCapabilities.EXPERIENCE.block();
        var receiver = level.getCapability(cap, pos, side);
        if (receiver == null) return null;
        return new com.ayoshiko.productivebeesgenesis.resource.ResourceNativeOutput.Receiver() {
            private long insert(long amount, boolean simulate) {
                long remaining = amount;
                for (int tank = 0, tanks = Math.min(16, receiver.getTanks()); tank < tanks && remaining > 0; tank++) {
                    remaining -= com.ayoshiko.productivebeesgenesis.resource.ResourceNativeOutput.checked(
                            receiver.receiveExperience(tank, remaining, simulate), remaining);
                }
                return amount - remaining;
            }
            public long simulate(long maximum) { return insert(maximum, true); }
            public long execute(long amount) { return insert(amount, false); }
            public void validate() {
                if (level.getCapability(cap, pos, side) != receiver) throw new IllegalStateException("Experience receiver changed");
            }
        };
    }

	public static void register(RegisterCapabilitiesEvent event) {
		ResourceContent.registerHostCapability(event, ExperienceLibCapabilities.EXPERIENCE.block(),
				(tile, side) -> tile.sided(side).outputAllowed(com.ayoshiko.productivebeesgenesis.resource.ResourceNativeType.EXPERIENCE)
                        ? (IExperienceHandler) tile.sided(side).experienceCapability() : null);
	}
}
