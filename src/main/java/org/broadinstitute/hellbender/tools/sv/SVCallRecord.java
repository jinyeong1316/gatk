package org.broadinstitute.hellbender.tools.sv;

import htsjdk.tribble.Feature;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.GenotypesContext;
import htsjdk.variant.variantcontext.StructuralVariantType;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFConstants;
import org.broadinstitute.hellbender.tools.copynumber.gcnv.GermlineCNVSegmentVariantComposer;
import org.broadinstitute.hellbender.tools.spark.sv.utils.GATKSVVCFConstants;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.codecs.SVCallRecordCodec;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;

import java.util.*;
import java.util.stream.Collectors;

public class SVCallRecord implements Feature {

    private final String startContig;
    private final int start;
    private final boolean startStrand;
    private final String endContig;
    private final int end;
    private final boolean endStrand;
    private final StructuralVariantType type;
    private int length;
    private final List<String> algorithms;
    private final List<Genotype> genotypes;
    private Set<String> samples;

    private final static List<String> nonDepthCallerAttributes = Arrays.asList(
            VCFConstants.END_KEY,
            GATKSVVCFConstants.ALGORITHMS_ATTRIBUTE,
            GATKSVVCFConstants.STRANDS_ATTRIBUTE,
            GATKSVVCFConstants.SVLEN,
            GATKSVVCFConstants.SVTYPE
    );

    public static SVCallRecord create(final VariantContext variant) {
        Utils.nonNull(variant);
        //TODO -- put me back
        //Utils.validate(variant.getAttributes().keySet().containsAll(nonDepthCallerAttributes), "Call is missing attributes");
        final String startContig = variant.getContig();
        final int start = variant.getStart();
        //final String endContig = variant.getAttributeAsString(SVCluster.END_CONTIG_ATTRIBUTE, "NA");
        final String endContig = startContig; //HACK
        final int end = variant.getAttributeAsInt(VCFConstants.END_KEY, variant.getStart());
        final StructuralVariantType type = variant.getStructuralVariantType();
        final List<String> algorithms = variant.getAttributeAsStringList(GATKSVVCFConstants.ALGORITHMS_ATTRIBUTE, GATKSVVCFConstants.DEPTH_ALGORITHM);
        if (algorithms.isEmpty()) {//HACK
            algorithms.add(GATKSVVCFConstants.DEPTH_ALGORITHM);
        }
        final String strands = variant.getAttributeAsString(GATKSVVCFConstants.STRANDS_ATTRIBUTE, "0");
        /*
        if (strands.length() != 2) {
            throw new IllegalArgumentException("Strands field is not 2 characters long");
        }
        final String startStrandChar = strands.substring(0, 1);
        if (!startStrandChar.equals(SVCallRecordCodec.STRAND_PLUS) && !startStrandChar.equals(SVCallRecordCodec.STRAND_MINUS)) {
            throw new IllegalArgumentException("Valid start strand not found");
        }
        final String endStrandChar = strands.substring(1, 2);
        if (!endStrandChar.equals(SVCallRecordCodec.STRAND_PLUS) && !endStrandChar.equals(SVCallRecordCodec.STRAND_MINUS)) {
            throw new IllegalArgumentException("Valid end strand not found");
        }

        final boolean startStrand = startStrandChar.equals(SVCallRecordCodec.STRAND_PLUS);
        final boolean endStrand = endStrandChar.equals(SVCallRecordCodec.STRAND_PLUS);
        final int length = variant.getAttributeAsInt(SVCluster.SVLEN_ATTRIBUTE, 0);
        */
        final boolean startStrand = true;
        final boolean endStrand = true;
        final int length = end - start + 1;


        return new SVCallRecord(startContig, start, startStrand, endContig, end, endStrand, type, length, algorithms, variant.getGenotypes());
    }

    public static SVCallRecord createDepthOnlyFromGCNV(final VariantContext variant, final double minQuality) {
        Utils.nonNull(variant);
        final List<Genotype> passing = variant.getGenotypes().stream()
                .filter(Genotype::isCalled)
                .filter(g -> Integer.valueOf((String)g.getExtendedAttribute(GermlineCNVSegmentVariantComposer.QS)) >= minQuality)
                .collect(Collectors.toList());
        if (passing.isEmpty()) return null;
        final List<String> algorithms = Collections.singletonList(GATKSVVCFConstants.DEPTH_ALGORITHM);

        //TODO : use new vcfs to get actual allele
        final int copyNumber = Integer.valueOf((String)variant.getGenotypes().get(0).getExtendedAttribute(GermlineCNVSegmentVariantComposer.CN));
        if (copyNumber == 2) return null;
        final boolean isDel = copyNumber < 2;
        final boolean startStrand = isDel ? true : false;
        final boolean endStrand = isDel ? false : true;
        final StructuralVariantType type = isDel ? StructuralVariantType.DEL : StructuralVariantType.DUP;

        final String startContig = variant.getContig();
        final int start = variant.getStart();
        final int end = variant.getEnd() + 1; // TODO this is a bug with gCNV vcf generation
        final int length = end - start;
        return new SVCallRecord(startContig, start, startStrand, startContig, end, endStrand, type, length, algorithms, passing);
    }

    public SVCallRecord(final String startContig,
                        final int start,
                        final boolean startStrand,
                        final String endContig,
                        final int end,
                        final boolean endStrand,
                        final StructuralVariantType type,
                        final int length,
                        final List<String> algorithms,
                        final List<Genotype> genotypes) {
        //TODO -- put these back or clean them up
        /*Utils.nonNull(startContig);
        Utils.nonNull(endContig);
        Utils.nonNull(type);
        Utils.nonNull(algorithms);
        Utils.nonNull(samples);
        Utils.nonEmpty(algorithms);
        Utils.nonEmpty(samples);
        Utils.containsNoNull(algorithms, "Encountered null algorithm");
        Utils.containsNoNull(samples, "Encountered null sample");*/
        this.startContig = startContig;
        this.start = start;
        this.startStrand = startStrand;
        this.endContig = endContig;
        this.end = end;
        this.endStrand = endStrand;
        this.type = type;
        this.length = length;
        this.algorithms = algorithms;
        this.genotypes = genotypes;
        this.samples = genotypes.stream()
                .filter(Genotype::isCalled)
                .map(Genotype::getSampleName)
                .collect(Collectors.toSet());
    }

    @Override
    public String getContig() {
        return startContig;
    }

    @Override
    public int getStart() {
        return start;
    }

    public boolean getStartStrand() {
        return startStrand;
    }

    public String getEndContig() {
        return endContig;
    }

    @Override
    public int getEnd() {
        return end;
    }

    public boolean getEndStrand() {
        return endStrand;
    }

    public StructuralVariantType getType() {
        return type;
    }

    public int getLength() {
        return length;
    }

    public List<String> getAlgorithms() {
        return algorithms;
    }

    public Set<String> getSamples() {
        return samples;
    }

    public List<Genotype> getGenotypes() {
        return genotypes;
    }

    public SimpleInterval getStartAsInterval() {
        return new SimpleInterval(startContig, start, start + 1);
    }

    public SimpleInterval getEndAsInterval() {
        return new SimpleInterval(endContig, end, end + 1);
    }
}