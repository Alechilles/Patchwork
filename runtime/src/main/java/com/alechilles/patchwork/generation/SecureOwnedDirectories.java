package com.alechilles.patchwork.generation;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.util.Set;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;

/** Opens an owned directory from its filesystem root through retained no-follow descriptors. */
final class SecureOwnedDirectories {
    private SecureOwnedDirectories() { }

    /** Executes only when the provider offers descriptor-relative directory traversal; otherwise returns false. */
    static boolean verifyNoFollow(Path directory) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        Path root = absolute.getRoot();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            if (!(stream instanceof SecureDirectoryStream<Path> secureRoot)) return false;
            SecureDirectoryStream<Path> current = secureRoot;
            try {
                for (Path segment : root.relativize(absolute)) {
                    SecureDirectoryStream<Path> next = current.newDirectoryStream(segment, LinkOption.NOFOLLOW_LINKS);
                    if (current != secureRoot) current.close();
                    current = next;
                }
                return true;
            } finally { if (current != secureRoot) current.close(); }
        }
    }

    /** Writes a final child through an anchored parent descriptor; false means the provider lacks SDS. */
    static boolean writeOwnedFile(Path root, String relative, byte[] bytes) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        Path fsRoot = absolute.getRoot();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(fsRoot)) {
            if (!(stream instanceof SecureDirectoryStream<Path> secureRoot)) return false;
            SecureDirectoryStream<Path> current = open(secureRoot, fsRoot.relativize(absolute));
            try {
                String[] parts = relative.split("/");
                for (int index = 0; index < parts.length - 1; index++) {
                    Path child = root.getFileSystem().getPath(parts[index]);
                    if (!Files.exists(root.resolve(String.join("/", java.util.Arrays.copyOf(parts, index + 1))), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Secure parent directory was not created.");
                    SecureDirectoryStream<Path> next = current.newDirectoryStream(child, LinkOption.NOFOLLOW_LINKS);
                    current.close(); current = next;
                }
                try (SeekableByteChannel channel = current.newByteChannel(root.getFileSystem().getPath(parts[parts.length - 1]), Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); if (channel instanceof FileChannel file) file.force(true);
                }
                return true;
            } finally { current.close(); }
        }
    }

    private static SecureDirectoryStream<Path> open(SecureDirectoryStream<Path> root, Path relative) throws IOException {
        SecureDirectoryStream<Path> current = root;
        for (Path part : relative) { SecureDirectoryStream<Path> next = current.newDirectoryStream(part, LinkOption.NOFOLLOW_LINKS); if (current != root) current.close(); current = next; }
        return current;
    }

    /** Deletes a tree solely through retained parent descriptors; false means no SDS provider was available. */
    static boolean deleteOwnedTree(Path root) throws IOException { return deleteOwnedTree(root, () -> { }); }
    /** Testable descriptor acquisition boundary used to prove mutable-path swaps cannot redirect deletion. */
    static boolean deleteOwnedTree(Path root, AfterOpenHook hook) throws IOException {
        Path absolute = root.toAbsolutePath().normalize(); Path fsRoot = absolute.getRoot(); Path parentPath = absolute.getParent();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(fsRoot)) {
            if (!(stream instanceof SecureDirectoryStream<Path> secureRoot)) return false;
            SecureDirectoryStream<Path> parent = open(secureRoot, fsRoot.relativize(parentPath));
            try {
                Path name = root.getFileSystem().getPath(absolute.getFileName().toString());
                try (SecureDirectoryStream<Path> child = parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) { hook.afterOpen(); deleteChildren(child); }
                parent.deleteDirectory(name);
                return true;
            } finally { parent.close(); }
        }
    }

    private static void deleteChildren(SecureDirectoryStream<Path> directory) throws IOException {
        for (Path entry : directory) {
            Path name = entry.getFileName();
            BasicFileAttributes attributes = directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
            if (attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Refusing unsafe secure cleanup child.");
            if (attributes.isDirectory()) { try (SecureDirectoryStream<Path> child = directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) { deleteChildren(child); } directory.deleteDirectory(name); }
            else directory.deleteFile(name);
        }
    }
    /** Moves an owned directory through stable parent descriptors; false means no secure provider is available. */
    static boolean moveOwnedDirectory(Path from, Path to) throws IOException { return moveOwnedDirectory(from, to, () -> { }); }
    static boolean moveOwnedDirectory(Path from, Path to, AfterOpenHook hook) throws IOException {
        Path source = from.toAbsolutePath().normalize(), destination = to.toAbsolutePath().normalize(); Path fsRoot = source.getRoot();
        if (!fsRoot.equals(destination.getRoot())) throw new IOException("Secure move cannot cross filesystem roots.");
        try (DirectoryStream<Path> rootStream = Files.newDirectoryStream(fsRoot)) {
            if (!(rootStream instanceof SecureDirectoryStream<Path> secureRoot)) return false;
            SecureDirectoryStream<Path> sourceParent = open(secureRoot, fsRoot.relativize(source.getParent()));
            SecureDirectoryStream<Path> targetParent = open(secureRoot, fsRoot.relativize(destination.getParent()));
            try {
                Path sourceName = source.getFileSystem().getPath(source.getFileName().toString());
                Path targetName = destination.getFileSystem().getPath(destination.getFileName().toString());
                BasicFileAttributes attributes = sourceParent.getFileAttributeView(sourceName, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
                if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("Unsafe secure move source.");
                hook.afterOpen();
                try { sourceParent.move(sourceName, targetParent, targetName); }
                catch (RuntimeException race) { throw new IOException("Secure move source changed after acquisition.", race); }
                return true;
            } finally { sourceParent.close(); targetParent.close(); }
        }
    }
    @FunctionalInterface interface AfterOpenHook { void afterOpen() throws IOException; }
}
