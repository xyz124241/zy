package de.neptun.zyphix;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.*;

public final class ZyphixPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<String, ItemStack> customItems = new LinkedHashMap<>();
    private final Map<String, RecipeEditor> editors = new HashMap<>();
    private final Map<String, Crystal> crystals = new HashMap<>();
    private final Map<UUID, List<String>> lastSpawn = new HashMap<>();
    private final Map<UUID, Map<Integer, String>> publicMenuSlots = new HashMap<>();
    private NamespacedKey zyphixKey;
    private NamespacedKey customKey;
    private int nextId = 1;
    private boolean loadingCrystals = false;

    @Override public void onEnable() {
        saveDefaultConfig();
        zyphixKey = new NamespacedKey(this, "zyphix");
        customKey = new NamespacedKey(this, "custom_item");
        loadItems(); loadRecipes(); loadCrystals();
        for (String cmd : List.of("zy", "aci", "ci", "cn")) {
            PluginCommand c = getCommand(cmd);
            if (c != null) { c.setExecutor(this); c.setTabCompleter(this); }
        }
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tickCrystals, 20L, 20L);
        getLogger().info("Zyphix enabled. Use /aci, /ci, /cn and /zy.");
    }

    @Override public void onDisable() { saveItems(); saveCrystals(); }

    private ItemStack zyphixItem(int stage) {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(mm.deserialize("<bold><#D5D9E8>Zyphix</#D5D9E8></bold>"));
        meta.lore(List.of(mm.deserialize("<gray>A pale, glowing crystal</gray>"), mm.deserialize("<dark_gray>Neptun SMP</dark_gray>")));
        meta.setCustomModelData(10001 + Math.max(0, Math.min(4, stage)));
        meta.getPersistentDataContainer().set(zyphixKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isZyphix(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(zyphixKey, PersistentDataType.BYTE);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("cn")) {
            if (!(sender instanceof Player p)) return true;
            if (args.length == 0) { p.sendMessage(mm.deserialize("<gray>Usage: <white>/cn <MiniMessage></white></gray>")); return true; }
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) { p.sendMessage(mm.deserialize("<red>Hold an item first.</red>")); return true; }
            String raw = String.join(" ", args);
            try { ItemMeta meta = hand.getItemMeta(); meta.displayName(mm.deserialize(raw)); hand.setItemMeta(meta);
                String customId = meta.getPersistentDataContainer().get(customKey, PersistentDataType.STRING);
                if (customId != null && customItems.containsKey(customId)) { customItems.put(customId, hand.clone()); saveItems(); }
                p.sendMessage(mm.deserialize("<gray>Item name updated.</gray>")); }
            catch (Exception ex) { p.sendMessage(mm.deserialize("<red>Invalid MiniMessage format.</red>")); }
            return true;
        }
        if (name.equals("ci")) { if (sender instanceof Player p) openPublicMenu(p); return true; }
        if (name.equals("aci")) {
            if (!(sender instanceof Player p)) return true;
            if (!isAdmin(p)) { p.sendMessage(mm.deserialize("<red>No permission.</red>")); return true; }
            if (args.length == 1 && args[0].equalsIgnoreCase("list")) { p.sendMessage(mm.deserialize("<gray>Custom items: <white>" + String.join(", ", customItems.keySet()) + "</white></gray>")); return true; }
            if (args.length >= 2 && args[0].equalsIgnoreCase("create")) {
                ItemStack inHand = p.getInventory().getItemInMainHand();
                if (inHand.getType().isAir()) { p.sendMessage(mm.deserialize("<red>Hold the item you want to add.</red>")); return true; }
                String id = args[1].toLowerCase(Locale.ROOT);
                if (!id.matches("[a-z0-9_-]+")) { p.sendMessage(mm.deserialize("<red>Use only a-z, 0-9, _ and -.</red>")); return true; }
                ItemStack saved = inHand.clone(); ItemMeta savedMeta = saved.getItemMeta(); savedMeta.getPersistentDataContainer().set(customKey, PersistentDataType.STRING, id); saved.setItemMeta(savedMeta); customItems.put(id, saved); saveItems(); p.sendMessage(mm.deserialize("<green>Saved custom item <white>" + id + "</white>.</green>")); return true;
            }
            if (args.length >= 2 && args[0].equalsIgnoreCase("recipe")) { openRecipeEditor(p, args[1]); return true; }
            if (args.length >= 2 && args[0].equalsIgnoreCase("delete")) { customItems.remove(args[1]); getConfig().set("recipes." + args[1], null); saveItems(); saveConfig(); p.sendMessage(mm.deserialize("<green>Deleted <white>" + args[1] + "</white>.</green>")); return true; }
            if (args.length >= 2 && args[0].equalsIgnoreCase("name")) {
                String id = args[1]; if (!customItems.containsKey(id) || args.length < 3) { p.sendMessage(mm.deserialize("<red>Usage: /aci name <id> <MiniMessage></red>")); return true; }
                ItemStack item = customItems.get(id); ItemMeta meta = item.getItemMeta(); meta.displayName(mm.deserialize(String.join(" ", Arrays.copyOfRange(args, 2, args.length)))); meta.getPersistentDataContainer().set(customKey, PersistentDataType.STRING, id); item.setItemMeta(meta); customItems.put(id, item); saveItems(); return true;
            }
            openAdminMenu(p); return true;
        }
        if (name.equals("zy")) {
            if (!(sender instanceof Player p)) return true;
            if (!isAdmin(p)) { p.sendMessage(mm.deserialize("<red>No permission.</red>")); return true; }
            if (args.length == 0) { p.sendMessage(mm.deserialize("<gray>/zy spawn | /zy undo</gray>")); return true; }
            if (args[0].equalsIgnoreCase("spawn")) { spawnCave(p); return true; }
            if (args[0].equalsIgnoreCase("undo")) { undoCave(p); return true; }
        }
        return true;
    }

    private boolean isAdmin(Player p) { return p.isOp() || p.hasPermission("zyphix.admin"); }

    private void openAdminMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, mm.deserialize("<dark_gray>Custom Items | Admin"));
        inv.setItem(4, button(Material.AMETHYST_SHARD, "<#D5D9E8>Zyphix", "<gray>Built-in crystal item"));
        inv.setItem(13, button(Material.HOPPER, "<white>Drop an item here", "<gray>Drag an item from your inventory into this slot."));
        inv.setItem(49, button(Material.LIME_CONCRETE, "<green>Save item", "<gray>Save the item in slot 13 as a custom item."));
        inv.setItem(45, button(Material.CRAFTING_TABLE, "<white>Recipe editor", "<gray>Use /aci recipe <id>"));
        inv.setItem(53, button(Material.BOOK, "<white>Item list", "<gray>Use /aci list"));
        p.openInventory(inv);
    }

    private ItemStack button(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat); ItemMeta meta = item.getItemMeta(); meta.displayName(mm.deserialize(name));
        meta.lore(Arrays.stream(lore).map(mm::deserialize).toList()); item.setItemMeta(meta); return item;
    }

    private void openPublicMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, mm.deserialize("<dark_gray>Neptun <#D5D9E8>Custom Items"));
        Map<Integer, String> slots = new HashMap<>();
        int slot = 0;
        for (Map.Entry<String, ItemStack> entry : customItems.entrySet()) {
            if (slot >= 45) break;
            ItemStack display = entry.getValue().clone(); ItemMeta meta = display.getItemMeta();
            List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(mm.deserialize("<dark_gray>ID: <gray>" + entry.getKey())); lore.add(mm.deserialize("<gray>Recipe: <white>/ci</white> to browse")); meta.lore(lore); display.setItemMeta(meta); inv.setItem(slot, display); slots.put(slot, entry.getKey()); slot++;
        }
        publicMenuSlots.put(p.getUniqueId(), slots);
        if (slot == 0) inv.setItem(22, button(Material.BARRIER, "<gray>No custom items published yet"));
        p.openInventory(inv);
    }

    private void openPublicRecipe(Player p, String id) {
        Inventory inv = Bukkit.createInventory(null, 45, mm.deserialize("<dark_gray>Recipe preview <#D5D9E8>" + id));
        ItemStack result = customItems.get(id);
        if (result != null) inv.setItem(24, result.clone());
        List<?> ingredients = getConfig().getList("recipes." + id + ".ingredients");
        int[] slots = {10,11,12,19,20,21,28,29,30}; int i = 0;
        if (ingredients != null) for (Object obj : ingredients) {
            if (i >= slots.length) break;
            if (obj instanceof ItemStack item) inv.setItem(slots[i++], item.clone());
        }
        inv.setItem(40, button(Material.ARROW, "<gray>Recipe ingredients", "<dark_gray>Shapeless recipe"));
        p.openInventory(inv);
    }

    private void openRecipeEditor(Player p, String id) {
        if (!customItems.containsKey(id)) { p.sendMessage(mm.deserialize("<red>Unknown custom item: " + id + "</red>")); return; }
        Inventory inv = Bukkit.createInventory(null, 54, mm.deserialize("<dark_gray>Recipe <#D5D9E8>" + id));
        int[] grid = {10,11,12,19,20,21,28,29,30};
        for (int s : grid) inv.setItem(s, null);
        inv.setItem(24, customItems.get(id).clone());
        inv.setItem(49, button(Material.LIME_CONCRETE, "<green>Save recipe", "<gray>Place ingredients in the 3x3 slots", "<gray>Then click here"));
        inv.setItem(45, button(Material.BARRIER, "<red>Cancel"));
        editors.put(p.getUniqueId().toString(), new RecipeEditor(id, inv)); p.openInventory(inv);
    }

    @EventHandler public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = PlainTextComponentSerializer.plainText().serialize(e.getView().title());
        if (title.startsWith("Custom Items | Admin")) {
            int raw = e.getRawSlot();
            if (raw == 13) return; // allow placing/removing the chosen item
            e.setCancelled(true);
            if (raw == 49) {
                ItemStack chosen = e.getInventory().getItem(13);
                if (chosen == null || chosen.getType().isAir() || chosen.getType() == Material.HOPPER) { p.sendMessage(mm.deserialize("<red>Put an item in slot 13 first.</red>")); return; }
                String id = "custom_" + nextId++;
                ItemStack saved = chosen.clone(); ItemMeta savedMeta = saved.getItemMeta(); savedMeta.getPersistentDataContainer().set(customKey, PersistentDataType.STRING, id); saved.setItemMeta(savedMeta); customItems.put(id, saved); saveItems();
                e.getInventory().setItem(13, button(Material.HOPPER, "<white>Drop an item here", "<gray>Drag an item from your inventory into this slot."));
                p.sendMessage(mm.deserialize("<green>Created custom item <white>" + id + "</white>. Edit its name with /cn while holding it, then save it again if needed.</green>"));
            }
            return;
        }
        if (title.startsWith("Neptun Custom Items")) {
            e.setCancelled(true);
            String id = publicMenuSlots.getOrDefault(p.getUniqueId(), Collections.emptyMap()).get(e.getRawSlot());
            if (id != null) openPublicRecipe(p, id);
            return;
        }
        if (title.startsWith("Recipe preview ")) { e.setCancelled(true); return; }
        if (!title.startsWith("Recipe ")) return;
        RecipeEditor editor = editors.get(p.getUniqueId().toString()); if (editor == null) return;
        int raw = e.getRawSlot();
        int[] grid = {10,11,12,19,20,21,28,29,30};
        if (raw == 49) { e.setCancelled(true); saveRecipe(p, editor); return; }
        if (raw == 45 || raw == 24 || (raw < 54 && Arrays.stream(grid).noneMatch(s -> s == raw))) { e.setCancelled(true); return; }
    }

    @EventHandler public void onInventoryDrag(InventoryDragEvent e) {
        String title = PlainTextComponentSerializer.plainText().serialize(e.getView().title());
        if (title.startsWith("Neptun Custom Items") || title.startsWith("Recipe preview ")) { e.setCancelled(true); return; }
        if (title.startsWith("Custom Items | Admin")) {
            for (int raw : e.getRawSlots()) if (raw < 54 && raw != 13) { e.setCancelled(true); return; }
            return;
        }
        if (!title.startsWith("Recipe ")) return;
        for (int raw : e.getRawSlots()) if (raw < 54 && !Set.of(10,11,12,19,20,21,28,29,30).contains(raw)) { e.setCancelled(true); return; }
    }

    @EventHandler public void onClose(InventoryCloseEvent e) { editors.remove(e.getPlayer().getUniqueId().toString()); }

    private void saveRecipe(Player p, RecipeEditor editor) {
        int[] grid = {10,11,12,19,20,21,28,29,30};
        ShapelessRecipe recipe = new ShapelessRecipe(new NamespacedKey(this, "custom_" + editor.id), customItems.get(editor.id).clone());
        List<ItemStack> ingredients = new ArrayList<>();
        for (int slot : grid) { ItemStack it = editor.inventory.getItem(slot); if (it != null && !it.getType().isAir()) { recipe.addIngredient(it.getType()); ingredients.add(new ItemStack(it.getType())); } }
        if (ingredients.isEmpty()) { p.sendMessage(mm.deserialize("<red>Add at least one ingredient.</red>")); return; }
        Bukkit.removeRecipe(recipe.getKey()); Bukkit.addRecipe(recipe);
        getConfig().set("recipes." + editor.id + ".ingredients", ingredients); saveConfig();
        p.sendMessage(mm.deserialize("<green>Recipe saved for <white>" + editor.id + "</white>. It is shapeless.</green>")); p.closeInventory();
    }

    @EventHandler public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_AIR)) return;
        ItemStack hand = e.getItem(); if (!isZyphix(hand)) return;
        if (e.getClickedBlock() == null) return;
        Block target = e.getClickedBlock().getRelative(e.getBlockFace());
        if (!target.getType().isAir()) return;
        e.setCancelled(true); target.setType(Material.BARRIER, false);
        if (hand.getAmount() > 1) hand.setAmount(hand.getAmount() - 1); else e.getPlayer().getInventory().setItemInMainHand(null);
        createCrystal(target.getLocation(), true, 0);
    }

    @EventHandler public void onBreak(BlockBreakEvent e) {
        String key = locKey(e.getBlock().getLocation()); Crystal crystal = crystals.remove(key); if (crystal == null) return;
        e.setCancelled(true); e.getBlock().setType(Material.AIR, false);
        if (crystal.display != null && crystal.display.isValid()) crystal.display.remove(); else if (crystal.displayId != null && Bukkit.getEntity(crystal.displayId) != null) Bukkit.getEntity(crystal.displayId).remove();
        double chance = crystal.playerPlaced ? 1.0 : 0.25;
        if (Math.random() <= chance) e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().add(.5,.5,.5), zyphixItem(crystal.stage));
        saveCrystals();
    }

    private void createCrystal(Location loc, boolean playerPlaced, int stage) {
        String key = locKey(loc); World world = loc.getWorld(); if (world == null) return;
        ItemDisplay display = world.spawn(loc.clone().add(.5,.05,.5), ItemDisplay.class, d -> {
            d.setItemStack(zyphixItem(stage)); d.setBillboard(Display.Billboard.FIXED); d.setPersistent(true);
            d.setTransformation(new Transformation(new Vector3f(-.20f, 0f, -.20f), new org.joml.Quaternionf(), new Vector3f(1.4f,1.4f,1.4f), new org.joml.Quaternionf()));
        });
        crystals.put(key, new Crystal(loc, playerPlaced, stage, display.getUniqueId())); if (!loadingCrystals) saveCrystals();
    }

    private void tickCrystals() {
        for (Crystal c : crystals.values()) {
            World w = c.location.getWorld(); if (w == null) continue;
            if (c.displayId != null) {
                var entity = Bukkit.getEntity(c.displayId);
                if (entity instanceof ItemDisplay d && d.isValid()) c.display = d;
            }
            if (c.display == null || !c.display.isValid()) continue;
            // Bright silver motes that drift upward around each crystal (reference-image look).
            Location mote = c.location.clone().add(.5, .35 + Math.random() * .45, .5);
            w.spawnParticle(Particle.END_ROD, mote, 2, .22, .18, .22, .002);
            w.spawnParticle(Particle.FIREWORK, mote.clone().add(0, .12, 0), 1, .18, .22, .18, .001);
            w.spawnParticle(Particle.ENCHANT, mote.clone().add(0, .18, 0), 3, .25, .24, .25, .02);
            if (Math.random() < .35) w.spawnParticle(Particle.GLOW, mote.clone().add(0, .2, 0), 1, .12, .18, .12, .0);
            if (c.stage < 4 && System.currentTimeMillis() - c.lastGrowth > 45_000L) {
                c.stage++; c.lastGrowth = System.currentTimeMillis(); c.display.setItemStack(zyphixItem(c.stage));
            }
        }
    }

    private void spawnCave(Player p) {
        Location base = p.getLocation().getBlock().getRelative(p.getFacing()).getLocation();
        List<String> made = new ArrayList<>();
        int[][] offsets = {{0,0,0},{1,0,0},{-1,0,0},{0,1,1},{1,1,1},{-1,1,1},{0,0,2},{1,0,2},{-1,0,2},{0,1,-1},{2,0,1},{-2,0,1},{2,1,1}};
        for (int[] o : offsets) {
            Location l = base.clone().add(o[0], o[1], o[2]);
            if (!l.getBlock().getType().isAir()) continue;
            l.getBlock().setType(Material.BARRIER, false); createCrystal(l, false, Math.min(4, Math.abs(o[0]) + o[1])); made.add(locKey(l));
        }
        lastSpawn.put(p.getUniqueId(), made); p.sendMessage(mm.deserialize("<gray>Spawned <#D5D9E8>" + made.size() + " Zyphix crystals<gray>. Use <white>/zy undo</white> to remove them."));
    }

    private void undoCave(Player p) {
        List<String> keys = lastSpawn.remove(p.getUniqueId()); if (keys == null || keys.isEmpty()) { p.sendMessage(mm.deserialize("<red>No recent Zyphix cave to undo.</red>")); return; }
        for (String key : keys) { Crystal c = crystals.remove(key); if (c != null) { if (c.display != null && c.display.isValid()) c.display.remove(); else if (c.displayId != null && Bukkit.getEntity(c.displayId) != null) Bukkit.getEntity(c.displayId).remove(); c.location.getBlock().setType(Material.AIR, false); } }
        saveCrystals(); p.sendMessage(mm.deserialize("<green>Last Zyphix cave removed.</green>"));
    }

    private String locKey(Location l) { return l.getWorld().getName()+":"+l.getBlockX()+":"+l.getBlockY()+":"+l.getBlockZ(); }
    private void saveItems() { for (var e : customItems.entrySet()) getConfig().set("items."+e.getKey(), e.getValue()); saveConfig(); }
    private void loadItems() { ConfigurationSection sec = getConfig().getConfigurationSection("items"); if (sec != null) for (String k : sec.getKeys(false)) { ItemStack it = sec.getItemStack(k); if (it != null) customItems.put(k,it); if (k.startsWith("custom_")) { try { nextId = Math.max(nextId, Integer.parseInt(k.substring(7)) + 1); } catch (NumberFormatException ignored) { } } } }
    private void loadRecipes() {
        ConfigurationSection sec = getConfig().getConfigurationSection("recipes"); if (sec == null) return;
        for (String id : sec.getKeys(false)) { ItemStack result = customItems.get(id); List<?> list = sec.getList(id+".ingredients"); if (result == null || list == null) continue;
            ShapelessRecipe r = new ShapelessRecipe(new NamespacedKey(this,"custom_"+id), result.clone());
            for (Object obj : list) if (obj instanceof ItemStack it && !it.getType().isAir()) r.addIngredient(it.getType());
            try { Bukkit.addRecipe(r); } catch (IllegalArgumentException ignored) { }
        }
    }
    private void saveCrystals() {
        getConfig().set("crystals", null);
        for (var e : crystals.entrySet()) { Crystal c=e.getValue(); String path="crystals."+e.getKey(); getConfig().set(path+".world", c.location.getWorld().getName()); getConfig().set(path+".x", c.location.getBlockX()); getConfig().set(path+".y", c.location.getBlockY()); getConfig().set(path+".z", c.location.getBlockZ()); getConfig().set(path+".placed", c.playerPlaced); getConfig().set(path+".stage", c.stage); }
        saveConfig();
    }
    private void loadCrystals() {
        ConfigurationSection sec=getConfig().getConfigurationSection("crystals"); if(sec==null)return;
        loadingCrystals = true;
        for(String key:sec.getKeys(false)){ ConfigurationSection s=sec.getConfigurationSection(key); if(s==null)continue; World w=Bukkit.getWorld(s.getString("world","world")); if(w==null)continue; Location l=new Location(w,s.getInt("x"),s.getInt("y"),s.getInt("z")); if(l.getBlock().getType()!=Material.BARRIER)continue; createCrystal(l,s.getBoolean("placed"),s.getInt("stage")); }
        loadingCrystals = false;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("zy") && args.length==1) return List.of("spawn","undo");
        if (command.getName().equalsIgnoreCase("aci") && args.length==1) return List.of("create","recipe","delete","name");
        if (command.getName().equalsIgnoreCase("aci") && args.length==2 && (args[0].equalsIgnoreCase("recipe")||args[0].equalsIgnoreCase("delete")||args[0].equalsIgnoreCase("name"))) return new ArrayList<>(customItems.keySet());
        return Collections.emptyList();
    }

    private record RecipeEditor(String id, Inventory inventory) {}
    private static final class Crystal {
        final Location location; final boolean playerPlaced; int stage; UUID displayId; ItemDisplay display; long lastGrowth=System.currentTimeMillis();
        Crystal(Location l, boolean placed, int stage, UUID id) { this.location=l.clone(); this.playerPlaced=placed; this.stage=stage; this.displayId=id; }
        Crystal(Location l, boolean placed, int stage, UUID id, ItemDisplay display) { this(l,placed,stage,id); this.display=display; }
    }
}
