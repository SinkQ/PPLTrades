package ru.sink_q.ppltrades;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Local JSON in the user's trusted profile directory. Only the leaf is protected against links;
 * callers must not use an untrusted parent directory. No file-system exception details escape.
 */
public final class PrivateJsonFiles {
    public static final int CONFIG_MAX_BYTES = 1024 * 1024;
    public static final int QUEUE_MAX_BYTES = 4 * 1024 * 1024;
    public static final int TOKEN_MAX_BYTES = 64 * 1024;
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    @FunctionalInterface
    public interface JsonWriter {
        void write(Writer writer) throws IOException;
    }

    private PrivateJsonFiles() {
    }

    /** Read at most limit + 1 actual bytes, before a JSON parser is involved; null means absent. */
    public static String readIfExists(Path path, int limit) throws IOException {
        checkLimit(limit);
        try {
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException absent) {
                return null;
            }
            if (!attributes.isRegularFile()) {
                throw new IOException();
            }
            // Do not change ACLs on Windows/non-POSIX stores: inherit the profile's ACL there.
            tightenPermissions(path);
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                if (channel.size() > limit) {
                    throw new IOException();
                }
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(limit, 8192));
                ByteBuffer buffer = ByteBuffer.allocate(Math.min(limit + 1, 8192));
                int total = 0;
                while (true) {
                    buffer.clear();
                    buffer.limit(Math.min(buffer.capacity(), limit - total + 1));
                    int count = channel.read(buffer);
                    if (count == -1) {
                        // Do not "repair" malformed UTF-8 and then overwrite a corrupt config.
                        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
                    }
                    total += count;
                    if (total > limit) {
                        throw new IOException();
                    }
                    bytes.write(buffer.array(), 0, count);
                }
            }
        } catch (IOException | RuntimeException failure) {
            throw new IOException("Local JSON could not be read");
        }
    }

    /**
     * Publish a complete, bounded same-directory temporary file. There is no in-place truncation
     * or copy fallback: failed serialization leaves the previous file alone. New POSIX files are 0600
     * from creation; on other stores the temporary file inherits its parent's profile ACL.
     */
    public static void write(Path path, int limit, JsonWriter json) throws IOException {
        checkLimit(limit);
        Path temporary = null;
        try {
            Path target = path.toAbsolutePath();
            Path parent = target.getParent();
            Files.createDirectories(parent);
            checkWritableLeaf(target);
            boolean posix = Files.getFileStore(parent).supportsFileAttributeView(PosixFileAttributeView.class);
            temporary = posix
                    ? Files.createTempFile(parent, ".ppltrades-", ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY))
                    : Files.createTempFile(parent, ".ppltrades-", ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 Writer writer = new BufferedWriter(new OutputStreamWriter(
                         new LimitedOutputStream(Channels.newOutputStream(channel), limit), StandardCharsets.UTF_8))) {
                json.write(writer);
                writer.flush();
                channel.force(true);
            }
            checkWritableLeaf(target);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException unsupported) {
                // Same-directory rename only; never open/truncate a possibly substituted leaf.
                checkWritableLeaf(target);
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException | RuntimeException failure) {
            throw new IOException("Local JSON could not be saved");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | RuntimeException ignored) {
                    // Do not replace a safe error with a path-bearing cleanup exception.
                }
            }
        }
    }

    /** Remove the directory entry itself (never its symlink target). */
    public static void delete(Path path) throws IOException {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException failure) {
            throw new IOException("Local JSON could not be removed");
        }
    }

    private static void checkWritableLeaf(Path path) throws IOException {
        try {
            if (!Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
                throw new IOException();
            }
        } catch (NoSuchFileException absent) {
            // The publication will create this leaf.
        }
    }

    private static void tightenPermissions(Path path) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view != null) {
            view.setPermissions(OWNER_ONLY);
        }
    }

    private static void checkLimit(int limit) {
        if (limit < 1 || limit == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid local JSON byte limit");
        }
    }

    private static final class LimitedOutputStream extends FilterOutputStream {
        private final int limit;
        private int written;

        private LimitedOutputStream(OutputStream output, int limit) {
            super(output);
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            reserve(1);
            out.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            reserve(length);
            out.write(bytes, offset, length);
        }

        private void reserve(int length) throws IOException {
            if (length > limit - written) {
                throw new IOException("Local JSON byte limit exceeded");
            }
            written += length;
        }
    }
}
