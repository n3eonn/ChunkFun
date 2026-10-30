package dev.chunkfun.supply;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Builds the loot of each supply category in each tier; lucky blocks hand out a nerfed share of it.
 *
 * <ul>
 *     <li>LEGENDARY: full netherite gear with the best enchantments, the complete elytra kit, a full stack of
 *     every stackable food and full stacks of crystal-PvP blocks.</li>
 *     <li>RARE: diamond gear with mid-level enchantments, an elytra with fewer rockets and one totem, 16 of every
 *     food and smaller crystal stacks.</li>
 *     <li>COMMON: unenchanted iron gear, a worn-out elytra with a few rockets, basic food and a handful of
 *     crystals.</li>
 * </ul>
 */
public final class SupplyLoot {

    private final Logger logger;
    private List<ItemStack> foods;

    public SupplyLoot(Logger logger) {
        this.logger = logger;
    }

    public List<ItemStack> armor(SupplyTier tier) {
        List<ItemStack> items = new ArrayList<>();
        switch (tier) {
            case LEGENDARY -> {
                items.add(this.enchanted(Material.NETHERITE_HELMET,
                        Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1,
                        Enchantment.RESPIRATION, 3, Enchantment.AQUA_AFFINITY, 1));
                items.add(this.enchanted(Material.NETHERITE_CHESTPLATE,
                        Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1));
                items.add(this.enchanted(Material.NETHERITE_LEGGINGS,
                        Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1,
                        Enchantment.SWIFT_SNEAK, 3));
                items.add(this.enchanted(Material.NETHERITE_BOOTS,
                        Enchantment.PROTECTION, 4, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1,
                        Enchantment.FEATHER_FALLING, 4, Enchantment.DEPTH_STRIDER, 3, Enchantment.SOUL_SPEED, 3));
            }
            case RARE -> {
                items.add(this.enchanted(Material.DIAMOND_HELMET, Enchantment.PROTECTION, 2, Enchantment.UNBREAKING, 2));
                items.add(this.enchanted(Material.DIAMOND_CHESTPLATE, Enchantment.PROTECTION, 2, Enchantment.UNBREAKING, 2));
                items.add(this.enchanted(Material.DIAMOND_LEGGINGS, Enchantment.PROTECTION, 2, Enchantment.UNBREAKING, 2));
                items.add(this.enchanted(Material.DIAMOND_BOOTS, Enchantment.PROTECTION, 2, Enchantment.UNBREAKING, 2,
                        Enchantment.FEATHER_FALLING, 2));
            }
            case COMMON -> {
                items.add(new ItemStack(Material.IRON_HELMET));
                items.add(new ItemStack(Material.IRON_CHESTPLATE));
                items.add(new ItemStack(Material.IRON_LEGGINGS));
                items.add(new ItemStack(Material.IRON_BOOTS));
            }
        }
        return items;
    }

