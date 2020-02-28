package org.broadinstitute.hellbender.utils.codecs.gtf;

import com.google.common.annotations.VisibleForTesting;
import htsjdk.samtools.util.IOUtil;
import htsjdk.tribble.AbstractFeatureCodec;
import htsjdk.tribble.readers.LineIterator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.exceptions.UserException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link htsjdk.tribble.Tribble} Codec to read data from a GENCODE GTF file.
 *
 * GENCODE GTF Files are defined here: https://www.gencodegenes.org/data_format.html
 *
 * This codec will scan through a GENCODE GTF file and return {@link GencodeGtfFeature} objects.
 * {@link GencodeGtfFeature} objects contain fields that have sub-features.  All features are
 * grouped by gene (this is the natural formatting of a GENCODE GTF file).
 *
 * All fields exist in the Abstract {@link GencodeGtfFeature}.  The subclasses contain representations of the logical
 * data hierarchy that reflect how the data were presented in the feature file itself (to preserve the natural
 * grouping by gene).
 * The {@link GencodeGtfFeature} logical data hierarchy (NOT the class hierarchy) is as follows
 * (with | representing a "has a" relationship)
 *
 * +--> {@link GencodeGtfGeneFeature}
 *    |
 *    +--> {@link GencodeGtfTranscriptFeature}
 *       |
 *       +--> {@link GencodeGtfSelenocysteineFeature}
 *       +--> {@link GencodeGtfUTRFeature}
 *       +--> {@link GencodeGtfExonFeature}
 *          |
 *          +--> {@link GencodeGtfCDSFeature}
 *          +--> {@link GencodeGtfStartCodonFeature}
 *          +--> {@link GencodeGtfStopCodonFeature}
 *
 * {@link htsjdk.tribble.Tribble} indexing has been tested and works as expected.
 * Does not support {@link htsjdk.tribble.index.tabix.TabixIndex} indexing.
 *
 * Unlike many other {@link htsjdk.tribble.Tribble} codecs, this one scans multiple input file lines to produce
 * a single feature.  This is due to how GENCODE GTF files are structured (essentially grouped by contig and gene).
 * For this reason, {@link GencodeGtfCodec} inherits from {@link AbstractFeatureCodec}, as opposed to {@link htsjdk.tribble.AsciiFeatureCodec}
 * (i.e. {@link htsjdk.tribble.AsciiFeatureCodec}s read a single line at a time, and {@link AbstractFeatureCodec} do not have that explicit purpose).
 *
 * Created by jonn on 7/21/17.
 */
final public class GencodeGtfCodec extends AbstractGtfCodec<GencodeGtfFeature> {

    private static final Logger logger = LogManager.getLogger(GencodeGtfCodec.class);

    private static final int GENCODE_GTF_MIN_VERSION_NUM_INCLUSIVE = 19;

    /**
     * Maximum version of gencode that will not generate a warning.  This parser will still attempt to parse versions above this number, but a warning about potential errors will appear.
     */
    private static final int GENCODE_GTF_MAX_VERSION_NUM_INCLUSIVE = 28;

    public static final String GENCODE_GTF_FILE_PREFIX = "gencode";

    private int currentLineNum = 1;
    private final List<String> header = new ArrayList<>();
    private static final int HEADER_NUM_LINES = 5;

    private static final Pattern VERSION_PATTERN = Pattern.compile("version (\\d+)");
    private int versionNumber;

    // ============================================================================================================

    /**
     * Gets the UCSC version corresponding to the given gencode version.
     * Version equivalences obtained here:
     *
     *  https://genome.ucsc.edu/FAQ/FAQreleases.html
     *  https://www.gencodegenes.org/releases/
     *
     * @param gencodeVersion The gencode version to convert to UCSC version.
     * @return The UCSC version in a {@link String} corresponding to the given gencode version.
     */
    private static String getUcscVersionFromGencodeVersion(final int gencodeVersion) {
        if (gencodeVersion < GENCODE_GTF_MIN_VERSION_NUM_INCLUSIVE) {
            throw new GATKException("Gencode version is too far out of date.  Cannot decode: " + gencodeVersion);
        }

        if ( gencodeVersion < 25 ) {
            return "hg19";
        }
        else {
            return "hg38";
        }
    }

    // ============================================================================================================

