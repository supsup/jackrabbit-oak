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
package org.apache.jackrabbit.oak.plugins.nodetype;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.addAll;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Lists.newArrayListWithCapacity;
import static com.google.common.collect.Sets.newHashSet;
import static org.apache.jackrabbit.JcrConstants.JCR_DEFAULTPRIMARYTYPE;
import static org.apache.jackrabbit.JcrConstants.JCR_ISMIXIN;
import static org.apache.jackrabbit.JcrConstants.JCR_MANDATORY;
import static org.apache.jackrabbit.JcrConstants.JCR_MIXINTYPES;
import static org.apache.jackrabbit.JcrConstants.JCR_NODETYPENAME;
import static org.apache.jackrabbit.JcrConstants.JCR_PRIMARYTYPE;
import static org.apache.jackrabbit.JcrConstants.JCR_UUID;
import static org.apache.jackrabbit.oak.api.CommitFailedException.CONSTRAINT;
import static org.apache.jackrabbit.oak.api.Type.NAMES;
import static org.apache.jackrabbit.oak.api.Type.UNDEFINED;
import static org.apache.jackrabbit.oak.api.Type.UNDEFINEDS;
import static org.apache.jackrabbit.oak.plugins.nodetype.NodeTypeConstants.JCR_IS_ABSTRACT;
import static org.apache.jackrabbit.oak.plugins.nodetype.NodeTypeConstants.OAK_NAMED_CHILD_NODE_DEFINITIONS;
import static org.apache.jackrabbit.oak.plugins.nodetype.NodeTypeConstants.OAK_RESIDUAL_CHILD_NODE_DEFINITIONS;
import static org.apache.jackrabbit.oak.plugins.nodetype.NodeTypeConstants.OAK_SUPERTYPES;

import java.util.List;
import java.util.Set;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeState;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

class EffectiveType {

    private final List<NodeState> types;

    EffectiveType(List<NodeState> types) {
        this.types = checkNotNull(types);
    }

    CommitFailedException constraintViolation(
            int code, String path, String message) {
        return new CommitFailedException(
                CONSTRAINT, code, path + this + ": " + message);
    }

    @Nonnull
    Set<String> findMissingMandatoryItems(NodeState node) {
        ImmutableSet.Builder<String> builder = ImmutableSet.builder();

        for (NodeState type : types) {
            PropertyState properties =
                    type.getProperty("oak:mandatoryProperties");
            for (String name : properties.getValue(NAMES)) {
                if (!node.hasProperty(name)) {
                    builder.add(name);
                }
            }

            PropertyState childNodes =
                    type.getProperty("oak:mandatoryChildNodes");
            for (String name : childNodes.getValue(NAMES)) {
                if (!node.hasChildNode(name)) {
                    builder.add(name);
                }
            }
        }

        return builder.build();
    }

    /**
     * Finds a matching definition for a property with the given name and type.
     *
     * @param property modified property
     * @return matching property definition, or {@code null}
     */
    @CheckForNull
    NodeState getDefinition(PropertyState property) {
        String propertyName = property.getName();
        Type<?> propertyType = property.getType();

        String escapedName;
        if (JCR_PRIMARYTYPE.equals(propertyName)) {
            escapedName = "oak:primaryType";
        } else if (JCR_MIXINTYPES.equals(propertyName)) {
            escapedName = "oak:mixinTypes";
        } else if (JCR_UUID.equals(propertyName)) {
            escapedName = "oak:uuid";
        } else {
            escapedName = propertyName;
        }

        String definedType = propertyType.toString();
        String undefinedType;
        if (propertyType.isArray()) {
            undefinedType = UNDEFINEDS.toString();
        } else {
            undefinedType = UNDEFINED.toString();
        }

        // Find matching named property definition
        for (NodeState type : types) {
            NodeState named = type.getChildNode("oak:namedPropertyDefinitions");
            NodeState definitions = named.getChildNode(escapedName);

            NodeState definition = definitions.getChildNode(definedType);
            if (definition.exists()) {
                return definition;
            }

            definition = definitions.getChildNode(undefinedType);
            if (definition.exists()) {
                return definition;
            }

            // OAK-822: a mandatory definition always overrides residual ones
            // TODO: unnecessary if the OAK-713 fallback wasn't needed below
            for (ChildNodeEntry entry : definitions.getChildNodeEntries()) {
                definition = entry.getNodeState();
                if (definition.getBoolean(JCR_MANDATORY)) {
                    return definition;
                }
            }

// TODO: Fall back to residual definitions until we have consensus on OAK-713
//          throw new ConstraintViolationException(
//                "No matching definition found for property " + propertyName);
        }

        // Find matching residual property definition
        for (NodeState type : types) {
            NodeState residual =
                    type.getChildNode("oak:residualPropertyDefinitions");
            NodeState definition = residual.getChildNode(definedType);
            if (!definition.exists()) {
                definition = residual.getChildNode(undefinedType);
            }
            if (definition.exists()) {
                return definition;
            }
        }

        return null;
    }

