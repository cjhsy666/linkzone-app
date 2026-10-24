cmake_minimum_required(VERSION 3.22.1)
project(lzpty C)

add_library(lzpty SHARED lzpty.c)

find_library(log-lib log)
target_link_libraries(lzpty ${log-lib})