    public List<ItemStack> tools(SupplyTier tier) {
        List<ItemStack> items = new ArrayList<>();
        switch (tier) {
            case LEGENDARY -> {
                items.add(this.enchanted(Material.NETHERITE_PICKAXE,
                        Enchantment.EFFICIENCY, 5, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1, Enchantment.FORTUNE, 3));
                items.add(this.enchanted(Material.NETHERITE_AXE,
                        Enchantment.EFFICIENCY, 5, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1, Enchantment.SHARPNESS, 5,
                        Enchantment.FORTUNE, 3));
                items.add(this.enchanted(Material.NETHERITE_SHOVEL,
                        Enchantment.EFFICIENCY, 5, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1, Enchantment.SILK_TOUCH, 1));
                items.add(this.enchanted(Material.NETHERITE_HOE,
                        Enchantment.EFFICIENCY, 5, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1, Enchantment.FORTUNE, 3));
                items.add(this.enchanted(Material.NETHERITE_SWORD,
                        Enchantment.SHARPNESS, 5, Enchantment.UNBREAKING, 3, Enchantment.MENDING, 1, Enchantment.LOOTING, 3,
                        Enchantment.FIRE_ASPECT, 2, Enchantment.SWEEPING_EDGE, 3));
            }
            case RARE -> {
                items.add(this.enchanted(Material.DIAMOND_PICKAXE, Enchantment.EFFICIENCY, 3, Enchantment.UNBREAKING, 2));
                items.add(this.enchanted(Material.DIAMOND_AXE, Enchantment.EFFICIENCY, 3, Enchantment.UNBREAKING, 2));
                items.add(this.enchanted(Material.DIAMOND_SHOVEL, Enchantment.EFFICIENCY, 3, Enchantment.UNBREAKING, 2));
                items.add(this.enchanted(Material.DIAMOND_HOE, Enchantment.EFFICIENCY, 3, Enchantment.UNBREAKING, 2));
                items.add(this.enchanted(Material.DIAMOND_SWORD, Enchantment.SHARPNESS, 3, Enchantment.UNBREAKING, 2));
            }
            case COMMON -> {
                items.add(new ItemStack(Material.IRON_PICKAXE));
                items.add(new ItemStack(Material.IRON_AXE));
                items.add(new ItemStack(Material.IRON_SHOVEL));
                items.add(new ItemStack(Material.IRON_HOE));
                items.add(new ItemStack(Material.IRON_SWORD));
            }
        }
        return items;
    }

    public List<ItemStack> elytra(SupplyTier tier) {
        List<ItemStack> items = new ArrayList<>();
        switch (tier) {
            case LEGENDARY -> {
                items.add(new ItemStack(Material.ELYTRA));
                items.add(rockets(64, 3));
                items.add(rockets(64, 3));
                for (int i = 0; i < 4; i++) {
                    items.add(new ItemStack(Material.TOTEM_OF_UNDYING));
                }
            }
            case RARE -> {
                items.add(new ItemStack(Material.ELYTRA));
                items.add(rockets(32, 2));
                items.add(new ItemStack(Material.TOTEM_OF_UNDYING));
            }
            case COMMON -> {
                // A worn-out elytra: roughly 15% durability left.
                ItemStack elytra = new ItemStack(Material.ELYTRA);
                int max = Material.ELYTRA.getMaxDurability();
                elytra.editMeta(Damageable.class, meta -> meta.setDamage(max - Math.max(1, max * 15 / 100)));
                items.add(elytra);
                items.add(rockets(8, 1));
            }
        }
        return items;
    }

    public List<ItemStack> crystals(SupplyTier tier) {
        return switch (tier) {
            case LEGENDARY -> List.of(
                    new ItemStack(Material.END_CRYSTAL, 64),
                    new ItemStack(Material.OBSIDIAN, 64),
                    new ItemStack(Material.RESPAWN_ANCHOR, 64),
                    new ItemStack(Material.GLOWSTONE, 64));
            case RARE -> List.of(
                    new ItemStack(Material.END_CRYSTAL, 16),
                    new ItemStack(Material.OBSIDIAN, 32),
                    new ItemStack(Material.RESPAWN_ANCHOR, 8),
                    new ItemStack(Material.GLOWSTONE, 32));
            case COMMON -> List.of(
                    new ItemStack(Material.END_CRYSTAL, 4),
                    new ItemStack(Material.OBSIDIAN, 8),
                    new ItemStack(Material.RESPAWN_ANCHOR, 2),
                    new ItemStack(Material.GLOWSTONE, 8));
        };
    }

    private static ItemStack rockets(int amount, int power) {
        ItemStack rockets = new ItemStack(Material.FIREWORK_ROCKET, amount);
        rockets.editMeta(FireworkMeta.class, meta -> meta.setPower(power));
        return rockets;
    }

