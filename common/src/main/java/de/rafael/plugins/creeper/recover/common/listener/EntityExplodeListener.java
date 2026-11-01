/*
 * Copyright (c) 2022-2023. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     * Redistributions of source code must retain the above copyright notice,
 *         this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *         notice, this list of conditions and the following disclaimer in the
 *         documentation and/or other materials provided with the distribution.
 *     * Neither the name of the developer nor the names of its contributors
 *         may be used to endorse or promote products derived from this software
 *         without specific prior written permission.
 *     * Redistributions in source or binary form must keep the original package
 *         and class name.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package de.rafael.plugins.creeper.recover.common.listener;

//------------------------------
//
// This class was developed by Rafael K.
// On 31.12.2021 at 12:37
// In the project CreeperRecover
//
//------------------------------

import de.rafael.plugins.creeper.recover.common.CreeperPlugin;
import de.rafael.plugins.creeper.recover.common.classes.Explosion;
import de.rafael.plugins.creeper.recover.common.classes.list.BlockList;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashSet;
import java.util.Set;

public class EntityExplodeListener implements Listener {

    @EventHandler
    public void on(EntityExplodeEvent event) {
        if (!CreeperPlugin.instance().configManager().enabled())
            return;

        // Check if world is blacklisted (but still protect blocks)
        String worldName = event.getLocation().getWorld().getName();
        boolean worldBlacklisted = CreeperPlugin.instance().configManager().isWorldBlacklisted(worldName);

        // ALWAYS remove protected blocks from explosion - they are ALWAYS protected
        // regardless of world blacklist or WorldGuard
        int protectedRemoved = 0;
        var iterator = event.blockList().iterator();
        while (iterator.hasNext()) {
            var block = iterator.next();
            if (CreeperPlugin.instance().configManager().isProtectedBlock(block.getType())) {
                iterator.remove();
                protectedRemoved++;
            }
        }

        if (protectedRemoved > 0) {
            CreeperPlugin.instance().configManager().sendDebugMessage(String.format(
                    "Protected %d blocks from explosion destruction (always protected)", protectedRemoved));
        }

        // Check if world is blacklisted - skip recovery but protected blocks are still
        // protected
        if (worldBlacklisted) {
            CreeperPlugin.instance().configManager().sendDebugMessage(String.format(
                    "Explosion recovery in world '%s' skipped - world is blacklisted", worldName));
            return;
        }

        // Check if this explosion type is blacklisted
        if (CreeperPlugin.instance().configManager().isExplosionBlacklisted(event.getEntityType())) {
            CreeperPlugin.instance().configManager().sendDebugMessage(String.format(
                    "Explosion recovery skipped - entity type %s is blacklisted",
                    event.getEntityType().name()));
            return;
        }

        // A core that is stopping for a swap leaves the explosion to vanilla, as when no core is
        // loaded: it must not empty containers or clear blocks it will never put back.
        if (CreeperPlugin.instance().explosionManager().isStopping()) {
            return;
        }

        if (CreeperPlugin.instance().configManager().usePlugin(event)) {
            // Blocks that hold items stay standing: putting a container back never brought its
            // contents back reliably, so it isn't blown up at all. ExplosionEntityListener does
            // the same for item frames and armour stands.
            keepHangingSupports(event);
            int storageKept = 0;
            var storage = event.blockList().iterator();
            while (storage.hasNext()) {
                if (storage.next().getState(false) instanceof InventoryHolder) {
                    storage.remove();
                    storageKept++;
                }
            }
            if (storageKept > 0) {
                CreeperPlugin.instance().configManager().sendDebugMessage(String.format(
                        "Kept %d storage blocks out of the explosion", storageKept));
            }

            var blocks = new BlockList(event.blockList());

            CreeperPlugin.instance().configManager().sendDebugMessage(String.format(
                    "Explosion detected: Entity=%s, Location=[%s], Blocks=%d",
                    event.getEntityType().name(),
                    event.getLocation().toString(),
                    blocks.blocks().size()));

            int originalBlockCount = blocks.blocks().size();

            // Remove blacklisted blocks from recovery (they get destroyed but won't be
            // restored)
            blocks.removeIf(
                    block -> CreeperPlugin.instance().configManager().blockBlacklist().contains(block.getType()));
            int filteredBlockCount = blocks.blocks().size();

            if (originalBlockCount != filteredBlockCount) {
                int blacklistedRemoved = originalBlockCount - filteredBlockCount;
                CreeperPlugin.instance().configManager().sendDebugMessage(String.format(
                        "Filtered %d blacklisted blocks, %d blocks remaining for recovery",
                        blacklistedRemoved,
                        filteredBlockCount));
            }

            // Store blocks. The explosion is only built (saving and emptying containers) if the
            // core takes it; on Folia it can start stopping between the check above and here.
            if (!CreeperPlugin.instance().explosionManager().handle(
                    () -> new Explosion(event.getLocation().clone(), blocks, event.getEntity()))) {
                return;
            }

            // Disable damage by explosion
            event.setYield(100);

            CreeperPlugin.instance().configManager().sendDebugMessage(String.format(
                    "Started recovery process for %d blocks",
                    filteredBlockCount));

            // Remove all blocks without collision. To prevent redstone and other blocks
            // from being without support blocks.
            blocks.stream().filter(Block::isPassable).forEach(block -> block.setType(Material.AIR, false));
            // Remove the rest of the blocks
            blocks.stream().filter(block -> !block.isPassable()).forEach(block -> block.setType(Material.AIR, false));
        }
    }


    /**
     * The blocks item frames and paintings hang on stay too. Without its wall a frame falls off,
     * and it also falls when the repair brings the wall back, dropping what it held.
     */
    private static void keepHangingSupports(EntityExplodeEvent event) {
        var center = event.getLocation();
        double reach = 2;
        for (Block block : event.blockList()) {
            reach = Math.max(reach, block.getLocation().add(0.5, 0.5, 0.5).distance(center) + 2);
        }
        Set<Block> supports = new HashSet<>();
        for (Entity entity : center.getWorld().getNearbyEntities(center, reach, reach, reach, entity -> entity instanceof Hanging)) {
            supports.add(entity.getLocation().getBlock().getRelative(((Hanging) entity).getAttachedFace()));
        }
        event.blockList().removeAll(supports);
    }
}
