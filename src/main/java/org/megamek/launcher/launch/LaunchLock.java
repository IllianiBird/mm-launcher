/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek Launcher.
 *
 * MegaMek Launcher is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek Launcher is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */

package org.megamek.launcher.launch;

import org.megamek.launcher.update.FlatpakSandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Proves whether a game started inside Flatpak is still running. Every Flatpak run has its own
 * process-ID namespace, so a recorded PID means nothing to another run or to a launcher outside
 * Flatpak. Instead the game itself holds a {@code flock(2)} file lock: {@code flock --no-fork}
 * takes it and becomes the game, keeping the same PID, and the kernel releases it when the game
 * ends, however it ends. Java's own file locks are a different kind and cannot test it.
 */
final class LaunchLock {
    enum State { HELD, FREE, UNKNOWN }

    private static final Path FLOCK = Path.of("/usr/bin/flock");
    private static final int CONFLICT = 75;
    private static final Duration PROBE_LIMIT = Duration.ofSeconds(5);

    private final boolean sandboxed;
    private final Path flock;

    LaunchLock(boolean sandboxed, Path flock) {
        this.sandboxed = sandboxed;
        this.flock = flock;
    }

    static LaunchLock system() {
        return new LaunchLock(FlatpakSandbox.active(), FLOCK);
    }

    /** Whether this launcher runs in Flatpak, where other launchers' processes are invisible. */
    boolean sandboxed() {
        return sandboxed;
    }

    boolean canHold() {
        return sandboxed && Files.isExecutable(flock);
    }

    /** Waits for the lock: only a probe, which holds it for an instant, can be in the way. */
    List<String> hold(Path file, List<String> command) {
        List<String> held = new ArrayList<>(List.of(flock.toString(), "--exclusive", "--no-fork",
                file.toString()));
        held.addAll(command);
        return List.copyOf(held);
    }

    State probe(Path file) throws IOException {
        if (!Files.isExecutable(flock)) return State.UNKNOWN;
        Process process = new ProcessBuilder(flock.toString(), "--exclusive", "--nonblock",
                "--conflict-exit-code", Integer.toString(CONFLICT), file.toString(), "true")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (!process.waitFor(PROBE_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) return State.UNKNOWN;
            return switch (process.exitValue()) {
                case 0 -> State.FREE;
                case CONFLICT -> State.HELD;
                default -> State.UNKNOWN;
            };
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return State.UNKNOWN;
        } finally {
            process.destroyForcibly();
        }
    }

    /** When this computer started, or null when that is unknown. Flatpak shares the host's value. */
    Instant bootTime() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/stat"))) {
                if (line.startsWith("btime ")) {
                    return Instant.ofEpochSecond(Long.parseLong(line.substring(6).strip()));
                }
            }
        } catch (IOException | RuntimeException unavailable) {
            // Unknown: callers then keep the entry.
        }
        return null;
    }
}
