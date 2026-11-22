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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LaunchLockTest {
    private static final Path FLOCK = Path.of("/usr/bin/flock");
    /** Stands in for a PID from another namespace: never a live process here. */
    private static final ProcessIdentity INVISIBLE =
            new ProcessIdentity(Integer.MAX_VALUE, Instant.now().toString());

    @TempDir Path temp;
    private Path root;
    private Path coordination;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Files.isExecutable(FLOCK), "flock is unavailable");
        root = Files.createDirectory(temp.toRealPath().resolve("app"));
        coordination = temp.toRealPath().resolve("coordination");
    }

    @Test
    void aHeldLockBlocksUpdatesInsideAndOutsideFlatpakUntilTheGameEnds() throws Exception {
        Process game = null;
        try {
            try (RootCoordinator.Lease lease = flatpak().acquireForLaunch(root)) {
                lease.markLaunchStarting();
                game = new ProcessBuilder(lease.holdLaunchLock(List.of("sleep", "60"))).start();
                lease.markChild(INVISIBLE);
            }
            Path lock = onlyLock();
            awaitState(lock, LaunchLock.State.HELD);
            expireGrace(lock);

            for (RootCoordinator coordinator : List.of(flatpak(), host())) {
                assertThrows(IOException.class, () -> coordinator.acquire(root, true),
                        "acknowledgement must not hide a running game");
                try (RootCoordinator.Lease ignored = coordinator.acquireForLaunch(root)) {
                    // Further launches stay available.
                }
            }
            assertTrue(Files.exists(lock));

            game.destroyForcibly().waitFor();
            awaitState(lock, LaunchLock.State.FREE);
            try (RootCoordinator.Lease ignored = host().acquire(root, false)) {
                assertFalse(Files.exists(lock), "an ended launch leaves no lock file");
            }
        } finally {
            if (game != null) game.destroyForcibly();
        }
    }

    @Test
    void theGameKeepsTheProcessIdItWasStartedWith() throws Exception {
        Path lock = Files.createFile(temp.resolve("game.alive"));
        Process game = new ProcessBuilder(new LaunchLock(true, FLOCK).hold(lock,
                List.of("sh", "-c", "echo $$; sleep 60"))).start();
        try {
            assertEquals(Long.toString(game.pid()), game.inputReader().readLine());
        } finally {
            game.destroyForcibly();
        }
    }

    @Test
    void aJustPublishedGameCountsAsRunningBeforeItTakesTheLock() throws Exception {
        try (RootCoordinator.Lease lease = flatpak().acquireForLaunch(root)) {
            lease.markLaunchStarting();
            lease.holdLaunchLock(List.of("unused"));
            lease.markChild(INVISIBLE);
        }
        assertThrows(IOException.class, () -> flatpak().acquire(root, true));

        expireGrace(onlyLock());
        try (RootCoordinator.Lease ignored = flatpak().acquire(root, false)) {
            // Never locked within the grace period: the game has ended.
        }
    }

    @Test
    void aLockThatCannotBeTestedBlocksUpdates() throws Exception {
        try (RootCoordinator.Lease lease = flatpak().acquireForLaunch(root)) {
            lease.markLaunchStarting();
            lease.holdLaunchLock(List.of("unused"));
            lease.markChild(INVISIBLE);
        }
        expireGrace(onlyLock());
        assertThrows(IOException.class, () -> new RootCoordinator(coordination,
                new LaunchLock(false, temp.resolve("missing-flock"))).acquire(root, true));
    }

    @Test
    void launchesFromOutsideBlockFlatpakUpdatesUnlessTheyPredateThisBoot() throws Exception {
        publishWithoutLock(INVISIBLE);
        assertThrows(IOException.class, () -> flatpak().acquire(root, true));
        try (RootCoordinator.Lease ignored = flatpak().acquireForLaunch(root)) {
            // Launching is still possible.
        }
        assertThrows(IOException.class, () -> flatpak().acquire(root, true),
                "a launch must not prune another launcher's entry");
        try (RootCoordinator.Lease ignored = host().acquire(root, false)) {
            // The launcher that can see the process reclaims its dead entry.
        }

        publishWithoutLock(new ProcessIdentity(Integer.MAX_VALUE, "2001-01-01T00:00:00Z"));
        try (RootCoordinator.Lease ignored = flatpak().acquire(root, false)) {
            // No process survives a restart.
        }
    }

    @Test
    void launchersOutsideFlatpakDoNotChangeTheCommandOrCreateLocks() throws Exception {
        try (RootCoordinator.Lease lease = host().acquireForLaunch(root)) {
            lease.markLaunchStarting();
            assertEquals(List.of("java", "Game"), lease.holdLaunchLock(List.of("java", "Game")));
            lease.clearLaunchMarker();
        }
        assertEquals(0, locks().size());
    }

    private void publishWithoutLock(ProcessIdentity identity) throws Exception {
        try (RootCoordinator.Lease lease = host().acquireForLaunch(root)) {
            lease.markLaunchStarting();
            lease.markChild(identity);
        }
    }

    private RootCoordinator flatpak() {
        return new RootCoordinator(coordination, new LaunchLock(true, FLOCK));
    }

    private RootCoordinator host() {
        return new RootCoordinator(coordination, new LaunchLock(false, FLOCK));
    }

    private List<Path> locks() throws IOException {
        try (var files = Files.list(coordination)) {
            return files.filter(file -> file.toString().endsWith(".alive")).toList();
        }
    }

    private Path onlyLock() throws IOException {
        assertEquals(1, locks().size());
        return locks().getFirst();
    }

    private static void expireGrace(Path lock) throws IOException {
        Files.setLastModifiedTime(lock, FileTime.from(Instant.now().minusSeconds(60)));
    }

    private static void awaitState(Path lock, LaunchLock.State expected) throws Exception {
        LaunchLock probe = new LaunchLock(false, FLOCK);
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (probe.probe(lock) != expected && System.nanoTime() < deadline) Thread.sleep(25);
        assertEquals(expected, probe.probe(lock));
    }
}
