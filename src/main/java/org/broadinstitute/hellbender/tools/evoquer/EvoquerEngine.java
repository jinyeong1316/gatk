package org.broadinstitute.hellbender.tools.evoquer;

import com.google.cloud.bigquery.*;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.variant.variantcontext.*;
import htsjdk.variant.vcf.VCFConstants;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderLine;
import htsjdk.variant.vcf.VCFStandardHeaderLines;
import org.apache.commons.math3.stat.descriptive.rank.Median;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.broadinstitute.hellbender.utils.IntervalMergingRule;
import org.broadinstitute.hellbender.utils.IntervalUtils;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.bigquery.BigQueryUtils;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;
import org.broadinstitute.hellbender.utils.variant.GATKVCFHeaderLines;

import java.util.*;
import java.util.stream.Collectors;

/**
 * EvoquerEngine ("EvokerEngine"):
 * Extract Variants Out of big QUERy Engine.
 *
 * Performs the work for {@link Evoquer} in a manner that is extensible.
 *
 * Created by jonn on 4/17/19.
 */
class EvoquerEngine {
    private static final Logger logger = LogManager.getLogger(EvoquerEngine.class);

    //==================================================================================================================
    // Public Static Members:

    //==================================================================================================================
    // Private Static Members:

    private static String RAW_MAPPING_QUALITY_WITH_DEPTH_KEY_SEPARATOR = ",";

    /** ID of the project containing the dataset and tables from which to pull variant data. */
    private static final String PROJECT_ID = "broad-dsp-spec-ops";

    /** ID of the table containing the names of all samples in the variant table. */
    private static final String SAMPLE_TABLE = "gvcf_test.sample_list_subsetted_100";

    /**
     * The conf threshold above which variants are not included in the position tables.
     * This value is used to construct the genotype information of those missing samples
     * when they are merged together into a {@link VariantContext} object in {@link #createHighConfRefSampleGenotype(String, int, Allele, int)}.
     */
    private static final int MISSING_CONF_THRESHOLD = 60;

    /**
     * Value to insert for strand bias for the reference in high-confidence variant sample data which is missing from
     * the database.
     */
    private static final int HIGH_CONF_REFERENCE_STRAND_BIAS = 50;


    /**
     * Value to insert for MQ, ReadPosRankSum, and MQRankSum for the reference in high-confidence variant sample data which is missing from
     * the database.
     */
    private static final int MISSING_MQ_AND_READ_POS_RANK_SUM_DEFAULT_VALUE = 20;

    /**
     * Map between contig name and the BigQuery table containing position data from that contig.
     */
    private static final Map<String, String> contigPositionExpandedTableMap;

    /**
     * Map between contig name and the BigQuery table containing variant data from that contig.
     */
    private static final Map<String, String> contigVariantTableMap;

    static {
        final Map<String, String> tmpContigTableMap = new HashMap<>();
        tmpContigTableMap.put("chr20", "gvcf_test.pet_subsetted_100");

        contigPositionExpandedTableMap = Collections.unmodifiableMap(tmpContigTableMap);

        final Map<String, String> tmpVariantTableMap = new HashMap<>();
        tmpVariantTableMap.put("chr20", "gvcf_test.vet_subsetted_100");

        contigVariantTableMap = Collections.unmodifiableMap(tmpVariantTableMap);
    }

    //==================================================================================================================
    // Private Members:

    /** Set of sample names seen in the variant data from BigQuery. */
    private final Set<String> sampleNames = new HashSet<>();

    //==================================================================================================================
    // Constructors:
    EvoquerEngine() {}

    //==================================================================================================================
    // Override Methods:

    //==================================================================================================================
    // Static Methods:

    //==================================================================================================================
    // Public Instance Methods:

    /**
     * Connects to the BigQuery table for the given {@link List<SimpleInterval>} and pulls out the information on the samples that
     * contain variants.
     * @param intervalList {@link List<SimpleInterval>} over which to query the BigQuery table.
     * @return A {@link List<VariantContext>} containing variants in the given {@code interval} in the BigQuery table.
     */
    List<VariantContext> evokeIntervals(final List<SimpleInterval> intervalList) {

        // Get the samples used in the dataset:
        populateSampleNames();

        // Merge and sort our interval list so we don't end up with edge cases:
        final List<SimpleInterval> sortedMergedIntervals =
                IntervalUtils.sortAndMergeIntervals(intervalList, IntervalMergingRule.ALL);

        // Now get our intervals into variants:
        return sortedMergedIntervals.stream()
                .flatMap( interval -> evokeInterval(interval).stream() )
                .collect(Collectors.toList());
    }

