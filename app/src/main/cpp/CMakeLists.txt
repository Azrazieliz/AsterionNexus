cmake_minimum_required(VERSION 3.22)
project(asterioncwd C)
add_library(asterioncwd SHARED asterion_cwd.c)

# Prepared from an exact upstream revision by scripts/prepare_nexus_translation.py.
# Normal source-only debug builds remain possible; translation reports engine unavailable.
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/third_party/sentencepiece/CMakeLists.txt")
  enable_language(CXX)
  set(CMAKE_POSITION_INDEPENDENT_CODE ON)
  set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
  set(SPM_BUILD_TEST OFF CACHE BOOL "" FORCE)
  set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
  set(SPM_USE_BUILTIN_PROTOBUF ON CACHE BOOL "" FORCE)
  find_library(NEXUS_ANDROID_LOG log REQUIRED)
  add_subdirectory(third_party/sentencepiece sentencepiece-build EXCLUDE_FROM_ALL)
  # The upstream CLI targets also link the static library; propagate log to all consumers.
  target_link_libraries(sentencepiece-static PUBLIC ${NEXUS_ANDROID_LOG})
  add_library(nexus_sentencepiece SHARED nexus_sentencepiece.cpp)
  target_include_directories(nexus_sentencepiece PRIVATE third_party/sentencepiece/src)
  target_link_libraries(nexus_sentencepiece PRIVATE sentencepiece-static ${NEXUS_ANDROID_LOG})
endif()
