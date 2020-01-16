package org.broadinstitute.hellbender.tools.walkers.mutect.filtering;

import com.google.common.annotations.VisibleForTesting;
import htsjdk.variant.variantcontext.VariantContext;
import org.apache.commons.math3.util.CombinatoricsUtils;
import org.broadinstitute.hellbender.engine.ReferenceContext;
import org.broadinstitute.hellbender.tools.walkers.annotator.allelespecific.StrandBiasUtils;
import org.broadinstitute.hellbender.tools.walkers.validation.basicshortmutpileup.BetaBinomialDistribution;
import org.broadinstitute.hellbender.utils.IndexRange;
import org.broadinstitute.hellbender.utils.MathUtils;
import org.broadinstitute.hellbender.utils.OptimizationUtils;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;

import java.util.*;
import java.util.function.DoubleUnaryOperator;
import java.util.stream.Collectors;

public class StrandArtifactFilter extends Mutect2AlleleFilter<Integer> {
    // beta prior on strand bias allele fraction
    private double INITIAL_ALPHA_STRAND = 1.0;
    private double INITIAL_BETA_STRAND = 20.0;

    private double alphaStrand = INITIAL_ALPHA_STRAND;
    private double betaStrand = INITIAL_BETA_STRAND;

    // beta prior on sequencing error allele fraction
    private static final double ALPHA_SEQ = 1;
    private static final double BETA_SEQ_SNV = 1000;
    private static final double BETA_SEQ_SHORT_INDEL = 5000;
    private static final double BETA_SEQ_LONG_INDEL = 50000;
    private static final int LONG_INDEL_SIZE = 3;
    private static final int LONGEST_STRAND_ARTIFACT_INDEL_SIZE = 4;

    private static final double INITIAL_STRAND_ARTIFACT_PRIOR = 0.001;

    private List<Double> alleleStrandArtifactPriors;

    private static final double ARTIFACT_PSEUDOCOUNT = 1;

    private static final double NON_ARTIFACT_PSEUDOCOUNT = 1000;

    private final List<List<EStep>> eStepsForAltAlleles = new ArrayList<>();

    @Override
    public ErrorType errorType() { return ErrorType.ARTIFACT; }

    @Override
    public List<Double> calculateErrorProbabilityForAlleles(final VariantContext vc, final Mutect2FilteringEngine filteringEngine, ReferenceContext referenceContext) {
        final List<EStep> alleleProbs = calculateArtifactProbabilities(vc, filteringEngine);
        return alleleProbs.isEmpty() ? Collections.emptyList() :
                alleleProbs.stream().map(probabilities -> probabilities.forwardArtifactResponsibility + probabilities.reverseArtifactResponsibility).collect(Collectors.toList());
    }

    public List<EStep> calculateArtifactProbabilities(final VariantContext vc, final Mutect2FilteringEngine filteringEngine) {
        // for each allele, forward and reverse count
        List<List<Integer>> sbs = StrandBiasUtils.getSBsForAlleles(vc);
        if (sbs == null || sbs.isEmpty() || sbs.size() <= 1) {
            return Collections.emptyList();
        }

        if (alleleStrandArtifactPriors == null) {
            alleleStrandArtifactPriors = Collections.nCopies(sbs.size()-1, INITIAL_STRAND_ARTIFACT_PRIOR);
        }

        final ListIterator<Integer> indelSizeIterator = vc.getAlternateAlleles().stream().map(alt -> Math.abs(vc.getReference().length() - alt.length())).collect(Collectors.toList()).listIterator();
        final ListIterator<Double> priorIterator = alleleStrandArtifactPriors.listIterator();
        int refFwd = sbs.get(0).get(0);
        int refRev = sbs.get(0).get(1);
        // skip the reference
        List<List<Integer>> altSBs = sbs.subList(1, sbs.size());

        return altSBs.stream().map(altSB -> {
            final int altIndelSize = indelSizeIterator.next();
            final double altPrior = priorIterator.next();
            if (altSB.stream().mapToInt(Integer::intValue).sum() == 0 || altIndelSize > LONGEST_STRAND_ARTIFACT_INDEL_SIZE) {
                return new EStep(0, 0, refFwd + altSB.get(0), refRev + altSB.get(1), altSB.get(0), altSB.get(1));
            } else {
                return new EStep(altPrior, refFwd + altSB.get(0), refRev + altSB.get(1), altSB.get(0), altSB.get(1), altIndelSize);
            }
        }).collect(Collectors.toList());
    }

