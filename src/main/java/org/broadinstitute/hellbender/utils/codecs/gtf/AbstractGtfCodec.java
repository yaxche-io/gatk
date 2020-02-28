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

    //==================================================================================================================
    // Instance Methods:

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
