cmake_minimum_required(VERSION 3.16)
project(llama-launcher C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

if(DEFINED APP_VERSION AND APP_VERSION MATCHES "^([0-9]+)\\.([0-9]+)\\.([0-9]+)")
    set(VER_MAJOR ${CMAKE_MATCH_1})
    set(VER_MINOR ${CMAKE_MATCH_2})
    set(VER_PATCH ${CMAKE_MATCH_3})
else()
    set(VER_MAJOR 0)
    set(VER_MINOR 0)
    set(VER_PATCH 0)
endif()

set(SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR})

if(WIN32)
    configure_file(
        ${CMAKE_CURRENT_SOURCE_DIR}/resources.rc.in
        ${CMAKE_CURRENT_BINARY_DIR}/resources.rc
        @ONLY
    )
    set(SOURCES launcher.c ${CMAKE_CURRENT_BINARY_DIR}/resources.rc)

    add_executable(llama.cpp-hub WIN32 ${SOURCES})
    target_include_directories(llama.cpp-hub PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/include/win32
    )
    target_link_libraries(llama.cpp-hub PRIVATE user32 advapi32 shell32)
    if(MSVC)
        target_link_options(llama.cpp-hub PRIVATE "/MANIFEST:NO")
    endif()
else()
    set(SOURCES launcher.c)

    add_executable(llama.cpp-hub ${SOURCES})
    target_include_directories(llama.cpp-hub PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/include/linux
    )
    target_link_libraries(llama.cpp-hub PRIVATE dl)
endif()

set_target_properties(llama.cpp-hub PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_CURRENT_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_DEBUG "${CMAKE_CURRENT_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_RELWITHDEBINFO "${CMAKE_CURRENT_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_MINSIZEREL "${CMAKE_CURRENT_BINARY_DIR}/bin"
)

install(TARGETS llama.cpp-hub
    RUNTIME DESTINATION "${CMAKE_CURRENT_BINARY_DIR}/bin"
)
