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

package de.rafael.plugins.creeper.recover.common.manager;

//------------------------------
//
// This class was developed by Rafael K.
// On 31.12.2021 at 12:37
// In the project CreeperRecover
//
//------------------------------

import de.rafael.plugins.creeper.recover.common.CreeperPlugin;
import de.rafael.plugins.creeper.recover.common.classes.Explosion;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ExplosionManager {

    private final List<Location> suppressedLocations = new ArrayList<>();
    private final List<Explosion> explosionList = new ArrayList<>();
    /**
     * Set once this core generation starts stopping (a swap or a shutdown). Recovery timers that
     * still fire after that do nothing: {@link #recoverAllForStop()} has put everything back.
     */
    private volatile boolean stopping;

    public void handle(Explosion explosion) {
        this.explosionList.add(explosion);
        explosion.blocks().forEach(explodedBlock -> this.suppressBlock(explodedBlock.location()));
        List<Explosion> explosions = Collections.singletonList(explosion);
        CreeperPlugin.scheduler().runAsyncAtFixedRate(cancel -> {
            if (this.stopping) {
                cancel.run();
                return;
            }
            // A dragon still perched on the blocks: wait for it to move off (checked each period).
            explosion.refreshHold();
            if (explosion.heldBySource()) {
                return;
            }
            recoverBlocks(explosions, false, 1);
            if (explosion.isFinished())
                cancel.run();
        }, CreeperPlugin.instance().configManager().recoverDelay(),
                CreeperPlugin.instance().configManager().recoverSpeed(), TimeUnit.MILLISECONDS);
    }

    /**
     * Puts back every block still waiting for recovery, now, on the calling thread, and stops the
     * timed recovery. Called from the core's stop on the main thread, for a swap or a shutdown, so
     * that nothing is in flight when the next core (or the next boot) takes over.
     *
     * <p>Order matters. {@link #stopping} goes up first so no timer starts another tick. Taking this
     * manager's monitor then waits out a timer tick that is already running. Hops such a tick queued
     * for the main thread carry blocks it has already taken off their explosion, so those run before
     * the remaining blocks are recovered, and once more afterwards in case one was queued at the
     * last moment. A held explosion (a dragon still perched) is recovered as well, as at shutdown.
     *
     * <p>Every recovered block frees its suppressed location, so the list is empty afterwards. If it
     * is not, the leftover locations are logged and dropped: the next core keeps its own list, and
     * this generation's physics listener is about to be unregistered.
     *
     * @return the number of blocks recovered here, including queued restores that were run
     */
    public int recoverAllForStop() {
        this.stopping = true;
        CreeperPlugin.Scheduler scheduler = CreeperPlugin.scheduler();
        int recovered;
        synchronized (this) {
            recovered = scheduler.runPendingNow();
            recovered += recoverBlocks(Integer.MAX_VALUE);
        }
        recovered += scheduler.runPendingNow();
        if (!this.suppressedLocations.isEmpty()) {
            CreeperPlugin.instance().context().logger().warning(String.format(
                    "%d suppressed locations left after recovering everything; dropping them: %s",
                    this.suppressedLocations.size(), this.suppressedLocations));
            this.suppressedLocations.clear();
        }
        return recovered;
    }

    public int recoverBlocks(int amount) {
        return recoverBlocks(this.explosionList, true, amount);
    }

    public synchronized int recoverBlocks(@NotNull List<Explosion> explosions, boolean removeFinished, int amount) {
        if (explosions.size() > 0) {
            Iterator<Explosion> iterator = explosions.iterator();
            int recovered = 0;
            while (recovered < amount && iterator.hasNext()) {
                Explosion explosion = iterator.next();
                int blocksRecovered = explosion.recoverBlocks(amount);
                recovered += blocksRecovered;

                if (blocksRecovered > 0) {
                    CreeperPlugin.instance().configManager().sendDebugMessage(String.format(
                            "Recovered %d blocks from explosion at %s",
                            blocksRecovered,
                            explosion.location().toString()));
                }

                if (removeFinished && explosion.isFinished()) {
                    iterator.remove();
                    CreeperPlugin.instance().configManager().sendDebugMessage(String.format(
                            "Explosion at %s completed recovery",
                            explosion.location().toString()));
                }
            }
            return recovered;
        } else {
            return 0;
        }
    }

    public boolean hasSuppressedBlocks() {
        return this.suppressedLocations.size() > 0;
    }

    public void suppressBlock(Location location) {
        this.suppressedLocations.add(location);
    }

    public void freeBlock(Location location) {
        this.suppressedLocations.remove(location);
    }

    public boolean isBlockSuppressed(Location location) {
        return this.suppressedLocations.contains(location);
    }

}
