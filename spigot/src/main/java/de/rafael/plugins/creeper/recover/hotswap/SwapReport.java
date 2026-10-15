package de.rafael.plugins.creeper.recover.hotswap;

// Vendored from TownTagsPlugin (com.towntags.hotswap), itself vendored from ThirdPlaceMcTweaks
// (com.thirdplace.tweaks.hotswap) and Arcadia's :hotswap module. Renamed into this package rather
// than depended on: the repositories share no build. Keep behaviour in step with the original
// when either changes.

import java.util.Objects;

/**
 * The outcome of a successful load or swap, in the form the swap command prints.
 *
 * <p>A report exists so the command handler contains no formatting logic worth testing: it
 * calls {@link #summary()} and sends the string. Everything an admin needs to decide "did that
 * work, and is the server still healthy" is in one line.
 *
 * @param oldSha256      digest of the core being replaced, or {@code null} for the initial
 *                       boot load (there was nothing to replace)
 * @param newSha256      digest of the core now running
 * @param durationMillis wall-clock time for the whole protocol, including stop and start
 * @param liveOldLoaders how many previously closed core loaders were still reachable when the
 *                       report was built — see {@link #liveOldLoaders()}
 */
public record SwapReport(String oldSha256, String newSha256, long durationMillis, int liveOldLoaders) {

    private static final int SHORT_SHA_LENGTH = 8;

    /**
     * Validates the report's invariants.
     *
     * <p>{@code oldSha256} is the only nullable component; a null <em>new</em> digest or a
     * negative loader count would mean the swapper miscounted, which is worth failing on
     * rather than printing.
     */
    public SwapReport {
        Objects.requireNonNull(newSha256, "newSha256");
        if (liveOldLoaders < 0) {
            throw new IllegalArgumentException("liveOldLoaders must not be negative: " + liveOldLoaders);
        }
    }

    /**
     * Whether this report describes the boot-time load rather than a swap.
     *
     * @return {@code true} when there was no previous core
     */
    public boolean isInitialLoad() {
        return oldSha256 == null;
    }

    /**
     * How many core loaders retired by this swapper were still reachable at report time.
     *
     * <p><strong>This is a heuristic, not a measurement.</strong> It is the count of retired
     * loaders that survived a {@link System#gc()} <em>hint</em> — and the JVM is free to
     * ignore that hint entirely, to have collected nothing yet, or to keep a loader alive for
     * reasons that are nobody's fault (a thread parked in a JDK pool, a soft-referenced
     * reflection cache). A non-zero count is therefore not proof of a leak, and a zero count
     * is not proof of its absence.
     *
     * <p>The loader retired by the swap being reported is deliberately excluded: it was closed
     * microseconds earlier and cannot possibly have been collected, so counting it would make
     * every report claim a live prior loader. The number therefore answers "are loaders from
     * <em>earlier</em> swaps still hanging around".
     *
     * <p>What it is good for is the <em>trend</em>. Cores are meant to be collectable once
     * stopped; if this number climbs monotonically across a session's swaps, some core is
     * leaving a static reference, a live thread, or a registered listener behind, and the
     * server needs a restart before memory becomes a problem. The restart cadence (Backend
     * every 12h, proxy once or twice a week) is the real backstop; this number is the early
     * warning that the cadence is not enough.
     *
     * @return the count of still-reachable retired loaders
     */
    public int liveOldLoaders() {
        return liveOldLoaders;
    }

    /**
     * Renders the one-line summary that swap commands print verbatim.
     *
     * <p>Fixed shape, short digests, no colour codes or platform-specific formatting — the
     * shells wrap it in whatever their chat layer wants. Kept to a single line because it is
     * read in a chat window scrolling with player messages.
     *
     * @return a human-readable single-line summary
     */
    public String summary() {
        String loaders = liveOldLoaders == 1 ? "1 prior loader still live" : liveOldLoaders + " prior loaders still live";
        if (isInitialLoad()) {
            return "core loaded " + shorten(newSha256) + " in " + durationMillis + "ms (" + loaders + ")";
        }
        return "core swapped " + shorten(oldSha256) + " -> " + shorten(newSha256)
                + " in " + durationMillis + "ms (" + loaders + ")";
    }

    private static String shorten(String sha) {
        return sha.length() <= SHORT_SHA_LENGTH ? sha : sha.substring(0, SHORT_SHA_LENGTH);
    }
}
