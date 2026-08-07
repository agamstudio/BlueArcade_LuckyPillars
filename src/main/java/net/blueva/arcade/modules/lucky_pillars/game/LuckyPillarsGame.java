package net.blueva.arcade.modules.lucky_pillars.game;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.blueva.arcade.api.ModuleAPI;
import net.blueva.arcade.api.config.CoreConfigAPI;
import net.blueva.arcade.api.config.DataAccess;
import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.api.game.GamePhase;
import net.blueva.arcade.api.module.ModuleInfo;
import net.blueva.arcade.api.stats.StatsAPI;
import net.blueva.arcade.api.store.StoreAPI;
import net.blueva.arcade.api.team.TeamInfo;
import net.blueva.arcade.api.utils.PlayerUtil;
import net.blueva.arcade.api.team.TeamsAPI;
import net.blueva.arcade.modules.lucky_pillars.state.ArenaState;
import net.blueva.arcade.modules.lucky_pillars.state.VoteState;
import net.blueva.arcade.modules.lucky_pillars.support.DescriptionService;
import net.blueva.arcade.modules.lucky_pillars.support.PlaceholderService;
import net.blueva.arcade.modules.lucky_pillars.support.combat.CombatService;
import net.blueva.arcade.modules.lucky_pillars.support.loadout.PlayerLoadoutService;
import net.blueva.arcade.modules.lucky_pillars.support.outcome.OutcomeService;
import net.blueva.arcade.modules.lucky_pillars.support.spawn.SpawnCageService;
import net.blueva.arcade.modules.lucky_pillars.support.vote.LuckyPillarsVoteService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class LuckyPillarsGame {

    private static final double CAGE_GUARD_MAX_DISTANCE_SQUARED = 2.25;

    private final ModuleInfo moduleInfo;
    private final ModuleConfigAPI moduleConfig;
    private final CoreConfigAPI coreConfig;
    private final StatsAPI statsAPI;

    private final Map<Integer, ArenaState> arenas = new ConcurrentHashMap<>();
    private final Map<Player, Integer> playerArena = new ConcurrentHashMap<>();

    private final DescriptionService descriptionService;
    private final PlayerLoadoutService loadoutService;  // Kept as null for compatibility
    private final PlaceholderService placeholderService;
    private final OutcomeService outcomeService;
    private final CombatService combatService;
    private final SpawnCageService spawnCageService;  // Spawn cages for cosmetics
    private final LuckyPillarsVoteService voteService;
    private final List<ArenaState.ScheduledEvent> scheduledEvents;

    public LuckyPillarsGame(ModuleInfo moduleInfo,
                       ModuleConfigAPI moduleConfig,
                       CoreConfigAPI coreConfig,
                       StatsAPI statsAPI,
                       StoreAPI storeAPI,
                       LuckyPillarsVoteService voteService) {
        this.moduleInfo = moduleInfo;
        this.moduleConfig = moduleConfig;
        this.coreConfig = coreConfig;
        this.statsAPI = statsAPI;
        this.descriptionService = new DescriptionService(moduleConfig);
        this.loadoutService = null; // No kits in Lucky Pillars
        this.placeholderService = new PlaceholderService(moduleConfig, this);
        this.outcomeService = new OutcomeService(moduleInfo, statsAPI, this, placeholderService);
        this.combatService = new CombatService(moduleConfig, coreConfig, statsAPI, this, null);
        this.spawnCageService = new SpawnCageService(moduleConfig, storeAPI);
        this.voteService = voteService;
        this.scheduledEvents = new ArrayList<>(); // No scheduled events in Lucky Pillars
    }

    public void startGame(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        int arenaId = context.getArenaId();

        context.getSchedulerAPI().cancelArenaTasks(arenaId);
        ArenaState state = new ArenaState(context);
        arenas.put(arenaId, state);
        if (voteService != null) {
            state.setVoteState(voteService.createVoteState());
            voteService.applyPendingVotes(state, context.getPlayers());
        }
        // No loot chests in Lucky Pillars
        state.setScheduledEvents(scheduledEvents);

        loadTeamSpawns(context, state);

        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        for (Player player : context.getPlayers()) {
            playerArena.put(player, arenaId);
            state.initializePlayer(player.getUniqueId());
            if (teamsAPI != null && teamsAPI.isEnabled() && teamsAPI.getTeam(player) == null) {
                teamsAPI.autoAssignPlayer(player);
            }
        }

        for (Player player : context.getPlayers()) {
            if (player != null && player.isOnline()) {
                teleportToTeamSpawn(context, state, player);
            }
        }

        scheduleSpawnCages(context, state);
        scheduleCageGuard(context, state);
        descriptionService.sendDescription(context);
    }

    private void loadTeamSpawns(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                ArenaState state) {
        if (context.getDataAccess() == null) {
            return;
        }

        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        if (teamsAPI == null || !teamsAPI.isEnabled()) {
            return;
        }

        String spawnBase = resolveDataBasePath(context, "team_spawns");
        World arenaWorld = context.getArenaAPI() != null ? context.getArenaAPI().getWorld() : null;

        int teamIndex = 1;
        boolean foundAny = false;
        for (TeamInfo<Player, Material> team : teamsAPI.getTeams()) {
            String teamId = team.getId();
            if (teamId == null || teamId.isBlank()) {
                teamIndex++;
                continue;
            }
            teamId = teamId.toLowerCase();
            String canonicalPath = spawnBase + "." + teamId;
            String numericPath = spawnBase + "." + teamIndex;

            String resolvedPath = null;
            if (context.getDataAccess().hasGameData(canonicalPath)) {
                resolvedPath = canonicalPath;
            } else if (context.getDataAccess().hasGameData(numericPath)) {
                resolvedPath = numericPath;
            }

            if (resolvedPath != null) {
                Location spawn = context.getDataAccess().getGameLocation(resolvedPath);
                if (spawn != null) {
                    if (spawn.getWorld() == null && arenaWorld != null) {
                        spawn = new Location(arenaWorld, spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getYaw(), spawn.getPitch());
                    }
                    state.setTeamSpawn(teamId, spawn);
                    foundAny = true;
                }
            }
            teamIndex++;
        }

        // Adapter: if no team spawns configured, migrate from generic spawns.list to disk
        if (!foundAny && context.getArenaAPI() != null) {
            List<Location> genericSpawns = context.getArenaAPI().getSpawns();
            if (!genericSpawns.isEmpty()) {
                Map<String, Location> migrated = new HashMap<>();
                List<TeamInfo<Player, Material>> orderedTeams = new ArrayList<>(teamsAPI.getTeams());
                for (int i = 0; i < genericSpawns.size(); i++) {
                    Location spawn = genericSpawns.get(i);
                    if (spawn == null) {
                        continue;
                    }
                    if (spawn.getWorld() == null && arenaWorld != null) {
                        spawn = new Location(arenaWorld, spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getYaw(), spawn.getPitch());
                    }
                    String teamId;
                    if (i < orderedTeams.size()) {
                        teamId = orderedTeams.get(i).getId() != null ? orderedTeams.get(i).getId() : String.valueOf(i + 1);
                        if (teamId == null || teamId.isBlank()) {
                            teamId = String.valueOf(i + 1);
                        } else {
                            teamId = teamId.toLowerCase();
                        }
                    } else {
                        teamId = String.valueOf(i + 1);
                    }
                    migrated.put(teamId, spawn);
                    state.setTeamSpawn(teamId, spawn);
                }
                if (!migrated.isEmpty()) {
                    migrateSpawnsToDisk(context.getArenaId(), context.getGameId(), migrated);
                }
            }
        }
    }

    private void migrateSpawnsToDisk(int arenaId, String gameId, Map<String, Location> teamSpawns) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("BlueArcade3");
        if (plugin == null) {
            return;
        }
        File gameFile = new File(plugin.getDataFolder(),
                "data/arenas/" + arenaId + "/games/" + gameId + ".json");
        if (!gameFile.exists()) {
            return;
        }
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonObject root;
            try (FileReader reader = new FileReader(gameFile)) {
                root = gson.fromJson(reader, JsonObject.class);
            }
            if (root == null) {
                return;
            }

            if (!root.has("game")) root.add("game", new JsonObject());
            JsonObject game = root.getAsJsonObject("game");
            if (!game.has("play_area")) game.add("play_area", new JsonObject());
            JsonObject playArea = game.getAsJsonObject("play_area");

            JsonObject teamSpawnsObj = new JsonObject();
            for (Map.Entry<String, Location> entry : teamSpawns.entrySet()) {
                Location loc = entry.getValue();
                JsonObject locObj = new JsonObject();
                locObj.addProperty("x", loc.getX());
                locObj.addProperty("y", loc.getY());
                locObj.addProperty("z", loc.getZ());
                locObj.addProperty("yaw", loc.getYaw());
                locObj.addProperty("pitch", loc.getPitch());
                teamSpawnsObj.add(entry.getKey(), locObj);
            }
            playArea.add("team_spawns", teamSpawnsObj);

            if (root.has("spawns")) {
                JsonObject spawnsSection = root.getAsJsonObject("spawns");
                if (spawnsSection != null) {
                    spawnsSection.remove("list");
                    if (spawnsSection.size() == 0) {
                        root.remove("spawns");
                    }
                }
            }

            try (FileWriter writer = new FileWriter(gameFile)) {
                gson.toJson(root, writer);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("[LuckyPillars] Failed to migrate spawns for arena " + arenaId + ": " + e.getMessage());
        }
    }

    private void teleportToTeamSpawn(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                     ArenaState state,
                                     Player player) {
        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        if (teamsAPI == null || !teamsAPI.isEnabled()) {
            return;
        }

        TeamInfo<Player, Material> team = teamsAPI.getTeam(player);
        if (team == null) {
            return;
        }

        Location spawn = state.getTeamSpawn(team.getId());
        if (spawn == null || spawn.getWorld() == null) {
            return;
        }

        context.getSchedulerAPI().runAtEntity(player, () -> player.teleport(centerSpawnLocation(spawn)));
    }

    private String resolveDataBasePath(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                       String section) {
        if (context.getDataAccess().hasGameData("game.play_area." + section)) {
            return "game.play_area." + section;
        }
        return "game." + section;
    }

    private Location centerSpawnLocation(Location spawn) {
        double centeredX = Math.floor(spawn.getX()) + 0.5;
        double centeredZ = Math.floor(spawn.getZ()) + 0.5;
        return new Location(spawn.getWorld(), centeredX, spawn.getY(), centeredZ, spawn.getYaw(), spawn.getPitch());
    }

    private void scheduleSpawnCages(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                    ArenaState state) {
        int arenaId = context.getArenaId();
        String taskId = "arena_" + arenaId + "_lucky_pillars_cages";
        int maxTicks = 40;
        int[] ticks = {0};
        context.getSchedulerAPI().runTimer(taskId, () -> {
            spawnCageService.buildCages(context, state);
            ticks[0]++;
            if (ticks[0] >= maxTicks || state.getCagedPlayerCount() >= context.getPlayers().size()) {
                context.getSchedulerAPI().cancelTask(taskId);
            }
        }, 1L, 1L);
    }

    private void scheduleCageGuard(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                   ArenaState state) {
        int arenaId = context.getArenaId();
        String taskId = "arena_" + arenaId + "_lucky_pillars_cage_guard";
        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        context.getSchedulerAPI().runTimer(taskId, () -> {
            if (teamsAPI == null || !teamsAPI.isEnabled()) {
                return;
            }
            for (Player player : context.getPlayers()) {
                if (player == null || !player.isOnline()) {
                    continue;
                }
                TeamInfo<Player, Material> team = teamsAPI.getTeam(player);
                if (team == null) {
                    continue;
                }
                Location spawn = state.getTeamSpawn(team.getId());
                if (spawn == null || spawn.getWorld() == null) {
                    continue;
                }
                Location playerLoc = player.getLocation();
                if (playerLoc.getWorld() == null || !playerLoc.getWorld().equals(spawn.getWorld())) {
                    continue;
                }
                if (playerLoc.distanceSquared(spawn) > CAGE_GUARD_MAX_DISTANCE_SQUARED) {
                    context.getSchedulerAPI().runAtEntity(player, () -> player.teleport(centerSpawnLocation(spawn)));
                }
            }
        }, 10L, 10L);
    }

    public void handleCountdownTick(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                    int secondsLeft) {
        for (Player player : context.getPlayers()) {
            if (!player.isOnline()) {
                continue;
            }

            context.getSoundsAPI().play(player, coreConfig.getSound("sounds.starting_game.countdown"));

            String title = coreConfig.getLanguage(player, "titles.starting_game.title")
                    .replace("{game_display_name}", moduleInfo.getName())
                    .replace("{time}", String.valueOf(secondsLeft));

            String subtitle = coreConfig.getLanguage(player, "titles.starting_game.subtitle")
                    .replace("{game_display_name}", moduleInfo.getName())
                    .replace("{time}", String.valueOf(secondsLeft));

            context.getTitlesAPI().sendRaw(player, title, subtitle, 0, 20, 5);
        }
    }

    public void handleCountdownFinish(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        for (Player player : context.getPlayers()) {
            if (!player.isOnline()) {
                continue;
            }

            String title = coreConfig.getLanguage(player, "titles.game_started.title")
                    .replace("{game_display_name}", moduleInfo.getName());

            String subtitle = coreConfig.getLanguage(player, "titles.game_started.subtitle")
                    .replace("{game_display_name}", moduleInfo.getName());

            context.getTitlesAPI().sendRaw(player, title, subtitle, 0, 20, 20);
            context.getSoundsAPI().play(player, coreConfig.getSound("sounds.starting_game.start"));
        }
    }

    public void beginPlaying(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        ArenaState state = getArenaState(context);
        if (state == null) {
            return;
        }

        if (context.getAlivePlayers().isEmpty() && !context.getPlayers().isEmpty()) {
            context.setPlayers(context.getPlayers());
        }

        if (voteService != null) {
            voteService.applyVotes(context, state);
        }

        startGameTimer(context, state);
        startItemDistribution(context, state); // Start random item distribution
        // No loot chests in Lucky Pillars
        context.getSchedulerAPI().cancelTask("arena_" + context.getArenaId() + "_lucky_pillars_cage_guard");
        spawnCageService.removeCages(context, state);
        if (voteService != null) {
            voteService.broadcastVoteResults(context, state);
        }

        Attribute maxHealthAttribute = maxHealthAttribute();
        for (Player player : context.getPlayers()) {
            player.setGameMode(GameMode.SURVIVAL);
            // Reset vitals to defaults before modifiers (e.g. one_heart / double_health) run
            if (maxHealthAttribute != null && player.getAttribute(maxHealthAttribute) != null) {
                player.getAttribute(maxHealthAttribute).setBaseValue(20.0);
            }
            player.setHealth(20.0);
            player.setFoodLevel(20);
            player.setSaturation(20.0f);
            player.setFireTicks(0);
            player.getInventory().clear();

            // Apply starting items and effects from config
            applyStartingItems(player);
            applyStartingEffects(player);

            registerFallProtection(state, player);
            context.getScoreboardAPI().showScoreboard(player, getScoreboardPath(context));
        }

        // Apply after player setup so health/items from modifiers are not overwritten
        applyModifier(context, state);

        // Core may clear inventories after onGameStart; re-equip elytra shortly after
        if ("elytra".equalsIgnoreCase(state.getSelectedModifier())) {
            scheduleElytraEquip(context, state, 1L);
            scheduleElytraEquip(context, state, 10L);
        }
    }

    private void scheduleElytraEquip(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                     ArenaState state,
                                     long delayTicks) {
        String elytraTaskId = "arena_" + context.getArenaId() + "_lucky_pillars_elytra_" + delayTicks;
        context.getSchedulerAPI().runTimer(elytraTaskId, () -> {
            context.getSchedulerAPI().cancelTask(elytraTaskId);
            if (!state.isEnded()) {
                applyElytraModifier(resolveModifierPlayers(context));
            }
        }, delayTicks, delayTicks);
    }

    private void applyStartingItems(Player player) {
        List<String> items = moduleConfig.getStringList("items.starting_items");
        if (items == null) return;
        
        for (String itemStr : items) {
            try {
                String[] parts = itemStr.split(":");
                if (parts.length >= 2) {
                    Material material = Material.valueOf(parts[0].toUpperCase());
                    int amount = Integer.parseInt(parts[1]);
                    int slot = parts.length >= 3 ? Integer.parseInt(parts[2]) : -1;
                    
                    ItemStack item = new ItemStack(material, amount);
                    if (slot >= 0 && slot < 41) {
                        player.getInventory().setItem(slot, item);
                    } else {
                        player.getInventory().addItem(item);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void applyStartingEffects(Player player) {
        List<String> effects = moduleConfig.getStringList("effects.starting_effects");
        if (effects == null) return;
        
        for (String effectStr : effects) {
            try {
                String[] parts = effectStr.split(":");
                if (parts.length >= 3) {
                    org.bukkit.potion.PotionEffectType type = 
                        org.bukkit.potion.PotionEffectType.getByName(parts[0].toUpperCase());
                    if (type != null) {
                        int duration = Integer.parseInt(parts[1]);
                        int amplifier = Integer.parseInt(parts[2]);
                        player.addPotionEffect(new org.bukkit.potion.PotionEffect(type, duration, amplifier));
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    public void finishGame(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        int arenaId = context.getArenaId();
        context.getSchedulerAPI().cancelArenaTasks(arenaId);

        ArenaState state = arenas.remove(arenaId);
        if (state != null) {
            // No loot chests in Lucky Pillars
            spawnCageService.removeCages(context, state);
        }
        resetWorldDefaults(context);
        resetPlayerHearts(context.getPlayers());
        clearPlayerInventories(context.getPlayers());
        removePlayersFromArena(arenaId, context.getPlayers());

        if (statsAPI != null) {
            for (Player player : context.getPlayers()) {
                statsAPI.addModuleStat(player, moduleInfo.getId(), "games_played", 1);
            }
        }
    }

    public void shutdown() {
        Set<ArenaState> states = Set.copyOf(arenas.values());
        for (ArenaState state : states) {
            state.getContext().getSchedulerAPI().cancelModuleTasks("lucky_pillars");
            // No loot chests in Lucky Pillars
            spawnCageService.removeCages(state.getContext(), state);
            resetWorldDefaults(state.getContext());
            resetPlayerHearts(state.getContext().getPlayers());
            clearPlayerInventories(state.getContext().getPlayers());
        }

        arenas.clear();
        playerArena.clear();
    }

    public Map<String, String> getPlaceholders(Player player) {
        return placeholderService.buildPlaceholders(player);
    }

    public boolean handleVoteCommand(Player player, String[] args) {
        if (voteService == null || player == null) {
            return false;
        }

        GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context = getContext(player);
        ArenaState state = context != null ? getArenaState(context) : null;

        // If no context/state (WAITING ROOM before countdown), open menu with defaults
        if (context == null || state == null) {
            return voteService.handleVoteCommandWithoutContext(player, args);
        }

        // Check game phase - voting only allowed in WAITING and COUNTDOWN
        GamePhase phase = context.getPhase();

        if (phase == GamePhase.PLAYING || phase == GamePhase.ENDING || phase == GamePhase.FINISHED) {
            return false;
        }

        // Game is in COUNTDOWN, voting is allowed
        String[] safeArgs = args != null ? args : new String[0];
        boolean result = voteService.handleVoteCommand(player, context, state, safeArgs);

        return result;
    }

    public void onPlayerQuit(Player player) {
        if (player == null) {
            return;
        }

        @SuppressWarnings("unchecked")
        PlayerUtil<Player> playerUtil = (PlayerUtil<Player>) ModuleAPI.getPlayerUtil();
        Integer waitingArenaId = playerUtil != null ? playerUtil.getPlayerArena(player) : null;
        if (waitingArenaId == null) {
            waitingArenaId = playerArena.get(player);
        }
        if (voteService != null && waitingArenaId != null) {
            voteService.clearWaitingVote(waitingArenaId, player.getUniqueId());
        }

        Integer activeArenaId = playerArena.get(player);
        if (activeArenaId != null) {
            ArenaState state = arenas.get(activeArenaId);
            if (state != null) {
                VoteState voteState = state.getVoteState();
                if (voteState != null) {
                    voteState.clearPlayerVotes(player.getUniqueId());
                }
            }
        }

        playerArena.remove(player);
    }

    public GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> getContext(Player player) {
        Integer arenaId = playerArena.get(player);

        // Fallback: search all active arenas if player not yet in playerArena map
        // (happens during countdown before onGameStart when players use vote items)
        if (arenaId == null) {
            for (ArenaState state : arenas.values()) {
                if (state.getContext() == null) {
                    continue;
                }
                GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> ctx = state.getContext();
                if (ctx.getPlayers().contains(player) || ctx.getSpectators().contains(player)) {
                    arenaId = ctx.getArenaId();
                    // Cache for next time
                    playerArena.put(player, arenaId);
                    break;
                }
            }
        }

        if (arenaId == null) {
            return null;
        }
        ArenaState state = arenas.get(arenaId);
        return state != null ? state.getContext() : null;
    }

    public ArenaState getArenaState(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        if (context == null) {
            return null;
        }
        return arenas.get(context.getArenaId());
    }

    public int getPlayerKills(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                              Player player) {
        ArenaState state = getArenaState(context);
        if (state == null) {
            return 0;
        }
        return state.getKills(player.getUniqueId());
    }

    public void addPlayerKill(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                              Player player) {
        ArenaState state = getArenaState(context);
        if (state == null) {
            return;
        }
        state.addKill(player.getUniqueId());
    }

    public void healKiller(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                           Player killer) {
        if (loadoutService != null) {
            loadoutService.handleKillRegeneration(context, killer);
        }
        context.getSoundsAPI().play(killer, coreConfig.getSound("sounds.in_game.respawn"));
    }

    public void handleKill(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                           Player attacker,
                           Player victim) {
        combatService.handleKillCredit(context, attacker);
        combatService.handleElimination(context, victim, attacker);
        checkForTeamVictory(context);
    }

    public void handleNonCombatDeath(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                     Player victim) {
        combatService.handleElimination(context, victim, null);
        checkForTeamVictory(context);
    }

    public void endGame(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        ArenaState state = getArenaState(context);
        if (state == null) {
            return;
        }

        outcomeService.endGame(context, state);
    }

    public ModuleConfigAPI getModuleConfig() {
        return moduleConfig;
    }

    public CoreConfigAPI getCoreConfig() {
        return coreConfig;
    }

    public ModuleInfo getModuleInfo() {
        return moduleInfo;
    }

    public StatsAPI getStatsAPI() {
        return statsAPI;
    }

    public Map<Player, Integer> getPlayerArena() {
        return playerArena;
    }

    public void removePlayersFromArena(int arenaId, List<Player> players) {
        for (Player player : players) {
            playerArena.remove(player);
        }
    }

    private void resetWorldDefaults(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        if (context == null || context.getArenaAPI() == null) {
            return;
        }
        World world = context.getArenaAPI().getWorld();
        if (world == null) {
            return;
        }
        world.setTime(1000L);
        world.setStorm(false);
        world.setThundering(false);
    }

    private void resetPlayerHearts(List<Player> players) {
        if (players == null) {
            return;
        }
        Attribute maxHealthAttribute = maxHealthAttribute();
        for (Player player : players) {
            if (player == null) {
                continue;
            }
            if (maxHealthAttribute != null && player.getAttribute(maxHealthAttribute) != null) {
                player.getAttribute(maxHealthAttribute).setBaseValue(20.0);
            }
            player.setHealth(Math.min(player.getHealth(), 20.0));
        }
    }

    private void clearPlayerInventories(List<Player> players) {
        if (players == null) {
            return;
        }
        for (Player player : players) {
            if (player == null) {
                continue;
            }
            player.getInventory().clear();
            player.getInventory().setArmorContents(null);
            player.getInventory().setExtraContents(null);
            player.updateInventory();
        }
    }

    public String getScoreboardPath(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        return isSoloMode(context) ? "scoreboard.solo" : "scoreboard.default";
    }

    public boolean isSoloMode(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        if (context == null) {
            return true;
        }
        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        if (teamsAPI == null || !teamsAPI.isEnabled()) {
            return true;
        }
        if (context.getDataAccess() == null) {
            return false;
        }
        Integer teamSize = context.getDataAccess().getGameData("teams.size", Integer.class);
        Integer teamCount = context.getDataAccess().getGameData("teams.count", Integer.class);
        if (teamSize != null && teamSize <= 1) {
            return true;
        }
        return teamCount != null && teamCount <= 1;
    }

    public List<String> getAliveTeamIds(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        if (teamsAPI == null || !teamsAPI.isEnabled()) {
            List<String> ids = new ArrayList<>();
            if (!context.getAlivePlayers().isEmpty()) {
                ids.add("solo");
            }
            return ids;
        }

        Set<String> teamIds = new HashSet<>();
        for (Player player : context.getAlivePlayers()) {
            TeamInfo<Player, Material> team = teamsAPI.getTeam(player);
            if (team != null) {
                teamIds.add(team.getId());
            }
        }
        return new ArrayList<>(teamIds);
    }

    public Map<String, Integer> getTeamKills(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        Map<String, Integer> teamKills = new HashMap<>();
        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        for (Player player : context.getPlayers()) {
            int kills = getPlayerKills(context, player);
            String teamId = "solo";
            if (teamsAPI != null && teamsAPI.isEnabled()) {
                TeamInfo<Player, Material> team = teamsAPI.getTeam(player);
                if (team != null) {
                    teamId = team.getId();
                }
            }
            teamKills.merge(teamId, kills, Integer::sum);
        }
        return teamKills;
    }

    public List<Player> getTeamPlayers(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                       String teamId) {
        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        List<Player> players = new ArrayList<>();
        // Only alive players can receive podium positions/rewards.
        for (Player player : context.getAlivePlayers()) {
            if (teamsAPI == null || !teamsAPI.isEnabled()) {
                players.add(player);
                continue;
            }
            TeamInfo<Player, Material> team = teamsAPI.getTeam(player);
            if (team != null && team.getId().equalsIgnoreCase(teamId)) {
                players.add(player);
            }
        }
        return players;
    }

    public void checkForTeamVictory(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        ArenaState state = getArenaState(context);
        if (state == null || state.isEnded()) {
            return;
        }

        if (shouldEndForVictory(context)) {
            endGame(context);
        }
    }

    private void startGameTimer(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                ArenaState state) {
        int arenaId = context.getArenaId();
        int fallProtectionSeconds = Math.max(0, moduleConfig.getInt("spawn_protection.fall_damage_seconds", 5));

        int gameTime = moduleConfig.getInt("game.time_limit_seconds", 0);
        boolean hasTimeLimit = gameTime > 0;
        final int[] timeLeft = {gameTime};
        String taskId = "arena_" + arenaId + "_lucky_pillars_timer";

        context.getSchedulerAPI().runTimer(taskId, () -> {
            if (state.isEnded()) {
                context.getSchedulerAPI().cancelTask(taskId);
                return;
            }

            state.incrementMatchSeconds();
            handleScheduledEvents(context, state);
            refreshFallProtection(state, context.getPlayers(), fallProtectionSeconds);

            List<Player> alivePlayers = context.getAlivePlayers();
            List<Player> allPlayers = context.getPlayers();

            if (hasTimeLimit && timeLeft[0] > 0) {
                timeLeft[0]--;
                if (timeLeft[0] <= 0) {
                    endGame(context);
                    return;
                }
            }

            if (shouldEndForVictory(context)) {
                endGame(context);
                return;
            }
            for (Player player : allPlayers) {
                String actionBarTemplate = coreConfig.getLanguage(player, "action_bar.in_game.global");
                if (!player.isOnline()) {
                    continue;
                }

                Map<String, String> customPlaceholders = placeholderService.buildPlaceholders(player);
                if (hasTimeLimit && timeLeft[0] > 0) {
                    customPlaceholders.put("time", formatCountdownTime(timeLeft[0]));
                }
                customPlaceholders.put("alive", String.valueOf(alivePlayers.size()));
                customPlaceholders.put("spectators", String.valueOf(context.getSpectators().size()));

                if (actionBarTemplate != null && hasTimeLimit) {
                    String actionBarMessage = actionBarTemplate
                            .replace("{time}", formatCountdownTime(timeLeft[0]))
                            .replace("{round}", String.valueOf(context.getCurrentRound()))
                            .replace("{round_max}", String.valueOf(context.getMaxRounds()));
                    context.getMessagesAPI().sendActionBar(player, actionBarMessage);
                }

                context.getScoreboardAPI().update(player, getScoreboardPath(context), customPlaceholders);
            }
        }, 0L, 20L);
    }

    private boolean shouldEndForVictory(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        if (teamsAPI != null && teamsAPI.isEnabled()) {
            return getAliveTeamIds(context).size() <= 1;
        }
        return context.getAlivePlayers().size() <= 1;
    }

    private void handleScheduledEvents(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                       ArenaState state) {
        // No scheduled events in Lucky Pillars
    }

    private void broadcastEventMessage(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                       String languagePath) {
        String message = moduleConfig.getTranslation(null, languagePath);
        if (message == null || message.isBlank()) {
            return;
        }
        for (Player player : context.getPlayers()) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            context.getMessagesAPI().sendRaw(player, message);
        }
    }

    private void registerFallProtection(ArenaState state, Player player) {
        if (state == null || player == null) {
            return;
        }
        int protectionSeconds = Math.max(0, moduleConfig.getInt("spawn_protection.fall_damage_seconds", 5));
        if (protectionSeconds <= 0) {
            return;
        }
        state.setFallProtection(player.getUniqueId(), System.currentTimeMillis() + (protectionSeconds * 1000L));
    }

    private void refreshFallProtection(ArenaState state, List<Player> players, int protectionSeconds) {
        if (state == null || protectionSeconds <= 0) {
            return;
        }
        for (Player player : players) {
            if (player == null) {
                continue;
            }
            if (state.hasFallProtection(player.getUniqueId())) {
                continue;
            }
            if (state.getMatchSeconds() == 1) {
                state.setFallProtection(player.getUniqueId(),
                        System.currentTimeMillis() + (protectionSeconds * 1000L));
            }
        }
    }

    /**
     * Applies the selected modifier effects to the game
     */
    private void applyModifier(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                               ArenaState state) {
        if (context == null || state == null) {
            return;
        }

        String modifier = state.getSelectedModifier();
        if (modifier == null || "none".equalsIgnoreCase(modifier)) {
            return; // No modifier selected
        }

        List<Player> players = resolveModifierPlayers(context);
        if (players.isEmpty()) {
            return;
        }

        switch (modifier.toLowerCase()) {
            case "elytra":
                applyElytraModifier(players);
                break;
            case "swap":
                startPositionSwapTimer(context, state);
                break;
            case "speed":
                // Speed modifier affects item distribution (handled separately)
                break;
            case "slow_fall":
                applySlowFallModifier(players);
                break;
            case "invisibility":
                applyInvisibilityModifier(players);
                break;
            case "double_health":
                applyDoubleHealthModifier(players);
                break;
            case "one_heart":
                applyOneHeartModifier(players);
                break;
            case "unbreakable":
                state.setBlockBreakingDisabled(true);
                break;
            case "ultra_jump":
                applyUltraJumpModifier(players);
                break;
            case "rising_lava":
                startRisingLava(context, state);
                break;
        }
    }

    private List<Player> resolveModifierPlayers(
            GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        if (context == null) {
            return List.of();
        }
        List<Player> alive = context.getAlivePlayers();
        if (alive != null && !alive.isEmpty()) {
            return alive;
        }
        List<Player> players = context.getPlayers();
        return players != null ? players : List.of();
    }

    private void applyElytraModifier(List<Player> players) {
        if (players == null || players.isEmpty()) {
            return;
        }
        for (Player player : players) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            ItemStack elytra = new ItemStack(Material.ELYTRA);
            player.getInventory().setChestplate(elytra);
            // Ensure the client sees the equipped armor immediately
            player.updateInventory();
        }
    }

    private void startPositionSwapTimer(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                        ArenaState state) {
        int arenaId = context.getArenaId();
        String taskId = "arena_" + arenaId + "_position_swap";
        
        context.getSchedulerAPI().runTimer(taskId, () -> {
            List<Player> alivePlayers = context.getAlivePlayers();
            if (alivePlayers == null || alivePlayers.size() < 2) {
                return;
            }

            // Shuffle players and swap their positions
            List<Location> locations = new ArrayList<>();
            for (Player player : alivePlayers) {
                if (player != null && player.isOnline()) {
                    locations.add(player.getLocation().clone());
                }
            }

            if (locations.size() >= 2) {
                java.util.Collections.shuffle(locations);
                for (int i = 0; i < alivePlayers.size() && i < locations.size(); i++) {
                    Player player = alivePlayers.get(i);
                    if (player != null && player.isOnline()) {
                        player.teleport(locations.get(i));
                        // Add particle effect
                        player.getWorld().spawnParticle(
                                org.bukkit.Particle.PORTAL,
                                player.getLocation(),
                                50,
                                0.5, 0.5, 0.5,
                                0.1
                        );
                    }
                }
            }
        }, 300L, 300L); // Every 15 seconds (300 ticks)
    }

    private void applySlowFallModifier(List<Player> players) {
        for (Player player : players) {
            if (player != null && player.isOnline()) {
                player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.SLOW_FALLING,
                        999999, // Infinite duration
                        0, // Amplifier
                        false,
                        false
                ));
            }
        }
    }

    private void applyInvisibilityModifier(List<Player> players) {
        for (Player player : players) {
            if (player != null && player.isOnline()) {
                player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.INVISIBILITY,
                        600, // 30 seconds
                        0, // Amplifier
                        false,
                        false
                ));
            }
        }
    }

    private void applyDoubleHealthModifier(List<Player> players) {
        Attribute maxHealthAttribute = maxHealthAttribute();
        for (Player player : players) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (maxHealthAttribute != null && player.getAttribute(maxHealthAttribute) != null) {
                player.getAttribute(maxHealthAttribute).setBaseValue(40.0); // 20 hearts
            }
            player.setHealth(Math.min(40.0, player.getMaxHealth()));
        }
    }

    private void applyOneHeartModifier(List<Player> players) {
        Attribute maxHealthAttribute = maxHealthAttribute();
        for (Player player : players) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (maxHealthAttribute != null && player.getAttribute(maxHealthAttribute) != null) {
                player.getAttribute(maxHealthAttribute).setBaseValue(2.0); // 1 heart
            }
            player.setHealth(Math.min(2.0, player.getMaxHealth()));
        }
    }

    private void applyUltraJumpModifier(List<Player> players) {
        org.bukkit.potion.PotionEffectType jumpEffectType =
                org.bukkit.potion.PotionEffectType.getByName("JUMP_BOOST");
        if (jumpEffectType == null) {
            jumpEffectType = org.bukkit.potion.PotionEffectType.getByName("JUMP");
        }
        if (jumpEffectType == null) {
            return;
        }

        for (Player player : players) {
            if (player != null && player.isOnline()) {
                player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        jumpEffectType,
                        999999, // Infinite duration
                        4, // Amplifier (very high jump)
                        false,
                        false
                ));
            }
        }
    }

    /**
     * Starts the rising lava modifier: lava fills one full layer of the play area
     * every configured interval, starting from the lowest layer.
     */
    private void startRisingLava(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                 ArenaState state) {
        World world = context.getArenaAPI() != null ? context.getArenaAPI().getWorld() : null;
        if (world == null) {
            return;
        }

        int intervalSeconds = moduleConfig.getInt("modifiers.rising_lava.rise_interval_seconds", 30);
        if (intervalSeconds <= 0) {
            return;
        }

        int[] bounds = resolveRisingLavaBounds(context);
        if (bounds == null) {
            Bukkit.getLogger().warning("[LuckyPillars] Rising lava modifier skipped in arena "
                    + context.getArenaId() + ": no play area region configured.");
            return;
        }

        int minX = bounds[0];
        int minZ = bounds[2];
        int maxX = bounds[3];
        int maxZ = bounds[5];
        int startY = Math.max(bounds[1], world.getMinHeight());
        int endY = Math.min(bounds[4], world.getMaxHeight() - 1);
        if (startY > endY) {
            return;
        }

        long layerBlocks = (long) (maxX - minX + 1) * (long) (maxZ - minZ + 1);
        int maxLayerBlocks = moduleConfig.getInt("modifiers.rising_lava.max_layer_blocks", 100000);
        if (maxLayerBlocks > 0 && layerBlocks > maxLayerBlocks) {
            Bukkit.getLogger().warning("[LuckyPillars] Rising lava modifier skipped in arena "
                    + context.getArenaId() + ": layer size " + layerBlocks
                    + " blocks exceeds the limit of " + maxLayerBlocks + ".");
            return;
        }

        int arenaId = context.getArenaId();
        String taskId = "arena_" + arenaId + "_rising_lava";
        long intervalTicks = intervalSeconds * 20L;
        int[] currentY = {startY};

        fillLavaLayer(world, minX, maxX, minZ, maxZ, currentY[0]++);

        context.getSchedulerAPI().runTimer(taskId, () -> {
            if (currentY[0] > endY) {
                context.getSchedulerAPI().cancelTask(taskId);
                return;
            }
            fillLavaLayer(world, minX, maxX, minZ, maxZ, currentY[0]++);
        }, intervalTicks, intervalTicks);
    }

    /**
     * Resolves the play area bounds ({@code game.play_area.bounds}) configured during
     * arena setup. Falls back to the arena bounds when no play area was saved.
     * Returns {@code [minX, minY, minZ, maxX, maxY, maxZ]} or null when unavailable.
     */
    private int[] resolveRisingLavaBounds(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        DataAccess<Location> dataAccess = context.getDataAccess();
        if (dataAccess != null) {
            Double minX = dataAccess.getGameData("game.play_area.bounds.min.x", Double.class);
            Double minY = dataAccess.getGameData("game.play_area.bounds.min.y", Double.class);
            Double minZ = dataAccess.getGameData("game.play_area.bounds.min.z", Double.class);
            Double maxX = dataAccess.getGameData("game.play_area.bounds.max.x", Double.class);
            Double maxY = dataAccess.getGameData("game.play_area.bounds.max.y", Double.class);
            Double maxZ = dataAccess.getGameData("game.play_area.bounds.max.z", Double.class);
            if (minX != null && minY != null && minZ != null && maxX != null && maxY != null && maxZ != null) {
                return new int[]{
                        (int) Math.floor(minX), (int) Math.floor(minY), (int) Math.floor(minZ),
                        (int) Math.floor(maxX), (int) Math.floor(maxY), (int) Math.floor(maxZ)
                };
            }
        }

        if (context.getArenaAPI() == null) {
            return null;
        }
        Location boundsMin = context.getArenaAPI().getBoundsMin();
        Location boundsMax = context.getArenaAPI().getBoundsMax();
        if (boundsMin == null || boundsMax == null) {
            return null;
        }
        return new int[]{
                Math.min(boundsMin.getBlockX(), boundsMax.getBlockX()),
                Math.min(boundsMin.getBlockY(), boundsMax.getBlockY()),
                Math.min(boundsMin.getBlockZ(), boundsMax.getBlockZ()),
                Math.max(boundsMin.getBlockX(), boundsMax.getBlockX()),
                Math.max(boundsMin.getBlockY(), boundsMax.getBlockY()),
                Math.max(boundsMin.getBlockZ(), boundsMax.getBlockZ())
        };
    }

    private void fillLavaLayer(World world, int minX, int maxX, int minZ, int maxZ, int y) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                Block block = world.getBlockAt(x, y, z);
                if (block.getType() == Material.AIR) {
                    // Physics disabled so the lava stays in place instead of flowing
                    block.setType(Material.LAVA, false);
                }
            }
        }
    }

    /**
     * Starts the random item distribution timer for Lucky Pillars.
     */
    private void startItemDistribution(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                       ArenaState state) {
        if (context == null || state == null) {
            return;
        }

        boolean enabled = moduleConfig.getBoolean("item_distribution.enabled", true);
        if (!enabled) {
            return;
        }

        int baseInterval = moduleConfig.getInt("game.item_distribution_interval", 3);
        if (baseInterval <= 0) {
            return;
        }

        int intervalSeconds = baseInterval;
        String modifier = state.getSelectedModifier();
        if ("speed".equalsIgnoreCase(modifier)) {
            intervalSeconds = Math.max(1, baseInterval / 2);
        }

        int arenaId = context.getArenaId();
        String taskId = "arena_" + arenaId + "_item_distribution";
        long intervalTicks = intervalSeconds * 20L;
        long intervalMillis = intervalTicks * 50L;

        state.setNextBlockDropAtMillis(System.currentTimeMillis() + 50L);
        context.getSchedulerAPI().runTimer(taskId, () -> {
            distributeRandomItem(context);
            state.setNextBlockDropAtMillis(System.currentTimeMillis() + intervalMillis);
        }, 1L, intervalTicks);
    }

    /**
     * Distributes a random Minecraft item to all alive players.
     */
    private void distributeRandomItem(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        if (context == null || context.getPhase() != GamePhase.PLAYING) {
            return;
        }

        List<Player> alivePlayers = context.getAlivePlayers();
        if (alivePlayers == null || alivePlayers.isEmpty()) {
            return;
        }

        List<Material> candidates = buildRandomItemCandidates();
        if (candidates.isEmpty()) {
            return;
        }

        for (Player player : alivePlayers) {
            if (player != null && player.isOnline()) {
                ItemStack item = selectRandomItem(candidates);
                if (item == null || item.getType() == Material.AIR) {
                    continue;
                }
                giveItemToPlayer(player, item.clone());
            }
        }
    }

    /**
     * Builds the pool of random item candidates.
     * <p>
     * Any material that is a valid item is eligible, not just blocks. The pool is
     * filtered by the configured blacklist and by a small hard-coded set of
     * game-breaking or creative-only items (command blocks, spawn eggs, etc.).
     * </p>
     */
    private List<Material> buildRandomItemCandidates() {
        List<String> blacklistValues = moduleConfig.getStringList("item_distribution.blacklist");
        if (blacklistValues == null || blacklistValues.isEmpty()) {
            blacklistValues = moduleConfig.getStringList("item_distribution.block_blacklist");
        }
        Set<Material> blacklist = new HashSet<>();

        if (blacklistValues != null) {
            for (String value : blacklistValues) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                try {
                    blacklist.add(Material.valueOf(value.trim().toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                    // Ignore invalid configured materials
                }
            }
        }

        List<Material> candidates = new ArrayList<>();
        for (Material material : Material.values()) {
            if (!material.isItem()) {
                continue;
            }
            if (material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR) {
                continue;
            }
            if (blacklist.contains(material)) {
                continue;
            }
            if (isHiddenOrCreativeItem(material)) {
                continue;
            }
            candidates.add(material);
        }

        if (candidates.isEmpty()) {
            return List.of();
        }

        return candidates;
    }

    /**
     * Returns true for items that should never be given to players randomly,
     * even if they are not in the configured blacklist.
     */
    private boolean isHiddenOrCreativeItem(Material material) {
        String name = material.name();
        if (name.startsWith("LEGACY_") || name.endsWith("_SPAWN_EGG")) {
            return true;
        }
        if (name.contains("COMMAND_BLOCK")) {
            return true;
        }
        return switch (name) {
            case "BARRIER", "LIGHT", "STRUCTURE_VOID", "STRUCTURE_BLOCK", "JIGSAW",
                 "DEBUG_STICK", "KNOWLEDGE_BOOK", "END_PORTAL_FRAME", "END_GATEWAY" -> true;
            default -> false;
        };
    }

    /**
     * Picks one random item from a prepared candidate list.
     */
    private ItemStack selectRandomItem(List<Material> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        int randomIndex = java.util.concurrent.ThreadLocalRandom.current().nextInt(candidates.size());
        return new ItemStack(candidates.get(randomIndex), 1);
    }

    /**
     * Gives an item to a player, adding to first available slot.
     */
    private void giveItemToPlayer(Player player, ItemStack item) {
        if (player == null || item == null) {
            return;
        }

        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            for (ItemStack remaining : leftover.values()) {
                player.getWorld().dropItem(player.getLocation(), remaining);
            }
        }
    }

    private Attribute maxHealthAttribute() {
        Attribute attribute = attributeConstant("MAX_HEALTH");
        return attribute != null ? attribute : attributeConstant("GENERIC_MAX_HEALTH");
    }

    private Attribute attributeConstant(String fieldName) {
        try {
            Object value = Attribute.class.getField(fieldName).get(null);
            return value instanceof Attribute attribute ? attribute : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static String formatCountdownTime(int seconds) {
        int safeSeconds = Math.max(0, seconds);
        return String.format("%02d:%02d", safeSeconds / 60, safeSeconds % 60);
    }

}
