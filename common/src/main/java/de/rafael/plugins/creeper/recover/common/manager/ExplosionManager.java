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
import de.rafael.plugins.creeper.recover.common.scheduler.CorePreloader;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

public class ExplosionManager {

    /**
     * Crater locations whose physics is cancelled until their block is back, with how many pending
     * blocks hold each one (the list this replaced allowed the same location twice). Concurrent
     * because on Folia every region thread reads it from the physics listener and frees its own
     * blocks as it restores them.
     */
    private final Map<Location, Integer> suppressedLocations = new ConcurrentHashMap<>();
    /** Explosions still being recovered. Guarded by this manager's monitor. */
    private final List<Explosion> explosionList = new ArrayList<>();
    /**
     * Set once this core generation starts stopping (a swap or a shutdown). Recovery timers that
     * still fire after that do nothing: {@link #recoverAllForStop()} has put everything back.
     */
    private volatile boolean stopping;
    /**
     * Read-held by {@link #handle} while it decides on and records an explosion, write-held by
     * {@link #recoverAllForStop()} once {@link #stopping} is up. Explosions in different regions take
     * the read side together; the drain waits for the ones already being recorded and every later
     * one sees {@link #stopping} and is refused.
     */
    private final ReadWriteLock intake = new ReentrantReadWriteLock();

    /**
     * Takes on a new explosion unless this core is stopping. The explosion is built here, by
     * {@code create}, only once it is certain to be taken: building one saves and then empties the
     * blocks' containers, so it must not happen for an explosion nobody will put back.
     *
     * <p>On Folia explosions arrive on the thread of the region they happen in, so one can arrive
     * while another thread drains this manager for a swap. The intake lock settles which comes
     * first. An explosion taken before {@link #recoverAllForStop()} starts draining is drained with
     * the rest; one that arrives later is refused and the caller leaves the event to vanilla, as if
     * no core were loaded. On Paper both run on the main thread and never overlap.
     *
     * @param create builds the explosion; called on the caller's thread, at most once
     * @return {@code false} if this core is stopping, in which case {@code create} was not called
     */
    public boolean handle(@NotNull Supplier<Explosion> create) {
        this.intake.readLock().lock();
        try {
            if (this.stopping) {
                return false;
            }
            Explosion explosion = create.get();
            // Nobody else can see the explosion until it is in the list, so its blocks can be
            // walked here without its monitor.
            explosion.blocks().forEach(explodedBlock -> this.suppressBlock(explodedBlock.location()));
            synchronized (this) {
                this.explosionList.add(explosion);
            }
            List<Explosion> explosions = Collections.singletonList(explosion);
            // Scheduled before the intake lock is released, so the timer always exists before this
            // generation's stop begins and is cancelled with the rest of its tasks.
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
            return true;
        } finally {
            this.intake.readLock().unlock();
        }
    }

    /** Whether this core generation has started stopping; see {@link #stopping}. */
    public boolean isStopping() {
        return this.stopping;
    }

    /**
     * Puts back every block still waiting for recovery and stops the timed recovery. Called from
     * the core's stop, for a swap or a shutdown, so that nothing is in flight when the next core (or
     * the next boot) takes over. Blocks the calling thread owns go back now, on this thread: on
     * Paper (the main thread) and at a Folia shutdown (the shutdown thread) that is all of them.
     * During a Folia swap the rest are handed to their own regions and go back on the next tick.
     *
     * <p>Order matters. {@link #stopping} goes up first so no timer starts another tick and no new
     * explosion is taken. Taking the intake lock's write side then waits out an explosion another
     * region is recording at this moment, so it is in the list before the drain reads it. Taking this
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
        // Wait out explosions other regions are recording right now; any later one is refused.
        this.intake.writeLock().lock();
        this.intake.writeLock().unlock();
        CreeperPlugin.Scheduler scheduler = CreeperPlugin.scheduler();
        int recovered;
        synchronized (this) {
            recovered = scheduler.runPendingNow();
            recovered += recoverBlocks(Integer.MAX_VALUE);
        }
        recovered += scheduler.runPendingNow();
        int handedOff = scheduler.pendingElsewhere();
        if (handedOff > 0) {
            // They run on this generation's classes after the shell has closed its class loader,
            // which can still use classes it has loaded but cannot load new ones.
            CorePreloader.preload(CreeperPlugin.instance().context().logger());
            CreeperPlugin.instance().context().logger().info(String.format(
                    "%d blocks are in regions this thread does not own; they go back on their region's next tick",
                    handedOff));
            // Their locations stay suppressed only until this generation's physics listener is
            // unregistered, a moment from now; the restores place blocks without physics anyway.
        }
        if (handedOff == 0 && !this.suppressedLocations.isEmpty()) {
            CreeperPlugin.instance().context().logger().warning(String.format(
                    "%d suppressed locations left after recovering everything; dropping them: %s",
                    this.suppressedLocations.size(), this.suppressedLocations.keySet()));
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
        return !this.suppressedLocations.isEmpty();
    }

    public void suppressBlock(Location location) {
        this.suppressedLocations.merge(location, 1, Integer::sum);
    }

    public void freeBlock(Location location) {
        this.suppressedLocations.computeIfPresent(location, (at, holds) -> holds > 1 ? holds - 1 : null);
    }

    public boolean isBlockSuppressed(Location location) {
        return this.suppressedLocations.containsKey(location);
    }

}
