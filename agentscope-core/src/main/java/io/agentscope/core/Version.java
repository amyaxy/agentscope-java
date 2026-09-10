/*
 * Copyright 2024-2026 the original author or authors.
 *
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
 */
package io.agentscope.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * AgentScope version and User-Agent information.
 *
 * <p>Provides a unified User-Agent string for all model requests to identify AgentScope Java
 * clients and collect usage statistics.
 */
public final class Version {

    private static final String VERSION_RESOURCE = "/META-INF/agentscope/version.properties";

    /**
     * AgentScope Java version.
     *
     * <p>Injected at build time by Maven resource filtering from {@code ${project.version}}.
     */
    public static final String VERSION = resolveVersion();

    private Version() {
        // Utility class - prevent instantiation
    }

    private static String resolveVersion() {
        try (InputStream in = Version.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String version = props.getProperty("version");
                // Guard against an unfiltered ${project.version} literal, e.g. when running
                // from an IDE that does not run Maven resource filtering.
                if (version != null && !version.isBlank() && !version.startsWith("${")) {
                    return version.trim();
                }
            }
        } catch (IOException e) {
            // Fall through to the fallback below.
        }
        return "unknown";
    }

    /**
     * Generate standard User-Agent string for all models.
     *
     * <p>Format: {@code agentscope-java/{version}; java/{java_version}; platform/{os}}
     *
     * @return unified User-Agent string
     */
    public static String getUserAgent() {
        return String.format(
                "agentscope-java/%s; java/%s; platform/%s",
                VERSION, System.getProperty("java.version"), System.getProperty("os.name"));
    }
}
