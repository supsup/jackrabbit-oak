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

package org.apache.jackrabbit.oak.plugins.document.bundlor;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import javax.annotation.CheckForNull;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.jackrabbit.JcrConstants;
import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;

import static org.apache.jackrabbit.oak.api.Type.STRINGS;
import static org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState.EMPTY_NODE;
import static org.apache.jackrabbit.oak.plugins.memory.PropertyStates.createProperty;

public class BundledTypesRegistry {
    private final Map<String, DocumentBundlor> bundlors;

    public BundledTypesRegistry(Map<String, DocumentBundlor> bundlors) {
        this.bundlors = ImmutableMap.copyOf(bundlors);
    }

    public static BundledTypesRegistry from(NodeState configParentState){
        Map<String, DocumentBundlor> bundlors = Maps.newHashMap();
        for (ChildNodeEntry e : configParentState.getChildNodeEntries()){
            bundlors.put(e.getName(), DocumentBundlor.from(e.getNodeState()));
        }
        return new BundledTypesRegistry(bundlors);
    }

    @CheckForNull
    public DocumentBundlor getBundlor(NodeState state) {
        //Prefer mixin (as they are more specific) over primaryType
        for (String mixin : getMixinNames(state)){
            DocumentBundlor bundlor = bundlors.get(mixin);
            if (bundlor != null){
                return bundlor;
            }
        }
        return bundlors.get(getPrimaryTypeName(state));
    }

    private static String getPrimaryTypeName(NodeState nodeState) {
        PropertyState ps = nodeState.getProperty(JcrConstants.JCR_PRIMARYTYPE);
        return (ps == null) ? JcrConstants.NT_BASE : ps.getValue(Type.NAME);
    }

    private static Iterable<String> getMixinNames(NodeState nodeState) {
        PropertyState ps = nodeState.getProperty(JcrConstants.JCR_MIXINTYPES);
        return (ps == null) ? Collections.<String>emptyList() : ps.getValue(Type.NAMES);
    }

    //~--------------------------------------------< Builder >

    public static BundledTypesRegistryBuilder builder(){
        return new BundledTypesRegistryBuilder(EMPTY_NODE.builder());
    }

    public static class BundledTypesRegistryBuilder {
        private final NodeBuilder builder;

        public BundledTypesRegistryBuilder(NodeBuilder builder) {
            this.builder = builder;
        }

        public TypeBuilder forType(String typeName){
            return new TypeBuilder(this, builder.child(typeName));
        }

        public TypeBuilder forType(String typeName, String ... includes){
            TypeBuilder typeBuilder = forType(typeName);
            for (String include : includes){
                typeBuilder.include(include);
            }
            return typeBuilder;
        }

        public BundledTypesRegistry buildRegistry() {
            return BundledTypesRegistry.from(builder.getNodeState());
        }

        public NodeState build(){
            return builder.getNodeState();
        }

        public static class TypeBuilder {
            private final BundledTypesRegistryBuilder parent;
            private final NodeBuilder typeBuilder;
            private final Set<String> patterns = Sets.newHashSet();

            private TypeBuilder(BundledTypesRegistryBuilder parent, NodeBuilder typeBuilder) {
                this.parent = parent;
                this.typeBuilder = typeBuilder;
            }

            public TypeBuilder include(String pattern){
                patterns.add(pattern);
                return this;
            }

            public BundledTypesRegistry buildRegistry(){
                setupPatternProp();
                return parent.buildRegistry();
            }

            public BundledTypesRegistryBuilder registry(){
                setupPatternProp();
                return parent;
            }

            public NodeState build(){
                setupPatternProp();
                return parent.build();
            }

            private void setupPatternProp() {
                typeBuilder.setProperty(createProperty(DocumentBundlor.PROP_PATTERN, patterns, STRINGS));
            }
        }
    }

}