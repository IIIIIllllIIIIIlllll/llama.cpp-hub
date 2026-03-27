package org.mark.project.tools;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.mark.llamacpp.server.LlamaServer;

/**
 * 	
 * @author: Mark·Liu
 * @date: 2024年3月12日 下午4:00:40
 */
public final class OutputHelper {
	
	
	public static void main(String[] args) {
		OutputHelper.run("Z:\\Workspace\\LlamaServer\\Release", LlamaServer.class);
	}
	
	
	
	protected OutputHelper() {
		
	}
	
	
	/**
	 * 	
	 * 
	 * @author: Mark·Liu
	 * @date: 2024年3月12日
	 * @param mainClass
	 */
	public static void run(Class<?> mainClass) {
		new OutputHelper(mainClass).compile();
	}
	
	/**
	 * 	
	 * 
	 * @author: Mark·Liu
	 * @date: 2024年3月12日
	 * @param path
	 * @param mainClass
	 */
	public static void run(String path, Class<?> mainClass) {
		new OutputHelper(path, mainClass).compile();
	}
	
	/**
	 * 	
	 */
	private String targetPath;
	
	/**
	 * 	
	 */
	private Class<?> mainClass;
	
	/**
	 * 	
	 * @param targetPath
	 * @param mainClass
	 */
	public OutputHelper(String targetPath, Class<?> mainClass) {
		this.targetPath = targetPath;
		this.mainClass = mainClass;
	}
	
	/**
	 * 	
	 * @param mainClass
	 */
	public OutputHelper(Class<?> mainClass) {
		this.mainClass = mainClass;
	}
	
	
	public void compile() {
		
		Class<?> mainClass = this.mainClass;
		String targetPath = this.targetPath;
		
		if(targetPath == null)
			targetPath = System.getProperty("user.dir") + File.separator + "output";
		
		String classes = targetPath + File.separator + "classes";
		String lib = targetPath + File.separator + "libs";
		
		// 获取当前运行系统的路径分隔符，用于解析classpath
		String osName = System.getProperty("os.name").toLowerCase();
		boolean isWindows = osName.contains("windows");
		String pathSeparator = isWindows ? ";" : ":";
		
		String[] classPath = System.getProperty("java.class.path").split(pathSeparator);

		List<String> fileName = new LinkedList<>();
		for (String cp : classPath) {
			File file = new File(cp);
			if(file.isDirectory()) {
				dirCopy(cp, classes);
			}else {
				fileCopy(file.getPath(), lib + File.separator + file.getName());
				fileName.add(file.getName());
			}
		}
		
		// 同时生成Windows和Linux两个平台的启动脚本
		generateWindowsScript(targetPath, mainClass.getName(), fileName);
		generateLinuxScript(targetPath, mainClass.getName(), fileName);
		zipClassesDirectory(classes);
	}
	
