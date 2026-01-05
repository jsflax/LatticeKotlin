cmake_minimum_required(VERSION 3.22.1)

project("lattice_jni")

# Find the Lattice C API library
# The prebuilt library should be in jniLibs/<ABI>/libLatticeCAPI.so
set(LATTICE_LIB_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# Add the prebuilt Lattice library
add_library(LatticeCAPI SHARED IMPORTED)
set_target_properties(LatticeCAPI PROPERTIES
    IMPORTED_LOCATION ${LATTICE_LIB_DIR}/libLatticeCAPI.so
)

# Include directory for lattice.h
include_directories(${CMAKE_SOURCE_DIR}/../../../libs)

# Build the JNI bridge library
add_library(lattice_jni SHARED
    lattice_jni.c
)

# Link against the Lattice C API and Android log
target_link_libraries(lattice_jni
    LatticeCAPI
    log
)
