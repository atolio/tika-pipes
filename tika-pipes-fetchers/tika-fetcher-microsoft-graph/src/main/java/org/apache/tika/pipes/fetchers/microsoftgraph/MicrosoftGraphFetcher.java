/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.pipes.fetchers.microsoftgraph;

import com.azure.identity.ClientCertificateCredentialBuilder;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.models.odataerrors.ODataError;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import org.apache.commons.io.FileUtils;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.pipes.fetchers.core.Fetcher;
import org.apache.tika.pipes.fetchers.core.FetcherConfig;
import org.apache.tika.pipes.fetchers.microsoftgraph.config.MicrosoftGraphFetcherConfig;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fetches files from Microsoft Graph API.
 * Fetch keys are ${siteDriveId},${driveItemId}
 */
@Extension
public class MicrosoftGraphFetcher implements Fetcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(MicrosoftGraphFetcher.class);
    private static final Set<String> NO_RETRY_ERROR_CODES = new HashSet<>();

    static {
        NO_RETRY_ERROR_CODES.add("itemNotFound");
        NO_RETRY_ERROR_CODES.add("invalidRequest");
        NO_RETRY_ERROR_CODES.add("Forbidden");
        NO_RETRY_ERROR_CODES.add("Unauthorized");
    }

    private GraphServiceClient graphClient;

    @Override
    public InputStream fetch(FetcherConfig fetcherConfig, String fetchKey, Map<String, Object> fetchMetadata, Map<String, Object> responseMetadata) throws IOException {
        MicrosoftGraphFetcherConfig config = (MicrosoftGraphFetcherConfig) fetcherConfig;
        if (config.getScopes().isEmpty()) {
            config.getScopes().add("https://graph.microsoft.com/.default");
        }
        String[] scopes = config
                .getScopes()
                .toArray(new String[0]);
        if (config.getCertificateBytesBase64() != null) {
            graphClient = new GraphServiceClient(new ClientCertificateCredentialBuilder()
                    .clientId(config.getClientId())
                    .tenantId(config.getTenantId())
                    .pfxCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(config.getCertificateBytesBase64())))
                    .clientCertificatePassword(config.getCertificatePassword())
                    .build(), scopes);
        } else if (config.getClientSecret() != null) {
            graphClient = new GraphServiceClient(new ClientSecretCredentialBuilder()
                    .tenantId(config.getTenantId())
                    .clientId(config.getClientId())
                    .clientSecret(config.getClientSecret())
                    .build(), scopes);
        }
        int tries = 0;
        Exception ex;
        List<Long> throttleSeconds = config.getThrottleSeconds() == null ? List.of(0L) : config.getThrottleSeconds();
        do {
            long start = System.currentTimeMillis();
            try {
                String[] fetchKeySplit = fetchKey.split(",");
                String siteDriveId = fetchKeySplit[0];
                String driveItemId = fetchKeySplit[1];
                InputStream is = graphClient
                        .drives()
                        .byDriveId(siteDriveId)
                        .items()
                        .byDriveItemId(driveItemId)
                        .content()
                        .get();

                if (is == null) {
                    throw new RuntimeException("Empty input stream when we tried to parse " + fetchKey);
                }
                if (config.isSpoolToTemp()) {
                    File tempFile = Files
                            .createTempFile("spooled-temp", ".dat")
                            .toFile();
                    FileUtils.copyInputStreamToFile(is, tempFile);
                    LOGGER.info("Spooled to temp file {}", tempFile);
                    return TikaInputStream.get(tempFile.toPath());
                }
                return TikaInputStream.get(is);
            } catch (Exception e) {
                LOGGER.warn("Exception fetching on retry=" + tries + ", exception type: " + e.getClass().getSimpleName(), e);
                if (e instanceof ODataError) {
                    LOGGER.info("Caught ODataError directly for key {}: {}", fetchKey, e.getMessage());
                    ODataError oDataError = (ODataError) e;
                    String errorCode = oDataError.getError().getCode();

                    LOGGER.warn("ODataError code: {}, message: {}", errorCode, oDataError.getError().getMessage());
                    if (errorCode != null && NO_RETRY_ERROR_CODES.contains(errorCode)) {
                        LOGGER.warn("Hit a no retry error code '{}' for key {}. Not retrying.", errorCode, fetchKey);
                        if ("itemNotFound".equals(errorCode)) {
                            throw new IOException("Microsoft Graph item not found: " + fetchKey);
                        }
                        throw new IOException("Microsoft Graph error: " + errorCode, e);
                    }
                } else if (e.getCause() instanceof ODataError) {
                    LOGGER.info("Caught ODataError as cause for key {}: {}", fetchKey, e.getMessage());
                    ODataError oDataError = (ODataError) e.getCause();
                    String errorCode = oDataError.getError().getCode();
                    LOGGER.warn("ODataError code: {}, message: {}", errorCode, oDataError.getError().getMessage());
                    if (errorCode != null && NO_RETRY_ERROR_CODES.contains(errorCode)) {
                        LOGGER.warn("Hit a no retry error code '{}' for key {}. Not retrying.", errorCode, fetchKey);
                        if ("itemNotFound".equals(errorCode)) {
                            throw new IOException("Microsoft Graph item not found: " + fetchKey);
                        }
                        throw new IOException("Microsoft Graph error: " + errorCode, e);
                    }
                }
                ex = e;
            } finally {
                long elapsed = System.currentTimeMillis() - start;
                LOGGER.debug("Total to fetch {}", elapsed);
            }
            handlePostFetch(throttleSeconds, tries);
        } while (++tries < throttleSeconds.size());
        throw new RuntimeException("Could not fetch " + fetchKey, ex);
    }

    private static void handlePostFetch(List<Long> throttleSeconds, int tries) {
        if (throttleSeconds.isEmpty()) {
            return;
        }
        Long secs = throttleSeconds.get(tries);
        LOGGER.warn("Sleeping for {} seconds before retry", secs);
        try {
            Thread.sleep(secs);
        } catch (InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
        }
    }
}
