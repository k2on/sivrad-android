cmake_minimum_required(VERSION 3.22.1)

project(sivrad_llm LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp is vendored as a git submodule (see .gitmodules) and built here,
# through Gradle's externalNativeBuild, so android.nix's gradle-state layer
# carries the .cxx output between builds.
#
# Only the libraries: no tools, examples, server, tests or `common` (which
# would pull in OpenSSL for model downloads). Nothing here may reach the
# network at configure time, which rules out KleidiAI (FetchContent).
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI    OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE       ON  CACHE BOOL "" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Tensor G3 (Cortex-X3 / A715 / A510) is Armv9.0 and has dotprod, fp16
    # and i8mm, which is what the Q4_K / Q8 matmul kernels want.
    # TODO(on-device): benchmark against `armv8.2-a+dotprod+fp16` and with
    # `+sve`; SVE is present on G3 but ggml's SVE paths are not always faster.
    set(GGML_CPU_ARM_ARCH "armv8.6-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)
endif()

add_subdirectory(llama.cpp build-llama)

add_library(sivrad_llm SHARED llm_jni.cpp)

target_link_libraries(sivrad_llm PRIVATE llama ggml android log)

# 16 KiB page alignment, required for Android 15+ devices that use it.
target_link_options(sivrad_llm PRIVATE "-Wl,-z,max-page-size=16384")
