package net.blueva.arcade.modules.lucky_pillars.support.combat;

import net.blueva.arcade.api.ModuleAPI;
import net.blueva.arcade.api.config.CoreConfigAPI;
import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.api.stats.StatsAPI;
import net.blueva.arcade.api.visuals.VisualEffectsAPI;
import net.blueva.arcade.modules.lucky_pillars.game.LuckyPillarsGame;
import net.blueva.arcade.modules.lucky_pillars.support.loadout.PlayerLoadoutService;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class CombatService {

    private final ModuleConfigAPI moduleConfig;
    private final CoreConfigAPI coreConfig;
    private final StatsAPI statsAPI;
    private final LuckyPillarsGame game;
    private final PlayerLoadoutService loadoutService;

    public CombatService(ModuleConfigAPI moduleConfig,
                         CoreConfigAPI coreConfig,
                         StatsAPI statsAPI,
                         LuckyPillarsGame game,
                         PlayerLoadoutService loadoutService) {
        this.moduleConfig = moduleConfig;
        this.coreConfig = coreConfig;
        this.statsAPI = statsAPI;
        this.game = game;
        this.loadoutService = loadoutService;
    }

    public void handleKillCredit(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                 Player killer) {
        if (context == null || killer == null) {
            return;
        }

        if (statsAPI != null) {
            statsAPI.addModuleStat(killer, game.getModuleInfo().getId(), "kills", 1);
        }

        game.addPlayerKill(context, killer);
        game.healKiller(context, killer);
    }

    public void handleElimination(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                  Player target,
                                  Player killer) {
        if (context == null || target == null) {
            return;
        }

        // Don't eliminate spectators
        if (context.getSpectators().contains(target)) {
            return;
        }

        Location deathLocation = target.getLocation();
        playVisualEffects(target, killer, deathLocation);

        broadcastDeathMessage(context, target, killer);
        dropInventoryAtLocation(target, deathLocation);
        context.eliminatePlayer(target, moduleConfig.getTranslation(target, "messages.eliminated"));
        context.setPlayerSpectating(target, true);
        sendDeathTitle(context, target, killer != null);
    }

    private void dropInventoryAtLocation(Player player, Location deathLocation) {
        if (player == null || deathLocation == null || deathLocation.getWorld() == null) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        dropItems(deathLocation, inventory.getStorageContents());
        dropItems(deathLocation, inventory.getArmorContents());
        dropItems(deathLocation, new ItemStack[]{inventory.getItemInOffHand()});
        inventory.clear();
    }

    private void dropItems(Location location, ItemStack[] contents) {
        if (contents == null || contents.length == 0) {
            return;
        }

        for (ItemStack item : contents) {
            if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
                continue;
            }
            location.getWorld().dropItemNaturally(location, item.clone());
        }
    }

    private void playVisualEffects(Player target, Player killer, Location deathLocation) {
        VisualEffectsAPI visualEffectsAPI = ModuleAPI.getVisualEffectsAPI();
        if (visualEffectsAPI == null) {
            return;
        }
        if (deathLocation != null) {
            visualEffectsAPI.playDeathEffect(target, deathLocation);
        } else {
            visualEffectsAPI.playDeathEffect(target);
        }
        if (killer != null) {
            visualEffectsAPI.playKillEffect(killer);
        }
    }

    private void sendDeathTitle(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                Player target,
                                boolean killed) {
        if (killed) {
            context.getSoundsAPI().play(target, coreConfig.getSound("sounds.in_game.dead"));
            context.getTitlesAPI().sendRaw(target,
                    moduleConfig.getTranslation(target, "titles.you_died.title"),
                    moduleConfig.getTranslation(target, "titles.you_died.subtitle"),
                    0, 80, 20);
            return;
        }

        context.getSoundsAPI().play(target, coreConfig.getSound("sounds.in_game.classified"));
        context.getTitlesAPI().sendRaw(target,
                moduleConfig.getTranslation(target, "titles.classified.title"),
                moduleConfig.getTranslation(target, "titles.classified.subtitle"),
                0, 80, 20);
    }

    private void broadcastDeathMessage(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                                       Player victim,
                                       Player killer) {
        // Don't broadcast death messages for spectators
        if (context.getSpectators().contains(victim)) {
            return;
        }

        String path = killer != null ? "messages.deaths.killed_by_player" : "messages.deaths.generic";
        String message = getRandomMessage(path);

        if (message == null) {
            return;
        }

        message = message
                .replace("{victim}", victim.getName())
                .replace("{killer}", killer != null ? killer.getName() : "");

        for (Player player : context.getPlayers()) {
            context.getMessagesAPI().sendRaw(player, message);
        }
    }

    private String getRandomMessage(String path) {
        List<String> messages = moduleConfig.getTranslationList(null, path);
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        int index = ThreadLocalRandom.current().nextInt(messages.size());
        return messages.get(index);
    }

    public void healKiller(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context,
                           Player killer) {
        loadoutService.handleKillRegeneration(context, killer);
        context.getSoundsAPI().play(killer, coreConfig.getSound("sounds.in_game.respawn"));
    }
}
