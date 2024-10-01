/*
 * Copyright 2022 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.prospero.metadata;

import org.jboss.galleon.Constants;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelManifest;
import org.wildfly.channel.ChannelManifestMapper;
import org.wildfly.channel.ChannelMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class ProsperoMetadataUtils {

    /**
     * Folder containing Prospero metadata inside the provisioned server.
     */
    public static final String METADATA_DIR = ".installation";
    /**
     * Name of the file containing channel manifest generated during provisioning.
     */
    public static final String MANIFEST_FILE_NAME = "manifest.yaml";
    /**
     * Name of the file containing list of channels the server is subscribed to.
     */
    public static final String INSTALLER_CHANNELS_FILE_NAME = "installer-channels.yaml";
    public static final String MAVEN_OPTS_FILE = "maven_opts.yaml";
    public static final String CURRENT_VERSION_FILE = "manifest_version.yaml";
    public static final String README_FILE_NAME = "README.txt";
    public static final String PROVISIONING_RECORD_XML = "provisioning_record.xml";

    private static final String WARNING_MESSAGE = "WARNING: The files in .installation directory should be only edited by the provisioning tool.";

    /**
     * Generate installer metadata inside {@code serverDir}. The generated metadata files allow the server to be
     * managed by Prospero.
     *
     * The metadata directory must not contain any metadata files.
     *
     * @param serverDir - base path of the provisioned servers
     * @param channels - list of channels the server should be subscribed to
     * @param manifest - channel manifest containing streams used to provision a server.
     * @param versionRecord - provisioned versions of manifests. Can be {@code null}
     * @throws IOException - if unable to write the metadata files
     * @throws IllegalArgumentException - if any of metadata files are already present.
     */
    public static void generate(Path serverDir, List<Channel> channels, ChannelManifest manifest, ManifestVersionRecord versionRecord) throws IOException {
        Objects.requireNonNull(serverDir);
        Objects.requireNonNull(channels);
        Objects.requireNonNull(manifest);

        final Path metadataDir = serverDir.resolve(METADATA_DIR);
        final Path manifestPath = metadataDir.resolve(MANIFEST_FILE_NAME);
        final Path configPath = metadataDir.resolve(INSTALLER_CHANNELS_FILE_NAME);
        final Path versionRecordPath = metadataDir.resolve(CURRENT_VERSION_FILE);
        final Path readmeFile = metadataDir.resolve(README_FILE_NAME);

        if (Files.exists(metadataDir) && !Files.isDirectory(metadataDir)) {
            throw new IllegalArgumentException(String.format("The target path %s is not a directory.", metadataDir));
        }

        if (Files.exists(manifestPath) || Files.exists(configPath)) {
            throw new IllegalArgumentException("Metadata files are already present at " + metadataDir);
        }

        if (!Files.exists(metadataDir)) {
            Files.createDirectory(metadataDir);
        }

        writeManifest(manifestPath, manifest);
        writeChannelsConfiguration(configPath, channels);
        if (versionRecord != null) {
            writeVersionRecord(versionRecordPath, versionRecord);
        }

        recordProvisioningDefinition(serverDir);

        // Add README.txt file to .installation directory to warn the files should not be edited.
        if (!Files.exists(readmeFile)) {
            writeWarningReadme(readmeFile);
        }
    }

    /**
     * record {@code channels} to the file at {@channelPath}. If the file already exist, it will be overwritten.
     * If the file doesn't exist, the parent directory needs to be present, otherwise an exception is thrown.
     *
     * @param channelPath - {@code Path} where the data should be saved
     * @param channels - {@code Channel}s to record
     * @throws IOException - if unable to write the file
     * @throws IllegalArgumentException - if the parent folder does not exist.
     */
    public static void writeChannelsConfiguration(Path channelPath, List<Channel> channels) throws IOException {
        Objects.requireNonNull(channelPath);
        Objects.requireNonNull(channels);

        if (!Files.exists(channelPath.getParent())) {
            throw new IllegalArgumentException(String.format("The target path %s does not exist.", channelPath.getParent()));
        }

        // prospero requires the channel names to be present in order for the users to be able to refer them in CLI operations
        // if the passed channel does not define a name, try to generate one in a format of channel-X.
        final Set<String> definedChannelNames = channels.stream().map(Channel::getName).filter(Objects::nonNull).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        final AtomicInteger counter = new AtomicInteger(0);
        final List<Channel> namedChannels = channels.stream().map(c -> {
            if (c.getName() == null || c.getName().isEmpty()) {
                final String name = nextAvailableName(c, counter, definedChannelNames);
                return new Channel.Builder(c)
                        .setName(name)
                        .build();
            } else {
                return c;
            }
        }).collect(Collectors.toList());


        writeToFile(channelPath, ChannelMapper.toYaml(namedChannels));
    }

    private static String nextAvailableName(Channel c, AtomicInteger counter, Set<String> definedChannelNames) {
        String name;
        do {
            name = (c.getName() == null || c.getName().isEmpty()) ? "channel-" + counter.getAndIncrement() : c.getName();
        } while (definedChannelNames.contains(name));
        definedChannelNames.add(name);
        return name;
    }

    /**
     * record {@code ChannelManifest} to the file at {@manifestPath}. If the file already exist, it will be overwritten.
     * If the file doesn't exist, the parent directory needs to be present, otherwise an exception is thrown.
     *
     * @param manifestPath - {@code Path} where the data should be saved
     * @param manifest - {@code ChannelManifest} to record
     * @throws IOException - if unable to write the file
     * @throws IllegalArgumentException - if the parent folder does not exist.
     */
    public static void writeManifest(Path manifestPath, ChannelManifest manifest) throws IOException {
        Objects.requireNonNull(manifestPath);
        Objects.requireNonNull(manifest);

        if (!Files.exists(manifestPath.getParent())) {
            throw new IllegalArgumentException(String.format("The target path %s does not exist.", manifestPath.getParent()));
        }

        writeToFile(manifestPath, ChannelManifestMapper.toYaml(manifest));
    }

    /**
     * record {@code ManifestVersionRecord} to the file at {@versionsPath}. If the file already exist, it will be overwritten.
     * If the file doesn't exist, the parent directory needs to be present, otherwise an exception is thrown.
     *
     * @param versionsPath - {@code Path} where the data should be saved
     * @param versionRecord - {@code ManifestVersionRecord} to record
     * @throws IOException - if unable to write the file
     * @throws IllegalArgumentException - if the parent folder does not exist.
     */
    public static void writeVersionRecord(Path versionsPath, ManifestVersionRecord versionRecord) throws IOException {
        Objects.requireNonNull(versionRecord);
        Objects.requireNonNull(versionsPath);

        if (!Files.exists(versionsPath.getParent())) {
            throw new IllegalArgumentException(String.format("The target path %s does not exist.", versionsPath.getParent()));
        }

        final String yaml = ManifestVersionRecord.toYaml(versionRecord);
        if (Files.exists(versionsPath)) {
            Files.delete(versionsPath);
        }
        Files.writeString(versionsPath, yaml);
    }

    public static void writeWarningReadme(Path readmeFile) throws IOException {
        Objects.requireNonNull(readmeFile);

        if (!Files.exists(readmeFile.getParent())) {
            throw new IllegalArgumentException(String.format("The target path %s does not exist.", readmeFile.getParent()));
        }

        writeToFile(readmeFile, WARNING_MESSAGE);
    }

    public static Path manifestPath(Path serverDir) {
        return serverDir.resolve(METADATA_DIR).resolve(MANIFEST_FILE_NAME);
    }

    public static Path configurationPath(Path serverDir) {
        return serverDir.resolve(METADATA_DIR).resolve(INSTALLER_CHANNELS_FILE_NAME);
    }

    /**
     * creates a copy of Galleon provisioning configuration from {@code Constants.PROVISIONED_STATE_DIR/Constants.PROVISIONING_XML}
     * in the {@code METADATA_DIR}.
     *
     * If the source file doesn't exist no copy is created.
     * If the provisioning configuration is the same as saved copy, the file is not overwritten.
     *
     * @param sourceServer - root folder of the server
     * @throws IOException - if the file cannot be copied.
     */
    public static void recordProvisioningDefinition(Path sourceServer) throws IOException {
        final Path provisioningFile = sourceServer.resolve(Constants.PROVISIONED_STATE_DIR).resolve(Constants.PROVISIONING_XML);
        final Path provisioningRecordFile = sourceServer.resolve(METADATA_DIR).resolve(PROVISIONING_RECORD_XML);

        if (!Files.exists(provisioningFile)) {
            return;
        }

        if (!Files.exists(provisioningRecordFile)) {
            final String content = Files.readString(provisioningFile);
            final String lineEndings = content.replaceAll("\\r\\n?", "\n");
            Files.writeString(provisioningRecordFile, lineEndings);
        } else if (!isFileContentEquals(provisioningFile, provisioningRecordFile)) {
            final String content = Files.readString(provisioningFile);
            final String lineEndings = content.replaceAll("\\r\\n?", "\n");
            Files.writeString(provisioningRecordFile, lineEndings);
        }

    }

    static boolean isFileContentEquals(Path path1, Path path2) throws IOException {
        try (BufferedReader bf1 = Files.newBufferedReader(path1);
             BufferedReader bf2 = Files.newBufferedReader(path2)) {
            String line1 = "", line2 = "";
            while ((line1 = bf1.readLine()) != null) {
                line2 = bf2.readLine();
                if (!line1.equals(line2)) {
                    return false;
                }
            }
            if (bf2.readLine() == null) {
                return true;
            } else {
                return false;
            }
        }
    }

    protected static void writeToFile(Path path, String text) throws IOException {
        if (!text.endsWith("\n")) {
            text += "\n";
        }
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }
}
