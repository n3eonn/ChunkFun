package dev.chunkfun.supply;

import dev.chunkfun.ChunkFunPlugin;
import dev.chunkfun.effects.Effects;
import dev.chunkfun.util.Text;
import java.util.List;
import java.util.Random;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.CaveSpider;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.entity.Silverfish;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/** Runs the outcome of a broken lucky block at its position, for the player who broke it. */
final class LuckyBlockOpener {

    private final ChunkFunPlugin plugin;
    private final SupplyLoot loot;
    private final Random random = new Random();

    LuckyBlockOpener(ChunkFunPlugin plugin, SupplyLoot loot) {
        this.plugin = plugin;
        this.loot = loot;
    }

    void open(Player player, Location block, SupplyCategory category, SupplyTier tier) {
        Location center = block.clone().add(0.5, 0.5, 0.5);
        LuckyOutcome outcome = this.plugin.settings().supply().outcomes().pick(this.random);
        Effects.burst(center, Particle.TOTEM_OF_UNDYING, 40, 0.4, 0.4);
        switch (outcome) {
            case GOOD -> this.good(player, center, category, tier);
            case NEUTRAL -> this.neutral(player, center);
            case BAD -> this.bad(player, center);
        }
    }

    private void good(Player player, Location center, SupplyCategory category, SupplyTier tier) {
        World world = center.getWorld();
        List<ItemStack> items = this.loot.luckyLoot(category, tier, this.random);
        for (ItemStack item : items) {
            world.dropItemNaturally(center, item);
        }
        Effects.fireworkBurst(center.clone().add(0.0, 1.5, 0.0));
        Effects.burst(center, Particle.HAPPY_VILLAGER, 25, 0.6, 0.1);
        world.playSound(Text.sound("entity.player.levelup", 1.0f, 1.3f), center.getX(), center.getY(), center.getZ());
        player.sendActionBar(Component.text("LUCKY! ", NamedTextColor.GREEN, TextDecoration.BOLD)
                .append(Component.text(tier.displayName() + " ", tier.color()))
                .append(Component.text(category.displayName(), category.color())));
    }

    private void neutral(Player player, Location center) {
        World world = center.getWorld();
        Component what;
        switch (this.random.nextInt(4)) {
            case 0 -> {
                for (int i = 0; i < 8; i++) {
                    world.spawn(center, ExperienceOrb.class, orb -> orb.setExperience(6));
                }
                what = Component.text("an XP shower", NamedTextColor.YELLOW);
            }
            case 1 -> {
                for (int i = 0; i < 4; i++) {
                    int delay = i * 6;
                    Bukkit.getScheduler().runTaskLater(this.plugin, () -> Effects.fireworkBurst(
                            center.clone().add(this.random.nextDouble(-2, 2), 2 + this.random.nextDouble(2), this.random.nextDouble(-2, 2))), delay);
                }
                what = Component.text("a firework show", NamedTextColor.YELLOW);
            }
            case 2 -> {
                PotionEffectType[] buffs = {PotionEffectType.SPEED, PotionEffectType.HASTE, PotionEffectType.REGENERATION,
                        PotionEffectType.JUMP_BOOST, PotionEffectType.NIGHT_VISION};
                PotionEffectType buff = buffs[this.random.nextInt(buffs.length)];
                player.addPotionEffect(new PotionEffect(buff, 20 * 30, buff == PotionEffectType.REGENERATION ? 0 : 1));
                what = Component.text("a random buff", NamedTextColor.YELLOW);
            }
            default -> {
                world.dropItemNaturally(center, new ItemStack(Material.CAKE));
                world.dropItemNaturally(center, new ItemStack(Material.COOKIE, 8));
                Effects.fireworkBurst(center.clone().add(0.0, 1.5, 0.0));
                what = Component.text("a cake party", NamedTextColor.YELLOW);
            }
        }
        Effects.burst(center, Particle.END_ROD, 20, 0.5, 0.05);
        world.playSound(Text.sound("block.amethyst_block.chime", 1.5f, 1.0f), center.getX(), center.getY(), center.getZ());
        player.sendActionBar(Component.text("Hmm... ", NamedTextColor.GOLD, TextDecoration.BOLD).append(what));
    }

    private void bad(Player player, Location center) {
        World world = center.getWorld();
        Component what;
        switch (this.random.nextInt(6)) {
            case 0 -> {
                world.spawn(center, TNTPrimed.class, tnt -> tnt.setFuseTicks(40));
                what = Component.text("TNT!", NamedTextColor.RED);
            }
            case 1 -> {
                for (int i = 0; i < 3; i++) {
                    Class<? extends org.bukkit.entity.Monster> type = switch (this.random.nextInt(3)) {
                        case 0 -> Zombie.class;
                        case 1 -> Skeleton.class;
                        default -> CaveSpider.class;
                    };
                    world.spawn(center, type, CreatureSpawnEvent.SpawnReason.CUSTOM, mob -> mob.setTarget(player));
                }
                what = Component.text("an ambush!", NamedTextColor.RED);
            }
            case 2 -> {
                world.strikeLightning(player.getLocation());
                what = Component.text("lightning!", NamedTextColor.RED);
            }
            case 3 -> {
                Location above = player.getLocation().add(0.0, 7.0, 0.0);
                if (above.getBlock().getType().isAir()) {
                    world.spawn(above, FallingBlock.class, anvil -> {
                        anvil.setBlockData(Material.ANVIL.createBlockData());
                        anvil.setHurtEntities(true);
                        anvil.setDamagePerBlock(1.5f);
                        anvil.setMaxDamage(12);
                    });
                    what = Component.text("look up!", NamedTextColor.RED);
                } else {
                    world.spawn(center, TNTPrimed.class, tnt -> tnt.setFuseTicks(40));
                    what = Component.text("TNT!", NamedTextColor.RED);
                }
            }
            case 4 -> {
                for (int i = 0; i < 5; i++) {
                    world.spawn(center, Silverfish.class, CreatureSpawnEvent.SpawnReason.CUSTOM,
                            mob -> mob.setVelocity(new Vector(this.random.nextDouble(-0.3, 0.3), 0.3, this.random.nextDouble(-0.3, 0.3))));
                }
                what = Component.text("silverfish!", NamedTextColor.RED);
            }
            default -> {
                if (this.random.nextBoolean()) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 20 * 3, 1));
                    what = Component.text("up you go!", NamedTextColor.RED);
                } else {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20 * 5, 0));
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 5, 1));
                    what = Component.text("lights out!", NamedTextColor.RED);
                }
            }
        }
        Effects.burst(center, Particle.LARGE_SMOKE, 25, 0.4, 0.05);
        Effects.burst(center, Particle.ANGRY_VILLAGER, 6, 0.5, 0.0);
        world.playSound(Text.sound("entity.witch.celebrate", 1.5f, 1.0f), center.getX(), center.getY(), center.getZ());
        player.sendActionBar(Component.text("UNLUCKY! ", NamedTextColor.DARK_RED, TextDecoration.BOLD).append(what));
    }
}
