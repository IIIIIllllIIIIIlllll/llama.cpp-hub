package org.mark.llamacpp.gguf;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mark.llamacpp.gguf.MtpHelper.MtpInfo;

/**
 * GGUF元数据读取器 (简化版) 只保留 fileName, filePath, general.architecture, context_length
 */
public class GGUFMetaData {

	/**
	 * 文件名
	 */
	private final String fileName;

	/**
	 * 文件路径
	 */
	private final String filePath;

	/**
	 * 架构
	 */
	private final String architecture;

	/**
	 * 上下文长度
	 */
	private final Integer contextLength;

	private final Integer fileType;

	private final String baseName;

	private final String name;

	private final String sizeLabel;

	private final boolean supportsAudio;

	private final boolean supportsVision;

	private final String chatTemplate;

	private final MtpInfo mtpInfo;

	private final String generalType;

	private GGUFMetaData(String fileName, String filePath, String architecture, Integer contextLength, Integer fileType,
			String baseName, String name, String sizeLabel, boolean supportsAudio, boolean supportsVision,
			String chatTemplate, MtpInfo mtpInfo, String generalType) {
		this.fileName = fileName;
		this.filePath = filePath;
		this.architecture = architecture;
		this.contextLength = contextLength;
		this.fileType = fileType;
		this.baseName = baseName;
		this.name = name;
		this.sizeLabel = sizeLabel;
		this.supportsAudio = supportsAudio;
		this.supportsVision = supportsVision;
		this.chatTemplate = chatTemplate;
		this.mtpInfo = mtpInfo;
		this.generalType = generalType;
	}

	public static GGUFMetaData readFile(File file) {
		if (file == null || !file.exists() || !file.isFile()) {
			return null;
		}

		String architecture = null;
		Integer contextLength = null;
		Integer fileType = null;
		String baseName = null;
		String name = null;
		String sizeLabel = null;
		boolean supportsAudio = false;
		boolean supportsVision = false;
		String chatTemplate = null;
		String generalType = null;
		Integer blockCount = null;
		Integer nextnPredictLayers = null;
		Map<String, Number> archIntValues = new HashMap<>();
		MtpInfo mtpInfo = null;

		byte[] buf = new byte[8];

		try (FileInputStream fis = new FileInputStream(file);
				BufferedInputStream bis = new BufferedInputStream(fis, 1024 * 1024)) {

			byte[] magicBytes = new byte[4];
			readFully(bis, magicBytes);
			String magic = new String(magicBytes, StandardCharsets.US_ASCII);
			if (!"GGUF".equals(magic)) {
				return null;
			}

			readUInt32(bis, buf);
			readUInt64(bis, buf);
			long kvCount = readUInt64(bis, buf);

			for (long i = 0; i < kvCount; i++) {
				long keyLength = readUInt64(bis, buf);
				if (keyLength > 1024 * 1024)
					break;
				String key = readString(bis, (int) keyLength, buf);
				int valueTypeId = readUInt32(bis, buf);

				boolean handled = false;
				if ("general.architecture".equals(key)) {
					architecture = readStringValue(bis, valueTypeId, buf);
					handled = true;
				} else if ("general.basename".equals(key)) {
					baseName = readStringValue(bis, valueTypeId, buf);
					handled = true;
				} else if ("general.name".equals(key)) {
					name = readStringValue(bis, valueTypeId, buf);
					handled = true;
				} else if ("general.size_label".equals(key)) {
					sizeLabel = readStringValue(bis, valueTypeId, buf);
					handled = true;
				} else if ("general.file_type".equals(key)) {
					Number v = readNumberValue(bis, valueTypeId, buf);
					if (v != null)
						fileType = v.intValue();
					handled = true;
				} else if (key.endsWith(".context_length")) {
					Number v = readNumberValue(bis, valueTypeId, buf);
					if (contextLength == null && v != null) {
						contextLength = v.intValue();
					}
					handled = true;
				} else if ("clip.has_audio_encoder".equals(key)) {
					Boolean v = readBoolValue(bis, valueTypeId, buf);
					if (v != null && v)
						supportsAudio = true;
					handled = true;
				} else if ("clip.has_vision_encoder".equals(key)) {
					Boolean v = readBoolValue(bis, valueTypeId, buf);
					if (v != null && v)
						supportsVision = true;
					handled = true;
				} else if ("general.type".equals(key)) {
					generalType = readStringValue(bis, valueTypeId, buf);
					handled = true;
				} else if ("tokenizer.chat_template".equals(key)) {
					chatTemplate = readStringValue(bis, valueTypeId, buf);
					handled = true;
				} else if (architecture != null && key.equals(architecture + ".block_count")) {
					Number v = readNumberValue(bis, valueTypeId, buf);
					if (v != null)
						blockCount = v.intValue();
					handled = true;
				} else if (architecture != null && key.equals(architecture + ".nextn_predict_layers")) {
					Number v = readNumberValue(bis, valueTypeId, buf);
					if (v != null)
						nextnPredictLayers = v.intValue();
					handled = true;
				} else if (key.indexOf('.') > 0
						&& (key.endsWith(".block_count") || key.endsWith(".nextn_predict_layers"))) {
					Number v = readNumberValue(bis, valueTypeId, buf);
					if (v != null)
						archIntValues.put(key, v);
					handled = true;
				}

				if (!handled) {
					skipValue(bis, valueTypeId, buf);
				}
			}

			if (architecture != null && blockCount == null) {
				Number v = archIntValues.get(architecture + ".block_count");
				if (v != null)
					blockCount = v.intValue();
			}
			if (architecture != null && nextnPredictLayers == null) {
				Number v = archIntValues.get(architecture + ".nextn_predict_layers");
				if (v != null)
					nextnPredictLayers = v.intValue();
			}

			mtpInfo = buildMtpInfo(architecture, blockCount, nextnPredictLayers);

		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}

		return new GGUFMetaData(file.getName(), file.getAbsolutePath(), architecture, contextLength, fileType, baseName,
				name, sizeLabel, supportsAudio, supportsVision, chatTemplate, mtpInfo, generalType);
	}

