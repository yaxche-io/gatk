package org.broadinstitute.hellbender.utils.codecs.gtf;

import com.google.common.annotations.VisibleForTesting;
import htsjdk.samtools.util.IOUtil;
import htsjdk.tribble.readers.LineIterator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.exceptions.UserException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Codec to decode data in GTF format from ENSEMBL.
 * According to ENSEMBL, GTF files downloaded from them conform to GFF version 2 (http://gmod.org/wiki/GFF2).
 */
final public class EnsemblGtfCodec extends AbstractGtfCodec<GencodeGtfFeature> {

    private static final Logger logger = LogManager.getLogger(EnsemblGtfCodec.class);

    //==================================================================================================================
    // Public Static Members:

    //==================================================================================================================
    // Private Static Members:

    //==================================================================================================================
    // Private Members:

    private final List<String> header         = new ArrayList<>();
    private int                currentLineNum = 1;
    private String version = "";

    //==================================================================================================================
    // Constructors:

    public EnsemblGtfCodec() {
        super(GencodeGtfFeature.class);
    }

    //==================================================================================================================
    // Override Methods:

    @Override
    String  getGtfFileType() {
        return "ENSEMBL";
    }

    @Override
    String getLineComment() {
        return "#!";
    }

    @Override
    int getCurrentLineNumber() {
        return currentLineNum;
    }

    @Override
    List<String> getHeader() {
        return header;
    }

    @Override
    boolean passesFileNameCheck(final String inputFilePath) {
        try {
            final Path p = IOUtil.getPath(inputFilePath);

            return p.getFileName().toString().toLowerCase().endsWith("." + GTF_FILE_EXTENSION);
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
    List<String> readActualHeader(final LineIterator reader) {

        // Make sure we start with a clear header:
        header.clear();

        // Read in the header lines:
        ingestHeaderLines(reader);

        // Validate our header:
        validateHeader(header, true);

        // Set our line number to be the line of the first actual Feature:
        currentLineNum = HEADER_NUM_LINES + 1;

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
            feature.setUcscGenomeVersion(getVersionFromHeader());

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
        if ( ! validateEnsemblGtfFeature( decodedFeature ) ) {
            throw new UserException.MalformedFile("Decoded feature is not valid: " + decodedFeature);
        }

        return decodedFeature;
    }

    /**
     * Get the version information from the header.
     */
    private String getVersionFromHeader() {
        // header version is of the form:
        //     #!genome-version ASM584v2
        // So we get the stuff after the space:
        return header.get(1).split("[ \t]")[1];
    }

    //==================================================================================================================
    // Static Methods:

    /**
     * Validates a given {@link GencodeGtfFeature} against a given version of the ENSEMBL GTF file spec.
     * This method ensures that all required fields are defined, but does not interrogate their values.
     * @param feature A {@link GencodeGtfFeature} to validate.
     * @return True if {@code feature} contains all required fields for the given GENCODE GTF version, {@code gtfVersion}
     */
    public static boolean validateEnsemblGtfFeature(final GencodeGtfFeature feature) {

        if ( feature == null ) {
            return false;
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

        if ( featureType != GencodeGtfFeature.FeatureType.GENE) {
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

    //==================================================================================================================
    // Instance Methods:

    /**
     * Check if the given header of a tentative ENSEMBL GTF file is, in fact, the header to such a file.
     * @param header Header lines to check for conformity to ENSEMBL GTF specifications.
     * @param throwIfInvalid If true, will throw a {@link UserException.MalformedFile} if the header is invalid.
     * @return true if the given {@code header} is that of a ENSEMBL GTF file; false otherwise.
     */
    @VisibleForTesting
    boolean validateHeader(final List<String> header, final boolean throwIfInvalid) {
        if ( header.size() != HEADER_NUM_LINES) {
            if ( throwIfInvalid ) {
                throw new UserException.MalformedFile(
                        "ENSEMBL GTF Header is of unexpected length: " +
                                header.size() + " != " + HEADER_NUM_LINES);
            }
            else {
                return false;
            }
        }

        // Check the normal commented fields:
        return checkHeaderLineStartsWith(header, 0, "genome-build") &&
               checkHeaderLineStartsWith(header, 1, "genome-version") &&
               checkHeaderLineStartsWith(header, 2, "genome-date") &&
               checkHeaderLineStartsWith(header, 3, "genome-build-accession") &&
               checkHeaderLineStartsWith(header, 4, "genebuild-last-updated");
    }

    //==================================================================================================================
    // Helper Data Types:

}
