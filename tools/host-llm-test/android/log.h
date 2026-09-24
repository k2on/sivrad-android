// Stand-in for the NDK header so llm_jni.cpp builds on a desktop host.
#pragma once
#include <cstdarg>
#include <cstdio>
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6
static inline int __android_log_print(int, const char * tag, const char * fmt, ...) {
    va_list ap; va_start(ap, fmt); fprintf(stderr, "[%s] ", tag); vfprintf(stderr, fmt, ap); fprintf(stderr, "\n"); va_end(ap); return 0;
}
