package com.ayoshiko.productivebeesgenesis.resource.emc;

import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

/** FTB 只负责当前团队关系；不保存团队快照，退队和 API 不可用时拒绝收款。 */
final class FtbTeamEmcRecipients {
    private FtbTeamEmcRecipients() {}

    private static final class Api {
        private static final Method API;
        private static final Method LOADED;
        private static final Method MANAGER;
        private static final Method SERVER;
        private static final Method SAME_TEAM;

        static {
            try {
                var entry = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI");
                var api = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI$API");
                var manager = Class.forName("dev.ftb.mods.ftbteams.api.TeamManager");
                API = entry.getMethod("api");
                LOADED = api.getMethod("isManagerLoaded");
                MANAGER = api.getMethod("getManager");
                SERVER = manager.getMethod("getServer");
                SAME_TEAM = manager.getMethod("arePlayersInSameTeam", UUID.class, UUID.class);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }

    static boolean sameTeam(MinecraftServer server, UUID owner, UUID recipient) {
        if (!server.isSameThread()) throw new IllegalStateException("EMC team query requires the server thread");
        if (owner.equals(recipient)) return true;
        if (!ModList.get().isLoaded("ftbteams")) return false;
        try {
            Object api = Api.API.invoke(null);
            if (!Boolean.TRUE.equals(Api.LOADED.invoke(api))) return false;
            Object manager = Api.MANAGER.invoke(api);
            return manager != null && Api.SERVER.invoke(manager) == server
                    && Boolean.TRUE.equals(Api.SAME_TEAM.invoke(manager, owner, recipient));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            Throwable cause = failure instanceof InvocationTargetException invoked ? invoked.getCause() : failure;
            LogThrottle.warnWithCooldown("emc_ftb_team", 10000, "FTB EMC recipient query failed; retaining machine balance", cause);
            return false;
        }
    }
}