    @Override
    protected void accumulateDataForLearning(final VariantContext vc, final ErrorProbabilities errorProbabilities, final Mutect2FilteringEngine filteringEngine) {
        if (requiredAnnotations().stream().allMatch(vc::hasAttribute)) {
            final ListIterator<EStep> eStepsIterator = calculateArtifactProbabilities(vc, filteringEngine).listIterator();
            eStepsForAltAlleles.stream().forEach(step -> step.add(eStepsIterator.next()));
        }
    }

    @Override
    protected void clearAccumulatedData() {
        eStepsForAltAlleles.clear();
    }

    @Override
    protected void learnParameters() {
        new IndexRange(0, eStepsForAltAlleles.size()).forEach(i -> {
            List<EStep> alleleESteps = eStepsForAltAlleles.get(i);
            final List<EStep> potentialArtifacts = alleleESteps.stream()
                    .filter(eStep -> eStep.getArtifactProbability() > 0.1).collect(Collectors.toList());
            final double totalArtifacts = potentialArtifacts.stream().mapToDouble(EStep::getArtifactProbability).sum();
            final double totalNonArtifacts = alleleESteps.stream().mapToDouble(e -> 1 - e.getArtifactProbability()).sum();
            alleleStrandArtifactPriors.set(i, (totalArtifacts + ARTIFACT_PSEUDOCOUNT) / (totalArtifacts + ARTIFACT_PSEUDOCOUNT + totalNonArtifacts + NON_ARTIFACT_PSEUDOCOUNT));

            final double artifactAltCount = potentialArtifacts.stream()
                    .mapToDouble(e -> e.forwardArtifactResponsibility * e.forwardAltCount + e.reverseArtifactResponsibility * e.reverseAltCount)
                    .sum();

            final double artifactDepth = potentialArtifacts.stream()
                    .mapToDouble(e -> e.forwardArtifactResponsibility * e.forwardCount + e.reverseArtifactResponsibility * e.reverseCount)
                    .sum();

            final double artifactBetaMean = (artifactAltCount + INITIAL_ALPHA_STRAND) / (artifactDepth + INITIAL_ALPHA_STRAND + INITIAL_BETA_STRAND);

            // We do the M step for the beta prior on the artifact allele fraction by brute force single-parameter optimization.
            // By estimating the mean empirically as above we can fix mean = alpha / (alpha + beta), hence beta = (1/mean - 1) * alpha.
            // This lets us do single-parameter optimization on alpha with beta/alpha fixed.
            // brute force optimization is fairly cheap because the objective includes only calls that show some evidence of strand bias.
            final DoubleUnaryOperator objective = alpha -> {
                final double beta = (1 / artifactBetaMean - 1) * alpha;
                return potentialArtifacts.stream()
                        .mapToDouble( e -> e.getForwardArtifactResponsibility() * artifactStrandLogLikelihood(e.forwardCount, e.forwardAltCount, alpha, beta)
                                + e.getReverseArtifactResponsibility() * artifactStrandLogLikelihood(e.reverseCount, e.reverseAltCount, alpha, beta))
                        .sum();
            };

            alphaStrand = OptimizationUtils.max(objective, 0.01, 100, INITIAL_ALPHA_STRAND, 0.01, 0.01, 100).getPoint();
            betaStrand = (1/artifactBetaMean - 1)*alphaStrand;
            // free up memory
            alleleESteps.clear();
        });
    }

