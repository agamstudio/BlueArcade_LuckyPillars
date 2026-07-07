package net.blueva.arcade.modules.lucky_pillars.setup;

import net.blueva.arcade.api.setup.GameSetupHandler;
import net.blueva.arcade.api.setup.SetupContext;
import net.blueva.arcade.api.setup.TabCompleteContext;
import net.blueva.arcade.api.setup.TabCompleteResult;
import net.blueva.arcade.modules.lucky_pillars.LuckyPillarsModule;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.List;
import java.util.Locale;

public class LuckyPillarsSetup implements GameSetupHandler {

    private final LuckyPillarsModule module;

    public LuckyPillarsSetup(LuckyPillarsModule module) {
        this.module = module;
    }

    @Override
    public boolean handle(SetupContext context) {
        return handleInternal(castSetupContext(context));
    }

    private boolean handleInternal(SetupContext<Player, CommandSender, Location> context) {
        String subcommand = context.getArg(context.getStartIndex() - 1);
        if ("region".equalsIgnoreCase(subcommand)) {
            return handleRegion(context);
        }
        if ("team".equalsIgnoreCase(subcommand)) {
            String teamSubcommand = context.getHandlerArg(0);
            if ("spawn".equalsIgnoreCase(teamSubcommand)) {
                return handleTeamSpawn(context);
            }
            return handleTeamConfig(context);
        }
        return handleTeamConfig(context);
    }

    @Override
    public TabCompleteResult tabComplete(TabCompleteContext context) {
        return tabCompleteInternal(castTabContext(context));
    }

    private TabCompleteResult tabCompleteInternal(TabCompleteContext<Player, CommandSender> context) {
        if (context.getRelativeArgIndex() == 0
                && "team".equalsIgnoreCase(context.getArg(context.getStartIndex() - 1))) {
            return TabCompleteResult.of("count", "size", "spawn");
        }
        if (context.getRelativeArgIndex() == 1
                && "team".equalsIgnoreCase(context.getArg(context.getStartIndex() - 1))
                && "spawn".equalsIgnoreCase(context.getArg(context.getStartIndex()))) {
            return TabCompleteResult.of("add", "set", "remove");
        }
        if (context.getRelativeArgIndex() == 0
                && "region".equalsIgnoreCase(context.getArg(context.getStartIndex() - 1))) {
            return TabCompleteResult.of("set", "clear");
        }
        return TabCompleteResult.empty();
    }

    @Override
    public List<String> getSubcommands() {
        return List.of("team", "region");
    }

    private boolean handleTeamConfig(SetupContext<Player, CommandSender, Location> context) {
        if (!context.hasHandlerArgs(2)) {
            context.getMessagesAPI().sendRaw(context.getPlayer(),
                    getSetupMessage(context.getPlayer(), "team.usage"));
            return true;
        }

        String setting = context.getHandlerArg(0);
        if (setting == null || (!setting.equalsIgnoreCase("count") && !setting.equalsIgnoreCase("size"))) {
            context.getMessagesAPI().sendRaw(context.getPlayer(),
                    getSetupMessage(context.getPlayer(), "team.usage"));
            return true;
        }

        String valueRaw = context.getHandlerArg(1);
        if (valueRaw == null || !isNumber(valueRaw)) {
            context.getMessagesAPI().sendRaw(context.getPlayer(),
                    module.getCoreConfig().getLanguage(context.getPlayer(), "admin_commands.errors.invalid_number")
                            .replace("{value}", valueRaw == null ? "" : valueRaw));
            return true;
        }

        int value = Integer.parseInt(valueRaw);
        if (value <= 0) {
            context.getMessagesAPI().sendRaw(context.getPlayer(),
                    getSetupMessage(context.getPlayer(), "team.invalid_value")
                            .replace("{setting}", setting));
            return true;
        }

        if (setting.equalsIgnoreCase("count") && value < 2) {
            context.getMessagesAPI().sendRaw(context.getPlayer(),
                    getSetupMessage(context.getPlayer(), "team.invalid_count"));
            return true;
        }

        int teamCount = context.getData().getInt("teams.count", 0);
        int teamSize = context.getData().getInt("teams.size", 0);
        if (setting.equalsIgnoreCase("count")) {
            teamCount = value;
        } else {
            teamSize = value;
        }

        int maxPlayers = context.getData().getArenaInt("arena.basic.max_players", 0);
        if (teamCount > 0 && teamSize > 0 && maxPlayers > 0 && teamCount * teamSize > maxPlayers) {
            context.getMessagesAPI().sendRaw(context.getPlayer(),
                    getSetupMessage(context.getPlayer(), "team.invalid_limit")
                            .replace("{max_players}", String.valueOf(maxPlayers)));
            return true;
        }

        context.getData().setTeamConfig(teamCount, teamSize);
        context.getData().save();

        context.getMessagesAPI().sendRaw(context.getPlayer(),
                getSetupMessage(context.getPlayer(), "team.success")
                        .replace("{game}", context.getGameId())
                        .replace("{arena_id}", String.valueOf(context.getArenaId()))
                        .replace("{setting}", setting.toLowerCase())
                        .replace("{value}", String.valueOf(value)));
        return true;
    }

