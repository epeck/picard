package picard.sam;

import htsjdk.samtools.BAMRecordCodec;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMFileReader;
import htsjdk.samtools.SAMFileWriter;
import htsjdk.samtools.SAMFileWriterFactory;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMRecordQueryNameComparator;
import htsjdk.samtools.SAMUtils;
import htsjdk.samtools.SamPairUtil;
import htsjdk.samtools.util.CloserUtil;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.Log;
import htsjdk.samtools.util.ProgressLogger;
import htsjdk.samtools.util.SortingCollection;
import picard.cmdline.CommandLineProgram;
import picard.cmdline.CommandLineProgramProperties;
import picard.cmdline.Option;
import picard.cmdline.StandardOptionDefinitions;
import picard.cmdline.programgroups.SamOrBam;

import java.io.File;
import java.util.Iterator;

/**
 * This tool reverts the original base qualities (if specified) and adds the mate cigar tag to mapped BAMs.
 * If the file does not have OQs and already has mate cigar tags, nothing is done.
 * New BAM/BAI/MD5 files are created.
 * @author Nils Homer
 */
@CommandLineProgramProperties(
        usage = "Reverts the original base qualities and adds the mate cigar tag to read-group BAMs.",
        usageShort = "Reverts the original base qualities and adds the mate cigar tag to read-group BAMs",
        programGroup = SamOrBam.class
)
public class RevertOriginalBaseQualitiesAndAddMateCigar extends CommandLineProgram {

    @Option(shortName= StandardOptionDefinitions.INPUT_SHORT_NAME, doc="The input SAM/BAM file to revert the state of.")
    public File INPUT;

    @Option(shortName=StandardOptionDefinitions.OUTPUT_SHORT_NAME, doc="The output SAM/BAM file to create.")
    public File OUTPUT;

     @Option(shortName="SO", doc="The sort order to create the reverted output file with."
            + "By default, the sort order will be the same as the input.", optional = true)
    public SAMFileHeader.SortOrder SORT_ORDER = null;

    @Option(shortName=StandardOptionDefinitions.USE_ORIGINAL_QUALITIES_SHORT_NAME, doc="True to restore original" +
            " qualities from the OQ field to the QUAL field if available.")
    public boolean RESTORE_ORIGINAL_QUALITIES = true;

    @Option(doc="The maximum number of records to examine to determine if we can exit early and not output, given that"
            + " there are a no original base qualities (if we are to restore) and mate cigars exist."
            + " Set to 0 to never skip the file.")
    public int MAX_RECORDS_TO_EXAMINE = 10000;

    private final static Log log = Log.getInstance(RevertOriginalBaseQualitiesAndAddMateCigar.class);

    public RevertOriginalBaseQualitiesAndAddMateCigar() {
        this.CREATE_INDEX = true;
        this.CREATE_MD5_FILE = true;
    }

    /** Default main method impl. */
    public static void main(final String[] args) {
        new RevertOriginalBaseQualitiesAndAddMateCigar().instanceMainWithExit(args);
    }

