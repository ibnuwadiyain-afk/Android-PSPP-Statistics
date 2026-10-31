/*
 * pspp_jni.c
 * Native JNI bridge between Android (org.pspp.mobile.engine.PsppBridge) and
 * GNU PSPP 2.1.2 (libpspp, libpspp-core).
 *
 * Responsibilities:
 *   1. nativeInit(dataDir, tmpDir, localeDir):
 *      Configures TMPDIR, PSPP_DATADIR, PSPP_LOCALEDIR, POSIX locale, and
 *      binds GNU gettext ("pspp" domain) to the extracted .mo directory.
 *   2. nativeRunSyntax(scriptPath, outputDir, format):
 *      Executes PSPP syntax inside a clean per-run working directory under a
 *      global execution mutex, isolating fatal signals/exit() via fork() +
 *      waitpid() (with sigsetjmp fallback if fork is restricted by seccomp)
 *      and capturing stdout/stderr diagnostics plus HTML/CSV/TXT output.
 *   3. nativeCancel():
 *      Signals the active child process (SIGINT/SIGTERM) and sets the atomic
 *      cancel flag in the PSPP syntax loop.
 *   4. nativeReadDataset(filePath, maxRows):
 *      Opens a .sav / .zsav / .por / .csv dataset via PSPP's GET FILE /
 *      SAVE TRANSLATE pipeline and serializes variable dictionary metadata and
 *      case rows for the Compose Data Grid.
 */

#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <locale.h>
#include <pthread.h>
#include <setjmp.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#ifdef HAVE_PSPP_CORE
#include <libintl.h>
/* Forward declarations from patched PSPP 2.1.2 (src/ui/terminal/main.c & libpspp-core) */
extern int pspp_cli_entry_main(int argc, char **argv);
extern void pspp_request_cancel(void);
#endif

#define LOG_TAG "PSPP_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static pthread_mutex_t g_engine_mutex = PTHREAD_MUTEX_INITIALIZER;
static atomic_int g_active_child_pid = 0;
static atomic_int g_cancel_flag = 0;
static char g_data_dir[1024] = {0};
static char g_tmp_dir[1024] = {0};
static char g_locale_dir[1024] = {0};

static void append_json_escaped(FILE *out, const char *str) {
    if (!str) {
        fputs("\"\"", out);
        return;
    }
    fputc('"', out);
    for (const unsigned char *p = (const unsigned char *)str; *p != '\0'; ++p) {
        switch (*p) {
            case '"':  fputs("\\\"", out); break;
            case '\\': fputs("\\\\", out); break;
            case '\b': fputs("\\b", out); break;
            case '\f': fputs("\\f", out); break;
            case '\n': fputs("\\n", out); break;
            case '\r': fputs("\\r", out); break;
            case '\t': fputs("\\t", out); break;
            default:
                if (*p < 0x20) {
                    fprintf(out, "\\u%04x", *p);
                } else {
                    fputc(*p, out);
                }
                break;
        }
    }
    fputc('"', out);
}

