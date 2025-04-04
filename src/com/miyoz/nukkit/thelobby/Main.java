package com.miyoz.nukkit.thelobby;

import cn.nukkit.AdventureSettings;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.player.*;
import cn.nukkit.form.element.ElementSlider;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.handler.FormResponseHandler;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.level.Level;
import cn.nukkit.level.Sound;
import cn.nukkit.permission.PermissionAttachment;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.event.Listener;
import cn.nukkit.item.Item;
import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.utils.Config;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.CommandParameter;
import cn.nukkit.event.inventory.InventoryTransactionEvent;

import java.util.*;

import static cn.nukkit.Player.CREATIVE;

public class Main extends PluginBase implements Listener {

    private Config config;
    private Config itemConfig;
    private Config langConfig; // 新增语言配置文件
    private Map<Integer, String> itemCommands = new HashMap<>();
    private Map<Player, PermissionAttachment> permissionAttachments = new WeakHashMap<>();
    private List<LobbyItem> cachedLobbyItems = new ArrayList<>();
    private Set<String> lobbyWorlds;
    private boolean canFly;
    private int life;
    private int exp;
    private Set<String> toggledFlight = new HashSet<>(); // 新增声明和初始化
    private int inventoryDelay;


    @Override
    public void onEnable() {
        // 加载主配置
        this.saveDefaultConfig();
        this.config = new Config(getDataFolder() + "/config.yml", Config.YAML);
        checkAndUpdateConfig();

        // 加载物品配置
        saveResource("item.yml", false);
        itemConfig = new Config(getDataFolder() + "/item.yml", Config.YAML);

        // 加载语言文件
        String language = config.getString("language", "eng");
        String langFile = "Lang/" + language + ".yml";
        saveResource(langFile, false);
        langConfig = new Config(getDataFolder() + "/" + langFile, Config.YAML);

        // 初始化配置参数
        inventoryDelay = config.getInt("delay", 20) * 20;  // 转换为tick
        canFly = config.getBoolean("can_fly");
        lobbyWorlds = new HashSet<>();
        for (String world : config.getStringList("lobby_worlds")) {
            lobbyWorlds.add(world.toLowerCase());  // 统一小写存储
        }

        // 加载物品命令
        loadItemCommands();

        // 注册事件和命令
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getCommandMap().register("lobby", new LobbyCommand(this));

        getLogger().info("■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■");
        getLogger().info(" ");
        getLogger().info("TheLobby - A Lobby System For Nukkit");
        getLogger().info("Author: MiyozMC");
        getLogger().info("Download The Latest Version in:bbs.nukkit-mot.com");
        getLogger().info("Notice: This is a free plugin. If you paid money to get it, you've definitely been scammed!");
        getLogger().info(" ");
        getLogger().info("■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■");
    }

