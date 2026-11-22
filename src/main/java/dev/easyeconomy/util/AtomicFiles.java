package dev.easyeconomy.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

// Write-then-rename with fsync at each step, so a crash cannot leave a
// half-written file or lose the rename itself. Plain java, no dependencies.
public final class AtomicFiles {
	private AtomicFiles() {
	}

	public static void writeAtomic(Path target, String content) throws IOException {
		Files.createDirectories(target.getParent());
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		try (FileChannel ch = FileChannel.open(tmp,
				StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
			ch.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
			ch.force(true);
		}
		try {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (java.nio.file.AtomicMoveNotSupportedException e) {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		}
		// fsync the directory so the rename itself survives a crash; some
		// filesystems refuse directory fsync, that is not fatal
		try (FileChannel dir = FileChannel.open(target.getParent(), StandardOpenOption.READ)) {
			dir.force(true);
		} catch (IOException ignored) {
		}
	}
}
