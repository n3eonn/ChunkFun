package dev.chunkfun.events.special;

import dev.chunkfun.ChunkFunPlugin;
import dev.chunkfun.config.ChunkFunConfig;
import dev.chunkfun.util.Keys;
import dev.chunkfun.util.Text;
import dev.chunkfun.world.Environment;
import dev.chunkfun.world.PlayableArea;
import dev.chunkfun.world.SurfaceScanner;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Meteor Attack: burning meteors streak down onto random spots of the playable area and explode,
 * leaving fire, magma and sometimes lava in the crater.
 *
 * <p>Each meteor is a single client-side {@link BlockDisplay} moved along a precomputed path with
 * interpolated teleports, plus particles. No physics entities are used, so a meteor cannot be deflected
 * out of the area, and at most {@code max-active} displays exist at once. Impact points are always chosen
 * inside the playable area.</p>
 */
public final class MeteorEvent extends SpecialEvent {

    private static final Set<Material> CRATER_CONVERTIBLE = EnumSet.of(
            Material.STONE, Material.DEEPSLATE, Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT,
            Material.PODZOL, Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.ANDESITE, Material.DIORITE,
            Material.GRANITE, Material.TUFF, Material.COBBLESTONE, Material.NETHERRACK, Material.BASALT,
            Material.BLACKSTONE, Material.SOUL_SAND, Material.SOUL_SOIL, Material.END_STONE, Material.CRIMSON_NYLIUM,
            Material.WARPED_NYLIUM, Material.MOSS_BLOCK, Material.CLAY, Material.MUD);

    private static final class Meteor {
        final BlockDisplay display;
        final Vector start;
        final Vector target;
        final int fallTicks;
        int age;

        Meteor(BlockDisplay display, Vector start, Vector target, int fallTicks) {
            this.display = display;
            this.start = start;
            this.target = target;
            this.fallTicks = fallTicks;
        }
    }

    private final List<Meteor> meteors = new ArrayList<>();

    public MeteorEvent(ChunkFunPlugin plugin, Environment environment) {
        super(plugin, environment);
    }

    @Override
    public SpecialEventType type() {
        return SpecialEventType.METEOR_ATTACK;
    }

    @Override
    public void start() {
        this.repeat(0L, this.settings().meteor().spawnIntervalTicks(), this::launch);
        this.repeat(1L, 1L, this::tickMeteors);
    }

    private void launch() {
        ChunkFunConfig.MeteorSettings settings = this.settings().meteor();
        if (this.meteors.size() >= settings.maxActive()) {
            return;
        }
        int[] column = this.randomImpactColumn();
        if (column == null) {
            return;
        }
        PlayableArea area = this.area();
        World world = area.world();
        int x = column[0];
        int impactY = column[1];
        int z = column[2];
        int open = SurfaceScanner.openSpaceAbove(area, x, impactY, z);
        int height = Math.max(4, Math.min(settings.startHeight(), open - 1));

        Vector target = new Vector(x + 0.5, impactY + 1.0, z + 0.5);
        // Come in at an angle, but keep the whole trail above the playable area.
        double drift = height * 0.45;
        double startX = clamp(target.getX() + (this.random.nextDouble() * 2 - 1) * drift, area.minX() + 0.5, area.maxX() + 0.5);
        double startZ = clamp(target.getZ() + (this.random.nextDouble() * 2 - 1) * drift, area.minZ() + 0.5, area.maxZ() + 0.5);
        Vector start = new Vector(startX, target.getY() + height, startZ);

        float scale = 1.4f + this.random.nextFloat() * 0.8f;
        Location spawn = start.toLocation(world);
        BlockDisplay display = world.spawn(spawn, BlockDisplay.class, entity -> {
            entity.setBlock(Material.MAGMA_BLOCK.createBlockData());
            entity.setTransformation(new Transformation(
                    new Vector3f(-scale / 2.0f, -scale / 2.0f, -scale / 2.0f),
                    new AxisAngle4f((float) (this.random.nextDouble() * Math.PI), 0.3f, 1.0f, 0.2f),
                    new Vector3f(scale, scale, scale),
                    new AxisAngle4f()));
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setTeleportDuration(1);
            entity.setPersistent(false);
            Keys.markManaged(entity, "meteor");
        });
        world.playSound(Text.sound("entity.blaze.shoot", 2.5f, 0.5f), spawn.getX(), spawn.getY(), spawn.getZ());
        int fallTicks = Math.max(8, (int) Math.round(settings.fallTicks() * Math.min(1.0, height / (double) settings.startHeight())));
        this.meteors.add(new Meteor(display, start, target, fallTicks));
    }

    private void tickMeteors() {
        World world = this.area().world();
        Iterator<Meteor> iterator = this.meteors.iterator();
        while (iterator.hasNext()) {
            Meteor meteor = iterator.next();
            if (!meteor.display.isValid()) {
                iterator.remove();
                continue;
            }
            meteor.age++;
            double progress = Math.min(1.0, meteor.age / (double) meteor.fallTicks);
            // Accelerate towards the ground.
            double eased = progress * progress;
            Vector position = meteor.start.clone().add(meteor.target.clone().subtract(meteor.start).multiply(eased));
            meteor.display.teleport(position.toLocation(world));
            world.spawnParticle(Particle.FLAME, position.getX(), position.getY(), position.getZ(), 10, 0.35, 0.35, 0.35, 0.02);
            world.spawnParticle(Particle.LARGE_SMOKE, position.getX(), position.getY(), position.getZ(), 3, 0.25, 0.25, 0.25, 0.01);
            if (meteor.age % 3 == 0) {
                world.spawnParticle(Particle.LAVA, position.getX(), position.getY(), position.getZ(), 1, 0.2, 0.2, 0.2, 0.0);
            }
            if (progress >= 1.0) {
                meteor.display.remove();
                iterator.remove();
                this.impact(meteor.target);
            }
        }
    }

    private void impact(Vector target) {
        ChunkFunConfig.MeteorSettings settings = this.settings().meteor();
        PlayableArea area = this.area();
        World world = area.world();
        if (!area.contains(target.getX(), target.getZ())) {
            return;
        }
        world.createExplosion(target.getX(), target.getY(), target.getZ(), settings.explosionPower(),
                settings.setFire(), settings.breakBlocks());
        world.spawnParticle(Particle.EXPLOSION_EMITTER, target.getX(), target.getY(), target.getZ(), 1);

        int cx = target.getBlockX();
        int cz = target.getBlockZ();
        int crater = SurfaceScanner.impactY(area, cx, cz);
        if (crater == SurfaceScanner.NONE) {
            return;
        }
        // Scorch the crater floor with magma (natural terrain only, never containers or builds).
        for (int i = 0; i < 4; i++) {
            int x = area.clampX(cx + this.random.nextInt(3) - 1);
            int z = area.clampZ(cz + this.random.nextInt(3) - 1);
            int y = SurfaceScanner.impactY(area, x, z);
            if (y != SurfaceScanner.NONE && CRATER_CONVERTIBLE.contains(world.getType(x, y, z))) {
                world.getBlockAt(x, y, z).setType(Material.MAGMA_BLOCK, false);
            }
        }
        if (this.random.nextDouble() < settings.lavaChance() && crater + 1 <= area.maxY()
                && world.getType(cx, crater + 1, cz).isAir()) {
            world.getBlockAt(cx, crater + 1, cz).setType(Material.LAVA, true);
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    protected void onStop(boolean immediate) {
        for (Meteor meteor : this.meteors) {
            if (meteor.display.isValid()) {
                meteor.display.remove();
            }
        }
        this.meteors.clear();
    }
}
