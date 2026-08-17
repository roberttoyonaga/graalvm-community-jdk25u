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

import static jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options.UseGraphCache;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.function.Predicate;

import com.oracle.graal.pointsto.meta.HostedProviders;
import com.oracle.svm.core.SubstrateTarget;
import com.oracle.svm.core.graal.meta.RuntimeConfiguration;
import com.oracle.svm.core.imagelayer.ImageLayerBuildingSupport;
import com.oracle.svm.core.nodes.CFunctionPrologueNode;
import com.oracle.svm.core.nodes.SubstrateMethodCallTargetNode;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedType;
import com.oracle.svm.hosted.meta.HostedUniverse;

import jdk.graal.compiler.core.common.CompilationIdentifier;
import jdk.graal.compiler.core.common.type.ObjectStamp;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.graph.NodeSourcePosition;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.options.EnumOptionKey;
import jdk.graal.compiler.options.Option;
import jdk.graal.compiler.options.OptionKey;
import jdk.graal.compiler.options.OptionType;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.OptimisticOptimizations;
import jdk.graal.compiler.phases.PhaseSuite;
import jdk.graal.compiler.phases.common.BoxNodeIdentityPhase;
import jdk.graal.compiler.phases.common.CanonicalizerPhase;
import jdk.graal.compiler.phases.common.priorityinline.CallTree;
import jdk.graal.compiler.phases.common.priorityinline.Expander;
import jdk.graal.compiler.phases.common.priorityinline.GraphCache;
import jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase;
import jdk.graal.compiler.phases.common.priorityinline.data.BenefitKind;
import jdk.graal.compiler.phases.common.priorityinline.nodes.CallTreeNode;
import jdk.graal.compiler.phases.common.priorityinline.nodes.CutoffNode;
import jdk.graal.compiler.phases.common.priorityinline.nodes.DontInlineCause;
import jdk.graal.compiler.phases.common.priorityinline.nodes.SubgraphNode;
import jdk.graal.compiler.phases.common.priorityinline.tuning.TuningPolicy;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.vm.ci.meta.AbstractJavaProfile;
import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.JavaMethodProfile;
import jdk.vm.ci.meta.JavaTypeProfile;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

public class SubstratePriorityInliningPhase extends PriorityInliningPhase {

    public static final String SUBSTRATE_PRIORITY_INLINING_PHASE = SubstratePriorityInliningPhase.class.getSimpleName();

    public static class Options {
        //@formatter:off
        @Option(help = "Use InterproceduralPartialEscapeAnalysisPhase in priorityInliner.")//
        public static final HostedOptionKey<Boolean> UseIPEA = new HostedOptionKey<>(true);

        @Option(help = "Track IPEA statistics in substratePriorityInliner.", type = OptionType.Debug)//
        public static final OptionKey<TrackIPEAMode> TrackIPEAStatistics = new EnumOptionKey<>(TrackIPEAMode.none);

        @Option(help = "Track IPEA statistics in substratePriorityInliner.", type = OptionType.Debug)//
        public static final HostedOptionKey<Integer> IPEAStatisticsHistogramBuckets = new HostedOptionKey<>(12);

        @Option(help = "Boost for CutoffNode for single escaping object.", type = OptionType.Expert)//
        public static final HostedOptionKey<Integer> IPEAVirtualEscapeBoostSingle = new HostedOptionKey<>(12);

        @Option(help = "Boost for Parent Nodes based on reduction of materializations.", type = OptionType.Expert)//
        public static final HostedOptionKey<Integer> IPEAMaterializationBoostConstant = new HostedOptionKey<>(12);

        @Option(help = "Weight of a materialization triggered by an Invoke corresponding to a CutoffNode in the CallTree for IPEA.", type = OptionType.Expert)//
        public static final HostedOptionKey<Double> IPEACutoffMaterializationWeight = new HostedOptionKey<>(0.0D);

        @Option(help = "Indicates how often to run IPEA analysis", type = OptionType.Expert)//
        public static final HostedOptionKey<Integer> IPEAFrequency = new HostedOptionKey<>(3);

