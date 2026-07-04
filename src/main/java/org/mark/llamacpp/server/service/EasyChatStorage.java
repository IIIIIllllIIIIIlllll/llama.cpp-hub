package org.mark.llamacpp.server.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.io.BoundedRandomAccessFileInputStream;
import org.mark.llamacpp.server.tools.JsonUtil;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

final class EasyChatStorage {

	static final String INDEX_FILE = "index.bin";
	static final String TOOLS_FILE = "tools.bin";
	static final String MODEL_INDEX_FILE = "model_index.json";

	private static final int IDX_HEADER = 16;
	private static final int IDX_CONV_NAME_LEN = 4;
	private static final int IDX_CONV_NAME = 4096;
	private static final int IDX_START_TIME = 8;
	private static final int IDX_ASSISTANT_NAME_LEN = 4;
	private static final int IDX_ASSISTANT_NAME = 4096;
	private static final int IDX_SEQ = 8;
	private static final int INDEX_FILE_SIZE = IDX_HEADER + IDX_CONV_NAME_LEN + IDX_CONV_NAME
		+ IDX_START_TIME + IDX_ASSISTANT_NAME_LEN + IDX_ASSISTANT_NAME + IDX_SEQ;

	static final int FRAG_HEADER_SIZE = 160;
	static final int FRAG_PAYLOAD_OFFSET = FRAG_HEADER_SIZE;
	static final int FRAG_COUNT_OFFSET = 32;
	static final int FRAG_ACTIVE_VARIANT_OFFSET = 34;
	static final int FRAG_LENGTHS_OFFSET = 36;
	static final int FRAG_MAX_VARIANTS = 10;
	static final int FRAG_LENGTH_ENTRY = 4;
	static final int FRAG_LENGTHS = FRAG_MAX_VARIANTS * FRAG_LENGTH_ENTRY;
	static final int FRAG_FLAGS_OFFSET = FRAG_LENGTHS_OFFSET + FRAG_LENGTHS;
	static final int FRAG_FLAGS_DELETED = 1;

	private static final String FRAG_NAME_FMT = "%016d.bin";
	private static final int COPY_BUFFER_SIZE = 8192;

	Path getFragmentsDir() throws IOException {
		Path dir = LlamaServer.getCachePath().resolve("easy-chat").resolve("fragments");
		if (!Files.exists(dir)) {
			Files.createDirectories(dir);
		}
		return dir;
	}

	Path getConversationDir(String conversationId) throws IOException {
		return getConversationDir(getFragmentsDir(), conversationId);
	}

	Path getConversationDir(Path fragmentsBase, String conversationId) throws IOException {
		Path dir = fragmentsBase.resolve(conversationId);
		if (!Files.exists(dir)) {
			Files.createDirectories(dir);
		}
		return dir;
	}

	Path fragmentFile(Path dir, long seq) {
		return dir.resolve(String.format(FRAG_NAME_FMT, seq));
	}

	Path indexFile(Path convDir) {
		return convDir.resolve(INDEX_FILE);
	}

	void ensureIndex(Path convDir, String assistantName) throws IOException {
		Path indexPath = indexFile(convDir);
		if (!Files.exists(indexPath)) {
			Files.createDirectories(convDir);
			createIndex(indexPath, "", System.currentTimeMillis(), assistantName == null ? "" : assistantName);
			return;
		}
		if (Files.size(indexPath) != INDEX_FILE_SIZE) {
			Files.delete(indexPath);
			createIndex(indexPath, "", System.currentTimeMillis(), assistantName == null ? "" : assistantName);
		}
	}

	private void createIndex(Path indexPath, String convName, long startTime, String assistantName) throws IOException {
		try (RandomAccessFile raf = new RandomAccessFile(indexPath.toFile(), "rw")) {
			FileChannel ch = raf.getChannel();
			ByteBuffer buf = ByteBuffer.allocate(INDEX_FILE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < IDX_HEADER; i++) {
				buf.put((byte) 0xFF);
			}
			byte[] convNameBytes = convName.getBytes(StandardCharsets.UTF_8);
			buf.putInt(Math.min(convNameBytes.length, IDX_CONV_NAME));
			buf.put(convNameBytes, 0, Math.min(convNameBytes.length, IDX_CONV_NAME));
			buf.position(IDX_HEADER + IDX_CONV_NAME_LEN + IDX_CONV_NAME);
			buf.putLong(startTime);
			byte[] assistantNameBytes = assistantName.getBytes(StandardCharsets.UTF_8);
			buf.putInt(Math.min(assistantNameBytes.length, IDX_ASSISTANT_NAME));
			buf.put(assistantNameBytes, 0, Math.min(assistantNameBytes.length, IDX_ASSISTANT_NAME));
			buf.position(INDEX_FILE_SIZE - IDX_SEQ);
			buf.putLong(0L);
			buf.flip();
			ch.write(buf);
		}
	}

