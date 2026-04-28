package org.mark.project.tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 编译 classpath 内容到内存缓存，可打包为 ZIP 字节数组。
 */
public final class OutputHelper {

	private final LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();

	private Class<?> mainClass;
	private String targetPath;

	protected OutputHelper() {

	}

	public OutputHelper(String targetPath, Class<?> mainClass) {
		this.targetPath = targetPath;
		this.mainClass = mainClass;
	}

	public OutputHelper(Class<?> mainClass) {
		this.mainClass = mainClass;
	}

	public static void run(String path, Class<?> mainClass) {
		new OutputHelper(path, mainClass).compile();
	}

	public static void run(Class<?> mainClass) {
		new OutputHelper(mainClass).compile();
	}

	public void compile() {
		String path = this.targetPath;
		if (path == null)
			path = System.getProperty("user.dir") + "/output";
		writeZip(path + "/release.zip");
	}

	/**
	 * 编译到内存缓存并写入 ZIP 文件。
	 */
	public void writeZip(String outputPath) {
		byte[] zip = this.compileToZip();
		File out = new File(outputPath);
		File parent = out.getParentFile();
		if (parent != null && !parent.exists()) parent.mkdirs();
		try (FileOutputStream fos = new FileOutputStream(out)) {
			fos.write(zip);
			System.out.println("ZIP written: " + out.getAbsolutePath() + " (" + zip.length + " bytes)");
		} catch (IOException e) {
			throw new RuntimeException("Failed to write ZIP", e);
		}
	}

	/**
	 * 编译所有内容到内存缓存，返回 ZIP 字节数组。
	 */
	public byte[] compileToZip() {
		this.entries.clear();
		this.populateEntries();
		return this.buildZip();
	}

	public int entryCount() {
		return this.entries.size();
	}

	public java.util.Set<String> entryPaths() {
		return this.entries.keySet();
	}

	private void populateEntries() {
		String[] classPath = System.getProperty("java.class.path").split(
				System.getProperty("os.name").toLowerCase().contains("windows") ? ";" : ":");

		List<String> jarNames = new LinkedList<>();
		for (String cp : classPath) {
			File file = new File(cp);
			if (file.isDirectory()) {
				readDirToEntries(cp, "classes/");
			} else {
				String name = file.getName();
				readFileToEntries(file.getPath(), "libs/" + name);
				jarNames.add(name);
			}
		}

		String mainClassName = this.mainClass.getName();
		addScriptEntry("start.bat", buildWindowsScript(mainClassName, jarNames));
		addScriptEntry("start.sh", buildLinuxScript(mainClassName, jarNames));
	}

	private void readDirToEntries(String srcPath, String prefix) {
		File src = new File(srcPath);
		if (!src.isDirectory()) return;
		for (File f : src.listFiles()) {
			if (f.isFile()) {
				readFileToEntries(f.getPath(), prefix + f.getName());
			} else {
				readDirToEntries(f.getPath(), prefix + f.getName() + "/");
			}
		}
	}

	private void readFileToEntries(String srcPath, String destPath) {
		File src = new File(srcPath);
		if (!src.isFile()) return;
		try (InputStream in = new FileInputStream(src)) {
			ByteArrayOutputStream buf = new ByteArrayOutputStream((int) Math.min(src.length(), Integer.MAX_VALUE));
			byte[] tmp = new byte[8192];
			int len;
			while ((len = in.read(tmp)) != -1) {
				buf.write(tmp, 0, len);
			}
			this.entries.put(normalizeEntryPath(destPath), buf.toByteArray());
		} catch (IOException e) {
			System.err.println("Failed to read: " + srcPath);
			e.printStackTrace();
		}
	}

	private void addScriptEntry(String entryName, String content) {
		this.entries.put(entryName, content.getBytes(StandardCharsets.UTF_8));
	}

	private String buildWindowsScript(String mainClassName, List<String> jarNames) {
		StringBuilder sb = new StringBuilder();
		sb.append("chcp 65001\n");
		sb.append("java -classpath \"");
		sb.append("classes;");
		for (String j : jarNames)
			sb.append("libs\\").append(j).append(";");
		sb.deleteCharAt(sb.length() - 1);
		sb.append("\" ").append(mainClassName);
		return sb.toString();
	}

	private String buildLinuxScript(String mainClassName, List<String> jarNames) {
		StringBuilder sb = new StringBuilder();
		sb.append("#!/bin/bash\n");
		sb.append("java -classpath ");
		sb.append("./classes:");
		for (String j : jarNames)
			sb.append("./libs/").append(j).append(":");
		sb.deleteCharAt(sb.length() - 1);
		sb.append(" ").append(mainClassName).append("\n");
		return sb.toString();
	}

	private byte[] buildZip() {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (ZipOutputStream zos = new ZipOutputStream(baos)) {
			for (Map.Entry<String, byte[]> e : this.entries.entrySet()) {
				ZipEntry ze = new ZipEntry(e.getKey());
				zos.putNextEntry(ze);
				zos.write(e.getValue());
				zos.closeEntry();
			}
		} catch (IOException ex) {
			throw new RuntimeException("Failed to build ZIP", ex);
		}
		return baos.toByteArray();
	}

	private static String normalizeEntryPath(String path) {
		return path.replace('\\', '/');
	}

	public void createStartScript(String libDirPath, String mainClass) {
		LinkedList<String> fileName = new LinkedList<>();
		File file = new File(libDirPath);
		for (File f : file.listFiles())
			fileName.add(f.getName());
		System.out.println("start.bat:");
		System.out.println(buildWindowsScript(mainClass, fileName));
		System.out.println("start.sh:");
		System.out.println(buildLinuxScript(mainClass, fileName));
	}

	public static void main(String[] args) {
		OutputHelper.run("Z:\\Workspace\\LlamaServer\\Release", org.mark.llamacpp.server.LlamaServer.class);
	}
}
