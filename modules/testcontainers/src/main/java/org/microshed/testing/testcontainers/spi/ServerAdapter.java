/*
 * Copyright (c) 2019 IBM Corporation and others
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.microshed.testing.testcontainers.spi;

import java.io.File;
import java.util.Map;
import java.util.Optional;

import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * An adapter for application runtimes can use to define information including:
 * <ul>
 * <li>Default HTTP port</li>
 * <li>Default HTTPS port</li>
 * <li>Default startup timeout</li>
 * <li>Default Dockerfile</li>
 * <li>Default readiness path</li>
 * </ul>
 *
 * @author aguibert
 */
public interface ServerAdapter {

    default int getPriority() {
        return 0;
    }

    /**
     * @return The default HTTP port for this runtime
     */
    int getDefaultHttpPort();

    /**
     * @return The default HTTPS port for this runtime
     */
    int getDefaultHttpsPort();

    /**
     * @return The default amount of time (in seconds) to wait for a runtime to start before
     *         assuming that application start has failed and aborting the start process.
     *
     *         Implementation note:
     *         It is reccomended to increase the default app start timeout when running in
     *         remote CI environments such as TravisCI by checking for the CI=true env var.
     */
    default int getDefaultAppStartTimeout() {
        return "true".equalsIgnoreCase(System.getenv("CI")) ? 90 : 30;
    }

    /**
     * Sets configuration properties on an already-started runtime.
     * If the runtime has not been started yet, it may be more efficient to use
     * other means of setting properties, such as <code>GenericContainer.withEnv(String,String)</code>
     * in the case of Testcontainers.
     * Calling this method will override any previously set properties using this method
     *
     * @param properties A map of key/value pairs that should be set on the runtime
     */
    default void setConfigProperties(Map<String, String> properties) {
        throw new UnsupportedOperationException("Dynamically setting config properties is not supported for the default ServerAdapter.");
    }

    /**
     * Returns a default Docker image using the current ServerAdapter as the base
     * later and the provided appFile in addition to any other vendor-speicific items
     * layerd on top.
     *
     * @param appFile The applicaiton file to include in the resulting Docker image
     * @return The default docker image including the supplied appFile
     */
    default ImageFromDockerfile getDefaultImage(File appFile) {
        throw new UnsupportedOperationException("Dynamically building image is not supported for the default ServerAdapter.");
    }

    /**
     * Defines the readiness path for the Server which will be used by default when the developer did not specify such value.
     * The implementation can choose to return null and then no value is defined by default.
     * @return the readiness path to be used by default.
     */
    Optional<String> getReadinessPath();
}
