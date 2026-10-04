package dev.venus;

import io.papermc.paper.event.player.PlayerShieldDisableEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class VenusPlugin extends JavaPlugin implements Listener {

    // ---- Settings (change these to taste) ----
    private static final Material VENUS_MATERIAL = Material.NETHER_STAR;
    private static final double EXTRA_HEALTH = 6.0;          // 6 health points = 3 hearts
    private static final long ABILITY_DURATION_MS = 5_000L;  // 5 seconds
    private static final long ABILITY_COOLDOWN_MS = 25_000L; // 25 seconds (starts when you activate)

    // Set to true once the Venus resource pack is installed (model venus:venus).
    // If the pack isn't loaded, the item would show a missing-texture cube.
    private static final boolean USE_CUSTOM_MODEL = true;

    private NamespacedKey itemKey;
    private NamespacedKey healthModKey;

    private final Map<UUID, Long> shieldActiveUntil = new HashMap<>();
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private final Set<UUID> showReady = new HashSet<>();

    @Override
    public void onEnable() {
        itemKey = new NamespacedKey(this, "venus_item");
        healthModKey = new NamespacedKey(this, "venus_health");

        getServer().getPluginManager().registerEvents(this, this);

        // Keep the passive in sync with whether the player is carrying Venus.
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : getServer().getOnlinePlayers()) {
                updatePassive(p);
            }
        }, 20L, 10L);

        // Cooldown display on the action bar (the line just above the hotbar / XP bar).
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : getServer().getOnlinePlayers()) {
                updateActionBar(p);
            }
        }, 20L, 4L);
    }

    @Override
    public void onDisable() {
        for (Player p : getServer().getOnlinePlayers()) {
            setPassive(p, false);
        }
    }

    // ---------------- Item ----------------

    private ItemStack createVenusItem() {
        ItemStack item = new ItemStack(VENUS_MATERIAL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Venus", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true));
        meta.lore(List.of(
                Component.text("Passive: +3 hearts while in your inventory", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Ability: /venus1 - unbreakable shield for 5s (25s cooldown)", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        if (USE_CUSTOM_MODEL) {
            meta.setItemModel(new NamespacedKey("venus", "venus"));
        }
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isVenus(ItemStack item) {
        if (item == null || item.getType() != VENUS_MATERIAL || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    private boolean hasVenus(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isVenus(item)) return true;
        }
        return false;
    }

    // ---------------- Passive: +3 hearts ----------------

    private void updatePassive(Player player) {
        setPassive(player, hasVenus(player));
    }

    private void setPassive(Player player, boolean enabled) {
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;

        AttributeModifier existing = null;
        for (AttributeModifier mod : attr.getModifiers()) {
            if (healthModKey.equals(mod.getKey())) {
                existing = mod;
                break;
            }
        }

        if (enabled && existing == null) {
            attr.addModifier(new AttributeModifier(healthModKey, EXTRA_HEALTH, AttributeModifier.Operation.ADD_NUMBER));
        } else if (!enabled && existing != null) {
            attr.removeModifier(existing);
            // Make sure current health never exceeds the new max.
            double max = attr.getValue();
            if (player.getHealth() > max) player.setHealth(max);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        updatePassive(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        shieldActiveUntil.remove(id);
        // cooldown is intentionally kept so relogging doesn't reset it
    }

    // ---------------- Cooldown display ----------------

    private static String bar(double fraction, int length) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, fraction)) * length);
        return "\u2588".repeat(filled) + "\u2591".repeat(length - filled);
    }

    private void updateActionBar(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        Long active = shieldActiveUntil.get(id);
        Long cd = cooldownUntil.get(id);

        if (active != null && now < active) {
            double left = (active - now) / 1000.0;
            player.sendActionBar(Component.text("Venus ", NamedTextColor.LIGHT_PURPLE)
                    .append(Component.text(bar(left * 1000.0 / ABILITY_DURATION_MS, 20), NamedTextColor.GOLD))
                    .append(Component.text(String.format(" Shield %.1fs", left), NamedTextColor.YELLOW)));
        } else if (cd != null && now < cd) {
            double left = (cd - now) / 1000.0;
            double progress = 1.0 - (cd - now) / (double) ABILITY_COOLDOWN_MS;
            player.sendActionBar(Component.text("Venus ", NamedTextColor.LIGHT_PURPLE)
                    .append(Component.text(bar(progress, 20), NamedTextColor.RED))
                    .append(Component.text(String.format(" %.1fs", left), NamedTextColor.GRAY)));
            showReady.add(id);
        } else if (showReady.remove(id)) {
            player.sendActionBar(Component.text("Venus ready! (/venus1)", NamedTextColor.GREEN));
        }
    }

    // ---------------- Ability: /venus1 ----------------

    private boolean isShieldProtected(Player player) {
        Long until = shieldActiveUntil.get(player.getUniqueId());
        return until != null && System.currentTimeMillis() < until;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (command.getName().equalsIgnoreCase("venus")) {
            player.getInventory().addItem(createVenusItem()).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            player.sendMessage(Component.text("You received Venus.", NamedTextColor.LIGHT_PURPLE));
            return true;
        }

        if (command.getName().equalsIgnoreCase("venus1")) {
            if (!hasVenus(player)) {
                player.sendMessage(Component.text("You need Venus in your inventory to use this.", NamedTextColor.RED));
                return true;
            }

            long now = System.currentTimeMillis();
            Long cd = cooldownUntil.get(player.getUniqueId());
            if (cd != null && now < cd) {
                long secondsLeft = (long) Math.ceil((cd - now) / 1000.0);
                player.sendMessage(Component.text("Venus is on cooldown for " + secondsLeft + "s.", NamedTextColor.RED));
                return true;
            }

            shieldActiveUntil.put(player.getUniqueId(), now + ABILITY_DURATION_MS);
            cooldownUntil.put(player.getUniqueId(), now + ABILITY_COOLDOWN_MS);
            player.sendMessage(Component.text("Venus: your shield is unbreakable for 5 seconds!", NamedTextColor.LIGHT_PURPLE));

            getServer().getScheduler().runTaskLater(this, () -> {
                if (player.isOnline() && !isShieldProtected(player)) {
                    player.sendMessage(Component.text("Venus: shield protection ended.", NamedTextColor.GRAY));
                }
            }, ABILITY_DURATION_MS / 50L);
            return true;
        }

        return false;
    }

    // Axes (and other shield-disabling attacks) can't put the shield on cooldown.
    @EventHandler
    public void onShieldDisable(PlayerShieldDisableEvent event) {
        if (isShieldProtected(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // Shield takes no durability damage, so it can't actually break.
    @EventHandler
    public void onItemDamage(PlayerItemDamageEvent event) {
        if (event.getItem().getType() == Material.SHIELD && isShieldProtected(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
