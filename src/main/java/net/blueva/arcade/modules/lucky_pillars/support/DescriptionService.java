package net.blueva.arcade.modules.lucky_pillars.support;

import net.blueva.arcade.api.config.ModuleConfigAPI;
import net.blueva.arcade.api.game.GameContext;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class DescriptionService {

    private final ModuleConfigAPI moduleConfig;

    public DescriptionService(ModuleConfigAPI moduleConfig) {
        this.moduleConfig = moduleConfig;
    }

    public void sendDescription(GameContext<Player, Location, World, Material, ItemStack, Sound, Block, Entity> context) {
        for (Player player : context.getPlayers()) {
            List<String> description = moduleConfig.getTranslationList(player, "description");
            if (description == null || description.isEmpty()) {
                continue;
            }
            for (String line : description) {
                context.getMessagesAPI().sendRaw(player, line);
            }
        }
    }
}
