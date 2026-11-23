# Speech recognition (automatic captions, text-based editing): whisper.cpp + ggml on the phone's ARM cores.
# (The Adreno GPU via ggml's OpenCL backend was tried: on a Snapdragon 8 Gen 3 it was ~90× slower than the CPU – whisper
# decodes token by token, too little work per GPU dispatch – and on an 8 Elite Gen 5 it crashed. CPU only.)
cmake_minimum_required(VERSION 3.22)
project(klipp_whisper C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

set(WHISPER_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)
# source paths relative to the repository in the library (asserts print __FILE__), not the build machine's folders
get_filename_component(KLIPP_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../.. ABSOLUTE)
add_compile_options(-ffile-prefix-map=${KLIPP_ROOT}/=)

# ggml linked statically into one library. ARMv8.2 with dotprod (int8 matrices, quantised models) and fp16 – every
# Snapdragon 8 since the 855. No OpenMP (ggml's own thread pool is enough and needs no extra library).
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
add_subdirectory(${WHISPER_ROOT}/ggml ggml)

add_library(klipp_whisper SHARED
    ${WHISPER_ROOT}/src/whisper.cpp
    whisper_jni.cpp
)
target_include_directories(klipp_whisper PRIVATE ${WHISPER_ROOT}/include ${WHISPER_ROOT}/src ${WHISPER_ROOT}/ggml/include)
target_compile_definitions(klipp_whisper PRIVATE WHISPER_VERSION="1.9.4")
target_compile_options(klipp_whisper PRIVATE -O3 -march=armv8.2-a+dotprod+fp16 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(klipp_whisper PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
find_library(LOG_LIB log)
target_link_libraries(klipp_whisper ggml ${LOG_LIB} android)

# Klipp Lab: MotionCam .mcraw reading (mcraw_jni.cpp) with MotionCam's own decoder library, motioncam-decoder
# (Apache-2.0, https://github.com/mirsadm/motioncam-decoder), fetched at a pinned commit. Only its sources are built
# here (its own CMakeLists adds an example, tests and -Werror): SOURCE_SUBDIR points at a folder without one.
include(FetchContent)
FetchContent_Declare(motioncam_decoder
    GIT_REPOSITORY https://github.com/mirsadm/motioncam-decoder.git
    GIT_TAG b931ec0e2466c8217c49f13df925531b78f76e33
    SOURCE_SUBDIR no-cmake-here)
FetchContent_MakeAvailable(motioncam_decoder)
set(MCD ${motioncam_decoder_SOURCE_DIR})
add_library(klipp_mcraw SHARED
    ${MCD}/lib/Decoder.cpp
    ${MCD}/lib/RawData.cpp
    ${MCD}/lib/RawData_Legacy.cpp
    mcraw_jni.cpp
)
target_include_directories(klipp_mcraw PRIVATE ${MCD}/lib/include ${MCD}/thirdparty ${MCD}/motioncam_decoder)
target_compile_options(klipp_mcraw PRIVATE -O3 -fexceptions -frtti -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(klipp_mcraw PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
target_link_libraries(klipp_mcraw ${LOG_LIB})

set(CRAW_ROOT ${KLIPP_ROOT}/third_party/craw)
add_library(klipp_craw SHARED
    craw_jni.cpp
    craw17u_jni.cpp
    ${CRAW_ROOT}/src/Decoder.cpp
    ${CRAW_ROOT}/src/Reader.cpp
    ${CRAW_ROOT}/tools/common/Clip.cpp
)
target_compile_features(klipp_craw PRIVATE cxx_std_20)
target_include_directories(klipp_craw PRIVATE ${CRAW_ROOT}/include ${CRAW_ROOT}/tools)
target_compile_options(klipp_craw PRIVATE -O3 -fvisibility=hidden)
target_link_options(klipp_craw PRIVATE -Wl,-z,max-page-size=16384)

# Klipp Lab: DNG frames (a folder of DNGs as a RAW clip: PhotonCamera's raw video, CinemaDNG) – the app's own reader
add_library(klipp_dng SHARED dng17u_jni.cpp)
target_compile_features(klipp_dng PRIVATE cxx_std_20)
target_compile_options(klipp_dng PRIVATE -O3 -fexceptions -fvisibility=hidden)
target_link_options(klipp_dng PRIVATE -Wl,-z,max-page-size=16384)
