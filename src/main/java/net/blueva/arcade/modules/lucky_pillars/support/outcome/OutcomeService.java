package net.blueva.arcade.modules.lucky_pillars.support.outcome;

import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.api.module.ModuleInfo;
import net.blueva.arcade.api.stats.StatsAPI;
import net.blueva.arcade.api.team.TeamsAPI;
import net.blueva.arcade.modules.lucky_pillars.game.LuckyPillarsGame;
import net.blueva.arcade.modules.lucky_pillars.state.ArenaState;
import net.blueva.arcade.modules.lucky_pillars.support.PlaceholderService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class OutcomeService {

    private final ModuleInfo moduleInfo;
    private final StatsAPI statsAPI;
    private final LuckyPillarsGame game;
    private final PlaceholderService placeholderService;

    public OutcomeService(ModuleInfo moduleInfo,
                          StatsAPI statsAPI,
                          LuckyPillarsGame game,
                          PlaceholderService placeholderService) {
        this.moduleInfo = moduleInfo;
        this.statsAPI = statsAPI;
        this.game = game;
        this.placeholderService = placeholderService;
    }

    public void endGame(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                        ArenaState state) {
        if (state.markEnded()) {
            return;
        }

        context.getSchedulerAPI().cancelArenaTasks(context.getArenaId());

        List<String> aliveTeams = game.getAliveTeamIds(context);
        if (aliveTeams.size() == 1) {
            declareWinningTeam(context, state, aliveTeams.get(0));
        } else if (aliveTeams.isEmpty()) {
            handleNoWinner(context);
        } else {
            declareTopTeamByKills(context, state);
        }

        context.endGame();
    }

    private void declareWinningTeam(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                    ArenaState state,
                                    String teamId) {
        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        List<Player> winners = game.getTeamPlayers(context, teamId);
        if (teamsAPI != null && teamsAPI.isEnabled()) {
            teamsAPI.setWinningTeam(teamId);
        }
        if (!winners.isEmpty()) {
            context.markSharedFirstPlace(winners);
        }
        handleWinStats(state, winners);
    }

    private void declareTopTeamByKills(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                       ArenaState state) {
        Map<String, Integer> teamKills = game.getTeamKills(context);
        if (teamKills.isEmpty()) {
            handleNoWinner(context);
            return;
        }

        int maxKills = teamKills.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<String> topTeams = teamKills.entrySet().stream()
                .filter(entry -> entry.getValue() == maxKills)
                .map(Map.Entry::getKey)
                .toList();

        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        if (teamsAPI != null && teamsAPI.isEnabled()) {
            teamsAPI.setWinningTeams(topTeams);
        }

        List<Player> winners = new ArrayList<>();
        for (String teamId : topTeams) {
            winners.addAll(game.getTeamPlayers(context, teamId));
        }
        if (!winners.isEmpty()) {
            context.markSharedFirstPlace(winners);
        }
        handleWinStats(state, winners);
    }

    private void handleNoWinner(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        List<Player> candidates = context.getAlivePlayers();
        if (candidates.isEmpty()) {
            candidates = new ArrayList<>(context.getPlayers());
        }
        List<Player> sortedByKills = placeholderService.getPlayersSortedByKills(
                context, candidates, candidates.size());
        if (sortedByKills.isEmpty()) {
            return;
        }
        context.markSharedFirstPlace(List.of(sortedByKills.get(0)));
    }

    private void handleWinStats(ArenaState state, List<Player> winners) {
        if (statsAPI == null || winners.isEmpty()) {
            return;
        }

        UUID winnerId = state.getWinnerId();
        if (winnerId != null) {
            return;
        }

        state.setWinner(winners.get(0).getUniqueId());
        for (Player winner : winners) {
            statsAPI.addModuleStat(winner, moduleInfo.getId(), "wins", 1);
        }
    }
}