    private void checkAndUpdateConfig() {

        // 创建包含所有默认键值的配置模板
        Map<String, Object> defaultConfig = new LinkedHashMap<>();
        defaultConfig.put("delay", 20);
        defaultConfig.put("language", "chs");
        defaultConfig.put("lobby_worlds", Arrays.asList("lobby1", "lobby2"));
        defaultConfig.put("can_fly", true);
        defaultConfig.put("can_hurt", false);
        defaultConfig.put("can_pvp", false);
        defaultConfig.put("life", 20);
        defaultConfig.put("lock_life", true);
        defaultConfig.put("exp", 0);
        defaultConfig.put("build_player", Arrays.asList("player1", "player2"));

        // 检查并补充缺失的配置项
        boolean needsSave = false;
        for (Map.Entry<String, Object> entry : defaultConfig.entrySet()) {
            if (!config.exists(entry.getKey())) {
                config.set(entry.getKey(), entry.getValue());
                needsSave = true;
            }
        }

        if (needsSave) {
            config.save();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        resetPlayerPermissions(player);
    }

    private void resetPlayerPermissions(Player player) {
        // 取消飞行权限
        player.setAllowFlight(false);

        // 移除建造权限
        PermissionAttachment attachment = permissionAttachments.remove(player);
        if (attachment != null) {
            attachment.remove();
        }
    }

    @EventHandler
    public void onInventoryTransaction(InventoryTransactionEvent event) {
        Player player = event.getTransaction().getSource();
        if (lobbyWorlds.contains(player.getLevel().getName())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null || event.getTo().getLevel() == null) return;

        Player player = event.getPlayer();
        Level toLevel = event.getTo().getLevel();

        if (lobbyWorlds.contains(toLevel.getName().toLowerCase())) {
            // 延迟设置物品栏
            this.getServer().getScheduler().scheduleDelayedTask(this, () -> {
                applyLobbySettings(player);
                sendWelcomeEffects(player);
            }, inventoryDelay);
        } else {
            resetPlayerPermissions(player);
        }
    }

    private void applyLobbySettings(Player player) {
        player.setAllowFlight(canFly);
        player.setHealth(life);
        player.setMaxHealth(life);
        player.setExperience(exp);
        setLobbyInventory(player);
        if (config.getBoolean("lock_life", true)) {
            player.setHealth(life);
            player.setMaxHealth(life);
        }
    }

    private void sendWelcomeEffects(Player player) {
        player.sendTitle(langConfig.getString("messages.hub-title"), langConfig.getString("messages.hub-subtitle"));
        player.sendActionBar(langConfig.getString("messages.hub-actionbar"));
        player.sendMessage(langConfig.getString("messages.hub-message"));
        if (config.getBoolean("lighting-when-hub")) {
            player.getLevel().addSound(player.getLocation(), Sound.MOB_ENDERDRAGON_FLAP);
        }
    }

    private void loadItemCommands() {
        itemCommands.clear();
        cachedLobbyItems.clear();

        List<Map<String, Object>> items = (List<Map<String, Object>>) itemConfig.getList("item");
        if (items != null) {
            for (Map<String, Object> itemMap : items) {
                try {
                    String idStr = (String) itemMap.get("id");
                    String[] idParts = idStr.split(":");

                    if (idParts.length != 3) {
                        getLogger().warning("Invalid item format: " + idStr);
                        continue;
                    }

                    LobbyItem item = new LobbyItem();
                    item.itemId = Integer.parseInt(idParts[0].trim());
                    item.meta = Integer.parseInt(idParts[1].trim());
                    item.count = Integer.parseInt(idParts[2].trim());
                    item.position = (int) itemMap.get("position");
                    item.name = (String) itemMap.get("name");
                    item.command = (String) itemMap.get("command");

                    cachedLobbyItems.add(item);
                    itemCommands.put(item.position, item.command);
                } catch (Exception e) {
                    getLogger().error("Error loading item: " + e.getMessage());
                }
            }
        }
    }

    private void setLobbyInventory(Player player) {
        player.getInventory().clearAll();
        for (LobbyItem item : cachedLobbyItems) {
            Item stack = Item.get(item.itemId, item.meta, item.count);
            stack.setCustomName(item.name);
            player.getInventory().setItem(item.position, stack);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Item item = event.getItem();

        // 检查玩家是否在大厅世界
        if (lobbyWorlds.contains(player.getLevel().getName())) {
            // 阻止物品移动和丢弃
            event.setCancelled(true);
            // 获取物品位置
            int position = player.getInventory().getHeldItemIndex();

            // 检查是否有对应的命令
            if (itemCommands.containsKey(position)) {
                String command = itemCommands.get(position);
                player.getServer().dispatchCommand(player, command);
            }
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();

        if (lobbyWorlds.contains(player.getLevel().getName().toLowerCase())) {
            if (!config.getBoolean("can_hurt")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPvP(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player && event.getEntity() instanceof Player) {
            Player attacker = (Player) event.getDamager();
            if (lobbyWorlds.contains(attacker.getLevel().getName().toLowerCase())) {
                if (!config.getBoolean("can_pvp")) {
                    event.setCancelled(true);
                }
            }
        }
    }

    public void openConfigForm(Player player) {
        FormWindowCustom form = new FormWindowCustom(langConfig.getString("messages.config_title"));

        // 添加配置项组件（使用当前配置值）
        form.addElement(new ElementSlider(langConfig.getString("messages.delay_setting"), 0, 60, 1, config.getInt("delay")));
        form.addElement(new ElementToggle(langConfig.getString("messages.can_fly_setting"), config.getBoolean("can_fly")));
        form.addElement(new ElementToggle(langConfig.getString("messages.can_hurt_setting"), config.getBoolean("can_hurt")));
        form.addElement(new ElementToggle(langConfig.getString("messages.can_pvp_setting"), config.getBoolean("can_pvp")));
        form.addElement(new ElementSlider(langConfig.getString("messages.life_setting"), 1, 20, 1, config.getInt("life")));
        form.addElement(new ElementToggle(langConfig.getString("messages.lock_life_setting"), config.getBoolean("lock_life")));
        form.addElement(new ElementSlider(langConfig.getString("messages.exp_setting"), 0, 100, 1, config.getInt("exp")));

        form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
            if (form.wasClosed()) return;

            // 获取表单响应值
            int delay = (int) form.getResponse().getSliderResponse(0);
            boolean canFly = form.getResponse().getToggleResponse(1);
            boolean canHurt = form.getResponse().getToggleResponse(2);
            boolean canPvP = form.getResponse().getToggleResponse(3);
            int life = (int) form.getResponse().getSliderResponse(4);
            boolean lockLife = form.getResponse().getToggleResponse(5);
            int exp = (int) form.getResponse().getSliderResponse(6);

            // 更新配置
            config.set("delay", delay);
            config.set("can_fly", canFly);
            config.set("can_hurt", canHurt);
            config.set("can_pvp", canPvP);
            config.set("life", life);
            config.set("lock_life", lockLife);
            config.set("exp", exp);

            saveConfig();
            player.sendMessage(langConfig.getString("messages.config_saved"));

            // 重新加载受影响配置
            inventoryDelay = delay * 20;
            this.canFly = canFly;
            lobbyWorlds = new HashSet<>(config.getStringList("lobby_worlds"));
        }));

        player.showFormWindow(form);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        String worldName = player.getLevel().getName().toLowerCase();

        // 仅在大厅世界处理
        if (!lobbyWorlds.contains(worldName)) return;

        // 根据配置处理飞行
        if (canFly) {
            if (player.getGamemode() != CREATIVE) {
                event.setCancelled(true); // 取消原版飞行切换事件

                AdventureSettings settings = player.getAdventureSettings();
                boolean isFlying = settings.get(AdventureSettings.Type.FLYING);

                if (!player.getAllowFlight()) {
                    // 首次启用飞行
                    player.setAllowFlight(true);
                    settings.set(AdventureSettings.Type.FLYING, true);
                    settings.update();
                    player.sendActionBar(langConfig.getString("messages.fly_enabled"));
                } else {
                    // 切换飞行状态
                    settings.set(AdventureSettings.Type.FLYING, !isFlying);
                    settings.update();
                    player.sendActionBar(langConfig.getString(
                            "messages." + (!isFlying ? "fly_enabled" : "fly_disabled")
                    ));
                }
            }
        } else {
            // 如果配置禁用飞行
            event.setCancelled(true);
            player.setAllowFlight(false);
            player.getAdventureSettings().set(AdventureSettings.Type.FLYING, false);
            player.getAdventureSettings().update();
            player.sendActionBar(langConfig.getString("messages.fly_disabled"));
        }
    }

    public Config getConfig() {
        return config;
    }

    public void saveConfig() {
        config.save();
    }

    public class LobbyCommand extends Command {

        private Main plugin;

        public LobbyCommand(Main plugin) {
            super("lobby", "大厅系统指令", "/lobby help");
            this.plugin = plugin;
            this.setPermission("lobby.command");
            this.commandParameters.clear();
            this.commandParameters.put("default", new CommandParameter[]{
                    new CommandParameter("subcommand", CommandParameter.ARG_TYPE_STRING, false),
                    new CommandParameter("args", CommandParameter.ARG_TYPE_STRING, true)
            });

        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            if (!this.testPermission(sender)) {
                return false;
            }

            if (args.length == 0) {
                sender.sendMessage(plugin.langConfig.getString("messages.usage")); // 使用语言文件
                return false;
            }

            String subcommand = args[0].toLowerCase();
            switch (subcommand) {
                case "admin":
                    if (args.length < 3) {
                        sender.sendMessage(plugin.langConfig.getString("messages.admin_usage")); // 使用语言文件
                        return false;
                    }
                    String adminAction = args[1].toLowerCase();
                    String playerName = args[2];
                    List<String> buildPlayers = plugin.getConfig().getStringList("build_player");
                    switch (adminAction) {
                        case "add":
                            if (buildPlayers.contains(playerName)) {
                                sender.sendMessage(playerName + plugin.langConfig.getString("messages.already_builder")); // 使用语言文件
                            } else {
                                buildPlayers.add(playerName);
                                plugin.getConfig().set("build_player", buildPlayers);
                                plugin.saveConfig();
                                sender.sendMessage(playerName + plugin.langConfig.getString("messages.added_builder")); // 使用语言文件
                            }
                            plugin.lobbyWorlds = new HashSet<>(plugin.getConfig().getStringList("lobby_worlds"));
                            break;
                        case "remove":
                            if (buildPlayers.contains(playerName)) {
                                buildPlayers.remove(playerName);
                                plugin.getConfig().set("build_player", buildPlayers);
                                plugin.saveConfig();
                                sender.sendMessage(playerName + plugin.langConfig.getString("messages.removed_builder")); // 使用语言文件
                            } else {
                                sender.sendMessage(playerName + plugin.langConfig.getString("messages.not_builder")); // 使用语言文件
                            }
                            plugin.lobbyWorlds = new HashSet<>(plugin.getConfig().getStringList("lobby_worlds"));
                            break;
                        default:
                            sender.sendMessage(plugin.langConfig.getString("messages.admin_usage")); // 使用语言文件
                            return false;
                    }
                    break;
                case "build":
                    if (!(sender instanceof Player)) {
                        sender.sendMessage(plugin.langConfig.getString("messages.in_game_only")); // 使用语言文件
                        return false;
                    }
                    Player player = (Player) sender;
                    PermissionAttachment attachment = permissionAttachments.get(player);
                    if (attachment == null) {
                        attachment = player.addAttachment((Plugin) plugin);
                        permissionAttachments.put(player, attachment);
                    }
                    boolean hasBuildPermission = player.hasPermission("lobby.build");
                    attachment.setPermission("lobby.build", !hasBuildPermission);
                    player.sendMessage(plugin.langConfig.getString("messages.build_mode") + (!hasBuildPermission ? plugin.langConfig.getString("messages.enabled") : plugin.langConfig.getString("messages.disabled"))); // 使用语言文件
                    break;
                case "fly":
                    onPlayerToggleFlight((PlayerToggleFlightEvent) sender);
                    break;
                case "help":
                    sender.sendMessage(plugin.langConfig.getString("messages.admin_add")); // 使用语言文件
                    sender.sendMessage(plugin.langConfig.getString("messages.admin_remove")); // 使用语言文件
                    sender.sendMessage(plugin.langConfig.getString("messages.build_mode_cmd")); // 使用语言文件
                    sender.sendMessage(plugin.langConfig.getString("messages.help")); // 使用语言文件
                    sender.sendMessage(plugin.langConfig.getString("messages.world_add")); // 使用语言文件
                    sender.sendMessage(plugin.langConfig.getString("messages.world_remove")); // 使用语言文件
                    sender.sendMessage(plugin.langConfig.getString("messages.reload")); // 使用语言文件
                    sender.sendMessage(plugin.langConfig.getString("messages.config_cmd"));
                    break;
                case "config":
                    if (!(sender instanceof Player)) {
                        sender.sendMessage(plugin.langConfig.getString("messages.in_game_only"));
                        return false;
                    }
                    plugin.openConfigForm((Player) sender);
                    break;
                case "world":
                    if (args.length < 3) {
                        sender.sendMessage(plugin.langConfig.getString("messages.world_usage")); // 使用语言文件
                        return false;
                    }
                    String worldAction = args[1].toLowerCase();
                    String worldName = args[2];
                    List<String> lobbyWorlds = plugin.getConfig().getStringList("lobby_worlds");
                    switch (worldAction) {
                        case "add":
                            if (lobbyWorlds.contains(worldName)) {
                                sender.sendMessage(worldName + plugin.langConfig.getString("messages.already_lobby_world")); // 使用语言文件
                            } else {
                                lobbyWorlds.add(worldName);
                                plugin.getConfig().set("lobby_worlds", lobbyWorlds);
                                plugin.saveConfig();
                                sender.sendMessage(worldName + plugin.langConfig.getString("messages.added_lobby_world")); // 使用语言文件
                            }
                            break;
                        case "remove":
                            if (lobbyWorlds.contains(worldName)) {
                                lobbyWorlds.remove(worldName);
                                plugin.getConfig().set("lobby_worlds", lobbyWorlds);
                                plugin.saveConfig();
                                sender.sendMessage(worldName + plugin.langConfig.getString("messages.removed_lobby_world")); // 使用语言文件
                            } else {
                                sender.sendMessage(worldName + plugin.langConfig.getString("messages.not_lobby_world")); // 使用语言文件
                            }
                            break;
                        default:
                            sender.sendMessage(plugin.langConfig.getString("messages.world_usage")); // 使用语言文件
                            return false;
                    }
                    break;
                case "reload":
                    plugin.reloadConfig();
                    plugin.lobbyWorlds = new HashSet<>(plugin.getConfig().getStringList("lobby_worlds"));
                    plugin.loadItemCommands();
                    checkAndUpdateConfig();
                    break;
                default:
                    sender.sendMessage(plugin.langConfig.getString("messages.unknown_command")); // 使用语言文件
                    return false;
            }
            return true;
        }
    }

    private static class LobbyItem {
        int itemId;
        int meta;
        int count;
        int position;
        String name;
        String command;
    }
}
