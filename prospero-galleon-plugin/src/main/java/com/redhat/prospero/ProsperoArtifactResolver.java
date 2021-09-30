/*
 * Copyright 2016-2021 Red Hat, Inc. and/or its affiliates
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

package com.redhat.prospero;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.redhat.prospero.api.ArtifactNotFoundException;
import com.redhat.prospero.api.Channel;
import com.redhat.prospero.api.Manifest;
import com.redhat.prospero.installation.Modules;
import com.redhat.prospero.xml.ManifestXmlSupport;
import com.redhat.prospero.xml.XmlException;

import java.util.HashSet;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.MavenUniverseException;

public class ProsperoArtifactResolver {
    private final static Logger log = LogManager.getLogger(ProsperoArtifactResolver.class);

    private final List<Channel> channels;
    private final Set<MavenArtifact> resolvedArtifacts = new HashSet<>();
    private MavenResolver repository;

    public ProsperoArtifactResolver(Path channelFile,
                                    RepositorySystem repoSystem, RepositorySystemSession repoSession,
                                    RepositorySystemSession fallbackRepoSession, List<RemoteRepository> fallbackRepositories) throws ProvisioningException {
        this(readChannels(channelFile), repoSystem, repoSession, fallbackRepoSession, fallbackRepositories);
    }

    private ProsperoArtifactResolver(List<Channel> channels,
                                     RepositorySystem repoSystem, RepositorySystemSession repoSession,
                                     RepositorySystemSession fallbackRepoSession, List<RemoteRepository> fallbackRepositories) throws ProvisioningException {
        this.channels = channels;
        repository = new MavenResolver(channels, repoSystem, repoSession, fallbackRepoSession, fallbackRepositories);
    }

    public List<RemoteRepository> getRepositories() {
        return repository.getRepositories();
    }

    private static List<Channel> readChannels(Path channelFile) throws ProvisioningException {
        try {
            return Channel.readChannels(channelFile);
        } catch (IOException e) {
            throw new ProvisioningException(e);
        }
    }

    public void writeManifestFile(Path home, Set<MavenArtifact> artifactSet) throws MavenUniverseException {
        List<com.redhat.prospero.api.Artifact> artifacts = new ArrayList<>();
        for (MavenArtifact artifact : artifactSet) {
            artifacts.add(new com.redhat.prospero.api.Artifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(),
                    artifact.getClassifier(), artifact.getExtension()));
        }

        try {
            ManifestXmlSupport.write(new Manifest(artifacts, home.resolve("manifest.xml")));
        } catch (XmlException e) {
            e.printStackTrace();
        }

        // write channels into installation
        final File channelsFile = home.resolve("channels.json").toFile();
        try {
            com.redhat.prospero.api.Channel.writeChannels(channels, channelsFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void resolveLatest(MavenArtifact artifact) throws MavenUniverseException {
        if (artifact.isResolved()) {
            throw new MavenUniverseException("Artifact is already resolved");
        }
        String range;
        if (artifact.getVersionRange() == null) {
            if (artifact.getVersion() == null) {
                throw new MavenUniverseException("Can't compute range, version is not set for " + artifact);
            }
            range = "[" + artifact.getVersion() + ",)";
        } else {
            if (artifact.getVersion() != null) {
                log.info("Version is set for {} although a range is provided {}. Using provided range." + artifact, artifact.getVersionRange());
            }
            range = artifact.getVersionRange();
        }
        try {
            final com.redhat.prospero.api.Artifact prosperoArtifact = new com.redhat.prospero.api.Artifact(artifact.getGroupId(),
                    artifact.getArtifactId(), artifact.getVersion(), artifact.getClassifier(), artifact.getExtension());
            // First attempt to get the highest version from the channels
            com.redhat.prospero.api.Artifact gav = repository.findLatestVersionOf(prosperoArtifact, range);
            if (gav == null) {
                log.info("The artifact {}:{} not found in channel, falling back", artifact.getGroupId(), artifact.getArtifactId());
                if (artifact.getVersion() == null) {
                    log.debug("Finding fallback version");
                    gav = repository.findLatestFallBack(prosperoArtifact, range);
                } else {
                    log.debug("Re-using artifact version {}", artifact.getVersion());
                    gav = prosperoArtifact.newVersion(artifact.getVersion());
                }
                if (gav == null) {
                    throw new MavenUniverseException("Artifact is not found " + artifact.getGroupId() + ":" + artifact.getArtifactId());
                }
                final File resolvedPath = repository.resolveFallback(prosperoArtifact.newVersion(gav.getVersion()));
                artifact.setVersion(gav.getVersion());
                artifact.setPath(resolvedPath.toPath());
            } else {
                final File resolvedPath = repository.resolve(prosperoArtifact.newVersion(gav.getVersion()));
                artifact.setVersion(gav.getVersion());
                artifact.setPath(resolvedPath.toPath());
            }
            log.debug("LATEST: Found version {} for range {}", artifact.getVersion(), range);
        } catch (ArtifactNotFoundException ex) {
            throw new MavenUniverseException(ex.getLocalizedMessage(), ex);
        }
        if ("jar".equals(artifact.getExtension())) {
            resolvedArtifacts.add(artifact);
        }
    }

    VersionRangeResult getVersionRange(Artifact artifact) throws MavenUniverseException {
        VersionRangeResult res = repository.getVersionRange(artifact);
        if (res.getHighestVersion() == null) {
            res = repository.getVersionRangeFallback(artifact);
        }
        return res;
    }

    public void resolve(MavenArtifact artifact) throws MavenUniverseException {
        if (artifact.isResolved()) {
            throw new MavenUniverseException("Artifact is already resolved");
        }
        if (artifact.getVersion() == null) {
            throw new MavenUniverseException("Version is not set for " + artifact);
        }
        if (artifact.getVersionRange() != null) {
            log.warn("WARNING: Version range is set for {}", artifact);
        }

        final com.redhat.prospero.api.Artifact prosperoArtifact = new com.redhat.prospero.api.Artifact(artifact.getGroupId(),
                artifact.getArtifactId(), artifact.getVersion(), artifact.getClassifier(), artifact.getExtension());
        try {
            final File resolvedPath = repository.resolve(prosperoArtifact);
            artifact.setPath(resolvedPath.toPath());
        } catch (ArtifactNotFoundException ex) {
            log.info("FALLBACK: The artifact {}:{} not found in channel, falling back", artifact.getGroupId(), artifact.getArtifactId());
            try {
                final File resolvedPath = repository.resolveFallback(prosperoArtifact);
                artifact.setPath(resolvedPath.toPath());
            } catch (ArtifactNotFoundException e) {
                throw new MavenUniverseException(ex.getLocalizedMessage(), e);
            }
        }
        log.info("RESOLVED: " + artifact);
        if ("jar".equals(artifact.getExtension())) {
            resolvedArtifacts.add(artifact);
        }
    }

    void provisioningDone(Path home) throws MavenUniverseException {
        final Modules modules = new Modules(home);
        Set<MavenArtifact> installed = new HashSet<>();
        for (MavenArtifact resolvedArtifact : resolvedArtifacts) {
            if (containsArtifact(resolvedArtifact, modules)) {
                installed.add(resolvedArtifact);
            }
        }
        writeManifestFile(home, installed);
    }

    public Set<MavenArtifact> resolvedArtfacts() {
        return resolvedArtifacts;
    }

    private boolean containsArtifact(MavenArtifact resolvedArtifact, Modules modules) {
        return !modules.find(new com.redhat.prospero.api.Artifact(resolvedArtifact.getGroupId(), resolvedArtifact.getArtifactId(), resolvedArtifact.getVersion(), resolvedArtifact.getClassifier(), resolvedArtifact.getExtension())).isEmpty();
    }

}
