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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Version} class.
 *
 * <p>Verifies User-Agent string generation for identifying AgentScope Java clients.
 */
class VersionTest {

    @Test
    void testVersionConstant() throws IOException {
        // Read the real project version from a Maven-filtered test resource.
        Properties props = new Properties();
        try (InputStream in =
                getClass().getResourceAsStream("/agentscope-test-version.properties")) {
            Assertions.assertNotNull(in, "test version.properties should exist on classpath");
            props.load(in);
        }
        String expectedVersion = props.getProperty("version");
        Assertions.assertNotNull(expectedVersion, "version property should be present");

        // When Maven resource filtering was not run (e.g. running directly from an IDE), the
        // value is an unfiltered ${project.version} literal and there is no real version to
        // cross-check, so skip the strict assertions. CI runs Maven, so filtering always applies.
        boolean filtered = !expectedVersion.contains("${");
        Assumptions.assumeTrue(
                filtered, "skipped: Maven resource filtering did not run (non-Maven/IDE run)");

        // Strict cross-check: the runtime version must match the Maven project version exactly.
        Assertions.assertEquals(
                expectedVersion, Version.VERSION, "VERSION must match the Maven project version");

        // Semantic version format check.
        Assertions.assertTrue(
                Version.VERSION.matches("\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?"),
                "VERSION should be a valid semver: " + Version.VERSION);
    }

    @Test
    void testGetUserAgent_Format() {
        // Get User-Agent string
        String userAgent = Version.getUserAgent();

        // Verify not null/empty
        Assertions.assertNotNull(userAgent, "User-Agent should not be null");
        Assertions.assertFalse(userAgent.isEmpty(), "User-Agent should not be empty");

        // Verify format: agentscope-java/{version}; java/{java_version}; platform/{os}
        Assertions.assertTrue(
                userAgent.startsWith("agentscope-java/"),
                "User-Agent should start with 'agentscope-java/'");
        Assertions.assertTrue(userAgent.contains("; java/"), "User-Agent should contain '; java/'");
        Assertions.assertTrue(
                userAgent.contains("; platform/"), "User-Agent should contain '; platform/'");
    }

    @Test
    void testGetUserAgent_ContainsVersion() {
        String userAgent = Version.getUserAgent();

        // Verify contains AgentScope version
        Assertions.assertTrue(
                userAgent.contains(Version.VERSION),
                "User-Agent should contain AgentScope version: " + Version.VERSION);
    }

    @Test
    void testGetUserAgent_ContainsJavaVersion() {
        String userAgent = Version.getUserAgent();
        String javaVersion = System.getProperty("java.version");

        // Verify contains Java version
        Assertions.assertTrue(
                userAgent.contains(javaVersion),
                "User-Agent should contain Java version: " + javaVersion);
    }

    @Test
    void testGetUserAgent_ContainsPlatform() {
        String userAgent = Version.getUserAgent();
        String platform = System.getProperty("os.name");

        // Verify contains platform/OS name
        Assertions.assertTrue(
                userAgent.contains(platform), "User-Agent should contain platform: " + platform);
    }

    @Test
    void testGetUserAgent_Consistency() {
        // Verify multiple calls return the same value
        String userAgent1 = Version.getUserAgent();
        String userAgent2 = Version.getUserAgent();

        Assertions.assertEquals(
                userAgent1,
                userAgent2,
                "Multiple calls to getUserAgent() should return consistent results");
    }

    @Test
    void testGetUserAgent_ExampleFormat() {
        String userAgent = Version.getUserAgent();

        // Verify matches expected pattern (relaxed check for different environments)
        String pattern = "^agentscope-java/.+; java/[0-9.]+; platform/.+$";
        Assertions.assertTrue(
                userAgent.matches(pattern),
                "User-Agent should match pattern: " + pattern + ", but got: " + userAgent);
    }
}
