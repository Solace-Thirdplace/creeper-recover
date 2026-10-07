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

package de.rafael.plugins.creeper.recover.common.classes;

//------------------------------
//
// This class was developed by Rafael K.
// On 31.12.2021 at 12:37
// In the project CreeperRecover
//
//------------------------------

import de.rafael.plugins.creeper.recover.common.CreeperPlugin;
import de.rafael.plugins.creeper.recover.common.classes.data.InventoryItems;
import de.rafael.plugins.creeper.recover.common.classes.data.sign.SignLines;
import de.rafael.plugins.creeper.recover.common.classes.data.sign.SignStyle;
import de.rafael.plugins.creeper.recover.common.classes.list.BlockList;
import de.rafael.plugins.creeper.recover.common.utils.MathUtils;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

@Getter
public class Explosion {

    private final Location location;
    private final List<ExplodedBlock> blocks;

    public Explosion(Location location, @NotNull BlockList blocks) {
        this.location = location;

        List<ExplodedBlock> explodedBlocks = new ArrayList<>();
        blocks.forEach((block, ignore, add) -> {
            if (block.getType() == Material.TNT) {
                // Try different TNT entity type names for version compatibility
                EntityType tntType;
                try {
                    tntType = EntityType.valueOf("TNT");
                } catch (IllegalArgumentException e) {
                    try {
                        tntType = EntityType.valueOf("PRIMED_TNT");
                    } catch (IllegalArgumentException e2) {
                        tntType = EntityType.valueOf("TNT_MINECART"); // Fallback
                    }
                }
                TNTPrimed tnt = (TNTPrimed) Objects.requireNonNull(block.getLocation().getWorld())
                        .spawnEntity(MathUtils.toCenterLocation(block.getLocation()), tntType);
                tnt.setFuseTicks(MathUtils.generateRandomInteger(10, 30));
                tnt.setVelocity(
                        MathUtils
                                .calculateVectorBetween2Locations(MathUtils.toCenterLocation(location.clone()),
                                        MathUtils.toCenterLocation(block.getLocation().clone()))
                                .normalize().multiply(0.7));
                return;
            }
            ExplodedBlock explodedBlock = new ExplodedBlock(block.getLocation().clone(), block.getType(),
                    block.getBlockData().clone());
            if (block.getState() instanceof InventoryHolder holder) {
                InventoryItems inventory = new InventoryItems();
                Inventory live = holder.getInventory();
                ItemStack[] contents = live.getStorageContents();
                for (int i = 0; i < contents.length; i++) {
                    if (contents[i] != null) {
                        inventory.set(i, contents[i].clone());
                    }
                }
                explodedBlock.addData(inventory);
                // Empty the live container now that its contents are saved. Since 1.21.5 removing a
                // container block spills its contents even through setType(AIR, false), and the
                // recovery restores the saved copy, so leaving them in would duplicate everything.
                // For a double chest this is the combined inventory and empties both halves.
                live.clear();
            }
            if (block.getState() instanceof Chest chest) {
                if (chest.getInventory() instanceof DoubleChestInventory doubleChestInventory) {
                    DoubleChest doubleChest = doubleChestInventory.getHolder();

                    assert doubleChest != null;
                    Chest leftSide = (Chest) doubleChest.getLeftSide();
                    Chest rightSide = (Chest) doubleChest.getRightSide();
                    assert leftSide != null;
                    assert rightSide != null;
                    if (block.equals(leftSide.getBlock())) {
                        add.accept(rightSide.getBlock());
                        ignore.accept(rightSide.getBlock());
                        ExplodedBlock extraChest = new ExplodedBlock(rightSide.getBlock().getLocation().clone(),
                                rightSide.getBlock().getType(), rightSide.getBlock().getBlockData().clone());
                        explodedBlock.connectBlock(extraChest);
                    } else if (block.equals(rightSide.getBlock())) {
                        add.accept(leftSide.getBlock());
                        ignore.accept(leftSide.getBlock());
                        ExplodedBlock extraChest = new ExplodedBlock(leftSide.getBlock().getLocation().clone(),
                                leftSide.getBlock().getType(), leftSide.getBlock().getBlockData().clone());
                        explodedBlock.connectBlock(extraChest);
                    }
                }
            }
            if (block.getState() instanceof Sign sign) {
                // 1.19.4 compatible sign handling (single-sided signs only)
                explodedBlock.addData(new SignLines(null, sign.getLines()));
                explodedBlock.addData(new SignStyle(null, sign.getColor(), sign.isGlowingText()));
                // Note: Sign waxing not supported in 1.19.4
            }
            explodedBlocks.add(explodedBlock);
        });
        this.blocks = explodedBlocks
                .stream().sorted(Comparator
                        .comparingDouble(item -> ((ExplodedBlock) item).location().distance(location)).reversed())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public synchronized boolean recoverBlock() {
        Iterator<ExplodedBlock> iterator = this.blocks.iterator();
        if (iterator.hasNext()) {
            ExplodedBlock block = iterator.next();
            block.recover();
            iterator.remove();
            return iterator.hasNext();
        } else {
            this.blocks.clear();
            return false;
        }
    }

    public int recoverBlocks() {
        int recovered = 0;
        while (recoverBlock()) {
            recovered++;
        }
        return recovered;
    }

    public int recoverBlocks(int amount) {
        int recovered = 0;
        while (true) {
            if (recovered >= amount) {
                break;
            }
            if (!recoverBlock()) {
                recovered++;
                break;
            }
            recovered++;
        }
        return recovered;
    }

    public boolean isFinished() {
        return this.blocks.size() == 0;
    }

    public void finished() {
        CreeperPlugin.instance().pluginStats().explosionRecovered();
    }

}
