/*-
 * =LICENSE=
 * ORAS Java SDK
 * ===
 * Copyright (C) 2024 - 2025 ORAS
 * ===
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =LICENSEEND=
 */

package land.oras;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import land.oras.auth.AuthProvider;
import land.oras.auth.BearerTokenProvider;
import land.oras.auth.FileStoreAuthenticationProvider;
import land.oras.auth.NoAuthProvider;
import land.oras.auth.UsernamePasswordProvider;
import land.oras.exception.OrasException;
import land.oras.utils.ArchiveUtils;
import land.oras.utils.Const;
import land.oras.utils.JsonUtils;
import land.oras.utils.OrasHttpClient;
import land.oras.utils.SupportedAlgorithm;

/**
 * A registry is the main entry point for interacting with a container registry
 */
@NullMarked
public final class Registry {

    /**
     * The logger
     */
    private static final Logger LOG = LoggerFactory.getLogger(Registry.class);

    /**
     * The HTTP client
     */
    private OrasHttpClient client;

    /**
     * The auth provider
     */
    private AuthProvider authProvider;

    /**
     * Insecure. Use HTTP instead of HTTPS
     */
    private boolean insecure;

    /**
     * Skip TLS verification
     */
    private boolean skipTlsVerify;

    /**
     * Constructor
     */
    private Registry() {
        this.authProvider = new NoAuthProvider();
        this.client = OrasHttpClient.Builder.builder().build();
    }

    /**
     * Return this registry with insecure flag
     * @param insecure Insecure
     */
    private void setInsecure(boolean insecure) {
        this.insecure = insecure;
    }

    /**
     * Return this registry with skip TLS verification
     * @param skipTlsVerify Skip TLS verification
     */
    private void setSkipTlsVerify(boolean skipTlsVerify) {
        this.skipTlsVerify = skipTlsVerify;
    }

    /**
     * Return this registry with auth provider
     * @param authProvider The auth provider
     */
    private void setAuthProvider(AuthProvider authProvider) {
        this.authProvider = authProvider;
        client.updateAuthentication(authProvider);
    }

    /**
     * Build the provider
     * @return The provider
     */
    private Registry build() {
        client = OrasHttpClient.Builder.builder()
                .withAuthentication(authProvider)
                .withSkipTlsVerify(skipTlsVerify)
                .build();
        return this;
    }

    /**
     * Get the HTTP scheme depending on the insecure flag
     * @return The scheme
     */
    public String getScheme() {
        return insecure ? "http" : "https";
    }

    /**
     * Get the tags of a container
     * @param containerRef The container
     * @return The tags
     */
    public List<String> getTags(ContainerRef containerRef) {
        URI uri = URI.create("%s://%s".formatted(getScheme(), containerRef.getTagsPath()));
        OrasHttpClient.ResponseWrapper<String> response =
                client.get(uri, Map.of(Const.ACCEPT_HEADER, Const.DEFAULT_JSON_MEDIA_TYPE));
        if (switchTokenAuth(containerRef, response)) {
            response = client.get(uri, Map.of(Const.ACCEPT_HEADER, Const.DEFAULT_JSON_MEDIA_TYPE));
        }
        handleError(response);
        return JsonUtils.fromJson(response.response(), Tags.class).tags();
    }

    /**
     * Get the referrers of a container
     * @param containerRef The container
     * @param artifactType The optional artifact type
     * @return The referrers
     */
    public Referrers getReferrers(ContainerRef containerRef, @Nullable ArtifactType artifactType) {
        if (containerRef.getDigest() == null) {
            throw new OrasException("Digest is required to get referrers");
        }
        URI uri = URI.create("%s://%s".formatted(getScheme(), containerRef.getReferrersPath(artifactType)));
        OrasHttpClient.ResponseWrapper<String> response =
                client.get(uri, Map.of(Const.ACCEPT_HEADER, Const.DEFAULT_INDEX_MEDIA_TYPE));
        if (switchTokenAuth(containerRef, response)) {
            response = client.get(uri, Map.of(Const.ACCEPT_HEADER, Const.DEFAULT_INDEX_MEDIA_TYPE));
        }
        handleError(response);
        return JsonUtils.fromJson(response.response(), Referrers.class);
    }

    /**
     * Delete a manifest
     * @param containerRef The artifact
     */
    public void deleteManifest(ContainerRef containerRef) {
        URI uri = URI.create("%s://%s".formatted(getScheme(), containerRef.getManifestsPath()));
        OrasHttpClient.ResponseWrapper<String> response = client.delete(uri, Map.of());
        logResponse(response);
        if (switchTokenAuth(containerRef, response)) {
            response = client.delete(uri, Map.of());
            logResponse(response);
        }
        handleError(response);
    }

