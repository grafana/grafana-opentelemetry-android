cmake_minimum_required(VERSION 3.22.1)
project(session_probe_crash LANGUAGES CXX)

add_library(session_probe_crash SHARED crash.cpp)
target_compile_options(session_probe_crash PRIVATE -Wall -Wextra -Werror)