    /**
     * Finds a matching definition for a child node with the given name and
     * types.
     *
     * @param nameWithIndex child node name, possibly with an SNS index
     * @param nodeType effective types of the child node
     * @return matching child node definition, or {@code null} if not found
     */
    @CheckForNull
    NodeState getDefinition(String nameWithIndex, Iterable<String> nodeType) {
        String nodeName = getNameWithoutIndex(nameWithIndex);

        // Find matching named child node definition
        for (NodeState type : types) {
            NodeState named = type.getChildNode("oak:namedChildNodeDefinitions");
            NodeState definitions = named.getChildNode(nodeName);

            for (String typeName : nodeType) {
                NodeState definition = definitions.getChildNode(typeName);
                if (definition.exists()) {
                    return definition;
                }
            }

            // OAK-822: a mandatory definition always overrides alternatives
            // TODO: unnecessary if the OAK-713 fallback wasn't needed below
            for (ChildNodeEntry entry : definitions.getChildNodeEntries()) {
                NodeState definition = entry.getNodeState();
                if (definition.getBoolean(JCR_MANDATORY)) {
                    return definition;
                }
            }

// TODO: Fall back to residual definitions until we have consensus on OAK-713
//          throw new ConstraintViolationException(
//                  "Incorrect node type of child node " + nodeName);
        }

        // Find matching residual child node definition
        for (NodeState type : types) {
            NodeState residual =
                    type.getChildNode("oak:residualChildNodeDefinitions");
            if (residual.exists()) {
                for (String typeName : nodeType) {
                    NodeState definition = residual.getChildNode(typeName);
                    if (definition.exists()) {
                        return definition;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Finds the default node type for a child node with the given name.
     *
     * @param nameWithIndex child node name, possibly with an SNS index
     * @return default type, or {@code null} if not found
     */
    @CheckForNull
    String getDefaultType(String nameWithIndex) {
        String name = getNameWithoutIndex(nameWithIndex);

        for (NodeState type : types) {
            NodeState named = type
                    .getChildNode(OAK_NAMED_CHILD_NODE_DEFINITIONS)
                    .getChildNode(name);
            NodeState residual = type
                    .getChildNode(OAK_RESIDUAL_CHILD_NODE_DEFINITIONS);

            for (ChildNodeEntry entry : concat(
                    named.getChildNodeEntries(),
                    residual.getChildNodeEntries())) {
                NodeState definition = entry.getNodeState();
                String defaultType = definition.getName(JCR_DEFAULTPRIMARYTYPE);
                if (defaultType != null) {
                    return defaultType;
                }
            }
        }

        return null;
    }

    Set<String> getTypeNames() {
        Set<String> names = newHashSet();
        for (NodeState type : types) {
            names.add(type.getName(JCR_NODETYPENAME));
            addAll(names, type.getNames(OAK_SUPERTYPES));
        }
        return names;
    }

    /**
     * Collects the primary and mixin types and all related supertypes
     * of the given child node and returns them as an effective node type.
     *
     * @param types the {@code /jcr:system/jcr:nodeTypes} subtree
     * @param path path of the parent node
     * @param name name of the child node
     * @param primary name of the primary type
     * @param mixins names of the mixin types
     * @throws CommitFailedException if the effective node type is invalid
     */
    EffectiveType computeEffectiveType(
            NodeState types, String path,
            String name, String primary, Iterable<String> mixins)
            throws CommitFailedException {
        List<NodeState> list = Lists.newArrayList();

        NodeState type = types.getChildNode(primary);
        if (!type.exists()) {
            throw constraintViolation(
                    11, path, "The primary type " + primary
                    + " of child node " + name + " does not exist");
        } else if (type.getBoolean(JCR_ISMIXIN)) {
            throw constraintViolation(
                    12, path, "Mixin type " + primary
                    + " used as the primary type of child node " + name);
        } else if (type.getBoolean(JCR_IS_ABSTRACT)) {
            throw constraintViolation(
                    13, path, "Abstract type " + primary
                    + " used as the primary type of child node " + name);
        } else {
            list.add(type);
        }

        // mixin types
        for (String mixin : mixins) {
            type = types.getChildNode(mixin);
            if (!type.exists()) {
                throw constraintViolation(
                        14, path, "The mixin type " + mixin
                        + " of child node " + name + " does not exist");
            } else if (!type.getBoolean(JCR_ISMIXIN)) {
                throw constraintViolation(
                        15, path, "Primary type " + mixin
                        + " used as a mixin type of child node " + name);
            } else if (type.getBoolean(JCR_IS_ABSTRACT)) {
                throw constraintViolation(
                        16, path, "Abstract type " + mixin
                        + " used as a mixin type of child node " + name);
            } else {
                list.add(type);
            }
        }

        return new EffectiveType(list);
    }


    //------------------------------------------------------------< Object >--

    @Override
    public String toString() {
        List<String> names = newArrayListWithCapacity(types.size());
        for (NodeState type : types) {
            names.add(type.getName(JCR_NODETYPENAME));
        }
        return names.toString();
    }

    //-----------------------------------------------------------< private >--

    private static String getNameWithoutIndex(String name) {
        int n = name.length();
        if (n > 3 && name.charAt(n - 1) == ']') {
            int i = n - 2;
            while (i > 1 && Character.isDigit(name.charAt(i))) {
                i--;
            }
            if (name.charAt(i) == '[') {
                return name.substring(0, i);
            }
        }
        return name;
    }

}
