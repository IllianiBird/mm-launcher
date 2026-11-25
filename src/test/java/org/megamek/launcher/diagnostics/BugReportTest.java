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

package org.megamek.launcher.diagnostics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BugReportTest {
    private static final Path TEMPLATE = Path.of(".github", "ISSUE_TEMPLATE", "bug_report.yml");
    private static final BugReport.Environment FIXTURE = new BugReport.Environment(
            "0.14.24", "Windows installer (.msi)", "Windows 11 (10.0.26200) amd64",
            "Eclipse Adoptium 21.0.8", "16 threads", "32 GB (18 GB free)", "512 MB", "en-CA");

    @TempDir Path temp;

    @Test
    void issueFormNamesTheTemplateAndFillsTheEnvironmentFields() {
        URI form = BugReport.issueForm(FIXTURE);
        assertTrue(form.toString().startsWith(BugReport.ISSUE_FORM_URL + "?template=bug_report.yml&"));
        Map<String, String> query = query(form);
        assertEquals("0.14.24", query.get(BugReport.FIELD_VERSION));
        assertEquals("Windows installer (.msi)", query.get(BugReport.FIELD_INSTALL_TYPE));
        assertEquals("Windows 11 (10.0.26200) amd64", query.get(BugReport.FIELD_OPERATING_SYSTEM));
        assertEquals("Eclipse Adoptium 21.0.8", query.get(BugReport.FIELD_JAVA_VERSION));
        assertEquals("16 threads", query.get(BugReport.FIELD_PROCESSOR));
        assertEquals("32 GB (18 GB free)", query.get(BugReport.FIELD_SYSTEM_MEMORY));
        assertEquals("512 MB", query.get(BugReport.FIELD_JAVA_MAX_MEMORY));
        assertEquals("en-CA", query.get(BugReport.FIELD_LOCALE));
        assertFalse(query.containsKey("labels"),
                "GitHub serves a 404 to reporters without triage permission when labels are set");
    }

    @Test
    void overlongDetailsFallBackToThePlainForm() {
        URI form = BugReport.issueForm(new BugReport.Environment("x".repeat(4000),
                "Portable archive", "y".repeat(4000), "z", "16 threads", "32 GB (18 GB free)",
                "512 MB", "en-CA"));
        assertEquals(BugReport.ISSUE_FORM_URL + "?template=bug_report.yml", form.toString());
    }

    @Test
    void prefilledFieldIdsExistInTheIssueTemplate() throws IOException {
        JsonNode template = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(TEMPLATE));
        Set<String> ids = new HashSet<>();
        for (JsonNode element : template.path("body")) {
            if (element.hasNonNull("id")) ids.add(element.get("id").asText());
        }
        for (String field : new String[]{BugReport.FIELD_VERSION, BugReport.FIELD_INSTALL_TYPE,
                BugReport.FIELD_OPERATING_SYSTEM, BugReport.FIELD_JAVA_VERSION,
                BugReport.FIELD_PROCESSOR, BugReport.FIELD_SYSTEM_MEMORY,
                BugReport.FIELD_JAVA_MAX_MEMORY, BugReport.FIELD_LOCALE}) {
            assertTrue(ids.contains(field), field + " is a field of " + TEMPLATE);
        }
        assertEquals(BugReport.TEMPLATE, TEMPLATE.getFileName().toString());
        JsonNode config = new ObjectMapper(new YAMLFactory()).readTree(
                Files.readString(TEMPLATE.resolveSibling("config.yml")));
        assertFalse(config.path("blank_issues_enabled").asBoolean(true),
                "blank issues stay off so the form, and its prefill, is always used");
    }

    @Test
    void installTypeDescribesHowTheLauncherWasDistributed() {
        assertEquals("Windows installer (.msi)", BugReport.installType("Windows 11", true, true));
        assertEquals("macOS installer (.pkg)", BugReport.installType("Mac OS X", true, true));
        assertEquals("Linux package (.deb or .rpm)", BugReport.installType("Linux", true, true));
        assertEquals("Portable archive", BugReport.installType("Windows 11", false, true));
        assertEquals("Portable archive", BugReport.installType("Linux", false, true),
                "the portable shell scripts run the released JAR without jpackage.app-path");
        assertEquals("Development build", BugReport.installType("Linux", false, false));
    }

    @Test
    void memoryIsShownLikeTheSuiteLogHeader() {
        long gigabyte = 1024L * 1024L * 1024L;
        assertEquals("32 GB (18 GB free)", BugReport.describeSystemMemory(32 * gigabyte, 18 * gigabyte));
        assertEquals("4 GB", BugReport.describeMemorySize(4 * gigabyte));
        assertEquals("512 MB", BugReport.describeMemorySize(512L * 1024L * 1024L),
                "a limit under 1 GB would otherwise read as 0 GB or 1 GB");
    }

    @Test
    void currentEnvironmentFillsEveryHardwareField() {
        BugReport.Environment environment = BugReport.Environment.current();
        assertTrue(environment.processor().endsWith(" threads"), environment.processor());
        assertFalse(environment.systemMemory().isBlank());
        assertFalse(environment.javaMaxMemory().isBlank());
        assertFalse(environment.locale().isBlank());
    }

    @Test
    void archiveFileNameIsPortable() {
        assertEquals("MegaMek-Launcher-bug-report-2026-10-31T181500Z.zip",
                BugReport.archiveFileName(Instant.parse("2026-10-31T18:15:00.123Z")));
    }

    @Test
    void archiveHoldsSanitizedSystemInfoAndLogs() throws IOException {
        String home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize().toString();
        Path target = temp.resolve("report.zip");
        Files.writeString(target, "previous");
        BugReport.writeArchive(target, FIXTURE, "Failure in " + home + "\\launcher token=abc123");

        Map<String, String> entries = unzip(target);
        assertEquals(Set.of("system-info.txt", "operation-logs.txt"), entries.keySet());
        assertTrue(entries.get("system-info.txt").contains("Launcher version: 0.14.24"));
        assertTrue(entries.get("system-info.txt").contains("Installed as: Windows installer (.msi)"));
        assertTrue(entries.get("system-info.txt").contains("System memory: 32 GB (18 GB free)"));
        assertTrue(entries.get("system-info.txt").contains("Locale: en-CA"));
        String logs = entries.get("operation-logs.txt");
        assertFalse(logs.contains(home), "the home directory is shortened");
        assertTrue(logs.contains("~"));
        assertFalse(logs.contains("abc123"), "secret fields are redacted");
        try (var files = Files.list(temp)) {
            assertEquals(1, files.count(), "no staging file is left behind");
        }
    }

    @Test
    void archiveNeedsAnExistingFolder() {
        assertThrows(IOException.class, () -> BugReport.writeArchive(
                temp.resolve("missing").resolve("report.zip"), FIXTURE, "logs"));
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private static Map<String, String> unzip(Path archive) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
