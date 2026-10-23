import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    androidLibrary {
        namespace = "dev.boxlet.shared"
        compileSdk = 37
        minSdk = 24

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    val jvmSharedMain = sourceSets.create("jvmSharedMain")
    jvmSharedMain.dependsOn(sourceSets.commonMain.get())
    sourceSets.getByName("androidMain").dependsOn(jvmSharedMain)
    sourceSets.getByName("jvmMain").dependsOn(jvmSharedMain)

    jvmSharedMain.dependencies {
        api(project(":core"))
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.okio)
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
