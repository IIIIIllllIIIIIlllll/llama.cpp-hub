# llama-server launcher

基于 JNI Invocation API 的 Java 应用启动器，内嵌 JRE 即可运行。

## 编译

需要 CMake 3.16+ 和 Visual Studio Build Tools。

```powershell
cmake -S launcher -B launcher\build
cmake --build launcher\build --config Release
```

产物：`launcher\build\bin\llama.cpp-hub.exe`

## 配置

将 `launcher.conf` 放在 exe 同目录下，按行填写 JVM 参数：

```
##MAINCLASS=org.mark.llamacpp.server.LlamaServer
-Djava.class.path=classes;libs\gson-2.8.9.jar;libs\netty-all-4.1.35.Final.jar
-Xms96m
-Xmx96m
-XX:+UseSerialGC
-XX:TieredStopAtLevel=1
-XX:ReservedCodeCacheSize=48m
-XX:MaxDirectMemorySize=128m
```

- `##MAINCLASS=`（12 字符前缀）指定入口类，写全限定名用 `.` 分隔
- classpath 用 `-Djava.class.path=` 指定
- 以 `#` 开头且不是 `##MAINCLASS=` 前缀的行视为注释