        @Option(help = "Indicates how often to force IPEA analysis when otherwise inlining would not continue", type = OptionType.Expert)//
        public static final HostedOptionKey<Integer> IPEAMaxForce = new HostedOptionKey<>(2);

        @Option(help = "Size of the IR that forces a reduction in the number of IPEA runs made.", type = OptionType.Expert)//
        public static final HostedOptionKey<Integer> SizeForIPEAFrequencyDecrease = new HostedOptionKey<>(500);

        //@formatter:on
    }

    enum TrackIPEAMode {
        none(false, false, false),
        histogram(true, false, true),
        dumpLast(true, true, true),
        dumpAll(true, true, false);

        private final boolean shouldTrack;
        private final boolean verbose;
        private final boolean lastRound;

        TrackIPEAMode(boolean shouldTrack, boolean verbose, boolean lastRound) {
            this.shouldTrack = shouldTrack;
            this.verbose = verbose;
            this.lastRound = lastRound;
        }

        public boolean shouldTrack() {
            return shouldTrack;
        }

        public boolean verbose() {
            return verbose;
        }

        public boolean lastRound() {
            return lastRound;
        }
    }

    private final RuntimeConfiguration runtimeConfig;
    private final OptimisticOptimizations optimisticOpts;
    private final HostedUniverse universe;
    private final PhaseSuite<HighTierContext> highTier;
    private final boolean layeredBuild;

    @SharedGlobalPhaseState private static volatile boolean IPEAShutDownHookAdded;
    static final InterProceduralPartialEscapeAnalysisStatistics IPEAStatistics = new InterProceduralPartialEscapeAnalysisStatistics();

    private SubstratePriorityInliningPhase(CanonicalizerPhase canonicalizer, OptionValues options, RuntimeConfiguration runtimeConfig, OptimisticOptimizations optimisticOpts,
                    HostedUniverse universe, PhaseSuite<HighTierContext> highTier) {
        super(canonicalizer, options, new SubstrateInliningProvider(universe));
        this.runtimeConfig = runtimeConfig;
        this.optimisticOpts = optimisticOpts;
        this.universe = universe;
        this.highTier = highTier;
        this.layeredBuild = ImageLayerBuildingSupport.buildingExtensionLayer();
    }

    private static void registerIPEAStatisticsShutdownHook(TrackIPEAMode trackIPEAMode) {
        if (!IPEAShutDownHookAdded) {
            synchronized (InterProceduralPartialEscapeAnalysisStatistics.class) {
                if (!IPEAShutDownHookAdded) {
                    try {
                        InterProceduralPartialEscapeAnalysisStatistics.IPEAStatisticsThread thread = new InterProceduralPartialEscapeAnalysisStatistics.IPEAStatisticsThread(IPEAStatistics,
                                        trackIPEAMode, Options.IPEAStatisticsHistogramBuckets.getValue());
                        Runtime.getRuntime().addShutdownHook(thread);
                        IPEAShutDownHookAdded = true;
                    } catch (IllegalStateException ise) {
                        // VM is already in process of shutting down - ignore
                    }
                }
            }
        }
    }

    @Override
    protected boolean isForceInlinedTarget(ResolvedJavaMethod targetMethod, Invoke invoke) {
        if (targetMethod instanceof HostedMethod && inliningForbidden(invoke)) {
            return false;
        }
        return super.isForceInlinedTarget(targetMethod, invoke);
    }

    @Override
    protected void runInlining(StructuredGraph graph, HighTierContext context) {
        logNodeCount("before", graph);
        super.runInlining(graph, context);
        logNodeCount("after", graph);
        TrackIPEAMode trackIPEAMode = Options.TrackIPEAStatistics.getValue(graph.getOptions());
        if (trackIPEAMode.shouldTrack()) {
            registerIPEAStatisticsShutdownHook(trackIPEAMode);
        }
    }

    private static void logNodeCount(String when, StructuredGraph graph) {
        try (DebugContext.Scope _ = graph.getDebug().scope(SUBSTRATE_PRIORITY_INLINING_PHASE)) {
            graph.getDebug().log("[%s] Node count of %s %s inlining: %d", SUBSTRATE_PRIORITY_INLINING_PHASE, graph.method().format("%H.%n"), when, graph.getNodeCount());
        }
    }

