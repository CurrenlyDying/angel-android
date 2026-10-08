cmake_minimum_required(VERSION 3.22.1)
project(angel_llama LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- llama.cpp (vendored, pinned: see llama.cpp/VENDORED.md) -----------------------------------
# Mirrors llama.cpp's official Android example: shared libs + runtime-selected CPU variants, so one
# APK uses the fastest kernels each phone supports (dotprod/i8mm/SVE...) without SIGILL on older CPUs.
set(BUILD_SHARED_LIBS     ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON    ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI        OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS      OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS    OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL       ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON  CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE        OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           ON  CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI     OFF CACHE BOOL "" FORCE) # would download sources at build time

add_subdirectory(llama.cpp build-llama)

# ---- JNI bridge ---------------------------------------------------------------------------------
add_library(angel_llama SHARED angel_llama.cpp)
target_include_directories(angel_llama PRIVATE
        llama.cpp/include
        llama.cpp/common
        llama.cpp/ggml/include)
target_link_libraries(angel_llama PRIVATE llama llama-common android log)

