# Type fully-qualified name is resolved from JNI code.
-keepnames class app.cash.zipline.MemoryUsage

# Type constructor is resolved from JNI code.
-keepclassmembers class app.cash.zipline.MemoryUsage {
  <init>(...);
}

# Type name and functions resolved from JNI code.
-keep app.cash.zipline.internal.bridge.CallChannel

# Method names (onMessage, onTasksEnqueued) are resolved from JNI code.
-keep interface app.cash.zipline.CdpListener {
  *;
}
-keepclasseswithmembers class * implements app.cash.zipline.CdpListener {
  *;
}