    /**
     * Priority inlining needs the parsed graph both to estimate a callee's cost and to expand it.
     * The graph can be unavailable for methods from a prior layer or for methods that become
     * reachable too late to be parsed.
     */
    private boolean inliningForbidden(Invoke invoke) {
        Predicate<HostedMethod> unavailableMethod = method -> {
            boolean unavailable = method.compilationInfo.getCompilationGraph() == null;
            if (unavailable && layeredBuild) {
                /*
                 * We have compiled this method in a prior layer, but don't have the graph available
                 * here.
                 */
                assert method.isCompiledInPriorLayer() || !method.wrapped.reachableInCurrentLayer() : method;
            }
            return unavailable;
        };

        HostedMethod targetMethod = (HostedMethod) invoke.getTargetMethod();
        if (invoke.getInvokeKind().isDirect()) {
            return unavailableMethod.test(targetMethod);
        }
        /*
         * GR-57274 - we can relax this if we add some hooks to the inline cache logic.
         */
        return Arrays.stream(targetMethod.getImplementations()).anyMatch(unavailableMethod);
    }

    @Override
    protected CallTree createCallTree(HighTierContext context, StructuredGraph graph, Expander.Policy expanderPolicy, TuningPolicy tuningPolicy) {
        GraphCache<ResolvedJavaMethod, StructuredGraph> graphCache = new GraphCache<>();
        SubgraphNode root = createRootNode(graphCache, graph);
        return new SubstrateCallTree(expanderPolicy, tuningPolicy, context, graphCache, root, graph.getOptions());
    }

    @Override
    protected SubstrateInliningProvider getInliningProvider() {
        return (SubstrateInliningProvider) super.getInliningProvider();
    }

    private class SubstrateCallTree extends CallTree {
        private final SubgraphNode root;

        SubstrateCallTree(Expander.Policy expanderPolicy, TuningPolicy tuningPolicy, HighTierContext context, GraphCache<ResolvedJavaMethod, StructuredGraph> graphCache, SubgraphNode root,
                        OptionValues options) {
            super(SubstratePriorityInliningPhase.this.canonicalizer, expanderPolicy, tuningPolicy, context, getInliningProvider(), graphCache, root,
                            options, SubstratePriorityInliningPhase.this.directedRules);
            this.root = root;
        }

        @Override
        public GraphCache.Ref<ResolvedJavaMethod, StructuredGraph> createGraph(ResolvedJavaMethod targetMethod, Invoke invoke, boolean withNodeSourcePosition,
                        NodeSourcePosition replaceePosition, boolean inOOMEProtectedInlineContext) {
            assert targetMethod instanceof HostedMethod;

            GraphCache<ResolvedJavaMethod, StructuredGraph> selectedGraphCache = getGraphCache(inOOMEProtectedInlineContext);
            GraphCache.Ref<ResolvedJavaMethod, StructuredGraph> ref = UseGraphCache.getValue(getOptions()) ? selectedGraphCache.getRef(targetMethod) : null;
            if (ref == null) {
                StructuredGraph graphCopy = ((HostedMethod) targetMethod).compilationInfo.createGraph(getDebug(), getOptions(), CompilationIdentifier.INVALID_COMPILATION_ID, true);
                /* Ensure box nodes in the graph are processed before using the graph. */
                new BoxNodeIdentityPhase().apply(graphCopy, getContext());
                CallTree.mergeUnwinds(graphCopy);

                HostedProviders providers = (HostedProviders) runtimeConfig.lookupBackend(targetMethod).getProviders();
                HighTierContext highTierContext = new HighTierContext(providers, highTier, optimisticOpts);

                ref = UseGraphCache.getValue(getOptions()) ? selectedGraphCache.createRef(targetMethod, graphCopy) : selectedGraphCache.createNonCounted(graphCopy);
            }
            totalNodes += ref.readonly().getNodeCount();
            assert !withNodeSourcePosition || ref.readonly().trackNodeSourcePosition();
            return ref;
        }

