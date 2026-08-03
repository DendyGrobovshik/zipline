cmake_minimum_required(VERSION 3.10)
project(qjs C CXX)
enable_language(CXX)
add_compile_definitions(CONFIG_VERSION="2024-01-12")
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Hermes paths
set(HERMES_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../../zipline/native/hermes")
set(HERMES_INCLUDE "${HERMES_SRC}/API")
set(HERMES_INCLUDE_JSI "${HERMES_SRC}/API/jsi")
set(HERMES_INCLUDE_PUBLIC "${HERMES_SRC}/public")

include_directories(${HERMES_INCLUDE})
include_directories(${HERMES_INCLUDE_JSI})
include_directories(${HERMES_INCLUDE_PUBLIC})
include_directories(${HERMES_SRC}/include)
include_directories(${HERMES_SRC}/external/llvh/include)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/../../zipline/native)

# JsIntrinsics sources
set(JSINTRINSICS_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../zipline/native/common/JsIntrinsics.cpp
)

# Hermes sources - use the prebuilt macOS static library produced by the
# :zipline:buildHermesMacosStatic Gradle task.
set(HERMES_STATIC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../zipline/build/hermes-macos-static")
set(HERMES_LIB "${HERMES_STATIC_DIR}/lib/libhermesvm_a.a")

if(NOT EXISTS ${HERMES_LIB})
    message(FATAL_ERROR "Hermes static library not found at ${HERMES_LIB}. Run './gradlew :zipline:buildHermesMacosStatic' first.")
endif()

# Create the qjs executable
add_executable(qjs
    qjs.cpp
    ${JSINTRINSICS_SOURCES}
)

# Link Hermes and other dependencies
target_link_libraries(qjs
    ${HERMES_LIB}
    ${HERMES_STATIC_DIR}/jsi/libjsi.a
    ${HERMES_STATIC_DIR}/external/boost/boost_1_86_0/libs/context/libboost_context.a
    "-framework Foundation"
    "-framework CoreFoundation"
    -lpthread
    -ldl
)

set_target_properties(qjs PROPERTIES RUNTIME_OUTPUT_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR})