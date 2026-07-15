#ifdef _WIN32
  #define UNICODE
  #define _UNICODE
  #include <windows.h>
  #include <libloaderapi.h>
  #include <strsafe.h>
  #define PATH_SEP ';'
  #define PATH_SEP_STR ";"
  #define DIR_SEP '\\'
  #define DIR_SEP_STR "\\"
  #define JRE_ROOT "jre"
  #define JVM_DLL_PATH JRE_ROOT DIR_SEP_STR "bin" DIR_SEP_STR "server" DIR_SEP_STR "jvm.dll"
  #define JRE_BIN_DIR JRE_ROOT DIR_SEP_STR "bin"
  #define JVM_LIB "_jvm"
#else
  #define _GNU_SOURCE
  #include <dlfcn.h>
  #include <libgen.h>
  #include <unistd.h>
  #include <sys/stat.h>
  #define PATH_SEP ':'
  #define PATH_SEP_STR ":"
  #define DIR_SEP '/'
  #define DIR_SEP_STR "/"
  #define JRE_ROOT "jre"
  #define JVM_DLL_PATH JRE_ROOT DIR_SEP_STR "lib" DIR_SEP_STR "server" DIR_SEP_STR "libjvm.so"
  #define JRE_BIN_DIR JRE_ROOT DIR_SEP_STR "bin"
  #define JVM_LIB "_jvm"
#endif

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#include "include/jni.h"

#define MAX_OPTIONS 64
#define MAX_LINE_LEN 1024
#define MAX_PATH_LEN 4096
#define CONF_FILE "launcher.conf"
#define ERROR_LOG "launcher.log"

#define MAIN_CLASS "org/mark/llamacpp/server/LlamaServer"

static const char *DEFAULT_JVM_OPTS[] = {
    "-Djava.class.path=./classes" PATH_SEP_STR "./lib/*",
    "-Dfile.encoding=UTF-8",
    "-Xms96m",
    "-Xmx96m",
    "-XX:MaxDirectMemorySize=128m",
    "-XX:+UseStringDeduplication",
    "-Dio.netty.allocator.preferDirect=true",
    "-Dio.netty.allocator.pageSize=4096",
    "-Dio.netty.allocator.maxOrder=9",
    "-Dio.netty.allocator.numDirectArenas=2",
    "-Dio.netty.allocator.numHeapArenas=2",
    "-Dstdout.encoding=UTF-8",
    "-Dstderr.encoding=UTF-8",
    "-XX:+ShowCodeDetailsInExceptionMessages",
    NULL
};

#ifdef _WIN32
static HANDLE error_log_handle = INVALID_HANDLE_VALUE;
#endif

