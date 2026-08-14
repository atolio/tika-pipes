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

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.FileTooLongException;
import org.apache.tika.io.FilenameUtils;
import org.apache.tika.io.TemporaryResources;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.pipes.core.exception.TikaPipesException;
import org.apache.tika.pipes.fetchers.core.Fetcher;
import org.apache.tika.pipes.fetchers.core.FetcherConfig;
import org.apache.tika.pipes.fetchers.gcs.config.GcsFetcherConfig;
import org.apache.tika.utils.StringUtils;
import org.pf4j.Extension;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Extension
@Slf4j
public class GcsFetcher implements Fetcher {
    private static final String PREFIX = "gcs";

    // Do not retry when the object is absent or the caller cannot read it.
    // Retrying either only delays the same failure.
    private static final Set<Integer> NO_RETRY_STATUS_CODES = new HashSet<>();
    private static final ConcurrentMap<GcsFetcherConfig, Storage> clientMap = new ConcurrentHashMap<>();

    static {
        NO_RETRY_STATUS_CODES.add(401);
        NO_RETRY_STATUS_CODES.add(403);
        NO_RETRY_STATUS_CODES.add(404);
    }

    @Override
    public InputStream fetch(FetcherConfig fetcherConfig, String fetchKey, Map<String, Object> fetchMetadata, Map<String, Object> responseMetadata) {
        GcsFetcherConfig gcsFetcherConfig = (GcsFetcherConfig) fetcherConfig;
        List<Long> throttleSeconds = gcsFetcherConfig.getThrottleSeconds() == null ? List.of(0L) : gcsFetcherConfig.getThrottleSeconds();
        int tries = 0;
        IOException ex;
        do {
            Storage storage = clientMap.computeIfAbsent(gcsFetcherConfig, k -> new GcsClientManager(gcsFetcherConfig).getStorage());
            String prefix = gcsFetcherConfig.getPrefix();
            if (org.apache.commons.lang3.StringUtils.isNotBlank(prefix) && !prefix.endsWith("/")) {
                prefix += "/";
            }
            String theFetchKey;
            if (StringUtils.isBlank(prefix) || fetchKey.startsWith(prefix)) {
                theFetchKey = fetchKey;
            } else {
                theFetchKey = prefix + fetchKey;
            }
            try {
                long start = System.currentTimeMillis();
                InputStream is = fetchImpl(storage, gcsFetcherConfig, theFetchKey, fetchMetadata, responseMetadata);
                long elapsed = System.currentTimeMillis() - start;
                log.debug("total to fetch {}", elapsed);
                return is;
            } catch (StorageException e) {
                if (NO_RETRY_STATUS_CODES.contains(e.getCode())) {
                    log.warn("Hit a no retry status code for key {}. Not retrying." + tries, theFetchKey, e);
                    throw new RuntimeException(e);
                }
                log.warn("client exception fetching on retry=" + tries, e);
                ex = new IOException(e);
            } catch (IOException e) {
                log.warn("client exception fetching on retry=" + tries, e);
                ex = e;
            }
            handlePostFail(throttleSeconds, tries);
        } while (++tries < throttleSeconds.size());

        throw new TikaPipesException("GCS operation failed after max number of retries " + tries, ex);
    }

    private static void handlePostFail(List<Long> throttleSeconds, int tries) {
        if (throttleSeconds.isEmpty()) {
            return;
        }
        log.warn("sleeping for {} seconds before retry", throttleSeconds.get(tries));
        try {
            Thread.sleep(throttleSeconds.get(tries) * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted");
        }
    }

    private InputStream fetchImpl(Storage storage, GcsFetcherConfig gcsFetcherConfig, String fetchKey, Map<String, Object> fetchMetadata, Map<String, Object> responseMetadata) throws IOException {
        TemporaryResources tmp = null;
        InputStream objectStream = null;
        String bucket = gcsFetcherConfig.getBucket();
        try {
            long start = System.currentTimeMillis();
            Blob blob = storage.get(BlobId.of(bucket, fetchKey));
            if (blob == null) {
                throw new IOException("Object not found in bucket " + bucket + ": " + fetchKey);
            }

            long length = blob.getSize() == null ? -1 : blob.getSize();
            responseMetadata.put(Metadata.CONTENT_LENGTH, Long.toString(length));
            long maxLength = gcsFetcherConfig.getMaxLength();
            if (maxLength > -1) {
                if (length > maxLength) {
                    throw new FileTooLongException(length, maxLength);
                }
            }
            log.debug("took {} ms to fetch file's metadata", System.currentTimeMillis() - start);

            if (gcsFetcherConfig.isExtractUserMetadata() && blob.getMetadata() != null) {
                for (Map.Entry<String, String> e : blob.getMetadata().entrySet()) {
                    fetchMetadata.put(PREFIX + ":" + e.getKey(), e.getValue());
                }
            }

            objectStream = Channels.newInputStream(blob.reader());
            if (!gcsFetcherConfig.isSpoolToTemp()) {
                return TikaInputStream.get(objectStream);
            } else {
                start = System.currentTimeMillis();
                tmp = new TemporaryResources();
                Path tmpPath = tmp.createTempFile(FilenameUtils.getSuffixFromPath(fetchKey));
                Files.copy(objectStream, tmpPath, StandardCopyOption.REPLACE_EXISTING);
                Metadata metadata = new Metadata();
                TikaInputStream tis = TikaInputStream.get(tmpPath, metadata, tmp);
                log.debug("took {} ms to fetch metadata and copy to local tmp file", System.currentTimeMillis() - start);
                return tis;
            }
        } catch (Throwable e) {
            if (objectStream != null) {
                log.info("Closing GCS object stream due to exception");
                objectStream.close();
            }
            if (tmp != null) {
                tmp.close();
            }
            throw e;
        }
    }
}
