plugins {
    alias(libs.plugins.drop.kmp.core)
    alias(libs.plugins.sqldelight)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:protocol"))
            api(project(":core:discovery"))
            // Public signatures use SqlDriver / SqlSchema (SqlDriverFactory) and Flow / CoroutineDispatcher.
            api(libs.sqldelight.runtime)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.sqldelight.coroutines)
        }
        jvmMain.dependencies {
            // compileOnly: the SQLite JDBC jar carries native libraries for every desktop OS (about 14 MB), which must
            // not reach the Android APK (architecture §15). Desktop apps that use JdbcSqliteDriverFactory add
            // libs.sqldelight.sqlite.driver themselves; Android plugs in its own SqlDriverFactory.
            compileOnly(libs.sqldelight.sqlite.driver)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}

sqldelight {
    databases {
        create("DropDatabase") {
            packageName.set("com.constrivo.drop.core.data.db")
            // The schema is the result of the numbered migrations: 0.sqm creates version 1 (DropSchema).
            deriveSchemaFromMigrations.set(true)
        }
    }
}