	long readIndexSeq(Path indexPath) throws IOException {
		try (RandomAccessFile raf = new RandomAccessFile(indexPath.toFile(), "r")) {
			raf.seek(INDEX_FILE_SIZE - IDX_SEQ);
			ByteBuffer buf = ByteBuffer.allocate(IDX_SEQ).order(ByteOrder.LITTLE_ENDIAN);
			raf.getChannel().read(buf);
			buf.flip();
			return buf.getLong();
		}
	}

	long readNextSeq(Path convDir) throws IOException {
		Path indexPath = indexFile(convDir);
		if (!Files.isRegularFile(indexPath)) {
			return 0L;
		}
		return Math.max(0L, readIndexSeq(indexPath));
	}

	void writeIndexSeq(Path indexPath, long seq) throws IOException {
		try (RandomAccessFile raf = new RandomAccessFile(indexPath.toFile(), "rw")) {
			raf.seek(INDEX_FILE_SIZE - IDX_SEQ);
			ByteBuffer buf = ByteBuffer.allocate(IDX_SEQ).order(ByteOrder.LITTLE_ENDIAN);
			buf.putLong(seq);
			buf.flip();
			raf.getChannel().write(buf);
		}
	}

	void writeFragment(Path dir, long seq, long timestamp, byte[] payload) throws IOException {
		Path target = fragmentFile(dir, seq);
		Path temp = target.resolveSibling(target.getFileName().toString() + ".tmp");
		try (RandomAccessFile raf = new RandomAccessFile(temp.toFile(), "rw")) {
			FileChannel ch = raf.getChannel();
			ByteBuffer header = buildHeader(timestamp, seq, 1, 0, 0, buildLengthsWithFirst(payload.length));
			ch.write(header);
			ch.write(ByteBuffer.wrap(payload));
		}
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * Write fragment from a source file (streaming path).
	 * Reads source file size, builds header, copies file content — avoids loading into memory.
	 */
	void writeFragment(Path dir, long seq, long timestamp, Path sourceFile) throws IOException {
		long payloadLength = Files.size(sourceFile);
		Path target = fragmentFile(dir, seq);
		Path temp = target.resolveSibling(target.getFileName().toString() + ".tmp");
		try (RandomAccessFile raf = new RandomAccessFile(temp.toFile(), "rw");
			 RandomAccessFile sourceRaf = new RandomAccessFile(sourceFile.toFile(), "r")) {
			FileChannel ch = raf.getChannel();
			ByteBuffer header = buildHeader(timestamp, seq, 1, 0, 0, buildLengthsWithFirst((int) payloadLength));
			ch.write(header);
			sourceRaf.getChannel().transferTo(0, payloadLength, ch);
		}
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
	}

	void appendVariant(Path dir, long seq, byte[] payload) throws IOException {
		Path file = fragmentFile(dir, seq);
		FragmentHeader header = requireHeader(file, seq);
		if (header.variantCount >= FRAG_MAX_VARIANTS) {
			throw new IOException("Fragment seq=" + seq + " already has " + header.variantCount
				+ " variants (max " + FRAG_MAX_VARIANTS + ")");
		}
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
			writeShort(raf, FRAG_COUNT_OFFSET, header.variantCount + 1);
			writeInt(raf, FRAG_LENGTHS_OFFSET + header.variantCount * FRAG_LENGTH_ENTRY, payload.length);
			writeShort(raf, FRAG_ACTIVE_VARIANT_OFFSET, header.variantCount);
			raf.seek(payloadEndOffset(header));
			raf.write(payload);
		}
	}

