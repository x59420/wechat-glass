plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.zaka.wechatglass"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.zaka.wechatglass"
        minSdk = 26
        targetSdk = 34
        versionCode = 22
        versionName = "0.5.14"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile(rootProject.file("app/AndroidManifest.xml"))
            java.srcDirs(rootProject.file("src"))
            // AGP 9 built-in Kotlin has a separate Kotlin source directory set.
            kotlin.directories.add(rootProject.file("src").absolutePath)
            res.srcDirs(rootProject.file("app/res"))
            assets.srcDirs(rootProject.file("app/assets"))
            resources.srcDirs(rootProject.file("app/resources"))
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/wxglass.jks")
            val signingPassword = providers.environmentVariable("WXGLASS_KEYSTORE_PASSWORD").orElse("wxglass2024").get()
            storePassword = signingPassword
            keyAlias = "wxglass"
            keyPassword = signingPassword
        }
    }

    buildTypes {
        release {
            // 剪掉没用到的代码/资源来压体积；类名不混淆（-dontobfuscate），
            // 保证 LSPosed 按 assets/xposed_init 里的全名加载入口类不出问题。
            isMinifyEnabled = true
            isShrinkResources = true
            // 必须显式声明，否则 R8 读不到 keep 规则，会把模块自己的类也裁光。
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                rootProject.file("app/proguard-rules.pro")
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    compileOnly(files(rootProject.file("libs/api-82.jar")))
    // 只在编译期用一下 RecyclerView 的类型，运行期直接用微信进程里那一份，不重复打包。
    compileOnly("androidx.recyclerview:recyclerview:1.4.0")
}
