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
package org.apache.tika.pipes.fetchers.gcs;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.pipes.fetchers.gcs.config.GcsFetcherConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;

/**
 * Builds the Storage client for a fetcher config. Unlike the S3 client, a
 * Storage instance is thread safe, so one instance per config is shared.
 */
@Slf4j
public class GcsClientManager {

    private final GcsFetcherConfig gcsFetcherConfig;
    private final Storage storage;

    public GcsClientManager(GcsFetcherConfig gcsFetcherConfig) {
        this.gcsFetcherConfig = gcsFetcherConfig;
        this.storage = initialize();
    }

    private Storage initialize() {
        StorageOptions.Builder builder = StorageOptions.newBuilder();

        if (StringUtils.isNotBlank(gcsFetcherConfig.getProjectId())) {
            builder.setProjectId(gcsFetcherConfig.getProjectId());
        }

        try {
            builder.setCredentials(credentials());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load GCS credentials", e);
        }

        return builder.build().getService();
    }

    /**
     * Returns credentials from the configured service account key, falling back
     * to Application Default Credentials. The fallback is the deployed path:
     * workload identity mounts the token the default credentials pick up, so no
     * key material needs to reach this config.
     */
    private GoogleCredentials credentials() throws IOException {
        String keyBase64 = gcsFetcherConfig.getServiceAccountKeyBase64();
        if (StringUtils.isBlank(keyBase64)) {
            log.info("No service account key configured, using application default credentials");
            return GoogleCredentials.getApplicationDefault();
        }

        return GoogleCredentials.fromStream(
                new ByteArrayInputStream(Base64.getDecoder().decode(keyBase64)));
    }

    public Storage getStorage() {
        return storage;
    }
}
