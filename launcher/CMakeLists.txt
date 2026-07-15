cmake_minimum_required(VERSION 3.16)
project(llama-launcher C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(SOURCES launcher.c)

if(WIN32)
    add_executable(llama.cpp-hub WIN32 ${SOURCES})
    target_include_directories(llama.cpp-hub PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/include/win32
    )
    target_link_libraries(llama.cpp-hub PRIVATE user32)
else()
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
