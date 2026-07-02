package org.mark.llamacpp.server.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import javax.imageio.ImageIO;

import org.mark.llamacpp.server.LlamaServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * EasyChat 聊天背景图存储服务。
 * <p>
 * 背景图存储在 {@code cache/easy-chat/backgrounds/} 目录下，缩略图由服务端生成。
 * 目录信息保存在同目录的 {@code backgrounds.json} 中。
 * </p>
 */
public class EasyChatBackgroundService {

	private static final Logger logger = LoggerFactory.getLogger(EasyChatBackgroundService.class);

	private static final long MAX_BACKGROUND_UPLOAD_BYTES = 2L * 1024L * 1024L;
	private static final int MAX_BACKGROUND_ITEMS = 20;
	private static final String[] BACKGROUND_EXTS = new String[] { "png", "jpg", "jpeg", "gif", "webp" };
	private static final String ID_PATTERN = "[A-Za-z0-9_-]+";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static final ReentrantLock lock = new ReentrantLock();
	private static volatile EasyChatBackgroundService instance;

	public static EasyChatBackgroundService getInstance() {
		if (instance == null) {
			synchronized (EasyChatBackgroundService.class) {
				if (instance == null) {
					instance = new EasyChatBackgroundService();
				}
			}
		}
		return instance;
	}

	private EasyChatBackgroundService() {
	}

	private Path getBackgroundDir() throws IOException {
		Path dir = LlamaServer.getCachePath().resolve("easy-chat").resolve("backgrounds").toAbsolutePath().normalize();
		if (!Files.exists(dir)) {
			Files.createDirectories(dir);
		}
		return dir;
	}

	private Path getCatalogFile() throws IOException {
		return getBackgroundDir().resolve("backgrounds.json").toAbsolutePath().normalize();
	}

	public BackgroundCatalog getCatalog() {
		lock.lock();
		try {
			Path file = getCatalogFile();
			if (!Files.isRegularFile(file)) {
				return new BackgroundCatalog();
			}
			String json = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
			if (json == null || json.isBlank()) {
				return new BackgroundCatalog();
			}
			BackgroundCatalog catalog = GSON.fromJson(json, BackgroundCatalog.class);
			if (catalog == null) {
				return new BackgroundCatalog();
			}
			if (catalog.items == null) {
				catalog.items = new ArrayList<>();
			}
			return catalog;
		} catch (Exception e) {
			logger.warn("[EasyChat][Background] 读取背景目录失败", e);
			return new BackgroundCatalog();
		} finally {
			lock.unlock();
		}
	}

