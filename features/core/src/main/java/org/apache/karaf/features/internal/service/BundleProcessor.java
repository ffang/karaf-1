/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.karaf.features.internal.service;

import java.io.File;

/**
 * Service that can process (enhance, modify, trim, ...) a bundle installation data. It's goal is similar to
 * {@link FeaturesProcessor} but may be used to alter URI location and manifest headers of bundle being installed
 * during feature installation.
 */
public interface BundleProcessor {

    /**
     * Processor is associated with a location that may be used as alternative maven repository - to resolve
     * processed bundles
     * @return
     */
    public File getProcessedBundlesRepository();

    /**
     * Returns a {@link File} for new bundle which is usually repackaged bundle pointed at by {@code originalFile}
     * but with {@code META-INF/MANIFEST.MF} processed according to rules from this procesor.
     * @param originalFile original file with bundle that may (or may not) be processed
     * @param mvnLocation {@code mvn:} location of original bundle
     * @return
     */
    public File processBundle(File originalFile, String mvnLocation) throws Exception;

}
