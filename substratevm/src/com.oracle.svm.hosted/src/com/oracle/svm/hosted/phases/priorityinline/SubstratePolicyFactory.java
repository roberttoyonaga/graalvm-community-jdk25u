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

import static com.oracle.svm.hosted.phases.priorityinline.InterproceduralPartialEscapeAnalysisCallTreeState.getIPEACallTreeState;
import static com.oracle.svm.hosted.phases.priorityinline.SubstratePriorityInliningPhase.Options.IPEAFrequency;
import static com.oracle.svm.hosted.phases.priorityinline.SubstratePriorityInliningPhase.Options.IPEAMaxForce;
import static com.oracle.svm.hosted.phases.priorityinline.SubstratePriorityInliningPhase.Options.SizeForIPEAFrequencyDecrease;
import static com.oracle.svm.hosted.phases.priorityinline.SubstratePriorityInliningPhase.Options.UseIPEA;
import static jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options.CallGraphSizePenaltyCoefficient;
import static jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options.ExpansionInertiaBaseValue;
import static jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options.ExpansionInertiaInvokeBonus;
import static jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options.RootSizePenaltyCoefficient;
import static jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options.RootSizePenaltyTypicalGraphSize;
import static jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options.TuneInlinerExploration;
import static jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options.TypicalCallGraphSize;
import static jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options.TypicalGraphSize;
import static jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase.Options.TypicalGraphSizeInvokeBonus;

import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.option.HostedOptionKey;

import jdk.graal.compiler.debug.DebugCloseable;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.TimerKey;
import jdk.graal.compiler.nodes.spi.CoreProviders;
import jdk.graal.compiler.options.OptionKey;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.common.priorityinline.CallTree;
import jdk.graal.compiler.phases.common.priorityinline.CallTreeState;
import jdk.graal.compiler.phases.common.priorityinline.DefaultPolicyFactory;
import jdk.graal.compiler.phases.common.priorityinline.Expander;
import jdk.graal.compiler.phases.common.priorityinline.Inliner;
import jdk.graal.compiler.phases.common.priorityinline.Optimizer;
import jdk.graal.compiler.phases.common.priorityinline.PriorityInliningPhase;
import jdk.graal.compiler.phases.common.priorityinline.TunableOptionKey;
import jdk.graal.compiler.phases.common.priorityinline.nodes.CallTreeNode;
import jdk.graal.compiler.phases.common.priorityinline.nodes.CutoffNode;
import jdk.graal.compiler.phases.common.priorityinline.nodes.ParentNode;
import jdk.graal.compiler.phases.tiers.HighTierContext;

/**
 * This enables InterproceduralPartialEscapeAnalysis, see
 * {@link InterproceduralPartialEscapeAnalysisPhase} for more details on the phase. When building
 * for native image, SubstratePolicyFactory will be selected by default and subsequently
 * {@link SubstratePriorityInliningPhase} will use {@link SubstrateExpanderPolicy} and
 * {@link SubstrateInlinerPolicy}.
 */
@Platforms(Platform.HOSTED_ONLY.class)
public class SubstratePolicyFactory extends DefaultPolicyFactory {

    @Override
    public Expander.Policy createExpanderPolicy(OptionValues options, HighTierContext context) {
        return new SubstrateExpanderPolicy();
    }

    @Override
    public SubstrateInlinerPolicy createInlinerPolicy(OptionValues options) {
        return new SubstrateInlinerPolicy();
    }

    @Override
    public int priority() {
        return 10;
    }

    /**
     * This policy uses {@link HostedOptionKey}s (e.g.
     * {@link SubstratePriorityInliningPhase.Options#UseIPEA}) so can only be used when in a native
     * image or during building a native image.
     */
    @Override
    public boolean isAllowed() {
        return ImageInfo.inImageCode();
    }

    public static class SubstrateInlinerPolicy extends Inliner.DefaultPolicy {
        @Override
        public boolean shouldContinueInlining(CallTree callTree, Optimizer optimizer, CoreProviders coreProviders) {
            if (super.shouldContinueInlining(callTree, optimizer, coreProviders)) {
                return true;
            }
            return shouldForceContinueInlining(callTree);
        }

        private static boolean shouldForceContinueInlining(CallTree callTree) {
            InterproceduralPartialEscapeAnalysisCallTreeState callTreeState = getIPEACallTreeState(callTree);
            if (!UseIPEA.getValue()) {
                return false;
            }

            if (callTreeState.numForceIPEA() >= getMaxForceIPEA(callTree)) {
                return false;
            }

            if (callTree.isCallGraphTooBig() || callTree.getPolicy().isInlinedGraphTooBig(callTree)) {
                return false;
            }

            if (callTreeState.shouldRunIPEA() && !callTreeState.callTreeModifiedInLastRound()) {
                return false;
            }
            return true;
        }

