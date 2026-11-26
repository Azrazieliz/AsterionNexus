plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace = "com.ailm.android"
 compileSdk = 36
 defaultConfig { minSdk = 30 }
 buildFeatures { compose = true; buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 androidResources { noCompress += listOf("zip") }
}
dependencies {
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.core:core-splashscreen:1.0.1")
 implementation("androidx.activity:activity-compose:1.10.0")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
 implementation("androidx.navigation:navigation-compose:2.8.5")
 implementation("androidx.work:work-runtime-ktx:2.10.0")
 implementation("androidx.datastore:datastore-preferences:1.2.1")
 implementation("androidx.graphics:graphics-path:1.1.0")
 implementation("androidx.paging:paging-runtime-ktx:3.3.6")
 implementation("androidx.room:room-runtime:2.6.1")
 implementation("androidx.room:room-ktx:2.6.1")
 implementation("androidx.documentfile:documentfile:1.0.1")
 implementation("io.coil-kt:coil-compose:2.7.0")
 implementation("io.coil-kt:coil-gif:2.7.0")
 implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
 implementation("org.tensorflow:tensorflow-lite:2.16.1")
 implementation("androidx.compose.ui:ui:1.7.6")
 implementation("androidx.compose.material3:material3:1.3.1")
 implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
}
