package com.example.stockticker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class StockTicker extends JavaPlugin {

    private final Map<String, Quote> latestQuotes = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private Scoreboard sharedScoreboard;
    private Objective objective;
    private int taskId = -1;
    private int tapeTaskId = -1;
    private SignManager signManager;
    private StockDisplayManager displayManager;
    private TickerTapeManager tapeManager;
    private List<String> apiKeys = List.of();
    private final AtomicInteger apiKeyCursor = new AtomicInteger(0);

    @Override
    public void onEnable() {
        saveDefaultConfig(); // copies config.yml from resources on first run

        signManager = new SignManager(this);
        displayManager = new StockDisplayManager(this);
        tapeManager = new TickerTapeManager(this);

        sharedScoreboard = getServer().getScoreboardManager().getNewScoreboard();
        if (getConfig().getBoolean("enable-scoreboard", false)) {
            setupScoreboard();
        }
        startFetchLoop();
        startTapeScrollLoop();

        getServer().getPluginManager().registerEvents(new JoinListener(sharedScoreboard), this);
        getServer().getPluginManager().registerEvents(new StockSignListener(signManager), this);

        getLogger().info("StockTicker enabled. Scoreboard: "
                + (getConfig().getBoolean("enable-scoreboard", false) ? "on" : "off")
                + ". Tracking " + signManager.size() + " sign(s), " + displayManager.size()
                + " big display(s), and " + tapeManager.size() + " ticker tape(s).");
    }

    @Override
    public void onDisable() {
        if (taskId != -1) {
            getServer().getScheduler().cancelTask(taskId);
        }
        if (tapeTaskId != -1) {
            getServer().getScheduler().cancelTask(tapeTaskId);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("stocks")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                if (getConfig().getBoolean("enable-scoreboard", false)) {
                    setupScoreboard();
                }
                if (taskId != -1) {
                    getServer().getScheduler().cancelTask(taskId);
                }
                if (tapeTaskId != -1) {
                    getServer().getScheduler().cancelTask(tapeTaskId);
                }
                startFetchLoop();
                startTapeScrollLoop();
                sender.sendMessage(ChatColor.GREEN + "StockTicker config reloaded.");
                return true;
            }
            sender.sendMessage(ChatColor.YELLOW + "Usage: /stocks reload");
            return true;
        }

        if (command.getName().equalsIgnoreCase("stockdisplay")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "This command can only be used in-game.");
                return true;
            }
            if (!player.hasPermission("stockticker.display")) {
                player.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                return true;
            }

            if (args.length >= 2 && args[0].equalsIgnoreCase("create")) {
                String ticker = args[1].toUpperCase();
                double scale = 3.0;
                if (args.length >= 3) {
                    try {
                        scale = Double.parseDouble(args[2]);
                    } catch (NumberFormatException e) {
                        player.sendMessage(ChatColor.RED + "Scale must be a number, e.g. 3.0");
                        return true;
                    }
                }
                float rotationOffset = 0f;
                if (args.length >= 4) {
                    try {
                        rotationOffset = Float.parseFloat(args[3]);
                    } catch (NumberFormatException e) {
                        player.sendMessage(ChatColor.RED + "Rotation must be a number of degrees, e.g. 90");
                        return true;
                    }
                }

                PlacementResult placement = resolvePlacement(player, rotationOffset);
                displayManager.create(placement.location(), ticker, scale);
                player.sendMessage(ChatColor.GREEN + "Created a big display for " + ticker
                        + " (scale " + scale + "), "
                        + (placement.mounted() ? "mounted on the wall." : "floating — no nearby wall found.")
                        + " It'll populate on the next refresh.");
                return true;
            }

            if (args.length >= 1 && args[0].equalsIgnoreCase("tape")) {
                if (args.length >= 5 && args[1].equalsIgnoreCase("create")) {
                    double width;
                    double height;
                    try {
                        width = Double.parseDouble(args[2]);
                        height = Double.parseDouble(args[3]);
                    } catch (NumberFormatException e) {
                        player.sendMessage(ChatColor.RED + "Width and height must be numbers, e.g. 8 3");
                        return true;
                    }

                    List<String> tickers = Arrays.stream(args[4].split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .map(String::toUpperCase)
                            .toList();
                    if (tickers.isEmpty()) {
                        player.sendMessage(ChatColor.RED
                                + "Give at least one ticker, comma-separated with no spaces, e.g. AAPL,MSFT,TSLA");
                        return true;
                    }

                    float rotationOffset = 0f;
                    if (args.length >= 6) {
                        try {
                            rotationOffset = Float.parseFloat(args[5]);
                        } catch (NumberFormatException e) {
                            player.sendMessage(ChatColor.RED + "Rotation must be a number of degrees, e.g. 90");
                            return true;
                        }
                    }

                    PlacementResult placement = resolvePlacement(player, rotationOffset);
                    tapeManager.create(placement.location(), tickers, width, height);
                    player.sendMessage(ChatColor.GREEN + "Created a " + width + "x" + height
                            + " scrolling ticker tape for " + String.join(", ", tickers) + ", "
                            + (placement.mounted() ? "mounted on the wall." : "floating — no nearby wall found.")
                            + " It'll start scrolling once quotes come in.");
                    return true;
                }

                player.sendMessage(ChatColor.YELLOW
                        + "Usage: /stockdisplay tape create <width> <height> <TICKER1,TICKER2,...> [rotation]");
                return true;
            }

            if (args.length >= 1 && args[0].equalsIgnoreCase("remove")) {
                double radius = 5.0;
                if (args.length >= 2) {
                    try {
                        radius = Double.parseDouble(args[1]);
                    } catch (NumberFormatException e) {
                        player.sendMessage(ChatColor.RED + "Radius must be a number, e.g. 5.0");
                        return true;
                    }
                }

                Entity nearestDisplay = displayManager.findNearestTrackedDisplay(player.getLocation(), radius);
                Entity nearestTape = tapeManager.findNearestTrackedTape(player.getLocation(), radius);
                Entity target = closerOf(player.getLocation(), nearestDisplay, nearestTape);
                if (target == null) {
                    player.sendMessage(ChatColor.RED + "No tracked stock display or ticker tape within "
                            + radius + " blocks of you.");
                    return true;
                }
                if (displayManager.removeIfTracked(target)) {
                    player.sendMessage(ChatColor.GREEN + "Removed the nearest stock display.");
                } else if (tapeManager.removeIfTracked(target)) {
                    player.sendMessage(ChatColor.GREEN + "Removed the nearest ticker tape.");
                }
                return true;
            }

            if (args.length >= 1 && args[0].equalsIgnoreCase("rotate")) {
                float degrees = 90f;
                if (args.length >= 2) {
                    try {
                        degrees = Float.parseFloat(args[1]);
                    } catch (NumberFormatException e) {
                        player.sendMessage(ChatColor.RED + "Degrees must be a number, e.g. 90 or -45");
                        return true;
                    }
                }

                Entity nearestDisplay = displayManager.findNearestTrackedDisplay(player.getLocation(), 5.0);
                Entity nearestTape = tapeManager.findNearestTrackedTape(player.getLocation(), 5.0);
                Entity target = closerOf(player.getLocation(), nearestDisplay, nearestTape);
                if (target == null) {
                    player.sendMessage(ChatColor.RED + "No tracked stock display or ticker tape within 5 blocks of you.");
                    return true;
                }
                if (displayManager.rotate(target, degrees)) {
                    player.sendMessage(ChatColor.GREEN + "Rotated the nearest stock display by " + degrees + " degrees.");
                } else if (tapeManager.rotate(target, degrees)) {
                    player.sendMessage(ChatColor.GREEN + "Rotated the nearest ticker tape by " + degrees + " degrees.");
                }
                return true;
            }

            if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
                player.sendMessage(ChatColor.YELLOW + "Tracking " + displayManager.size()
                        + " big stock display(s): " + String.join(", ", displayManager.getTrackedTickers()));
                player.sendMessage(ChatColor.YELLOW + "Tracking " + tapeManager.size()
                        + " scrolling ticker tape(s), showing: " + String.join(", ", tapeManager.getTrackedTickers()));
                return true;
            }

            player.sendMessage(ChatColor.YELLOW + "Usage: /stockdisplay create <TICKER> [scale] [rotation] "
                    + "| /stockdisplay tape create <width> <height> <TICKERS,...> [rotation] "
                    + "| /stockdisplay rotate [degrees] | /stockdisplay remove [radius] | /stockdisplay list");
            return true;
        }

        return false;
    }

    /** Whichever of a/b is non-null and closer to origin; null if both are null. */
    private Entity closerOf(Location origin, Entity a, Entity b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.getLocation().distanceSquared(origin) <= b.getLocation().distanceSquared(origin) ? a : b;
    }

    /**
     * Where a newly-created display/tape should spawn and face. Looks for a wall
     * the player is aiming at within a short range and, if the hit face is a flat
     * cardinal one (not a ceiling/floor), mounts flush against it facing outward.
     * Falls back to floating a couple of blocks in front of the player, facing back
     * toward them, if there's no usable surface in range.
     */
    private PlacementResult resolvePlacement(Player player, float rotationOffset) {
        RayTraceResult hit = player.rayTraceBlocks(6.0);
        BlockFace face = (hit != null) ? hit.getHitBlockFace() : null;
        Float wallYaw = (face != null) ? yawForCardinalFace(face) : null;

        if (hit != null && wallYaw != null) {
            Vector normal = new Vector(face.getModX(), face.getModY(), face.getModZ());
            Location loc = hit.getHitPosition().toLocation(player.getWorld()).add(normal.multiply(0.02));
            loc.setYaw(wallYaw + rotationOffset);
            loc.setPitch(0f);
            return new PlacementResult(loc, true);
        }

        Location loc = player.getEyeLocation().add(
                player.getEyeLocation().getDirection().normalize().multiply(2));
        loc.setYaw(player.getEyeLocation().getYaw() + 180f + rotationOffset);
        loc.setPitch(0f);
        return new PlacementResult(loc, false);
    }

    private record PlacementResult(Location location, boolean mounted) {}

    private void setupScoreboard() {
        // Remove any previous objective before re-registering (e.g. on /stocks reload).
        Objective old = sharedScoreboard.getObjective("stocks");
        if (old != null) {
            old.unregister();
        }

        String rawTitle = getConfig().getString("scoreboard-title", "&a&lStock Ticker");
        String title = ChatColor.translateAlternateColorCodes('&', rawTitle);

        objective = sharedScoreboard.registerNewObjective("stocks", "dummy", title);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Assign this scoreboard to every currently online player.
        for (Player p : getServer().getOnlinePlayers()) {
            p.setScoreboard(sharedScoreboard);
        }
    }

    private void startFetchLoop() {
        int refreshTicks = getConfig().getInt("refresh-seconds", 30) * 20;
        loadApiKeys();

        if (apiKeys.isEmpty()) {
            getLogger().warning("No Finnhub API key(s) set in config.yml — quotes will not update.");
        } else if (apiKeys.size() > 1) {
            getLogger().info("Rotating across " + apiKeys.size() + " Finnhub API keys.");
        }

        // Run async so the HTTP calls never block the main server thread,
        // then hop back to the main thread to touch Bukkit API (scoreboard/signs).
        taskId = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            // Union of the scoreboard's configured tickers and whatever tickers are
            // currently displayed on in-world stock signs — fetched once per symbol,
            // however many places it's displayed.
            Set<String> allTickers = new LinkedHashSet<>();
            if (getConfig().getBoolean("enable-scoreboard", false)) {
                allTickers.addAll(getConfig().getStringList("tickers"));
            }
            allTickers.addAll(signManager.getTrackedTickers());
            allTickers.addAll(displayManager.getTrackedTickers());
            allTickers.addAll(tapeManager.getTrackedTickers());
            // Guard against corrupted/legacy data (e.g. a leftover non-ticker entry in
            // a *.yml file from an earlier build) ever reaching the API as a "symbol".
            allTickers.removeIf(t -> t == null || !t.matches("[A-Za-z0-9.=\\-]{1,15}"));

            for (String ticker : allTickers) {
                if (ticker.toUpperCase().endsWith("=F")) {
                    fetchYahooFuturesQuote(ticker); // Yahoo endpoint is keyless, no rotation needed
                } else {
                    fetchQuote(ticker, nextApiKey());
                }
            }
            // Back to main thread to safely touch Bukkit API.
            getServer().getScheduler().runTask(this, () -> {
                long staleAfterMillis = getConfig().getInt("stale-after-seconds", 120) * 1000L;
                if (getConfig().getBoolean("enable-scoreboard", false)) {
                    renderScoreboard();
                }
                signManager.updateSigns(latestQuotes, staleAfterMillis);
                displayManager.updateDisplays(latestQuotes, staleAfterMillis);
                tapeManager.refreshContent(latestQuotes, staleAfterMillis);
            });
        }, 0L, refreshTicks).getTaskId();
    }

    /**
     * Separate, much faster repeating task that just advances each ticker tape's
     * scroll position and rewrites its visible text window — decoupled from the
     * Finnhub/Yahoo fetch cadence (refresh-seconds) since scrolling needs to run
     * many times a second to look continuous, while fetching quotes doesn't.
     */
    private void startTapeScrollLoop() {
        int scrollTicks = Math.max(1, getConfig().getInt("tape-scroll-ticks", 4));
        tapeTaskId = getServer().getScheduler().runTaskTimer(this, () -> tapeManager.tick(), 0L, scrollTicks).getTaskId();
    }

    /**
     * Reads the configured Finnhub key(s) into apiKeys. Accepts either a list under
     * `api-keys` (preferred — lets calls round-robin across multiple free-tier keys
     * for a higher effective rate limit) or a single `api-key` string for backward
     * compatibility. Blank entries and the unfilled-in placeholder are skipped.
     */
    private void loadApiKeys() {
        List<String> pool = new java.util.ArrayList<>();

        for (String key : getConfig().getStringList("api-keys")) {
            if (key != null && !key.isBlank() && !key.equals("YOUR_FINNHUB_API_KEY")) {
                pool.add(key.trim());
            }
        }

        if (pool.isEmpty()) {
            String single = getConfig().getString("api-key", "");
            if (single != null && !single.isBlank() && !single.equals("YOUR_FINNHUB_API_KEY")) {
                pool.add(single.trim());
            }
        }

        apiKeys = List.copyOf(pool);
        apiKeyCursor.set(0);
    }

    /** Round-robins through the configured key pool, one key per call, wrapping around. */
    private String nextApiKey() {
        if (apiKeys.isEmpty()) {
            return "";
        }
        int index = Math.floorMod(apiKeyCursor.getAndIncrement(), apiKeys.size());
        return apiKeys.get(index);
    }

    /**
     * Yaw that makes a static (Billboard.FIXED) display face outward from the given
     * block face — e.g. mounted on a wall's north face should read facing north, back
     * toward whoever's standing in front of it. Returns null for UP/DOWN or any
     * non-cardinal face, since there's no flat "wall" yaw for those.
     */
    private Float yawForCardinalFace(BlockFace face) {
        return switch (face) {
            case NORTH -> 180f;
            case SOUTH -> 0f;
            case WEST -> 90f;
            case EAST -> 270f;
            default -> null;
        };
    }

    private void fetchQuote(String ticker, String apiKey) {
        if (apiKey.isBlank()) {
            return; // no key available this cycle — warned about at startup/reload
        }
        try {
            String url = "https://finnhub.io/api/v1/quote?symbol=" + ticker + "&token=" + apiKey;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                getLogger().warning("Finnhub returned HTTP " + response.statusCode() + " for " + ticker);
                return;
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            // Finnhub /quote fields: c = current price, d = change, dp = percent change, t = unix seconds
            double current = json.get("c").getAsDouble();
            double change = json.get("d").isJsonNull() ? 0.0 : json.get("d").getAsDouble();
            double percent = json.get("dp").isJsonNull() ? 0.0 : json.get("dp").getAsDouble();
            long timestampMillis = (json.has("t") && !json.get("t").isJsonNull())
                    ? json.get("t").getAsLong() * 1000L
                    : System.currentTimeMillis();

            latestQuotes.put(ticker, new Quote(current, change, percent, timestampMillis));
        } catch (Exception e) {
            getLogger().warning("Failed to fetch quote for " + ticker + ": " + e.getMessage());
        }
    }

    /**
     * Fetches futures data (e.g. "NQ=F", "MNQ=F") from Yahoo Finance's unofficial
     * chart endpoint. This isn't a documented/supported API — no key, no rate-limit
     * guarantees, no SLA — so treat it as "best effort, latest available data" only.
     */
    private void fetchYahooFuturesQuote(String ticker) {
        try {
            String encoded = URLEncoder.encode(ticker, StandardCharsets.UTF_8);
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + encoded;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    // Yahoo's unofficial endpoint is more likely to reject requests that
                    // don't look like a real browser hit — these headers help but are not
                    // a guarantee; Yahoo can still rate-limit/block regardless.
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                    .header("Accept", "application/json")
                    .header("Referer", "https://finance.yahoo.com/")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                getLogger().warning("Yahoo Finance returned HTTP " + response.statusCode() + " for " + ticker);
                return;
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject chart = root.getAsJsonObject("chart");
            if (chart.has("error") && !chart.get("error").isJsonNull()) {
                getLogger().warning("Yahoo Finance error for " + ticker + ": " + chart.get("error"));
                return;
            }

            JsonObject meta = chart.getAsJsonArray("result").get(0).getAsJsonObject().getAsJsonObject("meta");

            double current = meta.get("regularMarketPrice").getAsDouble();
            double prevClose = meta.has("previousClose") && !meta.get("previousClose").isJsonNull()
                    ? meta.get("previousClose").getAsDouble()
                    : meta.get("chartPreviousClose").getAsDouble();
            double change = current - prevClose;
            double percent = prevClose != 0 ? (change / prevClose) * 100.0 : 0.0;
            long timestampMillis = (meta.has("regularMarketTime") && !meta.get("regularMarketTime").isJsonNull())
                    ? meta.get("regularMarketTime").getAsLong() * 1000L
                    : System.currentTimeMillis();

            latestQuotes.put(ticker, new Quote(current, change, percent, timestampMillis));
        } catch (Exception e) {
            getLogger().warning("Failed to fetch Yahoo futures quote for " + ticker + ": " + e.getMessage());
        }
    }

    private void renderScoreboard() {
        // Clear existing lines/teams before re-writing them.
        for (String entry : sharedScoreboard.getEntries()) {
            sharedScoreboard.resetScores(entry);
        }

        List<String> tickers = getConfig().getStringList("tickers");
        int score = tickers.size();

        // Use invisible-colour-coded "fake players" as sidebar lines, one per ticker.
        Map<String, String> lines = new LinkedHashMap<>();
        for (String ticker : tickers) {
            Quote q = latestQuotes.get(ticker);
            String line;
            if (q == null) {
                line = ChatColor.GRAY + ticker + ": ..." ;
            } else {
                ChatColor color = q.change() >= 0 ? ChatColor.GREEN : ChatColor.RED;
                String arrow = q.change() >= 0 ? "▲" : "▼";
                line = ChatColor.WHITE + ticker + ChatColor.GRAY + ": "
                        + color + String.format("$%.2f %s%.2f%%", q.price(), arrow, Math.abs(q.percentChange()));
            }
            lines.put(ticker, line);
        }

        for (String ticker : tickers) {
            String entryText = lines.get(ticker);
            // Bukkit scoreboard entries must be unique per line; use hidden color-code
            // suffixes per ticker index to guarantee uniqueness if two lines render the same text.
            registerLine(entryText, score);
            score--;
        }

        // Re-apply scoreboard to any players who joined after setup.
        for (Player p : getServer().getOnlinePlayers()) {
            if (p.getScoreboard() != sharedScoreboard) {
                p.setScoreboard(sharedScoreboard);
            }
        }
    }

    private void registerLine(String text, int score) {
        // Truncate to scoreboard line limits (Minecraft 1.13+ removed the old 40-char cap on
        // modern clients, but keep this conservative for compatibility).
        String safeText = text.length() > 64 ? text.substring(0, 64) : text;
        Team team = sharedScoreboard.getTeam("line" + score);
        if (team == null) {
            team = sharedScoreboard.registerNewTeam("line" + score);
        }
        String entry = ChatColor.values()[score % 15].toString() + ChatColor.RESET;
        team.addEntry(entry);
        team.setPrefix(safeText);
        objective.getScore(entry).setScore(score);
    }
}
