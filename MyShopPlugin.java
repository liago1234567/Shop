package me.yourname.myshopplugin;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class MyShopPlugin extends JavaPlugin implements CommandExecutor, Listener {

    private Economy economy;
    private final Map<Material, Double> itemPrices = new HashMap<>();
    private final List<AuctionItem> auctionHouse = new ArrayList<>();
    private final List<SaleHistoryItem> saleHistory = new ArrayList<>();
    private final Map<UUID, Integer> auctionPage = new HashMap<>();
    private File auctionFile;
    private FileConfiguration auctionConfig;
    private File salesFile;
    private FileConfiguration salesConfig;

    @Override
    public void onEnable() {
        if (!setupEconomy()) {
            getLogger().severe("Vault nicht gefunden oder keine Economy verbunden!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getCommand("shop").setExecutor(this);
        getCommand("pay").setExecutor(this);
        getCommand("ah").setExecutor(this);
        getCommand("ahhistory").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        loadPrices();
        loadAuctionHouse();
        loadSalesHistory();
        startAuctionCleanup();
        getLogger().info("MyShopPlugin mit allen neuen Features aktiviert!");
    }

    @Override
    public void onDisable() {
        saveAuctionHouse();
        saveSalesHistory();
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    private void loadPrices() {
        itemPrices.put(Material.DIAMOND, 100.0);
        itemPrices.put(Material.EMERALD, 80.0);
        itemPrices.put(Material.IRON_INGOT, 20.0);
        itemPrices.put(Material.GOLD_INGOT, 40.0);
    }

    private void loadAuctionHouse() {
        auctionFile = new File(getDataFolder(), "auctionhouse.yml");
        if (!auctionFile.exists()) saveResource("auctionhouse.yml", false);
        auctionConfig = YamlConfiguration.loadConfiguration(auctionFile);

        auctionHouse.clear();
        if (auctionConfig.contains("auctions")) {
            for (String key : auctionConfig.getConfigurationSection("auctions").getKeys(false)) {
                ItemStack item = auctionConfig.getItemStack("auctions." + key + ".item");
                String seller = auctionConfig.getString("auctions." + key + ".seller");
                double price = auctionConfig.getDouble("auctions." + key + ".price");
                long expire = auctionConfig.getLong("auctions." + key + ".expire");
                if (item != null && seller != null) {
                    auctionHouse.add(new AuctionItem(item, seller, price, expire));
                }
            }
        }
    }

    private void saveAuctionHouse() {
        auctionConfig.set("auctions", null);
        int id = 0;
        for (AuctionItem item : auctionHouse) {
            auctionConfig.set("auctions." + id + ".item", item.getItem());
            auctionConfig.set("auctions." + id + ".seller", item.getSeller());
            auctionConfig.set("auctions." + id + ".price", item.getPrice());
            auctionConfig.set("auctions." + id + ".expire", item.getExpire());
            id++;
        }
        try {
            auctionConfig.save(auctionFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadSalesHistory() {
        salesFile = new File(getDataFolder(), "sales.yml");
        if (!salesFile.exists()) saveResource("sales.yml", false);
        salesConfig = YamlConfiguration.loadConfiguration(salesFile);

        saleHistory.clear();
        if (salesConfig.contains("sales")) {
            for (String key : salesConfig.getConfigurationSection("sales").getKeys(false)) {
                ItemStack item = salesConfig.getItemStack("sales." + key + ".item");
                String seller = salesConfig.getString("sales." + key + ".seller");
                String buyer = salesConfig.getString("sales." + key + ".buyer");
                long time = salesConfig.getLong("sales." + key + ".time");
                if (item != null && seller != null && buyer != null) {
                    saleHistory.add(new SaleHistoryItem(item, seller, buyer, time));
                }
            }
        }
    }

    private void saveSalesHistory() {
        salesConfig.set("sales", null);
        int id = 0;
        for (SaleHistoryItem item : saleHistory) {
            salesConfig.set("sales." + id + ".item", item.getItem());
            salesConfig.set("sales." + id + ".seller", item.getSeller());
            salesConfig.set("sales." + id + ".buyer", item.getBuyer());
            salesConfig.set("sales." + id + ".time", item.getTime());
            id++;
        }
        try {
            salesConfig.save(salesFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void startAuctionCleanup() {
        new BukkitRunnable() {
            @Override
            public void run() {
                auctionHouse.removeIf(item -> item.getExpire() < System.currentTimeMillis());
            }
        }.runTaskTimer(this, 20L * 60, 20L * 60); // alle 60 Sekunden
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Nur Spieler können diesen Befehl benutzen!");
            return true;
        }

        Player player = (Player) sender;

        switch (command.getName().toLowerCase()) {
            case "shop":
                openShopGUI(player);
                return true;
            case "pay":
                return handlePayCommand(player, args);
            case "ah":
                if (args.length == 0) {
                    auctionPage.put(player.getUniqueId(), 0);
                    openAuctionHouse(player);
                } else if (args[0].equalsIgnoreCase("sell") && args.length == 2) {
                    try {
                        double price = Double.parseDouble(args[1]);
                        ItemStack item = player.getInventory().getItemInMainHand();
                        if (item == null || item.getType() == Material.AIR) {
                            player.sendMessage("§cDu musst ein Item in der Hand halten.");
                            return true;
                        }
                        ItemStack toSell = item.clone();
                        toSell.setAmount(1);
                        item.setAmount(item.getAmount() - 1);
                        long expire = System.currentTimeMillis() + (24 * 60 * 60 * 1000); // 24h
                        auctionHouse.add(new AuctionItem(toSell, player.getName(), price, expire));
                        player.sendMessage("§aItem zum Auktionshaus hinzugefügt für " + price + "$.");
                    } catch (NumberFormatException e) {
                        player.sendMessage("§cUngültiger Preis.");
                    }
                } else {
                    player.sendMessage("§cBenutzung: /ah oder /ah sell <preis>");
                }
                return true;
            case "ahhistory":
                showSaleHistory(player);
                return true;
        }

        return false;
    }

    private boolean handlePayCommand(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage("§cBenutzung: /pay <Spieler> <Betrag>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            player.sendMessage("§cSpieler nicht gefunden oder offline.");
            return true;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cUngültiger Betrag.");
            return true;
        }

        if (amount <= 0) {
            player.sendMessage("§cBetrag muss größer als 0 sein.");
            return true;
        }

        if (economy.getBalance(player) < amount) {
            player.sendMessage("§cDu hast nicht genug Geld.");
            return true;
        }

        economy.withdrawPlayer(player, amount);
        economy.depositPlayer(target, amount);

        player.sendMessage("§aDu hast " + target.getName() + " " + amount + "$ gesendet.");
        target.sendMessage("§aDu hast " + amount + "$ von " + player.getName() + " erhalten.");

        return true;
    }

    private void openShopGUI(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, "Shop");

        for (Map.Entry<Material, Double> entry : itemPrices.entrySet()) {
            ItemStack item = new ItemStack(entry.getKey());
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§a" + entry.getKey().name());
                meta.setLore(Arrays.asList("§7Preis: " + entry.getValue() + "$"));
                item.setItemMeta(meta);
            }
            inv.addItem(item);
        }

        player.openInventory(inv);
    }

    private void openAuctionHouse(Player player) {
        int page = auctionPage.getOrDefault(player.getUniqueId(), 0);
        int startIndex = page * 27;
        int endIndex = Math.min(startIndex + 27, auctionHouse.size());

        Inventory inv = Bukkit.createInventory(null, 27, "Auktionshaus");

        for (int i = startIndex; i < endIndex; i++) {
            AuctionItem auctionItem = auctionHouse.get(i);
            ItemStack item = auctionItem.getItem();
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§a" + auctionItem.getSeller() + " verkauft " + item.getType().name());
                meta.setLore(Arrays.asList("§7Preis: " + auctionItem.getPrice() + "$", "§7Ablauf: " + auctionItem.getExpire()));
                item.setItemMeta(meta);
            }
            inv.addItem(item);
        }

        player.openInventory(inv);
    }

    private void showSaleHistory(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, "Verkaufsverlauf");

        for (SaleHistoryItem sale : saleHistory) {
            ItemStack item = sale.getItem();
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§aVerkauft an " + sale.getBuyer());
                meta.setLore(Arrays.asList("§7Verkäufer: " + sale.getSeller(), "§7Zeit: " + new Date(sale.getTime()).toString()));
                item.setItemMeta(meta);
            }
            inv.addItem(item);
        }

        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getClickedInventory() == null) return;
        if (event.getView().getTitle().equals("Auktionshaus")) {
            event.setCancelled(true);
            if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;
            Player player = (Player) event.getWhoClicked();
            AuctionItem clickedItem = auctionHouse.get(event.getSlot());
            if (economy.getBalance(player) >= clickedItem.getPrice()) {
                economy.withdrawPlayer(player, clickedItem.getPrice());
                economy.depositPlayer(Bukkit.getPlayer(clickedItem.getSeller()), clickedItem.getPrice());
                player.sendMessage("§aDu hast " + clickedItem.getItem().getType().name() + " für " + clickedItem.getPrice() + "$ gekauft.");
                saleHistory.add(new SaleHistoryItem(clickedItem.getItem(), clickedItem.getSeller(), player.getName(), System.currentTimeMillis()));
                auctionHouse.remove(clickedItem);
            } else {
                player.sendMessage("§cDu hast nicht genug Geld.");
            }
        }
    }

    public class AuctionItem {
        private final ItemStack item;
        private final String seller;
        private final double price;
        private final long expire;

        public AuctionItem(ItemStack item, String seller, double price, long expire) {
            this.item = item;
            this.seller = seller;
            this.price = price;
            this.expire = expire;
        }

        public ItemStack getItem() {
            return item;
        }

        public String getSeller() {
            return seller;
        }

        public double getPrice() {
            return price;
        }

        public long getExpire() {
            return expire;
        }
    }

    public class SaleHistoryItem {
        private final ItemStack item;
        private final String seller;
        private final String buyer;
        private final long time;

        public SaleHistoryItem(ItemStack item, String seller, String buyer, long time) {
            this.item = item;
            this.seller = seller;
            this.buyer = buyer;
            this.time = time;
        }

        public ItemStack getItem() {
            return item;
        }

        public String getSeller() {
            return seller;
        }

        public String getBuyer() {
            return buyer;
        }

        public long getTime() {
            return time;
        }
    }
}