    public GencodeGtfCodec() {
        super(GencodeGtfFeature.class);
    }

    // ============================================================================================================

    @Override
    int getCurrentLineNumber() {
        return currentLineNum;
    }

    @Override
    List<String> getHeader() {
        return header;
    }

    @Override
    public GencodeGtfFeature decode(final LineIterator lineIterator) {

        GencodeGtfFeature decodedFeature = null;

        // Create some caches for our data (as we need to group it):
        GencodeGtfGeneFeature gene = null;
        GencodeGtfTranscriptFeature transcript = null;
        final List<GencodeGtfExonFeature> exonStore = new ArrayList<>();
        final List<GencodeGtfFeature> leafFeatureStore = new ArrayList<>();

        boolean needToFlushRecords = false;

        // Accumulate lines until we have a full gene and all of its internal features:
        while ( lineIterator.hasNext() ) {

            final String line = lineIterator.peek();

            // We must assume we can get header lines.
            // If we get a header line, we return null.
            // This allows indexing to work.
            if ( line.startsWith(getLineComment()) ) {
                lineIterator.next();
                return null;
            }

            // Split the line into different GTF Fields
            final String[] splitLine = splitGtfLine(line);

            // We need to key off the feature type to collapse our accumulated records:
            final GencodeGtfFeature.FeatureType featureType = GencodeGtfFeature.FeatureType.getEnum( splitLine[FEATURE_TYPE_FIELD_INDEX] );

            // Create a baseline feature to add into our data:
            final GencodeGtfFeature feature = GencodeGtfFeature.create(splitLine);

            // Make sure we keep track of the line number for if and when we need to write the file back out:
            feature.setFeatureOrderNumber(currentLineNum);

            // Set our UCSC version number:
            feature.setUcscGenomeVersion(getUcscVersionFromGencodeVersion(versionNumber));

            // Once we see another gene we take all accumulated records and combine them into the
            // current GencodeGtfFeature.
            // Then we then break out of the loop and return the last full gene object.
            if ((gene != null) && (featureType == GencodeGtfFeature.FeatureType.GENE)) {

                aggregateRecordsIntoGeneFeature(gene, transcript, exonStore, leafFeatureStore);

                // If we found a new gene line, we set our decodedFeature to be
                // the gene we just finished building.
                //
                // We intentionally break here so that we do not call lineIterator.next().
                // This is so that the new gene (i.e. the one that triggered us to be in this if statement)
                // remains intact for the next call to decode.
                decodedFeature = gene;

                needToFlushRecords = false;

                break;
            }
            // Once we see a transcript we aggregate our data into our current gene object and
            // set the current transcript object to the new transcript we just read.
            // Then we continue reading from the line iterator.
            else if ((transcript != null) && (featureType == GencodeGtfFeature.FeatureType.TRANSCRIPT)) {

                aggregateRecordsIntoGeneFeature(gene, transcript, exonStore, leafFeatureStore);

                transcript = (GencodeGtfTranscriptFeature) feature;
                ++currentLineNum;

                needToFlushRecords = true;
            }
            else {
                // We have not reached the end of this set of gene / transcript records.
                // We must cache these records together so we can create a meaningful data hierarchy from them all.
                // Records are stored in their Feature form, not string form.

                // Add the feature to the correct storage unit for easy assembly later:
                switch (featureType) {
                    case GENE:
                        gene = (GencodeGtfGeneFeature)feature;
                        break;
                    case TRANSCRIPT:
                        transcript = (GencodeGtfTranscriptFeature)feature;
                        break;
                    case EXON:
                        exonStore.add((GencodeGtfExonFeature)feature);
                        break;
                    default:
                        leafFeatureStore.add(feature);
                        break;
                }

                needToFlushRecords = false;
                ++currentLineNum;
            }

            // Increment our iterator here so we don't accidentally miss any features from the following gene
            lineIterator.next();
        }

        // For the last record in the file, we need to do one final check to make sure that we don't miss it.
        // This is because there will not be a subsequent `gene` line to read:
        if ( (gene != null) && (needToFlushRecords || (!exonStore.isEmpty()) || (!leafFeatureStore.isEmpty())) ) {

            aggregateRecordsIntoGeneFeature(gene, transcript, exonStore, leafFeatureStore);
            decodedFeature = gene;
        }

        // If we have other records left over we should probably yell a lot,
        // as this is bad.
        //
        // However, this should never actually happen.
        //
        if ( (!exonStore.isEmpty()) || (!leafFeatureStore.isEmpty()) ) {

            if (!exonStore.isEmpty()) {
                logger.error("Gene Feature Aggregation: Exon store not empty: " + exonStore.toString());
            }

            if (!leafFeatureStore.isEmpty()) {
                logger.error("Gene Feature Aggregation: leaf feature store not empty: " + leafFeatureStore.toString());
            }

            final String msg = "Aggregated data left over after parsing complete: Exons: " + exonStore.size() + " ; LeafFeatures: " + leafFeatureStore.size();
            throw new GATKException.ShouldNeverReachHereException(msg);
        }

        // Now we validate our feature before returning it:
        if ( ! validateGencodeGtfFeature( decodedFeature, versionNumber ) ) {
            throw new UserException.MalformedFile("Decoded feature is not valid: " + decodedFeature);
        }

        return decodedFeature;
    }