        @Override
        public void beforeRound(CallTree callTree, Optimizer optimizer, CoreProviders coreProviders) {
            if (!super.shouldContinueInlining(callTree, optimizer, coreProviders) && shouldForceContinueInlining(callTree)) {
                getIPEACallTreeState(callTree).setForceIPEA(true);
            }
        }

        private static int getMaxForceIPEA(CallTree callTree) {
            return Math.max(1, IPEAMaxForce.getValue() - callTree.getNodeCount() / 4 * SizeForIPEAFrequencyDecrease.getValue());
        }
    }

    public static class SubstrateExpanderPolicy extends Expander.DefaultPolicy {

        private static final int GRAPH_SIZE_MIN_VALUE = 0;
        private static final int GRAPH_SIZE_BONUS_MIN_VALUE = 0;
        private static final int EXPANSION_INERTIA_BONUS_MIN_VALUE = 0;
        private static final int EXPANSION_INERTIA_MIN_VALUE = 1;
        private static final int SCALING_MAX_VALUE_MULTIPLIER = 2;

        private static int getFrequency(CallTree callTree) {
            return IPEAFrequency.getValue() + callTree.getNodeCount() / SizeForIPEAFrequencyDecrease.getValue();
        }

        @Override
        public void beforeRound(CallTree callTree) {
            InterproceduralPartialEscapeAnalysisCallTreeState callTreeState = getIPEACallTreeState(callTree);
            if (UseIPEA.getValue()) {
                if (callTreeState.forceIPEA()) {
                    callTreeState.setShouldRunIPEA(true);
                    callTreeState.incNumForceIPEA();
                    callTreeState.setForceIPEA(false);
                    callTreeState.resetRoundsSinceForce();
                } else {
                    callTreeState.setShouldRunIPEA(callTreeState.roundsSinceForce() % getFrequency(callTree) == 0);
                }
                if (callTreeState.shouldRunIPEA()) {
                    callTreeState.incNumIPEARounds();
                }
                callTreeState.incRoundsSinceForce();
            }
        }

        @Override
        public void afterExpansionPhase(CallTree callTree, CoreProviders coreProviders, int expansionRound, TimerKey expanderExtraAnalysisDuration) {
            InterproceduralPartialEscapeAnalysisCallTreeState callTreeState = getIPEACallTreeState(callTree);
            if (callTreeState.shouldRunIPEA()) {
try (DebugCloseable dc = expanderExtraAnalysisDuration.start(callTree.getDebug())) {
                DebugContext debugContext = callTree.getDebug();
                InterproceduralPartialEscapeAnalysisUtil.afterExpansionPhase(callTree, callTreeState.analysisResult());
                debugContext.dump(DebugContext.VERBOSE_LEVEL, callTree, "round %d, after post-expansion analysis", expansionRound);
                }
            }
        }

        @Override
        public void afterExpandingCutoffNode(CallTreeNode replacementNode, CallTreeNode replacedNode, CoreProviders coreProviders, int expansionRound, TimerKey expanderExtraAnalysisDuration) {
            InterproceduralPartialEscapeAnalysisCallTreeState callTreeState = getIPEACallTreeState(replacementNode.callTree());
            if (callTreeState.shouldRunIPEA()) {
                try (DebugCloseable dc = expanderExtraAnalysisDuration.start(replacementNode.getDebug())) {
                    callTreeState.setAnalysisResult(InterproceduralPartialEscapeAnalysisUtil.afterExpandingCutoffNode(replacementNode, replacedNode, coreProviders, callTreeState.analysisResult()));
                    replacementNode.callTree().restoreSubtreeInvariants(replacementNode.parent(), true);
                }
            }
        }

        @Override
        public void beforeExpansion(CallTree callTree, CoreProviders coreProviders, int expansionRound, TimerKey expanderExtraAnalysisDuration) {
            InterproceduralPartialEscapeAnalysisCallTreeState callTreeState = getIPEACallTreeState(callTree);
            if (callTreeState.shouldRunIPEA()) {
                try (DebugCloseable dc = expanderExtraAnalysisDuration.start(callTree.getDebug())) {
                    DebugContext debugContext = callTree.getDebug();
                    callTreeState.setAnalysisResult(InterproceduralPartialEscapeAnalysisUtil.runOnFullTree(callTree, coreProviders));
                    debugContext.dump(DebugContext.VERBOSE_LEVEL, callTree, "round %d, after pre-expansion analysis", expansionRound);
                }
            }
        }

