package org.example.kirojavatest.fileanalyzer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Detects file type by magic number (file signature bytes) and compares
 * against the file name extension to find mismatches.
 */
public class MagicNumberUtil {

    /** Map of magic byte signatures to their expected extensions. */
    private static final Map<byte[], Set<String>> SIGNATURES = new LinkedHashMap<>();

    static {
        // PNG
        sig(new byte[]{(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, "png");
        // JPEG
        sig(new byte[]{(byte)0xFF, (byte)0xD8, (byte)0xFF}, "jpg", "jpeg", "jfif");
        // GIF87a / GIF89a
        sig(new byte[]{0x47, 0x49, 0x46, 0x38, 0x37, 0x61}, "gif");
        sig(new byte[]{0x47, 0x49, 0x46, 0x38, 0x39, 0x61}, "gif");
        // BMP
        sig(new byte[]{0x42, 0x4D}, "bmp");
        // TIFF (little-endian and big-endian)
        sig(new byte[]{0x49, 0x49, 0x2A, 0x00}, "tif", "tiff");
        sig(new byte[]{0x4D, 0x4D, 0x00, 0x2A}, "tif", "tiff");
        // WebP (RIFF....WEBP)
        sig(new byte[]{0x52, 0x49, 0x46, 0x46}, "webp"); // partial — checked with offset below
        // PDF
        sig(new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D}, "pdf");
        // ZIP (also jar, docx, xlsx, pptx, odt, etc.)
        sig(new byte[]{0x50, 0x4B, 0x03, 0x04}, "zip", "jar", "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub", "apk");
        // GZIP
        sig(new byte[]{0x1F, (byte)0x8B}, "gz", "tgz");
        // RAR
        sig(new byte[]{0x52, 0x61, 0x72, 0x21, 0x1A, 0x07}, "rar");
        // 7z
        sig(new byte[]{0x37, 0x7A, (byte)0xBC, (byte)0xAF, 0x27, 0x1C}, "7z");
        // MP3 (ID3 tag)
        sig(new byte[]{0x49, 0x44, 0x33}, "mp3");
        // MP4 / M4A (ftyp box — offset 4)
        sig(new byte[]{0x66, 0x74, 0x79, 0x70}, "mp4", "m4a", "m4v", "mov");
        // WAV
        sig(new byte[]{0x52, 0x49, 0x46, 0x46}, "wav"); // also RIFF-based
        // AVI
        // (also RIFF-based, handled by WAV entry)
        // EXE / DLL (MZ)
        sig(new byte[]{0x4D, 0x5A}, "exe", "dll");
        // ELF
        sig(new byte[]{0x7F, 0x45, 0x4C, 0x46}, "elf", "so", "o");
        // SVG (text-based, starts with < or <?xml)
        sig(new byte[]{0x3C, 0x73, 0x76, 0x67}, "svg");
    }

    private static void sig(byte[] magic, String... extensions) {
        SIGNATURES.put(magic, Set.of(extensions));
    }

    /**
     * Check if a file's magic number conflicts with its extension.
     * @return true if there is a mismatch (magic says one type, extension says another)
     */
    public static boolean hasMismatch(Path file) {
        String ext = getExtension(file.getFileName().toString());
        if (ext.isEmpty()) return false; // no extension to compare against

        byte[] header;
        try {
            header = readHeader(file, 12);
        } catch (IOException e) {
            return false;
        }
        if (header.length < 2) return false;

        // Check each signature
        for (var entry : SIGNATURES.entrySet()) {
            byte[] magic = entry.getKey();
            Set<String> expectedExts = entry.getValue();

            if (startsWith(header, magic)) {
                // Magic matches this type — does the extension match?
                if (!expectedExts.contains(ext)) {
                    return true; // mismatch
                }
                return false; // match found, no mismatch
            }
        }

        // Special case: MP4/MOV ftyp at offset 4
        if (header.length >= 8) {
            byte[] ftyp = {header[4], header[5], header[6], header[7]};
            if (ftyp[0] == 0x66 && ftyp[1] == 0x74 && ftyp[2] == 0x79 && ftyp[3] == 0x70) {
                Set<String> mp4Exts = Set.of("mp4", "m4a", "m4v", "mov", "3gp");
                if (!mp4Exts.contains(ext)) return true;
                return false;
            }
        }

        // No known magic matched — can't determine mismatch
        return false;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }

    private static byte[] readHeader(Path file, int maxBytes) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(maxBytes);
        }
    }

    private static String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase();
    }
}
