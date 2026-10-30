plugins { id("com.android.application") }

val vc = (System.getenv("VERSION_CODE") ?: "1").toInt()

android {
    namespace = "bg.imotipremier.pozdravi"
    compileSdk = 34
    defaultConfig {
        applicationId = "bg.imotipremier.pozdravi"
        minSdk = 26
        targetSdk = 34
        versionCode = vc
        versionName = "1.0.$vc"
    }
    signingConfigs {
        create("release") {
            val ks = file("release.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("KS_PASS")
                keyAlias = "pozdravi"
                keyPassword = System.getenv("KS_PASS")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (file("release.jks").exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
