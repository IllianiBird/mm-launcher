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

import org.megamek.launcher.update.LauncherSelfUpdate;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds what a launcher bug report needs: a link to the repository's issue form with the
 * environment fields already filled in, and a ZIP of sanitized diagnostics to attach to it.
 *
 * <p>The field ids are a contract with {@code .github/ISSUE_TEMPLATE/bug_report.yml}: if one is
 * renamed there, that field silently stops being filled in. A {@code labels} query parameter is
 * never added, because GitHub serves a 404 instead of the form to visitors without triage
 * permission; the template applies its own issue type for everyone.</p>
 */
public final class BugReport {
    public static final String ISSUE_FORM_URL = "https://github.com/MegaMek/mm-launcher/issues/new";
    public static final String TEMPLATE = "bug_report.yml";

    static final String FIELD_VERSION = "launcher-version";
    static final String FIELD_INSTALL_TYPE = "install-type";
    static final String FIELD_OPERATING_SYSTEM = "operating-system";
    static final String FIELD_JAVA_VERSION = "java-version";
    static final String FIELD_PROCESSOR = "cpu";
    static final String FIELD_SYSTEM_MEMORY = "system-memory";
    static final String FIELD_JAVA_MAX_MEMORY = "java-max-memory";
    static final String FIELD_LOCALE = "locale";

    static final String SYSTEM_INFO_ENTRY = "system-info.txt";
    static final String OPERATION_LOGS_ENTRY = "operation-logs.txt";

    /** Kept well inside what browsers and GitHub accept, so the link is never broken by its length. */
    private static final int MAX_URL_LENGTH = 6000;

    private static final double BYTES_PER_MEGABYTE = 1024.0 * 1024.0;
    private static final double BYTES_PER_GIGABYTE = BYTES_PER_MEGABYTE * 1024.0;
    private static final String UNKNOWN = "unknown";

    private BugReport() {
    }

    /**
     * The details the issue form asks for, gathered from the running launcher.
     *
     * <p>The hardware values come from plain Java only. The launcher carries no hardware
     * library, so there is no CPU name or graphics card; the suite's own bug form has those.</p>
     *
     * @param launcherVersion the launcher's release version, or {@code development build}
     * @param installType   how the launcher was distributed, from {@link #installType}
     * @param operatingSystem the operating system name, version and architecture
     * @param javaVersion   the Java vendor and version running the launcher
     * @param processor     the CPU thread count, such as {@code 16 threads}
     * @param systemMemory  installed and free memory, such as {@code 32 GB (18 GB free)}
     * @param javaMaxMemory the most memory the launcher's Java may use, such as {@code 512 MB}
     * @param locale        the system language and region as a language tag, such as {@code en-CA}
     */
    public record Environment(String launcherVersion, String installType, String operatingSystem,
                              String javaVersion, String processor, String systemMemory,
                              String javaMaxMemory, String locale) {
        public static Environment current() {
            String version = BugReport.class.getPackage().getImplementationVersion();
            boolean packaged = version != null && !version.isBlank();
            return new Environment(packaged ? version : "development build",
                    BugReport.installType(System.getProperty("os.name", ""),
                            LauncherSelfUpdate.available(), packaged),
                    "%s %s (%s)".formatted(System.getProperty("os.name"),
                            System.getProperty("os.version"), System.getProperty("os.arch")),
                    "%s %s".formatted(System.getProperty("java.vendor"),
                            System.getProperty("java.version")),
                    Runtime.getRuntime().availableProcessors() + " threads",
                    BugReport.currentSystemMemory(),
                    BugReport.describeMemorySize(Runtime.getRuntime().maxMemory()),
                    Locale.getDefault().toLanguageTag());
        }
    }

    /** Installed and free memory, or {@code unknown} on a Java without the extended bean. */
    private static String currentSystemMemory() {
        if (ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean operatingSystem) {
            return describeSystemMemory(operatingSystem.getTotalMemorySize(),
                    operatingSystem.getFreeMemorySize());
        }
        return UNKNOWN;
    }

    /** Matches the suite log header's memory line, such as {@code 32 GB (18 GB free)}. */
    static String describeSystemMemory(long totalBytes, long freeBytes) {
        return String.format(Locale.ROOT, "%.0f GB (%.0f GB free)", totalBytes / BYTES_PER_GIGABYTE,
                freeBytes / BYTES_PER_GIGABYTE);
    }

