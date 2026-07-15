cmake_minimum_required(VERSION 3.16)
project(llama-launcher C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(SOURCES launcher.c)

if(WIN32)
    add_executable(llama-server WIN32 ${SOURCES})
    target_include_directories(llama-server PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/include/win32
    )
    target_link_libraries(llama-server PRIVATE user32)
else()
    add_executable(llama-server ${SOURCES})
    target_include_directories(llama-server PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/include/linux
    )
    target_link_libraries(llama-server PRIVATE dl)
endif()

set_target_properties(llama-server PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_CURRENT_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_DEBUG "${CMAKE_CURRENT_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_RELWITHDEBINFO "${CMAKE_CURRENT_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_MINSIZEREL "${CMAKE_CURRENT_BINARY_DIR}/bin"
)

install(TARGETS llama-server
    RUNTIME DESTINATION "${CMAKE_CURRENT_BINARY_DIR}/bin"
)
