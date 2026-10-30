package dev.chunkfun.events.special;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import dev.chunkfun.config.ChunkFunConfig;
import dev.chunkfun.util.Text;
import java.util.EnumSet;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.util.Vector;

/**
 * Custom Paper mob goal that turns a sheep into an aggressive ram: it paths to the nearest survival
 * player, lunges when close and deals damage plus strong knockback, then waits for a cooldown.
 *
 * <p>All vanilla sheep goals (panic, grazing, wandering, tempting) are removed before this goal is added,
 * so it is the only thing driving the sheep.</p>
 */
public final class RamPlayerGoal implements Goal<Sheep> {

    private static final double RAM_RANGE_SQUARED = 2.3 * 2.3;

    private final GoalKey<Sheep> key;
    private final Sheep sheep;
    private final ChunkFunConfig.SheepSettings settings;
    private Player target;
    private int cooldown;
    private int repathIn;
    private int retargetIn;

    public RamPlayerGoal(NamespacedKey key, Sheep sheep, ChunkFunConfig.SheepSettings settings) {
        this.key = GoalKey.of(Sheep.class, key);
        this.sheep = sheep;
        this.settings = settings;
        this.cooldown = 20;
    }

    @Override
    public boolean shouldActivate() {
        return this.sheep.isValid() && !this.sheep.isDead();
    }

    @Override
    public void stop() {
        this.sheep.getPathfinder().stopPathfinding();
        this.target = null;
    }

    @Override
    public void tick() {
        // Without the vanilla float goal the sheep would sink; keep it swimming like FloatGoal does.
        if (this.sheep.isInWater() || this.sheep.isInLava()) {
            Vector velocity = this.sheep.getVelocity();
            if (velocity.getY() < 0.1) {
                this.sheep.setVelocity(velocity.setY(velocity.getY() + 0.06));
            }
        }
        if (this.cooldown > 0) {
            this.cooldown--;
        }
        if (--this.retargetIn <= 0 || !this.isValidTarget(this.target)) {
            this.target = this.findTarget();
            this.retargetIn = 20;
        }
        if (this.target == null) {
            return;
        }

        this.sheep.lookAt(this.target);
        double distanceSquared = this.sheep.getLocation().distanceSquared(this.target.getLocation());
        if (--this.repathIn <= 0) {
            this.sheep.getPathfinder().moveTo(this.target, this.settings.speed());
            this.repathIn = 10;
        }
        if (this.cooldown == 0 && distanceSquared <= RAM_RANGE_SQUARED && this.sheep.hasLineOfSight(this.target)) {
            this.ram(this.target);
        }
    }

    private void ram(Player player) {
        Location from = this.sheep.getLocation();
        Vector direction = player.getLocation().toVector().subtract(from.toVector()).setY(0.0);
        if (direction.lengthSquared() < 1.0E-4) {
            direction = from.getDirection().setY(0.0);
        }
        if (direction.lengthSquared() < 1.0E-4) {
            direction = new Vector(1.0, 0.0, 0.0);
        }
        direction.normalize();

        // Lunge forward like a goat ram.
        this.sheep.setVelocity(direction.clone().multiply(0.6).setY(0.2));

        DamageSource source = DamageSource.builder(DamageType.MOB_ATTACK)
                .withCausingEntity(this.sheep)
                .withDirectEntity(this.sheep)
                .build();
        player.damage(this.settings.damage(), source);
        // Override vanilla's light knockback with the ram's much stronger push.
        player.setVelocity(direction.multiply(this.settings.knockbackHorizontal()).setY(this.settings.knockbackVertical()));

        player.getWorld().playSound(Text.sound("entity.goat.ram_impact", 1.2f, 0.9f), player);
        player.getWorld().spawnParticle(Particle.CRIT, player.getLocation().add(0.0, 1.0, 0.0), 15, 0.3, 0.4, 0.3, 0.1);
        this.cooldown = this.settings.ramCooldownTicks();
    }

    private Player findTarget() {
        Player best = null;
        double bestDistance = this.settings.targetRange() * this.settings.targetRange();
        for (Player player : this.sheep.getWorld().getPlayers()) {
            if (!this.isValidTarget(player)) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(this.sheep.getLocation());
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    private boolean isValidTarget(Player player) {
        if (player == null || !player.isOnline() || player.isDead() || !player.getWorld().equals(this.sheep.getWorld())) {
            return false;
        }
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
            return false;
        }
        double range = this.settings.targetRange() * 1.5;
        return player.getLocation().distanceSquared(this.sheep.getLocation()) <= range * range;
    }

    @Override
    public GoalKey<Sheep> getKey() {
        return this.key;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.MOVE, GoalType.LOOK, GoalType.JUMP);
    }
}
