package com.example.stockticker;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks scrolling multi-ticker "tape" displays: a single wide TextDisplay entity
 * per tape that cycles a fixed-width window of characters through a longer
 * concatenated quote string, giving a stock-exchange marquee effect.
 *
 * This is a character-window scroll, not a smoothly-translating one: Minecraft's
 * TextDisplay entities have no clipping/masking, so continuously moving one
 * through space would eventually show it drifting outside the "sign" area rather
 * than looking like text scrolling behind a fixed frame. Shifting the visible
 * substring every tick avoids that and is cheap, at the cost of being a stepped
 * (character-by-character) scroll rather than a literally smooth one.
 *
 * Sizing in blocks is necessarily approximate — Minecraft's font isn't block-metric —
 * so CHARS_PER_BLOCK_AT_SCALE_1 below is a tuned estimate, not a guarantee. Adjust it
 * (or just the width/height a tape is created with) if tapes render too cramped or
 * too sparse for your taste.
 */
public class TickerTapeManager {

    private static final double CHARS_PER_BLOCK_AT_SCALE_1 = 8.0;
    private static final int MIN_WINDOW_CHARS = 6;
    private static final String SEPARATOR = "   |   ";

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, TickerTape> tapes = new ConcurrentHashMap<>();

    public TickerTapeManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "tickertapes.yml");
        load();
    }

    /**
     * Spawns a new black-background/white-text scrolling tape at the given location,
     * cycling through the given tickers, sized (approximately) to widthBlocks x
     * heightBlocks.
     */
    public TextDisplay create(Location location, List<String> tickers, double widthBlocks, double heightBlocks) {
        double scale = heightBlocks;
        int windowChars = computeWindowChars(widthBlocks, heightBlocks);

        TextDisplay display = location.getWorld().spawn(location, TextDisplay.class, entity -> {
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(Color.fromARGB(255, 0, 0, 0)); // solid opaque black
            entity.setSeeThrough(false);
            entity.setShadowed(false);
            // Wide enough that Minecraft never auto-wraps the line — our own
            // character-window truncation is what actually controls visible width.
            entity.setLineWidth(2000);
            entity.setAlignment(TextDisplay.TextAlignment.LEFT);
            entity.setText(ChatColor.GRAY + "...");

            Transformation transform = new Transformation(
                    new Vector3f(0f, 0f, 0f),
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f((float) scale, (float) scale, (float) scale),
                    new AxisAngle4f(0f, 0f, 0f, 1f)
            );
            entity.setTransformation(transform);
        });

        tapes.put(display.getUniqueId(), new TickerTape(tickers, widthBlocks, heightBlocks, windowChars));
        save();
        return display;
    }

    public boolean removeIfTracked(Entity entity) {
        if (tapes.remove(entity.getUniqueId()) != null) {
            entity.remove();
            save();
            return true;
        }
        return false;
    }

    /** Rotates a tracked tape in place, same convention as StockDisplayManager#rotate. */
    public boolean rotate(Entity entity, float degrees) {
        if (!tapes.containsKey(entity.getUniqueId())) {
            return false;
        }
        Location loc = entity.getLocation();
        loc.setYaw(loc.getYaw() + degrees);
        entity.teleport(loc);
        return true;
    }

    public int size() {
        return tapes.size();
    }

    /** Distinct set of every ticker referenced by any tape, so they get fetched. */
    public Set<String> getTrackedTickers() {
        Set<String> all = new LinkedHashSet<>();
        for (TickerTape tape : tapes.values()) {
            all.addAll(tape.tickers);
        }
        return all;
    }

    public Entity findNearestTrackedTape(Location location, double radius) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }

        Entity nearest = null;
        double nearestDistSq = radius * radius;

        for (UUID id : tapes.keySet()) {
            Entity entity = Bukkit.getEntity(id);
            if (entity == null || !entity.getWorld().equals(world)) {
                continue;
            }
            double distSq = entity.getLocation().distanceSquared(location);
            if (distSq <= nearestDistSq) {
                nearest = entity;
                nearestDistSq = distSq;
            }
        }

        return nearest;
    }

    /**
     * Rebuilds every tape's underlying content string from the latest quotes.
     * Call whenever quotes refresh (same cadence as signs/displays), not every tick.
     * Kept single-color (no per-quote red/green) deliberately: color codes are
     * two-character sequences, and slicing a fixed-width window through a string
     * containing them can cut a code in half at the window boundary, which briefly
     * shows a stray letter. Plain text with a directional arrow avoids that.
     */
    public void refreshContent(Map<String, Quote> quotes) {
        for (TickerTape tape : tapes.values()) {
            StringBuilder sb = new StringBuilder();
            for (String ticker : tape.tickers) {
                if (sb.length() > 0) {
                    sb.append(SEPARATOR);
                }
                Quote q = quotes.get(ticker);
                if (q == null) {
                    sb.append(ticker).append(" ...");
                } else {
                    String arrow = q.change() >= 0 ? "^" : "v";
                    sb.append(String.format("%s $%.2f %s%.1f%%", ticker, q.price(), arrow, Math.abs(q.percentChange())));
                }
            }
            sb.append(SEPARATOR); // gap before the loop repeats
            tape.content = sb.toString();
        }
    }

    /**
     * Advances every tape's scroll position by one character and rewrites its
     * visible window. Call on a fast repeating sync task (main thread) — this
     * touches Bukkit entities directly, unlike the async quote-fetch loop.
     */
    public void tick() {
        for (Map.Entry<UUID, TickerTape> entry : tapes.entrySet()) {
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof TextDisplay display)) {
                continue; // not currently loaded
            }

            TickerTape tape = entry.getValue();
            String content = tape.content;
            if (content == null || content.isEmpty()) {
                continue; // no quotes fetched yet
            }

            int len = content.length();
            tape.cursor = (tape.cursor + 1) % len;

            StringBuilder window = new StringBuilder(tape.windowChars);
            for (int i = 0; i < tape.windowChars; i++) {
                window.append(content.charAt((tape.cursor + i) % len));
            }
            display.setText(ChatColor.WHITE + window.toString());
        }
    }

    private static int computeWindowChars(double widthBlocks, double heightBlocks) {
        double scale = Math.max(0.1, heightBlocks);
        return (int) Math.max(MIN_WINDOW_CHARS, Math.round(widthBlocks / scale * CHARS_PER_BLOCK_AT_SCALE_1));
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("tapes");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection tapeSection = section.getConfigurationSection(key);
                if (tapeSection == null) {
                    continue;
                }
                List<String> tickers = tapeSection.getStringList("tickers");
                double width = tapeSection.getDouble("width", 8.0);
                double height = tapeSection.getDouble("height", 3.0);
                if (tickers.isEmpty()) {
                    continue;
                }
                tapes.put(id, new TickerTape(tickers, width, height, computeWindowChars(width, height)));
            } catch (IllegalArgumentException ignored) {
                // corrupt UUID key, skip
            }
        }
        plugin.getLogger().info("Loaded " + tapes.size() + " tracked ticker tape(s).");
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, TickerTape> entry : tapes.entrySet()) {
            String key = entry.getKey().toString();
            TickerTape tape = entry.getValue();
            yaml.set("tapes." + key + ".tickers", tape.tickers);
            yaml.set("tapes." + key + ".width", tape.widthBlocks);
            yaml.set("tapes." + key + ".height", tape.heightBlocks);
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save tickertapes.yml: " + e.getMessage());
        }
    }

    private static final class TickerTape {
        final List<String> tickers;
        final double widthBlocks;
        final double heightBlocks;
        final int windowChars;
        volatile String content = "";
        volatile int cursor = 0;

        TickerTape(List<String> tickers, double widthBlocks, double heightBlocks, int windowChars) {
            this.tickers = tickers;
            this.widthBlocks = widthBlocks;
            this.heightBlocks = heightBlocks;
            this.windowChars = windowChars;
        }
    }
}