    /**
     * Push a manifest
     * @param containerRef The container
     * @param manifest The manifest
     * @return The location
     */
    public Manifest pushManifest(ContainerRef containerRef, Manifest manifest) {

        Map<String, String> annotations = manifest.getAnnotations();
        if (!annotations.containsKey(Const.ANNOTATION_CREATED) && containerRef.getDigest() == null) {
            Map<String, String> manifestAnnotations = new HashMap<>(annotations);
            manifestAnnotations.put(Const.ANNOTATION_CREATED, Const.currentTimestamp());
            manifest = manifest.withAnnotations(manifestAnnotations);
        }
        URI uri = URI.create("%s://%s".formatted(getScheme(), containerRef.getManifestsPath()));
        OrasHttpClient.ResponseWrapper<String> response = client.put(
                uri,
                JsonUtils.toJson(manifest).getBytes(),
                Map.of(Const.CONTENT_TYPE_HEADER, Const.DEFAULT_MANIFEST_MEDIA_TYPE));
        if (switchTokenAuth(containerRef, response)) {
            response = client.put(
                    uri,
                    JsonUtils.toJson(manifest).getBytes(),
                    Map.of(Const.CONTENT_TYPE_HEADER, Const.DEFAULT_MANIFEST_MEDIA_TYPE));
        }
        logResponse(response);
        handleError(response);
        if (manifest.getSubject() != null) {
            // https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pushing-manifests-with-subject
            if (!response.headers().containsKey(Const.OCI_SUBJECT_HEADER.toLowerCase())) {
                throw new OrasException(
                        "Subject was set on manifest but not OCI subject header was returned. Legecy flow not implemented");
            }
        }
        return getManifest(containerRef);
    }

    /**
     * Push a manifest
     * @param containerRef The container
     * @param index The index
     * @return The location
     */
    public Index pushIndex(ContainerRef containerRef, Index index) {
        URI uri = URI.create("%s://%s".formatted(getScheme(), containerRef.getManifestsPath()));
        OrasHttpClient.ResponseWrapper<String> response = client.put(
                uri,
                JsonUtils.toJson(index).getBytes(),
                Map.of(Const.CONTENT_TYPE_HEADER, Const.DEFAULT_INDEX_MEDIA_TYPE));
        if (switchTokenAuth(containerRef, response)) {
            response = client.put(
                    uri,
                    JsonUtils.toJson(index).getBytes(),
                    Map.of(Const.CONTENT_TYPE_HEADER, Const.DEFAULT_INDEX_MEDIA_TYPE));
        }
        logResponse(response);
        handleError(response);
        return getIndex(containerRef);
    }

    /**
     * Delete a blob
     * @param containerRef The container
     */
    public void deleteBlob(ContainerRef containerRef) {
        URI uri = URI.create("%s://%s".formatted(getScheme(), containerRef.getBlobsPath()));
        OrasHttpClient.ResponseWrapper<String> response = client.delete(uri, Map.of());
        logResponse(response);
        // Switch to bearer auth if needed and retry first request
        if (switchTokenAuth(containerRef, response)) {
            response = client.delete(uri, Map.of());
            logResponse(response);
        }
        handleError(response);
    }

/**
     * Mount a blob from another repository
     * @param containerRef The container reference
     * @param digest The digest of the blob to mount
     * @param from The source repository
     * @return The response from the registry
     */
    public OrasHttpClient.ResponseWrapper<String> mountBlob(ContainerRef containerRef, String digest, String from) {
        String mountUrl = String.format(
            "%s://%s/v2/%s/blobs/uploads/?mount=%s&from=%s",
            getScheme(),
            containerRef.getRegistry(),
            containerRef.getRepository(),
            digest,
            from
        );

        URI uri = URI.create(mountUrl);
        OrasHttpClient.ResponseWrapper<String> response = client.post(uri, new byte[0], Map.of());

        if (response.statusCode() != 201) {
            throw new OrasException("Failed to mount blob: " + response.response());
        }

        return response;
    }

    /**
     * Upload an ORAS artifact
     * @param containerRef The container
     * @param paths The paths
     * @return The manifest
     */
    public Manifest pushArtifact(ContainerRef containerRef, LocalPath... paths) {
        return pushArtifact(containerRef, ArtifactType.unknown(), Annotations.empty(), Config.empty(), paths);
    }

    /**
     * Upload an ORAS artifact
     * @param containerRef The container
     * @param artifactType The artifact type
     * @param paths The paths
     * @return The manifest
     */
    public Manifest pushArtifact(ContainerRef containerRef, ArtifactType artifactType, LocalPath... paths) {
        return pushArtifact(containerRef, artifactType, Annotations.empty(), Config.empty(), paths);
    }

    /**
     * Upload an ORAS artifact
     * @param containerRef The container
     * @param artifactType The artifact type
     * @param annotations The annotations
     * @param paths The paths
     * @return The manifest
     */
    public Manifest pushArtifact(
            ContainerRef containerRef, ArtifactType artifactType, Annotations annotations, LocalPath... paths) {
        return pushArtifact(containerRef, artifactType, annotations, Config.empty(), paths);
    }

    /**
     * Download an ORAS artifact
     * @param containerRef The container
     * @param path The path
     * @param overwrite Overwrite
     */
    public void pullArtifact(ContainerRef containerRef, Path path, boolean overwrite) {

        // Only collect layer that are files
        List<Layer> layers = collectLayers(containerRef, false);
        if (layers.isEmpty()) {
            LOG.info("Skipped pulling layers without file name in '{}'", Const.ANNOTATION_TITLE);
            return;
        }
        for (Layer layer : layers) {
            try (InputStream is = fetchBlob(containerRef.withDigest(layer.getDigest()))) {
                // Unpack or just copy blob
                if (Boolean.parseBoolean(layer.getAnnotations().getOrDefault(Const.ANNOTATION_ORAS_UNPACK, "false"))) {
                    LOG.debug("Extracting blob to: {}", path);

                    // Uncompress the tar.gz archive and verify digest if present
                    LocalPath tempArchive = ArchiveUtils.uncompress(is, layer.getMediaType());
                    String expectedDigest = layer.getAnnotations().get(Const.ANNOTATION_ORAS_CONTENT_DIGEST);
                    if (expectedDigest != null) {
                        LOG.trace("Expected digest: {}", expectedDigest);
                        String actualDigest = containerRef.getAlgorithm().digest(tempArchive.getPath());
                        LOG.trace("Actual digest: {}", actualDigest);
                        if (!expectedDigest.equals(actualDigest)) {
                            throw new OrasException(
                                    "Digest mismatch: expected %s but got %s".formatted(expectedDigest, actualDigest));
                        }
                    }

                    // Extract the tar
                    ArchiveUtils.untar(Files.newInputStream(tempArchive.getPath()), path);

                } else {
                    Path targetPath = path.resolve(
                            layer.getAnnotations().getOrDefault(Const.ANNOTATION_TITLE, layer.getDigest()));
                    LOG.debug("Copying blob to: {}", targetPath);
                    Files.copy(
                            is,
                            targetPath,
                            overwrite ? StandardCopyOption.REPLACE_EXISTING : StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (IOException e) {
                throw new OrasException("Failed to pull artifact", e);
            }
        }
    }

    /**
     * Upload an ORAS artifact
     * @param containerRef The container
     * @param artifactType The artifact type. Can be null
     * @param annotations The annotations
     * @param config The config
     * @param paths The paths
     * @return The manifest
     */
    public Manifest pushArtifact(
            ContainerRef containerRef,
            ArtifactType artifactType,
            Annotations annotations,
            @Nullable Config config,
            LocalPath... paths) {
        Manifest manifest = Manifest.empty().withArtifactType(artifactType);
        Map<String, String> manifestAnnotations = new HashMap<>(annotations.manifestAnnotations());
        if (!manifestAnnotations.containsKey(Const.ANNOTATION_CREATED) && containerRef.getDigest() == null) {
            manifestAnnotations.put(Const.ANNOTATION_CREATED, Const.currentTimestamp());
        }
        manifest = manifest.withAnnotations(manifestAnnotations);
        if (config != null) {
            config = config.withAnnotations(annotations);
            manifest = manifest.withConfig(config);
        }

        // Push layers
        List<Layer> layers = pushLayers(containerRef, paths);

        // Push the config like any other blob
        Config pushedConfig = pushConfig(containerRef, config != null ? config : Config.empty());

        // Add layer and config
        manifest = manifest.withLayers(layers).withConfig(pushedConfig);

        // Push the manifest
        manifest = pushManifest(containerRef, manifest);
        LOG.debug(
                "Manifest pushed to: {}",
                containerRef.withDigest(manifest.getDescriptor().getDigest()));
        return manifest;
    }

    private void writeManifest(Manifest manifest, ManifestDescriptor descriptor, Path folder) throws IOException {
        Path blobs = folder.resolve("blobs");
        String manifestDigest = descriptor.getDigest();
        SupportedAlgorithm manifestAlgorithm = SupportedAlgorithm.fromDigest(manifestDigest);
        Path manifestFile = blobs.resolve(manifestAlgorithm.getPrefix())
                .resolve(SupportedAlgorithm.getDigest(descriptor.getDigest()));
        Path manifestPrefixDirectory = blobs.resolve(manifestAlgorithm.getPrefix());
        if (!Files.exists(manifestPrefixDirectory)) {
            Files.createDirectory(manifestPrefixDirectory);
        }
        // Skip if already exists
        if (Files.exists(manifestFile)) {
            LOG.debug("Manifest already exists: {}", manifestFile);
            return;
        }
        Files.writeString(manifestFile, JsonUtils.toJson(manifest));
    }

    private void writeConfig(ContainerRef containerRef, Config config, Path folder) throws IOException {
        Path blobs = folder.resolve("blobs");
        String configDigest = config.getDigest();
        SupportedAlgorithm configAlgorithm = SupportedAlgorithm.fromDigest(configDigest);
        Path configFile =
                blobs.resolve(configAlgorithm.getPrefix()).resolve(SupportedAlgorithm.getDigest(config.getDigest()));
        Path configPrefixDirectory = blobs.resolve(configAlgorithm.getPrefix());
        if (!Files.exists(configPrefixDirectory)) {
            Files.createDirectory(configPrefixDirectory);
        }
        // Skip if already exists
        if (Files.exists(configFile)) {
            LOG.debug("Config already exists: {}", configFile);
            return;
        }
        // Write the data from data or fetch the blob
        if (config.getData() != null) {
            Files.write(configFile, config.getDataBytes());
        } else {
            try (InputStream is = fetchBlob(containerRef.withDigest(configDigest))) {
                Files.copy(is, configFile);
            }
        }
    }

    /**
     * Copy the container ref into oci-layout
     * @param containerRef The container
     * @param folder The folder
     */
    public void copy(ContainerRef containerRef, Path folder) {
        if (!Files.isDirectory(folder)) {
            throw new OrasException("Folder does not exist: %s".formatted(folder));
        }

        try {

            // Create blobs directory if needed
            Path blobs = folder.resolve("blobs");
            Files.createDirectories(blobs);
            OciLayout ociLayout = OciLayout.fromJson("{\"imageLayoutVersion\":\"1.0.0\"}");

            // Write oci layout
            Files.writeString(folder.resolve("oci-layout"), ociLayout.toJson());

            String contentType = getContentType(containerRef);

            // Single manifest
            if (contentType.equals(Const.DEFAULT_MANIFEST_MEDIA_TYPE)) {

                // Write manifest as any blob
                Manifest manifest = getManifest(containerRef);
                ManifestDescriptor descriptor = manifest.getDescriptor();
                Config sourceConfig = manifest.getConfig();
                writeManifest(manifest, descriptor, folder);

                // Write the index.json
                Index index = Index.fromManifests(List.of(descriptor));
                Path indexFile = folder.resolve("index.json");
                Files.writeString(indexFile, index.toJson());

                // Write config as any blob
                writeConfig(containerRef, sourceConfig, folder);
            }
            // Index
            else {
                Index index = getIndex(containerRef);

                // Write all manifests and their config
                for (ManifestDescriptor descriptor : index.getManifests()) {
                    Manifest manifest = getManifest(containerRef.withDigest(descriptor.getDigest()));
                    writeManifest(manifest, descriptor, folder);
                    Config config = manifest.getConfig();
                    writeConfig(containerRef, config, folder);
                }

                // Write index
                Path indexFile = folder.resolve("index.json");
                Files.writeString(indexFile, index.toJson());
            }

            // Write all layer
            for (Layer layer : collectLayers(containerRef, true)) {
                try (InputStream is = fetchBlob(containerRef.withDigest(layer.getDigest()))) {

                    // Algorithm
                    SupportedAlgorithm algorithm = SupportedAlgorithm.fromDigest(layer.getDigest());

                    Path prefixDirectory = blobs.resolve(algorithm.getPrefix());
                    if (!Files.exists(prefixDirectory)) {
                        Files.createDirectory(prefixDirectory);
                    }
                    Path blobFile = prefixDirectory.resolve(SupportedAlgorithm.getDigest(layer.getDigest()));
                    // Skip if already exists
                    if (Files.exists(blobFile)) {
                        LOG.debug("Blob already exists: {}", blobFile);
                        continue;
                    }
                    Files.copy(is, blobFile);
                    LOG.debug("Copied blob to {}", blobFile);
                }
            }
        } catch (IOException e) {
            throw new OrasException("Failed to copy container", e);
        }
    }

    /**
     * Copy an artifact from one container to another
     * @param targetRegistry The target registry
     * @param sourceContainer The source container
     * @param targetContainer The target container
     */
    public void copy(Registry targetRegistry, ContainerRef sourceContainer, ContainerRef targetContainer) {

        // Copy config
        Manifest sourceManifest = getManifest(sourceContainer);
        Config sourceConfig = sourceManifest.getConfig();
        targetRegistry.pushConfig(targetContainer, sourceConfig);

        // Push all layer
        for (Layer layer : sourceManifest.getLayers()) {
            try (InputStream is = fetchBlob(sourceContainer.withDigest(layer.getDigest()))) {
                Layer newLayer = targetRegistry
                        .pushBlobStream(targetContainer, is, layer.getSize())
                        .withMediaType(layer.getMediaType())
                        .withAnnotations(layer.getAnnotations());
                LOG.debug(
                        "Copied layer {} from {} to {}",
                        newLayer.getDigest(),
                        sourceContainer.getApiRegistry(),
                        targetContainer.getApiRegistry());
            } catch (IOException e) {
                throw new OrasException("Failed to copy artifact", e);
            }
        }

        // Copy manifest
        targetRegistry.pushManifest(targetContainer, sourceManifest);
    }

    /**
     * Attach file to an existing manifest
     * @param containerRef The container
     * @param artifactType The artifact type
     * @param paths The paths
     * @return The manifest of the new artifact
     */
    public Manifest attachArtifact(ContainerRef containerRef, ArtifactType artifactType, LocalPath... paths) {
        return attachArtifact(containerRef, artifactType, Annotations.empty(), paths);
    }

    /**
     * Attach file to an existing manifest
     * @param containerRef The container
     * @param artifactType The artifact type
     * @param annotations The annotations
     * @param paths The paths
     * @return The manifest of the new artifact
     */
    public Manifest attachArtifact(
            ContainerRef containerRef, ArtifactType artifactType, Annotations annotations, LocalPath... paths) {

        // Push layers
        List<Layer> layers = pushLayers(containerRef, paths);

        // Get the subject from the manifest
        Subject subject = getManifest(containerRef).getDescriptor().toSubject();

        // Add created annotation if not present since we push with digest
        Map<String, String> manifestAnnotations = annotations.manifestAnnotations();
        if (!manifestAnnotations.containsKey(Const.ANNOTATION_CREATED)) {
            manifestAnnotations.put(Const.ANNOTATION_CREATED, Const.currentTimestamp());
        }

        // assemble manifest
        Manifest manifest = Manifest.empty()
                .withArtifactType(artifactType)
                .withAnnotations(manifestAnnotations)
                .withLayers(layers)
                .withSubject(subject);

        return pushManifest(
                containerRef.withDigest(
                        SupportedAlgorithm.SHA256.digest(manifest.toJson().getBytes(StandardCharsets.UTF_8))),
                manifest);
    }

    /**
     * Push a blob from file
     * @param containerRef The container
     * @param blob The blob
     * @return The layer
     */
    public Layer pushBlob(ContainerRef containerRef, Path blob) {
        return pushBlob(containerRef, blob, Map.of());
    }

    /**
     * Push a blob from file
     * @param containerRef The container
     * @param blob The blob
     * @param annotations The annotations
     * @return The layer
     */
    public Layer pushBlob(ContainerRef containerRef, Path blob, Map<String, String> annotations) {
        String digest = containerRef.getAlgorithm().digest(blob);
        LOG.debug("Digest: {}", digest);
        if (hasBlob(containerRef.withDigest(digest))) {
            LOG.info("Blob already exists: {}", digest);
            return Layer.fromFile(blob).withAnnotations(annotations);
        }
        URI uri = URI.create(
                "%s://%s".formatted(getScheme(), containerRef.withDigest(digest).getBlobsUploadDigestPath()));
        OrasHttpClient.ResponseWrapper<String> response = client.upload(
                "POST", uri, Map.of(Const.CONTENT_TYPE_HEADER, Const.APPLICATION_OCTET_STREAM_HEADER_VALUE), blob);
        logResponse(response);

        // Switch to bearer auth if needed and retry first request
        if (switchTokenAuth(containerRef, response)) {
            response = client.upload(
                    "POST", uri, Map.of(Const.CONTENT_TYPE_HEADER, Const.APPLICATION_OCTET_STREAM_HEADER_VALUE), blob);
            logResponse(response);
        }

        // Accepted single POST push
        if (response.statusCode() == 201) {
            return Layer.fromFile(blob).withAnnotations(annotations);
        }

        // We need to push via PUT
        if (response.statusCode() == 202) {
            String location = response.headers().get(Const.LOCATION_HEADER.toLowerCase());
            // Ensure location is absolute URI
            if (!location.startsWith("http") && !location.startsWith("https")) {
                location = "%s://%s/%s"
                        .formatted(getScheme(), containerRef.getApiRegistry(), location.replaceFirst("^/", ""));
            }
            LOG.debug("Location header: {}", location);
            response = client.upload(
                    "PUT",
                    URI.create("%s&digest=%s".formatted(location, digest)),
                    Map.of(Const.CONTENT_TYPE_HEADER, Const.APPLICATION_OCTET_STREAM_HEADER_VALUE),
                    blob);
            if (response.statusCode() == 201) {
                LOG.debug("Successful push: {}", response.response());
            } else {
                throw new OrasException("Failed to push layer: %s".formatted(response.response()));
            }
        }

        handleError(response);
        return Layer.fromFile(blob).withAnnotations(annotations);
    }

    /**
     * Push config
     * @param containerRef The container
     * @param config The config
     * @return The config
     */
    public Config pushConfig(ContainerRef containerRef, Config config) {
        Layer layer = pushBlob(containerRef, config.getDataBytes());
        LOG.debug("Config pushed: {}", layer.getDigest());
        return config;
    }

    /**
     * Push the blob for the given layer in a single post request. Might not be supported by all registries
     * Fallback to POST/then PUT (end-4a) if not supported
     * @param containerRef The container ref
     * @param data The data
     * @return The layer
     */
    public Layer pushBlob(ContainerRef containerRef, byte[] data) {
        String digest = containerRef.getAlgorithm().digest(data);
        if (hasBlob(containerRef.withDigest(digest))) {
            LOG.info("Blob already exists: {}", digest);
            return Layer.fromData(containerRef, data);
        }
        URI uri = URI.create(
                "%s://%s".formatted(getScheme(), containerRef.withDigest(digest).getBlobsUploadDigestPath()));
        OrasHttpClient.ResponseWrapper<String> response =
                client.post(uri, data, Map.of(Const.CONTENT_TYPE_HEADER, Const.APPLICATION_OCTET_STREAM_HEADER_VALUE));
        logResponse(response);

        // Switch to bearer auth if needed and retry first request
        if (switchTokenAuth(containerRef, response)) {
            response = client.post(
                    uri, data, Map.of(Const.CONTENT_TYPE_HEADER, Const.APPLICATION_OCTET_STREAM_HEADER_VALUE));
            logResponse(response);
        }

        // Accepted single POST push
        if (response.statusCode() == 201) {
            return Layer.fromData(containerRef, data);
        }

        // We need to push via PUT
        if (response.statusCode() == 202) {
            String location = response.headers().get(Const.LOCATION_HEADER.toLowerCase());
            // Ensure location is absolute URI
            if (!location.startsWith("http") && !location.startsWith("https")) {
                location = "%s://%s/%s"
                        .formatted(getScheme(), containerRef.getApiRegistry(), location.replaceFirst("^/", ""));
            }
            LOG.debug("Location header: {}", location);
            response = client.put(
                    URI.create("%s&digest=%s".formatted(location, digest)),
                    data,
                    Map.of(Const.CONTENT_TYPE_HEADER, Const.APPLICATION_OCTET_STREAM_HEADER_VALUE));
            if (response.statusCode() == 201) {
                LOG.debug("Successful push: {}", response.response());
            } else {
                throw new OrasException("Failed to push layer: %s".formatted(response.response()));
            }
        }

        handleError(response);
        return Layer.fromData(containerRef, data);
    }

    /**
     * Return if the registry contains already the blob
     * @param containerRef The container
     * @return True if the blob exists
     */
    public boolean hasBlob(ContainerRef containerRef) {
        URI uri = URI.create("%s://%s".formatted(getScheme(), containerRef.getBlobsPath()));
        OrasHttpClient.ResponseWrapper<String> response =
                client.head(uri, Map.of(Const.ACCEPT_HEADER, Const.APPLICATION_OCTET_STREAM_HEADER_VALUE));
        logResponse(response);

        // Switch to bearer auth if needed and retry first request
        if (switchTokenAuth(containerRef, response)) {
            response = client.head(uri, Map.of(Const.ACCEPT_HEADER, Const.APPLICATION_OCTET_STREAM_HEADER_VALUE));
            logResponse(response);
        }
        return response.statusCode() == 200;
    }

    /**
     * Get the blob for the given digest. Not be suitable for large blobs
     * @param containerRef The container
     * @return The blob as bytes
     */
    public byte[] getBlob(ContainerRef containerRef) {
        if (!hasBlob(containerRef)) {
            throw new OrasException(new OrasHttpClient.ResponseWrapper<>("", 404, Map.of()));
        }
        URI uri = URI.create("%s://%s".formatted(getScheme(), containerRef.getBlobsPath()));
        OrasHttpClient.ResponseWrapper<String> response =
                client.get(uri, Map.of(Const.ACCEPT_HEADER, Const.APPLICATION_OCTET_STREAM_HEADER_VALUE));
        logResponse(response);

        // Switch to bearer auth if needed and retry first request
        if (switchTokenAuth(containerRef, response)) {
            response = client.get(uri, Map.of(Const.ACCEPT_HEADER, Const.APPLICATION_OCTET_STREAM_HEADER_VALUE));
            logResponse(response);
        }
        handleError(response);
        return response.response().getBytes();
    }

    /**
     * Fetch blob and save it to file
     * @param containerRef The container
     * @param path The path to save the blob
     */
    public void fetchBlob(ContainerRef containerRef, Path path) {
        if (!hasBlob(containerRef)) {
            throw new OrasException(new OrasHttpClient.ResponseWrapper<>("", 404, Map.of()));
        }
        URI uri = URI.create("%s://%s".formatted(getScheme(), containerRef.getBlobsPath()));
        OrasHttpClient.ResponseWrapper<Path> response =
                client.download(uri, Map.of(Const.ACCEPT_HEADER, Const.APPLICATION_OCTET_STREAM_HEADER_VALUE), path);
        logResponse(response);
        handleError(response);
    }

    /**
     * Fetch blob and return it as input stream
     * @param containerRef The container
     * @return The input stream
     */
    public InputStream fetchBlob(ContainerRef containerRef) {
        if (!hasBlob(containerRef)) {
            throw new OrasException(new OrasHttpClient.ResponseWrapper<>("", 404, Map.of()));
        }
        URI uri = URI.create("%s://%s".formatted(getScheme(), containerRef.getBlobsPath()));
        OrasHttpClient.ResponseWrapper<InputStream> response =
                client.download(uri, Map.of(Const.ACCEPT_HEADER, Const.APPLICATION_OCTET_STREAM_HEADER_VALUE));
        logResponse(response);
        handleError(response);
        return response.response();
    }

    /**
     * Get the manifest of a container
     * @param containerRef The container
     * @return The manifest and it's associated descriptor
     */
    public Manifest getManifest(ContainerRef containerRef) {
        OrasHttpClient.ResponseWrapper<String> response = getManifestResponse(containerRef);
        logResponse(response);
        handleError(response);
        String contentType = response.headers().get(Const.CONTENT_TYPE_HEADER.toLowerCase());
        if (contentType.equals(Const.DEFAULT_INDEX_MEDIA_TYPE)) {
            throw new OrasException(
                    "Expected manifest but got index. Probably a multi-platform image instead of artifact");
        }
        String size = response.headers().get(Const.CONTENT_LENGTH_HEADER.toLowerCase());
        String digest = response.headers().get(Const.DOCKER_CONTENT_DIGEST_HEADER.toLowerCase());
        ManifestDescriptor descriptor =
                ManifestDescriptor.of(contentType, digest, size == null ? 0 : Long.parseLong(size));
        return JsonUtils.fromJson(response.response(), Manifest.class).withDescriptor(descriptor);
    }

    /**
     * Get the index of a container
     * @param containerRef The container
     * @return The index and it's associated descriptor
     */
    public Index getIndex(ContainerRef containerRef) {
        OrasHttpClient.ResponseWrapper<String> response = getManifestResponse(containerRef);
        logResponse(response);
        handleError(response);
        String contentType = response.headers().get(Const.CONTENT_TYPE_HEADER.toLowerCase());
        if (!contentType.equals(Const.DEFAULT_INDEX_MEDIA_TYPE)) {
            throw new OrasException("Expected index but got %s".formatted(contentType));
        }
        String size = response.headers().get(Const.CONTENT_LENGTH_HEADER.toLowerCase());
        String digest = response.headers().get(Const.DOCKER_CONTENT_DIGEST_HEADER.toLowerCase());
        ManifestDescriptor descriptor =
                ManifestDescriptor.of(contentType, digest, size == null ? 0 : Long.parseLong(size));
        return JsonUtils.fromJson(response.response(), Index.class).withDescriptor(descriptor);
    }

    /**
     * Get a manifest response
     * @param containerRef The container
     * @return The response
     */
    private OrasHttpClient.ResponseWrapper<String> getManifestResponse(ContainerRef containerRef) {
        URI uri = URI.create("%s://%s".formatted(getScheme(), containerRef.getManifestsPath()));
        OrasHttpClient.ResponseWrapper<String> response =
                client.head(uri, Map.of(Const.ACCEPT_HEADER, Const.MANIFEST_ACCEPT_TYPE));
        logResponse(response);

        // Switch to bearer auth if needed and retry first request
        if (switchTokenAuth(containerRef, response)) {
            response = client.head(uri, Map.of(Const.ACCEPT_HEADER, Const.MANIFEST_ACCEPT_TYPE));
            logResponse(response);
        }
        handleError(response);
        return client.get(uri, Map.of("Accept", Const.MANIFEST_ACCEPT_TYPE));
    }

    /**
     * Switch the current authentication to token auth
     * @param response The response
     */
    private boolean switchTokenAuth(ContainerRef containerRef, OrasHttpClient.ResponseWrapper<String> response) {
        if (response.statusCode() == 401 && !(authProvider instanceof BearerTokenProvider)) {
            LOG.debug("Requesting token with token flow");
            setAuthProvider(new BearerTokenProvider(authProvider).refreshToken(containerRef, response));
            return true;
        }
        // Need token refresh (expired or wrong scope)
        if ((response.statusCode() == 401 || response.statusCode() == 403)
                && authProvider instanceof BearerTokenProvider) {
            LOG.debug("Requesting new token with username password flow");
            setAuthProvider(((BearerTokenProvider) authProvider).refreshToken(containerRef, response));
            return true;
        }
        return false;
    }

    /**
     * Handle an error response
     * @param responseWrapper The response
     */
    @SuppressWarnings("unchecked")
    private void handleError(OrasHttpClient.ResponseWrapper<?> responseWrapper) {
        if (responseWrapper.statusCode() >= 400) {
            if (responseWrapper.response() instanceof String) {
                LOG.debug("Response: {}", responseWrapper.response());
                throw new OrasException((OrasHttpClient.ResponseWrapper<String>) responseWrapper);
            }
            throw new OrasException(new OrasHttpClient.ResponseWrapper<>("", responseWrapper.statusCode(), Map.of()));
        }
    }

    /**
     * Log the response
     * @param response The response
     */
    private void logResponse(OrasHttpClient.ResponseWrapper<?> response) {
        LOG.debug("Status Code: {}", response.statusCode());
        LOG.debug("Headers: {}", response.headers());
        // Only log non-binary responses
        if (response.response() instanceof String) {
            LOG.debug("Response: {}", response.response());
        }
    }

    /**
     * Push a blob using input stream to avoid loading the whole blob in memory
     * @param containerRef the container ref
     * @param input the input stream
     * @param size the size of the blob
     * @return The Layer containing the uploaded blob information
     * @throws OrasException if upload fails or digest calculation fails
     */
    public Layer pushBlobStream(ContainerRef containerRef, InputStream input, long size) {
        try {
            // TODO: Replace by chunk upload
            Path tempFile = Files.createTempFile("oras", "layer");
            Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING);
            return pushBlob(containerRef, tempFile);
        } catch (IOException e) {
            throw new OrasException("Failed to push blob", e);
        }
    }

    private List<Layer> pushLayers(ContainerRef containerRef, LocalPath... paths) {
        List<Layer> layers = new ArrayList<>();
        for (LocalPath path : paths) {
            try {
                // Create tar.gz archive for directory
                if (Files.isDirectory(path.getPath())) {
                    LocalPath tempTar = ArchiveUtils.tar(path);
                    LocalPath tempArchive = ArchiveUtils.compress(tempTar, path.getMediaType());
                    try (InputStream is = Files.newInputStream(tempArchive.getPath())) {
                        long size = Files.size(tempArchive.getPath());
                        Layer layer = pushBlobStream(containerRef, is, size)
                                .withMediaType(path.getMediaType())
                                .withAnnotations(Map.of(
                                        Const.ANNOTATION_TITLE,
                                        path.getPath().getFileName().toString(),
                                        Const.ANNOTATION_ORAS_CONTENT_DIGEST,
                                        containerRef.getAlgorithm().digest(tempTar.getPath()),
                                        Const.ANNOTATION_ORAS_UNPACK,
                                        "true"));
                        layers.add(layer);
                        LOG.info("Uploaded directory: {}", layer.getDigest());
                    }
                    Files.delete(tempArchive.getPath());
                } else {
                    try (InputStream is = Files.newInputStream(path.getPath())) {
                        long size = Files.size(path.getPath());
                        Layer layer = pushBlobStream(containerRef, is, size)
                                .withMediaType(path.getMediaType())
                                .withAnnotations(Map.of(
                                        Const.ANNOTATION_TITLE,
                                        path.getPath().getFileName().toString()));
                        layers.add(layer);
                        LOG.info("Uploaded: {}", layer.getDigest());
                    }
                }
            } catch (IOException e) {
                throw new OrasException("Failed to push artifact", e);
            }
        }
        return layers;
    }

    /**
     * Get blob as stream to avoid loading into memory
     * @param containerRef The container ref
     * @return The input stream
     */
    public InputStream getBlobStream(ContainerRef containerRef) {
        // Similar to fetchBlob()
        return fetchBlob(containerRef);
    }

    private String getContentType(ContainerRef containerRef) {
        URI uri = URI.create("%s://%s".formatted(getScheme(), containerRef.getManifestsPath()));
        OrasHttpClient.ResponseWrapper<String> response =
                client.head(uri, Map.of(Const.ACCEPT_HEADER, Const.MANIFEST_ACCEPT_TYPE));
        logResponse(response);

        // Switch to bearer auth if needed and retry first request
        if (switchTokenAuth(containerRef, response)) {
            response = client.head(uri, Map.of(Const.ACCEPT_HEADER, Const.MANIFEST_ACCEPT_TYPE));
            logResponse(response);
        }
        handleError(response);
        return response.headers().get(Const.CONTENT_TYPE_HEADER.toLowerCase());
    }

    /**
     * Collect layers from the container
     * @param containerRef The container
     * @param includeAll Include all layers or only the ones with title annotation
     * @return The layers
     */
    private List<Layer> collectLayers(ContainerRef containerRef, boolean includeAll) {
        List<Layer> layers = new LinkedList<>();
        String contentType = getContentType(containerRef);
        if (contentType.equals(Const.DEFAULT_MANIFEST_MEDIA_TYPE)) {
            return getManifest(containerRef).getLayers();
        }
        Index index = getIndex(containerRef);
        for (ManifestDescriptor manifestDescriptor : index.getManifests()) {
            List<Layer> manifestLayers = getManifest(containerRef.withDigest(manifestDescriptor.getDigest()))
                    .getLayers();
            for (Layer manifestLayer : manifestLayers) {
                if (manifestLayer.getAnnotations().isEmpty()
                        || !manifestLayer.getAnnotations().containsKey(Const.ANNOTATION_TITLE)) {
                    if (includeAll) {
                        LOG.debug("Including layer without title annotation: {}", manifestLayer.getDigest());
                        layers.add(manifestLayer);
                    }
                    LOG.debug("Skipping layer without title annotation: {}", manifestLayer.getDigest());
                    continue;
                }
                layers.add(manifestLayer);
            }
        }
        return layers;
    }

    /**
     * Builder for the registry
     */
    public static class Builder {

        private final Registry registry = new Registry();

        /**
         * Hidden constructor
         */
        private Builder() {
            // Hide constructor
        }

        /**
         * Return a new builder with default authentication using existing host auth
         * @return The builder
         */
        public Builder defaults() {
            registry.setAuthProvider(new FileStoreAuthenticationProvider());
            return this;
        }

        /**
         * Return a new builder with username and password authentication
         * @param username The username
         * @param password The password
         * @return The builder
         */
        public Builder defaults(String username, String password) {
            registry.setAuthProvider(new UsernamePasswordProvider(username, password));
            return this;
        }

        /**
         * Return a new builder with insecure communication and not authentification
         * @return The builder
         */
        public Builder insecure() {
            registry.setInsecure(true);
            registry.setSkipTlsVerify(true);
            registry.setAuthProvider(new NoAuthProvider());
            return this;
        }

        /**
         * Set the auth provider
         * @param authProvider The auth provider
         * @return The builder
         */
        public Builder withAuthProvider(AuthProvider authProvider) {
            registry.setAuthProvider(authProvider);
            return this;
        }

        /**
         * Set the insecure flag
         * @param insecure Insecure
         * @return The builder
         */
        public Builder withInsecure(boolean insecure) {
            registry.setInsecure(insecure);
            return this;
        }

        /**
         * Set the skip TLS verify flag
         * @param skipTlsVerify Skip TLS verify
         * @return The builder
         */
        public Builder withSkipTlsVerify(boolean skipTlsVerify) {
            registry.setSkipTlsVerify(skipTlsVerify);
            return this;
        }

        /**
         * Return a new builder
         * @return The builder
         */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * Build the registry
         * @return The registry
         */
        public Registry build() {
            return registry.build();
        }
    }
}
