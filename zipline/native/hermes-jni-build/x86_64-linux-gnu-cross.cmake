# Toolchain file for cross-compiling Hermes + glue from macOS to Linux x86_64.
#
# Usage:
#   cmake -S ... -B build -G Ninja \
#         -DCMAKE_TOOLCHAIN_FILE=path/to/x86_64-linux-gnu-cross.cmake
#
# Requirements on the macOS host:
#   * Apple clang (used as the compiler driver; the actual Linux linker and
#     binutils are discovered automatically).
#   * A Linux x86_64 sysroot. We look for one in:
#       1. $LINUX_SYSROOT environment variable
#       2. /opt/homebrew/Cellar/x86_64-unknown-linux-gnu/*/toolchain/x86_64-unknown-linux-gnu/sysroot
#       3. /usr/local/Cellar/x86_64-unknown-linux-gnu/*/toolchain/x86_64-unknown-linux-gnu/sysroot
#     If none of these exist the build will fail with linker errors.

set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR x86_64)

# Find a Linux sysroot automatically so the linker can locate crt*.o and
# libgcc. Prefer the explicit env override, then the brew cellar.
if(DEFINED ENV{LINUX_SYSROOT} AND IS_DIRECTORY "$ENV{LINUX_SYSROOT}")
  set(_linux_sysroot "$ENV{LINUX_SYSROOT}")
else()
  file(GLOB _brew_sysroots
    LIST_DIRECTORIES TRUE
    "/opt/homebrew/Cellar/x86_64-unknown-linux-gnu/*/toolchain/x86_64-unknown-linux-gnu/sysroot"
    "/usr/local/Cellar/x86_64-unknown-linux-gnu/*/toolchain/x86_64-unknown-linux-gnu/sysroot")
  if(_brew_sysroots)
    list(SORT _brew_sysroots)
    list(GET _brew_sysroots 0 _linux_sysroot)
  endif()
endif()

if(_linux_sysroot)
  message(STATUS "Cross-compiling to Linux x86_64 with sysroot: ${_linux_sysroot}")
  set(CMAKE_SYSROOT "${_linux_sysroot}")

  # Use the cross toolchain's binutils (ar, ranlib, ld) so the produced
  # archives are valid ELF (the host macOS ranlib/ar produce Mach-O archives
  # which the Linux linker can't read).
  if(IS_DIRECTORY "/opt/homebrew/bin")
    set(CMAKE_AR "/opt/homebrew/bin/x86_64-linux-gnu-ar" CACHE FILEPATH "")
    set(CMAKE_RANLIB "/opt/homebrew/bin/x86_64-linux-gnu-ranlib" CACHE FILEPATH "")
  endif()

  # The homebrew x86_64-unknown-linux-gnu cellar keeps libstdc++ headers in
  # toolchain/include/c++/<ver> (not under the sysroot). Force the linker
  # and include search paths so try_compile() probes during configuration
  # succeed.
  get_filename_component(_linux_toolchain_dir "${_linux_sysroot}" DIRECTORY)
  set(_linux_cxx_include_dir "${_linux_toolchain_dir}/include/c++/15.2.0")
  if(IS_DIRECTORY "${_linux_cxx_include_dir}")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -isystem ${_linux_cxx_include_dir}")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -isystem ${_linux_cxx_include_dir}")
    # The gcc cellars put bits/c++config.h under
    # include/c++/<ver>/<triple>/bits/, not directly in bits/. The
    # <triple> dir name differs per build so glob for it.
    file(GLOB _linux_cxx_triple_dirs
      LIST_DIRECTORIES TRUE
      "${_linux_cxx_include_dir}/x86_64-*-linux-gnu"
      "${_linux_cxx_include_dir}/*-linux-gnu")
    foreach(_triple_dir ${_linux_cxx_triple_dirs})
      if(IS_DIRECTORY "${_triple_dir}/bits")
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -isystem ${_triple_dir}")
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -isystem ${_triple_dir}")
      endif()
    endforeach()
  endif()

  # Find the gcc toolchain dir (contains lib/gcc/<triple>/<ver> with
  # crtbegin*.o, libgcc.a etc.). --gcc-toolchain lets clang find the
  # correct libgcc without needing to pass raw -L/-I flags.
  set(_linux_gcc_toolchain "/opt/homebrew/Cellar/x86_64-unknown-linux-gnu/15.2.0/toolchain")
  if(IS_DIRECTORY "${_linux_gcc_toolchain}")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} --gcc-toolchain=${_linux_gcc_toolchain}")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} --gcc-toolchain=${_linux_gcc_toolchain}")
  endif()

  set(CMAKE_C_LIBRARY_PATH "${_linux_sysroot}/usr/lib;${_linux_sysroot}/lib")
  set(CMAKE_CXX_LIBRARY_PATH "${_linux_sysroot}/usr/lib;${_linux_sysroot}/lib")

  # Many probe tests run with try_compile which inherits CMAKE_CXX_FLAGS
  # but not CMAKE_SYSROOT automatically. Add --sysroot to both so they
  # find the cross C++ headers and runtime libraries.
  if(NOT CMAKE_C_FLAGS MATCHES "--sysroot")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} --sysroot=${_linux_sysroot}")
  endif()
  if(NOT CMAKE_CXX_FLAGS MATCHES "--sysroot")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} --sysroot=${_linux_sysroot}")
  endif()

  # LLVM's CheckCompilerVersion.cmake uses check_cxx_source_compiles() which
  # prepends CMAKE_REQUIRED_FLAGS to the compile line. Pre-seed it with the
  # sysroot + include path so its probe finds libstdc++ headers.
  set(CMAKE_REQUIRED_FLAGS "${CMAKE_REQUIRED_FLAGS} --sysroot=${_linux_sysroot} -isystem ${_linux_cxx_include_dir}")

  set(CMAKE_C_COMPILER_WORKS 1)
  set(CMAKE_CXX_COMPILER_WORKS 1)
else()
  message(WARNING
    "No Linux sysroot found. Set LINUX_SYSROOT to a directory containing "
    "usr/lib/crt*.o (e.g. /opt/homebrew/Cellar/x86_64-unknown-linux-gnu/<ver>/toolchain/.../sysroot) "
    "or install x86_64-unknown-linux-gnu via Homebrew.")
endif()

# Use clang as the compiler driver; clang cross-compiles to Linux with --target.
set(CMAKE_C_COMPILER clang)
set(CMAKE_CXX_COMPILER clang++)

set(CMAKE_C_COMPILER_TARGET x86_64-linux-gnu)
set(CMAKE_CXX_COMPILER_TARGET x86_64-linux-gnu)
set(CMAKE_ASM_COMPILER_TARGET x86_64-linux-gnu)

set(CMAKE_SYSTEM_VERSION 1)
