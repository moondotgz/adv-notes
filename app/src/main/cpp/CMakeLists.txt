cmake_minimum_required(VERSION 3.22.1)
project(advnotes_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(ADVNOTES_LLAMA "Build the on-device llama.cpp runner" ON)

# ---------------------------------------------------------------------------------------------
# Scientific calculator: the C++ engine of moondotgz/casio (submodule) compiled against a small
# Qt-compatibility layer (casio_shim) instead of Qt.
# ---------------------------------------------------------------------------------------------
file(GLOB CASIO_CORE_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/casio/src/core/*.cpp)
add_library(advnotes_casio SHARED ${CASIO_CORE_SOURCES} casio_bridge/casio_api.cpp casio_bridge/casio_jni.cpp)
target_include_directories(advnotes_casio PRIVATE casio_shim casio/src/core casio_bridge)
target_compile_options(advnotes_casio PRIVATE -O2 -fexceptions -frtti)
target_link_libraries(advnotes_casio PRIVATE log)

# ---------------------------------------------------------------------------------------------
# On-device vision LLM runner: llama.cpp + libmtmd (submodule)
# ---------------------------------------------------------------------------------------------
if(ADVNOTES_LLAMA)
    set(CMAKE_CXX_STANDARD 17)
    set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_COMMON    OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_MTMD      ON  CACHE BOOL "" FORCE)
    set(LLAMA_OPENSSL         OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL       OFF CACHE BOOL "" FORCE)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        # ARMv8.2 dot-product + fp16: every phone/tablet with an S Pen from the last 6+ years.
        set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
    endif()
    add_subdirectory(llama.cpp build-llama)

    add_library(advnotes_llama SHARED llama_jni.cpp)
    target_link_libraries(advnotes_llama PRIVATE mtmd llama ggml android log)
    target_compile_options(advnotes_llama PRIVATE -O3)
endif()
