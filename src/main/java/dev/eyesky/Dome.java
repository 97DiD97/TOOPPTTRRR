package dev.eyesky;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Огромный чёрный купол при изгнании тени: внутри всё чёрное (игрокам внутри даётся слепота, а вокруг
 * них летят белые распадающиеся частицы), снаружи виден гигантский купол. Через несколько секунд он растворяется.
 */
public final class Dome {

    private static final String TAG = "eyesky_fx";
    private static final int GROW_TICKS = 20;
    private static final int FADE_TICKS = 30;

    private final EyeSkyPlugin plugin;
    private final Location center;
    private final World world;
    private final double radius;
    private final int holdTicks;
    private final Random rnd = new Random();
    private final List<Tile> tiles = new ArrayList<>();
    private final Set<UUID> blinded = new HashSet<>();
    private BukkitTask task;
    private int t;
    private int spawned;
    private boolean fading;

    private static final class Tile {
        BlockDisplay d;
        Quaternionf q;
        Vector3f pos;
        float w, h;
    }

    public Dome(EyeSkyPlugin plugin, Location center, double radius, int seconds) {
        this.plugin = plugin;
        this.center = center.clone();
        this.world = center.getWorld();
        this.radius = radius;
        this.holdTicks = Math.max(20, seconds * 20);
    }

