package net.blueva.arcade.modules.lucky_pillars.support;

import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.api.team.TeamInfo;
import net.blueva.arcade.api.team.TeamsAPI;
import net.blueva.arcade.modules.lucky_pillars.game.LuckyPillarsGame;
import net.blueva.arcade.modules.lucky_pillars.state.ArenaState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlaceholderService {

    private final ModuleConfigAPI moduleConfig;
    private final LuckyPillarsGame game;

    public PlaceholderService(ModuleConfigAPI moduleConfig, LuckyPillarsGame game) {
        this.moduleConfig = moduleConfig;
        this.game = game;
    }

    public Map<String, String> buildPlaceholders(Player player) {
        Map<String, String> placeholders = new HashMap<>();

        GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context = game.getContext(player);
        if (context != null) {
            placeholders.put("alive", String.valueOf(context.getAlivePlayers().size()));
            placeholders.put("spectators", String.valueOf(context.getSpectators().size()));
            placeholders.put("kills", String.valueOf(game.getPlayerKills(context, player)));
            placeholders.put("alive_teams", String.valueOf(game.getAliveTeamIds(context).size()));

            TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
            if (teamsAPI != null && teamsAPI.isEnabled() && !game.isSoloMode(context)) {
                TeamInfo<Player, Material> team = teamsAPI.getTeam(player);
                placeholders.put("team", team != null ? team.getDisplayName() : "-");
            } else {
                String teamLabel = moduleConfig.getTranslation(player, "scoreboard.solo_team_label");
                placeholders.put("team", teamLabel == null ? "" : teamLabel);
            }

            ArenaState state = game.getArenaState(context);
            if (state != null) {
                placeholders.put("next_event", resolveNextEventLabel(player, state));
                placeholders.put("next_event_time", resolveNextEventTime(player, state));
                placeholders.put("next_block_time", resolveNextBlockTime(player, state));
            }
        }

        return placeholders;
    }

    private String resolveNextEventLabel(Player player, ArenaState state) {
        if (state == null) {
            String none = moduleConfig.getTranslation(player, "scoreboard.event.none");
            return none == null ? "" : none;
        }
        ArenaState.ScheduledEvent event = state.getNextEvent();
        if (event == null) {
            String none = moduleConfig.getTranslation(player, "scoreboard.event.none");
            return none == null ? "" : none;
        }
        String label = event.getLabel();
        if (label == null || label.isBlank()) {
            String none = moduleConfig.getTranslation(player, "scoreboard.event.none");
            return none == null ? "" : none;
        }
        return label;
    }

    private String resolveNextEventTime(Player player, ArenaState state) {
        if (state == null) {
            String noneTime = moduleConfig.getTranslation(player, "scoreboard.event.none_time");
            return noneTime == null ? "" : noneTime;
        }
        int seconds = state.getSecondsUntilNextEvent();
        if (seconds < 0) {
            String noneTime = moduleConfig.getTranslation(player, "scoreboard.event.none_time");
            return noneTime == null ? "" : noneTime;
        }
        if (seconds <= 0) {
            String now = moduleConfig.getTranslation(player, "scoreboard.event.now");
            return now == null ? "" : now;
        }
        return formatTime(seconds);
    }

    private String resolveNextBlockTime(Player player, ArenaState state) {
        if (state == null) {
            String noneTime = moduleConfig.getTranslation(player, "scoreboard.event.none_time");
            return noneTime == null ? "" : noneTime;
        }

        int seconds = state.getSecondsUntilNextBlockDrop();
        if (seconds < 0) {
            String noneTime = moduleConfig.getTranslation(player, "scoreboard.event.none_time");
            return noneTime == null ? "" : noneTime;
        }
        if (seconds <= 0) {
            String now = moduleConfig.getTranslation(player, "scoreboard.event.now");
            return now == null ? "" : now;
        }
        return formatTime(seconds);
    }

    private String formatTime(int seconds) {
        int minutes = Math.max(0, seconds) / 60;
        int remainingSeconds = Math.max(0, seconds) % 60;
        return String.format("%02d:%02d", minutes, remainingSeconds);
    }

    public List<Player> getPlayersSortedByKills(
            GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
            List<Player> players,
            int limit) {
        Map<Player, Integer> killCounts = new HashMap<>();
        for (Player player : players) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            killCounts.put(player, game.getPlayerKills(context, player));
        }

        List<Map.Entry<Player, Integer>> sorted = new java.util.ArrayList<>(killCounts.entrySet());
        sorted.sort((a, b) -> {
            int compare = Integer.compare(b.getValue(), a.getValue());
            if (compare != 0) {
                return compare;
            }
            return a.getKey().getName().compareToIgnoreCase(b.getKey().getName());
        });

        List<Player> orderedPlayers = new java.util.ArrayList<>();
        for (Map.Entry<Player, Integer> entry : sorted) {
            orderedPlayers.add(entry.getKey());
            if (orderedPlayers.size() >= limit) {
                break;
            }
        }

        return orderedPlayers;
    }

    public ModuleConfigAPI getModuleConfig() {
        return moduleConfig;
    }

}