	void updateVariant(Path dir, long seq, int variantIndex, byte[] newPayload) throws IOException {
		Path file = fragmentFile(dir, seq);
		FragmentHeader header = requireHeader(file, seq);
		if (variantIndex < 0 || variantIndex >= header.variantCount) {
			throw new IOException("Variant index " + variantIndex + " out of range for seq=" + seq
				+ " (count=" + header.variantCount + ")");
		}
		byte[][] payloads = readAllVariantPayloads(file, header);
		payloads[variantIndex] = newPayload;
		header.lengths[variantIndex] = newPayload.length;
		rewriteFragmentFile(file, header, payloads, header.variantCount, header.activeVariantIndex, header.flags);
	}

	void deleteMessage(Path dir, long seq) throws IOException {
		Path file = fragmentFile(dir, seq);
		FragmentHeader header = requireHeader(file, seq);
		writeFlags(file, header.flags | FRAG_FLAGS_DELETED);
	}

	void clearDeletedFlag(Path dir, long seq) throws IOException {
		Path file = fragmentFile(dir, seq);
		FragmentHeader header = requireHeader(file, seq);
		writeFlags(file, header.flags & ~FRAG_FLAGS_DELETED);
	}

	int deleteVariant(Path dir, long seq, int variantIndex) throws IOException {
		Path file = fragmentFile(dir, seq);
		FragmentHeader header = requireHeader(file, seq);
		if (variantIndex < 0 || variantIndex >= header.variantCount) {
			throw new IOException("Variant index " + variantIndex + " out of range for seq=" + seq
				+ " (count=" + header.variantCount + ")");
		}
		byte[][] payloads = readAllVariantPayloads(file, header);
		int newCount = header.variantCount - 1;
		if (newCount <= 0) {
			writeFlags(file, header.flags | FRAG_FLAGS_DELETED);
			return -1;
		}
		byte[][] nextPayloads = new byte[newCount][];
		int[] nextLengths = new int[FRAG_MAX_VARIANTS];
		int target = 0;
		for (int i = 0; i < header.variantCount; i++) {
			if (i == variantIndex) {
				continue;
			}
			nextPayloads[target] = payloads[i];
			nextLengths[target] = payloads[i].length;
			target++;
		}
		int nextActive = header.activeVariantIndex;
		if (nextActive == variantIndex) {
			nextActive = 0;
		} else if (nextActive > variantIndex) {
			nextActive--;
		}
		header.lengths = nextLengths;
		rewriteFragmentFile(file, header, nextPayloads, newCount, nextActive, header.flags & ~FRAG_FLAGS_DELETED);
		return nextActive;
	}

