# Keep @Model classes and both no-arg + synthesized handle-hydration constructors
-keep @com.lattice.Model class * {
    public <init>();
    public <init>(long, com.lattice.LatticeHydrationMarker);
}
-keep class * implements com.lattice.LatticeObject {
    public <init>();
    public <init>(long, com.lattice.LatticeHydrationMarker);
}
-keep class com.lattice.LatticeHydrationMarker { *; }

# Keep JNI-invoked WebSocket & callback bridge methods (called from lattice_jni.c via GetMethodID)
-keep class com.lattice.NativeBridge { *; }
-keep interface com.lattice.LatticeWebSocket { *; }
-keep interface com.lattice.LatticeWebSocketFactory { *; }
-keep class com.lattice.JniWebSocketWrapper { *; }
-keep class com.lattice.JniWebSocketFactory { *; }
