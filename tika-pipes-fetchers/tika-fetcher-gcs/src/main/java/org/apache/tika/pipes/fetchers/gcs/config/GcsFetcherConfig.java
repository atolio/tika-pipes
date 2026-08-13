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
package org.apache.tika.pipes.fetchers.gcs.config;

import lombok.Getter;
import lombok.Setter;
import org.apache.tika.pipes.fetchers.core.DefaultFetcherConfig;
import org.pf4j.Extension;

import java.util.List;
import java.util.Objects;

@Extension
@Getter
@Setter
public class GcsFetcherConfig extends DefaultFetcherConfig {
    private boolean spoolToTemp;
    private String bucket;
    private String prefix;
    private boolean extractUserMetadata;
    private long maxLength;
    private List<Long> throttleSeconds;

    /**
     * The GCP project owning the bucket. Optional; when blank the project is
     * taken from the ambient credentials.
     */
    private String projectId;

    /**
     * A base64 encoded service account key. Optional; when blank Application
     * Default Credentials are used, which is how workload identity supplies
     * credentials to a pod.
     */
    private String serviceAccountKeyBase64;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        GcsFetcherConfig that = (GcsFetcherConfig) o;
        return spoolToTemp == that.spoolToTemp && extractUserMetadata == that.extractUserMetadata && maxLength == that.maxLength && Objects.equals(bucket, that.bucket) && Objects.equals(prefix, that.prefix) &&
                Objects.equals(throttleSeconds, that.throttleSeconds) && Objects.equals(projectId, that.projectId) && Objects.equals(serviceAccountKeyBase64, that.serviceAccountKeyBase64);
    }

    @Override
    public int hashCode() {
        return Objects.hash(spoolToTemp, bucket, prefix, extractUserMetadata, maxLength, throttleSeconds, projectId, serviceAccountKeyBase64);
    }
}
