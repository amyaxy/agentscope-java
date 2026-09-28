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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Internal registry for managing tool registration and lookup.
 *
 * <p>This class maintains mappings between tool names and their implementations, along with
 * metadata about registered tool functions. It is used internally by {@link Toolkit} to organize
 * and retrieve tools.
 *
 * <p><b>Thread Safety:</b> This class is thread-safe, using {@link ConcurrentHashMap} for internal
 * storage to support concurrent tool registration and lookup operations.
 *
 * <p><b>Key Responsibilities:</b>
 * <ul>
 *   <li>Store and retrieve {@link AgentTool} implementations by name</li>
 *   <li>Maintain {@link RegisteredToolFunction} metadata for schema generation</li>
 *   <li>Support dynamic tool removal for group-based activation</li>
 * </ul>
 */
class ToolRegistry {

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();
    private final Map<String, RegisteredToolFunction> registeredTools = new ConcurrentHashMap<>();

    /**
     * Register a tool with its metadata.
     *
     * <p>Registering a name that is already bound to a <em>different</em> tool fails fast with an
     * {@link IllegalStateException} unless {@code allowReplaceExisting} is explicitly set. This prevents
     * silent shadowing (e.g. an MCP tool quietly replacing a local tool of the same name).
     *
     * @param toolName Tool name
     * @param tool AgentTool implementation
     * @param registered RegisteredToolFunction wrapper with metadata
     * @param allowReplaceExisting when true, an existing different tool under the same name is replaced.
     *     Only pass true for deliberate replacements (e.g. {@code Toolkit.replaceAgentTool} or the
     *     framework meta-tool rebind in {@code Toolkit.copy()}); passing true elsewhere would
     *     reintroduce the silent-shadowing behavior this check exists to prevent.
     * @throws IllegalStateException if the name is taken by a different tool and {@code
     *     allowReplaceExisting} is false
     */
    void registerTool(
            String toolName,
            AgentTool tool,
            RegisteredToolFunction registered,
            boolean allowReplaceExisting) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Tool name cannot be null or blank");
        }
        AgentTool existing = tools.get(toolName);
        if (existing != null && existing != tool && !allowReplaceExisting) {
            throw new IllegalStateException(
                    duplicateRegistrationMessage(toolName, existing, tool, registered));
        }
        tools.put(toolName, tool);
        registeredTools.put(toolName, registered);
    }

    /**
     * Register a tool with its metadata, failing fast on a name already bound to a different tool.
     *
     * @see #registerTool(String, AgentTool, RegisteredToolFunction, boolean)
     */
    void registerTool(String toolName, AgentTool tool, RegisteredToolFunction registered) {
        registerTool(toolName, tool, registered, false);
    }

    /**
     * Builds the user-facing message for a rejected duplicate registration. Includes the
     * descriptions of both tools (and the incoming MCP client name when applicable) so the
     * conflict can be diagnosed without guessing, plus the escape hatches.
     */
    private static String duplicateRegistrationMessage(
            String toolName,
            AgentTool existing,
            AgentTool incoming,
            RegisteredToolFunction registered) {
        String incomingSource =
                registered.getMcpClientName() == null
                        ? "local tool"
                        : "MCP tool from client '" + registered.getMcpClientName() + "'";
        return "Tool '"
                + toolName
                + "' is already registered and will not be silently replaced."
                + "  existing: ["
                + existing.getDescription()
                + "]  incoming: ["
                + incomingSource
                + ", description: "
                + incoming.getDescription()
                + "]. To resolve the name conflict: use Toolkit.replaceAgentTool(...) to replace"
                + " intentionally, remove the existing tool first, or register the incoming tool"
                + " (e.g. an MCP client) under a distinct name prefix such as toolNamePrefix."
                + " Note: MCP tools are registered after local tools, so with the prefix disabled"
                + " an MCP tool can otherwise shadow a same-named local tool.";
    }

    /**
     * Get tool by name.
     *
     * @param name Tool name
     * @return AgentTool or null if not found
     */
    AgentTool getTool(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return tools.get(name);
    }

    /**
     * Get registered tool function by name.
     *
     * @param name Tool name
     * @return RegisteredToolFunction or null if not found
     */
    RegisteredToolFunction getRegisteredTool(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return registeredTools.get(name);
    }

    /**
     * Get all tool names.
     *
     * @return Set of tool names
     */
    Set<String> getToolNames() {
        return new HashSet<>(tools.keySet());
    }

    /**
     * Get all registered tool functions.
     *
     * @return Map of tool name to RegisteredToolFunction
     */
    Map<String, RegisteredToolFunction> getAllRegisteredTools() {
        return new ConcurrentHashMap<>(registeredTools);
    }

    /**
     * Remove a tool by name.
     *
     * @param toolName Tool name to remove
     */
    void removeTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Tool name cannot be null or blank");
        }
        tools.remove(toolName);
        registeredTools.remove(toolName);
    }

    /**
     * Atomically remove a tool only if the current instance matches the expected one.
     * Uses {@link ConcurrentHashMap#remove(Object, Object)} to avoid TOCTOU races.
     *
     * @param toolName Tool name to remove
     * @param expected The expected AgentTool instance (identity comparison)
     * @return true if the tool was removed, false if it was already replaced or absent
     */
    boolean removeToolIfSame(String toolName, AgentTool expected) {
        boolean removed = tools.remove(toolName, expected);
        if (removed) {
            registeredTools.remove(toolName);
        }
        return removed;
    }

    /**
     * Remove multiple tools by names.
     *
     * @param toolNames Set of tool names to remove
     */
    void removeTools(Set<String> toolNames) {
        toolNames.forEach(this::removeTool);
    }

    /**
     * Copy all tools (and their registration metadata) from this registry to another registry.
     *
     * <p>Tools are shared by reference (they are stateless and thread-safe); only the registry
     * entries (including {@link RegisteredToolFunction} metadata) are copied, so the target is an
     * isolated registry for build-time agent isolation.
     *
     * @param target The target registry to copy tools to
     */
    void copyTo(ToolRegistry target) {
        for (Map.Entry<String, AgentTool> entry : tools.entrySet()) {
            String toolName = entry.getKey();
            AgentTool tool = entry.getValue();
            RegisteredToolFunction registered = registeredTools.get(toolName);
            target.registerTool(
                    toolName,
                    tool,
                    registered == null
                            ? null
                            : new RegisteredToolFunction(
                                    tool,
                                    registered.getExtendedModel(),
                                    registered.getMcpClientName(),
                                    registered.getPresetParameters()));
        }
    }
}