    /**
     * Generates a {@link VCFHeader} object based on the VariantContext objects queried from the BigQuery backend.
     * If no objects have been queried, this will return a default {@link VCFHeader}.
     * @param defaultHeaderLines The default header lines to be added to the top of the VCF header.
     * @param sequenceDictionary The SequenceDictionary of the reference on which the variants are based.
     * @return A {@link VCFHeader} object representing the header for all variants that have been queried from the BigQuery backend.
     */
    VCFHeader generateVcfHeader(final Set<VCFHeaderLine> defaultHeaderLines,
                                       final SAMSequenceDictionary sequenceDictionary) {
        final Set<VCFHeaderLine> headerLines = new HashSet<>();

        headerLines.addAll( getEvoquerVcfHeaderLines() );
        headerLines.addAll( defaultHeaderLines );

        final VCFHeader header = new VCFHeader(headerLines, sampleNames);
        header.setSequenceDictionary(sequenceDictionary);

        return header;
    }

    //==================================================================================================================
    // Private Instance Methods:

    private Set<VCFHeaderLine> getEvoquerVcfHeaderLines() {
        final Set<VCFHeaderLine> headerLines = new HashSet<>();

        // TODO: Get a list of all possible values here so that we can make sure they're in the VCF Header!

        // Add standard VCF fields first:
        VCFStandardHeaderLines.addStandardInfoLines( headerLines, true,
                VCFConstants.STRAND_BIAS_KEY,
                VCFConstants.DEPTH_KEY,
                VCFConstants.RMS_MAPPING_QUALITY_KEY
        );

        VCFStandardHeaderLines.addStandardFormatLines(headerLines, true,
                VCFConstants.GENOTYPE_KEY,
                VCFConstants.GENOTYPE_QUALITY_KEY,
                VCFConstants.DEPTH_KEY,
                VCFConstants.GENOTYPE_PL_KEY,
                VCFConstants.GENOTYPE_ALLELE_DEPTHS
        );

        // Now add GATK VCF fields:
        headerLines.add(GATKVCFHeaderLines.getInfoLine(GATKVCFConstants.RAW_MAPPING_QUALITY_WITH_DEPTH_KEY));
        headerLines.add(GATKVCFHeaderLines.getInfoLine(GATKVCFConstants.MAPPING_QUALITY_DEPTH));
        headerLines.add(GATKVCFHeaderLines.getInfoLine(GATKVCFConstants.RAW_QUAL_APPROX_KEY));
        headerLines.add(GATKVCFHeaderLines.getInfoLine(GATKVCFConstants.READ_POS_RANK_SUM_KEY));
        headerLines.add(GATKVCFHeaderLines.getInfoLine(GATKVCFConstants.VARIANT_DEPTH_KEY));
        headerLines.add(GATKVCFHeaderLines.getInfoLine(GATKVCFConstants.MAP_QUAL_RANK_SUM_KEY));

        headerLines.add(GATKVCFHeaderLines.getFormatLine(GATKVCFConstants.MIN_DP_FORMAT_KEY));
        headerLines.add(GATKVCFHeaderLines.getFormatLine(GATKVCFConstants.STRAND_BIAS_BY_SAMPLE_KEY));
        headerLines.add(GATKVCFHeaderLines.getFormatLine(GATKVCFConstants.HAPLOTYPE_CALLER_PHASING_GT_KEY));
        headerLines.add(GATKVCFHeaderLines.getFormatLine(GATKVCFConstants.HAPLOTYPE_CALLER_PHASING_ID_KEY));

        headerLines.add(GATKVCFHeaderLines.getFilterLine(GATKVCFConstants.LOW_QUAL_FILTER_NAME));

        return headerLines;
    }

