package ru.refontstudio.restironevent;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class RestIronEvent extends JavaPlugin implements Listener {

    private FileConfiguration config;
    private File configFile;
    private File spawnPointsFile;
    private FileConfiguration spawnPointsConfig;
    private File damageDataFile;
    private FileConfiguration damageDataConfig;

    private boolean eventActive = false;
    private boolean eventScheduled = false;
    private int currentWave = 0;
    private BukkitTask eventTask;
    private BukkitTask scheduledEventTask;
    private BukkitTask announcementTask;
    private BukkitTask bossBarUpdateTask;
    private BukkitTask golemStayTask;
    private BukkitTask worldCheckTask;
    private BukkitTask topPlayersAnimationTask;
    private BukkitTask worldTimeTask;
    private BukkitTask respawnMobsTask;
    private BukkitTask removeTopBarsTask;

    private Location golemLocation;
    private IronGolem golem;
    private BossBar golemHealthBar;
    private BossBar infoBar;
    private BossBar topPlayersBar;
    private HashMap<UUID, Double> playerDamage = new HashMap<>();
    private List<Location> spawnPoints = new ArrayList<>();
    private List<Entity> spawnedMobs = new ArrayList<>();
    private Map<UUID, Boolean> previousFlightStates = new HashMap<>();
    private Map<UUID, Boolean> previousGodModeStates = new HashMap<>();

    private String eventWorldName;
    private World eventWorld;
    private boolean isShowingTopPlayers = false;
    private int topPlayerIndex = 0;
    private List<Map.Entry<UUID, Double>> sortedDamageList = new ArrayList<>();
    private long worldTime;
    private long lastRespawnCheck = 0;
    private boolean needRespawn = false;
    private boolean isEventEnded = false;
    private BukkitTask mobTargetCheckTask;

    // Константы для конфигурации
    private static final String CONFIG_EVENT_WORLD = "event.world";
    private static final String CONFIG_GOLEM_LOCATION = "event.golem_location";
    private static final String CONFIG_GOLEM_HEALTH = "event.golem_health";
    private static final String CONFIG_EVENT_DURATION = "event.duration";
    private static final String CONFIG_WAVE_DURATIONS = "event.wave_durations";
    private static final String CONFIG_WAVE_MOBS = "event.waves";
    private static final String CONFIG_REWARDS = "rewards";
    private static final String CONFIG_MESSAGES = "messages";
    private static final String CONFIG_SCHEDULED_DAYS = "schedule.days";
    private static final String CONFIG_SCHEDULED_TIME = "schedule.time";
    private static final String CONFIG_TIMEZONE = "schedule.timezone";
    private static final String CONFIG_DESIGN = "design";
    private static final String CONFIG_SOUNDS = "sounds";
    private static final String CONFIG_WORLD_TIME = "event.world_time";
    private static final String CONFIG_RESPAWN_INTERVAL = "event.respawn_interval";
    private static final String CONFIG_BOSSBAR_REMOVE_DELAY = "event.bossbar_remove_delay";
    private static final String CONFIG_PLAYER_SCALING = "event.player_scaling";
    private static final String CONFIG_PLAYER_MULTIPLIER = "event.player_multiplier";
    private static final String CONFIG_GRADUAL_SPAWN = "event.gradual_spawn";
    private static final String CONFIG_SPAWN_INTERVAL = "event.spawn_interval";
    private static final String CONFIG_DISABLE_EXPLOSIONS = "event.disable_explosions";

    @Override
    public void onEnable() {
        // Регистрация слушателя событий
        getServer().getPluginManager().registerEvents(this, this);

        // Загрузка или создание конфигурационных файлов
        loadConfig();
        loadSpawnPoints();
        loadDamageData();

        // Настройка планировщика для автоматического запуска ивента
        setupScheduledEvents();

        // Регистрация команд
        getCommand("golemevent").setExecutor(this);
        getCommand("golemeventspawn").setExecutor(this);

        // Инициализация BossBar для здоровья голема
        golemHealthBar = Bukkit.createBossBar(
                getMessage("bossbar_title"), // Используем getMessage вместо config.getString
                BarColor.RED,
                BarStyle.SOLID
        );

        // Инициализация BossBar для информации
        infoBar = Bukkit.createBossBar(
                ChatColor.translateAlternateColorCodes('&', "&fИнформация"),
                BarColor.BLUE,
                BarStyle.SOLID
        );

        // УДАЛЕНО: Инициализация BossBar для топ игроков
        // topPlayersBar = Bukkit.createBossBar(
        //     ChatColor.translateAlternateColorCodes('&', "&6Топ игроков"),
        //     BarColor.YELLOW,
        //     BarStyle.SOLID
        // );

        // Запуск задачи проверки мира
        startWorldCheckTask();

        getLogger().info("RestIronEvent успешно загружен!");
    }

    @Override
    public void onDisable() {
        // Отмена всех задач при выключении плагина
        if (eventTask != null) {
            eventTask.cancel();
        }
        if (scheduledEventTask != null) {
            scheduledEventTask.cancel();
        }
        if (announcementTask != null) {
            announcementTask.cancel();
        }
        if (bossBarUpdateTask != null) {
            bossBarUpdateTask.cancel();
        }
        if (mobTargetCheckTask != null) {
            mobTargetCheckTask.cancel();
        }
        if (golemStayTask != null) {
            golemStayTask.cancel();
        }
        if (worldCheckTask != null) {
            worldCheckTask.cancel();
        }
        if (topPlayersAnimationTask != null) {
            topPlayersAnimationTask.cancel();
        }
        if (worldTimeTask != null) {
            worldTimeTask.cancel();
        }
        if (respawnMobsTask != null) {
            respawnMobsTask.cancel();
        }
        if (removeTopBarsTask != null) {
            removeTopBarsTask.cancel();
        }

        // Удаление всех мобов и голема при выключении плагина
        if (eventWorld != null) {
            for (Entity entity : eventWorld.getEntities()) {
                if (entity instanceof Monster || (entity instanceof IronGolem && entity == golem)) {
                    entity.remove();
                }
            }
        }

        // Очистка списка мобов
        spawnedMobs.clear();

        // Восстановление состояний полета и годмода для всех игроков
        for (UUID uuid : previousFlightStates.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.setAllowFlight(previousFlightStates.get(uuid));
            }
        }

        for (UUID uuid : previousGodModeStates.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                setGodMode(player, previousGodModeStates.get(uuid));
            }
        }

        // Удаление BossBar
        if (golemHealthBar != null) {
            golemHealthBar.removeAll();
        }

        if (infoBar != null) {
            infoBar.removeAll();
        }

        if (topPlayersBar != null) {
            topPlayersBar.removeAll();
        }

        // Сохранение данных
        saveDamageData();

        getLogger().info("RestIronEvent успешно выключен!");
    }

    /**
     * Запуск задачи проверки мира
     */
    private void startWorldCheckTask() {
        worldCheckTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (eventWorld != null) {
                    // Установка сложности мира
                    if (eventWorld.getDifficulty() != Difficulty.NORMAL) {
                        eventWorld.setDifficulty(Difficulty.NORMAL);
                    }

                    // Проверка игроков в мире ивента
                    for (Player player : eventWorld.getPlayers()) {
                        // Отключение полета
                        if (player.getAllowFlight() && !player.hasPermission("golemevent.admin.fly")) {
                            if (!previousFlightStates.containsKey(player.getUniqueId())) {
                                previousFlightStates.put(player.getUniqueId(), player.getAllowFlight());
                            }
                            player.setAllowFlight(false);
                            player.setFlying(false);
                        }

                        // Отключение годмода
                        if (isGodModeEnabled(player) && !player.hasPermission("golemevent.admin.god")) {
                            if (!previousGodModeStates.containsKey(player.getUniqueId())) {
                                previousGodModeStates.put(player.getUniqueId(), isGodModeEnabled(player));
                            }
                            setGodMode(player, false);
                        }

                        // Отключение полета на элитрах
                        if (player.isGliding()) {
                            player.setGliding(false);
                            player.sendMessage(ChatColor.RED + "Полёт на элитрах запрещен в этом событии!");
                        }
                    }
                }
            }
        }.runTaskTimer(this, 20L, 20L); // Проверка каждую секунду
    }

    /**
     * Запуск задачи фиксации времени в мире
     */
    private void startWorldTimeTask() {
        // Отмена предыдущей задачи, если она была
        if (worldTimeTask != null) {
            worldTimeTask.cancel();
        }

        worldTimeTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (eventWorld != null && eventActive) {
                    // Фиксация времени в мире
                    eventWorld.setTime(worldTime);

                    // Установка ясной погоды
                    eventWorld.setStorm(false);
                    eventWorld.setThundering(false);
                }
            }
        }.runTaskTimer(this, 0L, 100L); // Каждые 5 секунд
    }

    /**
     * Проверка, включен ли режим бога у игрока через PlaceholderAPI
     */
    private boolean isGodModeEnabled(Player player) {
        // Используем PlaceholderAPI для получения значения
        String godMode = parsePlaceholder(player, "%essentials_godmode%");
        return godMode != null && godMode.equalsIgnoreCase("yes");
    }

    /**
     * Установка режима бога для игрока через консольную команду
     */
    private void setGodMode(Player player, boolean enabled) {
        // Выполняем команду от имени консоли с правильным форматом
        String command = "god " + player.getName() + (enabled ? " on " : " off ") ;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    /**
     * Обработка плейсхолдеров через PlaceholderAPI
     */
    private String parsePlaceholder(Player player, String placeholder) {
        Plugin placeholderAPI = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (placeholderAPI != null && placeholderAPI.isEnabled()) {
            try {
                // Использование PlaceholderAPI для обработки плейсхолдера
                Class<?> placeholderClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                return (String) placeholderClass.getMethod("setPlaceholders", Player.class, String.class)
                        .invoke(null, player, placeholder);
            } catch (Exception e) {
                getLogger().warning("Ошибка при обработке плейсхолдера: " + e.getMessage());
            }
        }
        return placeholder;
    }

    /**
     * Загрузка основной конфигурации плагина
     */
    private void loadConfig() {
        // Сохранение дефолтной конфигурации, если её нет
        saveDefaultConfig();
        config = getConfig();
        configFile = new File(getDataFolder(), "config.yml");

        // Установка значений по умолчанию, если они отсутствуют
        if (!config.contains(CONFIG_EVENT_WORLD)) {
            config.set(CONFIG_EVENT_WORLD, "iventgolem");
        }

        if (!config.contains(CONFIG_GOLEM_HEALTH)) {
            config.set(CONFIG_GOLEM_HEALTH, 1000.0);
        }

        if (!config.contains(CONFIG_EVENT_DURATION)) {
            config.set(CONFIG_EVENT_DURATION, 600); // 10 минут в секундах
        }

        if (!config.contains(CONFIG_WAVE_DURATIONS)) {
            List<Integer> durations = new ArrayList<>();
            durations.add(180); // 3 минуты для первой волны
            durations.add(180); // 3 минуты для второй волны
            durations.add(240); // 4 минуты для третьей волны
            config.set(CONFIG_WAVE_DURATIONS, durations);
        }

        // Установка времени мира по умолчанию (ночь)
        if (!config.contains(CONFIG_WORLD_TIME)) {
            config.set(CONFIG_WORLD_TIME, 14000);
        }

        // Установка интервала респавна мобов по умолчанию
        if (!config.contains(CONFIG_RESPAWN_INTERVAL)) {
            config.set(CONFIG_RESPAWN_INTERVAL, 30000); // 30 секунд в миллисекундах
        }

        // Установка задержки удаления BossBar после ивента
        if (!config.contains(CONFIG_BOSSBAR_REMOVE_DELAY)) {
            config.set(CONFIG_BOSSBAR_REMOVE_DELAY, 30); // 30 секунд
        }

        // Настройка масштабирования количества мобов от количества игроков
        if (!config.contains(CONFIG_PLAYER_SCALING)) {
            config.set(CONFIG_PLAYER_SCALING, true); // Включено по умолчанию
        }

        // Множитель для масштабирования количества мобов
        if (!config.contains(CONFIG_PLAYER_MULTIPLIER)) {
            config.set(CONFIG_PLAYER_MULTIPLIER, 1.3); // 1.3 моба на каждого игрока
        }

        // Настройка постепенного спавна мобов
        if (!config.contains(CONFIG_GRADUAL_SPAWN)) {
            config.set(CONFIG_GRADUAL_SPAWN, true); // Включено по умолчанию
        }

        // Интервал между спавном мобов
        if (!config.contains(CONFIG_SPAWN_INTERVAL)) {
            config.set(CONFIG_SPAWN_INTERVAL, 1.5); // 1.5 секунды
        }

        // Отключение взрывов от криперов
        if (!config.contains(CONFIG_DISABLE_EXPLOSIONS)) {
            config.set(CONFIG_DISABLE_EXPLOSIONS, true); // Включено по умолчанию
        }

        // Настройка волн мобов по умолчанию
        if (!config.contains(CONFIG_WAVE_MOBS)) {
            setupDefaultWaves();
        }

        // Настройка наград по умолчанию
        if (!config.contains(CONFIG_REWARDS)) {
            setupDefaultRewards();
        }

        // Настройка сообщений по умолчанию
        if (!config.contains(CONFIG_MESSAGES)) {
            setupDefaultMessages();
        }

        // Настройка расписания по умолчанию
        if (!config.contains(CONFIG_SCHEDULED_DAYS)) {
            List<String> days = new ArrayList<>();
            days.add("TUESDAY");
            days.add("THURSDAY");
            days.add("SATURDAY");
            config.set(CONFIG_SCHEDULED_DAYS, days);
        }

        if (!config.contains(CONFIG_SCHEDULED_TIME)) {
            config.set(CONFIG_SCHEDULED_TIME, "16:30");
        }

        if (!config.contains(CONFIG_TIMEZONE)) {
            config.set(CONFIG_TIMEZONE, "Europe/Moscow");
        }

        // Настройка дизайна
        if (!config.contains(CONFIG_DESIGN)) {
            setupDefaultDesign();
        }

        // Настройка звуков
        if (!config.contains(CONFIG_SOUNDS)) {
            setupDefaultSounds();
        }

        saveConfig();

        // Получение времени мира
        worldTime = config.getLong(CONFIG_WORLD_TIME, 14000);

        // Получение мира ивента
        eventWorldName = config.getString(CONFIG_EVENT_WORLD);
        eventWorld = Bukkit.getWorld(eventWorldName);
        if (eventWorld == null) {
            getLogger().warning("Мир '" + eventWorldName + "' не найден! Используется мир по умолчанию.");
            eventWorld = Bukkit.getWorlds().get(0);
            eventWorldName = eventWorld.getName();
        }

        // Установка сложности мира ивента
        if (eventWorld != null) {
            eventWorld.setDifficulty(Difficulty.NORMAL);
        }
    }

    /**
     * Настройка звуков по умолчанию
     */
    private void setupDefaultSounds() {
        ConfigurationSection sounds = config.createSection(CONFIG_SOUNDS);

        // Звук при запуске ивента
        sounds.set("event_start", "ENTITY_ENDER_DRAGON_GROWL");

        // Звук при начале волны
        sounds.set("wave_start", "ENTITY_WITHER_SPAWN");

        // Звук при смерти голема
        sounds.set("golem_death", "ENTITY_WITHER_DEATH");

        // Звук при успешном завершении ивента
        sounds.set("event_success", "ENTITY_PLAYER_LEVELUP");

        // Звук при выдаче наград
        sounds.set("reward", "ENTITY_EXPERIENCE_ORB_PICKUP");

        // Звук при спавне зомби
        sounds.set("spawn_zombie", "ENTITY_ZOMBIE_AMBIENT");

        // Звук при спавне скелета
        sounds.set("spawn_skeleton", "ENTITY_SKELETON_AMBIENT");

        // Звук при спавне паука
        sounds.set("spawn_spider", "ENTITY_SPIDER_AMBIENT");

        // Звук при спавне крипера
        sounds.set("spawn_creeper", "ENTITY_CREEPER_PRIMED");

        // Звук при спавне других мобов
        sounds.set("spawn_generic", "ENTITY_HOSTILE_HURT");
    }

    /**
     * Настройка дизайна по умолчанию
     */
    private void setupDefaultDesign() {
        ConfigurationSection design = config.createSection(CONFIG_DESIGN);

        // Заголовок для Сердце Голема
        design.set("title", "&cСердце Голема");

        // Волна
        design.set("wave", "&bВолна");

        // Здоровье голема
        design.set("health", "&aЗдоровье");

        // Урон
        design.set("damage", "&cУрон");

        // Позиция
        design.set("position", "&eПозиция");

        // Ивент
        design.set("event", "&d[Ивент]");

        // Кнопки
        design.set("button", "&6");

        // Топ игроков
        design.set("top_players", "&6Топ игроков");

        // Награда
        design.set("reward", "&eНаграда");
    }

    // Добавь этот метод в класс
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();

        // Проверяем, находится ли игрок в мире ивента
        if (!player.getWorld().getName().equals(eventWorldName)) return;

        // Проверяем, связано ли действие с элитрами
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        if ((current != null && current.getType() == Material.ELYTRA) ||
                (cursor != null && cursor.getType() == Material.ELYTRA)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "Элитры запрещены в этом событии!");
        }
    }

    /**
     * Настройка волн мобов по умолчанию
     */
    private void setupDefaultWaves() {
        // Волна 1 - мало мобов без особых характеристик
        ConfigurationSection wave1 = config.createSection(CONFIG_WAVE_MOBS + ".1");
        wave1.set("zombie.count", 10);
        wave1.set("zombie.health", 20.0);
        wave1.set("zombie.damage", 3.0);
        wave1.set("zombie.speed", 0.2);

        wave1.set("skeleton.count", 8);
        wave1.set("skeleton.health", 20.0);
        wave1.set("skeleton.damage", 2.0);
        wave1.set("skeleton.speed", 0.2);

        // Волна 2 - среднее количество мобов, некоторые быстрее
        ConfigurationSection wave2 = config.createSection(CONFIG_WAVE_MOBS + ".2");
        wave2.set("zombie.count", 15);
        wave2.set("zombie.health", 25.0);
        wave2.set("zombie.damage", 4.0);
        wave2.set("zombie.speed", 0.3);

        wave2.set("skeleton.count", 12);
        wave2.set("skeleton.health", 25.0);
        wave2.set("skeleton.damage", 3.0);
        wave2.set("skeleton.speed", 0.3);

        wave2.set("spider.count", 8);
        wave2.set("spider.health", 20.0);
        wave2.set("spider.damage", 3.0);
        wave2.set("spider.speed", 0.4);

        // Волна 3 - много мобов с разными характеристиками
        ConfigurationSection wave3 = config.createSection(CONFIG_WAVE_MOBS + ".3");
        wave3.set("zombie.count", 20);
        wave3.set("zombie.health", 30.0);
        wave3.set("zombie.damage", 5.0);
        wave3.set("zombie.speed", 0.35);

        wave3.set("skeleton.count", 15);
        wave3.set("skeleton.health", 25.0);
        wave3.set("skeleton.damage", 4.0);
        wave3.set("skeleton.speed", 0.3);

        wave3.set("spider.count", 12);
        wave3.set("spider.health", 25.0);
        wave3.set("spider.damage", 4.0);
        wave3.set("spider.speed", 0.45);

        wave3.set("creeper.count", 5);
        wave3.set("creeper.health", 20.0);
        wave3.set("creeper.damage", 0.0); // Урон происходит от взрыва
        wave3.set("creeper.speed", 0.3);
    }

    /**
     * Настройка наград по умолчанию
     */
    private void setupDefaultRewards() {
        ConfigurationSection rewards = config.createSection(CONFIG_REWARDS);

        // Топ-3 игрока получают рубины
        rewards.set("top_players.1.rubies", 50);
        rewards.set("top_players.2.rubies", 30);
        rewards.set("top_players.3.rubies", 15);

        // Предметы, которые разбрасывает голем
        List<String> items = new ArrayList<>();
        items.add("DIAMOND:10-20");
        items.add("GOLD_INGOT:20-30");
        items.add("IRON_INGOT:30-50");
        items.add("EMERALD:5-15");
        items.add("NETHERITE_INGOT:1-3");
        rewards.set("items", items);
    }

    /**
     * Настройка сообщений по умолчанию
     */
    private void setupDefaultMessages() {
        ConfigurationSection messages = config.createSection(CONFIG_MESSAGES);

        messages.set("event_start", "%event% &fИвент %title% &fначнется через 5 минут! Телепортация через &e{time} &fсекунд.");
        messages.set("event_teleport", "%event% &fВы были телепортированы на ивент %title%&f!");
        messages.set("event_wave_start", "%event% &f%wave% &e{wave} &fначалась! Защищайте голема!");
        messages.set("event_wave_end", "%event% &f%wave% &e{wave} &fзавершена!");
        messages.set("event_end_success", "%event% &fИвент %title% &fуспешно завершен! Голем выжил!");
        messages.set("event_end_fail", "%event% &fИвент %title% &fпроигран! Голем погиб!");
        messages.set("event_reward", "%event% &fВы получили награду: &e{reward}");
        messages.set("event_top_players", "%event% &fТоп игроков по урону:");
        messages.set("event_top_player_format", "&e{position}. &f{player} - %damage% &f{damage} &fурона");
        messages.set("bossbar_title", "%title%");
        messages.set("bossbar_info", "%wave%: &e{wave}/3 | %health%: &a{health}% | %damage%: &c{damage} | %position%: &e{position}");
        messages.set("bossbar_top_player", "&e{position}. &f{player} - %damage% &f{damage} &fурона");
        messages.set("command_no_permission", "&c[Ошибка] &fУ вас нет прав для выполнения этой команды!");
        messages.set("command_event_started", "%event% &fИвент %title% &fзапущен!");
        messages.set("command_event_stopped", "%event% &fИвент %title% &fостановлен!");
        messages.set("command_spawn_added", "%event% &fТочка спавна добавлена!");
        messages.set("command_spawn_removed", "%event% &fБлижайшая точка спавна удалена!");
        messages.set("command_golem_set", "%event% &fПозиция голема установлена!");
    }

    /**
     * Получение сообщения с подстановкой градиентов
     */
    private String getMessage(String path, Map<String, String> placeholders) {
        String message = config.getString(CONFIG_MESSAGES + "." + path, "");

        // Подстановка градиентов из дизайна
        message = message.replace("%title%", config.getString(CONFIG_DESIGN + ".title", ""));
        message = message.replace("%wave%", config.getString(CONFIG_DESIGN + ".wave", ""));
        message = message.replace("%health%", config.getString(CONFIG_DESIGN + ".health", ""));
        message = message.replace("%damage%", config.getString(CONFIG_DESIGN + ".damage", ""));
        message = message.replace("%position%", config.getString(CONFIG_DESIGN + ".position", ""));
        message = message.replace("%event%", config.getString(CONFIG_DESIGN + ".event", ""));

        // Подстановка пользовательских плейсхолдеров
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                message = message.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }

        return ChatColor.translateAlternateColorCodes('&', message);
    }

    /**
     * Получение сообщения с подстановкой градиентов
     */
    private String getMessage(String path) {
        return getMessage(path, null);
    }

    /**
     * Отправка сообщения всем игрокам в мире ивента
     */
    private void sendEventWorldMessage(String message) {
        if (eventWorld == null) return;

        for (Player player : eventWorld.getPlayers()) {
            player.sendMessage(message);
        }
    }

    /**
     * Воспроизведение звука для всех игроков в мире ивента
     */
    private void playSound(String soundPath) {
        String soundName = config.getString(CONFIG_SOUNDS + "." + soundPath);
        if (soundName == null || soundName.isEmpty()) {
            return;
        }

        try {
            Sound sound = Sound.valueOf(soundName);
            for (Player player : eventWorld.getPlayers()) {
                player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
            }
        } catch (IllegalArgumentException e) {
            getLogger().warning("Неверное название звука в конфигурации: " + soundName);
        }
    }

    /**
     * Воспроизведение звука спавна моба в указанной локации
     */
    private void playMobSpawnSound(EntityType entityType, Location location) {
        String soundPath;

        // Выбор звука в зависимости от типа моба
        switch (entityType) {
            case ZOMBIE:
                soundPath = "spawn_zombie";
                break;
            case SKELETON:
                soundPath = "spawn_skeleton";
                break;
            case SPIDER:
                soundPath = "spawn_spider";
                break;
            case CREEPER:
                soundPath = "spawn_creeper";
                break;
            default:
                soundPath = "spawn_generic";
                break;
        }

        // Получение настройки звука из конфига
        String soundName = config.getString(CONFIG_SOUNDS + "." + soundPath);
        if (soundName == null || soundName.isEmpty()) {
            return;
        }

        try {
            Sound sound = Sound.valueOf(soundName);
            // Воспроизведение звука для всех игроков в радиусе 20 блоков от точки спавна
            for (Player player : eventWorld.getPlayers()) {
                if (player.getLocation().distance(location) <= 20) {
                    player.playSound(location, sound, 0.5f, 1.0f);
                }
            }
        } catch (IllegalArgumentException e) {
            getLogger().warning("Неверное название звука в конфигурации: " + soundName);
        }
    }

    /**
     * Загрузка файла с точками спавна
     */
    private void loadSpawnPoints() {
        spawnPointsFile = new File(getDataFolder(), "spawn_points.yml");
        if (!spawnPointsFile.exists()) {
            try {
                spawnPointsFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        spawnPointsConfig = YamlConfiguration.loadConfiguration(spawnPointsFile);

        // Загрузка точек спавна
        spawnPoints.clear(); // Очищаем список перед загрузкой

        if (spawnPointsConfig.contains("spawn_points")) {
            List<String> pointsStr = spawnPointsConfig.getStringList("spawn_points");
            getLogger().info("Загружаем " + pointsStr.size() + " точек спавна");

            for (String pointStr : pointsStr) {
                String[] parts = pointStr.split(",");
                if (parts.length >= 4) {
                    String world = parts[0];
                    double x = Double.parseDouble(parts[1]);
                    double y = Double.parseDouble(parts[2]);
                    double z = Double.parseDouble(parts[3]);

                    World w = Bukkit.getWorld(world);
                    if (w != null) {
                        Location loc = new Location(w, x, y, z);
                        spawnPoints.add(loc);
                        getLogger().info("Загружена точка спавна: " + world + " " + x + " " + y + " " + z);
                    } else {
                        getLogger().warning("Мир " + world + " не найден при загрузке точки спавна");
                    }
                }
            }
        } else {
            getLogger().warning("Точки спавна не найдены в конфигурации");
        }

        getLogger().info("Всего загружено точек спавна: " + spawnPoints.size());

        // Загрузка позиции голема
        if (spawnPointsConfig.contains("golem_location")) {
            String golemLocStr = spawnPointsConfig.getString("golem_location");
            if (golemLocStr != null) {
                String[] parts = golemLocStr.split(",");
                if (parts.length >= 4) {
                    String world = parts[0];
                    double x = Double.parseDouble(parts[1]);
                    double y = Double.parseDouble(parts[2]);
                    double z = Double.parseDouble(parts[3]);

                    World w = Bukkit.getWorld(world);
                    if (w != null) {
                        golemLocation = new Location(w, x, y, z);
                        config.set(CONFIG_GOLEM_LOCATION, golemLocStr);
                        saveConfig();
                        getLogger().info("Загружена позиция голема: " + world + " " + x + " " + y + " " + z);
                    } else {
                        getLogger().warning("Мир " + world + " не найден при загрузке позиции голема");
                    }
                }
            }
        } else {
            getLogger().warning("Позиция голема не найдена в конфигурации");
        }
    }

    /**
     * Сохранение точек спавна в файл
     */
    private void saveSpawnPoints() {
        List<String> pointsStr = new ArrayList<>();
        for (Location loc : spawnPoints) {
            pointsStr.add(String.format("%s,%f,%f,%f",
                    loc.getWorld().getName(),
                    loc.getX(),
                    loc.getY(),
                    loc.getZ()));
        }

        spawnPointsConfig.set("spawn_points", pointsStr);

        if (golemLocation != null) {
            spawnPointsConfig.set("golem_location", String.format("%s,%f,%f,%f",
                    golemLocation.getWorld().getName(),
                    golemLocation.getX(),
                    golemLocation.getY(),
                    golemLocation.getZ()));
        }

        try {
            spawnPointsConfig.save(spawnPointsFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Загрузка данных об уроне игроков
     */
    private void loadDamageData() {
        damageDataFile = new File(getDataFolder(), "damage_data.yml");
        if (!damageDataFile.exists()) {
            try {
                damageDataFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        damageDataConfig = YamlConfiguration.loadConfiguration(damageDataFile);

        // Загрузка данных об уроне
        if (damageDataConfig.contains("player_damage")) {
            ConfigurationSection damageSection = damageDataConfig.getConfigurationSection("player_damage");
            if (damageSection != null) {
                for (String uuidStr : damageSection.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        double damage = damageSection.getDouble(uuidStr);
                        playerDamage.put(uuid, damage);
                    } catch (IllegalArgumentException e) {
                        getLogger().warning("Неверный формат UUID в damage_data.yml: " + uuidStr);
                    }
                }
            }
        }
    }

    /**
     * Сохранение данных об уроне игроков
     */
    private void saveDamageData() {
        ConfigurationSection damageSection = damageDataConfig.createSection("player_damage");

        for (Map.Entry<UUID, Double> entry : playerDamage.entrySet()) {
            damageSection.set(entry.getKey().toString(), entry.getValue());
        }

        try {
            damageDataConfig.save(damageDataFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Настройка запланированных ивентов
     */
    private void setupScheduledEvents() {
        if (scheduledEventTask != null) {
            scheduledEventTask.cancel();
        }

        scheduledEventTask = new BukkitRunnable() {
            @Override
            public void run() {
                checkScheduledEvents();
            }
        }.runTaskTimer(this, 20L, 20L * 60); // Проверка каждую минуту
    }

    /**
     * Проверка запланированных ивентов
     */
    private void checkScheduledEvents() {
        if (eventActive || eventScheduled) {
            return;
        }

        ZoneId timezone = ZoneId.of(config.getString(CONFIG_TIMEZONE, "Europe/Moscow"));
        ZonedDateTime now = ZonedDateTime.now(timezone);

        List<String> scheduledDays = config.getStringList(CONFIG_SCHEDULED_DAYS);
        String scheduledTime = config.getString(CONFIG_SCHEDULED_TIME, "16:30");

        // Проверка, является ли сегодня днем ивента
        DayOfWeek today = now.getDayOfWeek();
        if (scheduledDays.contains(today.toString())) {
            // Парсинг запланированного времени
            DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");
            LocalDateTime scheduledDateTime = LocalDateTime.parse(now.toLocalDate() + "T" + scheduledTime + ":00");
            ZonedDateTime scheduledZonedDateTime = ZonedDateTime.of(scheduledDateTime, timezone);

            // Расчет разницы во времени
            long minutesToEvent = ChronoUnit.MINUTES.between(now, scheduledZonedDateTime);

            // Если до ивента осталось 5 минут, запускаем анонс
            if (minutesToEvent <= 5 && minutesToEvent > 0 && !eventScheduled) {
                announceEvent();
            }
        }
    }

    /**
     * Анонс предстоящего ивента
     */
    private void announceEvent() {
        eventScheduled = true;

        // Отмена предыдущего анонса, если он был
        if (announcementTask != null) {
            announcementTask.cancel();
        }

        // Объявление о предстоящем ивенте
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("time", "300");

        Bukkit.broadcastMessage(getMessage("event_start", placeholders));

        // Запуск обратного отсчета и телепортации
        announcementTask = new BukkitRunnable() {
            int timeLeft = 300; // 5 минут в секундах

            @Override
            public void run() {
                timeLeft--;

                if (timeLeft <= 0) {
                    // Запуск ивента
                    startEvent();
                    eventScheduled = false;
                    this.cancel();
                } else if (timeLeft % 60 == 0 || (timeLeft <= 10 && timeLeft > 0)) {
                    // Объявление оставшегося времени
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("time", String.valueOf(timeLeft));

                    Bukkit.broadcastMessage(getMessage("event_start", placeholders));
                }
            }
        }.runTaskTimer(this, 20L, 20L); // Каждую секунду
    }

    /**
     * Запуск ивента
     */
    private void startEvent() {
        if (eventActive) {
            return;
        }

        // Проверка, достаточно ли настроек для запуска ивента
        if (spawnPoints.isEmpty()) {
            getLogger().warning("Не настроены точки спавна для ивента!");
            return;
        }

        if (golemLocation == null) {
            getLogger().warning("Не настроена позиция голема для ивента!");
            return;
        }

        eventActive = true;
        currentWave = 0;
        playerDamage.clear();
        lastRespawnCheck = System.currentTimeMillis();
        needRespawn = false;
        isEventEnded = false;

        // Запуск задачи проверки мобов
        startMobTargetCheckTask();

        // Установка сложности мира
        eventWorld.setDifficulty(Difficulty.NORMAL);

        // Установка фиксированного времени в мире
        eventWorld.setTime(worldTime);
        eventWorld.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);

        // Запуск задачи поддержки фиксированного времени
        startWorldTimeTask();

        // Запуск задачи проверки необходимости респавна мобов
        startRespawnMobsTask();

        // Создание голема
        golem = (IronGolem) eventWorld.spawnEntity(golemLocation, EntityType.IRON_GOLEM);
        golem.setCustomName(getMessage("bossbar_title"));
        golem.setCustomNameVisible(true);

        // Установка здоровья голема
        double golemHealth = config.getDouble(CONFIG_GOLEM_HEALTH, 1000.0);
        AttributeInstance healthAttribute = golem.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (healthAttribute != null) {
            healthAttribute.setBaseValue(golemHealth);
            golem.setHealth(golemHealth);
        }

        // Отключение AI голема, чтобы он стоял на месте
        golem.setAI(false);

        // Запуск задачи для удержания голема на месте
        startGolemStayTask();

        // Настройка BossBar
        golemHealthBar.setTitle(getMessage("bossbar_title"));
        golemHealthBar.setProgress(1.0);

        infoBar.setTitle(ChatColor.translateAlternateColorCodes('&', "&fЗагрузка информации..."));
        infoBar.setProgress(1.0);

        for (Player player : eventWorld.getPlayers()) {
            golemHealthBar.addPlayer(player);
            infoBar.addPlayer(player);
        }

        // Телепортация игроков
        teleportPlayersToEvent();

        // Воспроизведение звука начала ивента
        playSound("event_start");

        // Запуск таймера ивента
        eventTask = new BukkitRunnable() {
            int timeLeft = config.getInt(CONFIG_EVENT_DURATION, 600); // 10 минут в секундах
            int waveTimeLeft = 0; // Время до конца текущей волны
            int currentWaveIndex = 0; // Индекс текущей волны

            @Override
            public void run() {
                if (!eventActive || golem == null || golem.isDead()) {
                    endEvent(false);
                    this.cancel();
                    return;
                }

                timeLeft--;
                waveTimeLeft--;

                if (timeLeft <= 0) {
                    endEvent(true);
                    this.cancel();
                    return;
                }

                // Обновление BossBar
                golemHealthBar.setProgress(golem.getHealth() / golem.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue());

                // Обновляем заголовок BossBar с временем до конца волны
                updateGolemBossBarTitle(waveTimeLeft);

                // Управление волнами
                int newWaveIndex = manageWaves(timeLeft);

                // Если началась новая волна, обновляем счетчик времени волны
                if (newWaveIndex != currentWaveIndex) {
                    currentWaveIndex = newWaveIndex;

                    // Получаем длительность новой волны
                    List<Integer> waveDurations = config.getIntegerList(CONFIG_WAVE_DURATIONS);
                    if (currentWaveIndex > 0 && currentWaveIndex <= waveDurations.size()) {
                        waveTimeLeft = waveDurations.get(currentWaveIndex - 1);
                    }
                }

                // Проверка, все ли мобы убиты
                boolean allMobsDead = true;
                for (Entity entity : spawnedMobs) {
                    if (entity != null && !entity.isDead()) {
                        allMobsDead = false;
                        break;
                    }
                }

                // Если все мобы убиты, отмечаем необходимость респавна
                if (allMobsDead && !needRespawn) {
                    needRespawn = true;
                }
            }
        }.runTaskTimer(this, 20L, 20L); // Каждую секунду

        // Запуск обновления BossBar
        startBossBarUpdates();
    }

    /**
     * Запуск задачи проверки и корректировки целей мобов
     */
    private void startMobTargetCheckTask() {
        // Отмена предыдущей задачи, если она была
        if (mobTargetCheckTask != null) {
            mobTargetCheckTask.cancel();
        }

        mobTargetCheckTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!eventActive || golem == null || golem.isDead()) {
                    this.cancel();
                    return;
                }

                // Проверяем всех мобов из списка спавненных
                for (Entity entity : new ArrayList<>(spawnedMobs)) {
                    if (entity == null || entity.isDead()) {
                        spawnedMobs.remove(entity);
                        continue;
                    }

                    if (entity instanceof Mob) {
                        Mob mob = (Mob) entity;

                        // Если у моба нет цели или цель не голем
                        if (mob.getTarget() == null || mob.getTarget() != golem) {
                            // Принудительно устанавливаем цель на голема
                            mob.setTarget(golem);
                        }

                        // УДАЛЕНО: Телепортация мобов, которые слишком далеко
                        // Теперь мобы будут просто идти к голему сами
                    }
                }
            }
        }.runTaskTimer(this, 40L, 40L); // проверка каждые 2 секунды
    }

    /**
     * Запуск задачи для удержания голема на месте
     */
    private void startGolemStayTask() {
        if (golemStayTask != null) {
            golemStayTask.cancel();
        }

        golemStayTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!eventActive || golem == null || golem.isDead()) {
                    this.cancel();
                    return;
                }

                // Телепортация голема обратно на его позицию, если он сдвинулся
                if (golem.getLocation().distanceSquared(golemLocation) > 0.1) {
                    golem.teleport(golemLocation);
                }

                // Сброс скорости голема
                golem.setVelocity(new Vector(0, 0, 0));
            }
        }.runTaskTimer(this, 5L, 5L); // Проверка каждую 1/4 секунды
    }

    /**
     * Запуск обновления BossBar с информацией
     */
    private void startBossBarUpdates() {
        if (bossBarUpdateTask != null) {
            bossBarUpdateTask.cancel();
        }

        bossBarUpdateTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!eventActive && isEventEnded) {
                    this.cancel();
                    return;
                }

                // Обновляем информацию на BossBar для всех игроков в мире ивента
                for (Player player : eventWorld.getPlayers()) {
                    updatePlayerInfo(player);
                }
            }
        }.runTaskTimer(this, 10L, 10L); // Обновление каждые полсекунды
    }

    /**
     * Запуск анимации топа игроков в BossBar
     */
    private void startTopPlayersAnimation() {
        if (topPlayersAnimationTask != null) {
            topPlayersAnimationTask.cancel();
        }

        // Сортировка игроков по урону
        sortedDamageList = playerDamage.entrySet()
                .stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .collect(Collectors.toList());

        if (sortedDamageList.isEmpty()) {
            return;
        }

        // Показываем BossBar всем игрокам в мире ивента
        topPlayersBar.setTitle(ChatColor.translateAlternateColorCodes('&', config.getString(CONFIG_DESIGN + ".top_players", "&6Топ игроков")));
        topPlayersBar.setProgress(1.0);
        for (Player player : eventWorld.getPlayers()) {
            topPlayersBar.addPlayer(player);
        }

        isShowingTopPlayers = true;
        topPlayerIndex = 0;

        // Создаем строку с топом игроков для прокрутки
        final StringBuilder fullTopPlayersInfo = new StringBuilder();
        int count = Math.min(10, sortedDamageList.size());
        for (int i = 0; i < count; i++) {
            Map.Entry<UUID, Double> entry = sortedDamageList.get(i);
            String playerName = Bukkit.getOfflinePlayer(entry.getKey()).getName();
            if (playerName == null) playerName = "Неизвестный";

            fullTopPlayersInfo.append("&e").append(i + 1).append(". &f").append(playerName)
                    .append(" - &c").append(String.format("%.1f", entry.getValue())).append(" &fурона    ");
        }

        final String topPlayersText = fullTopPlayersInfo.toString();
        final int textLength = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', topPlayersText)).length();

        // Запуск анимации прокрутки
        topPlayersAnimationTask = new BukkitRunnable() {
            int scrollPosition = 0;

            @Override
            public void run() {
                if (!isShowingTopPlayers || sortedDamageList.isEmpty()) {
                    this.cancel();
                    topPlayersBar.removeAll();
                    return;
                }

                // Создаем прокручивающийся текст
                String visibleText;
                int maxVisibleLength = 50; // Максимальная длина видимого текста

                if (textLength <= maxVisibleLength) {
                    // Если текст полностью помещается, показываем его целиком
                    visibleText = topPlayersText;
                } else {
                    // Иначе вычисляем видимую часть с учетом прокрутки
                    String repeatedText = topPlayersText + "    " + topPlayersText;
                    int startPos = scrollPosition % (textLength + 4); // +4 для пробелов между повторами

                    // Берем подстроку нужной длины с учетом позиции прокрутки
                    if (startPos + maxVisibleLength <= repeatedText.length()) {
                        visibleText = repeatedText.substring(startPos, startPos + maxVisibleLength);
                    } else {
                        visibleText = repeatedText.substring(startPos);
                    }

                    scrollPosition++;
                }

                // Обновляем заголовок BossBar
                topPlayersBar.setTitle(ChatColor.translateAlternateColorCodes('&', visibleText));
            }
        }.runTaskTimer(this, 0L, 4L); // Обновление каждые 0.2 секунды для плавной прокрутки

        // Запуск задачи для удаления BossBar через заданное время
        scheduleTopBarsRemoval();
    }

    /**
     * Запуск задачи для удаления BossBar с топом игроков через заданное время
     */
    private void scheduleTopBarsRemoval() {
        if (removeTopBarsTask != null) {
            removeTopBarsTask.cancel();
        }

        int delay = config.getInt(CONFIG_BOSSBAR_REMOVE_DELAY, 30) * 20; // Конвертация в тики

        removeTopBarsTask = new BukkitRunnable() {
            @Override
            public void run() {
                // Удаление BossBar с топом игроков
                if (topPlayersBar != null) {
                    topPlayersBar.removeAll();
                }

                // Остановка анимации
                if (topPlayersAnimationTask != null) {
                    topPlayersAnimationTask.cancel();
                }

                isShowingTopPlayers = false;
            }
        }.runTaskLater(this, delay);
    }

    /**
     * Обновление информации для игрока
     */
    // Найди метод updatePlayerInfo и замени его на:
    private void updatePlayerInfo(Player player) {
        if ((!eventActive && !isEventEnded) || player == null || !player.isOnline()) {
            return;
        }

        // Проверка, находится ли игрок в мире ивента
        if (!player.getWorld().getName().equals(eventWorldName)) {
            return;
        }

        // Получение данных для отображения
        double playerDmg = playerDamage.getOrDefault(player.getUniqueId(), 0.0);
        double golemHealthPercent = 0;
        if (golem != null && !golem.isDead()) {
            golemHealthPercent = Math.round((golem.getHealth() / golem.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue()) * 100);
        }

        // Подготовка плейсхолдеров для сообщений
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("wave", String.valueOf(currentWave));
        placeholders.put("damage", String.format("%.1f", playerDmg));

        // Расчет оставшегося времени волны
        List<Integer> waveDurations = config.getIntegerList(CONFIG_WAVE_DURATIONS);
        int totalDuration = config.getInt(CONFIG_EVENT_DURATION, 600);
        int waveEndTime = totalDuration;

        for (int i = 0; i < currentWave; i++) {
            if (i < waveDurations.size()) {
                waveEndTime -= waveDurations.get(i);
            }
        }

        // Форматирование времени в минуты:секунды
        int timeLeft = waveEndTime;
        int minutes = timeLeft / 60;
        int seconds = timeLeft % 60;
        String timeString = String.format("%02d:%02d", minutes, seconds);
        placeholders.put("time", timeString);

        // Обновление информации на BossBar
        infoBar.setTitle(ChatColor.translateAlternateColorCodes('&',
                "&bВолна: &e" + currentWave + "/3 | &cУрон: &f" + String.format("%.1f", playerDmg) + " | &eПройдет: " + timeString));
    }

    // Также найди метод updateGolemBossBarTitle и замени его на:
    private void updateGolemBossBarTitle(int timeLeft) {
        if (golem == null || !eventActive) return;

        // Форматируем время в минуты:секунды
        int minutes = timeLeft / 60;
        int seconds = timeLeft % 60;
        String timeString = String.format("%02d:%02d", minutes, seconds);

        // Создаем итоговый заголовок только с названием
        String title = ChatColor.translateAlternateColorCodes('&', config.getString(CONFIG_DESIGN + ".title", "&cСердце Голема"));

        // Обновляем заголовок босс-бара
        golemHealthBar.setTitle(title);
    }

    /**
     * Получение позиции игрока в рейтинге по урону
     */
    private int getPlayerDamagePosition(UUID playerUuid) {
        if (!playerDamage.containsKey(playerUuid)) {
            return 0;
        }

        List<Map.Entry<UUID, Double>> sortedDamage = playerDamage.entrySet()
                .stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .collect(Collectors.toList());

        for (int i = 0; i < sortedDamage.size(); i++) {
            if (sortedDamage.get(i).getKey().equals(playerUuid)) {
                return i + 1;
            }
        }

        return 0;
    }

    /**
     * Телепортация игроков на ивент
     */
    private void teleportPlayersToEvent() {
        if (golemLocation == null) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            // Сохранение состояния полета и годмода перед телепортацией
            previousFlightStates.put(player.getUniqueId(), player.getAllowFlight());
            previousGodModeStates.put(player.getUniqueId(), isGodModeEnabled(player));

            // Телепортация игрока
            player.teleport(golemLocation);
            player.sendMessage(getMessage("event_teleport"));

            // Отключение полета и годмода
            if (!player.hasPermission("golemevent.admin.fly")) {
                player.setAllowFlight(false);
                player.setFlying(false);
            }

            if (!player.hasPermission("golemevent.admin.god")) {
                setGodMode(player, false);
            }
        }
    }

    /**
     * Управление волнами мобов
     * @return номер текущей волны
     */
    private int manageWaves(int timeLeft) {
        List<Integer> waveDurations = config.getIntegerList(CONFIG_WAVE_DURATIONS);
        int totalDuration = config.getInt(CONFIG_EVENT_DURATION, 600);

        int waveEndTime = totalDuration;
        int nextWave = 0;

        // Определение текущей волны
        for (int i = waveDurations.size() - 1; i >= 0; i--) {
            waveEndTime -= waveDurations.get(i);
            if (timeLeft <= totalDuration - waveEndTime) {
                nextWave = i + 1;
                break;
            }
        }

        // Если волна изменилась, спавним новых мобов
        if (nextWave != currentWave) {
            currentWave = nextWave;

            // Объявление о начале новой волны
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("wave", String.valueOf(currentWave));

            sendEventWorldMessage(getMessage("event_wave_start", placeholders));

            // Воспроизведение звука начала волны
            playSound("wave_start");

            // Спавн мобов для этой волны
            spawnWaveMobs();
        }

        return currentWave;
    }

    /**
     * Спавн мобов для текущей волны
     */
    private void spawnWaveMobs() {
        if (!eventActive || currentWave <= 0 || currentWave > 3) {
            return;
        }

        // Удаление предыдущих мобов
        for (Entity entity : spawnedMobs) {
            if (entity != null && !entity.isDead()) {
                entity.remove();
            }
        }
        spawnedMobs.clear();

        // Получение конфигурации для текущей волны
        ConfigurationSection waveConfig = config.getConfigurationSection(CONFIG_WAVE_MOBS + "." + currentWave);
        if (waveConfig == null) {
            getLogger().warning("Конфигурация для волны " + currentWave + " не найдена!");
            return;
        }

        // Масштабирование количества мобов в зависимости от онлайна
        int onlinePlayers = eventWorld.getPlayers().size();
        double scaleFactor = 1.0;

        // Если включено масштабирование по количеству игроков
        if (config.getBoolean(CONFIG_PLAYER_SCALING, true)) {
            double multiplier = config.getDouble(CONFIG_PLAYER_MULTIPLIER, 1.3);
            scaleFactor = Math.max(0.5, Math.min(3.0, onlinePlayers * multiplier / 10.0));
        }

        // Создаем общую очередь всех мобов для последовательного спавна
        List<Map<String, Object>> mobQueue = new ArrayList<>();

        // Заполняем очередь мобов для всех типов
        for (String mobType : waveConfig.getKeys(false)) {
            ConfigurationSection mobConfig = waveConfig.getConfigurationSection(mobType);
            if (mobConfig == null) continue;

            int count = (int) Math.ceil(mobConfig.getInt("count", 10) * scaleFactor);
            double health = mobConfig.getDouble("health", 20.0);
            double damage = mobConfig.getDouble("damage", 3.0);
            double speed = mobConfig.getDouble("speed", 0.2);

            // Добавляем каждого моба в очередь
            for (int i = 0; i < count; i++) {
                Map<String, Object> mobData = new HashMap<>();
                mobData.put("type", mobType);
                mobData.put("health", health);
                mobData.put("damage", damage);
                mobData.put("speed", speed);
                mobQueue.add(mobData);
            }
        }

        // Перемешиваем очередь для разнообразия
        Collections.shuffle(mobQueue);

        // Получаем настройку интервала между спавном мобов
        double spawnInterval = config.getDouble(CONFIG_SPAWN_INTERVAL, 1.5);

        // Настраиваем интервал в зависимости от волны
        if (currentWave == 1) {
            // Для первой волны оставляем стандартный интервал
        } else if (currentWave == 2) {
            spawnInterval = spawnInterval * 1.5; // Увеличиваем интервал для 2 волны
        } else if (currentWave == 3) {
            spawnInterval = spawnInterval * 2; // Удваиваем интервал для 3 волны
        }

        // Интервал между спавном мобов в тиках (1 секунда = 20 тиков)
        long spawnIntervalTicks = (long) (spawnInterval * 20);

        // Постепенный спавн мобов из очереди
        final List<Map<String, Object>> finalMobQueue = mobQueue;
        new BukkitRunnable() {
            int index = 0;

            @Override
            public void run() {
                if (!eventActive || index >= finalMobQueue.size()) {
                    this.cancel();
                    return;
                }

                Map<String, Object> mobData = finalMobQueue.get(index);
                String mobType = (String) mobData.get("type");
                double health = (double) mobData.get("health");
                double damage = (double) mobData.get("damage");
                double speed = (double) mobData.get("speed");

                // Выбираем случайную точку спавна
                if (!spawnPoints.isEmpty()) {
                    List<Location> availablePoints = new ArrayList<>(spawnPoints);
                    Collections.shuffle(availablePoints);
                    Location spawnPoint = availablePoints.get(0);

                    try {
                        EntityType entityType = EntityType.valueOf(mobType.toUpperCase());
                        spawnSingleMob(entityType, spawnPoint, health, damage, speed);
                    } catch (IllegalArgumentException e) {
                        getLogger().warning("Неизвестный тип моба: " + mobType);
                    }
                }

                index++;
            }
        }.runTaskTimer(this, 20L, spawnIntervalTicks);

        // Сбрасываем флаг необходимости респавна
        needRespawn = false;
        lastRespawnCheck = System.currentTimeMillis();
    }

    /**
     * Запуск задачи проверки необходимости респавна мобов
     */
    private void startRespawnMobsTask() {
        // Отмена предыдущей задачи, если она была
        if (respawnMobsTask != null) {
            respawnMobsTask.cancel();
        }

        respawnMobsTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (eventActive && currentWave > 0) {
                    // Проверка, все ли мобы убиты
                    boolean allMobsDead = true;
                    for (Entity entity : spawnedMobs) {
                        if (entity != null && !entity.isDead()) {
                            allMobsDead = false;
                            break;
                        }
                    }

                    // Если все мобы убиты и прошло достаточно времени с последнего респавна
                    long currentTime = System.currentTimeMillis();
                    long respawnInterval = config.getLong(CONFIG_RESPAWN_INTERVAL, 30000); // 30 секунд по умолчанию

                    if (allMobsDead && currentTime - lastRespawnCheck > respawnInterval) {
                        getLogger().info("Все мобы убиты, респавним новую волну");
                        spawnWaveMobs();
                        lastRespawnCheck = currentTime;
                    }
                }
            }
        }.runTaskTimer(this, 20L, 20L); // Проверка каждую секунду
    }

    /**
     * Спавн определенного типа мобов
     */
    private void spawnMobs(String mobType, int count, double health, double damage, double speed) {
        if (spawnPoints.isEmpty()) {
            getLogger().warning("Точки спавна не настроены! Мобы не могут быть созданы.");
            return;
        }

        EntityType entityType;
        try {
            entityType = EntityType.valueOf(mobType.toUpperCase());
        } catch (IllegalArgumentException e) {
            getLogger().warning("Неизвестный тип моба: " + mobType);
            return;
        }

        // Распределяем мобов по всем доступным точкам спавна
        List<Location> availablePoints = new ArrayList<>(spawnPoints);
        Collections.shuffle(availablePoints);

        // Получаем настройку интервала между спавном мобов
        double spawnInterval = config.getDouble(CONFIG_SPAWN_INTERVAL, 1.5);
        boolean gradualSpawn = config.getBoolean(CONFIG_GRADUAL_SPAWN, true);

        // Интервал между спавном мобов в тиках (1 секунда = 20 тиков)
        long spawnIntervalTicks = (long) (spawnInterval * 20);

        // Отладочная информация
        getLogger().info("Начинаем спавн " + count + " мобов типа " + mobType);
        getLogger().info("Доступно точек спавна: " + availablePoints.size());

        // Если постепенный спавн отключен, спавним всех мобов сразу
        if (!gradualSpawn) {
            for (int i = 0; i < count; i++) {
                // Если точек спавна меньше чем мобов, перемешиваем точки снова
                if (availablePoints.isEmpty()) {
                    availablePoints.addAll(spawnPoints);
                    Collections.shuffle(availablePoints);
                }

                // Берем следующую точку спавна
                Location spawnPoint = availablePoints.remove(0);

                spawnSingleMob(entityType, spawnPoint, health, damage, speed);
            }
            return;
        }

        // Постепенный спавн мобов с задержкой
        new BukkitRunnable() {
            int spawned = 0;

            @Override
            public void run() {
                if (!eventActive || spawned >= count) {
                    this.cancel();
                    return;
                }

                // Если точек спавна меньше чем мобов, перемешиваем точки снова
                if (availablePoints.isEmpty()) {
                    availablePoints.addAll(spawnPoints);
                    Collections.shuffle(availablePoints);
                }

                // Берем следующую точку спавна
                Location spawnPoint = availablePoints.remove(0);

                spawnSingleMob(entityType, spawnPoint, health, damage, speed);
                spawned++;
            }
        }.runTaskTimer(this, 20L, spawnIntervalTicks); // Спавн с заданным интервалом
    }

    /**
     * Спавн одного моба с заданными параметрами
     */
    private void spawnSingleMob(EntityType entityType, Location spawnPoint, double health, double damage, double speed) {
        try {
            // Спавним моба напрямую через API
            Entity entity = spawnPoint.getWorld().spawnEntity(spawnPoint, entityType);

            // Воспроизводим звук спавна моба
            playMobSpawnSound(entityType, spawnPoint);

            if (entity instanceof LivingEntity) {
                LivingEntity livingEntity = (LivingEntity) entity;

                // Настройка здоровья
                AttributeInstance healthAttribute = livingEntity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                if (healthAttribute != null) {
                    healthAttribute.setBaseValue(health);
                    livingEntity.setHealth(health);
                }

                // Настройка урона
                AttributeInstance damageAttribute = livingEntity.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE);
                if (damageAttribute != null) {
                    damageAttribute.setBaseValue(damage);
                }

                // Настройка скорости
                AttributeInstance speedAttribute = livingEntity.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
                if (speedAttribute != null) {
                    speedAttribute.setBaseValue(speed);
                }

                // Настройка AI для атаки голема
                if (golem != null && !golem.isDead()) {
                    if (livingEntity instanceof Mob) {
                        Mob mob = (Mob) livingEntity;
                        mob.setTarget(golem);

                        // Установка метаданных для идентификации мобов ивента
                        mob.setMetadata("event_mob", new FixedMetadataValue(this, true));
                    }

                    // Специальная обработка для летучих мышей
                    if (entityType == EntityType.BAT) {
                        // Делаем летучую мышь агрессивной и заставляем атаковать голема
                        // Для этого используем задачу, которая периодически проверяет
                        // расстояние до голема и наносит урон при приближении
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                if (!eventActive || livingEntity.isDead() || golem == null || golem.isDead()) {
                                    this.cancel();
                                    return;
                                }

                                // Двигаем летучую мышь к голему
                                Vector direction = golem.getLocation().toVector()
                                        .subtract(livingEntity.getLocation().toVector())
                                        .normalize()
                                        .multiply(0.5); // Скорость полета

                                livingEntity.setVelocity(direction);

                                // Если летучая мышь близко к голему, наносим урон
                                if (livingEntity.getLocation().distance(golem.getLocation()) < 2.0) {
                                    golem.damage(damage, livingEntity);

                                    // Обновляем BossBar здоровья голема
                                    golemHealthBar.setProgress(golem.getHealth() / golem.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue());
                                }
                            }
                        }.runTaskTimer(this, 20L, 20L); // Проверка каждую секунду
                    }
                }

                // Делаем моба персистентным
                if (livingEntity instanceof Mob) {
                    ((Mob) livingEntity).setPersistent(true);
                }

                // Добавляем в список спавненных мобов
                spawnedMobs.add(entity);
            }
        } catch (Exception e) {
            getLogger().warning("Ошибка при спавне моба " + entityType + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Обработка события спавна мобов
     */
    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        // Полностью отключаем обработчик - не выполняем никаких проверок и отмен
        return;
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        // Если взрыв происходит в мире ивента
        if (event.getEntity().getWorld().getName().equals(eventWorldName)) {
            // Если это крипер
            if (event.getEntity() instanceof Creeper) {
                // Отменяем разрушение блоков
                event.blockList().clear();

                // Если ивент активен и голем существует
                if (eventActive && golem != null && !golem.isDead()) {
                    // Проверяем, принадлежит ли крипер к ивенту
                    if (spawnedMobs.contains(event.getEntity())) {
                        // Расчет расстояния от крипера до голема
                        double distance = event.getEntity().getLocation().distance(golem.getLocation());

                        // Если голем в радиусе взрыва (максимум 5 блоков)
                        if (distance <= 5.0) {
                            // Расчет урона: ближе = больше урона (от 20 до 4)
                            double damage = 20.0 * (1.0 - (distance / 5.0));

                            // Наносим урон голему от имени крипера
                            golem.damage(damage, event.getEntity());

                            // Обновляем BossBar здоровья голема
                            golemHealthBar.setProgress(golem.getHealth() / golem.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue());

                            // Отладочное сообщение
                            getLogger().info("Крипер нанес голему " + damage + " урона (расстояние: " + distance + ")");
                        }
                    }
                }
            }
        }
    }

    /**
     * Одновременное удаление всех мобов в мире ивента
     */
    private void removeAllMobsInWorld() {
        if (eventWorld == null) return;

        for (Entity entity : eventWorld.getEntities()) {
            if (entity instanceof Monster && entity != golem) {
                entity.remove();
            }
        }

        spawnedMobs.clear();
    }

    /**
     * Завершение ивента
     */
    private void endEvent(boolean success) {
        if (!eventActive) {
            return;
        }

        eventActive = false;
        isEventEnded = true;

        // Воспроизведение звука в зависимости от результата
        if (success) {
            playSound("event_success");
        } else {
            playSound("golem_death");
        }

        // Удаление всех мобов одновременно
        removeAllMobsInWorld();

        // Объявление о завершении ивента
        if (success) {
            sendEventWorldMessage(getMessage("event_end_success"));

            // Раздача наград
            giveRewards();
        } else {
            sendEventWorldMessage(getMessage("event_end_fail"));
        }

        // Удаление голема
        if (golem != null && !golem.isDead()) {
            golem.remove();
        }
        golem = null;

        // Отображение топа игроков в чате
        showTopPlayers();

        // Восстановление состояний полета и годмода для игроков
        for (UUID uuid : previousFlightStates.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.setAllowFlight(previousFlightStates.get(uuid));
            }
        }

        for (UUID uuid : previousGodModeStates.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                setGodMode(player, previousGodModeStates.get(uuid));
            }
        }

        previousFlightStates.clear();
        previousGodModeStates.clear();

        // Сохранение данных
        saveDamageData();

        // Отмена задач
        if (golemStayTask != null) {
            golemStayTask.cancel();
            golemStayTask = null;
        }

        if (worldTimeTask != null) {
            worldTimeTask.cancel();
            worldTimeTask = null;
        }

        if (respawnMobsTask != null) {
            respawnMobsTask.cancel();
            respawnMobsTask = null;
        }

        if (mobTargetCheckTask != null) {
            mobTargetCheckTask.cancel();
            mobTargetCheckTask = null;
        }

        if (bossBarUpdateTask != null) {
            bossBarUpdateTask.cancel();
            bossBarUpdateTask = null;
        }

        // Восстановление настроек мира
        eventWorld.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true);

        // Запуск задачи для удаления BossBar через заданное время
        int delay = config.getInt(CONFIG_BOSSBAR_REMOVE_DELAY, 30) * 20; // Конвертация в тики
        new BukkitRunnable() {
            @Override
            public void run() {
                golemHealthBar.removeAll();
                infoBar.removeAll();
                isEventEnded = false;
            }
        }.runTaskLater(this, delay);

        // Телепортация игроков на спавн через 10 секунд используя команду /spawn
        new BukkitRunnable() {
            @Override
            public void run() {
                // Сохраняем список игроков, которые находятся в мире ивента
                List<Player> eventPlayers = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getWorld().getName().equals(eventWorldName)) {
                        eventPlayers.add(player);
                    }
                }

                // Телепортируем каждого игрока на спавн через команду /spawn
                for (Player player : eventPlayers) {
                    // Выполняем команду от имени консоли
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "spawn " + player.getName());

                    // Отправляем сообщение о завершении ивента
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            config.getString(CONFIG_DESIGN + ".event") + " &fИвент завершен"));
                }
            }
        }.runTaskLater(this, success ? 1200L : 200L); // 60 секунд (1200 тиков) при победе, 10 секунд (200 тиков) при поражении
    }

    /**
     * Отображение топа игроков по урону
     */
    private void showTopPlayers() {
        // Сортировка игроков по урону
        List<Map.Entry<UUID, Double>> sortedDamage = playerDamage.entrySet()
                .stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .collect(Collectors.toList());

        if (sortedDamage.isEmpty()) {
            return;
        }

        // Отправка сообщения с топом игроков
        sendEventWorldMessage(getMessage("event_top_players"));

        // Отображение топ-10 игроков или меньше, если игроков меньше
        int count = Math.min(10, sortedDamage.size());
        for (int i = 0; i < count; i++) {
            Map.Entry<UUID, Double> entry = sortedDamage.get(i);
            String playerName = Bukkit.getOfflinePlayer(entry.getKey()).getName();
            if (playerName == null) playerName = "Неизвестный";

            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("position", String.valueOf(i + 1));
            placeholders.put("player", playerName);
            placeholders.put("damage", String.format("%.1f", entry.getValue()));

            sendEventWorldMessage(getMessage("event_top_player_format", placeholders));
        }
    }

    /**
     * Раздача наград
     */
    private void giveRewards() {
        if (golemLocation == null) return;

        // Сортировка игроков по урону
        List<Map.Entry<UUID, Double>> sortedDamage = playerDamage.entrySet()
                .stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .collect(Collectors.toList());

        if (sortedDamage.isEmpty()) {
            return;
        }

        // Награды для топ-3 игроков
        ConfigurationSection topRewards = config.getConfigurationSection(CONFIG_REWARDS + ".top_players");
        if (topRewards != null) {
            for (int i = 0; i < Math.min(3, sortedDamage.size()); i++) {
                UUID playerUuid = sortedDamage.get(i).getKey();
                String playerName = Bukkit.getOfflinePlayer(playerUuid).getName();
                if (playerName == null) continue;

                ConfigurationSection playerReward = topRewards.getConfigurationSection(String.valueOf(i + 1));
                if (playerReward != null) {
                    int rubies = playerReward.getInt("rubies", 0);

                    // Выдача рубинов через

                    // Выдача рубинов через команду
                    String command = "playerpoints:p give " + playerName + " " + rubies;
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);

                    // Воспроизведение звука награды
                    Player player = Bukkit.getPlayer(playerUuid);
                    if (player != null && player.isOnline()) {
                        // Отправка сообщения о награде
                        Map<String, String> placeholders = new HashMap<>();
                        placeholders.put("reward", rubies + " рубинов");
                        player.sendMessage(getMessage("event_reward", placeholders));

                        // Воспроизведение звука награды для игрока
                        try {
                            Sound rewardSound = Sound.valueOf(config.getString(CONFIG_SOUNDS + ".reward", "ENTITY_EXPERIENCE_ORB_PICKUP"));
                            player.playSound(player.getLocation(), rewardSound, 1.0f, 1.0f);
                        } catch (IllegalArgumentException e) {
                            // Игнорируем неверное название звука
                        }
                    }
                }
            }
        }

        // Разбрасывание предметов
        List<String> itemsConfig = config.getStringList(CONFIG_REWARDS + ".items");
        Random random = new Random();

        for (String itemConfig : itemsConfig) {
            String[] parts = itemConfig.split(":");
            if (parts.length < 2) continue;

            String materialName = parts[0];
            String[] countRange = parts[1].split("-");

            Material material;
            try {
                material = Material.valueOf(materialName);
            } catch (IllegalArgumentException e) {
                getLogger().warning("Неизвестный материал: " + materialName);
                continue;
            }

            int minCount = 1;
            int maxCount = 1;

            if (countRange.length >= 2) {
                try {
                    minCount = Integer.parseInt(countRange[0]);
                    maxCount = Integer.parseInt(countRange[1]);
                } catch (NumberFormatException e) {
                    getLogger().warning("Неверный формат диапазона количества: " + parts[1]);
                }
            }

            // Генерация случайного количества предметов
            int count = random.nextInt(maxCount - minCount + 1) + minCount;

            // Разбрасывание предметов вокруг голема
            for (int i = 0; i < count; i++) {
                // Случайное смещение
                double offsetX = (random.nextDouble() - 0.5) * 10;
                double offsetZ = (random.nextDouble() - 0.5) * 10;

                Location dropLocation = golemLocation.clone().add(offsetX, 0.5, offsetZ);

                // Создание предмета и его выброс
                ItemStack item = new ItemStack(material);
                dropLocation.getWorld().dropItemNaturally(dropLocation, item);
            }
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!eventActive) {
            return;
        }

        // Проверка, является ли цель големом и атакующий игроком
        if (golem != null && event.getEntity() == golem) {
            // Если атакующий - игрок или снаряд, выпущенный игроком
            if (event.getDamager() instanceof Player ||
                    (event.getDamager() instanceof Projectile &&
                            ((Projectile) event.getDamager()).getShooter() instanceof Player)) {
                // Отменяем событие урона
                event.setCancelled(true);
                return;
            }
        }

        // Проверка, является ли атакующий игроком
        Player attacker = null;
        if (event.getDamager() instanceof Player) {
            attacker = (Player) event.getDamager();
        } else if (event.getDamager() instanceof Projectile) {
            Projectile projectile = (Projectile) event.getDamager();
            if (projectile.getShooter() instanceof Player) {
                attacker = (Player) projectile.getShooter();
            }
        }

        if (attacker == null) {
            return;
        }

        // Проверка, является ли цель мобом ивента
        if (spawnedMobs.contains(event.getEntity())) {
            // Учет урона игрока
            double damage = event.getFinalDamage();
            UUID playerUuid = attacker.getUniqueId();

            playerDamage.put(playerUuid, playerDamage.getOrDefault(playerUuid, 0.0) + damage);
        }

        // Если цель - голем
        if (event.getEntity() == golem) {
            // Обновление BossBar
            golemHealthBar.setProgress(Math.max(0, golem.getHealth() - event.getFinalDamage()) / golem.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue());

            // Если голем умирает, завершаем ивент
            if (golem.getHealth() - event.getFinalDamage() <= 0) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        endEvent(false);
                    }
                }.runTaskLater(this, 1L);
            }
        }
    }

    /**
     * Обработка смерти мобов
     */
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (!eventActive) {
            return;
        }

        // Если это голем - не убираем дроп, так как это часть награды
        if (event.getEntity() == golem) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    endEvent(false);
                }
            }.runTaskLater(this, 1L);
            return; // Выходим из метода, чтобы сохранить дроп с голема
        }

        // Удаление моба из списка, если он был частью ивента
        if (spawnedMobs.contains(event.getEntity())) {
            spawnedMobs.remove(event.getEntity());

            // Убираем дроп предметов с мобов
            event.getDrops().clear();
            event.setDroppedExp(0);

            // Проверка, все ли мобы убиты
            boolean allMobsDead = true;
            for (Entity entity : spawnedMobs) {
                if (entity != null && !entity.isDead()) {
                    allMobsDead = false;
                    break;
                }
            }

            // Если все мобы убиты, отмечаем необходимость респавна
            if (allMobsDead) {
                needRespawn = true;
            }
        }
        // Для всех других мобов в мире ивента (не из списка spawnedMobs, но в том же мире)
        else if (event.getEntity().getWorld().getName().equals(eventWorldName) && !(event.getEntity() instanceof Player)) {
            // Убираем дроп предметов
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    // Добавь новый метод для запрета PvP между игроками
    @EventHandler
    public void onPlayerDamagePlayer(EntityDamageByEntityEvent event) {
        // Проверяем, происходит ли это в мире ивента
        if (!event.getEntity().getWorld().getName().equals(eventWorldName)) return;

        // Проверяем, является ли цель игроком
        if (!(event.getEntity() instanceof Player)) return;

        // Получаем атакующего (может быть игрок или снаряд от игрока)
        Player attacker = null;
        if (event.getDamager() instanceof Player) {
            attacker = (Player) event.getDamager();
        } else if (event.getDamager() instanceof Projectile) {
            Projectile projectile = (Projectile) event.getDamager();
            if (projectile.getShooter() instanceof Player) {
                attacker = (Player) projectile.getShooter();
            }
        }

        // Если атакующий - игрок, и цель - игрок, отменяем урон
        if (attacker != null && event.getEntity() instanceof Player) {
            event.setCancelled(true);
            attacker.sendMessage(ChatColor.RED + "PvP запрещен в этом событии!");
        }
    }

    @EventHandler
    public void onPlayerToggleGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player)) return;

        Player player = (Player) event.getEntity();

        // Проверяем, находится ли игрок в мире ивента
        if (!player.getWorld().getName().equals(eventWorldName)) return;

        // Если игрок начинает полет на элитрах, отменяем это
        if (event.isGliding()) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "Полёт на элитрах запрещен в этом событии!");
        }
    }

    @EventHandler
    public void onEntityTarget(EntityTargetEvent event) {
        if (!eventActive) {
            return;
        }

        // Проверяем, является ли атакующий мобом из нашего ивента
        if (spawnedMobs.contains(event.getEntity())) {
            Entity entity = event.getEntity();

            // Для всех мобов: если цель не голем, отменяем нацеливание
            if (event.getTarget() != golem) {
                event.setCancelled(true);

                // Перенаправляем моба на голема
                if (entity instanceof Mob && golem != null && !golem.isDead()) {
                    ((Mob) entity).setTarget(golem);
                }
                return;
            }

            // Специфичная проверка для зомби и скелетов
            if ((entity instanceof Zombie || entity instanceof Skeleton) &&
                    (event.getTarget() instanceof Zombie || event.getTarget() instanceof Skeleton)) {
                event.setCancelled(true);

                // Перенаправляем на голема
                if (entity instanceof Mob && golem != null && !golem.isDead()) {
                    ((Mob) entity).setTarget(golem);
                }
            }
        }
    }

    @EventHandler
    public void onPlayerChangeWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();

        // Если игрок вошел в мир ивента
        if (player.getWorld().getName().equals(eventWorldName)) {
            // Сохранение состояния полета
            if (!previousFlightStates.containsKey(player.getUniqueId())) {
                previousFlightStates.put(player.getUniqueId(), player.getAllowFlight());
            }

            // Сохранение состояния годмода
            if (!previousGodModeStates.containsKey(player.getUniqueId())) {
                previousGodModeStates.put(player.getUniqueId(), isGodModeEnabled(player));
            }

            // Отключение полета
            if (!player.hasPermission("golemevent.admin.fly")) {
                player.setAllowFlight(false);
                player.setFlying(false);
            }

            // Отключение годмода
            if (!player.hasPermission("golemevent.admin.god")) {
                setGodMode(player, false);
            }

            // Добавление игрока в BossBar, если ивент активен
            if (eventActive || isEventEnded) {
                golemHealthBar.addPlayer(player);
                infoBar.addPlayer(player);
            }
        }
        // Если игрок покинул мир ивента
        else if (event.getFrom().getName().equals(eventWorldName)) {
            // Восстановление состояния полета
            if (previousFlightStates.containsKey(player.getUniqueId())) {
                player.setAllowFlight(previousFlightStates.get(player.getUniqueId()));
                previousFlightStates.remove(player.getUniqueId());
            }

            // Восстановление состояния годмода
            if (previousGodModeStates.containsKey(player.getUniqueId())) {
                setGodMode(player, previousGodModeStates.get(player.getUniqueId()));
                previousGodModeStates.remove(player.getUniqueId());
            }

            // Удаление игрока из BossBar
            golemHealthBar.removePlayer(player);
            infoBar.removePlayer(player);
            // Удалено: topPlayersBar.removePlayer(player);
        }
    }

    /**
     * Обработка включения полета игроком
     */
    @EventHandler
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();

        // Если игрок в мире ивента и не имеет разрешения, отменяем полет
        if (player.getWorld().getName().equals(eventWorldName) && !player.hasPermission("golemevent.admin.fly")) {
            if (event.isFlying()) {
                event.setCancelled(true);
                player.setAllowFlight(false);
                player.setFlying(false);
            }
        }
    }

    /**
     * Обработка смены игрового режима
     */
    @EventHandler
    public void onPlayerGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();

        // Проверяем, находится ли игрок в мире ивента
        if (player.getWorld().getName().equals(eventWorldName)) {
            // Если игрок переходит в креатив или спектатор, и у него нет прав, отменяем
            if ((event.getNewGameMode() == GameMode.CREATIVE || event.getNewGameMode() == GameMode.SPECTATOR)
                    && !player.hasPermission("golemevent.admin.gamemode")) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        config.getString(CONFIG_DESIGN + ".event") + " &fВы не можете использовать этот режим игры в мире ивента!"));
            }
        }
    }

    /**
     * Обработка входа игрока на сервер
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Если ивент активен и игрок в мире ивента
        if ((eventActive || isEventEnded) && player.getWorld().getName().equals(eventWorldName)) {
            // Добавление игрока в BossBar
            golemHealthBar.addPlayer(player);
            infoBar.addPlayer(player);

            // Отключение полета и годмода
            if (!player.hasPermission("golemevent.admin.fly")) {
                previousFlightStates.put(player.getUniqueId(), player.getAllowFlight());
                player.setAllowFlight(false);
                player.setFlying(false);
            }

            if (!player.hasPermission("golemevent.admin.god")) {
                previousGodModeStates.put(player.getUniqueId(), isGodModeEnabled(player));
                setGodMode(player, false);
            }
        }

        // Если показывается топ игроков и игрок в мире ивента
        if (isShowingTopPlayers && player.getWorld().getName().equals(eventWorldName)) {
            topPlayersBar.addPlayer(player);
        }
    }

    /**
     * Обработка команд плагина
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("golemevent")) {
            if (!sender.hasPermission("golemevent.admin")) {
                sender.sendMessage(getMessage("command_no_permission"));
                return true;
            }

            if (args.length == 0) {
                String buttonColor = config.getString(CONFIG_DESIGN + ".button", "&e");
                sender.sendMessage(ChatColor.GREEN + "Команды плагина RestIronEvent:");
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', buttonColor + "/golemevent start") + ChatColor.GRAY + " - Запустить ивент");
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', buttonColor + "/golemevent stop") + ChatColor.GRAY + " - Остановить ивент");
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', buttonColor + "/golemevent reload") + ChatColor.GRAY + " - Перезагрузить конфигурацию");
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', buttonColor + "/golemeventspawn add") + ChatColor.GRAY + " - Добавить точку спавна мобов");
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', buttonColor + "/golemeventspawn remove") + ChatColor.GRAY + " - Удалить ближайшую точку спавна");
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', buttonColor + "/golemeventspawn golem") + ChatColor.GRAY + " - Установить позицию голема");
                return true;
            }

            if (args[0].equalsIgnoreCase("start")) {
                if (eventActive) {
                    sender.sendMessage(ChatColor.RED + "Ивент уже запущен!");
                    return true;
                }

                startEvent();
                sender.sendMessage(getMessage("command_event_started"));
                return true;
            }

            if (args[0].equalsIgnoreCase("stop")) {
                if (!eventActive) {
                    sender.sendMessage(ChatColor.RED + "Ивент не запущен!");
                    return true;
                }

                endEvent(false);
                sender.sendMessage(getMessage("command_event_stopped"));
                return true;
            }

            if (args[0].equalsIgnoreCase("reload")) {
                // Остановка ивента, если он запущен
                if (eventActive) {
                    endEvent(false);
                }

                // Перезагрузка конфигурации
                reloadConfig();
                config = getConfig();

                // Обновление времени мира
                worldTime = config.getLong(CONFIG_WORLD_TIME, 14000);

                loadSpawnPoints();
                loadDamageData();
                setupScheduledEvents();

                sender.sendMessage(ChatColor.GREEN + "Конфигурация перезагружена!");
                return true;
            }

            sender.sendMessage(ChatColor.RED + "Неизвестная команда. Используйте /golemevent для справки.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("golemeventspawn")) {
            if (!sender.hasPermission("golemevent.admin")) {
                sender.sendMessage(getMessage("command_no_permission"));
                return true;
            }

            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Эта команда может быть выполнена только игроком!");
                return true;
            }

            Player player = (Player) sender;

            if (args.length == 0) {
                String buttonColor = config.getString(CONFIG_DESIGN + ".button", "&e");
                player.sendMessage(ChatColor.GREEN + "Команды настройки точек спавна:");
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', buttonColor + "/golemeventspawn add") + ChatColor.GRAY + " - Добавить точку спавна мобов");
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', buttonColor + "/golemeventspawn remove") + ChatColor.GRAY + " - Удалить ближайшую точку спавна");
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', buttonColor + "/golemeventspawn golem") + ChatColor.GRAY + " - Установить позицию голема");
                return true;
            }

            if (args[0].equalsIgnoreCase("add")) {
                // Добавление точки спавна
                Location location = player.getLocation();
                spawnPoints.add(location);
                saveSpawnPoints();

                player.sendMessage(getMessage("command_spawn_added"));
                return true;
            }

            if (args[0].equalsIgnoreCase("remove")) {
                // Удаление ближайшей точки спавна
                Location playerLocation = player.getLocation();
                Location closest = null;
                double closestDistance = Double.MAX_VALUE;

                for (Location location : spawnPoints) {
                    if (!location.getWorld().equals(playerLocation.getWorld())) {
                        continue;
                    }

                    double distance = location.distance(playerLocation);
                    if (distance < closestDistance) {
                        closestDistance = distance;
                        closest = location;
                    }
                }

                if (closest != null) {
                    spawnPoints.remove(closest);
                    saveSpawnPoints();

                    player.sendMessage(getMessage("command_spawn_removed"));
                } else {
                    player.sendMessage(ChatColor.RED + "Поблизости нет точек спавна!");
                }

                return true;
            }

            if (args[0].equalsIgnoreCase("golem")) {
                // Установка позиции голема
                golemLocation = player.getLocation();
                saveSpawnPoints();

                player.sendMessage(getMessage("command_golem_set"));
                return true;
            }

            player.sendMessage(ChatColor.RED + "Неизвестная команда. Используйте /golemeventspawn для справки.");
            return true;
        }

        return false;
    }
}