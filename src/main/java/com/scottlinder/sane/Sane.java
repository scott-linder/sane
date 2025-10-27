package com.scottlinder.sane;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.view.*;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitScheduler;

import io.papermc.paper.util.Tick;

import java.text.MessageFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.stream.Collectors;

public final class Sane extends JavaPlugin implements Listener {

    static final Material TRADE_REFRESH_MATERIAL = Material.EMERALD;
    static final int TRADE_REFRESH_AMOUNT_PER_LEVEL = 8;

    static final String ERROR_LEVEL_ONE = "You cannot bribe a villager into refreshing their level-1 trades!";
    static final String ERROR_MUST_HOLD = "You must be holding {0} to bribe this villager into refreshing their non-level-1 trades!";
    static final String ERROR_NOT_ENOUGH = "You must have {0} {1} to bribe this villager into refreshing their non-level-1 trades!";
    static final String SUCCESS_YOU_SPENT = "The villager accepted your bribe of {0} {1} and refreshed their non-level-1 trades!";

    static final int TOWN_DIM_X = 250;
    static final int TOWN_DIM_Y = 50;
    static final int TOWN_DIM_Z = 250;

    static final Permission PACIFIER_COMPLETE = new Permission(
        "sane.pacifier.complete",
        "Player will never draw aggro from hostile mobs",
        PermissionDefault.FALSE
    );
    static final Permission PACIFIER_COOLDOWN = new Permission(
        "sane.pacifier.cooldown",
        "Player will not draw aggro from hostile mobs except for during a brief cooldown after attacking one",
        PermissionDefault.FALSE
    );
    static final long PACIFIER_AGGRO_TICKS = Tick.tick().fromDuration(Duration.ofSeconds(10));
    static final long PACIFIER_CLEANUP_PERIOD_TICKS  = Tick.tick().fromDuration(Duration.ofSeconds(1));

    Server server;
    BlockData airData;
    Map<Player, Long> playerLastDamageTick;

    @Override
    public void onEnable() {
        server = getServer();
        airData = server.createBlockData(Material.AIR);
        playerLastDamageTick = new HashMap<Player, Long>();
        PluginManager pluginManager = server.getPluginManager();
        pluginManager.registerEvents(this, this);
        pluginManager.addPermission(PACIFIER_COMPLETE);
        pluginManager.addPermission(PACIFIER_COOLDOWN);
        addReverseRecipes();
        server.getScheduler().runTaskTimer(this, () -> {
            doPacifierCleanup();
        }, PACIFIER_CLEANUP_PERIOD_TICKS, PACIFIER_CLEANUP_PERIOD_TICKS);
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll((Listener) this);
    }