	private void saveCatalog(BackgroundCatalog catalog) {
		lock.lock();
		try {
			Path file = getCatalogFile();
			Path temp = file.resolveSibling(file.getFileName().toString() + ".tmp");
			String json = GSON.toJson(catalog);
			Files.writeString(temp, json, java.nio.charset.StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
			Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			logger.warn("[EasyChat][Background] 保存背景目录失败", e);
			throw new RuntimeException("保存背景目录失败", e);
		} finally {
			lock.unlock();
		}
	}

	public BackgroundItem saveBackground(byte[] bytes, String originalFileName, String contentType) {
		if (bytes == null || bytes.length == 0) {
			throw new IllegalArgumentException("文件内容为空");
		}
		if (bytes.length > MAX_BACKGROUND_UPLOAD_BYTES) {
			throw new IllegalArgumentException("背景图片超过最大限制: 2MB");
		}

		String ext = resolveExtension(contentType, originalFileName);
		if (ext == null) {
			throw new IllegalArgumentException("仅支持图片格式: png/jpg/jpeg/gif/webp");
		}

		String id = generateId();
		validateId(id);

		Path dir;
		try {
			dir = getBackgroundDir();
		} catch (IOException e) {
			throw new RuntimeException("创建背景目录失败", e);
		}

		lock.lock();
		try {
			Path target = dir.resolve(id + "." + ext).toAbsolutePath().normalize();
			if (!target.startsWith(dir)) {
				throw new IllegalArgumentException("非法文件名");
			}

			Path temp = target.resolveSibling(target.getFileName().toString() + ".tmp");
			Files.write(temp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
					StandardOpenOption.WRITE);
			Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

			generateThumbnail(bytes, id);

			BackgroundCatalog catalog = getCatalog();
			if (catalog.items == null) {
				catalog.items = new ArrayList<>();
			}
			BackgroundItem item = new BackgroundItem();
			item.id = id;
			item.name = originalFileName == null ? (id + "." + ext) : originalFileName;
			item.createdAt = System.currentTimeMillis();
			catalog.items.add(0, item);

			while (catalog.items.size() > MAX_BACKGROUND_ITEMS) {
				BackgroundItem removed = catalog.items.remove(catalog.items.size() - 1);
				deleteBackgroundFiles(removed.id);
			}

			catalog.activeId = id;
			saveCatalog(catalog);

			logger.info("[EasyChat][Background] 保存背景成功 id={} file={} size={}", id, target, bytes.length);
			return item;
		} catch (IOException e) {
			deleteBackgroundFiles(id);
			throw new RuntimeException("保存背景失败", e);
		} finally {
			lock.unlock();
		}
	}

	public boolean deleteBackground(String id) {
		validateId(id);
		lock.lock();
		try {
			BackgroundCatalog catalog = getCatalog();
			if (catalog.items == null) {
				return false;
			}
			boolean removed = catalog.items.removeIf(item -> id.equals(item.id));
			if (!removed) {
				return false;
			}
			if (id.equals(catalog.activeId)) {
				catalog.activeId = null;
			}
			saveCatalog(catalog);
			deleteBackgroundFiles(id);
			logger.info("[EasyChat][Background] 删除背景成功 id={}", id);
			return true;
		} finally {
			lock.unlock();
		}
	}

	public void clearAll() {
		lock.lock();
		try {
			BackgroundCatalog catalog = getCatalog();
			List<String> ids = new ArrayList<>();
			if (catalog.items != null) {
				for (BackgroundItem item : catalog.items) {
					if (item != null && item.id != null) {
						ids.add(item.id);
					}
				}
			}
			catalog.items = new ArrayList<>();
			catalog.activeId = null;
			saveCatalog(catalog);
			for (String id : ids) {
				deleteBackgroundFiles(id);
			}
			logger.info("[EasyChat][Background] 清空全部背景成功");
		} finally {
			lock.unlock();
		}
	}

	public void setActive(String id) {
		lock.lock();
		try {
			BackgroundCatalog catalog = getCatalog();
			if (id == null) {
				catalog.activeId = null;
				saveCatalog(catalog);
				return;
			}
			validateId(id);
			if (catalog.items == null || catalog.items.stream().noneMatch(item -> id.equals(item.id))) {
				throw new IllegalArgumentException("背景图片不存在");
			}
			catalog.activeId = id;
			saveCatalog(catalog);
		} finally {
			lock.unlock();
		}
	}

	public void setOpacity(int opacity) {
		if (opacity < 0 || opacity > 100) {
			throw new IllegalArgumentException("透明度必须在 0-100 之间");
		}
		lock.lock();
		try {
			BackgroundCatalog catalog = getCatalog();
			catalog.opacity = opacity;
			saveCatalog(catalog);
		} finally {
			lock.unlock();
		}
	}

	public Path findBackgroundFile(String id) {
		validateId(id);
		Path dir;
		try {
			dir = getBackgroundDir();
		} catch (IOException e) {
			logger.warn("[EasyChat][Background] 获取背景目录失败", e);
			return null;
		}
		for (String e : BACKGROUND_EXTS) {
			Path p = dir.resolve(id + "." + e).toAbsolutePath().normalize();
			if (!p.startsWith(dir)) {
				continue;
			}
			if (Files.isRegularFile(p)) {
				return p;
			}
		}
		return null;
	}

	public Path findThumbnailFile(String id) {
		validateId(id);
		Path dir;
		try {
			dir = getBackgroundDir();
		} catch (IOException e) {
			logger.warn("[EasyChat][Background] 获取背景目录失败", e);
			return null;
		}
		Path p = dir.resolve(id + ".thumb.png").toAbsolutePath().normalize();
		if (!p.startsWith(dir)) {
			return null;
		}
		return Files.isRegularFile(p) ? p : null;
	}

	private void generateThumbnail(byte[] bytes, String id) throws IOException {
		BufferedImage original = ImageIO.read(new ByteArrayInputStream(bytes));
		if (original == null) {
			throw new IOException("无法读取图片");
		}
		int thumbWidth = 160;
		int thumbHeight = 100;
		double scale = Math.min((double) thumbWidth / original.getWidth(),
				(double) thumbHeight / original.getHeight());
		int w = Math.max(1, (int) Math.round(original.getWidth() * scale));
		int h = Math.max(1, (int) Math.round(original.getHeight() * scale));
		BufferedImage thumb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = thumb.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, w, h);
		g.drawImage(original, 0, 0, w, h, null);
		g.dispose();

		Path dir = getBackgroundDir();
		Path thumbFile = dir.resolve(id + ".thumb.png").toAbsolutePath().normalize();
		if (!thumbFile.startsWith(dir)) {
			throw new IOException("非法缩略图路径");
		}
		Path temp = thumbFile.resolveSibling(thumbFile.getFileName().toString() + ".tmp");
		ImageIO.write(thumb, "png", temp.toFile());
		Files.move(temp, thumbFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	private void deleteBackgroundFiles(String id) {
		if (id == null) {
			return;
		}
		try {
			Path dir = getBackgroundDir();
			for (String e : BACKGROUND_EXTS) {
				try {
					Path p = dir.resolve(id + "." + e).toAbsolutePath().normalize();
					if (p.startsWith(dir)) {
						Files.deleteIfExists(p);
					}
				} catch (Exception ignore) {
				}
			}
			try {
				Path thumb = dir.resolve(id + ".thumb.png").toAbsolutePath().normalize();
				if (thumb.startsWith(dir)) {
					Files.deleteIfExists(thumb);
				}
			} catch (Exception ignore) {
			}
		} catch (Exception e) {
			logger.warn("[EasyChat][Background] 删除背景文件失败 id={}", id, e);
		}
	}

	private String generateId() {
		return "bg-" + System.currentTimeMillis() + "-" + Long.toHexString((long) (Math.random() * 0x100000000L));
	}

	private static void validateId(String id) {
		if (id == null || id.isEmpty()) {
			throw new IllegalArgumentException("缺少背景ID");
		}
		if (id.length() > 128) {
			throw new IllegalArgumentException("背景ID过长");
		}
		if (!id.matches(ID_PATTERN)) {
			throw new IllegalArgumentException("背景ID包含非法字符");
		}
	}

	private static String resolveExtension(String contentType, String originalFileName) {
		String ext = normalizeExt(extensionFromContentType(contentType));
		if (ext == null) {
			ext = normalizeExt(extractSafeFileExtension(originalFileName));
		}
		return ext;
	}

	private static String extensionFromContentType(String contentType) {
		if (contentType == null) {
			return null;
		}
		String ct = contentType.trim().toLowerCase();
		if (ct.isEmpty() || !ct.startsWith("image/")) {
			return null;
		}
		String subtype = ct.substring("image/".length()).trim();
		int semi = subtype.indexOf(';');
		if (semi >= 0) {
			subtype = subtype.substring(0, semi).trim();
		}
		if (subtype.isEmpty()) {
			return null;
		}
		if ("jpeg".equals(subtype) || "jpg".equals(subtype)) {
			return "jpg";
		}
		if ("png".equals(subtype) || "gif".equals(subtype) || "webp".equals(subtype)) {
			return subtype;
		}
		return null;
	}

	private static String extractSafeFileExtension(String originalFileName) {
		if (originalFileName == null) {
			return null;
		}
		String n = originalFileName.trim();
		if (n.isEmpty()) {
			return null;
		}
		int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
		if (slash >= 0 && slash < n.length() - 1) {
			n = n.substring(slash + 1);
		}
		int dot = n.lastIndexOf('.');
		if (dot <= 0 || dot >= n.length() - 1) {
			return null;
		}
		String ext = n.substring(dot + 1);
		if (ext.length() > 16) {
			return null;
		}
		for (int i = 0; i < ext.length(); i++) {
			char ch = ext.charAt(i);
			boolean ok = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
			if (!ok) {
				return null;
			}
		}
		return ext.toLowerCase();
	}

	private static String normalizeExt(String ext) {
		if (ext == null) {
			return null;
		}
		String e = ext.trim().toLowerCase();
		if (e.isEmpty()) {
			return null;
		}
		for (String allow : BACKGROUND_EXTS) {
			if (allow.equals(e)) {
				return e;
			}
		}
		return null;
	}

	public static String inferImageContentType(Path file) {
		if (file == null) {
			return "application/octet-stream";
		}
		String n = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase();
		if (n.endsWith(".png")) {
			return "image/png";
		}
		if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
			return "image/jpeg";
		}
		if (n.endsWith(".gif")) {
			return "image/gif";
		}
		if (n.endsWith(".webp")) {
			return "image/webp";
		}
		return "application/octet-stream";
	}

	public static class BackgroundCatalog {
		private String activeId;
		private int opacity = 45;
		private List<BackgroundItem> items = new ArrayList<>();

		public String getActiveId() {
			return activeId;
		}

		public void setActiveId(String activeId) {
			this.activeId = activeId;
		}

		public int getOpacity() {
			return opacity;
		}

		public void setOpacity(int opacity) {
			this.opacity = opacity;
		}

		public List<BackgroundItem> getItems() {
			return items;
		}

		public void setItems(List<BackgroundItem> items) {
			this.items = items;
		}
	}

	public static class BackgroundItem {
		private String id;
		private String name;
		private long createdAt;

		public String getId() {
			return id;
		}

		public void setId(String id) {
			this.id = id;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public long getCreatedAt() {
			return createdAt;
		}

		public void setCreatedAt(long createdAt) {
			this.createdAt = createdAt;
		}
	}
}