	/**
	 * 生成Windows启动脚本
	 */
	private void generateWindowsScript(String targetPath, String mainClassName, List<String> fileName) {
		// Windows平台路径设置
		String classesPath = "classes";
		String libPath = "libs";
		
		StringBuilder sb = new StringBuilder();
		// 头
		sb.append("chcp 65001\n");
		sb.append("java -classpath ").append("\"");
		// 拼接classpath
		sb.append(classesPath).append(";");
		for(String jarFile : fileName)
			sb.append(libPath).append("\\").append(jarFile).append(";");
		// 去掉最后一个
		sb.deleteCharAt(sb.length() - 1);
		// 拼接尾部
		sb.append("\" ").append(mainClassName);
		// 写入文件
		File file = new File(targetPath + File.separator + "start.bat");
		try {
			if(!file.exists())
				file.createNewFile();
			BufferedWriter out = new BufferedWriter(new FileWriter(file));
			out.write(sb.toString());
			out.close();
			System.out.println("Windows startup script generated: " + file.getAbsolutePath());
		}catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * 生成Linux启动脚本
	 */
	private void generateLinuxScript(String targetPath, String mainClassName, List<String> fileName) {
		// Linux平台路径设置
		String classesPath = "./classes";
		String libPath = "./libs";
		
		StringBuilder sb = new StringBuilder();
		// 头
		sb.append("#!/bin/bash\n");
		sb.append("java -classpath ");
		// 拼接classpath
		sb.append(classesPath).append(":");
		for(String jarFile : fileName)
			sb.append(libPath).append("/").append(jarFile).append(":");
		// 去掉最后一个
		sb.deleteCharAt(sb.length() - 1);
		// 拼接尾部
		sb.append(" ").append(mainClassName).append("\n");
		// 写入文件
		File file = new File(targetPath + File.separator + "start.sh");
		try {
			if(!file.exists())
				file.createNewFile();
			BufferedWriter out = new BufferedWriter(new FileWriter(file));
			out.write(sb.toString());
			out.close();
			System.out.println("Linux startup script generated: " + file.getAbsolutePath());
			
			// 设置脚本为可执行
			file.setExecutable(true);
		}catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void createStartScript(String libDirPath, String mainClass) {
		LinkedList<String> fileName = new LinkedList<>();
		File file = new File(libDirPath);
		
		for(File f : file.listFiles())
			fileName.add(f.getName());
		
		// 同时生成Windows和Linux两个平台的启动脚本
		createWindowsStartScript(libDirPath, mainClass, fileName);
		createLinuxStartScript(libDirPath, mainClass, fileName);
	}
	
	/**
	 * 创建Windows启动脚本
	 */
	private void createWindowsStartScript(String libDirPath, String mainClass, LinkedList<String> fileName) {
		// 生成一个启动脚本
		StringBuilder sb = new StringBuilder();
		// 头
		sb.append("chcp 65001\n");
		sb.append("java -classpath ").append("\"");
		// 拼接classpath
		sb.append("classes").append(";");
		for(String jarFile : fileName)
			sb.append("libs\\").append(jarFile).append(";");
		// 去掉最后一个
		sb.deleteCharAt(sb.length() - 1);
		// 拼接尾部
		sb.append("\" ").append(mainClass);
		
		// 写入文件
		File scriptFile = new File(libDirPath + File.separator + ".." + File.separator + "start.bat");
		try {
			if(!scriptFile.exists())
				scriptFile.createNewFile();
			BufferedWriter out = new BufferedWriter(new FileWriter(scriptFile));
			out.write(sb.toString());
			out.close();
			System.out.println("Windows startup script created: " + scriptFile.getAbsolutePath());
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * 创建Linux启动脚本
	 */
	private void createLinuxStartScript(String libDirPath, String mainClass, LinkedList<String> fileName) {
		// 生成一个启动脚本
		StringBuilder sb = new StringBuilder();
		// 头
		sb.append("#!/bin/bash\n");
		sb.append("java -classpath ");
		// 拼接classpath
		sb.append("./classes").append(":");
		for(String jarFile : fileName)
			sb.append("./libs/").append(jarFile).append(":");
		// 去掉最后一个
		sb.deleteCharAt(sb.length() - 1);
		// 拼接尾部
		sb.append(" ").append(mainClass).append("\n");
		
		// 写入文件
		File scriptFile = new File(libDirPath + File.separator + ".." + File.separator + "start.sh");
		try {
			if(!scriptFile.exists())
				scriptFile.createNewFile();
			BufferedWriter out = new BufferedWriter(new FileWriter(scriptFile));
			out.write(sb.toString());
			out.close();
			System.out.println("Linux startup script created: " + scriptFile.getAbsolutePath());
			
			// 设置脚本为可执行
			scriptFile.setExecutable(true);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * 	拷贝文件夹。
	 * @param srcPath
	 * @param destPath
	 */
	public void dirCopy(String srcPath, String destPath) {
		File src = new File(srcPath);
		if (!new File(destPath).exists()) {
			new File(destPath).mkdirs();
		}
		for (File s : src.listFiles()) {
			if (s.isFile()) {
				fileCopy(s.getPath(), destPath + File.separator + s.getName());
			} else {
				dirCopy(s.getPath(), destPath + File.separator + s.getName());
			}
		}
	}

	/**
	 * 	拷贝文件。
	 * @param srcPath
	 * @param destPath
	 */
	public void fileCopy(String srcPath, String destPath) {
		File src = new File(srcPath);
		File dest = new File(destPath);
		//	判断父目录
		File parent = dest.getParentFile();
		if(!parent.exists())
			parent.mkdirs();
		//	使用jdk1.7 try-with-resource 释放资源，并添加了缓存流
		try (InputStream is = new BufferedInputStream(new FileInputStream(src));
				OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
			byte[] flush = new byte[1024];
			int len = -1;
			while ((len = is.read(flush)) != -1) {
				out.write(flush, 0, len);
			}
			out.flush();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	
	public List<File> scanDir(String fileDir){
		List<File> result = new LinkedList<>();
		File file = new File(fileDir);
		scanDir_0(file, result);
		return result;
	}
	
	public void scanDir_0(File fileDir, List<File> list){
		if(fileDir.isDirectory()) {
			for(File f : fileDir.listFiles()) {
				if(f.isDirectory())
					scanDir_0(f, list);
				else
					list.add(f);
			}
		}
	}

	public void zipClassesDirectory(String classesDirPath) {
		File classesDir = new File(classesDirPath);
		if(!classesDir.exists() || !classesDir.isDirectory())
			return;
		File zipFile = new File(classesDir.getParentFile(), classesDir.getName() + ".zip");
		try (ZipOutputStream zipOut = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))) {
			zipDirectory(classesDir, classesDir, zipOut);
			zipOut.flush();
			System.out.println("Classes zip generated: " + zipFile.getAbsolutePath());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void zipDirectory(File rootDir, File currentDir, ZipOutputStream zipOut) throws IOException {
		File[] files = currentDir.listFiles();
		if(files == null || files.length == 0) {
			if(!rootDir.equals(currentDir)) {
				String entryName = rootDir.toURI().relativize(currentDir.toURI()).getPath();
				ZipEntry zipEntry = new ZipEntry(entryName);
				zipOut.putNextEntry(zipEntry);
				zipOut.closeEntry();
			}
			return;
		}
		for(File file : files) {
			if(file.isDirectory()) {
				zipDirectory(rootDir, file, zipOut);
			}else {
				String entryName = rootDir.toURI().relativize(file.toURI()).getPath();
				ZipEntry zipEntry = new ZipEntry(entryName);
				zipOut.putNextEntry(zipEntry);
				try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
					byte[] buffer = new byte[1024];
					int len;
					while ((len = in.read(buffer)) != -1) {
						zipOut.write(buffer, 0, len);
					}
				}
				zipOut.closeEntry();
			}
		}
	}
}