    @VisibleForTesting
    EStep strandArtifactProbability(final double strandArtifactPrior, int forwardCount, int reverseCount, int forwardAltCount, int reverseAltCount, final int indelSize) {
        final double forwardLogLikelihood = artifactStrandLogLikelihood(forwardCount, forwardAltCount)
                + nonArtifactStrandLogLikelihood(reverseCount, reverseAltCount, indelSize);
        final double reverseLogLikelihood = artifactStrandLogLikelihood(reverseCount, reverseAltCount)
                + nonArtifactStrandLogLikelihood(forwardCount, forwardAltCount, indelSize);
        final double noneLogLikelihood = CombinatoricsUtils.binomialCoefficientLog(forwardCount, forwardAltCount)
                + CombinatoricsUtils.binomialCoefficientLog(reverseCount, reverseAltCount)
                - CombinatoricsUtils.binomialCoefficientLog(forwardCount + reverseCount, forwardAltCount + reverseAltCount)
                + new BetaBinomialDistribution(null, 1, 1, forwardCount + reverseCount).logProbability(forwardAltCount + reverseAltCount);

        final double forwardLogPrior = Math.log(strandArtifactPrior/2);
        final double reverseLogPrior = Math.log(strandArtifactPrior/2);
        final double noneLogPrior = Math.log(1 - strandArtifactPrior);

        final double[] forwardReverseNoneProbs = MathUtils.normalizeLog10(new double[] {(forwardLogLikelihood + forwardLogPrior) * MathUtils.LOG10_E,
                (reverseLogLikelihood + reverseLogPrior)* MathUtils.LOG10_E, (noneLogLikelihood + noneLogPrior) * MathUtils.LOG10_E}, false, true);

        return new EStep(forwardReverseNoneProbs[0], forwardReverseNoneProbs[1], forwardCount, reverseCount, forwardAltCount, reverseAltCount);
    }

    @Override
    public String filterName() {
        return GATKVCFConstants.STRAND_ARTIFACT_FILTER_NAME;
    }

    @Override
    protected List<String> requiredAnnotations() {
        return Collections.emptyList();
    }

    private double artifactStrandLogLikelihood(final int strandCount, final int strandAltCount) {
        return artifactStrandLogLikelihood(strandCount, strandAltCount, alphaStrand, betaStrand);
    }

    private static double artifactStrandLogLikelihood(final int strandCount, final int strandAltCount, final double alpha, final double beta) {
        return new BetaBinomialDistribution(null, alpha, beta, strandCount).logProbability(strandAltCount);
    }

    private double nonArtifactStrandLogLikelihood(final int strandCount, final int strandAltCount, final int indelSize) {
        final double betaSeq = indelSize == 0 ? BETA_SEQ_SNV : (indelSize < LONG_INDEL_SIZE ? BETA_SEQ_SHORT_INDEL : BETA_SEQ_LONG_INDEL);
        return new BetaBinomialDistribution(null, ALPHA_SEQ, betaSeq, strandCount).logProbability(strandAltCount);
    }

    @Override
    public Optional<String> phredScaledPosteriorAnnotationName() {
        return Optional.of(GATKVCFConstants.STRAND_QUAL_KEY);
    }

    public static final class EStep {
        private double forwardArtifactResponsibility;
        private double reverseArtifactResponsibility;
        private int forwardCount;
        private int reverseCount;
        private int forwardAltCount;
        private int reverseAltCount;

        public EStep(double forwardArtifactResponsibility, double reverseArtifactResponsibility, int forwardCount, int reverseCount, int forwardAltCount, int reverseAltCount) {
            this.forwardArtifactResponsibility = forwardArtifactResponsibility;
            this.reverseArtifactResponsibility = reverseArtifactResponsibility;
            this.forwardCount = forwardCount;
            this.reverseCount = reverseCount;
            this.forwardAltCount = forwardAltCount;
            this.reverseAltCount = reverseAltCount;
        }

        public double getForwardArtifactResponsibility() {
            return forwardArtifactResponsibility;
        }

        public double getReverseArtifactResponsibility() {
            return reverseArtifactResponsibility;
        }

        public double getArtifactProbability() {
            return getForwardArtifactResponsibility() + getReverseArtifactResponsibility();
        }

        public int getForwardCount() {
            return forwardCount;
        }

        public int getReverseCount() {
            return reverseCount;
        }

        public int getForwardAltCount() {
            return forwardAltCount;
        }

        public int getReverseAltCount() {
            return reverseAltCount;
        }
    }

}