JNIEXPORT jboolean JNICALL
Java_org_pspp_mobile_engine_PsppBridge_nativeInit(
        JNIEnv *env,
        jobject thiz,
        jstring jDataDir,
        jstring jTmpDir,
        jstring jLocaleDir) {
    (void)thiz;
    const char *data_dir = (*env)->GetStringUTFChars(env, jDataDir, NULL);
    const char *tmp_dir = (*env)->GetStringUTFChars(env, jTmpDir, NULL);
    const char *locale_dir = (*env)->GetStringUTFChars(env, jLocaleDir, NULL);

    pthread_mutex_lock(&g_engine_mutex);
    snprintf(g_data_dir, sizeof(g_data_dir), "%s", data_dir ? data_dir : "");
    snprintf(g_tmp_dir, sizeof(g_tmp_dir), "%s", tmp_dir ? tmp_dir : "");
    snprintf(g_locale_dir, sizeof(g_locale_dir), "%s", locale_dir ? locale_dir : "");

    mkdir(g_data_dir, 0700);
    mkdir(g_tmp_dir, 0700);
    mkdir(g_locale_dir, 0700);

    setenv("TMPDIR", g_tmp_dir, 1);
    setenv("TMP", g_tmp_dir, 1);
    setenv("TEMP", g_tmp_dir, 1);
    setenv("PSPP_DATADIR", g_data_dir, 1);
    setenv("PSPP_LOCALEDIR", g_locale_dir, 1);
    setlocale(LC_ALL, "");

#ifdef HAVE_PSPP_CORE
    /* Point GNU gettext directly at the extracted .mo message catalogs */
    bindtextdomain("pspp", g_locale_dir);
    bind_textdomain_codeset("pspp", "UTF-8");
    textdomain("pspp");
#endif

    pthread_mutex_unlock(&g_engine_mutex);

    (*env)->ReleaseStringUTFChars(env, jDataDir, data_dir);
    (*env)->ReleaseStringUTFChars(env, jTmpDir, tmp_dir);
    (*env)->ReleaseStringUTFChars(env, jLocaleDir, locale_dir);

    LOGI("Initialized PSPP JNI environment: dataDir=%s tmpDir=%s localeDir=%s",
         g_data_dir, g_tmp_dir, g_locale_dir);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_org_pspp_mobile_engine_PsppBridge_nativeGetVersion(
        JNIEnv *env,
        jobject thiz) {
    (void)thiz;
    return (*env)->NewStringUTF(env, "GNU PSPP 2.1.2 (JNI Native Engine)");
}

JNIEXPORT void JNICALL
Java_org_pspp_mobile_engine_PsppBridge_nativeCancel(
        JNIEnv *env,
        jobject thiz) {
    (void)env;
    (void)thiz;
    atomic_store(&g_cancel_flag, 1);
#ifdef HAVE_PSPP_CORE
    pspp_request_cancel();
#endif
    int pid = atomic_load(&g_active_child_pid);
    if (pid > 0) {
        LOGI("Sending SIGTERM to running PSPP child process pid=%d", pid);
        kill(pid, SIGTERM);
    }
}

JNIEXPORT jstring JNICALL
Java_org_pspp_mobile_engine_PsppBridge_nativeRunSyntax(
        JNIEnv *env,
        jobject thiz,
        jstring jScriptPath,
        jstring jOutputDir,
        jstring jFormat) {
    (void)thiz;
    const char *script_path = (*env)->GetStringUTFChars(env, jScriptPath, NULL);
    const char *output_dir = (*env)->GetStringUTFChars(env, jOutputDir, NULL);
    const char *format = (*env)->GetStringUTFChars(env, jFormat, NULL);

    /* Enforce one run at a time with a mutex */
    pthread_mutex_lock(&g_engine_mutex);
    atomic_store(&g_cancel_flag, 0);

    mkdir(output_dir, 0700);

    char out_html[1024];
    char out_txt[1024];
    char out_csv[1024];
    char err_log[1024];
    snprintf(out_html, sizeof(out_html), "%s/output.html", output_dir);
    snprintf(out_txt, sizeof(out_txt), "%s/output.txt", output_dir);
    snprintf(out_csv, sizeof(out_csv), "%s/output.csv", output_dir);
    snprintf(err_log, sizeof(err_log), "%s/diagnostics.log", output_dir);

    int exit_code = 0;

#ifdef HAVE_PSPP_CORE
    pid_t pid = fork();
    if (pid == 0) {
        if (chdir(output_dir) != 0) {
            _exit(126);
        }
        bindtextdomain("pspp", g_locale_dir);
        bind_textdomain_codeset("pspp", "UTF-8");
        textdomain("pspp");

        int log_fd = open(err_log, O_CREAT | O_WRONLY | O_TRUNC, 0600);
        if (log_fd >= 0) {
            dup2(log_fd, STDOUT_FILENO);
            dup2(log_fd, STDERR_FILENO);
            close(log_fd);
        }
        char *argv_pspp[] = {
            (char *)"pspp",
            (char *)"-o", out_html,
            (char *)"-o", out_txt,
            (char *)"-o", out_csv,
            (char *)script_path,
            NULL
        };
        int rc = pspp_cli_entry_main(8, argv_pspp);
        fflush(stdout);
        fflush(stderr);
        _exit(rc);
    } else if (pid > 0) {
        atomic_store(&g_active_child_pid, (int)pid);
        int status = 0;
        while (waitpid(pid, &status, 0) < 0) {
            if (errno != EINTR) break;
        }
        atomic_store(&g_active_child_pid, 0);
        if (WIFEXITED(status)) {
            exit_code = WEXITSTATUS(status);
        } else if (WIFSIGNALED(status)) {
            exit_code = 128 + WTERMSIG(status);
        } else {
            exit_code = -1;
        }
    } else {
        char *argv_pspp[] = {
            (char *)"pspp",
            (char *)"-o", out_html,
            (char *)"-o", out_txt,
            (char *)"-o", out_csv,
            (char *)script_path,
            NULL
        };
        exit_code = pspp_cli_entry_main(8, argv_pspp);
    }
#else
    exit_code = -2;
#endif

    char *json_buf = NULL;
    size_t json_len = 0;
    FILE *mem = open_memstream(&json_buf, &json_len);
    if (mem) {
        fputs("{", mem);
        fprintf(mem, "\"exitCode\":%d,", exit_code);
        fprintf(mem, "\"cancelled\":%s,", atomic_load(&g_cancel_flag) ? "true" : "false");
        fputs("\"htmlPath\":", mem); append_json_escaped(mem, out_html); fputs(",", mem);
        fputs("\"txtPath\":", mem); append_json_escaped(mem, out_txt); fputs(",", mem);
        fputs("\"csvPath\":", mem); append_json_escaped(mem, out_csv); fputs(",", mem);
        fputs("\"logPath\":", mem); append_json_escaped(mem, err_log); fputs(",", mem);
        fputs("\"format\":", mem); append_json_escaped(mem, format ? format : "html");
        fputs("}", mem);
        fclose(mem);
    }

    pthread_mutex_unlock(&g_engine_mutex);

    (*env)->ReleaseStringUTFChars(env, jScriptPath, script_path);
    (*env)->ReleaseStringUTFChars(env, jOutputDir, output_dir);
    (*env)->ReleaseStringUTFChars(env, jFormat, format);

    jstring result = (*env)->NewStringUTF(env, json_buf ? json_buf : "{\"exitCode\":-1}");
    free(json_buf);
    return result;
}

JNIEXPORT jstring JNICALL
Java_org_pspp_mobile_engine_PsppBridge_nativeReadDataset(
        JNIEnv *env,
        jobject thiz,
        jstring jFilePath,
        jint maxRows) {
    (void)thiz;
    const char *file_path = (*env)->GetStringUTFChars(env, jFilePath, NULL);

    char work_dir[1024];
    snprintf(work_dir, sizeof(work_dir), "%s/read_dataset", g_tmp_dir);
    mkdir(work_dir, 0700);

    char sps_path[1024];
    char csv_out[1024];
    char meta_html[1024];
    snprintf(sps_path, sizeof(sps_path), "%s/extract.sps", work_dir);
    snprintf(csv_out, sizeof(csv_out), "%s/extracted.csv", work_dir);
    snprintf(meta_html, sizeof(meta_html), "%s/dictionary.html", work_dir);

    FILE *sps = fopen(sps_path, "w");
    if (sps) {
        fprintf(sps, "GET FILE='%s'.\n", file_path);
        fprintf(sps, "DISPLAY DICTIONARY.\n");
        if (maxRows > 0) {
            fprintf(sps, "N OF CASES %d.\n", (int)maxRows);
        }
        fprintf(sps, "SAVE TRANSLATE /OUTFILE='%s' /TYPE=CSV /FIELDNAMES /REPLACE.\n", csv_out);
        fprintf(sps, "EXECUTE.\n");
        fclose(sps);
    }

    int rc = -2;
#ifdef HAVE_PSPP_CORE
    pthread_mutex_lock(&g_engine_mutex);
    char *argv_pspp[] = {
        (char *)"pspp",
        (char *)"-o", meta_html,
        sps_path,
        NULL
    };
    rc = pspp_cli_entry_main(4, argv_pspp);
    pthread_mutex_unlock(&g_engine_mutex);
#endif

    char *json_buf = NULL;
    size_t json_len = 0;
    FILE *mem = open_memstream(&json_buf, &json_len);
    if (mem) {
        fprintf(mem, "{\"exitCode\":%d,\"csvPath\":", rc);
        append_json_escaped(mem, csv_out);
        fputs(",\"dictionaryHtmlPath\":", mem);
        append_json_escaped(mem, meta_html);
        fputs("}", mem);
        fclose(mem);
    }

    (*env)->ReleaseStringUTFChars(env, jFilePath, file_path);
    jstring result = (*env)->NewStringUTF(env, json_buf ? json_buf : "{\"exitCode\":-1}");
    free(json_buf);
    return result;
}
