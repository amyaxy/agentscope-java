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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Regression tests for issue #3328: registering a tool whose name is already bound to a different
 * tool must fail fast instead of silently replacing it (including MCP tools shadowing local
 * tools), while intentional replacement stays available through an explicit API.
 */
@Tag("unit")
@DisplayName("Duplicate tool registration (issue #3328)")
class DuplicateToolRegistrationTest {

    private Toolkit toolkit;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
    }

    static class LocalTools {
        @Tool(name = "dup", description = "local version")
        public String dup(@ToolParam(name = "x", description = "x") String x) {
            return "LOCAL " + x;
        }
    }

    static class OtherTools {
        @Tool(name = "dup", description = "remote version")
        public String dup(@ToolParam(name = "y", description = "y") String y) {
            return "REMOTE " + y;
        }
    }

    /** One object declaring two different methods under the same tool name. */
    static class SelfClashingTools {
        @Tool(name = "dup", description = "first method")
        public String first(@ToolParam(name = "a", description = "a") String a) {
            return a;
        }

        @Tool(name = "dup", description = "second method")
        public String second(@ToolParam(name = "b", description = "b") String b) {
            return b;
        }
    }

    static AgentTool simpleTool(String name, String description) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.text(description));
            }
        };
    }

    private McpClientWrapper mcpWrapperServing(String clientName, String toolName) {
        McpClientWrapper wrapper = mock(McpClientWrapper.class);
        when(wrapper.getName()).thenReturn(clientName);
        when(wrapper.initialize()).thenReturn(Mono.empty());
        McpSchema.Tool mcpTool = mock(McpSchema.Tool.class);
        when(mcpTool.name()).thenReturn(toolName);
        when(mcpTool.description()).thenReturn("mcp " + toolName);
        when(mcpTool.inputSchema())
                .thenReturn(
                        new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null));
        when(wrapper.listTools()).thenReturn(Mono.just(List.of(mcpTool)));
        return wrapper;
    }

    @Test
    @DisplayName("Duplicate name from a different tool object fails fast")
    void differentToolSameNameThrows() {
        toolkit.registerTool(new LocalTools());

        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class, () -> toolkit.registerTool(new OtherTools()));

        // The message names both tools so the conflict is diagnosable, plus the escape hatches.
        assertTrue(ex.getMessage().contains("dup"), ex.getMessage());
        assertTrue(ex.getMessage().contains("local version"), ex.getMessage());
        assertTrue(ex.getMessage().contains("remote version"), ex.getMessage());
        assertTrue(ex.getMessage().contains("replaceAgentTool"), ex.getMessage());
        assertTrue(ex.getMessage().contains("toolNamePrefix"), ex.getMessage());

        // The original registration must stay intact (no partial mutation).
        assertEquals("local version", toolkit.getTool("dup").getDescription());
    }

    @Test
    @DisplayName("Re-registering the same tool object is an idempotent refresh")
    void sameToolObjectReregistrationAllowed() {
        LocalTools source = new LocalTools();
        toolkit.registerTool(source);
        assertDoesNotThrow(() -> toolkit.registerTool(source));
        assertEquals("local version", toolkit.getTool("dup").getDescription());
    }

    @Test
    @DisplayName("Registering the same AgentTool instance twice is allowed")
    void sameInstanceTwiceAllowed() {
        AgentTool tool = simpleTool("dup", "only one");
        toolkit.registerAgentTool(tool);
        assertDoesNotThrow(() -> toolkit.registerAgentTool(tool));
        assertSame(tool, toolkit.getTool("dup"));
    }

    @Test
    @DisplayName("Different AgentTool instances sharing a name fail fast")
    void differentInstancesSameNameThrows() {
        AgentTool first = simpleTool("dup", "first");
        AgentTool second = simpleTool("dup", "second");
        toolkit.registerAgentTool(first);

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> toolkit.registerAgentTool(second));
        assertTrue(ex.getMessage().contains("first"), ex.getMessage());
        assertTrue(ex.getMessage().contains("second"), ex.getMessage());
        assertSame(first, toolkit.getTool("dup"));
    }

    @Test
    @DisplayName("replaceAgentTool overrides intentionally")
    void replaceAgentToolOverrides() {
        AgentTool first = simpleTool("dup", "first");
        AgentTool second = simpleTool("dup", "second");
        toolkit.registerAgentTool(first);

        toolkit.replaceAgentTool(second);
        assertSame(second, toolkit.getTool("dup"));
    }

    @Test
    @DisplayName("Two methods of one object sharing a tool name fail fast")
    void selfClashingToolObjectThrows() {
        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () -> toolkit.registerTool(new SelfClashingTools()));
        assertTrue(ex.getMessage().contains("first method"), ex.getMessage());
        assertTrue(ex.getMessage().contains("second method"), ex.getMessage());
    }

    @Test
    @DisplayName("MCP tool shadowing a local tool is reported at registration")
    void mcpToolShadowingLocalToolFailsFast() {
        toolkit.registerTool(new LocalTools());
        McpClientWrapper wrapper = mcpWrapperServing("test-mcp", "dup");

        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () -> toolkit.registerMcpClient(wrapper).block());
        assertTrue(ex.getMessage().contains("MCP tool from client 'test-mcp'"), ex.getMessage());
        assertEquals("local version", toolkit.getTool("dup").getDescription());
    }

    @Test
    @DisplayName("Prefixed MCP registration coexists with a same-named local tool")
    void prefixedMcpToolCoexists() {
        toolkit.registerTool(new LocalTools());
        McpClientWrapper wrapper = mcpWrapperServing("test-mcp", "dup");

        toolkit.registration().mcpClient(wrapper).mcpToolNamePrefix("srv__").apply();

        assertEquals("local version", toolkit.getTool("dup").getDescription());
        assertTrue(toolkit.getToolNames().contains("srv__dup"));
    }

    @Test
    @DisplayName("Toolkit.copy rebinds the meta tool under the same name intentionally")
    void copyRebindsMetaToolWithoutConflict() {
        toolkit.registerTool(new LocalTools());
        toolkit.registerMetaTool();

        Toolkit copy = assertDoesNotThrow(() -> toolkit.copy());
        assertSame(toolkit.getTool("dup"), copy.getTool("dup"));
        // The copy's meta tool is a fresh instance bound to the copy's group manager.
        org.junit.jupiter.api.Assertions.assertNotNull(copy.getTool("reset_equipped_tools"));
    }
}