	static void readFully(BufferedInputStream bis, byte[] buf) throws IOException {
		int offset = 0;
		while (offset < buf.length) {
			int n = bis.read(buf, offset, buf.length - offset);
			if (n < 0)
				throw new IOException("Unexpected EOF at offset " + offset);
			offset += n;
		}
	}

	static long readUInt64(BufferedInputStream bis, byte[] buf) throws IOException {
		readFully(bis, buf);
		return ((long) (buf[7] & 0xFF) << 56) | ((long) (buf[6] & 0xFF) << 48) | ((long) (buf[5] & 0xFF) << 40)
				| ((long) (buf[4] & 0xFF) << 32) | ((long) (buf[3] & 0xFF) << 24) | ((long) (buf[2] & 0xFF) << 16)
				| ((long) (buf[1] & 0xFF) << 8) | (buf[0] & 0xFF);
	}

	static int readUInt32(BufferedInputStream bis, byte[] buf) throws IOException {
		readFully(bis, buf, 4);
		return ((buf[3] & 0xFF) << 24) | ((buf[2] & 0xFF) << 16) | ((buf[1] & 0xFF) << 8) | (buf[0] & 0xFF);
	}

	static void readFully(BufferedInputStream bis, byte[] buf, int len) throws IOException {
		int offset = 0;
		while (offset < len) {
			int n = bis.read(buf, offset, len - offset);
			if (n < 0)
				throw new IOException("Unexpected EOF at offset " + offset + " of " + len);
			offset += n;
		}
	}