    private boolean handleTeamSpawn(SetupContext<Player, CommandSender, Location> context) {
        String action = context.getHandlerArg(1);

        if ("add".equalsIgnoreCase(action)) {
            return handleTeamSpawnAdd(context);
        }
        if ("set".equalsIgnoreCase(action)) {
            return handleTeamSpawnSet(context);
        }
        if ("remove".equalsIgnoreCase(action)) {
            return handleTeamSpawnRemove(context);
        }

        context.getMessagesAPI().sendRaw(context.getPlayer(), getSetupMessage(context.getPlayer(), "team_spawn.usage_add"));
        context.getMessagesAPI().sendRaw(context.getPlayer(), getSetupMessage(context.getPlayer(), "team_spawn.usage_set"));
        context.getMessagesAPI().sendRaw(context.getPlayer(), getSetupMessage(context.getPlayer(), "team_spawn.usage_remove"));
        return true;
    }

    private boolean handleTeamSpawnRemove(SetupContext<Player, CommandSender, Location> context) {
        if (!context.hasHandlerArgs(3)) {
            context.getMessagesAPI().sendRaw(context.getPlayer(), getSetupMessage(context.getPlayer(), "team_spawn.usage_remove"));
            return true;
        }

        int teamCount = context.getData().getInt("teams.count", 0);
        if (teamCount <= 0) {
            context.getMessagesAPI().sendRaw(context.getPlayer(), getSetupMessage(context.getPlayer(), "team_spawn.teams_not_configured"));
            return true;
        }

        String teamId = normalizeTeamId(context.getHandlerArg(2));
        if (teamId == null) {
            context.getMessagesAPI().sendRaw(context.getPlayer(), getSetupMessage(context.getPlayer(), "team_spawn.usage_remove"));
            sendTeamIdRangeMessage(context);
            return true;
        }

        if (!isExistingTeam(context, teamId)) {
            sendTeamIdRangeMessage(context);
            return true;
        }

        String path = "game.play_area.team_spawns." + teamId.toLowerCase();
        context.getData().remove(path);
        context.getData().save();

        context.getMessagesAPI().sendRaw(context.getPlayer(), getSetupMessage(context.getPlayer(), "team_spawn.removed")
                .replace("{team}", teamId));
        return true;
    }

    private boolean handleTeamSpawnAdd(SetupContext<Player, CommandSender, Location> context) {
        int teamCount = context.getData().getInt("teams.count", 0);
        if (teamCount <= 0) {
            context.getMessagesAPI().sendRaw(context.getPlayer(), getSetupMessage(context.getPlayer(), "team_spawn.teams_not_configured"));
            return true;
        }

        Player player = context.getPlayer();
        if (player == null) {
            return true;
        }

        // Find next free numeric slot
        int nextSlot = -1;
        for (int i = 1; i <= teamCount; i++) {
            String path = "game.play_area.team_spawns." + i;
            if (!context.getData().has(path)) {
                nextSlot = i;
                break;
            }
        }

        if (nextSlot == -1) {
            context.getMessagesAPI().sendRaw(player, getSetupMessage(context.getPlayer(), "team_spawn.all_slots_filled")
                    .replace("{count}", String.valueOf(teamCount)));
            return true;
        }

        Location location = player.getLocation();
        context.getData().setLocation("game.play_area.team_spawns." + nextSlot, location);
        context.getData().save();

        context.getMessagesAPI().sendRaw(player, getSetupMessage(context.getPlayer(), "team_spawn.added")
                .replace("{slot}", String.valueOf(nextSlot))
                .replace("{count}", String.valueOf(teamCount)));
        return true;
    }

    private boolean handleTeamSpawnSet(SetupContext<Player, CommandSender, Location> context) {
        if (!context.hasHandlerArgs(3)) {
            context.getMessagesAPI().sendRaw(context.getPlayer(), getSetupMessage(context.getPlayer(), "team_spawn.usage_set"));
            return true;
        }

        int teamCount = context.getData().getInt("teams.count", 0);
        if (teamCount <= 0) {
            context.getMessagesAPI().sendRaw(context.getPlayer(), getSetupMessage(context.getPlayer(), "team_spawn.teams_not_configured"));
            return true;
        }

        String teamId = normalizeTeamId(context.getHandlerArg(2));
        if (teamId == null) {
            context.getMessagesAPI().sendRaw(context.getPlayer(), getSetupMessage(context.getPlayer(), "team_spawn.usage_set"));
            sendTeamIdRangeMessage(context);
            return true;
        }

        if (!isExistingTeam(context, teamId)) {
            sendTeamIdRangeMessage(context);
            return true;
        }

        Player player = context.getPlayer();
        if (player == null) {
            return true;
        }

        Location location = player.getLocation();
        String path = "game.play_area.team_spawns." + teamId.toLowerCase();
        context.getData().setLocation(path, location);
        context.getData().save();

        context.getMessagesAPI().sendRaw(player, getSetupMessage(context.getPlayer(), "team_spawn.set")
                .replace("{team}", teamId));
        return true;
    }

