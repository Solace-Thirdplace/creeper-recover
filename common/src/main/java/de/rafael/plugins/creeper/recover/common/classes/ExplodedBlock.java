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
import de.rafael.plugins.creeper.recover.common.classes.data.IBlockData;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class ExplodedBlock {

    private Location location;

    // Material, BlockData and other data
    private Material material;
    private BlockData data;
    private final List<IBlockData> otherData = new ArrayList<>();

    // Inventory and connectedBlocks
    private final List<ExplodedBlock> connectedBlocks = new ArrayList<>();

    public ExplodedBlock(Location location, Material material, BlockData data) {
        this.location = location;
        this.material = material;
        this.data = data;
    }

    public void connectBlock(ExplodedBlock block) {
        this.connectedBlocks.add(block);
    }

    public void addData(IBlockData data) {
        this.otherData.add(data);
    }

    public void recover() {
        for (ExplodedBlock connectedBlock : this.connectedBlocks) {
            CreeperPlugin.scheduler().runOnCorrectThread(connectedBlock.location(), connectedBlock::recoverBasics);
        }
        CreeperPlugin.scheduler().runOnCorrectThread(this.location, this::recoverBasics);
    }

    public void recoverBasics() {
        CreeperPlugin.instance().explosionManager().freeBlock(this.location);
        Block block = this.location.getBlock();

        // Don't recover over anything placed in the crater since the blast (e.g. GravesX graves,
        // player rebuilds). The crater is normally air (or has liquid seep in) at restore time, so
        // only restore over air/liquid. Anything else means a block now occupies this location and
        // restoring would overwrite (and destroy) it. Material.isAir()/Block.isLiquid() keep this
        // on the Spigot API so it stays portable across the spigot and folia modules.
        if (!block.getType().isAir() && !block.isLiquid()) {
            CreeperPlugin.instance().configManager().sendDebugMessage(String.format(
                    "Skipped recovery at %s - occupied by %s",
                    this.location.toString(),
                    block.getType().name()));
            return;
        }

        CreeperPlugin.instance().configManager().sendDebugMessage(String.format(
                "Recovering block: %s at %s",
                this.material.name(),
                this.location.toString()));

        block.setType(this.material, false);
        block.setBlockData(this.data, false);

        for (IBlockData blockData : this.otherData) {
            blockData.apply(block, IBlockData.RecoverPhase.PRE_STATE_UPDATE);
        }

        block.getState().update(true, false);

        for (IBlockData blockData : this.otherData) {
            blockData.apply(block, IBlockData.RecoverPhase.POST_STATE_UPDATE);
        }

        CreeperPlugin.instance().pluginStats().blockRecovered();
        block.getWorld().playSound(block.getLocation(), CreeperPlugin.instance().configManager().blockRecoverSound(),
                0.5f, 1f);
    }

}