    @Override
    List<String> readActualHeader(final LineIterator reader) {

        // Make sure we start with a clear header:
        header.clear();

        // Clear our version number too:
        versionNumber = -1;

        // Read in the header lines:
        ingestHeaderLines(reader);

        // Validate our header:
        validateHeader(header, true);

        // Set our version number:
        setVersionNumber();

        // Set our line number to be the line of the first actual Feature:
        currentLineNum = HEADER_NUM_LINES + 1;

        return header;
    }

    /**
     * Sets {@link #versionNumber} to the number corresponding to the value in the header.
     */
    private void setVersionNumber() {
        try {
            final Matcher versionMatcher = VERSION_PATTERN.matcher(header.get(0));
            versionMatcher.find();
            versionNumber = Integer.valueOf(versionMatcher.group(1));
        }
        catch (final NumberFormatException ex) {
            throw new UserException("Could not read version number from header", ex);
        }
    }

    /**
     * Validates a given {@link GencodeGtfFeature} against a given version of the GENCODE GTF file spec.
     * This method ensures that all required fields are defined, but does not interrogate their values.
     * @param feature A {@link GencodeGtfFeature} to validate.
     * @param gtfVersion The GENCODE GTF version against which to validate {@code feature}
     * @return True if {@code feature} contains all required fields for the given GENCODE GTF version, {@code gtfVersion}
     */
    static public boolean validateGencodeGtfFeature(final GencodeGtfFeature feature, final int gtfVersion) {

        if ( feature == null ) {
            return false;
        }

        if (gtfVersion < GencodeGtfCodec.GENCODE_GTF_MIN_VERSION_NUM_INCLUSIVE) {
            throw new GATKException("Invalid version number for validation: " + gtfVersion +
                    " must be above: " + GencodeGtfCodec.GENCODE_GTF_MIN_VERSION_NUM_INCLUSIVE);
        }

        final GencodeGtfFeature.FeatureType featureType = feature.getFeatureType();

        if (feature.getChromosomeName() == null) {
            return false;
        }
        if (feature.getAnnotationSource() == null) {
            return false;
        }
        if (feature.getFeatureType() == null) {
            return false;
        }
        if (feature.getGenomicStrand() == null) {
            return false;
        }
        if (feature.getGenomicPhase() == null) {
            return false;
        }

        if (feature.getGeneId() == null) {
            return false;
        }
        if (feature.getGeneType() == null) {
            return false;
        }
        if (feature.getGeneName() == null) {
            return false;
        }
        if (feature.getLocusLevel() == null) {
            return false;
        }

        if ( gtfVersion < 26 ) {
            if (feature.getGeneStatus() == null) {
                return false;
            }
            if (feature.getTranscriptStatus() == null) {
                return false;
            }
        }

        if ( (featureType != GencodeGtfFeature.FeatureType.GENE) ||
                (gtfVersion < 21) ) {
            if (feature.getTranscriptId() == null) {
                return false;
            }
            if (feature.getTranscriptType() == null) {
                return false;
            }
            if (feature.getTranscriptName() == null) {
                return false;
            }
        }

        if ( (featureType != GencodeGtfFeature.FeatureType.GENE) &&
             (featureType != GencodeGtfFeature.FeatureType.TRANSCRIPT) &&
             (featureType != GencodeGtfFeature.FeatureType.SELENOCYSTEINE) ) {

            if (feature.getExonNumber() == GencodeGtfFeature.NO_EXON_NUMBER) {
                return false;
            }
            if (feature.getExonId() == null) {
                return false;
            }
        }

        return true;
    }

