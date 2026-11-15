package dev.eyesky;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.logging.Level;

/**
 * Эффектные исчезновение и появление глаза.
 *
 * HIDE   - глаз судорожно моргает, сжимается в точку, всё стягивается в него и он исчезает.
 * APPEAR - громкий звук, небо "лагает" (время и погода у игроков дёргаются, в небе мерцают нарисованные
 *          помехи), глаз собирается из глитча, финальный взрыв света - и всё снова нормально.
 */
public final class Entrance {

    public enum Kind { HIDE, APPEAR }

    private static final int HIDE_TICKS = 70;
    private static final int T_EYE = 90;          // APPEAR: в этот момент глаз начинает проявляться
    private static final int T_BOOM = 140;        // APPEAR: финальный взрыв
    private static final int APPEAR_TICKS = 180;
    private static final int PANELS_PER_PLAYER = 7;
    private static final int MAX_PLAYERS = 6;
    private static final String TAG = "eyesky_fx";

    private static final Emotion[] GLITCH_EMOTIONS = {
            Emotion.ANGRY, Emotion.SAD, Emotion.SURPRISED, Emotion.ALERT, Emotion.HAPPY, Emotion.BLINK, Emotion.SLEEPY
    };
    private static final long[] GLITCH_TIMES = {1000, 6000, 12500, 13500, 18000, 23000, 0, 9000};
    private static final String GLITCH_CHARS = "█▓▒░#@%&$?!<>/\\|";

    private final EyeSkyPlugin plugin;
    private final EyeManager eye;
    private final Kind kind;
    private final Random rnd = new Random();

    private BukkitTask task;
    private World world;
    private int t;
    private ItemDisplay glow;
    private final List<Panel> panels = new ArrayList<>();
    private final List<Player> audience = new ArrayList<>();
    private ItemStack glitchA, glitchB;
    private boolean eyeShown;

    private static final class Panel {
        final ItemDisplay d;
        final Player owner;
        Panel(ItemDisplay d, Player owner) {
            this.d = d;
            this.owner = owner;
        }
    }

    public Entrance(EyeSkyPlugin plugin, EyeManager eye, Kind kind) {
        this.plugin = plugin;
        this.eye = eye;
        this.kind = kind;
    }

    // =====================================================================================

    /** Запуск. Возвращает null, если всё хорошо, иначе причину отказа. */
    public String start() {
        world = eye.world();
        if (world == null) return "нет мира глаза";
        if (kind == Kind.HIDE) {
            if (eye.isHidden()) return "глаз уже спрятан (вернуть: /eye appear)";
            Vector p = eye.position();
            if (p == null) return "глаза ещё нет на небе";
            eye.setHold(p);
        } else {
            if (!eye.isHidden()) return "глаз и так на небе (спрятать: /eye hide)";
            List<Player> all = world.getPlayers();
            if (all.isEmpty()) return "в мире глаза нет игроков";
            for (Player p : all) {
                if (audience.size() < MAX_PLAYERS) audience.add(p);
            }
            glitchA = modelItem("eyesky:10");
            glitchB = modelItem("eyesky:11");
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        return null;
    }

    /** Остановка досрочно (выключение плагина, /eye stop). */
    public void abort() {
        if (task != null) task.cancel();
        task = null;
        cleanup();
    }

    private void finish() {
        if (task != null) task.cancel();
        task = null;
        cleanup();
        eye.entranceEnded(this);
    }

    private void cleanup() {
        for (Panel p : panels) p.d.remove();
        panels.clear();
        if (glow != null) glow.remove();
        glow = null;
        for (Player p : world == null ? new ArrayList<Player>() : world.getPlayers()) {
            p.resetPlayerTime();
            p.resetPlayerWeather();
        }
        eye.setHold(null);
        eye.visual(1.0, 0.0);
    }

    private void tick() {
        try {
            if (kind == Kind.HIDE) tickHide(); else tickAppear();
            t++;
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING, "Ошибка в анимации глаза", ex);
            if (kind == Kind.APPEAR && !eyeShown) {
                eye.showNow();                        // чтобы глаз не остался спрятанным из-за ошибки
            }
            finish();
        }
    }

    // =====================================================================================
    //  Исчезновение
    // =====================================================================================

