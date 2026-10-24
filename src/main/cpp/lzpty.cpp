// 交互式终端所需的原生 pty：Java 层无法分配伪终端，只能靠 forkpty。
// 只做三件事：forkpty 起子进程（execve 到 proot/shell）、改窗口尺寸、回收子进程。
#include <jni.h>
#include <android/log.h>
#include <fcntl.h>
#include <pty.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define TAG "LzPty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// JNI 导出函数必须是 C 链接，否则 C++ name mangling 后 Java 侧找不到符号
extern "C" {

static char **toCStringArray(JNIEnv *env, jobjectArray arr, int *countOut) {
    int count = arr ? (int) env->GetArrayLength(arr) : 0;
    char **out = (char **) calloc((size_t) count + 1, sizeof(char *));
    if (!out) return NULL;
    for (int i = 0; i < count; i++) {
        jstring s = (jstring) env->GetObjectArrayElement(arr, i);
        if (!s) continue;
        const char *utf = env->GetStringUTFChars(s, NULL);
        out[i] = strdup(utf);
        env->ReleaseStringUTFChars(s, utf);
        env->DeleteLocalRef(s);
    }
    *countOut = count;
    return out;
}

static void freeCStringArray(char **arr, int count) {
    if (!arr) return;
    for (int i = 0; i < count; i++) free(arr[i]);
    free(arr);
}

/**
 * 启动一个带 pty 的子进程。
 * 返回值 int[]{masterFd, pid}；失败返回 null。
 *
 * fork 之后子进程只调用 async-signal-safe 的系统调用（chdir/execve/_exit），
 * 因为 Android 应用进程天然是多线程的，fork 后继续用 malloc 会死锁。
 */
JNIEXPORT jintArray JNICALL
Java_com_cjhsy_linkzone_workspace_term_NativePty_nativeStart(
        JNIEnv *env, jclass clazz, jobjectArray argvArr, jstring cwdStr,
        jobjectArray envArr, jint rows, jint cols) {
    int argc = 0, envc = 0;
    char **argv = toCStringArray(env, argvArr, &argc);
    char **envp = toCStringArray(env, envArr, &envc);
    if (!argv || argc == 0) {
        freeCStringArray(argv, argc);
        freeCStringArray(envp, envc);
        return NULL;
    }
    const char *cwd = cwdStr ? env->GetStringUTFChars(cwdStr, NULL) : NULL;
    char *cwdCopy = cwd ? strdup(cwd) : NULL;
    if (cwd) env->ReleaseStringUTFChars(cwdStr, cwd);

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;

    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &ws);
    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        freeCStringArray(argv, argc);
        freeCStringArray(envp, envc);
        free(cwdCopy);
        return NULL;
    }

    if (pid == 0) {
        if (cwdCopy) chdir(cwdCopy);
        execve(argv[0], argv, envp);
        _exit(127);
    }

    jintArray result = env->NewIntArray(2);
    if (result) {
        jint values[2] = {(jint) master, (jint) pid};
        env->SetIntArrayRegion(result, 0, 2, values);
    }
    freeCStringArray(argv, argc);
    freeCStringArray(envp, envc);
    free(cwdCopy);
    return result;
}

/** 窗口尺寸变化时同步给 pty，否则 vim/top 之类会按旧尺寸绘制 */
JNIEXPORT void JNICALL
Java_com_cjhsy_linkzone_workspace_term_NativePty_nativeSetSize(
        JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ioctl(fd, TIOCSWINSZ, &ws);
}

/** 非阻塞回收：返回退出码；仍在运行返回 -1 */
JNIEXPORT jint JNICALL
Java_com_cjhsy_linkzone_workspace_term_NativePty_nativePoll(
        JNIEnv *env, jclass clazz, jint pid) {
    int status = 0;
    pid_t r = waitpid((pid_t) pid, &status, WNOHANG);
    if (r <= 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

/** 给前台进程组发信号（Ctrl+C / Ctrl+D 用信号语义更接近真实终端） */
JNIEXPORT void JNICALL
Java_com_cjhsy_linkzone_workspace_term_NativePty_nativeSignal(
        JNIEnv *env, jclass clazz, jint pid, jint sig) {
    if (pid > 0) kill(-(pid_t) pid, sig);
}

} // extern "C"