    /**
     * Connects to the BigQuery table for the given interval and pulls out the information on the samples that
     * contain variants.
     * @param interval {@link SimpleInterval} over which to query the BigQuery table.
     * @return A {@link List<VariantContext>} containing variants in the given {@code interval} in the BigQuery table.
     */
    private List<VariantContext> evokeInterval(final SimpleInterval interval) {

        if ( contigPositionExpandedTableMap.containsKey(interval.getContig()) ) {
            // Get the query string:
            final String variantQueryString = getVariantQueryString(interval);

            logger.info("Created Query: \n" + variantQueryString);

            // Execute the query:
            final TableResult result = BigQueryUtils.executeQuery(variantQueryString);

            // Show our pretty results:
            logger.info("Pretty Query Results:");
            final String prettyQueryResults = BigQueryUtils.getResultDataPrettyString(result);
            logger.info( "\n" + prettyQueryResults );

            // Convert results into variant context objects:
            return createVariantsFromTableResult( result ).stream()
//                    .map(GnarlyGenotyperEngine::finalizeGenotype)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
        else {
            logger.warn("Contig missing from contigPositionExpandedTableMap, ignoring interval: " + interval.toString());
        }
        return Collections.emptyList();
    }

    private static String getPositionTableForContig(final String contig ) {
        return contigPositionExpandedTableMap.get(contig);
    }

    private static String getVariantTableForContig(final String contig ) {
        return contigVariantTableMap.get(contig);
    }

    private static String getTableQualifier() {
        return PROJECT_ID;
    }

    private static String getFQTableName( final String tableName ) {
        return getTableQualifier() + "." + tableName;
    }

    /**
     * Get the fully-qualified table name corresponding to the table in BigQuery that contains the position
     * data specified in the given {@code interval}.
     *
     * Uses {@link #PROJECT_ID} for the project of the BigQuery table.
     * Assumes the tables have dataset information in them.
     *
     * @param interval The {@link SimpleInterval} for which to get the corresponding table in BigQuery.
     * @return The name of the table corresponding to the given {@code interval}, or {@code null} if no such table exists.
     */
    private static String getFQPositionTable(final SimpleInterval interval) {
        return getFQTableName(getPositionTableForContig( interval.getContig() ));
    }

    /**
     * Get the fully-qualified table name corresponding to the table in BigQuery that contains the variant
     * data specified in the given {@code interval}.
     *
     * Uses {@link #PROJECT_ID} for the project of the BigQuery table.
     * Assumes the tables have dataset information in them.
     *
     * @param interval The {@link SimpleInterval} for which to get the corresponding table in BigQuery.
     * @return The name of the table corresponding to the given {@code interval}, or {@code null} if no such table exists.
     */
    private static String getFQVariantTable(final SimpleInterval interval) {
        return getFQTableName(getVariantTableForContig( interval.getContig() ));
    }

    private List<VariantContext> createVariantsFromTableResult(final TableResult result) {
        // Have to convert to int here.
        // Sloppy, but if we ever go larger than MAXINT, we have bigger problems.
        final List<VariantContext> variantContextList = new ArrayList<>((int)result.getTotalRows());
        final List<VariantContext> mergedVariantContextList = new ArrayList<>((int)result.getTotalRows());

        // Details of the variants to store:
        List<VariantDetailData> currentVariantDetails = new ArrayList<>( sampleNames.size() / 10 );

        // Position / alleles are the key here.
        String currentContig = "";
        long currentPos = 0;
        List<Allele> currentAlleleList = new ArrayList<>(3);

        // Initialize with values from the first result:
        if ( result.getTotalRows() != 0 ) {
            final FieldValueList row = result.iterateAll().iterator().next();

            currentContig = row.get("reference_name").getStringValue();
            // Add 1 because in the DB right now starts are exclusive:
            currentPos = row.get("start_position").getLongValue() + 1;

            // Fill in alleles:
            currentAlleleList.add( Allele.create(row.get("reference_bases").getStringValue(), true) );

            currentAlleleList.addAll(
                    row.get("alternate_bases").getRepeatedValue().stream()
                    .map( fieldValue -> Allele.create(fieldValue.getRecordValue().get(0).getStringValue()) )
                    .collect(Collectors.toList())
            );
        }

        for ( final FieldValueList row : result.iterateAll() ) {
            final VariantContextBuilder variantContextBuilder = new VariantContextBuilder();

            final VariantDetailData variantDetailData = new VariantDetailData();

            // Fill in trivial stuff:
            addBasicFieldsToVariantBuilder(row, variantContextBuilder, variantDetailData);

            // Do we have a new position / new alleles?
            // If so we merge the accumulated variants and setup the new stores:
            if ( (!variantContextBuilder.getContig().equals(currentContig)) ||
                    (variantContextBuilder.getStart() != currentPos)  ||
                    (!variantContextBuilder.getAlleles().equals(currentAlleleList)) ) {

                mergedVariantContextList.add(
                        mergeVariantDetails( currentContig, currentPos, currentAlleleList, currentVariantDetails )
                );

                // Setup new values:
                currentContig = variantContextBuilder.getContig();
                currentPos = variantContextBuilder.getStart();
                currentAlleleList = variantContextBuilder.getAlleles();
                currentVariantDetails = new ArrayList<>( sampleNames.size() / 10 );
            }

            // Fill in info field stuff:
            addInfoFieldsToVariantBuilder( row, variantContextBuilder, variantDetailData );

            // Fill in sample field stuff:
            // The "call" field has the genotype / sample information in it.
            // It should never be null.
            addSampleFieldsToVariantBuilder( row, result.getSchema(), variantContextBuilder, variantDetailData );

            // Add our variant context to the list:
            variantContextList.add( variantContextBuilder.make() );

            // Add our variant data to the accumulated list:
            currentVariantDetails.add( variantDetailData );
        }

        // We must merge the remaining variant details together if any are left:
        if ( !currentVariantDetails.isEmpty() ) {
            mergedVariantContextList.add(
                    mergeVariantDetails( currentContig, currentPos, currentAlleleList, currentVariantDetails )
            );
        }

        return mergedVariantContextList;
    }

    /**
     * Merges together the given variant details into a {@link VariantContext} object.
     *
     * Merges according to the following rules:
     *
     * The set of combine operations can be found here: https://github.com/Intel-HLS/GenomicsDB/wiki/Importing-VCF-data-into-GenomicsDB#fields-information
     * It's also missing a new operation, which is the combined histograms (given the same double precision, combine the counts of things with the same value)
     *
     * What it lists for GATK is:
     *
     * QUAL:            set to missing
     *
     * INFO DP:         sum
     *
     * MQ:              median
     * MQRankSum:       median
     * RAW_MQ:          sum
     * QUALapprox:      sum
     * ReadPosRankSum:  median
     *
     * BaseQRankSum:    median
     * ClippingRankSum: median
     * MQ0:             median
     * ExcessHet:       median
     *
     * The GVCFs will have a FORMAT annotation for a read strand contingency table with the key "SB".
     * We combine those together across samples as element-by-element adds and move the sum to the INFO field.
     * (This gets used to calculate FS and SOR.)
     *
     * Allele-specific annotations have the same data as the traditional annotations, but the data for each
     * alternate allele is separated by a pipe as a delimiter.
     *
     * For allele-specific annotations we do a better job keeping (raw) data than the classic ones:
     * AS_RAW_*RankSum is combined by sum of histograms
     * AS_RAW_MQ is ebe sum
     * AS_SB_TABLE is ebe sum
     * AS_QD we can ignore for now.
     *
     * @param contig The contig for the given {@code variantDetails}.
     * @param position The position for the given {@code variantDetails}.
     * @param alleles The alleles for the given {@code variantDetails}.
     * @param variantDetails {@link VariantDetailData} for each sample occuring at the given locus to be merged.
     * @return A {@link VariantContext} that combines all the information in the given {@code variantDetails}.
     */
    private VariantContext mergeVariantDetails(final String contig,
                                               final long position,
                                               final List<Allele> alleles,
                                               final List<VariantDetailData> variantDetails) {

        final VariantContextBuilder variantContextBuilder = new VariantContextBuilder();

        // Populate trivial fields in variant context builder:
        variantContextBuilder.chr(contig)
                            .start(position)
                            .alleles(alleles);

        // Get the alleles we actually care about here:
        final List<Allele> genotypeAlleles = alleles.stream().filter( a -> !a.equals(Allele.NON_REF_ALLELE) ).collect(Collectors.toList());

        // no need to populate ID
        // no need to populate FILTER
        // no need to populate QUAL as per rules

        final Set<String>    samplesMissing  = new HashSet<>( sampleNames );
        final List<Genotype> sampleGenotypes = new ArrayList<>( sampleNames.size() );

        int depth = 0;
        int variantDepth = 0;
        int mapQualityDepth = 0;
        long rawMq = 0;
        double qualApprox = 0;

        final List<Double> mq             = new ArrayList<>(variantDetails.size());
        final List<Double> mqRankSum      = new ArrayList<>(variantDetails.size());
        final List<Double> readPosRankSum = new ArrayList<>(variantDetails.size());

        // Now go over each sample and aggregate the data as per the rules:
        for ( final VariantDetailData sampleData : variantDetails  ) {

            // ------------------------------------------------------------------
            // INFO fields:

            // Simple aggregations on:
            //   DP, MQ_DP, QUALapprox, RAW_MQ, VAR_DP
            if ( sampleData.infoDp != null ) {
                depth += sampleData.infoDp;
            }
            if ( sampleData.infoQualApprox != null ) {
                qualApprox += sampleData.infoQualApprox;
            }
            if ( sampleData.infoRawMq != null ) {
                rawMq += sampleData.infoRawMq;
            }
            if ( sampleData.infoMqDp != null ) {
                // TODO: This may not be right!
                mapQualityDepth += sampleData.infoMqDp;
            }
            if ( sampleData.infoVarDp != null ) {
                // TODO: This may not be right!
                variantDepth += sampleData.infoVarDp;
            }

            // Median calculations on:
            //   MQ, MQRankSum, readPosRankSum

            if ( sampleData.infoMq != null ) {
                mq.add(sampleData.infoMq);
            }
            if ( sampleData.infoMqRankSum != null ) {
                mqRankSum.add(sampleData.infoMqRankSum);
            }
            if ( sampleData.infoReadPosRankSum != null ) {
                readPosRankSum.add(sampleData.infoReadPosRankSum);
            }

            // Genotype fields should just be added as-is:
            sampleGenotypes.add( createGenotypeFromVariantDetails(sampleData, genotypeAlleles) );

            // Account for this sample in our sample set:
            samplesMissing.remove( sampleData.sampleName );
        }

        // Calculate the median values:
        double mqMedian             = new Median().evaluate(mq.stream().mapToDouble(Double::doubleValue).toArray());
        double mqRankSumMedian      = new Median().evaluate(mqRankSum.stream().mapToDouble(Double::doubleValue).toArray());
        double readPosRankSumMedian = new Median().evaluate(readPosRankSum.stream().mapToDouble(Double::doubleValue).toArray());

        // Ensure no NaN values:
        // TODO: Make sure these values are good:
        mqMedian             = Double.isNaN(mqMedian) ? MISSING_MQ_AND_READ_POS_RANK_SUM_DEFAULT_VALUE : mqMedian;
        mqRankSumMedian      = Double.isNaN(mqRankSumMedian) ? MISSING_MQ_AND_READ_POS_RANK_SUM_DEFAULT_VALUE : mqRankSumMedian;
        readPosRankSumMedian = Double.isNaN(readPosRankSumMedian) ? MISSING_MQ_AND_READ_POS_RANK_SUM_DEFAULT_VALUE : readPosRankSumMedian;

        // Add the aggregate values to our builder:
        variantContextBuilder.attribute(VCFConstants.DEPTH_KEY, depth);
        variantContextBuilder.attribute(GATKVCFConstants.RAW_QUAL_APPROX_KEY, qualApprox);
        variantContextBuilder.attribute(GATKVCFConstants.MAPPING_QUALITY_DEPTH, mapQualityDepth);
        variantContextBuilder.attribute(GATKVCFConstants.VARIANT_DEPTH_KEY, variantDepth);
        variantContextBuilder.attribute(GATKVCFConstants.RAW_MAPPING_QUALITY_WITH_DEPTH_KEY, String.format("%d%s%d", rawMq, RAW_MAPPING_QUALITY_WITH_DEPTH_KEY_SEPARATOR, depth));

        variantContextBuilder.attribute(VCFConstants.RMS_MAPPING_QUALITY_KEY, mqMedian);
        variantContextBuilder.attribute(GATKVCFConstants.MAP_QUAL_RANK_SUM_KEY, mqRankSumMedian);
        variantContextBuilder.attribute(GATKVCFConstants.READ_POS_RANK_SUM_KEY, readPosRankSumMedian);

        // Now add in empty values for each sample that was not in our variant details.
        // We assume these samples have high confidence reference regions at this allele
        for ( final String sample : samplesMissing ) {
            sampleGenotypes.add( createHighConfRefSampleGenotype(sample, depth, genotypeAlleles.get(0), genotypeAlleles.size() ) );
        }

        // Set our genotypes:
        variantContextBuilder.genotypes( sampleGenotypes );

        // Return the VC:
        return variantContextBuilder.make();
    }

    /**
     * Create a {@link Genotype} from the given {@link VariantDetailData} and {@code alleles.}
     * @param sampleData {@link VariantDetailData} containing sample information from which to create a {@link Genotype}.
     * @param alleles {@link Allele} objects from which to create the {@link Genotype}.
     * @return A {@link Genotype} object containing the information in the given {@code sampleData}.
     */
    private Genotype createGenotypeFromVariantDetails(final VariantDetailData sampleData, final List<Allele> alleles) {
        final GenotypeBuilder genotypeBuilder = new GenotypeBuilder();
        genotypeBuilder.name(sampleData.sampleName);

        // GT:AD:DP:GQ:PL:SB
        genotypeBuilder.alleles( alleles );
        genotypeBuilder.AD( sampleData.gtAd );
        genotypeBuilder.DP( sampleData.gtDp );
        genotypeBuilder.GQ( sampleData.gtGq );
        genotypeBuilder.PL( sampleData.gtPl );
        genotypeBuilder.attribute(
                VCFConstants.STRAND_BIAS_KEY,
                Arrays.stream(sampleData.gtSb).map(i -> Integer.toString(i)).collect(Collectors.joining(","))
        );
        return genotypeBuilder.make();
    }

    /**
     * Creates a {@link Genotype} object containing default "high-confidence" field values for the given
     * {@code sampleId}.
     *
     * These values are placeholders used only so the resuling {@link VariantContext} will contain information from the
     * given {@code sampleId} to enable genotyping.  These data are not necessarily reflective of the actual sample
     * information.
     *
     * @param sampleId The ID of a sample for which to generate default information in the given {@link VariantContextBuilder}.
     * @param depth The depth to use for the sample.
     * @param refAllele The reference {@link Allele} in this variant.
     * @param numAlleles Total number of alleles in this variant, including the reference allele.
     * @return The {@link Genotype} object with default "high-confidence" field values for the given {@code sampleId}.
     */
    private Genotype createHighConfRefSampleGenotype(final String sampleId,
                                                     final int depth,
                                                     final Allele refAllele,
                                                     final int numAlleles ) {

        final GenotypeBuilder genotypeBuilder = new GenotypeBuilder();

        genotypeBuilder.name(sampleId);

        // GT:AD:DP:GQ:PL:SB
        // TODO: this is probably wrong - need to remove one copy for the <NON_REF> allele so we can ignore it.
        genotypeBuilder.alleles( Collections.nCopies( numAlleles, refAllele ) );
        genotypeBuilder.AD( Collections.nCopies( numAlleles, depth ).stream().mapToInt( i -> i).toArray() );
        genotypeBuilder.DP(depth);
        genotypeBuilder.GQ(MISSING_CONF_THRESHOLD);

        // Setup our PLs:
        final List<Integer> pls = new ArrayList<>((numAlleles-1)*3);
        pls.add(0);
        for ( int i = 0 ; i < ((numAlleles-1)*3)-1 ; i++) { pls.add(MISSING_CONF_THRESHOLD); }
        genotypeBuilder.PL( pls.stream().mapToInt(i -> i).toArray() );

        // Setup our SBs:
        final List<Integer> sbs = new ArrayList<>(numAlleles * 2);
        sbs.add(HIGH_CONF_REFERENCE_STRAND_BIAS);
        sbs.add(HIGH_CONF_REFERENCE_STRAND_BIAS);
        for ( int i = 0 ; i < (numAlleles-1)*2 ; i++) { sbs.add(0); }
        genotypeBuilder.attribute(
                VCFConstants.STRAND_BIAS_KEY,
                sbs.stream().map( Object::toString ).collect(Collectors.joining(","))
        );

        return genotypeBuilder.make();
    }

    private void addBasicFieldsToVariantBuilder(final FieldValueList row,
                                                final VariantContextBuilder variantContextBuilder,
                                                final VariantDetailData variantDetailData) {
        variantContextBuilder
                .chr( row.get("reference_name").getStringValue() )
                // Add 1 because in the DB right now starts are exclusive:
                .start( row.get("start_position").getLongValue() + 1 )
                .stop( row.get("end_position").getLongValue() );

        // Get the filter(s):
        if ( !row.get("filter").isNull() ) {
            variantContextBuilder.filters(
                    row.get("filter").getRepeatedValue().stream()
                        .map( FieldValue::getStringValue )
                        .collect(Collectors.toSet())
            );
        }

        // Qual:
        if ( !row.get("quality").isNull() ) {
            final double qualVal = row.get("quality").getDoubleValue() / -10.0;
            variantContextBuilder.log10PError( qualVal );
            variantDetailData.qual = qualVal;
        }

        // Fill in alleles:
        final List<String> alleles = new ArrayList<>(5);
        alleles.add( row.get("reference_bases").getStringValue() );

        alleles.addAll(
                row.get("alternate_bases").getRepeatedValue().stream()
                    .map( fieldValue -> fieldValue.getRecordValue().get(0).getStringValue() )
                    .collect(Collectors.toList())
        );

        // Add the alleles:
        variantContextBuilder.alleles( alleles );
    }

    private void addInfoFieldsToVariantBuilder(final FieldValueList row,
                                               final VariantContextBuilder variantContextBuilder,
                                               final VariantDetailData variantDetailData) {

        addInfoFieldToVariantContextBuilder( row, VCFConstants.DEPTH_KEY, VCFConstants.DEPTH_KEY, variantContextBuilder );
        addInfoFieldToVariantContextBuilder( row, VCFConstants.RMS_MAPPING_QUALITY_KEY, VCFConstants.RMS_MAPPING_QUALITY_KEY, variantContextBuilder );
        addInfoFieldToVariantContextBuilder( row, GATKVCFConstants.MAP_QUAL_RANK_SUM_KEY, GATKVCFConstants.MAP_QUAL_RANK_SUM_KEY, variantContextBuilder );
        addInfoFieldToVariantContextBuilder( row, GATKVCFConstants.MAPPING_QUALITY_DEPTH, GATKVCFConstants.MAPPING_QUALITY_DEPTH, variantContextBuilder );
        addInfoFieldToVariantContextBuilder( row, GATKVCFConstants.RAW_QUAL_APPROX_KEY, GATKVCFConstants.RAW_QUAL_APPROX_KEY, variantContextBuilder );
        addInfoFieldToVariantContextBuilder( row, GATKVCFConstants.READ_POS_RANK_SUM_KEY, GATKVCFConstants.READ_POS_RANK_SUM_KEY, variantContextBuilder );
        addInfoFieldToVariantContextBuilder( row, GATKVCFConstants.VARIANT_DEPTH_KEY, GATKVCFConstants.VARIANT_DEPTH_KEY, variantContextBuilder );

        // Handle RAW_MQandDP field:
        if ( !row.get(VCFConstants.DEPTH_KEY).isNull() &&
             !row.get(GATKVCFConstants.RAW_RMS_MAPPING_QUALITY_KEY).isNull() ) {

            final Integer dp     = (int) row.get(VCFConstants.DEPTH_KEY).getLongValue();
            final long  raw_mq = Math.round(row.get(GATKVCFConstants.RAW_RMS_MAPPING_QUALITY_KEY).getDoubleValue());
            variantContextBuilder.attribute(GATKVCFConstants.RAW_MAPPING_QUALITY_WITH_DEPTH_KEY, String.format("%d%s%d", raw_mq, RAW_MAPPING_QUALITY_WITH_DEPTH_KEY_SEPARATOR, dp));
        }

        // ======

        // TODO: What happens if one or more of these fields is null?  How do we combine them?
        if ( !row.get(VCFConstants.DEPTH_KEY).isNull() ) {
            variantDetailData.infoDp = (int) row.get(VCFConstants.DEPTH_KEY).getLongValue();
        }
        if ( !row.get(VCFConstants.RMS_MAPPING_QUALITY_KEY).isNull() ) {
            variantDetailData.infoMq = row.get(VCFConstants.RMS_MAPPING_QUALITY_KEY).getDoubleValue();
        }
        if ( !row.get(GATKVCFConstants.MAP_QUAL_RANK_SUM_KEY).isNull() ) {
            variantDetailData.infoMqRankSum = row.get(GATKVCFConstants.MAP_QUAL_RANK_SUM_KEY).getDoubleValue();
        }
        if ( !row.get(GATKVCFConstants.MAPPING_QUALITY_DEPTH).isNull() ) {
            variantDetailData.infoMqDp = (int) row.get(GATKVCFConstants.MAPPING_QUALITY_DEPTH).getLongValue();
        }
        if ( !row.get(GATKVCFConstants.RAW_QUAL_APPROX_KEY).isNull() ) {
            variantDetailData.infoQualApprox = (int) row.get(GATKVCFConstants.RAW_QUAL_APPROX_KEY).getLongValue();
        }
        if ( !row.get(GATKVCFConstants.READ_POS_RANK_SUM_KEY).isNull() ) {
            variantDetailData.infoReadPosRankSum = row.get(GATKVCFConstants.READ_POS_RANK_SUM_KEY).getDoubleValue();
        }
        if ( !row.get(GATKVCFConstants.VARIANT_DEPTH_KEY).isNull() ) {
            variantDetailData.infoVarDp = (int) row.get(GATKVCFConstants.VARIANT_DEPTH_KEY).getLongValue();
        }
        if ( !row.get(GATKVCFConstants.RAW_RMS_MAPPING_QUALITY_KEY).isNull() ) {
            variantDetailData.infoRawMq = Math.round(row.get(GATKVCFConstants.RAW_RMS_MAPPING_QUALITY_KEY).getDoubleValue());
        }
    }

    private void addSampleFieldsToVariantBuilder( final FieldValueList row,
                                                  final Schema schema,
                                                  final VariantContextBuilder variantContextBuilder,
                                                  final VariantDetailData variantDetailData) {

        // Create our call data with the schema information
        // to enable name-based access to fields:
        final FieldValueList callData =
                FieldValueList.of(
                        // I'm not sure why we need this extra layer of unwrapping:
                        row.get("call").getRecordValue().get(0).getRecordValue(),
                        schema.getFields().get("call").getSubFields()
                );

        final Allele refAllele = Allele.create(row.get("reference_bases").getStringValue().getBytes(), true);

        final String sampleName = callData.get("name").getStringValue();

        final int dp = (int)callData.get(VCFConstants.DEPTH_KEY).getLongValue();
        final int gq = (int)callData.get(VCFConstants.GENOTYPE_QUALITY_KEY).getLongValue();

        // Create the genotype builder and get to work:
        final GenotypeBuilder genotypeBuilder = new GenotypeBuilder();

        // Get the scalar fields first:
        genotypeBuilder.name(sampleName);
        genotypeBuilder.DP(dp);
        genotypeBuilder.GQ(gq);
        addScalarAttributeToGenotypeBuilder( callData, "phaseset", genotypeBuilder );
        addScalarAttributeToGenotypeBuilder( callData, GATKVCFConstants.MIN_DP_FORMAT_KEY, genotypeBuilder );
        addScalarAttributeToGenotypeBuilder( callData, GATKVCFConstants.HAPLOTYPE_CALLER_PHASING_GT_KEY, genotypeBuilder );
        addScalarAttributeToGenotypeBuilder( callData, GATKVCFConstants.HAPLOTYPE_CALLER_PHASING_ID_KEY, genotypeBuilder );

        variantDetailData.sampleName = sampleName;
        variantDetailData.gtDp = dp;
        variantDetailData.gtGq = gq;
        //TODO: Do we need to track phaseset here?
        //TODO: Do we need to track MIN_DP here?
        //TODO: Do we need to track PGT here?
        //TODO: Do we need to track PID here?

        // Get the array fields:

        // Add the alleles:
        final FieldList alternateBasesSchema = schema.getFields().get("alternate_bases").getSubFields();

        final List<Allele> alleleList = new ArrayList<>();
        callData.get("genotype").getRepeatedValue().stream()
                .map( f -> (int)f.getLongValue() )
                .forEach( gtIndex ->
                    {
                        if ( gtIndex == 0 ) {
                            alleleList.add(refAllele);
                        }
                        else {

                            // Account for the ref allele's position in the list:
                            gtIndex--;

                            final FieldValueList altAlleleFields = FieldValueList.of(
                                    // Get the correct alternate allele based on the index:
                                    row.get("alternate_bases").getRecordValue().get(gtIndex).getRecordValue(),
                                    alternateBasesSchema
                            );

                            alleleList.add(
                                    Allele.create(
                                            altAlleleFields
                                                .get("alt")
                                                .getStringValue()
                                    )
                            );
                        }
                    }
                );
        genotypeBuilder.alleles( alleleList );

        // Add the alleles to our variantDetailData:
        variantDetailData.gtGenotype = callData.get("genotype").getRepeatedValue().stream()
                .mapToInt( f -> (int)f.getLongValue() )
                .toArray();

        // AD should never be null:
        final int ad[] =
                callData.get(VCFConstants.GENOTYPE_ALLELE_DEPTHS).getRecordValue().stream()
                        .map( FieldValue::getLongValue )
                        .mapToInt( Long::intValue )
                        .toArray();

        genotypeBuilder.AD(ad);
        variantDetailData.gtAd = ad;

        // PL should never be null:
        final int pl[] = callData.get(VCFConstants.GENOTYPE_PL_KEY).getRecordValue().stream()
                                 .map( FieldValue::getLongValue )
                                 .mapToInt( Long::intValue )
                                 .toArray();
        genotypeBuilder.PL(pl);
        variantDetailData.gtPl = pl;

        if ( !callData.get(VCFConstants.STRAND_BIAS_KEY).isNull() ) {

            final Integer sb[] =
                    callData.get(VCFConstants.STRAND_BIAS_KEY).getRecordValue().stream()
                            .map( FieldValue::getLongValue )
                            .mapToInt( Long::intValue )
                            .boxed()
                            .toArray(Integer[]::new);

            genotypeBuilder.attribute(VCFConstants.STRAND_BIAS_KEY,
                    Arrays.stream(sb).map( Object::toString ).collect(Collectors.joining(",")));

            variantDetailData.gtSb = sb;
        }

        variantContextBuilder.genotypes( genotypeBuilder.make() );
    }

    private void addScalarAttributeToGenotypeBuilder(final FieldValueList row,
                                                     final String fieldName,
                                                     final GenotypeBuilder genotypeBuilder ) {
        // Only add the info if it is not null:
        if ( !row.get(fieldName).isNull() ) {
            genotypeBuilder.attribute(fieldName, row.get(fieldName).getStringValue());
        }
    }

    private void addInfoFieldToVariantContextBuilder(final FieldValueList row,
                                                     final String columnName,
                                                     final String infoFieldName,
                                                     final VariantContextBuilder variantContextBuilder ) {
        // Only add the info if it is not null:
        if ( !row.get(columnName).isNull() ) {
            variantContextBuilder.attribute(infoFieldName, row.get(columnName).getStringValue());
        }
    }

    private void populateSampleNames() {
        // Get the query string:
        final String sampleListQueryString = getSampleListQueryString();

        logger.info("Created Query: \n" + sampleListQueryString);

        // Execute the query:
        final TableResult result = BigQueryUtils.executeQuery(sampleListQueryString);

        // Show our pretty results:
        logger.info("Pretty Query Results:");
        final String prettyQueryResults = BigQueryUtils.getResultDataPrettyString(result);
        logger.info( "\n" + prettyQueryResults );

        // Add our samples to our map:
        for ( final FieldValueList row : result.iterateAll() ) {
            sampleNames.add( row.get(0).getStringValue() );
        }
    }

    private String getVariantQueryString( final SimpleInterval interval ) {

        // TODO: When finalized, remove this variable:
        final String limit_string = "LIMIT 10";

        // TODO: Replace column names with variable field values for consistency (as above)
        return "SELECT " + "\n" +
                "  reference_name, start_position, end_position, reference_bases, alternate_bases, names, quality, " + "\n" +
                "  filter, call, BaseQRankSum, ClippingRankSum, variants.DP AS DP, ExcessHet, MQ, " + "\n" +
                "  MQRankSum, MQ_DP, QUALapprox, RAW_MQ, ReadPosRankSum, VarDP, variant_samples.state" + "\n" +
                "FROM " +  "\n" +
                "  `" + getFQPositionTable(interval) + "` AS variant_samples " + "\n" +
                "INNER JOIN " + "\n" +
                " `" + getFQVariantTable(interval) + "` AS variants ON variants.end_position = variant_samples.position, " + "\n" +
                "UNNEST(variants.call) AS samples," + "\n" +
                "UNNEST(variants.alternate_bases) AS alt_bases" + "\n" +
                "WHERE " + "\n" +
                "  reference_name = '" + interval.getContig() + "' AND" + "\n" +
                "  samples.name = variant_samples.sample AND" + "\n" +
                "  alt_bases.alt != '<NON_REF>' AND" + "\n" +
                // Since position corresponds to end_position, we don't need to subtract 1 from thbe start here: "\n" +
                "  (position >= " + interval.getStart() + " AND position <= " + interval.getEnd() + ") AND " + "\n" +
                "  variant_samples.state = 1 " + "\n" +
                "ORDER BY reference_name, start_position, end_position" + "\n" +
                limit_string;
    }

    private static String getSampleListQueryString() {
        return "SELECT * FROM `" + getFQTableName(SAMPLE_TABLE)+ "`";
    }

    //==================================================================================================================
    // Helper Data Types:

    /**
     * A class to hold variant detail information without constructing an entire {@link VariantContext} object.
     */
    private class VariantDetailData {

        // High-level fields:
        String sampleName;
        Double qual;

        // Info Fields:
        //DP=7;MQ=34.15;MQRankSum=1.300;MQ_DP=7;QUALapprox=10;RAW_MQandDP=239.05,7;ReadPosRankSum=1.754;VarDP=7
        Integer infoDp;
        Double infoMq;
        Double infoMqRankSum;
        Integer infoMqDp;
        Integer infoQualApprox;
        Long infoRawMq;
        Double infoReadPosRankSum;
        Integer infoVarDp;

        // Genotype fields:
        // GT:AD:DP:GQ:PL:SB 0/1:   4,3,0:  7: 10:  10,0,119,101,128,229:0,4,0,3
        int gtGenotype[];
        int gtAd[];
        Integer gtDp;
        Integer gtGq;
        int gtPl[];
        Integer gtSb[];
    }
}