#ifndef QUICKJS_ANDROID_INTSETBUILTINS_H
#define QUICKJS_ANDROID_INTSETBUILTINS_H

#ifdef __cplusplus
extern "C" {
#endif

// Register JS intrinsics (IntSet/ScatterSet/ScatterMap/etc.) that back the
// kotlinx.collections fast paths in Kotlin/JS. These are called from
// generated Kotlin/JS code via _intsetFind, _scatterSetFind, etc.
//
// The runtime parameter is a void* because jsi::Runtime is not available in
// the Kotlin/Native cinterop (which only exposes plain C types).
void js_register_intrinsics(void* runtime);

#ifdef __cplusplus
} /* extern "C" { */
#endif

#endif //QUICKJS_ANDROID_INTSETBUILTINS_H