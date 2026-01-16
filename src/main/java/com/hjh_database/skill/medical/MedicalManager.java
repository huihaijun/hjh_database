package com.hjh_database.skill.medical;

import com.hjh_database.Hjh_database;
import com.hjh_database.data.PlayerData;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.banner.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class MedicalManager {
    private final Hjh_database plugin;
    // 缓存：ConfigKey -> 物品
    private final Map<String, ItemStack> skillBooks = new HashMap<>();
    // 缓存：SkillID -> 稀有度
    private final Map<String, Integer> skillRarityMap = new HashMap<>();
    // 缓存：SkillID -> 技能名
    private final Map<String, String> skillNameCache = new HashMap<>();

    public final NamespacedKey keySkillId;
    public final NamespacedKey keyIgnoreRefresh;

    private final Map<UUID, ItemStack[]> loomSessions = new ConcurrentHashMap<>();

    public MedicalManager(Hjh_database plugin) {
        this.plugin = plugin;
        this.keySkillId = new NamespacedKey(plugin, "med_skill_id");
        this.keyIgnoreRefresh = new NamespacedKey(plugin, "hjh_ignore_refresh");
        loadSkillBooks();
    }

    public void loadSkillBooks() {
        skillBooks.clear();
        skillRarityMap.clear();
        skillNameCache.clear();

        File file = new File(plugin.getDataFolder(), "medical_items.yml");
        if (!file.exists()) plugin.saveResource("medical_items.yml", false);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection items = config.getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection sec = items.getConfigurationSection(key);
                String skillId = sec.getString("skill_id", key);
                // 【修复】处理名字颜色
                String name = ChatColor.translateAlternateColorCodes('&', sec.getString("name", "未知医术"));
                int rarity = sec.getInt("rarity", 1);
                // 构建物品
                ItemStack item = new ItemStack(Material.valueOf(sec.getString("material", "PAPER")));
                ItemMeta meta = item.getItemMeta();
                meta.setDisplayName(name);

                // === 构建 Lore (修改点) ===
                List<String> rawLore = sec.getStringList("lore");
                List<String> finalLore = new ArrayList<>();

                // 1. 【新增】手动插入稀有度行 (确保在第一行，紧跟名字)
                String rarityColor;
                switch (rarity) {
                    case 1: rarityColor = "§f"; break; // 白
                    case 2: rarityColor = "§a"; break; // 绿
                    case 3: rarityColor = "§9"; break; // 蓝
                    case 4: rarityColor = "§d"; break; // 粉
                    case 5: rarityColor = "§e"; break; // 黄
                    case 6: rarityColor = "§c"; break; // 红
                    default: rarityColor = "§7"; break;
                }
                // 这里的 getRarityStars 方法见下方
                finalLore.add(rarityColor + "稀有度: " + getRarityStars(rarity));

                // 2. 【修复】追加配置文件的 Lore (处理颜色)
                if (rawLore != null) {
                    for (String line : rawLore) {
                        finalLore.add(ChatColor.translateAlternateColorCodes('&', line));
                    }
                }
                meta.setLore(finalLore);
                // 写入 NBT
                meta.getPersistentDataContainer().set(keySkillId, PersistentDataType.STRING, skillId);
                meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "rarity"), PersistentDataType.INTEGER, rarity);
                // 3. 【关键修复】加上免刷新锁！
                // 只有加上这个，ResourceListener 才会跳过它，防止Lore被刷没
                meta.getPersistentDataContainer().set(keyIgnoreRefresh, PersistentDataType.INTEGER, 1);
                item.setItemMeta(meta);
                // 存入缓存
                skillBooks.put(skillId, item);
                skillRarityMap.put(skillId, rarity);
                skillNameCache.put(skillId, name);
            }
        }
    }

    // 请确保类里有这个辅助方法 (生成星星)
    private String getRarityStars(int rarity) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rarity; i++) sb.append("★");
        return sb.toString();
    }

    // 获取缓存中的书籍 (带颜色和NBT的成品)
    public ItemStack getSkillBook(String skillId) {
        return skillBooks.get(skillId);
    }

    public String getSkillName(String skillId) { return skillNameCache.getOrDefault(skillId, "未知医术"); }
    public java.util.Set<String> getAllSkillIds() { return skillNameCache.keySet(); }
    public ItemStack[] getLoomSession(Player player) { return loomSessions.get(player.getUniqueId()); }
    public void saveLoomSession(Player player, ItemStack[] contents) { loomSessions.put(player.getUniqueId(), contents); }
    public ItemStack getMedicalStationItem() {
        ItemStack item = new ItemStack(Material.END_PORTAL_FRAME);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§b§l医术绘制台");
        meta.setLore(Arrays.asList("§7放置后右键点击打开医术界面"));
        return item;
    }
    public boolean isMedicalBanner(ItemStack item) { return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(keySkillId, PersistentDataType.STRING); }
    public String getSkillIdFromBanner(ItemStack item) { return isMedicalBanner(item) ? item.getItemMeta().getPersistentDataContainer().get(keySkillId, PersistentDataType.STRING) : null; }

    // === 刻印逻辑 ===
    public ItemStack etchSkill(Player player, ItemStack banner, ItemStack book) {
        if (banner == null || book == null) return null;
        if (!banner.getType().name().endsWith("_BANNER")) return null;

        ItemMeta bookMeta = book.getItemMeta();
        if (bookMeta == null) return null;
        String skillId = bookMeta.getPersistentDataContainer().get(keySkillId, PersistentDataType.STRING);
        if (skillId == null) return null;

        if (isMedicalBanner(banner)) {
            player.sendMessage("§c[绘制失败] §7这面旗帜上已经有医术了！");
            return null;
        }

        // 检查重复掌握
        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data.getMedicalLoadout().contains(skillId)) {
            player.sendMessage("§c[绘制失败] §7你脑海中已经掌握了此医术。");
            return null;
        }

        ItemStack result = banner.clone();
        result.setAmount(1);
        ItemMeta resultMeta = result.getItemMeta();
        // 修改显示名称：原名 + [医术名]
        String skillDisplayName = getSkillName(skillId);
        if (resultMeta.hasDisplayName()) {
            // 使用 §r 重置颜色，避免前面名字的颜色影响到后面
            resultMeta.setDisplayName(resultMeta.getDisplayName() + "§r[" + skillDisplayName + "§r]");
        } else {
            // 如果原本没名字，就给个默认的（防止空指针或无名）
            resultMeta.setDisplayName("§f医旗§r[" + skillDisplayName + "§r]");
        }
        // 1. 设置技能 ID
        resultMeta.getPersistentDataContainer().set(keySkillId, PersistentDataType.STRING, skillId);

        // 2. 【核心】加上免刷新锁 (配合 WeaponManager 修复)
        resultMeta.getPersistentDataContainer().set(keyIgnoreRefresh, PersistentDataType.INTEGER, 1);

        // 3. 隐藏原版旗帜图案
        resultMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_POTION_EFFECTS);

        // 4. Lore 继承 (过滤掉提示语)
        List<String> lore = resultMeta.hasLore() ? resultMeta.getLore() : new ArrayList<>();
        lore.add("§8----------------");
        lore.add("§6[医术] §e" + getSkillName(skillId));

        if (bookMeta.hasLore()) {
            for (String line : bookMeta.getLore()) {
                if (line.contains("放入绘制台")) continue;
                lore.add(line);
            }
        }
        resultMeta.setLore(lore);

        // 5. 绘制图案
        if (resultMeta instanceof BannerMeta) {
            BannerMeta bannerMeta = (BannerMeta) resultMeta;
            List<Pattern> patterns = MedicalPatternRegistry.getPatterns(skillId);
            if (patterns != null) {
                for (Pattern p : patterns) bannerMeta.addPattern(p);
            }
        }

        result.setItemMeta(resultMeta);

        data.addMedicalSkillMemory(skillId);
        CompletableFuture.runAsync(() -> plugin.getDatabaseManager().saveMedicalData(data));

        player.sendMessage("§a[绘制成功] §7你将 §e" + getSkillName(skillId) + " §7刻印于旗帜之上！");
        player.playSound(player.getLocation(), Sound.UI_LOOM_TAKE_RESULT, 1f, 1f);
        return result;
    }

    // === 分离逻辑 ===
    public ItemStack[] separateSkill(Player player, ItemStack inputBanner) {
        if (!isMedicalBanner(inputBanner)) return null;
        String skillId = getSkillIdFromBanner(inputBanner);
        PlayerData data = plugin.getPlayerManager().getPlayerData(player);

        if (!data.getMedicalLoadout().contains(skillId)) {
            player.sendMessage("§c[分离失败] §7你并未掌握此医术。");
            return null;
        }

        ItemStack book = getSkillBook(skillId);
        if (book == null) book = new ItemStack(Material.PAPER);
        ItemStack returnBook = book.clone();
        returnBook.setAmount(1);

        ItemStack blankBanner = inputBanner.clone();
        blankBanner.setAmount(1);
        ItemMeta meta = blankBanner.getItemMeta();

        // 还原显示名称：移除 [医术名]
        String skillDisplayName = getSkillName(skillId);
        if (meta.hasDisplayName()) {
            String currentName = meta.getDisplayName();
            // 构造后缀字符串（必须和etchSkill里加的一模一样）
            String suffix = "§r[" + skillDisplayName + "§r]";

            if (currentName.contains(suffix)) {
                // 将后缀替换为空
                meta.setDisplayName(currentName.replace(suffix, ""));
            }
        }
        meta.getPersistentDataContainer().remove(keySkillId);

        // 【核心】移除免刷新锁 (让它变回普通武器，可以被 WeaponManager 刷新属性)
        meta.getPersistentDataContainer().remove(keyIgnoreRefresh);

        // 智能清理 Lore
        if (meta.hasLore()) {
            List<String> bannerLore = meta.getLore();
            List<String> originalSkillLore = new ArrayList<>();
            if (book.hasItemMeta() && book.getItemMeta().hasLore()) {
                originalSkillLore = book.getItemMeta().getLore();
            }
            List<String> linesToRemove = new ArrayList<>(originalSkillLore);
            linesToRemove.add("§6[医术] §e" + getSkillName(skillId));
            linesToRemove.add("§8----------------");

            for (String removeTarget : linesToRemove) {
                bannerLore.remove(removeTarget);
            }
            meta.setLore(bannerLore);
        }

        // 清除图案
        if (meta instanceof BannerMeta) {
            ((BannerMeta) meta).setPatterns(new ArrayList<>());
        }

        blankBanner.setItemMeta(meta);

        data.removeMedicalSkillMemory(skillId);
        CompletableFuture.runAsync(() -> plugin.getDatabaseManager().saveMedicalData(data));

        player.sendMessage("§a[分离成功] §7医术已剥离。");
        player.playSound(player.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 1f, 1f);

        return new ItemStack[]{blankBanner, returnBook};
    }
}