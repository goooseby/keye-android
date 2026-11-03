# AndroidViewModelFactory discovers these Application constructors by reflection.
# Class names may still be shortened; model JSON uses explicit keys, not reflection.
-keepclassmembers,allowoptimization,allowobfuscation class io.github.goooseby.keye.app.KeyeViewModel {
    public <init>(android.app.Application);
}
-keepclassmembers,allowoptimization,allowobfuscation class io.github.goooseby.keye.update.AppUpdateViewModel {
    public <init>(android.app.Application);
}