    private void tickHide() {
        Vector p = eye.position();
        if (p == null) { finish(); return; }
        Location eL = new Location(world, p.getX(), p.getY(), p.getZ());
        double pr = (double) t / HIDE_TICKS;

        if (t == 0) {
            sound(eL, Sound.ENTITY_ENDERMAN_TELEPORT, 8f, 0.5f);
            sound(eL, Sound.BLOCK_BEACON_DEACTIVATE, 8f, 0.7f);
        }
        // судорожные эмоции, в конце - закрывается
        if (t < 12) eye.forceEmotionTicks(Emotion.SURPRISED, 4);
        else if (t > HIDE_TICKS - 10) eye.forceEmotionTicks(Emotion.BLINK, 4);
        else if (t % 3 == 0) eye.forceEmotionTicks(GLITCH_EMOTIONS[rnd.nextInt(GLITCH_EMOTIONS.length)], 4);

        // сжимается в точку, всё сильнее дрожит
        eye.visual(Math.max(0.03, 1.0 - 0.95 * Math.pow(pr, 1.6)) + 0.06 * Math.sin(t * 1.6), 0.08 + 0.5 * pr);

        // свечение вспухает и гаснет
        if (glow == null) glow = glowDisplay(eL);
        setScale(glow, eye.eyeSize() * (0.2 + 1.6 * Math.sin(Math.PI * pr)), eL);

        // всё стягивается в глаз
        int n = 3 + (int) (20 * pr);
        for (int i = 0; i < n; i++) {
            Vector u = randomUnit();
            double d = 4 + rnd.nextDouble() * 5;
            world.spawnParticle(Particle.END_ROD, p.getX() + u.getX() * d, p.getY() + u.getY() * d, p.getZ() + u.getZ() * d,
                    0, -u.getX(), -u.getY(), -u.getZ(), 0.4 + rnd.nextDouble() * 0.3, null, true);
        }
        if (t % 2 == 0) {
            Vector u = randomUnit();
            world.spawnParticle(Particle.LARGE_SMOKE, p.getX() + u.getX() * 3, p.getY() + u.getY() * 3, p.getZ() + u.getZ() * 3,
                    0, -u.getX(), -u.getY(), -u.getZ(), 0.3, null, true);
        }
        if (t % 10 == 0) sound(eL, Sound.BLOCK_NOTE_BLOCK_BIT, 6f, (float) (1.8 - 1.3 * pr));

        if (t >= HIDE_TICKS) {
            world.spawnParticle(Particle.EXPLOSION, eL, 1, 0, 0, 0, 0, null, true);
            world.spawnParticle(Particle.END_ROD, eL, 60, 0.5, 0.5, 0.5, 0.5, null, true);
            sound(eL, Sound.ENTITY_ENDERMAN_TELEPORT, 8f, 0.3f);
            sound(eL, Sound.ENTITY_GENERIC_EXPLODE, 4f, 1.8f);
            eye.finishHide();
            finish();
        }
    }

    // =====================================================================================
    //  Появление
    // =====================================================================================

