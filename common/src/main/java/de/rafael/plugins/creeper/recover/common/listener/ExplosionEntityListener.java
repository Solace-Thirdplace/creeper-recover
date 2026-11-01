package de.rafael.plugins.creeper.recover.common.listener;

import de.rafael.plugins.creeper.recover.common.CreeperPlugin;
import de.rafael.plugins.creeper.recover.common.manager.ConfigManager;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;

/**
 * Keeps item frames, paintings and armour stands out of the explosions this plugin repairs, as
 * EntityExplodeListener keeps storage blocks out: none of them comes back with what it held.
 */
public class ExplosionEntityListener implements Listener {

    /** The frame or painting itself breaking. */
    @EventHandler(ignoreCancelled = true)
    public void on(HangingBreakByEntityEvent event) {
        if (event.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION && repaired(event.getRemover())) {
            event.setCancelled(true);
        }
    }

    /** A frame's item popping out, or an armour stand being knocked over. */
    @EventHandler(ignoreCancelled = true)
    public void on(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            return;
        }
        Entity entity = event.getEntity();
        if ((entity instanceof Hanging || entity instanceof ArmorStand) && repaired(event.getDamager())) {
            event.setCancelled(true);
        }
    }

    /** Whether an explosion by {@code source} is one EntityExplodeListener would repair. */
    private static boolean repaired(Entity source) {
        ConfigManager config = CreeperPlugin.instance().configManager();
        return source != null && config.enabled()
                && !config.isWorldBlacklisted(source.getWorld().getName())
                && !config.isExplosionBlacklisted(source.getType());
    }
}
