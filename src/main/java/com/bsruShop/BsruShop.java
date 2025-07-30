package com.bsruShop;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

enum GUIType {
    MAIN_SHOP,
    CATEGORY,
    PURCHASE
}

public class BsruShop extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final Map<UUID, PurchaseSession> playerPurchasing = new HashMap<>();
    private static Economy econ = null;
    private FileConfiguration config;
    private File configFile;
    private FileConfiguration itemsConfig;
    private File itemsFile;

    @Override
    public void onEnable() {
        if (!setupEconomy()) {
            getLogger().severe("Vault or an Economy plugin not found! Disabling BsruShop.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        setupFiles();
        getCommand("shop").setExecutor(this);
        getCommand("bsrushop").setExecutor(this);
        getCommand("bsrushop").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("BsruShop has been enabled!");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        econ = rsp.getProvider();
        return econ != null;
    }

    private void setupFiles() {
        if (!getDataFolder().exists()) getDataFolder().mkdir();
        configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) saveResource("config.yml", false);
        config = YamlConfiguration.loadConfiguration(configFile);
        itemsFile = new File(getDataFolder(), "items.yml");
        if (!itemsFile.exists()) saveResource("items.yml", false);
        itemsConfig = YamlConfiguration.loadConfiguration(itemsFile);
    }

    public void reloadConfigs() {
        config = YamlConfiguration.loadConfiguration(configFile);
        itemsConfig = YamlConfiguration.loadConfiguration(itemsFile);
    }

    public void saveItemsConfig() {
        try {
            itemsConfig.save(itemsFile);
        } catch (IOException e) {
            getLogger().severe("Could not save items.yml! " + e.getMessage());
        }
    }

    public class ShopGUIHolder implements InventoryHolder {
        private final GUIType type;
        private final String categoryKey;

        public ShopGUIHolder(GUIType type, String categoryKey) {
            this.type = type;
            this.categoryKey = categoryKey;
        }

        public ShopGUIHolder(GUIType type) {
            this(type, null);
        }

        public GUIType getType() {
            return type;
        }

        public String getCategoryKey() {
            return categoryKey;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("bsrushop")) {
            if (args.length == 1) {
                final List<String> subCommands = Arrays.asList("help", "reload", "additem", "removeitem");
                return StringUtil.copyPartialMatches(args[0], subCommands, new ArrayList<>());
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("additem") || args[0].equalsIgnoreCase("removeitem"))) {
                ConfigurationSection categoriesSection = itemsConfig.getConfigurationSection("categories");
                if (categoriesSection != null) {
                    return StringUtil.copyPartialMatches(args[1], categoriesSection.getKeys(false), new ArrayList<>());
                }
            }
        }
        return Collections.emptyList();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("shop")) {
            if (!(sender instanceof Player)) {
                sendMessage(sender, "player-only");
                return true;
            }
            openMainShopGUI((Player) sender);
            return true;
        } else if (command.getName().equalsIgnoreCase("bsrushop")) {
            if (args.length == 0) {
                sendPluginInfo(sender);
                return true;
            }

            switch (args[0].toLowerCase()) {
                case "help":
                    sendHelpMessage(sender);
                    break;
                case "reload":
                    if (!sender.hasPermission("bsrushop.admin")) {
                        sendMessage(sender, "no-permission");
                        return true;
                    }
                    reloadConfigs();
                    sendMessage(sender, "reload-success");
                    break;
                case "additem":
                    if (!sender.hasPermission("bsrushop.admin")) {
                        sendMessage(sender, "no-permission");
                        return true;
                    }
                    handleAddItemCommand(sender, args);
                    break;
                case "removeitem":
                    if (!sender.hasPermission("bsrushop.admin")) {
                        sendMessage(sender, "no-permission");
                        return true;
                    }
                    handleRemoveItemCommand(sender, args);
                    break;
                default:
                    sendMessage(sender, "invalid-command");
                    break;
            }
            return true;
        }
        return false;
    }

    private void handleAddItemCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sendMessage(sender, "player-only");
            return;
        }
        if (args.length != 4) {
            sendMessage(sender, "additem-usage");
            return;
        }
        Player player = (Player) sender;
        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (itemInHand.getType() == Material.AIR) {
            sendMessage(sender, "must-hold-item");
            return;
        }
        String categoryKey = args[1];
        if (itemsConfig.getConfigurationSection("categories." + categoryKey) == null) {
            sendMessage(sender, "category-not-found", "{category}", categoryKey);
            return;
        }
        int price, slot;
        try {
            price = Integer.parseInt(args[2]);
            slot = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            sendMessage(sender, "invalid-number", "{input}", e.getMessage().split("\"")[1]);
            return;
        }
        String newItemId = itemInHand.getType().name().toLowerCase() + "_" + System.currentTimeMillis();
        String itemPath = "categories." + categoryKey + ".items." + newItemId;

        ItemMeta meta = itemInHand.getItemMeta();
        String name = meta != null && meta.hasDisplayName() ? meta.getDisplayName() : toTitleCase(itemInHand.getType().name().replace("_", " "));

        itemsConfig.set(itemPath + ".itemstack", itemInHand);
        itemsConfig.set(itemPath + ".price", price);
        itemsConfig.set(itemPath + ".slot", slot);
        itemsConfig.set(itemPath + ".material", itemInHand.getType().name());
        itemsConfig.set(itemPath + ".name", name);

        saveItemsConfig();
        sendMessage(sender, "item-added", "{item_name}", name, "{category}", categoryKey, "{price}", String.valueOf(price), "{slot}", String.valueOf(slot));
    }

    private void handleRemoveItemCommand(CommandSender sender, String[] args) {
        if (args.length != 3) {
            sendMessage(sender, "removeitem-usage");
            return;
        }
        String categoryKey = args[1];
        String itemsPath = "categories." + categoryKey + ".items";
        if (itemsConfig.getConfigurationSection("categories." + categoryKey) == null) {
            sendMessage(sender, "category-not-found", "{category}", categoryKey);
            return;
        }
        int slot;
        try {
            slot = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sendMessage(sender, "invalid-number", "{input}", args[2]);
            return;
        }

        String itemToRemoveId = null;
        ConfigurationSection itemsSection = itemsConfig.getConfigurationSection(itemsPath);
        if (itemsSection != null) {
            for (String itemId : itemsSection.getKeys(false)) {
                if (itemsSection.getInt(itemId + ".slot") == slot) {
                    itemToRemoveId = itemId;
                    break;
                }
            }
        }

        if (itemToRemoveId != null) {
            itemsConfig.set(itemsPath + "." + itemToRemoveId, null);
            saveItemsConfig();
            sendMessage(sender, "item-removed", "{slot}", String.valueOf(slot), "{category}", categoryKey);
        } else {
            sendMessage(sender, "slot-is-empty", "{slot}", String.valueOf(slot), "{category}", categoryKey);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShopGUIHolder)) {
            return;
        }

        event.setCancelled(true);
        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        ShopGUIHolder holder = (ShopGUIHolder) event.getInventory().getHolder();

        switch (holder.getType()) {
            case MAIN_SHOP:
                handleMainShopClick(player, event.getCurrentItem());
                break;
            case CATEGORY:
                handleCategoryShopClick(player, event.getCurrentItem());
                break;
            case PURCHASE:
                handlePurchaseGUIClick(player, event.getCurrentItem());
                break;
        }
    }

    private void handleMainShopClick(Player player, ItemStack clickedItem) {
        if (clickedItem == null || !clickedItem.hasItemMeta()) {
            return;
        }

        ItemMeta meta = clickedItem.getItemMeta();
        NamespacedKey key = new NamespacedKey(this, "category_key");

        if (meta.getPersistentDataContainer().has(key, PersistentDataType.STRING)) {
            String categoryKey = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
            String path = "categories." + categoryKey + ".display_item.";

            String commandToRun = itemsConfig.getString(path + "command");
            if (commandToRun != null && !commandToRun.isEmpty()) {
                player.performCommand(commandToRun);
                playSound(player, "click-quantity");
                player.closeInventory();
            } else {
                openCategoryGUI(player, categoryKey);
            }
        }
    }

    private void handleCategoryShopClick(Player player, ItemStack clickedItem) {
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof ShopGUIHolder)) {
            return;
        }
        ShopGUIHolder holder = (ShopGUIHolder) player.getOpenInventory().getTopInventory().getHolder();
        String categoryKey = holder.getCategoryKey();

        if (categoryKey == null || clickedItem == null || clickedItem.getType() == Material.AIR) {
            return;
        }

        if (clickedItem.hasItemMeta()) {
            String backButtonName = format(itemsConfig.getString("categories." + categoryKey + ".back-button.name"));
            if (clickedItem.getItemMeta().getDisplayName().equals(backButtonName)) {
                openMainShopGUI(player);
                return;
            }
        }

        ConfigurationSection itemsSection = itemsConfig.getConfigurationSection("categories." + categoryKey + ".items");
        if (itemsSection == null) return;

        for (String itemKey : itemsSection.getKeys(false)) {
            String itemPath = "categories." + categoryKey + ".items." + itemKey;
            ItemStack loadedItem = itemsConfig.getItemStack(itemPath + ".itemstack");

            if (loadedItem != null) {
                String clickedName = clickedItem.hasItemMeta() ? clickedItem.getItemMeta().getDisplayName() : "";
                String loadedName = loadedItem.hasItemMeta() ? loadedItem.getItemMeta().getDisplayName() : "";

                if (clickedItem.getType() == loadedItem.getType() && clickedName.equals(loadedName)) {
                    // --- เพิ่มเสียงตรงนี้ ---
                    playSound(player, "click-quantity");

                    playerPurchasing.put(player.getUniqueId(), new PurchaseSession(categoryKey, itemKey));
                    openPurchaseGUI(player, 1);
                    return;
                }
            }
        }
    }

    private void handlePurchaseGUIClick(Player player, ItemStack clickedItem) {
        PurchaseSession session = playerPurchasing.get(player.getUniqueId());
        if (session == null) {
            player.closeInventory();
            return;
        }

        String itemPath = "categories." + session.getCategoryKey() + ".items." + session.getItemKey();
        ItemStack itemToBuy = itemsConfig.getItemStack(itemPath + ".itemstack");
        if (itemToBuy == null || !clickedItem.hasItemMeta()) {
            player.closeInventory();
            return;
        }

        int maxStack = itemToBuy.getMaxStackSize();
        String clickedName = clickedItem.getItemMeta().getDisplayName();
        int currentAmount = session.getAmount();
        ConfigurationSection buttons = config.getConfigurationSection("gui-settings.purchase-window.buttons");

        boolean quantityChanged = false;
        if (clickedName.equals(format(buttons.getString("plus-1")))) { currentAmount = Math.min(maxStack, currentAmount + 1); quantityChanged = true; }
        else if (clickedName.equals(format(buttons.getString("minus-1")))) { currentAmount = Math.max(1, currentAmount - 1); quantityChanged = true; }
        else if (clickedName.equals(format(buttons.getString("plus-16", "&a+16 &7(Max)")))) { currentAmount = maxStack; quantityChanged = true; }
        else if (clickedName.equals(format(buttons.getString("minus-16", "&c-16 &7(Min)")))) { currentAmount = 1; quantityChanged = true; }
        else if (clickedName.equals(format(buttons.getString("plus-10")))) { currentAmount = Math.min(maxStack, currentAmount + 10); quantityChanged = true; }
        else if (clickedName.equals(format(buttons.getString("plus-64")))) { currentAmount = maxStack; quantityChanged = true; }
        else if (clickedName.equals(format(buttons.getString("minus-10")))) { currentAmount = Math.max(1, currentAmount - 10); quantityChanged = true; }
        else if (clickedName.equals(format(buttons.getString("minus-64")))) { currentAmount = 1; quantityChanged = true; }

        session.setAmount(currentAmount);

        if (quantityChanged) {
            playSound(player, "click-quantity");
            openPurchaseGUI(player, currentAmount);
        } else if (clickedName.equals(format(buttons.getString("confirm")))) {
            buyItem(player, session);
        } else if (clickedName.equals(format(buttons.getString("cancel")))) {
            openCategoryGUI(player, session.getCategoryKey());
        }
    }

    private void openMainShopGUI(Player player) {
        String title = format(config.getString("gui-settings.main-shop-title", "&8ѕʜᴏᴘ"));
        Inventory gui = Bukkit.createInventory(new ShopGUIHolder(GUIType.MAIN_SHOP), 27, title);
        playSound(player, "open-shop");

        ConfigurationSection categoriesSection = itemsConfig.getConfigurationSection("categories");
        if (categoriesSection != null) {
            for (String categoryKey : categoriesSection.getKeys(false)) {
                String path = "categories." + categoryKey + ".display_item.";
                List<String> loreLines = itemsConfig.getStringList(path + "lore");
                String[] formattedLore = new String[loreLines.size()];
                for (int i = 0; i < loreLines.size(); i++) {
                    formattedLore[i] = format(loreLines.get(i));
                }
                ItemStack displayItem = createGuiItem(
                        Material.valueOf(itemsConfig.getString(path + "material")),
                        format(itemsConfig.getString(path + "name")),
                        formattedLore
                );

                ItemMeta meta = displayItem.getItemMeta();
                if (meta != null) {
                    meta.getPersistentDataContainer().set(new NamespacedKey(this, "category_key"), PersistentDataType.STRING, categoryKey);
                    displayItem.setItemMeta(meta);
                }

                gui.setItem(itemsConfig.getInt(path + "slot"), displayItem);
            }
        }
        player.openInventory(gui);
    }

    private void openCategoryGUI(Player player, String categoryKey) {
        String path = "categories." + categoryKey;
        String title = format(itemsConfig.getString(path + ".title"));
        int rows = itemsConfig.getInt(path + ".rows", 3);
        int size = Math.max(9, Math.min(54, rows * 9));
        Inventory gui = Bukkit.createInventory(new ShopGUIHolder(GUIType.CATEGORY, categoryKey), size, title);
        playSound(player, "open-category");

        ConfigurationSection itemsSection = itemsConfig.getConfigurationSection(path + ".items");
        if (itemsSection != null) {
            for (String itemKey : itemsSection.getKeys(false)) {
                String itemPath = path + ".items." + itemKey + ".";
                ItemStack itemStack = itemsConfig.getItemStack(itemPath + ".itemstack");
                if (itemStack == null) continue;
                int price = itemsConfig.getInt(itemPath + "price");

                List<String> loreFormat = config.getStringList("item-format.lore");
                List<String> finalLore = new ArrayList<>();
                if (itemStack.hasItemMeta() && itemStack.getItemMeta().hasLore()) {
                    finalLore.addAll(itemStack.getItemMeta().getLore());
                }

                for (String line : loreFormat) {
                    finalLore.add(format(line.replace("{price}", String.valueOf(price))));
                }

                ItemStack displayItem = itemStack.clone();
                ItemMeta displayMeta = displayItem.getItemMeta();
                if (displayMeta != null) {
                    displayMeta.setLore(finalLore);
                    displayItem.setItemMeta(displayMeta);
                }

                gui.setItem(itemsConfig.getInt(itemPath + "slot"), displayItem);
            }
        }

        String backButtonPath = path + ".back-button.";
        List<String> backButtonLoreLines = itemsConfig.getStringList(backButtonPath + "lore");
        String[] formattedBackButtonLore = new String[backButtonLoreLines.size()];
        for (int i = 0; i < backButtonLoreLines.size(); i++) {
            formattedBackButtonLore[i] = format(backButtonLoreLines.get(i));
        }

        gui.setItem(itemsConfig.getInt(backButtonPath + "slot", size - 1), createGuiItem(
                Material.valueOf(itemsConfig.getString(backButtonPath + "material", "BARRIER")),
                format(itemsConfig.getString(backButtonPath + "name")),
                formattedBackButtonLore
        ));
        player.openInventory(gui);
    }

    private void openPurchaseGUI(Player player, int amount) {
        PurchaseSession session = playerPurchasing.get(player.getUniqueId());
        if (session == null) return;
        String path = "categories." + session.getCategoryKey() + ".items." + session.getItemKey() + ".";
        ItemStack itemToBuy = itemsConfig.getItemStack(path + ".itemstack");
        if (itemToBuy == null) {
            player.closeInventory();
            sendMessage(player, "invalid-item");
            return;
        }

        int price = itemsConfig.getInt(path + "price");
        int maxStack = itemToBuy.getMaxStackSize();

        if (maxStack == 1) {
            amount = 1;
            session.setAmount(1);
        } else {
            amount = Math.max(1, Math.min(maxStack, amount));
            session.setAmount(amount);
        }

        int totalPrice = price * amount;
        ConfigurationSection guiSettings = config.getConfigurationSection("gui-settings.purchase-window");
        String guiTitle = format(guiSettings.getString("title-prefix")) + (itemToBuy.hasItemMeta() && itemToBuy.getItemMeta().hasDisplayName() ? itemToBuy.getItemMeta().getDisplayName() : toTitleCase(itemToBuy.getType().name()));
        Inventory gui = Bukkit.createInventory(new ShopGUIHolder(GUIType.PURCHASE), 27, guiTitle);
        ConfigurationSection slots = guiSettings.getConfigurationSection("slots");
        ConfigurationSection buttons = guiSettings.getConfigurationSection("buttons");

        if (maxStack == 16) {
            if (amount > 1) {
                if (slots.getInt("minus-10", -1) != -1)
                    gui.setItem(slots.getInt("minus-10"), createButton(Material.RED_STAINED_GLASS_PANE, buttons.getString("minus-16", "&c-16 &7(Min)"), 1));
                if (slots.getInt("minus-1", -1) != -1)
                    gui.setItem(slots.getInt("minus-1"), createButton(Material.RED_STAINED_GLASS_PANE, buttons.getString("minus-1"), 1));
            }
            if (amount < maxStack) {
                if (slots.getInt("plus-10", -1) != -1)
                    gui.setItem(slots.getInt("plus-10"), createButton(Material.GREEN_STAINED_GLASS_PANE, buttons.getString("plus-16", "&a+16 &7(Max)"), 16));
                if (slots.getInt("plus-1", -1) != -1)
                    gui.setItem(slots.getInt("plus-1"), createButton(Material.GREEN_STAINED_GLASS_PANE, buttons.getString("plus-1"), 1));
            }
        } else if (maxStack > 1) {
            if (amount > 1) {
                if (slots.getInt("minus-64", -1) != -1)
                    gui.setItem(slots.getInt("minus-64"), createButton(Material.RED_STAINED_GLASS_PANE, buttons.getString("minus-64"), 64));
                if (slots.getInt("minus-10", -1) != -1 && amount > 10)
                    gui.setItem(slots.getInt("minus-10"), createButton(Material.RED_STAINED_GLASS_PANE, buttons.getString("minus-10"), 10));
                if (slots.getInt("minus-1", -1) != -1)
                    gui.setItem(slots.getInt("minus-1"), createButton(Material.RED_STAINED_GLASS_PANE, buttons.getString("minus-1"), 1));
            }
            if (amount < maxStack) {
                if (slots.getInt("plus-64", -1) != -1)
                    gui.setItem(slots.getInt("plus-64"), createButton(Material.GREEN_STAINED_GLASS_PANE, buttons.getString("plus-64"), maxStack));
                if (slots.getInt("plus-10", -1) != -1 && amount <= maxStack - 10)
                    gui.setItem(slots.getInt("plus-10"), createButton(Material.GREEN_STAINED_GLASS_PANE, buttons.getString("plus-10"), 10));
                if (slots.getInt("plus-1", -1) != -1)
                    gui.setItem(slots.getInt("plus-1"), createButton(Material.GREEN_STAINED_GLASS_PANE, buttons.getString("plus-1"), 1));
            }
        }

        List<String> loreLines = buttons.getStringList("item-lore");
        List<String> finalLore = new ArrayList<>();
        for (String line : loreLines) {
            finalLore.add(format(line.replace("{amount}", String.valueOf(amount)).replace("{price}", String.valueOf(totalPrice))));
        }

        ItemStack purchaseItem = itemToBuy.clone();
        ItemMeta purchaseMeta = purchaseItem.getItemMeta();
        if (purchaseMeta != null) {
            purchaseMeta.setLore(finalLore);
            purchaseItem.setItemMeta(purchaseMeta);
        }

        purchaseItem.setAmount(amount);
        gui.setItem(slots.getInt("item"), purchaseItem);

        gui.setItem(slots.getInt("confirm"), createButton(Material.GREEN_STAINED_GLASS_PANE, buttons.getString("confirm"), 1));
        gui.setItem(slots.getInt("cancel"), createButton(Material.RED_STAINED_GLASS_PANE, buttons.getString("cancel"), 1));

        player.openInventory(gui);
    }

    private void buyItem(Player player, PurchaseSession session) {
        String path = "categories." + session.getCategoryKey() + ".items." + session.getItemKey() + ".";
        ItemStack itemToBuy = itemsConfig.getItemStack(path + ".itemstack");
        if (itemToBuy == null) {
            sendMessage(player, "invalid-item");
            player.closeInventory();
            return;
        }

        int price = itemsConfig.getInt(path + "price");
        int amount = session.getAmount();
        int totalPrice = price * amount;

        if (!econ.has(player, totalPrice)) {
            sendMessage(player, "not-enough-money", "{price}", String.valueOf(totalPrice));
            playSound(player, "purchase-fail");
            return;
        }

        if (!hasEnoughSpace(player, new ItemStack(itemToBuy.getType(), amount))) {
            sendMessage(player, "inventory-full", "{amount}", String.valueOf(amount));
            playSound(player, "purchase-fail");
            return;
        }

        EconomyResponse r = econ.withdrawPlayer(player, totalPrice);
        if (r.transactionSuccess()) {
            ItemStack finalItem = itemToBuy.clone();
            finalItem.setAmount(amount);
            player.getInventory().addItem(finalItem);
            sendMessage(player, "purchase-success", "{amount}", String.valueOf(amount), "{item_name}", finalItem.hasItemMeta() && finalItem.getItemMeta().hasDisplayName() ? finalItem.getItemMeta().getDisplayName() : toTitleCase(finalItem.getType().name()), "{price}", String.valueOf(totalPrice));
            playSound(player, "purchase-success");
        } else {
            sendMessage(player, "not-enough-money", "{price}", String.valueOf(totalPrice));
            playSound(player, "purchase-fail");
        }
    }

    private boolean hasEnoughSpace(Player player, ItemStack item) {
        Inventory testInventory = Bukkit.createInventory(null, 36);
        testInventory.setContents(player.getInventory().getStorageContents());
        return testInventory.addItem(item.clone()).isEmpty();
    }

    private ItemStack createButton(Material material, String name, int amount) {
        ItemStack item = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) meta.setDisplayName(format(name));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createGuiItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Arrays.asList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    private String toTitleCase(String text) {
        if (text == null || text.isEmpty()) return "";
        return Arrays.stream(text.split(" ")).map(word -> word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase().replace("_", " ")).collect(Collectors.joining(" "));
    }

    private String format(String message) {
        if (message == null) return "";
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private void playSound(Player player, String soundKey) {
        try {
            String soundName = config.getString("sounds." + soundKey + ".name");
            if (soundName == null || soundName.equalsIgnoreCase("none")) return;
            float volume = (float) config.getDouble("sounds." + soundKey + ".volume", 1.0);
            float pitch = (float) config.getDouble("sounds." + soundKey + ".pitch", 1.0);
            player.playSound(player.getLocation(), Sound.valueOf(soundName.toUpperCase()), volume, pitch);
        } catch (IllegalArgumentException e) {
            getLogger().warning("Invalid sound name in config.yml for key: " + soundKey);
        }
    }

    private void sendMessage(CommandSender sender, String messageKey, String... placeholders) {
        String message = config.getString("messages." + messageKey, "&cMessage not found: " + messageKey);
        for (int i = 0; i < placeholders.length; i += 2) {
            if (i + 1 < placeholders.length) {
                message = message.replace(placeholders[i], placeholders[i + 1]);
            }
        }

        String prefix = config.getString("message-prefix", "");
        String finalMessage = format(prefix + message);

        sender.sendMessage(finalMessage);

        if (sender instanceof Player) {
            Player player = (Player) sender;
            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new net.md_5.bungee.api.chat.TextComponent(finalMessage));
        }
    }

    private void sendPluginInfo(CommandSender sender) {
        sender.sendMessage(format("&e&m-------------------------------------------"));
        sender.sendMessage(format(" &b&lBsruShop"));
        sender.sendMessage(format(" &7ปลั๊กอินร้านค้า GUI อเนกประสงค์"));
        sender.sendMessage(format(""));
        sender.sendMessage(format(" &fผู้พัฒนา: &a_Nattapat2871_"));
        sender.sendMessage(format(" &fGitHub: &ehttps://github.com/Nattapat2871/BsruShop"));
        sender.sendMessage(format(""));
        sender.sendMessage(format(" &7พิมพ์ &a/bsrushop help &7สำหรับดูรายการคำสั่ง"));
        sender.sendMessage(format("&e&m-------------------------------------------"));
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(format("&e&m-----------&b&l BsruShop Help &e&m-----------"));
        sender.sendMessage(format(" &a/shop &f- &7เปิดร้านค้าหลัก"));
        sender.sendMessage(format(" &a/bsrushop &f- &7แสดงข้อมูลปลั๊กอิน"));
        sender.sendMessage(format(" &a/bsrushop help &f- &7แสดงหน้านี้"));
        sender.sendMessage(format(" &a/bsrushop reload &f- &7รีโหลดคอนฟิก"));
        sender.sendMessage(format(" &a/bsrushop additem <หมวด> <ราคา> <ช่อง> &f- &7เพิ่มไอเทมในมือเข้าร้าน"));
        sender.sendMessage(format(" &a/bsrushop removeitem <หมวด> <ช่อง> &f- &7ลบไอเทมจากร้านค้า"));
        sender.sendMessage(format("&e&m-------------------------------------------"));
    }

    private static class PurchaseSession {
        private final String categoryKey, itemKey;
        private int amount;
        public PurchaseSession(String c, String i) { categoryKey = c; itemKey = i; amount = 1; }
        public String getCategoryKey() { return categoryKey; }
        public String getItemKey() { return itemKey; }
        public int getAmount() { return amount; }
        public void setAmount(int a) { amount = a; }
    }
}