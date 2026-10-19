cmake_minimum_required(VERSION 3.22.1)
project(tishe LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(SOLOUD ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/soloud)
file(GLOB CORE CONFIGURE_DEPENDS ${SOLOUD}/src/core/*.cpp)
file(GLOB WAV CONFIGURE_DEPENDS ${SOLOUD}/src/audiosource/wav/*.cpp ${SOLOUD}/src/audiosource/wav/*.c)
add_library(tishe SHARED audio_engine.cpp ${CORE} ${WAV}
    ${SOLOUD}/src/filter/soloud_freeverbfilter.cpp
    ${SOLOUD}/src/filter/soloud_bassboostfilter.cpp
    ${SOLOUD}/src/filter/soloud_fftfilter.cpp
    ${SOLOUD}/src/filter/soloud_biquadresonantfilter.cpp
    ${SOLOUD}/src/filter/soloud_echofilter.cpp
    ${SOLOUD}/src/filter/soloud_lofifilter.cpp
    ${SOLOUD}/src/filter/soloud_flangerfilter.cpp
    ${SOLOUD}/src/backend/miniaudio/soloud_miniaudio.cpp
    ${SOLOUD}/src/backend/null/soloud_null.cpp)
target_include_directories(tishe PRIVATE ${SOLOUD}/include)
target_compile_definitions(tishe PRIVATE WITH_MINIAUDIO WITH_NULL)
target_compile_options(tishe PRIVATE -fexceptions -frtti)
target_link_options(tishe PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(tishe PRIVATE log android dl)
