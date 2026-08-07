package net.blueva.arcade.modules.lucky_pillars.listener;

import net.blueva.arcade.api.game.GameContext;
import net.blueva.arcade.api.game.GamePhase;
import net.blueva.arcade.api.team.TeamInfo;
import net.blueva.arcade.api.team.TeamsAPI;
import net.blueva.arcade.modules.lucky_pillars.game.LuckyPillarsGame;
import net.blueva.arcade.modules.lucky_pillars.state.ArenaState;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.ItemStack;

public class LuckyPillarsListener implements Listener {

    private final LuckyPillarsGame game;

    public LuckyPillarsListener(LuckyPillarsGame game) {
        this.game = game;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context =
                game.getContext(player);

        if (context == null || !context.isPlayerPlaying(player)) {
            return;
        }

        if (event.getTo() == null) {
            return;
        }

        if (context.getPhase() != GamePhase.PLAYING) {
            return;
        }

        if (!context.isInsideBounds(event.getTo())) {
            game.handleNonCombatDeath(context, player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context =
                game.getContext(player);

        if (context == null || !context.isPlayerPlaying(player)) {
            return;
        }

        if (context.getPhase() != GamePhase.PLAYING) {
            event.setCancelled(true);
            return;
        }

        // Check for Unbreakable modifier
        ArenaState state = game.getArenaState(context);
        if (state != null && state.isBlockBreakingDisabled()) {
            event.setCancelled(true);
            context.getMessagesAPI().sendRaw(player, "<red>Block breaking is disabled! (Unbreakable modifier)</red>");
            return;
        }

        if (!context.isInsideBounds(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context =
                game.getContext(player);

        if (context == null || !context.isPlayerPlaying(player)) {
            return;
        }

        if (context.getPhase() != GamePhase.PLAYING) {
            event.setCancelled(true);
            return;
        }

        if (!context.isInsideBounds(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpectatorInteractEntity(PlayerInteractEntityEvent event) {
        if (!isArenaSpectator(event.getPlayer())) {
            return;
        }
        if (event.getRightClicked() instanceof Vehicle) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpectatorVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player && isArenaSpectator(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpectatorVehicleDamage(VehicleDamageEvent event) {
        if (!(event.getAttacker() instanceof Player player)) {
            return;
        }
        if (isArenaSpectator(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpectatorVehicleDestroy(VehicleDestroyEvent event) {
        if (!(event.getAttacker() instanceof Player player)) {
            return;
        }
        if (isArenaSpectator(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker != null && isArenaSpectator(attacker) && event.getEntity() instanceof Vehicle) {
            event.setCancelled(true);
            return;
        }

        if (!(event.getEntity() instanceof Player target)) {
            return;
        }

        GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context =
                game.getContext(target);
        if (context == null || !context.isPlayerPlaying(target)) {
            return;
        }

        if (context.getPhase() != GamePhase.PLAYING) {
            event.setCancelled(true);
            return;
        }

        if (attacker == null || !context.isPlayerPlaying(attacker)) {
            event.setCancelled(true);
            return;
        }

        TeamsAPI<Player, Material> teamsAPI = context.getTeamsAPI();
        if (teamsAPI != null && teamsAPI.isEnabled()) {
            TeamInfo<Player, Material> attackerTeam = teamsAPI.getTeam(attacker);
            TeamInfo<Player, Material> targetTeam = teamsAPI.getTeam(target);
            if (attackerTeam != null && targetTeam != null && attackerTeam.getId().equalsIgnoreCase(targetTeam.getId())) {
                event.setCancelled(true);
                return;
            }
        }

        double finalHealth = target.getHealth() - event.getFinalDamage();
        if (finalHealth > 0) {
            return;
        }

        // Let vanilla Totem of Undying activate instead of forcing elimination
        if (hasTotemOfUndying(target)) {
            return;
        }

        event.setCancelled(true);
        game.handleKill(context, attacker, target);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGenericDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player target)) {
            return;
        }

        if (event instanceof EntityDamageByEntityEvent) {
            return;
        }

        GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context =
                game.getContext(target);
        if (context == null || !context.isPlayerPlaying(target)) {
            return;
        }

        if (context.getPhase() != GamePhase.PLAYING) {
            event.setCancelled(true);
            return;
        }

        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            ArenaState state = game.getArenaState(context);
            if (state != null && state.hasFallProtection(target.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
        }

        double finalHealth = target.getHealth() - event.getFinalDamage();
        if (finalHealth > 0) {
            return;
        }

        // Let vanilla Totem of Undying activate instead of forcing elimination
        if (hasTotemOfUndying(target)) {
            return;
        }

        event.setCancelled(true);
        game.handleNonCombatDeath(context, target);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        game.onPlayerQuit(event.getPlayer());
    }

    private boolean isArenaSpectator(Player player) {
        if (player == null) {
            return false;
        }

        GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context =
                game.getContext(player);
        if (context == null) {
            return false;
        }

        if (context.getSpectators() != null && context.getSpectators().contains(player)) {
            return true;
        }

        // Fallback for spectators that are still tracked in the arena but not listed yet
        return player.getGameMode() == GameMode.SPECTATOR
                && (context.getPhase() == GamePhase.PLAYING || context.getPhase() == GamePhase.ENDING);
    }

    private boolean hasTotemOfUndying(Player player) {
        if (player == null) {
            return false;
        }
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        return (mainHand != null && mainHand.getType() == Material.TOTEM_OF_UNDYING)
                || (offHand != null && offHand.getType() == Material.TOTEM_OF_UNDYING);
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }

        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }

        return null;
    }
}
