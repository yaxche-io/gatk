package org.broadinstitute.hellbender.utils.codecs.gtf;

import com.google.common.annotations.VisibleForTesting;
import htsjdk.samtools.util.CloserUtil;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.LocationAware;
import htsjdk.tribble.AbstractFeatureCodec;
import htsjdk.tribble.Feature;
import htsjdk.tribble.FeatureCodecHeader;
import htsjdk.tribble.readers.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.broadinstitute.hellbender.exceptions.UserException;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public abstract class AbstractGtfCodec<T extends Feature> extends AbstractFeatureCodec<T, LineIterator> {

    static final Logger logger = LogManager.getLogger(AbstractGtfCodec.class);

    //==================================================================================================================
    // Public Static Members:
    public static final String GTF_FILE_EXTENSION = "gtf";

    //==================================================================================================================
    // Private/Protected Static Members:
    static final int HEADER_NUM_LINES = 5;
    static final String FIELD_DELIMITER = "\t";

    static final int NUM_COLUMNS = 9;
    static final int FEATURE_TYPE_FIELD_INDEX = 2;

    //==================================================================================================================
    // Private Members:


    //==================================================================================================================
    // Constructors:
    protected AbstractGtfCodec(final Class<T> myClass) {
        super(myClass);
    }

    //==================================================================================================================
    // Override Methods:

    @Override
    public boolean canDecode(final String inputFilePath) {

        boolean canDecode;
        try {
            // Simple file and name checks to start with:
            final Path p = IOUtil.getPath(inputFilePath);

            canDecode = passesFileNameCheck(inputFilePath);

            if (canDecode) {

                // Crack open the file and look at the top of it:
                try ( final BufferedReader br = new BufferedReader(new InputStreamReader(Files.newInputStream(p))) ) {

                    // TThe first HEADER_NUM_LINES compose the header of a valid GTF File:
                    final List<String> headerLines = new ArrayList<>(HEADER_NUM_LINES);

                    for (int i = 0; i < HEADER_NUM_LINES; ++i) {
                        final String line = br.readLine();
                        if ( line == null ) {
                            break;
                        }
                        headerLines.add( line );
                    }

                    // Validate our header:
                    canDecode = validateHeader(headerLines);
                }

            }
        }
        catch (final FileNotFoundException ex) {
            logger.warn("File does not exist! - " + inputFilePath + " - returning can decode as failure.");
            canDecode = false;
        }
        catch (final IOException ex) {
            logger.warn("Caught IOException on file: " + inputFilePath + " - returning can decode as failure.");
            canDecode = false;
        }

        return canDecode;
    }

    // ============================================================================================================
    // Trivial override methods that are pulled form AsciiFeatureCodec
    // This was done to ensure that this was a reasonable Codec class (with good interfaces for reading features).

    @Override
    public void close(final LineIterator lineIterator) {
        CloserUtil.close(lineIterator);
    }

    @Override
    public boolean isDone(final LineIterator lineIterator) {
        return !lineIterator.hasNext();
    }

    @Override
    public LineIterator makeSourceFromStream(final InputStream bufferedInputStream) {
        return new LineIteratorImpl(new SynchronousLineReader(bufferedInputStream));
    }

    @Override
    public FeatureCodecHeader readHeader(final LineIterator lineIterator) throws IOException {
        return new FeatureCodecHeader(readActualHeader(lineIterator), FeatureCodecHeader.NO_HEADER_END);
    }

    @Override
    public LocationAware makeIndexableSourceFromStream(final InputStream bufferedInputStream) {
        return new AsciiLineReaderIterator(AsciiLineReader.from(bufferedInputStream));
    }

    //==================================================================================================================
    // Static Methods:

    /**
     * Aggregates the given feature sets into a single gene feature.
     *
     * The given gene is updated using modifiers.
     * {@code exonStore} and {@code leafFeatureStore} are cleared of all data.
     *
     * @param gene {@link GencodeGtfGeneFeature} into which to aggregate features.
     * @param transcript {@link GencodeGtfTranscriptFeature} to insert into {@code gene}
     * @param exonStore {@link List} of {@link GencodeGtfExonFeature}s to insert into corresponding {@link GencodeGtfTranscriptFeature} {@code transcript}
     * @param leafFeatureStore {@link List} of {@link GencodeGtfFeature}s to insert into corresponding {@link GencodeGtfExonFeature} objects in {@code exonStore}
     */
    static void aggregateRecordsIntoGeneFeature(final GencodeGtfGeneFeature gene,
                                                final GencodeGtfTranscriptFeature transcript,
                                                final List< GencodeGtfExonFeature > exonStore,
                                                final List< GencodeGtfFeature > leafFeatureStore ) {

        // OK, we go through the record and consolidate the sub parts of the record.
        // We must consolidate these records through grouping by genomic position.

        // Loop through the Exons and put the correct leaf features into each:
        for ( final GencodeGtfExonFeature exon : exonStore ) {
            for ( final Iterator<GencodeGtfFeature> iterator = leafFeatureStore.iterator(); iterator.hasNext(); ) {

                final GencodeGtfFeature feature = iterator.next();

                // Features that are within the extents of an exon belong in that exon:
                if ( exon.contains(feature) ) {

                    final GencodeGtfFeature.FeatureType featureType = feature.getFeatureType();

                    // Add the feature to the correct place in the exon:
                    switch (featureType) {
                        case CDS:
                            exon.setCds((GencodeGtfCDSFeature) feature);
                            break;
                        case START_CODON:
                            exon.setStartCodon((GencodeGtfStartCodonFeature) feature);
                            break;
                        case STOP_CODON:
                            exon.setStopCodon((GencodeGtfStopCodonFeature) feature);
                            break;
                        case UTR:
                            transcript.addUtr((GencodeGtfUTRFeature) feature);
                            break;
                        case SELENOCYSTEINE:
                            transcript.addSelenocysteine(((GencodeGtfSelenocysteineFeature) feature));
                            break;
                        default:
                            throw new UserException.MalformedFile(
                                    "Found unexpected Feature Type in GENCODE GTF File (line " +
                                            feature.getFeatureOrderNumber() + "): " +
                                            featureType.toString()
                            );
                    }

                    // We have used this iterator item.
                    // We should remove it now so we don't keep going through the list each exon.
                    iterator.remove();
                }
            }

            // Now insert this exon into the transcript:
            transcript.addExon(exon);
        }

        // Add in the transcript:
        gene.addTranscript(transcript);

        // Clear the input data:
        exonStore.clear();
        leafFeatureStore.clear();
    }

    //==================================================================================================================
    // Instance Methods:

    /**
     * Split the given line in a GTF file into fields.
     * Throws a {@link UserException} if the file is not valid.
     * @param line {@link String} containing one line of a GTF file to split.
     * @return A {@link String[]} with each entry containing a field from the GTF line.
     */
    String[] splitGtfLine(final String line) {
        // Split the line into different GTF Fields
        // Note that we're using -1 as the limit so that empty tokens will still be counted
        // (as opposed to discarded).
        final String[] splitLine = line.split(FIELD_DELIMITER, -1);

        // Ensure the file is at least trivially well-formed:
        if (splitLine.length != NUM_COLUMNS) {
            throw new UserException.MalformedFile("Found an invalid number of columns in the given GTF file on line "
                    + getCurrentLineNumber() + " - Given: " + splitLine.length + " Expected: " + NUM_COLUMNS + " : " + line);
        }
        return splitLine;
    }

    /**
     * Read in lines from the given {@link LineIterator} and put them in the header file.
     * Will read until the lines no longer start with comments.
     * @param reader {@link LineIterator} a reader pointing at the top of a GTF file.
     */
    void ingestHeaderLines(final LineIterator reader) {
        int numHeaderLinesRead = 0;
        while ( reader.hasNext() ) {
            final String line = reader.peek();

            // The file will start with commented out lines.
            // Grab them until there are no more commented out lines.
            if ( line.startsWith(getLineComment()) ) {

                // Sanity check for if a file has
                // WAY too many commented out lines at the top:
                if (numHeaderLinesRead > HEADER_NUM_LINES) {
                    throw new UserException.MalformedFile(
                            "File header is longer than expected: " + numHeaderLinesRead + " > " + HEADER_NUM_LINES
                    );
                }

                getHeader().add(line);
                reader.next();
                ++numHeaderLinesRead;
            }
            else {
                break;
            }
        }
    }

    /**
     * Checks that the given header line number starts with the given text.
     * @param header A {@link List<String>} containing a header to validate.
     * @param lineNum Line number in the header to check.
     * @param startingText {@link String} containing text that the line should start with
     * @return {@code true} IFF the header line number {@code lineNum} starts with {@code startingText}; {@code false} otherwise.
     */
    boolean checkHeaderLineStartsWith(final List<String> header, final int lineNum, final String startingText) {
        return checkHeaderLineStartsWith(header, lineNum, startingText, false);
    }

    /**
     * Checks that the given header line number starts with the given text.
     * @param header A {@link List<String>} containing a header to validate.
     * @param lineNum Line number in the header to check.
     * @param startingText {@link String} containing text that the line should start with
     * @param throwIfInvalid If {@code true} will throw a {@link UserException} instead of returning false.
     * @return {@code true} IFF the header line number {@code lineNum} starts with {@code startingText}; {@code false} otherwise.
     */
    boolean checkHeaderLineStartsWith(final List<String> header, final int lineNum, final String startingText, final boolean throwIfInvalid ) {
        if ( !header.get(lineNum).startsWith(getLineComment() + startingText) ) {
            if ( throwIfInvalid ) {
                throw new UserException.MalformedFile(
                        getGtfFileType() + " GTF Header line " + (lineNum+1) + " does not contain expected information (" +
                                getLineComment() + startingText + "): " + header.get(lineNum));
            }
            else {
                return false;
            }
        }
        return true;
    }

    /** @return The current line number for this AbstractGtfCodec. */
    abstract int getCurrentLineNumber();

    /** @return The header AbstractGtfCodec. */
    abstract List<String> getHeader();

    /**
     * @return The {@link String} a line beings with to indicate that line is commented out.
     */
    abstract String getLineComment();

    /**
     * @return The type of GTF file in this {@link AbstractGtfCodec}.
     */
    abstract String getGtfFileType();

    /**
     * @param inputFilePath A {@link String} containing the path to a potential GTF file.
     * @return {@code true} IFF the given {@code inputFilePath} is a valid name for this {@link AbstractGtfCodec}.
     */
    abstract boolean passesFileNameCheck(final String inputFilePath);

    /**
     * Check if the given header of a tentative GTF file is, in fact, the header to such a file.
     * @param header Header lines to check for conformity to GTF specifications.
     * @return true if the given {@code header} is that of a GTF file; false otherwise.
     */
    @VisibleForTesting
    boolean validateHeader(final List<String> header) {
        return validateHeader(header, false);
    }

    /**
     * Check if the given header of a tentative GTF file is, in fact, the header to such a file.
     * @param header Header lines to check for conformity to GTF specifications.
     * @param throwIfInvalid If true, will throw a {@link UserException.MalformedFile} if the header is invalid.
     * @return true if the given {@code header} is that of a GTF file; false otherwise.
     */
    @VisibleForTesting
    abstract boolean validateHeader(final List<String> header, final boolean throwIfInvalid);

    /**
     * Read the {@code header} from the given {@link LineIterator} for the GTF File.
     * Will also validate this {@code header} for correctness before returning it.
     * Throws a {@link UserException.MalformedFile} if the header is malformed.
     *
     * This must be called before {@link #decode(Object)}
     *
     * @param reader The {@link LineIterator} from which to read the header.
     * @return The header as read from the {@code reader}
     */
    abstract List<String> readActualHeader(final LineIterator reader);

    //==================================================================================================================
    // Helper Data Types:

}
