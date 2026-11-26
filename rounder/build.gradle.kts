plugins { id("com.android.library") }
android {
 namespace = "com.openlocal.office"
 compileSdk = 35
 defaultConfig { minSdk = 29 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
 implementation("androidx.media3:media3-common:1.11.1")
 implementation("androidx.media3:media3-datasource:1.11.1")
 implementation("androidx.media3:media3-exoplayer:1.11.1")
 implementation("androidx.media3:media3-ui:1.11.1")
 testImplementation("junit:junit:4.13.2")
}