    public int doWork() {
        IOUtil.assertFileIsReadable(INPUT);
        IOUtil.assertFileIsWritable(OUTPUT);

        boolean foundPairedMappedReads = false;

        // Check if we can skip this file since it does not have OQ tags and the mate cigar tag is already there.
        final CanSkipSamFile skipSamFile = RevertOriginalBaseQualitiesAndAddMateCigar.canSkipSAMFile(INPUT, MAX_RECORDS_TO_EXAMINE, RESTORE_ORIGINAL_QUALITIES);
        log.info(skipSamFile.getMessage(MAX_RECORDS_TO_EXAMINE));
        if (skipSamFile.canSkip()) return 0;

        final SAMFileReader in = new SAMFileReader(INPUT, true);
        final SAMFileHeader inHeader = in.getFileHeader();

        // Build the output writer based on the correct sort order
        final SAMFileHeader outHeader = inHeader.clone();
        if (null == SORT_ORDER) this.SORT_ORDER = inHeader.getSortOrder(); // same as the input
        outHeader.setSortOrder(SORT_ORDER);
        SAMFileWriterFactory.setDefaultCreateIndexWhileWriting(CREATE_INDEX);
        SAMFileWriterFactory.setDefaultCreateMd5File(CREATE_MD5_FILE);
        final SAMFileWriter out = new SAMFileWriterFactory().makeSAMOrBAMWriter(outHeader, false, OUTPUT);

        // Iterate over the records, revert original base qualities, and push them into a SortingCollection by queryname
        final SortingCollection<SAMRecord> sorter = SortingCollection.newInstance(SAMRecord.class, new BAMRecordCodec(outHeader),
                new SAMRecordQueryNameComparator(), MAX_RECORDS_IN_RAM);
        final ProgressLogger revertingProgress = new ProgressLogger(log, 1000000, " reverted OQs");
        int numOriginalQualitiesRestored = 0;
        for (final SAMRecord record : in) {
            // Clean up reads that map off the end of the reference
            AbstractAlignmentMerger.createNewCigarsIfMapsOffEndOfReference(record);

            if (RESTORE_ORIGINAL_QUALITIES && null != record.getOriginalBaseQualities()) {
                // revert the original base qualities
                record.setBaseQualities(record.getOriginalBaseQualities());
                record.setOriginalBaseQualities(null);
                numOriginalQualitiesRestored++;
            }
            if (!foundPairedMappedReads && record.getReadPairedFlag() && !record.getReadUnmappedFlag()) foundPairedMappedReads = true;
            revertingProgress.record(record);
            sorter.add(record);
        }
        CloserUtil.close(in);
        log.info("Reverted the original base qualities for " + numOriginalQualitiesRestored + " records");

        /**
         * Iterator through sorting collection output
         * 1. Set mate cigar string and mate information
         * 2. push record into SAMFileWriter to the output
         */
        final SamPairUtil.SetMateInfoIterator sorterIterator = new SamPairUtil.SetMateInfoIterator(sorter.iterator(), true);
        final ProgressLogger sorterProgress = new ProgressLogger(log, 1000000, " mate cigars added");
        while (sorterIterator.hasNext()) {
            final SAMRecord record = sorterIterator.next();
            out.addAlignment(record);
            sorterProgress.record(record);
        }
        sorterIterator.close();
        CloserUtil.close(out);
        log.info("Updated " + sorterIterator.getNumMateCigarsAdded() + " records with mate cigar");
        if (!foundPairedMappedReads) log.info("Did not find any paired mapped reads.");

        return 0;
    }

    /**
     * Used as a return for the canSkipSAMFile function.
     */
    public enum CanSkipSamFile {
        CAN_SKIP("Can skip the BAM file", true),
        CANNOT_SKIP_FOUND_OQ("Cannot skip the BAM as we found a record with an OQ", false),
        CANNOT_SKIP_FOUND_NO_MC("Cannot skip the BAM as we found a mate with no mate cigar tag", false),
        FOUND_NO_EVIDENCE("Found no evidence of OQ or mate with no mate cigar in the first %d records.  Will continue...", false);
        final private String format;
        final private boolean skip;

        private CanSkipSamFile(final String format, final boolean skip) {
            this.format = format;
            this.skip = skip;
        }

        public String getMessage(final int maxRecordsToExamine) { return String.format(this.format, maxRecordsToExamine); }
        public boolean canSkip() { return this.skip; }
    }

    /**
     * Checks if we can skip the SAM/BAM file when reverting origin base qualities and adding mate cigars.
     * @param inputFile the SAM/BAM input file
     * @param maxRecordsToExamine the maximum number of records to examine before quitting
     * @param revertOriginalBaseQualities true if we are to revert original base qualities, false otherwise
     * @return whether we can skip or not, and the explanation why.
     */
    public static CanSkipSamFile canSkipSAMFile(final File inputFile, final int maxRecordsToExamine, final boolean revertOriginalBaseQualities)  {
        final SAMFileReader in = new SAMFileReader(inputFile, true);
        final Iterator<SAMRecord> iterator = in.iterator();
        int numRecordsExamined = 0;
        CanSkipSamFile returnType = CanSkipSamFile.FOUND_NO_EVIDENCE;

        while (iterator.hasNext() && numRecordsExamined < maxRecordsToExamine) {
            final SAMRecord record = iterator.next();

            if (revertOriginalBaseQualities && null != record.getOriginalBaseQualities()) {
                // has OQ, break and return case #2
                returnType = CanSkipSamFile.CANNOT_SKIP_FOUND_OQ;
                break;
            }

            // check if mate pair and its mate is mapped
            if (record.getReadPairedFlag() && !record.getMateUnmappedFlag()) {
                if (null == SAMUtils.getMateCigar(record)) {
                    // has no MC, break and return case #2
                    returnType = CanSkipSamFile.CANNOT_SKIP_FOUND_NO_MC;
                    break;
                }
                else {
                    // has MC, previously checked that it does not have OQ, break and return case #1
                    returnType = CanSkipSamFile.CAN_SKIP;
                    break;
                }
            }

            numRecordsExamined++;
        }

        // no more records anyhow, so we can skip
        if (!iterator.hasNext() && CanSkipSamFile.FOUND_NO_EVIDENCE == returnType) {
            returnType = CanSkipSamFile.CAN_SKIP;
        }

        in.close();

        return returnType;
    }
}
