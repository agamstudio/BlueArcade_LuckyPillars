package net.blueva.arcade.modules.lucky_pillars.support.vote;

import net.blueva.arcade.api.ModuleAPI;
import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.api.game.GamePhase;
import net.blueva.arcade.api.ui.ItemAPI;
import net.blueva.arcade.api.ui.LobbyItemDefinition;
import net.blueva.arcade.api.ui.MenuAPI;
import net.blueva.arcade.api.ui.MessageAPI;
import net.blueva.arcade.api.ui.menu.MenuDefinition;
import net.blueva.arcade.api.utils.PlayerUtil;
import net.blueva.arcade.modules.lucky_pillars.game.LuckyPillarsGame;
import net.blueva.arcade.modules.lucky_pillars.state.ArenaState;
import net.blueva.arcade.modules.lucky_pillars.state.VoteState;
import org.bukkit.Bukkit;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simplified vote service for Lucky Pillars single modifier voting
 */
public class LuckyPillarsVoteService {

    private static final String VOTE_PERMISSION_BASE = "bluearcade.lucky_pillars.votes";
    private static final String WAITING_ITEM_ID = "lucky_pillars_vote_settings";
    public static final String COMMAND = "lucky_pillarsvote";
    public static final String MENU_MODIFIERS = "vote_modifiers";

    private static final Set<String> MODIFIER_OPTIONS = Set.of(
            "none", "elytra", "swap", "speed", "slow_fall",
            "invisibility", "double_health", "one_heart", "unbreakable", "ultra_jump"
    );

    private final ModuleConfigAPI moduleConfig;
    private final MenuAPI<Player, Material> menuAPI;
    private final ItemAPI<Player, ItemStack, Material> itemAPI;
    private final String moduleId;
    private final LuckyPillarsVoteMenuRepository menuRepository;
    private final Map<Integer, VoteState> waitingVoteStates = new ConcurrentHashMap<>();
    private LuckyPillarsGame game;

    public LuckyPillarsVoteService(ModuleConfigAPI moduleConfig,
                              MenuAPI<Player, Material> menuAPI,
                              ItemAPI<Player, ItemStack, Material> itemAPI,
                              String moduleId) {
        this.moduleConfig = moduleConfig;
        this.menuAPI = menuAPI;
        this.itemAPI = itemAPI;
        this.moduleId = moduleId;
        this.menuRepository = new LuckyPillarsVoteMenuRepository(moduleConfig);
        this.menuRepository.loadMenus();
        registerMenusWithCore();
    }

    /**
     * Register menu opener with the core so OPEN actions can find our menus.
     */
    private void registerMenusWithCore() {
        LuckyPillarsMenuAPI luckyPillarsMenuAPI = new LuckyPillarsMenuAPI(this.menuAPI, this);
        menuAPI.registerModuleMenuAPI("lucky_pillars", luckyPillarsMenuAPI);
    }

    public VoteState createVoteState() {
        String defaultModifier = normalizeOption(
                moduleConfig.getString("votes.defaults.modifier", "none"), 
                MODIFIER_OPTIONS, 
                "none"
        );
        return new VoteState(defaultModifier);
    }

    public VoteState getWaitingVoteState(int arenaId) {
        return waitingVoteStates.computeIfAbsent(arenaId, id -> createVoteState());
    }

    public void clearWaitingVote(int arenaId, UUID playerId) {
        VoteState state = waitingVoteStates.get(arenaId);
        if (state == null) {
            return;
        }
        state.clearPlayerVotes(playerId);
        if (state.getVoterIds().isEmpty()) {
            waitingVoteStates.remove(arenaId);
        }
    }

    public void cleanStaleVotes() {
        @SuppressWarnings("unchecked")
        PlayerUtil<Player> playerUtil = (PlayerUtil<Player>) ModuleAPI.getPlayerUtil();
        if (playerUtil == null) {
            return;
        }

        for (Map.Entry<Integer, VoteState> entry : new ArrayList<>(waitingVoteStates.entrySet())) {
            cleanStaleVotesForArena(entry.getValue(), entry.getKey());
            if (entry.getValue().getVoterIds().isEmpty()) {
                waitingVoteStates.remove(entry.getKey());
            }
        }
    }

