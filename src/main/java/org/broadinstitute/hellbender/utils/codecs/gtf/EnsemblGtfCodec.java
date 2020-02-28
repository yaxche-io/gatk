package org.broadinstitute.hellbender.utils.codecs.gtf;

import com.google.common.annotations.VisibleForTesting;
import htsjdk.samtools.util.IOUtil;
import htsjdk.tribble.readers.LineIterator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.broadinstitute.hellbender.exceptions.UserException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final public class EnsemblGtfCodec extends AbstractGtfCodec<GencodeGtfFeature> {

    static final Logger logger = LogManager.getLogger(EnsemblGtfCodec.class);

    //==================================================================================================================
    // Public Static Members:

    //==================================================================================================================
    // Private Static Members:

    //==================================================================================================================
    // Private Members:

    final List<String> header         = new ArrayList<>();
    int                currentLineNum = 1;

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
        return null;
    }

    //==================================================================================================================
    // Static Methods:

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