	static String readString(BufferedInputStream bis, int length, byte[] buf) throws IOException {
		byte[] bytes = new byte[length];
		readFully(bis, bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	static String readStringValue(BufferedInputStream bis, int typeId, byte[] buf) throws IOException {
		if (typeId == 8) {
			long len = readUInt64(bis, buf);
			return readString(bis, (int) len, buf);
		}
		skipValue(bis, typeId, buf);
		return null;
	}

	static Number readNumberValue(BufferedInputStream bis, int typeId, byte[] buf) throws IOException {
		switch (typeId) {
		case 0:
			return readUInt8(bis, buf);
		case 1:
			return readInt8(bis, buf);
		case 2:
			return readUInt16(bis, buf);
		case 3:
			return readInt16(bis, buf);
		case 4:
			return readUInt32(bis, buf);
		case 5: {
			readFully(bis, buf, 4);
			return ((buf[3] & 0xFF) << 24) | ((buf[2] & 0xFF) << 16) | ((buf[1] & 0xFF) << 8) | (buf[0] & 0xFF);
		}
		case 10:
		case 11:
			return readUInt64(bis, buf);
		default:
			skipValue(bis, typeId, buf);
			return null;
		}
	}

	static Boolean readBoolValue(BufferedInputStream bis, int typeId, byte[] buf) throws IOException {
		if (typeId == 7) {
			readFully(bis, buf, 1);
			return buf[0] != 0;
		}
		skipValue(bis, typeId, buf);
		return null;
	}

	static int readUInt8(BufferedInputStream bis, byte[] buf) throws IOException {
		readFully(bis, buf, 1);
		return buf[0] & 0xFF;
	}

	static int readInt8(BufferedInputStream bis, byte[] buf) throws IOException {
		readFully(bis, buf, 1);
		return buf[0];
	}

	static int readUInt16(BufferedInputStream bis, byte[] buf) throws IOException {
		readFully(bis, buf, 2);
		return (buf[1] & 0xFF) << 8 | (buf[0] & 0xFF);
	}

	static int readInt16(BufferedInputStream bis, byte[] buf) throws IOException {
		readFully(bis, buf, 2);
		return (buf[1] << 8) | buf[0];
	}

	static void skipValue(BufferedInputStream bis, int typeId, byte[] buf) throws IOException {
		switch (typeId) {
		case 0:
		case 1:
		case 7:
			bis.skipNBytes(1);
			break;
		case 2:
		case 3:
			bis.skipNBytes(2);
			break;
		case 4:
		case 5:
		case 6:
			bis.skipNBytes(4);
			break;
		case 10:
		case 11:
		case 12:
			bis.skipNBytes(8);
			break;
		case 8: {
			long len = readUInt64(bis, buf);
			bis.skipNBytes(len);
			break;
		}
		case 9: {
			int subType = readUInt32(bis, buf);
			long len = readUInt64(bis, buf);
			for (long j = 0; j < len; j++) {
				skipValue(bis, subType, buf);
			}
			break;
		}
		default:
			break;
		}
	}

	public String getFileName() {
		return fileName;
	}

	public String getFilePath() {
		return filePath;
	}

	public String getArchitecture() {
		return architecture;
	}

	public Integer getContextLength() {
		return contextLength;
	}

	public Integer getFileType() {
		return fileType;
	}

	public String getBaseName() {
		return baseName;
	}

	public String getName() {
		return name;
	}

	public String getSizeLabel() {
		return sizeLabel;
	}

	public boolean isSupportsAudio() {
		return supportsAudio;
	}

	public boolean isSupportsVision() {
		return supportsVision;
	}

	public MtpInfo getMtpInfo() {
		return mtpInfo;
	}

	public String getChatTemplate() {
		return chatTemplate;
	}

	public String getQuantizationType() {
		if (fileType == null)
			return null;
		return fileTypeToQuantizationName(fileType.intValue());
	}

	/**
	 * 兼容旧代码的 Getter
	 */
	public String getStringValue(String key) {
		if ("general.architecture".equals(key)) {
			return architecture;
		}
		if ("general.basename".equals(key)) {
			return baseName;
		}
		if ("general.name".equals(key)) {
			return name;
		}
		if ("general.size_label".equals(key)) {
			return sizeLabel;
		}
		if ("general.type".equals(key)) {
			return generalType;
		}
		return null;
	}

	public String getGeneralType() {
		return generalType;
	}

	/**
	 * 兼容旧代码的 Getter
	 */
	public Integer getIntValue(String key) {
		if (key != null && key.endsWith(".context_length")) {
			return contextLength;
		}
		if ("general.file_type".equals(key)) {
			return fileType;
		}
		return null;
	}

	private static String fileTypeToQuantizationName(int fileType) {
		return switch (fileType) {
		case 0 -> "F32";
		case 1 -> "F16";
		case 2 -> "Q4_0";
		case 3 -> "Q4_1";
		case 7 -> "Q8_0";
		case 8 -> "Q5_0";
		case 9 -> "Q5_1";
		case 10 -> "Q2_K";
		case 11 -> "Q3_K_S";
		case 12 -> "Q3_K_M";
		case 13 -> "Q3_K_L";
		case 14 -> "Q4_K_S";
		case 15 -> "Q4_K_M";
		case 16 -> "Q5_K_S";
		case 17 -> "Q5_K_M";
		case 18 -> "Q6_K";
		case 19 -> "IQ2_XXS";
		case 23 -> "IQ3_XXS";
		case 24 -> "IQ1_S";
		case 25 -> "IQ4_NL";
		case 27 -> "IQ3_M";
		case 29 -> "IQ2_M";
		case 30 -> "IQ4_XS";
		case 31 -> "IQ1_M";
		case 32 -> "BF16";
		case 38 -> "MXFP4";
		case 39 -> "NVFP4";
		case 41 -> "Q2_0";
		case 76 -> "MXFP4";
		case 105 -> "ROCmFP4";
		case 141 -> "PQ2_0";
		default -> "UNKNOWN(" + fileType + ")";
		};
	}

	// --- 内部辅助方法和枚举 ---

	private static MtpInfo buildMtpInfo(String architecture, Integer blockCount, Integer nextnPredictLayers) {
		if (architecture == null)
			return MtpInfo.none();
		if (nextnPredictLayers == null || nextnPredictLayers <= 0)
			return MtpInfo.none();
		if (blockCount == null || blockCount < nextnPredictLayers)
			return MtpInfo.none();

		int bc = blockCount;
		int nn = nextnPredictLayers;
		int trunk = bc - nn;

		List<String> prefixes = new ArrayList<>(nn);
		for (int i = 0; i < nn; i++) {
			prefixes.add("blk." + (trunk + i) + ".");
		}
		return new MtpInfo(true, architecture, bc, nn, trunk, prefixes);
	}

	/**
	 * 是否为仅包含 MTP 层的独立 donor/draft 文件（不能作为主模型加载）。
	 * 当模型 block_count 等于 nextn_predict_layers 时，说明没有 trunk 层，全是 MTP 层。
	 */
	public boolean isStandaloneMtpDonor() {
		return mtpInfo != null && mtpInfo.hasMtp() && mtpInfo.trunkCount() == 0;
	}
}
