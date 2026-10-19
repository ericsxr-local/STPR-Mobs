package com.redmoore.stprmobs;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class STPRMobsPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final Map<String, MobTemplate> mobTemplates = new LinkedHashMap<>();
    private final Map<String, MobMap> mobMaps = new LinkedHashMap<>();
    
    // 每个 STPR 对局只记录本局真正生成过怪物的区块，结束时按区块临时加载并清理。
    private final Map<String, Set<Long>> sessionChunks = new HashMap<>();
    private final Map<String, Map<Long, String>> sessionChunkWorlds = new HashMap<>();

    private final Map<String, Set<Long>> sessionKeepLoadedChunks = new HashMap<>();
    private final Map<String, Map<Long, String>> sessionKeepLoadedWorlds = new HashMap<>();
    private final Map<UUID, SpawnRecord> spawned = new HashMap<>();
    private final Map<String, SessionState> liveSessions = new HashMap<>();
    /** Links STPR Match instance identity -> generated session id. */
    private final IdentityHashMap<Object, String> stprMatchSessions = new IdentityHashMap<>();
    private NamespacedKey mobKey;
    private NamespacedKey sessionKey;
    private NamespacedKey pointKey;
    private long tickCounter;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mobKey = new NamespacedKey(this, "template");
        sessionKey = new NamespacedKey(this, "session");
        pointKey = new NamespacedKey(this, "point");
        loadAll();
        getServer().getPluginManager().registerEvents(this, this);
        var command = getCommand("stprmob");
        if (command == null) throw new IllegalStateException("plugin.yml is missing stprmob command");
        command.setExecutor(this);
        command.setTabCompleter(this);
        long period = Math.max(1, getConfig().getLong("settings.tick-interval", 20L));
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, period);
        getLogger().info("STPR-Mobs enabled. Mob templates: " + mobTemplates.size() + ", maps: " + mobMaps.size());
    }

    @Override
    public void onDisable() {
        cleanupAllSpawned();
        spawned.clear();
        for (String sessionId : new ArrayList<>(sessionKeepLoadedChunks.keySet())) {
            releaseSessionChunkTickets(sessionId);
        }
        liveSessions.clear();
    }

    private void loadAll() {
        mobTemplates.clear();
        mobMaps.clear();
        File mobDir = new File(getDataFolder(), "mobs");
        File mapDir = new File(getDataFolder(), "maps");
        if (!mobDir.exists()) mobDir.mkdirs();
        if (!mapDir.exists()) mapDir.mkdirs();
        loadMobFiles(mobDir);
        loadMapFiles(mapDir);
    }

    private void loadMobFiles(File dir) {
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            try {
                FileConfiguration c = YamlConfiguration.loadConfiguration(file);
                String id = c.getString("id", stripExt(file.getName())).toLowerCase(Locale.ROOT);
                MobTemplate t = new MobTemplate(id);
                t.type = parseEntityType(c.getString("entity.type", "ZOMBIE"));
                t.name = c.getString("entity.name", "");
                t.health = Math.max(1.0, c.getDouble("entity.health", getConfig().getDouble("settings.default-health", 20.0)));
                t.damage = Math.max(0.0, c.getDouble("entity.damage", getConfig().getDouble("settings.default-damage", 3.0)));
                t.speed = Math.max(0.01, c.getDouble("entity.speed", getConfig().getDouble("settings.default-speed", 0.23)));
                t.followRange = Math.max(1.0, c.getDouble("entity.follow-range", getConfig().getDouble("settings.default-follow-range", 32.0)));
                t.nameVisible = c.getBoolean("entity.name-visible", getConfig().getBoolean("settings.default-name-visible", true));
                ConfigurationSection eq = c.getConfigurationSection("equipment");
                if (eq != null) {
                    for (String slot : Arrays.asList("helmet","chest","legs","boots","hand","offhand")) {
                        String encoded = eq.getString(slot);
                        if (encoded != null && !encoded.isBlank()) t.equipment.put(slot, deserializeItem(encoded));
                    }
                }
                ConfigurationSection drops = c.getConfigurationSection("drops");
                if (drops != null) {
                    for (String key : drops.getKeys(false)) {
                        String encoded = drops.getString(key + ".item");
                        double chance = drops.getDouble(key + ".chance", 100.0);
                        if (encoded != null) t.drops.add(new DropDef(deserializeItem(encoded), Math.max(0, Math.min(100, chance))));
                    }
                }
                mobTemplates.put(id, t);
            } catch (Exception ex) {
                getLogger().warning("Failed to load mob template " + file.getName() + ": " + ex.getMessage());
            }
        }
    }

    private void loadMapFiles(File dir) {
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            try {
                FileConfiguration c = YamlConfiguration.loadConfiguration(file);
                String mapName = c.getString("map", stripExt(file.getName()));
                MobMap map = new MobMap(mapName);
                map.world = c.getString("world", "");
                ConfigurationSection zones = c.getConfigurationSection("zones");
                if (zones != null) {
                    for (String zoneId : zones.getKeys(false)) {
                        ConfigurationSection z = zones.getConfigurationSection(zoneId);
                        if (z == null) continue;
                        Zone zone = new Zone(zoneId);
                        zone.enabled = z.getBoolean("enabled", true);
                        ConfigurationSection zonePool = z.getConfigurationSection("mob-pool");
                        if (zonePool != null) readWeightedPool(zonePool, zone.mobPool);
                        ConfigurationSection points = z.getConfigurationSection("points");
                        if (points != null) {
                            for (String pointId : points.getKeys(false)) {
                                ConfigurationSection p = points.getConfigurationSection(pointId);
                                if (p == null) continue;
                                SpawnPoint sp = readPoint(p, pointId, map.world);
                                if (sp != null) zone.points.put(pointId, sp);
                            }
                        }
                        map.zones.put(zoneId, zone);
                    }
                }
                ConfigurationSection boss = c.getConfigurationSection("boss");
                if (boss != null) {
                    map.boss.enabled = boss.getBoolean("enabled", false);
                    map.boss.intervalSeconds = Math.max(1, parseDurationSeconds(boss.getString("interval", "600s")));
                    map.boss.chance = clamp(boss.getDouble("chance", 5.0), 0, 100);
                    map.boss.maxAlive = Math.max(1, boss.getInt("max-alive", getConfig().getInt("settings.boss-max-alive-per-map", 1)));
                    for (Map<?, ?> raw : boss.getMapList("points")) {
                        Location loc = readMapLocation(raw);
                        if (loc != null) map.boss.points.add(loc);
                    }
                    ConfigurationSection pool = boss.getConfigurationSection("mob-pool");
                    if (pool != null) readWeightedPool(pool, map.boss.mobPool);
                }
                ConfigurationSection tide = c.getConfigurationSection("tide");
                if (tide != null) {
                    map.tide.enabled = tide.getBoolean("enabled", false);
                    map.tide.intervalSeconds = Math.max(1, parseDurationSeconds(tide.getString("interval", "1200s")));
                    map.tide.chance = clamp(tide.getDouble("chance", 20.0), 0, 100);
                    map.tide.minAmount = Math.max(1, tide.getInt("min", 15));
                    map.tide.maxAmount = Math.max(map.tide.minAmount, tide.getInt("max", 30));
                    map.tide.maxPerEvent = Math.max(1, Math.min(getConfig().getInt("settings.tide-max-spawn-per-event", 60), map.tide.maxAmount));
                    for (Map<?, ?> raw : tide.getMapList("points")) {
                        Location loc = readMapLocation(raw);
                        if (loc != null) map.tide.points.add(loc);
                    }
                    ConfigurationSection pool = tide.getConfigurationSection("mob-pool");
                    if (pool != null) readWeightedPool(pool, map.tide.mobPool);
                }
                mobMaps.put(mapName.toLowerCase(Locale.ROOT), map);
            } catch (Exception ex) {
                getLogger().warning("Failed to load STPR-Mobs map " + file.getName() + ": " + ex.getMessage());
            }
        }
    }

    private SpawnPoint readPoint(ConfigurationSection p, String pointId, String defaultWorld) {
        String worldName = p.getString("world", defaultWorld);
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        SpawnPoint sp = new SpawnPoint(pointId);
        sp.location = new Location(world, p.getDouble("x"), p.getDouble("y"), p.getDouble("z"), (float)p.getDouble("yaw"), (float)p.getDouble("pitch"));
        sp.minAmount = Math.max(1, p.getInt("min", 1));
        sp.maxAmount = Math.max(sp.minAmount, p.getInt("max", 1));
        sp.respawnDelaySeconds = Math.max(0, parseDurationSeconds(p.getString("respawn-delay", getConfig().getString("settings.default-respawn-delay", "300s"))));
        return sp;
    }

    private void readWeightedPool(ConfigurationSection section, Map<String, Double> target) {
        target.clear();
        for (String key : section.getKeys(false)) {
            double weight = section.getDouble(key, 0.0);
            if (weight > 0) target.put(key.toLowerCase(Locale.ROOT), weight);
        }
    }

    private void tick() {
        for (Map.Entry<UUID, SpawnRecord> entry : new ArrayList<>(spawned.entrySet())) {
            SpawnRecord r = entry.getValue();
            if (r == null || !liveSessions.containsKey(r.sessionId)) {
                continue;
            }
            Entity e = Bukkit.getEntity(entry.getKey());
            if (e != null && e.isValid() && !e.isDead()) {
                keepSessionChunkLoaded(r.sessionId, e.getLocation().getChunk());
            }
        }


        tickCounter++;

        MatchSnapshot snapshot = STPRBridge.snapshot(stprMatchSessions);
        Set<String> activeSessionIds = snapshot.sessions.keySet();

        // Every SessionState represents one concrete STPR Match instance.
        for (String sessionId : new HashSet<>(liveSessions.keySet())) {
            if (!activeSessionIds.contains(sessionId)) {
                cleanupSession(sessionId);
                liveSessions.remove(sessionId);
            }
        }

        for (Map.Entry<String, String> entry : snapshot.sessions.entrySet()) {
            String sessionId = entry.getKey();
            String mapName = entry.getValue();
            MobMap map = mobMaps.get(mapName.toLowerCase(Locale.ROOT));
            if (map == null) continue;

            if (!liveSessions.containsKey(sessionId)) {
                SessionState session = new SessionState(sessionId, mapName.toLowerCase(Locale.ROOT));
                initializeSession(session, map);
                liveSessions.put(sessionId, session);
            }

            tickMap(map, liveSessions.get(sessionId), sessionId);
        }
    }

    private void initializeSession(SessionState session, MobMap map) {
        long now = System.currentTimeMillis();
        for (Zone zone : map.zones.values()) {
            if (!zone.enabled) continue;
            for (SpawnPoint point : zone.points.values()) {
                session.points.put(point.id, new PointState(now, false));
            }
        }
        session.bossNextEventAt = now + map.boss.intervalSeconds * 1000L;
        session.tideNextEventAt = now + map.tide.intervalSeconds * 1000L;
    }

    private void tickMap(MobMap map, SessionState session, String sessionId) {
        long now = System.currentTimeMillis();
        for (Zone zone : map.zones.values()) {
            if (!zone.enabled || zone.mobPool.isEmpty()) continue;
            for (SpawnPoint point : zone.points.values()) {
                tickPoint(zone, point, session, sessionId, now);
            }
        }
        tickBoss(map, session, sessionId, now);
        tickTide(map, session, sessionId, now);
    }

    private void tickPoint(Zone zone, SpawnPoint point, SessionState session, String sessionId, long now) {
        PointState state = session.points.computeIfAbsent(point.id, k -> new PointState(now, false));
        int live = countLiveAtPoint(point.id, sessionId);
        if (live >= point.maxAmount) return;
        if (zone.mobPool.isEmpty()) return;
        if (now < state.nextRespawnAt) return;

        int missing = point.maxAmount - live;
        int amount = randomBetween(Math.min(point.minAmount, missing), Math.min(point.maxAmount, missing));
        spawnBatch(point.location, zone.mobPool, amount, sessionId, point.id, false);
        state.nextRespawnAt = now + point.respawnDelaySeconds * 1000L;
    }

    private void tickBoss(MobMap map, SessionState session, String sessionId, long now) {
        if (!map.boss.enabled || map.boss.points.isEmpty() || map.boss.mobPool.isEmpty()) return;
        if (now < session.bossNextEventAt) return;
        session.bossNextEventAt = now + map.boss.intervalSeconds * 1000L;
        if (randomPercent() > map.boss.chance) return;
        if (countBosses(sessionId) >= map.boss.maxAlive) return;
        Location loc = map.boss.points.get(ThreadLocalRandom.current().nextInt(map.boss.points.size()));
        spawnBatch(loc, map.boss.mobPool, 1, sessionId, "__BOSS__", true);
    }

    private void tickTide(MobMap map, SessionState session, String sessionId, long now) {
        if (!map.tide.enabled || map.tide.points.isEmpty() || map.tide.mobPool.isEmpty()) return;
        if (now < session.tideNextEventAt) return;
        session.tideNextEventAt = now + map.tide.intervalSeconds * 1000L;
        if (randomPercent() > map.tide.chance) return;
        int amount = Math.min(randomBetween(map.tide.minAmount, map.tide.maxAmount), map.tide.maxPerEvent);
        Location loc = map.tide.points.get(ThreadLocalRandom.current().nextInt(map.tide.points.size()));
        spawnBatch(loc, map.tide.mobPool, amount, sessionId, "__TIDE__", false);
    }

    private String sessionScoreboardTag(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        // 每局使用稳定且短的 Tag，便于 Minecraft Entity Tag 保存。
        return "sm" + Integer.toUnsignedString(sessionId.hashCode(), 36);
    }





    private void rememberSessionChunk(String sessionId, Chunk chunk) {
        if (sessionId == null || sessionId.isBlank() || chunk == null) {
            return;
        }
        long chunkKey = (((long) chunk.getX()) << 32) | (chunk.getZ() & 0xffffffffL);
        sessionChunks.computeIfAbsent(sessionId, k -> new HashSet<>()).add(chunkKey);
        sessionChunkWorlds.computeIfAbsent(sessionId, k -> new HashMap<>())
                .put(chunkKey, chunk.getWorld().getName());
    }

    private void keepSessionChunkLoaded(String sessionId, Chunk chunk) {
        if (sessionId == null || sessionId.isBlank() || chunk == null) {
            return;
        }
        chunk.addPluginChunkTicket(this);
        long key = (((long) chunk.getX()) << 32) | (chunk.getZ() & 0xffffffffL);
        sessionKeepLoadedChunks.computeIfAbsent(sessionId, k -> new HashSet<>()).add(key);
        sessionKeepLoadedWorlds.computeIfAbsent(sessionId, k -> new HashMap<>())
                .put(key, chunk.getWorld().getName());
    }

    private void releaseSessionChunkTickets(String sessionId) {
        Set<Long> keys = sessionKeepLoadedChunks.remove(sessionId);
        Map<Long, String> worlds = sessionKeepLoadedWorlds.remove(sessionId);
        if (keys == null) {
            return;
        }
        for (long key : keys) {
            String worldName = worlds == null ? null : worlds.get(key);
            if (worldName == null) {
                continue;
            }
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                continue;
            }
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            if (world.isChunkLoaded(chunkX, chunkZ)) {
                world.getChunkAt(chunkX, chunkZ).removePluginChunkTicket(this);
            }
        }
    }

    private void addSessionTag(Entity entity, String sessionId) {
        String tag = sessionScoreboardTag(sessionId);
        if (tag != null) entity.addScoreboardTag(tag);
    }                        private int cleanupSessionByTag(String sessionId) {
        String tag = sessionScoreboardTag(sessionId);
        if (tag == null) {
            return 0;
        }

        int removed = 0;
        Set<Long> keys = sessionKeepLoadedChunks.get(sessionId);
        Map<Long, String> worlds = sessionKeepLoadedWorlds.get(sessionId);

        // 本版在对局运行期间持续给本局怪物所在区块保留 Plugin Chunk Ticket，
        // 因此正常情况下这里无需依赖“重新加载未加载实体数据”。
        if (keys != null) {
            for (long key : new HashSet<>(keys)) {
                String worldName = worlds == null ? null : worlds.get(key);
                if (worldName == null) {
                    continue;
                }
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    continue;
                }

                int chunkX = (int) (key >> 32);
                int chunkZ = (int) key;
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    world.loadChunk(chunkX, chunkZ, true);
                }

                Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                for (Entity entity : chunk.getEntities()) {
                    if (entity.getScoreboardTags().contains(tag)) {
                        entity.remove();
                        removed++;
                    }
                }
            }
        }

        // 最终保险：删除当前服务器中仍然加载的、本局 Tag 实体。
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getScoreboardTags().contains(tag)) {
                    entity.remove();
                    removed++;
                }
            }
        }

        releaseSessionChunkTickets(sessionId);
        return removed;
    }





    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        boolean keep = false;

        for (Entity entity : chunk.getEntities()) {
            String sessionId = entity.getPersistentDataContainer().get(sessionKey, PersistentDataType.STRING);
            if (sessionId != null && liveSessions.containsKey(sessionId)) {
                rememberSessionChunk(sessionId, chunk);
                keepSessionChunkLoaded(sessionId, chunk);
                keep = true;
            }
        }

        if (keep) {
            event.getHandlers();
        }
    }


    private void spawnBatch(Location base, Map<String, Double> pool, int amount, String sessionId, String pointId, boolean boss) {
        for (int i = 0; i < amount; i++) {
            String templateId = pickWeighted(pool);
            MobTemplate template = mobTemplates.get(templateId);
            if (template == null) {
                getLogger().warning("Spawn pool references missing mob template: " + templateId);
                continue;
            }
            Location loc = spreadLocation(base, i, amount);
            LivingEntity entity = spawnTemplate(loc, template, sessionId, pointId, boss);
            if (entity != null) {
                spawned.put(entity.getUniqueId(), new SpawnRecord(sessionId, pointId, boss));
            }
        }
    }

    private LivingEntity spawnTemplate(Location location, MobTemplate template, String sessionId, String pointId, boolean boss) {
        if (template.type == null || !template.type.isAlive()) return null;
        Entity entity = location.getWorld().spawnEntity(location, template.type);
        if (!(entity instanceof LivingEntity)) {
            entity.remove();
            return null;
        }
        LivingEntity living = (LivingEntity) entity;
        living.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, template.id);
        living.getPersistentDataContainer().set(sessionKey, PersistentDataType.STRING, sessionId);
        living.getPersistentDataContainer().set(pointKey, PersistentDataType.STRING, pointId);
        addSessionTag(living, sessionId);

        Chunk spawnedChunk = living.getLocation().getChunk();
        rememberSessionChunk(sessionId, spawnedChunk);
        keepSessionChunkLoaded(sessionId, spawnedChunk);
        if (boss) living.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, template.id + ":BOSS");
        applyAttributes(living, template);
        equip(living, template);
        if (template.name != null && !template.name.isBlank()) {
            living.setCustomName(color(template.name));
            living.setCustomNameVisible(template.nameVisible);
        }
        return living;
    }

    private void applyAttributes(LivingEntity entity, MobTemplate t) {
        setAttr(entity, Attribute.GENERIC_MAX_HEALTH, t.health);
        entity.setHealth(Math.min(t.health, entity.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null ? entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue() : t.health));
        setAttr(entity, Attribute.GENERIC_ATTACK_DAMAGE, t.damage);
        setAttr(entity, Attribute.GENERIC_MOVEMENT_SPEED, t.speed);
        setAttr(entity, Attribute.GENERIC_FOLLOW_RANGE, t.followRange);
    }

    private void setAttr(LivingEntity entity, Attribute attr, double value) {
        AttributeInstance ai = entity.getAttribute(attr);
        if (ai != null) ai.setBaseValue(value);
    }

    private void equip(LivingEntity entity, MobTemplate t) {
        if (t.equipment.get("helmet") != null) entity.getEquipment().setHelmet(t.equipment.get("helmet").clone());
        if (t.equipment.get("chest") != null) entity.getEquipment().setChestplate(t.equipment.get("chest").clone());
        if (t.equipment.get("legs") != null) entity.getEquipment().setLeggings(t.equipment.get("legs").clone());
        if (t.equipment.get("boots") != null) entity.getEquipment().setBoots(t.equipment.get("boots").clone());
        if (t.equipment.get("hand") != null) entity.getEquipment().setItemInMainHand(t.equipment.get("hand").clone());
        if (t.equipment.get("offhand") != null) entity.getEquipment().setItemInOffHand(t.equipment.get("offhand").clone());
        // AI enemies should not normally drop the custom equipment unless separately implemented as drops.
        entity.getEquipment().setHelmetDropChance(0f);
        entity.getEquipment().setChestplateDropChance(0f);
        entity.getEquipment().setLeggingsDropChance(0f);
        entity.getEquipment().setBootsDropChance(0f);
        entity.getEquipment().setItemInMainHandDropChance(0f);
        entity.getEquipment().setItemInOffHandDropChance(0f);
    }

    private void handleDrops(EntityDeathEvent event, MobTemplate template) {
        if (template.drops.isEmpty()) return;
        Location loc = event.getEntity().getLocation();
        for (DropDef drop : template.drops) {
            if (randomPercent() <= drop.chance) loc.getWorld().dropItemNaturally(loc, drop.item.clone());
        }
    }

    private int countLiveAtPoint(String pointId, String sessionId) {
        int count = 0;
        for (Map.Entry<UUID, SpawnRecord> entry : new ArrayList<>(spawned.entrySet())) {
            SpawnRecord r = entry.getValue();
            if (!r.sessionId.equals(sessionId) || !r.pointId.equals(pointId)) continue;
            Entity e = Bukkit.getEntity(entry.getKey());
            if (e == null || e.isDead() || !e.isValid()) {
                spawned.remove(entry.getKey());
                continue;
            }
            count++;
        }
        return count;
    }

    private int countBosses(String sessionId) {
        int count = 0;
        for (Map.Entry<UUID, SpawnRecord> entry : new ArrayList<>(spawned.entrySet())) {
            SpawnRecord r = entry.getValue();
            if (!r.sessionId.equals(sessionId) || !r.boss) continue;
            Entity e = Bukkit.getEntity(entry.getKey());
            if (e == null || e.isDead() || !e.isValid()) {
                spawned.remove(entry.getKey());
                continue;
            }
            count++;
        }
        return count;
    }

    private void cleanupSession(String sessionId) {
        int removed = cleanupSessionByTag(sessionId);

        // Clear the in-memory records too, including records whose entities were already invalid.
        for (UUID id : new ArrayList<>(spawned.keySet())) {
            SpawnRecord r = spawned.get(id);
            if (r != null && r.sessionId.equals(sessionId)) {
                spawned.remove(id);
            }
        }

        getLogger().info("Cleaned up STPR-Mobs session " + sessionId + " by entity tag. Removed " + removed + " entities.");
    }

    private void cleanupAllSpawned() {
        for (UUID id : new ArrayList<>(spawned.keySet())) {
            Entity e = Bukkit.getEntity(id);
            if (e != null && e.isValid()) e.remove();
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        SpawnRecord record = spawned.remove(event.getEntity().getUniqueId());
        if (record == null) return;
        String templateId = event.getEntity().getPersistentDataContainer().get(mobKey, PersistentDataType.STRING);
        if (templateId != null && templateId.endsWith(":BOSS")) templateId = templateId.substring(0, templateId.length() - 5);
        MobTemplate template = mobTemplates.get(templateId);
        if (template != null) handleDrops(event, template);
        if (record.pointId.startsWith("__")) return;
        SessionState session = liveSessions.get(record.sessionId);
        if (session == null) return;
        SpawnPoint point = findPoint(record.sessionId, record.pointId);
        if (point != null) {
            long next = System.currentTimeMillis() + point.respawnDelaySeconds * 1000L;
            PointState state = session.points.computeIfAbsent(point.id, k -> new PointState(next, false));
            if (next > state.nextRespawnAt) state.nextRespawnAt = next;
        }
    }

    private SpawnPoint findPoint(String sessionId, String pointId) {
        SessionState session = liveSessions.get(sessionId);
        if (session == null) return null;
        MobMap map = mobMaps.get(session.mapName);
        if (map == null) return null;
        for (Zone zone : map.zones.values()) {
            SpawnPoint p = zone.points.get(pointId);
            if (p != null) return p;
        }
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) return;
        LivingEntity living = (LivingEntity) event.getEntity();
        if (spawned.containsKey(living.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onTransform(EntityTransformEvent event) {
        SpawnRecord record = spawned.remove(event.getEntity().getUniqueId());
        if (record != null) cleanupSessionEntity(event.getEntity().getUniqueId());
    }

    private void cleanupSessionEntity(UUID id) {
        spawned.remove(id);
    }

    @EventHandler
    public void onPortal(EntityPortalEvent event) {
        if (spawned.containsKey(event.getEntity().getUniqueId())) event.setCancelled(true);
    }

    private boolean ensureAdmin(CommandSender sender) {
        if (!sender.hasPermission("stprmobs.admin")) {
            send(sender, "no-permission");
            return false;
        }
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!ensureAdmin(sender)) return true;
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (root) {
                case "create" -> commandCreate(sender, args);
                case "delete" -> commandDelete(sender, args);
                case "list" -> commandList(sender);
                case "set" -> commandSet(sender, args);
                case "equip" -> commandEquip(sender, args);
                case "unequip" -> commandUnequip(sender, args);
                case "drop" -> commandDrop(sender, args);
                case "test" -> commandTest(sender, args);
                case "zone" -> commandZone(sender, args);
                case "point" -> commandPoint(sender, args);
                case "boss" -> commandBoss(sender, args);
                case "tide" -> commandTide(sender, args);
                case "reload" -> { loadAll(); send(sender, "reloaded"); }
                default -> sendHelp(sender);
            }
        } catch (Exception ex) {
            getLogger().warning("Command error: " + ex.getMessage());
            send(sender, "invalid");
        }
        return true;
    }

    private void commandCreate(CommandSender sender, String[] args) throws IOException {
        if (args.length < 2) { send(sender, "usage"); return; }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!validId(id) || mobTemplates.containsKey(id)) { send(sender, mobTemplates.containsKey(id) ? "exists" : "invalid", Map.of("id", id)); return; }
        MobTemplate t = new MobTemplate(id);
        if (args.length >= 3) t.type = parseEntityType(args[2]);
        mobTemplates.put(id, t);
        saveMob(t);
        send(sender, "created", Map.of("id", id));
    }

    private void commandDelete(CommandSender sender, String[] args) {
        if (args.length != 2) { send(sender, "usage"); return; }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (mobTemplates.remove(id) == null) { send(sender, "not-found", Map.of("id", id)); return; }
        File f = new File(getDataFolder(), "mobs/" + id + ".yml");
        if (f.exists() && !f.delete()) getLogger().warning("Could not delete " + f);
        send(sender, "deleted", Map.of("id", id));
    }

    private void commandList(CommandSender sender) {
        sendRaw(sender, "&aMob templates: &f" + String.join("&7, &f", mobTemplates.keySet()));
        sendRaw(sender, "&aConfigured maps: &f" + String.join("&7, &f", mobMaps.keySet()));
    }

    private void commandSet(CommandSender sender, String[] args) throws IOException {
        if (args.length < 4) { send(sender, "usage"); return; }
        MobTemplate t = getTemplate(args[1]);
        if (t == null) return;
        String field = args[2].toLowerCase(Locale.ROOT);
        String value = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        switch (field) {
            case "type" -> t.type = parseEntityType(value);
            case "name" -> t.name = value;
            case "health" -> t.health = Math.max(1, Double.parseDouble(value));
            case "damage" -> t.damage = Math.max(0, Double.parseDouble(value));
            case "speed" -> t.speed = Math.max(0.01, Double.parseDouble(value));
            case "follow-range" -> t.followRange = Math.max(1, Double.parseDouble(value));
            case "name-visible" -> t.nameVisible = Boolean.parseBoolean(value);
            default -> { send(sender, "invalid"); return; }
        }
        saveMob(t);
        send(sender, "set", Map.of("id", t.id, "path", field));
    }

    private void commandEquip(CommandSender sender, String[] args) throws IOException {
        if (!(sender instanceof Player) || args.length != 3) { send(sender, args.length == 3 ? "player-only" : "usage"); return; }
        Player player = (Player) sender;
        MobTemplate t = getTemplate(args[1]);
        if (t == null) return;
        String slot = normalizeSlot(args[2]);
        if (slot == null) { send(sender, "invalid"); return; }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) { send(sender, "equip-empty"); return; }
        t.equipment.put(slot, item.clone());
        saveMob(t);
        send(sender, "equip", Map.of("id", t.id, "slot", slot));
    }

    private void commandUnequip(CommandSender sender, String[] args) throws IOException {
        if (args.length != 3) { send(sender, "usage"); return; }
        MobTemplate t = getTemplate(args[1]);
        if (t == null) return;
        String slot = normalizeSlot(args[2]);
        if (slot == null) { send(sender, "invalid"); return; }
        t.equipment.remove(slot);
        saveMob(t);
        send(sender, "unequip", Map.of("id", t.id, "slot", slot));
    }

    private void commandDrop(CommandSender sender, String[] args) throws IOException {
        if (!(sender instanceof Player)) { send(sender, "player-only"); return; }
        Player player = (Player) sender;
        if (args.length < 2) { send(sender, "usage"); return; }
        MobTemplate t = getTemplate(args[1]);
        if (t == null) return;
        String action = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "add";
        if (action.equals("clear")) {
            t.drops.clear(); saveMob(t); send(sender, "drop-cleared", Map.of("id", t.id)); return;
        }
        double chance = args.length >= 4 ? Double.parseDouble(args[3]) : 100.0;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) { send(sender, "equip-empty"); return; }
        t.drops.add(new DropDef(item.clone(), clamp(chance, 0, 100)));
        saveMob(t);
        send(sender, "drop-added", Map.of("id", t.id, "chance", trim(chance)));
    }

    private void commandTest(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) { send(sender, "player-only"); return; }
        Player player = (Player) sender;
        if (args.length < 2 || args.length > 3) { send(sender, "usage"); return; }
        MobTemplate t = getTemplate(args[1]);
        if (t == null) return;
        int amount = args.length == 3 ? Math.max(1, Integer.parseInt(args[2])) : 1;
        for (int i = 0; i < amount; i++) spawnTemplate(spreadLocation(player.getLocation(), i, amount), t, "__TEST__", "__TEST__", false);
        send(sender, "test-spawned", Map.of("id", t.id, "amount", String.valueOf(amount)));
    }

    private void commandZone(CommandSender sender, String[] args) throws IOException {
        if (args.length < 2) { send(sender, "usage"); return; }
        String action = args[1].toLowerCase(Locale.ROOT);

        if (action.equals("create") || action.equals("delete")) {
            if (args.length != 4) { send(sender, "usage"); return; }
            String map = args[2].toLowerCase(Locale.ROOT), zone = args[3].toLowerCase(Locale.ROOT);
            MobMap mm = mobMaps.computeIfAbsent(map, MobMap::new);
            if (action.equals("create")) {
                if (mm.zones.containsKey(zone)) { sendRaw(sender, "&cZone already exists: &e" + zone); return; }
                mm.zones.put(zone, new Zone(zone));
                saveMap(mm); send(sender, "zone-created", Map.of("map", map, "zone", zone));
            } else {
                if (mm.zones.remove(zone) == null) { sendRaw(sender, "&cZone not found: &e" + zone); return; }
                saveMap(mm); send(sender, "zone-deleted", Map.of("map", map, "zone", zone));
            }
            return;
        }

        if (action.equals("mob")) {
            if (args.length != 6) { send(sender, "usage"); return; }
            MobMap map = mobMaps.get(args[2].toLowerCase(Locale.ROOT));
            if (map == null) { send(sender, "invalid"); return; }
            Zone zone = map.zones.get(args[3].toLowerCase(Locale.ROOT));
            if (zone == null) { send(sender, "invalid"); return; }
            String id = args[4].toLowerCase(Locale.ROOT);
            if (!mobTemplates.containsKey(id)) { send(sender, "not-found", Map.of("id", id)); return; }
            double weight = Double.parseDouble(args[5]);
            if (weight <= 0) { send(sender, "invalid"); return; }
            zone.mobPool.put(id, weight);
            saveMap(map);
            send(sender, "zone-mob-added", Map.of("zone", zone.id, "mob", id, "weight", trim(weight)));
            return;
        }

        if (action.equals("clearmob")) {
            if (args.length != 4) { send(sender, "usage"); return; }
            MobMap map = mobMaps.get(args[2].toLowerCase(Locale.ROOT));
            if (map == null) { send(sender, "invalid"); return; }
            Zone zone = map.zones.get(args[3].toLowerCase(Locale.ROOT));
            if (zone == null) { send(sender, "invalid"); return; }
            zone.mobPool.clear();
            saveMap(map);
            send(sender, "cleared", Map.of("what", "mob pool for zone " + zone.id));
            return;
        }

        send(sender, "usage");
    }

    private void commandPoint(CommandSender sender, String[] args) throws IOException {
        if (!(sender instanceof Player)) { send(sender, "player-only"); return; }
        Player player = (Player) sender;
        if (args.length < 2) { send(sender, "usage"); return; }
        String action = args[1].toLowerCase(Locale.ROOT);

        if (action.equals("add")) {
            if (args.length < 5 || args.length > 7) { send(sender, "usage"); return; }

            String mapName = args[2].toLowerCase(Locale.ROOT);
            String zoneId = args[3].toLowerCase(Locale.ROOT);
            int minAmount = Math.max(1, Integer.parseInt(args[4]));
            int maxAmount = minAmount;
            String delayArg = getConfig().getString("settings.default-respawn-delay", "300s");

            // /mob point add <map> <zone> <amount>
            // /mob point add <map> <zone> <min> <max>
            // /mob point add <map> <zone> <min> <max> <delay>
            if (args.length >= 6) maxAmount = Math.max(minAmount, Integer.parseInt(args[5]));
            if (args.length == 7) delayArg = args[6];

            MobMap map = mobMaps.computeIfAbsent(mapName, MobMap::new);
            map.world = player.getWorld().getName();
            Zone zone = map.zones.computeIfAbsent(zoneId, Zone::new);

            int index = nextPointIndex(zone);
            String pointId = String.valueOf(index);

            SpawnPoint p = new SpawnPoint(pointId);
            p.location = player.getLocation().clone();
            p.minAmount = minAmount;
            p.maxAmount = maxAmount;
            p.respawnDelaySeconds = Math.max(0, parseDurationSeconds(delayArg));

            zone.points.put(pointId, p);
            saveMap(map);

            send(sender, "point-added", Map.of(
                    "map", mapName,
                    "zone", zoneId,
                    "point", pointId
            ));
            return;
        }

        if (action.equals("delete") && args.length == 5) {
            MobMap map = mobMaps.get(args[2].toLowerCase(Locale.ROOT));
            if (map == null) { send(sender, "invalid"); return; }
            Zone zone = map.zones.get(args[3].toLowerCase(Locale.ROOT));
            String pointId = args[4].toLowerCase(Locale.ROOT);
            if (zone == null || zone.points.remove(pointId) == null) { send(sender, "invalid"); return; }
            saveMap(map);
            send(sender, "point-deleted", Map.of("point", pointId));
            return;
        }

        if (action.equals("clear") && args.length == 4) {
            MobMap map = mobMaps.get(args[2].toLowerCase(Locale.ROOT));
            if (map == null) { send(sender, "invalid"); return; }
            Zone zone = map.zones.get(args[3].toLowerCase(Locale.ROOT));
            if (zone == null) { send(sender, "invalid"); return; }
            zone.points.clear();
            saveMap(map);
            send(sender, "cleared", Map.of("what", "all spawn points in " + zone.id));
            return;
        }

        send(sender, "usage");
    }

    private static int nextPointIndex(Zone zone) {
        int i = 0;
        while (zone.points.containsKey(String.valueOf(i))) i++;
        return i;
    }

    private static boolean looksLikeDuration(String value) {
        if (value == null || value.isBlank()) return false;
        String s = value.trim().toLowerCase(Locale.ROOT);
        return s.endsWith("s") || s.endsWith("m") || s.endsWith("h") || s.endsWith("ms");
    }

    private void commandBoss(CommandSender sender, String[] args) throws IOException {
        if (!(sender instanceof Player)) { send(sender, "player-only"); return; }
        Player player = (Player) sender;
        if (args.length < 3) { send(sender, "usage"); return; }
        String action = args[1].toLowerCase(Locale.ROOT);
        String mapName = args[2].toLowerCase(Locale.ROOT);
        MobMap map = mobMaps.computeIfAbsent(mapName, MobMap::new);
        map.world = player.getWorld().getName();
        switch (action) {
            case "enable" -> { map.boss.enabled = Boolean.parseBoolean(args[3]); }
            case "interval" -> { map.boss.intervalSeconds = Math.max(1, parseDurationSeconds(args[3])); }
            case "chance" -> { map.boss.chance = clamp(Double.parseDouble(args[3]), 0, 100); }
            case "max" -> { map.boss.maxAlive = Math.max(1, Integer.parseInt(args[3])); }
            case "point" -> { if (args.length < 4) { send(sender, "usage"); return; } map.boss.points.add(player.getLocation().clone()); }
            case "clearponts", "clearpoints" -> map.boss.points.clear();
            case "mob" -> { if (args.length < 5) { send(sender, "usage"); return; } String id = args[3].toLowerCase(Locale.ROOT); if (!mobTemplates.containsKey(id)) { send(sender, "not-found", Map.of("id", id)); return; } map.boss.mobPool.put(id, Double.parseDouble(args[4])); }
            case "clearmob" -> map.boss.mobPool.clear();
            default -> { send(sender, "usage"); return; }
        }
        saveMap(map); send(sender, "map-saved", Map.of("map", mapName));
    }

    private void commandTide(CommandSender sender, String[] args) throws IOException {
        if (!(sender instanceof Player)) { send(sender, "player-only"); return; }
        Player player = (Player) sender;
        if (args.length < 3) { send(sender, "usage"); return; }
        String action = args[1].toLowerCase(Locale.ROOT);
        String mapName = args[2].toLowerCase(Locale.ROOT);
        MobMap map = mobMaps.computeIfAbsent(mapName, MobMap::new);
        map.world = player.getWorld().getName();
        switch (action) {
            case "enable" -> map.tide.enabled = Boolean.parseBoolean(args[3]);
            case "interval" -> map.tide.intervalSeconds = Math.max(1, parseDurationSeconds(args[3]));
            case "chance" -> map.tide.chance = clamp(Double.parseDouble(args[3]), 0, 100);
            case "amount" -> { if (args.length < 5) { send(sender, "usage"); return; } map.tide.minAmount = Math.max(1, Integer.parseInt(args[3])); map.tide.maxAmount = Math.max(map.tide.minAmount, Integer.parseInt(args[4])); map.tide.maxPerEvent = Math.min(getConfig().getInt("settings.tide-max-spawn-per-event", 60), map.tide.maxAmount); }
            case "point" -> map.tide.points.add(player.getLocation().clone());
            case "clearpoints", "clearponts" -> map.tide.points.clear();
            case "mob" -> { if (args.length < 5) { send(sender, "usage"); return; } String id = args[3].toLowerCase(Locale.ROOT); if (!mobTemplates.containsKey(id)) { send(sender, "not-found", Map.of("id", id)); return; } map.tide.mobPool.put(id, Double.parseDouble(args[4])); }
            case "clearmob" -> map.tide.mobPool.clear();
            default -> { send(sender, "usage"); return; }
        }
        saveMap(map); send(sender, "map-saved", Map.of("map", mapName));
    }

    private MobTemplate getTemplate(String id) {
        MobTemplate t = mobTemplates.get(id.toLowerCase(Locale.ROOT));
        if (t == null) getLogger().info("Template not found: " + id);
        return t;
    }

    private void saveMob(MobTemplate t) throws IOException {
        File dir = new File(getDataFolder(), "mobs");
        if (!dir.exists()) dir.mkdirs();
        YamlConfiguration c = new YamlConfiguration();
        c.set("id", t.id);
        c.set("entity.type", t.type == null ? "ZOMBIE" : t.type.name());
        c.set("entity.name", t.name);
        c.set("entity.health", t.health);
        c.set("entity.damage", t.damage);
        c.set("entity.speed", t.speed);
        c.set("entity.follow-range", t.followRange);
        c.set("entity.name-visible", t.nameVisible);
        for (String slot : Arrays.asList("helmet","chest","legs","boots","hand","offhand")) {
            ItemStack item = t.equipment.get(slot);
            c.set("equipment." + slot, item == null ? null : serializeItem(item));
        }
        for (int i = 0; i < t.drops.size(); i++) {
            DropDef d = t.drops.get(i);
            c.set("drops." + i + ".item", serializeItem(d.item));
            c.set("drops." + i + ".chance", d.chance);
        }
        c.save(new File(dir, t.id + ".yml"));
    }

    private void saveMap(MobMap map) throws IOException {
        File dir = new File(getDataFolder(), "maps");
        if (!dir.exists()) dir.mkdirs();
        YamlConfiguration c = new YamlConfiguration();
        c.set("map", map.name);
        c.set("world", map.world == null ? currentWorldName() : map.world);
        for (Zone zone : map.zones.values()) {
            c.set("zones." + zone.id + ".enabled", zone.enabled);
            for (Map.Entry<String, Double> e : zone.mobPool.entrySet()) {
                c.set("zones." + zone.id + ".mob-pool." + e.getKey(), e.getValue());
            }
            for (SpawnPoint p : zone.points.values()) {
                String path = "zones." + zone.id + ".points." + p.id;
                writeLocation(c, path, p.location);
                c.set(path + ".min", p.minAmount);
                c.set(path + ".max", p.maxAmount);
                c.set(path + ".respawn-delay", p.respawnDelaySeconds + "s");
            }
        }
        c.set("boss.enabled", map.boss.enabled);
        c.set("boss.interval", map.boss.intervalSeconds + "s");
        c.set("boss.chance", map.boss.chance);
        c.set("boss.max-alive", map.boss.maxAlive);
        List<Map<String,Object>> bossPoints = new ArrayList<>();
        for (Location loc : map.boss.points) bossPoints.add(toMap(loc));
        c.set("boss.points", bossPoints);
        for (Map.Entry<String, Double> e : map.boss.mobPool.entrySet()) c.set("boss.mob-pool." + e.getKey(), e.getValue());

        c.set("tide.enabled", map.tide.enabled);
        c.set("tide.interval", map.tide.intervalSeconds + "s");
        c.set("tide.chance", map.tide.chance);
        c.set("tide.min", map.tide.minAmount);
        c.set("tide.max", map.tide.maxAmount);
        List<Map<String,Object>> tidePoints = new ArrayList<>();
        for (Location loc : map.tide.points) tidePoints.add(toMap(loc));
        c.set("tide.points", tidePoints);
        for (Map.Entry<String, Double> e : map.tide.mobPool.entrySet()) c.set("tide.mob-pool." + e.getKey(), e.getValue());
        c.save(new File(dir, map.name + ".yml"));
    }

    private void writeLocation(YamlConfiguration c, String path, Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        c.set(path + ".world", loc.getWorld().getName());
        c.set(path + ".x", loc.getX());
        c.set(path + ".y", loc.getY());
        c.set(path + ".z", loc.getZ());
        c.set(path + ".yaw", loc.getYaw());
        c.set(path + ".pitch", loc.getPitch());
    }

    private static Map<String,Object> toMap(Location loc) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("world", loc.getWorld().getName()); m.put("x", loc.getX()); m.put("y", loc.getY()); m.put("z", loc.getZ());
        m.put("yaw", loc.getYaw()); m.put("pitch", loc.getPitch()); return m;
    }

    private Location readMapLocation(Map<?, ?> raw) {
        String worldName = raw.get("world") == null ? null : String.valueOf(raw.get("world"));
        if (worldName == null) return null;
        World world = Bukkit.getWorld(worldName); if (world == null) return null;
        return new Location(world, number(raw.get("x")), number(raw.get("y")), number(raw.get("z")), (float)number(raw.get("yaw")), (float)number(raw.get("pitch")));
    }

    private String currentWorldName() { return Bukkit.getWorlds().isEmpty() ? "" : Bukkit.getWorlds().get(0).getName(); }

    private static double number(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }
    private static String stripExt(String n) { return n.endsWith(".yml") ? n.substring(0,n.length()-4) : n; }
    private static boolean validId(String id) { return id.matches("[a-z0-9_-]{1,32}"); }
    private static double clamp(double x,double a,double b){ return Math.max(a,Math.min(b,x)); }
    private static String trim(double x){ return x == Math.rint(x) ? String.valueOf((long)x) : String.format(Locale.US,"%.2f",x); }
    private static double randomPercent(){ return ThreadLocalRandom.current().nextDouble(0,100); }
    private static int randomBetween(int min,int max){ return min>=max?min:ThreadLocalRandom.current().nextInt(min,max+1); }

    private static EntityType parseEntityType(String raw) {
        try { return EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT)); } catch (Exception e) { return EntityType.ZOMBIE; }
    }

    private static String pickWeighted(Map<String,Double> pool) {
        double total = pool.values().stream().filter(v->v>0).mapToDouble(Double::doubleValue).sum();
        if (total <= 0) return null;
        double r = ThreadLocalRandom.current().nextDouble(total);
        for (Map.Entry<String,Double> e : pool.entrySet()) { r -= Math.max(0,e.getValue()); if (r <= 0) return e.getKey(); }
        return pool.keySet().iterator().next();
    }

    private static Location spreadLocation(Location base, int index, int amount) {
        double radius = amount <= 1 ? 0 : Math.min(3.0, 0.5 + Math.sqrt(amount) * 0.35);
        double angle = (Math.PI * 2 * index / Math.max(1, amount)) + ThreadLocalRandom.current().nextDouble(-0.35,0.35);
        double dx = Math.cos(angle) * radius * ThreadLocalRandom.current().nextDouble(0.25,1.0);
        double dz = Math.sin(angle) * radius * ThreadLocalRandom.current().nextDouble(0.25,1.0);
        return base.clone().add(dx,0,dz);
    }

    private static String normalizeSlot(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "helmet","head","hat" -> "helmet";
            case "chest","chestplate","body" -> "chest";
            case "legs","leggings" -> "legs";
            case "boots","feet" -> "boots";
            case "hand","mainhand","main-hand" -> "hand";
            case "offhand","off-hand" -> "offhand";
            default -> null;
        };
    }

    private static long parseDurationSeconds(String text) {
        if (text == null || text.isBlank()) return 0;
        String s = text.trim().toLowerCase(Locale.ROOT);
        try {
            if (s.endsWith("ms")) return Math.max(0, Long.parseLong(s.substring(0, s.length()-2)) / 1000L);
            if (s.endsWith("s")) return Math.max(0, Long.parseLong(s.substring(0, s.length()-1)));
            if (s.endsWith("m")) return Math.max(0, Long.parseLong(s.substring(0, s.length()-1)) * 60L);
            if (s.endsWith("h")) return Math.max(0, Long.parseLong(s.substring(0, s.length()-1)) * 3600L);
            return Math.max(0, Long.parseLong(s));
        } catch (Exception e) { return 0; }
    }

    private static String serializeItem(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    private static ItemStack deserializeItem(String encoded) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
    }

    private void sendHelp(CommandSender sender) {
        String[] lines = {
                "&6--- STPR-Mobs 1.1.0 ---",
                "&e/mob create <id> [ENTITY_TYPE] &7Create a template (no mob spawned)",
                "&e/mob set <id> type|name|health|damage|speed|follow-range <value>",
                "&e/mob equip <id> helmet|chest|legs|boots|hand|offhand &7Read item in your main hand",
                "&e/mob unequip <id> <slot>",
                "&e/mob drop <id> add [chance] &7Read held item; /mob drop <id> clear",
                "&e/mob test <id> [amount] &7Temporary test spawn",
                "&e/mob zone create <map> <zone>",
                "&e/mob point add <map> <zone> <min> [max] [delay] &7Point ID auto: 0,1,2...",
                "&e/mob point delete <map> <zone> <pointId>",
                "&e/mob point clear <map> <zone>",
                "&e/mob zone mob <map> <zone> <mobId> <weight> &7Set ONE mob pool for the whole zone",
                "&e/mob zone clearmob <map> <zone> &7Clear the zone mob pool",
                "&e/mob boss enable|interval|chance|max <map> <value>",
                "&e/mob boss point <map> &7Add current position",
                "&e/mob boss mob <map> <mobId> <weight>",
                "&e/mob tide enable|interval|chance <map> <value>",
                "&e/mob tide amount <map> <min> <max>",
                "&e/mob tide point <map> &7Add current position",
                "&e/mob tide mob <map> <mobId> <weight>",
                "&e/mob reload"
        };
        for (String l : lines) sendRaw(sender, l);
    }

    private void send(CommandSender sender, String key) { send(sender,key,Collections.emptyMap()); }
    private void send(CommandSender sender, String key, Map<String,String> vars) {
        String s = getConfig().getString("messages."+key, key);
        for (var e:vars.entrySet()) s = s.replace("%"+e.getKey()+"%", e.getValue());
        sendRaw(sender,s);
    }
    private void sendRaw(CommandSender sender, String s) { sender.sendMessage(color(getConfig().getString("messages.prefix","[STPR-Mobs] ")+s)); }
    private static String color(String s){return ChatColor.translateAlternateColorCodes('&',s==null?"":s);}

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("stprmobs.admin")) return Collections.emptyList();
        if (args.length == 1) {
            return partial(args[0], Arrays.asList(
                    "create", "delete", "list", "set", "equip", "unequip", "drop", "test",
                    "zone", "point", "boss", "tide", "reload", "help"
            ));
        }

        String root = args[0].toLowerCase(Locale.ROOT);

        if (root.equals("create") && args.length == 3) {
            return partial(args[2], Arrays.asList(
                    "ZOMBIE", "HUSK", "SKELETON", "STRAY", "PIGLIN", "PIGLIN_BRUTE",
                    "VINDICATOR", "EVOKER", "DROWNED", "WITHER_SKELETON"
            ));
        }
        if ((root.equals("delete") || root.equals("set") || root.equals("equip") || root.equals("test") || root.equals("unequip") || root.equals("drop"))
                && args.length == 2) {
            return partial(args[1], mobTemplates.keySet());
        }
        if (root.equals("set") && args.length == 3) {
            return partial(args[2], Arrays.asList("type", "name", "health", "damage", "speed", "follow-range", "name-visible"));
        }
        if ((root.equals("equip") || root.equals("unequip")) && args.length == 3) {
            return partial(args[2], Arrays.asList("helmet", "chest", "legs", "boots", "hand", "offhand"));
        }
        if (root.equals("drop") && args.length == 3) {
            return partial(args[2], Arrays.asList("add", "clear"));
        }
        if (root.equals("zone") && args.length == 2) {
            return partial(args[1], Arrays.asList("create", "delete", "mob", "clearmob"));
        }
        if (root.equals("zone") && args.length == 3) {
            if (args[1].equalsIgnoreCase("create") || args[1].equalsIgnoreCase("delete") || args[1].equalsIgnoreCase("mob") || args[1].equalsIgnoreCase("clearmob")) {
                return partial(args[2], mobMaps.keySet());
            }
        }
        if (root.equals("zone") && args.length == 4) {
            if (args[1].equalsIgnoreCase("delete")) {
                MobMap map = mobMaps.get(args[2].toLowerCase(Locale.ROOT));
                if (map != null) return partial(args[3], map.zones.keySet());
            }
            if (args[1].equalsIgnoreCase("create") || args[1].equalsIgnoreCase("mob") || args[1].equalsIgnoreCase("clearmob")) {
                MobMap map = mobMaps.get(args[2].toLowerCase(Locale.ROOT));
                if (map != null) return partial(args[3], map.zones.keySet());
            }
        }
        if (root.equals("zone") && args.length == 5 && args[1].equalsIgnoreCase("mob")) {
            return partial(args[4], mobTemplates.keySet());
        }

        if (root.equals("point") && args.length == 2) {
            return partial(args[1], Arrays.asList("add", "delete", "clear"));
        }
        if (root.equals("point") && args.length == 3 && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("delete") || args[1].equalsIgnoreCase("clear"))) {
            return partial(args[2], mobMaps.keySet());
        }
        if (root.equals("point") && args.length == 4 && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("delete") || args[1].equalsIgnoreCase("clear"))) {
            MobMap map = mobMaps.get(args[2].toLowerCase(Locale.ROOT));
            if (map != null) return partial(args[3], map.zones.keySet());
        }
        if (root.equals("point") && args.length == 5 && args[1].equalsIgnoreCase("delete")) {
            MobMap map = mobMaps.get(args[2].toLowerCase(Locale.ROOT));
            if (map != null) {
                Zone zone = map.zones.get(args[3].toLowerCase(Locale.ROOT));
                if (zone != null) return partial(args[4], zone.points.keySet());
            }
        }

        if ((root.equals("boss") || root.equals("tide")) && args.length == 2) {
            if (root.equals("boss")) {
                return partial(args[1], Arrays.asList("enable", "interval", "chance", "max", "point", "clearpoints", "mob", "clearmob"));
            }
            return partial(args[1], Arrays.asList("enable", "interval", "chance", "amount", "point", "clearpoints", "mob", "clearmob"));
        }
        if ((root.equals("boss") || root.equals("tide")) && args.length == 3) {
            return partial(args[2], mobMaps.keySet());
        }
        if (root.equals("boss") && args.length == 4 && args[1].equalsIgnoreCase("mob")) return partial(args[3], mobTemplates.keySet());
        if (root.equals("tide") && args.length == 4 && args[1].equalsIgnoreCase("mob")) return partial(args[3], mobTemplates.keySet());

        return Collections.emptyList();
    }

    private static List<String> partial(String prefix, Collection<String> vals) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(); for(String v:vals) if(v.toLowerCase(Locale.ROOT).startsWith(p)) out.add(v); return out;
    }

    private static final class MobTemplate {
        final String id; EntityType type = EntityType.ZOMBIE; String name = ""; double health=20,damage=3,speed=.23,followRange=32; boolean nameVisible=true;
        final Map<String,ItemStack> equipment=new HashMap<>(); final List<DropDef> drops=new ArrayList<>();
        MobTemplate(String id){this.id=id;}
    }
    private static final class DropDef { final ItemStack item; final double chance; DropDef(ItemStack i,double c){item=i;chance=c;} }
    private static final class MobMap {
        final String name; String world=""; final Map<String,Zone> zones=new LinkedHashMap<>(); final BossConfig boss=new BossConfig(); final TideConfig tide=new TideConfig();
        MobMap(String n){name=n;}
    }
    private static final class Zone {
        final String id;
        boolean enabled = true;
        final Map<String, SpawnPoint> points = new LinkedHashMap<>();
        final Map<String, Double> mobPool = new LinkedHashMap<>();
        Zone(String i) { id = i; }
    }

    private static final class SpawnPoint {
        final String id;
        Location location;
        int minAmount = 1, maxAmount = 1;
        long respawnDelaySeconds = 300;
        SpawnPoint(String i) { id = i; }
    }

    private static final class PointState {
        long nextRespawnAt;
        boolean initialized;
        PointState(long nextRespawnAt, boolean initialized) {
            this.nextRespawnAt = nextRespawnAt;
            this.initialized = initialized;
        }
    }

    private static final class SessionState {
        final String sessionId;
        final String mapName;
        final Map<String, PointState> points = new HashMap<>();
        long bossNextEventAt;
        long tideNextEventAt;
        SessionState(String sessionId, String mapName) {
            this.sessionId = sessionId;
            this.mapName = mapName;
        }
    }
    private static final class BossConfig { boolean enabled=false; long intervalSeconds=600,nextEventAt=0; double chance=5; int maxAlive=1; final List<Location> points=new ArrayList<>(); final Map<String,Double> mobPool=new LinkedHashMap<>(); }
    private static final class TideConfig { boolean enabled=false; long intervalSeconds=1200,nextEventAt=0; double chance=20; int minAmount=15,maxAmount=30,maxPerEvent=60; final List<Location> points=new ArrayList<>(); final Map<String,Double> mobPool=new LinkedHashMap<>(); }
    private static final class SpawnRecord { final String sessionId; final String pointId; final boolean boss; SpawnRecord(String s,String p,boolean b){sessionId=s;pointId=p;boss=b;} }

    private static final class STPRBridge {
        static MatchSnapshot snapshot(IdentityHashMap<Object, String> knownSessions) {
            Map<String, String> activeSessions = new LinkedHashMap<>();
            var stpr = Bukkit.getPluginManager().getPlugin("STPR");
            if (stpr == null || !stpr.isEnabled()) return new MatchSnapshot(activeSessions);

            try {
                Field matchesField = findField(stpr.getClass(), "matches");
                matchesField.setAccessible(true);
                Object matchesObj = matchesField.get(stpr);
                if (!(matchesObj instanceof Iterable<?>)) return new MatchSnapshot(activeSessions);
                Iterable<?> matches = (Iterable<?>) matchesObj;

                Set<Object> aliveMatchObjects = Collections.newSetFromMap(new IdentityHashMap<>());

                for (Object match : matches) {
                    if (match == null) continue;
                    aliveMatchObjects.add(match);

                    Field stateField = findField(match.getClass(), "state");
                    stateField.setAccessible(true);
                    Object state = stateField.get(match);
                    if (state == null || !"ACTIVE".equalsIgnoreCase(state.toString())) continue;

                    Field mapField = findField(match.getClass(), "map");
                    mapField.setAccessible(true);
                    Object map = mapField.get(match);
                    if (map == null) continue;

                    Field nameField = findField(map.getClass(), "name");
                    nameField.setAccessible(true);
                    String mapName = String.valueOf(nameField.get(map)).toLowerCase(Locale.ROOT);

                    String sessionId = knownSessions.get(match);
                    if (sessionId == null) {
                        sessionId = "match-" + UUID.randomUUID();
                        knownSessions.put(match, sessionId);
                    }
                    activeSessions.put(sessionId, mapName);
                }

                // Prevent the identity map from retaining ended Match objects forever.
                knownSessions.keySet().removeIf(match -> !aliveMatchObjects.contains(match));
            } catch (Exception ignored) {
                // Treat STPR as having no active sessions this tick.
            }

            return new MatchSnapshot(activeSessions);
        }

        static Field findField(Class<?> type, String name) throws NoSuchFieldException {
            Class<?> c = type;
            while (c != null) {
                try {
                    return c.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            throw new NoSuchFieldException(name);
        }
    }
    private static final class MatchSnapshot {
        final Map<String, String> sessions;
        MatchSnapshot(Map<String, String> sessions) {
            this.sessions = sessions;
        }
    }
}