	byte[] readPayload(Path dir, long seq, int variantIndex) throws IOException {
		Path file = fragmentFile(dir, seq);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		FragmentHeader header = readFragmentHeader(file);
		if (header == null) {
			return null;
		}
		int resolvedIndex = resolveVariantIndex(header, variantIndex);
		if (resolvedIndex < 0) {
			return new byte[0];
		}
		int length = header.lengths[resolvedIndex];
		byte[] payload = new byte[length];
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
			raf.seek(payloadOffset(header, resolvedIndex));
			raf.readFully(payload);
		}
		return payload;
	}

	FragmentHeader readFragmentHeader(Path dir, long seq) throws IOException {
		Path file = fragmentFile(dir, seq);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		return readFragmentHeader(file);
	}

	FragmentSlice getVariantSlice(Path dir, long seq, int variantIndex) throws IOException {
		Path file = fragmentFile(dir, seq);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		FragmentHeader header = readFragmentHeader(file);
		if (header == null) {
			return null;
		}
		int resolvedIndex = resolveVariantIndex(header, variantIndex);
		if (resolvedIndex < 0) {
			return null;
		}
		return new FragmentSlice(file, payloadOffset(header, resolvedIndex), header.lengths[resolvedIndex]);
	}

	boolean streamVariant(Path dir, long seq, int variantIndex, OutputStream output) throws IOException {
		FragmentSlice slice = getVariantSlice(dir, seq, variantIndex);
		if (slice == null || slice.length <= 0) {
			return false;
		}
		streamSlice(slice, output);
		return true;
	}

	/**
	 * Stream a fragment slice's payload bytes directly to {@code output} using an
	 * 8KB buffer. Never materializes the payload in memory.
	 */
	void streamSlice(FragmentSlice slice, OutputStream output) throws IOException {
		if (slice == null || slice.length <= 0) {
			return;
		}
		byte[] buffer = new byte[COPY_BUFFER_SIZE];
		try (RandomAccessFile raf = new RandomAccessFile(slice.file.toFile(), "r")) {
			raf.seek(slice.offset);
			int remaining = slice.length;
			while (remaining > 0) {
				int chunk = Math.min(buffer.length, remaining);
				int read = raf.read(buffer, 0, chunk);
				if (read < 0) {
					break;
				}
				output.write(buffer, 0, read);
				remaining -= read;
			}
		}
	}

	/**
	 * Open a bounded {@link InputStream} over a single variant's payload bytes.
	 * Caller is responsible for closing the stream. Returns {@code null} if the
	 * fragment/variant does not exist or is empty.
	 */
	InputStream openVariantInputStream(Path dir, long seq, int variantIndex) throws IOException {
		FragmentSlice slice = getVariantSlice(dir, seq, variantIndex);
		if (slice == null || slice.length <= 0) {
			return null;
		}
		return new BoundedRandomAccessFileInputStream(slice.file, slice.offset, slice.length);
	}

	void writeActiveVariantIndex(Path dir, long seq, int idx) throws IOException {
		Path file = fragmentFile(dir, seq);
		if (!Files.isRegularFile(file)) {
			return;
		}
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
			writeShort(raf, FRAG_ACTIVE_VARIANT_OFFSET, idx);
		}
	}

	byte[] readTools(Path dir) throws IOException {
		Path file = dir.resolve(TOOLS_FILE);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		return Files.readAllBytes(file);
	}

	void writeTools(Path dir, byte[] data) throws IOException {
		Path target = dir.resolve(TOOLS_FILE);
		Path temp = target.resolveSibling(target.getFileName().toString() + ".tmp");
		Files.write(temp, data);
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
	}

	/* ---- Model index (per-variant modelId tracking) ---- */

	/**
	 * Read model_index.json. Returns map of seq -> (variantIndex -> modelId).
	 * Backward compatible with old format {"seq":"modelId"} which is treated as variant 0.
	 */
	Map<Long, Map<Integer, String>> readModelIndex(Path convDir) {
		Path file = convDir.resolve(MODEL_INDEX_FILE);
		if (!Files.isRegularFile(file)) {
			return new HashMap<>();
		}
		try {
			byte[] bytes = Files.readAllBytes(file);
			if (bytes == null || bytes.length == 0) {
				return new HashMap<>();
			}
			JsonObject obj = JsonUtil.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
			if (obj == null) {
				return new HashMap<>();
			}
			Map<Long, Map<Integer, String>> result = new HashMap<>();
			for (String seqKey : obj.keySet()) {
				long seq;
				try {
					seq = Long.parseLong(seqKey);
				} catch (NumberFormatException ignore) {
					continue;
				}
				JsonElement seqEl = obj.get(seqKey);
				Map<Integer, String> variants = new HashMap<>();
				if (seqEl != null && seqEl.isJsonObject()) {
					JsonObject variantObj = seqEl.getAsJsonObject();
					for (String variantKey : variantObj.keySet()) {
						try {
							int variantIndex = Integer.parseInt(variantKey);
							JsonElement modelEl = variantObj.get(variantKey);
							if (modelEl != null && !modelEl.isJsonNull() && modelEl.isJsonPrimitive()) {
								variants.put(variantIndex, modelEl.getAsString());
							}
						} catch (NumberFormatException ignore) {
							// skip invalid variant keys
						}
					}
				} else if (seqEl != null && !seqEl.isJsonNull() && seqEl.isJsonPrimitive()) {
					// old format: seq -> modelId, treat as variant 0
					try {
						variants.put(0, seqEl.getAsString());
					} catch (Exception ignore) {
					}
				}
				if (!variants.isEmpty()) {
					result.put(seq, variants);
				}
			}
			return result;
		} catch (Exception e) {
			// treat corrupted model_index as empty
			return new HashMap<>();
		}
	}

	private void writeModelIndex(Path convDir, Map<Long, Map<Integer, String>> index) throws IOException {
		Path file = convDir.resolve(MODEL_INDEX_FILE);
		JsonObject obj = new JsonObject();
		List<Long> sortedSeqs = new ArrayList<>(index.keySet());
		Collections.sort(sortedSeqs);
		for (long seq : sortedSeqs) {
			Map<Integer, String> variants = index.get(seq);
			if (variants == null || variants.isEmpty()) {
				continue;
			}
			JsonObject variantObj = new JsonObject();
			List<Integer> sortedVariants = new ArrayList<>(variants.keySet());
			Collections.sort(sortedVariants);
			for (int variantIndex : sortedVariants) {
				String modelId = variants.get(variantIndex);
				if (modelId != null && !modelId.isBlank()) {
					variantObj.addProperty(String.valueOf(variantIndex), modelId);
				}
			}
			if (variantObj.size() > 0) {
				obj.add(String.valueOf(seq), variantObj);
			}
		}
		byte[] bytes = JsonUtil.toJson(obj).getBytes(StandardCharsets.UTF_8);
		Path temp = file.resolveSibling(file.getFileName().toString() + ".tmp");
		Files.write(temp, bytes);
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
	}

	void setModelForVariant(Path convDir, long seq, int variantIndex, String modelId) throws IOException {
		Map<Long, Map<Integer, String>> index = readModelIndex(convDir);
		Map<Integer, String> variants = index.computeIfAbsent(seq, k -> new HashMap<>());
		if (modelId == null || modelId.isBlank()) {
			variants.remove(variantIndex);
		} else {
			variants.put(variantIndex, modelId);
		}
		if (variants.isEmpty()) {
			index.remove(seq);
		}
		if (index.isEmpty()) {
			Path file = convDir.resolve(MODEL_INDEX_FILE);
			Files.deleteIfExists(file);
		} else {
			writeModelIndex(convDir, index);
		}
	}

	String getModelForVariant(Path convDir, long seq, int variantIndex) {
		Map<Integer, String> variants = readModelIndex(convDir).get(seq);
		if (variants == null) {
			return null;
		}
		return variants.get(variantIndex);
	}

	Map<Integer, String> getModelsForSeq(Path convDir, long seq) {
		Map<Integer, String> variants = readModelIndex(convDir).get(seq);
		if (variants == null) {
			return new HashMap<>();
		}
		return new HashMap<>(variants);
	}

	private FragmentHeader readFragmentHeader(Path file) throws IOException {
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
			byte[] headerBytes = new byte[FRAG_HEADER_SIZE];
			raf.readFully(headerBytes);
			ByteBuffer buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN);
			buf.position(16);
			long timestamp = buf.getLong();
			long seq = buf.getLong();
			int count = buf.getShort() & 0xFFFF;
			int activeVariant = buf.getShort() & 0xFFFF;
			int[] lengths = new int[FRAG_MAX_VARIANTS];
			for (int i = 0; i < FRAG_MAX_VARIANTS; i++) {
				lengths[i] = buf.getInt();
			}
			int flags = 0;
			if (headerBytes.length >= FRAG_FLAGS_OFFSET + 4) {
				buf.position(FRAG_FLAGS_OFFSET);
				flags = buf.getInt();
			}
			return new FragmentHeader(timestamp, seq, count, activeVariant, flags, lengths);
		}
	}

	private FragmentHeader requireHeader(Path file, long seq) throws IOException {
		if (!Files.isRegularFile(file)) {
			throw new IOException("Fragment file not found seq=" + seq);
		}
		FragmentHeader header = readFragmentHeader(file);
		if (header == null) {
			throw new IOException("Failed to read fragment header seq=" + seq);
		}
		return header;
	}

	private byte[][] readAllVariantPayloads(Path file, FragmentHeader header) throws IOException {
		byte[][] payloads = new byte[header.variantCount][];
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
			for (int i = 0; i < header.variantCount; i++) {
				int len = header.lengths[i];
				if (len <= 0) {
					payloads[i] = new byte[0];
					continue;
				}
				payloads[i] = new byte[len];
				raf.seek(payloadOffset(header, i));
				raf.readFully(payloads[i]);
			}
		}
		return payloads;
	}

	private void rewriteFragmentFile(Path file, FragmentHeader header, byte[][] payloads,
		int count, int activeVariantIndex, int flags) throws IOException {
		Path temp = file.resolveSibling(file.getFileName().toString() + ".tmp");
		try (RandomAccessFile raf = new RandomAccessFile(temp.toFile(), "rw")) {
			FileChannel ch = raf.getChannel();
			ByteBuffer out = buildHeader(header.timestamp, header.seq, count, activeVariantIndex, flags, header.lengths);
			ch.write(out);
			for (int i = 0; i < count; i++) {
				byte[] payload = payloads[i];
				if (payload != null && payload.length > 0) {
					ch.write(ByteBuffer.wrap(payload));
				}
			}
		}
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
	}

	private ByteBuffer buildHeader(long timestamp, long seq, int count, int activeVariantIndex,
		int flags, int[] lengths) {
		ByteBuffer buf = ByteBuffer.allocate(FRAG_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < 16; i++) {
			buf.put((byte) 0xFF);
		}
		buf.putLong(timestamp);
		buf.putLong(seq);
		buf.putShort((short) count);
		buf.putShort((short) activeVariantIndex);
		for (int i = 0; i < FRAG_MAX_VARIANTS; i++) {
			buf.putInt(i < lengths.length ? lengths[i] : 0);
		}
		buf.position(FRAG_FLAGS_OFFSET);
		buf.putInt(flags);
		buf.position(FRAG_HEADER_SIZE);
		buf.flip();
		return buf;
	}

	private int[] buildLengthsWithFirst(int length) {
		int[] lengths = new int[FRAG_MAX_VARIANTS];
		lengths[0] = length;
		return lengths;
	}

	private long payloadOffset(FragmentHeader header, int variantIndex) {
		long offset = FRAG_PAYLOAD_OFFSET;
		for (int i = 0; i < variantIndex; i++) {
			offset += Math.max(0, header.lengths[i]);
		}
		return offset;
	}

	private long payloadEndOffset(FragmentHeader header) {
		long offset = FRAG_PAYLOAD_OFFSET;
		for (int i = 0; i < header.variantCount; i++) {
			offset += Math.max(0, header.lengths[i]);
		}
		return offset;
	}

	int resolveVariantIndex(FragmentHeader header, Integer preferredVariantIndex) {
		if (header == null || header.variantCount <= 0) {
			return -1;
		}
		if (preferredVariantIndex != null && preferredVariantIndex >= 0
			&& preferredVariantIndex < header.variantCount
			&& header.lengths[preferredVariantIndex] > 0) {
			return preferredVariantIndex;
		}
		if (header.activeVariantIndex >= 0 && header.activeVariantIndex < header.variantCount
			&& header.lengths[header.activeVariantIndex] > 0) {
			return header.activeVariantIndex;
		}
		for (int i = 0; i < header.variantCount; i++) {
			if (header.lengths[i] > 0) {
				return i;
			}
		}
		return -1;
	}

	boolean isDeleted(FragmentHeader header) {
		if (header == null) {
			return true;
		}
		return header.isDeleted() || header.variantCount <= 0;
	}

	boolean isDeleted(Path dir, long seq, FragmentHeader header) {
		return isDeleted(header);
	}

	private void writeFlags(Path file, int flags) throws IOException {
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
			writeInt(raf, FRAG_FLAGS_OFFSET, flags);
		}
	}

	private void writeShort(RandomAccessFile raf, long offset, int value) throws IOException {
		raf.seek(offset);
		ByteBuffer buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
		buf.putShort((short) value);
		buf.flip();
		raf.getChannel().write(buf);
	}

	private void writeInt(RandomAccessFile raf, long offset, int value) throws IOException {
		raf.seek(offset);
		ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		buf.putInt(value);
		buf.flip();
		raf.getChannel().write(buf);
	}

	static final class FragmentHeader {
		final long timestamp;
		final long seq;
		final int variantCount;
		final int activeVariantIndex;
		final int flags;
		int[] lengths;

		FragmentHeader(long timestamp, long seq, int variantCount, int activeVariantIndex, int flags, int[] lengths) {
			this.timestamp = timestamp;
			this.seq = seq;
			this.variantCount = variantCount;
			this.activeVariantIndex = activeVariantIndex;
			this.flags = flags;
			this.lengths = lengths;
		}

		boolean isDeleted() {
			return (flags & FRAG_FLAGS_DELETED) != 0;
		}
	}

	static final class FragmentSlice {
		final Path file;
		final long offset;
		final int length;

		FragmentSlice(Path file, long offset, int length) {
			this.file = file;
			this.offset = offset;
			this.length = length;
		}
	}
}
