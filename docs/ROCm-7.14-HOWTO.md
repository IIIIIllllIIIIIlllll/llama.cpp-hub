# ROCm 7.14.0 Runtime Library Path Issue

## Problem

ROCm 7.14.0 installs shared libraries under `/opt/rocm/core-7.14/lib/` instead of the traditional `/opt/rocm/lib/`. Binaries compiled with `-DGGML_HIP=ON` are linked against `libhipblas.so.3`, `librocblas.so.3`, etc., but the dynamic linker does not know where to find them.

Error:

```
./llama-fit-params: error while loading shared libraries: libhipblas.so.3: cannot open shared object file: No such file or directory
```

This is a llama.cpp build system issue (missing RPATH in `ggml/src/ggml-hip/CMakeLists.txt`), not a user error. The temporary workarounds below do NOT require modifying llama.cpp source code.

## Full List of Missing Libraries

All shared libraries under `/opt/rocm/core-7.14/lib/` that the binary may need at runtime:

```
libamdhip64.so.6
libhipblas.so.3
librocblas.so.3
libhsa-runtime64.so.1
libamd_comgr.so.3
libamd_comgr.so.3.0.0
librccl.so.1          (if GGML_HIP_RCCL=ON)
libroctx64.so.4
librsmi.so.1
```

## Workarounds for Java Launcher

### Option A: Use `ldconfig` (Recommended)

Run once during system setup or in the launcher's install script:

```bash
echo /opt/rocm/core-7.14/lib | sudo tee /etc/ld.so.conf.d/rocm.conf
sudo ldconfig
```

This adds the ROCm path to the system library search database. No environment variables needed. Works for all processes including Java subprocesses.

### Option B: Set `LD_LIBRARY_PATH` in Java

In your Java launcher, before executing the llama.cpp subprocess:

```java
// Java
ProcessBuilder pb = new ProcessBuilder("./llama-fit-params", "--model", ...);
Map<String, String> env = pb.environment();
String rocmLib = "/opt/rocm/core-7.14/lib";
String existing = env.get("LD_LIBRARY_PATH");
if (existing != null && !existing.isEmpty()) {
    env.put("LD_LIBRARY_PATH", rocmLib + ":" + existing);
} else {
    env.put("LD_LIBRARY_PATH", rocmLib);
}
Process p = pb.start();
```

### Option C: Use `patchelf` (No system-wide changes)

If you want to hardcode the library path directly into the binary, run once after build:

```bash
patchelf --set-rpath /opt/rocm/core-7.14/lib /path/to/llama-fit-params
patchelf --set-rpath /opt/rocm/core-7.14/lib /path/to/llama-cli
...

```

This embeds the search path into each ELF binary, works regardless of environment. Repeat after each rebuild.

### Option D: Symlink all libraries to `/usr/lib/` (One-time system setup)

```bash
sudo ln -s /opt/rocm/core-7.14/lib/*.so* /usr/lib/
sudo ldconfig
```

### Option E: Create a wrapper script

Create a shell script that your Java launcher calls instead of the binary directly:

```bash
#!/bin/bash
export LD_LIBRARY_PATH=/opt/rocm/core-7.14/lib:$LD_LIBRARY_PATH
exec "$(dirname "$0")/llama-fit-params" "$@"
```

## Detection

Your Java launcher can detect whether this fix is needed by checking:

```java
/**
 * Check if ROCm 7.14 workaround is needed.
 * Returns true if binary will fail due to missing library search path.
 */
static boolean isRocmWorkaroundNeeded(String binaryPath) {
    try {
        // Try to find libhipblas.so.3 in standard paths
        boolean libExists = new File("/opt/rocm/core-7.14/lib/libhipblas.so.3").exists();
        boolean ldConfigHasIt = false;
        
        // Check ldconfig cache
        Process p = Runtime.getRuntime().exec("ldconfig -p | grep libhipblas.so.3");
        ldConfigHasIt = p.getInputStream().read() != -1;
        
        return libExists && !ldConfigHasIt;
    } catch (Exception e) {
        return false;
    }
}
```

Or more simply, just check if ROCm 7.14 path exists:

```java
static boolean isRocm714() {
    return new File("/opt/rocm/core-7.14/lib/libhipblas.so.3").exists();
}
```

## Background

ROCm 7.14.0 introduced a `core-7.14/` subdirectory structure. Previous ROCm versions (6.x, 7.0-7.13) placed libraries directly under `/opt/rocm/lib/` or `/opt/rocm/lib64/`. The llama.cpp build system uses `find_package(hipblas)` which finds the correct library path at compile time but does not embed it into the binary via RPATH.

The proper fix (to be submitted upstream) is adding `BUILD_RPATH` to `ggml/src/ggml-hip/CMakeLists.txt`.