    /**
     * Every stackable food, read from the item registry at runtime (every item whose default components include
     * {@code minecraft:food} and whose max stack size is above one), one full stack each. This stays correct for
     * every item the running 26.2 server knows, including data-driven additions.
     */
    public List<ItemStack> foods() {
        if (this.foods == null) {
            Registry<ItemType> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ITEM);
            List<ItemType> types = new ArrayList<>();
            for (ItemType type : registry) {
                if (type.hasDefaultData(DataComponentTypes.FOOD) && type.getMaxStackSize() > 1) {
                    types.add(type);
                }
            }
            types.sort(Comparator.comparing(type -> {
                NamespacedKey key = registry.getKey(type);
                return key == null ? "" : key.toString();
            }));
            List<ItemStack> stacks = new ArrayList<>(types.size());
            for (ItemType type : types) {
                stacks.add(type.createItemStack(type.getMaxStackSize()));
            }
            this.foods = List.copyOf(stacks);
            this.logger.info("Food supply contains " + stacks.size() + " stackable food items.");
        }
        return this.foods.stream().map(ItemStack::clone).toList();
    }

    /** Food contents for one tier, before splitting across boxes. */
    private List<ItemStack> foodsFor(SupplyTier tier) {
        return switch (tier) {
            case LEGENDARY -> this.foods();
            case RARE -> this.foods().stream().map(stack -> {
                stack.setAmount(Math.min(stack.getAmount(), 16));
                return stack;
            }).toList();
            case COMMON -> List.of(
                    new ItemStack(Material.BREAD, 16),
                    new ItemStack(Material.BAKED_POTATO, 16),
                    new ItemStack(Material.CARROT, 16),
                    new ItemStack(Material.APPLE, 8),
                    new ItemStack(Material.COOKED_BEEF, 8),
                    new ItemStack(Material.COOKED_CHICKEN, 8));
        };
    }

    /**
     * The loot of one lucky block: a nerfed share of the category's full set. About half of the items are picked
     * at random (food: four stacks), and stack sizes are halved. The elytra category always keeps its elytra.
     */
    public List<ItemStack> luckyLoot(SupplyCategory category, SupplyTier tier, Random random) {
        List<ItemStack> full = new ArrayList<>(switch (category) {
            case ARMOR -> this.armor(tier);
            case TOOLS -> this.tools(tier);
            case ELYTRA -> this.elytra(tier);
            case FOOD -> this.foodsFor(tier);
            case CRYSTALS -> this.crystals(tier);
        });
        List<ItemStack> result = new ArrayList<>();
        if (category == SupplyCategory.ELYTRA && !full.isEmpty()) {
            result.add(full.removeFirst());
        }
        Collections.shuffle(full, random);
        int picks = category == SupplyCategory.FOOD ? Math.min(4, full.size()) : Math.max(1, full.size() / 2);
        for (int i = 0; i < picks && i < full.size(); i++) {
            ItemStack stack = full.get(i).clone();
            if (stack.getAmount() > 1) {
                stack.setAmount((stack.getAmount() + 1) / 2);
            }
            result.add(stack);
        }
        return result;
    }

    /** Creates an item with the given (enchantment, level) pairs, validating each against the item. */
    private ItemStack enchanted(Material material, Object... pairs) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Enchantment enchantment = (Enchantment) pairs[i];
            int level = (Integer) pairs[i + 1];
            if (!enchantment.canEnchantItem(item)) {
                this.logger.warning(enchantment.getKey() + " cannot be applied to " + material + "; skipped.");
                continue;
            }
            if (level > enchantment.getMaxLevel()) {
                this.logger.warning(enchantment.getKey() + " " + level + " exceeds the maximum level "
                        + enchantment.getMaxLevel() + "; using the maximum.");
                level = enchantment.getMaxLevel();
            }
            if (meta.hasConflictingEnchant(enchantment)) {
                this.logger.warning(enchantment.getKey() + " conflicts with another enchantment on " + material + "; skipped.");
                continue;
            }
            meta.addEnchant(enchantment, level, false);
        }
        item.setItemMeta(meta);
        return item;
    }
}