    @Override
    boolean passesFileNameCheck(final String inputFilePath) {
        try {
            final Path p = IOUtil.getPath(inputFilePath);

            return p.getFileName().toString().toLowerCase().startsWith(GENCODE_GTF_FILE_PREFIX) &&
                    p.getFileName().toString().toLowerCase().endsWith("." + GTF_FILE_EXTENSION);
        }
        catch (final FileNotFoundException ex) {
            logger.warn("File does not exist! - " + inputFilePath + " - returning name check as failure.");
        }
        catch (final IOException ex) {
            logger.warn("Caught IOException on file: " + inputFilePath + " - returning name check as failure.");
        }

        return false;
    }

    @Override
    String getLineComment() {
        return "##";
    }

    @Override
    String  getGtfFileType() {
        return "GENCODE";
    }

    // ============================================================================================================

    /**
     * Check if the given header of a tentative GENCODE GTF file is, in fact, the header to such a file.
     * Will also return true if the file is a general GTF file (i.e. a GTF file that was not created and
     * maintained by GENCODE).
     * @param header Header lines to check for conformity to GENCODE GTF specifications.
     * @param throwIfInvalid If true, will throw a {@link UserException.MalformedFile} if the header is invalid.
     * @return true if the given {@code header} is that of a GENCODE GTF file; false otherwise.
     */
    @VisibleForTesting
    boolean validateHeader(final List<String> header, final boolean throwIfInvalid) {
        if ( header.size() != HEADER_NUM_LINES) {
            if ( throwIfInvalid ) {
                throw new UserException.MalformedFile(
                        "GENCODE GTF Header is of unexpected length: " +
                                header.size() + " != " + HEADER_NUM_LINES);
            }
            else {
                return false;
            }
        }

        // Check the normal commented fields:
        if ( !checkHeaderLineStartsWith(header,0, "description:") ) {
            return false;
        }

        if ( !header.get(0).contains("version") ) {
            if ( throwIfInvalid ) {
                throw new UserException.MalformedFile(
                        "GENCODE GTF Header line 1 does not contain version specification: " +
                                header.get(0));
            }
            else {
                return false;
            }
        }

        // Grab the version from the file and make sure it's within the acceptable range:
        final Matcher versionMatcher = VERSION_PATTERN.matcher(header.get(0));
        if ( !versionMatcher.find() ) {
            if ( throwIfInvalid ) {
                throw new UserException.MalformedFile(
                        "GENCODE GTF Header line 1 does not contain a recognizable version number: " +
                                header.get(0));
            }
            else {
                return false;
            }
        }

        try {
            final int versionNumber = Integer.valueOf(versionMatcher.group(1));
            if (versionNumber < GENCODE_GTF_MIN_VERSION_NUM_INCLUSIVE) {
                final String message = "GENCODE GTF Header line 1 has an out-of-date (< v" + GENCODE_GTF_MIN_VERSION_NUM_INCLUSIVE + " version number (" +
                        versionNumber + "): " + header.get(0);
                if (throwIfInvalid) {
                    throw new UserException.MalformedFile(message);
                } else {
                    logger.warn(message + "   Continuing, but errors may occur.");
                }
            }

            if (versionNumber > GENCODE_GTF_MAX_VERSION_NUM_INCLUSIVE) {
                logger.warn("GENCODE GTF Header line 1 has a version number that is above maximum tested version (v " + GENCODE_GTF_MAX_VERSION_NUM_INCLUSIVE + ") (given: " +
                        versionNumber + "): " + header.get(0) + "   Continuing, but errors may occur.");
            }
        }
        catch (final NumberFormatException ex) {
            if ( throwIfInvalid ) {
                throw new UserException("Could not create number value for version: " + versionMatcher.group(1), ex);
            }
            else {
                return false;
            }
        }

        return checkHeaderLineStartsWith(header, 1, "provider: GENCODE") &&
                checkHeaderLineStartsWith(header, 2, "contact: gencode") &&
                checkHeaderLineStartsWith(header, 3, "format: gtf") &&
                checkHeaderLineStartsWith(header, 4, "date:");
    }
}