static void log_error(const char *msg) {
#ifdef _WIN32
    DWORD written;
    if (error_log_handle == INVALID_HANDLE_VALUE) {
        WCHAR path[MAX_PATH];
        GetCurrentDirectoryW(MAX_PATH, path);
        WCHAR log_file[MAX_PATH];
        StringCchPrintfW(log_file, MAX_PATH, L"%s\\%S", path, ERROR_LOG);
        error_log_handle = CreateFileW(log_file, FILE_APPEND_DATA,
            FILE_SHARE_READ | FILE_SHARE_WRITE, NULL,
            OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    }
    if (error_log_handle != INVALID_HANDLE_VALUE) {
        char buf[1024];
        int len = (int)strlen(msg);
        memcpy(buf, msg, len);
        buf[len] = '\r';
        buf[len + 1] = '\n';
        WriteFile(error_log_handle, buf, len + 2, &written, NULL);
    }
#else
    fprintf(stderr, "%s\n", msg);
#endif
}

static void fatal_error(const char *msg) {
    log_error(msg);
#ifdef _WIN32
    MessageBoxA(NULL, msg, "llama-server Launcher Error",
        MB_OK | MB_ICONERROR | MB_SETFOREGROUND);
#endif
    exit(1);
}

static void close_error_log(void) {
#ifdef _WIN32
    if (error_log_handle != INVALID_HANDLE_VALUE) {
        CloseHandle(error_log_handle);
        error_log_handle = INVALID_HANDLE_VALUE;
    }
#endif
}

static void get_executable_dir(char *buf, size_t size) {
#ifdef _WIN32
    WCHAR wpath[MAX_PATH];
    GetModuleFileNameW(NULL, wpath, MAX_PATH);
    char path[MAX_PATH];
    WideCharToMultiByte(CP_UTF8, 0, wpath, -1, path, MAX_PATH, NULL, NULL);
    char *last_sep = strrchr(path, '\\');
    if (last_sep) {
        *last_sep = '\0';
    }
    strncpy(buf, path, size - 1);
    buf[size - 1] = '\0';
#else
    ssize_t len = readlink("/proc/self/exe", buf, size - 1);
    if (len > 0) {
        buf[len] = '\0';
        char *dname = dirname(buf);
        strncpy(buf, dname, size - 1);
        buf[size - 1] = '\0';
    } else {
        buf[0] = '\0';
    }
#endif
}

#ifdef _WIN32
static void *load_jvm_library(const char *jvm_path, const char *bin_dir) {
    WCHAR wpath[MAX_PATH];
    WCHAR wbin_dir[MAX_PATH];

    MultiByteToWideChar(CP_UTF8, 0, bin_dir, -1, wbin_dir, MAX_PATH);
    MultiByteToWideChar(CP_UTF8, 0, jvm_path, -1, wpath, MAX_PATH);

    typedef BOOL (WINAPI *AddDllDirectoryFunc)(PCWSTR);
    typedef BOOL (WINAPI *SetDefaultDllDirectoriesFunc)(DWORD);

    HMODULE kernel32 = GetModuleHandleW(L"kernel32.dll");
    AddDllDirectoryFunc pAddDllDirectory =
        (AddDllDirectoryFunc)GetProcAddress(kernel32, "AddDllDirectory");
    SetDefaultDllDirectoriesFunc pSetDefaultDllDirectories =
        (SetDefaultDllDirectoriesFunc)GetProcAddress(kernel32, "SetDefaultDllDirectories");

    if (pSetDefaultDllDirectories) {
        pSetDefaultDllDirectories(0x1000);
    }
    if (pAddDllDirectory) {
        pAddDllDirectory(wbin_dir);
    } else {
        SetDllDirectoryW(wbin_dir);
    }

    return LoadLibraryW(wpath);
}
#else
static void *load_jvm_library(const char *jvm_path, const char *bin_dir) {
    (void)bin_dir;
    return dlopen(jvm_path, RTLD_NOW | RTLD_GLOBAL);
}
#endif

static int read_config(const char *conf_path, const char **options, int *opt_count,
                       char *main_class, size_t main_class_size) {
    FILE *f = fopen(conf_path, "r");
    if (!f) return 0;

    main_class[0] = '\0';

    char line[MAX_LINE_LEN];
    while (fgets(line, sizeof(line), f) && *opt_count < MAX_OPTIONS) {
        int len = (int)strlen(line);
        while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) {
            line[--len] = '\0';
        }
        if (len == 0) continue;

        if (strncmp(line, "##MAINCLASS=", 12) == 0) {
            const char *cls = line + 12;
            size_t cls_len = strlen(cls);
            if (cls_len > 0 && cls_len < main_class_size) {
                memcpy(main_class, cls, cls_len + 1);
                for (char *p = main_class; *p; p++) {
                    if (*p == '.') *p = '/';
                }
            }
            continue;
        }

        if (line[0] == '#') continue;

        if ((strncmp(line, "-classpath=", 11) == 0 || strncmp(line, "-cp=", 4) == 0) && *opt_count < MAX_OPTIONS) {
            const char *value = strchr(line, '=') + 1;
            char classpath_opt[MAX_LINE_LEN];
            snprintf(classpath_opt, sizeof(classpath_opt), "-Djava.class.path=%s", value);
            char *option = strdup(classpath_opt);
            if (option) {
                options[(*opt_count)++] = option;
            }
            continue;
        }

        char *option = strdup(line);
        if (option) {
            options[(*opt_count)++] = option;
        }
    }
    fclose(f);
    return 1;
}

typedef jint (JNICALL *CreateJavaVMFunc)(JavaVM **, void **, void *);

