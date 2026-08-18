/*
 * Copyright (c) 2026, 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.phases.priorityinline;

import static jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options.MaxPolymorphicDispatches;

import java.util.Objects;

import org.graalvm.nativeimage.Platform;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.graal.code.SubstrateCallingConventionType;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedUniverse;

import jdk.graal.compiler.core.common.type.StampPair;
import jdk.graal.compiler.nodes.CallTargetNode;
import jdk.graal.compiler.nodes.DirectCallTargetNode;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.common.priorityinline.DefaultInliningProvider;
import jdk.graal.compiler.phases.common.priorityinline.PolicyFactory;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

public class SubstrateInliningProvider extends DefaultInliningProvider {

    private final HostedUniverse universe;

    public SubstrateInliningProvider(HostedUniverse universe) {
        Objects.requireNonNull(universe);
        this.universe = universe;
    }

    @Override
    public PolicyFactory policy(OptionValues options) {
        return new SubstratePolicyFactory();
    }

    @Override
    public ResolvedJavaMethod methodForDevirtualizationCheck(ResolvedJavaMethod originalTargetMethod, ResolvedJavaMethod targetMethod, ResolvedJavaMethod concreteMethod,
                    ResolvedJavaType receiverType) {
        return originalTargetMethod;
    }

    @SuppressWarnings("unused")
    @Override
    public boolean isMethodForDevirtualizationInTable(ResolvedJavaMethod originalTargetMethod, ResolvedJavaMethod targetMethod, ResolvedJavaMethod concreteMethod, ResolvedJavaType receiverType) {
        return originalTargetMethod.isInVirtualMethodTable(receiverType);
    }

    @Override
    public boolean canInlineUninitialized() {
        return true;
    }

    @Override
    public DirectCallTargetNode createDirectCallTarget(ValueNode[] arguments, StampPair returnStamp, JavaType[] signature, ResolvedJavaMethod dispatchedMethod, CallTargetNode.InvokeKind invokeKind) {
        SubstrateCallingConventionType substrateCallingConventionType = ((HostedMethod) dispatchedMethod).getCallingConventionKind().toType(true);
        return new DirectCallTargetNode(arguments, returnStamp, signature, dispatchedMethod, substrateCallingConventionType, invokeKind);
    }

    @Override
    public int getMaxPolymorphicDispatches(OptionValues options) {
        if (MaxPolymorphicDispatches.hasBeenSet(options)) {
            return MaxPolymorphicDispatches.getValue(options);
        }
        // Default for SVM
        return 3;
    }

    @Override
    public boolean useMethodChecks(OptionValues options) {
        final boolean useLLVMBackend = SubstrateOptions.useLLVMBackend();
        final boolean darwinShared = Platform.includedIn(Platform.DARWIN.class) && SubstrateOptions.SharedLibrary.getValue();
        return !useLLVMBackend && !darwinShared;
    }

    @Override
    public boolean areDeoptsAllowed() {
        return false;
    }
}
