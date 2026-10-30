import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Build-only lossless archive compaction; keeps entry paths, license text and PNG metadata. */
public final class CompactReleaseJar {
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final LocalDateTime ARCHIVE_TIME = LocalDateTime.of(1980, 2, 1, 0, 0);
    private static final int MAX_IMAGE_BYTES = 64 * 1024 * 1024;

    private CompactReleaseJar() {
    }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Expected archive and report paths");
        }
        Path archive = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path report = Path.of(arguments[1]).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(archive) || Files.isSymbolicLink(report)) {
            throw new IOException("Refusing linked build outputs");
        }
        long before = Files.size(archive);
        Path staged = Files.createTempFile(archive.getParent(), "compact-release-", ".jar");
        List<String> changes = new ArrayList<>();
        int entries = 0;
        long jsonSaved = 0;
        long pngSaved = 0;
        try (ZipFile original = new ZipFile(archive.toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(staged))) {
            output.setLevel(Deflater.BEST_COMPRESSION);
            for (ZipEntry entry : original.stream().sorted((a, b) -> a.getName().compareTo(b.getName())).toList()) {
                ZipEntry target = new ZipEntry(entry.getName());
                target.setTimeLocal(ARCHIVE_TIME);
                if (entry.isDirectory()) {
                    target.setMethod(ZipEntry.STORED);
                    target.setSize(0);
                    target.setCompressedSize(0);
                    target.setCrc(0);
                    output.putNextEntry(target);
                    output.closeEntry();
                    continue;
                }
                byte[] data = original.getInputStream(entry).readAllBytes();
                byte[] compact = data;
                if (entry.getName().endsWith(".json") || entry.getName().endsWith(".mcmeta")) {
                    compact = minifyJson(data);
                    jsonSaved += data.length - compact.length;
                } else if (entry.getName().endsWith(".png")) {
                    compact = compactPng(data);
                    pngSaved += data.length - compact.length;
                }
                if (compact.length != data.length) {
                    changes.add((data.length - compact.length) + "\t" + entry.getName());
                }
                int compressedSize = deflate(compact, true).length;
                CRC32 checksum = new CRC32();
                checksum.update(compact);
                target.setSize(compact.length);
                target.setCrc(checksum.getValue());
                if (compressedSize >= compact.length) {
                    target.setMethod(ZipEntry.STORED);
                    target.setCompressedSize(compact.length);
                } else {
                    target.setCompressedSize(compressedSize);
                }
                output.putNextEntry(target);
                output.write(compact);
                output.closeEntry();
                entries++;
            }
        }
        long after = Files.size(staged);
        if (after > before) {
            throw new IOException("Compacted archive grew; original retained at " + archive);
        }
        try (ZipFile result = new ZipFile(staged.toFile())) {
            if (result.stream().filter(entry -> !entry.isDirectory()).count() != entries) {
                throw new IOException("Compaction lost archive entries");
            }
            for (ZipEntry entry : result.stream().filter(value -> !value.isDirectory()).toList()) {
                result.getInputStream(entry).transferTo(java.io.OutputStream.nullOutputStream());
            }
        }
        Files.move(staged, archive, StandardCopyOption.REPLACE_EXISTING);
        Files.createDirectories(report.getParent());
        Files.writeString(report, "before=" + before + "\nafter=" + after + "\nentries=" + entries
                + "\njson_uncompressed_saved=" + jsonSaved + "\npng_saved=" + pngSaved
                + "\nchanges:\n" + String.join("\n", changes) + "\n", StandardCharsets.UTF_8);
        System.out.println("Lossless release compaction: " + before + " -> " + after + " bytes");
    }

    private static byte[] minifyJson(byte[] source) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream(source.length);
        boolean quoted = false;
        boolean escaped = false;
        for (byte value : source) {
            if (quoted) {
                result.write(value);
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    quoted = false;
                }
            } else if (value == '"') {
                quoted = true;
                result.write(value);
            } else if (value != ' ' && value != '\n' && value != '\r' && value != '\t') {
                result.write(value);
            }
        }
        if (quoted) {
            throw new IOException("Unterminated JSON string in maintained resource");
        }
        return result.toByteArray();
    }

    private static byte[] compactPng(byte[] source) throws IOException {
        if (!Arrays.equals(Arrays.copyOf(source, 8), PNG_SIGNATURE)) {
            throw new IOException("Invalid PNG signature");
        }
        ByteBuffer bytes = ByteBuffer.wrap(source).order(ByteOrder.BIG_ENDIAN);
        bytes.position(8);
        List<Chunk> chunks = new ArrayList<>();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        int width = 0;
        int height = 0;
        int pixelBytes = 0;
        while (bytes.remaining() >= 12) {
            int offset = bytes.position();
            int length = bytes.getInt();
            if (length < 0 || length > bytes.remaining() - 8) {
                throw new IOException("Invalid PNG chunk length");
            }
            byte[] type = new byte[4];
            bytes.get(type);
            String name = new String(type, StandardCharsets.US_ASCII);
            byte[] payload = new byte[length];
            bytes.get(payload);
            bytes.getInt();
            chunks.add(new Chunk(name, offset, length + 12));
            if (name.equals("IHDR")) {
                ByteBuffer header = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
                width = header.getInt();
                height = header.getInt();
                int depth = header.get() & 255;
                int colorType = header.get() & 255;
                int compression = header.get() & 255;
                int filtering = header.get() & 255;
                int interlace = header.get() & 255;
                if (depth == 8 && compression == 0 && filtering == 0 && interlace == 0) {
                    pixelBytes = switch (colorType) {
                        case 0, 3 -> 1;
                        case 2 -> 3;
                        case 4 -> 2;
                        case 6 -> 4;
                        default -> 0;
                    };
                }
            } else if (name.equals("IDAT")) {
                compressed.write(payload);
            }
        }
        if (pixelBytes == 0 || width <= 0 || height <= 0) {
            return source;
        }
        long expected = ((long) width * pixelBytes + 1) * height;
        if (expected > MAX_IMAGE_BYTES) {
            return source;
        }
        byte[] filtered;
        try (var input = new InflaterInputStream(new ByteArrayInputStream(compressed.toByteArray()))) {
            filtered = input.readNBytes((int) expected + 1);
        }
        if (filtered.length != expected) {
            throw new IOException("Unexpected PNG image data length");
        }
        byte[] refiltered = refilter(filtered, width * pixelBytes, height, pixelBytes);
        byte[] candidate = deflate(refiltered, false);
        byte[] originalFilters = deflate(filtered, false);
        if (originalFilters.length < candidate.length) {
            candidate = originalFilters;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(source.length);
        output.write(PNG_SIGNATURE);
        boolean emitted = false;
        for (Chunk chunk : chunks) {
            if (chunk.name().equals("IDAT")) {
                if (!emitted) {
                    writeChunk(output, "IDAT", candidate);
                    emitted = true;
                }
            } else {
                output.write(source, chunk.offset(), chunk.length());
            }
        }
        byte[] result = output.toByteArray();
        return result.length < source.length ? result : source;
    }

    private static byte[] refilter(byte[] original, int stride, int height, int pixelBytes) throws IOException {
        byte[] result = new byte[original.length];
        byte[] previous = new byte[stride];
        byte[] row = new byte[stride];
        byte[] trial = new byte[stride];
        byte[] best = new byte[stride];
        for (int y = 0; y < height; y++) {
            int offset = y * (stride + 1);
            int filter = original[offset] & 255;
            if (filter > 4) {
                throw new IOException("Invalid PNG row filter");
            }
            for (int x = 0; x < stride; x++) {
                int left = x >= pixelBytes ? row[x - pixelBytes] & 255 : 0;
                int above = previous[x] & 255;
                int upperLeft = x >= pixelBytes ? previous[x - pixelBytes] & 255 : 0;
                row[x] = (byte) ((original[offset + 1 + x] & 255) + predictor(filter, left, above, upperLeft));
            }
            long bestScore = Long.MAX_VALUE;
            int bestFilter = 0;
            for (int candidate = 0; candidate <= 4; candidate++) {
                long score = 0;
                for (int x = 0; x < stride; x++) {
                    int left = x >= pixelBytes ? row[x - pixelBytes] & 255 : 0;
                    int above = previous[x] & 255;
                    int upperLeft = x >= pixelBytes ? previous[x - pixelBytes] & 255 : 0;
                    byte difference = (byte) ((row[x] & 255) - predictor(candidate, left, above, upperLeft));
                    trial[x] = difference;
                    score += Math.abs((int) difference);
                }
                if (score < bestScore) {
                    bestScore = score;
                    bestFilter = candidate;
                    System.arraycopy(trial, 0, best, 0, stride);
                }
            }
            result[offset] = (byte) bestFilter;
            System.arraycopy(best, 0, result, offset + 1, stride);
            byte[] swap = previous;
            previous = row;
            row = swap;
        }
        return result;
    }

    private static int predictor(int filter, int left, int above, int upperLeft) {
        return switch (filter) {
            case 0 -> 0;
            case 1 -> left;
            case 2 -> above;
            case 3 -> (left + above) / 2;
            case 4 -> {
                int p = left + above - upperLeft;
                int a = Math.abs(p - left);
                int b = Math.abs(p - above);
                int c = Math.abs(p - upperLeft);
                if (a <= b && a <= c) {
                    yield left;
                }
                if (b <= c) {
                    yield above;
                }
                yield upperLeft;
            }
            default -> throw new IllegalArgumentException("Unknown filter");
        };
    }

    private static byte[] deflate(byte[] bytes, boolean raw) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Deflater compressor = new Deflater(Deflater.BEST_COMPRESSION, raw);
        try (var stream = new DeflaterOutputStream(output, compressor)) {
            stream.write(bytes);
        } finally {
            compressor.end();
        }
        return output.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream output, String name, byte[] payload) throws IOException {
        byte[] type = name.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(payload);
        output.write(ByteBuffer.allocate(4).putInt(payload.length).array());
        output.write(type);
        output.write(payload);
        output.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private record Chunk(String name, int offset, int length) {
    }
}