        @Override
        public CallTreeNode createChild(CallTreeNode caller, Invoke invoke, double frequency) {
            if (!invoke.useForInlining()) {
                return createGenericChild(caller, invoke, frequency, DontInlineCause.NotUsedForInlining);
            } else if (inliningForbidden(invoke)) {
                return createGenericChild(caller, invoke, frequency, DontInlineCause.NotUsedForInlining);
            }
            return super.createChild(caller, invoke, frequency);
        }

        @Override
        protected CutoffNode createCutoffNode(CallTreeNode caller, Invoke invoke, ResolvedJavaMethod targetMethod, ResolvedJavaType dispatchedType, ResolvedJavaType originalDispatchedType,
                        boolean monomorphic, double frequency, EnumSet<BenefitKind> benefits) {
            return new SubstrateCutoffNode(concatPositions(invoke, caller), invoke, frequency, targetMethod, dispatchedType, originalDispatchedType, monomorphic, benefits);
        }

        /**
         * We return the first available profile in the following ordered list (skipping method
         * profiles if disallowed):
         *
         * 1. Fully context-sensitive method profile inferred from sampling<br>
         * 2. (Potentially context-insensitive) Dynamic type profile<br>
         * 3. (Potentially context-insensitive) Dynamic method profile<br>
         * 4. Static type profile<br>
         * 5. Static method profile
         */
        @Override
        protected AbstractJavaProfile<?, ?> getPreferredProfile(CallTreeNode caller, MethodCallTargetNode callTarget) {
            if (caller == null || callTarget == null) {
                return null;
            }
            SubstrateInliningProvider inliningProvider = getInliningProvider();
            boolean allowMethodProfiles = inliningProvider.useMethodChecks(getOptions());
            SubstrateMethodCallTargetNode substrateCallTarget = (SubstrateMethodCallTargetNode) callTarget;
            if (substrateCallTarget.hasDynamicTypeProfile()) {
                return substrateCallTarget.getTypeProfile();
            }
            if (allowMethodProfiles && substrateCallTarget.hasDynamicMethodProfile()) {
                return substrateCallTarget.getMethodProfile();
            }
            JavaTypeProfile staticTypeProfile = substrateCallTarget.getStaticTypeProfile();
            if (staticTypeProfile != null) {
                return staticTypeProfile;
            }
            JavaMethodProfile staticMethodProfile = substrateCallTarget.getStaticMethodProfile();
            if (allowMethodProfiles && staticMethodProfile != null) {
                return staticMethodProfile;
            }
            return null;
        }

        @Override
        protected EnumSet<BenefitKind> estimateBenefits(Invoke invoke) {
            /*
             * Adjust the estimation of benefits to SubstrateVM specifics, i.e., take into account
             * Static Analysis, Word types, etc.
             */
            return BenefitKind.estimateBenefit(invoke, () -> {
                Assumptions assumptions = invoke.asNode().graph().getAssumptions();
                ResolvedJavaMethod targetMethod = invoke.callTarget().targetMethod();

                /* Thanks to Static Analysis, we can trust interface types. */
                Stamp[] parameterStamps = StampFactory.createParameterStamps(assumptions, targetMethod, true);

                /* Replace stamp of parameters that are Word types. */
                for (int i = 0; i < parameterStamps.length; i++) {
                    if (parameterStamps[i].getStackKind().isObject()) {
                        ObjectStamp parameterStamp = (ObjectStamp) parameterStamps[i];
                        if (parameterStamp.type() != null && ((HostedType) parameterStamp.type()).isWordType()) {
                            assert BenefitKind.getArgumentStamps(invoke)[i].getStackKind() == SubstrateTarget.getWordKind();
                            parameterStamps[i] = SubstrateTarget.getWordStamp();
                        }
                    }
                }
                return parameterStamps;
            });
        }
    }

    /**
     * We cannot devirtualize invokes guarded with {@link CFunctionPrologueNode} since it's an
     * invariant of {@link CFunctionPrologueNode} that there is nothing but a single {@link Invoke}
     * between {@link CFunctionPrologueNode} and
     * {@link com.oracle.svm.core.nodes.CFunctionEpilogueNode}.
     *
     * @see CFunctionPrologueNode
     */
    private static boolean isCFunctionInvoke(Invoke invoke) {
        return invoke.predecessor() instanceof CFunctionPrologueNode;
    }
}