    private boolean isExistingTeam(SetupContext<Player, CommandSender, Location> context, String teamId) {
        int teamCount = context.getData().getInt("teams.count", 0);
        if (teamCount <= 0) {
            return false;
        }
        for (int i = 1; i <= teamCount; i++) {
            if (String.valueOf(i).equalsIgnoreCase(teamId)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeTeamId(String teamRaw) {
        if (teamRaw == null) {
            return null;
        }
        String value = teamRaw.trim().toLowerCase(Locale.ROOT);
        return isNumericId(value) ? value : null;
    }

    private boolean isNumericId(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        for (int i = 0; i < raw.length(); i++) {
            if (!Character.isDigit(raw.charAt(i))) {
                return false;
            }
        }
        try {
            return Integer.parseInt(raw) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private void sendTeamIdRangeMessage(SetupContext<Player, CommandSender, Location> context) {
        int teamCount = context.getData().getInt("teams.count", 0);
        String max = teamCount > 0 ? String.valueOf(teamCount) : "N";
        context.getMessagesAPI().sendRaw(context.getPlayer(),
                getSetupMessage(context.getPlayer(), "team.numeric_ids_only")
                        .replace("{min}", "1")
                        .replace("{max}", max));
    }

    private boolean handleRegion(SetupContext<Player, CommandSender, Location> context) {
        if (!context.hasHandlerArgs(1)) {
            context.getMessagesAPI().sendRaw(context.getPlayer(),
                    getSetupMessage(context.getPlayer(), "region.usage"));
            return true;
        }

        String action = context.getHandlerArg(0);
        if (action == null) {
            context.getMessagesAPI().sendRaw(context.getPlayer(),
                    getSetupMessage(context.getPlayer(), "region.usage"));
            return true;
        }

        if ("clear".equalsIgnoreCase(action)) {
            context.getData().remove("game.play_area");
            context.getData().remove("regeneration.regions");
            context.getData().save();
            context.getMessagesAPI().sendRaw(context.getPlayer(),
                    getSetupMessage(context.getPlayer(), "region.cleared"));
            return true;
        }

        if (!"set".equalsIgnoreCase(action)) {
            context.getMessagesAPI().sendRaw(context.getPlayer(),
                    getSetupMessage(context.getPlayer(), "region.usage"));
            return true;
        }

        Player player = context.getPlayer();
        if (player == null) {
            return true;
        }

        if (!context.getSelection().hasCompleteSelection(player)) {
            context.getMessagesAPI().sendRaw(player,
                    getSetupMessage(context.getPlayer(), "region.must_use_stick"));
            return true;
        }

        Location pos1 = context.getSelection().getPosition1(player);
        Location pos2 = context.getSelection().getPosition2(player);

        context.getData().registerRegenerationRegion("game.play_area", pos1, pos2);
        context.getData().save();

        int x = (int) Math.abs(pos2.getX() - pos1.getX()) + 1;
        int y = (int) Math.abs(pos2.getY() - pos1.getY()) + 1;
        int z = (int) Math.abs(pos2.getZ() - pos1.getZ()) + 1;
        int blocks = x * y * z;

        context.getMessagesAPI().sendRaw(player,
                getSetupMessage(context.getPlayer(), "region.set")
                        .replace("{blocks}", String.valueOf(blocks))
                        .replace("{x}", String.valueOf(x))
                        .replace("{y}", String.valueOf(y))
                        .replace("{z}", String.valueOf(z)));
        return true;
    }

    private String getSetupMessage(Player player, String key) {
        String message = module.getModuleConfig().getTranslation(player, "setup_messages." + key);
        if (message == null) {
            return "";
        }
        return message;
    }

    private boolean isNumber(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }


    @SuppressWarnings("unchecked")
    private SetupContext<Player, CommandSender, Location> castSetupContext(SetupContext context) {
        return (SetupContext<Player, CommandSender, Location>) context;
    }

    @SuppressWarnings("unchecked")
    private TabCompleteContext<Player, CommandSender> castTabContext(TabCompleteContext context) {
        return (TabCompleteContext<Player, CommandSender>) context;
    }
}
