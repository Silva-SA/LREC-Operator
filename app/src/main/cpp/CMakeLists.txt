cmake_minimum_required(VERSION 3.22.1)
project("lrec_engine" LANGUAGES CXX)

add_library(
    lrec_engine
    SHARED
    lrec_engine.cpp
)

find_library(log-lib log)

target_link_libraries(
    lrec_engine
    ${log-lib}
    z
)

target_compile_options(lrec_engine PRIVATE
    -O2
    -std=c++17
    -fexceptions
    -fvisibility=hidden
    -Wall
    -Wno-unused-parameter
)
