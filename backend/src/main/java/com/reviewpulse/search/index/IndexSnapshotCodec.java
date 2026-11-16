package com.reviewpulse.search.index;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32C;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

import com.reviewpulse.search.postings.PostingList;
import com.reviewpulse.search.tokenizer.Tokenizer;

/**
 * Binary snapshot format (version 1) for a COMPACTED index:
 * <pre>
 *   magic "RPIX" | format int | header | aggregates | products | docs | terms+postings | versions | CRC32C long
 * </pre>
 * Doc term lists and posting doc ids are delta-encoded varints; frequencies are varints. The trailing
 * CRC32C covers every preceding byte, so truncation and bit flips are detected on load.
 * The header records which Kafka offsets the snapshot reflects, the topic id, the tokenizer
 * fingerprint and the last bulk import run, which is how recovery decides whether it can be trusted.
 */
public final class IndexSnapshotCodec {

    static final int MAGIC = 0x52504958; // "RPIX"
    static final int FORMAT_VERSION = 1;
    private static final int BUFFER = 1 << 16;

    private IndexSnapshotCodec() {
    }

    /**
     * Metadata stored with a snapshot.
     *
     * @param offsets     per partition, the next Kafka offset NOT reflected in the snapshot
     * @param importRunId id of the latest completed bulk import the data includes (0 = none)
     */
    public record Header(String tokenizerFingerprint, String topicId, long importRunId, long createdAtMillis,
                         long generation, Map<Integer, Long> offsets) {
        public Header {
            offsets = Map.copyOf(offsets);
        }
    }

    /** A decoded snapshot. */
    public record Loaded(Header header, InvertedIndex index) {
    }

    /** Thrown when bytes are not a valid snapshot of this format. */
    public static class CorruptSnapshotException extends IOException {
        public CorruptSnapshotException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ write

    /** Writes {@code index} (must be compacted: every doc slot live) with {@code header}. */
    public static void write(InvertedIndex index, Header header, OutputStream target) throws IOException {
        if (index.docSlots() != index.liveDocCount()) {
            throw new IllegalArgumentException("only compacted indexes can be snapshotted");
        }
        CheckedOutputStream checked = new CheckedOutputStream(new BufferedOutputStream(target, BUFFER), new CRC32C());
        DataOutputStream out = new DataOutputStream(checked);
        out.writeInt(MAGIC);
        out.writeInt(FORMAT_VERSION);
        writeHeader(out, header);
        out.writeInt(index.liveDocCount());
        out.writeLong(index.liveTitleLengthSum());
        out.writeLong(index.liveBodyLengthSum());
        writeProducts(out, index);
        writeDocs(out, index.docTable());
        writeTerms(out, index);
        writeVersions(out, index.versionMap());
        out.flush();
        long crc = checked.getChecksum().getValue();
        out.writeLong(crc);
        out.flush();
    }

    private static void writeHeader(DataOutputStream out, Header h) throws IOException {
        out.writeUTF(h.tokenizerFingerprint());
        out.writeUTF(h.topicId());
        out.writeLong(h.importRunId());
        out.writeLong(h.createdAtMillis());
        out.writeLong(h.generation());
        Map<Integer, Long> sorted = new TreeMap<>(h.offsets());
        out.writeInt(sorted.size());
        for (Map.Entry<Integer, Long> e : sorted.entrySet()) {
            out.writeInt(e.getKey());
            out.writeLong(e.getValue());
        }
    }

    private static void writeProducts(DataOutputStream out, InvertedIndex index) throws IOException {
        List<String> products = index.productList();
        out.writeInt(products.size());
        for (String p : products) {
            out.writeUTF(p);
        }
    }

    private static void writeDocs(DataOutputStream out, DocTable docs) throws IOException {
        out.writeInt(docs.size());
        for (int d = 0; d < docs.size(); d++) {
            out.writeLong(docs.reviewId(d));
            out.writeLong(docs.version(d));
            writeVarInt(out, docs.titleLength(d));
            writeVarInt(out, docs.bodyLength(d));
            out.writeByte(docs.rating(d));
            writeVarInt(out, docs.helpfulVotes(d));
            out.writeBoolean(docs.verified(d));
            writeVarInt(out, docs.productId(d));
            out.writeLong(docs.reviewedAt(d));
            out.writeLong(docs.contentHashHi(d));
            out.writeLong(docs.contentHashLo(d));
            int[] terms = docs.termIds(d);
            writeVarInt(out, terms.length);
            int prev = 0;
            for (int t : terms) {
                writeVarInt(out, t - prev);
                prev = t;
            }
        }
    }

    private static void writeTerms(DataOutputStream out, InvertedIndex index) throws IOException {
        List<String> terms = index.termList();
        int[] df = index.documentFrequencies();
        out.writeInt(terms.size());
        for (int t = 0; t < terms.size(); t++) {
            out.writeUTF(terms.get(t));
            writeVarInt(out, df[t]);
            writePostings(out, index.postingLists().get(t), true);
        }
        for (PostingList p : index.productPostingLists()) {
            writePostings(out, p, false);
        }
    }

    private static void writePostings(DataOutputStream out, PostingList list, boolean withFreqs) throws IOException {
        writeVarInt(out, list.size());
        int prev = 0;
        for (int i = 0; i < list.size(); i++) {
            writeVarInt(out, list.docAt(i) - prev);
            prev = list.docAt(i);
            if (withFreqs) {
                writeVarInt(out, list.packedFreqAt(i));
            }
        }
    }

