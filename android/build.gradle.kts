plugins { id("com.android.library") version "9.2.0" }
android {
    namespace = "dev.pam.backgroundtransfer"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    compileOnly(files(providers.gradleProperty("pamPluginApi").get()))
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.work:work-runtime:2.11.2")
}
