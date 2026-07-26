plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "com.matchmvp.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.matchmvp.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-pilot"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Для публикации в Google Play сборка ДОЛЖНА быть подписана
            // твоим собственным ключом — см. инструкцию в README.md,
            // раздел "Как опубликовать в Google Play", шаг про подпись.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    // Crashlytics — ловит все крэши приложения и показывает их в
    // Firebase Console (в браузере), без Android Studio и без ADB.
    implementation("com.google.firebase:firebase-crashlytics-ktx")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
}
