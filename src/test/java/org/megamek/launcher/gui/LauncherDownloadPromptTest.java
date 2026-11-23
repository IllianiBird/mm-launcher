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

package org.megamek.launcher.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherDownloadPromptTest {
    @Test
    void flatpakPlayersAreToldToInstallTheNewBundle() {
        String prompt = LauncherFrame.launcherDownloadPrompt("0.15.0", true);

        assertTrue(prompt.contains("can't update itself"), prompt);
        assertTrue(prompt.contains("flatpak install --user MegaMek-Launcher-0.15.0-linux-x64.flatpak"), prompt);
        assertFalse(prompt.contains("portable"), prompt);
        assertFalse(prompt.contains("flatpak update"), "the bundle has no application repository");
    }

    @Test
    void otherCopiesKeepThePortableGuidance() {
        String prompt = LauncherFrame.launcherDownloadPrompt("0.15.0", false);

        assertTrue(prompt.contains("portable archive"), prompt);
        assertFalse(prompt.contains("latpak"), prompt);
    }
}