    public void start() {
        buildLayout();
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(center) < 300 * 300) {
                p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_EMERGE, SoundCategory.MASTER, 1f, 0.5f);
                p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.MASTER, 1f, 0.4f);
            }
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void abort() {
        if (task != null) task.cancel();
        task = null;
        cleanup();
    }

    private void cleanup() {
        for (Tile k : tiles) if (k.d != null) k.d.remove();
        tiles.clear();
        for (UUID id : new ArrayList<>(blinded)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.removePotionEffect(PotionEffectType.BLINDNESS);
        }
        blinded.clear();
    }

    /** Раскладка плиток по полусфере. */
    private void buildLayout() {
        double tile = 26;
        double step = tile / radius;
        int maxK = (int) Math.ceil(Math.PI / 2 / step);
        for (int k = -1; k <= maxK; k++) {
            double el = Math.min((k + 0.5) * step, Math.PI / 2);
            double cos = Math.cos(el), sin = Math.sin(el);
            int n = Math.max(1, (int) Math.ceil(2 * Math.PI * radius * cos / (tile * 0.9)));
            if (el >= Math.PI / 2 - 1e-6) n = 1;
            for (int i = 0; i < n; i++) {
                double az = 2 * Math.PI * (i + (k % 2 == 0 ? 0 : 0.5)) / n;
                Vector3f nrm = new Vector3f((float) (cos * Math.cos(az)), (float) sin, (float) (cos * Math.sin(az)));
                Vector3f x = new Vector3f((float) -Math.sin(az), 0, (float) Math.cos(az));
                Vector3f y = new Vector3f(nrm).cross(x);
                Matrix3f m = new Matrix3f(x.x, x.y, x.z, y.x, y.y, y.z, nrm.x, nrm.y, nrm.z);
                Tile ti = new Tile();
                ti.q = new Quaternionf().setFromNormalized(m);
                ti.pos = new Vector3f(nrm).mul((float) radius);
                ti.w = (float) (2 * Math.PI * radius * cos / n * 1.1 + 0.6);
                ti.h = (float) (tile * 1.1);
                if (n == 1) ti.w = ti.h;
                tiles.add(ti);
            }
        }
    }

    private Transformation transform(Tile k, float f) {
        Vector3f s = new Vector3f(k.w * f, k.h * f, 1.5f * f);
        Vector3f tr = new Vector3f(s).mul(0.5f);
        k.q.transform(tr);
        tr.negate();
        return new Transformation(tr, k.q, s, new Quaternionf());
    }

    private void spawnBatch() {
        int end = Math.min(tiles.size(), spawned + 140);
        for (; spawned < end; spawned++) {
            Tile k = tiles.get(spawned);
            double x = center.getX() + k.pos.x, y = center.getY() + k.pos.y, z = center.getZ() + k.pos.z;
            if (!world.isChunkLoaded((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4)) continue;   // чужие чанки не генерируем
            k.d = world.spawn(new Location(world, x, y, z), BlockDisplay.class, d -> {
                d.setBlock(Material.BLACK_CONCRETE.createBlockData());
                d.setBrightness(new Display.Brightness(0, 0));
                d.setViewRange(4f);
                d.setShadowRadius(0f);
                d.setPersistent(false);
                d.setInvulnerable(true);
                d.setGravity(false);
                d.addScoreboardTag(TAG);
                d.setTransformation(transform(k, 0.02f));
            });
        }
    }

    private void tick() {
        try {
            doTick();
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING, "Ошибка купола", ex);
            abort();
            plugin.shade().domeEnded(this);
        }
    }

    private void doTick() {
        if (spawned < tiles.size()) spawnBatch();
        else if (t == 5) {                       // все плитки созданы: растём из точек до полного размера
            for (Tile k : tiles) {
                if (k.d == null) continue;
                k.d.setInterpolationDelay(0);
                k.d.setInterpolationDuration(GROW_TICKS);
                k.d.setTransformation(transform(k, 1f));
            }
        }

        // внутри - чёрное и белые распадающиеся частицы
        boolean inside;
        for (Player p : world.getPlayers()) {
            Location pl = p.getLocation();
            inside = !fading && pl.distanceSquared(center) < radius * radius;
            if (inside) {
                if (t % 10 == 0 || !blinded.contains(p.getUniqueId())) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, true, false, false));
                    blinded.add(p.getUniqueId());
                }
                for (int i = 0; i < 7; i++) {
                    double a = rnd.nextDouble() * Math.PI * 2, d = 1.5 + rnd.nextDouble() * 3.5, h = rnd.nextDouble() * 4 - 1.2;
                    Location at = pl.clone().add(Math.cos(a) * d, h + 1, Math.sin(a) * d);
                    p.spawnParticle(i % 2 == 0 ? Particle.END_ROD : Particle.WHITE_ASH, at, 0,
                            (rnd.nextDouble() - 0.5) * 0.1, -0.05 - rnd.nextDouble() * 0.1, (rnd.nextDouble() - 0.5) * 0.1, 1.0);
                }
            } else if (blinded.remove(p.getUniqueId())) {
                p.removePotionEffect(PotionEffectType.BLINDNESS);   // вышел из купола - всё нормально
            }
        }
        if (t % 20 == 0) {
            world.playSound(center, Sound.AMBIENT_CAVE, SoundCategory.AMBIENT, 4f, 0.5f);
        }

        // растворение
        if (!fading && t >= holdTicks) {
            fading = true;
            for (UUID id : new ArrayList<>(blinded)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    p.removePotionEffect(PotionEffectType.BLINDNESS);
                    p.playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.MASTER, 1f, 0.7f);
                }
            }
            blinded.clear();
            for (Tile k : tiles) {
                if (k.d == null) continue;
                k.d.setInterpolationDelay(rnd.nextInt(15));
                k.d.setInterpolationDuration(FADE_TICKS - 15);
                k.d.setTransformation(transform(k, 0.02f));
            }
        }
        if (fading) {
            for (int i = 0; i < 12; i++) {
                Tile k = tiles.get(rnd.nextInt(tiles.size()));
                if (k.d == null) continue;
                Location l = k.d.getLocation();
                world.spawnParticle(Particle.END_ROD, l, 0, 0, -0.2, 0, 1.0, null, true);
            }
            if (t >= holdTicks + FADE_TICKS + 5) {
                abort();
                plugin.shade().domeEnded(this);
                return;
            }
        }
        t++;
    }
}