    private void tickAppear() {
        if (t == 0) {
            for (Player p : world.getPlayers()) {
                loudAt(p, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1f, 0.8f);
                loudAt(p, Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 0.5f);
                loudAt(p, Sound.BLOCK_BEACON_POWER_SELECT, 1f, 0.5f);
            }
        }

        // ---------- небо лагает (до взрыва)
        if (t < T_BOOM + 5) {
            double ramp = Math.min(1.0, t / 70.0);
            skyGlitch(ramp);
            glitchPanels(ramp);
            if (t % 3 == 0) glitchBar();
            if (t % 2 == 0) {                          // цифровой треск
                for (Player p : audience) {
                    loudAt(p, Sound.BLOCK_NOTE_BLOCK_BIT, 0.7f, 0.5f + rnd.nextFloat() * 1.5f);
                }
            }
            if (t % 12 == 0 && t > 20) {
                for (Player p : audience) loudAt(p, Sound.ENTITY_WARDEN_NEARBY_CLOSE, 1f, 0.6f + rnd.nextFloat() * 0.6f);
            }
        }

        // ---------- глаз начинает проявляться
        if (t == T_EYE) {
            Player anchor = audience.get(rnd.nextInt(audience.size()));
            eye.relocateNear(anchor);
            eye.showNow();
            eyeShown = true;
            Vector p = eye.position();
            if (p != null) {
                Location eL = new Location(world, p.getX(), p.getY(), p.getZ());
                glow = glowDisplay(eL);
                for (Player pl : audience) loudAt(pl, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1f, 1.2f);
            }
        }
        if (eyeShown && t >= T_EYE && t < T_BOOM) {
            Vector p = eye.position();
            if (p != null) {
                Location eL = new Location(world, p.getX(), p.getY(), p.getZ());
                double pr = (double) (t - T_EYE) / (T_BOOM - T_EYE);
                eye.setHold(p);
                // собирается из помех: рваный рост размера и эмоций
                double jitter = rnd.nextDouble() < 0.3 ? 0.0 : 1.0;
                eye.visual(Math.max(0.04, (0.05 + 1.1 * pr * pr) * jitter + (jitter == 0.0 ? 0.02 : 0.0)), 0.1 + 0.5 * (1 - pr));
                if (t % 2 == 0) eye.forceEmotionTicks(GLITCH_EMOTIONS[rnd.nextInt(GLITCH_EMOTIONS.length)], 3);
                if (glow != null) setScale(glow, eye.eyeSize() * (0.3 + 2.2 * pr), eL);
                int n = 6 + (int) (30 * pr);
                for (int i = 0; i < n; i++) {
                    Vector u = randomUnit();
                    double d = 3 + rnd.nextDouble() * 6;
                    world.spawnParticle(Particle.ELECTRIC_SPARK, p.getX() + u.getX() * d, p.getY() + u.getY() * d, p.getZ() + u.getZ() * d,
                            0, -u.getX(), -u.getY(), -u.getZ(), 0.5, null, true);
                }
                if (t % 4 == 0) {
                    for (Player pl : audience) loudAt(pl, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, (float) (0.5 + 1.5 * pr));
                }
            }
        }

        // ---------- взрыв света: небо приходит в норму
        if (t == T_BOOM) {
            Vector p = eye.position();
            Location eL = p == null ? world.getSpawnLocation() : new Location(world, p.getX(), p.getY(), p.getZ());
            for (Player pl : world.getPlayers()) {
                loudAt(pl, Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 0.7f);
                loudAt(pl, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 0.5f);
                loudAt(pl, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.5f);
                loudAt(pl, Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 0.4f);
                loudAt(pl, Sound.BLOCK_BEACON_ACTIVATE, 1f, 0.6f);
            }
            sound(eL, Sound.ENTITY_WARDEN_SONIC_BOOM, 12f, 0.6f);
            sound(eL, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 12f, 0.5f);
            world.strikeLightningEffect(eL);
            world.spawnParticle(Particle.EXPLOSION_EMITTER, eL, 3, 1.5, 1.5, 1.5, 0, null, true);
            world.spawnParticle(Particle.SONIC_BOOM, eL, 1, 0, 0, 0, 0, null, true);
            world.spawnParticle(Particle.END_ROD, eL, 150, 1.0, 1.0, 1.0, 0.6, null, true);
            eye.forceEmotionTicks(Emotion.SURPRISED, 40);
            eye.visual(1.3, 0.0);
            if (glow != null) setScale(glow, eye.eyeSize() * 3.5, eL);
            // все помехи исчезают, время и погода возвращаются
            for (Panel pnl : panels) pnl.d.remove();
            panels.clear();
            for (Player pl : world.getPlayers()) {
                pl.resetPlayerTime();
                pl.resetPlayerWeather();
                pl.sendActionBar(Component.text("", TextColor.color(0xFFFFFF)));
            }
        }

        // ---------- после взрыва: ударные кольца, глаз успокаивается
        if (t > T_BOOM) {
            Vector p = eye.position();
            if (p != null) {
                Location eL = new Location(world, p.getX(), p.getY(), p.getZ());
                int k = t - T_BOOM;
                double r = k * 2.2;
                for (int i = 0; i < 48; i++) {
                    double a = Math.PI * 2 * i / 48;
                    world.spawnParticle(Particle.END_ROD, p.getX() + Math.cos(a) * r, p.getY(), p.getZ() + Math.sin(a) * r,
                            0, 0, 0, 0, 0, null, true);
                }
                eye.visual(1.0 + 0.3 * Math.max(0, 1 - k / 20.0), 0.0);
                if (glow != null) setScale(glow, Math.max(0.01, eye.eyeSize() * 3.5 * (1 - k / 25.0)), eL);
                if (k == 25) eye.forceEmotionTicks(Emotion.NORMAL, 2);
            }
        }

        if (t >= APPEAR_TICKS) {
            if (!eyeShown) eye.showNow();
            finish();
        }
    }

    /** Небо лагает: время у игроков замирает и скачет, погода мигает. */
    private void skyGlitch(double ramp) {
        for (Player p : audience) {
            if (rnd.nextDouble() < 0.25 + 0.5 * ramp) {
                p.setPlayerTime(GLITCH_TIMES[rnd.nextInt(GLITCH_TIMES.length)], false);
            }
            if (rnd.nextDouble() < 0.06 + 0.1 * ramp) {
                p.setPlayerWeather(rnd.nextBoolean() ? WeatherType.DOWNFALL : WeatherType.CLEAR);
            }
        }
    }

