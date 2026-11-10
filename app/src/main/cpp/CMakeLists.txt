cmake_minimum_required(VERSION 3.22)
project(aharou_llama CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 只编推理库：示例、服务端、测试都不需要，编进来只会拖慢 CI
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

# 静态链接：否则 llama_jni.so 还要带 libllama/libggml* 四个依赖，
# 少拷一个就导致 System.loadLibrary 直接失败
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_SHARED OFF CACHE BOOL "" FORCE)
set(GGML_SHARED OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

# CI 会把源码解到 cpp/llama.cpp；本地没有就按固定 tag 拉一份
if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt)
    add_subdirectory(llama.cpp)
else()
    include(FetchContent)
    FetchContent_Declare(llama
        URL https://codeload.github.com/ggml-org/llama.cpp/zip/refs/tags/b11829
    )
    FetchContent_MakeAvailable(llama)
endif()

add_library(llama_jni SHARED llama_jni.cpp)
target_link_libraries(llama_jni llama android log)