    public List<MerchantRecipe> getTownRecipes(Location location) {
        return location.getWorld().getNearbyEntities(location, TOWN_DIM_X, TOWN_DIM_Y, TOWN_DIM_Z).stream()
                .filter(e -> e.getType() == EntityType.VILLAGER)
                .flatMap(e -> ((Villager) e).getRecipes().stream()
                        .map(r -> {
                            MerchantRecipe mr = new MerchantRecipe(r.getResult(), /* uses= */0,
                                    /* maxUses= */Integer.MAX_VALUE, /* experienceReward= */false);
                            r.getIngredients().forEach(mr::addIngredient);
                            return mr;
                        }))
                .toList();
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK)
            return;
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (!player.isSneaking() || block == null)
            return;
        boolean shouldCancel = true;
        switch (block.getType()) {
            default: {
                shouldCancel = false;
                break;
            }
            case BELL: {
                Bukkit.getScheduler().runTask(this,
                        () -> {
                            MerchantView merchantView = MenuType.MERCHANT.create(player, Component.text("Trading Post"));
                            merchantView.getMerchant().setRecipes(getTownRecipes(block.getLocation()));
                            player.openInventory(merchantView);
                        });
                break;
            }
            case BARREL:
            case BLAST_FURNACE:
            case BREWING_STAND:
            case CARTOGRAPHY_TABLE:
            case CAULDRON:
            case COMPOSTER:
            case FLETCHING_TABLE:
            case GRINDSTONE:
            case LECTERN:
            case LOOM:
            case SMITHING_TABLE:
            case SMOKER:
            case STONECUTTER: {
                BlockData origBlockData = block.getBlockData();
                Bukkit.getScheduler().runTask(this,
                        () -> {
                            block.setBlockData(airData);
                            Bukkit.getScheduler().runTaskLater(this,
                                    () -> {
                                        // TODO: turn to item if block is no longer air?
                                        block.setBlockData(origBlockData);
                                    }, 2L);
                        });
                break;
            }
        }
        event.setCancelled(shouldCancel);
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        PlayerInventory inventory = player.getInventory();
        Entity entity = event.getRightClicked();
        if (!player.isSneaking() || entity.getType() != EntityType.VILLAGER)
            return;
        Villager villager = (Villager) entity;
        int level = villager.getVillagerLevel();
        if (level == 1) {
            player.sendMessage(Component.text(ERROR_LEVEL_ONE));
            event.setCancelled(true);
            return;
        }
        if (inventory.getItemInMainHand().getType() != TRADE_REFRESH_MATERIAL) {
            player.sendMessage(Component.text(MessageFormat.format(ERROR_MUST_HOLD, TRADE_REFRESH_MATERIAL.name())));
            event.setCancelled(true);
            return;
        }
        int tradeRefreshAmount = TRADE_REFRESH_AMOUNT_PER_LEVEL * (level - 1);
        if (!inventory.contains(TRADE_REFRESH_MATERIAL, tradeRefreshAmount)) {
            player.sendMessage(Component.text(
                    MessageFormat.format(ERROR_NOT_ENOUGH, tradeRefreshAmount, TRADE_REFRESH_MATERIAL.name())));
            event.setCancelled(true);
            return;
        }
        player.sendMessage(Component
                .text(MessageFormat.format(SUCCESS_YOU_SPENT, tradeRefreshAmount, TRADE_REFRESH_MATERIAL.name())));
        inventory.removeItemAnySlot(new ItemStack(TRADE_REFRESH_MATERIAL, tradeRefreshAmount));
        villager.setVillagerLevel(1);
        ArrayList<MerchantRecipe> levelOneRecipes = new ArrayList<>();
        levelOneRecipes.add(villager.getRecipe(0));
        levelOneRecipes.add(villager.getRecipe(1));
        villager.setRecipes(levelOneRecipes);
        villager.increaseLevel(level - 1);
    }

    private static String cleanKey(NamespacedKey key) {
        return key.getKey().replace('_', ' ');
    }

    private static String formatEnchantments(Map<Enchantment, Integer> enchantments) {
        return enchantments.entrySet().stream().map((e) ->
                "[%s:%d]".formatted(cleanKey(e.getKey().getKey()), e.getValue())).collect(Collectors.joining());
    }

    @EventHandler
    public void onVillagerCareerChangeEvent(VillagerCareerChangeEvent event) {
        if (event.getReason() != VillagerCareerChangeEvent.ChangeReason.EMPLOYED)
            return;
        Villager villager = event.getEntity();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!villager.isValid())
                return;
            String profession = cleanKey(villager.getProfession().getKey());
            List<String> newRecipes = villager.getRecipes().stream().map(Recipe::getResult).map((ItemStack stack) ->
                    {
                        String itemName = cleanKey(stack.getType().getKey());
                        String enchantments = formatEnchantments(stack.getEnchantments());
                        if (stack.getItemMeta() instanceof EnchantmentStorageMeta enchantmentStorageMeta)
                            enchantments += formatEnchantments(enchantmentStorageMeta.getStoredEnchants());
                        return "%s%s".formatted(itemName, enchantments);
                    }
            ).toList();
            TextComponent.Builder component = Component.text()
                    .append(Component.text(profession, NamedTextColor.GREEN))
                    .append(Component.text(" now sells: ", NamedTextColor.WHITE));
            for (String newRecipe : newRecipes) {
                component.append(Component.text(newRecipe, NamedTextColor.GREEN))
                        .append(Component.text("; ", NamedTextColor.WHITE));
            }
            for (Player nearbyPlayer : villager.getLocation().getNearbyPlayers(TOWN_DIM_X, TOWN_DIM_Y, TOWN_DIM_Z))
                nearbyPlayer.sendMessage(component);
        });
    }

    public void addReverseSlabRecipe(Material fullMat, Material slabMat) {
        ShapedRecipe recipe = new ShapedRecipe(
                new NamespacedKey(this, "reverse_slab_%s_%s".formatted(slabMat, fullMat)),
                new ItemStack(fullMat, 3)
        );
        recipe.shape("   ", "AAA", "AAA");
        recipe.setIngredient('A', slabMat);
        server.addRecipe(recipe);
    }

    public void addReverseSlabRecipes() {
        //addReverseSlabRecipe(Material.TUFF, Material.TUFF_SLAB);
        //addReverseSlabRecipe(Material.POLISHED_TUFF, Material.POLISHED_TUFF_SLAB);
        //addReverseSlabRecipe(Material.TUFF_BRICKS, Material.TUFF_BRICK_SLAB);
        addReverseSlabRecipe(Material.CUT_COPPER, Material.CUT_COPPER_SLAB);
        addReverseSlabRecipe(Material.EXPOSED_CUT_COPPER, Material.EXPOSED_CUT_COPPER_SLAB);
        addReverseSlabRecipe(Material.WEATHERED_CUT_COPPER, Material.WEATHERED_CUT_COPPER_SLAB);
        addReverseSlabRecipe(Material.OXIDIZED_CUT_COPPER, Material.OXIDIZED_CUT_COPPER_SLAB);
        addReverseSlabRecipe(Material.WAXED_CUT_COPPER, Material.WAXED_CUT_COPPER_SLAB);
        addReverseSlabRecipe(Material.WAXED_EXPOSED_CUT_COPPER, Material.WAXED_EXPOSED_CUT_COPPER_SLAB);
        addReverseSlabRecipe(Material.WAXED_WEATHERED_CUT_COPPER, Material.WAXED_WEATHERED_CUT_COPPER_SLAB);
        addReverseSlabRecipe(Material.WAXED_OXIDIZED_CUT_COPPER, Material.WAXED_OXIDIZED_CUT_COPPER_SLAB);
        addReverseSlabRecipe(Material.OAK_PLANKS, Material.OAK_SLAB);
        addReverseSlabRecipe(Material.SPRUCE_PLANKS, Material.SPRUCE_SLAB);
        addReverseSlabRecipe(Material.BIRCH_PLANKS, Material.BIRCH_SLAB);
        addReverseSlabRecipe(Material.JUNGLE_PLANKS, Material.JUNGLE_SLAB);
        addReverseSlabRecipe(Material.ACACIA_PLANKS, Material.ACACIA_SLAB);
        addReverseSlabRecipe(Material.CHERRY_PLANKS, Material.CHERRY_SLAB);
        addReverseSlabRecipe(Material.DARK_OAK_PLANKS, Material.DARK_OAK_SLAB);
        addReverseSlabRecipe(Material.MANGROVE_PLANKS, Material.MANGROVE_SLAB);
        addReverseSlabRecipe(Material.BAMBOO_PLANKS, Material.BAMBOO_SLAB);
        addReverseSlabRecipe(Material.BAMBOO_MOSAIC, Material.BAMBOO_MOSAIC_SLAB);
        addReverseSlabRecipe(Material.CRIMSON_PLANKS, Material.CRIMSON_SLAB);
        addReverseSlabRecipe(Material.WARPED_PLANKS, Material.WARPED_SLAB);
        addReverseSlabRecipe(Material.STONE, Material.STONE_SLAB);
        addReverseSlabRecipe(Material.SMOOTH_STONE, Material.SMOOTH_STONE_SLAB);
        addReverseSlabRecipe(Material.SANDSTONE, Material.SANDSTONE_SLAB);
        addReverseSlabRecipe(Material.CUT_SANDSTONE, Material.CUT_SANDSTONE_SLAB);
        addReverseSlabRecipe(Material.COBBLESTONE, Material.COBBLESTONE_SLAB);
        addReverseSlabRecipe(Material.BRICK, Material.BRICK_SLAB);
        addReverseSlabRecipe(Material.STONE_BRICKS, Material.STONE_BRICK_SLAB);
        addReverseSlabRecipe(Material.MUD_BRICKS, Material.MUD_BRICK_SLAB);
        addReverseSlabRecipe(Material.NETHER_BRICKS, Material.NETHER_BRICK_SLAB);
        addReverseSlabRecipe(Material.QUARTZ_PILLAR, Material.QUARTZ_SLAB);
        addReverseSlabRecipe(Material.RED_SANDSTONE, Material.RED_SANDSTONE_SLAB);
        addReverseSlabRecipe(Material.CUT_RED_SANDSTONE, Material.CUT_RED_SANDSTONE_SLAB);
        addReverseSlabRecipe(Material.PURPUR_BLOCK, Material.PURPUR_SLAB);
        addReverseSlabRecipe(Material.PRISMARINE, Material.PRISMARINE_SLAB);
        addReverseSlabRecipe(Material.PRISMARINE_BRICKS, Material.PRISMARINE_BRICK_SLAB);
        addReverseSlabRecipe(Material.DARK_PRISMARINE, Material.DARK_PRISMARINE_SLAB);
        addReverseSlabRecipe(Material.POLISHED_GRANITE, Material.POLISHED_GRANITE_SLAB);
        addReverseSlabRecipe(Material.SMOOTH_RED_SANDSTONE, Material.SMOOTH_RED_SANDSTONE_SLAB);
        addReverseSlabRecipe(Material.MOSSY_STONE_BRICKS, Material.MOSSY_STONE_BRICK_SLAB);
        addReverseSlabRecipe(Material.POLISHED_DIORITE, Material.POLISHED_DIORITE_SLAB);
        addReverseSlabRecipe(Material.MOSSY_COBBLESTONE, Material.MOSSY_COBBLESTONE_SLAB);
        addReverseSlabRecipe(Material.END_STONE_BRICKS, Material.END_STONE_BRICK_SLAB);
        addReverseSlabRecipe(Material.SMOOTH_SANDSTONE, Material.SMOOTH_SANDSTONE_SLAB);
        addReverseSlabRecipe(Material.SMOOTH_QUARTZ, Material.SMOOTH_QUARTZ_SLAB);
        addReverseSlabRecipe(Material.GRANITE, Material.GRANITE_SLAB);
        addReverseSlabRecipe(Material.ANDESITE, Material.ANDESITE_SLAB);
        addReverseSlabRecipe(Material.RED_NETHER_BRICKS, Material.RED_NETHER_BRICK_SLAB);
        addReverseSlabRecipe(Material.POLISHED_ANDESITE, Material.POLISHED_ANDESITE_SLAB);
        addReverseSlabRecipe(Material.DIORITE, Material.DIORITE_SLAB);
        addReverseSlabRecipe(Material.COBBLED_DEEPSLATE, Material.COBBLED_DEEPSLATE_SLAB);
        addReverseSlabRecipe(Material.POLISHED_DEEPSLATE, Material.POLISHED_DEEPSLATE_SLAB);
        addReverseSlabRecipe(Material.DEEPSLATE_BRICKS, Material.DEEPSLATE_BRICK_SLAB);
        addReverseSlabRecipe(Material.DEEPSLATE_TILES, Material.DEEPSLATE_TILE_SLAB);
        addReverseSlabRecipe(Material.BLACKSTONE, Material.BLACKSTONE_SLAB);
        addReverseSlabRecipe(Material.POLISHED_BLACKSTONE, Material.POLISHED_BLACKSTONE_SLAB);
        addReverseSlabRecipe(Material.POLISHED_BLACKSTONE_BRICKS, Material.POLISHED_BLACKSTONE_BRICK_SLAB);
    }

    public void addReverseStairsRecipe(Material fullMat, Material stairsMat) {
        ShapedRecipe recipe = new ShapedRecipe(
                new NamespacedKey(this, "reverse_stairs_%s_%s".formatted(stairsMat, fullMat)),
                new ItemStack(fullMat, 6)
        );
        recipe.shape("   ", "AA ", "AA ");
        recipe.setIngredient('A', stairsMat);
        server.addRecipe(recipe);
    }

    public void addReverseStairsRecipes() {
        //addReverseStairsRecipe(Material.TUFF, Material.TUFF_STAIRS);
        //addReverseStairsRecipe(Material.POLISHED_TUFF, Material.POLISHED_TUFF_STAIRS);
        //addReverseStairsRecipe(Material.TUFF_BRICKS, Material.TUFF_BRICK_STAIRS);
        addReverseStairsRecipe(Material.CUT_COPPER, Material.CUT_COPPER_STAIRS);
        addReverseStairsRecipe(Material.EXPOSED_CUT_COPPER, Material.EXPOSED_CUT_COPPER_STAIRS);
        addReverseStairsRecipe(Material.WEATHERED_CUT_COPPER, Material.WEATHERED_CUT_COPPER_STAIRS);
        addReverseStairsRecipe(Material.OXIDIZED_CUT_COPPER, Material.OXIDIZED_CUT_COPPER_STAIRS);
        addReverseStairsRecipe(Material.WAXED_CUT_COPPER, Material.WAXED_CUT_COPPER_STAIRS);
        addReverseStairsRecipe(Material.WAXED_EXPOSED_CUT_COPPER, Material.WAXED_EXPOSED_CUT_COPPER_STAIRS);
        addReverseStairsRecipe(Material.WAXED_WEATHERED_CUT_COPPER, Material.WAXED_WEATHERED_CUT_COPPER_STAIRS);
        addReverseStairsRecipe(Material.WAXED_OXIDIZED_CUT_COPPER, Material.WAXED_OXIDIZED_CUT_COPPER_STAIRS);
        addReverseStairsRecipe(Material.PURPUR_BLOCK, Material.PURPUR_STAIRS);
        addReverseStairsRecipe(Material.COBBLESTONE, Material.COBBLESTONE_STAIRS);
        addReverseStairsRecipe(Material.BRICKS, Material.BRICK_STAIRS);
        addReverseStairsRecipe(Material.STONE_BRICKS, Material.STONE_BRICK_STAIRS);
        addReverseStairsRecipe(Material.MUD_BRICKS, Material.MUD_BRICK_STAIRS);
        addReverseStairsRecipe(Material.NETHER_BRICKS, Material.NETHER_BRICK_STAIRS);
        addReverseStairsRecipe(Material.SANDSTONE, Material.SANDSTONE_STAIRS);
        addReverseStairsRecipe(Material.OAK_PLANKS, Material.OAK_STAIRS);
        addReverseStairsRecipe(Material.SPRUCE_PLANKS, Material.SPRUCE_STAIRS);
        addReverseStairsRecipe(Material.BIRCH_PLANKS, Material.BIRCH_STAIRS);
        addReverseStairsRecipe(Material.JUNGLE_PLANKS, Material.JUNGLE_STAIRS);
        addReverseStairsRecipe(Material.ACACIA_PLANKS, Material.ACACIA_STAIRS);
        addReverseStairsRecipe(Material.CHERRY_PLANKS, Material.CHERRY_STAIRS);
        addReverseStairsRecipe(Material.DARK_OAK_PLANKS, Material.DARK_OAK_STAIRS);
        addReverseStairsRecipe(Material.MANGROVE_PLANKS, Material.MANGROVE_STAIRS);
        addReverseStairsRecipe(Material.BAMBOO_PLANKS, Material.BAMBOO_STAIRS);
        addReverseStairsRecipe(Material.BAMBOO_MOSAIC, Material.BAMBOO_MOSAIC_STAIRS);
        addReverseStairsRecipe(Material.CRIMSON_PLANKS, Material.CRIMSON_STAIRS);
        addReverseStairsRecipe(Material.WARPED_PLANKS, Material.WARPED_STAIRS);
        addReverseStairsRecipe(Material.QUARTZ_PILLAR, Material.QUARTZ_STAIRS);
        addReverseStairsRecipe(Material.PRISMARINE, Material.PRISMARINE_STAIRS);
        addReverseStairsRecipe(Material.PRISMARINE_BRICKS, Material.PRISMARINE_BRICK_STAIRS);
        addReverseStairsRecipe(Material.DARK_PRISMARINE, Material.DARK_PRISMARINE_STAIRS);
        addReverseStairsRecipe(Material.RED_SANDSTONE, Material.RED_SANDSTONE_STAIRS);
        addReverseStairsRecipe(Material.POLISHED_GRANITE, Material.POLISHED_GRANITE_STAIRS);
        addReverseStairsRecipe(Material.SMOOTH_RED_SANDSTONE, Material.SMOOTH_RED_SANDSTONE_STAIRS);
        addReverseStairsRecipe(Material.MOSSY_STONE_BRICKS, Material.MOSSY_STONE_BRICK_STAIRS);
        addReverseStairsRecipe(Material.POLISHED_DIORITE, Material.POLISHED_DIORITE_STAIRS);
        addReverseStairsRecipe(Material.MOSSY_COBBLESTONE, Material.MOSSY_COBBLESTONE_STAIRS);
        addReverseStairsRecipe(Material.END_STONE_BRICKS, Material.END_STONE_BRICK_STAIRS);
        addReverseStairsRecipe(Material.STONE, Material.STONE_STAIRS);
        addReverseStairsRecipe(Material.SMOOTH_SANDSTONE, Material.SMOOTH_SANDSTONE_STAIRS);
        addReverseStairsRecipe(Material.SMOOTH_QUARTZ, Material.SMOOTH_QUARTZ_STAIRS);
        addReverseStairsRecipe(Material.GRANITE, Material.GRANITE_STAIRS);
        addReverseStairsRecipe(Material.ANDESITE, Material.ANDESITE_STAIRS);
        addReverseStairsRecipe(Material.RED_NETHER_BRICKS, Material.RED_NETHER_BRICK_STAIRS);
        addReverseStairsRecipe(Material.POLISHED_ANDESITE, Material.POLISHED_ANDESITE_STAIRS);
        addReverseStairsRecipe(Material.DIORITE, Material.DIORITE_STAIRS);
        addReverseStairsRecipe(Material.COBBLED_DEEPSLATE, Material.COBBLED_DEEPSLATE_STAIRS);
        addReverseStairsRecipe(Material.POLISHED_DEEPSLATE, Material.POLISHED_DEEPSLATE_STAIRS);
        addReverseStairsRecipe(Material.DEEPSLATE_BRICKS, Material.DEEPSLATE_BRICK_STAIRS);
        addReverseStairsRecipe(Material.DEEPSLATE_TILES, Material.DEEPSLATE_TILE_STAIRS);
        addReverseStairsRecipe(Material.BLACKSTONE, Material.BLACKSTONE_STAIRS);
        addReverseStairsRecipe(Material.POLISHED_BLACKSTONE, Material.POLISHED_BLACKSTONE_STAIRS);
        addReverseStairsRecipe(Material.POLISHED_BLACKSTONE_BRICKS, Material.POLISHED_BLACKSTONE_BRICK_STAIRS);
    }

    public void addReverseWallRecipe(Material fullMat, Material wallMat) {
        ShapedRecipe recipe = new ShapedRecipe(
                new NamespacedKey(this, "reverse_wall_%s_%s".formatted(wallMat, fullMat)),
                new ItemStack(fullMat, 6)
        );
        recipe.shape("   ", "AAA", "AAA");
        recipe.setIngredient('A', wallMat);
        server.addRecipe(recipe);
    }

    public void addReverseWallRecipes() {
        //addReverseWallRecipe(Material.TUFF, Material.TUFF_WALL);
        //addReverseWallRecipe(Material.POLISHED_TUFF, Material.POLISHED_TUFF_WALL);
        //addReverseWallRecipe(Material.TUFF, Material.TUFF_BRICK_WALL);
        addReverseWallRecipe(Material.COBBLESTONE, Material.COBBLESTONE_WALL);
        addReverseWallRecipe(Material.MOSSY_COBBLESTONE, Material.MOSSY_COBBLESTONE_WALL);
        addReverseWallRecipe(Material.BRICK, Material.BRICK_WALL);
        addReverseWallRecipe(Material.PRISMARINE, Material.PRISMARINE_WALL);
        addReverseWallRecipe(Material.RED_SANDSTONE, Material.RED_SANDSTONE_WALL);
        addReverseWallRecipe(Material.MOSSY_STONE_BRICKS, Material.MOSSY_STONE_BRICK_WALL);
        addReverseWallRecipe(Material.GRANITE, Material.GRANITE_WALL);
        addReverseWallRecipe(Material.STONE, Material.STONE_BRICK_WALL);
        addReverseWallRecipe(Material.MUD_BRICKS, Material.MUD_BRICK_WALL);
        addReverseWallRecipe(Material.NETHER_BRICK, Material.NETHER_BRICK_WALL);
        addReverseWallRecipe(Material.ANDESITE, Material.ANDESITE_WALL);
        addReverseWallRecipe(Material.RED_NETHER_BRICKS, Material.RED_NETHER_BRICK_WALL);
        addReverseWallRecipe(Material.SANDSTONE, Material.SANDSTONE_WALL);
        addReverseWallRecipe(Material.END_STONE_BRICKS, Material.END_STONE_BRICK_WALL);
        addReverseWallRecipe(Material.DIORITE, Material.DIORITE_WALL);
        addReverseWallRecipe(Material.BLACKSTONE, Material.BLACKSTONE_WALL);
        addReverseWallRecipe(Material.POLISHED_BLACKSTONE, Material.POLISHED_BLACKSTONE_WALL);
        addReverseWallRecipe(Material.POLISHED_BLACKSTONE_BRICKS, Material.POLISHED_BLACKSTONE_BRICK_WALL);
        addReverseWallRecipe(Material.COBBLED_DEEPSLATE, Material.COBBLED_DEEPSLATE_WALL);
        addReverseWallRecipe(Material.POLISHED_DEEPSLATE, Material.POLISHED_DEEPSLATE_WALL);
        addReverseWallRecipe(Material.DEEPSLATE_BRICKS, Material.DEEPSLATE_BRICK_WALL);
        addReverseWallRecipe(Material.DEEPSLATE_TILES, Material.DEEPSLATE_TILE_WALL);

    }

    public void addReverseRecipes() {
        addReverseSlabRecipes();
        addReverseStairsRecipes();
        addReverseWallRecipes();
    }

    public long getCurrentTick(Player player) {
        return player.getWorld().getFullTime();
    }

    public boolean canMobsAttack(Player player) {
        if (player.hasPermission(PACIFIER_COMPLETE))
            return false;
        if (!player.hasPermission(PACIFIER_COOLDOWN))
            return true;
        Long lastDamageTick = playerLastDamageTick.get(player);
        Long currentTick = getCurrentTick(player);
        if (lastDamageTick == null)
            return false;
        return (currentTick - lastDamageTick) < PACIFIER_AGGRO_TICKS;
    }

    @EventHandler
    public void onEntityTargetEvent(EntityTargetEvent targetEvent) {
        Entity entity = targetEvent.getEntity();
        Entity target = targetEvent.getTarget();
        if (entity instanceof Monster monster
                && target instanceof Player player
                && !canMobsAttack(player)) {
            targetEvent.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDamageByEntityEvent(EntityDamageByEntityEvent damageEvent) {
        Entity entity = damageEvent.getEntity();
        Entity damager = damageEvent.getDamager();
        if (damager instanceof Projectile projectile) {
            if (projectile.getShooter() instanceof Entity e) {
                damager = e;
            } else {
                return;
            }
        }
        if (damager instanceof Player player
                && entity instanceof Monster monster
                && player.hasPermission(PACIFIER_COOLDOWN)) {
            playerLastDamageTick.put(player, getCurrentTick(player));
        } else if (damager instanceof Monster monster
                    && entity instanceof Player player
                    && !canMobsAttack(player)) {
            monster.setTarget(null);
        }
    }

    public void doPacifierCleanup() {
        for (var entry : playerLastDamageTick.entrySet()) {
            Player player = entry.getKey();
            if (entry.getValue() == null)
                continue;
            if (!canMobsAttack(player)) {
                entry.setValue(null);
                for (var entity : player.getNearbyEntities(32, 10, 32)) {
                    if (entity instanceof Monster monster) {
                        if (monster.getTarget() == player)
                            monster.setTarget(null);
                    }
                }
            }
        }
    }
}
