cmake_minimum_required(VERSION 3.22.1)
project(lzpty CXX)

add_library(lzpty SHARED lzpty.cpp)

find_library(log-lib log)
target_link_libraries(lzpty ${log-lib})