    /** Нарисованные помехи: плоскости-текстуры дёргаются по небу вокруг каждого игрока. */
    private void glitchPanels(double ramp) {
        if (panels.isEmpty()) {
            for (Player p : audience) {
                for (int i = 0; i < PANELS_PER_PLAYER; i++) {
                    ItemDisplay d = world.spawn(p.getLocation().add(0, 30, 0), ItemDisplay.class, g -> {
                        g.setItemStack(glitchA);
                        g.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                        g.setBillboard(Display.Billboard.CENTER);
                        g.setBrightness(new Display.Brightness(15, 15));
                        g.setViewRange(3f);
                        g.setShadowRadius(0f);
                        g.setPersistent(false);
                        g.setInvulnerable(true);
                        g.setGravity(false);
                        g.addScoreboardTag(TAG);
                        g.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.01f, 0.01f, 0.01f), new AxisAngle4f()));
                    });
                    panels.add(new Panel(d, p));
                }
            }
        }
        int active = (int) Math.ceil(panels.size() * ramp);
        for (int i = 0; i < panels.size(); i++) {
            Panel pn = panels.get(i);
            if (!pn.owner.isOnline()) continue;
            if (i >= active) continue;
            if (rnd.nextDouble() > 0.45) continue;                // часть плоскостей на секунду замирает, часть прыгает
            double az = rnd.nextDouble() * Math.PI * 2;
            double el = Math.toRadians(12 + rnd.nextDouble() * 65);
            double dist = 22 + rnd.nextDouble() * 35;
            Location o = pn.owner.getLocation();
            Location at = o.clone().add(Math.cos(az) * Math.cos(el) * dist, Math.sin(el) * dist + 1.5, Math.sin(az) * Math.cos(el) * dist);
            pn.d.teleport(at);
            pn.d.setItemStack(rnd.nextInt(4) == 0 ? glitchB : glitchA);
            float s = (float) (7 + rnd.nextDouble() * 16);
            float sy = s * (float) (0.4 + rnd.nextDouble() * 1.0);
            pn.d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateZ((float) (rnd.nextGaussian() * 0.5)),
                    new Vector3f(s, sy, s), new Quaternionf()));
        }
    }

    /** Сломанная строка над хотбаром. */
    private void glitchBar() {
        StringBuilder sb = new StringBuilder("⚠ ");
        for (int i = 0; i < 18; i++) sb.append(GLITCH_CHARS.charAt(rnd.nextInt(GLITCH_CHARS.length())));
        sb.append(" ⚠");
        Component c = Component.text(sb.toString(), TextColor.color(rnd.nextBoolean() ? 0x00FFF0 : 0xFF00C8));
        for (Player p : audience) p.sendActionBar(c);
    }

    // =====================================================================================
    //  Вспомогательное
    // =====================================================================================

    private ItemStack modelItem(String model) {
        ItemStack item = new ItemStack(Material.PAPER);
        NamespacedKey key = Objects.requireNonNull(NamespacedKey.fromString(model));
        item.editMeta(meta -> meta.setItemModel(key));
        return item;
    }

    private ItemDisplay glowDisplay(Location at) {
        return world.spawn(at, ItemDisplay.class, g -> {
            g.setItemStack(modelItem("eyesky:9"));                // свечение
            g.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            g.setBillboard(Display.Billboard.CENTER);
            g.setBrightness(new Display.Brightness(15, 15));
            g.setViewRange(8f);
            g.setShadowRadius(0f);
            g.setTeleportDuration(1);
            g.setPersistent(false);
            g.setInvulnerable(true);
            g.setGravity(false);
            g.addScoreboardTag(TAG);
            g.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.01f, 0.01f, 0.01f), new AxisAngle4f()));
        });
    }

    private void setScale(ItemDisplay d, double s, Location where) {
        if (d == null) return;
        float f = (float) Math.max(0.01, s);
        d.teleport(where);
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(1);
        d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(f, f, f), new AxisAngle4f()));
    }

    private void sound(Location l, Sound s, float volume, float pitch) {
        world.playSound(l, s, SoundCategory.MASTER, volume, pitch);
    }

    /** Звук прямо в ушах у игрока (не зависит от расстояния до глаза). */
    private void loudAt(Player p, Sound s, float volume, float pitch) {
        p.playSound(p.getLocation(), s, SoundCategory.MASTER, volume, pitch);
    }

    private Vector randomUnit() {
        Vector v;
        do {
            v = new Vector(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian());
        } while (v.lengthSquared() < 1e-6);
        return v.normalize();
    }
}
