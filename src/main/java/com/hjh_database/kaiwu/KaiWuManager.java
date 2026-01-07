package com.hjh_database.kaiwu;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class KaiWuManager {
    private final Hjh_database plugin;
    private final File nodesFile;
    private final File configFile;
    private YamlConfiguration nodesConfig;
    private YamlConfiguration config;

    // --- 核心缓存 ---
    private final Map<String, NodeConfig> nodeCache = new ConcurrentHashMap<>();
    private final Map<String, List<NodeConfig>> chunkNodeMap = new ConcurrentHashMap<>();

    // 运行时数据
    private final Map<UUID, Integer> miningTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> miningBars = new ConcurrentHashMap<>();
    private final Map<UUID, Location> miningStartLoc = new ConcurrentHashMap<>();
    public final Map<UUID, String> deleteConfirmations = new ConcurrentHashMap<>();

    // 状态后缀
    private static final String SUFFIX_DEPLETED = "_depleted";   // 枯竭状态
    private static final String SUFFIX_RECOVERING = "_recover";  // 恢复状态

    // 粒子颜色缓存
    private Particle.DustOptions dustDepleted;
    private Particle.DustOptions dustRecovering;

    public KaiWuManager(Hjh_database plugin) {
        this.plugin = plugin;
        this.nodesFile = new File(plugin.getDataFolder(), "nodes.yml");
        this.configFile = new File(plugin.getDataFolder(), "kaiwu.yml");
        loadConfig();
        loadNodes();
        startRegenTask();
        startVisualTask(); // 启动粒子线程
    }

    public void loadConfig() {
        if (!configFile.exists()) {
            plugin.saveResource("kaiwu.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(configFile);
        dustDepleted = parseColor(config.getString("visual.colors.depleted", "255,170,0"), 1.2f);
        dustRecovering = parseColor(config.getString("visual.colors.recovering", "128,128,128"), 1.0f);
    }

    private Particle.DustOptions parseColor(String rgb, float size) {
        try {
            String[] parts = rgb.split(",");
            return new Particle.DustOptions(Color.fromRGB(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim())
            ), size);
        } catch (Exception e) {
            return new Particle.DustOptions(Color.GRAY, size);
        }
    }

    public void loadNodes() {
        if (!nodesFile.exists()) {
            try { nodesFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }
        nodesConfig = YamlConfiguration.loadConfiguration(nodesFile);

        nodeCache.clear();
        chunkNodeMap.clear();

        for (String key : nodesConfig.getKeys(false)) {
            ConfigurationSection sec = nodesConfig.getConfigurationSection(key);
            if (sec == null) continue;

            NodeConfig node = new NodeConfig();
            List<?> list = sec.getList("drops");
            node.drops = new ArrayList<>();
            if (list != null) {
                for (Object o : list) {
                    if (o instanceof ItemStack) node.drops.add((ItemStack) o);
                }
            }
            node.timeSeconds = sec.getDouble("time", 2.0);
            node.energyCost = sec.getDouble("energy", 5.0);
            node.exp = sec.getInt("exp", 10);
            node.reqLevel = sec.getInt("req_level", 1);
            node.cooldownSec = sec.getInt("cooldown", 60);

            // 【新增】读取独立的枯竭恢复时间，如果没有则读取全局配置作为默认值
            int globalDepleted = config.getInt("mining.depleted_duration", 300);
            node.depletedSec = sec.getInt("depleted", globalDepleted);

            String[] parts = key.split(",");
            if (parts.length == 4) {
                try {
                    node.worldName = parts[0];
                    node.x = Double.parseDouble(parts[1]);
                    node.y = Double.parseDouble(parts[2]);
                    node.z = Double.parseDouble(parts[3]);
                    node.cachedLoc = new Location(Bukkit.getWorld(node.worldName), node.x, node.y, node.z);
                    nodeCache.put(key, node);

                    int chunkX = ((int) node.x) >> 4;
                    int chunkZ = ((int) node.z) >> 4;
                    String chunkKey = node.worldName + "," + chunkX + "," + chunkZ;
                    chunkNodeMap.computeIfAbsent(chunkKey, k -> Collections.synchronizedList(new ArrayList<>())).add(node);
                } catch (Exception e) {
                    plugin.getLogger().warning("资源点坐标解析失败: " + key);
                }
            }
        }
        plugin.getLogger().info("已加载 " + nodeCache.size() + " 个开物资源点。");
    }

    // ==========================================
    //           开采逻辑
    // ==========================================

    public void startMining(Player player, Location loc) {
        String locKey = serializeLoc(loc);
        NodeConfig node = nodeCache.get(locKey);
        if (node == null) return;
        if (miningTasks.containsKey(player.getUniqueId())) return;

        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data == null) return;

        if (data.getKaiWuLevel() < node.reqLevel) {
            player.sendMessage("§c等级不足！需要 Lv." + node.reqLevel);
            return;
        }

        long now = System.currentTimeMillis();
        Map<String, Long> cds = data.getNodeCoolDowns();

        // 1. 检查【恢复期】
        if (cds.containsKey(locKey + SUFFIX_RECOVERING)) {
            long recoverEnd = cds.get(locKey + SUFFIX_RECOVERING);
            if (now < recoverEnd) {
                long left = (recoverEnd - now) / 1000;
                player.sendMessage(getMsg("messages.node_recovering").replace("%time%", String.valueOf(left)));
                return;
            } else {
                cds.remove(locKey + SUFFIX_RECOVERING);
                player.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f);
            }
        }

        // 2. 检查【枯竭期】
        boolean isDepleted = false;
        if (cds.containsKey(locKey + SUFFIX_DEPLETED)) {
            long depletedEnd = cds.get(locKey + SUFFIX_DEPLETED);

            if (now < depletedEnd) {
                isDepleted = true;
            } else {
                cds.remove(locKey + SUFFIX_DEPLETED);
            }
        }

        // 3. 计算倍率
        double timeMult = 1.0;
        double energyMult = 1.0;
        double yieldMult = 1.0;

        if (isDepleted) {
            timeMult = config.getDouble("mining.depleted_time_multiplier", 2.0);
            energyMult = config.getDouble("mining.depleted_energy_multiplier", 1.5);
            yieldMult = config.getDouble("mining.depleted_yield_ratio", 0.5);

            String hint = getMsg("messages.node_depleted_hint")
                    .replace("%time%", String.valueOf(timeMult))
                    .replace("%energy%", String.valueOf(energyMult))
                    .replace("%yield%", String.valueOf((int)(yieldMult * 100)));
            player.sendMessage(hint);
        }

        double finalEnergyCost = node.energyCost * energyMult;
        double finalTime = node.timeSeconds * timeMult;

        if (data.getKaiWuEnergy() < finalEnergyCost) {
            player.sendMessage("§c精力不足！需要 " + String.format("%.1f", finalEnergyCost) + " 点。");
            return;
        }

        String title = isDepleted ? getMsg("bossbar.title_depleted") : getMsg("bossbar.title_rich");
        BarColor barColor = isDepleted ?
                safeBarColor(config.getString("bossbar.color_depleted", "YELLOW")) :
                safeBarColor(config.getString("bossbar.color_rich", "GREEN"));

        BossBar bar = Bukkit.createBossBar(title, barColor, BarStyle.SOLID);
        bar.addPlayer(player);
        miningBars.put(player.getUniqueId(), bar);
        miningStartLoc.put(player.getUniqueId(), player.getLocation());

        final boolean finalIsDepleted = isDepleted;
        final double finalYield = yieldMult;
        final double costEnergy = finalEnergyCost;

        BukkitRunnable task = new BukkitRunnable() {
            double progress = 0.0;
            final double tickAdd = 1.0 / (finalTime * 20);

            @Override
            public void run() {
                if (!player.isOnline() || player.isDead()) {
                    cancelMining(player, false); return;
                }

                double maxDist = config.getDouble("mining.interrupt_distance", 5.0);
                Location start = miningStartLoc.get(player.getUniqueId());
                if (start != null && player.getLocation().distance(start) > maxDist) {
                    player.sendMessage(getMsg("messages.mining_interrupted_move"));
                    cancelMining(player, false);
                    return;
                }

                progress += tickAdd;
                if (progress >= 1.0) progress = 1.0;
                bar.setProgress(progress);

                if (progress >= 1.0) {
                    finishMining(player, data, node, locKey, finalIsDepleted, costEnergy, finalYield);
                    cancelMining(player, false);
                }
            }
        };

        task.runTaskTimer(plugin, 0L, 1L);
        miningTasks.put(player.getUniqueId(), task.getTaskId());
    }

    private void finishMining(Player player, PlayerData data, NodeConfig node, String locKey, boolean wasDepleted, double energyCost, double yieldMult) {
        if (data.getKaiWuEnergy() < energyCost) return;
        data.setKaiWuEnergy(data.getKaiWuEnergy() - energyCost);

        long now = System.currentTimeMillis();

        if (!wasDepleted) {
            // 富饶 -> 枯竭
            // 【修改】使用节点独立的枯竭恢复时间
            long duration = node.depletedSec * 1000L;
            data.getNodeCoolDowns().put(locKey + SUFFIX_DEPLETED, now + duration);
        } else {
            // 枯竭 -> 恢复
            // 使用节点独立的重生冷却时间
            long cooldown = node.cooldownSec * 1000L;
            data.getNodeCoolDowns().put(locKey + SUFFIX_RECOVERING, now + cooldown);
            data.getNodeCoolDowns().remove(locKey + SUFFIX_DEPLETED);
        }

        if (node.drops != null && !node.drops.isEmpty()) {
            ItemStack template = node.drops.get(new Random().nextInt(node.drops.size())).clone();
            int maxAmount = template.getAmount();
            int finalAmount = 1;
            if (maxAmount > 1) finalAmount = 1 + new Random().nextInt(maxAmount);

            double calcAmount = finalAmount * yieldMult;
            if (calcAmount < 1.0) {
                if (Math.random() > calcAmount) finalAmount = 0;
                else finalAmount = 1;
            } else {
                finalAmount = (int) Math.round(calcAmount);
            }

            if (finalAmount > 0 && template.getType() != Material.AIR) {
                template.setAmount(finalAmount);
                HashMap<Integer, ItemStack> left = player.getInventory().addItem(template);
                if (!left.isEmpty()) {
                    player.getWorld().dropItem(player.getLocation(), left.get(0));
                    player.sendMessage(getMsg("messages.mining_fail_bag_full"));
                }
                String name = getDisplayName(template);
                player.sendMessage(getMsg("messages.mining_success")
                        .replace("%item%", name)
                        .replace("%amount%", String.valueOf(finalAmount)));
            } else {
                player.sendMessage("§7资源过于贫瘠，本次开采化为乌有...");
            }
        } else {
            player.sendMessage("§7一无所获...");
        }

        data.setKaiWuExp(data.getKaiWuExp() + node.exp);
        checkLevelUp(player, data);
        plugin.getDatabaseManager().savePlayer(data);
    }

    // ==========================================
    //           视觉特效
    // ==========================================

    private void startVisualTask() {
        int interval = config.getInt("visual.check_interval", 20);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!config.getBoolean("visual.enabled", true)) return;
                double range = config.getDouble("visual.default_range", 10.0);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    try { highlightNodes(p, range); } catch (Exception ignored) {}
                }
            }
        }.runTaskTimer(plugin, 40L, interval);
    }

    public void highlightNodes(Player p, double range) {
        if (!p.isOnline()) return;

        PlayerData data = plugin.getPlayerManager().getPlayerData(p);
        if (data == null) return;

        Location pLoc = p.getLocation();
        String worldName = pLoc.getWorld().getName();
        int pChunkX = pLoc.getBlockX() >> 4;
        int pChunkZ = pLoc.getBlockZ() >> 4;

        Map<String, Long> cds = data.getNodeCoolDowns();
        long now = System.currentTimeMillis();
        int particleCount = config.getInt("visual.particle_count", 3);

        for (int cx = pChunkX - 1; cx <= pChunkX + 1; cx++) {
            for (int cz = pChunkZ - 1; cz <= pChunkZ + 1; cz++) {
                String chunkKey = worldName + "," + cx + "," + cz;
                List<NodeConfig> nodes = chunkNodeMap.get(chunkKey);

                if (nodes == null) continue;

                for (NodeConfig node : new ArrayList<>(nodes)) {
                    if (node.cachedLoc.distanceSquared(pLoc) > range * range) continue;

                    String locKey = serializeLoc(node.cachedLoc);
                    String recoverKey = locKey + SUFFIX_RECOVERING;
                    String depletedKey = locKey + SUFFIX_DEPLETED;

                    if (cds.containsKey(recoverKey)) {
                        long recoverEnd = cds.get(recoverKey);
                        if (now >= recoverEnd) {
                            p.spawnParticle(Particle.VILLAGER_HAPPY, node.x + 0.5, node.y + 1.2, node.z + 0.5,
                                    15, 0.5, 0.5, 0.5, 0);
                            p.spawnParticle(Particle.TOTEM, node.x + 0.5, node.y + 1.2, node.z + 0.5,
                                    5, 0.2, 0.2, 0.2, 0.1);
                            p.playSound(node.cachedLoc, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 2.0f);
                            cds.remove(recoverKey);
                        } else {
                            p.spawnParticle(Particle.REDSTONE, node.x + 0.5, node.y + 1.2, node.z + 0.5,
                                    particleCount, 0.3, 0.3, 0.3, 0, dustRecovering);
                        }
                        continue;
                    }

                    if (cds.containsKey(depletedKey)) {
                        long depletedEnd = cds.get(depletedKey);
                        if (now >= depletedEnd) {
                            cds.remove(depletedKey);
                        } else {
                            p.spawnParticle(Particle.REDSTONE, node.x + 0.5, node.y + 1.2, node.z + 0.5,
                                    particleCount, 0.3, 0.3, 0.3, 0, dustDepleted);
                            continue;
                        }
                    }

                    p.spawnParticle(Particle.VILLAGER_HAPPY, node.x + 0.5, node.y + 1.2, node.z + 0.5,
                            particleCount, 0.4, 0.2, 0.4, 0);
                }
            }
        }
    }

    // ==========================================
    //           辅助与管理
    // ==========================================

    private void startRegenTask() {
        int interval = config.getInt("energy.regen_interval_min", 10);
        double amount = config.getDouble("energy.regen_amount", 20.0);
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    PlayerData data = plugin.getPlayerManager().getPlayerData(p);
                    if (data != null && data.getKaiWuEnergy() < data.getMaxKaiWuEnergy()) {
                        data.setKaiWuEnergy(Math.min(data.getMaxKaiWuEnergy(), data.getKaiWuEnergy() + amount));
                    }
                }
            }
        }.runTaskTimer(plugin, interval * 60 * 20L, interval * 60 * 20L);
    }

    public void removeNode(Player p, String locKey) {
        NodeConfig removed = nodeCache.remove(locKey);
        nodesConfig.set(locKey, null);
        try { nodesConfig.save(nodesFile); } catch (IOException e) { e.printStackTrace(); }

        if (removed != null) {
            int cx = ((int)removed.x) >> 4;
            int cz = ((int)removed.z) >> 4;
            String chunkKey = removed.worldName + "," + cx + "," + cz;
            if (chunkNodeMap.containsKey(chunkKey)) {
                List<NodeConfig> list = chunkNodeMap.get(chunkKey);
                if (list != null) list.remove(removed);
            }
        }

        try {
            String[] parts = locKey.split(",");
            if (parts.length == 4) {
                new Location(Bukkit.getWorld(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3])).getBlock().setType(Material.AIR);
            }
        } catch (Exception ignored) {}

        if (p != null) p.sendMessage(getMsg("messages.admin_delete_success"));
    }

    public void requestDeleteNode(Player p, String locKey) {
        deleteConfirmations.put(p.getUniqueId(), locKey);
        p.sendMessage(getMsg("messages.admin_confirm_delete"));
        new BukkitRunnable() { @Override public void run() {
            if(deleteConfirmations.containsKey(p.getUniqueId())) {
                deleteConfirmations.remove(p.getUniqueId());
                p.sendMessage("§7操作超时。");
            }
        }}.runTaskLater(plugin, 60 * 20L);
    }

    public void confirmDeleteNode(Player p) {
        String key = deleteConfirmations.remove(p.getUniqueId());
        if (key != null) removeNode(p, key);
    }

    // 【新增】保存时包含枯竭恢复时间参数
    public void saveNodeFromEditor(String locKey, List<ItemStack> drops, double time, double energy, int exp, int reqLv, int cooldown, int depleted) {
        nodesConfig.set(locKey + ".world", locKey.split(",")[0]);
        nodesConfig.set(locKey + ".drops", drops);
        nodesConfig.set(locKey + ".time", time);
        nodesConfig.set(locKey + ".energy", energy);
        nodesConfig.set(locKey + ".exp", exp);
        nodesConfig.set(locKey + ".req_level", reqLv);
        nodesConfig.set(locKey + ".cooldown", cooldown);
        nodesConfig.set(locKey + ".depleted", depleted); // 保存新字段
        try { nodesConfig.save(nodesFile); } catch (IOException e) { e.printStackTrace(); }
        loadNodes();
    }

    public void cancelMining(Player player, boolean isDamage) {
        UUID uuid = player.getUniqueId();
        if (miningTasks.containsKey(uuid)) {
            Bukkit.getScheduler().cancelTask(miningTasks.remove(uuid));
            if (miningBars.containsKey(uuid)) miningBars.remove(uuid).removeAll();
            miningStartLoc.remove(uuid);
            if (isDamage) player.sendMessage(getMsg("messages.mining_interrupted_damage"));
        }
    }

    public boolean isMining(Player player) { return miningTasks.containsKey(player.getUniqueId()); }

    private void checkLevelUp(Player p, PlayerData data) {
        int base = config.getInt("level_exp_base", 100);
        int req = data.getKaiWuLevel() * base;
        if (data.getKaiWuExp() >= req) {
            data.setKaiWuExp(data.getKaiWuExp() - req);
            data.setKaiWuLevel(data.getKaiWuLevel() + 1);
            p.sendMessage(getMsg("messages.level_up").replace("%level%", String.valueOf(data.getKaiWuLevel())));
            p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
    }

    private String getMsg(String path) {
        String prefix = ChatColor.translateAlternateColorCodes('&', config.getString("messages.prefix", ""));
        String msg = ChatColor.translateAlternateColorCodes('&', config.getString(path, ""));
        return prefix + msg;
    }

    private BarColor safeBarColor(String name) {
        try { return BarColor.valueOf(name); } catch (IllegalArgumentException e) { return BarColor.GREEN; }
    }

    private String getDisplayName(ItemStack item) {
        if (item.getItemMeta() != null && item.getItemMeta().hasDisplayName()) return item.getItemMeta().getDisplayName();
        String type = item.getType().name().toLowerCase().replace("_", " ");
        StringBuilder sb = new StringBuilder();
        for (String s : type.split(" ")) {
            if (s.length() > 0) sb.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1)).append(" ");
        }
        return sb.toString().trim();
    }

    public String serializeLoc(Location loc) { return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ(); }
    public NodeConfig getNode(String key) { return nodeCache.get(key); }
    public boolean isNode(Location loc) { return nodeCache.containsKey(serializeLoc(loc)); }

    // Admin setters
    public void setPlayerLevel(Player p, int lv) {
        PlayerData data = plugin.getPlayerManager().getPlayerData(p);
        if (data != null) { data.setKaiWuLevel(lv); p.sendMessage("§a等级已设为 " + lv); }
    }
    public void setPlayerEnergy(Player p, double energy) {
        PlayerData data = plugin.getPlayerManager().getPlayerData(p);
        if (data != null) { data.setKaiWuEnergy(energy); p.sendMessage("§a精力已设为 " + energy); }
    }

    public static class NodeConfig {
        List<ItemStack> drops = new ArrayList<>();
        double timeSeconds;
        double energyCost;
        int exp;
        int reqLevel;
        int cooldownSec;
        int depletedSec; // 【新增】枯竭恢复时间
        String worldName;
        double x, y, z;
        Location cachedLoc;
    }
}