static int launch_jvm(const char **options, int opt_count) {
    char exec_dir[MAX_PATH_LEN];
    char jvm_full[MAX_PATH_LEN];
    char bin_full[MAX_PATH_LEN];
    char conf_full[MAX_PATH_LEN];

    get_executable_dir(exec_dir, sizeof(exec_dir));

    snprintf(jvm_full, sizeof(jvm_full), "%s" DIR_SEP_STR "%s", exec_dir, JVM_DLL_PATH);
    snprintf(bin_full, sizeof(bin_full), "%s" DIR_SEP_STR "%s", exec_dir, JRE_BIN_DIR);
    snprintf(conf_full, sizeof(conf_full), "%s" DIR_SEP_STR "%s", exec_dir, CONF_FILE);

    char msg_buffer[1024];
    snprintf(msg_buffer, sizeof(msg_buffer), "[launcher] working dir: %s", exec_dir);
    log_error(msg_buffer);

#ifdef _WIN32
    SetCurrentDirectoryA(exec_dir);
#else
    chdir(exec_dir);
#endif

    snprintf(msg_buffer, sizeof(msg_buffer), "[launcher] JVM library: %s", jvm_full);
    log_error(msg_buffer);

    void *jvm_lib = load_jvm_library(jvm_full, bin_full);
    if (!jvm_lib) {
#ifdef _WIN32
        DWORD err = GetLastError();
        snprintf(msg_buffer, sizeof(msg_buffer),
            "[launcher] failed to load JVM library: %s (error code: %lu)", jvm_full, err);
#else
        snprintf(msg_buffer, sizeof(msg_buffer),
            "[launcher] failed to load JVM library: %s (%s)", jvm_full, dlerror());
#endif
        fatal_error(msg_buffer);
        return 1;
    }

#ifdef _WIN32
    CreateJavaVMFunc CreateJavaVM = (CreateJavaVMFunc)GetProcAddress(jvm_lib, "JNI_CreateJavaVM");
#else
    CreateJavaVMFunc CreateJavaVM = (CreateJavaVMFunc)dlsym(jvm_lib, "JNI_CreateJavaVM");
#endif

    if (!CreateJavaVM) {
        fatal_error("[launcher] JNI_CreateJavaVM not found in JVM library");
        return 1;
    }

    snprintf(msg_buffer, sizeof(msg_buffer), "[launcher] reading config: %s", conf_full);
    log_error(msg_buffer);

    const char *opts[MAX_OPTIONS + 4];
    int n = 0;
    int from_config = 0;
    char main_class_conf[MAX_LINE_LEN];

    from_config = read_config(conf_full, opts, &n, main_class_conf, sizeof(main_class_conf));
    if (!from_config) {
        snprintf(msg_buffer, sizeof(msg_buffer),
            "[launcher] config not found, using built-in defaults");
        log_error(msg_buffer);
        for (int i = 0; DEFAULT_JVM_OPTS[i] != NULL && n < MAX_OPTIONS; i++) {
            opts[n++] = DEFAULT_JVM_OPTS[i];
        }
    }

    JavaVMOption *vmOptions = malloc(sizeof(JavaVMOption) * n);
    if (!vmOptions) {
        fatal_error("[launcher] out of memory allocating VM options");
        return 1;
    }

    for (int i = 0; i < n; i++) {
        vmOptions[i].optionString = (char *)opts[i];
        vmOptions[i].extraInfo = NULL;
    }

    JavaVMInitArgs vmArgs;
    vmArgs.version = JNI_VERSION_21;
    vmArgs.nOptions = n;
    vmArgs.options = vmOptions;
    vmArgs.ignoreUnrecognized = JNI_TRUE;

    JavaVM *jvm = NULL;
    JNIEnv *env = NULL;

    log_error("[launcher] creating JVM...");
    jint res = CreateJavaVM(&jvm, (void **)&env, &vmArgs);

    free(vmOptions);
    if (from_config) {
        for (int i = 0; i < n; i++) {
            free((char *)opts[i]);
        }
    }

    if (res != JNI_OK) {
        snprintf(msg_buffer, sizeof(msg_buffer),
            "[launcher] JNI_CreateJavaVM failed with code: %d", res);
        fatal_error(msg_buffer);
        return 1;
    }

    log_error("[launcher] JVM started, invoking main class...");

    const char *target_class = main_class_conf[0] ? main_class_conf : MAIN_CLASS;

    jclass mainClass = (*env)->FindClass(env, target_class);
    if (!mainClass || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        snprintf(msg_buffer, sizeof(msg_buffer),
            "[launcher] class not found: %s", target_class);
        fatal_error(msg_buffer);
        (*jvm)->DestroyJavaVM(jvm);
        return 1;
    }

    jmethodID mainMethod = (*env)->GetStaticMethodID(env, mainClass,
        "main", "([Ljava/lang/String;)V");
    if (!mainMethod || (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        fatal_error("[launcher] main() method not found");
        (*jvm)->DestroyJavaVM(jvm);
        return 1;
    }

    (*env)->CallStaticVoidMethod(env, mainClass, mainMethod, NULL);

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
    }

    log_error("[launcher] JVM exited, destroying...");
    (*jvm)->DestroyJavaVM(jvm);
    close_error_log();

    return 0;
}

#ifdef _WIN32
int WINAPI WinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance,
                   LPSTR lpCmdLine, int nCmdShow) {
    (void)hInstance;
    (void)hPrevInstance;
    (void)lpCmdLine;
    (void)nCmdShow;
    return launch_jvm(NULL, 0);
}
#else
int main(int argc, char *argv[]) {
    (void)argc;
    (void)argv;
    return launch_jvm(NULL, 0);
}
#endif