    /** A size in GB, or in MB under 1 GB so a small limit does not read as {@code 0 GB}. */
    static String describeMemorySize(long bytes) {
        if (bytes >= BYTES_PER_GIGABYTE) {
            return String.format(Locale.ROOT, "%.0f GB", bytes / BYTES_PER_GIGABYTE);
        }
        return String.format(Locale.ROOT, "%.0f MB", bytes / BYTES_PER_MEGABYTE);
    }

    /**
     * @param osName          the {@code os.name} property
     * @param nativeInstall   whether this is a self-updating native installation
     * @param packaged        whether the launcher runs from its released JAR, which carries an
     *                        {@code Implementation-Version}. Portable entry points do not set
     *                        {@code jpackage.app-path}, so that property cannot tell them apart
     *                        from a source build.
     */
    static String installType(String osName, boolean nativeInstall, boolean packaged) {
        if (nativeInstall) {
            if (osName.startsWith("Windows")) return "Windows installer (.msi)";
            if (osName.startsWith("Mac")) return "macOS installer (.pkg)";
            return "Linux package (.deb or .rpm)";
        }
        return packaged ? "Portable archive" : "Development build";
    }

    /** The issue form with this environment filled in, or the plain form if that would be too long. */
    public static URI issueForm(Environment environment) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(FIELD_VERSION, environment.launcherVersion());
        fields.put(FIELD_INSTALL_TYPE, environment.installType());
        fields.put(FIELD_OPERATING_SYSTEM, environment.operatingSystem());
        fields.put(FIELD_JAVA_VERSION, environment.javaVersion());
        fields.put(FIELD_PROCESSOR, environment.processor());
        fields.put(FIELD_SYSTEM_MEMORY, environment.systemMemory());
        fields.put(FIELD_JAVA_MAX_MEMORY, environment.javaMaxMemory());
        fields.put(FIELD_LOCALE, environment.locale());
        StringJoiner query = new StringJoiner("&");
        query.add("template=" + TEMPLATE);
        for (Map.Entry<String, String> field : fields.entrySet()) {
            query.add(field.getKey() + "=" + URLEncoder.encode(
                    SanitizedErrors.text(field.getValue()), StandardCharsets.UTF_8));
        }
        String prefilled = ISSUE_FORM_URL + "?" + query;
        return URI.create(prefilled.length() <= MAX_URL_LENGTH ? prefilled
                : ISSUE_FORM_URL + "?template=" + TEMPLATE);
    }

    public static String systemInfo(Environment environment) {
        return "MegaMek Launcher bug report\n"
                + "Created: " + Instant.now() + "\n"
                + "Launcher version: " + environment.launcherVersion() + "\n"
                + "Installed as: " + environment.installType() + "\n"
                + "Operating system: " + environment.operatingSystem() + "\n"
                + "Java: " + environment.javaVersion() + "\n"
                + "CPU: " + environment.processor() + "\n"
                + "System memory: " + environment.systemMemory() + "\n"
                + "Java max memory: " + environment.javaMaxMemory() + "\n"
                + "Locale: " + environment.locale() + "\n";
    }

    /** A file name for the archive that sorts by creation time and never needs escaping. */
    public static String archiveFileName(Instant now) {
        return "MegaMek-Launcher-bug-report-"
                + now.toString().replaceAll("\\.\\d+Z$", "Z").replace(":", "") + ".zip";
    }

    /**
     * Writes the archive through a sibling temporary file, so a failed write never leaves a
     * truncated ZIP at {@code target}. Both entries pass through {@link SanitizedErrors#document}.
     *
     * @param operationLogs the operation log text, as shown by the log viewer
     */
    public static void writeArchive(Path target, Environment environment, String operationLogs)
            throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path directory = absolute.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            throw new IOException("The folder for the bug report does not exist");
        }
        Path staging = Files.createTempFile(directory, ".bug-report-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(staging);
                 ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                entry(zip, SYSTEM_INFO_ENTRY, systemInfo(environment));
                entry(zip, OPERATION_LOGS_ENTRY, operationLogs);
            }
            Files.move(staging, absolute, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(SanitizedErrors.document(text).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
