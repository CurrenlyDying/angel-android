cmake_minimum_required(VERSION 3.25)
project(angel_llama LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Normalize native paths for comparison across separate build checkouts.
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    # NDK OpenMP embeds absolute source paths when debug info is enabled, even
    # with prefix maps. Native code is always Release; omit that debug info so
    # builds in different checkout directories produce the same libraries.
    add_compile_options(-g0)
    add_compile_options("-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=angel-native"
                        "-ffile-prefix-map=${CMAKE_BINARY_DIR}=angel-build"
                        "-ffile-prefix-map=${CMAKE_ANDROID_NDK}=android-ndk")
endif()

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
set(LLAMA_BUILD_MTMD      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_IS_DEV    OFF CACHE BOOL "" FORCE)
set(LLAMA_LLGUIDANCE      OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS      OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS    OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL       ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON  CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE        OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           ON  CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI     OFF CACHE BOOL "" FORCE) # would download sources at build time
# Upstream b11960/v0.8.0, independent of Angel's enclosing Git checkout.
set(LLAMA_BUILD_NUMBER 11960 CACHE STRING "" FORCE)
set(LLAMA_BUILD_COMMIT "8642541" CACHE STRING "" FORCE)

add_subdirectory(llama.cpp build-llama)

# ggml independently probes the enclosing Git repository. Normalize its generated
# header after configuration without changing the vendored upstream sources.
block(SCOPE_FOR VARIABLES)
    get_directory_property(GGML_VERSION DIRECTORY llama.cpp/ggml DEFINITION GGML_VERSION)
    set(GGML_BUILD_COMMIT "8642541")
    configure_file(llama.cpp/ggml/src/ggml-version.h.in
                   build-llama/ggml/src/ggml-version.h @ONLY)
endblock()

# ---- JNI bridge ---------------------------------------------------------------------------------
add_library(angel_llama SHARED angel_llama.cpp)
target_include_directories(angel_llama PRIVATE
        llama.cpp/include
        llama.cpp/common
        llama.cpp/ggml/include)
target_link_libraries(angel_llama PRIVATE llama llama-common android log)