        @Override
        public void updateCutoffNodeLocalBenefit(CutoffNode node) {
            InterproceduralPartialEscapeAnalysisCallTreeState callTreeState = getIPEACallTreeState(node.callTree());
            double originalLocalBenefit = node.getLocalBenefit();
            super.updateCutoffNodeLocalBenefit(node);
            double updatedLocalBenefit = node.getLocalBenefit() * InterproceduralPartialEscapeAnalysisUtil.escapingObjectCutoffBonus(node, callTreeState.analysisResult());
            node.setLocalBenefit(updatedLocalBenefit);
            if (originalLocalBenefit != updatedLocalBenefit && node.activeCutoffCount() == 0) {
                node.setActiveCutoffCount(1);
            }
        }

        @Override
        protected int getTypicalCallGraphSizeValue(OptionValues options) {
            if (TypicalCallGraphSize.hasBeenSet(options)) {
                return TypicalCallGraphSize.getValue(options);
            }
            return 200;
        }

        @Override
        protected double getCallGraphSizePenaltyCoefficientValue(OptionValues options) {
            if (CallGraphSizePenaltyCoefficient.hasBeenSet(options)) {
                return CallGraphSizePenaltyCoefficient.getValue(options);
            }
            return 0.001;
        }

        @Override
        protected double getRootSizePenaltyCoefficientValue(OptionValues options) {
            if (RootSizePenaltyCoefficient.hasBeenSet(options)) {
                return RootSizePenaltyCoefficient.getValue(options);
            }
            return 0;
        }

        @Override
        public void updateParentNodeLocalBenefit(ParentNode node) {
            super.updateParentNodeLocalBenefit(node);
            double localBenefit = node.getLocalBenefit();
            Double boost = getIPEACallTreeState(node.callTree()).getCachedLocalBenefitBoost(node);
            if (UseIPEA.getValue() && boost != null) {
                localBenefit = localBenefit * boost;
            }
            node.setLocalBenefit(localBenefit);
        }

        @Override
        public CallTreeState createCallTreeState() {
            return new InterproceduralPartialEscapeAnalysisCallTreeState();
        }

        @Override
        public int expansionInertiaBaseValue(OptionValues options) {
            return scaleOption(options, ExpansionInertiaBaseValue, EXPANSION_INERTIA_MIN_VALUE, SCALING_MAX_VALUE_MULTIPLIER * ExpansionInertiaInvokeBonus.getValue(options), true);
        }

        @Override
        public int expansionInertiaInvokeBonus(OptionValues options) {
            return scaleOption(options, ExpansionInertiaInvokeBonus, EXPANSION_INERTIA_BONUS_MIN_VALUE, SCALING_MAX_VALUE_MULTIPLIER * ExpansionInertiaInvokeBonus.getValue(options), true);
        }

        @Override
        public int typicalGraphSize(OptionValues options) {
            return scaleOption(options, TypicalGraphSize, GRAPH_SIZE_MIN_VALUE, SCALING_MAX_VALUE_MULTIPLIER * TypicalGraphSize.getValue(options), true);
        }

        @Override
        public int typicalGraphSizeInvokeBonus(OptionValues options) {
            return scaleOption(options, TypicalGraphSizeInvokeBonus, GRAPH_SIZE_BONUS_MIN_VALUE, SCALING_MAX_VALUE_MULTIPLIER * TypicalGraphSizeInvokeBonus.getValue(options), true);
        }

        @Override
        protected int rootSizePenaltyTypicalGraphSize(OptionValues options) {
            return scaleOption(options, RootSizePenaltyTypicalGraphSize, GRAPH_SIZE_MIN_VALUE, SCALING_MAX_VALUE_MULTIPLIER * RootSizePenaltyTypicalGraphSize.getValue(options), true);
        }

        private static int scaleOption(OptionValues options, OptionKey<Integer> optionKey, int minValue, int maxValue, boolean higherIsMoreExpensive) {
            return (int) TunableOptionKey.getTunedValue(TuneInlinerExploration.getValue(options),
                            minValue,
                            optionKey.getValue(options),
                            maxValue,
                            higherIsMoreExpensive);
        }

        @Override
        public int getExtraStatisticsMetric(CallTree callTree) {
            return getIPEACallTreeState(callTree).numIPEARounds();
        }
    }
}