    private void cleanStaleVotesForArena(VoteState state, int arenaId) {
        @SuppressWarnings("unchecked")
        PlayerUtil<Player> playerUtil = (PlayerUtil<Player>) ModuleAPI.getPlayerUtil();
        if (playerUtil == null || state == null) {
            return;
        }

        for (UUID playerId : new ArrayList<>(state.getVoterIds())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                state.clearPlayerVotes(playerId);
                continue;
            }
            Integer playerArena = playerUtil.getPlayerArena(player);
            if (playerArena == null || playerArena != arenaId) {
                state.clearPlayerVotes(playerId);
            }
        }
    }

    public void setGame(LuckyPillarsGame game) {
        this.game = game;
    }

    public void applyPendingVotes(ArenaState state, List<Player> players) {
        if (state == null || players == null || players.isEmpty()) {
            return;
        }
        VoteState voteState = state.getVoteState();
        if (voteState == null) {
            return;
        }

        int arenaId = state.getContext().getArenaId();
        VoteState waiting = getWaitingVoteState(arenaId);
        cleanStaleVotesForArena(waiting, arenaId);

        for (Player player : players) {
            if (player == null) {
                continue;
            }
            String modifier = waiting.getPlayerVote(player.getUniqueId());
            if (modifier != null) {
                voteState.castVote(player.getUniqueId(), modifier);
            }
        }
        waitingVoteStates.remove(arenaId);
    }

    public void registerWaitingItem() {
        if (itemAPI == null || moduleConfig == null) {
            return;
        }

        if (!isWaitingItemEnabled()) {
            unregisterWaitingItem();
            return;
        }

        String materialName = moduleConfig.getString("waiting_items.vote_settings.material", "NAME_TAG");
        Material material;
        try {
            material = Material.valueOf(materialName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            material = Material.NAME_TAG;
        }

        int slot = moduleConfig.getInt("waiting_items.vote_settings.slot", 1);
        String displayName = moduleConfig.getString("waiting_items.vote_settings.display_name");
        List<String> lore = moduleConfig.getStringList("waiting_items.vote_settings.lore");

        LobbyItemDefinition<Material> definition = new LobbyItemDefinition<>(
                WAITING_ITEM_ID,
                material,
                slot,
                displayName,
                lore,
                List.of(),
                true
        );

        itemAPI.registerWaitingItem(moduleId, definition);
    }

    public void registerClickHandler(LuckyPillarsGame game) {
        if (itemAPI == null) {
            return;
        }
        if (!isWaitingItemEnabled()) {
            itemAPI.unregisterClickHandler(WAITING_ITEM_ID);
            return;
        }
        itemAPI.registerClickHandler(WAITING_ITEM_ID,
                player -> game.handleVoteCommand(player, new String[]{"menu", "modifiers"}));
    }

    public void unregisterWaitingItem() {
        if (itemAPI == null) {
            return;
        }
        itemAPI.unregisterWaitingItem(WAITING_ITEM_ID);
        itemAPI.unregisterClickHandler(WAITING_ITEM_ID);
    }

    private boolean isWaitingItemEnabled() {
        return moduleConfig != null && moduleConfig.getBoolean("waiting_items.vote_settings.enabled", true);
    }

    public boolean handleVoteCommand(Player player,
                                     GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                     ArenaState state,
                                     String[] args) {
        if (player == null || context == null || state == null) {
            return false;
        }

        GamePhase phase = context.getPhase();
        if (phase == GamePhase.PLAYING || phase == GamePhase.ENDING || phase == GamePhase.FINISHED) {
            sendMessage(context, player, "votes.messages.not_available");
            return true;
        }

        if (args.length == 0) {
            return openMenu(player, state, MENU_MODIFIERS);
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("menu")) {
            return openMenu(player, state, MENU_MODIFIERS);
        }

        if (action.equals("vote")) {
            if (args.length < 3) {
                sendMessage(context, player, "votes.messages.invalid");
                return true;
            }

            String modifier = args[2].toLowerCase(Locale.ROOT);
            
            if (!isModifierValid(modifier)) {
                sendMessage(context, player, "votes.messages.invalid");
                return true;
            }
            if (!hasModifierPermission(player, modifier)) {
                String message = moduleConfig.getTranslation(player, "votes.messages.no_permission");
                if (message != null) {
                    message = message.replace("{modifier}", getModifierLabel(modifier));
                    context.getMessagesAPI().sendRaw(player, message);
                }
                return true;
            }

            VoteState voteState = state.getVoteState();
            if (voteState == null) {
                return true;
            }

            String previousVote = voteState.getPlayerVote(player.getUniqueId());
            voteState.castVote(player.getUniqueId(), modifier);

            if (!modifier.equals(previousVote)) {
                String modifierLabel = getModifierLabel(modifier);
                String message = moduleConfig.getTranslation(player, "votes.messages.broadcast");
                if (message != null && !message.isBlank()) {
                    int voteCount = voteState.getVotes(modifier);
                    message = message.replace("{player}", player.getName())
                            .replace("{modifier}", modifierLabel)
                            .replace("{votes}", String.valueOf(voteCount));
                    broadcastMessage(context, message);
                }
            }
            return true;
        }

        return openMenu(player, state, MENU_MODIFIERS);
    }

    public boolean handleVoteCommandWithoutContext(Player player, String[] args) {
        if (player == null) {
            return false;
        }

        Integer arenaId = getPlayerArenaId(player);
        if (arenaId == null) {
            return true;
        }

        VoteState waiting = getWaitingVoteState(arenaId);
        cleanStaleVotesForArena(waiting, arenaId);

        String[] safeArgs = args != null ? args : new String[0];
        if (safeArgs.length == 0) {
            return openMenuWaiting(player);
        }

        String action = safeArgs[0].toLowerCase(Locale.ROOT);
        if (action.equals("menu")) {
            return openMenuWaiting(player);
        }

        if (action.equals("vote")) {
            if (safeArgs.length < 3) {
                return true;
            }

            String modifier = safeArgs[2].toLowerCase(Locale.ROOT);
            if (!isModifierValid(modifier)) {
                return true;
            }
            if (!hasModifierPermission(player, modifier)) {
                return true;
            }

            String previousVote = waiting.getPlayerVote(player.getUniqueId());
            waiting.castVote(player.getUniqueId(), modifier);
            if (!modifier.equals(previousVote)) {
                broadcastWaitingVote(player, modifier, waiting);
            }
            return openMenuWaiting(player);
        }

        return false;
    }

    public void applyVotes(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                           ArenaState state) {
        if (context == null || state == null) {
            return;
        }

        VoteState voteState = state.getVoteState();
        if (voteState == null) {
            return;
        }

        String modifier = voteState.resolveWinner();
        state.setSelectedModifier(modifier);
    }

    public void broadcastVoteResults(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                     ArenaState state) {
        if (context == null || state == null) {
            return;
        }
        VoteState voteState = state.getVoteState();
        if (voteState == null) {
            return;
        }

        String modifier = voteState.resolveWinner();
        String modifierLabel = getModifierLabel(modifier);
        
        String source = voteState.hasVotes() ? 
                moduleConfig.getTranslation(null, "votes.messages.selected.sources.popular") :
                moduleConfig.getTranslation(null, "votes.messages.selected.sources.default");
        
        String message = moduleConfig.getTranslation(null, "votes.messages.selected.modifier");
        if (message != null && !message.isBlank()) {
            message = message.replace("{modifier}", modifierLabel)
                    .replace("{source}", source);
            broadcastMessage(context, message);
        }
    }

    private void broadcastWaitingVote(Player player, String modifier, VoteState voteState) {
        if (player == null || modifier == null) {
            return;
        }

        String message = moduleConfig.getTranslation(player, "votes.messages.broadcast");
        if (message == null || message.isBlank()) {
            return;
        }

        int voteCount = voteState != null ? voteState.getVotes(modifier) : 0;
        message = message.replace("{player}", player.getName())
                .replace("{modifier}", getModifierLabel(modifier))
                .replace("{votes}", String.valueOf(voteCount));

        GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context = getGameContext(player);
        if (context == null) {
            broadcastToWaitingArena(player, message);
            return;
        }

        broadcastMessage(context, message);
    }

    private void sendWaitingBroadcast(Player player, String message) {
        if (player == null || message == null || message.isBlank()) {
            return;
        }

        @SuppressWarnings("unchecked")
        MessageAPI<Player> messagesAPI = (MessageAPI<Player>) ModuleAPI.getMessagesAPI();
        if (messagesAPI != null) {
            messagesAPI.sendRaw(player, message);
            return;
        }

        player.sendMessage(message);
    }

    private void broadcastToWaitingArena(Player sender, String message) {
        if (sender == null || message == null || message.isBlank()) {
            return;
        }
        @SuppressWarnings("unchecked")
        PlayerUtil<Player> playerUtil = (PlayerUtil<Player>) ModuleAPI.getPlayerUtil();
        if (playerUtil == null) {
            return;
        }
        Integer senderArenaId = playerUtil.getPlayerArena(sender);
        if (senderArenaId == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        MessageAPI<Player> messagesAPI = (MessageAPI<Player>) ModuleAPI.getMessagesAPI();
        for (Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (online == null || !online.isOnline()) {
                continue;
            }
            Integer onlineArenaId = playerUtil.getPlayerArena(online);
            if (!senderArenaId.equals(onlineArenaId)) {
                continue;
            }
            if (messagesAPI != null) {
                messagesAPI.sendRaw(online, message);
            } else {
                online.sendMessage(message);
            }
        }
    }

    private GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> getGameContext(Player player) {
        if (game == null || player == null) {
            return null;
        }
        return game.getContext(player);
    }

    private boolean openMenu(Player player, ArenaState state, String menuId) {
        VoteState voteState = state != null ? state.getVoteState() : null;
        return openMenu(player, voteState, menuId);
    }

    private boolean openMenuWaiting(Player player) {
        Integer arenaId = getPlayerArenaId(player);
        if (arenaId == null) {
            return openMenu(player, createVoteState(), MENU_MODIFIERS);
        }
        VoteState waiting = getWaitingVoteState(arenaId);
        cleanStaleVotesForArena(waiting, arenaId);
        return openMenu(player, waiting, MENU_MODIFIERS);
    }

    private boolean openMenu(Player player, VoteState voteState, String menuId) {
        if (menuAPI == null || player == null) {
            return false;
        }

        MenuDefinition<Material> menu = menuRepository.getMenu(menuId);
        if (menu == null) {
            return false;
        }

        return menuAPI.openMenu(player, menu, buildPlaceholders(player, voteState));
    }

    private java.util.Map<String, String> buildPlaceholders(Player player, VoteState voteState) {
        java.util.Map<String, String> placeholders = new java.util.HashMap<>();
        for (String option : MODIFIER_OPTIONS) {
            placeholders.put("{votes_modifier_" + option + "}", String.valueOf(voteState != null
                    ? voteState.getVotes(option)
                    : 0));
        }
        placeholders.put("{selected_modifier}", resolveWinningLabel(voteState));
        placeholders.put("{player_vote_modifier}", resolvePlayerVoteLabel(player, voteState));
        return placeholders;
    }

    private String resolveWinningLabel(VoteState voteState) {
        String option = voteState != null ? voteState.resolveWinner() : null;
        return getModifierLabel(option != null ? option : moduleConfig.getString("votes.defaults.modifier", "none"));
    }

    private String resolvePlayerVoteLabel(Player player, VoteState voteState) {
        if (player == null || voteState == null) {
            return getModifierLabel(moduleConfig.getString("votes.defaults.modifier", "none"));
        }
        String option = voteState.getPlayerVote(player.getUniqueId());
        if (option == null) {
            option = voteState.resolveWinner();
        }
        return getModifierLabel(option);
    }

    private Integer getPlayerArenaId(Player player) {
        if (player == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        PlayerUtil<Player> playerUtil = (PlayerUtil<Player>) ModuleAPI.getPlayerUtil();
        if (playerUtil == null) {
            return null;
        }
        return playerUtil.getPlayerArena(player);
    }

    private boolean isModifierValid(String modifier) {
        return modifier != null && MODIFIER_OPTIONS.contains(modifier.toLowerCase(Locale.ROOT));
    }

    private boolean hasModifierPermission(Player player, String modifier) {
        if (player == null || modifier == null) {
            return false;
        }
        String permission = VOTE_PERMISSION_BASE + "." + modifier.toLowerCase(Locale.ROOT);
        return player.hasPermission(permission) || player.hasPermission(VOTE_PERMISSION_BASE + ".*");
    }

    private String getModifierLabel(String modifier) {
        if (modifier == null) {
            return "";
        }
        String label = moduleConfig.getTranslation(null, "votes.labels.modifiers." + modifier.toLowerCase(Locale.ROOT));
        return label != null ? label : modifier;
    }

    private String normalizeOption(String raw, Set<String> validOptions, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return validOptions.contains(normalized) ? normalized : fallback;
    }

    private void sendMessage(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                            Player player, String messagePath) {
        if (context == null || player == null || messagePath == null) {
            return;
        }
        String message = moduleConfig.getTranslation(player, messagePath);
        if (message != null && !message.isBlank()) {
            context.getMessagesAPI().sendRaw(player, message);
        }
    }

    private void broadcastMessage(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                 String message) {
        if (context == null || message == null || message.isBlank()) {
            return;
        }
        for (Player player : context.getPlayers()) {
            if (player != null && player.isOnline()) {
                context.getMessagesAPI().sendRaw(player, message);
            }
        }
    }
}