    private static void writeVersions(DataOutputStream out, Map<Long, Long> versions) throws IOException {
        out.writeInt(versions.size());
        for (Map.Entry<Long, Long> e : new TreeMap<>(versions).entrySet()) {
            out.writeLong(e.getKey());
            out.writeLong(e.getValue());
        }
    }

    // ------------------------------------------------------------------ read

    public static Loaded read(InputStream source, Tokenizer tokenizer) throws IOException {
        CheckedInputStream checked = new CheckedInputStream(new BufferedInputStream(source, BUFFER), new CRC32C());
        DataInputStream in = new DataInputStream(checked);
        try {
            if (in.readInt() != MAGIC) {
                throw new CorruptSnapshotException("not a ReviewPulse index snapshot");
            }
            int format = in.readInt();
            if (format != FORMAT_VERSION) {
                throw new CorruptSnapshotException("unsupported snapshot format " + format);
            }
            Header header = readHeader(in);
            InvertedIndex index = new InvertedIndex(tokenizer);
            int live = in.readInt();
            long titleSum = in.readLong();
            long bodySum = in.readLong();
            readProducts(in, index);
            readDocs(in, index);
            readTerms(in, index);
            readVersions(in, index);
            index.restoreAggregates(live, titleSum, bodySum);
            long expected = checked.getChecksum().getValue();
            long stored = in.readLong();
            if (expected != stored) {
                throw new CorruptSnapshotException("checksum mismatch");
            }
            if (index.docSlots() != live) {
                throw new CorruptSnapshotException("document count mismatch");
            }
            return new Loaded(header, index);
        } catch (EOFException e) {
            throw new CorruptSnapshotException("snapshot is truncated");
        } catch (IllegalArgumentException | IndexOutOfBoundsException | NegativeArraySizeException e) {
            throw new CorruptSnapshotException("snapshot structure is invalid: " + e.getMessage());
        }
    }

    private static Header readHeader(DataInputStream in) throws IOException {
        String fingerprint = in.readUTF();
        String topicId = in.readUTF();
        long importRun = in.readLong();
        long createdAt = in.readLong();
        long generation = in.readLong();
        int partitions = boundedCount(in.readInt(), 100_000);
        Map<Integer, Long> offsets = new TreeMap<>();
        for (int i = 0; i < partitions; i++) {
            offsets.put(in.readInt(), in.readLong());
        }
        return new Header(fingerprint, topicId, importRun, createdAt, generation, offsets);
    }

    private static void readProducts(DataInputStream in, InvertedIndex index) throws IOException {
        int n = boundedCount(in.readInt(), Integer.MAX_VALUE);
        for (int i = 0; i < n; i++) {
            if (index.productIdOrCreate(in.readUTF()) != i) {
                throw new CorruptSnapshotException("duplicate product");
            }
        }
    }

    private static void readDocs(DataInputStream in, InvertedIndex index) throws IOException {
        int n = boundedCount(in.readInt(), Integer.MAX_VALUE);
        DocTable docs = index.docTable();
        for (int d = 0; d < n; d++) {
            long reviewId = in.readLong();
            long version = in.readLong();
            int titleLength = readVarInt(in);
            int bodyLength = readVarInt(in);
            int rating = in.readByte();
            int helpful = readVarInt(in);
            boolean verified = in.readBoolean();
            int productId = readVarInt(in);
            long reviewedAt = in.readLong();
            long hi = in.readLong();
            long lo = in.readLong();
            int[] terms = new int[boundedCount(readVarInt(in), 1_000_000)];
            int prev = 0;
            for (int i = 0; i < terms.length; i++) {
                prev += readVarInt(in);
                terms[i] = prev;
            }
            docs.appendRaw(reviewId, version, titleLength, bodyLength, rating, helpful, verified, productId,
                    reviewedAt, hi, lo, terms);
            index.liveDocMap().put(reviewId, d);
        }
    }

    private static void readTerms(DataInputStream in, InvertedIndex index) throws IOException {
        int n = boundedCount(in.readInt(), Integer.MAX_VALUE);
        int[] df = new int[n];
        for (int t = 0; t < n; t++) {
            if (index.termIdOrCreate(in.readUTF()) != t) {
                throw new CorruptSnapshotException("duplicate term");
            }
            df[t] = readVarInt(in);
            readPostings(in, index.postingLists().get(t), true);
        }
        int[] target = index.documentFrequencies();
        System.arraycopy(df, 0, target, 0, n);
        for (PostingList p : index.productPostingLists()) {
            readPostings(in, p, false);
        }
    }

    private static void readPostings(DataInputStream in, PostingList list, boolean withFreqs) throws IOException {
        int n = boundedCount(readVarInt(in), Integer.MAX_VALUE);
        int doc = 0;
        for (int i = 0; i < n; i++) {
            doc += readVarInt(in);
            list.appendPacked(doc, withFreqs ? readVarInt(in) : 0);
        }
        list.trimToSize();
    }

    private static void readVersions(DataInputStream in, InvertedIndex index) throws IOException {
        int n = boundedCount(in.readInt(), Integer.MAX_VALUE);
        Map<Long, Long> versions = index.versionMap();
        for (int i = 0; i < n; i++) {
            versions.put(in.readLong(), in.readLong());
        }
    }

    // ------------------------------------------------------------------ varints

    static void writeVarInt(DataOutputStream out, int value) throws IOException {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    static int readVarInt(DataInputStream in) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new CorruptSnapshotException("malformed varint");
    }

    private static int boundedCount(int n, int max) throws CorruptSnapshotException {
        if (n < 0 || n > max) {
            throw new CorruptSnapshotException("invalid count " + n);
        }
        return n;
    }
}
